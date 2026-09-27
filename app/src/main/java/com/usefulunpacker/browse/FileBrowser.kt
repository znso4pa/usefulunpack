package com.usefulunpacker

import android.app.AlertDialog
import android.app.PendingIntent
import android.app.ProgressDialog
import android.content.ClipData
import android.content.Intent
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileInputStream
import kotlin.concurrent.thread

/** Shares a file with another app via ACTION_SEND + FileProvider (no network
 *  permission needed — the receiving app handles any transfer). Delivers the
 *  EXACT file name via DISPLAY_NAME (FileProvider), ClipData label and
 *  EXTRA_TITLE, so receivers keep `123.zip` instead of re-deriving a name. */
internal fun MainActivity.shareFile(f: File) {
    if (f.isDirectory) { toast(getString(R.string.msg_share_folder)); return }
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeOf(f)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, f.name)
        // ClipData label = exact name + proper read grant on every Android.
        clipData = ClipData.newUri(contentResolver, f.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
}

/** Navigates the active tab. Kept as the entry point used by legacy code. */
internal fun MainActivity.nav(dir: File) = navTab(activeTab, dir)

internal fun MainActivity.navTab(tab: TabState, dir: File) {
        // If this tab's fragment hasn't bound its views yet (e.g. right after
        // startup addTab), just record the directory — the fragment re-renders
        // on creation via viewsBound.
        val prevDir = tab.currentDir
        tab.currentDir = dir
        if (!tab.viewsBound) return
        // Leave an in-tab preview if we're navigating this tab elsewhere.
        if (tab.previewActive) {
            tab.previewActive = false
            tab.previewOpenKey?.let { OpenArchiveRegistry.unregister(it) }
            tab.previewOpenKey = null
            tab.previewEntries = emptyList()
            tab.previewSelected.clear()
            tab.previewExpanded.clear()
        }
        tab.previewRoot.visibility = View.GONE
        // Clear any leftover multi-select when navigating to a DIFFERENT
        // directory — otherwise the batch bar persists and the selection mixes
        // files from old dirs (batch delete could target files the user can no
        // longer see). Same-dir calls must NOT clear: onCreateView re-renders
        // (rebuildPager from addTab/openWorkspaceTab/closeTab recreates every
        // fragment) and FileObserver debounce refreshes both call navTab with
        // the SAME dir — clearing there would wipe cross-tab selections on
        // every tab open/close and every auto-refresh.
        if (tab.multiSelectMode && prevDir != dir) exitMultiSelect(tab)
        tab.selectedFile = null
        tab.bottomBar.visibility = View.GONE
        tab.fabExtract.visibility = View.GONE
        tab.btnExtract.text = getString(R.string.msg_extract_title)
        tab.btnExtract.setOnClickListener { extract(tab) }
        // btnFolderNext lives on this tab's bottom bar; only touch it when
        // this is the active tab (its view is attached).
        if (activeTab === tab) tab.btnFolderNext?.visibility = View.GONE
        saveSession()
        restartDirObserverFor(tab)
        tab.tvPath.text = dir.absolutePath
        val isCompress = prefs.getInt("work_mode", 0) == 1
        updateTitle()
        tab.tvCount.text = "…"
        tab.tvEmpty.visibility = View.GONE
        tab.listFiles.adapter = FileAdapter(this, emptyList(), bookmarks, { path -> if (bookmarks.contains(path)) bookmarks.remove(path) else bookmarks.add(0, path); saveBookmarks() }, df)

        thread {
            val raw = dir.listFiles()?.filter { it.name != ".recycle" }
            val sortMode = prefs.getString("sort_mode", "name_asc") ?: "name_asc"
            val files: List<File> = when {
                raw != null -> raw.sortedWith(when (sortMode) {
                    "name_asc" -> compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
                    "name_desc" -> compareBy<File> { !it.isDirectory }.thenByDescending { it.name.lowercase() }
                    "size_asc" -> compareBy<File> { !it.isDirectory }.thenBy { it.length() }
                    "size_desc" -> compareBy<File> { !it.isDirectory }.thenByDescending { it.length() }
                    "date_asc" -> compareBy<File> { !it.isDirectory }.thenBy { it.lastModified() }
                    "date_desc" -> compareBy<File> { !it.isDirectory }.thenByDescending { it.lastModified() }
                    else -> compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
                })
                else -> {
                    val probed = mutableListOf<File>()
                    for (name in arrayOf("0", "self", "primary")) {
                        val child = File(dir, name)
                        if (child.isDirectory) probed.add(child)
                    }
                    probed
                }
            }
            val adapter = FileAdapter(this, files, bookmarks, { path -> if (bookmarks.contains(path)) bookmarks.remove(path) else bookmarks.add(0, path); saveBookmarks() }, df)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Stale-navigation guard: if the user navigated this tab to another
                // dir while the background scan was running, don't overwrite.
                if (tab.currentDir != dir) return@runOnUiThread
                tab.tvCount.text = getString(R.string.selected_count, files.size)
                if (files.isEmpty()) {
                    tab.tvEmpty.text = if (raw == null) getString(R.string.no_permission) else getString(R.string.empty_folder)
                    tab.tvEmpty.visibility = View.VISIBLE
                } else tab.tvEmpty.visibility = View.GONE
                tab.listFiles.adapter = adapter
            }
            // Background password detection for archive files → 🔒 badge.
            val archives = files.filter { it.isFile && (it.extension.lowercase() in ARCHIVE_EXTS || isVolumeFile(it) != null) }
            if (archives.isNotEmpty()) {
                val pw = archives.filter { isPasswordProtected(it) }.map { it.absolutePath }.toSet()
                if (pw.isNotEmpty()) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (tab.listFiles.adapter === adapter) {
                            adapter.passwordProtected = pw
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }
    }

internal fun MainActivity.extract() = extract(activeTab)

internal fun MainActivity.extract(tab: TabState) {
        val src = tab.selectedFile ?: return
        val ext = src.name.lowercase().substringAfterLast('.')
        // Accept magic-detected archives too — the manual format picker must
        // be reachable for extensionless / mislabeled files.
        if (ext !in ARCHIVE_EXTS && isVolumeFile(src) == null && detectFormatByMagic(src) == null && src.isDirectory.not()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_extract_failed))
                                .setMessage(getString(R.string.err_not_archive))
                .setPositiveButton(getString(R.string.action_confirm), null)
                .show()
            return
        }
        showFormatPicker(this, getString(R.string.title_select_format)) { format ->
            showExtractOptions(src, format)
        }
    }

internal fun MainActivity.select(f: File) = select(activeTab, f)

internal fun MainActivity.select(tab: TabState, f: File) {
        val ext = f.name.lowercase().substringAfterLast('.')
        val volumeFmt = isVolumeFile(f)

        val isApk = ext == "apk"
        // APK files are NEVER treated as archives — magic sniffing would read
        // them as ZIP (they are zip containers), which would make the app look
        // like an unpacker. APKs only ever go through the system installer.
        if (!isApk && (ext in ARCHIVE_EXTS || volumeFmt != null || detectFormatByMagic(f) != null)) {
            if (prefs.getInt("work_mode", 0) == 1) {
                toast(getString(R.string.msg_compress_mode_block))
                return
            }
            tab.selectedFile = f
            val vols = when {
                volumeFmt == "rar" || ext == "rar" -> resolveRarVolumes(f)
                volumeFmt == "7z" -> resolveSevenZVolumes(f)
                volumeFmt == "zip" -> resolveZipVolumes(f)
                else -> listOf(f)
            }
            val label = if (vols.size > 1) getString(R.string.msg_multivolume, vols.size) else ""
            tab.tvSelected.text = "${f.name}  |  ${fmt(fileSize(f))}" + if (label.isNotEmpty()) "  |  $label" else ""
            tab.fabExtract.visibility = View.VISIBLE
            tab.fabExtract.setOnClickListener {
                val src = tab.selectedFile ?: return@setOnClickListener
                val fmt = volumeFmt ?: detectFormat(src) ?: detectFormatByMagic(src)
                if (fmt != null) previewArchive(src, fmt) else extract(tab)
            }
            return
        }

        // APK → show FAB; tapping it hands the file to the system installer
        // (same interaction as archives). APKs are never unpacked here.
        if (isApk) {
            tab.selectedFile = f
            tab.tvSelected.text = "${f.name}  |  ${fmt(fileSize(f))}"
            tab.fabExtract.visibility = View.VISIBLE
            tab.fabExtract.setOnClickListener {
                val src = tab.selectedFile ?: return@setOnClickListener
                installApk(src)
            }
            return
        }

        // CSO (PSP compressed ISO) → convert back to ISO.
        if (ext == "cso" || ext == "ciso") {
            tab.selectedFile = f
            tab.tvSelected.text = "${f.name}  |  ${fmt(fileSize(f))}"
            tab.fabExtract.visibility = View.VISIBLE
            tab.fabExtract.setOnClickListener {
                val src = tab.selectedFile ?: return@setOnClickListener
                convertIso(src, toCso = false)
            }
            return
        }

        // Compress mode: any non-archive single file → show compress FAB at bottom-right
        if (prefs.getInt("work_mode", 0) == 1) {
            tab.selectedFile = f
            tab.tvSelected.text = getString(R.string.file_selected, f.name, fmt(fileSize(f)))
            tab.bottomBar.visibility = View.GONE
            if (activeTab === tab) tab.btnFolderNext?.visibility = View.GONE
            tab.fabExtract.visibility = View.VISIBLE
            tab.fabExtract.setOnClickListener { showCompressFormatPicker(this, f, prefs, tab.currentDir, tab) { navTab(tab, tab.currentDir) } }
            return
        }

        // Previewable non-archive files → show preview dialog
        if (ext in PREVIEW_EXTS) {
            // A previously selected archive's FAB would otherwise stay visible
            // and act on the old file — reset the selection state.
            tab.selectedFile = null
            tab.fabExtract.visibility = View.GONE
            AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(arrayOf(getString(R.string.preview), getString(R.string.action_file_info))) { _, w ->
                    when (w) {
                        0 -> previewLocalFile(this, f)
                        1 -> showFileInfoDialog(f)
                    }
                }.setNegativeButton(getString(R.string.action_cancel), null).show()
            return
        }

        // Neither archive nor previewable — just show info
        tab.selectedFile = null
        tab.fabExtract.visibility = View.GONE
        showFileInfoDialog(f)
    }

internal fun MainActivity.showExtractOptions(src: File, format: String) {
        val parent = src.parentFile ?: return
        val outDir = uniqueFile(parent, src.nameWithoutExtension)

        val items = mutableListOf(getString(R.string.action_preview), getString(R.string.action_extract))
        if (format == "iso") items.add(getString(R.string.action_convert_cso))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_preview_archive, src.name, format.uppercase()))
            .setItems(items.toTypedArray()) { _, w ->
                when (w) {
                    0 -> previewArchive(src, format)
                    1 -> showDirectExtractDialog(src, format, parent, outDir)
                    2 -> convertIso(src, toCso = true)
                }
            }.setNegativeButton(getString(R.string.action_cancel), null).show()
    }

internal fun MainActivity.showDirectExtractDialog(src: File, format: String, parent: File, outDir: File) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_extract_to))
            .setItems(arrayOf(getString(R.string.extract_new_folder, getString(R.string.title_new_folder), outDir.name), getString(R.string.action_extract), getString(R.string.action_choose_dir))) { _, w ->
                when (w) {
                    0 -> extractAll(outDir, src, format)
                    1 -> extractAll(parent, src, format)
                    2 -> showFolderPicker(this, parent) { picked ->
                        extractAll(picked, src, format)
                    }
                }
            }.setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

/**
 * Hand the APK to the system installer. Primary path is ACTION_VIEW via a
 * content:// URI with the read grant attached to BOTH the intent and its
 * clipData (EMUI/HarmonyOS refuses installs when the grant only rides on the
 * intent). If the ROM has no working ACTION_VIEW handler for package archives,
 * fall back to streaming the bytes straight into the system PackageInstaller,
 * which works on every Android version regardless of ROM quirks.
 */
internal fun MainActivity.installApk(f: File) {
    // Honor/EMUI system installers may delete the source APK after a
    // successful install — let the user choose to keep a copy first.
    // Options live in a custom view body instead of setItems / positive+neutral
    // buttons: on some EMUI builds those get hidden and only the negative
    // (cancel) button stays visible.
    fun optionRow(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = 15f
        setPadding(24, 16, 24, 16)
        background = android.graphics.drawable.ColorDrawable(0x00000000)
        setOnClickListener { onClick() }
    }
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(C["surface"]!!)
        addView(TextView(this@installApk).apply {
            text = getString(R.string.install_warn_delete)
            setTextColor(C["secondary"]!!)
            textSize = 13f
            setPadding(24, 16, 24, 12)
        })
        addView(optionRow(getString(R.string.install_keep_copy), C["accent"]!!) { doInstallApk(f, keepCopy = true) })
        addView(View(this@installApk).apply {
            setBackgroundColor(C["divider_subtle"]!!)
            layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(24, 0, 24, 0) }
        })
        addView(optionRow(getString(R.string.install_now), C["primary"]!!) { doInstallApk(f, keepCopy = false) })
    }
    AlertDialog.Builder(this)
        .setTitle(f.name)
        .setView(body)
        .setNegativeButton(getString(R.string.action_cancel), null)
        .show()
}

private fun MainActivity.doInstallApk(f: File, keepCopy: Boolean) {
    var backup: File? = null
    if (keepCopy) {
        // Back up next to the original APK (not app-private cache under
        // Android/data, which the file browser can't show). uniqueFile gives
        // "app (1).apk" when the name already exists.
        val parent = f.parentFile
        if (parent != null) {
            val b = uniqueFile(parent, f.name)
            try {
                f.copyTo(b, overwrite = false)
                backup = b
            } catch (_: Exception) { backup = null }
        }
    }
    toast(getString(R.string.msg_installing))
    val launched = try {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        true
    } catch (_: Exception) {
        false
    }
    if (backup != null) toast(getString(R.string.install_backup_done, backup.path))
    if (!launched) installViaPackageInstaller(f)
}

/** Streams the APK into the system PackageInstaller — no content:// grant, no
 *  ROM ACTION_VIEW handler needed. The byte copy runs on a worker thread so a
 *  large APK can't ANR the UI thread. */
private fun MainActivity.installViaPackageInstaller(f: File) {
    val pd = ProgressDialog(this).apply {
        setTitle(getString(R.string.msg_installing))
        setProgressStyle(ProgressDialog.STYLE_SPINNER)
        setCancelable(false)
        show()
    }
    thread {
        try {
            val pm = packageManager
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val session = pm.packageInstaller.openSession(pm.packageInstaller.createSession(params))
            try {
                // Writing and then closing the stream signals the session that the
                // bytes are complete; commit() then shows the system install screen.
                FileInputStream(f).use { input ->
                    session.openWrite("install", 0, f.length()).use { out ->
                        input.copyTo(out, 1 shl 20)
                    }
                }
                val pending = PendingIntent.getBroadcast(this, 0,
                    Intent("com.usefulunpacker.INSTALL_RESULT"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                session.commit(pending.intentSender)
            } finally {
                session.close()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                pd.dismiss()
            }
        } catch (e: SecurityException) {
            // REQUEST_INSTALL_PACKAGES not granted — guide the user to settings.
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                pd.dismiss()
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_install_failed))
                    .setMessage(getString(R.string.msg_install_unknown))
                    .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName")))
                        } catch (e: Exception) { android.util.Log.e("FileBrowser", "APK backup failed", e) }
                    }
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                pd.dismiss()
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_install_failed))
                    .setMessage(getString(R.string.err_install_apk, e.message ?: ""))
                    .setPositiveButton(getString(R.string.action_confirm), null)
                    .show()
            }
        }
    }
}

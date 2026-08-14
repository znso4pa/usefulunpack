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

internal fun MainActivity.nav(dir: File) {
        // Clear any leftover multi-select when navigating away — otherwise the
        // batch bar persists and the selection mixes files from old dirs
        // (batch delete could target files the user can no longer see).
        if (multiSelectMode) exitMultiSelect()
        selectedFile = null
        bottomBar.visibility = View.GONE
        fabExtract.visibility = View.GONE
        btnExtract.text = getString(R.string.msg_extract_title)
        btnExtract.setOnClickListener { extract() }
        btnFolderNext.visibility = View.GONE
        currentDir = dir
        tvPath.text = dir.absolutePath
        val isCompress = prefs.getInt("work_mode", 0) == 1
        findViewById<TextView>(R.id.tvTitle)?.text = "UsefulUnpack" + (if (isCompress) getString(R.string.title_mode_compress) else getString(R.string.title_mode_archive))
        tvCount.text = "…"
        tvEmpty.visibility = View.GONE
        listFiles.adapter = FileAdapter(this, emptyList(), bookmarks, { path -> if (bookmarks.contains(path)) bookmarks.remove(path) else bookmarks.add(0, path); saveBookmarks() }, df)

        thread {
            val raw = dir.listFiles()
            val files: List<File> = when {
                raw != null -> raw.sortedWith(
                    compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
                )
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
                tvCount.text = getString(R.string.selected_count, files.size)
                if (files.isEmpty()) {
                    tvEmpty.text = if (raw == null) getString(R.string.no_permission) else getString(R.string.empty_folder)
                    tvEmpty.visibility = View.VISIBLE
                } else tvEmpty.visibility = View.GONE
                listFiles.adapter = adapter
            }
            // Background password detection for archive files → 🔒 badge.
            // Only paint onto the adapter this scan created — if the user has
            // navigated elsewhere meanwhile, skip (don't stain the new folder).
            val archives = files.filter { it.isFile && (it.extension.lowercase() in ARCHIVE_EXTS || isVolumeFile(it) != null) }
            if (archives.isNotEmpty()) {
                val pw = archives.filter { isPasswordProtected(it) }.map { it.absolutePath }.toSet()
                if (pw.isNotEmpty()) {
                    runOnUiThread {
                        if (listFiles.adapter === adapter) {
                            adapter.passwordProtected = pw
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }
    }

internal fun MainActivity.extract() {
        val src = selectedFile ?: return
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

internal fun MainActivity.select(f: File) {
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
            selectedFile = f
            val vols = when {
                volumeFmt == "rar" || ext == "rar" -> resolveRarVolumes(f)
                volumeFmt == "7z" -> resolveSevenZVolumes(f)
                volumeFmt == "zip" -> resolveZipVolumes(f)
                else -> listOf(f)
            }
            val label = if (vols.size > 1) getString(R.string.msg_multivolume, vols.size) else ""
            tvSelected.text = "${f.name}  |  ${fmt(fileSize(f))}" + if (label.isNotEmpty()) "  |  $label" else ""
            fabExtract.visibility = View.VISIBLE
            fabExtract.setOnClickListener {
                val src = selectedFile ?: return@setOnClickListener
                val fmt = volumeFmt ?: detectFormat(src) ?: detectFormatByMagic(src)
                if (fmt != null) previewArchive(src, fmt) else extract()
            }
            return
        }

        // APK → show FAB; tapping it hands the file to the system installer
        // (same interaction as archives). APKs are never unpacked here.
        if (isApk) {
            selectedFile = f
            tvSelected.text = "${f.name}  |  ${fmt(fileSize(f))}"
            fabExtract.visibility = View.VISIBLE
            fabExtract.setOnClickListener {
                val src = selectedFile ?: return@setOnClickListener
                installApk(src)
            }
            return
        }

        // Compress mode: any non-archive single file → show compress FAB at bottom-right
        if (prefs.getInt("work_mode", 0) == 1) {
            selectedFile = f
            tvSelected.text = getString(R.string.file_selected, f.name, fmt(fileSize(f)))
            bottomBar.visibility = View.GONE
            btnFolderNext.visibility = View.GONE
            fabExtract.visibility = View.VISIBLE
            fabExtract.setOnClickListener { showCompressFormatPicker(this, f, prefs, currentDir) { nav(currentDir) } }
            return
        }

        // Previewable non-archive files → show preview dialog
        if (ext in PREVIEW_EXTS) {
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
                showFileInfoDialog(f)
    }

internal fun MainActivity.showExtractOptions(src: File, format: String) {
        val parent = src.parentFile ?: return
        val outDir = uniqueFile(parent, src.nameWithoutExtension)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_preview_archive, src.name, format.uppercase()))
            .setItems(arrayOf(getString(R.string.action_preview), getString(R.string.action_extract))) { _, w ->
                when (w) {
                    0 -> previewArchive(src, format)
                    1 -> showDirectExtractDialog(src, format, parent, outDir)
                }
            }.setNegativeButton(getString(R.string.action_cancel), null).show()
    }

internal fun MainActivity.showDirectExtractDialog(src: File, format: String, parent: File, outDir: File) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_extract_to))
            .setItems(arrayOf(getString(R.string.extract_new_folder, getString(R.string.title_new_folder), outDir.name), getString(R.string.action_extract))) { _, w ->
                val out = if (w == 0) outDir else parent
                extractAll(out, src, format)
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
            runOnUiThread { pd.dismiss() }
        } catch (e: SecurityException) {
            // REQUEST_INSTALL_PACKAGES not granted — guide the user to settings.
            runOnUiThread {
                pd.dismiss()
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_install_failed))
                    .setMessage(getString(R.string.msg_install_unknown))
                    .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName")))
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show()
            }
        } catch (e: Exception) {
            runOnUiThread {
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

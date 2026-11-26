package com.usefulunpacker

import com.usefulunpacker.fileops.RecentsStore

import android.app.AlertDialog
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import com.usefulunpacker.featureThread as thread
/** 编辑 minimal loop: full extract to a cache dir → script list → in-place
 *  edit (showTextEditor) → repack into a new "…-cn.xp3/pfs" alongside the
 *  original. The working dir persists while the script list is open, so edits
 *  accumulate until the user taps 封回; re-entering 编辑 re-extracts fresh. */
internal fun MainActivity.startEditArchive(src: File, format: String, pwd: String, ownerTab: TabState = activeTab) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("archive_edit", newChain = true)
    try {
    val act = this
    // Same-archive mutex: never let two windows edit the same archive (each
    // would write its own -cn copy / repack from the same source).
    val openKey = archiveKey(src)
    val conflict = OpenArchiveRegistry.owner(openKey)
    if (conflict != null && conflict !== activeTab) {
        toast(getString(R.string.msg_archive_open_in_tab, tabTitle(conflict)))
        val idx = tabs.indexOf(conflict)
        if (idx >= 0) viewPager.currentItem = idx
        return
    }
    val editDir = File(cacheDir, "edit/${src.nameWithoutExtension}")
    // Lock first, then the dual progress dialog (see extractAll); the work dir
    // is cleared INSIDE the lock so a queued re-edit can't destroy an active one.
    val opH = tryStartOperation(this, format)
    OpenArchiveRegistry.register(openKey, activeTab)
    RecentsStore.record(this, src)
    var cancelled = false
    val accessors = extractAccessors(format)
    val prog = PollingProgressDialog(
        this,
        "${getString(R.string.edit_archive_title)} — ${src.name}",
        accessors,
        { n, b, t -> extractProgressMessage(this, n, b, t) },
        getString(R.string.action_cancel),
        { cancelled = true; accessors.cancel() },
        opH,
        ownerTab
    )
    prog.start()
    thread {
        // A queued-cancel must release the registry entry too — it was taken
        // eagerly at enqueue time and no other exit path runs below.
        if (!opH.await()) { OpenArchiveRegistry.unregister(openKey); return@thread }
        try {
            com.usefulunpacker.deleteOutputChecked(editDir, recursive = true)
            editDir.mkdirs()
            val o = extractByFormat(format, src.path, editDir.path, "", prefs, pwd)
            val scripts = editDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in EDIT_SCRIPT_EXTS }
                .sortedBy { it.name.lowercase() }
                .toList()
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                prog.dismiss()
                if (cancelled) { OpenArchiveRegistry.unregister(openKey); toast(getString(R.string.msg_cancelled)); return@runFeatureOnUiThread }
                if (!o.counts.ok) { OpenArchiveRegistry.unregister(openKey); toast(friendlyExtractError(act, o.error)); return@runFeatureOnUiThread }
                if (scripts.isEmpty()) { OpenArchiveRegistry.unregister(openKey); toast(getString(R.string.edit_no_scripts)); return@runFeatureOnUiThread }
                showEditScriptList(src, format, editDir, scripts, openKey)
            }
        } catch (e: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewFlow", e);
            OpenArchiveRegistry.unregister(openKey)
            runFeatureOnUiThread { if (isFinishing || isDestroyed) return@runFeatureOnUiThread; prog.dismiss(); failureNotice(getString(R.string.err_extract_io, e.message ?: "")) }
        } finally {
            opH.release()
        }
    }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
}

private fun MainActivity.showEditScriptList(src: File, format: String, editDir: File, scripts: List<File>, openKey: String) {
    val list = ListView(this).apply {
        adapter = object : BaseAdapter() {
            override fun getCount() = scripts.size
            override fun getItem(pos: Int): Any = scripts[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, v: View?, p: ViewGroup?): View {
                val row = v ?: layoutInflater.inflate(R.layout.item_folder_row, p, false)
                row.findViewById<TextView>(R.id.folder_row_name).text =
                    scripts[pos].relativeTo(editDir).path
                return row
            }
        }
        // Editing happens in a modal editor over this dialog — the dialog stays
        // alive so the user can edit several scripts, then tap 封回 once.
        setOnItemClickListener { _, _, pos, _ -> showTextEditor(this@showEditScriptList, scripts[pos]) }
        divider = android.graphics.drawable.ColorDrawable(C["surface_dark"]!!)
        dividerHeight = 1
        enableFastScroll()
    }
    val builder = com.usefulunpacker.UuDialogBuilder(this)
        .setTitle(getString(R.string.edit_archive_title))
        .setView(list)
        .setPositiveButton(getString(R.string.edit_repack)) { _, _ -> repackEditedArchive(src, format, editDir) }
        .setNegativeButton(R.string.action_cancel, null)
    lateinit var dlg: AlertDialog
    // Search the whole extracted working folder (the archive is already fully
    // unpacked into editDir, so results are real files — tap one to preview/edit,
    // then Repack as usual).
    builder.setNeutralButton(getString(R.string.action_search)) { _, _ ->
        dlg.dismiss()
        globalSearch(editDir)
    }
    dlg = builder.create()
    // Tall script list (capped 0.8h) — bottom-anchor below the tab strip; the
    // helper also re-arms the pager on dismiss, and `also` releases the slot.
    dlg.belowTabs { OpenArchiveRegistry.unregister(openKey) }
    dlg.show()
}

private fun MainActivity.repackEditedArchive(src: File, format: String, editDir: File, ownerTab: TabState = activeTab) {
    val parent = src.parentFile ?: return
    val ext = src.extension.ifEmpty { format }
    // Artemis 命名与产物名在【拿到槽位之后】解析（见 resolvePfsOutName）；进度卡
    // 在 await 前就要画，所以标题用【源包名】而不是产物名。
    val artemisNaming = format in setOf("pfs", "pf6") && prefs.getBoolean("pfs_artemis_naming", true)
    // RPG Maker only opens an archive named `Game.<ext>` beside its .exe.
    // NOTE: `format` here is the *read* key ("rgss"), not the pack key — the
    // write version is derived from the source extension further down. Testing
    // against the pack keys here silently disabled renaming on every repack.
    val gameNaming = format == "rgss" && prefs.getBoolean("rgss_game_naming", true)
    val opH = tryStartOperation(this, format)
    var cancelled = false
    val accessors = compressAccessors(format)
    val prog = PollingProgressDialog(
        this,
        "${getString(R.string.edit_repack)} — ${src.name}",
        accessors,
        { n, b, t -> compressProgressMessage(this, n, b, t) },
        getString(R.string.action_cancel),
        { cancelled = true; accessors.cancel() },
        opH,
        ownerTab
    )
    prog.start()
    thread {
        if (!opH.await()) return@thread
        try {
            val (outF, renamed) = resolveRgssOutName(
                resolvePfsOutName(uniqueFile(parent, "${src.nameWithoutExtension}-cn.$ext"), artemisNaming),
                gameNaming, ext
            )
            val ok = when (format) {
                // Mirror the source archive's encryption: a game's krkr2 filter
                // runs over EVERY entry, so a plain repack of an encrypted
                // archive would be unreadable by the game that owns it. The
                // original is still on disk and is the oracle the scheme is
                // resolved from.
                "xp3" -> Xp3Core.xp3CreateArchive(
                    "", editDir.path, outF.path, prefs.getInt("generic_level", 6).toString(),
                    xp3RepackEnc(ownerTab.previewEncNote)
                ) != null
                "nsa" -> NsaCore.nsaCreateArchive("", editDir.path, outF.path, "2") != null
                "iso" -> IsoCore.isoCreateArchive("", editDir.path, outF.path) != null
                "ypf" -> YpfCore.ypfCreateArchive("", editDir.path, outF.path, prefs.getInt("generic_level", 6).toString()) != null
                // Reached only from the edit workspace, where `format` is the
                // read key — decide the container from the source archive's
                // extension so the game's own `Game.rgss3a` keeps a v3 body.
                "rgss" -> RgssCore.rgssCreateArchive("", editDir.path, outF.path, rgssRepackVersionOf(src.name)) != null
                "7z" -> {
                    val pwEnabled = prefs.getBoolean("compress_password_enabled", false)
                    val password = if (pwEnabled) prefs.getString("compress_password", "") ?: "" else ""
                    compressDispatch(editDir, outF, "7z", prefs.getInt("sz_level", 6), password, prefs)
                }
                "pf6" -> PfsCore.pfsCreateArchivePf6("", editDir.path, outF.path) != null
                else -> PfsCore.pfsCreateArchive("", editDir.path, outF.path) != null
            }
            // 失败/取消清半成品：产物名可能是 root.pfs，截断的 root.pfs 会被
            // 游戏当分层补丁挂载，比留个 -cn 残留危险得多。
            if (cancelled || !ok) com.usefulunpacker.deleteOutputChecked(outF)
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                prog.dismiss()
                if (cancelled) toast(getString(R.string.msg_cancelled))
                else if (ok) {
                    toast(getString(R.string.edit_done, outF.name) + if (renamed) " " + getString(R.string.rgss_game_naming_note) else "")
                    refreshTab(ownerTab)
                }
                else failureNotice(getString(R.string.title_compress_failed))
            }
        } finally {
            opH.release()
        }
    }
}

package com.usefulunpacker


import android.widget.*
import java.io.File
import com.usefulunpacker.featureThread as thread
/**
 * True when the archive is a PKWARE multi-disk zip set (`.z01/.z02/.zip`).
 * Such archives can't be modified in place (zip_modify needs a single
 * seekable stream and would silently drop entries), so editing is refused.
 */
internal fun MainActivity.isMultiDiskZipArchive(archive: File): Boolean =
    isVolumeFile(archive) == "zip" && resolveZipVolumes(archive).size > 1

/**
 * Deletes the temp copies a zip-modify operation may leave behind:
 * `name.mod.zip` / `name.del.zip` / `name.add.zip` and the Rust-side
 * `.tmp{pid}` files next to them. Called on failure so a broken edit never
 * litters the archive's directory.
 */
internal fun MainActivity.cleanupZipModifyArtifacts(archive: File) {
    val dir = archive.parentFile ?: cacheDir
    val base = archive.nameWithoutExtension
    dir.listFiles()?.forEach { f ->
        val n = f.name
        if (n.startsWith("$base.") && (n == "$base.mod.zip" || n == "$base.del.zip" || n == "$base.add.zip"
                || n.startsWith("$base.mod.zip.tmp") || n.startsWith("$base.del.zip.tmp") || n.startsWith("$base.add.zip.tmp"))) {
            com.usefulunpacker.deleteOutputChecked(f)
        }
    }
}

/**
 * Replaces one entry inside a ZIP archive with the edited cache copy, via the
 * Rust zipModify path (untouched entries stay byte-identical). The result is
 * written as a `name-cn.zip` copy (uniqueFile dedupes to `name-cn (1).zip`),
 * never overwriting the original archive — a failed edit can't corrupt it.
 */
internal fun MainActivity.zipReplaceEntry(archive: File, entry: ArchiveEntry, newContent: File, pwd: String = "", ownerTab: TabState = activeTab) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("zip_replace", newChain = true)
    try {
    if (isMultiDiskZipArchive(archive)) { toast(getString(R.string.zip_multi_disk_no_edit)); return }
    // Rust 侧按 split('|') 解析操作串：条目名带 '|' 会错切路径（错删别的条目/
    // 写入垃圾路径），必须拒绝。
    if (entry.path.contains('|')) { failureNotice(getString(R.string.zip_invalid_entry_name)); return }
    val opH = tryStartOperation(this, "zip")
    val parent = archive.parentFile ?: cacheDir
    thread {
        if (!opH.await()) return@thread
        // outF/tmp 在拿到槽位后再解析：排队中的第二次编辑若在入队时解析，
        // 会与第一次解析出同一个 -cn 输出名，后完成者覆盖先完成者（编辑丢失）。
        val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.zip")
        val tmp = File(parent, "${archive.nameWithoutExtension}.mod.zip")
        try {
            val ok = ZipCore.zipModify("", archive.path, tmp.path,
                "replace|${entry.path}|${newContent.path}", pwd)
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                if (ok) {
                    // Move the temp to the -cn output; the original stays put.
                    java.nio.file.Files.move(tmp.toPath(), outF.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    toast(getString(R.string.edit_done, outF.name))
                    navTab(ownerTab, ownerTab.currentDir)
                } else {
                    cleanupZipModifyArtifacts(archive)
                    failureNotice(getString(R.string.title_compress_failed))
                }
            }
        } catch (e: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewFlow", e);
            cleanupZipModifyArtifacts(archive)
            runFeatureOnUiThread { if (isFinishing || isDestroyed) return@runFeatureOnUiThread; failureNotice(getString(R.string.err_extract_io, e.message ?: "")) }
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

/**
 * Deletes selected entries from a ZIP archive via the Rust zipModify path
 * (`delete|path` per line), writing a `name-cn.zip` copy (original untouched).
 * Split-volume zips are excluded by the caller.
 */
internal fun MainActivity.zipDeleteEntries(archive: File, paths: List<String>, pwd: String = "", ownerTab: TabState = activeTab) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("zip_delete", newChain = true)
    try {
    if (isMultiDiskZipArchive(archive)) { toast(getString(R.string.zip_multi_disk_no_edit)); return }
    if (paths.isEmpty()) { toast(getString(R.string.msg_select_one)); return }
    // '|' 会错切 delete 操作串（Rust split('|')），错删别的条目——拒绝。
    if (paths.any { it.contains('|') }) { failureNotice(getString(R.string.zip_invalid_entry_name)); return }
    val opH = tryStartOperation(this, "zip")
    val parent = archive.parentFile ?: cacheDir
    val ops = paths.joinToString("\n") { "delete|$it" }
    thread {
        if (!opH.await()) return@thread
        val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.zip") // 拿到槽位后再解析，防排队互相覆盖
        val tmp = File(parent, "${archive.nameWithoutExtension}.del.zip")
        try {
            val ok = ZipCore.zipModify("", archive.path, tmp.path, ops, pwd)
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                if (ok) {
                    java.nio.file.Files.move(tmp.toPath(), outF.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    toast(getString(R.string.edit_done, outF.name))
                    navTab(ownerTab, ownerTab.currentDir)
                } else {
                    cleanupZipModifyArtifacts(archive)
                    failureNotice(getString(R.string.title_compress_failed))
                }
            }
        } catch (e: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewFlow", e);
            cleanupZipModifyArtifacts(archive)
            runFeatureOnUiThread { if (isFinishing || isDestroyed) return@runFeatureOnUiThread; failureNotice(getString(R.string.err_extract_io, e.message ?: "")) }
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

/**
 * Adds one entry to a ZIP archive from a local file, via the Rust zipModify
 * `add|name|srcPath` path, writing a `name-cn.zip` copy (original untouched).
 */
internal fun MainActivity.zipAddEntry(archive: File, entryName: String, srcFile: File, pwd: String = "", ownerTab: TabState = activeTab) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("zip_add", newChain = true)
    try {
    if (isMultiDiskZipArchive(archive)) { toast(getString(R.string.zip_multi_disk_no_edit)); return }
    val opH = tryStartOperation(this, "zip")
    val parent = archive.parentFile ?: cacheDir
    val safeName = entryName.replace('\\', '/').trim('/')
    // 用户输入的新条目名带 '|' 会错切 add 操作串（路径/源路径错位）——拒绝。
    if (safeName.contains('|')) { failureNotice(getString(R.string.zip_invalid_entry_name)); return }
    thread {
        if (!opH.await()) return@thread
        val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.zip") // 拿到槽位后再解析，防排队互相覆盖
        val tmp = File(parent, "${archive.nameWithoutExtension}.add.zip")
        try {
            val ok = ZipCore.zipModify("", archive.path, tmp.path,
                "add|$safeName|${srcFile.path}", pwd)
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                if (ok) {
                    java.nio.file.Files.move(tmp.toPath(), outF.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    toast(getString(R.string.edit_done, outF.name))
                    navTab(ownerTab, ownerTab.currentDir)
                } else {
                    cleanupZipModifyArtifacts(archive)
                    failureNotice(getString(R.string.title_compress_failed))
                }
            }
        } catch (e: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewFlow", e);
            cleanupZipModifyArtifacts(archive)
            runFeatureOnUiThread { if (isFinishing || isDestroyed) return@runFeatureOnUiThread; failureNotice(getString(R.string.err_extract_io, e.message ?: "")) }
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

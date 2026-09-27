package com.usefulunpacker

import android.app.AlertDialog
import java.io.File
import kotlin.concurrent.thread

/**
 * 预览工作区：预览 ⋮「在窗口中打开」把归档整包（或勾选子集）解压到
 * `cacheDir/ws/<hash>/`，再以普通目录 tab 打开——多选/复制/移动/重命名/
 * 分享/详情等文件能力全部免费获得。设计定稿见 TODO.md「预览工作区」段。
 *
 * 套娃递归天然支持：工作区里的子归档走现有 select → preview 流程，
 * 其 ⋮ 又能再开工作区，无限层级。工作区内删除走正常回收站，无需特判。
 */

/** 工作区目录命名：沿用 openNestedArchive 的 `<名字>_<路径哈希>` 惯例。 */
private fun MainActivity.wsDirFor(src: File): File =
    File(cacheDir, "ws/${src.nameWithoutExtension}_${src.absolutePath.hashCode().toString(16)}")

/**
 * 工作区入口。selectedOnly=false 整包；true 仅解压预览里勾选的顶层条目
 * （目录勾选 = 整棵子树，与「解压所选」同一套顶层过滤）。
 */
internal fun MainActivity.openWorkspaceFromPreview(tab: TabState, selectedOnly: Boolean) {
    val src = tab.previewSrc ?: return
    val format = tab.previewFormat
    val pwd = tab.previewPwd
    val entries = tab.previewEntries
    val sel = if (selectedOnly)
        tab.previewSelected.filter { p -> tab.previewSelected.none { o -> o != p && o.startsWith(p + "/") } }
    else emptyList()
    if (selectedOnly && sel.isEmpty()) { toast(getString(R.string.msg_select_one)); return }
    // 前置拒绝：窗口已满就先别解压，否则解压完的缓存目录成了孤儿。
    if (tabs.size >= MainActivity.MAX_TABS) { toast(getString(R.string.msg_max_tabs)); return }
    val wsDir = wsDirFor(src)
    // 同一归档已开过工作区：直接跳过去，绝不 deleteRecursively 别的窗口正浏览的目录。
    val existing = tabs.firstOrNull { it.wsDir == wsDir }
    if (existing != null) {
        toast(getString(R.string.msg_archive_open_in_tab, tabTitle(existing)))
        val idx = tabs.indexOf(existing)
        if (idx >= 0) viewPager.currentItem = idx
        return
    }
    // 大包保护：实际条目大小超过阈值弹确认（显示真实大小）。勾选目录时按
    // 前缀把整棵子树都算上——目录条目本身 size=0，只算顶层会绕过确认框。
    val totalSize = if (selectedOnly)
        sel.sumOf { p ->
            entries.filter { !it.isDirectory && (it.path == p || it.path.startsWith(p + "/")) }.sumOf { it.size }
        }
    else entries.filter { !it.isDirectory }.sumOf { it.size }
    if (totalSize > WS_SIZE_LIMIT_BYTES) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ws_open))
            .setMessage(getString(R.string.ws_size_confirm, fmt(totalSize)))
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                startWorkspaceExtract(tab, src, format, pwd, sel, wsDir)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
        return
    }
    startWorkspaceExtract(tab, src, format, pwd, sel, wsDir)
}

private fun MainActivity.startWorkspaceExtract(
    tab: TabState, src: File, format: String, pwd: String, sel: List<String>, wsDir: File
) {
    // 密码不变量：能进预览说明密码已收集（或无密码），这里直接复用，无需再弹窗，
    // 也不占槽位等待（密码弹窗禁止进 tryStartOperation 的全局约束）。
    val opH = tryStartOperation(this, format)
    var cancelled = false
    val accessors = extractAccessors(format)
    val prog = PollingProgressDialog(
        this,
        getString(R.string.ws_open) + " — " + src.name,
        accessors,
        { n, b, t -> extractProgressMessage(this, n, b, t) },
        getString(R.string.action_cancel),
        { cancelled = true; accessors.cancel() },
        opH,
        tab
    )
    prog.start()
    thread {
        if (!opH.await()) return@thread
        try {
            // 工作目录在锁内清空重建：排队的第二次开工作区不会毁掉进行中的那次。
            wsDir.deleteRecursively()
            wsDir.mkdirs()
            val o = extractByFormat(format, src.path, wsDir.path, sel.joinToString("\n"), prefs, pwd)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                prog.dismiss()
                if (cancelled) { wsDir.deleteRecursively(); toast(getString(R.string.msg_cancelled)); return@runOnUiThread }
                if (!o.counts.ok) { wsDir.deleteRecursively(); toast(friendlyExtractError(this, o.error)); return@runOnUiThread }
                openWorkspaceTab(wsDir, src.name)
            }
        } catch (e: Exception) {
            wsDir.deleteRecursively()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                prog.dismiss()
                toast(getString(R.string.err_extract_io, e.message ?: ""))
            }
        } finally {
            opH.release()
        }
    }
}

/** 开一个浏览工作区目录的新 tab（手动构建，照 openPickerInTab 模式）。 */
internal fun MainActivity.openWorkspaceTab(wsDir: File, srcName: String) {
    // 重查同 wsDir：入口检查后可能又开了一次（第一次还在排队时）。这里绝不
    // 能删 wsDir——已有 tab 正在浏览它，且排队的那次解压已把目录重建为相同
    // 内容（同源归档）；只需跳转过去。
    tabs.firstOrNull { it.wsDir == wsDir }?.let { existing ->
        toast(getString(R.string.msg_archive_open_in_tab, tabTitle(existing)))
        val idx = tabs.indexOf(existing)
        if (idx >= 0) viewPager.currentItem = idx
        return
    }
    if (tabs.size >= MainActivity.MAX_TABS) { wsDir.deleteRecursively(); toast(getString(R.string.msg_max_tabs)); return } // 双保险：不留孤儿缓存
    val ws = TabState((tabs.maxOfOrNull { it.tabId } ?: -1) + 1)
    ws.currentDir = wsDir
    ws.wsDir = wsDir
    // 📦 前缀来自字符串资源（emoji 只进资源，不在 Kotlin 里拼接）。
    ws.title = getString(R.string.ws_tab_title, srcName)
    tabs.add(ws)
    rebuildPager()
    viewPager.post {
        viewPager.currentItem = tabs.size - 1
        restartDirObserverFor(ws)
    }
    saveSession()
}

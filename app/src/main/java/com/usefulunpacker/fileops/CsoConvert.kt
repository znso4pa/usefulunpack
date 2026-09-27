package com.usefulunpacker

import android.content.SharedPreferences
import java.io.File
import kotlin.concurrent.thread

/**
 * Converts an ISO image to PSP CISO (CSO) or back. Both directions stream
 * block-by-block under the OpScheduler ("cso" key) with the dual progress
 * dialog. The result is written alongside the source with a de-duplicated name.
 */
internal fun MainActivity.convertIso(src: File, toCso: Boolean, ownerTab: TabState = activeTab) {
    val parent = src.parentFile ?: return
    val outName = if (toCso) "${src.nameWithoutExtension}.cso" else "${src.nameWithoutExtension}.iso"
    val outFile = uniqueFile(parent, outName)
    val opH = tryStartOperation(this, "cso")
    var cancelled = false
    val accessors = ProgressAccessors(
        { CsoCore.csoProgressCount() }, { CsoCore.csoProgressTotal() },
        { CsoCore.csoProgressFileCount() }, { CsoCore.csoProgressFileTotal() },
        { CsoCore.csoProgressName() }, { CsoCore.csoCancel() }
    )
    val prog = PollingProgressDialog(
        this,
        getString(R.string.msg_converting) + " — " + outFile.name,
        accessors,
        { n, b, t -> compressProgressMessage(this, n, b, t) },
        getString(R.string.action_cancel),
        { cancelled = true; CsoCore.csoCancel() },
        opH,
        ownerTab
    )
    prog.start()
    thread {
        if (!opH.await()) return@thread
        try {
            val ok = if (toCso) CsoCore.isoToCso("", src.path, outFile.path, "2048")
                     else CsoCore.csoToIso("", src.path, outFile.path)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                prog.dismiss()
                if (cancelled) {
                    outFile.delete()
                    toast(getString(R.string.msg_cancelled))
                } else if (ok) {
                    toast(getString(R.string.msg_convert_done, outFile.name))
                    // 刷拥有该操作的窗口，而不是瞬时的活跃窗口——长转换期间
                    // 用户切窗后结果要出现在发起它的那个 tab 里。
                    refreshTab(ownerTab)
                } else {
                    outFile.delete()
                    toast(getString(R.string.title_convert_failed))
                }
            }
        } catch (e: Exception) {
            outFile.delete()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                prog.dismiss()
                toast(getString(R.string.title_convert_failed))
            }
        } finally {
            opH.release()
        }
    }
}

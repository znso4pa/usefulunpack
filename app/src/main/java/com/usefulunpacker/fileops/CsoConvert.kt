package com.usefulunpacker

import android.content.SharedPreferences
import java.io.File
import kotlin.concurrent.thread

/**
 * Converts an ISO image to PSP CISO (CSO) or back. Both directions stream
 * block-by-block with the dual progress dialog and OperationLock isolation.
 * The result is written alongside the source with a de-duplicated name.
 */
internal fun MainActivity.convertIso(src: File, toCso: Boolean) {
    val parent = src.parentFile ?: return
    val outName = if (toCso) "${src.nameWithoutExtension}.cso" else "${src.nameWithoutExtension}.iso"
    val outFile = uniqueFile(parent, outName)
    if (!tryStartOperation(this)) return
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
        { cancelled = true; CsoCore.csoCancel() }
    )
    prog.start()
    thread {
        try {
            val ok = if (toCso) CsoCore.isoToCso("", src.path, outFile.path, "2048")
                     else CsoCore.csoToIso("", src.path, outFile.path)
            runOnUiThread {
                prog.dismiss()
                if (cancelled) {
                    outFile.delete()
                    toast(getString(R.string.msg_cancelled))
                } else if (ok) {
                    toast(getString(R.string.msg_convert_done, outFile.name))
                    nav(currentDir)
                } else {
                    outFile.delete()
                    toast(getString(R.string.title_convert_failed))
                }
            }
        } catch (e: Exception) {
            outFile.delete()
            runOnUiThread {
                prog.dismiss()
                toast(getString(R.string.title_convert_failed))
            }
        } finally {
            OperationLock.release()
        }
    }
}

package com.usefulunpacker

import java.io.File
import kotlin.concurrent.thread

/**
 * 把一个音频文件转成 [targetFmt]（mp3 / ogg / wav）。
 *
 * 产出写在源文件旁边、用 [uniqueFile] 去重，**绝不覆盖原文件** —— 转换是有损的，
 * 覆盖等于把用户的原始素材毁掉。
 *
 * 形状与 [convertIso] 一致：走 OpScheduler 的 `audio` 槽位、双条进度卡片、
 * 取消只在显式按钮上生效。区别只有两点 —— 目标格式是变量、选项来自
 * [AudioConvertOptions]。两条流程共用一个 `libarchive_audio_core` 里的
 * progress store，所以必须用同一个槽位键，否则两个并发转换会互相清对方的进度。
 */
internal fun MainActivity.convertAudio(
    src: File,
    targetFmt: String,
    opts: AudioConvertOptions,
    ownerTab: TabState = activeTab,
) {
    val parent = src.parentFile ?: return
    val outFile = uniqueFile(parent, "${src.nameWithoutExtension}.$targetFmt")
    val opH = tryStartOperation(this, "audio")
    var cancelled = false
    val accessors = ProgressAccessors(
        { AudioCore.audioProgressCount() }, { AudioCore.audioProgressTotal() },
        { AudioCore.audioProgressFileCount() }, { AudioCore.audioProgressFileTotal() },
        { AudioCore.audioProgressName() }, { AudioCore.audioCancel() }
    )
    val prog = PollingProgressDialog(
        this,
        getString(R.string.msg_converting) + " — " + outFile.name,
        accessors,
        { n, b, t -> compressProgressMessage(this, n, b, t) },
        getString(R.string.action_cancel),
        { cancelled = true; AudioCore.audioCancel() },
        opH,
        ownerTab
    )
    prog.start()
    thread {
        if (!opH.await()) return@thread
        try {
            val ok = AudioCore.audioConvert("", src.path, outFile.path, targetFmt, opts.toJni())
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
            // 引擎的真原因在异常消息里（Rust 侧抛的是 `IOException("audio: …")`）。
            // 只报一句「转换失败」等于把唯一的诊断信息扔掉 —— 真机排障时
            // 用户能看到的就只有这一个 Toast。
            outFile.delete()
            val why = e.message ?: e.javaClass.simpleName
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                prog.dismiss()
                toast(getString(R.string.msg_convert_failed_why, why))
            }
        } finally {
            opH.release()
        }
    }
}

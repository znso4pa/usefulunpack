package com.usefulunpacker

import java.io.File
import kotlin.concurrent.thread

/**
 * 把一个 WMV/ASF 转成 MP4（H.264 + AAC）。
 *
 * 产出写在源文件旁边、用 [uniqueFile] 去重，**绝不覆盖原文件** —— 转换是有损的，
 * 覆盖等于把用户的原始素材毁掉。
 *
 * 形状与 [convertAudio] 一致：走 OpScheduler 的 `video` 槽位、双条进度卡片、
 * 取消只在显式按钮上生效。槽位键与音频**必须不同** —— 两个引擎是两份独立的
 * 进度槽（各自的 .so），共用键只会让调度器以为它们在抢同一个全局状态。
 */
internal fun MainActivity.convertVideo(
    src: File,
    opts: String = "",
    ownerTab: TabState = activeTab,
) {
    val parent = src.parentFile ?: return
    val outFile = uniqueFile(parent, "${src.nameWithoutExtension}.mp4")
    val opH = tryStartOperation(this, "video")
    var cancelled = false
    val accessors = ProgressAccessors(
        { VideoCore.videoProgressCount() }, { VideoCore.videoProgressTotal() },
        { VideoCore.videoProgressFileCount() }, { VideoCore.videoProgressFileTotal() },
        { VideoCore.videoProgressName() }, { VideoCore.videoCancel() }
    )
    val prog = PollingProgressDialog(
        this,
        getString(R.string.msg_converting) + " — " + outFile.name,
        accessors,
        { n, b, t -> compressProgressMessage(this, n, b, t) },
        getString(R.string.action_cancel),
        { cancelled = true; VideoCore.videoCancel() },
        opH,
        ownerTab
    )
    prog.start()
    thread {
        if (!opH.await()) return@thread
        try {
            val ok = VideoCore.videoConvert("", src.path, outFile.path, "mp4", opts)
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
            // 引擎的真原因在异常消息里（C 侧抛的是 `IOException("video: …")`）。
            // 只报一句「转换失败」等于把唯一的诊断信息扔掉。
            outFile.delete()
            val why = e.message ?: e.javaClass.simpleName
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                prog.dismiss()
                // 取消也会走到这里（C 侧抛 `video: cancelled`）。取消是用户自己按的，
                // 再弹一句「转换失败」等于把一次正常操作报成故障。
                if (cancelled) toast(getString(R.string.msg_cancelled))
                else toast(getString(R.string.msg_convert_failed_why, why))
            }
        } finally {
            opH.release()
        }
    }
}

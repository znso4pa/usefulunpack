package com.usefulunpacker

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import com.usefulunpacker.featureThread as thread
fun playAudio(activity: AppCompatActivity, file: File) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("audio_preview")
    try {
    val m = activity as? MainActivity
    // 释放之前的MediaPlayer，并关掉它的对话框（否则旧对话框永远「播放中」）
    m?.currentAudioDialog?.let { old -> m.currentAudioDialog = null; com.usefulunpacker.observedAttempt("PreviewDialogs") { old.dismiss() } }
    m?.currentMediaPlayer?.let { old -> m.currentMediaPlayer = null; old.release() }

    // prepare() 可能阻塞数百毫秒到数秒（大文件/慢存储）——必须离开主线程，
    // 否则输入事件堆积有 ANR 风险。对话框先弹，就绪后才开始计数。
    val dlg = com.usefulunpacker.UuDialogBuilder(activity)
        .setTitle(activity.getString(R.string.title_audio_player, file.name))
        .setMessage(activity.getString(R.string.msg_audio_playing))
        .setPositiveButton(activity.getString(R.string.action_stop), null)
        .create()
    // 停止 / 返回键 / 手动 dismiss 统一在 dismiss 监听里释放（按引用比对，防止
    // 与 OnCompletionListener 双重释放）。
    // ⚠️ 必须走 keepTabsTappable 的 `also`：它内部的 resetPagerInputOnDialogDismiss()
    // **独占** dismiss 监听器 —— 单独 setOnDismissListener 会被它整个覆盖，那样关掉
    // 播放对话框后 MediaPlayer 既不 stop 也不 release，音频继续响、文件句柄不释放。
    dlg.keepTabsTappable(also = {
        m?.currentMediaPlayer?.let { cur -> m.currentMediaPlayer = null; cur.release() }
        if (m?.currentAudioDialog === dlg) m.currentAudioDialog = null
    })
    dlg.show()
    m?.currentAudioDialog = dlg

    thread {
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .build())
                setDataSource(file.path)
                prepare()
                start()
            }
            activity.runFeatureOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !dlg.isShowing) {
                    mp.release(); return@runFeatureOnUiThread
                }
                m?.currentMediaPlayer = mp
                mp.setOnCompletionListener { done ->
                    if (m?.currentMediaPlayer === done) {
                        m.currentMediaPlayer = null
                        done.release()
                    }
                }
            }
        } catch (e: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewDialogs", e);
            activity.runFeatureOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
                dlg.dismiss()
                com.usefulunpacker.UuNotice.failure(activity, activity.getString(R.string.err_audio_playback, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
}

fun playVideo(activity: AppCompatActivity, file: File) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("video_preview")
    try {
    try {
        // Uri.fromFile throws FileUriExposedException on API 24+ — always go
        // through the FileProvider (same one the APK installer uses).
        val uri = androidx.core.content.FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewDialogs", e);
        com.usefulunpacker.UuNotice.failure(activity, activity.getString(R.string.err_video_playback, e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
}

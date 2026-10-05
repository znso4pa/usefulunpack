package com.usefulunpacker

import android.app.AlertDialog
import android.graphics.Bitmap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/** Conversion working-resolution cap (longest side). Balances quality vs OOM. */
private const val IMG_CONVERT_MAX_PX = 4096

/**
 * Opens a dialog to convert the given image to another format, saving a copy
 * (never overwriting the original):
 *  - static  jpg / png / webp  ↔  each other (Bitmap.compress)
 *  - animated gif / webp  →  static first frame (png / jpg / webp)
 * Full animated↔animated conversion needs encoders the framework lacks, so it
 * is intentionally out of scope here.
 */
fun showImageConvert(activity: AppCompatActivity, file: File) {
    val labels = arrayOf("JPG", "PNG", "WEBP")
    val fmts = arrayOf("jpg", "png", "webp")
    AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.img_convert_choose))
        .setItems(labels) { _, w ->
            convertImage(activity, file, fmts[w])
        }
        .setNegativeButton(activity.getString(R.string.action_cancel), null)
        .show()
        .also { it.keepTabsTappable() }
}

/** 支持的转换目标（`uu img` 与对话框共用同一份）。 */
internal val IMG_TARGET_FORMATS = setOf("jpg", "png", "webp")

/**
 * 解码 → (jpg 白底合成) → 压缩写出 [out]。**Activity 无关**，CLI 的 `uu img`
 * 与对话框走同一实现 —— 这段逻辑此前内联在对话框的线程里，两处各写一份必然漂移。
 *
 * @param quality jpg 1..90 有损；webp <100 有损、==100 无损；png 忽略（恒无损）
 * @return 是否写出了文件
 */
internal fun encodeImageTo(
    src: File, targetFmt: String, out: File, quality: Int = 90, maxPx: Int = IMG_CONVERT_MAX_PX,
): Boolean = runCatching {
    var bmp = decodeBitmapCapped(src, maxPx) ?: return@runCatching false
    // JPEG has no alpha: composite onto white so transparent PNG/WebP
    // areas don't turn black in the converted copy.
    if (targetFmt == "jpg" && bmp.hasAlpha()) {
        val opaque = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        opaque.eraseColor(0xFFFFFFFF.toInt())
        android.graphics.Canvas(opaque).drawBitmap(bmp, 0f, 0f, null)
        bmp.recycle()
        bmp = opaque
    }
    val format = when (targetFmt) {
        "jpg" -> Bitmap.CompressFormat.JPEG
        "png" -> Bitmap.CompressFormat.PNG
        else -> if (quality >= 100) {
            if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
        } else Bitmap.CompressFormat.WEBP
    }
    val q = when (targetFmt) {
        "jpg" -> quality.coerceIn(1, 100)
        "png" -> 100
        else -> quality.coerceIn(1, 100)
    }
    val ok = java.io.FileOutputStream(out).use { bmp.compress(format, q, it) }
    bmp.recycle()
    if (!ok) out.delete()
    ok
}.getOrDefault(false)

private fun convertImage(activity: AppCompatActivity, file: File, targetFmt: String) {
    val pd = android.app.ProgressDialog(activity)
    pd.setMessage(activity.getString(R.string.msg_loading)); pd.setCancelable(false); pd.show()
    pd.keepTabsTappable()
    thread {
        // 先在后台把成品压到缓存临时文件：目录选择可能被取消——此前取消时
        // 解码出的位图(≤64MB)会一直滞留等 GC。现在选完目录只是搬移成品。
        val temp: File? = runCatching {
            val tmp = File(activity.cacheDir, "img_convert_${file.nameWithoutExtension}_${System.currentTimeMillis()}.$targetFmt")
            if (encodeImageTo(file, targetFmt, tmp, if (targetFmt == "jpg") 90 else 100)) tmp else null
        }.getOrNull()
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            pd.dismiss()
            if (temp == null) {
                Toast.makeText(activity, activity.getString(R.string.msg_cannot_decode), Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            showFolderPicker(activity, file.parentFile ?: activity.cacheDir, false) { dir ->
                val outF = uniqueFile(dir, "${file.nameWithoutExtension}.$targetFmt")
                thread {
                    val ok = runCatching {
                        java.nio.file.Files.move(temp.toPath(), outF.toPath())
                        true
                    }.getOrDefault(false).also { if (!it) temp.delete() }
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        if (ok) Toast.makeText(activity, activity.getString(R.string.img_edit_saved, outF.name), Toast.LENGTH_LONG).show()
                        else Toast.makeText(activity, activity.getString(R.string.img_edit_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
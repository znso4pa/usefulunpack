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
}

private fun convertImage(activity: AppCompatActivity, file: File, targetFmt: String) {
    val pd = android.app.ProgressDialog(activity)
    pd.setMessage(activity.getString(R.string.msg_loading)); pd.setCancelable(false); pd.show()
    thread {
        val triple = runCatching {
            val bmp = decodeBitmapCapped(file, IMG_CONVERT_MAX_PX)
                ?: return@runCatching Triple<Bitmap?, Bitmap.CompressFormat, Int>(null, Bitmap.CompressFormat.PNG, 100)
            val format = when (targetFmt) {
                "jpg" -> Bitmap.CompressFormat.JPEG
                "png" -> Bitmap.CompressFormat.PNG
                else -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
            }
            val quality = if (targetFmt == "png" || targetFmt == "webp") 100 else 90
            Triple(bmp, format, quality)
        }.getOrDefault(Triple(null, Bitmap.CompressFormat.PNG, 100))
        val bmp = triple.first; val format = triple.second; val quality = triple.third
        activity.runOnUiThread {
            pd.dismiss()
            if (bmp == null) {
                Toast.makeText(activity, activity.getString(R.string.msg_cannot_decode), Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            showFolderPicker(activity, file.parentFile ?: activity.cacheDir, false) { dir ->
                val outF = uniqueFile(dir, "${file.nameWithoutExtension}.$targetFmt")
                thread {
                    val ok = runCatching {
                        java.io.FileOutputStream(outF).use { bmp.compress(format, quality, it) }
                        true
                    }.getOrDefault(false)
                    activity.runOnUiThread {
                        if (ok) Toast.makeText(activity, activity.getString(R.string.img_edit_saved, outF.name), Toast.LENGTH_LONG).show()
                        else Toast.makeText(activity, activity.getString(R.string.img_edit_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
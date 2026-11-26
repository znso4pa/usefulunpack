package com.usefulunpacker

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import com.usefulunpacker.featureThread as thread
/** Decodes an image as a static Bitmap capped to [maxPx] on the longest side
 *  (sampled), safe for very large files. GIF/WebP yield their first frame.
 *  Returns null on failure; safe off the UI thread.
 *  EXIF orientation: the ImageDecoder branch (API 28+) rotates automatically;
 *  the BitmapFactory fallback (API 26/27) gets an explicit re-rotation below. */
fun decodeBitmapCapped(file: File, maxPx: Int): Bitmap? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
            val src = android.graphics.ImageDecoder.createSource(file)
            android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                // Force a SOFTWARE bitmap: hardware bitmaps can't be drawn onto
                // a software Canvas (editor autosave/save) nor read with
                // getPixel() (eyedrop) — both crashed before this.
                decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE)
                val m = maxOf(info.size.width, info.size.height)
                if (m > maxPx) decoder.setTargetSampleSize(m / maxPx + 1)
            }
        } catch (failureCause: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewDialogs", failureCause);
            decodeBitmapSampled(file, maxPx)
        }
    } else decodeBitmapSampled(file, maxPx)
}

private fun decodeBitmapSampled(file: File, maxPx: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var s = 1
        while (bounds.outWidth / (s * 2) >= maxPx && bounds.outHeight / (s * 2) >= maxPx) s *= 2
        val opt = BitmapFactory.Options().apply { inSampleSize = s }
        BitmapFactory.decodeFile(file.path, opt)?.let { exifRotate(file, it) }
    } catch (failureCause: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewDialogs", failureCause); null }
}

/** Pre-API-28 BitmapFactory ignores EXIF orientation, so photos shot in
 *  portrait render sideways. Re-apply the common camera rotations (90/180/
 *  270; mirrored variants are vanishingly rare and left untouched). */
private fun exifRotate(file: File, bmp: Bitmap): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return bmp // ImageDecoder already rotated
    val degrees = try {
        when (androidx.exifinterface.media.ExifInterface(file.path).getAttributeInt(
            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (failureCause: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewDialogs", failureCause); return bmp }
    if (degrees == 0f) return bmp
    return try {
        val m = android.graphics.Matrix().apply { postRotate(degrees) }
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    } catch (failureCause: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewDialogs", failureCause); bmp }
}

fun showImagePreview(activity: AppCompatActivity, file: File) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("image_preview")
    try {
    // Decode OFF the UI thread with a target sample size — a huge PNG/JPG
    // decoded at full size on the main thread ANRs and can OOM.
    val metrics = activity.resources.displayMetrics
    val screenW = metrics.widthPixels
    val screenH = (metrics.heightPixels * 0.8).toInt()
    lateinit var d: AlertDialog
    thread {
        fun sampleFor(w: Int, h: Int): Int {
            var s = 1
            while (w / (s * 2) >= screenW && h / (s * 2) >= screenH) s *= 2
            return s
        }
        fun decodeSampled(): android.graphics.drawable.Drawable? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return null
            val opt = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight) }
            return BitmapFactory.decodeFile(file.path, opt)?.let { android.graphics.drawable.BitmapDrawable(activity.resources, exifRotate(file, it)) }
        }
        val drawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val src = android.graphics.ImageDecoder.createSource(file)
                android.graphics.ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
                    decoder.setTargetSampleSize(sampleFor(info.size.width, info.size.height))
                }.also { (it as? android.graphics.drawable.AnimatedImageDrawable)?.start() }
            } catch (failureCause: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewDialogs", failureCause);
                decodeSampled()
            }
        } else {
            decodeSampled()
        }
        activity.runFeatureOnUiThread {
            // Large images can take seconds to decode — the activity may be
            // gone by the time this runs (BadTokenException otherwise).
            if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
            if (drawable == null) {
                com.usefulunpacker.UuNotice.failure(activity, activity.getString(R.string.msg_cannot_decode), Toast.LENGTH_SHORT).show()
                return@runFeatureOnUiThread
            }
            val iv = ImageView(activity).apply {
                setImageDrawable(drawable)
                setBackgroundColor(0xFF000000.toInt())
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                maxWidth = screenW
                maxHeight = screenH
            }
            val scroll = com.usefulunpacker.UuScrollView(activity).apply {
                addView(iv)
                setBackgroundColor(0xFF000000.toInt())
                // Honor/EMUI NPEs drawing scrollbars on custom views — disable (scroll works).
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
            }

            // Top bar: filename + overflow menu (编辑 / 转换为其他格式) — keeps
            // the bottom of the preview clean (only Close).
            val overflowBtn = com.usefulunpacker.UuIconButton(activity).apply {
                setImageResource(R.drawable.ic_overflow)
                contentDescription = activity.getString(R.string.action_more)
                setOnClickListener { v ->
                    UuPopupMenu(activity, v).apply {
                        action(1, R.string.action_edit) { d.dismiss(); showImageEditor(activity, file) }
                        action(2, R.string.img_convert_menu) { d.dismiss(); showImageConvert(activity, file) }
                        show()
                    }
                }
            }
            val title = TextView(activity).apply {
                text = file.name
                setTextColor(C["primary"]!!)
                textSize = 16f
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val topBar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10, 4, 8, 4)
                addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(overflowBtn, LinearLayout.LayoutParams((40 * activity.resources.displayMetrics.density).toInt(), (40 * activity.resources.displayMetrics.density).toInt()))
            }
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(topBar)
                addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
            d = com.usefulunpacker.UuDialogBuilder(activity)
                .setView(root)
                .setPositiveButton(activity.getString(R.string.action_close), null)
                .create()
            // Full-image preview is tall — bottom-anchor below the tab strip.
            d.belowTabs()
            d.show()
        }
    }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
}

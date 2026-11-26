package com.usefulunpacker

import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.usefulunpacker.featureThread as thread
// ─── 背景图（壁纸）让位表 ─────────────────────────────────────────────────
//
// 壁纸是画在 `R.id.root` 上的，所以**任何不透明底都会把它挡住**。4.x 只有单窗口，
// 文件列表就是 `panel`，清掉 `panel` 就够了；加了多窗口之后布局变成
// `tabBar + toolbar + viewPager` + 每个 tab 自己的 `folderRoot/panel/previewRoot`，
// 每加一层不透明底就重新把壁纸挡死一次，而 `applyBackgroundImage` 从没跟着更新。
//
// 三条纪律（新增/改动布局时务必照做）：
//  1. 这里列出的每个 id，布局里都得有对应的半透明或透明处理；
//  2. 新加不透明背景的 view，必须同时加进下面的表里；
//  3. 只清一份是不够的 —— 每个 tab 一份，必须走整棵树。

/** 压半透明黑（对齐 toolbar 的观感）的 view。 */
private val BACKDROP_TRANSLUCENT = intArrayOf(
    R.id.toolbar, R.id.tabBar, R.id.pathBar, R.id.bottomBar,
)

/** 彻底清空背景的 view。 */
private val BACKDROP_TRANSPARENT = intArrayOf(
    R.id.viewPager, R.id.folderRoot, R.id.panel, R.id.previewRoot, R.id.homeRoot,
)

/** id → 原始背景资源，「清除背景」时照此恢复。两张表必须和上面一一对应。 */
private val BACKDROP_ORIGINAL: List<Pair<Int, Int>> = listOf(
    R.id.toolbar to R.color.bg_toolbar,
    R.id.tabBar to R.color.bg_toolbar,
    R.id.pathBar to R.color.bg_pathbar,
    R.id.bottomBar to R.color.bg_surface_raised,
    R.id.viewPager to R.color.bg_file_list,
    R.id.folderRoot to R.color.bg_file_list,
    R.id.panel to R.color.bg_file_list,
    R.id.previewRoot to R.color.bg_file_list,
    // 主界面（hub）：卡片配色本身按 hasWallpaper() 现算（HomeHub.kt），
    // 这里只管 ScrollView 自己的那层底。
    R.id.homeRoot to R.color.bg_file_list,
)

internal fun applyBackdropInTree(view: View) {
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) applyBackdropInTree(view.getChildAt(i))
    }
    val id = view.id
    if (id == View.NO_ID) return
    if (id in BACKDROP_TRANSLUCENT) view.setBackgroundColor(0xBB000000.toInt())
    if (id in BACKDROP_TRANSPARENT) view.background = null
}

internal fun restoreBackdropInTree(view: View) {
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) restoreBackdropInTree(view.getChildAt(i))
    }
    BACKDROP_ORIGINAL.firstOrNull { it.first == view.id }?.let { view.setBackgroundResource(it.second) }
}

internal fun MainActivity.hasWallpaper(): Boolean = prefs.getString("bg_image_uri", null) != null

/**
 * 让当前已存在的所有子层给壁纸让位（无壁纸则恢复原始背景）。
 *
 * 只覆盖**已经挂进 Activity 视图树**的那些 view。各 tab 的 `folderRoot` / `panel` /
 * `pathBar` / `bottomBar` / `previewRoot` 由 `FolderFragment` 在 `onCreateView` 里
 * 直接对自己的子树调 [applyBackdropInTree] —— 那时它还没被挂到 ViewPager 上，
 * 从 `R.id.root` 往下走**走不到它**，所以这里覆盖不到。
 */
internal fun MainActivity.refreshBackdrop() {
    val root = findViewById<View>(R.id.root) ?: return
    if (hasWallpaper()) applyBackdropInTree(root)
    else restoreBackdropInTree(root)
    // hub 的页面底色不在这两张表里（它按 hasWallpaper() 现算），得单独跟着重算 ——
    // 换壁纸时 hub 不会重建，漏了这句主界面就还停在旧底色上。见 browse/HomeHub.kt。
    refreshHubPageBg()
}

internal fun MainActivity.applyBackgroundImage(uri: Uri) {

        try {
            // Decode (and close the stream) off the UI thread; downsample to the
            // screen so a huge wallpaper image doesn't ANR/OOM the main thread.
            thread {
                val bmp = com.usefulunpacker.observedAttempt("AppSettings") {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(ins, null, bounds)
                        if (bounds.outWidth > 0) {
                            // Size for the LONG side so the same decoded master is
                            // enough for either orientation — rotation must not force
                            // a re-decode from the URI (which may have lost its
                            // persistable permission) nor upscale a too-small copy.
                            val need = maxOf(
                                resources.displayMetrics.widthPixels,
                                resources.displayMetrics.heightPixels,
                            )
                            val longSide = maxOf(bounds.outWidth, bounds.outHeight)
                            var sample = 1
                            while (longSide / (sample * 2) >= need) sample *= 2
                            val opt = BitmapFactory.Options().apply { inSampleSize = sample }
                            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
                        } else {
                            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        }
                    }
                }.getOrNull() ?: run {
                    AppDiagnostics.begin("wallpaper").fail(FailureCode.INVALID_RESULT, "Cannot decode selected wallpaper")
                    return@thread
                }
                runFeatureOnUiThread {
                    if (isFinishing || isDestroyed) { bmp.recycle(); return@runFeatureOnUiThread }
                    // The new image becomes the master; drop the previous one.
                    if (bgSourceBitmap !== bmp) bgSourceBitmap?.recycle()
                    bgSourceBitmap = bmp
                    bgLastW = -1; bgLastH = -1
                    installBgRootListener()
                    fitBackgroundToRoot()
                    // If the root is not measured yet this is a no-op and the
                    // layout listener does the first fit once it is.
                }
            }
            // Make surfaces transparent so the bg shows through everywhere
            refreshBackdrop()
            // Match status bar to toolbar
            window?.statusBarColor = 0xBB000000.toInt()
        } catch (failureCause: Exception) { com.usefulunpacker.AppDiagnostics.failure("AppSettings", failureCause);}

    }

/**
 * Re-fit the master wallpaper to the current `root` size.
 *
 * Scales to COVER the root (aspect preserved), then centre-crops to the root's
 * exact aspect ratio so `BitmapDrawable`'s default FILL gravity never distorts
 * the image. Called on first application and again on every root size change
 * (rotation, split-screen, multi-window resize) — see [installBgRootListener].
 *
 * Only the intermediate bitmaps are recycled here; the master (`bgSourceBitmap`)
 * must survive so the next re-fit still has full-resolution pixels.
 */
internal fun MainActivity.fitBackgroundToRoot() {
    val root = findViewById<View>(R.id.root) ?: return
    val bmp = bgSourceBitmap ?: return
    if (bmp.isRecycled) return
    val rw = root.width; val rh = root.height
    if (rw <= 0 || rh <= 0) return
    val alpha = prefs.getInt("bg_image_alpha", 20).coerceIn(1, 100)
    val bmpW = bmp.width; val bmpH = bmp.height
    val scale = maxOf(rw.toFloat() / bmpW, rh.toFloat() / bmpH)
    val sw = (bmpW * scale).toInt(); val sh = (bmpH * scale).toInt()
    // createScaledBitmap/createBitmap 在尺寸恰好相等时会返回【源对象本身】——
    // 无脑 recycle 源图就会把正在用的位图标记回收，下一帧绘制即抛 recycled bitmap。
    // 按身份判断再回收；母图永不回收。
    val scaled = if (sw == bmpW && sh == bmpH) bmp
        else android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true)
    val x = maxOf((sw - rw) / 2, 0); val y = maxOf((sh - rh) / 2, 0)
    val cw = minOf(rw, sw); val ch = minOf(rh, sh)
    val cropped = if (x == 0 && y == 0 && cw == scaled.width && ch == scaled.height) scaled
        else android.graphics.Bitmap.createBitmap(scaled, x, y, cw, ch)
    if (cropped !== scaled && scaled !== bmp) scaled.recycle()
    val dr = android.graphics.drawable.BitmapDrawable(resources, cropped)
    dr.alpha = (alpha * 255 / 100).coerceIn(1, 255)
    root.background = dr
    refreshBackdrop()
}

/**
 * Watch `root` for size changes and re-fit the wallpaper.
 *
 * Installed once. Guarded by the last seen size so setting the background (which
 * does not change the root's size) cannot re-enter. Without this, rotation —
 * which does NOT recreate the Activity because of `configChanges` — left the
 * wallpaper scaled for the previous orientation and visibly stretched.
 */
internal fun MainActivity.installBgRootListener() {
    if (bgRootListener != null) return
    val root = findViewById<View>(R.id.root) ?: return
    val l = android.view.View.OnLayoutChangeListener { _, l0, t0, r0, b0, _, _, _, _ ->
        val w = r0 - l0; val h = b0 - t0
        if (w != bgLastW || h != bgLastH) {
            bgLastW = w; bgLastH = h
            fitBackgroundToRoot()
        }
    }
    root.addOnLayoutChangeListener(l)
    bgRootListener = l
}

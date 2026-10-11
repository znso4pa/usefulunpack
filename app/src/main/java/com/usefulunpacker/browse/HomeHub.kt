package com.usefulunpacker

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.usefulunpacker.fileops.RecentsStore
import com.usefulunpacker.fileops.RecycleBin
import com.usefulunpacker.fileops.showRecycleBinDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每个 tab 的「主界面」（hub）—— 用户从四版 HTML 设计稿里选定的 **C2「命令中心」**。
 *
 * ```text
 *   tab ─┬─ 主界面（hub，盖住 pathBar+panel）─┬─ 命令面板（点击 → 全局搜索）
 *        │                                   ├─ 两张 hero 卡（文件浏览 / 终端）
 *        │                                   ├─ 快捷行（搜索 / 回收站 / 根目录 / 设置）
 *        │                                   ├─ 最近打开的归档 │ 书签（宽屏双栏）
 *        │                                   └─ 页脚
 *        └─ 文件浏览器（navTab 渲染的列表）
 * ```
 *
 * 实现与归档内预览（`previewRoot`）同一套路：`folder_view.xml` 里最后声明的覆盖层，
 * 盖住 pathBar + panel；`TabState.homeVisible` 决定谁在上面。
 *
 * 新 tab 默认落在主界面（`addTab` 置 true）；恢复会话的 tab 不落（恢复的是「你上次
 * 在哪儿」）—— 所以 `homeVisible` 不进 session，默认 false 即可。
 *
 * ⚠️ **壁纸让路**（AGENTS.md Known trap）：`homeRoot` 已登记进 `BACKDROP_TRANSPARENT`，
 * 卡片/面板的配色在 [buildHomeCards] 里按 [MainActivity.hasWallpaper] 现算 —— 有壁纸时
 * 整体降不透明度，让画在 `R.id.root` 上的壁纸透出来。这里**不能再**引入不透明背景。
 *
 * ⚠️ **派生，不存储**（不变式 8）：最近归档 / 书签 / 回收站条数都是 [showHome] 时
 * 现查现画的 —— 不缓存，用户解包完回到主界面看到的就是新的。
 */
/** 切到主界面。视图未绑时（启动期 addTab）只记状态，fragment 建视图时按它渲染。 */
internal fun MainActivity.showHome(tab: TabState) {
    if (!tab.viewsBound) {
        tab.homeVisible = true
        return
    }
    tab.homeVisible = true
    val root = tab.homeRoot ?: return
    buildHomeCards(this, root.findViewById(R.id.homeGrid), tab)
    root.visibility = View.VISIBLE
    tab.pathBar?.visibility = View.GONE
    tab.previewRoot.visibility = View.GONE
    tab.bottomBar.visibility = View.GONE
    tab.fabExtract.visibility = View.GONE
    tab.batchBar?.visibility = View.GONE
    // 不动 previewActive：hub 只是盖在上面，重建视图时预览还能恢复；
    // 真正进文件浏览器（enterBrowser → navTab）才把它作废。
}

/** 进文件浏览器（当前目录）。 */
internal fun MainActivity.enterBrowser(tab: TabState) = enterBrowserAt(tab, tab.currentDir)

/** 进文件浏览器并直接落在 [dir]（hub 的「存储根目录」卡 / 最近书签用）。 */
internal fun MainActivity.enterBrowserAt(tab: TabState, dir: File) {
    tab.homeVisible = false
    tab.homeRoot?.visibility = View.GONE
    tab.pathBar?.visibility = View.VISIBLE
    // navTab 会清掉多选 / 预览态、重启 FileObserver、刷新列表 —— 全套进浏览器。
    navTab(tab, dir)
}

// ── 卡片构建 ─────────────────────────────────────────────────────────

internal fun buildHomeCards(act: MainActivity, container: LinearLayout, tab: TabState) {
    val dm = act.resources.displayMetrics
    fun d(v: Int) = (v * dm.density).toInt()
    val wp = act.hasWallpaper()
    // 有壁纸时整体降不透明度（见类注释），让画在 R.id.root 上的壁纸透出来。
    val cardBg = if (wp) 0xB3252525.toInt() else C["surface"]!!
    val dimBg = if (wp) 0xA31F1F1F.toInt() else C["surface_dim"]!!
    val wide = act.resources.getBoolean(R.bool.is_wide)

    container.removeAllViews()
    container.orientation = LinearLayout.VERTICAL
    container.setPadding(d(22), d(18), d(22), d(24))

    // ── 命令面板：点击整条 → 全局搜索 ─────────────────────────────
    val palette = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(d(16), d(12), d(12), d(12))
        background = GradientDrawable().apply {
            cornerRadius = d(14).toFloat()
            setColor(dimBg)
            setStroke(d(1), 0x5935ACC6)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        foreground = RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, null)
        isClickable = true
        isFocusable = true
        setOnClickListener { act.globalSearch() }
    }
    palette.addView(homeIcon(act, R.drawable.ic_search, d(19), C["accent"]!!))
    palette.addView(homeText(act, act.getString(R.string.home_palette_hint), 14f, C["hint"]!!).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = d(12) }
    })
    palette.addView(homeText(act, act.getString(R.string.msg_search_global), 11f, C["accent"]!!).apply {
        background = GradientDrawable().apply { cornerRadius = d(6).toFloat(); setStroke(d(1), 0x33FFFFFF) }
        setPadding(d(8), d(3), d(8), d(3))
    })
    container.addView(palette)

    // ── 两张 hero 卡 ─────────────────────────────────────────────
    val browseTag = if (tab.currentDir == Environment.getExternalStorageDirectory()) {
        act.getString(R.string.home_storage_root)
    } else {
        act.getString(R.string.home_last_dir, tab.currentDir.name)
    }
    val heroes = homeRow(
        act,
        homeHero(act, R.drawable.ic_folder, act.getString(R.string.home_browse),
            act.getString(R.string.home_browse_desc), browseTag, false, cardBg) { act.enterBrowser(tab) },
        homeHero(act, R.drawable.ic_terminal, act.getString(R.string.home_terminal),
            act.getString(R.string.home_terminal_desc), act.getString(R.string.home_cmds_tag, UuCommands.cmdCount),
            true, cardBg) { act.terminalPanel.show() },
        gap = d(13),
    )
    container.addView(heroes, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = d(18) })

    // ── 快捷行：搜索 / 回收站 / 根目录 / 设置 ─────────────────────
    val recycleN = RecycleBin.recycleDir(act).listFiles()?.size ?: 0
    val recycleSub = if (recycleN == 0) act.getString(R.string.home_recycle_empty)
    else act.getString(R.string.home_recycle_sub, recycleN)
    val quick = homeRow(
        act,
        homeMini(act, R.drawable.ic_search, act.getString(R.string.msg_search_global),
            act.getString(R.string.home_q_search_sub), dimBg) { act.globalSearch() },
        homeMini(act, R.drawable.ic_recycle, act.getString(R.string.title_recycle_bin),
            recycleSub, dimBg) { showRecycleBinDialog(act) },
        homeMini(act, R.drawable.ic_home, act.getString(R.string.home_storage_root),
            Environment.getExternalStorageDirectory().absolutePath, dimBg) {
            act.enterBrowserAt(tab, Environment.getExternalStorageDirectory())
        },
        homeMini(act, R.drawable.ic_settings, act.getString(R.string.settings),
            act.getString(R.string.home_q_settings_sub), dimBg) { act.settings() },
        gap = d(12),
    )
    container.addView(quick, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = d(14) })

    // ── 双栏：最近打开的归档 │ 书签（窄屏上下堆叠）─────────────────
    val recents = homeRecentsColumn(act, tab, cardBg)
    val bookmarks = homeBookmarksColumn(act, tab, cardBg)
    if (wide) {
        val cols = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        cols.addView(recents, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f)
            .apply { marginEnd = d(14) })
        cols.addView(bookmarks, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(cols, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = d(20) })
    } else {
        container.addView(recents, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = d(20) })
        container.addView(bookmarks, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = d(20) })
    }

    // ── 页脚 ─────────────────────────────────────────────────────
    container.addView(homeText(
        act,
        act.getString(R.string.home_foot_fmt, ARCHIVE_EXTS.size) + " · " +
            act.getString(R.string.home_foot_local),
        11f, C["tertiary"]!!,
    ).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = d(22) }
    })
}

// ── 区块：最近打开的归档 ─────────────────────────────────────────────

private fun homeRecentsColumn(act: MainActivity, tab: TabState, cardBg: Int): LinearLayout {
    fun d(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    col.addView(homeCap(act, act.getString(R.string.home_recents), null))
    val list = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = d(14).toFloat(); setColor(cardBg); setStroke(d(1), 0x14E0F9FF)
        }
    }
    val entries = RecentsStore.list(act)
    if (entries.isEmpty()) {
        list.addView(homeText(act, act.getString(R.string.home_recents_empty), 12f, C["tertiary"]!!).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(20), 0, d(20))
        })
    } else {
        entries.forEachIndexed { i, e ->
            if (i > 0) list.addView(View(act).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, d(1),
                ).apply { marginStart = d(14); marginEnd = d(14) }
                setBackgroundColor(0x14E0F9FF)
            })
            list.addView(homeRecentRow(act, tab, e))
        }
    }
    col.addView(list)
    return col
}

private fun homeRecentRow(act: MainActivity, tab: TabState, e: RecentsStore.Entry): LinearLayout {
    fun d(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    val f = e.file
    val ext = f.extension.lowercase()
    val row = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(d(14), d(11), d(14), d(11))
        foreground = RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, null)
        isClickable = true
        isFocusable = true
        // v1 先落到归档所在目录（直接在此开预览要接一遍 openKey/互斥，后续再做）。
        setOnClickListener { f.parentFile?.let { act.enterBrowserAt(tab, it) } }
    }
    val fmtColor = FORMAT_COLORS[ext] ?: 0xFF5A5A5A.toInt()
    row.addView(homeText(act, ext.uppercase(), 10f, 0xF0FFFFFF.toInt()).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = d(7).toFloat(); setColor(fmtColor) }
        layoutParams = LinearLayout.LayoutParams(d(48), d(26))
    })
    val names = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = d(12) }
    }
    names.addView(homeText(act, f.name, 13f, C["primary"]!!, bold = true).apply {
        setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
    })
    names.addView(homeText(act, f.parent ?: "", 10.5f, C["tertiary"]!!).apply {
        setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    })
    row.addView(names)
    row.addView(homeText(
        act,
        fmt(fileSize(f)) + " · " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(e.time)),
        11f, C["tertiary"]!!,
    ))
    return row
}

// ── 区块：书签 ───────────────────────────────────────────────────────

private fun homeBookmarksColumn(act: MainActivity, tab: TabState, cardBg: Int): LinearLayout {
    fun d(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    col.addView(homeCap(act, act.getString(R.string.home_bookmarks), act.getString(R.string.home_bookmarks_empty)))
    val box = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = d(14).toFloat(); setColor(cardBg); setStroke(d(1), 0x14E0F9FF)
        }
        setPadding(d(6), d(6), d(6), d(6))
    }
    val bookmarks = act.bookmarks
    if (bookmarks.isEmpty()) {
        box.addView(homeText(act, act.getString(R.string.home_bookmarks_empty), 12f, C["tertiary"]!!).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(16), 0, d(16))
        })
    } else {
        bookmarks.forEach { path ->
            box.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(12), d(10), d(12), d(10))
                foreground = RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, null)
                isClickable = true
                isFocusable = true
                setOnClickListener { act.enterBrowserAt(tab, File(path)) }
                addView(homeIcon(act, R.drawable.ic_folder, d(15), C["accent"]!!))
                addView(homeText(act, File(path).name.ifEmpty { path }, 12.5f, C["secondary"]!!).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = d(10) }
                    setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
            })
        }
    }
    col.addView(box)
    return col
}

// ── 小构件 ───────────────────────────────────────────────────────────

/** 「标题 …… 尾注」的区块头。 */
private fun homeCap(act: MainActivity, title: String, tail: String?): LinearLayout {
    fun d(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    val cap = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.BOTTOM
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(d(2), 0, d(2), d(10)) }
    }
    cap.addView(homeText(act, title, 11f, C["tertiary"]!!).apply { letterSpacing = 0.12f })
    if (tail != null) {
        cap.addView(homeText(act, tail, 11f, C["tertiary"]!!).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = d(10)
            }
            gravity = Gravity.END
        })
    }
    return cap
}

/** 一行 n 个等重子项，[gap] 为间距（最后一个不加）。 */
private fun homeRow(act: MainActivity, vararg cells: View, gap: Int): LinearLayout {
    fun d(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    cells.forEachIndexed { i, v ->
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        if (i < cells.size - 1) lp.marginEnd = d(gap)
        row.addView(v, lp)
    }
    return row
}

/** hero 卡：图标盒 + 标题 + 描述 + 右上角标。 */
private fun homeHero(
    act: MainActivity,
    iconRes: Int,
    title: String,
    desc: String,
    tag: String?,
    teal: Boolean,
    fallbackBg: Int,
    onClick: () -> Unit,
): View {
    fun d(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    val wp = act.hasWallpaper()
    val bg = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (teal) intArrayOf(0xFF1F3A41.toInt(), fallbackBg)
        else intArrayOf(0xFF2E3D42.toInt(), fallbackBg),
    ).apply {
        cornerRadius = d(17).toFloat()
        if (wp) alpha = 216 // 壁纸模式下整卡再让一点
    }
    val content = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(d(20), d(20), d(20), d(20))
        addView(homeIcon(act, iconRes, d(24), C["accent"]!!).apply {
            background = GradientDrawable().apply {
                cornerRadius = d(14).toFloat(); setColor(0x2E35ACC6)
            }
            layoutParams = FrameLayout.LayoutParams(d(46), d(46))
        })
        addView(homeText(act, title, 17f, C["primary"]!!, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = d(14) }
        })
        addView(homeText(act, desc, 12f, C["secondary"]!!).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = d(6) }
        })
    }
    return FrameLayout(act).apply {
        background = bg
        foreground = RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, null)
        isClickable = true
        isFocusable = true
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(content)
        if (tag != null) {
            addView(homeText(act, tag, 10f, C["accent"]!!).apply {
                background = GradientDrawable().apply {
                    cornerRadius = d(99).toFloat(); setColor(0x2E35ACC6)
                }
                setPadding(d(9), d(4), d(9), d(4))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ).apply { marginEnd = d(14); topMargin = d(14) }
            })
        }
        setOnClickListener { onClick() }
    }
}

/** 快捷入口小卡：图标盒 + 标题 / 一行说明。 */
private fun homeMini(
    act: MainActivity,
    iconRes: Int,
    title: String,
    sub: String,
    bg: Int,
    onClick: () -> Unit,
): LinearLayout {
    fun d(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    return LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(d(14), d(13), d(12), d(13))
        background = GradientDrawable().apply {
            cornerRadius = d(14).toFloat(); setColor(bg); setStroke(d(1), 0x14E0F9FF)
        }
        foreground = RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, null)
        isClickable = true
        isFocusable = true
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(homeIcon(act, iconRes, d(19), C["accent"]!!).apply {
            background = GradientDrawable().apply {
                cornerRadius = d(11).toFloat(); setColor(0x2E35ACC6)
            }
            layoutParams = LinearLayout.LayoutParams(d(38), d(38)).apply { gravity = Gravity.CENTER }
        })
        val names = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        names.addView(homeText(act, title, 13.5f, C["primary"]!!, bold = true))
        names.addView(homeText(act, sub, 10.5f, C["tertiary"]!!).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = d(2) }
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = d(12) })
        setOnClickListener { onClick() }
    }
}

private fun homeIcon(act: MainActivity, res: Int, sizePx: Int, tint: Int): ImageView =
    ImageView(act).apply {
        setImageResource(res)
        setColorFilter(tint)
        layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
    }

private fun homeText(
    act: MainActivity,
    text: String,
    sp: Float,
    color: Int,
    bold: Boolean = false,
): TextView = TextView(act).apply {
    this.text = text
    textSize = sp
    setTextColor(color)
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
}

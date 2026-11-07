package com.usefulunpacker

import android.app.AlertDialog
import android.graphics.drawable.ColorDrawable
import android.os.Environment
import android.os.StatFs
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.usefulunpacker.fileops.DiskNode
import com.usefulunpacker.fileops.DiskScan
import com.usefulunpacker.fileops.DiskScanner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 「磁盘查询」—— WizTree 那类空间分析功能的 GUI。
 *
 * ## 两个入口
 * - **主页**：快捷入口下面那条「磁盘占用」横条（`browse/HomeHub.kt` 的 `diskUsageBar`），
 *   条上直接写着「已用 X / 共 Y」，点进来自动扫存储根目录。
 * - **设置**：「磁盘查询」菜单项，先弹目录选择，扫指定的目录。
 *
 * ## 为什么是「容量条 + 列表」而不是 WizTree 的矩形树图
 * 矩形树图（treemap）在手机上手指点不中、字也塞不下，而它的全部价值是「一眼看出谁大
 * 谁小」—— 这件事一条按比例分段的容量条 + 一个按大小降序、每行带细条形的列表就能做到，
 * 代码量还只有前者的零头。真要做 treemap 是后续可选项（`TODO.md` 里记过）。
 *
 * ## 钻取靠内存里的树，不重扫
 * 扫描时建的是整棵**目录树**（`fileops/DiskUsage.kt`），所以点进子目录只是换一个节点
 * 渲染 —— 再扫一遍是分钟级的浪费。
 *
 * ## 长任务的纪律（与批量解压同一条）
 * 扫描框**不要护盾**（`blockBehind = false`）：扫一分钟的时间里用户照样能翻目录。
 * 取消按钮置位 `AtomicBoolean`，扫描器每个文件、每个目录都查一次。
 */
internal fun MainActivity.showDiskUsage(startDir: File = Environment.getExternalStorageDirectory()) {
    val act = this
    val density = resources.displayMetrics.density
    fun px(v: Int) = (v * density).toInt()

    val cancelled = AtomicBoolean(false)

    val tvPath = textView(act, startDir.absolutePath, 11.5f, C["tertiary"]!!).apply {
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.MIDDLE
    }
    val tvProgress = textView(
        act, act.getString(R.string.disk_scanning, "0", fmt(0L)), 13f, C["secondary"]!!,
    )
    val bar = android.widget.ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
    }
    val body = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(16), px(20), px(4))
        addView(tvPath, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(tvProgress, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(10) })
        addView(bar, LinearLayout.LayoutParams(MATCH, px(4)).apply { topMargin = px(12) })
    }

    val dlg = AlertDialog.Builder(act)
        .setTitle(getString(R.string.disk_usage))
        .setView(body)
        .setNegativeButton(getString(R.string.action_cancel), null)
        .create()
    // ⚠️ 取消只能从 `also` 进来：`setOnDismissListener` 只有一个槽位，
    // `keepTabBarTappable` 里 `resetPagerInputOnDialogDismiss` 已经占用了它，
    // 再自己设一次就会把「关掉弹窗后 ViewPager 还能滑」那条修复顶掉（真机踩过两次）。
    dlg.keepTabsTappable(rootToTab = false, blockBehind = false) { cancelled.set(true) }
    dlg.show()

    thread {
        val scanner = DiskScanner(
            topN = DISK_TOP_FILES,
            onProgress = { n, b ->
                act.runOnUiThread {
                    if (!cancelled.get() && dlg.isShowing) {
                        tvProgress.text = act.getString(R.string.disk_scanning, n.toString(), fmt(b))
                    }
                }
            },
            isCancelled = { cancelled.get() },
        )
        val scan = scanner.scan(startDir)
        act.runOnUiThread {
            if (act.isFinishing || act.isDestroyed) return@runOnUiThread
            if (!dlg.isShowing) return@runOnUiThread
            dlg.dismiss()
            if (scan == null) act.toast(getString(R.string.disk_cancelled))
            else showDiskResult(act, scan)
        }
    }
}

/** 外部存储卷的 `(已用, 总量)`。StatFs 是系统调用，微秒级，主线程直接调。 */
internal fun externalStorageUsage(): Pair<Long, Long> = try {
    val sf = StatFs(Environment.getExternalStorageDirectory().absolutePath)
    val total = sf.totalBytes
    (total - sf.availableBytes) to total
} catch (_: Exception) {
    0L to 0L
}

// ── 结果对话框 ───────────────────────────────────────────────────────

/** 容量条最多画几段（再多就分不清了，剩下的归「其他」）。 */
private const val DISK_SEGMENTS = 6

/** 全局保留几个最大文件（钻到任何一层都能看到整棵树里最大的那些）。 */
private const val DISK_TOP_FILES = 100

/**
 * 分段配色 —— 深色底上彼此可分辨，第一段跟主色走。
 * 与 `FORMAT_COLORS` 同一套做法：这里是一组**视觉**常量，不进 `colors.xml` 的 token 表。
 */
private val DISK_SEGMENT_COLORS = intArrayOf(
    0xFF35ACC6.toInt(),
    0xFF4F8FD6.toInt(),
    0xFF6FA85B.toInt(),
    0xFFC9A227.toInt(),
    0xFFC4643C.toInt(),
    0xFF8E6FA8.toInt(),
)

/** 「其他」那一段：中性灰，别抢前几名的注意力。 */
private const val DISK_REST_COLOR = 0xFF4A4A4A.toInt()

private sealed class DiskRow {
    class Dir(val node: DiskNode) : DiskRow()
    class FileRow(val size: Long, val file: File) : DiskRow()
}

private class DiskRowAdapter(
    private val act: MainActivity,
    /**
     * 紧凑行（宽屏双栏用）：目录行只报大小、不报文件数。
     *
     * 双栏里每栏只有 ~240dp，右边那条「共 36.4 GB，42 个文件」会吃掉一半宽度、
     * 把名字挤成三四个字。比例关系已经由上面的容量条负责，这里只留最要紧的数字。
     */
    private val compact: Boolean,
    private val onDir: (DiskNode) -> Unit,
) : BaseAdapter() {

    private var rows: List<DiskRow> = emptyList()
    private var maxBytes = 1L
    private val density = act.resources.displayMetrics.density
    private fun px(v: Int) = (v * density).toInt()

    fun submit(rows: List<DiskRow>, maxBytes: Long) {
        this.rows = rows
        this.maxBytes = maxBytes.coerceAtLeast(1L)
    }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()

    /**
     * 不复用 `convertView`：行是两种形态（目录 / 文件）拼出来的，复用得先做类型判定再
     * 重绑一堆子 view —— 而这层列表最多几百行，省下来的分配不值得那份复杂度。
     */
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val row = rows[position]
        val isDir = row is DiskRow.Dir
        val name: String
        val bytes: Long
        val sub: String
        when (row) {
            is DiskRow.Dir -> {
                name = row.node.dir.name.ifEmpty { row.node.dir.absolutePath }
                bytes = row.node.bytes
                sub = if (compact) fmt(bytes) else
                    act.getString(R.string.cli_du_result, fmt(bytes), row.node.files.toString())
            }
            is DiskRow.FileRow -> {
                name = row.file.name
                bytes = row.size
                // 文件行不重复报「1 个文件」，只给大小。
                sub = fmt(bytes)
            }
        }

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            if (compact) setPadding(px(12), px(7), px(12), px(8))
            else setPadding(px(14), px(9), px(14), px(10))
        }
        val line = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(
            iconView(
                act, if (isDir) R.drawable.ic_folder else R.drawable.ic_file, px(15),
                if (isDir) C["warning"]!! else C["icon_gray_dim"]!!,
            )
        )
        line.addView(textView(act, name, 13f, C["primary"]!!, bold = isDir).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(9) })
        line.addView(textView(act, sub, 11f, C["tertiary"]!!).apply {
            setSingleLine(true)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = px(8) })
        box.addView(line, LinearLayout.LayoutParams(MATCH, WRAP))

        // 细条形：相对**同层最大的那个**，而不是相对总量 —— 一屏里 5% 和 0.3% 若按
        // 「相对总量」画，都是看不见的一条；相对同层才分得出主次。
        val filled = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        filled.addView(View(act).apply {
            setBackgroundColor(if (isDir) C["accent"]!! else C["icon_gray_dim"]!!)
        }, LinearLayout.LayoutParams(0, MATCH, bytes.coerceAtLeast(1L).toFloat()))
        val rest = maxBytes - bytes
        if (rest > 0) {
            filled.addView(View(act), LinearLayout.LayoutParams(0, MATCH, rest.toFloat()))
        }
        val barFrame = FrameLayout(act).apply {
            background = roundedBg(px(2), C["surface_dark"]!!)
            clipToOutline = true
            addView(filled, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        box.addView(barFrame, LinearLayout.LayoutParams(MATCH, px(3)).apply { topMargin = px(6) })

        if (isDir) {
            box.foreground = ripple(C["ripple"]!!)
            box.isClickable = true
            box.isFocusable = true
            box.setOnClickListener { onDir((row as DiskRow.Dir).node) }
            box.mergeIntoA11yNode("$name，$sub")
        } else {
            // 文件行点了没有下一步（打开 / 定位不在这一版范围里），不做成可点。
            box.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            box.contentDescription = "$name，${fmt(bytes)}"
        }
        return box
    }
}

/** 宽屏双栏里的一栏：小标题 + 列表（列表吃满剩余高度）。 */
private fun diskColumn(act: MainActivity, title: String, pane: View): LinearLayout {
    val density = act.resources.displayMetrics.density
    fun px(v: Int) = (v * density).toInt()
    return LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            textView(act, title, 11f, C["tertiary"]!!).apply { letterSpacing = 0.12f },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(px(14), px(8), px(14), px(6)) },
        )
        addView(pane, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }
}

private fun showDiskResult(act: MainActivity, scan: DiskScan) {
    val density = act.resources.displayMetrics.density
    fun px(v: Int) = (v * density).toInt()

    // 横竖两套骨架，判据与主页**同一个**（`isRoomy`）：两处不一致就会出现
    // 「主页是宽屏骨架、点开对话框却是竖屏骨架」的错位。
    val wide = isRoomy(act)

    /** 钻取栈：栈顶就是当前看的目录。用栈而不是给节点存 parent —— 扫描器不必为 UI 记账。 */
    val stack = ArrayDeque<DiskNode>().apply { addLast(scan.root) }

    val tvPath = textView(act, "", 12f, C["primary"]!!).apply {
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.MIDDLE
    }
    val tvTotal = textView(act, "", 11.5f, C["tertiary"]!!)
    val btnPick = Button(act).apply {
        text = act.getString(R.string.disk_pick_dir)
        setTextColor(C["accent"]!!)
        background = null
        textSize = 11.5f
        setPadding(px(6), 0, px(6), 0)
        minWidth = 0
        minimumWidth = 0
    }

    val pathRow = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    pathRow.addView(iconView(act, R.drawable.ic_disk, px(15), C["accent"]!!))
    pathRow.addView(tvPath, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(8) })
    pathRow.addView(btnPick, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = px(4) })

    val barHolder = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    val barFrame = FrameLayout(act).apply {
        background = roundedBg(px(4), C["surface_dark"]!!)
        clipToOutline = true
        addView(barHolder, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    val header = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(14), px(if (wide) 10 else 12), px(14), px(if (wide) 8 else 10))
        addView(pathRow, LinearLayout.LayoutParams(MATCH, WRAP))
        if (wide) {
            // 宽屏：容量条和总量挤进同一行。横屏那张卡是 480dp × 可用高，比竖屏的贴底
            // 大窗矮一截，竖向能省一行就省一行 —— 省下来的正好多显示一行目录。
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(barFrame, LinearLayout.LayoutParams(0, px(7), 1f))
            row.addView(tvTotal, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = px(12) })
            addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) })
        } else {
            addView(tvTotal, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(5) })
            addView(barFrame, LinearLayout.LayoutParams(MATCH, px(9)).apply { topMargin = px(8) })
        }
    }

    // 点击回调要能在 `render` 定义之后才填（局部函数不能先引用后声明）——
    // 用一个可变的 lambda 做中转，adapter 里读它。
    var onDirClick: (DiskNode) -> Unit = {}
    // 竖屏：**一个**列表，目录在前、本目录的文件在后 —— 一条道滚到底，符合竖屏的阅读习惯。
    // 宽屏：**两个**列表并排。横屏的毛病是「把一屏目录滚完才见到文件」，而横屏本来就
    // 横向富裕，分成两栏才用得着那份宽度。
    val dirAdapter = DiskRowAdapter(act, compact = wide) { node -> onDirClick(node) }
    val fileAdapter = DiskRowAdapter(act, compact = true) { }

    /** 列表 + 空态叠一层（互斥显示）。 */
    fun makePane(): Triple<FrameLayout, ListView, TextView> {
        val tv = TextView(act).apply {
            setTextColor(C["hint"]!!)
            textSize = 12f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val lv = ListView(act).apply {
            divider = ColorDrawable(C["hairline"]!!)
            dividerHeight = px(1)
            setPadding(0, 0, 0, 0)
            clipToPadding = false
        }
        val frame = FrameLayout(act).apply {
            addView(lv, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(tv, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        }
        return Triple(frame, lv, tv)
    }

    val dirPane = makePane().also { it.second.adapter = dirAdapter }
    val filePane = makePane().also { it.second.adapter = fileAdapter }

    val body: View = if (wide) {
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                diskColumn(act, act.getString(R.string.disk_dirs), dirPane.first),
                LinearLayout.LayoutParams(0, MATCH, 1.6f).apply { marginEnd = px(8) },
            )
            addView(
                diskColumn(act, act.getString(R.string.disk_big_files), filePane.first),
                LinearLayout.LayoutParams(0, MATCH, 1f),
            )
        }
    } else {
        dirPane.first
    }

    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(
            View(act).apply { setBackgroundColor(C["hairline"]!!) },
            LinearLayout.LayoutParams(MATCH, px(1)),
        )
        addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    var dlg: AlertDialog? = null

    /** 把一栏喂满：换行、切空态、切列表可见性。 */
    fun fill(
        adapter: DiskRowAdapter,
        rows: List<DiskRow>,
        maxBytes: Long,
        pane: Triple<FrameLayout, ListView, TextView>,
        emptyRes: Int,
    ) {
        adapter.submit(rows, maxBytes)
        adapter.notifyDataSetChanged()
        pane.third.text = act.getString(emptyRes)
        pane.third.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        pane.second.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    fun render(node: DiskNode) {
        tvPath.text = node.dir.absolutePath
        tvTotal.text = act.getString(R.string.cli_du_result, fmt(node.bytes), node.files.toString())

        // ── 容量条：前 DISK_SEGMENTS 个子目录按比例分段，剩下的归「其他」 ──
        barHolder.removeAllViews()
        val top = node.dirs.take(DISK_SEGMENTS)
        top.forEachIndexed { i, child ->
            barHolder.addView(
                View(act).apply {
                    setBackgroundColor(DISK_SEGMENT_COLORS[i % DISK_SEGMENT_COLORS.size])
                },
                LinearLayout.LayoutParams(0, MATCH, child.bytes.coerceAtLeast(1L).toFloat()),
            )
        }
        val rest = node.bytes - top.sumOf { it.bytes }
        if (rest > 0) {
            barHolder.addView(
                View(act).apply { setBackgroundColor(DISK_REST_COLOR) },
                LinearLayout.LayoutParams(0, MATCH, rest.toFloat()),
            )
        }

        // ── 列表 ──
        val dirRows = node.dirs.map { DiskRow.Dir(it) }
        val fileRows = node.topFiles.map { DiskRow.FileRow(it.first, it.second) }
        if (wide) {
            // 两栏各自按**本栏最大**归一：跨栏比大小没意义（左边是目录总量，右边是单文件）。
            fill(
                dirAdapter, dirRows, dirRows.maxOfOrNull { it.node.bytes } ?: 0L,
                dirPane, R.string.disk_no_subdirs,
            )
            fill(
                fileAdapter, fileRows, fileRows.maxOfOrNull { it.size } ?: 0L,
                filePane, R.string.disk_no_files,
            )
        } else {
            // 竖屏一个列表：两类行共用同一把尺子（同层最大），条形才可比。
            val all = dirRows + fileRows
            val maxB = maxOf(
                dirRows.maxOfOrNull { it.node.bytes } ?: 0L,
                fileRows.maxOfOrNull { it.size } ?: 0L,
            )
            fill(dirAdapter, all, maxB, dirPane, R.string.disk_empty)
        }
        dlg?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = stack.size > 1
    }

    onDirClick = { node ->
        stack.addLast(node)
        render(node)
    }

    btnPick.setOnClickListener {
        // 换目录要重扫：先关掉当前结果，再从选择器回调里开一轮新的。
        showFolderPicker(act, stack.last().dir) { picked ->
            dlg?.dismiss()
            act.showDiskUsage(picked)
        }
    }

    val dialog = AlertDialog.Builder(act)
        .setTitle(act.getString(R.string.disk_usage))
        .setView(root)
        .setPositiveButton(act.getString(R.string.action_close), null)
        .setNeutralButton(act.getString(R.string.disk_up), null)
        .create()
    dlg = dialog
    // 中性按钮默认点完就关窗；「上一级」要的是就地重画，所以自己接管。
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            if (stack.size > 1) {
                stack.removeLast()
                render(stack.last())
            }
        }
    }
    dialog.belowTabs(fitContent = false, rootToTab = false)
    dialog.show()
    render(scan.root)
}

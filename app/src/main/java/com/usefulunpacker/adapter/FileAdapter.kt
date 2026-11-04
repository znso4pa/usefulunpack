package com.usefulunpacker

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FileAdapter(
    context: Context,
    private val files: List<File>,
    private val bookmarks: List<String>,
    private val onBookmarkToggled: (String) -> Unit,
    private val df: SimpleDateFormat
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    var multiSelected_: Set<File> = emptySet()
    var passwordProtected: Set<String> = emptySet()

    /**
     * 「刚被定位到」的那一行（全局搜索点结果后滚过去的那一条）。
     *
     * 只做视觉提示：搜索框收起、列表滚到目标之后，用户得知道**是哪一行** —— 否则只是
     * 「跳到某个目录」，还得自己找。1.6s 后自动褪掉，不留状态。
     */
    var highlightPath: String? = null
        private set
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingClear: Runnable? = null

    /** 高亮 [path] 那一行并安排褪色（重复调用只保留最后一次）。 */
    fun flashLocated(path: String) {
        highlightPath = path
        notifyDataSetChanged()
        pendingClear?.let { mainHandler.removeCallbacks(it) }
        val clear = Runnable {
            highlightPath = null
            notifyDataSetChanged()
        }
        pendingClear = clear
        mainHandler.postDelayed(clear, LOCATE_FLASH_MS)
    }

    override fun getCount() = files.size
    override fun getItem(pos: Int) = files[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    /** 当前列表的文件路径（顺序即显示顺序）—— navTab 用它判断「这次刷新列表变没变」。 */
    fun paths(): List<String> = files.map { it.absolutePath }

    /** 第 [pos] 项的文件；越界返回 null（滚动锚点用）。 */
    fun itemAt(pos: Int): File? = files.getOrNull(pos)

    override fun getView(pos: Int, v: View?, p: ViewGroup?): View {
        val view = v ?: inflater.inflate(R.layout.item_file, p, false)
        val f = files[pos]
        val cb = view.findViewById<CheckBox>(R.id.checkbox)
        // 行底色优先级：多选 > 搜索定位高亮 > 透明。
        // 多选最高 —— 定位高亮只是提示「是哪一行」，不能盖掉用户已选中的状态。
        val located = f.absolutePath == highlightPath
        if (multiSelected_.isNotEmpty()) {
            cb.visibility = View.VISIBLE; cb.isChecked = f in multiSelected_
            cb.isClickable = false; cb.isFocusable = false
            if (f in multiSelected_) view.setBackgroundColor(BG_SELECTED)
            else view.setBackgroundColor(if (located) BG_LOCATED else 0)
        } else {
            cb.visibility = View.GONE
            view.setBackgroundColor(if (located) BG_LOCATED else 0)
        }
        val icon = view.findViewById<ImageView>(R.id.icon)
        val label = view.findViewById<TextView>(R.id.label)
        val size = view.findViewById<TextView>(R.id.info_size)
        val date = view.findViewById<TextView>(R.id.info_date)

        val starBtn = view.findViewById<ImageView>(R.id.btnStar)
        if (f.isDirectory) {
            starBtn.visibility = View.VISIBLE
            val bm = bookmarks.contains(f.absolutePath)
            starBtn.setImageResource(if (bm) R.drawable.ic_star_on else R.drawable.ic_star_off)
            starBtn.setColorFilter(if (bm) 0xFFffc107.toInt() else 0xFF666666.toInt())
            // 星标是个**真控件**（独立可点），必须自带描述，否则 TalkBack 只会念
            // 「未标记的按钮」——描述还要跟着选中态走（加入 / 取消）。
            starBtn.contentDescription =
                view.context.getString(if (bm) R.string.a11y_bm_remove else R.string.a11y_bm_add)
            starBtn.setOnClickListener {
                onBookmarkToggled(f.absolutePath)
                notifyDataSetChanged()
            }
            icon.setImageResource(R.drawable.ic_folder); icon.setColorFilter(C["warning"]!!)
            icon.contentDescription = view.context.getString(R.string.a11y_type_folder)
            label.text = f.name; size.text = ""; date.text = ""
        } else {
            starBtn.visibility = View.GONE
            val isApk = f.name.lowercase().endsWith(".apk")
            val isArc = isArchiveFile(f)
            icon.setImageResource(if (isApk || isArc) R.drawable.ic_archive else R.drawable.ic_file)
            icon.setColorFilter(if (isApk) 0xFF1976D2.toInt() else iconTintFor(f))
            // 行图标是「徽标」——名字本身不含类型信息时（如 .png_），这一句才是类型的唯一来源。
            icon.contentDescription = view.context.getString(
                if (isApk || isArc) R.string.a11y_type_archive else R.string.a11y_type_file,
            )
            label.text = f.name
            size.text = (if (f.absolutePath in passwordProtected) "🔒 " else "") + fmt(fileSize(f))
            date.text = df.format(Date(f.lastModified()))
        }
        return view
    }

    private companion object {
        /** 多选行底（accent 20%）。 */
        const val BG_SELECTED = 0x3335acc6
        /** 「刚定位到」的行底（accent 25%），比多选淡一档。 */
        const val BG_LOCATED = 0x4035ACC6
        /** 高亮停留时长：够看清是哪一行，又不至于以为它被选中了。 */
        const val LOCATE_FLASH_MS = 1600L
    }
}

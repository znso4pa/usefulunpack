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

    override fun getCount() = files.size
    override fun getItem(pos: Int) = files[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, v: View?, p: ViewGroup?): View {
        val view = v ?: inflater.inflate(R.layout.item_file, p, false)
        val f = files[pos]
        val cb = view.findViewById<CheckBox>(R.id.checkbox)
        if (multiSelected_.isNotEmpty()) {
            cb.visibility = View.VISIBLE; cb.isChecked = f in multiSelected_
            cb.isClickable = false; cb.isFocusable = false
            if (f in multiSelected_) view.setBackgroundColor(0x4035acc6.toInt())
            else view.setBackgroundColor(0x00000000.toInt())
        } else {
            cb.visibility = View.GONE
            view.setBackgroundColor(0x00000000.toInt())
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
            starBtn.setOnClickListener {
                onBookmarkToggled(f.absolutePath)
                notifyDataSetChanged()
            }
            icon.setImageResource(R.drawable.ic_folder); icon.setColorFilter(C["warning"]!!)
            label.text = f.name; size.text = ""; date.text = ""
        } else {
            starBtn.visibility = View.GONE
            val isApk = f.name.lowercase().endsWith(".apk")
            val isArc = isArchiveFile(f)
            icon.setImageResource(if (isApk || isArc) R.drawable.ic_archive else R.drawable.ic_file)
            icon.setColorFilter(if (isApk) 0xFF1976D2.toInt() else iconTintFor(f))
            label.text = f.name
            size.text = (if (f.absolutePath in passwordProtected) "🔒 " else "") + fmt(fileSize(f))
            date.text = df.format(Date(f.lastModified()))
        }
        return view
    }
}

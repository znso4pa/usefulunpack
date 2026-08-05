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
            starBtn.setImageResource(if (bm) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
            starBtn.setColorFilter(if (bm) 0xFFffc107.toInt() else 0xFF666666.toInt())
            starBtn.setOnClickListener {
                onBookmarkToggled(f.absolutePath)
                notifyDataSetChanged()
            }
            icon.setImageResource(android.R.drawable.ic_menu_compass); icon.setColorFilter(C["warning"]!!)
            label.text = f.name; size.text = ""; date.text = ""
        } else {
            starBtn.visibility = View.GONE
            val n = f.name.lowercase()
            val res = when { n.endsWith(".xp3")||n.endsWith(".pfs") -> android.R.drawable.ic_menu_compass; n.endsWith(".apk") -> android.R.drawable.ic_menu_manage; else -> android.R.drawable.ic_menu_gallery }
            icon.setImageResource(res); icon.setColorFilter(C["primary"]!!)
            label.text = f.name; size.text = fmt(fileSize(f)); date.text = df.format(Date(f.lastModified()))
        }
        return view
    }
}

package com.usefulunpacker

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView

class PreviewAdapter(
    context: Context,
    private val entries: List<ArchiveEntry>,
    private val selectedPaths: MutableSet<String>,
    private val expandedPaths: MutableSet<String>,
    private val onFileClick: (ArchiveEntry) -> Unit = {},
    private val onSelectionChanged: () -> Unit = {}
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private val density = context.resources.displayMetrics.density

    var searchQuery: String = ""
        set(value) {
            field = value; rebuildVisible(); notifyDataSetChanged()
        }

    private var visible: List<ArchiveEntry> = entries.filter { isVisible(it) }

    private fun isVisible(e: ArchiveEntry): Boolean {
        if (searchQuery.isNotEmpty()) {
            return e.path.lowercase().contains(searchQuery.lowercase()) ||
                   e.name.lowercase().contains(searchQuery.lowercase())
        }
        val parts = e.path.split('/')
        for (i in 1 until parts.size) {
            val dirPath = parts.take(i).joinToString("/")
            if (dirPath.isNotEmpty() && !expandedPaths.contains(dirPath)) {
                return false
            }
        }
        return true
    }

    private fun rebuildVisible() {
        visible = entries.filter { isVisible(it) }
    }

    override fun getCount(): Int {
        rebuildVisible()
        return visible.size
    }

    override fun getItem(pos: Int) = visible.getOrNull(pos)
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, v: View?, p: ViewGroup?): View {
        val view = v ?: inflater.inflate(R.layout.item_preview, p, false)
        val entry = visible[pos]
        val checkbox = view.findViewById<CheckBox>(R.id.checkbox)
        val icon = view.findViewById<ImageView>(R.id.icon)
        val label = view.findViewById<TextView>(R.id.label)
        val size = view.findViewById<TextView>(R.id.info_size)

        val indentPx = (minOf(entry.depth, 10) * 24 * density).toInt()
        val baseStart = (4 * density).toInt()
        view.setPadding(baseStart + indentPx, 0, (8 * density).toInt(), 0)

        checkbox.setOnCheckedChangeListener(null)
        if (entry.isDirectory) {
            checkbox.isClickable = true
            checkbox.isFocusable = true
            checkbox.isChecked = selectedPaths.contains(entry.path)
            checkbox.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedPaths.add(entry.path)
                else selectedPaths.remove(entry.path)
                val prefix = "${entry.path}/"
                for (e in entries) {
                    if (e.path.startsWith(prefix)) {
                        if (checked) selectedPaths.add(e.path) else selectedPaths.remove(e.path)
                    }
                }
                onSelectionChanged(); notifyDataSetChanged()
            }
        } else {
            checkbox.isClickable = true
            checkbox.isFocusable = true
            checkbox.isChecked = selectedPaths.contains(entry.path)
            checkbox.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedPaths.add(entry.path) else selectedPaths.remove(entry.path)
                onSelectionChanged(); notifyDataSetChanged()
            }
        }

        if (entry.isDirectory) {
            icon.setImageResource(android.R.drawable.ic_menu_compass)
            icon.setColorFilter(C["warning"]!!)
            val toggle = View.OnClickListener {
                if (expandedPaths.contains(entry.path)) expandedPaths.remove(entry.path)
                else expandedPaths.add(entry.path)
                notifyDataSetChanged()
            }
            icon.setOnClickListener(toggle)
            label.setOnClickListener(toggle)
            val arrow = if (expandedPaths.contains(entry.path)) "▼ " else "▶ "
            label.text = "$arrow${entry.name}"
        } else {
            val ext = entry.path.lowercase()
            val res = when {
                ext.endsWith(".xp3") || ext.endsWith(".pfs") -> android.R.drawable.ic_menu_compass
                else -> android.R.drawable.ic_menu_gallery
            }
            icon.setImageResource(res)
            icon.setColorFilter(C["primary"]!!)
            label.text = entry.name
            val click = View.OnClickListener { onFileClick(entry) }
            icon.setOnClickListener(click)
            label.setOnClickListener(click)
        }

        size.text = if (entry.isDirectory) "" else fmt(entry.size)
        if (entry.isEncrypted) {
            size.text = "🔒 ${size.text}"
        }

        return view
    }
}

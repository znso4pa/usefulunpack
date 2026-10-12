package com.usefulunpacker.fileops

import android.app.AlertDialog
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.usefulunpacker.C
import com.usefulunpacker.formatOfName
import com.usefulunpacker.iconTintForName
import com.usefulunpacker.keepTabsTappable
import com.usefulunpacker.belowTabs
import com.usefulunpacker.MainActivity
import com.usefulunpacker.R
import com.usefulunpacker.fmt
import com.usefulunpacker.nav
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

private class RecycleEntryAdapter(
    private val activity: MainActivity,
    private var entries: MutableList<RecycleEntry>,
    private val tvHeader: TextView,
    private val onListEmpty: () -> Unit = {}
) : BaseAdapter() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun updateEntries(newEntries: List<RecycleEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
        tvHeader.text = activity.getString(R.string.recycle_items, entries.size, fmt(entries.sumOf { it.size }))
        if (entries.isEmpty()) {
            onListEmpty()
        }
    }

    override fun getCount() = entries.size
    override fun getItem(position: Int) = entries[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_recycle_entry, parent, false)
        val entry = entries[position]

        // 图标盒：目录用文件夹色，归档按格式着色（与文件浏览器同一张表），其余中性色。
        // 原来这一列根本没有 —— 三行文字直接贴到左边的按钮上，行内没有视觉锚点。
        view.findViewById<ImageView>(R.id.recycle_icon).apply {
            setImageResource(if (entry.isDirectory) R.drawable.ic_folder else R.drawable.ic_file)
            setColorFilter(
                when {
                    entry.isDirectory -> C["warning"]!!
                    formatOfName(entry.originalName) != null -> iconTintForName(entry.originalName)
                    else -> C["secondary"]!!
                }
            )
        }

        view.findViewById<TextView>(R.id.recycle_name).text = entry.originalName
        view.findViewById<TextView>(R.id.recycle_path).text = entry.originalPath.parent ?: ""
        view.findViewById<TextView>(R.id.recycle_date).text = dateFormat.format(Date(entry.deletedAt))
        view.findViewById<TextView>(R.id.recycle_size).text = fmt(entry.size)

        view.findViewById<Button>(R.id.btnRestore).setOnClickListener {
            thread {
                val success = RecycleBin.restore(activity, entry.id)
                // Re-listing reads every _meta.json — do it on the worker, not
                // the UI thread; then guard the follow-up UI against a dead
                // activity (empty-bin dialog would crash with BadTokenException).
                val updatedEntries = RecycleBin.listEntries(activity)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (success) {
                        Toast.makeText(activity, activity.getString(R.string.action_restore), Toast.LENGTH_SHORT).show()
                        activity.nav(activity.currentDir)
                    } else {
                        Toast.makeText(activity, activity.getString(R.string.msg_restore_failed, 1), Toast.LENGTH_SHORT).show()
                    }
                    updateEntries(updatedEntries)
                    if (updatedEntries.isEmpty()) showEmptyBinDialog(activity)
                }
            }
        }

        view.findViewById<Button>(R.id.btnPermanentDelete).setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.recycle_permanent_delete))
                .setMessage(entry.originalName)
                .setPositiveButton(activity.getString(R.string.action_delete)) { _, _ ->
                    thread {
                        val dir = RecycleBin.recycleDir(activity)
                        java.io.File(dir, entry.id).deleteRecursively()
                        RecycleBin.removeFromManifest(activity, entry.id)
                        val updatedEntries = RecycleBin.listEntries(activity)
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            updateEntries(updatedEntries)
                            if (updatedEntries.isEmpty()) showEmptyBinDialog(activity)
                        }
                    }
                }
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .show().also { it.keepTabsTappable(rootToTab = false) }
        }

        return view
    }
}

/**
 * 空回收站：居中的图标 + 一句「删掉的东西会先放这儿」。
 *
 * 原来三处空态都是 `.setTitle(回收站).setMessage(回收站为空)` —— 一个只有两行字的方框，
 * 既看不出这是「空」还是「出错」，也不知道这东西有什么用。
 */
private fun showEmptyBinDialog(activity: MainActivity) {
    val d = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(30), dp(26), dp(30), dp(6))
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_recycle)
            setColorFilter(C["tertiary"]!!)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(TextView(activity).apply {
            text = activity.getString(R.string.recycle_empty)
            setTextColor(C["primary"]!!); textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })
        addView(TextView(activity).apply {
            text = activity.getString(R.string.recycle_empty_hint)
            setTextColor(C["hint"]!!); textSize = 12f
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
    }
    AlertDialog.Builder(activity)
        .setView(body)
        .setPositiveButton(activity.getString(R.string.action_confirm), null)
        .show().also { it.belowTabs(fitContent = true) }
}

fun showRecycleBinDialog(activity: MainActivity) {
    val recycleEnabled = RecycleBin.isEnabled(activity.getSharedPreferences("bm", 0))

    if (!recycleEnabled) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.title_recycle_bin))
            .setMessage(activity.getString(R.string.recycle_disabled_message))
            .setPositiveButton(activity.getString(R.string.action_confirm), null)
            .show().also { it.belowTabs(fitContent = true) }
        return
    }

    // Loading dialog while the recycle index is read on a worker thread — a
    // large bin (hundreds of entries) must not freeze the UI with no feedback.
    val pd = android.app.ProgressDialog(activity).apply {
        setTitle(activity.getString(R.string.title_recycle_bin))
        setMessage(activity.getString(R.string.recycle_loading))
        setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER)
        setCancelable(false)
        show()
    }
    pd.keepTabsTappable(rootToTab = false)
    thread {
        val entries = RecycleBin.listEntries(activity)
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            pd.dismiss()
            if (entries.isEmpty()) {
                showEmptyBinDialog(activity)
                return@runOnUiThread
            }

            val d = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()

            // 统计行：图标 + 「N 项 · 总量」。它是列表卡的第一行，不再是一条贴边的平条。
            val tvHeader = TextView(activity).apply {
                text = activity.getString(R.string.recycle_items, entries.size, fmt(entries.sumOf { it.size }))
                setTextColor(C["tertiary_light"]!!)
                textSize = 12f
            }
            val headerRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                addView(ImageView(activity).apply {
                    setImageResource(R.drawable.ic_recycle)
                    setColorFilter(C["accent"]!!)
                }, LinearLayout.LayoutParams(dp(15), dp(15)))
                addView(tvHeader, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp(9) })
            }

            var dialog: AlertDialog? = null

            val adapter = RecycleEntryAdapter(activity, entries.toMutableList(), tvHeader) {
                dialog?.dismiss()
            }

            val listView = ListView(activity).apply {
                this.adapter = adapter
                // 行之间用发丝线（与主界面卡片同一档），不再是无分隔的一片。
                divider = ColorDrawable(C["hairline"]!!)
                dividerHeight = dp(1)
                setPadding(0, 0, 0, 0)
                clipToPadding = false
            }

            // 整块 = 一张卡片：统计行 + 发丝线 + 列表。
            // 列表仍是 wrap_content（见下），卡片跟着内容长，条目多了列表自己滚。
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = activity.getDrawable(R.drawable.bg_card)
                clipToOutline = true
                addView(headerRow, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(View(activity).apply { setBackgroundColor(C["hairline"]!!) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                // wrap_content, not height=0 + weight=1: the sheet is fit-to-
                // content (belowTabs(fitContent = true)), so the list must hug a
                // short bin and only scroll once the entries outgrow the capped
                // sheet. A weighted child would fill the whole available height
                // and bring back the 1-entry-but-full-screen bug.
                addView(listView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            dialog = AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_recycle_bin))
                .setView(card)
                .setPositiveButton(activity.getString(R.string.recycle_empty_now)) { _, _ ->
                    AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.recycle_empty_now))
                        .setMessage(activity.getString(R.string.recycle_empty_confirm))
                        .setPositiveButton(activity.getString(R.string.action_delete)) { _, _ ->
                            thread {
                                RecycleBin.emptyRecycleBin(activity)
                                activity.runOnUiThread {
                                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                                    Toast.makeText(activity, activity.getString(R.string.recycle_empty), Toast.LENGTH_SHORT).show()
                                    dialog?.dismiss()
                                    activity.nav(activity.currentDir)
                                }
                            }
                        }
                        .setNegativeButton(activity.getString(R.string.action_cancel), null)
                        .show().also { it.keepTabsTappable(rootToTab = false) }
                }
                .setNegativeButton(activity.getString(R.string.action_close), null)
                .show().also { it.belowTabs(fitContent = true) }
        }
    }
}

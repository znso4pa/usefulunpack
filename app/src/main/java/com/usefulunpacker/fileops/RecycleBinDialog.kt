package com.usefulunpacker.fileops

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import com.usefulunpacker.C
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
                    if (updatedEntries.isEmpty()) {
                        AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.title_recycle_bin))
                            .setMessage(activity.getString(R.string.recycle_empty))
                            .setPositiveButton(activity.getString(R.string.action_confirm), null)
                            .show()
                    }
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
                            if (updatedEntries.isEmpty()) {
                                AlertDialog.Builder(activity)
                                    .setTitle(activity.getString(R.string.title_recycle_bin))
                                    .setMessage(activity.getString(R.string.recycle_empty))
                                    .setPositiveButton(activity.getString(R.string.action_confirm), null)
                                    .show()
                            }
                        }
                    }
                }
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .show()
        }

        return view
    }
}

fun showRecycleBinDialog(activity: MainActivity) {
    val recycleEnabled = RecycleBin.isEnabled(activity.getSharedPreferences("bm", 0))

    if (!recycleEnabled) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.title_recycle_bin))
            .setMessage(activity.getString(R.string.recycle_disabled_message))
            .setPositiveButton(activity.getString(R.string.action_confirm), null)
            .show()
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
    thread {
        val entries = RecycleBin.listEntries(activity)
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            pd.dismiss()
            if (entries.isEmpty()) {
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.title_recycle_bin))
                    .setMessage(activity.getString(R.string.recycle_empty))
                    .setPositiveButton(activity.getString(R.string.action_confirm), null)
                    .show()
                return@runOnUiThread
            }

            val tvHeader = TextView(activity).apply {
                text = activity.getString(R.string.recycle_items, entries.size, fmt(entries.sumOf { it.size }))
                setTextColor(C["tertiary_light"]!!)
                textSize = 12f
                setPadding(16, 8, 16, 8)
                setBackgroundColor(C["surface_dim"]!!)
            }

            var dialog: AlertDialog? = null

            val adapter = RecycleEntryAdapter(activity, entries.toMutableList(), tvHeader) {
                dialog?.dismiss()
            }

            val listView = ListView(activity).apply {
                this.adapter = adapter
                divider = null
                dividerHeight = 0
                setPadding(0, 0, 0, 0)
                clipToPadding = false
            }

            val container = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(tvHeader)
                addView(listView, android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    0, 1f
                ))
            }

            dialog = AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_recycle_bin))
                .setView(container)
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
                        .show()
                }
                .setNegativeButton(activity.getString(R.string.action_close), null)
                .show()
        }
    }
}

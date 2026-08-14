package com.usefulunpacker

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Full-screen folder picker dialog. Displays only subdirectories of the
 * current path; tap a row to descend, the top "parent" row (or the path-bar
 * up button) to ascend. Confirm returns the currently displayed directory.
 * Reusable for any "pick a destination folder" flow.
 */
fun showFolderPicker(activity: AppCompatActivity, startDir: File, onPick: (File) -> Unit) {
    val view = LayoutInflater.from(activity).inflate(R.layout.dialog_folder_picker, null)
    val tvPath = view.findViewById<TextView>(R.id.folderPath)
    val list = view.findViewById<ListView>(R.id.folderList)
    val btnUp = view.findViewById<ImageButton>(R.id.btnFolderUp)

    var current = startDir
    var dirs: List<File> = emptyList()

    fun render() {
        tvPath.text = current.absolutePath
        dirs = current.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        list.adapter = object : BaseAdapter() {
            override fun getCount() = dirs.size + 1
            override fun getItem(pos: Int): Any = if (pos == 0) current.parentFile ?: File("/") else dirs[pos - 1]
            override fun getItemId(pos: Int) = pos.toLong()
            // Two distinct row layouts must declare view types, otherwise
            // ListView recycles an "up" row into a "dir" row (or vice versa)
            // and findViewById returns null → NPE.
            override fun getViewTypeCount() = 2
            override fun getItemViewType(pos: Int) = if (pos == 0) 0 else 1
            override fun getView(pos: Int, v: android.view.View?, p: ViewGroup?): android.view.View {
                if (pos == 0) {
                    val r = v ?: LayoutInflater.from(activity).inflate(R.layout.item_folder_up, p, false)
                    r.findViewById<TextView>(R.id.folder_up_label).text =
                        activity.getString(R.string.parent_dir)
                    return r
                }
                val r = v ?: LayoutInflater.from(activity).inflate(R.layout.item_folder_row, p, false)
                r.findViewById<TextView>(R.id.folder_row_name).text = dirs[pos - 1].name
                return r
            }
        }
    }
    render()

    list.setOnItemClickListener { _, _, pos, _ ->
        if (pos == 0) current.parentFile?.let { current = it; render() }
        else { current = dirs[pos - 1]; render() }
    }
    btnUp.setOnClickListener { current.parentFile?.let { current = it; render() } }

    val dlg = AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.title_search_dir))
        .setView(view)
        .setPositiveButton(activity.getString(R.string.pick_this_dir)) { _, _ -> onPick(current) }
        .setNegativeButton(activity.getString(R.string.action_cancel), null)
        .create()
    // Size the window BEFORE show so the first layout is already the final
    // size (resizing in onShow causes a visible jump/flash during the enter
    // animation).
    val metrics = activity.resources.displayMetrics
    dlg.window?.setLayout((metrics.widthPixels * 0.92).toInt(), (metrics.heightPixels * 0.75).toInt())
    dlg.show()
}

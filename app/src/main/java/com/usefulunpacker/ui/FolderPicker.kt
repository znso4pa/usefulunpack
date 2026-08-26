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
 * Full-screen file/folder picker dialog. Displays subdirectories (and, when
 * [allowFiles] is set, files) of the current path; tap a row to descend,
 * the top "parent" row (or the path-bar up button) to ascend. Confirm returns
 * the currently displayed directory — or, in file mode, the selected file.
 *
 * Dispatches to a NEW WINDOW picker when the "pick in a new window" setting is
 * on (prefs key `picker_mode` = "tab"), otherwise shows this dialog.
 */
fun showFolderPicker(activity: AppCompatActivity, startDir: File, allowFiles: Boolean = false, onPick: (File) -> Unit) {
    if (activity is MainActivity && activity.prefs.getString("picker_mode", "dialog") == "tab") {
        activity.openPickerInTab(startDir, allowFiles, onPick)
        return
    }
    showFolderPickerDialog(activity, startDir, allowFiles, onPick)
}

/** The legacy in-dialog folder/file picker. */
internal fun showFolderPickerDialog(activity: AppCompatActivity, startDir: File, allowFiles: Boolean = false, onPick: (File) -> Unit) {
    val view = LayoutInflater.from(activity).inflate(R.layout.dialog_folder_picker, null)
    val tvPath = view.findViewById<TextView>(R.id.folderPath)
    val list = view.findViewById<ListView>(R.id.folderList)
    val btnUp = view.findViewById<ImageButton>(R.id.btnFolderUp)

    var current = startDir
    var dirs: List<File> = emptyList()
    var selectedFile: File? = null

    // Generation token: a slow listing that finishes after the user already
    // navigated elsewhere must not overwrite the newer view.
    var renderGen = 0

    fun render() {
        tvPath.text = current.absolutePath
        selectedFile = null
        val dir = current
        val gen = ++renderGen
        // listFiles() blocks on huge/networked folders — list off-thread.
        kotlin.concurrent.thread(start = true) {
            val loaded = dir.listFiles()?.let { fs ->
                fs.filter { it.isDirectory || allowFiles }
                    .sortedBy { !it.isDirectory } // dirs first, then files
                    .sortedBy { it.name.lowercase() }
            } ?: emptyList()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (gen != renderGen) return@runOnUiThread
                dirs = loaded
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
                        val f = dirs[pos - 1]
                        r.findViewById<TextView>(R.id.folder_row_name).text =
                            if (f.isDirectory) f.name else "📄 ${f.name}"
                        return r
                    }
                }
            }
        }
    }
    render()

    list.setOnItemClickListener { _, _, pos, _ ->
        if (pos == 0) current.parentFile?.let { current = it; render() }
        else {
            val f = dirs[pos - 1]
            if (f.isDirectory) { current = f; render() }
            else selectedFile = f
        }
    }
    btnUp.setOnClickListener { current.parentFile?.let { current = it; render() } }

    val dlg = AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.title_search_dir))
        .setView(view)
        .setPositiveButton(activity.getString(R.string.pick_this_dir)) { _, _ ->
            val picked = selectedFile ?: if (allowFiles) null else current
            if (allowFiles && picked == null) {
                android.widget.Toast.makeText(activity,
                    activity.getString(R.string.zip_add_pick), android.widget.Toast.LENGTH_SHORT).show()
            } else if (picked != null) {
                onPick(picked)
            }
        }
        .setNegativeButton(activity.getString(R.string.action_cancel), null)
        .create()
    // Size the window BEFORE show so the first layout is already the final
    // size (resizing in onShow causes a visible jump/flash during the enter
    // animation).
    val metrics = activity.resources.displayMetrics
    val (fw, fh) = activity.cappedDialogSize(0.92f, 0.75f)
    dlg.window?.setLayout(fw, fh)
    dlg.show()
}

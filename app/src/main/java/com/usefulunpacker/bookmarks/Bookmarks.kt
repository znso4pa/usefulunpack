package com.usefulunpacker

import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import java.io.File

internal fun MainActivity.loadBookmarks() {
        val s = prefs.getStringSet("paths", emptySet()) ?: emptySet()
        bookmarks.clear(); bookmarks.addAll(s)
        val items = bookmarks.map { "📌 " + getString(R.string.bookmark_item, File(it).name) }
        listBookmarks.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(pos: Int, v: View?, p: ViewGroup): View {
                val view = super.getView(pos, v, p)
                (view.findViewById<TextView>(android.R.id.text1)).apply { setTextColor(C["primary"]!!); textSize = 13f }
                return view
            }
        }
    }

internal fun MainActivity.saveBookmarks() { prefs.edit().putStringSet("paths", bookmarks.toSet()).apply(); loadBookmarks() }

    /**
     * Removes bookmarks whose folder was actually deleted (checked by path
     * no longer existing), so a recreated folder at the same path doesn't
     * inherit a stale star / shortcut.
     */

internal fun MainActivity.pruneBookmarksForDeleted(targets: List<File>) {
        val dead = targets.filter { !it.exists() }.map { it.absolutePath }.toSet()
        if (dead.isEmpty()) return
        bookmarks.removeAll { it in dead }
        saveBookmarks()
    }

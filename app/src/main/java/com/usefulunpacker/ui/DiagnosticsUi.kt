package com.usefulunpacker

import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.snackbar.Snackbar
import java.io.File
import com.usefulunpacker.featureThread as thread

/** Lifecycle ownership belongs here; failures remain in the journal when no Activity is visible. */
internal class DiagnosticsUi(private val activity: MainActivity) {
    @Volatile private var resumed = false
    private val listener: (AppFailure) -> Unit = { failure ->
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || !resumed) return@runOnUiThread
            try { showPending() } catch (e: Exception) {
                // The failure presenter cannot report through itself recursively.
                AppDiagnostics.store?.append("application", "diagnostics_ui", e.javaClass.simpleName, true)
                UuNotice.makeText(activity, activity.getString(R.string.diagnostics_failure) + " [${failure.logId}]", Toast.LENGTH_LONG).show()
            }
        }
    }
    fun resume() { resumed = true; AppDiagnostics.addListener(listener); showPending() }
    fun pause() { resumed = false; AppDiagnostics.removeListener(listener) }
    private fun showPending() {
        val failures = AppDiagnostics.takePending()
        if (failures.isEmpty() || activity.isFinishing || activity.isDestroyed) return
        val groups = failures.groupBy { it.logId }
        val id = groups.keys.singleOrNull()
        val message = if (id == null) activity.getString(R.string.diagnostics_multiple_failures, groups.size)
            else activity.getString(if (groups.getValue(id).any { it.code == FailureCode.CLEANUP }) R.string.diagnostics_cleanup_failed else R.string.diagnostics_failure) + " [$id]"
        Snackbar.make(activity.findViewById(R.id.root), message, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.diagnostics_view) { activity.showDiagnostics(id) }.show()
    }
}

internal fun MainActivity.showDiagnostics(selectedId: String? = null, feature: String? = null) {
    thread {
        val store = AppDiagnostics.store
        val allEntries = store?.snapshot().orEmpty()
        val entries = if (feature == null) allEntries else allEntries.filter { it.feature == feature }
        val groups = entries.groupBy { it.feature }.toList()
        runFeatureOnUiThread {
            if (isFinishing || isDestroyed) return@runFeatureOnUiThread
            val selected = entries.firstOrNull { it.id == selectedId }
            if (selected != null) showDiagnosticEntry(selected)
            else UuDialogBuilder(this)
                .setTitle(getString(R.string.diagnostics_title) + if (feature == null) "" else " · $feature")
                .setMessage(when {
                    store?.storageError != null -> getString(R.string.diagnostics_storage_failed)
                    entries.isEmpty() -> getString(R.string.diagnostics_empty)
                    else -> null
                })
                .apply {
                    if (entries.isNotEmpty()) {
                        if (feature == null) setItems(groups.map { (name, records) ->
                            "${if (records.any { it.failed }) "! " else ""}$name (${records.size})"
                        }.toTypedArray()) { _, which -> showDiagnostics(feature = groups[which].first) }
                        else setItems(entries.map { "${if (it.failed) "! " else ""}${it.id} · ${it.status}" + if (it.cleanupFailed) " · ${getString(R.string.diagnostics_cleanup_failed)}" else "" }.toTypedArray()) { _, which -> showDiagnosticEntry(entries[which]) }
                    }
                }
                .setNegativeButton(R.string.action_close, null)
                .show().also { it.keepTabsTappable(rootToTab = false) }
        }
    }
}

private fun MainActivity.showDiagnosticEntry(entry: DiagnosticEntry) {
    val text = TextView(this).apply {
        text = entry.text + if (AppDiagnostics.store?.storageError != null) "\n${getString(R.string.diagnostics_storage_failed)}" else ""
        setTextIsSelectable(true)
        setTextColor(getColor(R.color.text_primary))
        textSize = 14f
        typeface = Typeface.MONOSPACE
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false; addView(text) }
    UuDialogBuilder(this).setTitle("${entry.id}.log").setView(scroll)
        .setNeutralButton(R.string.diagnostics_share) { _, _ ->
            userIntent("share_log") {
            thread {
                try {
                    val dir = File(cacheDir, "shared-logs")
                    if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("Cannot create log export directory")
                    val file = File(dir, "${entry.id}.log").apply { writeText(entry.text) }
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    runFeatureOnUiThread {
                        if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                        try {
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.diagnostics_share)))
                        } catch (e: Exception) { AppDiagnostics.failure("log-export", e) }
                    }
                } catch (e: Exception) { AppDiagnostics.failure("log-export", e) }
            }
            }
        }
        .setNegativeButton(R.string.action_close, null).show().also { it.belowTabs(rootToTab = false, fitContent = true) }
}

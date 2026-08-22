package com.usefulunpacker

import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Date
import kotlin.concurrent.thread

internal fun MainActivity.showFileInfoDialog(f: File) {
        val tvName = TextView(this).apply {
            text = f.name; setTextColor(C["primary"]!!); textSize = 14f
            setPadding(24, 16, 24, 2); setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val tvSize = TextView(this).apply {
            text = fmt(fileSize(f)); setTextColor(C["secondary"]!!); textSize = 12f
            setPadding(24, 2, 24, 2)
        }
        val tvDate = TextView(this).apply {
            text = df.format(Date(f.lastModified())); setTextColor(C["hint"]!!); textSize = 12f
            setPadding(24, 2, 24, 8)
        }
        val divider = View(this).apply {
            setBackgroundColor(C["divider"]!!); layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(24, 4, 24, 4) }
        }
        val tvMd5Label = TextView(this).apply { text = "MD5"; setTextColor(C["primary"]!!); textSize = 12f; setPadding(24, 4, 24, 0); setTypeface(null, android.graphics.Typeface.BOLD) }
        val tvMd5Val = TextView(this).apply {
            text = getString(R.string.msg_calculating); setTextColor(C["hint"]!!); textSize = 11f
            setPadding(24, 0, 8, 0); setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; typeface = android.graphics.Typeface.MONOSPACE
        }
        val btnMd5 = Button(this).apply { text = "📋"; setTextColor(C["accent"]!!); background = null; textSize = 12f; setPadding(0, 0, 24, 0); isEnabled = false }
        val rowMd5 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(tvMd5Val, LinearLayout.LayoutParams(0, WRAP, 1f)); addView(btnMd5) }
        val tvShaLabel = TextView(this).apply { text = "SHA-256"; setTextColor(C["primary"]!!); textSize = 12f; setPadding(24, 6, 24, 0); setTypeface(null, android.graphics.Typeface.BOLD) }
        val tvShaVal = TextView(this).apply {
            text = getString(R.string.msg_calculating); setTextColor(C["hint"]!!); textSize = 11f
            setPadding(24, 0, 8, 0); setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; typeface = android.graphics.Typeface.MONOSPACE
        }
        val btnSha = Button(this).apply { text = "📋"; setTextColor(C["accent"]!!); background = null; textSize = 12f; setPadding(0, 0, 24, 0); isEnabled = false }
        val rowSha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(tvShaVal, LinearLayout.LayoutParams(0, WRAP, 1f)); addView(btnSha) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tvName); addView(tvSize); addView(tvDate)
            addView(divider)
            addView(tvMd5Label); addView(rowMd5)
            addView(tvShaLabel); addView(rowSha)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_file_info))
            .setView(layout)
            .setPositiveButton(getString(R.string.action_confirm), null)
            .create()
        dlg.show()

        thread {
            try {
                val md5 = hashFile(f, "MD5")
                val sha = hashFile(f, "SHA-256")
                runOnUiThread {
                    if (isFinishing || !dlg.isShowing) return@runOnUiThread
                    tvMd5Val.text = md5; btnMd5.isEnabled = true
                    btnMd5.setOnClickListener { copyToClipboard(this, md5); toast(getString(R.string.msg_hash_copied)) }
                    tvShaVal.text = sha; btnSha.isEnabled = true
                    btnSha.setOnClickListener { copyToClipboard(this, sha); toast(getString(R.string.msg_hash_copied)) }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (isFinishing || !dlg.isShowing) return@runOnUiThread
                    tvMd5Val.text = getString(R.string.calc_failed); tvShaVal.text = getString(R.string.calc_failed)
                }
            }
        }
    }

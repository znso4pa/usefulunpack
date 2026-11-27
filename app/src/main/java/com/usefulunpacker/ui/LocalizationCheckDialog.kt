package com.usefulunpacker

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal fun showLocalizationCheck(activity: AppCompatActivity, source: File) {
    val scope = AppDiagnostics.enterIntent("localization_check", newChain = true)
    try { LocalizationCheckDialog(activity, source).show() }
    catch (e: Exception) {
        scope.trace.fail(FailureCode.UNEXPECTED, "Cannot open localization check (${e.javaClass.simpleName})")
        UuNotice.failure(activity, activity.getString(R.string.localization_failed), Toast.LENGTH_LONG).show()
    } finally { AppDiagnostics.leaveIntent(scope) }
}

/** One tab-owned sheet switches between setup and a bounded finding list. Files are never changed. */
private class LocalizationCheckDialog(private val activity: AppCompatActivity, source: File) {
    private var original = source
    private var translated: File? = null
    private var sourceEncoding = "auto"
    private var translationEncoding = "auto"
    private var targetEncoding = "translation"
    private var profile = "encoding"
    private var running = false
    private var closed = false
    private var cancellation = AtomicBoolean()
    private lateinit var dialog: AlertDialog
    private val pad = UuMetrics.px(activity, R.dimen.space_md)
    private val status = TextView(activity).apply {
        textSize = 12f
        setTextColor(activity.getColor(R.color.text_secondary))
        setPadding(pad, pad / 2, pad, pad / 2)
    }
    private val sourceRow = UuActionRow(activity)
    private val translationRow = UuActionRow(activity)
    private val sourceEncodingRow = UuActionRow(activity)
    private val translationEncodingRow = UuActionRow(activity)
    private val targetEncodingRow = UuActionRow(activity)
    private val profileRow = UuActionRow(activity)
    private val setup = UuScrollView(activity).apply {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                setText(R.string.localization_scope)
                textSize = 13f
                setTextColor(activity.getColor(R.color.text_secondary))
                setPadding(pad, pad, pad, pad)
            })
            for (row in listOf(sourceRow, translationRow, sourceEncodingRow, translationEncodingRow, targetEncodingRow, profileRow)) addView(row)
        })
    }
    private val findings = ListView(activity).apply {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        visibility = View.GONE
    }
    private val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(setup, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(findings, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    fun show() {
        bindSetup()
        status.setText(R.string.localization_start_hint)
        dialog = UuDialogBuilder(activity).setTitle(R.string.localization_title).setView(root)
            .setPositiveButton(R.string.localization_run, null)
            .setNeutralButton(R.string.localization_inputs, null)
            .setNegativeButton(R.string.action_close, null).create()
        dialog.belowTabs()
        dialog.setOnDismissListener { closed = true; cancellation.set(true) }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).onUserIntent("localization_check") { run() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (!running) {
                    setup.visibility = View.VISIBLE
                    findings.visibility = View.GONE
                    status.setText(R.string.localization_start_hint)
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.GONE
                }
            }
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.GONE
    }

    private fun bindSetup() {
        sourceRow.bind(activity.getString(R.string.localization_source, visibleAgentText(original.name)), navigation = true) {
            showFolderPicker(activity, original.parentFile ?: File("/"), allowFiles = true) {
                if (closed) return@showFolderPicker
                original = it
                bindSetup()
            }
        }
        translationRow.bind(activity.getString(R.string.localization_translation,
            translated?.name?.let { visibleAgentText(it) } ?: activity.getString(R.string.localization_choose)), navigation = true) {
            showFolderPicker(activity, translated?.parentFile ?: original.parentFile ?: File("/"), allowFiles = true) {
                if (closed) return@showFolderPicker
                translated = it
                bindSetup()
            }
        }
        bindEncoding(sourceEncodingRow, R.string.localization_source_encoding, sourceEncoding, false) { sourceEncoding = it }
        bindEncoding(translationEncodingRow, R.string.localization_translation_encoding, translationEncoding, false) { translationEncoding = it }
        bindEncoding(targetEncodingRow, R.string.localization_target_encoding, targetEncoding, true) { targetEncoding = it }
        val profiles = listOf("encoding", "named", "printf")
        val labels = intArrayOf(R.string.localization_profile_encoding, R.string.localization_profile_named, R.string.localization_profile_printf)
            .map(activity::getString).toTypedArray()
        val selected = profiles.indexOf(profile)
        profileRow.bind(activity.getString(R.string.localization_profile, labels[selected]), navigation = true) {
            UuDialogBuilder(activity).setTitle(R.string.localization_profile_confirm)
                .setSingleChoiceItems(labels, selected) { picker, index ->
                    profile = profiles[index]
                    picker.dismiss()
                    bindSetup()
                }.setNegativeButton(R.string.action_cancel, null).show()
        }
    }

    private fun bindEncoding(row: UuActionRow, label: Int, value: String, output: Boolean, selected: (String) -> Unit) {
        val values = (if (output) listOf("translation", "source") else listOf("auto")) + TEXT_ENCODINGS
        val labels = values.map { encoding -> when (encoding) {
            "auto" -> activity.getString(R.string.localization_auto)
            "source" -> activity.getString(R.string.localization_same_encoding)
            "translation" -> activity.getString(R.string.localization_translation_encoding_default)
            else -> encoding
        } }.toTypedArray()
        val current = values.indexOf(value).coerceAtLeast(0)
        row.bind(activity.getString(label, labels[current]), navigation = true) {
            UuDialogBuilder(activity).setTitle(activity.getString(label, ""))
                .setSingleChoiceItems(labels, current) { picker, index ->
                    selected(values[index])
                    picker.dismiss()
                    bindSetup()
                }.setNegativeButton(R.string.action_cancel, null).show()
        }
    }

    private fun run() {
        if (running) return
        val input = translated
        if (input == null) {
            UuNotice.makeText(activity, activity.getString(R.string.localization_pick_translation), Toast.LENGTH_SHORT).show()
            return
        }
        val source = original
        val readSource = sourceEncoding
        val readTranslation = translationEncoding
        val writeEncoding = targetEncoding
        val checkProfile = profile
        val cancelled = AtomicBoolean()
        cancellation = cancelled
        running = true
        setup.visibility = View.GONE
        findings.visibility = View.GONE
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
        val trace = AppDiagnostics.begin("localization_rules")
        status.text = activity.getString(R.string.localization_running, trace.id)
        featureThread {
            try {
                val result = LocalizationChecker.check(source, input, readSource, readTranslation, writeEncoding, checkProfile) { cancelled.get() }
                if (cancelled.get()) throw CancellationException()
                trace.note("profile=${result.profile} errors=${result.errors} warnings=${result.totalIssues - result.errors} limited=${result.limited} aligned=${result.aligned} skipped_lines=${result.skippedLines}")
                trace.success()
                activity.runFeatureOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
                    if (closed) return@runFeatureOnUiThread
                    running = false
                    showReport(result)
                    status.append("\n" + activity.getString(R.string.line_edit_chain, trace.id))
                    enableActions()
                }
            } catch (_: CancellationException) { trace.cancelled() }
            catch (e: Exception) {
                val reason = (e as? LocalizationCheckException)?.reason ?: "IO"
                trace.fail(if (e is LocalizationCheckException) FailureCode.INVALID_RESULT else FailureCode.IO,
                    "Localization check failed: $reason")
                activity.runFeatureOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
                    if (closed) return@runFeatureOnUiThread
                    running = false
                    setup.visibility = View.VISIBLE
                    status.text = activity.getString(R.string.localization_failure_code, reason, trace.id)
                    UuNotice.failure(activity, activity.getString(R.string.localization_failed), Toast.LENGTH_LONG).show()
                    enableActions()
                }
            }
        }
    }

    private fun enableActions() {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
    }

    private fun showReport(result: LocalizationReport) {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.VISIBLE
        val sections = mutableListOf(activity.getString(R.string.localization_summary, result.errors, result.totalIssues - result.errors,
            result.sourceLines, result.translationLines, result.sourceEncoding, result.translationEncoding, result.targetEncoding))
        sections += listOfNotNull(
                R.string.localization_limited.takeIf { result.limited },
                R.string.localization_unaligned.takeIf { !result.aligned && result.profile != "encoding" },
                when {
                result.totalIssues == 0 -> R.string.localization_no_findings
                else -> R.string.localization_review_hint
                }).map { activity.getString(it) }
        if (result.skippedLines > 0) sections += activity.getString(R.string.localization_skipped, result.skippedLines)
        status.text = sections.joinToString("\n")
        setup.visibility = View.GONE
        findings.visibility = View.VISIBLE
        findings.adapter = object : BaseAdapter() {
            override fun getCount() = result.issues.size
            override fun getItem(position: Int) = result.issues[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val issue = getItem(position)
                return (convertView as? UuActionRow ?: UuActionRow(activity)).apply {
                    val description = activity.getString(issue.rule.label())
                    val prefix = activity.getString(if (issue.error) R.string.localization_error else R.string.localization_warning)
                    val title = "$prefix · " + if (issue.line == null) description
                        else activity.getString(R.string.line_edit_row_error, issue.line, description)
                    bind(title, navigation = issue.line != null) {
                        if (issue.line != null) {
                            val detail = listOfNotNull(issue.original?.let {
                                activity.getString(R.string.localization_source, visibleAgentText(it, keepNewlines = true))
                            }, issue.translation?.let {
                                activity.getString(R.string.localization_translation, visibleAgentText(it, keepNewlines = true))
                            }).joinToString("\n\n")
                            UuDialogBuilder(activity).setTitle(title).setMessage(detail)
                                .setPositiveButton(R.string.action_close, null).create().apply { belowTabs(fitContent = true); show() }
                        }
                    }
                }
            }
        }
    }
}

private fun LocalizationRule.label(): Int = when (this) {
    LocalizationRule.PLACEHOLDERS_CHANGED -> R.string.localization_rule_placeholders
    LocalizationRule.TARGET_UNMAPPABLE -> R.string.localization_rule_unmappable
}

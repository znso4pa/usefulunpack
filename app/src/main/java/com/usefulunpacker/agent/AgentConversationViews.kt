package com.usefulunpacker

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible

internal class AgentTurnCard(private val context: Context, question: String, private val onChange: (AgentFileChange) -> Unit) {
    private val changeFiles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; isVisible = false }
    private val changeBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; isVisible = false }
    private val changeToggle = UuButton(context).apply {
        actionStyle = ActionStyle.TEXT
    }
    init {
        changeFiles.addView(changeToggle)
        changeFiles.addView(changeBody)
    }
    fun setChanges(files: List<AgentFileChange>, incomplete: Boolean) {
        changeFiles.isVisible = files.isNotEmpty() || incomplete
        val single = files.singleOrNull()?.takeUnless { incomplete }
        changeToggle.setOnClickListener {
            if (single != null) onChange(single)
            else { changeBody.isVisible = !changeBody.isVisible; changeToggle.isSelected = changeBody.isVisible }
        }
        if (single != null) changeBody.isVisible = false
        changeToggle.text = if (single != null) context.agentChangeLabel(single)
        else if (files.isNotEmpty() && files.none { it.limited } && !incomplete) {
            context.getString(R.string.agent_changes_count_diff, files.size,
                files.sumOf { file -> file.lines.count { it.kind == 1 } },
                files.sumOf { file -> file.lines.count { it.kind == -1 } })
        } else context.getString(R.string.agent_changes_count, files.size)
        changeBody.removeAllViews()
        if (incomplete) changeBody.addView(TextView(context).apply {
            UuMetrics.text(this)
            setText(R.string.agent_changes_incomplete)
        })
        for (file in files) changeBody.addView(UuActionRow(context).apply {
            bind(context.agentChangeLabel(file), navigation = true) { onChange(file) }
        })
    }
    private val toolRows = mutableMapOf<Int, AgentActivityEntry>()
    private val timeline = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val usageRows = mutableListOf<String>()
    private var expanded = false
    private val disclosure = UuButton(context).apply {
        actionStyle = ActionStyle.TEXT
        setText(R.string.agent_activity_expand)
        visibility = View.GONE
        setOnClickListener {
            expanded = !expanded
            setText(if (expanded) R.string.agent_activity_collapse else R.string.agent_activity_expand)
            refreshDetails()
        }
    }
    private val answer = line(R.dimen.text_lg, R.color.text_primary).apply {
        setTextIsSelectable(true)
        visibility = View.GONE
    }
    private val usageDetails = line(R.dimen.text_sm, R.color.text_secondary).apply {
        visibility = View.GONE
    }

    val view = context.agentSection().apply {
        val padding = UuMetrics.px(context, R.dimen.space_md)
        setPadding(padding, padding, padding, padding)
        addView(line(R.dimen.text_title, R.color.text_primary).apply {
            text = question
            setTextIsSelectable(true)
        })
        addView(timeline)
        addView(disclosure, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(usageDetails)
        addSpaced(answer, R.dimen.space_sm)
        addView(changeFiles)
    }

    fun setAnswer(value: String): View = answer.apply {
        text = value
        visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    }

    fun appendReasoning(value: String) {
        val entry = AgentActivityEntry(context, R.color.agent_reasoning_accent)
        entry.render(context.getString(R.string.agent_reasoning), value)
        timeline.addSpaced(entry.view, R.dimen.space_xs)
    }

    fun appendUsage(usage: AgentModelUsage) {
        fun format(value: Long?) = value?.toString() ?: "—"
        usageRows += context.getString(R.string.agent_model_usage, usageRows.size + 1, format(usage.input),
            format(usage.output), format(usage.reasoning), format(usage.cachedInput), usage.elapsedMs)
        disclosure.visibility = View.VISIBLE
        if (expanded) refreshDetails()
    }

    fun markLocal() {
        usageDetails.setText(R.string.agent_local_no_model)
        usageDetails.visibility = View.VISIBLE
    }

    fun updateTool(event: AgentToolEvent) {
        val row = toolRows.getOrPut(event.callIndex) {
            AgentActivityEntry(context, R.color.agent_tool_accent).also {
                timeline.addSpaced(it.view, R.dimen.space_xs)
            }
        }
        val status = when {
            event.failure != null -> context.getString(R.string.agent_tool_failed, event.failure)
            event.stage == AgentToolStage.APPROVAL -> context.getString(R.string.agent_tool_awaiting, event.name)
            event.stage == AgentToolStage.SUCCESS -> context.getString(R.string.agent_tool_success, event.name)
            event.stage == AgentToolStage.CANCELLED -> context.getString(R.string.agent_tool_cancelled, event.name)
            event.readOnly -> context.getString(R.string.agent_tool_read, event.name)
            else -> context.getString(R.string.agent_tool_write, event.name)
        }
        val label = listOfNotNull(event.name.takeIf { event.failure != null && it.isNotEmpty() }, status)
            .joinToString(" · ")
        val details = listOfNotNull(
            event.path.takeIf(String::isNotEmpty),
            event.resultCount?.let { context.getString(R.string.agent_tool_result_items, it) },
            event.resultLabel,
        ).joinToString("\n")
        row.render(label, details, event.failure != null)
    }

    private fun refreshDetails() {
        if (usageRows.isEmpty()) return
        usageDetails.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) usageDetails.text = usageRows.joinToString("\n")
    }

    private fun line(size: Int, color: Int) = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(size))
        setTextColor(context.getColor(color))
    }
}

private class AgentActivityEntry(private val context: Context, private val accent: Int) {
    private val header = UuActionRow(context)
    private val body = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_sm))
        setTextColor(context.getColor(R.color.text_secondary))
        setTextIsSelectable(true)
        val inset = context.dp(16)
        setPadding(inset, 0, inset, context.dp(12))
        visibility = View.GONE
    }
    private var expanded = false

    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val surface = context.getColor(R.color.bg_surface)
        val tint = androidx.core.graphics.ColorUtils.blendARGB(surface, context.getColor(accent), 0.12f)
        background = roundedBg(UuMetrics.px(context, R.dimen.corner_input), tint)
        addView(header)
        addView(body)
    }

    fun render(label: String, details: String, failed: Boolean = false) {
        val hasDetails = details.isNotEmpty()
        if (!hasDetails) expanded = false
        val indicator = when {
            !hasDetails -> ""
            expanded -> "−  "
            else -> "+  "
        }
        val toggle: (() -> Unit)? = if (!hasDetails) null else {
            {
                expanded = !expanded
                render(label, details, failed)
            }
        }
        header.bind(indicator + label, onClick = toggle)
        header.isEnabled = true
        header.isClickable = hasDetails
        header.isFocusable = hasDetails
        header.contentDescription = label
        header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_sm))
        header.setTextColor(context.getColor(if (failed) R.color.state_error else accent))
        ViewCompat.setStateDescription(header, if (!hasDetails) null else context.getString(
            if (expanded) R.string.agent_step_expanded else R.string.agent_step_collapsed))
        body.visibility = if (hasDetails && expanded) View.VISIBLE else View.GONE
        body.text = if (expanded) details else ""
    }
}

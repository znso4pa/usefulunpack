package com.usefulunpacker

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Replaces the conversation viewport. It creates neither a tab nor an Android window. */
internal class AgentChangesView(context: Context, onBack: () -> Unit) : LinearLayout(context) {
    private val title = TextView(context).apply {
        UuMetrics.text(this)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val summary = TextView(context).apply {
        UuMetrics.text(this)
        setPadding(UuMetrics.px(context, R.dimen.space_md), 0, UuMetrics.px(context, R.dimen.space_md),
            UuMetrics.px(context, R.dimen.space_sm))
    }
    private val list = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        itemAnimator = null
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.bg_surface_dark))
        addView(UuActionBar(context).apply {
            addView(UuButton(context).apply {
                actionStyle = ActionStyle.TEXT
                setText(R.string.agent_changes_back)
                setOnClickListener { onBack() }
            })
            addView(title, LayoutParams(0, WRAP, 1f))
        })
        addView(summary)
        addView(list, LayoutParams(MATCH, 0, 1f))
    }

    fun show(change: AgentFileChange) {
        title.text = context.agentChangeLabel(change)
        summary.text = context.getString(R.string.agent_change_sizes, change.beforeSize, change.afterSize) +
            if (change.limited) "\n" + context.getString(R.string.agent_changes_metadata)
            else "\n" + context.getString(R.string.agent_changes_columns)
        val rows = change.lines
        list.adapter = object : RecyclerView.Adapter<DiffRow>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = DiffRow(TextView(parent.context).apply {
                UuMetrics.text(this)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                val inset = UuMetrics.px(context, R.dimen.space_sm)
                setPadding(inset, inset / 2, inset, inset / 2)
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            })
            override fun onBindViewHolder(holder: DiffRow, position: Int) {
                val line = rows[position]
                val marker = when (line.kind) { -1 -> "−"; 1 -> "+"; else -> " " }
                holder.label.text = "${line.oldLine ?: "·"}  ${line.newLine ?: "·"}  $marker ${visibleAgentText(line.text)}"
                holder.label.setTextColor(context.getColor(when (line.kind) {
                    -1 -> R.color.state_error
                    1 -> R.color.state_success
                    else -> R.color.text_secondary
                }))
            }
        }
    }

    private class DiffRow(val label: TextView) : RecyclerView.ViewHolder(label)
}

internal fun Context.agentChangeLabel(change: AgentFileChange): String = getString(when {
    !change.existedBefore -> R.string.agent_change_added
    !change.existsAfter -> R.string.agent_change_deleted
    else -> R.string.agent_change_modified
}, visibleAgentText(change.path))

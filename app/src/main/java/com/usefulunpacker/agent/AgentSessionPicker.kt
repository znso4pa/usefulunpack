package com.usefulunpacker

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

internal data class AgentSessionChoice(val id: String, val title: String, val current: Boolean, val hasDraft: Boolean)

internal fun MainActivity.showAgentSessionPicker(
    ownerTabId: Int,
    sessions: List<AgentSessionChoice>,
    warning: CharSequence?,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRename: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val search = UuTextField(this).apply {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        setHint(R.string.agent_session_search)
    }
    val empty = TextView(this).apply {
        UuMetrics.text(this)
        setText(R.string.agent_sessions_no_match)
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    val list = ListView(this).apply {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        divider = ColorDrawable(Color.TRANSPARENT)
        dividerHeight = UuMetrics.px(context, R.dimen.space_xs)
    }
    val rename = UuButton(this).apply {
        actionStyle = ActionStyle.TEXT
        setText(R.string.agent_session_rename)
    }
    val delete = onDelete?.let {
        UuButton(this).apply { actionStyle = ActionStyle.DANGER; setText(R.string.action_delete) }
    }
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isFocusableInTouchMode = true
        val margin = UuMetrics.px(context, R.dimen.space_lg)
        setPadding(margin, 0, margin, 0)
        addView(search)
        if (warning != null) addView(TextView(context).apply {
            UuMetrics.text(this)
            text = warning
        })
        addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            topMargin = UuMetrics.px(context, R.dimen.space_sm)
        })
        addView(empty, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(UuActionBar(context).apply {
            addView(rename, LinearLayout.LayoutParams(0, WRAP, 1f))
            delete?.let { addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }
        })
    }
    val picker = UuDialogBuilder(this).setTitle(R.string.agent_sessions)
        .setView(body)
        .setNegativeButton(R.string.action_close, null)
        .setNeutralButton(R.string.agent_new_session) { _, _ -> onNew() }
        .create()
    var shown = sessions
    val adapter = object : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): AgentSessionChoice = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, view: View?, parent: ViewGroup): View {
            val item = getItem(position)
            val label = when {
                item.current -> getString(R.string.agent_session_current, item.title)
                item.hasDraft -> getString(R.string.agent_session_draft, item.title)
                else -> item.title
            }
            return (view as? UuChoiceCell ?: UuChoiceCell(parent.context)).apply {
                bind(label, listMode = true, selected = item.current) {
                    picker.dismiss()
                    onSelect(item.id)
                }
            }
        }
    }
    list.emptyView = empty
    list.adapter = adapter
    search.doAfterTextChanged {
        val query = it?.toString().orEmpty().trim()
        shown = if (query.isEmpty()) sessions else sessions.filter { item -> item.title.contains(query, ignoreCase = true) }
        adapter.notifyDataSetChanged()
        list.setSelection(0)
    }
    rename.setOnClickListener { picker.dismiss(); onRename() }
    delete?.setOnClickListener { picker.dismiss(); onDelete?.invoke() }
    rootDialogToTab(picker, ownerTabId)
    picker.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    picker.belowTabs()
    picker.show()
    body.requestFocus()
}

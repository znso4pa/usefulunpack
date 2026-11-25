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

private data class DocumentChoice(val reference: AgentReferenceDocument, val title: String, val description: String) {
    val searchable = "$title $description ${reference.searchable}"
}

internal fun MainActivity.showAgentDocumentPicker(ownerTabId: Int, onSelect: (AgentReferenceDocument) -> Unit) {
    val search = UuTextField(this).apply {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        setHint(R.string.agent_document_search)
    }
    val list = ListView(this).apply {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        divider = ColorDrawable(Color.TRANSPARENT)
        dividerHeight = UuMetrics.px(context, R.dimen.space_xs)
    }
    val empty = TextView(this).apply {
        UuMetrics.text(this)
        setText(R.string.agent_documents_no_match)
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isFocusableInTouchMode = true
        val margin = UuMetrics.px(context, R.dimen.space_lg)
        setPadding(margin, 0, margin, 0)
        addView(search)
        addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            topMargin = UuMetrics.px(context, R.dimen.space_sm)
        })
        addView(empty, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }
    val picker = UuDialogBuilder(this).setTitle(R.string.agent_documents)
        .setMessage(R.string.agent_documents_info)
        .setView(body)
        .setNegativeButton(R.string.action_close, null)
        .create()
    val choices = AgentReferenceDocument.entries.map { document ->
        DocumentChoice(document, document.title(this), document.description(this))
    }
    var shown = choices
    val adapter = object : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): DocumentChoice = shown[position]
        override fun getItemId(position: Int): Long = getItem(position).reference.ordinal.toLong()
        override fun getView(position: Int, view: View?, parent: ViewGroup): View {
            val item = getItem(position)
            return (view as? UuActionRow ?: UuActionRow(parent.context)).apply {
                bind(item.title, item.description)
                isEnabled = true
                isFocusable = false
            }
        }
    }
    list.emptyView = empty
    list.adapter = adapter
    list.setOnItemClickListener { _, _, position, _ ->
        val reference = adapter.getItem(position).reference
        picker.dismiss()
        onSelect(reference)
    }
    search.doAfterTextChanged { input ->
        val query = input?.toString().orEmpty()
        shown = choices.filter { AgentReferenceDocument.matches(it.searchable, query) }
        adapter.notifyDataSetChanged()
        list.setSelection(0)
    }
    rootDialogToTab(picker, ownerTabId)
    picker.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    picker.belowTabs()
    picker.show()
    body.requestFocus()
}

package com.usefulunpacker

import androidx.appcompat.app.AppCompatActivity

/** Preview and editing share the same encoding picker, labels and choice ordering. */
internal fun textEncodingRow(activity: AppCompatActivity, initial: String, onSelect: (String) -> Unit): UuActionRow {
    val labels = intArrayOf(R.string.encoding_utf8, R.string.encoding_sjis, R.string.encoding_gbk, R.string.encoding_utf16)
        .map(activity::getString).toTypedArray()
    var selected = TEXT_ENCODINGS.indexOf(initial).coerceAtLeast(0)
    val row = UuActionRow(activity)
    fun bind() {
        row.bind(activity.getString(R.string.text_encoding_value, labels[selected]), navigation = true) {
            UuDialogBuilder(activity).setTitle(R.string.settings_text_encoding)
                .setSingleChoiceItems(labels, selected) { dialog, index ->
                    selected = index
                    bind()
                    dialog.dismiss()
                    onSelect(TEXT_ENCODINGS[index])
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show().also { it.keepTabsTappable() }
        }
    }
    bind()
    return row
}

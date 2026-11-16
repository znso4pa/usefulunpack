package com.usefulunpacker

/** Derived from ownership and attachment; a cached hidden flag cannot grant input. */
internal data class TabModalPresentation(val visible: Boolean, val acceptsInput: Boolean, val blocksBackground: Boolean)

internal fun tabModalPresentation(ownerTab: Int, activeTab: Int, attached: Boolean, top: Boolean): TabModalPresentation {
    val visible = ownerTab == activeTab
    return TabModalPresentation(visible, visible && attached && top, visible && attached)
}

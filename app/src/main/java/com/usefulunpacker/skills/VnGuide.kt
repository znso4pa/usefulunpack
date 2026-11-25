package com.usefulunpacker

internal object VnGuide {
    const val URI = "usefulunpack://guides/vn-v1"
    const val TITLE = "UsefulUnpack VN guide"
    val text: String get() = AgentReferenceDocument.VN_GUIDE.readText()
}

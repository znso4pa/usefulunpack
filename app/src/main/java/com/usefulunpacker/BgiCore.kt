package com.usefulunpacker

/**
 * BGI / Ethornell `.arc`（读-only）。
 *
 * 两种布局共用这个 key：v1 `Pack`+`File    `（最常见），v2 `BURI`+`KO ARC20`
 * （Buriko）。原生侧按包头自动区分，所以 UI 不需要让用户选。
 *
 * 封包未实现 —— 参考实现把这两种布局都标成只读，DSC 的编码器也没公开。
 */
object BgiCore {
    init { System.loadLibrary("archive_bgi_core") }
    external fun bgiExtract(tool: String, input: String, output: String): String?
    external fun bgiExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun bgiListEntries(input: String): String?
    external fun bgiExtractProgressCount(): Long
    external fun bgiExtractProgressTotal(): Long
    external fun bgiExtractProgressFileCount(): Long
    external fun bgiExtractProgressFileTotal(): Long
    external fun bgiExtractProgressName(): String?
    external fun bgiExtractCancel()
}

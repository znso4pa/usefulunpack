package com.usefulunpacker

/**
 * 音频转换的 JNI 桥（`libarchive_audio_core`）。
 *
 * 形状和 [CsoCore] 完全一致：操作返回 Boolean，进度靠五个静态量轮询，取消是
 * 一次置位调用。**选项走一个字符串**（`rate=44100;ch=2;depth=16;br=192;…`）
 * 而不是五个参数 —— JNI 签名越短越好，而且以后加一项不用动这里，也不用动
 * Rust 侧的 `#[no_mangle]` 符号名。协议见 `crates/audio-core/src/options.rs`。
 *
 * 返回 false 时 Rust 侧会抛 `java.io.IOException`，消息以 `audio: ` 开头。
 */
object AudioCore {
    init { System.loadLibrary("archive_audio_core") }

    external fun audioConvert(
        tool: String,
        input: String,
        output: String,
        target: String,
        options: String,
    ): Boolean

    external fun audioProgressCount(): Long
    external fun audioProgressTotal(): Long
    external fun audioProgressFileCount(): Long
    external fun audioProgressFileTotal(): Long
    external fun audioProgressName(): String?
    external fun audioCancel()
}

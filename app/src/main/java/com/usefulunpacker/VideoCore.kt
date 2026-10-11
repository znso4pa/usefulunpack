package com.usefulunpacker

/**
 * 视频转换的 JNI 桥（`libarchive_video_core`）。
 *
 * 形状和 [AudioCore] 完全一致：操作返回 Boolean，进度靠五个静态量轮询，取消是一次
 * 置位调用。**选项走一个字符串**，以后加参数不用动这里、也不用动 C 侧的符号名。
 *
 * 与音频那套的区别只有引擎：这条链是 ffmpeg 解（ASF/VC-1/WMV3/WMA）+ Android 编
 * （`AMediaCodec` 出 H.264/AAC）+ Android 封（`AMediaMuxer` 出 MP4）。所以它跟
 * `libarchive_audio_core` **不是同一个进度槽**，两边各用各的，互不干扰。
 *
 * 返回 false 时 C 侧会抛 `java.io.IOException`，消息以 `video: ` 开头。
 */
object VideoCore {
    init { System.loadLibrary("archive_video_core") }

    external fun videoConvert(
        tool: String,
        input: String,
        output: String,
        target: String,
        options: String,
    ): Boolean

    external fun videoProgressCount(): Long
    external fun videoProgressTotal(): Long
    external fun videoProgressFileCount(): Long
    external fun videoProgressFileTotal(): Long
    external fun videoProgressName(): String?
    external fun videoCancel()
}

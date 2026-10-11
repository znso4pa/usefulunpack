package com.usefulunpacker

import android.app.AlertDialog
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 一个可选的转换目标。
 *
 * `available` 为 false 的卡片渲染成灰色、点一下只提示「即将支持」——
 * 引擎还没接的格式不该假装能用。
 */
internal class MediaTarget(val fmt: String, val label: String, val available: Boolean)

/**
 * 能作为**转换源**的音频扩展名 —— 也就是引擎里真有解码器的那些格式。
 * 别的扩展名选音频目标只会拿到一个看不懂的解码错误，所以那些目标在界面上就是灰的。
 *
 * - `mp3` / `ogg` / `wav`：从第一天就在。
 * - `flac`：无损，`claxon` 只解不编。
 * - `m4a` / `m4b` / `mp4` / `aac`：AAC。前三个是 MP4 容器（会读 `elst` 掐掉编码器
 *   priming），`aac` 是裸 ADTS 基本流。`.mp4` 收在这里意味着**从视频里抽音轨**是
 *   合法请求 —— 解码器只挑 AAC 音轨，没有音轨的 MP4 会明确报错。
 * - `aif` / `aiff` / `aifc`：AIFF（大端），`aifc` 只有 `NONE` / `sowt` 两种压缩。
 * - `au` / `snd`：Sun/NeXT，支持 µ-law / A-law / 8·16·24·32 位线性 PCM。
 */
internal val AUDIO_SOURCE_EXTS = setOf(
    "mp3", "ogg", "wav",
    "flac",
    "m4a", "m4b", "mp4", "aac",
    "aif", "aiff", "aifc",
    "au", "snd",
)

/**
 * 音频目标的引擎已接通；视频的还没有。CLI（`uu audio`）也用它，别再写第二份。
 *
 * `aiff` / `au` 是无损容器（写大端 PCM），`aac` 出的是**裸 ADTS** 基本流 ——
 * `.m4a` 需要自己建 `moov/stbl/stsz/stco` 整棵样本表，那是另一件事，暂时不做。
 */
internal val AUDIO_TARGETS = setOf("mp3", "ogg", "wav", "aiff", "au", "aac")

/**
 * 能作为**视频转换源**的扩展名 —— 引擎里接了五个解复用器（ASF / AVI / FLV /
 * MPEG-PS / MPEG-TS）与 VC-1 / WMV1-3 / MSMPEG4 / MPEG-1·2 / H.263 / Sorenson /
 * MJPEG / VP6 解码器。
 *
 * `.mkv` / `.mp4` / `.webm` **不在这里**：它们里面装的通常是 H.264 或 VP8/VP9，
 * 而这两个解码器进不了 5 MB 的体积预算（VP8/VP9 光它自己就要 2.3 MB，还会连带
 * 把 H.264 的帧内预测 DSP 拖进来）。容器没接就别假装能用。
 *
 * `.ts` 既是 MPEG-TS 也是 TypeScript 的后缀 —— 这里按视频收。真拿一个 TypeScript
 * 文件进来，ffmpeg 的解复用器探不出来会直接报错，不会产出垃圾。
 */
internal val VIDEO_SOURCE_EXTS = setOf(
    "wmv", "asf",           // ASF
    "avi",                  // AVI
    "flv",                  // FLV
    "mpg", "mpeg", "vob",   // MPEG-PS
    "ts", "m2ts", "mts",    // MPEG-TS
)

/**
 * 视频目标只有 MP4。WMV **没有**编码器 —— ffmpeg 里没开，Android 也没有，
 * 所以那一格恒灰（它只是个「看得见但还不能用」的占位）。
 */
internal val VIDEO_TARGETS = setOf("mp4")

/**
 * 目标格式的**形状**（唯一真源）：`(栏标题, [(扩展名, 标签)])`。
 * 顺序即界面顺序：左栏音频、右栏视频。加一个格式只改这里。
 */
private val MEDIA_SHAPE: List<Pair<Int, List<Pair<String, String>>>> = listOf(
    R.string.media_col_audio to listOf(
        "mp3" to "MP3",
        "ogg" to "OGG",
        "wav" to "WAV",
        "aiff" to "AIFF",
        "au" to "AU",
        "aac" to "AAC",
    ),
    R.string.media_col_video to listOf("wmv" to "WMV", "mp4" to "MP4"),
)

/**
 * 按源文件算出这一屏真正可用的目标。
 *
 * 可用性是**源文件的函数**，不是常量：同一个 MP3 目标，源是 `.wav` 时可用、
 * 源是 `.mp4` 时不可用。把可用性写死在格式表里就等于承诺了引擎没有的能力。
 */
internal fun mediaColumnsFor(src: File): List<Pair<Int, List<MediaTarget>>> {
    val ext = src.extension.lowercase()
    val canDecodeAudio = ext in AUDIO_SOURCE_EXTS
    val canDecodeVideo = ext in VIDEO_SOURCE_EXTS
    return MEDIA_SHAPE.map { (header, items) ->
        header to items.map { (fmt, label) ->
            val ok = when {
                fmt in AUDIO_TARGETS -> canDecodeAudio
                fmt in VIDEO_TARGETS -> canDecodeVideo
                else -> false
            }
            MediaTarget(fmt, label, available = ok)
        }
    }
}

/**
 * 长按 → 文件转换：左右双栏的目标格式选择器（一栏音频、一栏视频）。
 *
 * 复用 [formatCell] 的卡片与 [buildFormatPickerDialog] 的外壳，所以观感、tab 归属、
 * 不透明面板这些行为跟分组网格那个选择器完全一致，不会各走各的。
 */
internal fun showMediaConvertDialog(activity: MainActivity, file: File) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()

    val columns = mediaColumnsFor(file)
    // 选中目标后要先关掉这一层再开下一层（选项面板 / 转换），否则选择器会一直留在
    // 底下 —— 转换都跑完了它还盖着屏幕。showFormatPicker 早就是这个约定。
    var dialogRef: AlertDialog? = null
    val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    columns.forEachIndexed { ci, (headerRes, targets) ->
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(activity).apply {
            text = activity.getString(headerRes)
            setTextColor(C["accent"]!!)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(6), dp(4), dp(6), dp(6))
        })
        targets.forEach { t ->
            col.addView(
                formatCell(activity, t.label, columns = 1, density = density, available = t.available) {
                    // 不可用的目标只是「还没接」：提示一下就留着面板，让用户能改选。
                    if (!t.available) {
                        Toast.makeText(activity, activity.getString(R.string.media_soon), Toast.LENGTH_SHORT).show()
                        return@formatCell
                    }
                    dialogRef?.dismiss()
                    convertMedia(activity, file, t.fmt)
                },
                LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    val m = dp(3)
                    topMargin = m
                    bottomMargin = m
                },
            )
        }
        // 两栏等宽：右栏多留一点左边距当栏间距。
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            if (ci > 0) marginStart = dp(8)
        })
    }

    val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    content.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
    // 源文件不是我们能解码的音频/视频时，对应那一栏全是灰的 —— 说清楚为什么，
    // 否则用户只会觉得「按钮坏了」。
    val ext = file.extension.lowercase()
    var pad = 12
    fun addHint(text: String) {
        content.addView(TextView(activity).apply {
            this.text = text
            setTextColor(C["hint"]!!)
            textSize = 11f
            setPadding(dp(6), dp(pad), dp(6), dp(2))
        })
        pad = 2
    }
    if (ext !in AUDIO_SOURCE_EXTS) {
        addHint(activity.getString(R.string.media_unsupported_source, file.extension.uppercase()))
    }
    if (ext !in VIDEO_SOURCE_EXTS) {
        addHint(activity.getString(R.string.media_unsupported_video_source, file.extension.uppercase()))
    }
    if (ext in VIDEO_SOURCE_EXTS) {
        // 源编码五花八门（VC-1 / WMV3 / MSMPEG4 / MPEG4…），用户会想知道变成什么。
        addHint(activity.getString(R.string.media_video_note))
    }

    val scroller = ScrollView(activity).apply {
        addView(content, LinearLayout.LayoutParams(MATCH, WRAP))
        // Honor/EMUI 在自定义 View 上画滚动条会 NPE —— 关掉。
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }
    dialogRef = activity.buildFormatPickerDialog(activity.getString(R.string.media_convert_title), scroller)
}

/**
 * 转换入口 —— 唯一的派发点。按 [targetFmt] 分派到对应实现；
 * 选择器里 `available = false` 的目标根本走不到这里。
 *
 * 音频先过一道选项面板（采样率 / 声道 / 位深 / 码率），确认后才开跑。
 * 视频目前没有选项面板：码率按分辨率自动取，先让链路跑通。
 */
internal fun convertMedia(activity: MainActivity, file: File, targetFmt: String) {
    if (targetFmt in AUDIO_TARGETS) {
        showAudioConvertOptions(activity, file, targetFmt) { opts ->
            activity.convertAudio(file, targetFmt, opts)
        }
        return
    }
    if (targetFmt in VIDEO_TARGETS) {
        activity.convertVideo(file)
        return
    }
    Toast.makeText(activity, activity.getString(R.string.media_engine_pending), Toast.LENGTH_SHORT).show()
}

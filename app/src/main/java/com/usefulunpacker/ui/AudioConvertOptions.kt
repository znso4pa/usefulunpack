package com.usefulunpacker

import android.app.AlertDialog
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 音频转换的选项。字段与 Rust 侧 `AudioOptions`（`crates/audio-core/src/options.rs`）
 * 一一对应，`0` 一律表示「保持源文件的值」或「用格式默认值」。
 */
internal data class AudioConvertOptions(
    /** 目标采样率 Hz；0 = 保持源采样率。 */
    val rate: Int = 0,
    /** 目标声道数 1/2；0 = 保持源声道数。 */
    val channels: Int = 0,
    /** WAV 位深 8/16/24/32（32 一律按浮点写）。只有 WAV 输出读它。 */
    val depth: Int = 16,
    /** MP3 / OGG 码率 kbps；0 = 用格式默认值。 */
    val bitrate: Int = 0,
    /** MP3 走 VBR 而不是 CBR。 */
    val vbr: Boolean = false,
    /** MP3 VBR 质量档 0（最好）… 9（最小）。 */
    val vbrQuality: Int = 2,
) {
    /** 压成 Rust 侧认的那**一个**字符串 —— 选项协议只有这一处实现。 */
    fun toJni(): String = buildString {
        append("rate=").append(rate)
        append(";ch=").append(channels)
        append(";depth=").append(depth)
        append(";br=").append(bitrate)
        append(";vbr=").append(if (vbr) 1 else 0)
        append(";q=").append(vbrQuality)
    }

    /** 某一组当前选中的值。`vbr` 组用 0/1 编码。 */
    private fun valueOf(key: String): Int = when (key) {
        "rate" -> rate
        "ch" -> channels
        "depth" -> depth
        "br" -> bitrate
        "vbr" -> if (vbr) 1 else 0
        else -> 0
    }

    fun with(key: String, value: Int): AudioConvertOptions = when (key) {
        "rate" -> copy(rate = value)
        "ch" -> copy(channels = value)
        "depth" -> copy(depth = value)
        "br" -> copy(bitrate = value)
        "vbr" -> copy(vbr = value == 1)
        else -> this
    }

    /** 某个 key 当前选中值，供面板渲染高亮。 */
    fun selectedValue(key: String): Int = valueOf(key)
}

/** 一个互斥选项：`value` 是要压进选项串的数。`available = false` 表示这个目标格式
 *  下该选项没有意义（灰掉、点了不动），而不是「点了之后报错」。 */
private class OptChoice(val label: String, val value: Int, val available: Boolean = true)

/** 一个选项组（标题 + 互斥选项）。`key` 与选项串的键同名。 */
private class OptGroup(val key: String, val titleRes: Int, val choices: List<OptChoice>)

/** UI 上给出的采样率档位。Rust 侧的白名单更宽（含 88200），这里只挑常用的。 */
private val OPT_RATES = listOf(8000, 11025, 16000, 22050, 24000, 32000, 44100, 48000, 96000, 192_000)

/**
 * AAC 的采样率档位 = AAC 的标准速率 **∩** 引擎的采样率白名单（[OPT_RATES]）。
 *
 * 两层约束叠在一起，哪一层都不能省：
 * - **AAC 侧**：`samplingFrequencyIndex` 只有 4 位，13 个标准值（外加一个只有 MP4 的
 *   `esds` 用得上的 24 位转义码）；**ADTS 头里没有转义码的位置**。所以 192 kHz 这种
 *   「合法但非标准」的速率在 AAC 输出上编不出来，引擎会明确报错（`encode.rs` 的
 *   `AAC_STANDARD_RATES`，同一份清单）。
 * - **引擎侧**：`AudioOptions::sanitize` 会把白名单外的采样率**静默吸附**到最近一档
 *   （12000 → 11025、64000 → 48000、7350 → 8000）。UI 摆出一个会被悄悄改掉的档位，
 *   比不摆更糟 —— 用户以为他选了 64 kHz。
 *
 * 用 `filter` 而不是手抄一份交集：`OPT_RATES` 变了这里不会漂。
 */
private val OPT_AAC_RATES =
    listOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)
        .filter { it in OPT_RATES }

/** MP3 Layer III 的常用 CBR 档位（引擎会夹到 8..320 并吸附到合法值）。 */
private val OPT_BITRATES = listOf(64, 96, 128, 160, 192, 224, 256, 320)

/** 有「位深」这个选项的**无损容器**目标。 */
private val LOSSLESS_TARGETS = setOf("wav", "aiff", "au")

/** 有「码率」这个选项的**有损**目标。 */
private val LOSSY_TARGETS = setOf("mp3", "ogg", "aac")

private const val OPT_COLUMNS = 4

/**
 * 该目标格式下**真正有意义**的选项组。
 *
 * 按格式给而不是全给：无损容器（WAV / AIFF / AU）没有「码率」，有损编码
 * （MP3 / OGG / AAC）没有「位深」。把不适用的选项也摆出来，用户选完之后发现
 * 没效果，比不给他选更糟。
 */
private fun optionGroupsFor(activity: AppCompatActivity, fmt: String): List<OptGroup> {
    val keep = activity.getString(R.string.media_opt_keep)
    val groups = mutableListOf<OptGroup>()

    val rates = if (fmt == "aac") OPT_AAC_RATES else OPT_RATES
    groups += OptGroup(
        "rate", R.string.media_opt_rate,
        listOf(OptChoice(keep, 0)) + rates.map { OptChoice(it.toString(), it) },
    )
    groups += OptGroup(
        "ch", R.string.media_opt_channels,
        listOf(
            OptChoice(keep, 0),
            // OGG 编不了单声道：`rusty_vorbis` 只内嵌了一套 stereo setup，
            // 喂单声道进去会报 `bad coupling channels`（真机上炸过一次）。
            // 引擎侧对「保持源 + 单声道源」是升混，但对**明确选单声道**是报错 ——
            // 所以这里直接灰掉，别让用户选一个注定失败的值。
            OptChoice(
                activity.getString(R.string.media_opt_mono), 1,
                available = fmt != "ogg",
            ),
            OptChoice(activity.getString(R.string.media_opt_stereo), 2),
        ),
    )

    if (fmt in LOSSLESS_TARGETS) {
        groups += OptGroup(
            "depth", R.string.media_opt_depth,
            listOf(8, 16, 24, 32).map {
                OptChoice(activity.getString(R.string.media_opt_bits, it), it)
            },
        )
    } else if (fmt in LOSSY_TARGETS) {
        groups += OptGroup(
            "br", R.string.media_opt_bitrate,
            listOf(OptChoice(activity.getString(R.string.media_opt_default), 0)) +
                OPT_BITRATES.map { OptChoice("$it kbps", it) },
        )
        // VBR 只有 MP3 编码器支持；OGG 本来就是 VBR，AAC 这边是 CBR，都没有这个开关。
        if (fmt == "mp3") {
            groups += OptGroup(
                "vbr", R.string.media_opt_mode,
                listOf(
                    OptChoice(activity.getString(R.string.media_opt_cbr), 0),
                    OptChoice(activity.getString(R.string.media_opt_vbr), 1),
                ),
            )
        }
    }
    return groups
}

/**
 * 转换前的选项面板：采样率 / 声道 / 位深 / 码率 / 编码模式。
 *
 * 每组的选项按 [OPT_COLUMNS] 列铺成卡片网格，选中的那张用 accent 描边 ——
 * 卡片本身复用 [formatCell]，所以观感和目标格式选择器是同一套。
 */
internal fun showAudioConvertOptions(
    activity: AppCompatActivity,
    file: File,
    fmt: String,
    onStart: (AudioConvertOptions) -> Unit,
) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()

    val groups = optionGroupsFor(activity, fmt)
    var opts = AudioConvertOptions()

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    /** 重画整块面板。选项互斥且状态只在 `opts` 里，重画比逐格改样式可靠。 */
    fun render() {
        root.removeAllViews()
        groups.forEachIndexed { gi, g ->
            root.addView(TextView(activity).apply {
                text = activity.getString(g.titleRes)
                setTextColor(C["accent"]!!)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(6), if (gi == 0) dp(4) else dp(14), dp(6), dp(6))
            })
            val cur = opts.selectedValue(g.key)
            g.choices.chunked(OPT_COLUMNS).forEach { rowItems ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                rowItems.forEach { c ->
                    row.addView(
                        formatCell(
                            activity, c.label, OPT_COLUMNS, density,
                            available = c.available,
                            selected = c.value == cur,
                        ) {
                            if (!c.available) return@formatCell
                            opts = opts.with(g.key, c.value)
                            render()
                        },
                        LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                            val m = dp(3)
                            marginStart = m; marginEnd = m; topMargin = m; bottomMargin = m
                        },
                    )
                }
                repeat(OPT_COLUMNS - rowItems.size) {
                    row.addView(android.view.View(activity), LinearLayout.LayoutParams(0, WRAP, 1f))
                }
                root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }

        // 每个目标格式的上游限制写在用户看得见的地方，而不是让他选完之后吃一个失败。
        // 这些结论在引擎侧都有对应的代码，别只改一边：
        //   OGG  → encode.rs 的 VORBIS_MEASURED / lib.rs 的 enforce_target_channels
        //   AAC  → encode.rs 的 AAC_STANDARD_RATES
        //   AIFF/AU → aiff.rs / au.rs 的 encode（32 位写的是整数，不是浮点）
        val notes = when (fmt) {
            "ogg" -> listOf(R.string.media_opt_ogg_note, R.string.media_opt_ogg_mono)
            "aac" -> listOf(R.string.media_opt_aac_note)
            "aiff", "au" -> listOf(R.string.media_opt_lossless_note)
            else -> emptyList()
        }
        for (note in notes) {
            root.addView(TextView(activity).apply {
                text = activity.getString(note)
                setTextColor(C["hint"]!!)
                textSize = 11f
                setPadding(dp(6), dp(8), dp(6), dp(0))
            })
        }
    }
    render()

    val scroller = ScrollView(activity).apply {
        addView(root)
        // Honor/EMUI 在自定义 View 上画滚动条会 NPE —— 关掉。
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    val dialog = AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.media_opt_title, fmt.uppercase()))
        .setView(scroller)
        .setPositiveButton(activity.getString(R.string.media_opt_start)) { _, _ ->
            onStart(opts)
        }
        .setNegativeButton(activity.getString(R.string.action_cancel), null)
        .create()
    dialog.belowTabs(fitContent = true)
    androidx.appcompat.content.res.AppCompatResources
        .getDrawable(activity, R.drawable.dialog_bg_rounded)
        ?.let { dialog.window?.setBackgroundDrawable(it) }
    dialog.show()
}

package com.usefulunpacker

import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * Lightweight Markdown / RTF rendering for the text preview — no external
 * dependency, deliberately small. Markdown is turned into a [SpannableString]
 * (headings, bold/italic, inline code, fenced code blocks, lists, links); the
 * character offsets are preserved so search-highlight spans still line up.
 * RTF is stripped to readable plain text (control words removed).
 */

/** True when [ext] should offer rich rendering in the preview. */
fun isRichTextExt(ext: String): Boolean =
    ext in setOf("md", "markdown", "rtf")

/** Renders [text] as Markdown into a SpannableString (same char offsets as the
 *  input, so highlight spans apply on top). Falls back to plain text. */
fun renderMarkdown(text: String): CharSequence {
    if (text.isEmpty()) return text
    val sb = StringBuilder()
    val spans = mutableListOf<Triple<Int, Int, Any>>() // start, end, span
    val lines = text.split('\n')
    var inCodeBlock = false
    var codeFence = ""
    for (line in lines) {
        val trimmed = line.trim()
        // Fenced code block toggle: ``` or ~~~ .
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            if (!inCodeBlock) {
                inCodeBlock = true
                codeFence = trimmed.take(3)
            } else {
                inCodeBlock = false
            }
            sb.append('\n')
            continue
        }
        if (inCodeBlock) {
            val start = sb.length
            sb.append(line).append('\n')
            spans.add(Triple(start, sb.length, BackgroundColorSpan(0xFF1d2733.toInt())))
            spans.add(Triple(start, sb.length, android.text.style.TypefaceSpan("monospace")))
            continue
        }
        // Headings: # ## ### …
        val head = trimmed.takeWhile { it == '#' }
        if (head.length in 1..6 && head.length < trimmed.length && trimmed[head.length] == ' ') {
            val start = sb.length
            val body = trimmed.substring(head.length + 1)
            sb.append(body).append('\n')
            val size = 1.25f - head.length * 0.08f
            spans.add(Triple(start, sb.length, RelativeSizeSpan(size.coerceAtLeast(1.05f))))
            spans.add(Triple(start, sb.length, StyleSpan(Typeface.BOLD)))
            continue
        }
        // Horizontal rule.
        if (trimmed.all { it == '-' } && trimmed.length >= 3) {
            sb.append("──────────────\n")
            continue
        }
        // Blockquote.
        if (trimmed.startsWith(">")) {
            val start = sb.length
            sb.append(renderInline(trimmed.trimStart('>').trimStart(), sb, spans))
                .append('\n')
            spans.add(Triple(start, sb.length, StyleSpan(Typeface.ITALIC)))
            continue
        }
        // List items: "- ", "* ", "+ ", "1. " (with indent continuation kept simple).
        val bullet = Regex("^([-*+]|\\d+[.)])\\s+").find(trimmed)
        if (bullet != null) {
            val body = trimmed.substring(bullet.range.last + 1)
            sb.append("   •  ").append(renderInline(body, sb, spans)).append('\n')
            continue
        }
        sb.append(renderInline(line, sb, spans)).append('\n')
    }
    val sp = SpannableString(sb)
    for ((start, end, span) in spans) {
        if (start in 0..sb.length && end in start..sb.length) {
            sp.setSpan(span, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return sp
}

/** Renders inline Markdown (`**bold**`, `*italic*`, `` `code` ``, `[text](url)`)
 *  appending the plain text to [sb] and registering spans on it. */
private fun renderInline(line: String, sb: StringBuilder, spans: MutableList<Triple<Int, Int, Any>>): String {
    var out = StringBuilder()
    var i = 0
    val n = line.length
    while (i < n) {
        val c = line[i]
        // Inline code `…`
        if (c == '`') {
            val end = line.indexOf('`', i + 1)
            if (end > i) {
                val start = sb.length + out.length
                out.append(line.substring(i + 1, end))
                spans.add(Triple(start, sb.length + out.length, BackgroundColorSpan(0xFF1d2733.toInt())))
                spans.add(Triple(start, sb.length + out.length, android.text.style.TypefaceSpan("monospace")))
                i = end + 1
                continue
            }
        }
        // Bold **…** / __…__  (check ** first so * doesn't eat it)
        if (i + 1 < n && c == '*' && line[i + 1] == '*') {
            val end = line.indexOf("**", i + 2)
            if (end > i) {
                val start = sb.length + out.length
                out.append(line.substring(i + 2, end))
                spans.add(Triple(start, sb.length + out.length, StyleSpan(Typeface.BOLD)))
                i = end + 2
                continue
            }
        }
        if (i + 1 < n && c == '_' && line[i + 1] == '_') {
            val end = line.indexOf("__", i + 2)
            if (end > i) {
                val start = sb.length + out.length
                out.append(line.substring(i + 2, end))
                spans.add(Triple(start, sb.length + out.length, StyleSpan(Typeface.BOLD)))
                i = end + 2
                continue
            }
        }
        // Italic *…*
        if (c == '*') {
            val end = line.indexOf('*', i + 1)
            if (end > i) {
                val start = sb.length + out.length
                out.append(line.substring(i + 1, end))
                spans.add(Triple(start, sb.length + out.length, StyleSpan(Typeface.ITALIC)))
                i = end + 1
                continue
            }
        }
        if (c == '_') {
            val end = line.indexOf('_', i + 1)
            if (end > i) {
                val start = sb.length + out.length
                out.append(line.substring(i + 1, end))
                spans.add(Triple(start, sb.length + out.length, StyleSpan(Typeface.ITALIC)))
                i = end + 1
                continue
            }
        }
        // Link [text](url)
        if (c == '[') {
            val close = line.indexOf(']', i + 1)
            if (close > i && close + 1 < n && line[close + 1] == '(') {
                val pend = line.indexOf(')', close + 1)
                if (pend > close) {
                    val start = sb.length + out.length
                    val label = line.substring(i + 1, close)
                    out.append(label)
                    spans.add(Triple(start, sb.length + out.length, StyleSpan(Typeface.BOLD)))
                    i = pend + 1
                    continue
                }
            }
        }
        out.append(c)
        i += 1
    }
    return out.toString()
}

/**
 * Strips RTF control words / groups to readable plain text: `\par`/`\line`
 * become newlines, `\tab` a tab, `\'hh` a raw byte, `\uN?` a unicode char.
  * Control words (and their numeric args) are dropped; `\b`/`\i` toggles are
  * mapped to bold/italic spans so styled RTF text keeps its emphasis instead of
  * becoming a flat string. Simple, lossy, good enough to make a .rtf readable.
  */
fun stripRtf(raw: String): CharSequence {
    if (raw.isBlank()) return raw
    val sb = StringBuilder()
    val spans = mutableListOf<Triple<Int, Int, Any>>() // start, end, span
    var i = 0
    val n = raw.length
    var bold = false
    var italic = false
    var spanStart = 0
    // Group braces (`{...}`) scope styles in RTF: `{\b bold}` applies bold only
    // inside the group, and the `}` restores whatever was active before. Track a
    // stack so nested groups restore correctly instead of leaking the style.
    val styleStack = ArrayDeque<Pair<Boolean, Boolean>>()
    fun markSpan() {
        // Close any open style span at the current buffer position.
        if (bold) spans.add(Triple(spanStart, sb.length, StyleSpan(Typeface.BOLD)))
        else if (italic) spans.add(Triple(spanStart, sb.length, StyleSpan(Typeface.ITALIC)))
    }
    fun setStyle(b: Boolean, it: Boolean) {
        markSpan()
        bold = b; italic = it
        spanStart = sb.length
    }
    while (i < n) {
        val c = raw[i]
        when {
            c == '{' -> {
                // Push current style; group contents may override, restored on `}`.
                styleStack.addLast(bold to italic)
                markSpan()
                spanStart = sb.length
                i++
            }
            c == '}' -> {
                markSpan()
                val prev = styleStack.removeLastOrNull()
                bold = prev?.first ?: false
                italic = prev?.second ?: false
                spanStart = sb.length
                i++
            }
            c == '\\' && i + 1 < n -> {
                val cmd = StringBuilder()
                var j = i + 1
                // Read the control word (letters).
                while (j < n && raw[j].isLetter()) { cmd.append(raw[j]); j++ }
                // `\b0` / `\i0` are style toggles whose digit is PART of the
                // control word — keep it in cmd so "b0" means "off", not "b"
                // followed by an ignored argument. Other words (fs24, \par…)
                // keep the digit as an argument (dropped).
                if ((cmd.toString() == "b" || cmd.toString() == "i") && j < n &&
                    (raw[j] == '0' || raw[j] == '1')) {
                    cmd.append(raw[j]); j++
                }
                // Optional signed numeric argument.
                var minus = false
                if (j < n && (raw[j] == '-' || raw[j] == '+')) { minus = raw[j] == '-'; j++ }
                while (j < n && raw[j].isDigit()) { j++ }
                when (cmd.toString()) {
                    // Style toggles: close the previous run, flip, reopen.
                    "b" -> setStyle(true, false)
                    "i" -> setStyle(false, true)
                    "b0" -> setStyle(false, italic)
                    "b1" -> setStyle(true, italic)
                    "i0" -> setStyle(bold, false)
                    "i1" -> setStyle(bold, true)
                    "u" -> {
                        val num = raw.substring(if (minus) i + 3 else i + 2, j).toIntOrNull()
                        if (num != null) {
                            val ch = (num and 0xFFFF).toChar()
                            if (ch != '\uFFFD') sb.append(ch)
                        }
                        if (j < n) j++ // skip the fallback char
                    }
                    "par", "line", "page" -> sb.append('\n')
                    "tab" -> sb.append('\t')
                    "'" -> {
                        // \'hh : single raw byte (hex)
                        if (j + 2 <= n) {
                            val hh = raw.substring(j, j + 2).toIntOrNull(16)
                            if (hh != null) {
                                val b = hh.toByte()
                                sb.append(if (b in 0x20..0x7e) b.toInt().toChar() else ' ')
                            }
                            j += 2
                        }
                    }
                    else -> { /* drop other control words */ }
                }
                i = j
            }
            else -> { sb.append(c); i++ }
        }
    }
    markSpan()
    // The final text collapses 3+ newlines to 2 and trims whitespace — this
    // SHIFTS every span offset. Build the final string while recording, for
    // each output position, the source position it came from, then remap spans
    // through that map instead of just filtering (which dropped/misaligned
    // spans after trailing `\par`s or leading whitespace).
    val src = sb.toString()
    val out = StringBuilder(src.length)
    val srcIndex = IntArray(src.length) // srcIndex[outPos] = srcPos (strictly ascending)
    var pos = 0
    while (pos < src.length) {
        if (src[pos] == '\n') {
            var q = pos
            while (q < src.length && src[q] == '\n') q++
            val keep = if (q - pos >= 3) 2 else q - pos
            for (k in 0 until keep) {
                srcIndex[out.length] = pos + k
                out.append('\n')
            }
            pos = q
        } else {
            srcIndex[out.length] = pos
            out.append(src[pos])
            pos++
        }
    }
    var text = out.toString()
    var start = 0
    var end = text.length
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    text = text.substring(start, end)

    fun lowerBound(lo: Int, hi: Int, x: Int): Int {
        var l = lo; var h = hi
        while (l < h) {
            val m = (l + h) ushr 1
            if (srcIndex[m] < x) l = m + 1 else h = m
        }
        return l
    }
    val outSp = SpannableString(text)
    for ((s, e, span) in spans) {
        if (e <= s) continue
        val os = lowerBound(start, end, s)
        if (os >= end) continue
        val oe = lowerBound(start, end, e).coerceAtMost(end)
        if (oe <= os) continue
        outSp.setSpan(span, os - start, oe - start, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return outSp
}

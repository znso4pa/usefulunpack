package com.usefulunpacker

/** Null means unsupported/ambiguous grammar: skip the whole line, never compare a partial parse. */
internal fun localizationArguments(text: String, profile: String, active: () -> Unit = {}): Map<String, String>? =
    when (profile) {
        "named" -> NamedArguments(text, active).parse()
        "printf" -> printfArguments(text, active)
        else -> null
    }

private val fieldPart = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
private val fieldIndex = Regex("[0-9]{1,6}")

/** Bounded Python-format dependency parser. It never evaluates attributes, indexes or expressions. */
private class NamedArguments(private val text: String, private val active: () -> Unit) {
    private val fields = linkedMapOf<String, String>()
    private var automatic = 0
    private var numbering: Boolean? = null // true: automatic; false: explicit numeric

    fun parse(): Map<String, String>? = if (template(0, text.length, 0)) fields else null

    private fun template(start: Int, end: Int, depth: Int): Boolean {
        var index = start
        while (index < end) {
            if (index % 1024 == 0) active()
            when (text[index]) {
                '{', '}' -> {
                    if (index + 1 < end && text[index + 1] == text[index]) { index += 2; continue }
                    if (text[index] == '}' || depth >= 2) return false
                    index = field(index + 1, end, depth) ?: return false
                }
                else -> index++
            }
        }
        return true
    }

    private fun field(start: Int, end: Int, depth: Int): Int? {
        active()
        var index = start
        while (index < end && text[index] !in "!:}") {
            if (text[index] == '{' || index - start > 512) return null
            index++
        }
        if (index == end) return null
        val name = canonical(text.substring(start, index)) ?: return null
        fields[name] = "field"
        if (fields.size > 256) return null
        if (text[index] == '!') {
            index++
            if (index >= end || text[index] !in "rsa") return null
            index++
            if (index >= end || text[index] !in ":}") return null
        }
        if (text[index] == '}') return index + 1
        // Locate the complete format spec first; escaped braces are interpreted by its own template.
        val specStart = ++index
        var braces = 0
        while (index < end) {
            if (index % 1024 == 0) active()
            when (text[index]) {
                '{' -> braces++
                '}' -> if (braces == 0) {
                    return if (template(specStart, index, depth + 1)) index + 1 else null
                } else braces--
            }
            index++
        }
        return null
    }

    private fun canonical(raw: String): String? {
        if (raw.isEmpty()) {
            if (numbering == false) return null
            numbering = true
            return (automatic++).toString()
        }
        var end = raw.indexOfFirst { it == '.' || it == '[' }.let { if (it < 0) raw.length else it }
        val root = raw.substring(0, end)
        val numeric = fieldIndex.matches(root)
        if (!numeric && !fieldPart.matches(root)) return null
        if (numeric) {
            if (numbering == true) return null
            numbering = false
        }
        val result = StringBuilder(if (numeric) root.toInt().toString() else root)
        while (end < raw.length) {
            val start = end + 1
            when (raw[end]) {
                '.' -> {
                    end = start
                    while (end < raw.length && raw[end] != '.' && raw[end] != '[') end++
                    val attr = raw.substring(start, end)
                    if (!fieldPart.matches(attr)) return null
                    result.append('.').append(attr)
                }
                '[' -> {
                    end = raw.indexOf(']', start)
                    if (end < 0) return null
                    val key = raw.substring(start, end)
                    val number = fieldIndex.matches(key)
                    if (!number && !fieldPart.matches(key)) return null
                    result.append('[').append(if (number) key.toInt().toString() else key).append(']')
                    end++
                }
                else -> return null
            }
        }
        return result.toString()
    }
}

private val cConversion = Regex("%([0-9]{1,6}\\$)?([-+#0 ]{0,8})([0-9]{1,6}|\\*(?:[0-9]{1,6}\\$)?)?(?:\\.([0-9]{0,6}|\\*(?:[0-9]{1,6}\\$)?))?(hh|ll|[hlLjzt])?([diuoxXfFeEgGaAcspn])")

/** POSIX argument positions/types, independent of presentation width, precision and reference order. */
private fun printfArguments(text: String, active: () -> Unit): Map<String, String>? {
    val arguments = linkedMapOf<String, String>()
    var numbered: Boolean? = null
    var implicit = 0
    var index = 0
    fun add(position: String, type: String): Boolean {
        val explicit = position.isNotEmpty()
        if (numbered != null && numbered != explicit) return false
        numbered = explicit
        val number = if (explicit) position.removeSuffix("$").toIntOrNull() ?: return false else ++implicit
        if (number !in 1..128) return false
        val key = number.toString()
        val previous = arguments.putIfAbsent(key, type)
        return previous == null || previous == type
    }
    while (index < text.length) {
        if (index % 1024 == 0) active()
        if (text[index] != '%') { index++; continue }
        if (text.startsWith("%%", index)) { index += 2; continue }
        val match = cConversion.matchAt(text, index) ?: return null
        val (position, flags, width, precision, length, conversion) = match.destructured
        val kind = conversion.single()
        val hasPrecision = match.groups[4] != null
        val type = when (kind) {
            'd', 'i', 'u', 'o', 'x', 'X', 'n' -> {
                if (length == "L") return null
                val base = when (length) { "", "h", "hh" -> "int"; else -> length + "int" }
                when {
                    kind == 'n' -> "pointer:" + length + "signed-int"
                    kind in "uoxX" && length !in setOf("h", "hh") -> "unsigned:" + base
                    else -> "signed:" + base
                }
            }
            'f', 'F', 'e', 'E', 'g', 'G', 'a', 'A' -> when (length) {
                "", "l" -> "double"
                "L" -> "long-double"
                else -> return null
            }
            's' -> when (length) { "" -> "string"; "l" -> "wide-string"; else -> return null }
            'c' -> when (length) { "" -> "signed:int"; "l" -> "wide-char"; else -> return null }
            'p' -> if (length.isEmpty()) "pointer" else return null
            else -> return null
        }
        if (kind == 'n' && (flags.isNotEmpty() || width.isNotEmpty() || hasPrecision)) return null
        if (kind in "csp" && flags.any { it != '-' }) return null
        if (kind in "cp" && hasPrecision) return null
        if (kind in "uoxX" && flags.any { it == '+' || it == ' ' }) return null
        if (kind in "diu" && '#' in flags) return null
        for (size in listOf(width, precision)) if (size.startsWith('*')) {
            if (!add(size.removePrefix("*"), "signed:int")) return null
        }
        if (!add(position, type)) return null
        index += match.value.length
    }
    // POSIX numbered conversions cannot leave holes in the argument list.
    if (numbered == true && (1..arguments.size).any { it.toString() !in arguments }) return null
    return arguments
}

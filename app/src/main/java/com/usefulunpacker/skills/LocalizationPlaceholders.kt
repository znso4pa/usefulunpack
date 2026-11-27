package com.usefulunpacker

/** Null means unsupported/ambiguous grammar: skip the whole line, never compare a partial parse. */
internal fun localizationArguments(text: String, profile: String, active: () -> Unit = {}): Map<String, String>? =
    when (profile) {
        "named" -> namedArguments(text, active)
        "printf" -> printfArguments(text, active)
        else -> null
    }

private val simpleField = Regex("\\{([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*|[0-9]{1,6})}")

/** Explicit simple Python format fields only. Conversions, indexing and format specs need another profile. */
private fun namedArguments(text: String, active: () -> Unit): Map<String, String>? {
    val fields = linkedMapOf<String, String>()
    var index = 0
    while (index < text.length) {
        if (index % 1024 == 0) active()
        when {
            text.startsWith("{{", index) || text.startsWith("}}", index) -> index += 2
            text[index] == '{' -> {
                val match = simpleField.matchAt(text, index) ?: return null
                if (match.value.length > 130) return null
                val field = match.groupValues[1]
                fields[if (field.all(Char::isDigit)) field.toInt().toString() else field] = "field"
                index += match.value.length
            }
            text[index] == '}' -> return null
            else -> index++
        }
    }
    // Repeating a named field or changing its order does not require a different argument.
    return fields
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

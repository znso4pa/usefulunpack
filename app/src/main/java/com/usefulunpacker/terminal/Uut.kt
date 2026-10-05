package com.usefulunpacker

/**
 * UUT —— uu 的迷你脚本语言（6.2 提前落地）。
 *
 * 纯文本、行式；语法刻意贴着 `.bat` 的习惯，只加两件它做不到的事：
 * 变量替换与 `$(...)` 输出捕获（`.bat` 拿不到子命令输出，所以它没法写
 * `if answer = xp3`）。
 *
 * ```
 * # 注释
 * set f = game.xp3
 * set answer = $(uu info $f)
 * if answer = xp3 then uu x $f
 * for a in *.zip
 *   uu l $a > $a.list.txt
 * end
 * ```
 *
 * **能力边界（安全不变量）**：脚本里只允许 `uu` 子命令与 `ls / cd / pwd / echo`
 * —— 不许任意 `sh -c`，否则就是套娃 shell。
 *
 * 本文件是纯解析层（无 Android 依赖）：[parse] 产出 AST，[expandVars] 负责
 * `$name` 替换。执行在 TerminalPanel（复用终端的分段/管道/重定向执行器）。
 */
internal sealed class UutStmt {
    /** 一行普通命令（原始文本，运行时才展开变量并分词）。[line] 供报错。 */
    class Cmd(val line: Int, val raw: String) : UutStmt()

    /** `set <名字> = <值>`；值以 `$(` 开头并以 `)` 结尾 = 命令捕获。 */
    class Set(val line: Int, val name: String, val rawValue: String) : UutStmt()

    /** `if <变量> [!=] <值> then <命令>`（单行形态）。 */
    class IfInline(val line: Int, val varName: String, val negate: Boolean, val value: String, val rawCmd: String) : UutStmt()

    /** `for <变量> in <模式...>` … `end`。 */
    class For(val line: Int, val varName: String, val rawPatterns: String, val body: List<UutStmt>) : UutStmt()
}

internal class UutParseException(val line: Int, val reason: String) : Exception(reason)

internal object UutParser {

    /** 允许出现在脚本里的命令名（第一个 token）。 */
    val ALLOWED_COMMANDS = setOf("uu", "ls", "cd", "pwd", "help", "echo")

    fun parse(text: String): List<UutStmt> {
        val lines = text.lines()
        var idx = 0

        fun parseBlock(inBlock: Boolean): List<UutStmt> {
            val out = ArrayList<UutStmt>()
            while (idx < lines.size) {
                val raw = lines[idx].trim()
                val lineNo = idx + 1
                idx++
                if (raw.isEmpty() || raw.startsWith("#")) continue
                if (raw == "end") {
                    if (inBlock) return out
                    throw UutParseException(lineNo, "'end' without block")
                }
                when {
                    raw.startsWith("set ") -> {
                        val rest = raw.removePrefix("set ").trim()
                        val eq = rest.indexOf('=')
                        if (eq <= 0) throw UutParseException(lineNo, "set needs <name> = <value>")
                        val name = rest.substring(0, eq).trim()
                        if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                            throw UutParseException(lineNo, "bad variable name: $name")
                        }
                        out.add(UutStmt.Set(lineNo, name, rest.substring(eq + 1).trim()))
                    }
                    raw.startsWith("if ") -> {
                        // if <var> [!=] <value> then <cmd>
                        val rest = raw.removePrefix("if ").trim()
                        val thenIdx = rest.indexOf(" then ")
                        if (thenIdx <= 0) throw UutParseException(lineNo, "if needs 'then'")
                        val cond = rest.substring(0, thenIdx).trim()
                        val cmd = rest.substring(thenIdx + " then ".length).trim()
                        if (cmd.isEmpty()) throw UutParseException(lineNo, "if has no command after then")
                        val negate = cond.contains("!=")
                        val parts = cond.split(if (negate) "!=" else "=", limit = 2)
                        if (parts.size != 2) throw UutParseException(lineNo, "if needs <var> = <value>")
                        val varName = parts[0].trim().removePrefix("$")
                        val value = parts[1].trim().trim('"', '\'')
                        out.add(UutStmt.IfInline(lineNo, varName, negate, value, cmd))
                    }
                    raw.startsWith("for ") -> {
                        val rest = raw.removePrefix("for ").trim()
                        val inIdx = rest.indexOf(" in ")
                        if (inIdx <= 0) throw UutParseException(lineNo, "for needs 'in'")
                        val varName = rest.substring(0, inIdx).trim()
                        val patterns = rest.substring(inIdx + " in ".length).trim()
                        if (patterns.isEmpty()) throw UutParseException(lineNo, "for has no patterns")
                        val body = parseBlock(inBlock = true)
                        if (idx == 0 || lines[idx - 1].trim() != "end") {
                            throw UutParseException(lineNo, "for needs 'end'")
                        }
                        out.add(UutStmt.For(lineNo, varName, patterns, body))
                    }
                    else -> out.add(UutStmt.Cmd(lineNo, raw))
                }
            }
            return out
        }

        return parseBlock(inBlock = false)
    }

    /**
     * `$name` 文本替换（未定义变量 → 空串）。`$(...)` 捕获由执行层在
     * [UutStmt.Set] 的求值阶段处理（此处只做普通替换，遇到 `$(` 原样保留）。
     */
    fun expandVars(text: String, vars: Map<String, String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '$' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '_')) {
                var j = i + 1
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                sb.append(vars[text.substring(i + 1, j)] ?: "")
                i = j
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** `$(cmd)` 包裹检测：提取内部命令，非捕获形态返回 null。 */
    fun captureInner(value: String): String? {
        val v = value.trim()
        return if (v.startsWith("$(") && v.endsWith(")") && v.length > 3) {
            v.substring(2, v.length - 1).trim()
        } else null
    }

    /** 脚本命令行的首个 token 必须是白名单命令（防止套娃 shell）。 */
    fun commandAllowed(firstToken: String): Boolean = firstToken in ALLOWED_COMMANDS
}

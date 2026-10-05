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
 * set f = $1                      # 脚本参数：uu run x.uut game.xp3
 * set answer = $(uu info $f)
 * if answer = xp3 then uu x $f     # 行内分支
 * if answer != ""                  # 块式分支（可嵌套，可带 else）
 *   uu l $f > $f.list.txt
 * else
 *   echo not an archive
 * end
 * for a in *.zip
 *   uu l $a
 * end
 * for i in 1..3                    # 计数区间（可降序 3..1）
 *   uu l f$i
 * end
 * if exist out/                    # 文件/目录测试
 *   echo already extracted
 * end
 * set n = 0
 * while n < 3                      # 数值比较 + while（有迭代上限）
 *   set n = $n + 1                 # 算术赋值
 *   if $n = 2 then break           # 跳出最内层循环
 *   echo n=$n
 * end
 * uu l $f > list.txt
 * if $? != 0 then echo listing failed   # 上一条命令的退出码
 * return 0                         # 提前结束（退出码）
 * ```
 *
 * **能力边界（安全不变量）**：脚本里只允许 `uu` 子命令与 `ls / cd / pwd / echo`
 * —— 不许任意 `sh -c`，否则就是套娃 shell。
 *
 * 本文件是纯解析层（无 Android 依赖）：[parse] 产出 AST，[expandVars] 负责
 * `$name` 替换。执行在 TerminalPanel（复用终端的分段/管道/重定向执行器）。
 */
/**
 * `if` 的条件。独立成类型的原因：再加一类判断（文件是否存在…）只需加一个子类，
 * 不必给 [UutStmt.If] / [UutStmt.IfInline] 各加一组字段、也不必让执行器猜。
 */
internal sealed class UutCond {
    /**
     * `<变量> <运算符> <字面值>`。`$v` 的 `$` 会被剥掉，所以 `$1` 也能比。
     * `=` / `!=` 是**字面**比较；`<` `>` `<=` `>=` 是**数值**比较
     *（任一侧不是整数 → 运行期报错，绝不静默判假：while 条件判假会变成静默空转
     * 或错分支，比报错难查得多）。
     */
    class VarCmp(val name: String, val op: String, val value: String) : UutCond() {
        val negate: Boolean get() = op == "!="
    }

    /** `exist <路径>` / `not exist <路径>`：相对会话 cwd 解析，**不展开通配符**。 */
    class Exists(val rawPath: String, val negate: Boolean) : UutCond()
}

internal sealed class UutStmt {
    /** 一行普通命令（原始文本，运行时才展开变量并分词）。[line] 供报错。 */
    class Cmd(val line: Int, val raw: String) : UutStmt()

    /** `set <名字> = <值>`；值以 `$(` 开头并以 `)` 结尾 = 命令捕获。 */
    class Set(val line: Int, val name: String, val rawValue: String) : UutStmt()

    /** `if <条件> then <命令>`（单行形态）。 */
    class IfInline(val line: Int, val cond: UutCond, val rawCmd: String) : UutStmt()

    /** 块式 `if <条件>` … [`else` …] `end`（可嵌套）。 */
    class If(
        val line: Int, val cond: UutCond,
        val thenBody: List<UutStmt>, val elseBody: List<UutStmt>,
    ) : UutStmt()

    /** `for <变量> in <模式...>` … `end`。 */
    class For(val line: Int, val varName: String, val rawPatterns: String, val body: List<UutStmt>) : UutStmt()

    /** `while <条件>` … `end`（迭代上限见 [UutParser.LOOP_MAX]，超限报错而不是卡死）。 */
    class While(val line: Int, val cond: UutCond, val body: List<UutStmt>) : UutStmt()

    /** `break`：跳出**最内层** for / while。 */
    class Break(val line: Int) : UutStmt()

    /** `continue`：跳过**最内层** for / while 的本轮剩余部分。 */
    class Continue(val line: Int) : UutStmt()

    /** `return [退出码]`：结束**当前脚本**（嵌套 `uu run` 只结束那一层）。 */
    class Return(val line: Int, val rawCode: String) : UutStmt()
}

internal class UutParseException(val line: Int, val reason: String) : Exception(reason)

internal object UutParser {

    /**
     * 允许出现在脚本里的命令名（第一个 token）。`break` / `return` 是 UUT 语句，
     * 但它们可以出现在**行内**（`if x then break`、`a ; return 1`）—— 执行器的
     * 片段循环要在白名单检查之前拦截它们，否则会被当成 shell 命令拒掉
     *（真机踩过："UUT 不允许的命令：break"）。
     */
    val ALLOWED_COMMANDS = setOf("uu", "ls", "cd", "pwd", "help", "echo", "break", "return")

    fun parse(text: String): List<UutStmt> = BlockParser(text.lines()).parseAll()

    /**
     * 块解析器：把「行游标 + 三种语句/两种块的解析」收进一个类。
     * 不用局部函数的原因：`parseBlock` 与 `parseIf`/`parseFor` 互相调用，
     * 而 Kotlin 的局部函数不能前向引用（成员函数可以）。
     */
    private class BlockParser(private val lines: List<String>) {
        private var idx = 0

        /**
         * 同行 `else if <cond>` / `elif <cond>` 的条件文本：parseBlock 撞见这种行时
         * 收在这里并按 "else" 终止当前块，紧跟着的 parseIf 优先消费它 —— 否则整行
         * 会被当普通命令，`else if` 里的分支永远走不到（真机实测：恒走 else）。
         */
        private var pendingElseIf: String? = null

        fun parseAll(): List<UutStmt> = parseBlock(inBlock = false).first

        /** 条件：`exist <路径>` / `not exist <路径>` / `<变量> [!=] <值>`。 */
        private fun parseCond(condRaw: String, lineNo: Int): UutCond {
            val negExist = condRaw.startsWith("not exist ")
            if (negExist || condRaw.startsWith("exist ")) {
                val path = condRaw.removePrefix(if (negExist) "not exist " else "exist ").trim()
                if (path.isEmpty()) throw UutParseException(lineNo, "exist needs a path")
                return UutCond.Exists(path, negExist)
            }
            // 双字符运算符先试，避免 "<=" 被当成 "<" + "=…"
            for (op in OPS) {
                val i = indexOfOp(condRaw, op)
                if (i <= 0) continue
                return UutCond.VarCmp(
                    condRaw.substring(0, i).trim().removePrefix("$"), op,
                    condRaw.substring(i + op.length).trim().trim('"', '\'')
                )
            }
            throw UutParseException(lineNo, "condition needs an operator (= != < > <= >=)")
        }

        /** 找到 [op] 在条件里第一次出现的位置（跳过引号内）。 */
        private fun indexOfOp(s: String, op: String): Int {
            var quote = '\u0000'
            var i = 0
            while (i <= s.length - op.length) {
                val c = s[i]
                if (quote != '\u0000') { if (c == quote) quote = '\u0000'; i++; continue }
                if (c == '"' || c == '\'') { quote = c; i++; continue }
                if (s.startsWith(op, i)) return i
                i++
            }
            return -1
        }

        /** `if <条件>` / `if <条件> then <命令>`（两者的区别只在有没有 ` then `）。 */
        private fun parseIf(lineNo: Int, rest: String): UutStmt {
            val thenIdx = rest.indexOf(" then ")
            if (thenIdx > 0) {
                val cond = rest.substring(0, thenIdx).trim()
                val cmd = rest.substring(thenIdx + " then ".length).trim()
                if (cmd.isEmpty()) throw UutParseException(lineNo, "if has no command after then")
                return UutStmt.IfInline(lineNo, parseCond(cond, lineNo), cmd)
            }
            val cond = parseCond(rest, lineNo)
            val (thenBody, term) = parseBlock(inBlock = true)
            if (term == null) throw UutParseException(lineNo, "if needs 'end'")
            var elseBody: List<UutStmt> = emptyList()
            if (term == "else") {
                // elif 的两种形态：同行 `else if <cond>` / `elif <cond>`（pendingElseIf，
                // parseBlock 已把行消费掉），或 `else` 换行后紧跟 `if` 行（peek）。
                // 两者都作为嵌套 if 解析，整条链共享一个 end。
                val inline = pendingElseIf
                pendingElseIf = null
                when {
                    inline != null -> elseBody = listOf(parseIf(lineNo, inline))
                    else -> {
                        var peek = idx
                        while (peek < lines.size && lines[peek].trim().isEmpty()) peek++
                        if (peek < lines.size && lines[peek].trim().startsWith("if ")) {
                            val ln = peek + 1
                            val rest = lines[peek].trim().removePrefix("if ").trim()
                            idx = peek + 1
                            elseBody = listOf(parseIf(ln, rest))
                        } else {
                            val (eb, t2) = parseBlock(inBlock = true)
                            if (t2 != "end") throw UutParseException(lineNo, "if needs 'end'")
                            elseBody = eb
                        }
                    }
                }
            }
            return UutStmt.If(lineNo, cond, thenBody, elseBody)
        }

        /** `for <变量> in <模式...>` … `end`。 */
        private fun parseFor(lineNo: Int, rest: String): UutStmt {
            val inIdx = rest.indexOf(" in ")
            if (inIdx <= 0) throw UutParseException(lineNo, "for needs 'in'")
            val varName = rest.substring(0, inIdx).trim()
            val patterns = rest.substring(inIdx + " in ".length).trim()
            if (patterns.isEmpty()) throw UutParseException(lineNo, "for has no patterns")
            val (body, term) = parseBlock(inBlock = true)
            // 用终止符判断，不再回看 lines[idx-1]：块式 if 的 end 也是
            // `end`，回看会把 `for … if … end end` 里缺的那个当成有
            if (term != "end") throw UutParseException(lineNo, "for needs 'end'")
            return UutStmt.For(lineNo, varName, patterns, body)
        }

        /**
         * 解析一个块，返回 (语句, 终止符)。终止符 ∈ {null(EOF), "end", "else"} ——
         * 块式 if 需要区分 else 与 end，所以不能像早期版本那样只看 `lines[idx-1]`。
         */
        private fun parseBlock(inBlock: Boolean): Pair<List<UutStmt>, String?> {
            val out = ArrayList<UutStmt>()
            while (idx < lines.size) {
                val raw = lines[idx].trim()
                val lineNo = idx + 1
                idx++
                if (raw.isEmpty() || raw.startsWith("#")) continue
                if (raw == "end" || raw == "else") {
                    if (inBlock) return out to raw
                    throw UutParseException(
                        lineNo,
                        "'$raw' without block (a block header must start the line or follow ';' / '&&')"
                    )
                }
                // 同行 elif：`else if <cond>` / `elif <cond>` —— 收下条件文本后按
                // "else" 终止当前块（parseIf 会优先消费 pendingElseIf）
                if (raw.startsWith("else if ") || raw.startsWith("elif ")) {
                    pendingElseIf = when {
                        raw.startsWith("else if ") -> raw.removePrefix("else if ").trim()
                        else -> raw.removePrefix("elif ").trim()
                    }
                    if (inBlock) return out to "else"
                    throw UutParseException(lineNo, "'else' without block")
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
                    raw.startsWith("if ") -> out.add(parseIf(lineNo, raw.removePrefix("if ").trim()))
                    raw.startsWith("for ") -> out.add(parseFor(lineNo, raw.removePrefix("for ").trim()))
                    raw.startsWith("while ") -> {
                        val cond = parseCond(raw.removePrefix("while ").trim(), lineNo)
                        val (body, term) = parseBlock(inBlock = true)
                        if (term != "end") throw UutParseException(lineNo, "while needs 'end'")
                        out.add(UutStmt.While(lineNo, cond, body))
                    }
                    raw == "break" -> out.add(UutStmt.Break(lineNo))
                    raw == "continue" -> out.add(UutStmt.Continue(lineNo))
                    raw == "return" || raw.startsWith("return ") ->
                        out.add(UutStmt.Return(lineNo, raw.removePrefix("return").trim()))
                    else -> {
                        // 段尾块头：`uu mkdir d ; if exist d` / `a && for i in 1..2`。
                        // 解析器与执行器必须用同一套切法（splitChain），否则这里会
                        // 把整行当普通命令，后面那个 `end` 就成了"没有块的 end"——
                        // 真机上正是这么炸的（错误还指向 end 那行，指错地方）。
                        val pieces = splitChain(raw)
                        val head = pieces.lastOrNull()?.first
                        if (pieces.size > 1 && head != null &&
                            (head.startsWith("if ") || head.startsWith("for ") || head.startsWith("while "))
                        ) {
                            val prefix = pieces.dropLast(1)
                                .mapIndexed { i, p -> if (i == 0) p.first else "${p.second} ${p.first}" }
                                .joinToString(" ")
                            out.add(UutStmt.Cmd(lineNo, prefix))
                            out.add(
                                when {
                                    head.startsWith("if ") -> parseIf(lineNo, head.removePrefix("if ").trim())
                                    head.startsWith("for ") -> parseFor(lineNo, head.removePrefix("for ").trim())
                                    else -> {
                                        val cond = parseCond(head.removePrefix("while ").trim(), lineNo)
                                        val (body, term) = parseBlock(inBlock = true)
                                        if (term != "end") throw UutParseException(lineNo, "while needs 'end'")
                                        UutStmt.While(lineNo, cond, body)
                                    }
                                }
                            )
                        } else out.add(UutStmt.Cmd(lineNo, raw))
                    }
                }
            }
            return out to null
        }
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
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            // `$?` = 上一条命令的退出码（单字符名字，不与命名变量规则冲突）
            if (c == '$' && next == '?') {
                sb.append(vars["?"] ?: "0")
                i += 2
                continue
            }
            if (c == '$' && (next.isLetter() || next == '_' || next.isDigit())) {
                var j = i + 1
                // 位置参数 `$1` 是纯数字（`$10` 也整体吃掉）；命名变量照旧
                if (next.isDigit()) {
                    while (j < text.length && text[j].isDigit()) j++
                } else {
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                }
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

    /**
     * 把一行原文按 `;` / `&&` / `||` 切成片段（引号内与 `\` 转义的分隔符不算），
     * 返回 (文本, 连接符)，连接符 ∈ {"", ";"(总是执行), "&&"(前段成功才执行),
     * "||"(前段失败才执行)}。
     *
     * 为什么要在**展开变量之前**切：`uu l nope.zip ; echo rc=$?` 里的 `$?` 必须
     * 看到前一段的退出码，而"整行先展开再执行"的话它拿到的是上一行留下的值
     *（真机实测：rc 恒为 0）。所以 UUT 逐段展开、逐段执行。
     */
    fun splitChain(raw: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val cur = StringBuilder()
        var quote = '\u0000'
        var joiner = ""
        var i = 0
        fun flush() {
            val t = cur.toString().trim()
            if (t.isNotEmpty()) out.add(t to joiner)
            cur.setLength(0)
            joiner = ";"
        }
        while (i < raw.length) {
            val c = raw[i]
            when {
                quote != '\u0000' -> {
                    cur.append(c)
                    if (c == quote) quote = '\u0000'
                    i++
                }
                c == '\\' && i + 1 < raw.length -> {
                    cur.append(c).append(raw[i + 1]); i += 2
                }
                c == '"' || c == '\'' -> { quote = c; cur.append(c); i++ }
                c == ';' -> { flush(); i++ }
                c == '&' && i + 1 < raw.length && raw[i + 1] == '&' -> {
                    flush(); joiner = "&&"; i += 2
                }
                c == '|' && i + 1 < raw.length && raw[i + 1] == '|' -> {
                    flush(); joiner = "||"; i += 2
                }
                else -> { cur.append(c); i++ }
            }
        }
        flush()
        // 首段没有连接符；flush() 默认给 ";"，这里纠正
        return if (out.isEmpty()) out else listOf(out[0].first to "") + out.drop(1).map { it.first to it.second }
    }

    /** 条件里可用的运算符（双字符在前，"<=" 不能被拆成 "<"）。 */
    val OPS = listOf("!=", "<=", ">=", "=", "<", ">")

    /**
     * `set n = $n + 1` 的算术：`<整数> <op> <整数>`（op ∈ + - * /）。
     * 不是算术式 → null（当普通字符串赋值）；除零 → 抛 [ArithmeticException]，
     * 由执行器转成脚本错误（静默给 0 会让计数器悄悄跑飞）。
     */
    fun evalArith(expr: String): String? {
        val m = ARITH_REGEX.matchEntire(expr.trim()) ?: return null
        val a = m.groupValues[1].toLongOrNull() ?: return null
        val b = m.groupValues[3].toLongOrNull() ?: return null
        return when (m.groupValues[2]) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            else -> if (b == 0L) throw ArithmeticException("division by zero") else a / b
        }.toString()
    }

    /**
     * 条件比较。`=` / `!=` 按字符串；其余按**整数**（任一侧不是整数 → null，
     * 由执行器报错；不静默判假）。
     */
    fun compareValues(left: String, op: String, right: String): Boolean? = when (op) {
        "=" -> left == right
        "!=" -> left != right
        else -> {
            val a = left.trim().toLongOrNull()
            val b = right.trim().toLongOrNull()
            if (a == null || b == null) null
            else when (op) {
                "<" -> a < b
                ">" -> a > b
                "<=" -> a <= b
                else -> a >= b
            }
        }
    }

    /**
     * `1..5` 计数区间 → ["1".."5"]（含两端，可降序）；不是区间返回 null。
     * 纯函数，便于单测；执行器只在 `for` 的模式列表是**单个**区间时使用。
     */
    fun rangeValues(pattern: String): List<String>? {
        val m = RANGE_REGEX.matchEntire(pattern.trim()) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        if (kotlin.math.abs(b - a) > RANGE_MAX) return emptyList()   // 上限保护：不生成百万行
        return if (a <= b) (a..b).map { it.toString() } else (a downTo b).map { it.toString() }
    }

    private val RANGE_REGEX = Regex("^(\\d+)\\.\\.(\\d+)$")

    /** `for i in 1..N` 的生成上限（超过则视为空，宁可什么都不做也不要卡死）。 */
    const val RANGE_MAX = 100_000

    /**
     * `while` 的迭代上限。脚本是**在 App 里**跑的（共享一个终端线程），死循环会
     * 让终端看起来卡死且取消按钮只能点一次 —— 到上限就报错停下，比挂住强。
     */
    const val LOOP_MAX = 10_000

    private val ARITH_REGEX = Regex("^(-?\\d+)\\s*([+\\-*/])\\s*(-?\\d+)$")

    /** 脚本命令行的首个 token 必须是白名单命令（防止套娃 shell）。 */
    fun commandAllowed(firstToken: String): Boolean = firstToken in ALLOWED_COMMANDS
}

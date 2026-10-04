package com.usefulunpacker

/**
 * 引号感知的 tokenizer。
 *
 * 拆分规则刻意保持最小且可预测：
 *  - 空白分隔
 *  - `"` 与 `'` 成对引号内不拆空白（`'` 在 Windows 习惯里也是引号，UI 输入里两种都会用到）
 *  - `\` 只在**引号外**转义下一个字符（引号内保持字面：反斜杠在 Android 上是合法文件名字符）
 *  - 引号未闭合 → 报错，不做「尽力而为」的猜测
 *
 * 为什么不用 `String.split("\\s+")`：它会把 `uu x "my game.zip"` 拆成
 * `["\"my", "game.zip\"]`，也就是带空格路径**无法表达**。UU 的归档路径带空格
 * 是常态（用户目录名里就有），所以引号支持是必需项而非增强项。
 *
 **失败时返回结构化原因而不是文案** —— 文案要走字符串资源，而纯函数拿不到
 Context；由调用方把 [UnclosedQuote] 映射成对应资源。
 */
internal class CliTokenizer {

    /**
     * 失败原因。刻意**不**命名为 `Err`：它与 `Out.Err` 会在 `Out` 的作用域内
     * 互相遮蔽（同名的嵌套类，编译器解析到内层那个，报一个看不出所以然的
     * 类型不匹配）。叫 `Reason` 一眼可辨。
     */
    sealed class Reason {
        /** 引号未闭合，[quote] 是触发的那一个引号字符。 */
        class UnclosedQuote(val quote: Char) : Reason()
    }

    sealed class Out {
        class Ok(val tokens: List<String>) : Out()
        class Bad(val reason: Reason) : Out()
    }

    fun tokenize(input: String): Out {
        val tokens = ArrayList<String>()
        val cur = StringBuilder()
        // 「本 token 是否已开始」——用来区分「空 token（`""`）」与「没有 token」
        var started = false
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> {
                    if (started) { tokens.add(cur.toString()); cur.setLength(0); started = false }
                    i++
                }
                // 引号外的反斜杠转义下一个字符（写 \" \' \ 空格）
                c == '\\' && i + 1 < input.length -> {
                    cur.append(input[i + 1]); started = true; i += 2
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    i++
                    var closed = false
                    while (i < input.length) {
                        if (input[i] == quote) { closed = true; i++; break }
                        cur.append(input[i]); i++
                    }
                    if (!closed) return Out.Bad(Reason.UnclosedQuote(quote))
                    started = true    // `""` 是合法的空参数
                }
                else -> { cur.append(c); started = true; i++ }
            }
        }
        if (started) tokens.add(cur.toString())
        return Out.Ok(tokens)
    }
}

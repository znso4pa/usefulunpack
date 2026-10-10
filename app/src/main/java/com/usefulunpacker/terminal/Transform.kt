package com.usefulunpacker

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 字节变换管道 —— `uu tr` 的引擎（6.3）。
 *
 * 为什么要有它：真实世界里的归档经常只是「被裹了一层」—— 整个文件异或了一个常量、
 * 前面多了 16 字节的头、密钥按 4 字节循环。这类东西内置的 27 种格式都解不了，
 * 但它既不是加密也不是新压缩算法，就是**一个按偏移的纯字节函数**。
 *
 * **能力边界（刻意的）**：每个 op 都是「按源偏移的逐字节纯函数」。
 * 这条约束换来三件东西：可以**流式**（不用整文件进内存）、可以**随机访问**
 * （将来能直接接进读取路径）、以及 —— 最重要的 —— **可逆性可以静态判定**
 * （见 [TrOp.inverse]），而写方向（`uu c --post`）的安全性完全建立在它上面。
 *
 * **不做什么**：不做表达式求值、不做文件引用、不做循环。固定词表 + 数字参数。
 * 这正是它和「跑用户 py/rs 脚本」的本质区别，也是它安全的原因 —— 解析器碰不到
 * 任何可执行的东西，恶意管道最多是写错字节，不可能执行代码。
 *
 * 本文件是纯逻辑（无 Android 依赖），`TransformTest` 直接在纯 JVM 下断言。
 */

/** 变换的一步。 */
internal sealed class TrOp {

    /**
     * 对**源偏移** [off] 处的字节 [b] 施加变换。
     *
     * 偏移坐标系是「解析后的源」的 0 基偏移：普通文件就是文件偏移，`fN` 区间就是
     * 区间内偏移。`skip` 只丢字节、不改坐标系 —— 与 `uu dd` 的 `skip` 语义一致。
     */
    abstract fun apply(off: Long, b: Byte): Byte

    /**
     * 逆变换；`null` = **不可逆**。
     *
     * `uu c --post` 会**静态拒绝**含不可逆步骤的管道 —— 写方向若不能读回验证，
     * 就等于在生产一个没人能解开的文件。
     */
    abstract fun inverse(): TrOp?

    /** XOR 一个循环密钥（长度 1..256）。自逆。 */
    class Xor(val key: ByteArray) : TrOp() {
        override fun apply(off: Long, b: Byte): Byte =
            (b.toInt() xor key[(off % key.size).toInt()].toInt()).toByte()

        override fun inverse(): TrOp = Xor(key)
    }

    /** 加上 [k]（0..255，按字节回绕）。逆是 [Sub]。 */
    class Add(val k: Int) : TrOp() {
        override fun apply(off: Long, b: Byte): Byte = (b + k).toByte()
        override fun inverse(): TrOp = Sub(k)
    }

    /** 减去 [k]（0..255，按字节回绕）。逆是 [Add]。 */
    class Sub(val k: Int) : TrOp() {
        override fun apply(off: Long, b: Byte): Byte = (b - k).toByte()
        override fun inverse(): TrOp = Add(k)
    }

    /** 8 位循环右移 [n]（1..7）。逆是 [RotLeft]。 */
    class RotRight(val n: Int) : TrOp() {
        override fun apply(off: Long, b: Byte): Byte = b.rotateRight(n)
        override fun inverse(): TrOp = RotLeft(n)
    }

    /** 8 位循环左移 [n]（1..7）。逆是 [RotRight]。 */
    class RotLeft(val n: Int) : TrOp() {
        override fun apply(off: Long, b: Byte): Byte = b.rotateLeft(n)
        override fun inverse(): TrOp = RotRight(n)
    }

    /** 逐位取反。自逆。 */
    object Not : TrOp() {
        override fun apply(off: Long, b: Byte): Byte = b.toInt().inv().toByte()
        override fun inverse(): TrOp = Not
    }

    /**
     * 丢掉当前流开头的 [n] 字节。**不可逆**（数据真的没了）——
     * 所以它被限制为「只能作第一步、只能出现一次」，`--post` 也会拒绝含它的管道。
     */
    class Skip(val n: Long) : TrOp() {
        override fun apply(off: Long, b: Byte): Byte = b
        override fun inverse(): TrOp? = null
    }
}

/** 解析好的一条管道：[steps] 按顺序施加，[skip] 是开头要丢掉的字节数（0 = 不丢）。 */
internal class TrPipeline(val steps: List<TrOp>, val skip: Long) {

    /** 全部步骤都可逆 → 可用于写方向（`--post`）。 */
    val invertible: Boolean get() = steps.all { it.inverse() != null }

    /**
     * 逆管道：把 [inverse] 逐个反序。含不可逆步骤时返回 null。
     *
     * `skip` 不可逆，所以只要它非 0 就整体不可逆 —— 这也是 `--post` 拒绝
     * `skip` 的方式：不是特判，而是「逆不存在」这个更本质的理由。
     */
    fun inverse(): TrPipeline? {
        if (skip != 0L) return null
        val inv = ArrayList<TrOp>(steps.size)
        for (s in steps.asReversed()) inv.add(s.inverse() ?: return null)
        return TrPipeline(inv, 0L)
    }
}

/** 管道解析器。纯函数，便于单测。 */
internal object TrParser {

    /** 一条管道的步数上限 —— 词表里没有循环，正常管道不会长。 */
    const val MAX_STEPS = 16

    /** XOR 密钥长度上限（字节）。 */
    const val MAX_KEY = 256

    /** 解析期的 `skip` 上限；运行期还会再对源的实际长度校验一次。 */
    const val MAX_SKIP = 1L shl 32

    sealed class Bad {
        /** 不认识的步骤名。 */
        class UnknownStep(val token: String) : Bad()
        /** 步骤认得出，但参数不合法。 */
        class BadArg(val step: String, val arg: String) : Bad()
        /** `skip` 不是第一步，或出现了不止一次。 */
        object SkipNotFirst : Bad()
        class TooManySteps(val max: Int) : Bad()
    }

    sealed class Out {
        class Ok(val pipeline: TrPipeline) : Out()
        class Err(val bad: Bad) : Out()
    }

    /**
     * 解析 `"skip 16 | xor 0xAA"` 这样的管道。
     *
     * 分隔符用 `|`：终端的 tokenizer 是**引号感知**的（`"skip 16 | xor 0xAA"` 整个
     * 是一个 token），而分段/管道切分只匹配**恰好等于** `|` 的 token，所以不会误切。
     */
    fun parse(text: String): Out {
        val rawSteps = text.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (rawSteps.isEmpty()) return Out.Err(Bad.UnknownStep(text.trim()))
        if (rawSteps.size > MAX_STEPS) return Out.Err(Bad.TooManySteps(MAX_STEPS))

        val steps = ArrayList<TrOp>(rawSteps.size)
        var skip = 0L
        for ((i, raw) in rawSteps.withIndex()) {
            val parts = raw.split(Regex("\\s+"), limit = 2)
            val name = parts[0].lowercase()
            val arg = parts.getOrNull(1)?.trim() ?: ""
            when (name) {
                "skip" -> {
                    // 只允许第一步、只允许一次：放在中间时「丢的是变换前还是变换后的字节」
                    // 会有歧义，而这个歧义会静默地改变结果 —— 不如直接拒绝。
                    if (i != 0 || skip != 0L) return Out.Err(Bad.SkipNotFirst)
                    val n = num(arg) ?: return Out.Err(Bad.BadArg(name, arg))
                    if (n <= 0 || n > MAX_SKIP) return Out.Err(Bad.BadArg(name, arg))
                    skip = n
                    steps.add(TrOp.Skip(n))
                }
                "xor" -> {
                    val key = hexBytes(arg) ?: return Out.Err(Bad.BadArg(name, arg))
                    if (key.isEmpty() || key.size > MAX_KEY) return Out.Err(Bad.BadArg(name, arg))
                    steps.add(TrOp.Xor(key))
                }
                "add", "sub" -> {
                    val n = num(arg) ?: return Out.Err(Bad.BadArg(name, arg))
                    if (n !in 0..255) return Out.Err(Bad.BadArg(name, arg))
                    steps.add(if (name == "add") TrOp.Add(n.toInt()) else TrOp.Sub(n.toInt()))
                }
                "rot" -> {
                    val bits = arg.split(Regex("\\s+"))
                    val dir = bits.getOrNull(0)?.lowercase()
                    val n = bits.getOrNull(1)?.let { num(it) }
                    if (dir !in listOf("r", "l") || n == null || n !in 1..7) {
                        return Out.Err(Bad.BadArg(name, arg))
                    }
                    steps.add(if (dir == "r") TrOp.RotRight(n.toInt()) else TrOp.RotLeft(n.toInt()))
                }
                "not" -> {
                    if (arg.isNotEmpty()) return Out.Err(Bad.BadArg(name, arg))
                    steps.add(TrOp.Not)
                }
                else -> return Out.Err(Bad.UnknownStep(raw))
            }
        }
        return Out.Ok(TrPipeline(steps, skip))
    }

    /** `0x` 前缀十六进制或十进制；非数字返回 null。 */
    private fun num(s: String): Long? =
        if (s.startsWith("0x", true)) s.drop(2).toLongOrNull(16) else s.toLongOrNull()

    /** 十六进制字节串（可带 `0x` 前缀、可空白分隔）；奇数长 / 非十六进制 / 空 → null。 */
    private fun hexBytes(s: String): ByteArray? {
        var hex = s.filter { !it.isWhitespace() }
        if (hex.startsWith("0x", true)) hex = hex.drop(2)
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** 变换的执行结果。 */
internal sealed class TrResult {
    /** [written] 字节已写入 [dest]。[interrupted] = 被中断（输出是半成品，调用方决定去留）。 */
    class Ok(val written: Long, val interrupted: Boolean) : TrResult()
    object Failed : TrResult()
}

/**
 * 流式施加管道：从 [src] 的 [srcOffset] 起读 [srcLen] 字节（null = 读到文件尾），
 * 丢掉开头的 `skip` 字节，其余按 `steps` 变换后写进 [dest]。
 *
 * **不整文件进内存**（1 MiB 分块），所以大文件也能跑；偏移坐标按 [srcOffset] 折算，
 * 使 `fN` 区间的坐标从 0 开始（与 `uu dd` 的 `skip` 语义一致）。
 *
 * 失败时**不删** [dest] —— 调用方负责（`uu tr` 会删掉半成品，不留截断文件）。
 */
internal fun runTransform(
    src: File, srcOffset: Long, srcLen: Long?, pipeline: TrPipeline, dest: File,
    onProgress: (Long) -> Unit = {},
): TrResult {
    var written = 0L
    return try {
        FileInputStream(src).use { input ->
            // skip() 不保证一次跳到位 —— 循环它（与 carveToFile 同一条理由）
            var toSkip = srcOffset
            while (toSkip > 0) {
                val n = input.skip(toSkip)
                if (n <= 0) break
                toSkip -= n
            }
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(1 shl 20)
                var remaining = srcLen
                var dropped = pipeline.skip
                var off = 0L                       // 源区间内的 0 基偏移
                while (!Thread.currentThread().isInterrupted) {
                    val want = (remaining?.coerceAtMost(buf.size.toLong()) ?: buf.size.toLong()).toInt()
                    if (want <= 0) break
                    val n = input.read(buf, 0, want)
                    if (n <= 0) break
                    var start = 0
                    if (dropped > 0) {
                        val d = minOf(dropped, n.toLong()).toInt()
                        dropped -= d
                        start = d
                    }
                    for (i in start until n) {
                        var b = buf[i]
                        for (s in pipeline.steps) b = s.apply(off + i, b)
                        buf[i] = b
                    }
                    if (start < n) {
                        out.write(buf, start, n - start)
                        written += (n - start)
                        onProgress(written)
                    }
                    off += n
                    remaining = remaining?.let { it - n }
                }
            }
        }
        TrResult.Ok(written, Thread.currentThread().isInterrupted)
    } catch (_: Exception) {
        TrResult.Failed
    }
}

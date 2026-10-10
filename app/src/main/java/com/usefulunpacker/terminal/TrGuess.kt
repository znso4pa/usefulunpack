package com.usefulunpacker

/**
 * `uu guess` —— 从**内容**反推「这个怪文件被做了什么变换」。
 *
 * ## 为什么「魔数对上了」根本不算证据
 *
 * 密钥本来就是从魔数反推出来的：`key = cipher ^ magic`。任何文件都能凑出这么一个
 * key，然后"变换后前几字节就是魔数"—— 这是同义反复。所以判据必须来自与魔数
 * **无关**的地方：
 *
 *  - **约束数**：单参数变换（`xor k` / `add k` / …）命中一条 L 字节魔数，就有
 *    `L - 1` 个独立等式。这就是为什么把魔数写全很值 —— XP3 的 11 字节比 zip 的
 *    4 字节硬了 7 个数量级。
 *  - **周期约束**：多字节循环密钥要求 `cipher[i] ^ magic[i]` 真的是周期的。
 *  - **结构检查**：与魔数无关的格式内部结构（zip 的本地头链、7z 的 next-header
 *    偏移要落在文件内、XP3 的索引偏移要落在文件内……）。
 *
 * 第三条正是 `zip -FF` 手册里那个警告的正解 —— 它明说 `-FF` 可能"找到**内嵌**
 * zip 里的条目而不是外层"。只靠签名不够，得让格式自己的结构说话。
 *
 * **报告永远是「候选 + 判据」**，不是一句"就是它"：证据不足的直接不报，报了就把
 * 理由写出来，让用户自己判断。
 */

/** 判据。分两类，因为它们的**强度完全不同**。 */
internal sealed class TrEvidence {
    /**
     * 格式内部结构检查通过 —— **唯一与魔数无关**的证据。
     * [tag] 是技术标识（如 `zip:local-header-chain`），不是句子，所以不进资源表。
     */
    class Structure(val tag: String) : TrEvidence()

    /** 只有魔数约束；[n] 是独立等式的个数（魔数长度 - 自由参数个数）。 */
    class Constraints(val n: Int) : TrEvidence()
}

/** 一个候选：能直接粘进 `uu tr` 的管道文本 + 它让文件变成了什么 + 判据。 */
internal class TrCandidate(val pipeline: String, val format: String, val evidence: TrEvidence)

/** 猜测用的文件样本。只读头部，不把整个文件读进内存。 */
internal class TrProbe(val head: ByteArray, val size: Long)

internal object TrGuesser {

    /** `skip` 扫描上限。真归档的前置头通常几十字节，64 足够。 */
    const val MAX_SKIP = 64

    /** 最多报几个候选。 */
    const val MAX_REPORT = 8

    /** 头样本大小：结构检查（zip 的本地头链）需要看进去一点。 */
    const val HEAD_PROBE = 64 * 1024

    /**
     * 证据门槛。**结构检查只抵一个约束，不能替三个** —— 这是刻意的保守：
     * 有些结构检查（gz 的保留位、lz4 的版本位）本身就只有几个比特，若让它单独撑起
     * 一条候选，噪声就会漏进来（实测：一段普通英文文本能撞出 `skip 11 | xor 764D3A`）。
     */
    private const val MIN_CONSTRAINTS = 3

    fun guess(p: TrProbe): List<TrCandidate> {
        val found = LinkedHashMap<String, TrCandidate>()   // 按管道文本去重
        for ((magic, fmt) in MAGICS) {
            val len = magic.size
            for (skip in 0..MAX_SKIP) {
                if (skip + len > p.head.size) break
                // ① 原样就在这个偏移上（前置了头，没有变换）→ 0 个自由参数
                if (matchesAt(p.head, skip, magic)) {
                    record(found, p, skip, emptyList(), fmt, magic, freeParams = 0)
                    continue
                }
                // ② 单字节变换：参数由第 0 个字节唯一确定 → 1 个自由参数
                val c0 = p.head[skip]
                val m0 = magic[0]
                for (op in singleByteOps(c0, m0)) {
                    if (magicHolds(p.head, skip, op, magic)) {
                        record(found, p, skip, listOf(op), fmt, magic, freeParams = 1)
                    }
                }
                // ③ 循环 XOR 密钥：由整条魔数反推，要求它真是周期的
                repeatingXor(p.head, skip, magic)?.let { key ->
                    if (magicHolds(p.head, skip, TrOp.Xor(key), magic)) {
                        record(found, p, skip, listOf(TrOp.Xor(key)), fmt, magic, freeParams = key.size)
                    }
                }
            }
        }
        // 有结构检查的排前面 —— 那是唯一与魔数无关的证据
        return found.values
            .sortedWith(compareByDescending<TrCandidate> { it.evidence is TrEvidence.Structure }
                .thenByDescending { it.format })
            .take(MAX_REPORT)
    }

    // ── 候选生成 ──

    /** 由「第一个密文字节 → 第一个明文字节」唯一确定的单字节变换（最多 5 个）。 */
    private fun singleByteOps(c0: Byte, m0: Byte): List<TrOp> {
        val c = c0.toInt() and 0xFF
        val m = m0.toInt() and 0xFF
        val out = ArrayList<TrOp>(5)
        out.add(TrOp.Xor(byteArrayOf(((c xor m) and 0xFF).toByte())))
        out.add(TrOp.Add((m - c) and 0xFF))
        out.add(TrOp.Sub((c - m) and 0xFF))
        if (c0.toInt().inv().toByte() == m0) out.add(TrOp.Not)
        for (n in 1..7) {
            if (c0.rotateRight(n) == m0) { out.add(TrOp.RotRight(n)); break }
        }
        for (n in 1..7) {
            if (c0.rotateLeft(n) == m0) { out.add(TrOp.RotLeft(n)); break }
        }
        return out
    }

    /**
     * 由整条魔数反推循环 XOR 密钥，并检查它**真是周期的**（周期 ≤ 4 且小于魔数长度）。
     *
     * 这里**不**做「约束够不够」的门槛 —— 那是 [record] 的活：门槛要同时考虑结构检查，
     * 在这里提前丢掉会让结构检查根本没机会跑（4 字节魔数配 2 字节密钥只有 2 个约束，
     * 但 zip 的中央目录标记能补上那一步）。
     */
    private fun repeatingXor(head: ByteArray, skip: Int, magic: ByteArray): ByteArray? {
        val len = magic.size
        val key = ByteArray(len) { i -> (head[skip + i].toInt() xor magic[i].toInt()).toByte() }
        for (p in 1..minOf(4, len)) {
            if (key.indices.all { key[it] == key[it % p] }) {
                return if (p < len) key.copyOf(p) else null   // p == len → 没有独立约束
            }
        }
        return null
    }

    private fun matchesAt(buf: ByteArray, at: Int, magic: ByteArray): Boolean {
        if (at + magic.size > buf.size) return false
        for (i in magic.indices) if (buf[at + i] != magic[i]) return false
        return true
    }

    /** 把 [op] 施加到 `buf[at .. at+magic.size)` 上，必须逐字节等于 [magic]。 */
    private fun magicHolds(buf: ByteArray, at: Int, op: TrOp, magic: ByteArray): Boolean {
        for (i in magic.indices) {
            if (op.apply(i.toLong(), buf[at + i]) != magic[i]) return false
        }
        return true
    }

    // ── 记录 + 结构检查 ──

    private fun record(
        into: MutableMap<String, TrCandidate>, p: TrProbe, skip: Int,
        ops: List<TrOp>, fmt: String, magic: ByteArray, freeParams: Int,
    ) {
        val pipeline = TrPipeline(ops, skip.toLong())
        val text = pipeline.text()
        if (into.containsKey(text)) return
        // 独立约束 = 魔数长度 - 自由参数个数（`not` 和纯 skip 是 0 个自由参数）
        val constraints = magic.size - freeParams
        val struct = structuralOk(fmt, p, skip, ops)
        // 结构检查最多抵一个约束：`constraints >= 3` 直接过，`== 2` 要结构检查兜底
        if (constraints < MIN_CONSTRAINTS && (struct == null || constraints < MIN_CONSTRAINTS - 1)) return
        val evidence = if (struct != null) TrEvidence.Structure("$fmt:$struct")
                       else TrEvidence.Constraints(constraints)
        into[text] = TrCandidate(text, fmt, evidence)
    }

    /** 变换后的头样本（`skip` 之后的坐标系）。 */
    private fun transformedHead(p: TrProbe, skip: Int, ops: List<TrOp>): ByteArray =
        transformFrom(p.head, skip, ops, 0L)

    /**
     * 与魔数**无关**的格式内部结构检查。返回一句人话判据，或 null（该格式只有魔数证据）。
     *
     * 只加约束、不放松 —— 所以这里写错最坏是漏报，不会造假阳性。
     */
    private fun structuralOk(fmt: String, p: TrProbe, skip: Int, ops: List<TrOp>): String? {
        val h = transformedHead(p, skip, ops)
        fun u16(at: Int) = (h[at].toInt() and 0xFF) or ((h[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = (0 until 4).fold(0L) { acc, i -> acc or ((h[at + i].toLong() and 0xFF) shl (8 * i)) }
        fun ok(at: Int, n: Int) = at >= 0 && at + n <= h.size
        return when (fmt) {
            // zip 有两条独立的检查，任一条成立即可：
            //  ① 本地头链：off + 30 + nlen + elen + csize 处是下一个本地头或中央目录。
            //     注意**流式写出的 zip 用数据描述符**（本地头里 csize=0，真尺寸在后面），
            //     这条就失效 —— 所以不能只靠它。
            //  ② 中央目录标记 `PK\x01\x02` 出现在头部。每个非空 zip 都有；大文件的中央
            //     目录在尾部、不在头样本里，那种情况退回约束数。
            "zip" -> {
                var chain = false
                if (ok(26, 6) && ok(18, 4)) {
                    val at = (30 + u16(26) + u16(28) + u32(18)).toInt()
                    if (ok(at, 4) && h[at] == 0x50.toByte() && h[at + 1] == 0x4B.toByte()) {
                        val tag = "%02X%02X".format(h[at + 2], h[at + 3])
                        chain = tag == "0304" || tag == "0102"
                    }
                }
                when {
                    chain -> "local-header-chain"
                    indexOf(h, byteArrayOf(0x50, 0x4B, 0x01, 0x02)) >= 0 -> "central-directory"
                    else -> null
                }
            }
            // 7z 的 next-header 偏移（u64 LE @12）必须落在文件内
            "7z" -> if (ok(12, 8)) {
                val off = (0 until 8).fold(0L) { acc, i -> acc or ((h[12 + i].toLong() and 0xFF) shl (8 * i)) }
                if (off in 0 until p.size) "next-header-in-range" else null
            } else null
            // XP3 的索引偏移（i64 LE @11）必须落在文件内
            "xp3" -> if (ok(11, 8)) {
                val off = (0 until 8).fold(0L) { acc, i -> acc or ((h[11 + i].toLong() and 0xFF) shl (8 * i)) }
                if (off in 0 until p.size) "index-in-range" else null
            } else null
            "rar" -> if (ok(6, 1) && (h[6] == 0x00.toByte() || h[6] == 0x01.toByte())) "version-byte" else null
            // gzip：头字段只是弱证据（保留位 3 比特、OS 4 比特），真正的强证据是
            // **能解压出来** —— 试解一小段 raw deflate，失败即否。
            "gz" -> gzipHeaderEnd(h)?.let { start -> if (inflates(h, start)) "inflate-ok" else null }
            // bzip2：`BZh<level>` 之后紧跟 pi 的前 6 位十六进制（6 字节常量）—— 硬证据
            "bz2" -> when {
                !ok(3, 1) || h[3].toInt() !in '1'.code..'9'.code -> null
                ok(4, 6) && matchesAt(h, 4, BZ2_BLOCK_MAGIC) -> "block-magic"
                else -> null
            }
            "xz" -> if (ok(6, 2) && h[6] == 0x00.toByte() && (h[7].toInt() and 0xFF) <= 0x0F) "stream-flags" else null
            "zst" -> if (ok(4, 1) && (h[4].toInt() and 0x0C) == 0) "reserved-bits" else null
            // lz4：版本位必须是 01、保留位为 0，且 BD 的块上限必须是 4..7（其余值非法）
            "lz4" -> if (ok(4, 2) && (h[4].toInt() and 0xC1) == 0x40) {
                if (((h[5].toInt() and 0x70) shr 4) in 4..7) "frame-header" else null
            } else null
            "int" -> if (ok(4, 4)) {
                val n = u32(4)
                if (n in 1..1_000_000) "entry-count" else null
            } else null
            "rgss" -> if (ok(7, 1) && h[7].toInt() in 1..3) "version-byte" else null
            "rpa" -> if (ok(8, 8)) {
                val off = (0 until 8).fold(0L) { acc, i -> acc or ((h[8 + i].toLong() and 0xFF) shl (8 * i)) }
                if (off in 0 until p.size) "index-in-range" else null
            } else null
            "rpgmv" -> if (ok(9, 2) && h[9] == 0x03.toByte() && h[10] == 0x01.toByte()) "header-tail" else null
            else -> null
        }
    }

    /** 从 [from] 起对 [src] 施加 [ops]（坐标从 0 开始，与 `uu tr` 的 `skip` 语义一致）。 */
    private fun transformFrom(src: ByteArray, from: Int, ops: List<TrOp>, base: Long): ByteArray {
        if (from >= src.size) return ByteArray(0)
        val out = ByteArray(src.size - from)
        for (i in out.indices) {
            var b = src[from + i]
            for (o in ops) b = o.apply(base + i, b)
            out[i] = b
        }
        return out
    }

    /** 朴素子串查找。样本最大 64 KiB、needle 固定 4 字节，够用。 */
    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** bzip2 块魔数：`BZh<level>` 之后紧跟 pi 的前 6 位十六进制。 */
    private val BZ2_BLOCK_MAGIC =
        byteArrayOf(0x31, 0x41, 0x59, 0x26, 0x53, 0x59)

    /**
     * gzip 头部长度 → 载荷起点；头部不合法返回 null。
     * 按 FLG 跳过 FEXTRA / FNAME / FCOMMENT / FHCRC。
     */
    private fun gzipHeaderEnd(h: ByteArray): Int? {
        fun ok(at: Int, n: Int) = at >= 0 && at + n <= h.size
        if (!ok(3, 7)) return null
        val flg = h[3].toInt() and 0xFF
        if (flg and 0xE0 != 0) return null                     // 保留位必须为 0
        val os = h[9].toInt() and 0xFF
        if (os > 13 && os != 255) return null                  // OS 是枚举值
        var p = 10
        if (flg and 0x04 != 0) {                               // FEXTRA
            if (!ok(p, 2)) return null
            p += 2 + ((h[p].toInt() and 0xFF) or ((h[p + 1].toInt() and 0xFF) shl 8))
        }
        if (flg and 0x08 != 0) {                               // FNAME（NUL 结尾）
            while (ok(p, 1) && h[p] != 0.toByte()) p++
            p++
        }
        if (flg and 0x10 != 0) {                               // FCOMMENT
            while (ok(p, 1) && h[p] != 0.toByte()) p++
            p++
        }
        if (flg and 0x02 != 0) p += 2                          // FHCRC
        return if (ok(p, 1)) p else null
    }

    /**
     * 试解一小段 raw deflate —— gzip 载荷没有 zlib 头，所以 `nowrap = true`。
     * 能解出东西就是硬证据（随机数据撞不出一段合法的 deflate 流）。
     */
    private fun inflates(data: ByteArray, from: Int): Boolean = runCatching {
        val inf = java.util.zip.Inflater(true)
        try {
            inf.setInput(data, from, minOf(data.size - from, 4096))
            inf.inflate(ByteArray(4096)) > 0
        } finally {
            inf.end()
        }
    }.getOrDefault(false)
}

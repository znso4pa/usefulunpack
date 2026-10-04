package com.usefulunpacker

import java.io.File

/**
 * 扫描会话的**文件描述符表**。
 *
 * `uu scan` 不只打印结果，还把宿主的每一段命中注册成一个伪 FD（`f3`、`f4`…），
 * 之后 `uu l f3` / `dd if=f3 of=x.zip` / `uu x f3` 都能直接引用，
 * **不必把片段先落盘**。
 *
 * 编号：**f0 / f1 / f2 保留**给 stdin/stdout/stderr（POSIX fd 语义），第一个命中是 `f3` ——
 * 与真 POSIX 对齐，也让输出里的编号和用户直觉一致。
 *
 * 生命周期：进程内，**会话级**。关掉终端层不清（用户还要回来用 `dd`）；
 * 重新 `uu scan` 同一文件时整表重建。
 *
 * 失效检查：注册时记下宿主的 `size` + `mtime`，每次取用前比对。宿主被改过或
 * 删掉的 FD **宁可报错也不要静默读出垃圾** —— 偏移量已经不再指向原来的字节。
 */
internal class FdTable {

    /**
     * @param host 宿主文件
     * @param offset 片段在宿主中的起始偏移
     * @param length 片段长度；null = 到宿主末尾
     * @param label scan-core 给的标签
     * @param archiveKey 该命中能否当归档读（由 `ARCHIVE_LABELS` 判定）；null = 非归档
     */
    class Entry(
        val fd: Int,
        val host: File,
        val offset: Long,
        val length: Long?,
        val label: String,
        val archiveKey: String?,
        val hostSize: Long,
        val hostMtime: Long,
    ) {
        fun isArchive() = archiveKey != null

        /** 片段的绝对结束偏移（宿主总长兜底）。 */
        fun end(hostFileSize: Long): Long = length?.let { offset + it } ?: hostFileSize

        fun byteSize(): Long = length ?: (hostSize - offset)
    }

    /** 取用失败的原因。 */
    sealed class Rejected {
        class NoSuchFd(val name: String) : Rejected()
        class Stale(val name: String, val fd: Int) : Rejected()
    }

    private val entries = LinkedHashMap<Int, Entry>()

    /** 保留区，刻意不注册。 */
    private fun isReserved(n: Int) = n in 0..2

    // 下面所有访问表的方法都 @Synchronized：每条命令行跑在自己的线程上，
    // `uu scan` 整表重建（clear + 重新填）可能和另一条 `uu l f3` 撞上，
    // 无锁读 LinkedHashMap 正在被写会抛 ConcurrentModificationException。

    /**
     * 整表重建：同一次扫描的所有命中。
     * @return fd 编号列表（从 3 开始）
     */
    @Synchronized
    fun register(host: File, hits: List<ScanHit>, hostSize: Long, hostMtime: Long, keyOf: (String) -> String?): List<Int> {
        entries.clear()
        var next = FIRST_FD
        val fds = ArrayList<Int>(hits.size)
        for (h in hits) {
            val e = Entry(
                fd = next++,
                host = host,
                offset = h.offset,
                length = h.size,
                label = h.label,
                archiveKey = keyOf(h.label),
                hostSize = hostSize,
                hostMtime = hostMtime,
            )
            entries[e.fd] = e
            fds.add(e.fd)
        }
        return fds
    }

    /** 解析用户输入的 `fN`。非 FD 名一律返回 null，让调用方按普通路径处理。 */
    @Synchronized
    fun looksLikeFd(arg: String): Boolean = FD_PREFIX_REGEX.matches(arg)

    @Synchronized
    fun get(n: Int): Entry? = if (isReserved(n)) null else entries[n]

    @Synchronized
    fun all(): List<Entry> = entries.values.toList()

    @Synchronized
    fun clear() = entries.clear()

    @get:Synchronized
    val size: Int get() = entries.size

    /**
     * 取用前校验宿主是否还是扫描时那个样子。
     * 宿主被改写/删除后 offset 已无意义 —— 报 stale，而不是读出错位的内容。
     */
    fun checkFresh(e: Entry): Rejected? {
        if (!e.host.isFile) return Rejected.Stale(e.host.name, e.fd)
        val now = e.host.lastModified()
        val len = try { e.host.length() } catch (_: Exception) { return Rejected.Stale(e.host.name, e.fd) }
        if (now != e.hostMtime || len != e.hostSize) return Rejected.Stale(e.host.name, e.fd)
        return null
    }

    companion object {
        const val FIRST_FD = 3
        private val FD_PREFIX_REGEX = Regex("^f\\d+$")
    }
}

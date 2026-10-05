package com.usefulunpacker

import java.io.File

/**
 * 会话级**文件描述符表**（进程单例，见 [Companion.GLOBAL]）。
 *
 * 两种条目共用一张表、统一 `fN` 编号（**从 0 开始**）：
 *  - **整文件**（`ls` 注册）：`ls` 按应用当前排序方式给目录里的**每个文件**发一个
 *    `fN`（目录不编号），同一文件重复 ls 复用同一编号；
 *  - **字节区间**（`uu scan` 注册）：宿主的每段命中一个 `fN`，`uu l fN` / `uu x fN` /
 *    `dd if=fN` 直接引用，不必先落盘。
 *
 * 生命周期：**进程级**——大退（进程结束）才清；跨 tab、跨终端层开关、旋转都保留。
 * 表只增不清：同一文件/同一命中再次注册时**复用**既有编号，所以编号是稳定的。
 *
 * 失效检查：注册时记下宿主的 `size` + `mtime`，每次取用前比对。宿主被改过或
 * 删掉的 FD **宁可报错也不要静默读出垃圾** —— 偏移量已经不再指向原来的字节。
 */
internal class FdTable {

    class Entry(
        val fd: Int,
        val host: File,
        val offset: Long,
        val length: Long?,
        val label: String,
        val archiveKey: String?,
        val hostSize: Long,
        val hostMtime: Long,
        /** true = 整文件条目（ls 注册，offset=0，使用时直接读 host）；false = 扫描区间。 */
        val wholeFile: Boolean = false,
    ) {
        fun isArchive() = archiveKey != null

        /** 复用编号时刷新宿主快照（size/mtime），让 checkFresh 恢复新鲜。 */
        fun refreshed(hostSize: Long, hostMtime: Long) = Entry(
            fd, host, offset, length, label, archiveKey, hostSize, hostMtime, wholeFile
        )

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

    // 下面所有访问表的方法都 @Synchronized：每条命令行跑在自己的线程上，
    // 注册（ls / scan）可能和另一条 `uu l fN` 撞上，无锁读 LinkedHashMap
    // 正在被写会抛 ConcurrentModificationException。

    private fun nextFd(): Int = (entries.keys.maxOrNull() ?: -1) + 1

    /**
     * `ls` 用：按调用方排好的顺序给**每个文件**发 fN（目录由调用方过滤掉）。
     * 同一绝对路径的整文件条目已存在 → 复用其编号（编号跨 ls 调用稳定）。
     * @return 与输入等长的 fd 编号列表
     */
    @Synchronized
    fun registerFiles(files: List<File>): List<Int> = files.map { f ->
        val existing = entries.entries.firstOrNull {
            it.value.wholeFile && it.value.host.absolutePath == f.absolutePath
        }
        if (existing != null) {
            // 复用编号的同时**刷新快照**：否则文件被修改过之后，旧的
            // size+mtime 让 checkFresh 永远 stale，只有重启进程才能自愈。
            val old = existing.value
            entries[old.fd] = old.refreshed(f.length(), f.lastModified())
            old.fd
        } else {
            val fd = nextFd()
            entries[fd] = Entry(
                fd = fd, host = f, offset = 0L, length = null, label = f.name,
                archiveKey = null, hostSize = f.length(), hostMtime = f.lastModified(),
                wholeFile = true,
            )
            fd
        }
    }

    /**
     * `uu scan` 用：宿主的每段命中注册为区间条目。表**不清空**（进程级累积）；
     * 同一宿主同偏移同长度的命中已存在 → 复用编号。
     * @return 与 hits 等长的 fd 编号列表
     */
    @Synchronized
    fun register(host: File, hits: List<ScanHit>, hostSize: Long, hostMtime: Long, keyOf: (String) -> String?): List<Int> = hits.map { h ->
        val existing = entries.entries.firstOrNull {
            !it.value.wholeFile && it.value.host.absolutePath == host.absolutePath &&
                it.value.offset == h.offset && it.value.length == h.size
        }
        if (existing != null) {
            // 同宿主同偏移同长度 → 内容没挪位，刷新快照让 checkFresh 恢复新鲜
            val old = existing.value
            entries[old.fd] = old.refreshed(hostSize, hostMtime)
            old.fd
        } else {
            val fd = nextFd()
            entries[fd] = Entry(
                fd = fd, host = host, offset = h.offset, length = h.size,
                label = h.label, archiveKey = keyOf(h.label),
                hostSize = hostSize, hostMtime = hostMtime,
            )
            fd
        }
    }

    /** 解析用户输入的 `fN`。非 FD 名一律返回 null，让调用方按普通路径处理。 */
    @Synchronized
    fun looksLikeFd(arg: String): Boolean = FD_PREFIX_REGEX.matches(arg)

    @Synchronized
    fun get(n: Int): Entry? = entries[n]

    @Synchronized
    fun all(): List<Entry> = entries.values.toList()

    @Synchronized
    fun size(): Int = entries.size

    /**
     * 取用前校验宿主是否还是注册时那个样子。
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
        /**
         * 进程级单例：大退才清，跨 tab / 跨终端层开关 / 旋转保留。
         * （终端的 cwd/历史在 ViewModel 里，FD 表刻意不放那里 —— 它的生命周期
         * 按「软件大退」算，不按 Activity 算。）
         */
        val GLOBAL: FdTable = FdTable()

        private val FD_PREFIX_REGEX = Regex("^f\\d+$")
    }
}

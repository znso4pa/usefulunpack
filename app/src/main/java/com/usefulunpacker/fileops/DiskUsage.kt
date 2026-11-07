package com.usefulunpacker.fileops

import java.io.File
import java.util.PriorityQueue

/**
 * 磁盘占用扫描 —— WizTree 那类功能在 Android 上的形态。
 *
 * ## 为什么做不到 WizTree 的「秒出」
 * WizTree 读的是 NTFS 的 **MFT**：整张卷的文件表连续存放，一次顺序读就拿到所有
 * 「谁占了多少」。Android 的 `/sdcard` 是 FUSE / sdcardfs，**没有可读的元数据表** ——
 * 只能一个目录一个目录 `listFiles()` + `length()`。几十万文件就是分钟级，这不是实现
 * 好坏的问题，是平台给的边界。所以这里把力气全花在「别让用户干等」上：可取消、
 * 每 [PROGRESS_EVERY] 个文件回报一次进度。
 *
 * ## 只建目录树，不建文件节点
 * 一个游戏目录动辄几万文件，全建成对象必然 OOM；目录数通常小两个数量级。
 * 文件只在两处保留：**全局最大的 [topN] 个**（结果页的「最大文件」）与
 * **每个目录直接含有的最大几个**（[DiskNode.topFiles]，钻进去时才有东西可看）。
 *
 * ## 只走一趟
 * 子树大小在递归回溯时自然得到（[walk] 的返回值），不做「先 du 一遍、再逐个 du
 * 子目录」那种每层重走整棵树的 N 倍 I/O —— 在 `/sdcard` 上这是分钟级的差别。
 * （`uu du -d` 的 CLI 实现是同一个理由，见 `UuCommands.du`。）
 *
 * ## 兜底
 * 深度超过 [MAX_DEPTH] 后不再建节点、只累加大小（[DiskScan.truncatedDirs] 记数），
 * 免得某些 ROM 上的异常深目录把堆吃光。真实路径几乎碰不到 12 层。
 *
 * 纯 `java.io.File`，不碰 Android API —— 单测能直接在 JVM 上跑（见 `DiskUsageTest`）。
 */

/** 目录树里的一个节点。**只表示目录**，文件不进树（见类注释）。 */
internal class DiskNode(val dir: File) {
    /** 子树总字节数（含自身直接文件与所有子目录）。 */
    var bytes = 0L
        private set

    /** 子树文件数。 */
    var files = 0
        private set

    /** 子目录，扫描结束时按 [bytes] 降序排好。 */
    val dirs = ArrayList<DiskNode>()

    /** 本目录**直接**含有的最大文件，降序，最多 [DiskScanner.TOP_FILES_PER_DIR] 个。 */
    val topFiles = ArrayList<Pair<Long, File>>()

    internal fun add(b: Long, n: Int) {
        bytes += b
        files += n
    }
}

/** 一次扫描的完整结果。 */
internal class DiskScan(
    val root: DiskNode,
    /** 整棵树里最大的 [DiskScanner.topN] 个文件，降序。 */
    val topFiles: List<Pair<Long, File>>,
    val files: Int,
    val bytes: Long,
    /** 因深度上限只统计了大小、没有建节点的目录数。 */
    val truncatedDirs: Int,
    /**
     * `listFiles()` 失败的目录数（权限 / scoped storage / 拔卡）。
     *
     * ⚠️ **不记这个数，总量就是「静默少算」**：真机上 `/storage/emulated/0` 扫出 85.59 GB，
     * 而同一块卷已用 189.67 GB —— 差额里最大的一块是 `Android/data`（55 GB），它在 Android 11+
     * 对**任何** App 都不可读（连 `MANAGE_EXTERNAL_STORAGE` 也不行），`listFiles()` 直接返回
     * null。结果页必须把「有 N 个目录没算进去」明写出来，否则用户只会觉得这个功能算错了。
     */
    val deniedDirs: Int,
)

/**
 * 扫描器。**一个实例扫一次**（内部有累计状态），要重扫就新建一个。
 *
 * @param topN 全局保留几个最大文件（0 = 不要）
 * @param onProgress 每 [PROGRESS_EVERY] 个文件回调一次，**在工作线程**上调用
 * @param isCancelled 每个文件、每个目录都问一次；返回 true 时 [scan] 返回 null
 */
internal class DiskScanner(
    private val topN: Int = 100,
    private val onProgress: (files: Int, bytes: Long) -> Unit = { _, _ -> },
    private val isCancelled: () -> Boolean = { false },
) {
    private var files = 0
    private var bytes = 0L
    private var truncated = 0
    private var denied = 0

    /** 小顶堆：堆顶是当前候选里最小的，比它大就换掉 —— 内存只占 N 个。 */
    private val top = PriorityQueue<Pair<Long, File>>(compareBy { it.first })

    /** 扫描 [root]。被取消时返回 null（调用方据此区分「取消」与「空目录」）。 */
    fun scan(root: File): DiskScan? {
        val node = walk(root, 0) ?: return null
        return DiskScan(node, top.sortedByDescending { it.first }, files, bytes, truncated, denied)
    }

    /**
     * 递归一棵子树，返回它的节点。取消时返回 null 一路冒泡出去。
     *
     * 节点的 `bytes` / `files` 由**子节点与直接文件**累加而来 —— 这就是「只走一趟」
     * 的落点：回溯时顺手拿到子树总量，不需要第二遍。
     */
    private fun walk(dir: File, depth: Int): DiskNode? {
        if (isCancelled()) return null
        val node = DiskNode(dir)
        // listFiles() 会抛（权限 / 拔卡 / IO 错），一个读不了的目录不该让整次扫描失败。
        val kids = runCatching { dir.listFiles() }.getOrNull()
        if (kids == null) {
            // 读不了 → 记一笔，让结果页能说「有 N 个目录没算进去」（见 [DiskScan.deniedDirs]）。
            denied++
        } else {
            for (f in kids) {
                if (isCancelled()) return null
                if (f.isDirectory) {
                    if (depth < MAX_DEPTH) {
                        val sub = walk(f, depth + 1) ?: return null
                        node.dirs.add(sub)
                        node.add(sub.bytes, sub.files)
                    } else {
                        truncated++
                        val (b, n) = weigh(f)
                        node.add(b, n)
                    }
                } else {
                    count(f, node)
                }
            }
        }
        node.dirs.sortByDescending { it.bytes }
        node.topFiles.sortByDescending { it.first }
        return node
    }

    /**
     * 深度到顶后的纯累加：不建任何节点，只把大小 / 文件数 / 全局 top 走一遍。
     * 用显式栈而不是递归 —— 这一段本来就是为了「太深」，再递归就没意义了。
     */
    private fun weigh(dir: File): Pair<Long, Int> {
        var b = 0L
        var n = 0
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            if (isCancelled()) break
            val d = stack.removeLast()
            val kids = runCatching { d.listFiles() }.getOrNull()
            if (kids == null) {
                denied++
                continue
            }
            for (f in kids) {
                if (f.isDirectory) {
                    stack.addLast(f)
                } else {
                    b += count(f, null)
                    n++
                }
            }
        }
        return b to n
    }

    /** 记一个文件：全局计数 / 全局 top-N / 所属目录的 top-3。返回它的大小。 */
    private fun count(f: File, owner: DiskNode?): Long {
        val sz = runCatching { f.length() }.getOrDefault(0L)
        files++
        bytes += sz
        if (topN > 0) {
            if (top.size < topN) {
                top.add(sz to f)
            } else if (sz > (top.peek()?.first ?: 0L)) {
                top.poll()
                top.add(sz to f)
            }
        }
        if (owner != null) {
            owner.add(sz, 1)
            val tf = owner.topFiles
            if (tf.size < TOP_FILES_PER_DIR) {
                tf.add(sz to f)
            } else {
                var minI = 0
                for (i in 1 until tf.size) if (tf[i].first < tf[minI].first) minI = i
                if (sz > tf[minI].first) tf[minI] = sz to f
            }
        }
        if (files % PROGRESS_EVERY == 0) onProgress(files, bytes)
        return sz
    }

    internal companion object {
        /** 每个目录直接保留几个「最大文件」—— 够填满钻进去那一屏就行。 */
        const val TOP_FILES_PER_DIR = 3

        /** 建节点的深度上限，超过只累加（见类注释的兜底）。 */
        const val MAX_DEPTH = 12

        /** 每多少个文件回报一次进度（和 `uu du` 同一档）。 */
        const val PROGRESS_EVERY = 2000
    }
}

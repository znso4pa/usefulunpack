package com.usefulunpacker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.File

/**
 * 终端会话状态容器。
 *
 * **会话按 tab 分桶**（[sessions]）：每个窗口有自己独立的 cwd / 历史 / scrollback ——
 * 在 tab1 里跑的命令不会出现在 tab2 的终端里（产品反馈的跨 tab 串台 bug）。
 * FD 表例外：[fds] 是进程级单例（大退才清，跨 tab 共享，用户拍板）。
 *
 * 放在 ViewModel 而不是 Activity 字段 / 进程单例：
 *  - Activity 字段：旋转即毁，且 scrollback 要塞进 Bundle（几十 KB 文本不值得）
 *  - 进程单例：「关掉终端再打开」的归属说不清，且跨 Activity 实例泄漏
 *
 * **关闭终端层 ≠ 清会话**：关掉再打开时该 tab 的历史与输出都还在，这是预期行为。
 * 只有显式「清屏」或进程结束才清。（写明是为了防止后来人「顺手」清掉。）
 */
internal class TerminalViewModel : ViewModel() {

    /** FD 表：**进程级单例**（大退才清，跨 tab/终端开关/旋转保留），ls 与 scan 共用。 */
    val fds = FdTable.GLOBAL

    /** 每个 tab 一份会话。 */
    private val sessions = HashMap<Int, Session>()

    fun session(tabId: Int): Session = sessions.getOrPut(tabId) { Session() }

    /** 清掉已关闭 tab 的会话（≤200KB scrollback ×4 不该陪葬整个 Activity 生命周期）。 */
    fun retainSessions(alive: Collection<Int>) {
        sessions.keys.retainAll(alive.toSet())
    }

    class Session {
        /** 会话 cwd。`cd` 会改它。 */
        var cwd: File = File("/")

        /** cwd 是否已从当前 tab 对齐过。没对齐过时首次 show() 取 activeTab.currentDir。 */
        var cwdInitialized: Boolean = false

        /** `cd -` 的上一个目录。 */
        var lastDir: File? = null

        private val history = ArrayList<String>()

        /** scrollback。超限从**头部**裁，保尾部（用户刚看的部分）。 */
        private val buf = StringBuilder()

        /**
         * 进行中的进度行在 [buf] 里的起点（x/c 进度不弹对话框，在终端里整行原地
         * 刷新）。-1 = 当前没有进度行；append 会把它定型为普通行。
         */
        private var progressStart = -1

        var output: String = ""
            private set

        val historySize: Int get() = history.size

        fun historyAt(i: Int): String? = history.getOrNull(i)

        fun pushHistory(line: String) {
            // 连续重复的同一条不重复入栈（回车连按不该刷屏）
            if (history.isEmpty() || history[history.size - 1] != line) history.add(line)
            if (history.size > HISTORY_LIMIT) history.removeAt(0)
        }

        /** 进度行：已有一条就**原地替换**，否则新起一行（行尾带 \n 便于定型）。 */
        fun progress(line: String) {
            if (progressStart < 0) progressStart = buf.length
            buf.replace(progressStart, buf.length, line + "\n")
            output = buf.toString()
        }

        /** 进度行定型为普通行（结果行紧随其后 append 时也走这条路径）。 */
        fun endProgress() {
            progressStart = -1
        }

        fun append(text: String) {
            endProgress()  // 进度行就地变成普通行，新内容接在其后
            buf.append(text).append('\n')
            trimHead()
            output = buf.toString()
        }

        private fun trimHead() {
            var over = buf.length - OUTPUT_LIMIT
            if (over <= 0) return
            // 裁到下一个换行，避免把一行切成半截
            var nl = buf.indexOf("\n", over)
            if (nl < 0) nl = buf.length - 1
            buf.delete(0, nl + 1)
        }

        fun clearOutput() {
            buf.setLength(0)
            progressStart = -1
            output = ""
        }

        companion object {
            private const val OUTPUT_LIMIT = 200_000
            private const val HISTORY_LIMIT = 200
        }
    }

    companion object {
        /**
         * 终端层**不是** Fragment，所以不能用 `by viewModels()`；从 Activity 取
         * （MainActivity 是 ViewModelStoreOwner）。配置变更时 ViewModelProvider
         * 返回同一实例，于是各 tab 的会话全部存活。
         *
         * 必须走 `ViewModelProvider`：直接调 `ViewModelStore.get/put` 是
         * restricted API（只允许 androidx 自己的 library group 调用），
         * lint 会报 `RestrictedApi` **error**。
         */
        fun of(activity: MainActivity): TerminalViewModel =
            ViewModelProvider(activity)[TerminalViewModel::class.java]
    }
}

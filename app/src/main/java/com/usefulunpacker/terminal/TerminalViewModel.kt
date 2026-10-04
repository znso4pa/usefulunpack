package com.usefulunpacker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.File

/**
 * 终端会话状态。
 *
 * 放在 ViewModel 而不是 Activity 字段 / 进程单例：
 *  - Activity 字段：旋转即毁，且 scrollback 要塞进 Bundle（几十 KB 文本不值得）
 *  - 进程单例：「关掉终端再打开」的归属说不清，且跨 Activity 实例泄漏
 *
 * **关闭终端层 ≠ 清本 ViewModel**：关掉再打开时历史与输出都还在，这是预期行为。
 * 只有显式「清屏」或进程结束才清。（写明是为了防止后来人「顺手」在 hide() 里清掉。）
 */
internal class TerminalViewModel : ViewModel() {

    /** 会话 cwd。`cd` 会改它。 */
    var cwd: File = File("/")

    /** cwd 是否已从当前 tab 对齐过。没对齐过时首次 show() 取 activeTab.currentDir。 */
    var cwdInitialized: Boolean = false

    /** `cd -` 的上一个目录。 */
    var lastDir: File? = null

    /** `uu scan` 建立的 FD 表（跨命令保留，因为 fd3 要留给后续的 dd / x 用）。 */
    val fds = FdTable()

    private val history = ArrayList<String>()

    /** scrollback。超限从**头部**裁，保尾部（用户刚看的部分）。 */
    private val buf = StringBuilder()

    var output: String = ""
        private set

    val historySize: Int get() = history.size

    fun historyAt(i: Int): String? = history.getOrNull(i)

    fun pushHistory(line: String) {
        // 连续重复的同一条不重复入栈（回车连按不该刷屏）
        if (history.isEmpty() || history[history.size - 1] != line) history.add(line)
        if (history.size > HISTORY_LIMIT) history.removeAt(0)
    }

    fun append(text: String) {
        buf.append(text).append('\n')
        var over = buf.length - OUTPUT_LIMIT
        if (over > 0) {
            // 裁到下一个换行，避免把一行切成半截
            var nl = buf.indexOf("\n", over)
            if (nl < 0) nl = buf.length - 1
            buf.delete(0, nl + 1)
            over = 0
        }
        output = buf.toString()
    }

    fun clearOutput() {
        buf.setLength(0)
        output = ""
    }

    companion object {
        private const val OUTPUT_LIMIT = 200_000
        private const val HISTORY_LIMIT = 200

        /**
         * 终端层**不是** Fragment，所以不能用 `by viewModels()`；从 Activity 取
         * （MainActivity 是 ViewModelStoreOwner）。配置变更时 ViewModelProvider
         * 返回同一实例，于是 cwd / 历史 / scrollback 全部存活。
         *
         * 必须走 `ViewModelProvider`：直接调 `ViewModelStore.get/put` 是
         * restricted API（只允许 androidx 自己的 library group 调用），
         * lint 会报 `RestrictedApi` **error**。
         */
        fun of(activity: MainActivity): TerminalViewModel =
            ViewModelProvider(activity)[TerminalViewModel::class.java]
    }
}

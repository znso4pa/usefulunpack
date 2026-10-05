package com.usefulunpacker

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 全屏终端层。
 *
 * **不是独立 Activity**（三条硬约束）：
 *  1. `cd` 要实时联动 tab 导航（`navTab`），独立 Activity 调不到
 *  2. 解压/封包的进度卡挂在 MainActivity 的 OpOverlay 上，独立 Activity 看不见
 *  3. `tryStartOperation` 的队列与取消语义绑定 MainActivity
 *
 * 会话状态（cwd / 历史 / 输出）在 [TerminalViewModel] 里，旋转存活。
 *
 * 层级注意（§7.1）：OpOverlay 的 host 是**按需**挂到 `android.R.id.content`
 * 末尾、空了又移除。所以本层打开时若 host 已存在，必须插到它**前面**，
 * 否则「操作进行中才打开终端」会把进度卡盖住。
 */
internal class TerminalPanel(private val act: MainActivity) {

    private val vm = TerminalViewModel.of(act)
    private val fds = vm.fds

    /**
     * 当前绑定会话（按 tab 分桶，见 TerminalViewModel.sessions）。
     * show()/mount() 时绑定；hide() 后失效（视图引用也一并清掉）。
     */
    private lateinit var sess: TerminalViewModel.Session
    private val tokenizer = CliTokenizer()

    private var root: LinearLayout? = null

    /**
     * 本终端归属的 tab（按 tab 扎根，ed4d9b3 弹窗扎根同一个模型）：
     * 切走 GONE 隐藏（不吃触摸不绘制，picker tab 露得出来），切回自动恢复 ——
     * 会话状态在 ViewModel，输出/历史/cd 过的目录全部还在，接着用。
     *
     * `cd` 导航的也是这个 tab（不是执行时刻的 activeTab）。
     */
    private var ownerTabId: Int = -1
    private var out: TextView? = null
    private var scroller: ScrollView? = null

    /**
     * 独立的**单行进度视图**（输出区与输入行之间）：x/c 的进度每 200ms 刷这里，
     * 大输出区完全不动 —— 之前整块输出 setText 5 次/秒，最大 200KB，真机抽帧。
     */
    private var progressView: TextView? = null
    private var input: EditText? = null
    private var cwdView: TextView? = null
    private var pinnedToBottom = true
    /** 手指是否正按在输出区拖动（只有真实拖动才改变跟随状态）。 */
    private var userScrolling = false

    /** 运行中操作的取消钩子（命令侧注册；点进度行 → 确认 → 调用）。 */
    @Volatile private var cancelThunk: (() -> Unit)? = null
    private var historyIdx = -1

    private fun str(id: Int, vararg a: Any) = act.getString(id, *a)


    // ─── 挂载 ────────────────────────────────────────────────────────────

    fun show() {
        val cur = act.activeTab.tabId
        val existing = root
        if (existing != null) {
            if (ownerTabId == cur) {
                // 同一 tab 再点 CLI：可见时关闭（toggle），隐藏中（切走又切回）
                // 则恢复。
                if (existing.visibility == View.VISIBLE) hide() else {
                    existing.visibility = View.VISIBLE
                    render()  // 同 onTabChanged：GONE 期间的滚动位置可能失效
                }
                return
            }
            // 另一个 tab 的终端还挂着：撤下它的视图（会话按 tab 留在 vm 里），
            // 给当前 tab 挂一个**它自己的**终端 —— 会话按 tab 独立，绝不串台。
            hide()
        }
        mount(cur)
    }

    /** 为 [tabId] 挂一个全新视图，绑定它自己的会话（cwd/历史/输出）。 */
    private fun mount(tabId: Int) {
        sess = vm.session(tabId)
        ownerTabId = tabId
        // 面板级状态跟会话走：换 tab 重挂后不残留上一个会话的滚动/历史游标
        historyIdx = -1
        pinnedToBottom = true
        // 首次打开时把 cwd 对齐到当前 tab 的目录（之后再由 cd 自己维护，
        // 重开终端不能把用户 cd 过去的位置重置掉）
        if (!sess.cwdInitialized) {
            sess.cwd = act.currentDir
            sess.cwdInitialized = true
        }
        val dm = act.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        // ── 输出区 ──────────────────────────────────────────────────────
        val outView = TextView(act).apply {
            setTextColor(C["secondary"]!!)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(0f, 1.25f)
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(act).apply {
            // Honor/EMUI ROM: custom ScrollView 启用原生滚动条会在
            // onDrawScrollBars 里 NPE（项目既有不变量）
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        scroll.addView(outView)
        scroller = scroll
        out = outView

        // ── 顶部工具行：标题 + cwd + 清屏/关闭 ────────────────────────────
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C["surface_raised"]!!)
            setPadding(dp(12), dp(6), dp(4), dp(6))
            addView(TextView(act).apply {
                text = act.getString(R.string.terminal_title)
                setTextColor(C["primary"]!!); textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(act).apply {
                id = R.id.terminal_cwd
                text = sess.cwd.name.ifEmpty { "/" }
                setTextColor(C["hint"]!!); textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(8) }
                also { cwdView = it }
            })
            addView(action(R.string.terminal_docs) {
                append(UuCommands.renderDocs())
            })
            addView(action(R.string.terminal_clear) { sess.clearOutput(); render() })
            addView(action(R.string.terminal_close) { hide() })
        }

        // ── 底部输入行：提示符 + 输入框 + 运行 ────────────────────────────
        val inp = EditText(act).apply {
            setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            textSize = 13f
            setSingleLine(true)
            setPadding(0, 0, 0, 0)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { run0(); true } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { recallHistory(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { recallHistory(1); true }
                    else -> false
                }
            }
        }
        input = inp
        val inputBar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C["surface"]!!)
            setPadding(dp(12), dp(10), dp(8), dp(10))
            addView(TextView(act).apply {
                text = PROMPT
                setTextColor(C["accent"]!!); textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
            })
            addView(inp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(action(R.string.terminal_run) { run0() }.apply {
                setTextColor(C["accent"]!!)
            })
        }
        // 分隔线必须**独立一层**：放进水平 LinearLayout 且宽度 MATCH_PARENT 的话，
        // 它会吃掉整行宽度，把提示符和运行按钮挤出屏幕（第一版就这么全空了）。
        val inputStack = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C["surface"]!!)
            addView(View(act).apply { setBackgroundColor(C["divider"]!!) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(inputBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val layout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            id = R.id.terminal_root
            setBackgroundColor(C["nav_bg"]!!)
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(TextView(act).apply {
                progressView = this
                visibility = View.GONE
                setTextColor(C["accent"]!!)
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setBackgroundColor(C["surface_dim"]!!)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                isClickable = true
                // 点进度行 = 取消当前操作（排队中=出队,运行中=点火 cancel）
                setOnClickListener {
                    val thunk = cancelThunk ?: return@setOnClickListener
                    android.app.AlertDialog.Builder(act)
                        .setTitle(R.string.cli_cancel_op)
                        .setPositiveButton(R.string.action_confirm) { _, _ ->
                            thunk()
                            cancelThunk = null
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(inputStack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // 直接挂到 activity content（OpOverlay.ensureHost 同机制）：
        // - 若 op_overlay_host 已存在（有操作在跑），把终端插到它**之前**，
        //   否则「操作进行中才打开终端」会让终端盖住进度卡；
        // - host 不存在时正常追加 —— 之后 ensureHost 创建 host 时 addView 到
        //   content 末尾，自然盖在终端之上。
        // topInset 让开 tab 栏 + 工具栏：终端自己画标题/清屏/关闭，tab 栏必须
        // 留着 —— 它是切窗口的唯一入口。
        val content = act.findViewById<ViewGroup>(android.R.id.content)
            ?: return cleanupAfterMountFail()
        val overlayHost = content.findViewById<View>(R.id.op_overlay_host)
        val insertIdx = if (overlayHost != null) content.indexOfChild(overlayHost) else content.childCount
        content.addView(layout, insertIdx.coerceIn(0, content.childCount),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layout.layoutParams = (layout.layoutParams as ViewGroup.MarginLayoutParams).apply {
            topMargin = topInset()
        }
        root = layout

        if (sess.output.isEmpty()) append(str(R.string.terminal_banner))
        render()
        // 刚 attach 时 requestFocus() 还不生效（窗口没拿到焦点），键盘也不会自己弹，
        // 于是打开终端后直接敲键盘没反应 —— 必须 post 到下一帧再聚焦。
        inp.post {
            if (!isOpen) return@post
            inp.requestFocus()
            act.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(inp, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * 工具栏 + tab 条 + 状态栏的总高（与 OpOverlay 的 topInset 同一口径：
     * 状态栏实测 + 106dp 固定 chrome）。
     */
    private fun topInset(): Int {
        val dm = act.resources.displayMetrics
        var inset = 106 * dm.density
        val sb = act.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (sb > 0) inset += act.resources.getDimensionPixelSize(sb)
        return inset.toInt()
    }

    private fun cleanupAfterMountFail() {
        scroller = null; out = null; input = null; cwdView = null
    }

    private fun action(resId: Int, onClick: () -> Unit) = TextView(act).apply {
        text = act.getString(resId)
        setTextColor(C["primary"]!!); textSize = 13f
        val pad = (12 * act.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        val ripple = android.util.TypedValue()
        act.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
        setOnClickListener { onClick() }
    }

    /** 用户主动关闭（✕ / back）：从视图树移除。状态留在 ViewModel。 */
    fun hide() {
        val r = root
        root = null; out = null; scroller = null; input = null; cwdView = null
        progressView = null
        cancelThunk = null
        (r?.parent as? ViewGroup)?.removeView(r)
    }

    val isOpen: Boolean get() = root != null

    /**
     * 按 tab 扎根（ed4d9b3 弹窗扎根同一个模型）：切走 GONE（视图留在树上，
     * 不吃触摸不绘制，picker tab 露得出来），切回自动恢复。归属 tab 被关掉
     * 时彻底移除 —— 它永远回不来了。
     */
    fun onTabChanged(newActiveTabId: Int) {
        // 顺带清掉已关闭 tab 的会话（防累积）
        vm.retainSessions(act.tabs.map { it.tabId })
        val r = root ?: return
        val ownerAlive = act.tabs.any { it.tabId == ownerTabId }
        if (!ownerAlive) { hide(); return }
        val visible = newActiveTabId == ownerTabId
        r.visibility = if (visible) View.VISIBLE else View.GONE
        // 恢复可见时滚回底部：GONE 期间后台输出的 fullScroll 在无布局的视图上
        // 会失败，不补一次的话切回来停在顶部，看不到最新输出。
        if (visible) render()
    }

    /** configChanges 旋转不重建视图树：顶部偏移要手动重算。 */
    fun relayout() {
        val r = root ?: return
        r.layoutParams = (r.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            topMargin = topInset()
        }
    }

    /** back 只在终端**可见**时拦截（切走隐藏后 back 应作用于下层界面）。 */
    fun onBackPressed(): Boolean {
        if (root?.visibility != View.VISIBLE) return false
        hide()
        return true
    }


    // ─── 输出 ────────────────────────────────────────────────────────────

    /**
     * ↑/↓ 回看历史。`historyIdx` 的取值域是 -1..n：
     * -1 = 还没回看（在输入新的一行），n = 回到底下的新行（输入框清空）。
     */
    private fun recallHistory(delta: Int) {
        val n = sess.historySize
        if (n == 0) return
        val next = when {
            historyIdx < 0 -> if (delta < 0) n - 1 else return
            historyIdx + delta >= n -> n
            else -> historyIdx + delta
        }
        historyIdx = next
        val text = if (next >= n) "" else sess.historyAt(next).orEmpty()
        input?.setText(text)
        input?.setSelection(text.length)
    }

    private fun append(text: String) {
        sess.append(text)
        render()
    }

    private fun render() {
        val sv = scroller
        // setText 会把滚动位置重置到顶部：非跟随状态下必须先记后还原，否则
        // 进度刷新期间用户上滑浏览会每 200ms 被拽回最头上。
        val scrollY = sv?.scrollY ?: 0
        out?.text = sess.output
        // cwd 是**衍生**状态：`cd` 只改 sess.cwd，不去碰这个 TextView。
        // 不在这里统一刷新的话，`cd` 之后标题栏还停在旧目录。
        cwdView?.text = sess.cwd.name.ifEmpty { "/" }
        if (pinnedToBottom) sv?.post { sv.fullScroll(View.FOCUS_DOWN) }
        else sv?.post { sv.scrollTo(0, scrollY) }
        // 跟随状态**只由用户真实拖动改变**：键盘弹出/收起、setText、布局变化都会
        // 触发 scroll-change，早期实现把它们当作用户上滑，一次误判就永久卡在
        // 半空（真机反馈：输出继续走，视图不再跟底）。
        fun recomputePinned() {
            val h = sv?.getChildAt(0)?.height ?: 0
            val range = h - (sv?.height ?: 0)
            pinnedToBottom = range <= 0 || (sv?.scrollY ?: 0) >= range - 8
        }
        sv?.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> userScrolling = true
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    userScrolling = false
                    sv.post { recomputePinned() }
                }
            }
            false
        }
        sv?.setOnScrollChangeListener { _, _, _, _, _ ->
            if (userScrolling) recomputePinned()
        }
        // 布局变化（键盘开合/旋屏）：跟随时保持贴底
        sv?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (pinnedToBottom) v.post { sv.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ─── 执行 ────────────────────────────────────────────────────────────

    /**
     * 外部入口（`am start --es uut <脚本路径>`）：不依赖 UI 输入地跑一个 UUT
     * 脚本 —— 供自动化/CI 驱动（`adb` 注入文本会被输入法吞掉，见 Uut.kt）。
     * 输出进该 tab 的会话缓冲（用户打开终端可见）；脚本可用 `> <路径>` 自行
     * 落盘，外部读取结果即完成闭环。owner=-1 / pv=null：不碰任何视图。
     */
    fun runExternal(path: String) {
        val s = vm.session(act.activeTab.tabId)
        // 与会话首次挂载同一套初始化（cwd 是衍生状态，不能留成 File("/")）
        if (!s.cwdInitialized) {
            s.cwd = act.currentDir
            s.cwdInitialized = true
        }
        // 同时落一份 `<脚本>.log`：外部驱动者读它拿结果（adb pull/cat 即可），
        // 会话缓冲也收到同样内容（用户打开终端能看到刚才跑了什么）。
        val log = File(UuText.resolve(s.cwd, path).absolutePath + ".log")
        thread {
            runCatching { log.parentFile?.mkdirs(); log.writeText("") }
            runScript(listOf(path), ExecCtx(s, null, -1, sink = { line ->
                postOut(line, s)
                runCatching { log.appendText(line + "\n") }
            }, echo = true), 0)
            runCatching { log.appendText("[exit]\n") }
        }
    }

    private fun run0() {
        val line = input?.text?.toString()?.trim().orEmpty()
        if (line.isEmpty()) return
        input?.setText("")
        historyIdx = -1
        sess.pushHistory(line)
        val tokens = when (val t = tokenizer.tokenize(line)) {
            is CliTokenizer.Out.Bad -> {
                val reason = t.reason
                val msg = if (reason is CliTokenizer.Reason.UnclosedQuote) {
                    UuText.unclosedQuote(ctxStr(), reason.quote)
                } else {
                    UuText.failed(ctxStr(), reason.toString())
                }
                postOut(msg, sess)
                return
            }
            is CliTokenizer.Out.Ok -> t.tokens
        }
        // 捕获当前会话/进度视图/归属 tab：命令期间用户可能切 tab 甚至打开别的
        // 终端 —— 结果必须回到发起它的那个会话，不能读活字段串台。
        val s = sess
        val pv = progressView
        val owner = ownerTabId
        append(PROMPT + line)
        thread { runTokens(line, tokens, ExecCtx(s, pv, owner)) }
    }

    /** 一行命令的产出：要么是文本，要么是「缺路径，请用户选一个」。 */
    private sealed interface Outcome {
        data class Text(val s: String, val exitCode: Int = 0) : Outcome
        data class NeedPath(val cmd: String, val kind: UuCommands.Picker) : Outcome
    }

    /** runSegment 的结果。 */
    private sealed interface SegResult
    private class SegDone(val exit: Int) : SegResult
    private class SegAsk(val cmd: String, val kind: UuCommands.Picker) : SegResult

    /**
     * 执行上下文：会话 + 进度视图 + 归属 + 输出汇。
     * [sink] == null → 正常交互（写终端）；非 null → `$(...)` 捕获（只收文本,
     * 不碰视图、不弹选择器、不写进度）。
     */
    private inner class ExecCtx(
        val s: TerminalViewModel.Session,
        val pv: TextView?,
        val owner: Int,
        val sink: ((String) -> Unit)? = null,
        /** 是否回显脚本行。外部运行也回显（落日志便于事后排查）。 */
        val echo: Boolean = sink == null,
        /**
         * 每**段**执行完的回调（`&&`/`;` 串联里逐段触发）。UUT 用它维护 `$?`：
         * 只在整行结束后记账的话，`uu l nope.zip ; echo rc=$?` 会打印上一行的
         * 退出码 —— 而 shell 里这就是"看前一条命令结果"的标准写法（实测踩过）。
         */
        val onExit: ((Int) -> Unit)? = null,
    ) {
        val interactive: Boolean get() = sink == null
        fun emit(text: String) { if (sink != null) sink(text) else postOut(text, s) }

        /** 复制一份带退出码钩子的上下文（会话/进度/归属不变）。 */
        fun withExitHook(hook: (Int) -> Unit) = ExecCtx(s, pv, owner, sink, echo, hook)
    }

    /**
     * 后台执行。**只算字符串 / 只改 ViewModel，绝不碰 View** ——
     * 在这里 setText 会抛 CalledFromWrongThreadException 并杀掉整个进程。
     * 支持 `&&` / `;` 链、`|` 管道（过滤段仅 grep/head/tail/wc）、`>` / `>>`
     * 重定向与 `uu run <UUT 脚本>`；返回最后一段的退出码（0 成功 / 1 失败 / 2 缺参）。
     */
    private fun runTokens(line: String, tokens0: List<String>, ec: ExecCtx, depth: Int = 0): Int {
        var exit = 0
        for (seg in UuCommands.splitSegments(tokens0)) {
            if (seg.andAlso && exit != 0) break
            if (seg.tokens.isEmpty()) continue
            when (val r = runSegment(line, seg.tokens, ec, depth)) {
                is SegDone -> {
                    exit = r.exit
                    ec.onExit?.invoke(r.exit)
                }
                is SegAsk -> {
                    hideProgressView(ec.pv, ec.owner)
                    act.runOnUiThread {
                        if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                        askPath(line, r.cmd, r.kind, ec.s, ec.pv, ec.owner)
                    }
                    return exit
                }
            }
        }
        hideProgressView(ec.pv, ec.owner)
        return exit
    }

    private fun hideProgressView(pv: TextView?, owner: Int) {
        cancelThunk = null
        act.runOnUiThread {
            if (act.isFinishing || act.isDestroyed) return@runOnUiThread
            if (ownerTabId == owner) pv?.visibility = View.GONE
        }
    }

    /** 一段：可选重定向 + 管道链；首段正常执行，后续段必须是内部过滤器。 */
    private fun runSegment(line: String, tokens: List<String>, ec: ExecCtx, depth: Int): SegResult {
        var toks = tokens
        var redir: Pair<String, Boolean>? = null
        val ri = toks.indexOfFirst { it == ">" || it == ">>" }
        if (ri >= 0) {
            val path = toks.getOrNull(ri + 1)
            if (path == null) {
                ec.emit(ctxStr()(R.string.cli_redirect_path, arrayOf(toks[ri])))
                return SegDone(2)
            }
            redir = path to (toks[ri] == ">>")
            toks = toks.subList(0, ri)
        }
        val stages = UuCommands.splitOnPipe(toks)
        var text = ""
        var exit = 0
        for ((i, stage) in stages.withIndex()) {
            if (stage.isEmpty()) continue
            if (i == 0) {
                when (val o = runStage(stage, ec, depth, capture = redir != null)) {
                    is Outcome.NeedPath -> {
                        if (stages.size == 1 && redir == null && ec.interactive) return SegAsk(o.cmd, o.kind)
                        // 管道/重定向/捕获语境不能弹选择器：说清楚要显式给路径，
                        // 而不是笼统报"只支持过滤器"（那是 pipe 的错，不是这里的病）
                        ec.emit(ctxStr()(R.string.cli_need_explicit_path, arrayOf(o.cmd)))
                        return SegDone(2)
                    }
                    is Outcome.Text -> { text = o.s; exit = o.exitCode }
                }
            } else {
                val f = UuCommands.pipeFilter(stage, text)
                if (f == null) {
                    ec.emit(ctxStr()(R.string.cli_pipe_filter_only, arrayOf()))
                    return SegDone(2)
                }
                text = f.first
                exit = f.second
            }
        }
        if (redir != null) {
            val f = UuText.resolve(ec.s.cwd, redir.first)
            val ok = runCatching {
                f.parentFile?.mkdirs()
                if (redir.second) f.appendText(text + "\n") else f.writeText(text + "\n")
                true
            }.getOrDefault(false)
            ec.emit(
                if (ok) ctxStr()(R.string.cli_saved_to, arrayOf(f.name))
                else UuText.failed({ id, a -> if (a.isEmpty()) act.getString(id) else act.getString(id, *a) },
                    redir.first)
            )
            return SegDone(if (ok) exit else 1)
        }
        if (text.isNotEmpty()) ec.emit(text)
        return SegDone(exit)
    }

    /**
     * 单段分发（含 `uu run` 脚本；shell 兜底用 token 重建命令）。
     * [capture]：本段带重定向 —— 需要把**整棵子树**的输出收进返回值，因为
     * `uu run inner.uut > out.txt` 里的子脚本是直接 emit 的，不经过 text；
     * 不特判的话重定向写出一个空文件（实测踩过：日志说"已保存到"，文件是空的）。
     */
    private fun runStage(tokens: List<String>, ec: ExecCtx, depth: Int, capture: Boolean = false): Outcome {
        val cwd = ec.s.cwd
        // 通配符展开(所有命令共用): uu x *.zip / uu c *.ks -c / uu hash *.png…
        // 模式参数位置排除（find 的 glob / grep 的 pattern，见 globSkipIndices）
        val ex = UuCommands.expandGlobs(tokens, cwd, UuCommands.globSkipIndices(tokens))
        return when (val argv = ex.firstOrNull()) {
            null -> Outcome.Text("")
            "ls" -> lsOutcome(ex.drop(1), cwd)
            "pwd" -> Outcome.Text(cwd.absolutePath)
            "cd" -> cdOutcome(ex.drop(1), ec.s, ec.owner)
            "help" -> Outcome.Text(UuCommands.renderHelp(ctx(ec.s, ec.pv, ec.owner)))
            "uu" -> {
                if (ex.getOrNull(1) == "run") {
                    if (capture) {
                        // 子脚本 + 它再嵌套的一切输出都收进来（echo 保留，便于读日志）
                        val sb = StringBuilder()
                        val capEc = ExecCtx(ec.s, null, -1, sink = { sb.append(it).append('\n') }, echo = true)
                        // 先跑完再取字符串：写成 Outcome.Text(sb.toString(), runScript(...))
                        // 是错的 —— Kotlin 从左到右求值，sb 还没被填就已经 toString 了
                        //（实测：重定向出来是个空文件，且子脚本输出整个消失）。
                        val code = runScript(ex.drop(2), capEc, depth)
                        Outcome.Text(sb.toString().trimEnd('\n'), code)
                    } else Outcome.Text("", runScript(ex.drop(2), ec, depth))
                } else runUu(ex.drop(1), ctx(ec.s, ec.pv, ec.owner))
            }
            else -> {
                val cmdLine = ex.joinToString(" ") { UuCommands.shellQuote(it) }
                val (out, code) = runShell(cmdLine, cwd)
                Outcome.Text(out, code)
            }
        }
    }

    /** 行分词（引号未闭合返回 null）。 */
    private fun tokenizeLine(text: String): List<String>? = when (val t = tokenizer.tokenize(text)) {
        is CliTokenizer.Out.Ok -> t.tokens
        is CliTokenizer.Out.Bad -> null
    }

    /**
     * `uu run [-k] <file> [脚本参数...]`：UUT 脚本——解析 AST 后逐句执行
     * （行内管道/重定向可用）。路径**之后**的参数原样传给脚本（`$1`…`$n`、
     * `$argc`、`$args`），所以带 `-` 的实参不会被误当标志。
     */
    private fun runScript(args: List<String>, ec: ExecCtx, depth: Int): Int {
        if (depth >= SCRIPT_MAX_DEPTH) {
            ec.emit(str(R.string.cli_run_stopped, "0", "1", "depth>${SCRIPT_MAX_DEPTH - 1}"))
            return 1
        }
        var keepGoing = false
        var i = 0
        while (i < args.size && args[i].startsWith("-")) {
            when (args[i]) {
                "-k" -> keepGoing = true
                else -> {
                    ec.emit(str(R.string.cli_unknown_flag, args[i]))
                    return 2
                }
            }
            i++
        }
        val path = args.getOrNull(i)
        if (path == null) {
            ec.emit("uu run [-k] <file.uut> [args...]")
            return 2
        }
        val scriptArgs = args.drop(i + 1)
        val f = UuText.resolve(ec.s.cwd, path)
        if (!f.isFile) {
            ec.emit(str(R.string.cli_not_found, path))
            return 1
        }
        val bytes = runCatching { readPrefix(f, SCRIPT_MAX_BYTES) }.getOrNull() ?: return 1
        val enc = detectBestEncoding(bytes)
        val text = if (enc != null) decodeTextStrict(bytes, enc) else String(bytes, Charsets.UTF_8)
        val ast = try {
            UutParser.parse(text)
        } catch (e: UutParseException) {
            ec.emit(str(R.string.cli_uut_parse, e.reason, e.line.toString()))
            return 2
        }
        val vars = HashMap<String, String>()
        vars["0"] = path
        scriptArgs.forEachIndexed { k, a -> vars[(k + 1).toString()] = a }
        vars["argc"] = scriptArgs.size.toString()
        vars["args"] = scriptArgs.joinToString(" ")
        val r = execUut(ast, vars, ec, depth, keepGoing)
        ec.emit(
            if (r.stoppedLine > 0) str(R.string.cli_run_stopped, r.ran.toString(), r.exit.toString(), r.stoppedLine.toString())
            else str(R.string.cli_run_summary, r.ran.toString(), r.exit.toString())
        )
        return r.exit
    }

    /** 求值一个 UUT 条件（变量比较 / 文件测试）。 */
    private fun evalCond(cond: UutCond, vars: Map<String, String>, cwd: File): Boolean = when (cond) {
        is UutCond.VarEq -> {
            val cur = vars[cond.name] ?: ""
            if (cond.negate) cur != cond.value else cur == cond.value
        }
        is UutCond.Exists -> {
            val p = UutParser.expandVars(cond.rawPath, vars)
            val exists = UuText.resolve(cwd, p).exists()
            if (cond.negate) !exists else exists
        }
    }

    /** 每条命令执行完记下退出码：`$?` / `$errorlevel` 都能取（bat 的 errorlevel 习惯）。 */
    private fun noteExit(vars: MutableMap<String, String>, code: Int) {
        vars["?"] = code.toString()
        vars["errorlevel"] = code.toString()
    }

    /** UUT 执行统计。[returned] = 脚本里执行了 `return`（只结束当前这一层）。 */
    private class UutRun(
        val ran: Int = 0, val exit: Int = 0, val stoppedLine: Int = 0, val returned: Boolean = false,
    )

    private fun execUut(
        stmts: List<UutStmt>, vars: HashMap<String, String>, ec: ExecCtx, depth: Int, keepGoing: Boolean
    ): UutRun {
        var ran = 0
        var exit = 0
        for (st in stmts) {
            when (st) {
                is UutStmt.Cmd -> {
                    // 逐段展开 / 逐段执行（见 UutParser.splitChain 的说明）：
                    // 这样 `a ; echo rc=$?` 的 $? 看到的是 a 的退出码。
                    var lastExit = 0
                    var anyRan = false
                    for ((piece, joiner) in UutParser.splitChain(st.raw)) {
                        if (joiner == "&&" && lastExit != 0) continue   // && 短路
                        val expanded = UutParser.expandVars(piece, vars)
                        if (ec.echo) ec.emit(PROMPT + expanded)
                        val toks = tokenizeLine(expanded) ?: run {
                            ec.emit(str(R.string.cli_unclosed_quote, "'"))
                            return UutRun(ran + 1, 1, st.line)
                        }
                        if (toks.isEmpty()) continue
                        if (!UutParser.commandAllowed(toks[0])) {
                            ec.emit(str(R.string.cli_uut_cmd_not_allowed, toks[0]))
                            if (!keepGoing) return UutRun(ran + 1, 1, st.line)
                            anyRan = true; lastExit = 1; exit = 1
                            continue
                        }
                        lastExit = runTokens(expanded, toks, ec.withExitHook { noteExit(vars, it) }, depth + 1)
                        noteExit(vars, lastExit)
                        anyRan = true
                        // 注意：**不在这里**判错退出 —— `a ; b` 的语义就是 b 必须跑
                        //（停下要等整行结束；否则 `uu l nope.zip ; echo rc=$?` 的
                        // 第二段永远执行不到，退出码也读不出来）
                    }
                    exit = lastExit
                    if (anyRan) ran++
                    if (exit != 0 && !keepGoing) return UutRun(ran, exit, st.line)
                }
                is UutStmt.Set -> {
                    val expanded = UutParser.expandVars(st.rawValue, vars).trim()
                    val cap = UutParser.captureInner(expanded)
                    vars[st.name] = if (cap != null) captureCmd(cap, vars, ec, depth) else expanded
                }
                is UutStmt.IfInline -> {
                    if (evalCond(st.cond, vars, ec.s.cwd)) {
                        val expanded = UutParser.expandVars(st.rawCmd, vars)
                        if (ec.echo) ec.emit(PROMPT + expanded)
                        val toks = tokenizeLine(expanded) ?: run {
                            ec.emit(str(R.string.cli_unclosed_quote, "'"))
                            return UutRun(ran + 1, 1, st.line)
                        }
                        if (toks.isNotEmpty() && !UutParser.commandAllowed(toks[0])) {
                            ec.emit(str(R.string.cli_uut_cmd_not_allowed, toks[0]))
                            if (!keepGoing) return UutRun(ran + 1, 1, st.line)
                            ran++; exit = 1
                        } else if (toks.isNotEmpty()) {
                            exit = runTokens(expanded, toks, ec.withExitHook { noteExit(vars, it) }, depth + 1)
                            noteExit(vars, exit)
                            ran++
                            if (exit != 0 && !keepGoing) return UutRun(ran, exit, st.line)
                        }
                    }
                }
                is UutStmt.For -> {
                    val patterns = UutParser.expandVars(st.rawPatterns, vars)
                        .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    if (patterns.isEmpty()) continue
                    // 单个 `1..N` = 计数循环（纯数字列表，不做通配展开）
                    val range = UutParser.rangeValues(patterns[0])
                    if (range != null) {
                        if (range.isEmpty()) continue      // 超上限保护：什么都不做
                        for (n in range) {
                            vars[st.varName] = n
                            val b = execUut(st.body, vars, ec, depth, keepGoing)
                            ran += b.ran; exit = b.exit
                            if (b.returned || b.stoppedLine > 0) {
                                return UutRun(ran, exit, b.stoppedLine, b.returned)
                            }
                            if (b.exit != 0 && !keepGoing) return UutRun(ran, exit, st.line)
                        }
                        continue
                    }
                    // 复用通配展开。expandGlobs 约定 token0 是命令名（不展开），
                    // 这里没有命令，补一个占位首 token 再丢掉，否则 `for a in *.txt`
                    // 永远匹配不到（实测踩过：报"没有匹配"且循环体整个不跑）。
                    val files = UuCommands.expandGlobs(listOf("for") + patterns, ec.s.cwd)
                        .drop(1)
                        .filterNot { it.contains('*') || it.contains('?') }
                    if (files.isEmpty()) {
                        ec.emit(str(R.string.cli_find_none, patterns.joinToString(" ")))
                        continue
                    }
                    for (f in files) {
                        vars[st.varName] = f
                        val sub = execUut(st.body, vars, ec, depth, keepGoing)
                        ran += sub.ran
                        exit = sub.exit
                        if (sub.returned || sub.stoppedLine > 0) {
                            return UutRun(ran, exit, sub.stoppedLine, sub.returned)
                        }
                        if (sub.exit != 0 && !keepGoing) return UutRun(ran, exit, st.line)
                    }
                }
                is UutStmt.If -> {
                    val body = if (evalCond(st.cond, vars, ec.s.cwd)) st.thenBody else st.elseBody
                    // 空分支是合法的（`if x` … `end` 什么都不做）
                    if (body.isNotEmpty()) {
                        val sub = execUut(body, vars, ec, depth, keepGoing)
                        ran += sub.ran
                        exit = sub.exit
                        if (sub.returned || sub.stoppedLine > 0) {
                            return UutRun(ran, exit, sub.stoppedLine, sub.returned)
                        }
                        if (sub.exit != 0 && !keepGoing) return UutRun(ran, exit, st.line)
                    }
                }
                is UutStmt.Return -> {
                    // return 结束**本层**脚本：嵌套 `uu run` 只结束它自己，
                    // 退出码成为那条 `uu run` 命令的退出码
                    val code = UutParser.expandVars(st.rawCode, vars).trim().toIntOrNull() ?: 0
                    return UutRun(ran, code, 0, returned = true)
                }
            }
        }
        return UutRun(ran, exit, 0)
    }

    /** `$(...)` 捕获：跑一条白名单命令，收集输出文本（不碰视图）。 */
    private fun captureCmd(inner: String, vars: MutableMap<String, String>, ec: ExecCtx, depth: Int): String {
        val expanded = UutParser.expandVars(inner, vars)
        val toks = tokenizeLine(expanded) ?: return ""
        if (toks.isEmpty() || !UutParser.commandAllowed(toks[0])) return ""
        val sb = StringBuilder()
        val capEc = ExecCtx(
            ec.s, null, -1, sink = { sb.append(it).append('\n') },
            onExit = { noteExit(vars, it) },
        )
        runTokens(expanded, toks, capEc, depth + 1)
        return sb.toString().trim()
    }

    /** 结果进捕获的会话；仅当终端仍显示该会话时才刷新视图。 */
    private fun postOut(text: String, s: TerminalViewModel.Session) = act.runOnUiThread {
        if (act.isFinishing || act.isDestroyed) return@runOnUiThread
        s.append(text)
        refreshIfCurrent(s)
    }

    /**
     * 仅当面板正显示 [s] 时才重绘。`sess` 是 lateinit：外部入口
     *（`--es uut`，见 [runExternal]）不挂视图就执行，直接 `sess === s`
     * 会抛 UninitializedPropertyAccessException 并杀掉进程（实测踩过）。
     */
    private fun refreshIfCurrent(s: TerminalViewModel.Session) {
        if (::sess.isInitialized && sess === s) render()
    }

    private fun ctxStr(): StrFn = { id, args ->
        if (args.isEmpty()) act.getString(id) else act.getString(id, *args)
    }

    private fun ctx(s: TerminalViewModel.Session, pv: TextView?, owner: Int) = UuCommands.Ctx(
        prefs = act.prefs,
        cwd = s.cwd,
        activity = act,
        str = ctxStr(),
        fds = fds,
        cacheDir = act.cacheDir,
        // 长命令（x/c）的进度：只刷独立单行视图，**不弹对话框**也不重排大输出区
        //（产品要求：进度只写在 CLI 窗口内；整块 setText 5 次/秒会抽帧）。
        progress = { line ->
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                // 只写发起时刻的进度视图；切 tab 后（owner 变了）静默丢弃，
                // 不污染别的 tab 的终端。
                if (ownerTabId == owner) pv?.apply {
                    visibility = View.VISIBLE
                    text = line
                }
            }
        },
        // x/c 不加路径时的默认输出位置（单独路径，产品要求）
        defaultOutDir = File(android.os.Environment.getExternalStorageDirectory(), "uu_cli"),
        // 命令侧拿到 opH/accessors 后注册取消（进度行可点）
        registerCancel = { thunk -> cancelThunk = thunk },
    )

    private fun runUu(rest: List<String>, c: UuCommands.Ctx): Outcome {
        if (rest.isEmpty()) return Outcome.Text(UuCommands.renderHelp(c))
        val r = UuCommands.dispatch(rest, c)
        val p = r.picker
        return if (p != null) Outcome.NeedPath(rest[0], p) else Outcome.Text(r.text, r.exitCode)
    }

    // ─── 缺路径 → 弹选择器 ────────────────────────────────────────────────

    /** 选中之后要重跑的那一行（`uu l` → `uu l /选中的路径`）。只在主线程读写。 */
    private var pendingLine: String = ""
    private var pendingTokens: List<String> = emptyList()
    private var pendingSession: TerminalViewModel.Session? = null
    private var pendingPv: TextView? = null
    private var pendingOwner: Int = -1

    /**
     * 命令缺路径时的统一入口。
     *
     * 目录类命令（`cd` / `ls`）给**两个**选择，因为「不换目录」本身也是个有效答案：
     * - 保留目录 → 把当前目录填进命令行（`ls` → `ls <当前目录>`）并直接执行
     * - 切换目录 → 打开路径选择器，复用文件管理那套（[showFolderPicker] 自己按
     *   `picker_mode` 偏好走「新窗口」或「对话框」）
     *
     * 手机上靠键盘敲长中文路径很难用，而 UU 的强项本来就是浏览文件，
     * 所以缺参时给选择器比报一行 usage 有用。
     */
    private fun askPath(
        line: String, cmd: String, kind: UuCommands.Picker,
        s: TerminalViewModel.Session, pv: TextView?, owner: Int
    ) {
        pendingLine = line
        pendingTokens = tokenizer.tokenize(line).let { (it as? CliTokenizer.Out.Ok)?.tokens ?: emptyList() }
        // 会话快照：选完目录回来时，即使终端已切到别的 tab，命令结果仍进原会话
        pendingSession = s
        pendingPv = pv
        pendingOwner = owner
        val title = if (kind == UuCommands.Picker.FILE) {
            str(R.string.cli_pick_file_title, cmd)
        } else {
            str(R.string.cli_pick_folder_title, cmd)
        }

        // 句柄要等 create/show 之后才有值；choice 的点击回调是在那之后才触发的，
        // 所以这里捕获 var 是安全的。
        var dlg: android.app.AlertDialog? = null
        fun close() { dlg?.dismiss() }

        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C["surface"]!!)
        }
        fun choice(label: String, sub: String, onClick: () -> Unit) {
            body.addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                val p = { v: Int -> (v * act.resources.displayMetrics.density).toInt() }
                setPadding(p(18), p(14), p(18), p(14))
                setOnClickListener { close(); onClick() }
                addView(TextView(act).apply {
                    text = label
                    setTextColor(C["primary"]!!); textSize = 15f
                })
                addView(TextView(act).apply {
                    text = sub
                    setTextColor(C["hint"]!!); textSize = 12f
                })
            })
            body.addView(View(act).apply {
                setBackgroundColor(C["divider_subtle"]!!)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1
                )
            })
        }

        if (kind == UuCommands.Picker.FOLDER) {
            choice(
                str(R.string.cli_pick_keep), str(R.string.cli_pick_keep_sub, s.cwd.absolutePath)
            ) { rerunWith(s.cwd, s, pv, owner) }
        // 跟随设置里的「路径选择方式」：终端按 tab 扎根后，新窗口模式开的
        // picker tab 会让终端 GONE 让位（选完切回自动恢复），不再需要强制对话框。
            choice(str(R.string.cli_pick_switch), str(R.string.cli_pick_switch_sub)) {
                showFolderPicker(act, s.cwd, false) { picked -> rerunWith(picked, s, pv, owner) }
            }
        } else if (kind == UuCommands.Picker.FILE_OR_FOLDER) {
            // `uu c` 的源：先看当前目录（最常见 = 打包整个 cwd，不弹任何选择器），
            // 再给「选文件夹」（带 ✓ 选此目录入口）和「选文件」两个入口——
            // 之前 FILE 模式的选择器没有选文件夹入口，用户导航到目录里却选不了它。
            choice(
                str(R.string.cli_pick_keep), str(R.string.cli_pick_keep_sub, s.cwd.absolutePath)
            ) { rerunWith(s.cwd, s, pv, owner) }
            choice(str(R.string.cli_pick_switch), str(R.string.cli_pick_switch_sub)) {
                showFolderPicker(act, s.cwd, false) { picked -> rerunWith(picked, s, pv, owner) }
            }
            choice(str(R.string.action_choose_dir), str(R.string.cli_pick_switch_sub)) {
                showFolderPicker(act, s.cwd, true) { picked -> rerunWith(picked, s, pv, owner) }
            }
        } else {
            choice(str(R.string.action_choose_dir), str(R.string.cli_pick_switch_sub)) {
                showFolderPicker(act, s.cwd, true) { picked -> rerunWith(picked, s, pv, owner) }
            }
        }

        // tab 栏保持可点（ed4d9b3 起的统一机制）；不按 tab 扎根 —— 终端层自己
        // 已经是 tab 作用域，选择框跟着活跃 tab 走即可。
        dlg = android.app.AlertDialog.Builder(act)
            .setTitle(title)
            .setView(body)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dlg.show()
        dlg.keepTabsTappable(rootToTab = false)
    }

    /** 把选中的路径接到原命令后面重跑一次。 */
    private fun rerunWith(
        picked: File, s: TerminalViewModel.Session, pv: TextView?, owner: Int
    ) {
        val tokens = pendingTokens
        pendingTokens = emptyList()
        if (tokens.isEmpty()) return
        val next = tokens + picked.absolutePath
        val echo = next.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
        // 结果仍进发起会话：append 直写快照，视图仅在终端还显示它时刷新
        s.append(PROMPT + echo)
        refreshIfCurrent(s)
        thread { runTokens(echo, next, ExecCtx(s, pv, owner)) }
    }

    // ─── 内建命令 ────────────────────────────────────────────────────────

    /**
     * `ls` 支持通配符（`ls *.zip` / `ls sub/bg??.png`）与多参数；目录照旧列内容。
     * **每个文件都注册一个 fN 描述符**（全局表，进程存活期内稳定可引用），编号
     * 顺序 = 应用「排序方式」设置排出来的顺序（目录不编号），f 从 0 开始。
     */
    private fun lsOutcome(args: List<String>, cwd: File): Outcome {
        // `ls` 不给参数 = 列当前目录（shell 语义；脚本里 `ls > r1.txt` 依赖它）。
        // 空参数**不弹**选择器：要选目录请显式写路径或用 uu l（后者保留选择器）。
        val sections = (if (args.isEmpty()) listOf(".") else args).map { arg ->
            val resolved = UuText.resolve(cwd, arg)
            val parent = resolved.parentFile ?: cwd
            when {
                CliGlob.hasWildcards(resolved.name) -> {
                    val files = parent.listFiles { f -> CliGlob.matches(f.name, resolved.name) }
                        ?.toList()?.let { sortLikeBrowser(it) } ?: emptyList()
                    arg to if (files.isEmpty()) str(R.string.terminal_not_found, arg)
                           else renderWithFds(files)
                }
                resolved.isDirectory -> arg to renderWithFds(sortLikeBrowser(
                    resolved.listFiles()?.toList().orEmpty()))
                resolved.isFile -> arg to renderWithFds(listOf(resolved))
                else -> arg to str(R.string.terminal_not_found, arg)
            }
        }
        return Outcome.Text(
            if (sections.size == 1) sections[0].second
            else sections.joinToString("\n\n") { (title, body) -> "$title:\n$body" }
        )
    }

    /** 与文件浏览器同一套排序（sort_mode 设置），目录优先只对 name 系生效。 */
    private fun sortLikeBrowser(files: List<File>): List<File> {
        val mode = act.prefs.getString("sort_mode", "name_asc") ?: "name_asc"
        return files.sortedWith(when (mode) {
            "name_desc" -> compareBy<File> { !it.isDirectory }.thenByDescending { it.name.lowercase() }
            "size_asc" -> compareBy { it.length() }
            "size_desc" -> compareByDescending { it.length() }
            "date_asc" -> compareBy { it.lastModified() }
            "date_desc" -> compareByDescending { it.lastModified() }
            else -> compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
        })
    }

    /** 渲染 + 给全部**文件**注册 fN（目录不编号），输出行首带 `fN` 标签。 */
    private fun renderWithFds(files: List<File>): String {
        if (files.isEmpty()) return str(R.string.terminal_empty)
        val realFiles = files.filter { it.isFile }
        val fds = fds.registerFiles(realFiles)
        val fdOf = if (realFiles.isNotEmpty()) realFiles.zip(fds).toMap() else emptyMap()
        return files.joinToString("\n") { f ->
            val fdTag = fdOf[f]?.let { "f$it" }?.padStart(5) ?: "     "
            val size = if (f.isDirectory) "/" else fmt(fileSize(f))
            "  $fdTag  ${f.name}  $size"
        }
    }

    /** §7.4：原来的实现只认 `..` / 绝对 / 单段；这里补多段归一化、`~`、无参数回 cwd。 */
    private fun cdOutcome(args: List<String>, s: TerminalViewModel.Session, owner: Int): Outcome {
        if (args.isEmpty()) return Outcome.NeedPath("cd", UuCommands.Picker.FOLDER)
        val (text, code) = doCd(args, s, owner)
        return Outcome.Text(text, code)
    }

    private fun doCd(args: List<String>, s: TerminalViewModel.Session, owner: Int): Pair<String, Int> {
        val target = args.firstOrNull() ?: return s.cwd.absolutePath to 0
        val cwd = s.cwd
        val dest = when {
            target == "~" -> android.os.Environment.getExternalStorageDirectory()
            target == "-" -> s.lastDir.takeIf { it != null } ?: cwd
            target.startsWith("/") -> File(target)
            else -> File(cwd, target)
        }
        // 归一化（消掉 a/b/../c 里的 ..）
        var cur = dest.absoluteFile
        val parts = ArrayDeque<String>()
        for (seg in cur.path.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        val norm = File("/" + parts.joinToString("/"))
        if (!norm.isDirectory) return str(R.string.terminal_not_found, target) to 1
        s.lastDir = cwd
        s.cwd = norm
        // 联动 tab（§2 硬约束）：导航**终端扎根的 tab**。owner 已被关闭时不退化
        // 到 activeTab（那是别的窗口，不该被动导航）——报错即可。
        // owner < 0 = 外部入口（--es uut）：只改会话 cwd，不导航任何窗口
        if (owner >= 0) {
            val ownerTab = act.tabs.firstOrNull { it.tabId == owner }
                ?: return str(R.string.terminal_not_found, "$target (${owner})") to 1
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                act.navTab(ownerTab, norm)
            }
        }
        return "→ ${norm.absolutePath}" to 0
    }

    /**
     * 外部命令落系统 shell。
     * §7.3：**不**把 cwd 插值进 shell 字符串（旧写法 `cd "<path>" && cmd` 在目录名
     * 含 `"` / `$` / 反引号时会拆坏命令，且是注入面）。用 `directory()` 设工作目录。
     * 管道排空是必须的：waitFor 先阻塞的话子进程写满 ~64KB 管道就永远等不到退出。
     */
    private fun runShell(cmdLine: String, cwd: File): Pair<String, Int> = runCatching {
        val p = ProcessBuilder("/system/bin/sh", "-c", cmdLine)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()
        val buf = ByteArrayOutputStream()
        val pump = thread { runCatching { p.inputStream.copyTo(buf) } }
        if (p.waitFor(SHELL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            pump.join(2000)
            buf.toString().trimEnd('\n') to runCatching { p.exitValue() }.getOrDefault(0)
        } else {
            p.destroyForcibly()
            str(R.string.terminal_exec_timeout) to 124
        }
    }.getOrDefault(str(R.string.terminal_exec_failed) to 127)

    private companion object {
        const val SHELL_TIMEOUT_SEC = 30L
        /** `uu run`：脚本文件上限 / 递归深度上限。 */
        const val SCRIPT_MAX_BYTES = 1L * 1024 * 1024
        const val SCRIPT_MAX_DEPTH = 3
        /** 输入行与回显统一用的 POSIX 风格提示符。 */
        const val PROMPT = "~ \$ "
    }
}

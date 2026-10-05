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
    private var input: EditText? = null
    private var cwdView: TextView? = null
    private var pinnedToBottom = true
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
        (r?.parent as? ViewGroup)?.removeView(r)
    }

    val isOpen: Boolean get() = root != null

    /**
     * 按 tab 扎根（ed4d9b3 弹窗扎根同一个模型）：切走 GONE（视图留在树上，
     * 不吃触摸不绘制，picker tab 露得出来），切回自动恢复。归属 tab 被关掉
     * 时彻底移除 —— 它永远回不来了。
     */
    fun onTabChanged(newActiveTabId: Int) {
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
        // 上滑浏览时不要强拉回底部
        sv?.setOnScrollChangeListener { _, _, scrollYNew, _, _ ->
            val h = sv?.getChildAt(0)?.height ?: 0
            val range = h - (sv?.height ?: 0)
            pinnedToBottom = range <= 0 || scrollYNew >= range - 8
        }
    }

    // ─── 执行 ────────────────────────────────────────────────────────────

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
                postOut(msg)
                return
            }
            is CliTokenizer.Out.Ok -> t.tokens
        }
        val cwd = sess.cwd       // 主线程读：dispatch 里的 cd 会写它
        append(PROMPT + line)
        thread { runTokens(line, tokens, cwd) }
    }

    /** 一行命令的产出：要么是文本，要么是「缺路径，请用户选一个」。 */
    private sealed interface Outcome {
        data class Text(val s: String) : Outcome
        data class NeedPath(val cmd: String, val kind: UuCommands.Picker) : Outcome
    }

    /**
     * 后台执行。**只算字符串 / 只改 ViewModel，绝不碰 View** ——
     * 在这里 setText 会抛 CalledFromWrongThreadException 并杀掉整个进程。
     */
    private fun runTokens(line: String, tokens: List<String>, cwd: File) {
        val outcome = when (val argv = tokens.firstOrNull()) {
            null -> Outcome.Text("")
            "ls" -> lsOutcome(tokens.drop(1), cwd)
            "pwd" -> Outcome.Text(cwd.absolutePath)
            "cd" -> cdOutcome(tokens.drop(1), cwd)
            "help" -> Outcome.Text(UuCommands.renderHelp(ctx()))
            "uu" -> runUu(tokens.drop(1), cwd)
            else -> Outcome.Text(runShell(line, cwd))   // §7.3：绝不把 cwd 插值进 shell 字符串
        }
        when (outcome) {
            is Outcome.Text -> if (outcome.s.isNotEmpty()) postOut(outcome.s)
            is Outcome.NeedPath -> act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                askPath(line, outcome.cmd, outcome.kind)
            }
        }
    }

    private fun postOut(text: String) = act.runOnUiThread {
        if (act.isFinishing || act.isDestroyed) return@runOnUiThread
        append(text)
    }

    private fun ctxStr(): StrFn = { id, args ->
        if (args.isEmpty()) act.getString(id) else act.getString(id, *args)
    }

    private fun ctx() = UuCommands.Ctx(
        prefs = act.prefs,
        cwd = sess.cwd,
        activity = act,
        str = ctxStr(),
        fds = fds,
        cacheDir = act.cacheDir,
        // 长命令（x/c）的进度：整行替换输出区最后一条进度行，**不弹对话框**
        //（产品要求：进度只写在 CLI 窗口内）。
        progress = { line ->
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                sess.progress(line)
                render()
            }
        },
    )

    private fun runUu(rest: List<String>, cwd: File): Outcome {
        if (rest.isEmpty()) return Outcome.Text(UuCommands.renderHelp(ctx()))
        val r = UuCommands.dispatch(rest, ctx())
        val p = r.picker
        return if (p != null) Outcome.NeedPath(rest[0], p) else Outcome.Text(r.text)
    }

    // ─── 缺路径 → 弹选择器 ────────────────────────────────────────────────

    /** 选中之后要重跑的那一行（`uu l` → `uu l /选中的路径`）。只在主线程读写。 */
    private var pendingLine: String = ""
    private var pendingTokens: List<String> = emptyList()

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
    private fun askPath(line: String, cmd: String, kind: UuCommands.Picker) {
        pendingLine = line
        pendingTokens = tokenizer.tokenize(line).let { (it as? CliTokenizer.Out.Ok)?.tokens ?: emptyList() }
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
                str(R.string.cli_pick_keep), str(R.string.cli_pick_keep_sub, sess.cwd.absolutePath)
            ) { rerunWith(sess.cwd) }
        // 跟随设置里的「路径选择方式」：终端按 tab 扎根后，新窗口模式开的
        // picker tab 会让终端 GONE 让位（选完切回自动恢复），不再需要强制对话框。
            choice(str(R.string.cli_pick_switch), str(R.string.cli_pick_switch_sub)) {
                showFolderPicker(act, sess.cwd, false) { picked -> rerunWith(picked) }
            }
        } else {
            choice(str(R.string.action_choose_dir), str(R.string.cli_pick_switch_sub)) {
                showFolderPicker(act, sess.cwd, true) { picked -> rerunWith(picked) }
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
    private fun rerunWith(picked: File) {
        val tokens = pendingTokens
        pendingTokens = emptyList()
        if (tokens.isEmpty()) return
        val next = tokens + picked.absolutePath
        val echo = next.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
        val cwd = sess.cwd
        append(PROMPT + echo)
        thread { runTokens(echo, next, cwd) }
    }

    // ─── 内建命令 ────────────────────────────────────────────────────────

    /**
     * `ls` 支持通配符（`ls *.zip` / `ls sub/bg??.png`）与多参数；目录照旧列内容。
     * **每个文件都注册一个 fN 描述符**（全局表，进程存活期内稳定可引用），编号
     * 顺序 = 应用「排序方式」设置排出来的顺序（目录不编号），f 从 0 开始。
     */
    private fun lsOutcome(args: List<String>, cwd: File): Outcome {
        if (args.isEmpty()) return Outcome.NeedPath("ls", UuCommands.Picker.FOLDER)
        val sections = args.map { arg ->
            val resolved = UuText.resolve(cwd, arg)
            val parent = resolved.parentFile ?: cwd
            when {
                CliGlob.hasWildcards(resolved.name) -> {
                    val re = CliGlob.toRegex(resolved.name)
                    val files = parent.listFiles { f -> re.matches(f.name) }
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
    private fun cdOutcome(args: List<String>, cwd: File): Outcome {
        if (args.isEmpty()) return Outcome.NeedPath("cd", UuCommands.Picker.FOLDER)
        return Outcome.Text(doCd(args, cwd))
    }

    private fun doCd(args: List<String>, cwd: File): String {
        val target = args.firstOrNull() ?: return cwd.absolutePath
        val dest = when {
            target == "~" -> android.os.Environment.getExternalStorageDirectory()
            target == "-" -> sess.lastDir.takeIf { it != null } ?: cwd
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
        if (!norm.isDirectory) return str(R.string.terminal_not_found, target)
        sess.lastDir = cwd
        sess.cwd = norm
        // 联动 tab（§2 硬约束）：导航**终端扎根的 tab**。终端隐藏期间不可输入，
        // 所以可见时 owner tab 就是眼前这个窗口 —— 目录切换就发生在用户眼前。
        // 不在后台线程读 activeTab：它的值随时可能因为切窗而变。
        val ownerTab = act.tabs.firstOrNull { it.tabId == ownerTabId } ?: act.activeTab
        act.runOnUiThread {
            if (act.isFinishing || act.isDestroyed) return@runOnUiThread
            act.navTab(ownerTab, norm)
        }
        return "→ ${norm.absolutePath}"
    }

    /**
     * 外部命令落系统 shell。
     * §7.3：**不**把 cwd 插值进 shell 字符串（旧写法 `cd "<path>" && cmd` 在目录名
     * 含 `"` / `$` / 反引号时会拆坏命令，且是注入面）。用 `directory()` 设工作目录。
     * 管道排空是必须的：waitFor 先阻塞的话子进程写满 ~64KB 管道就永远等不到退出。
     */
    private fun runShell(cmdLine: String, cwd: File): String = runCatching {
        val p = ProcessBuilder("/system/bin/sh", "-c", cmdLine)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()
        val buf = ByteArrayOutputStream()
        val pump = thread { runCatching { p.inputStream.copyTo(buf) } }
        if (p.waitFor(SHELL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            pump.join(2000)
            buf.toString().trimEnd('\n')
        } else {
            p.destroyForcibly()
            str(R.string.terminal_exec_timeout)
        }
    }.getOrDefault(str(R.string.terminal_exec_failed))

    private companion object {
        const val SHELL_TIMEOUT_SEC = 30L
        /** 输入行与回显统一用的 POSIX 风格提示符。 */
        const val PROMPT = "~ \$ "
    }
}

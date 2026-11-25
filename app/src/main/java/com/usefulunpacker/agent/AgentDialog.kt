package com.usefulunpacker

import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.os.Environment
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

private const val PREF_AGENT_BASE_URL = "agent_base_url"
private const val PREF_AGENT_MODEL = "agent_model"
private const val MAX_PROMPT_CHARS = 4096

private data class AgentDraft(val text: String, val unsavedSession: AgentSession?)

internal fun MainActivity.showAgent() {
    val storage = Environment.getExternalStorageDirectory()
    val initialDir = currentDir
    val ownerTabId = activeTab.tabId
    val store = AgentSessionStore(File(noBackupFilesDir, "agent-sessions"))
    val credentials = AgentCredentials(File(noBackupFilesDir, "agent-credentials"))
    fun newSession() = AgentSession(
        baseUrl = prefs.getString(PREF_AGENT_BASE_URL, "").orEmpty(),
        model = prefs.getString(PREF_AGENT_MODEL, "").orEmpty(),
    )
    var session = newSession()
    val drafts = mutableMapOf<String, AgentDraft>()
    var activeRequest: AgentRequest? = null
    var activeApproval: AlertDialog? = null
    val conversation = agentForm()
    val scroll = UuScrollView(this).apply {
        background = roundedBg(UuMetrics.px(context, R.dimen.corner_card), context.getColor(R.color.bg_surface_dark))
        addView(conversation)
    }
    val sessionRow = UuActionRow(this)
    val sessionNameRow = UuActionRow(this)
    val workspaceRow = UuActionRow(this)
    val modelRow = UuActionRow(this)
    val permissionRow = UuActionRow(this)
    val contextRow = UuActionRow(this)
    val labels = listOf(R.string.agent_perm_read, R.string.agent_perm_archive,
        R.string.agent_perm_edit, R.string.agent_perm_full).map(::getString)
    val status = TextView(this).apply {
        UuMetrics.text(this)
        setTextIsSelectable(true)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    val prompt = agentField(R.string.agent_prompt, MAX_PROMPT_CHARS).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 1
        maxLines = 3
    }
    val send = UuButton(this).apply {
        actionStyle = ActionStyle.PRIMARY
        setText(R.string.agent_send)
    }
    val stop = UuButton(this).apply {
        actionStyle = ActionStyle.TEXT
        setText(R.string.action_cancel)
        visibility = View.GONE
    }
    val rows = listOf(sessionRow, sessionNameRow, workspaceRow, modelRow, permissionRow, contextRow)
    sessionRow.maxLines = 2
    sessionRow.ellipsize = android.text.TextUtils.TruncateAt.END
    val details = agentSection(sessionNameRow, workspaceRow, modelRow, permissionRow, contextRow)
        .apply { visibility = View.GONE }
    val settings = UuButton(this).apply {
        actionStyle = ActionStyle.TEXT
        setText(R.string.agent_conversation_settings)
    }
    conversation.addView(details)
    conversation.addSpaced(TextView(this).apply {
        UuMetrics.text(this)
        setTextColor(context.getColor(R.color.text_secondary))
        setText(R.string.agent_data_notice)
    }, R.dimen.space_sm)
    val messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    conversation.addView(messages)
    val panel = agentForm().apply {
        addView(UuActionBar(context).apply {
            addView(sessionRow, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(settings, LinearLayout.LayoutParams(WRAP, WRAP))
        })
        addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            topMargin = UuMetrics.px(context, R.dimen.space_xs)
        })
        addSpaced(status, R.dimen.space_sm)
        addSpaced(UuActionBar(context).apply {
            addView(prompt, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(send, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            addView(stop, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        }, R.dimen.space_sm)
    }
    val dialog = UuDialogBuilder(this).setTitle(R.string.agent_title)
        .setView(panel).setNegativeButton(R.string.action_close, null).create()

    fun isOpen() = dialog.window?.decorView?.isAttachedToWindow == true

    fun showSettings(expanded: Boolean) {
        details.visibility = if (expanded) View.VISIBLE else View.GONE
        settings.selectChoice(expanded)
        if (expanded) scroll.scrollTo(0, 0)
    }

    settings.setOnClickListener { showSettings(details.visibility != View.VISIBLE) }

    var followPending = false
    var followReply: View? = null
    fun followLatest(reply: View? = null) {
        if (reply != null) followReply = reply
        if (followPending) return
        followPending = true
        conversation.doOnLayout {
            val answer = followReply
            followReply = null
            followPending = false
            val bottom = (conversation.height - scroll.height).coerceAtLeast(0)
            val top = if (answer == null) bottom else {
                if (!answer.isAttachedToWindow) return@doOnLayout
                val bounds = Rect().also(answer::getDrawingRect)
                conversation.offsetDescendantRectToMyCoords(answer, bounds)
                bounds.top.coerceIn(0, bottom)
            }
            scroll.scrollTo(0, top)
        }
    }

    fun addTurn(question: String): AgentTurnCard =
        AgentTurnCard(this, question).also { messages.addSpaced(it.view, R.dimen.space_sm) }

    fun trimVisibleTurns() {
        if (messages.childCount > 24) messages.removeViewAt(0)
    }

    fun showStatus(text: CharSequence) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    fun recordStorageFailure(feature: String): AppFailure {
        val scope = AppDiagnostics.enterIntent(feature, newChain = true)
        try {
            return AppDiagnostics.begin("session_storage").fail(FailureCode.IO,
                "Agent session could not be saved, loaded or deleted", notifyUser = false)
        } finally {
            AppDiagnostics.leaveIntent(scope)
        }
    }

    fun storageFailure() = showStatus(getString(R.string.agent_storage_failed, recordStorageFailure(session.feature).logId))

    fun sessionTitle(item: AgentSession): String =
        item.title.ifBlank { getString(R.string.agent_new_session) }

    fun refreshRows() {
        sessionRow.valueText = sessionTitle(session)
        sessionNameRow.valueText = sessionTitle(session)
        workspaceRow.valueText = session.workspace
        modelRow.valueText = listOf(session.model, session.baseUrl).filter(String::isNotEmpty).joinToString("\n")
        permissionRow.valueText = labels[session.permission.ordinal]
        contextRow.valueText = getString(if (session.context.isEmpty()) R.string.agent_context_empty else R.string.agent_context_set)
    }

    fun applySession(next: AgentSession) {
        session = next
        refreshRows()
    }

    fun loadSession(next: AgentSession, clearDraft: Boolean = true) {
        applySession(next)
        followReply = null
        messages.removeAllViews()
        for (turn in session.turns.takeLast(8)) {
            addTurn(turn.question).setAnswer(turn.answer)
        }
        followLatest()
        status.visibility = View.GONE
        if (clearDraft) prompt.text?.clear()
    }

    fun switchSession(next: AgentSession) {
        if (next.id == session.id) {
            if (next.revision > session.revision) loadSession(next, clearDraft = false)
            return
        }
        val draft = prompt.text.toString()
        if (draft.isNotBlank()) {
            drafts[session.id] = AgentDraft(draft, session.takeIf { it.revision == 0L })
        }
        loadSession(next)
        drafts.remove(next.id)?.let {
            prompt.setText(it.text)
            prompt.setSelection(prompt.length())
        }
    }

    fun setBusy(busy: Boolean, cancellable: Boolean = busy) {
        rows.forEach { it.isEnabled = !busy }
        settings.isEnabled = !busy
        prompt.isEnabled = !busy
        send.visibility = if (busy) View.GONE else View.VISIBLE
        stop.visibility = if (cancellable) View.VISIBLE else View.GONE
        stop.isEnabled = cancellable
    }

    fun saveSettings(
        next: AgentSession,
        saved: (AgentSession) -> Unit = {},
        failed: (AppFailure) -> Unit = {},
    ) {
        setBusy(true, cancellable = false)
        featureThread {
            var failure: AppFailure? = null
            val updated = try { store.save(next) } catch (_: Exception) {
                failure = recordStorageFailure(next.feature)
                null
            }
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed || !isOpen()) return@runFeatureOnUiThread
                setBusy(false)
                if (updated != null) {
                    applySession(updated)
                    status.visibility = View.GONE
                    saved(updated)
                } else failure?.let {
                    showStatus(getString(R.string.agent_storage_failed, it.logId))
                    failed(it)
                }
            }
        }
    }

    fun renameSession() {
        val current = session
        val nameInput = agentField(R.string.agent_session_name, AGENT_SESSION_NAME_LIMIT, current.customName.orEmpty())
        val body = agentForm().apply { addView(nameInput) }
        val rename = UuDialogBuilder(this).setTitle(R.string.agent_session_rename)
            .setMessage(R.string.agent_session_name_hint)
            .setView(UuScrollView(this).apply { addView(body) })
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .show()
        rename.keepTabsTappable()
        val saveButton = rename.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancelButton = rename.getButton(AlertDialog.BUTTON_NEGATIVE)
        saveButton.setOnClickListener {
            val renamed = current.withName(nameInput.text.toString())
            if (renamed.customName == current.customName) {
                rename.dismiss()
                return@setOnClickListener
            }
            nameInput.isEnabled = false
            saveButton.isEnabled = false
            cancelButton.isEnabled = false
            rename.setCancelable(false)
            saveSettings(renamed, saved = { rename.dismiss() }, failed = {
                if (rename.window?.decorView?.isAttachedToWindow == true) {
                    nameInput.isEnabled = true
                    nameInput.error = getString(R.string.agent_storage_failed, it.logId)
                    saveButton.isEnabled = true
                    cancelButton.isEnabled = true
                    rename.setCancelable(true)
                }
            })
        }
    }

    fun deleteSession() {
        val target = session
        UuDialogBuilder(this).setTitle(R.string.agent_delete_session)
            .setMessage(R.string.agent_delete_session_detail)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                setBusy(true, cancellable = false)
                featureThread {
                    val deleted = try {
                        store.delete(target) { AppDiagnostics.store?.deleteAgentSession(it) }
                        true
                    } catch (_: Exception) { false }
                    runFeatureOnUiThread {
                        if (isFinishing || isDestroyed || !isOpen()) return@runFeatureOnUiThread
                        setBusy(false)
                        if (deleted) {
                            drafts.remove(target.id)
                            loadSession(newSession())
                        } else storageFailure()
                    }
                }
            }.show().keepTabsTappable()
    }

    fun openSession(choice: AgentSessionSummary) {
        if (choice.id == session.id && choice.revision == session.revision) return
        drafts[choice.id]?.unsavedSession?.let { switchSession(it); return }
        setBusy(true, cancellable = false)
        showStatus(getString(R.string.agent_sessions_loading))
        featureThread {
            val opened = try { store.open(choice.id) } catch (_: Exception) { null }
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed || !isOpen()) return@runFeatureOnUiThread
                setBusy(false)
                if (opened == null) {
                    val failure = recordStorageFailure("agent_session_${choice.id}")
                    showStatus(getString(R.string.agent_storage_failed, failure.logId))
                } else {
                    status.visibility = View.GONE
                    switchSession(opened)
                }
            }
        }
    }

    sessionRow.bind(getString(R.string.agent_sessions), navigation = true, onClick = {
        setBusy(true, cancellable = false)
        showStatus(getString(R.string.agent_sessions_loading))
        featureThread {
            val catalog = try { store.catalog() } catch (_: Exception) { null }
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed || !isOpen()) return@runFeatureOnUiThread
                setBusy(false)
                if (catalog == null) {
                    storageFailure()
                    return@runFeatureOnUiThread
                }
                status.visibility = View.GONE
                val warning = catalog.unreadableCount.takeIf { it > 0 }?.let { count ->
                    getString(R.string.agent_sessions_partial, count, recordStorageFailure(session.feature).logId)
                }
                val available = (catalog.sessions + drafts.values.mapNotNull { it.unsavedSession?.summary } +
                    session.summary).distinctBy { it.id }.associateBy { it.id }
                val choices = available.values.map {
                    AgentSessionChoice(it.id, it.title.ifBlank { getString(R.string.agent_new_session) },
                        it.id == session.id, it.id in drafts)
                }
                showAgentSessionPicker(
                    ownerTabId, choices, warning,
                    onSelect = { id -> available[id]?.let(::openSession) },
                    onNew = { switchSession(newSession()) },
                    onRename = ::renameSession,
                    onDelete = if (session.revision > 0) ::deleteSession else null,
                )
            }
        }
    })
    sessionNameRow.bind(getString(R.string.agent_session_name), navigation = true, onClick = ::renameSession)
    workspaceRow.bind(getString(R.string.agent_workspace), navigation = true, onClick = {
        showFolderPickerDialog(this, session.workspace?.let(::File) ?: initialDir) { chosen ->
            if (session.workspace != chosen.path) {
                val next = if (session.turns.isEmpty()) session.copy(workspace = chosen.path)
                    else AgentSession(workspace = chosen.path, baseUrl = session.baseUrl, model = session.model, context = session.context)
                saveSettings(next, saved = { loadSession(it, clearDraft = false) })
            }
        }
    })
    modelRow.bind(getString(R.string.agent_model_settings), navigation = true, onClick = {
        showAgentModelSettings(session.baseUrl, session.model, credentials) { url, modelName, completed ->
            val changed = session.baseUrl != url || session.model != modelName
            if (!changed) {
                completed(null)
            } else {
                val clearDraft = session.changesModelEndpoint(url)
                saveSettings(session.withModelSettings(url, modelName), saved = {
                    loadSession(it, clearDraft)
                    prefs.edit().putString(PREF_AGENT_BASE_URL, url).putString(PREF_AGENT_MODEL, modelName).apply()
                    completed(null)
                }, failed = completed)
            }
        }
    })
    permissionRow.bind(getString(R.string.agent_permission), navigation = true, onClick = {
        UuDialogBuilder(this).setTitle(R.string.agent_permission)
            .setSingleChoiceItems(labels.toTypedArray(), session.permission.ordinal) { picker, index ->
                picker.dismiss()
                val permission = AgentPermission.entries[index]
                if (permission != session.permission) saveSettings(session.copy(permission = permission))
            }
            .setNegativeButton(R.string.action_close, null)
            .show().keepTabsTappable()
    })
    contextRow.bind(getString(R.string.agent_context), navigation = true, onClick = {
        val context = agentField(R.string.agent_context_hint, AGENT_CONTEXT_LIMIT, session.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
        }
        val body = agentForm().apply { addView(context) }
        UuDialogBuilder(this).setTitle(R.string.agent_context)
            .setMessage(R.string.agent_context_notice)
            .setView(UuScrollView(this).apply { addView(body) })
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val value = context.text.toString()
                if (value != session.context) saveSettings(session.copy(context = value))
            }
            .show().keepTabsTappable()
    })

    fun approve(approval: AgentApproval, request: AgentRequest): Boolean {
        val choice = CompletableFuture<Boolean>()
        runFeatureOnUiThread(onUnavailable = { choice.complete(false) }) {
            if (isFinishing || isDestroyed) { choice.complete(false); return@runFeatureOnUiThread }
            if (!isOpen() || activeRequest !== request) { choice.complete(false); return@runFeatureOnUiThread }
            val details = TextView(this).apply {
                UuMetrics.text(this)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setTextIsSelectable(true)
                text = getString(R.string.agent_approval_detail, visibleAgentText(session.workspace.orEmpty()),
                    approval.name, visibleAgentText(approval.input.toString(2), keepNewlines = true))
            }
            val approvalDialog = UuDialogBuilder(this).setTitle(R.string.agent_approval)
                .setView(UuScrollView(this).apply { addView(details) })
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.agent_approve) { _, _ -> choice.complete(true) }
                .create()
            approvalDialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            rootDialogToTab(approvalDialog, ownerTabId)
            approvalDialog.show()
            activeApproval = approvalDialog
            approvalDialog.keepTabsTappable {
                if (activeApproval === approvalDialog) activeApproval = null
                choice.complete(false)
            }
        }
        return choice.get()
    }

    stop.setOnClickListener {
        activeRequest?.cancel()
        activeApproval?.dismiss()
        stop.isEnabled = false
    }
    send.setOnClickListener {
        if (activeRequest != null) return@setOnClickListener
        val selected = session.workspace?.let(::File)
        val endpoint = agentEndpoint(session.baseUrl)
        val question = prompt.text.toString().trim()
        val missing = when {
            selected == null -> R.string.agent_choose_workspace
            endpoint == null || session.model.isBlank() -> R.string.agent_model_missing
            question.isBlank() -> R.string.agent_prompt
            else -> null
        }
        if (missing != null || selected == null || endpoint == null) {
            if (selected == null || endpoint == null || session.model.isBlank()) showSettings(true)
            showStatus(getString(missing ?: R.string.agent_invalid_settings))
            return@setOnClickListener
        }
        val originalSession = session
        val request = AgentRequest()
        val scope = AppDiagnostics.enterIntent(originalSession.feature, newChain = true)
        val trace = scope.trace
        trace.chain?.let { dialogChainTabs[it] = ownerTabId }
        activeRequest = request
        setBusy(true)
        if (details.visibility == View.VISIBLE) showSettings(false)
        showStatus(getString(R.string.agent_running))
        val turn = addTurn(question)
        followLatest()
        try {
            featureThread {
                request.setWorker(Thread.currentThread())
                var persisted: AgentSession? = null
                var updated: AgentSession? = null
                var answer: String? = null
                var error: AppFailure? = null
                var cancelled = false
                try {
                    request.checkActive()
                    val running = try {
                        store.beginTurn(originalSession, question)
                    } catch (_: Exception) {
                        throw AgentClientException("session_storage", FailureCode.IO, "Agent question could not be saved")
                    }
                    persisted = running
                    runFeatureOnUiThread {
                        if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                        if (!isOpen() || activeRequest !== request) return@runFeatureOnUiThread
                        prompt.text?.clear()
                        trimVisibleTurns()
                    }
                    request.checkActive()
                    val previous = originalSession.historyWindow().flatMap {
                        listOf(AgentClient.message("user", it.question), AgentClient.message("assistant", it.answer))
                    }
                    val workspace = requireAgentWorkspace(storage, selected)
                    val key = try { credentials.read(endpoint).orEmpty() } catch (_: Exception) {
                        throw AgentClientException("credential_read", FailureCode.IO, "Agent API key could not be read")
                    }
                    val worker = McpArchiveWorker(workspace, prefs, strFnOf(this))
                    val credentialScope = AppDiagnostics.enterIntent(running.feature, listOf(key))
                    try {
                        McpTasks(agentFeature = running.feature, execute = worker::execute).use { tasks ->
                            val runtime = WorkspaceSkillRuntime(workspace, tasks, prefs)
                            val response = AgentClient(endpoint, running.model, key, request).complete(
                                previous, question, runtime, running.permission, running.context,
                                confirm = { approve(it, request) },
                                deleteFile = { deleteAgentFile(workspace, it) },
                                onReasoning = { reasoning ->
                                    runFeatureOnUiThread {
                                        if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                                        if (!isOpen() || activeRequest !== request) return@runFeatureOnUiThread
                                        val follow = !scroll.canScrollVertically(1)
                                        turn.appendReasoning(reasoning)
                                        if (follow) followLatest()
                                    }
                                },
                            ) { event ->
                                runFeatureOnUiThread {
                                    if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                                    if (!isOpen() || activeRequest !== request) return@runFeatureOnUiThread
                                    val follow = !scroll.canScrollVertically(1)
                                    turn.updateTool(event)
                                    if (follow) followLatest()
                                }
                            }
                            answer = (if (key.isEmpty()) response else response.replace(key, "[redacted]"))
                                .take(MAX_PROMPT_CHARS)
                        }
                    } finally {
                        AppDiagnostics.leaveIntent(credentialScope)
                    }
                    request.checkActive()
                    val result = checkNotNull(answer)
                    updated = try {
                        store.save(running.copy(turns = running.turns.dropLast(1) + AgentExchange(question, result)), inFlight = true)
                    } catch (_: Exception) {
                        throw AgentClientException("session_storage", FailureCode.IO, "Agent answer could not be saved")
                    }
                    trace.success()
                } catch (_: CancellationException) {
                    cancelled = true
                    trace.cancelled()
                } catch (_: InterruptedException) {
                    cancelled = true
                    trace.cancelled()
                } catch (e: AgentClientException) {
                    error = AppDiagnostics.begin(e.stage).fail(e.code, e.message.orEmpty(), notifyUser = false)
                } catch (e: Exception) {
                    error = AppDiagnostics.begin("agent_request").fail(FailureCode.IO,
                        "Agent request failed (${e.javaClass.simpleName})", notifyUser = false)
                } finally {
                    request.setWorker(null)
                    persisted?.let { store.finish(it.id) }
                    runFeatureOnUiThread {
                        if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                        if (!isOpen() || activeRequest !== request) return@runFeatureOnUiThread
                        activeRequest = null
                        activeApproval?.dismiss()
                        setBusy(false)
                        val saved = updated ?: persisted
                        if (saved != null) {
                            applySession(saved)
                        } else {
                            messages.removeView(turn.view)
                        }
                        val follow = !scroll.canScrollVertically(1)
                        val replyView = answer?.let(turn::setAnswer)
                        val failure = error
                        when {
                            cancelled -> showStatus(getString(R.string.msg_cancelled))
                            failure != null -> showStatus(when {
                                failure.stage == "workspace" -> getString(R.string.agent_invalid_workspace)
                                failure.stage == "credential_read" -> getString(R.string.agent_key_read_failed, failure.logId)
                                failure.stage == "session_storage" -> getString(R.string.agent_storage_failed, failure.logId)
                                else -> getString(R.string.agent_failed, "${failure.code.name} · ${failure.logId}")
                            })
                            else -> status.visibility = View.GONE
                        }
                        if (follow && replyView != null) followLatest(replyView)
                    }
                }
            }
        } finally {
            AppDiagnostics.leaveIntent(scope)
        }
    }

    refreshRows()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    dialog.belowTabs(wide = true) {
        activeRequest?.cancel()
        activeApproval?.dismiss()
    }
    dialog.show()
}

private fun requireAgentWorkspace(storage: File, selected: File): McpWorkspace {
    val root = storage.canonicalFile
    val directory = selected.canonicalFile
    if (directory == root || !directory.toPath().startsWith(root.toPath()) ||
        !directory.isDirectory || !directory.canRead()) {
        throw AgentClientException("workspace", FailureCode.INVALID_RESULT,
            "Choose a readable subdirectory of shared storage")
    }
    return McpWorkspace(directory)
}

private fun MainActivity.showAgentModelSettings(
    baseUrl: String,
    model: String,
    credentials: AgentCredentials,
    save: (String, String, (AppFailure?) -> Unit) -> Unit,
) {
    val body = agentForm()
    val urlInput = agentField(R.string.agent_base_url, 1024, baseUrl).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }
    val modelInput = agentField(R.string.agent_model_name, 200, model)
    val keyInput = agentField(R.string.agent_api_key, AGENT_KEY_LIMIT).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod = PasswordTransformationMethod.getInstance()
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
    val reveal = UuSwitch(this).apply {
        setOnCheckedChangeListener { _, visible ->
            keyInput.transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()
            keyInput.setSelection(keyInput.length())
        }
    }
    val clearing = UuButton(this).apply { actionStyle = ActionStyle.TEXT; setText(R.string.agent_key_clear) }
    val status = TextView(this).apply {
        UuMetrics.text(this)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    var keyEdited = false
    var settingKey = false
    var endpoint = agentEndpoint(baseUrl)
    var readingKey = false
    var saving = false
    var connectionTest: AgentRequest? = null

    fun fillKey(value: String) {
        settingKey = true
        try { keyInput.setText(value) } finally { settingKey = false }
    }

    fun showStatus(message: CharSequence) {
        status.text = message
        status.visibility = View.VISIBLE
    }

    fun error(message: Int, logId: String) = showStatus(getString(message, logId))

    keyInput.doAfterTextChanged { if (!settingKey) keyEdited = true }
    clearing.setOnClickListener {
        reveal.isChecked = false
        fillKey("")
        keyEdited = true
        status.setText(R.string.agent_key_clear_pending)
        status.visibility = View.VISIBLE
    }
    body.addView(status)
    body.addSpaced(urlInput, R.dimen.space_sm)
    body.addSpaced(modelInput, R.dimen.space_sm)
    body.addSpaced(keyInput, R.dimen.space_md)
    body.addSpaced(agentSection(
        UuToggleRow(this).apply { bind(getString(R.string.agent_key_reveal), reveal) }, clearing,
    ), R.dimen.space_sm)
    val scroll = UuScrollView(this).apply { addView(body) }
    val dialog = UuDialogBuilder(this).setTitle(R.string.agent_model_settings)
        .setMessage(R.string.agent_key_storage_notice)
        .setView(scroll)
        .setNegativeButton(R.string.action_cancel, null)
        .setPositiveButton(R.string.action_save, null)
        .setNeutralButton(R.string.agent_connection_test, null)
        .create()
    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    dialog.show()
    val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
    val testButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
    val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
    UuMetrics.action(testButton, ActionStyle.SECONDARY)
    dialog.keepTabsTappable {
        connectionTest?.cancel()
        reveal.isChecked = false
        fillKey("")
    }
    keyInput.viewTreeObserver.addOnWindowFocusChangeListener { focused -> if (!focused) reveal.isChecked = false }
    fun checkControls() {
        val editing = !saving && connectionTest == null
        urlInput.isEnabled = editing
        modelInput.isEnabled = editing
        keyInput.isEnabled = editing && !readingKey
        reveal.isEnabled = editing && !readingKey
        clearing.isEnabled = editing && !readingKey
        testButton.isEnabled = editing && !readingKey
        saveButton.isEnabled = editing
        cancelButton.isEnabled = !saving
        dialog.setCancelable(!saving)
    }

    fun loadKey() {
        val requested = endpoint ?: return
        readingKey = true
        checkControls()
        featureThread {
            var failure: AppFailure? = null
            val value = try { credentials.read(requested).orEmpty() } catch (e: Exception) {
                failure = AppDiagnostics.begin("agent_key_read").fail(FailureCode.IO,
                    "Agent API key could not be read (${e.javaClass.simpleName})", notifyUser = false)
                ""
            }
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                if (dialog.window?.decorView?.isAttachedToWindow != true ||
                    endpoint?.toExternalForm() != requested.toExternalForm()) return@runFeatureOnUiThread
                readingKey = false
                checkControls()
                if (!keyEdited) fillKey(value)
                failure?.let { error(R.string.agent_key_read_failed, it.logId) }
            }
        }
    }

    urlInput.doAfterTextChanged {
        val next = agentEndpoint(it.toString())
        if (endpoint?.toExternalForm() != next?.toExternalForm()) {
            endpoint = next
            reveal.isChecked = false
            fillKey("")
            keyEdited = false
            readingKey = false
            status.visibility = View.GONE
            checkControls()
        }
    }
    loadKey()
    testButton.onUserIntent("agent_model_test") {
        val destination = agentEndpoint(urlInput.text.toString())
        val modelName = modelInput.text.toString().trim()
        val typedSecret = keyInput.text.toString().trim().takeIf { keyEdited }
        if (destination == null || modelName.isBlank()) {
            showStatus(getString(R.string.agent_invalid_settings))
            scroll.smoothScrollTo(0, 0)
            return@onUserIntent
        }
        if (typedSecret != null && !validAgentKey(typedSecret)) {
            showStatus(getString(R.string.agent_key_invalid))
            scroll.smoothScrollTo(0, 0)
            return@onUserIntent
        }
        val request = AgentRequest()
        connectionTest = request
        reveal.isChecked = false
        checkControls()
        showStatus(getString(R.string.agent_connection_testing))
        scroll.scrollTo(0, 0)
        featureThread {
            request.setWorker(Thread.currentThread())
            val step = AppDiagnostics.begin("model_probe", listOfNotNull(typedSecret))
            val result = try {
                request.checkActive()
                val secret = typedSecret ?: try { credentials.read(destination).orEmpty() } catch (_: Exception) {
                    throw AgentClientException("credential_read", FailureCode.IO, "Agent API key could not be read")
                }
                val elapsed = AgentClient(destination, modelName, secret, request).testConnection()
                step.success()
                getString(R.string.agent_connection_success, elapsed)
            } catch (_: CancellationException) {
                step.cancelled()
                null
            } catch (e: AgentClientException) {
                val failure = step.fail(e.code, e.message.orEmpty(), notifyUser = false)
                val http = e.httpStatus
                if (http != null) {
                    val detail = when (http) {
                        401, 403 -> R.string.agent_connection_access
                        404, 405 -> R.string.agent_connection_route
                        429 -> R.string.agent_connection_limit
                        else -> R.string.agent_connection_provider
                    }
                    getString(R.string.agent_connection_http, http, getString(detail), failure.logId)
                } else getString(
                    if (e.stage == "credential_read") R.string.agent_key_read_failed else R.string.agent_connection_format,
                    failure.logId,
                )
            } catch (e: java.net.SocketTimeoutException) {
                val failure = step.fail(FailureCode.IO, "Agent connection test timed out", notifyUser = false)
                getString(R.string.agent_connection_timeout, failure.logId)
            } catch (e: Exception) {
                val failure = step.fail(FailureCode.IO, "Agent connection test failed (${e.javaClass.simpleName})", notifyUser = false)
                getString(R.string.agent_connection_network, failure.logId)
            } finally {
                request.setWorker(null)
            }
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                if (dialog.window?.decorView?.isAttachedToWindow != true || connectionTest !== request) return@runFeatureOnUiThread
                connectionTest = null
                checkControls()
                showStatus(result ?: getString(R.string.msg_cancelled))
                scroll.scrollTo(0, 0)
            }
        }
    }
    saveButton.setOnClickListener {
        val url = urlInput.text.toString().trim()
        val modelName = modelInput.text.toString().trim()
        val secret = keyInput.text.toString().trim()
        val destination = agentEndpoint(url)
        if (destination == null || modelName.isBlank()) {
            UuNotice.makeText(this, R.string.agent_invalid_settings, android.widget.Toast.LENGTH_LONG).show()
            return@setOnClickListener
        }
        if (!validAgentKey(secret)) {
            status.setText(R.string.agent_key_invalid)
            status.visibility = View.VISIBLE
            return@setOnClickListener
        }
        reveal.isChecked = false
        saving = true
        checkControls()
        fun saveModel() {
            save(url, modelName) { failure ->
                if (dialog.window?.decorView?.isAttachedToWindow != true) return@save
                saving = false
                checkControls()
                if (failure == null) dialog.dismiss()
                else error(R.string.agent_storage_failed, failure.logId)
            }
        }
        if (!keyEdited) {
            saveModel()
            return@setOnClickListener
        }
        featureThread {
            val failure = try {
                credentials.save(destination, secret)
                null
            } catch (e: Exception) {
                AppDiagnostics.begin("agent_key_save").fail(FailureCode.IO,
                    "Agent API key could not be saved (${e.javaClass.simpleName})", notifyUser = false)
            }
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                if (dialog.window?.decorView?.isAttachedToWindow != true) return@runFeatureOnUiThread
                if (failure == null) saveModel()
                else {
                    saving = false
                    checkControls()
                    error(R.string.agent_key_save_failed, failure.logId)
                }
            }
        }
    }
}

private fun MainActivity.agentForm() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val gutter = resources.getDimensionPixelSize(R.dimen.space_xl)
    setPadding(gutter, gutter / 2, gutter, gutter / 2)
}

private class AgentTurnCard(private val context: Context, question: String) {
    private val rows = linkedMapOf<Int, TextView>()
    private val reasoningText = StringBuilder()
    private var expanded = false
    private val tools = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val reasoningTitle = line(R.dimen.text_sm, R.color.accent).apply {
        setText(R.string.agent_reasoning)
        visibility = View.GONE
    }
    private val reasoning = line(R.dimen.text_sm, R.color.text_secondary).apply {
        setTextIsSelectable(true)
        visibility = View.GONE
    }
    private val disclosure = UuButton(context).apply {
        actionStyle = ActionStyle.TEXT
        setText(R.string.agent_activity_expand)
        visibility = View.GONE
        setOnClickListener {
            expanded = !expanded
            setText(if (expanded) R.string.agent_activity_collapse else R.string.agent_activity_expand)
            refreshDetails()
        }
    }
    private val answer = line(R.dimen.text_lg, R.color.text_primary).apply {
        setTextIsSelectable(true)
        visibility = View.GONE
    }

    val view = context.agentSection().apply {
        val padding = UuMetrics.px(context, R.dimen.space_md)
        setPadding(padding, padding, padding, padding)
        addView(line(R.dimen.text_title, R.color.text_primary).apply {
            text = question
            setTextIsSelectable(true)
        })
        addSpaced(tools, R.dimen.space_xs)
        tools.visibility = View.GONE
        addView(disclosure, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(reasoningTitle)
        addView(reasoning)
        addSpaced(answer, R.dimen.space_sm)
    }

    fun setAnswer(value: String): View = answer.apply {
        text = value
        visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    }

    fun appendReasoning(value: String) {
        val first = reasoningText.isEmpty()
        if (!first) reasoningText.append("\n\n")
        reasoningText.append(value)
        if (expanded) reasoning.text = reasoningText.toString()
        disclosure.visibility = View.VISIBLE
        if (first && expanded) refreshDetails()
    }

    fun updateTool(event: AgentToolEvent) {
        val newRow = event.callIndex !in rows
        val row = rows.getOrPut(event.callIndex) {
            line(R.dimen.text_sm, R.color.text_secondary).also(tools::addView)
        }
        val status = when {
            event.failure != null -> context.getString(R.string.agent_tool_failed, event.failure)
            event.stage == AgentToolStage.APPROVAL -> context.getString(R.string.agent_tool_awaiting, event.name)
            event.stage == AgentToolStage.SUCCESS -> context.getString(R.string.agent_tool_success, event.name)
            event.stage == AgentToolStage.CANCELLED -> context.getString(R.string.agent_tool_cancelled, event.name)
            event.readOnly -> context.getString(R.string.agent_tool_read, event.name)
            else -> context.getString(R.string.agent_tool_write, event.name)
        }
        row.text = listOfNotNull(
            event.name.takeIf { event.failure != null && it.isNotEmpty() },
            status,
            event.resultCount?.let { context.getString(R.string.agent_tool_result_items, it) },
            event.resultLabel,
            event.path.takeIf(String::isNotEmpty),
        ).joinToString(" · ")
        row.setTextColor(context.getColor(if (event.failure == null) R.color.text_secondary else R.color.state_error))
        disclosure.visibility = View.VISIBLE
        tools.visibility = View.VISIBLE
        if (newRow) refreshDetails()
    }

    private fun refreshDetails() {
        val previewStart = rows.size - 3
        rows.values.forEachIndexed { index, row ->
            row.visibility = if (expanded || index >= previewStart) View.VISIBLE else View.GONE
            val lines = if (expanded) Int.MAX_VALUE else 1
            if (row.maxLines != lines) row.maxLines = lines
            val ellipsis = if (expanded) null else TextUtils.TruncateAt.END
            if (row.ellipsize != ellipsis) row.ellipsize = ellipsis
        }
        val reasoningVisibility = if (expanded && reasoningText.isNotEmpty()) View.VISIBLE else View.GONE
        if (reasoningVisibility == View.VISIBLE && reasoning.text.isEmpty()) reasoning.text = reasoningText.toString()
        if (reasoningVisibility == View.GONE && reasoning.text.isNotEmpty()) reasoning.text = ""
        reasoningTitle.visibility = reasoningVisibility
        reasoning.visibility = reasoningVisibility
    }

    private fun line(size: Int, color: Int) = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(size))
        setTextColor(context.getColor(color))
    }
}

private fun Context.agentSection(vararg items: View) = UuActionPanel(this).apply {
    setPadding(0, 0, 0, 0)
    background = roundedBg(UuMetrics.px(context, R.dimen.corner_card), context.getColor(R.color.bg_surface),
        dp(1), context.getColor(R.color.hairline))
    items.forEach { addView(it) }
}

private fun LinearLayout.addSpaced(view: View, gap: Int) {
    addView(view, LinearLayout.LayoutParams(MATCH, WRAP).apply {
        topMargin = UuMetrics.px(context, gap)
    })
}

private fun MainActivity.agentField(hintId: Int, maxChars: Int, initial: String = "") = UuTextField(this).apply {
    isSaveEnabled = false
    setHint(hintId)
    setSingleLine()
    filters = arrayOf(InputFilter.LengthFilter(maxChars))
    setText(initial)
}

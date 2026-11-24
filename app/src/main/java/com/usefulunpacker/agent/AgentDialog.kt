package com.usefulunpacker

import android.app.AlertDialog
import android.os.Environment
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

private const val PREF_AGENT_BASE_URL = "agent_base_url"
private const val PREF_AGENT_MODEL = "agent_model"
private const val MAX_PROMPT_CHARS = 4096

internal fun MainActivity.showAgent() {
    val storage = Environment.getExternalStorageDirectory()
    val initialDir = currentDir
    val ownerTabId = activeTab.tabId
    val store = AgentSessionStore(File(noBackupFilesDir, "agent-sessions"))
    fun newSession() = AgentSession(
        baseUrl = prefs.getString(PREF_AGENT_BASE_URL, "").orEmpty(),
        model = prefs.getString(PREF_AGENT_MODEL, "").orEmpty(),
    )
    var session = newSession()
    var apiKey = ""
    var activeRequest: AgentRequest? = null
    var activeApproval: AlertDialog? = null
    val content = agentForm()
    val scroll = UuScrollView(this).apply { addView(content) }
    val sessionRow = UuActionRow(this)
    val workspaceRow = UuActionRow(this)
    val modelRow = UuActionRow(this)
    val permissionRow = UuActionRow(this)
    val contextRow = UuActionRow(this)
    val labels = listOf(R.string.agent_perm_read, R.string.agent_perm_archive,
        R.string.agent_perm_edit, R.string.agent_perm_full).map(::getString)
    val status = TextView(this).apply {
        UuMetrics.text(this)
        setTextIsSelectable(true)
        visibility = View.GONE
    }
    val transcript = TextView(this).apply {
        UuMetrics.text(this)
        setTextIsSelectable(true)
        visibility = View.GONE
    }
    val prompt = agentField(R.string.agent_prompt, MAX_PROMPT_CHARS).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 2
        maxLines = 5
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
    val rows = listOf(sessionRow, workspaceRow, modelRow, permissionRow, contextRow)
    rows.forEach { content.addView(it) }
    content.addView(TextView(this).apply {
        UuMetrics.text(this)
        setText(R.string.agent_data_notice)
    })
    content.addView(transcript)
    content.addView(status)
    content.addView(prompt)
    content.addView(send)
    content.addView(stop)
    val dialog = UuDialogBuilder(this).setTitle(R.string.agent_title)
        .setView(scroll).setNegativeButton(R.string.action_close, null).create()

    fun showStatus(text: CharSequence) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    fun storageFailure() {
        val scope = AppDiagnostics.enterIntent(session.feature, newChain = true)
        try {
            val failure = AppDiagnostics.begin("session_storage").fail(FailureCode.IO,
                "Agent session could not be saved, loaded or deleted", notifyUser = false)
            showStatus(getString(R.string.agent_storage_failed, failure.logId))
        } finally {
            AppDiagnostics.leaveIntent(scope)
        }
    }

    fun refreshRows() {
        sessionRow.valueText = session.title.ifBlank { getString(R.string.agent_new_session) }
        workspaceRow.valueText = session.workspace
        modelRow.valueText = listOf(session.model, session.baseUrl).filter(String::isNotEmpty).joinToString("\n")
        permissionRow.valueText = labels[session.permission.ordinal]
        contextRow.valueText = getString(if (session.context.isEmpty()) R.string.agent_context_empty else R.string.agent_context_set)
    }

    fun loadSession(next: AgentSession) {
        if (session.baseUrl != next.baseUrl) apiKey = ""
        session = next
        refreshRows()
        transcript.text = session.turns.takeLast(8).joinToString("\n\n") { turn ->
            if (turn.answer.isEmpty()) turn.question else getString(R.string.agent_exchange, turn.question, turn.answer)
        }.takeLast(24_000)
        transcript.visibility = if (transcript.text.isEmpty()) View.GONE else View.VISIBLE
        status.visibility = View.GONE
        prompt.text?.clear()
    }

    fun save(next: AgentSession): Boolean = try {
        session = store.save(next)
        refreshRows()
        true
    } catch (_: Exception) {
        storageFailure()
        false
    }

    fun setBusy(busy: Boolean, cancellable: Boolean = busy) {
        rows.forEach { it.isEnabled = !busy }
        prompt.isEnabled = !busy
        send.visibility = if (busy) View.GONE else View.VISIBLE
        stop.visibility = if (cancellable) View.VISIBLE else View.GONE
        stop.isEnabled = cancellable
    }

    fun appendMessage(message: CharSequence) {
        transcript.text = if (transcript.text.isEmpty()) message else "${transcript.text}\n\n$message".takeLast(24_000)
        transcript.visibility = View.VISIBLE
    }

    sessionRow.bind(getString(R.string.agent_sessions), navigation = true, onClick = {
        val saved = try { store.list() } catch (_: Exception) {
            storageFailure()
            return@bind
        }
        val chooser = UuDialogBuilder(this).setTitle(R.string.agent_sessions)
            .setItems(saved.map { it.title.ifBlank { getString(R.string.agent_new_session) } }.toTypedArray()) { _, index ->
                loadSession(saved[index])
            }
            .setNegativeButton(R.string.action_close, null)
            .setNeutralButton(R.string.agent_new_session) { _, _ -> loadSession(newSession()) }
        if (session.revision > 0) {
            chooser.setPositiveButton(R.string.action_delete) { _, _ ->
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
                                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                                if (!dialog.isShowing) return@runFeatureOnUiThread
                                setBusy(false)
                                if (deleted) loadSession(newSession()) else storageFailure()
                            }
                        }
                    }.show().keepTabsTappable()
            }
        }
        chooser.show().keepTabsTappable()
    })
    workspaceRow.bind(getString(R.string.agent_workspace), navigation = true, onClick = {
        showFolderPickerDialog(this, session.workspace?.let(::File) ?: initialDir) { chosen ->
            if (session.workspace != chosen.path) {
                val next = if (session.turns.isEmpty()) session.copy(workspace = chosen.path)
                    else AgentSession(workspace = chosen.path, baseUrl = session.baseUrl, model = session.model, context = session.context)
                if (save(next)) loadSession(session)
            }
        }
    })
    modelRow.bind(getString(R.string.agent_model_settings), navigation = true, onClick = {
        showAgentModelSettings(session.baseUrl, session.model, apiKey) { url, modelName, key ->
            val changed = session.baseUrl != url || session.model != modelName
            val next = if (changed && session.turns.isNotEmpty()) {
                AgentSession(workspace = session.workspace, context = session.context, baseUrl = url, model = modelName)
            } else session.copy(baseUrl = url, model = modelName)
            if (save(next)) {
                loadSession(session)
                apiKey = key
                prefs.edit().putString(PREF_AGENT_BASE_URL, url).putString(PREF_AGENT_MODEL, modelName).apply()
            }
        }
    })
    permissionRow.bind(getString(R.string.agent_permission), navigation = true, onClick = {
        UuDialogBuilder(this).setTitle(R.string.agent_permission)
            .setSingleChoiceItems(labels.toTypedArray(), session.permission.ordinal) { picker, index ->
                if (save(session.copy(permission = AgentPermission.entries[index]))) picker.dismiss()
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
            .setPositiveButton(R.string.action_save) { _, _ -> save(session.copy(context = context.text.toString())) }
            .show().keepTabsTappable()
    })

    fun approve(approval: AgentApproval, request: AgentRequest): Boolean {
        val choice = CompletableFuture<Boolean>()
        runFeatureOnUiThread(onUnavailable = { choice.complete(false) }) {
            if (isFinishing || isDestroyed) { choice.complete(false); return@runFeatureOnUiThread }
            if (!dialog.isShowing || activeRequest !== request) { choice.complete(false); return@runFeatureOnUiThread }
            val details = TextView(this).apply {
                UuMetrics.text(this)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setTextIsSelectable(true)
                text = getString(R.string.agent_approval_detail, approval.name, approval.input.toString(2))
            }
            val approvalDialog = UuDialogBuilder(this).setTitle(R.string.agent_approval)
                .setView(UuScrollView(this).apply { addView(details) })
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.agent_approve) { _, _ -> choice.complete(true) }
                .show()
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
            showStatus(getString(missing ?: R.string.agent_invalid_settings))
            return@setOnClickListener
        }
        val previous = session.turns.filter { it.answer.isNotEmpty() }.takeLast(6).flatMap {
            listOf(AgentClient.message("user", it.question), AgentClient.message("assistant", it.answer))
        }
        if (!save(session.copy(turns = session.turns + AgentExchange(question)))) return@setOnClickListener
        val running = session
        if (!store.start(running.id)) { storageFailure(); return@setOnClickListener }
        val request = AgentRequest()
        val key = apiKey
        val scope = AppDiagnostics.enterIntent(running.feature, listOf(key), newChain = true)
        val trace = scope.trace
        trace.chain?.let { dialogChainTabs[it] = ownerTabId }
        activeRequest = request
        setBusy(true)
        showStatus(getString(R.string.agent_running))
        appendMessage(question)
        prompt.text?.clear()
        try {
            featureThread {
                request.setWorker(Thread.currentThread())
                var updated: AgentSession? = null
                var answer: String? = null
                var error: AppFailure? = null
                var invalidFolder = false
                var cancelled = false
                try {
                    request.checkActive()
                    val root = storage.canonicalFile
                    val directory = selected.canonicalFile
                    if (directory == root || !directory.toPath().startsWith(root.toPath()) ||
                        !directory.isDirectory || !directory.canRead()) {
                        invalidFolder = true
                        throw AgentClientException("workspace", FailureCode.INVALID_RESULT,
                            "Choose a readable subdirectory of shared storage")
                    }
                    val workspace = McpWorkspace(directory)
                    val worker = McpArchiveWorker(workspace, prefs, strFnOf(this))
                    McpTasks(agentFeature = running.feature, execute = worker::execute).use { tasks ->
                        val runtime = WorkspaceSkillRuntime(workspace, tasks, prefs)
                        answer = AgentClient(endpoint, running.model, key, request).complete(
                            previous, question, runtime, running.permission, running.context,
                            confirm = { approve(it, request) }, deleteFile = { deleteAgentFile(workspace, it) },
                        ) { event ->
                            runFeatureOnUiThread {
                                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                                if (!dialog.isShowing || activeRequest !== request) return@runFeatureOnUiThread
                                appendMessage(when {
                                    event.failure != null -> getString(R.string.agent_tool_failed, event.failure)
                                    event.readOnly -> getString(R.string.agent_tool_read, event.name, event.path)
                                    else -> getString(R.string.agent_tool_write, event.name, event.path)
                                })
                            }
                        }.take(MAX_PROMPT_CHARS)
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
                    store.finish(running.id)
                    runFeatureOnUiThread {
                        if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                        if (!dialog.isShowing || activeRequest !== request) return@runFeatureOnUiThread
                        activeRequest = null
                        activeApproval?.dismiss()
                        setBusy(false)
                        updated?.let { session = it; refreshRows() }
                        answer?.let(::appendMessage)
                        val failure = error
                        when {
                            cancelled -> showStatus(getString(R.string.msg_cancelled))
                            failure != null -> showStatus(if (invalidFolder) getString(R.string.agent_invalid_workspace)
                                else getString(R.string.agent_failed, "${failure.code.name} · ${failure.logId}"))
                            else -> status.visibility = View.GONE
                        }
                    }
                }
            }
        } finally {
            AppDiagnostics.leaveIntent(scope)
        }
    }

    refreshRows()
    dialog.show()
    dialog.belowTabs(fitContent = true) {
        activeRequest?.cancel()
        activeApproval?.dismiss()
        apiKey = ""
    }
}

private fun MainActivity.showAgentModelSettings(
    baseUrl: String,
    model: String,
    key: String,
    save: (String, String, String) -> Unit,
) {
    val body = agentForm()
    val urlInput = agentField(R.string.agent_base_url, 1024, baseUrl).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }
    val modelInput = agentField(R.string.agent_model_name, 200, model)
    val keyInput = agentField(R.string.agent_api_key, 1024, key).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod = PasswordTransformationMethod.getInstance()
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    body.addView(urlInput)
    body.addView(modelInput)
    body.addView(keyInput)
    val dialog = UuDialogBuilder(this).setTitle(R.string.agent_model_settings)
        .setView(UuScrollView(this).apply { addView(body) })
        .setNegativeButton(R.string.action_cancel, null)
        .setPositiveButton(R.string.action_save, null)
        .show()
    dialog.keepTabsTappable()
    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val url = urlInput.text.toString().trim()
        val modelName = modelInput.text.toString().trim()
        val secret = keyInput.text.toString().trim()
        if (agentEndpoint(url) == null || modelName.isBlank() || secret.any { it.isISOControl() }) {
            UuNotice.makeText(this, R.string.agent_invalid_settings, android.widget.Toast.LENGTH_LONG).show()
            return@setOnClickListener
        }
        save(url, modelName, secret)
        dialog.dismiss()
    }
}

private fun MainActivity.agentForm() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val gutter = resources.getDimensionPixelSize(R.dimen.space_xl)
    setPadding(gutter, gutter / 2, gutter, gutter / 2)
}

private fun MainActivity.agentField(hintId: Int, maxChars: Int, initial: String = "") = UuTextField(this).apply {
    isSaveEnabled = false
    setHint(hintId)
    setSingleLine()
    filters = arrayOf(InputFilter.LengthFilter(maxChars))
    setText(initial)
}

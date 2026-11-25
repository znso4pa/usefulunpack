package com.usefulunpacker

import android.app.AlertDialog
import android.os.Environment
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
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
    val credentials = AgentCredentials(File(noBackupFilesDir, "agent-credentials"))
    fun newSession() = AgentSession(
        baseUrl = prefs.getString(PREF_AGENT_BASE_URL, "").orEmpty(),
        model = prefs.getString(PREF_AGENT_MODEL, "").orEmpty(),
    )
    var session = newSession()
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

    fun isOpen() = dialog.window?.decorView?.isAttachedToWindow == true

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

    fun showSessions(saved: List<AgentSession>) {
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
                                if (!isOpen()) return@runFeatureOnUiThread
                                setBusy(false)
                                if (deleted) loadSession(newSession()) else storageFailure()
                            }
                        }
                    }.show().keepTabsTappable()
            }
        }
        val picker = chooser.create()
        rootDialogToTab(picker, ownerTabId)
        picker.show()
        picker.keepTabsTappable()
    }
    sessionRow.bind(getString(R.string.agent_sessions), navigation = true, onClick = {
        setBusy(true, cancellable = false)
        showStatus(getString(R.string.agent_sessions_loading))
        featureThread {
            val saved = try { store.list() } catch (_: Exception) { null }
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                if (!isOpen()) return@runFeatureOnUiThread
                setBusy(false)
                if (saved == null) storageFailure() else {
                    status.visibility = View.GONE
                    showSessions(saved)
                }
            }
        }
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
        showAgentModelSettings(session.baseUrl, session.model, credentials) { url, modelName ->
            val changed = session.baseUrl != url || session.model != modelName
            if (!changed) return@showAgentModelSettings true
            val next = session.withModelSettings(url, modelName)
            val saved = save(next)
            if (saved) {
                loadSession(session)
                prefs.edit().putString(PREF_AGENT_BASE_URL, url).putString(PREF_AGENT_MODEL, modelName).apply()
            }
            saved
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
        val scope = AppDiagnostics.enterIntent(running.feature, newChain = true)
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
                            ) { event ->
                                runFeatureOnUiThread {
                                    if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                                    if (!isOpen() || activeRequest !== request) return@runFeatureOnUiThread
                                    appendMessage(when {
                                        event.failure != null -> getString(R.string.agent_tool_failed, event.failure)
                                        event.readOnly -> getString(R.string.agent_tool_read, event.name, event.path)
                                        else -> getString(R.string.agent_tool_write, event.name, event.path)
                                    })
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
                    store.finish(running.id)
                    runFeatureOnUiThread {
                        if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                        if (!isOpen() || activeRequest !== request) return@runFeatureOnUiThread
                        activeRequest = null
                        activeApproval?.dismiss()
                        setBusy(false)
                        updated?.let { session = it; refreshRows() }
                        answer?.let(::appendMessage)
                        val failure = error
                        when {
                            cancelled -> showStatus(getString(R.string.msg_cancelled))
                            failure != null -> showStatus(when {
                                invalidFolder -> getString(R.string.agent_invalid_workspace)
                                failure.stage == "credential_read" -> getString(R.string.agent_key_read_failed, failure.logId)
                                else -> getString(R.string.agent_failed, "${failure.code.name} · ${failure.logId}")
                            })
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
    }
}

private fun MainActivity.showAgentModelSettings(
    baseUrl: String,
    model: String,
    credentials: AgentCredentials,
    save: (String, String) -> Boolean,
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
        visibility = View.GONE
    }
    var keyEdited = false
    var settingKey = false
    var endpoint = agentEndpoint(baseUrl)
    var readingKey = false
    var saving = false

    fun fillKey(value: String) {
        settingKey = true
        try { keyInput.setText(value) } finally { settingKey = false }
    }

    fun error(message: Int, logId: String) {
        status.text = getString(message, logId)
        status.visibility = View.VISIBLE
    }

    keyInput.doAfterTextChanged { if (!settingKey) keyEdited = true }
    clearing.setOnClickListener {
        reveal.isChecked = false
        fillKey("")
        keyEdited = true
        status.setText(R.string.agent_key_clear_pending)
        status.visibility = View.VISIBLE
    }
    body.addView(urlInput)
    body.addView(modelInput)
    body.addView(keyInput)
    body.addView(UuToggleRow(this).apply { bind(getString(R.string.agent_key_reveal), reveal) })
    body.addView(clearing)
    body.addView(status)
    val dialog = UuDialogBuilder(this).setTitle(R.string.agent_model_settings)
        .setMessage(R.string.agent_key_storage_notice)
        .setView(UuScrollView(this).apply { addView(body) })
        .setNegativeButton(R.string.action_cancel, null)
        .setPositiveButton(R.string.action_save, null)
        .create()
    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    dialog.show()
    val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
    dialog.keepTabsTappable { reveal.isChecked = false; fillKey("") }
    keyInput.viewTreeObserver.addOnWindowFocusChangeListener { focused -> if (!focused) reveal.isChecked = false }
    fun checkControls() {
        urlInput.isEnabled = !saving
        modelInput.isEnabled = !saving
        keyInput.isEnabled = !saving && !readingKey
        reveal.isEnabled = !saving && !readingKey
        clearing.isEnabled = !saving && !readingKey
        saveButton.isEnabled = !saving
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
        if (!keyEdited) {
            if (save(url, modelName)) dialog.dismiss()
            return@setOnClickListener
        }
        reveal.isChecked = false
        saving = true
        checkControls()
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
                saving = false
                checkControls()
                if (failure != null) error(R.string.agent_key_save_failed, failure.logId)
                else if (save(url, modelName)) dialog.dismiss()
            }
        }
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

package com.usefulunpacker

import android.app.AlertDialog
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import java.util.concurrent.CancellationException

internal fun MainActivity.showAgentModelSettings(
    baseUrl: String,
    model: String,
    maxOutputTokens: Int,
    credentials: AgentCredentials,
    save: (String, String, Int, (AppFailure?) -> Unit) -> Unit,
) {
    val body = agentForm()
    val urlInput = agentField(R.string.agent_base_url, 1024, baseUrl).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }
    val modelInput = agentField(R.string.agent_model_name, 200, model)
    val tokenInput = agentField(R.string.agent_output_tokens, 5, maxOutputTokens.toString()).apply {
        id = View.generateViewId()
        inputType = InputType.TYPE_CLASS_NUMBER
    }
    val tokenHelp = getString(R.string.agent_output_tokens_hint,
        AGENT_OUTPUT_TOKEN_RANGE.first, AGENT_OUTPUT_TOKEN_RANGE.last, DEFAULT_AGENT_OUTPUT_TOKENS)
    val tokenHint = TextView(this).apply {
        UuMetrics.text(this)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        text = tokenHelp
    }
    val tokenHelpColor = tokenHint.currentTextColor
    tokenInput.doAfterTextChanged {
        if (tokenHint.text != tokenHelp) {
            tokenHint.text = tokenHelp
            tokenHint.setTextColor(tokenHelpColor)
        }
    }
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
    var keyReadGeneration = 0
    var pendingKeyRead: Runnable? = null
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
    body.addSpaced(TextView(this).apply {
        UuMetrics.text(this)
        setText(R.string.agent_output_tokens)
        labelFor = tokenInput.id
    }, R.dimen.space_md)
    body.addView(tokenInput)
    body.addView(tokenHint)
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
        pendingKeyRead?.let(urlInput::removeCallbacks)
        pendingKeyRead = null
        keyReadGeneration++
        connectionTest?.cancel()
        reveal.isChecked = false
        fillKey("")
    }
    keyInput.viewTreeObserver.addOnWindowFocusChangeListener { focused -> if (!focused) reveal.isChecked = false }
    fun checkControls() {
        val editing = !saving && connectionTest == null
        urlInput.isEnabled = editing
        modelInput.isEnabled = editing
        tokenInput.isEnabled = editing
        keyInput.isEnabled = editing && !readingKey
        reveal.isEnabled = editing && !readingKey
        clearing.isEnabled = editing && !readingKey
        testButton.isEnabled = editing && !readingKey
        saveButton.isEnabled = editing && !readingKey
        cancelButton.isEnabled = !saving
        dialog.setCancelable(!saving)
    }

    fun loadKey() {
        val requested = endpoint ?: return
        val generation = ++keyReadGeneration
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
                    endpoint?.toExternalForm() != requested.toExternalForm() || generation != keyReadGeneration) return@runFeatureOnUiThread
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
            pendingKeyRead?.let(urlInput::removeCallbacks)
            pendingKeyRead = null
            keyReadGeneration++
            reveal.isChecked = false
            fillKey("")
            keyEdited = false
            readingKey = false
            status.visibility = View.GONE
            checkControls()
            if (next != null) {
                readingKey = true
                checkControls()
                pendingKeyRead = Runnable {
                    pendingKeyRead = null
                    if (dialog.window?.decorView?.isAttachedToWindow == true) loadKey()
                }.also { urlInput.postDelayed(it, 300) }
            }
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
        val limit = tokenInput.text.toString().trim().toIntOrNull()
        if (limit == null || limit !in AGENT_OUTPUT_TOKEN_RANGE) {
            tokenHint.text = getString(R.string.agent_output_tokens_invalid,
                AGENT_OUTPUT_TOKEN_RANGE.first, AGENT_OUTPUT_TOKEN_RANGE.last)
            tokenHint.setTextColor(getColor(R.color.state_error))
            tokenInput.requestFocus()
            return@setOnClickListener
        }
        reveal.isChecked = false
        saving = true
        checkControls()
        fun saveModel() {
            save(url, modelName, limit) { failure ->
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

package com.usefulunpacker

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.content.Intent
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/** Offline smoke only. Uses private disposable fixtures and never reads provider credentials. */
class InspectionSmokeInstrumentation : Instrumentation() {
    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val root = Files.createTempDirectory(targetContext.cacheDir.toPath(), "uu-inspection-smoke-").toFile().canonicalFile
        var checks = 0
        var stage = "setup"
        var activity: MainActivity? = null
        var dialog: AlertDialog? = null
        var code = Activity.RESULT_OK
        val outcome = Bundle()
        try {
            val a = File(root, "a").apply { mkdir() }
            val b = File(root, "b").apply { mkdir() }
            a.resolve("script.txt").writeText("Hello {name}\n")
            b.resolve("script.txt").writeText("你好 {name}\n")
            a.resolve("same.txt").writeText("same")
            b.resolve("same.txt").writeText("same")
            stage = "line-edit"
            val encoding = LineEditEncoding.forSnapshot("UTF-8", ByteArray(0))
            val document = LineEditDocument("Hello {name}\\n")
            check(document.validate(mapOf(0 to "你好 {name}\\n"), encoding) == null &&
                document.validate(mapOf(0 to "你好\\n"), encoding)?.problem == LineEditProblem.PLACEHOLDER_MISMATCH); checks++
            stage = "bytes"
            val comparison = DirectoryComparator.compare(a,b)
            check(comparison.complete && comparison.differences == 1 && comparison.unchanged == 1); checks++
            stage = "diff"
            val preview = directoryComparisonPreview(comparison, comparison.entries.single()) {}
            check(preview.lines.any { it.kind == 1 } && preview.lines.any { it.kind == -1 }); checks++
            stage = "cli"
            val ctx = UuCommands.Ctx(null,root,str = { id,args -> targetContext.getString(id,*args) })
            check(UuCommands.dispatch(listOf("dircmp","a","b","-j"),ctx).exitCode == 1); checks++
            check(UuCommands.dispatch(listOf("check","a/script.txt","b/script.txt","--profile","named","-j"),ctx).exitCode == 0); checks++
            stage = "charset"
            a.resolve("vendor.txt").writeBytes(byteArrayOf(0x87.toByte(),0x40))
            b.resolve("vendor.txt").writeText("①")
            check(LocalizationChecker.check(a.resolve("vendor.txt"),b.resolve("vendor.txt"),sourceEncoding="CP932",targetEncoding="CP932").errors == 0); checks++
            val encodingSource=root.resolve("encoding-source.txt").apply { writeText("original") }
            val encodingDraft=TranslationDraft.open(encodingSource,"UTF-8","CP932")
            encodingDraft.edit(0,"①")
            check(encodingDraft.export().readBytes().contentEquals(byteArrayOf(0x87.toByte(),0x40)));checks++
            stage = "uut"
            val workspace=McpWorkspace(root)
            val worker=McpArchiveWorker(workspace)
            McpTasks(execute=worker::execute).use { tasks ->
                val runtime=WorkspaceSkillRuntime(workspace,tasks)
                val run=runtime.invokeSkill("RUN_UUT",JSONObject().put("script",
                    "uu dircmp a a -j\nuu check a/script.txt b/script.txt --profile named -j").put("waitMs",15000))
                check(!run.isError && run.data.getString("state")=="SUCCESS"); checks++
                stage = "sandbox"
                var denied=false
                try { workspace.resolve("../outside") } catch (_: McpInputException) { denied=true }
                check(denied); checks++
                stage = "http"
                McpListener(McpProtocol(runtime,"smoke"),tasks,root).use { listener ->
                    val conn=URL(listener.url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
                    try {
                        conn.requestMethod="POST";conn.doOutput=true;conn.connectTimeout=3000;conn.readTimeout=10000
                        conn.setRequestProperty("Content-Type","application/json")
                        conn.setRequestProperty("Authorization","Bearer ${listener.token}")
                        val request=JSONObject().put("jsonrpc","2.0").put("id",1).put("method","tools/call")
                            .put("params",JSONObject().put("name","skill_call").put("arguments",JSONObject()
                                .put("skill","COMPARE_DIRECTORY").put("input",JSONObject().put("source","a").put("target","b"))))
                        conn.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                        val response=JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                            .getJSONObject("result").getJSONObject("structuredContent")
                        check(response.getString("status")=="DIFFERENT" && response.getBoolean("complete")); checks++
                    } finally { conn.disconnect() }
                }
            }
            stage="ui"
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            onMain { dialog=showDirectoryComparison(checkNotNull(activity),a,b) }
            waitForIdleSync()
            onMain { checkNotNull(dialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            fun contains(view: View, text: String): Boolean = (view is TextView && view.text.toString().contains(text)) ||
                (view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it),text) })
            var complete=false
            repeat(80) {
                if (!complete) {
                    onMain { complete=dialog?.window?.decorView?.let { contains(it,checkNotNull(activity).getString(R.string.directory_finished)) } == true }
                    if (!complete) Thread.sleep(100)
                }
            }
            check(complete); checks++
            onMain { dialog?.dismiss() }
            waitForIdleSync()
            checks++
            stage="line-editor-input"
            val original=a.resolve("script.txt").readBytes()
            onMain {
                dialog=showLineEditor(checkNotNull(activity),a.resolve("script.txt"),String(original,Charsets.UTF_8),original,"UTF-8","UTF-8")
            }
            fun find(view: View, predicate: (View)->Boolean): View? {
                if(predicate(view)) return view
                if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),predicate)?.let { return it }
                return null
            }
            onMain {
                val rootView=checkNotNull(dialog?.window?.decorView)
                outcome.putBoolean("inspection_initial_ime_blocked",checkNotNull(dialog?.window).attributes.flags and android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0)
                val button=find(rootView) { it is UuButton && it.text.toString()==checkNotNull(activity).getString(R.string.line_edit_translate) }
                checkNotNull(button).performClick()
            }
            var input:UuTextField?=null
            repeat(80) {
                if(input==null) {
                    onMain { input=dialog?.window?.decorView?.let { rootView -> find(rootView) { it is UuTextField && it.isShown } as? UuTextField } }
                    if(input==null) Thread.sleep(100)
                }
            }
            onMain {
                checkNotNull(input)
                check(checkNotNull(dialog?.window).attributes.flags and android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM == 0)
            };checks++
            stage="line-editor-ime"
            val point=IntArray(2)
            var x=0f;var y=0f
            onMain {
                val field=checkNotNull(input)
                field.getLocationOnScreen(point)
                x=point[0]+field.width/2f;y=point[1]+field.height/2f
            }
            val time=android.os.SystemClock.uptimeMillis()
            for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                val event=android.view.MotionEvent.obtain(time,android.os.SystemClock.uptimeMillis(),action,x,y,0)
                try { sendPointerSync(event) } finally { event.recycle() }
            }
            var ime=false
            repeat(80) {
                if(!ime) {
                    onMain { ime=androidx.core.view.ViewCompat.getRootWindowInsets(checkNotNull(input))?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true }
                    if(!ime) Thread.sleep(100)
                }
            }
            if(!ime) onMain {
                outcome.putBoolean("inspection_editor_focus",input?.hasFocus()==true)
                outcome.putBoolean("inspection_window_focus",input?.hasWindowFocus()==true)
                outcome.putInt("inspection_editor_flags",dialog?.window?.attributes?.flags ?: 0)
            }
            check(ime);checks++
            stage="line-editor-nested-input"
            var child:AlertDialog?=null
            onMain {
                child=UuDialogBuilder(checkNotNull(activity)).setMessage(R.string.line_edit_start).setNegativeButton(R.string.action_close,null).show()
            }
            waitForIdleSync()
            var blocked=false
            repeat(40) {
                if(!blocked) {
                    onMain { blocked=checkNotNull(dialog?.window).attributes.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0 }
                    if(!blocked) Thread.sleep(50)
                }
            }
            check(blocked)
            onMain {
                child?.dismiss()
                (targetContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(checkNotNull(input).windowToken,0)
                dialog?.dismiss()
            };checks++
            check(original.contentEquals(a.resolve("script.txt").readBytes()));checks++
            stage="translation-workbench-ui"
            val draft=TranslationDraft.open(a.resolve("script.txt"),"UTF-8","UTF-8")
            val sessionDirectory=File(targetContext.noBackupFilesDir,"agent-sessions")
            val previousSessionFiles=sessionDirectory.list()?.toSet().orEmpty()
            val sessions=AgentSessionStore(sessionDirectory)
            var testSession:AgentSession?=null
            try {
                onMain {
                    checkNotNull(activity).showAgent(draft)
                    dialog=checkNotNull(activity).rootedDialogs.last().dlg as AlertDialog
                }
                repeat(80) {
                    if(testSession==null) {
                        val created=sessionDirectory.list().orEmpty().filter { it !in previousSessionFiles && it.matches(Regex("[a-f0-9]{32}\\.gz")) }
                        testSession=created.map { sessions.open(it.removeSuffix(".gz")) }.firstOrNull { it.translation && it.workspace==a.path }
                        if(testSession==null) Thread.sleep(100)
                    }
                }
                checkNotNull(testSession)
                var row:UuTextField?=null
                repeat(80) {
                    if(row==null) {
                        onMain { row=dialog?.window?.decorView?.let { view -> find(view) { it is UuTextField &&
                            it.contentDescription==checkNotNull(activity).getString(R.string.line_edit_row_input,1) && it.isShown } as? UuTextField } }
                        if(row==null) Thread.sleep(100)
                    }
                }
                onMain { checkNotNull(row).setText("manual {name}") }
                check(draft.value(0)=="manual {name}");checks++
                onMain {
                    checkNotNull(find(checkNotNull(dialog?.window?.decorView)) { it is UuButton && it.text.toString()==checkNotNull(activity).getString(R.string.translation_undo) }).performClick()
                }
                check(draft.value(0)=="Hello {name}");checks++
                onMain {
                    checkNotNull(find(checkNotNull(dialog?.window?.decorView)) { it is UuTextField && it.contentDescription==checkNotNull(activity).getString(R.string.line_edit_row_input,1) } as? UuTextField).setText("persisted {name}")
                }
                var saved=false
                repeat(80) {
                    if(!saved) {
                        saved=sessions.openTranslation(checkNotNull(testSession))?.value(0)=="persisted {name}"
                        if(!saved) Thread.sleep(100)
                    }
                }
                check(saved);checks++
                onMain {
                    checkNotNull(find(checkNotNull(dialog?.window?.decorView)) { it is UuButton && it.text.toString()==checkNotNull(activity).getString(R.string.translation_chat) }).performClick()
                    check(row?.isShown==false)
                    checkNotNull(find(checkNotNull(dialog?.window?.decorView)) { it is UuButton && it.text.toString()==checkNotNull(activity).getString(R.string.translation_tab) }).performClick()
                    check(row?.isShown==true)
                };checks++
                onMain {
                    checkNotNull(find(checkNotNull(dialog?.window?.decorView)) { it is UuIconButton && it.contentDescription==checkNotNull(activity).getString(R.string.agent_conversation_settings) }).performClick()
                    check(row?.isShown==false)
                    dialog?.dismiss()
                };checks++
            } finally {
                onMain { dialog?.dismiss() }
                testSession?.let { owned ->
                    repeat(80) { if(TranslationEditors.heldByOther(owned.id,this)) Thread.sleep(100) }
                    sessions.delete(sessions.open(owned.id)) { AppDiagnostics.store?.deleteAgentSession(it) }
                }
            }
            check(original.contentEquals(a.resolve("script.txt").readBytes()));checks++
            outcome.putString("inspection_state","pass")
        } catch (e: Throwable) {
            code=Activity.RESULT_CANCELED
            outcome.putString("inspection_state","failed");outcome.putString("inspection_stage",stage)
            outcome.putString("inspection_error",e.javaClass.simpleName)
        } finally {
            onMain { dialog?.dismiss(); activity?.finish() }
            if (!root.deleteRecursively()) { code=Activity.RESULT_CANCELED;outcome.putString("inspection_cleanup","failed") }
        }
        outcome.putInt("inspection_checks",checks)
        finish(code,outcome)
    }
}

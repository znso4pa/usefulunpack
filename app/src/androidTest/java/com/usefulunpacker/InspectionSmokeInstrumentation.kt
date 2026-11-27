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
            runOnMainSync { dialog=showDirectoryComparison(checkNotNull(activity),a,b) }
            waitForIdleSync()
            runOnMainSync { checkNotNull(dialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            fun contains(view: View, text: String): Boolean = (view is TextView && view.text.toString().contains(text)) ||
                (view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it),text) })
            var complete=false
            repeat(80) {
                if (!complete) {
                    runOnMainSync { complete=dialog?.window?.decorView?.let { contains(it,checkNotNull(activity).getString(R.string.directory_finished)) } == true }
                    if (!complete) Thread.sleep(100)
                }
            }
            check(complete); checks++
            runOnMainSync { dialog?.dismiss() }
            waitForIdleSync()
            checks++
            outcome.putString("inspection_state","pass")
        } catch (e: Throwable) {
            code=Activity.RESULT_CANCELED
            outcome.putString("inspection_state","failed");outcome.putString("inspection_stage",stage)
            outcome.putString("inspection_error",e.javaClass.simpleName)
        } finally {
            runOnMainSync { dialog?.dismiss(); activity?.finish() }
            if (!root.deleteRecursively()) { code=Activity.RESULT_CANCELED;outcome.putString("inspection_cleanup","failed") }
        }
        outcome.putInt("inspection_checks",checks)
        finish(code,outcome)
    }
}

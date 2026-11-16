package com.usefulunpacker

import android.app.Application

/**
 * 应用入口：初始化主题 token 与持久诊断，保留 Android 原有崩溃处理。
 *
 * 必须在任何界面读 `C` 之前跑 —— 所以放在 `Application.onCreate`（早于所有 Activity、
 * 主题 inflate）。此前本工程没有 Application 子类；见 `util/Constants.kt` 的 `initTokens`。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        initTokens(this)
        AppDiagnostics.store = DiagnosticStore(java.io.File(filesDir, "logs"), onStorageFailure = { detail, id ->
            // Publish directly: trying to write another log here would recurse on a full disk.
            AppDiagnostics.publish(AppFailure(FailureCode.IO, "diagnostic-storage", detail, id ?: "application"))
        })
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { worker, cause ->
            try {
                AppDiagnostics.begin("uncaught").fail(FailureCode.UNEXPECTED, "Thread ${worker.name}", cause)
                AppDiagnostics.store?.flush()
            } finally {
                if (previous != null) previous.uncaughtException(worker, cause)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
    }
}

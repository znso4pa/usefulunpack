package com.usefulunpacker

import android.app.Activity
import android.view.View

/** A new click is a new intent; internal continuations keep the captured chain. */
fun View.onUserIntent(feature: String, action: (View) -> Unit) {
    setOnClickListener { view ->
        val scope = AppDiagnostics.enterIntent(feature, newChain = true)
        try { AppDiagnostics.invokeCallback(scope.trace) { action(view) } }
        finally { AppDiagnostics.leaveIntent(scope) }
    }
}

/** Retains the operation chain across Handler dispatch, then restores the UI thread's previous scope. */
fun Activity.runFeatureOnUiThread(block: () -> Unit) {
    val trace = AppDiagnostics.currentTrace()
    trace?.retain()
    runOnUiThread {
        if (isFinishing || isDestroyed) { trace?.release(); return@runOnUiThread }
        try {
            AppDiagnostics.invokeCallback(trace, block)
        } finally { trace?.release() }
    }
}

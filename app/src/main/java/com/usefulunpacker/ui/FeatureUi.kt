package com.usefulunpacker

import android.app.Activity
import android.view.View

/** A new click is a new intent; internal continuations keep the captured chain. */
fun View.onUserIntent(feature: String, action: (View) -> Unit) {
    setOnClickListener { view ->
        val scope = AppDiagnostics.enterIntent(feature, newChain = true)
        (view.context.hostActivity() as? MainActivity)?.let { activity ->
            scope.trace.chain?.let { chain -> activity.tabs.getOrNull(activity.activeTabIndex)?.let { activity.dialogChainTabs[chain] = it.tabId } }
        }
        try { AppDiagnostics.invokeCallback(scope.trace) { action(view) } }
        finally { AppDiagnostics.leaveIntent(scope) }
    }
}

/** Retains the operation chain across Handler dispatch, then restores the UI thread's previous scope. */
fun Activity.runFeatureOnUiThread(onUnavailable: () -> Unit = {}, block: () -> Unit) {
    val trace = AppDiagnostics.currentTrace()
    trace?.retain()
    runOnUiThread {
        if (isFinishing || isDestroyed) {
            // Bookkeeping only: callers can unblock a worker without touching a dead UI.
            try { AppDiagnostics.invokeCallback(trace, onUnavailable) }
            finally { trace?.release() }
            return@runOnUiThread
        }
        try {
            AppDiagnostics.invokeCallback(trace, block)
        } finally { trace?.release() }
    }
}

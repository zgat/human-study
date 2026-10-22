package com.zga.meridianatlas.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Handles back only on the main screen. The caller disables this while an IME or
 * modal panel is open so their normal back handling has priority.
 */
@Composable
internal fun DoubleBackExit(enabled: Boolean) {
    val context = LocalContext.current
    val contextActivity = remember(context) { context.findActivity() }
    val activity = LocalActivity.current ?: contextActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { DoubleBackExitState() }
    val prompt = remember { ExitPrompt() }

    DisposableEffect(enabled, lifecycleOwner) {
        fun reset() {
            state.reset()
            prompt.clear()
        }
        if (!enabled) reset()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) reset()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            reset()
        }
    }

    BackHandler(enabled = enabled && activity != null) {
        if (state.shouldExitOnBack(SystemClock.uptimeMillis())) {
            prompt.clear()
            activity?.finish()
        } else {
            prompt.show(context.applicationContext)
        }
    }
}

/** A monotonic-time gate, kept independent of Android for deterministic tests. */
internal class DoubleBackExitState {
    private var firstBackAtMillis: Long? = null

    fun shouldExitOnBack(nowUptimeMillis: Long): Boolean {
        val previous = firstBackAtMillis
        if (previous != null && nowUptimeMillis >= previous &&
            nowUptimeMillis - previous <= WINDOW_MILLIS) {
            reset()
            return true
        }
        firstBackAtMillis = nowUptimeMillis
        return false
    }

    fun reset() {
        firstBackAtMillis = null
    }

    private companion object {
        const val WINDOW_MILLIS = 2_000L
    }
}

private class ExitPrompt {
    private var toast: Toast? = null

    fun show(context: Context) {
        clear()
        toast = Toast.makeText(context, "再按一次返回键退出", Toast.LENGTH_SHORT).also { it.show() }
    }

    fun clear() {
        toast?.cancel()
        toast = null
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}

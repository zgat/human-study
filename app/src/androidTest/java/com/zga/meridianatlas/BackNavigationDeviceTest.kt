package com.zga.meridianatlas

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real key events against the app's existing composition; no dispatcher or exit-state calls. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BackNavigationDeviceTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val failureEvidence = QaFailureRule()
    @get:Rule(order = 2) val testName = TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    // Keep this reference: compose.activity cannot be queried after its scenario is destroyed.
    private lateinit var activity: MainActivity
    private val observations = JSONArray()
    private val captures = mutableListOf<Pair<String, Bitmap>>()
    private val runToken = System.currentTimeMillis()
    private var completed = false

    private data class Press(val start: Long, val end: Long) {
        fun json() = JSONObject().put("inputStartUptimeMillis", start).put("inputEndUptimeMillis", end)
    }

    private data class ActivityState(val finishing: Boolean, val destroyed: Boolean,
        val lifecycle: Lifecycle.State, val focused: Boolean, val imeVisible: Boolean) {
        fun json() = JSONObject().put("finishing", finishing).put("destroyed", destroyed)
            .put("lifecycle", lifecycle.name).put("windowFocused", focused).put("imeVisible", imeVisible)
    }

    @Before fun ready() {
        activity = compose.activity
        onMain { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.waitUntil(180_000) {
            compose.onAllNodesWithContentDescription(ABOUT).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(180_000) {
            compose.onAllNodesWithText(MODEL_LOADING).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("请检查模型资源是否完整随包提供").assertDoesNotExist()
        compose.onNodeWithContentDescription(ABOUT).assertIsDisplayed()
        compose.waitForIdle()
        val initial = state()
        assertMainIsActive("Initial main screen", initial)
        observations.put(JSONObject().put("stage", "initial-main").put("activity", initial.json())
            .put("modelLoadingOverlayAbsent", true).put("modelErrorOverlayAbsent", true)
            .put("appVersion", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
            .put("sdk", Build.VERSION.SDK_INT).put("device", "${Build.MANUFACTURER} ${Build.MODEL}"))
    }

    @After fun saveEvidenceWithoutAccessingDestroyedComposeActivity() {
        try {
            val folder = File(instrumentation.targetContext.filesDir, "qa/back-navigation").apply { mkdirs() }
            captures.forEach { (stage, bitmap) ->
                val file = File(folder, "${testName.methodName}-$runToken-$stage.png")
                try {
                    file.outputStream().use { assertTrue("PNG encoding must succeed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    observations.put(JSONObject().put("stage", "saved-screen").put("file", file.name)
                        .put("width", bitmap.width).put("height", bitmap.height).put("bytes", file.length()))
                } finally {
                    bitmap.recycle()
                }
            }
            File(folder, "${testName.methodName}-$runToken.json").writeText(JSONObject()
                .put("case", testName.methodName).put("completed", completed).put("observations", observations)
                .put("inputMethod", "Instrumentation.sendKeyDownUpSync(KEYCODE_BACK); no dispatcher or DoubleBackExitState invocation")
                .put("expectedToastTextFromSource", "再按一次返回键退出")
                .put("toastEvidenceBoundary", "The screen is captured after the first real Back while this Activity remains resumed and focused. Toast text is retained for visual review; no Compose/accessibility text assertion is claimed.")
                .put("timingEvidence", "The interval from the first key injection start through the second key injection end must be at most 2000ms. PNG encoding and file writes run only afterwards.")
                .toString(2))
        } finally {
            captures.filterNot { it.second.isRecycled }.forEach { it.second.recycle() }
            if (::activity.isInitialized) onMain {
                if (!activity.isDestroyed) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    @Test fun mainFirstBackKeepsActivityAndSecondRealBackExits() {
        exerciseMainDoubleBack("main")
        completed = true
    }

    @Test fun aboutPanelBackClosesPanelWithoutCountingAsFirstExitBack() {
        val title = activity.getString(R.string.about_app_title, activity.getString(R.string.app_name))
        compose.onNodeWithContentDescription(ABOUT).performClick()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.waitForIdle()

        val panelBack = realBack("about-panel-back")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(title).assertDoesNotExist()
        awaitCondition(1_000) { state().focused }
        val closed = state()
        assertMainIsActive("After closing About", closed)
        observations.put(JSONObject().put("stage", "about-dismissed").put("activity", closed.json()))

        exerciseMainDoubleBack("after-about", panelBack)
        completed = true
    }

    private fun exerciseMainDoubleBack(stage: String, panelBack: Press? = null) {
        val first = realBack("$stage-first-main-back")
        val firstState = state()
        observations.put(JSONObject().put("stage", "$stage-after-first-main-back").put("activity", firstState.json()))
        assertMainIsActive("First main Back must only prompt", firstState)
        if (panelBack != null) {
            val interval = first.end - panelBack.start
            observations.put(JSONObject().put("stage", "panel-back-not-counted")
                .put("panelBackToFirstMainBackMillis", interval))
            assertTrue("Close About and press main Back within 2s, so timeout cannot hide incorrect counting ($interval ms)", interval in 0L..EXIT_WINDOW_MILLIS)
        }

        // Let the system Toast draw across real frames, then retain pixels only.
        // Encoding a full tablet screenshot here would consume the exit window.
        captureFirstBackPrompt("$stage-first-back-prompt", first)
        val second = realBack("$stage-second-main-back")
        val interval = second.end - first.start
        observations.put(JSONObject().put("stage", "$stage-double-back-window")
            .put("firstInputStartToSecondInputEndMillis", interval))
        assertTrue("Both actual Back inputs must fit inside 2s ($interval ms)", interval in 0L..EXIT_WINDOW_MILLIS)

        awaitCondition(5_000) { state().let { it.finishing || it.destroyed } }
        val exited = state()
        observations.put(JSONObject().put("stage", "$stage-after-second-main-back").put("activity", exited.json()))
        assertTrue("The second real Back must finish or destroy this Activity", exited.finishing || exited.destroyed)
        // No more Compose queries or screenshots after Activity exit.
    }

    private fun captureFirstBackPrompt(stage: String, firstBack: Press) {
        val frames = CountDownLatch(1)
        val frameCount = AtomicInteger()
        val firstFrameNanos = AtomicLong(-1L)
        val lastFrameNanos = AtomicLong(-1L)
        val deadline = firstBack.end + PROMPT_FRAME_WAIT_MILLIS
        val choreographer = onMain { Choreographer.getInstance() }
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameCount.incrementAndGet()
                firstFrameNanos.compareAndSet(-1L, frameTimeNanos)
                lastFrameNanos.set(frameTimeNanos)
                if (SystemClock.uptimeMillis() >= deadline) frames.countDown()
                else choreographer.postFrameCallback(this)
            }
        }
        onMain { choreographer.postFrameCallback(callback) }
        val frameWaitStart = SystemClock.uptimeMillis()
        try {
            assertTrue("App frames must advance through the 500ms prompt draw window", frames.await(1_000, TimeUnit.MILLISECONDS))
            assertTrue("Prompt capture requires multiple real app frames", frameCount.get() >= 2)
        } finally {
            onMain { choreographer.removeFrameCallback(callback) }
            observations.put(JSONObject().put("stage", "$stage-frame-wait")
                .put("requestedMillisAfterFirstBack", PROMPT_FRAME_WAIT_MILLIS)
                .put("waitStartUptimeMillis", frameWaitStart).put("waitEndUptimeMillis", SystemClock.uptimeMillis())
                .put("frameCount", frameCount.get()).put("firstFrameNanos", firstFrameNanos.get())
                .put("lastFrameNanos", lastFrameNanos.get()))
        }
        assertMainIsActive("Prompt screenshot must belong to this active app", state())
        val start = SystemClock.uptimeMillis()
        assertTrue("Prompt pixels must be captured at least 500ms after the first Back completed", start - firstBack.end >= PROMPT_FRAME_WAIT_MILLIS)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UiAutomation screenshot unavailable" }
        captures += stage to bitmap
        observations.put(JSONObject().put("stage", stage).put("captureStartUptimeMillis", start)
            .put("captureEndUptimeMillis", SystemClock.uptimeMillis()).put("deferredPngEncoding", true))
    }

    private fun realBack(stage: String): Press {
        val start = SystemClock.uptimeMillis()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val press = Press(start, SystemClock.uptimeMillis())
        observations.put(JSONObject().put("stage", stage).put("backInput", press.json()))
        return press
    }

    private fun state(): ActivityState = onMain {
        ActivityState(activity.isFinishing, activity.isDestroyed, activity.lifecycle.currentState,
            activity.hasWindowFocus(), !activity.isDestroyed &&
                ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
    }

    private fun assertMainIsActive(message: String, value: ActivityState) {
        assertFalse("$message must not finish the Activity", value.finishing)
        assertFalse("$message must not destroy the Activity", value.destroyed)
        assertTrue("$message must remain resumed", value.lifecycle == Lifecycle.State.RESUMED)
        assertTrue("$message must retain the app window focus", value.focused)
        assertFalse("$message requires the IME to be closed", value.imeVisible)
    }

    private fun awaitCondition(timeoutMillis: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10)
        assertTrue("Expected Activity condition within $timeoutMillis ms", predicate())
    }

    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }

    private companion object {
        const val ABOUT = "关于与数据来源"
        const val MODEL_LOADING = "正在载入真实解剖模型"
        const val PROMPT_FRAME_WAIT_MILLIS = 500L
        const val EXIT_WINDOW_MILLIS = 2_000L
    }
}

package com.zga.meridianatlas

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/** Preserve the originating assertion before an Activity/UiAutomation teardown can mask it. */
class QaFailureRule : TestWatcher() {
    override fun failed(error: Throwable, description: Description) {
        Log.e("AtlasQa", "Failure in ${description.displayName}", error)
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "qa/failures")
        folder.mkdirs()
        File(folder, "${description.className.substringAfterLast('.')}-${description.methodName}.txt")
            .writeText(error.stackTraceToString())
    }
}

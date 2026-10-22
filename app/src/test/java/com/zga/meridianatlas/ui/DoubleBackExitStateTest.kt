package com.zga.meridianatlas.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleBackExitStateTest {
    @Test fun firstBackPromptsAndSecondBackWithinTwoSecondsExits() {
        val state = DoubleBackExitState()
        assertFalse(state.shouldExitOnBack(10_000L))
        assertTrue(state.shouldExitOnBack(11_999L))
    }

    @Test fun zeroUptimeIsAValidFirstBackRatherThanAnEmptySentinel() {
        val state = DoubleBackExitState()
        assertFalse(state.shouldExitOnBack(0L))
        assertTrue(state.shouldExitOnBack(1L))
    }

    @Test fun twoSecondBoundaryIsIncludedButExpiredBackStartsANewWindow() {
        val atBoundary = DoubleBackExitState()
        assertFalse(atBoundary.shouldExitOnBack(100L))
        assertTrue(atBoundary.shouldExitOnBack(2_100L))

        val expired = DoubleBackExitState()
        assertFalse(expired.shouldExitOnBack(100L))
        assertFalse(expired.shouldExitOnBack(2_101L))
        assertTrue(expired.shouldExitOnBack(4_100L))
    }

    @Test fun resetRequiresTwoFreshBacksAfterBackgroundOrAnotherBackHandler() {
        val state = DoubleBackExitState()
        assertFalse(state.shouldExitOnBack(5_000L))
        state.reset()
        assertFalse(state.shouldExitOnBack(5_100L))
        assertTrue(state.shouldExitOnBack(5_200L))
    }

    @Test fun exitClearsThePreviousWindow() {
        val state = DoubleBackExitState()
        assertFalse(state.shouldExitOnBack(100L))
        assertTrue(state.shouldExitOnBack(200L))
        assertFalse(state.shouldExitOnBack(300L))
    }

    @Test fun aClockRollbackCannotCountAsASecondBack() {
        val state = DoubleBackExitState()
        assertFalse(state.shouldExitOnBack(5_000L))
        assertFalse(state.shouldExitOnBack(4_900L))
        assertTrue(state.shouldExitOnBack(5_000L))
    }

    @Test fun separateScreensDoNotShareExitWindowsAndResetIsIdempotent() {
        val first = DoubleBackExitState()
        val second = DoubleBackExitState()
        assertFalse(first.shouldExitOnBack(100L))
        assertFalse(second.shouldExitOnBack(200L))
        first.reset()
        first.reset()
        assertFalse(first.shouldExitOnBack(300L))
        assertTrue(second.shouldExitOnBack(400L))
    }
}

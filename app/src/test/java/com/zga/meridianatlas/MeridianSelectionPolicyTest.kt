package com.zga.meridianatlas

import com.zga.meridianatlas.model.MeridianSelectionPolicy
import org.junit.Assert.*
import org.junit.Test

class MeridianSelectionPolicyTest {
    private val routes = setOf("LU", "LI", "ST", "SP", "HT", "SI", "BL", "KI", "PC", "TE",
        "GB", "LR", "GV", "CV", "CHONG", "DAI", "YINQIAO", "YANGQIAO", "YINWEI", "YANGWEI")
    private val extra = setOf("__extra__")

    @Test fun selectingEveryRouteKeepsExtraPointChoice() {
        assertEquals(routes + extra, MeridianSelectionPolicy.setEnabled(extra, routes, true))
        assertEquals(routes, MeridianSelectionPolicy.setEnabled(setOf("LU"), routes, true))
    }

    @Test fun clearingEveryRouteKeepsExtraPointChoiceAndSelectedPointContext() {
        val enabled = MeridianSelectionPolicy.setEnabled(routes + extra, routes, false)
        assertEquals(extra, enabled)
        assertEquals(extra + "LU", MeridianSelectionPolicy.effective(enabled, "LU"))
        assertEquals(extra, MeridianSelectionPolicy.effective(enabled, null))
    }

    @Test fun extraPointCategoryChangesPreserveAllRouteChoices() {
        assertEquals(routes + extra, MeridianSelectionPolicy.setEnabled(routes, extra, true))
        assertEquals(routes, MeridianSelectionPolicy.setEnabled(routes + extra, extra, false))
    }

    @Test fun singleRouteChangePreservesOtherCategoriesAndNeverMutatesInputs() {
        val current = mutableSetOf("LU", "ST", "__extra__")
        val category = mutableSetOf("LU")
        val cleared = MeridianSelectionPolicy.setEnabled(current, category, false)
        assertEquals(setOf("ST", "__extra__"), cleared)
        assertEquals(setOf("LU", "ST", "__extra__"), current)
        assertEquals(setOf("LU"), category)
        assertEquals(current, MeridianSelectionPolicy.setEnabled(cleared, category, true))
        assertEquals(current, MeridianSelectionPolicy.effective(current, "LU"))
        assertEquals(setOf("LU", "ST", "__extra__"), current)
    }

    @Test fun emptyCategoryAndRepeatedBulkActionsDoNotChangeTheResult() {
        assertEquals(extra, MeridianSelectionPolicy.setEnabled(extra, emptySet(), true))
        assertEquals(extra, MeridianSelectionPolicy.setEnabled(extra, emptySet(), false))
        assertEquals(routes, MeridianSelectionPolicy.setEnabled(routes, routes, true))
        assertTrue(MeridianSelectionPolicy.setEnabled(emptySet(), routes, false).isEmpty())
    }
}

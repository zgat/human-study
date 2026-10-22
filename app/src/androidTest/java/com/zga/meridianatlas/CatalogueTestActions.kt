package com.zga.meridianatlas

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Search lives only in the catalogue; a wide workspace already has it on screen. */
internal fun openCatalogueIfNeeded(compose: ComposeContentTestRule) {
    if (compose.onAllNodesWithTag("atlas-catalogue").fetchSemanticsNodes().isEmpty()) {
        compose.onNodeWithContentDescription("图谱目录").assertIsDisplayed().performClick()
        compose.waitForIdle()
    }
    compose.onNodeWithTag("atlas-catalogue-search").assertIsDisplayed()
}

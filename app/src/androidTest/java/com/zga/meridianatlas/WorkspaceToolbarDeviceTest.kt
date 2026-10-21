package com.zga.meridianatlas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.zga.meridianatlas.ui.WorkspaceToolbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Smallest supported viewport, with every contextual action present. No model is loaded. */
class WorkspaceToolbarDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowToolbarKeepsActionsSeparateAndUsesOnlyOneCompactRow() {
        val calls = IntArray(5)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    WorkspaceToolbar(true, true, { calls[0]++ }, { calls[1]++ }, { calls[2]++ },
                        { calls[3]++ }, { calls[4]++ })
                }
            }
        }
        val descriptions = listOf("搜索图谱", "撤销上一步显示操作", "一键还原组织", "所选项目说明", "关于与数据来源")
        val bounds = descriptions.map { description ->
            compose.onNodeWithContentDescription(description).assertIsDisplayed().assertHasClickAction()
                .fetchSemanticsNode().boundsInRoot
        }
        bounds.zipWithNext().forEach { (left, right) ->
            assertTrue("Touch targets must not overlap", left.right <= right.left + .1f)
        }
        compose.onNodeWithTag("workspace-toolbar").assertHeightIsEqualTo(52.dp)
        descriptions.forEach { compose.onNodeWithContentDescription(it).performClick() }
        compose.runOnIdle { calls.forEach { assertEquals(1, it) } }
    }

    @Test fun unavailableHistoryAndDefaultAnatomyHaveDisabledActions() {
        compose.setContent {
            MaterialTheme { WorkspaceToolbar(false, false, {}, {}, {}, {}) }
        }
        compose.onNodeWithContentDescription("撤销上一步显示操作").assertIsNotEnabled()
        compose.onNodeWithContentDescription("一键还原组织").assertIsNotEnabled()
        compose.onNodeWithContentDescription("搜索图谱").assertIsEnabled()
        compose.onNodeWithContentDescription("所选项目说明").assertDoesNotExist()
    }
}

package com.zga.meridianatlas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.zga.meridianatlas.ui.WorkspaceActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Only the reusable action component is mounted; no anatomy model or full app is loaded. */
class WorkspaceToolbarDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val labels = listOf("关于", "目录", "撤销", "还原")
    private val descriptions = listOf("关于与数据来源", "图谱目录", "撤销上一步显示操作", "一键还原组织")

    @Test fun circularActionsKeepVerticalOrderAndSeparateTouchTargetsIn320dpWidth() {
        val calls = IntArray(4)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 280.dp).testTag("actions-fixture")) {
                    WorkspaceActions(true, true, { calls[0]++ }, { calls[1]++ }, { calls[2]++ },
                        { calls[3]++ }, modifier = Modifier.align(Alignment.TopEnd))
                }
            }
        }
        val fixture = compose.onNodeWithTag("actions-fixture").fetchSemanticsNode()
        val container = compose.onNodeWithTag("workspace-actions").fetchSemanticsNode()
        val density = fixture.layoutInfo.density.density
        assertEquals("Actions must not reserve a toolbar or top gap", fixture.boundsInRoot.top,
            container.boundsInRoot.top, 1f)
        assertTrue("The action rail must fit the workspace", container.boundsInRoot.right <= fixture.boundsInRoot.right + 1f)
        compose.onAllNodes(hasClickAction()).assertCountEquals(4)
        val targets = descriptions.map { description ->
            compose.onNodeWithContentDescription(description).assertIsDisplayed().assertHasClickAction()
                .fetchSemanticsNode().touchBoundsInRoot
        }
        targets.zipWithNext().forEach { (upper, lower) ->
            assertTrue("Action order must be top to bottom without overlapping touch targets", upper.bottom <= lower.top + 1f)
            assertEquals("All circular actions must share one vertical axis", upper.center.x, lower.center.x, 1f)
        }
        targets.forEach { bounds ->
            assertTrue("Every touch target must fit 320dp width", bounds.left >= fixture.boundsInRoot.left - 1f && bounds.right <= fixture.boundsInRoot.right + 1f)
            assertTrue("Every touch target must fit the fixture height", bounds.top >= fixture.boundsInRoot.top - 1f && bounds.bottom <= fixture.boundsInRoot.bottom + 1f)
        }
        labels.forEach { assertCircleFace(it, density) }
        descriptions.forEach { compose.onNodeWithContentDescription(it).performClick() }
        compose.runOnIdle { calls.forEach { assertEquals(1, it) } }
        compose.onNodeWithTag("workspace-toolbar").assertDoesNotExist()
    }

    @Test fun unavailableHistoryAndDefaultAnatomyHaveDisabledActions() {
        val calls = IntArray(4)
        compose.setContent {
            MaterialTheme {
                WorkspaceActions(false, false, { calls[0]++ }, { calls[1]++ }, { calls[2]++ }, { calls[3]++ })
            }
        }
        compose.onNodeWithContentDescription("撤销上一步显示操作").assertIsNotEnabled()
        compose.onNodeWithContentDescription("一键还原组织").assertIsNotEnabled()
        // Real pointer input must not invoke a disabled action.
        compose.onNodeWithContentDescription("撤销上一步显示操作").performTouchInput { click() }
        compose.onNodeWithContentDescription("一键还原组织").performTouchInput { click() }
        compose.onNodeWithContentDescription("关于与数据来源").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("图谱目录").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, calls[0]); assertEquals(1, calls[1])
            assertEquals(0, calls[2]); assertEquals(0, calls[3])
        }
        compose.onNodeWithContentDescription("搜索图谱").assertDoesNotExist()
        compose.onNodeWithContentDescription("所选项目说明").assertDoesNotExist()
    }

    @Test fun short320dpWorkspaceScrollsEveryCircularActionIntoBounds() {
        val calls = IntArray(4)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 144.dp).testTag("actions-fixture")) {
                    WorkspaceActions(true, true, { calls[0]++ }, { calls[1]++ }, { calls[2]++ },
                        { calls[3]++ }, modifier = Modifier.align(Alignment.TopEnd))
                }
            }
        }
        val fixture = compose.onNodeWithTag("actions-fixture").fetchSemanticsNode()
        val density = fixture.layoutInfo.density.density
        val container = compose.onNodeWithTag("workspace-actions").fetchSemanticsNode().boundsInRoot
        assertTrue("The scroll viewport must fit a short workspace", container.top >= fixture.boundsInRoot.top - 1f && container.bottom <= fixture.boundsInRoot.bottom + 1f)
        descriptions.forEachIndexed { index, description ->
            val node = compose.onNodeWithContentDescription(description).performScrollTo().assertIsDisplayed()
            val semantics = node.fetchSemanticsNode()
            val bounds = semantics.boundsInRoot
            // performScrollTo brings the visual semantics rectangle into view;
            // Material3's larger touch expansion may extend beyond the clipped
            // viewport. Require the whole face and prove real pointer access.
            assertTrue("$description face must become fully visible: face=$bounds fixture=${fixture.boundsInRoot}",
                bounds.left >= fixture.boundsInRoot.left - 1f && bounds.right <= fixture.boundsInRoot.right + 1f &&
                    bounds.top >= fixture.boundsInRoot.top - 1f && bounds.bottom <= fixture.boundsInRoot.bottom + 1f)
            assertEquals("The face must not be clipped vertically", semantics.size.height.toFloat(), bounds.height, 1f)
            assertEquals("The face must not be clipped horizontally", semantics.size.width.toFloat(), bounds.width, 1f)
            assertTrue("Keep the standard 48dp minimum touch target", semantics.touchBoundsInRoot.height >= 48f * density - 1f)
            assertCircleFace(labels[index], density)
            node.performTouchInput { click() }
        }
        compose.runOnIdle { calls.forEach { assertEquals(1, it) } }
    }

    @Test fun sidebarActionsOmitCatalogueAndItsSpacingWithoutLeavingAnEmptySlot() {
        val calls = IntArray(3)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 240.dp).testTag("actions-fixture")) {
                    WorkspaceActions(true, true, { calls[0]++ }, null, { calls[1]++ }, { calls[2]++ },
                        modifier = Modifier.align(Alignment.TopEnd))
                }
            }
        }
        val descriptions = listOf("关于与数据来源", "撤销上一步显示操作", "一键还原组织")
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
        compose.onNodeWithContentDescription("图谱目录").assertDoesNotExist()
        compose.onNodeWithText("目录").assertDoesNotExist()
        compose.onNodeWithTag("workspace-action-face-目录", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索图谱").assertDoesNotExist()
        compose.onNodeWithText("搜索目录").assertDoesNotExist()
        val container = compose.onNodeWithTag("workspace-actions").fetchSemanticsNode()
        val density = container.layoutInfo.density.density
        val targets = descriptions.map {
            compose.onNodeWithContentDescription(it).assertIsDisplayed().assertIsEnabled()
                .fetchSemanticsNode().touchBoundsInRoot
        }
        targets.zipWithNext().forEach { (upper, lower) ->
            assertEquals("Omitted catalogue must leave only the normal 6dp gap", 6f * density,
                lower.top-upper.bottom, 1.5f)
            assertEquals("Remaining actions must share one vertical axis", upper.center.x, lower.center.x, 1f)
        }
        assertEquals("No leading placeholder may remain", container.boundsInRoot.top, targets.first().top, 1f)
        assertEquals("No trailing placeholder may remain", container.boundsInRoot.bottom, targets.last().bottom, 1f)
        descriptions.forEach { compose.onNodeWithContentDescription(it).performClick() }
        compose.runOnIdle { calls.forEach { assertEquals(1, it) } }
    }

    private fun assertCircleFace(label: String, density: Float) {
        val face = compose.onNodeWithTag("workspace-action-face-$label", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("Circular face diameter must remain 36dp", 36f * density, face.size.width.toFloat(), density * .5f)
        assertEquals("Circular face must have equal width and height", face.size.width, face.size.height)
    }
}

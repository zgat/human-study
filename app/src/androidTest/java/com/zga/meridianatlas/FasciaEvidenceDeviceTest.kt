package com.zga.meridianatlas

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.AtlasViewModel
import com.zga.meridianatlas.ui.MeridianAtlasApp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real phone-width controls and shipped evidence; source records are not force-vector validation. */
@RunWith(AndroidJUnit4::class)
class FasciaEvidenceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: AtlasViewModel

    @Before fun ready() {
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[AtlasViewModel::class.java] }
        compose.waitUntil(180_000) { !model.state.value.loading }
        assertNull(model.state.value.error)
        compose.runOnUiThread {
            model.mode(AtlasMode.OVERVIEW)
            model.restore()
            model.resetLayers()
            model.clearSelection()
            model.quality(ModelQuality.LOW)
            compose.activity.setContent {
                Box(Modifier.sizeIn(maxWidth = 420.dp, maxHeight = 760.dp).fillMaxSize()) {
                    MeridianAtlasApp(model)
                }
            }
        }
        compose.waitUntil(180_000) {
            compose.onAllNodesWithText("正在载入真实解剖模型").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("图层控制").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openFascia() {
        compose.onAllNodes(hasText("筋膜解剖") and hasClickAction()).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("筋膜解剖与连接").assertIsDisplayed()
    }

    private fun panelFind(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).performScrollToNode(matcher)
        return compose.onAllNodes(matcher and hasAnyAncestor(isDialog())).onFirst()
    }

    @Test fun defaultCatalogueSearchObservesAnActualFasciaSheetRatherThanSyntheticLines() {
        val data = model.state.value.data!!
        val sheet = data.structureGroups.entries.first { group ->
            group.members.all { it.system == "connective" && it.tissueType == "fascia" }
        }
        openFascia()
        compose.onNodeWithText("解剖结构").assertIsSelected()
        compose.onNodeWithText("文献记录的局部连接").assertDoesNotExist()
        compose.onNodeWithText("搜索筋膜、腱膜、支持带").performTextInput(sheet.representative.nameZh)
        panelFind(hasContentDescription("观察筋膜结构${sheet.id}")).performClick()
        compose.onNodeWithText("筋膜解剖与连接").assertDoesNotExist()
        compose.runOnIdle {
            val state = model.state.value
            assertEquals(sheet.id, state.selectedStructure)
            assertEquals(sheet.id, state.layer.isolateId)
            assertTrue(state.layer.connective)
            assertFalse(state.layer.skin)
            assertEquals(sheet.memberIds, VisibilityPolicy.visibleIds(data.structures, state.layer))
        }
    }

    @Test fun lineKindsAndReferenceMarkersSwitchIndependentlyWithoutLosingTheSelectedPoint() {
        compose.runOnUiThread { model.selectPoint("lu1", reveal = false) }
        openFascia()
        compose.onNodeWithText("文献连接").performClick()
        panelFind(hasContentDescription("全选筋膜文献连接")).performClick()
        panelFind(hasContentDescription("组织范围辅助线（非力线）")).assertIsOff().performClick()
        panelFind(hasContentDescription("显示局部连接示意线")).assertIsOn().performClick()
        panelFind(hasContentDescription("显示连接参考区")).assertIsOn().performClick()
        compose.runOnIdle {
            val state = model.state.value
            assertTrue(state.layer.fasciaChains)
            assertTrue(state.layer.fasciaContext)
            assertFalse(state.layer.fasciaRelations)
            assertFalse(state.layer.fasciaTargets)
            assertEquals(state.data!!.fascialConnections.map { it.id }.toSet(), state.layer.enabledFascialConnections)
            assertEquals("lu1", state.selectedPoint)
            assertEquals("LU", state.selectedPointMeridian)
        }
        panelFind(hasContentDescription("显示局部连接示意线")).performClick()
        compose.runOnIdle {
            assertTrue(model.state.value.layer.fasciaRelations)
            assertTrue(model.state.value.layer.fasciaContext)
            assertFalse(model.state.value.layer.fasciaTargets)
            assertEquals("lu1", model.state.value.selectedPoint)
        }
    }

    @Test fun eachEvidenceStatementRetainsItsStudyConditionsLimitationsAndLinkedSources() {
        val connections = model.state.value.data!!.fascialConnections
        val allowed = setOf("anatomical_continuity", "experimental_loading", "human_indirect_observation")
        connections.forEach { group ->
            assertTrue(group.evidenceReferences.isNotEmpty())
            val sources = group.sourceRefs.map { it.id }.toSet()
            group.evidenceReferences.forEach { evidence ->
                assertTrue(evidence.kind in allowed)
                assertTrue(evidence.sourceIds.isNotEmpty() && sources.containsAll(evidence.sourceIds))
                assertTrue(evidence.scopeZh.isNotBlank() && evidence.sampleZh.isNotBlank())
                assertTrue(evidence.conditionsZh.isNotBlank() && evidence.measurementZh.isNotBlank())
                assertTrue(evidence.limitationsZh.isNotBlank())
            }
            group.paths.filter { it.kind == "anatomical_context_schematic" }.forEach {
                assertTrue(it.evidenceIds.isEmpty())
                assertEquals("组织范围辅助线（非力线）", it.meaningZh)
            }
        }
        openFascia()
        compose.onNodeWithText("文献连接").performClick()
        val evidence = connections.first().evidenceReferences.first()
        panelFind(hasText("逐项证据与条件")).assertIsDisplayed()
        panelFind(hasText(evidence.scopeZh)).assertIsDisplayed()
        panelFind(hasText("条件：${evidence.conditionsZh}")).assertIsDisplayed()
        panelFind(hasText("限制：${evidence.limitationsZh}")).assertIsDisplayed()
        val source = connections.first().sourceRefs.first { it.id in evidence.sourceIds }
        panelFind(hasText(source.title, substring = true)).assertIsDisplayed()
    }
}

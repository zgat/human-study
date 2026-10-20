package com.zga.meridianatlas

import com.zga.meridianatlas.model.*
import org.junit.Assert.*
import org.junit.Test

class CutawayPolicyTest {
    private fun structure(id: String, name: String, system: String, x: Float = 0f) =
        AnatomyStructure(id, id, name, system, "head_neck", 0, id, Vec3(x, 1.6f, .16f))

    private val skin = structure("skin", "skin", "skin")
    private val muscle = structure("muscle", "left orbicularis oculi", "muscle")
    private val facial = listOf("left cornea", "right cornea", "left sclera", "right sclera",
        "left iris", "right iris", "lip", "gingiva of upper jaw", "gingiva of lower jaw")
        .mapIndexed { index, name -> structure("face-$index", name, "organ") }
    private val internal = listOf(
        structure("lens", "left lens", "organ"), structure("retina", "optic part of right retina", "organ"),
        structure("tongue", "tongue", "organ"), structure("lacrimal", "left lacrimal gland", "organ"),
        structure("heart", "heart", "organ"), structure("skull", "left frontal bone", "bone"),
        structure("jaw", "mandible", "bone"), structure("cartilage", "septal nasal cartilage", "bone"))
    private val tooth = structure("tooth", "left lower central secondary incisor tooth", "bone")
    private val chamber = structure("chamber", "anterior chamber of right eyeball", "space")

    @Test fun halfBodyCutsMusclesSkinAndFacialSurfacesWhileKeepingDeepOrgansAndBonesWhole() {
        val parts = listOf(skin, muscle, tooth, chamber) + facial + internal
        assertEquals(setOf(skin.id, muscle.id) + facial.map { it.id }, CutawayPolicy.outerIds(parts))
        assertTrue(CutawayPolicy.outerIds(internal).isEmpty())
        assertFalse(tooth.id in CutawayPolicy.outerIds(parts))
    }

    @Test fun opaqueSkinAllowsFacialSurfacesAndTeethWithoutEnablingTheirLayers() {
        val parts = listOf(skin, muscle, tooth, chamber) + facial + internal
        assertEquals(setOf(skin.id, tooth.id, chamber.id) + facial.map { it.id }, CutawayPolicy.exteriorIds(parts))
        assertTrue(CutawayPolicy.exteriorIds(internal).isEmpty())
        val surface = LayerState(muscle = false, organ = false, bone = false)
        assertEquals(setOf(skin.id) + facial.map { it.id }, VisibilityPolicy.visibleIds(parts, surface))
    }

    @Test fun facialNamesMatchWholeAnatomicalNamesAndTheirCorrectSystem() {
        val parts = listOf(structure("case", "  LEFT CORNEA  ", "organ"),
            structure("lip-bone", "lip", "bone"), structure("near-lip", "lip region", "organ"),
            structure("near-cornea", "tissue near left cornea", "organ"),
            structure("tooth-organ", "tooth", "organ"), structure("toothlike", "toothlike process", "bone"))
        assertEquals(setOf("case"), CutawayPolicy.outerIds(parts))
        assertEquals(setOf("case"), CutawayPolicy.exteriorIds(parts))
        assertTrue(CutawayPolicy.isFacialSurface(parts.first()))
        parts.drop(1).forEach { assertFalse(CutawayPolicy.isFacialSurface(it)) }
    }

    @Test fun policyChoosesRenderableSurfacesRegardlessOfSideCenterOrCrossingTheMidline() {
        val parts = listOf(structure("left", "left cornea", "organ", -.03f),
            structure("right", "right cornea", "organ", .03f), structure("lip", "lip", "organ", 0f))
        assertEquals(parts.map { it.id }.toSet(), CutawayPolicy.outerIds(parts))
        assertEquals(parts.map { it.id }.toSet(), CutawayPolicy.exteriorIds(parts))
    }
}

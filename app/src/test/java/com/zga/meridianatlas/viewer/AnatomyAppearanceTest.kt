package com.zga.meridianatlas.viewer

import org.junit.Assert.*
import org.junit.Test

/** Numerical display policy only: Filament Java does not expose native uniform-value readback. */
class AnatomyAppearanceTest {
    @Test fun tissueColorsAreDistinctLinearDielectricsWithTheirOwnRoughness() {
        val skin = checkNotNull(AnatomyAppearance.forMaterialName("skin"))
        val bone = checkNotNull(AnatomyAppearance.forMaterialName("bone"))
        val connective = checkNotNull(AnatomyAppearance.forMaterialName("connective"))
        assertEquals(.56f, skin.roughness, 0f)
        assertEquals(.58f, bone.roughness, 0f)
        assertEquals(.42f, connective.roughness, 0f)
        for (tissue in listOf(skin, bone, connective)) {
            assertEquals(0f, tissue.metallic, 0f)
            assertTrue(tissue.color().all { it in 0f..1f })
        }
        val s = skin.color(); val b = bone.color(); val c = connective.color()
        assertTrue(s[0] > s[1] && s[1] > s[2])
        assertTrue(b[0] > b[2] && b[2] > .65f)
        assertTrue(c[2] > c[0] && c[1] > c[0])
        // #CB (203 / 255 display RGB) is approximately 0.5972 in linear RGB, not 0.7961.
        assertEquals(.5972018f, s[0], .000001f)
        assertTrue(b[0] - c[0] > .25f)
        assertNull(AnatomyAppearance.forMaterialName("organ"))
        assertNull(AnatomyAppearance.forMaterialName("muscle"))
    }

    @Test fun referenceAlphaAndReturnedColorArraysCannotMutateTheSharedAppearancePolicy() {
        val skin = checkNotNull(AnatomyAppearance.forMaterialName("skin"))
        val reference = skin.color(.055f)
        assertEquals(.055f, reference[3], 0f)
        reference[0] = 0f
        assertEquals(.5972018f, skin.color()[0], .000001f)
        assertEquals(1f, skin.color()[3], 0f)
    }
}

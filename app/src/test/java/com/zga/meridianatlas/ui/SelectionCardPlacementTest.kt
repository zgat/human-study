package com.zga.meridianatlas.ui

import org.junit.Assert.*
import org.junit.Test

class SelectionCardPlacementTest {
    private fun assertSafe(rect: LabelRect?, width: Float, height: Float, cardWidth: Float, cardHeight: Float,
        primary: CardProtectionPoint? = null, obstacles: List<LabelRect> = emptyList(), inset: Float = 0f): LabelRect {
        assertNotNull("A safe measured-card placement must exist", rect)
        val box = checkNotNull(rect)
        assertEquals(cardWidth, box.width, .001f)
        assertEquals(cardHeight, box.height, .001f)
        assertTrue(box.left >= inset && box.top >= inset && box.right <= width - inset && box.bottom <= height - inset)
        for (obstacle in obstacles.filter { it.width > 0f && it.height > 0f }) {
            // Independent half-open rectangle overlap, rather than the production helper.
            assertFalse(box.left < obstacle.right && box.right > obstacle.left && box.top < obstacle.bottom && box.bottom > obstacle.top)
        }
        primary?.let { point ->
            if (point.radius == 0f) {
                assertFalse(point.x >= box.left && point.x < box.right && point.y >= box.top && point.y < box.bottom)
            } else {
                // Independent closest-point circle/rectangle intersection check.
                val dx = maxOf(box.left.toDouble() - point.x, 0.0, point.x.toDouble() - box.right)
                val dy = maxOf(box.top.toDouble() - point.y, 0.0, point.y.toDouble() - box.bottom)
                assertTrue("Card $box must not cover primary $point", dx * dx + dy * dy >= point.radius.toDouble() * point.radius)
            }
        }
        return box
    }

    @Test fun bottomLeftIsPreferredAndNoPrimarySupportsHiddenOrOffscreenSelections() {
        assertEquals(LabelRect(12f, 418f, 332f, 588f),
            placeSelectionCard(400f, 600f, 320f, 170f, inset = 12f))
        val offscreen = CardProtectionPoint(-100f, 900f, 20f)
        assertEquals(placeSelectionCard(400f, 600f, 320f, 170f),
            placeSelectionCard(400f, 600f, 320f, 170f, primary = offscreen))
    }

    @Test fun primaryAndUiObstaclesAreHardEvenWhenEverySoftPointPrefersAnUnsafeCard() {
        val primary = CardProtectionPoint(70f, 470f, 20f)
        val obstacles = listOf(LabelRect(330f, 0f, 400f, 600f), LabelRect(0f, 540f, 400f, 600f),
            LabelRect(0f, 0f, 400f, 60f))
        val soft = List(20) { CardProtectionPoint(160f, 200f, 100f) }
        val box = placeSelectionCard(400f, 600f, 260f, 150f, primary, soft, obstacles, inset = 8f)
        assertSafe(box, 400f, 600f, 260f, 150f, primary, obstacles, 8f)
    }

    @Test fun softPointsCanImproveTheInitialPlacementButCannotDislodgeASafePreviousOne() {
        val original = checkNotNull(placeSelectionCard(400f, 600f, 180f, 100f, inset = 8f))
        val soft = listOf(CardProtectionPoint(original.left + 20f, original.top + 20f, 8f),
            CardProtectionPoint(original.left + 60f, original.top + 60f, 8f))
        val alternative = checkNotNull(placeSelectionCard(400f, 600f, 180f, 100f, softPoints = soft, inset = 8f))
        assertNotEquals(original, alternative)
        repeat(100) { step ->
            val moved = soft.map { it.copy(x = it.x + step * .01f, y = it.y - step * .01f) }
            assertEquals(original, placeSelectionCard(400f, 600f, 180f, 100f,
                softPoints = moved, previousRect = original, inset = 8f))
        }
    }

    @Test fun movingPrimaryOrNewObstacleInvalidatesPreviousWithoutCoveringThePoint() {
        val previous = LabelRect(8f, 392f, 208f, 592f)
        val primary = CardProtectionPoint(80f, 480f, 20f)
        val next = assertSafe(placeSelectionCard(400f, 600f, 200f, 200f, primary = primary,
            previousRect = previous, inset = 8f), 400f, 600f, 200f, 200f, primary, inset = 8f)
        assertNotEquals(previous, next)
        val obstacle = LabelRect(0f, 350f, 230f, 600f)
        val afterObstacle = assertSafe(placeSelectionCard(400f, 600f, 200f, 200f,
            obstacles = listOf(obstacle), previousRect = previous, inset = 8f),
            400f, 600f, 200f, 200f, obstacles = listOf(obstacle), inset = 8f)
        assertNotEquals(previous, afterObstacle)
    }

    @Test fun resizingOrChangingMeasuredHeightDoesNotReuseAStalePreviousRectangle() {
        val previous = LabelRect(12f, 600f, 332f, 760f)
        val resized = assertSafe(placeSelectionCard(360f, 568f, 300f, 160f,
            previousRect = previous, inset = 12f), 360f, 568f, 300f, 160f, inset = 12f)
        assertNotEquals(previous, resized)
        val primary = CardProtectionPoint(160f, 400f, 20f)
        val short = LabelRect(12f, 420f, 332f, 500f)
        // The caller must receive the current measured height, within the resized safe region.
        val taller = assertSafe(placeSelectionCard(400f, 600f, 320f, 180f, primary = primary,
            previousRect = short, inset = 12f), 400f, 600f, 320f, 180f, primary, inset = 12f)
        assertNotEquals(short, taller)
    }

    @Test fun edgeContactAndCircleTangencyAreAllowedWhileInteriorOverlapIsRejected() {
        val obstacle = LabelRect(0f, 0f, 100f, 100f)
        val exact = placeSelectionCard(200f, 100f, 100f, 100f, obstacles = listOf(obstacle))
        assertEquals(LabelRect(100f, 0f, 200f, 100f), exact)
        val tangent = CardProtectionPoint(80f, 50f, 20f)
        assertEquals(exact, placeSelectionCard(200f, 100f, 100f, 100f,
            primary = tangent, obstacles = listOf(obstacle)))
        assertNull(placeSelectionCard(200f, 100f, 100f, 100f,
            primary = tangent.copy(x = 80.1f), obstacles = listOf(obstacle)))
        // A corner touching a circle is not the same as intersection of their bounding boxes.
        val diagonal = CardProtectionPoint(80f, 80f, 28f)
        assertEquals(LabelRect(100f, 100f, 200f, 200f), placeSelectionCard(200f, 200f, 100f, 100f,
            primary = diagonal, obstacles = listOf(LabelRect(0f, 0f, 100f, 200f), LabelRect(100f, 0f, 200f, 100f))))
    }

    @Test fun zeroRadiusPointUsesHalfOpenEdgesAndEmptyObstaclesDoNotBlock() {
        val full = LabelRect(0f, 0f, 100f, 100f)
        assertEquals(full, placeSelectionCard(100f, 100f, 100f, 100f,
            primary = CardProtectionPoint(100f, 50f, 0f), obstacles = listOf(LabelRect(40f, 0f, 40f, 100f))))
        assertNull(placeSelectionCard(100f, 100f, 100f, 100f, primary = CardProtectionPoint(0f, 50f, 0f)))
        assertNull(placeSelectionCard(100f, 100f, 100f, 100f, primary = CardProtectionPoint(50f, 0f, 0f)))
    }

    @Test fun subpixelTangencyIsRecheckedAtTheActualWholePixelPlacement() {
        val primary = CardProtectionPoint(80.25f, 50f, 20f)
        val obstacles = listOf(LabelRect(0f, 0f, 100f, 100f))
        val placed = assertSafe(placeSelectionCard(220f, 100f, 100f, 100f, primary,
            obstacles = obstacles), 220f, 100f, 100f, 100f, primary, obstacles)
        assertEquals(101f, placed.left, 0f)
        assertEquals(placed.left.toInt().toFloat(), placed.left, 0f)
        assertEquals(placed.top.toInt().toFloat(), placed.top, 0f)
        assertNull(placeSelectionCard(200.25f, 100f, 100f, 100f, primary, obstacles = obstacles))
    }

    @Test fun fractionalPreviousTangencyCannotBeRoundedOntoThePrimaryAndSafeIntegerHistoryStaysStable() {
        val primary = CardProtectionPoint(80.25f, 50f, 20f)
        val fractional = LabelRect(100.25f, 0f, 200.25f, 100f)
        val placed = assertSafe(placeSelectionCard(220f, 100f, 100f, 100f, primary,
            previousRect = fractional), 220f, 100f, 100f, 100f, primary)
        assertEquals(LabelRect(101f, 0f, 201f, 100f), placed)
        assertEquals(placed, placeSelectionCard(220f, 100f, 100f, 100f,
            primary.copy(x = 80.75f), previousRect = placed))
        val moved = assertSafe(placeSelectionCard(220f, 100f, 100f, 100f,
            primary.copy(x = 81.25f), previousRect = placed),
            220f, 100f, 100f, 100f, primary.copy(x = 81.25f))
        assertEquals(102f, moved.left, 0f)
    }

    @Test fun obstacleCutLinesFindSafeInteriorSlotsWhenAllViewportCornersAreBlocked() {
        val obstacles = listOf(LabelRect(0f, 0f, 140f, 500f), LabelRect(360f, 0f, 500f, 500f),
            LabelRect(140f, 0f, 360f, 180f))
        val result = assertSafe(placeSelectionCard(500f, 500f, 200f, 150f, obstacles = obstacles),
            500f, 500f, 200f, 150f, obstacles = obstacles)
        assertEquals(140f, result.left, 0f)
        assertEquals(350f, result.top, 0f)
    }

    @Test fun callerCanTryMeasuredFullCompactAndMinimalCardsWithAnExplicitNoFitResult() {
        val primary = CardProtectionPoint(160f, 284f, 20f)
        val obstacles = listOf(LabelRect(0f, 0f, 320f, 180f), LabelRect(0f, 350f, 320f, 568f),
            LabelRect(280f, 180f, 320f, 350f))
        assertNull(placeSelectionCard(320f, 568f, 280f, 180f, primary, obstacles = obstacles, inset = 8f))
        assertNull(placeSelectionCard(320f, 568f, 240f, 120f, primary, obstacles = obstacles, inset = 8f))
        assertSafe(placeSelectionCard(320f, 568f, 120f, 48f, primary, obstacles = obstacles, inset = 8f),
            320f, 568f, 120f, 48f, primary, obstacles, 8f)
        assertNull(placeSelectionCard(320f, 568f, 300f, 80f,
            obstacles = listOf(LabelRect(0f, 0f, 320f, 568f))))
        assertNull(placeSelectionCard(320f, 568f, 321f, 1f))
        assertNull(placeSelectionCard(0f, 568f, 1f, 1f))
    }

    @Test fun fullCompactAndMinimalCardSizesStaySafeAcrossAllViewportEdgesAndCorners() {
        data class Viewport(val width: Float, val height: Float)
        val viewports = listOf(Viewport(320f, 568f), Viewport(420f, 760f), Viewport(760f, 320f),
            Viewport(900f, 1000f))
        val cardSizes = listOf(260f to 170f, 220f to 110f, 120f to 48f)
        var checked = 0
        for ((w, h) in viewports) for ((cw, ch) in cardSizes) {
            val obstacles = listOf(LabelRect(w - 48f, 36f, w - 8f, h - 64f),
                LabelRect(8f, h - 48f, 140f, h - 8f), LabelRect(8f, 8f, w - 8f, 36f))
            val soft = listOf(CardProtectionPoint(w * .5f, h * .5f, 12f),
                CardProtectionPoint(w * .4f, h * .65f, 10f), CardProtectionPoint(w * .6f, h * .3f, 10f))
            for (step in 0..40) {
                val fraction = step / 40f
                val edgePoints = listOf(0f to h * fraction, w to h * fraction,
                    w * fraction to 0f, w * fraction to h)
                for ((x, y) in edgePoints) {
                    val primary = CardProtectionPoint(x, y, 20f)
                    val result = placeSelectionCard(w, h, cw, ch, primary, soft, obstacles, inset = 8f)
                    // If a large landscape card has no safe slot, null is the required result.
                    if (result != null) assertSafe(result, w, h, cw, ch, primary, obstacles, 8f)
                    else assertFalse("Do not lose feasible full/compact phone slots", w <= 420f && ch <= 110f)
                    checked++
                }
            }
        }
        assertEquals(1968, checked)
    }

    @Test fun aDenseSmallViewportSweepAgreesWithAnIndependentFeasibilityOracle() {
        // Every integer translation is exhaustively checked. Inputs and obstacle edges are
        // integer-valued, so any safe grid slot must be discoverable by the boundary candidates.
        val w = 32f; val h = 24f; val cw = 11f; val ch = 7f; val inset = 1f
        val obstacles = listOf(LabelRect(1f, 1f, 8f, 9f), LabelRect(25f, 2f, 31f, 22f),
            LabelRect(1f, 20f, 18f, 23f))
        for (px in -2..34 step 2) for (py in -2..26 step 2) {
            val primary = CardProtectionPoint(px.toFloat(), py.toFloat(), 4f)
            val oracle = (1..(w.toInt() - cw.toInt() - 1)).any { x ->
                (1..(h.toInt() - ch.toInt() - 1)).any { y ->
                    val l = x.toFloat(); val t = y.toFloat(); val r = l + cw; val b = t + ch
                    val obstacleFree = obstacles.none { l < it.right && r > it.left && t < it.bottom && b > it.top }
                    val dx = maxOf(l - primary.x, 0f, primary.x - r).toDouble()
                    val dy = maxOf(t - primary.y, 0f, primary.y - b).toDouble()
                    obstacleFree && dx * dx + dy * dy >= 16.0
                }
            }
            val result = placeSelectionCard(w, h, cw, ch, primary, obstacles = obstacles, inset = inset)
            if (oracle) assertSafe(result, w, h, cw, ch, primary, obstacles, inset)
            else if (result != null) assertSafe(result, w, h, cw, ch, primary, obstacles, inset)
        }
    }

    @Test fun invalidNonFiniteOrNegativeGeometryIsRejectedBeforeScoring() {
        fun rejects(action: () -> Unit) {
            try { action(); fail("Invalid geometry must be rejected") } catch (_: IllegalArgumentException) { }
        }
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            rejects { placeSelectionCard(invalid, 600f, 200f, 100f) }
            rejects { placeSelectionCard(400f, 600f, 200f, 100f, primary = CardProtectionPoint(invalid, 10f, 20f)) }
            rejects { placeSelectionCard(400f, 600f, 200f, 100f, softPoints = listOf(CardProtectionPoint(10f, invalid, 20f))) }
            rejects { placeSelectionCard(400f, 600f, 200f, 100f, obstacles = listOf(LabelRect(0f, 0f, invalid, 20f))) }
            rejects { placeSelectionCard(400f, 600f, 200f, 100f, previousRect = LabelRect(0f, invalid, 200f, 100f)) }
        }
        rejects { placeSelectionCard(400f, 600f, 0f, 100f) }
        rejects { placeSelectionCard(400f, 600f, 200f, 100f, inset = -1f) }
        rejects { placeSelectionCard(400f, 600f, 200f, 100f, primary = CardProtectionPoint(10f, 10f, -1f)) }
        rejects { placeSelectionCard(400f, 600f, 200f, 100f, obstacles = listOf(LabelRect(20f, 0f, 10f, 100f))) }
    }
}

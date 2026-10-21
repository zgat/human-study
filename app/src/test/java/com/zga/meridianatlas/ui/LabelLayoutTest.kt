package com.zga.meridianatlas.ui

import org.junit.Assert.*
import org.junit.Test

class LabelLayoutTest {
    private fun place(x: Float = 200f, y: Float = 200f, width: Float = 60f, height: Float = 24f,
        viewportWidth: Float = 400f, viewportHeight: Float = 600f, exclusion: LabelRect? = null) =
        placeSelectedLabel(x, y, width, height, viewportWidth, viewportHeight, 10f, 4f, exclusion)

    @Test fun aSelectedNameHasOneStableInstanceAndNeverFallsBackToTheOtherSide() {
        val points = listOf(OverlayPoint("point:lu1|0", "lu1", "中府", 0L),
            OverlayPoint("point:lu1|1", "lu1", "中府", 0L),
            OverlayPoint("point:lu2|0", "lu2", "云门", 0L))
        assertEquals("point:lu1|1", selectedNameAnchorId(points, "lu1", "point:lu1|1"))
        assertEquals("point:lu1|0", selectedNameAnchorId(points, "lu1", null))
        assertNull(selectedNameAnchorId(points, "lu1", "point:lu2|0"))
        assertNull(selectedNameAnchorId(points, null, "point:lu1|0"))
        // Losing the selected side must not silently move its name onto another body position.
        assertNull(selectedNameAnchorId(points.filterNot { it.anchorId == "point:lu1|1" }, "lu1", "point:lu1|1"))
    }

    @Test fun rightIsPreferredAndTheLeaderEndsOnItsNearestBoundary() {
        val placement = place()
        assertEquals(LabelRect(210f, 188f, 270f, 212f), placement.box)
        assertEquals(210f, placement.leaderX, 0f)
        assertEquals(200f, placement.leaderY, 0f)
        assertEquals(placement, place())
    }

    @Test fun aCardBlockingTheRightSlotUsesTheLeftSlotWithoutDroppingTheName() {
        val card = LabelRect(205f, 100f, 395f, 300f)
        val placement = place(exclusion = card)
        assertEquals(LabelRect(130f, 188f, 190f, 212f), placement.box)
        assertFalse(placement.box.intersects(card))
        assertEquals(190f, placement.leaderX, 0f)
    }

    @Test fun aboveAndBelowAreAvailableWhenBothSideSlotsAreBlocked() {
        val sideCard = LabelRect(125f, 190f, 275f, 210f)
        assertEquals(LabelRect(170f, 166f, 230f, 190f), place(exclusion = sideCard).box)
        val aboveAlsoBlocked = LabelRect(125f, 160f, 275f, 205f)
        assertEquals(LabelRect(170f, 210f, 230f, 234f), place(exclusion = aboveAlsoBlocked).box)
    }

    @Test fun anAnchorBehindTheCardUsesTheNearestVisibleSpaceAndAConnectingLeader() {
        val card = LabelRect(20f, 260f, 380f, 580f)
        val placement = place(x = 200f, y = 420f, exclusion = card)
        assertEquals(226f, placement.box.top, 0f)
        assertEquals(250f, placement.box.bottom, 0f)
        assertFalse(placement.box.intersects(card))
        assertEquals(200f, placement.leaderX, 0f)
        assertEquals(250f, placement.leaderY, 0f)
    }

    @Test fun anAvailablePreviousSlotSurvivesBoundaryChangesInsteadOfSwitchingBackAndForth() {
        val blockedRight = LabelRect(205f, 100f, 395f, 300f)
        val first = place(exclusion = blockedRight)
        assertEquals(LabelSlot.LEFT, first.slot)
        val rightNowAvailable = LabelRect(275f, 100f, 395f, 300f)
        val next = placeSelectedLabel(200f, 200f, 60f, 24f, 400f, 600f, 10f, 4f,
            rightNowAvailable, first.slot)
        assertEquals(first.box, next.box)
        assertEquals(LabelSlot.LEFT, next.slot)
        // A new selected anchor starts with no remembered slot and can prefer its nearest right slot.
        assertEquals(LabelSlot.RIGHT, place(exclusion = rightNowAvailable).slot)
    }

    @Test fun screenEdgesAndOversizedNamesStayInsideTheViewport() {
        for ((x, y) in listOf(0f to 0f, 400f to 0f, 0f to 600f, 400f to 600f, -100f to 900f)) {
            val placement = place(x, y)
            assertInViewport(placement.box, 400f, 600f)
        }
        val tiny = place(width = 100f, height = 40f, viewportWidth = 30f, viewportHeight = 15f)
        assertInViewport(tiny.box, 30f, 15f)
        assertTrue(tiny.box.width > 0f && tiny.box.height > 0f)
    }

    @Test fun aRememberedRightSlotMovesLeftBeforeClampingItsNameOverTheEdgeAnchor() {
        val placement = placeSelectedLabel(395f, 200f, 60f, 24f, 400f, 600f, 10f, 4f,
            preferredSlot = LabelSlot.RIGHT)
        assertEquals(LabelSlot.LEFT, placement.slot)
        assertEquals(LabelRect(325f, 188f, 385f, 212f), placement.box)
        assertDoesNotCoverAnchor(placement.box, 395f, 200f)
    }

    @Test fun visibleAnchorsNearEveryViewportEdgeStayOutsideTheirNameBoxes() {
        for ((x, y) in listOf(0f to 0f, 400f to 0f, 0f to 600f, 400f to 600f,
            5f to 5f, 395f to 5f, 5f to 595f, 395f to 595f,
            1f to 300f, 399f to 300f, 200f to 1f, 200f to 599f)) {
            val placement = place(x, y)
            assertInViewport(placement.box, 400f, 600f)
            assertDoesNotCoverAnchor(placement.box, x, y)
        }
    }

    @Test fun cardBoundaryCrossingsNeverRemoveOrCoverASelectedNameWhenSpaceIsAvailable() {
        val card = LabelRect(20f, 360f, 380f, 580f)
        for (step in 0..200) {
            val placement = place(x = 190f + step / 10f, y = 310f + step / 2f, exclusion = card)
            assertInViewport(placement.box, 400f, 600f)
            assertFalse(placement.box.intersects(card))
        }
    }

    @Test fun impossibleCardCoverageStillReturnsAVisibleDeterministicFallback() {
        val card = LabelRect(0f, 0f, 400f, 600f)
        val placement = place(exclusion = card)
        assertInViewport(placement.box, 400f, 600f)
        assertEquals(placement, place(exclusion = card))
    }

    private fun assertInViewport(box: LabelRect, width: Float, height: Float) {
        assertTrue(box.left >= 0f && box.top >= 0f)
        assertTrue(box.right <= width && box.bottom <= height)
    }

    private fun assertDoesNotCoverAnchor(box: LabelRect, x: Float, y: Float) {
        assertFalse("Name box $box must leave the selected point ($x, $y) visible",
            x >= box.left && x <= box.right && y >= box.top && y <= box.bottom)
    }
}

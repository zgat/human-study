package com.zga.meridianatlas.ui

/** Pixel geometry independent of Compose, Android Paint and the camera renderer. */
internal data class LabelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun intersects(other: LabelRect): Boolean = left < other.right && right > other.left &&
        top < other.bottom && bottom > other.top
}

internal enum class LabelSlot { RIGHT, LEFT, ABOVE, BELOW }

internal data class LabelPlacement(val box: LabelRect, val leaderX: Float, val leaderY: Float,
    val slot: LabelSlot = LabelSlot.RIGHT)

/**
 * Retains a feasible previous side, then tries right, left, above and below the anchor before
 * using the nearest space outside the card.
 * The label always fits the viewport. If the card leaves no space large enough, the least-overlap
 * placement remains visible instead of dropping the selected name entirely.
 */
internal fun placeSelectedLabel(
    anchorX: Float,
    anchorY: Float,
    width: Float,
    height: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    gap: Float,
    inset: Float,
    exclusion: LabelRect? = null,
    preferredSlot: LabelSlot? = null,
): LabelPlacement {
    require(listOf(anchorX, anchorY, width, height, viewportWidth, viewportHeight, gap, inset).all { it.isFinite() })
    require(width > 0f && height > 0f && viewportWidth > 0f && viewportHeight > 0f)
    require(gap >= 0f && inset >= 0f)
    require(exclusion == null || listOf(exclusion.left, exclusion.top, exclusion.right, exclusion.bottom).all { it.isFinite() })
    require(exclusion == null || exclusion.left <= exclusion.right && exclusion.top <= exclusion.bottom)

    val marginX = inset.coerceAtMost(viewportWidth / 4f)
    val marginY = inset.coerceAtMost(viewportHeight / 4f)
    val labelWidth = width.coerceAtMost(viewportWidth - 2f * marginX)
    val labelHeight = height.coerceAtMost(viewportHeight - 2f * marginY)
    val maxX = (viewportWidth - marginX - labelWidth).coerceAtLeast(marginX)
    val maxY = (viewportHeight - marginY - labelHeight).coerceAtLeast(marginY)
    fun box(x: Float, y: Float): LabelRect {
        val left = x.coerceIn(marginX, maxX)
        val top = y.coerceIn(marginY, maxY)
        return LabelRect(left, top, left + labelWidth, top + labelHeight)
    }
    fun placement(candidate: Pair<LabelSlot, LabelRect>): LabelPlacement {
        val (slot, box) = candidate
        return LabelPlacement(box, anchorX.coerceIn(box.left, box.right),
            anchorY.coerceIn(box.top, box.bottom), slot)
    }
    fun squaredDistance(box: LabelRect): Double {
        val dx = anchorX.toDouble() - anchorX.coerceIn(box.left, box.right)
        val dy = anchorY.toDouble() - anchorY.coerceIn(box.top, box.bottom)
        return dx * dx + dy * dy
    }
    val nearby = listOf(
        LabelSlot.RIGHT to box(anchorX + gap, anchorY - labelHeight / 2f),
        LabelSlot.LEFT to box(anchorX - gap - labelWidth, anchorY - labelHeight / 2f),
        LabelSlot.ABOVE to box(anchorX - labelWidth / 2f, anchorY - gap - labelHeight),
        LabelSlot.BELOW to box(anchorX - labelWidth / 2f, anchorY + gap),
    ).distinct()
    val obstacle = exclusion?.takeIf { it.width > 0f && it.height > 0f }
    val outsideCard = obstacle?.let { card -> listOf(
        LabelSlot.LEFT to box(card.left - gap - labelWidth, anchorY - labelHeight / 2f),
        LabelSlot.RIGHT to box(card.right + gap, anchorY - labelHeight / 2f),
        LabelSlot.ABOVE to box(anchorX - labelWidth / 2f, card.top - gap - labelHeight),
        LabelSlot.BELOW to box(anchorX - labelWidth / 2f, card.bottom + gap),
    ).distinct() }.orEmpty()
    val clearance = (gap - .01f).coerceAtLeast(0f).toDouble()
    fun containsAnchor(box: LabelRect) = anchorX in box.left..box.right && anchorY in box.top..box.bottom
    fun available(candidate: Pair<LabelSlot, LabelRect>): Boolean {
        val box = candidate.second
        // Clamping a nominal right/left slot at the viewport edge can put it over the point.
        // Prefer another slot before sacrificing the selected marker's clearance.
        return !containsAnchor(box) && squaredDistance(box) >= clearance * clearance &&
            (obstacle == null || !box.intersects(obstacle))
    }
    // Keep a feasible previous side rather than switching back at every card/viewport boundary.
    preferredSlot?.let { slot -> (nearby + outsideCard).firstOrNull { it.first == slot && available(it) }
        ?.let { return placement(it) } }
    nearby.firstOrNull(::available)?.let { return placement(it) }
    outsideCard.filter(::available).minByOrNull { squaredDistance(it.second) }?.let { return placement(it) }

    fun overlapArea(box: LabelRect): Double = obstacle?.let { card ->
        (minOf(box.right, card.right) - maxOf(box.left, card.left)).coerceAtLeast(0f).toDouble() *
            (minOf(box.bottom, card.bottom) - maxOf(box.top, card.top)).coerceAtLeast(0f)
    } ?: 0.0
    return placement((nearby + outsideCard).minWith(compareBy<Pair<LabelSlot, LabelRect>>(
        { overlapArea(it.second) }, { containsAnchor(it.second) }, { squaredDistance(it.second) })))
}

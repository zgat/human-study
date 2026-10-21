package com.zga.meridianatlas.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** A viewport-local pixel circle. The caller includes any desired visual clearance in radius. */
internal data class CardProtectionPoint(val x: Float, val y: Float, val radius: Float)

/**
 * Positions a measured card without changing the camera, anchors, or card dimensions.
 *
 * The primary circle, nonempty UI obstacles, and viewport inset are hard constraints. Other
 * points are soft preferences only. A still-safe previous position wins before soft scoring,
 * so small projection changes cannot make the card alternate between otherwise safe slots.
 * A null result means this measured size has no safe position; the caller may measure a more
 * compact card and try again. This function never returns a least-overlap hard-constraint fallback.
 *
 * Rectangles are half-open. Positive-radius circle tangency and rectangle edge contact are
 * allowed; radius zero protects the point using half-open rectangle containment.
 * Returned left/top coordinates are whole pixels, matching Compose Placeable placement;
 * rounding a subpixel tangent position afterwards could otherwise violate the hard constraint.
 */
internal fun placeSelectionCard(
    viewportWidth: Float,
    viewportHeight: Float,
    cardWidth: Float,
    cardHeight: Float,
    primary: CardProtectionPoint? = null,
    softPoints: List<CardProtectionPoint> = emptyList(),
    obstacles: List<LabelRect> = emptyList(),
    previousRect: LabelRect? = null,
    inset: Float = 0f,
): LabelRect? {
    require(listOf(viewportWidth, viewportHeight, cardWidth, cardHeight, inset).all { it.isFinite() })
    require(viewportWidth >= 0f && viewportHeight >= 0f && cardWidth > 0f && cardHeight > 0f && inset >= 0f)
    fun validatePoint(point: CardProtectionPoint) {
        require(point.x.isFinite() && point.y.isFinite() && point.radius.isFinite() && point.radius >= 0f)
    }
    fun validateRect(rect: LabelRect) {
        require(listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() })
        require(rect.left <= rect.right && rect.top <= rect.bottom)
    }
    primary?.let(::validatePoint)
    softPoints.forEach(::validatePoint)
    obstacles.forEach(::validateRect)
    previousRect?.let(::validateRect)
    val hardObstacles = obstacles.filter { it.width > 0f && it.height > 0f }
    val minX = ceil(inset.toDouble())
    val minY = ceil(inset.toDouble())
    val maxX = floor(viewportWidth.toDouble() - inset - cardWidth)
    val maxY = floor(viewportHeight.toDouble() - inset - cardHeight)
    if (maxX < minX || maxY < minY) return null

    fun rectangle(x: Float, y: Float) = LabelRect(x, y, x + cardWidth, y + cardHeight)
    fun squaredDistance(rect: LabelRect, point: CardProtectionPoint): Double {
        val x = point.x.toDouble()
        val y = point.y.toDouble()
        val dx = x - x.coerceIn(rect.left.toDouble(), rect.right.toDouble())
        val dy = y - y.coerceIn(rect.top.toDouble(), rect.bottom.toDouble())
        return dx * dx + dy * dy
    }
    fun covers(rect: LabelRect, point: CardProtectionPoint): Boolean = if (point.radius == 0f) {
        point.x >= rect.left && point.x < rect.right && point.y >= rect.top && point.y < rect.bottom
    } else squaredDistance(rect, point) < point.radius.toDouble() * point.radius
    fun safe(rect: LabelRect): Boolean = rect.left >= inset && rect.top >= inset &&
        rect.right <= viewportWidth - inset && rect.bottom <= viewportHeight - inset &&
        rect.width > 0f && rect.height > 0f &&
        (primary == null || !covers(rect, primary)) && hardObstacles.none { rect.intersects(it) }

    // Reconstruct using this measurement before checking safety: a rounding-sized dimension
    // difference must not allow a newly taller/wider card to cover the protected point.
    previousRect?.takeIf { abs(it.width - cardWidth) <= .01f && abs(it.height - cardHeight) <= .01f &&
        it.left.toDouble() == floor(it.left.toDouble()) && it.top.toDouble() == floor(it.top.toDouble()) }
        ?.let { rectangle(it.left, it.top) }?.takeIf(::safe)?.let { return it }

    val xs = linkedSetOf(minX, maxX)
    val ys = linkedSetOf(minY, maxY)
    fun cutX(value: Double) { xs += value.coerceIn(minX, maxX) }
    fun cutY(value: Double) { ys += value.coerceIn(minY, maxY) }
    for (obstacle in hardObstacles) {
        cutX(obstacle.left.toDouble() - cardWidth)
        cutX(obstacle.right.toDouble())
        cutY(obstacle.top.toDouble() - cardHeight)
        cutY(obstacle.bottom.toDouble())
    }
    primary?.let { point ->
        cutX(point.x.toDouble() - point.radius - cardWidth)
        cutX(point.x.toDouble() + point.radius)
        cutY(point.y.toDouble() - point.radius - cardHeight)
        cutY(point.y.toDouble() + point.radius)
    }
    val candidates = linkedSetOf<LabelRect>()
    fun candidate(x: Double, y: Double) {
        val clampedX = x.coerceIn(minX, maxX)
        val clampedY = y.coerceIn(minY, maxY)
        for (pixelX in listOf(floor(clampedX), ceil(clampedX))) {
            for (pixelY in listOf(floor(clampedY), ceil(clampedY))) {
                val rect = rectangle(pixelX.toFloat(), pixelY.toFloat())
                if (safe(rect)) candidates += rect
            }
        }
    }
    // The hard-obstacle cut lines partition the feasible translation space into rectangles.
    // Their cross-product also finds spaces between obstacles rather than checking only four
    // corners. A single convex primary exclusion cannot hide every corner of a feasible cell
    // while leaving an interior placement outside it.
    for (x in xs) for (y in ys) candidate(x, y)
    // Soft cut lines improve scoring without making the full X/Y grid quadratic in soft-point
    // count. Every soft point still contributes to every candidate's score.
    for (point in softPoints) {
        for (x in listOf(point.x.toDouble() - point.radius - cardWidth, point.x.toDouble() + point.radius)) {
            for (y in ys) candidate(x, y)
        }
        for (y in listOf(point.y.toDouble() - point.radius - cardHeight, point.y.toDouble() + point.radius)) {
            for (x in xs) candidate(x, y)
        }
    }
    data class Scored(val rect: LabelRect, val coveredPoints: Int, val penetration: Double, val preferredDistance: Double)
    fun score(rect: LabelRect): Scored {
        var covered = 0
        var penetration = 0.0
        for (point in softPoints) if (covers(rect, point)) {
            covered++
            val radiusSquared = point.radius.toDouble() * point.radius
            penetration += if (radiusSquared == 0.0) 1.0 else 1.0 - squaredDistance(rect, point) / radiusSquared
        }
        val dx = rect.left.toDouble() - minX
        val dy = maxY - rect.top.toDouble()
        return Scored(rect, covered, penetration, dx * dx + dy * dy)
    }
    return candidates.map(::score).minWithOrNull(compareBy<Scored> { it.coveredPoints }
        .thenBy { it.penetration }.thenBy { it.preferredDistance }
        .thenByDescending { it.rect.top }.thenBy { it.rect.left })?.rect
}

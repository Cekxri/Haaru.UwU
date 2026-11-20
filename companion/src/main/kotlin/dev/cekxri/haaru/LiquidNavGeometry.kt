package dev.cekxri.haaru

import kotlin.math.abs
import kotlin.math.roundToInt

/** Pointer geometry stays independent of rendering and never performs a root operation. */
internal fun liquidLogicalPosition(x: Float, width: Float, count: Int, rtl: Boolean): Float {
    if (width <= 0f || count <= 1) return 0f
    val logicalX = if (rtl) width - x else x
    val raw = logicalX * count / width - .5f
    val bounded = raw.coerceIn(0f, (count - 1).toFloat())
    val excess = raw - bounded
    return bounded + .11f * excess / (.11f + abs(excess))
}

internal fun liquidReleaseIndex(position: Float, count: Int): Int =
    position.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))

internal fun liquidPhysicalPosition(position: Float, count: Int, rtl: Boolean): Float =
    if (rtl) count - 1f - position else position

internal fun liquidCanCommit(y: Float, height: Float, tolerance: Float): Boolean =
    y >= -tolerance && y <= height + tolerance

internal fun liquidDragDestination(position: Float, current: Int, count: Int, cancelled: Boolean,
                                   y: Float, height: Float, tolerance: Float): Int =
    if (cancelled || !liquidCanCommit(y, height, tolerance)) current.coerceIn(0, (count - 1).coerceAtLeast(0))
    else liquidReleaseIndex(position, count)

internal data class LiquidScale(val x: Float, val y: Float)

/** Reference velocity deformation stays bounded even after a fast fling. */
internal fun liquidLensScale(baseX: Float, baseY: Float, velocity: Float, reduced: Boolean): LiquidScale {
    if (reduced) return LiquidScale(1f, 1f)
    val speed = if (velocity.isFinite()) velocity / 10f else 0f
    return LiquidScale(baseX / (1f - (speed * .75f).coerceIn(-.2f, .2f)),
        baseY * (1f - (speed * .25f).coerceIn(-.2f, .2f)))
}

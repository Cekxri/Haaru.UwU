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

internal data class LiquidPose(val scaleX: Float, val scaleY: Float, val lift: Float, val lightAngle: Float)

internal fun liquidPose(position: Float, target: Float, pressure: Float, reduced: Boolean): LiquidPose {
    if (reduced) return LiquidPose(1f, 1f, 0f, 40f)
    val lag = (target - position).coerceIn(-1f, 1f)
    val press = pressure.coerceIn(0f, 1f)
    val stretch = .11f * abs(lag)
    return LiquidPose(1f + stretch + .028f * press,
        1f - stretch * .42f + .085f * press, -2.1f * press,
        40f + 25f * lag + 7f * (position - 1f).coerceIn(-1f, 1f))
}

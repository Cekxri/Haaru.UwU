/* SPDX-License-Identifier: Apache-2.0
 * Adapted from Kyant0/AndroidLiquidGlass catalogue DampedDragAnimation,
 * commit eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5. See NOTICE.
 */
package dev.cekxri.haaru

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The reference's five springs, with cancellation and an immediate reduced-motion path. */
internal class HaaruDragAnimation(
    parentScope: CoroutineScope,
    initialValue: Float,
    private val valueRange: ClosedRange<Float>,
    private val reduced: Boolean,
    private val pressedScale: Float,
    private val onDragStarted: (Offset) -> Unit,
    private val onDragStopped: HaaruDragAnimation.(cancelled: Boolean, position: Offset) -> Unit,
    private val onDrag: HaaruDragAnimation.(IntSize, Offset) -> Unit
) {
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val valueSpec = spring<Float>(1f, 1000f, .001f)
    private val velocitySpec = spring<Float>(.5f, 300f, .01f)
    private val pressureSpec = spring<Float>(1f, 1000f, .001f)
    private val scaleXSpec = spring<Float>(.6f, 250f, .001f)
    private val scaleYSpec = spring<Float>(.7f, 250f, .001f)
    private val valueAnimation = Animatable(initialValue, .001f)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressureAnimation = Animatable(0f, .001f)
    private val scaleXAnimation = Animatable(1f, .001f)
    private val scaleYAnimation = Animatable(1f, .001f)
    private val transitionMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()
    private var intendedValue by mutableFloatStateOf(initialValue)
    private var releaseJob: Job? = null
    private var transitionJob: Job? = null
    private var pressureJob: Job? = null
    private var lastPosition = Offset.Zero

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = intendedValue
    val pressProgress: Float get() = if (reduced) 0f else pressureAnimation.value
    val scaleX: Float get() = if (reduced) 1f else scaleXAnimation.value
    val scaleY: Float get() = if (reduced) 1f else scaleYAnimation.value
    val velocity: Float get() = if (reduced) 0f else velocityAnimation.value

    val modifier = Modifier.pointerInput(this) {
        inspectGlassDragGestures(
            onDragStart = {
                lastPosition = it.position
                transitionJob?.cancel()
                onDragStarted(it.position)
                press()
            },
            onDragEnd = {
                lastPosition = it.position
                onDragStopped(false, it.position)
                release()
            },
            onDragCancel = {
                onDragStopped(true, lastPosition)
                release()
            },
            onDrag = { change, amount ->
                lastPosition = change.position
                onDrag(size, amount)
            }
        )
    }

    fun press() {
        releaseJob?.cancel()
        pressureJob?.cancel()
        velocityTracker.resetTracking()
        if (reduced) return
        pressureJob = scope.launch {
            launch { pressureAnimation.animateTo(1f, pressureSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYSpec) }
        }
    }

    private fun release() {
        releaseJob?.cancel()
        if (reduced) return
        releaseJob = scope.launch {
            withFrameNanos { }
            val threshold = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1f) * .025f
            snapshotFlow { abs(valueAnimation.value - intendedValue) }.first { it < threshold }
            pressureJob?.cancel()
            launch { pressureAnimation.animateTo(0f, pressureSpec) }
            launch { scaleXAnimation.animateTo(1f, scaleXSpec) }
            launch { scaleYAnimation.animateTo(1f, scaleYSpec) }
        }
    }

    fun updateValue(value: Float) {
        intendedValue = value.coerceIn(valueRange)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (reduced) valueAnimation.snapTo(intendedValue)
            else valueAnimation.animateTo(intendedValue, valueSpec) { updateVelocity() }
        }
    }

    fun animateToValue(value: Float) {
        intendedValue = value.coerceIn(valueRange)
        transitionJob?.cancel()
        transitionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transitionMutex.mutate {
                if (reduced) {
                    valueAnimation.snapTo(intendedValue)
                    return@mutate
                }
                press()
                launch { valueAnimation.animateTo(intendedValue, valueSpec) }
                if (velocityAnimation.value != 0f) launch { velocityAnimation.animateTo(0f, velocitySpec) }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(System.nanoTime() / 1_000_000L, Offset(valueAnimation.value, 0f))
        val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1f)
        val target = velocityTracker.calculateVelocity().x / range
        scope.launch { velocityAnimation.animateTo(target, velocitySpec) }
    }

    fun close() { scope.cancel() }
}

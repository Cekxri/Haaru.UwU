/* SPDX-License-Identifier: Apache-2.0
 * Adapted from Kyant0/AndroidLiquidGlass catalogue DragGestureInspector,
 * commit eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5. See NOTICE.
 */
package dev.cekxri.haaru

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.CancellationException

/** Observes the selected lens without swallowing the neighbouring tab buttons. */
internal suspend fun PointerInputScope.inspectGlassDragGestures(
    onDragStart: (PointerInputChange) -> Unit,
    onDragEnd: (PointerInputChange) -> Unit,
    onDragCancel: () -> Unit,
    onDrag: (PointerInputChange, Offset) -> Unit
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)
        try {
            onDragStart(down)
            onDrag(initialDown, Offset.Zero)
            val up = glassDrag(initialDown.id) { onDrag(it, it.positionChange()) }
            if (up == null) onDragCancel() else onDragEnd(up)
        } catch (cancelled: CancellationException) {
            onDragCancel()
            throw cancelled
        }
    }
}

private suspend fun AwaitPointerEventScope.glassDrag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit
): PointerInputChange? {
    if (currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true) return null
    var pointer = pointerId
    while (true) {
        val change = awaitGlassDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend fun AwaitPointerEventScope.awaitGlassDragOrUp(pointerId: PointerId): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (change.changedToUpIgnoreConsumed()) {
            val other = event.changes.fastFirstOrNull { it.pressed }
            if (other == null) return change
            pointer = other.id
        } else if (change.previousPosition != change.position) return change
    }
}

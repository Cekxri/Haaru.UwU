package dev.cekxri.haaru

import org.junit.Assert.*
import org.junit.Test

class LiquidNavGeometryTest {
    @Test fun rtlMirrorsPointerAndPillPositionsButKeepsLogicalTabs() {
        assertEquals(0f, liquidLogicalPosition(50f, 300f, 3, false), .001f)
        assertEquals(0f, liquidLogicalPosition(250f, 300f, 3, true), .001f)
        assertEquals(2f, liquidPhysicalPosition(0f, 3, true), .001f)
        assertEquals(1, liquidReleaseIndex(liquidLogicalPosition(150f, 300f, 3, true), 3))
    }
    @Test fun overscrollIsBoundedAndReleaseNeverSelectsAnInvalidTab() {
        val left = liquidLogicalPosition(-10000f, 300f, 3, false)
        val right = liquidLogicalPosition(10000f, 300f, 3, false)
        assertTrue(left in -.11f..0f)
        assertTrue(right in 2f..2.11f)
        assertEquals(0, liquidReleaseIndex(left, 3))
        assertEquals(2, liquidReleaseIndex(right, 3))
    }
    @Test fun leavingTheNavVerticallyCancelsCommit() {
        assertTrue(liquidCanCommit(40f, 64f, 24f))
        assertFalse(liquidCanCommit(-25f, 64f, 24f))
        assertFalse(liquidCanCommit(89f, 64f, 24f))
    }
    @Test fun aCancelledOrVerticallyEscapedDragDoesNotCommitItsPreviewTab() {
        assertEquals(0, liquidDragDestination(2f, 0, 3, true, 30f, 54f, 24f))
        assertEquals(1, liquidDragDestination(2f, 1, 3, false, 79f, 54f, 24f))
        assertEquals(2, liquidDragDestination(1.8f, 0, 3, false, 30f, 54f, 24f))
    }
    @Test fun reducedMotionDisablesEvenAnInFlightPressedOrVelocityDeformation() {
        assertEquals(LiquidScale(1f, 1f), liquidLensScale(1.4f, 1.4f, 1000f, true))
        val right = liquidLensScale(1f, 1f, 1000f, false)
        val left = liquidLensScale(1f, 1f, -1000f, false)
        assertEquals(1.25f, right.x, .001f)
        assertEquals(.8f, right.y, .001f)
        assertEquals(1f / 1.2f, left.x, .001f)
        assertEquals(1.2f, left.y, .001f)
    }
}

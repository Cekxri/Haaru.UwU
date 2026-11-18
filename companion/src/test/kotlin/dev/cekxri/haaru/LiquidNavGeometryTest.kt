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
    @Test fun reducedMotionRemovesStretchAndLiftDuringDrag() {
        assertEquals(LiquidPose(1f, 1f, 0f, 40f), liquidPose(-.1f, 2f, 1f, true))
        val moving = liquidPose(0f, 2f, 1f, false)
        assertTrue(moving.scaleX in 1f..1.15f)
        assertTrue(moving.scaleY in .95f..1.09f)
        assertTrue(moving.lift in -2.2f..0f)
    }
}

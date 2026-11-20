package dev.cekxri.haaru

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import haaru.ui.*
import org.junit.Assert.*
import org.junit.Test

class CafeColourContrastTest {
    private fun contrast(ink: Color, surface: Color): Float {
        val a = ink.luminance(); val b = surface.luminance()
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }

    @Test fun labelsAndActionsStayReadableAcrossStylesSeedsAndElevation() {
        val seeds = listOf(Color(0xFF7E57C2), Color(0xFF147858), Color(0xFFC84638),
            Color(0xFFF2C500), Color(0xFF1768C0), Color.White, Color.Black)
        for (seed in seeds) for (style in ColourStyle.entries) for (dark in listOf(false, true)) {
            val c = cafeColours(manualColours(dark, seed, style), dark)
            for (surface in listOf(c.background, c.surface, c.surfaceContainerLow,
                c.surfaceContainer, c.surfaceContainerHigh, c.surfaceContainerHighest)) {
                assertTrue("$seed/$style/$dark main text", contrast(c.onSurface, surface) >= 4.5f)
                assertTrue("$seed/$style/$dark secondary text", contrast(c.onSurfaceVariant, surface) >= 4.5f)
            }
            assertTrue(contrast(c.error, c.background) >= 3f)
            assertTrue(contrast(c.onPrimary, c.primary) >= 4.5f)
            assertTrue(contrast(c.onPrimaryContainer, c.primaryContainer) >= 4.5f)
            assertTrue(contrast(c.onSecondaryContainer, c.secondaryContainer) >= 4.5f)
        }
    }

    @Test fun lightModeSeparatesTheMutedGroundFromTheCards() {
        for (style in ColourStyle.entries) {
            val colours = cafeColours(manualColours(false, Color(0xFF7E57C2), style), false)
            assertTrue(colours.background.luminance() <= .48f)
            assertTrue(colours.surface.luminance() - colours.background.luminance() >= .07f)
        }
    }

    @Test fun changingThePaletteChangesTheWholeMaterialWorld() {
        for (dark in listOf(false, true)) {
            val violet = cafeColours(manualColours(dark, Color(0xFF7E57C2)), dark)
            val green = cafeColours(manualColours(dark, Color(0xFF147858)), dark)
            assertNotEquals(violet.background, green.background)
            assertNotEquals(violet.surface, green.surface)
            assertNotEquals(violet.primary, green.primary)
            assertNotEquals(violet.tertiaryContainer, green.tertiaryContainer)
            assertNotEquals(violet.onSurface, green.onSurface)
        }
    }
}

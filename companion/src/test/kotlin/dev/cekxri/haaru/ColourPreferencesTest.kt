package dev.cekxri.haaru

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import haaru.ui.*
import org.junit.Assert.*
import org.junit.Test

class ColourPreferencesTest {
    @Test fun legacyAppearanceEnablesWallpaperColoursWithoutLosingSavedPalette() {
        val old = UiPreferences.decode("""{"palette":"MINT","glass":false}""")
        assertTrue(old.dynamicColour)
        assertEquals(Palette.MINT, old.palette)
        assertFalse(old.glass)
        assertEquals(Palette.VIOLET, UiPreferences.decode(null).palette)
    }
    @Test fun manualCustomColourRoundTripsCanonicallyAndInvalidHexIsNotEmitted() {
        val saved = UiPreferences(dynamicColour = false, customColour = "#b39ddb", palette = Palette.LAVENDER)
        val decoded = UiPreferences.decode(saved.encode())
        assertEquals("#B39DDB", decoded.customColour)
        assertFalse(decoded.dynamicColour)
        assertEquals(Palette.LAVENDER, decoded.palette)
        assertEquals("", UiPreferences.decode("""{"customColour":"red;rm"}""").customColour)
    }
    @Test fun everyManualPaletteAndHsvColourRemainsSelectable() {
        assertTrue(Palette.entries.size >= 15)
        Palette.entries.forEach { palette ->
            val hex = HsvColour(colourHsv(paletteColour(palette)).hue,
                colourHsv(paletteColour(palette)).saturation, colourHsv(paletteColour(palette)).brightness).hex()
            assertTrue(validCustomColour(hex))
            assertEquals(palette, UiPreferences.decode(UiPreferences(palette = palette).encode()).palette)
        }
        assertEquals("#FF0000", HsvColour(0f, 1f, 1f).hex())
        assertEquals("#00FF00", HsvColour(120f, 1f, 1f).hex())
        assertEquals("#000000", HsvColour(270f, 1f, 0f).hex())
    }
    @Test fun miuixUsesTheSuppliedGoogleRolesInBothAppearances() {
        listOf(lightColorScheme(primary = Color(0xFF246350)), darkColorScheme(primary = Color(0xFFAFD0BF))).forEachIndexed { i, roles ->
            val mapped = miuixColours(roles, i == 1)
            assertEquals(roles.primary, mapped.primary)
            assertEquals(roles.onPrimary, mapped.onPrimary)
            assertEquals(roles.surface, mapped.surface)
            assertEquals(roles.onSurface, mapped.onSurface)
            assertEquals(roles.primaryContainer, mapped.primaryContainer)
        }
    }
    @Test fun missingWallpaperRolesUseManualColoursWithoutCallingUnsupportedApis() {
        val manual = lightColorScheme(primary = Color(0xFF7E57C2))
        var calls = 0
        val oldAndroid = resolveColourRoles(true, 30, { calls++; error("Unsupported API") }, { manual })
        assertEquals(0, calls)
        assertFalse(oldAndroid.dynamicActive)
        val missing = resolveColourRoles(true, 31, { calls++; error("Missing wallpaper resource") }, { manual })
        assertFalse(missing.dynamicActive)
        assertEquals(manual.primary, missing.scheme.primary)
        val available = resolveColourRoles(true, 31, { lightColorScheme(primary = Color(0xFF246350)) }, { manual })
        assertTrue(available.dynamicActive)
        assertEquals(Color(0xFF246350), available.scheme.primary)
    }
}

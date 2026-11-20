package dev.cekxri.haaru

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.Variant
import com.materialkolor.utils.ColorUtils
import haaru.ui.*
import org.junit.Assert.*
import org.junit.Test

class ColourPreferencesTest {
    @Test fun freshAppearanceUsesVioletUntilTheUserEnablesWallpaperColours() {
        listOf(UiPreferences(), UiPreferences.decode(null), UiPreferences.decode("{}")).forEach { fresh ->
            assertFalse(fresh.dynamicColour)
            assertEquals(Palette.VIOLET, fresh.palette)
            assertEquals(ColourStyle.EXPRESSIVE, fresh.colourStyle)
        }
        assertTrue(UiPreferences.decode("""{"dynamicColour":true}""").dynamicColour)
        assertFalse(UiPreferences.decode("""{"dynamicColour":false}""").dynamicColour)
    }
    @Test fun legacyAppearanceEnablesWallpaperColoursWithoutLosingSavedPalette() {
        val old = UiPreferences.decode("""{"palette":"MINT","glass":false}""")
        assertTrue(old.dynamicColour)
        assertEquals(Palette.MINT, old.palette)
        assertFalse(old.glass)
        assertEquals(Palette.VIOLET, UiPreferences.decode(null).palette)
        assertEquals(ColourStyle.EXPRESSIVE, old.colourStyle)
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
        listOf(manualColours(false, Color(0xFF246350)), manualColours(true, Color(0xFFAFD0BF))).forEachIndexed { i, roles ->
            val mapped = miuixColours(roles, i == 1)
            assertEquals(roles.primary, mapped.primary)
            assertEquals(roles.onPrimary, mapped.onPrimary)
            assertEquals(roles.surface, mapped.surface)
            assertEquals(roles.onSurface, mapped.onSurface)
            assertEquals(roles.primaryContainer, mapped.primaryContainer)
        }
    }
    @Test fun earlierMaterialPreferenceIsReadButOnlyMiuixIsSaved() {
        val earlier = UiPreferences.decode("""{"style":"MATERIAL3","dynamicColour":false,"palette":"PLUM","customColour":"#72405c"}""")
        assertEquals(UiStyle.MIUIX, earlier.style)
        assertEquals(Palette.PLUM, earlier.palette)
        assertEquals("#72405C", earlier.customColour)
        assertFalse(earlier.dynamicColour)
        assertTrue(earlier.encode().contains("\"style\":\"MIUIX\""))
        assertFalse(UiPreferences(style = UiStyle.MATERIAL3).encode().contains("MATERIAL3"))
    }
    @Test fun wallpaperSeedUsesTheSelectedStyleAndChangesWithTheSystemPalette() {
        val firstSeed = Color(0xFF246350)
        val secondSeed = Color(0xFF7E57C2)
        ColourStyle.entries.forEach { style ->
            val first = systemWallpaperColours(false, style) { if (it == "system_accent1_500") firstSeed else null }
            val second = systemWallpaperColours(false, style) { if (it == "system_accent1_500") secondSeed else null }
            assertEquals(manualColours(false, firstSeed, style), first)
            assertEquals(manualColours(false, secondSeed, style), second)
            assertNotEquals(first, second)
            assertEquals(first.primary, miuixColours(first, false).primary)
        }
        assertEquals(firstSeed, systemWallpaperSeed { if (it == "system_accent1_600") firstSeed else null })
    }
    @Test fun missingWallpaperRolesUseManualColoursWithoutCallingUnsupportedApis() {
        val manual = manualColours(false, Color(0xFF7E57C2))
        var calls = 0
        val oldAndroid = resolveColourRoles(true, 30, { calls++; error("Unsupported API") }, { manual })
        assertEquals(0, calls)
        assertFalse(oldAndroid.dynamicActive)
        val missing = resolveColourRoles(true, 31, { calls++; error("Missing wallpaper resource") }, { manual })
        assertFalse(missing.dynamicActive)
        assertEquals(manual.primary, missing.scheme.primary)
        val available = resolveColourRoles(true, 31, { manualColours(false, Color(0xFF246350)) }, { manual })
        assertTrue(available.dynamicActive)
        assertEquals(manualColours(false, Color(0xFF246350)).primary, available.scheme.primary)
    }

    @Test fun selectedColourStylesRoundTripWithoutLosingTheSeedOrOtherPreferences() {
        ColourStyle.entries.forEach { style ->
            val preferences = UiPreferences(colourStyle = style, palette = Palette.IRIS,
                customColour = "#6546b5", dynamicColour = true, reduceMotion = true)
            val after = UiPreferences.decode(preferences.encode())
            assertEquals(style, after.colourStyle)
            assertEquals(Palette.IRIS, after.palette)
            assertEquals("#6546B5", after.customColour)
            assertTrue(after.dynamicColour)
            assertTrue(after.reduceMotion)
        }
        assertEquals(ColourStyle.EXPRESSIVE, UiPreferences.decode("""{"colourStyle":"INVALID","palette":"PLUM"}""").colourStyle)
        assertEquals(Palette.PLUM, UiPreferences.decode("""{"palette":"PLUM"}""").palette)
    }

    @Test fun allStylesUseTheirActualLatestSpecificationAndProduceDifferentSchemes() {
        val seed = Color(0xFF7E57C2)
        val variants = mapOf(ColourStyle.EXPRESSIVE to Variant.EXPRESSIVE,
            ColourStyle.VIBRANT to Variant.VIBRANT, ColourStyle.TONAL_SPOT to Variant.TONAL_SPOT,
            ColourStyle.NEUTRAL to Variant.NEUTRAL, ColourStyle.CMF to Variant.CMF)
        for (dark in listOf(false, true)) {
            val schemes = ColourStyle.entries.map { style ->
                val material = materialColourScheme(dark, seed, style)
                assertEquals(if (style == ColourStyle.CMF) ColorSpec.SpecVersion.SPEC_2026
                    else ColorSpec.SpecVersion.SPEC_2025, material.specVersion)
                assertEquals(variants.getValue(style), material.variant)
                assertEquals(seed.toArgb(), material.sourceColorArgb)
                manualColours(dark, seed, style)
            }
            assertEquals(ColourStyle.entries.size, schemes.distinct().size)
        }
    }

    @Test fun generatedMaterialRolesMaintainReadableContrastInLightAndDarkAppearances() {
        fun contrast(foreground: Color, background: Color): Double {
            val first = ColorUtils.lstarFromArgb(foreground.toArgb())
            val second = ColorUtils.lstarFromArgb(background.toArgb())
            val high = ColorUtils.yFromLstar(maxOf(first, second))
            val low = ColorUtils.yFromLstar(minOf(first, second))
            return (high + 5.0) / (low + 5.0)
        }
        for (style in ColourStyle.entries) for (dark in listOf(false, true)) {
            val roles = manualColours(dark, Color(0xFF7E57C2), style)
            assertTrue("$style/$dark primary text contrast", contrast(roles.onPrimary, roles.primary) >= 4.5)
            assertTrue("$style/$dark surface text contrast", contrast(roles.onSurface, roles.surface) >= 4.5)
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */
package haaru.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct

/** Material supplies the palette; Haaru's material world controls its tonal distribution. */
internal fun cafeColours(source: HaaruColours, dark: Boolean): HaaruColours {
    val pigment = Hct.fromInt(source.primary.toArgb())
    fun ink(tone: Double, chroma: Double = 7.0): Color =
        Color(Hct.from(pigment.hue, chroma.coerceAtMost(maxOf(pigment.chroma, 4.0)), tone).toInt())
    val accent = ink(if (dark) 72.0 else 40.0, pigment.chroma.coerceAtMost(48.0))
    val foreground = ink(if (dark) 94.0 else 12.0, 4.0)
    val secondaryInk = ink(if (dark) 72.0 else 26.0, 6.0)
    val ground = ink(if (dark) 5.0 else 73.0, if (dark) 9.0 else 10.0)
    val paper = ink(if (dark) 10.0 else 80.0, 9.0)
    val accentWell = ink(if (dark) 22.0 else 77.0, 16.0)
    return source.copy(
        primary = accent, onPrimary = ink(if (dark) 8.0 else 94.0, 3.0),
        primaryContainer = accentWell, onPrimaryContainer = foreground,
        secondary = ink(if (dark) 75.0 else 38.0, 10.0),
        onSecondary = ink(if (dark) 8.0 else 94.0, 3.0),
        secondaryContainer = ink(if (dark) 18.0 else 78.0, 11.0),
        onSecondaryContainer = foreground,
        tertiary = ink(if (dark) 80.0 else 40.0, 6.0),
        onTertiary = ink(if (dark) 8.0 else 94.0, 3.0),
        tertiaryContainer = ink(if (dark) 25.0 else 77.0, 9.0),
        onTertiaryContainer = foreground,
        background = ground, onBackground = foreground,
        surface = paper, onSurface = foreground,
        surfaceVariant = ink(if (dark) 18.0 else 77.0, 9.0),
        onSurfaceVariant = secondaryInk,
        surfaceContainerLowest = ink(if (dark) 4.0 else 74.0, 9.0),
        surfaceContainerLow = ink(if (dark) 8.0 else 78.0, 8.0),
        surfaceContainer = paper,
        surfaceContainerHigh = ink(if (dark) 14.0 else 82.0, 9.0),
        surfaceContainerHighest = ink(if (dark) 20.0 else 84.0, 9.0),
        outline = ink(if (dark) 46.0 else 54.0, 6.0),
        outlineVariant = ink(if (dark) 25.0 else 63.0, 7.0)
    )
}

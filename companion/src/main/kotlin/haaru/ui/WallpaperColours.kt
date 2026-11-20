package haaru.ui

import androidx.compose.ui.graphics.Color

/** Read a real Android Monet palette seed, then apply the selected Material specification. */
internal fun systemWallpaperSeed(read: (String) -> Color?): Color =
    listOf("system_accent1_500", "system_accent1_600", "system_accent1_400")
        .firstNotNullOfOrNull(read) ?: error("System wallpaper palette is unavailable")

internal fun systemWallpaperColours(dark: Boolean, style: ColourStyle = ColourStyle.EXPRESSIVE,
                                    read: (String) -> Color?): HaaruColours =
    manualColours(dark, systemWallpaperSeed(read), style)

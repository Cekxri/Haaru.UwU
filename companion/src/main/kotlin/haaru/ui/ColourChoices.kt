package haaru.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val hexColour = Regex("#[0-9A-Fa-f]{6}")
fun canonicalCustomColour(value: String): String = if (hexColour.matches(value)) value.uppercase(Locale.ROOT) else ""
fun validCustomColour(value: String): Boolean = hexColour.matches(value)

fun paletteColour(palette: Palette): Color = Color(when (palette) {
    Palette.VIOLET -> 0xFF7E57C2
    Palette.LAVENDER -> 0xFFB39DDB
    Palette.WISTERIA -> 0xFF9B72CF
    Palette.IRIS -> 0xFF6546B5
    Palette.PLUM -> 0xFF72405C
    Palette.LILAC -> 0xFFC8A2C8
    Palette.ORCHID -> 0xFFAC59B8
    Palette.MAUVE -> 0xFF94618E
    Palette.PERIWINKLE -> 0xFF7F83CC
    Palette.MINT -> 0xFF527A69
    Palette.BLUE -> 0xFF64798D
    Palette.ROSE -> 0xFFAA727C
    Palette.SAGE -> 0xFF7D8F7B
    Palette.CAFE -> 0xFF8B6F5C
    Palette.SLATE -> 0xFF708090
}.toLong())

fun paletteName(palette: Palette): String = when (palette) {
    Palette.VIOLET -> "紫羅蘭"; Palette.LAVENDER -> "薰衣草"; Palette.WISTERIA -> "紫藤"
    Palette.IRIS -> "鳶尾"; Palette.PLUM -> "梅子"; Palette.LILAC -> "丁香"
    Palette.ORCHID -> "蘭花"; Palette.MAUVE -> "灰紫"; Palette.PERIWINKLE -> "長春花"
    Palette.MINT -> "薄荷"; Palette.BLUE -> "霧藍"; Palette.ROSE -> "玫瑰"
    Palette.SAGE -> "鼠尾草"; Palette.CAFE -> "咖啡"; Palette.SLATE -> "石板"
}

fun manualSeed(preferences: UiPreferences): Color {
    val custom = canonicalCustomColour(preferences.customColour)
    return if (custom.isEmpty()) paletteColour(preferences.palette) else Color((0xFF000000L or custom.drop(1).toLong(16)))
}

fun colourStyleName(style: ColourStyle): String = when (style) {
    ColourStyle.EXPRESSIVE -> "Expressive 2025 · 表現力"
    ColourStyle.VIBRANT -> "Vibrant 2025 · 鮮明"
    ColourStyle.TONAL_SPOT -> "Tonal Spot 2025 · 柔和"
    ColourStyle.NEUTRAL -> "Neutral 2025 · 低彩度"
    ColourStyle.CMF -> "CMF 2026 · 原色與材質"
}

data class HsvColour(val hue: Float, val saturation: Float, val brightness: Float) {
    fun colour(): Color = Color.hsv(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
    fun hex(): String = String.format(Locale.ROOT, "#%06X", colour().toArgb() and 0xFFFFFF)
}

fun colourHsv(colour: Color): HsvColour {
    val highest = max(colour.red, max(colour.green, colour.blue))
    val lowest = min(colour.red, min(colour.green, colour.blue))
    val delta = highest - lowest
    val hue = when {
        delta == 0f -> 0f
        highest == colour.red -> 60f * (((colour.green - colour.blue) / delta) % 6f)
        highest == colour.green -> 60f * ((colour.blue - colour.red) / delta + 2f)
        else -> 60f * ((colour.red - colour.green) / delta + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return HsvColour(hue, if (highest == 0f) 0f else delta / highest, highest)
}

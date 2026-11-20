package haaru.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeVibrant
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeCmf
import top.yukonga.miuix.kmp.theme.Colors as MiuixColours

/** Independent colour roles; the UI has no Material 3 theme or widget dependency. */
data class HaaruColours(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val secondary: Color, val onSecondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    val tertiary: Color, val onTertiary: Color, val tertiaryContainer: Color, val onTertiaryContainer: Color,
    val background: Color, val onBackground: Color, val surface: Color, val onSurface: Color,
    val surfaceVariant: Color, val onSurfaceVariant: Color, val outline: Color, val outlineVariant: Color,
    val surfaceContainer: Color, val surfaceContainerHigh: Color, val surfaceContainerHighest: Color,
    val surfaceContainerLow: Color, val surfaceContainerLowest: Color,
    val error: Color, val onError: Color, val errorContainer: Color, val onErrorContainer: Color, val scrim: Color
)

internal val LocalHaaruColours = staticCompositionLocalOf<HaaruColours> { error("HaaruTheme is required") }
object HaaruTheme {
    val colours: HaaruColours @Composable @ReadOnlyComposable get() = LocalHaaruColours.current
}
val LocalDynamicColourActive = staticCompositionLocalOf { false }
internal data class ColourRoles(val scheme: HaaruColours, val dynamicActive: Boolean)
internal fun resolveColourRoles(dynamic: Boolean, sdk: Int, wallpaper: () -> HaaruColours, manual: () -> HaaruColours): ColourRoles {
    if (dynamic && sdk >= 31) runCatching { wallpaper() }.getOrNull()?.let { return ColourRoles(it, true) }
    return ColourRoles(manual(), false)
}

/** Expressive styles still use 2025; the newer CMF style has its own 2026 specification. */
internal val materialColourSpec = ColorSpec.SpecVersion.SPEC_2025

internal fun materialColourScheme(dark: Boolean, seed: Color, style: ColourStyle): DynamicScheme {
    val source = Hct.fromInt(seed.toArgb())
    return when (style) {
        ColourStyle.EXPRESSIVE -> SchemeExpressive(source, dark, 0.0, materialColourSpec, DynamicScheme.Platform.PHONE)
        ColourStyle.VIBRANT -> SchemeVibrant(source, dark, 0.0, materialColourSpec, DynamicScheme.Platform.PHONE)
        ColourStyle.TONAL_SPOT -> SchemeTonalSpot(source, dark, 0.0, materialColourSpec, DynamicScheme.Platform.PHONE)
        ColourStyle.NEUTRAL -> SchemeNeutral(source, dark, 0.0, materialColourSpec, DynamicScheme.Platform.PHONE)
        ColourStyle.CMF -> SchemeCmf(source, dark, 0.0, ColorSpec.SpecVersion.SPEC_2026, DynamicScheme.Platform.PHONE)
    }
}

/** Generate every role from the chosen seed and colour style, including manual colours. */
internal fun manualColours(dark: Boolean, seed: Color, style: ColourStyle = ColourStyle.EXPRESSIVE): HaaruColours {
    val scheme = materialColourScheme(dark, seed, style)
    return HaaruColours(
        primary = Color(scheme.primary),
        onPrimary = Color(scheme.onPrimary),
        primaryContainer = Color(scheme.primaryContainer),
        onPrimaryContainer = Color(scheme.onPrimaryContainer),
        secondary = Color(scheme.secondary),
        onSecondary = Color(scheme.onSecondary),
        secondaryContainer = Color(scheme.secondaryContainer),
        onSecondaryContainer = Color(scheme.onSecondaryContainer),
        tertiary = Color(scheme.tertiary),
        onTertiary = Color(scheme.onTertiary),
        tertiaryContainer = Color(scheme.tertiaryContainer),
        onTertiaryContainer = Color(scheme.onTertiaryContainer),
        background = Color(scheme.background),
        onBackground = Color(scheme.onBackground),
        surface = Color(scheme.surface),
        onSurface = Color(scheme.onSurface),
        surfaceVariant = Color(scheme.surfaceVariant),
        onSurfaceVariant = Color(scheme.onSurfaceVariant),
        error = Color(scheme.error),
        onError = Color(scheme.onError),
        errorContainer = Color(scheme.errorContainer),
        onErrorContainer = Color(scheme.onErrorContainer),
        outline = Color(scheme.outline),
        outlineVariant = Color(scheme.outlineVariant),
        scrim = Color(scheme.scrim),
        surfaceContainer = Color(scheme.surfaceContainer),
        surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
        surfaceContainerHighest = Color(scheme.surfaceContainerHighest),
        surfaceContainerLow = Color(scheme.surfaceContainerLow),
        surfaceContainerLowest = Color(scheme.surfaceContainerLowest),
    )
}


/** Project the same colour roles onto Miuix controls, including readable disabled states. */
internal fun miuixColours(md: HaaruColours, dark: Boolean): MiuixColours {
    val disabled = md.onSurface.copy(alpha = .12f).compositeOver(md.surface)
    val disabledText = md.onSurface.copy(alpha = .38f).compositeOver(disabled)
    return MiuixColours(
        primary = md.primary, onPrimary = md.onPrimary,
        primaryVariant = md.primaryContainer, onPrimaryVariant = md.onPrimaryContainer,
        error = md.error, onError = md.onError, errorContainer = md.errorContainer, onErrorContainer = md.onErrorContainer,
        disabledPrimary = disabled, disabledOnPrimary = disabledText,
        disabledPrimaryButton = disabled, disabledOnPrimaryButton = disabledText, disabledPrimarySlider = disabled,
        primaryContainer = md.primaryContainer, onPrimaryContainer = md.onPrimaryContainer,
        secondary = md.outlineVariant, onSecondary = md.onSurfaceVariant,
        secondaryVariant = md.surfaceContainerHigh, onSecondaryVariant = md.onSurface,
        disabledSecondary = disabled, disabledOnSecondary = disabledText,
        disabledSecondaryVariant = disabled, disabledOnSecondaryVariant = disabledText,
        secondaryContainer = md.secondaryContainer, onSecondaryContainer = md.onSecondaryContainer,
        secondaryContainerVariant = md.surfaceContainerHighest, onSecondaryContainerVariant = md.onSurfaceVariant,
        tertiaryContainer = md.tertiaryContainer, onTertiaryContainer = md.onTertiaryContainer,
        tertiaryContainerVariant = md.onTertiaryContainer,
        background = md.background, onBackground = md.onBackground, onBackgroundVariant = md.primary,
        surface = md.surface, onSurface = md.onSurface, surfaceVariant = md.surfaceVariant,
        onSurfaceSecondary = md.onSurface, onSurfaceVariantSummary = md.onSurfaceVariant,
        onSurfaceVariantActions = md.onSurfaceVariant, disabledOnSurface = disabledText,
        surfaceContainer = md.surfaceContainer, onSurfaceContainer = md.onSurface,
        onSurfaceContainerVariant = md.onSurfaceVariant, surfaceContainerHigh = md.surfaceContainerHigh,
        onSurfaceContainerHigh = md.onSurface, surfaceContainerHighest = md.surfaceContainerHighest,
        onSurfaceContainerHighest = md.onSurface, outline = md.outline, dividerLine = md.outlineVariant,
        windowDimming = md.scrim.copy(alpha = if (dark) .55f else .35f),
        sliderKeyPoint = md.primary, sliderKeyPointForeground = md.onPrimary,
        sliderBackground = md.primary.copy(alpha = .20f).compositeOver(md.surface)
    )
}


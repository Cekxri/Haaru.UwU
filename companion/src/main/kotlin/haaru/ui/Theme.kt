/* Haaru native theme, Apache-2.0. Android wallpaper roles are shared by both toolkits. */
package haaru.ui

import android.app.WallpaperManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme as mdDarkColors
import androidx.compose.material3.lightColorScheme as mdLightColors
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeFidelity
import top.yukonga.miuix.kmp.theme.Colors as MiuixColours
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles

val LocalDynamicColourActive = staticCompositionLocalOf { false }
internal data class ColourRoles(val scheme: ColorScheme, val dynamicActive: Boolean)
internal fun resolveColourRoles(dynamic: Boolean, sdk: Int, wallpaper: () -> ColorScheme, manual: () -> ColorScheme): ColourRoles {
    if (dynamic && sdk >= 31) runCatching { wallpaper() }.getOrNull()?.let { return ColourRoles(it, true) }
    return ColourRoles(manual(), false)
}

/** The Android resource scheme supplies actual Google Monet colours, without a fixed seed. */
@Composable
fun DualTheme(style: UiStyle, dark: Boolean, seed: Color, fontFamily: FontFamily,
              dynamicColour: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var wallpaperRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(context, dynamicColour) {
        if (dynamicColour && Build.VERSION.SDK_INT >= 31) {
            val manager = WallpaperManager.getInstance(context)
            val listener = WallpaperManager.OnColorsChangedListener { _, _ -> wallpaperRevision++ }
            val registered = runCatching { manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper())) }.isSuccess
            onDispose { if (registered) runCatching { manager.removeOnColorsChangedListener(listener) } }
        } else onDispose { }
    }
    val roles = remember(context, configuration, wallpaperRevision, dark, seed, dynamicColour) {
        resolveColourRoles(dynamicColour, Build.VERSION.SDK_INT, wallpaper = {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }, manual = { manualMaterialColours(dark, seed) })
    }
    val mdColors = roles.scheme
    val miColors = remember(mdColors, dark) { miuixColours(mdColors, dark) }
    val miTypography = remember(fontFamily) {
        val defaults = defaultTextStyles()
        defaults.copy(
            main = defaults.main.copy(fontFamily = fontFamily),
            paragraph = defaults.paragraph.copy(fontFamily = fontFamily),
            body1 = defaults.body1.copy(fontFamily = fontFamily),
            body2 = defaults.body2.copy(fontFamily = fontFamily),
            button = defaults.button.copy(fontFamily = fontFamily),
            footnote1 = defaults.footnote1.copy(fontFamily = fontFamily),
            footnote2 = defaults.footnote2.copy(fontFamily = fontFamily),
            headline1 = defaults.headline1.copy(fontFamily = fontFamily),
            headline2 = defaults.headline2.copy(fontFamily = fontFamily),
            subtitle = defaults.subtitle.copy(fontFamily = fontFamily),
            title1 = defaults.title1.copy(fontFamily = fontFamily),
            title2 = defaults.title2.copy(fontFamily = fontFamily),
            title3 = defaults.title3.copy(fontFamily = fontFamily),
            title4 = defaults.title4.copy(fontFamily = fontFamily),
        )
    }
    val mdTypography = remember(fontFamily) {
        val defaults = Typography()
        defaults.copy(
            displayLarge = defaults.displayLarge.copy(fontFamily = fontFamily),
            displayMedium = defaults.displayMedium.copy(fontFamily = fontFamily),
            displaySmall = defaults.displaySmall.copy(fontFamily = fontFamily),
            headlineLarge = defaults.headlineLarge.copy(fontFamily = fontFamily),
            headlineMedium = defaults.headlineMedium.copy(fontFamily = fontFamily),
            headlineSmall = defaults.headlineSmall.copy(fontFamily = fontFamily),
            titleLarge = defaults.titleLarge.copy(fontFamily = fontFamily),
            titleMedium = defaults.titleMedium.copy(fontFamily = fontFamily),
            titleSmall = defaults.titleSmall.copy(fontFamily = fontFamily),
            bodyLarge = defaults.bodyLarge.copy(fontFamily = fontFamily),
            bodyMedium = defaults.bodyMedium.copy(fontFamily = fontFamily),
            bodySmall = defaults.bodySmall.copy(fontFamily = fontFamily),
            labelLarge = defaults.labelLarge.copy(fontFamily = fontFamily),
            labelMedium = defaults.labelMedium.copy(fontFamily = fontFamily),
            labelSmall = defaults.labelSmall.copy(fontFamily = fontFamily),
        )
    }
    CompositionLocalProvider(LocalDynamicColourActive provides roles.dynamicActive) { when (style) {
        UiStyle.MIUIX -> MaterialTheme(colorScheme = mdColors, typography = mdTypography) {
            MiuixTheme(colors = miColors, textStyles = miTypography, content = content)
        }
        UiStyle.MATERIAL3 -> MiuixTheme(colors = miColors, textStyles = miTypography) {
            MaterialTheme(colorScheme = mdColors, typography = mdTypography, content = content)
        }
    } }
}

/** Project the same colour roles onto Miuix controls, including readable disabled states. */
internal fun miuixColours(md: ColorScheme, dark: Boolean): MiuixColours {
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

/** Manual seeds generate a coherent scheme when wallpaper colours are disabled or unavailable. */
internal fun manualMaterialColours(dark: Boolean, seed: Color): ColorScheme {
    val scheme = SchemeFidelity(
        sourceColorHct = Hct.fromInt(seed.toArgb()),
        isDark = dark,
        contrastLevel = 0.0,
        specVersion = ColorSpec.SpecVersion.SPEC_2021,
        platform = DynamicScheme.Platform.PHONE,
    )
    val base = if (dark) mdDarkColors() else mdLightColors()
    return base.copy(
        primary = Color(scheme.primary),
        onPrimary = Color(scheme.onPrimary),
        primaryContainer = Color(scheme.primaryContainer),
        onPrimaryContainer = Color(scheme.onPrimaryContainer),
        inversePrimary = Color(scheme.inversePrimary),
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
        surfaceTint = Color(scheme.primary),
        inverseSurface = Color(scheme.inverseSurface),
        inverseOnSurface = Color(scheme.inverseOnSurface),
        error = Color(scheme.error),
        onError = Color(scheme.onError),
        errorContainer = Color(scheme.errorContainer),
        onErrorContainer = Color(scheme.onErrorContainer),
        outline = Color(scheme.outline),
        outlineVariant = Color(scheme.outlineVariant),
        scrim = Color(scheme.scrim),
        surfaceBright = Color(scheme.surfaceBright),
        surfaceDim = Color(scheme.surfaceDim),
        surfaceContainer = Color(scheme.surfaceContainer),
        surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
        surfaceContainerHighest = Color(scheme.surfaceContainerHighest),
        surfaceContainerLow = Color(scheme.surfaceContainerLow),
        surfaceContainerLowest = Color(scheme.surfaceContainerLowest),
    )
}


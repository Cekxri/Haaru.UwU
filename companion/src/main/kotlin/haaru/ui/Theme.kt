/*
 * Haaru native Compose theme, Apache-2.0.
 * Uses the actual Miuix 0.9.4 and Compose Material 3 components.
 * API compatibility references are recorded in research/miuix-material3-ui-review.md.
 */
package haaru.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button as MdButton
import androidx.compose.material3.Card as MdCard
import androidx.compose.material3.CardDefaults as MdCardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalButton as MdTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField as MdField
import androidx.compose.material3.RadioButton as MdRadioButton
import androidx.compose.material3.Switch as MdSwitch
import androidx.compose.material3.Text as MdText
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme as mdDarkColors
import androidx.compose.material3.lightColorScheme as mdLightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot
import top.yukonga.miuix.kmp.basic.Button as MiButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiCard
import top.yukonga.miuix.kmp.basic.RadioButton as MiRadioButton
import top.yukonga.miuix.kmp.basic.Switch as MiSwitch
import top.yukonga.miuix.kmp.basic.Text as MiText
import top.yukonga.miuix.kmp.basic.TextField as MiField
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/**
 * Both color/typography locals remain available to common content, with the selected
 * design system innermost so its indications and overscroll behavior take precedence.
 * Preferences are owned by the caller; changing this theme never runs a root command.
 */
@Composable
fun DualTheme(
    style: UiStyle,
    dark: Boolean,
    seed: Color,
    fontFamily: FontFamily,
    content: @Composable () -> Unit,
) {
    val miController = remember(dark, seed) {
        ThemeController(
            colorSchemeMode = ColorSchemeMode.MonetSystem,
            keyColor = seed,
            isDark = dark,
            paletteStyle = ThemePaletteStyle.TonalSpot,
            colorSpec = ThemeColorSpec.Spec2021,
        )
    }
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
    val mdColors = remember(dark, seed) { materialColors(dark, seed) }
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
    when (style) {
        UiStyle.MIUIX -> MaterialTheme(colorScheme = mdColors, typography = mdTypography) {
            MiuixTheme(controller = miController, textStyles = miTypography, content = content)
        }
        UiStyle.MATERIAL3 -> MiuixTheme(controller = miController, textStyles = miTypography) {
            MaterialTheme(colorScheme = mdColors, typography = mdTypography, content = content)
        }
    }
}

/** Material color utilities generate coherent roles for both light and dark palettes. */
private fun materialColors(dark: Boolean, seed: Color): ColorScheme {
    val scheme = SchemeTonalSpot(
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


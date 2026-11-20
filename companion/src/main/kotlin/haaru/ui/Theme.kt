/* Haaru Miuix-only theme, Apache-2.0. */
package haaru.ui

import android.app.WallpaperManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles

@Composable
fun MiuixAppTheme(dark: Boolean, seed: Color, fontFamily: FontFamily,
                   dynamicColour: Boolean = false, colourStyle: ColourStyle = ColourStyle.EXPRESSIVE,
                   content: @Composable () -> Unit) {
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
    val roles = remember(context, configuration, wallpaperRevision, dark, seed, dynamicColour, colourStyle) {
        resolveColourRoles(dynamicColour, Build.VERSION.SDK_INT,
            wallpaper = { systemWallpaperColours(dark, colourStyle) { name ->
                val id = context.resources.getIdentifier(name, "color", "android")
                if (id == 0) null else Color(context.resources.getColor(id, context.theme))
            } }, manual = { manualColours(dark, seed, colourStyle) })
    }
    val visualRoles = remember(roles.scheme, dark) { cafeColours(roles.scheme, dark) }
    val miColors = remember(visualRoles, dark) { miuixColours(visualRoles, dark) }
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
    CompositionLocalProvider(LocalHaaruColours provides visualRoles, LocalDynamicColourActive provides roles.dynamicActive) {
        MiuixTheme(colors = miColors, textStyles = miTypography, content = content)
    }
}

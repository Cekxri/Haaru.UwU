package dev.cekxri.haaru

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import haaru.ui.UiStyle
import top.yukonga.miuix.kmp.basic.Button as MiButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiCardDefaults
import top.yukonga.miuix.kmp.basic.Switch as MiSwitch
import top.yukonga.miuix.kmp.basic.TextField as MiField
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalHaaruReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun HaaruCard(style: UiStyle, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    when (style) {
        UiStyle.MIUIX -> MiCard(
            modifier = modifier.fillMaxWidth(), cornerRadius = 24.dp,
            insideMargin = PaddingValues(16.dp),
            colors = MiCardDefaults.defaultColors(colors.surfaceContainer, colors.onSurface)
        ) { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
        UiStyle.MATERIAL3 -> Card(
            modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)
        ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
    }
}

@Composable
internal fun HaaruButton(style: UiStyle, label: String, enabled: Boolean = true,
                         secondary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalHaaruReducedMotion.current
    val scale by animateFloatAsState(if (pressed && !reduced) .97f else 1f,
        if (reduced) snap() else spring(dampingRatio = .75f, stiffness = 650f), label = "buttonPress")
    val feedback = modifier.graphicsLayer { scaleX = scale; scaleY = scale }
    when (style) {
        UiStyle.MIUIX -> MiButton(onClick = onClick, enabled = enabled, minHeight = 48.dp, modifier = feedback,
            interactionSource = interaction,
            colors = if (secondary) MiButtonDefaults.buttonColors() else MiButtonDefaults.buttonColorsPrimary()) {
            Text(label, fontWeight = FontWeight.SemiBold,
                color = if (secondary) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onPrimary)
        }
        UiStyle.MATERIAL3 -> if (secondary) FilledTonalButton(onClick, feedback.heightIn(min = 48.dp), enabled = enabled, interactionSource = interaction) { Text(label) }
            else Button(onClick, feedback.heightIn(min = 48.dp), enabled = enabled, interactionSource = interaction) { Text(label) }
    }
}

@Composable
internal fun HaaruField(style: UiStyle, value: String, label: String, modifier: Modifier = Modifier,
                        onChange: (String) -> Unit) {
    when (style) {
        UiStyle.MIUIX -> MiField(value = value, onValueChange = onChange, label = label,
            singleLine = true, modifier = modifier.fillMaxWidth().semantics { contentDescription = label })
        UiStyle.MATERIAL3 -> OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
            modifier = modifier.fillMaxWidth().semantics { contentDescription = label }, shape = RoundedCornerShape(16.dp))
    }
}

@Composable
internal fun SettingSwitch(style: UiStyle, title: String, subtitle: String, checked: Boolean,
                           enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        // Restart just the switch at its target when reduced motion is requested.
        // Library-provided thumb springs otherwise keep their normal native behavior.
        key(if (LocalHaaruReducedMotion.current) checked else Unit) { when (style) {
            UiStyle.MIUIX -> MiSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = title })
            UiStyle.MATERIAL3 -> Switch(checked, onChange, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = title })
        } }
    }
}

@Composable
internal fun SelectionRow(title: String, value: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick)
        .heightIn(min = 60.dp).padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(21.dp), tint = colors.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(value, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(HaaruIcons.Chevron, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
internal fun SectionTitle(label: String) {
    Text(label, Modifier.padding(start = 4.dp, top = 6.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One recorded page layer is sampled by this small nav surface, never by itself. */
@Composable
internal fun HaaruNavigation(backdrop: Backdrop, selected: Int, glass: Boolean,
                             reduced: Boolean, onSelect: (Int) -> Unit) {
    LiquidNavigation(backdrop, selected, glass, reduced, onSelect)
}

/** Original geometric icons, built as vectors; no raster or bundled fonts. */
internal object HaaruIcons {
    private fun icon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
                strokeLineWidth = 1.8f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round, pathBuilder = block)
        }.build()
    val Home = icon("Home") { moveTo(3f, 10f); lineTo(12f, 3f); lineTo(21f, 10f); moveTo(5f, 9f); lineTo(5f, 21f); lineTo(19f, 21f); lineTo(19f, 9f); moveTo(9f, 21f); lineTo(9f, 14f); lineTo(15f, 14f); lineTo(15f, 21f) }
    val Tools = icon("Tools") { moveTo(3f, 6f); lineTo(21f, 6f); moveTo(3f, 12f); lineTo(21f, 12f); moveTo(3f, 18f); lineTo(21f, 18f); moveTo(8f, 3f); lineTo(8f, 9f); moveTo(16f, 9f); lineTo(16f, 15f); moveTo(10f, 15f); lineTo(10f, 21f) }
    val Settings = icon("Settings") { moveTo(12f, 2f); lineTo(21f, 7f); lineTo(21f, 17f); lineTo(12f, 22f); lineTo(3f, 17f); lineTo(3f, 7f); close(); moveTo(16f, 12f); arcTo(4f, 4f, 0f, true, true, 8f, 12f); arcTo(4f, 4f, 0f, true, true, 16f, 12f) }
    val Globe = icon("Country") { moveTo(22f, 12f); arcTo(10f, 10f, 0f, true, true, 2f, 12f); arcTo(10f, 10f, 0f, true, true, 22f, 12f); moveTo(2f, 12f); lineTo(22f, 12f); moveTo(12f, 2f); curveTo(5f, 8f, 5f, 16f, 12f, 22f); curveTo(19f, 16f, 19f, 8f, 12f, 2f) }
    val Sim = icon("SIM") { moveTo(8f, 2f); lineTo(19f, 2f); lineTo(19f, 22f); lineTo(5f, 22f); lineTo(5f, 5f); close(); moveTo(8f, 10f); lineTo(16f, 10f); lineTo(16f, 18f); lineTo(8f, 18f); close(); moveTo(12f, 10f); lineTo(12f, 18f) }
    val Chevron = icon("Choose") { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
    val Refresh = icon("Refresh") { moveTo(20f, 7f); curveTo(13f, -3f, 1f, 4f, 3f, 13f); moveTo(4f, 17f); curveTo(11f, 27f, 23f, 20f, 21f, 11f); moveTo(20f, 2f); lineTo(20f, 7f); lineTo(15f, 7f); moveTo(4f, 22f); lineTo(4f, 17f); lineTo(9f, 17f) }
    val Cup = icon("Cafe") { moveTo(3f, 8f); lineTo(17f, 8f); lineTo(17f, 15f); curveTo(17f, 23f, 3f, 23f, 3f, 15f); close(); moveTo(17f, 9f); lineTo(20f, 9f); curveTo(24f, 9f, 24f, 16f, 17f, 16f); moveTo(7f, 5f); curveTo(3f, 2f, 10f, 1f, 7f, -1f); moveTo(13f, 5f); curveTo(9f, 2f, 16f, 1f, 13f, -1f) }
    val Check = icon("Confirmed") { moveTo(4f, 12f); lineTo(10f, 18f); lineTo(21f, 5f) }
}

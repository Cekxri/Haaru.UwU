/* SPDX-License-Identifier: Apache-2.0
 * Adapted from Kyant0/AndroidLiquidGlass catalogue LiquidBottomTabs and
 * LiquidBottomTab, commit eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5.
 * Haaru adds Miuix colours, its own tabs, reduced motion and cancelled-drag handling.
 * See NOTICE for the public source and licence. No reference APK code is included.
 */
package dev.cekxri.haaru

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import haaru.ui.HaaruTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.abs
import kotlin.math.sign

/** The selected capsule is a lens over a separate, non-interactive tinted tab layer. */
@Composable
internal fun LiquidNavigation(backdrop: Backdrop, selected: Int, glass: Boolean,
                              reduced: Boolean, onSelect: (Int) -> Unit) {
    val colours = HaaruTheme.colours
    val light = colours.background.luminance() >= .30f
    val liquid = glass && isRuntimeShaderSupported()
    val committed = selected.coerceIn(0, 2)
    val selectedNow by rememberUpdatedState(committed)
    val onSelectNow by rememberUpdatedState(onSelect)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    val tabsBackdrop = rememberLayerBackdrop()
    val combinedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
    val containerColour = colours.surfaceContainerHigh.copy(alpha = if (light) .76f else .62f)

    BoxWithConstraints(Modifier.widthIn(max = 420.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        // The labelled layout keeps a 48 dp minimum tab and caps its side inset at 44 dp.
        val sideInset = minOf(44.dp, ((maxWidth - 48.dp * 3 - 8.dp) / 2).coerceAtLeast(0.dp))
        val barWidth = (maxWidth - sideInset * 2).coerceAtLeast(8.dp)
        val tabWidth = with(density) { ((barWidth - 8.dp) / 3).toPx().coerceAtLeast(1f) }
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(barWidth, density, reduced) {
            derivedStateOf {
                if (reduced) 0f else {
                    val fraction = (offsetAnimation.value / with(density) { barWidth.toPx() }).coerceIn(-1f, 1f)
                    with(density) { 4.dp.toPx() } * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
        var dragging by remember { mutableStateOf(false) }
        val motion = remember(scope, tabWidth, rtl, reduced) {
            HaaruDragAnimation(
                parentScope = scope,
                initialValue = selectedNow.toFloat(),
                valueRange = 0f..2f,
                reduced = reduced,
                pressedScale = 78f / 56f,
                onDragStarted = { dragging = true },
                onDragStopped = { cancelled, position ->
                    val destination = liquidDragDestination(targetValue, selectedNow, 3, cancelled,
                        position.y, with(density) { 54.dp.toPx() }, with(density) { 24.dp.toPx() })
                    dragging = false
                    animateToValue(destination.toFloat())
                    if (!cancelled && liquidCanCommit(position.y, with(density) { 54.dp.toPx() }, with(density) { 24.dp.toPx() })) {
                        onSelectNow(destination)
                    }
                    scope.launch {
                        if (reduced) offsetAnimation.snapTo(0f)
                        else offsetAnimation.animateTo(0f, spring(1f, 300f, .5f))
                    }
                },
                onDrag = { _, amount ->
                    updateValue(targetValue + amount.x / tabWidth * if (rtl) -1f else 1f)
                    if (!reduced) scope.launch { offsetAnimation.snapTo(offsetAnimation.value + amount.x) }
                }
            )
        }
        DisposableEffect(motion) { onDispose { motion.close() } }
        LaunchedEffect(committed, motion) {
            if (!dragging && abs(motion.targetValue - committed) > .0001f) motion.animateToValue(committed.toFloat())
        }
        val interactiveHighlight = remember(scope, motion, tabWidth, rtl, reduced) {
            GlassInteractionHighlight(scope) { size, _ ->
                Offset(if (rtl) size.width - (motion.value + .5f) * tabWidth + panelOffset
                    else (motion.value + .5f) * tabWidth + panelOffset, size.height / 2f)
            }
        }
        val tabLabels = remember { listOf("首頁" to HaaruIcons.Home, "工具" to HaaruIcons.Tools, "設定" to HaaruIcons.Settings) }
        val track = if (liquid) Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { Capsule() },
            effects = { vibrancy(); blur(4.dp.toPx()); lens(24.dp.toPx(), 24.dp.toPx(), depthEffect = false) },
            highlight = { null },
            layerBlock = {
                val scale = lerp(1f, 1f + 16.dp.toPx() / size.width.coerceAtLeast(1f), motion.pressProgress)
                scaleX = scale; scaleY = scale
            },
            onDrawSurface = { drawRect(containerColour) }
        ).then(if (reduced) Modifier else interactiveHighlight.modifier)
        else Modifier.background(colours.surfaceContainer, Capsule())

        Row(Modifier.padding(horizontal = sideInset).width(barWidth)
            .graphicsLayer { translationX = panelOffset }.then(track).height(62.dp).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            tabLabels.forEachIndexed { index, tab ->
                LiquidTab(tab.first, tab.second, colours.onSurface, 1f,
                    selected = index == committed, interactive = true) { onSelectNow(index) }
            }
        }
        if (liquid) {
            // Only this visual row is recorded. It excludes the indicator and has no click/focus targets.
            Row(Modifier.padding(horizontal = sideInset).width(barWidth)
                .clearAndSetSemantics { }.alpha(0f).layerBackdrop(tabsBackdrop)
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        val progress = motion.pressProgress
                        vibrancy(); blur(4.dp.toPx())
                        lens(24.dp.toPx() * progress, 24.dp.toPx() * progress, depthEffect = false)
                    },
                    highlight = { null },
                    onDrawSurface = { drawRect(containerColour) }
                ).then(if (reduced) Modifier else interactiveHighlight.modifier)
                .height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                tabLabels.forEach { tab ->
                    LiquidTab(tab.first, tab.second, colours.primary,
                        lerp(1f, 1.2f, motion.pressProgress), selected = false, interactive = false) { }
                }
            }
        }
        val indicator = if (liquid) Modifier.drawBackdrop(
            backdrop = combinedBackdrop,
            shape = { Capsule() },
            effects = { lens(10.dp.toPx() * motion.pressProgress, 14.dp.toPx() * motion.pressProgress,
                depthEffect = true, chromaticAberration = false) },
            highlight = { null },
            shadow = { Shadow(alpha = motion.pressProgress) },
            innerShadow = { InnerShadow(radius = 8.dp * motion.pressProgress, alpha = motion.pressProgress) },
            layerBlock = {
                val scale = liquidLensScale(motion.scaleX, motion.scaleY, motion.velocity, reduced)
                scaleX = scale.x; scaleY = scale.y
            },
            onDrawSurface = {
                val progress = motion.pressProgress
                drawRect(colours.primaryContainer.copy(alpha = .48f), alpha = 1f - progress)
                drawRect(colours.surfaceContainerHighest.copy(alpha = .12f * progress))
            }
        ) else Modifier.background(colours.primary.copy(alpha = .15f), Capsule())
        Box(Modifier.padding(start = sideInset + 4.dp).width(with(density) { tabWidth.toDp() })
            .graphicsLayer {
                translationX = (if (rtl) -motion.value else motion.value) * tabWidth + panelOffset
            }
            .then(if (liquid && !reduced) interactiveHighlight.gestureModifier else Modifier)
            .then(motion.modifier).then(indicator).height(54.dp).clearAndSetSemantics { })
        // Legibility is a foreground plane. The optical layer must never recolour these glyphs.
        // It has no input or accessibility targets; the original tabs remain the only controls.
        Row(Modifier.padding(horizontal = sideInset + 4.dp).width(barWidth - 8.dp).height(54.dp)
            .graphicsLayer { translationX = panelOffset }
            .clearAndSetSemantics { }
            .drawWithCache {
                val mask = Path()
                onDrawWithContent {
                    val start = if (rtl) size.width - (motion.value + 1f) * tabWidth else motion.value * tabWidth
                    mask.reset()
                    mask.addRoundRect(RoundRect(Rect(start, 0f, start + tabWidth, size.height),
                        CornerRadius(size.height / 2f, size.height / 2f)))
                    val contentScope = this
                    clipPath(mask) { contentScope.drawContent() }
                }
            }, verticalAlignment = Alignment.CenterVertically) {
            tabLabels.forEach { tab ->
                LiquidTab(tab.first, tab.second, colours.onPrimaryContainer,
                    lerp(1f, 1.2f, motion.pressProgress), selected = false, interactive = false) { }
            }
        }
    }
}

@Composable
private fun RowScope.LiquidTab(label: String, icon: ImageVector, colour: Color, scale: Float,
                              selected: Boolean, interactive: Boolean, onSelect: () -> Unit) {
    val interaction = if (interactive) Modifier.semantics { this.selected = selected }
        .clickable(interactionSource = null, indication = null, role = Role.Tab, onClick = onSelect)
        else Modifier.clearAndSetSemantics { }
    Column(Modifier.weight(1f).height(54.dp).clip(Capsule()).then(interaction)
        .graphicsLayer { scaleX = scale; scaleY = scale },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)) {
        Icon(icon, null, Modifier.size(22.dp), tint = colour)
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Normal, color = colour, maxLines = 1)
    }
}

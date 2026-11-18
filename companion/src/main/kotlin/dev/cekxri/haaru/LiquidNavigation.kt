package dev.cekxri.haaru

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Original Haaru glass: one clear track and one soft lens, confined to the nav.
 * The exported track contains only the background surface, excluding the icons
 * and selection. The selection therefore never recursively samples itself.
 */
@Composable
internal fun LiquidNavigation(backdrop: Backdrop, selected: Int, glass: Boolean,
                              reduced: Boolean, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dark = colors.background.luminanceForGlass() < .35f
    val count = 3
    val committed = selected.coerceIn(0, count - 1)
    val selectedNow by rememberUpdatedState(committed)
    val onSelectNow by rememberUpdatedState(onSelect)
    val scope = rememberCoroutineScope()
    val position = remember { Animatable(committed.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(committed.toFloat()) }
    var lastY by remember { mutableFloatStateOf(0f) }
    val interactions = remember { List(count) { MutableInteractionSource() } }
    val pressed = interactions.map { it.collectIsPressedAsState().value }
    val pressedIndex = pressed.indexOfFirst { it }
    val pressure by animateFloatAsState(if (dragging || pressed.any { it }) 1f else 0f,
        if (reduced) snap() else spring(dampingRatio = .76f, stiffness = 560f), label = "glassPressure")
    val rendered = if (dragging) dragPosition else position.value
    val previewIndex = if (pressedIndex >= 0) pressedIndex else committed
    val target = if (dragging) liquidReleaseIndex(dragPosition, count).toFloat() else previewIndex.toFloat()
    val pose = liquidPose(rendered, target, pressure, reduced)
    val surfaceShape = remember { RoundedCornerShape(38.dp) }
    val pillShape = remember { RoundedCornerShape(32.dp) }
    val exportedTrack = rememberLayerBackdrop()
    val restingSpring = remember { spring<Float>(dampingRatio = .78f, stiffness = 380f, visibilityThreshold = .001f) }
    LaunchedEffect(committed, reduced, pressedIndex) {
        if (!dragging) position.animateTo(previewIndex.toFloat(), if (reduced) snap() else restingSpring)
    }

    val track = if (glass) Modifier.drawBackdrop(
        backdrop = backdrop, shape = { surfaceShape },
        effects = { vibrancy(); blur(2.5.dp.toPx()); lens(10.dp.toPx(), 18.dp.toPx(), depthEffect = true) },
        highlight = { Highlight(width = .65.dp, blurRadius = 1.dp,
            style = HighlightStyle.Default(color = Color.White.copy(alpha = if (dark) .46f else .62f),
                angle = pose.lightAngle, falloff = .9f)) },
        shadow = { Shadow(radius = 18.dp, color = Color.Black.copy(alpha = if (dark) .20f else .10f)) },
        exportedBackdrop = exportedTrack,
        onDrawSurface = {
            drawRect(colors.surface.copy(alpha = if (dark) .10f else .16f))
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .065f), Color.Transparent,
                Color.Black.copy(alpha = if (dark) .045f else .02f))))
        }
    ) else Modifier.background(colors.surfaceContainer, surfaceShape)

    BoxWithConstraints(Modifier.widthIn(max = 400.dp).fillMaxWidth().padding(horizontal = 24.dp)
        .then(track).padding(5.dp)) {
        val itemWidth = maxWidth / count
        val rowWidth = with(density) { maxWidth.toPx() }
        val rowHeight = with(density) { 64.dp.toPx() }
        val verticalTolerance = with(density) { 24.dp.toPx() }
        val pill = if (glass) Modifier.drawBackdrop(
            backdrop = exportedTrack, shape = { pillShape },
            effects = {
                vibrancy(); blur(1.1.dp.toPx())
                lens((14f + 2f * pressure).dp.toPx(), (24f + 6f * pressure).dp.toPx(),
                    depthEffect = true, chromaticAberration = true)
            },
            highlight = { Highlight(width = .85.dp, blurRadius = 1.2.dp,
                style = HighlightStyle.Default(color = Color.White.copy(alpha = .62f + .16f * pressure),
                    angle = pose.lightAngle + 14f, falloff = 1.1f)) },
            shadow = { Shadow(radius = 9.dp, color = Color.Black.copy(alpha = if (dark) .18f else .08f)) },
            layerBlock = { scaleX = pose.scaleX; scaleY = pose.scaleY },
            onDrawSurface = {
                drawRect(colors.primary.copy(alpha = if (dark) .16f else .10f))
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .09f + .05f * pressure),
                    Color.Transparent, colors.primary.copy(alpha = .04f))))
            }
        ) else Modifier.background(colors.primaryContainer, pillShape)

        Box(Modifier.absoluteOffset {
            IntOffset((with(density) { itemWidth.toPx() } * liquidPhysicalPosition(rendered, count, rtl) + 4.dp.toPx()).roundToInt(),
                with(density) { (1f + if (glass) pose.lift else 0f).dp.toPx().roundToInt() })
        }.width(itemWidth - 8.dp).height(62.dp).then(pill).clearAndSetSemantics { })

        Row(Modifier.fillMaxWidth().pointerInput(rowWidth, rtl, reduced) {
            detectDragGestures(
                onDragStart = { point ->
                    dragging = true
                    dragPosition = liquidLogicalPosition(point.x, rowWidth, count, rtl)
                    lastY = point.y
                    scope.launch { position.stop() }
                },
                onDrag = { change, _ ->
                    change.consume()
                    dragPosition = liquidLogicalPosition(change.position.x, rowWidth, count, rtl)
                    lastY = change.position.y
                },
                onDragEnd = {
                    val destination = if (liquidCanCommit(lastY, rowHeight, verticalTolerance))
                        liquidReleaseIndex(dragPosition, count) else selectedNow
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        position.snapTo(dragPosition)
                        dragging = false
                        onSelectNow(destination)
                        position.animateTo(destination.toFloat(), if (reduced) snap() else restingSpring)
                    }
                },
                onDragCancel = {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        position.snapTo(dragPosition)
                        dragging = false
                        position.animateTo(selectedNow.toFloat(), if (reduced) snap() else restingSpring)
                    }
                }
            )
        }) {
            listOf("首頁" to HaaruIcons.Home, "工具" to HaaruIcons.Tools, "設定" to HaaruIcons.Settings).forEachIndexed { index, item ->
                val scale by animateFloatAsState(if (pressed[index] && !reduced) .94f else 1f,
                    if (reduced) snap() else spring(dampingRatio = .72f, stiffness = 650f), label = "glassIconPress")
                val visualIndex = if (dragging) liquidReleaseIndex(dragPosition, count) else previewIndex
                Column(Modifier.weight(1f).height(64.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(pillShape).semantics { this.selected = index == committed }
                    .clickable(interactionSource = interactions[index], indication = null, role = Role.Tab) { onSelectNow(index) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val active = index == visualIndex
                    Icon(item.second, null, Modifier.size(23.dp), tint = if (active) colors.primary else colors.onSurfaceVariant)
                    Spacer(Modifier.height(3.dp))
                    Text(item.first, fontSize = 11.sp, color = if (active) colors.onSurface else colors.onSurfaceVariant,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

private fun Color.luminanceForGlass() = .2126f * red + .7152f * green + .0722f * blue

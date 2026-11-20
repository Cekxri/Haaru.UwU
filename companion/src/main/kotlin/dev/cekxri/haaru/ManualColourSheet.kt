/* SPDX-License-Identifier: Apache-2.0 */
package dev.cekxri.haaru

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle
import haaru.ui.*
import java.util.Locale
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Colour edits stay local until the sheet finishes the user's explicit apply action. */
@Composable
internal fun ManualColourSheet(preferences: UiPreferences, reduced: Boolean, dismiss: () -> Unit,
                               apply: (Palette, String) -> Unit) {
    var palette by remember { mutableStateOf(preferences.palette) }
    var custom by remember { mutableStateOf(preferences.customColour.isNotEmpty()) }
    var hsv by remember { mutableStateOf(colourHsv(manualSeed(preferences))) }
    var advanced by remember { mutableStateOf(false) }
    var hexDraft by remember { mutableStateOf(hsv.hex()) }
    fun change(value: HsvColour) { hsv = value; custom = true; hexDraft = value.hex() }
    var pending by remember { mutableStateOf<Pair<Palette, String>?>(null) }
    HaaruSheet(reduced, { pending?.let { apply(it.first, it.second) } ?: dismiss() }) { close ->
        val colours = HaaruTheme.colours
        val fontScale = LocalDensity.current.fontScale
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).imePadding().navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("手動色彩", Modifier.weight(1f), style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.SemiBold, color = colours.onSurface)
                HaaruTextButton(close) { Text("取消", color = colours.primary) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(hsv.colour(), CircleShape)
                        .border(1.dp, colours.outline, CircleShape))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (custom) "自訂色彩" else paletteName(palette), color = colours.onSurface,
                            style = MiuixTheme.textStyles.main, fontWeight = FontWeight.SemiBold)
                        Text(hsv.hex(), style = MiuixTheme.textStyles.footnote1, color = colours.onSurfaceVariant)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth < 320.dp || fontScale > 1.2f) 3 else 5
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Palette.entries.chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { choice ->
                                    val selected = !custom && choice == palette
                                    val contentColour = if (selected) colours.onSecondaryContainer else colours.onSurface
                                    Column(Modifier.weight(1f).clip(RoundedRectangle(12.dp))
                                        .background(if (selected) colours.secondaryContainer else colours.surface)
                                        .selectable(selected, role = Role.RadioButton) {
                                            palette = choice; custom = false
                                            hsv = colourHsv(paletteColour(choice)); hexDraft = hsv.hex()
                                        }.heightIn(min = 76.dp).padding(horizontal = 4.dp, vertical = 8.dp)
                                        .semantics { contentDescription = "${paletteName(choice)}配色" },
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(30.dp).background(paletteColour(choice), CircleShape)
                                                .border(1.dp, colours.outline, CircleShape))
                                            if (selected) {
                                                Spacer(Modifier.width(4.dp))
                                                Icon(HaaruIcons.Check, null, Modifier.size(17.dp), tint = contentColour)
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Text(paletteName(choice), fontSize = 11.sp, lineHeight = 16.sp,
                                            color = contentColour, textAlign = TextAlign.Center)
                                    }
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                HorizontalDivider(color = colours.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(17.dp)) {
                    ColourSlider("色相", hsv.hue, 0f..360f,
                        Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) })) { change(hsv.copy(hue = it)) }
                    ColourSlider("飽和度", hsv.saturation, 0f..1f,
                        Brush.horizontalGradient(listOf(hsv.copy(saturation = 0f).colour(), hsv.copy(saturation = 1f).colour()))) { change(hsv.copy(saturation = it)) }
                    ColourSlider("亮度", hsv.brightness, 0f..1f,
                        Brush.horizontalGradient(listOf(Color.Black, hsv.copy(brightness = 1f).colour()))) { change(hsv.copy(brightness = it)) }
                }
                HaaruTextButton({ advanced = !advanced }) {
                    Text(if (advanced) "收起進階色碼" else "進階色碼", color = colours.primary)
                }
                if (advanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HaaruField(preferences.style, hexDraft, "色碼") { value ->
                            hexDraft = "#" + value.uppercase(Locale.ROOT).filter { it in "0123456789ABCDEF" }.take(6)
                            if (validCustomColour(hexDraft)) change(colourHsv(manualSeed(preferences.copy(customColour = hexDraft))))
                        }
                        if (!validCustomColour(hexDraft)) Text("請輸入六位色碼", color = colours.error,
                            style = MiuixTheme.textStyles.footnote1)
                    }
                }
            }
            HorizontalDivider(color = colours.outlineVariant)
            HaaruButton(preferences.style, "套用色彩", enabled = !advanced || validCustomColour(hexDraft),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                pending = palette to if (custom) hsv.hex() else ""
                close()
            }
        }
    }
}

@Composable
private fun ColourSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, gradient: Brush,
                         change: (Float) -> Unit) {
    val colours = HaaruTheme.colours
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MiuixTheme.textStyles.main, color = colours.onSurface)
            Text(if (range.endInclusive > 1f) "${value.roundToInt()}°" else "${(value * 100).roundToInt()}%",
                style = MiuixTheme.textStyles.footnote1, color = colours.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // These gradients encode hue/saturation/value; they are functional colour spectra.
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(6.dp)
                .background(gradient, RoundedCornerShape(3.dp)))
            Slider(value = value, onValueChange = change, valueRange = range,
                colors = SliderDefaults.sliderColors(foregroundColor = Color.Transparent,
                    backgroundColor = Color.Transparent, thumbColor = colours.primary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label })
        }
    }
}

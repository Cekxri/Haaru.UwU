package dev.cekxri.haaru

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import haaru.ui.*
import java.util.Locale

/** Colour edits stay local to this sheet until the user applies them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualColourSheet(preferences: UiPreferences, reduced: Boolean, dismiss: () -> Unit,
                               apply: (Palette, String) -> Unit) {
    var palette by remember { mutableStateOf(preferences.palette) }
    var custom by remember { mutableStateOf(preferences.customColour.isNotEmpty()) }
    var hsv by remember { mutableStateOf(colourHsv(manualSeed(preferences))) }
    var advanced by remember { mutableStateOf(false) }
    var hexDraft by remember { mutableStateOf(hsv.hex()) }
    fun change(value: HsvColour) { hsv = value; custom = true; hexDraft = value.hex() }
    val content: @Composable () -> Unit = {
        val colours = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().heightIn(max = 700.dp).imePadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("手動色彩", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, color = colours.onSurface)
                TextButton(dismiss) { Text("取消") }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colours.surfaceContainer)
                .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).background(hsv.colour(), CircleShape))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(if (custom) "自訂色彩" else paletteName(palette), color = colours.onSurface,
                        fontWeight = FontWeight.SemiBold)
                    Text("色彩預覽", style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant)
                }
            }
            Palette.entries.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { choice ->
                        val selected = !custom && choice == palette
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(if (selected) colours.secondaryContainer else Color.Transparent)
                            .selectable(selected, role = Role.RadioButton) {
                                palette = choice; custom = false
                                hsv = colourHsv(paletteColour(choice)); hexDraft = hsv.hex()
                            }.padding(vertical = 7.dp).semantics { contentDescription = "${paletteName(choice)}配色" },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(34.dp).background(paletteColour(choice), CircleShape), contentAlignment = Alignment.Center) {
                                if (selected) Icon(HaaruIcons.Check, null, Modifier.size(19.dp),
                                    tint = if (paletteColour(choice).luminance() > .40f) Color.Black.copy(alpha = .7f) else Color.White)
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(paletteName(choice), fontSize = 10.sp, color = colours.onSurface)
                        }
                    }
                }
            }
            HorizontalDivider(color = colours.outlineVariant.copy(alpha = .45f))
            ColourSlider("色相", hsv.hue, 0f..360f,
                Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) })) { change(hsv.copy(hue = it)) }
            ColourSlider("飽和度", hsv.saturation, 0f..1f,
                Brush.horizontalGradient(listOf(hsv.copy(saturation = 0f).colour(), hsv.copy(saturation = 1f).colour()))) { change(hsv.copy(saturation = it)) }
            ColourSlider("亮度", hsv.brightness, 0f..1f,
                Brush.horizontalGradient(listOf(Color.Black, hsv.copy(brightness = 1f).colour()))) { change(hsv.copy(brightness = it)) }
            TextButton({ advanced = !advanced }) { Text(if (advanced) "收起進階色碼" else "進階色碼") }
            if (advanced) {
                HaaruField(preferences.style, hexDraft, "色碼") { value ->
                    hexDraft = "#" + value.uppercase(Locale.ROOT).filter { it in "0123456789ABCDEF" }.take(6)
                    if (validCustomColour(hexDraft)) change(colourHsv(manualSeed(preferences.copy(customColour = hexDraft))))
                }
                if (!validCustomColour(hexDraft)) Text("請輸入六位色碼", color = colours.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            HaaruButton(preferences.style, "套用色彩", enabled = !advanced || validCustomColour(hexDraft),
                modifier = Modifier.fillMaxWidth()) { apply(palette, if (custom) hsv.hex() else "") }
            Spacer(Modifier.height(4.dp))
        }
    }
    if (reduced) Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface) { content() }
        }
    } else ModalBottomSheet(onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) { content() }
}

@Composable
private fun ColourSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, gradient: Brush, change: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(7.dp).background(gradient, RoundedCornerShape(4.dp)))
            Slider(value = value, onValueChange = change, valueRange = range,
                colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
        }
    }
}

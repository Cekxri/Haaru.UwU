package dev.cekxri.haaru

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import haaru.ui.HaaruTheme
import haaru.ui.UiStyle
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

/** Keep the actual Miuix sheet attached until its dismissal motion has finished. */
@Composable
internal fun HaaruSheet(reduced: Boolean, dismissed: () -> Unit, content: @Composable (close: () -> Unit) -> Unit) {
    var showing by remember { mutableStateOf(true) }
    if (reduced) Dialog(dismissed, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            HaaruCard(UiStyle.MIUIX, Modifier.fillMaxWidth()) { content(dismissed) }
        }
    } else WindowBottomSheet(show = showing, backgroundColor = HaaruTheme.colours.surface,
        insideMargin = DpSize(0.dp, 0.dp), onDismissRequest = { showing = false },
        onDismissFinished = dismissed) { content { showing = false } }
}

@Composable
internal fun HaaruConfirmationDialog(title: String, message: String, reduced: Boolean,
                                      dismiss: () -> Unit, confirm: () -> Unit) {
    var showing by remember { mutableStateOf(true) }
    var accepted by remember { mutableStateOf(false) }
    val finished = { if (accepted) confirm() else dismiss() }
    val buttons: @Composable (close: () -> Unit) -> Unit = { close ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HaaruButton(UiStyle.MIUIX, "取消", secondary = true, modifier = Modifier.weight(1f), onClick = close)
            HaaruButton(UiStyle.MIUIX, "確認", modifier = Modifier.weight(1f)) { accepted = true; close() }
        }
    }
    if (reduced) Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = true)) {
        HaaruCard(UiStyle.MIUIX) {
            top.yukonga.miuix.kmp.basic.Text(title, color = HaaruTheme.colours.onSurface)
            top.yukonga.miuix.kmp.basic.Text(message, color = HaaruTheme.colours.onSurfaceVariant)
            buttons(finished)
        }
    } else WindowDialog(show = showing, title = title, summary = message,
        onDismissRequest = { showing = false }, onDismissFinished = finished,
        backgroundColor = HaaruTheme.colours.surfaceContainer) { buttons { showing = false } }
}

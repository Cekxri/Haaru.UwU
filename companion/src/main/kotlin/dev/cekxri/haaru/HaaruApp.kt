package dev.cekxri.haaru

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import haaru.ui.*

private data class Picker(val title: String, val options: List<Pair<String, String>>,
                          val selected: String, val onSelect: (String) -> Unit)
private data class Confirmation(val title: String, val message: String, val confirm: () -> Unit)

@Composable
fun HaaruApp(bridge: UiBridge) {
    val scope = rememberCoroutineScope()
    val state = remember(bridge) { HaaruState(bridge, scope) }
    val loadedPreferences = bridge.loadPreferences()
    var preferences by remember(bridge) { mutableStateOf(UiPreferences.decode(loadedPreferences)) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var selectedSlot by rememberSaveable { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var preferenceNotice by remember { mutableStateOf("") }
    val dark = when (preferences.appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.DARK -> true
        Appearance.LIGHT -> false
    }
    val reduced = preferences.reduceMotion || bridge.systemReducedMotion
    val seed = when (preferences.palette) {
        Palette.MINT -> Color(0xFF527A69)
        Palette.BLUE -> Color(0xFF64798D)
        Palette.VIOLET -> Color(0xFF81708E)
        Palette.ROSE -> Color(0xFFAA727C)
    }
    fun update(value: UiPreferences) {
        if (state.snapshot.readOnly || state.snapshot.upgradePending) {
            preferenceNotice = "請先啟用模組並完成重新啟動。"; return
        }
        preferences = value
        try {
            bridge.savePreferences(value.encode())
            scope.launch {
                bridge.flushPreferences()
                preferenceNotice = if (bridge.preferenceError == null) "外觀已儲存。" else "外觀尚未儲存，請重新試一次。"
            }
        } catch (_: Exception) { preferenceNotice = "外觀尚未儲存，請重新試一次。" }
    }
    LaunchedEffect(loadedPreferences) {
        if (loadedPreferences != null) preferences = UiPreferences.decode(loadedPreferences)
    }
    LaunchedEffect(bridge) { state.run("status") }
    LaunchedEffect(state.snapshot.cards) {
        if (state.snapshot.cards.none { it.slot == selectedSlot }) selectedSlot = state.snapshot.cards.firstOrNull()?.slot ?: 0
    }
    DualTheme(preferences.style, dark, seed, FontFamily.Default) {
        CompositionLocalProvider(LocalHaaruReducedMotion provides reduced) {
        val colors = MaterialTheme.colorScheme
        val backdrop = rememberLayerBackdrop()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(Modifier.fillMaxSize().background(colors.background)) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Brush.verticalGradient(listOf(
                colors.primary.copy(alpha = if (dark) .09f else .04f), Color.Transparent)))
                .drawWithCache {
                    // Quiet café light belongs to the page layer sampled by the glass.
                    // These cached gradients do not animate or allocate a full-screen blur.
                    val lamp = Brush.radialGradient(listOf(colors.tertiary.copy(alpha = if (dark) .15f else .07f), Color.Transparent),
                        center = Offset(size.width * .80f, size.height - 78.dp.toPx()), radius = 190.dp.toPx())
                    val mist = Brush.radialGradient(listOf(colors.primary.copy(alpha = if (dark) .17f else .09f), Color.Transparent),
                        center = Offset(size.width * .20f, size.height - 32.dp.toPx()), radius = 200.dp.toPx())
                    onDrawBehind { drawRect(lamp); drawRect(mist) }
                }) {
                AnimatedContent(page, modifier = Modifier.fillMaxSize(), label = "page",
                    transitionSpec = {
                        if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        else (fadeIn(tween(190)) + slideInVertically(tween(240)) { it / 35 }) togetherWith fadeOut(tween(100))
                    }) { currentPage ->
                    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().imePadding(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 100.dp + bottom),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item("header") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(when (currentPage) { 1 -> "工具"; 2 -> "設定"; else -> "Haaru" },
                                        fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                                    Text(if (currentPage == 0) "城堡之下的SIM國度.咖啡廳☕" else "Haaru · ${state.snapshot.moduleVersion.ifBlank { BuildConfig.VERSION_NAME }}",
                                        color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                                }
                                IconButton(onClick = { state.run("status") }, enabled = !state.busy) {
                                    if (state.busy) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
                                    else Icon(HaaruIcons.Refresh, "重新讀取模組", tint = colors.onSurfaceVariant)
                                }
                            }
                        }
                        when (currentPage) {
                            0 -> {
                                item("status") { StatusHero(state) }
                                if (state.notice.isNotBlank() && (state.fresh || !state.failed || state.notice.startsWith("設定已儲存")))
                                    item("notice") { InlineNotice(state.notice, state.failed) }
                                if (state.fresh && state.snapshot.cards.isEmpty()) item("empty") {
                                    HaaruCard(preferences.style) {
                                        Text("沒有可用的 SIM", fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                                        Text("插入 SIM 後，再重新讀取。", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                        HaaruButton(preferences.style, "重新讀取", !state.busy, secondary = true) { state.run("status") }
                                    }
                                } else if (state.snapshot.cards.isNotEmpty()) {
                                    item("slots") {
                                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            state.snapshot.cards.forEach { sim ->
                                                FilterChip(selectedSlot == sim.slot, { selectedSlot = sim.slot },
                                                    label = { Text("SIM ${sim.slot + 1}") }, leadingIcon = { Icon(HaaruIcons.Sim, null, Modifier.size(17.dp)) })
                                            }
                                        }
                                    }
                                    item("editor-$selectedSlot") {
                                        state.snapshot.cards.firstOrNull { it.slot == selectedSlot }?.let { sim ->
                                            SimEditor(sim, state, preferences.style,
                                                open = { title, options, selected, choose -> picker = Picker(title, options, selected, choose) },
                                                confirm = { title, message, action -> confirmation = Confirmation(title, message, action) })
                                        }
                                    }
                                    item("scope") {
                                        Text("修改系統回報的 SIM 地區與電信商名稱。", color = colors.onSurfaceVariant,
                                            fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                    }
                                }
                            }
                            1 -> {
                                if (state.notice.isNotBlank()) item("notice") { InlineNotice(state.notice, state.failed) }
                                item("tools-title") { SectionTitle("套用與還原") }
                                item("tools") {
                                    HaaruCard(preferences.style) {
                                        ToolRow("重新套用已存方案", "恢復所有已存 SIM 方案。", state.writable) { state.run(if (state.snapshot.enabled) "refresh" else "enable") }
                                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f))
                                        ToolRow("還原全部 SIM", "停止套用，保留已存方案。", !state.busy && !state.snapshot.upgradePending) {
                                            confirmation = Confirmation("還原全部 SIM？", "恢復原始 SIM 回報；已存方案會保留，可稍後重新套用。") { state.run("disable") }
                                        }
                                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f))
                                        ToolRow("清除全部設定", "還原 SIM，並刪除全部已存方案。", !state.busy && !state.snapshot.upgradePending) {
                                            confirmation = Confirmation("清除全部設定？", "所有 SIM 都會恢復原始回報，已存方案也會刪除。") { state.run("clear") }
                                        }
                                    }
                                }
                                item("diagnostics-title") { SectionTitle("診斷") }
                                item("diagnostics") { Diagnostics(state, bridge.preferenceError.orEmpty(), preferences.style) }
                            }
                            2 -> {
                                item("appearance-title") { SectionTitle("外觀") }
                                item("appearance") {
                                    HaaruCard(preferences.style) {
                                        SelectionRow("介面風格", if (preferences.style == UiStyle.MIUIX) "Miuix" else "Material 3", HaaruIcons.Settings) {
                                            picker = Picker("介面風格", listOf("MIUIX" to "Miuix", "MATERIAL3" to "Material 3"), preferences.style.name) { update(preferences.copy(style = UiStyle.valueOf(it))) }
                                        }
                                        SelectionRow("明暗", appearanceName(preferences.appearance), HaaruIcons.Cup) {
                                            picker = Picker("明暗", Appearance.entries.map { it.name to appearanceName(it) }, preferences.appearance.name) { update(preferences.copy(appearance = Appearance.valueOf(it))) }
                                        }
                                        SelectionRow("色調", paletteName(preferences.palette), HaaruIcons.Globe) {
                                            picker = Picker("色調", Palette.entries.map { it.name to paletteName(it) }, preferences.palette.name) { update(preferences.copy(palette = Palette.valueOf(it))) }
                                        }
                                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f))
                                        SettingSwitch(preferences.style, "液態玻璃導航", "背景折射與水滴動態", preferences.glass) { update(preferences.copy(glass = it)) }
                                        SettingSwitch(preferences.style, "減少動態效果", if (bridge.systemReducedMotion) "系統目前也已關閉動畫" else "保留切換，減少動畫", preferences.reduceMotion) { update(preferences.copy(reduceMotion = it)) }
                                    }
                                }
                                if (preferenceNotice.isNotBlank() || bridge.preferenceError != null) item("prefs-notice") {
                                    InlineNotice(if (bridge.preferenceError != null) "外觀尚未儲存，等模組服務連上後再試。" else preferenceNotice, bridge.preferenceError != null)
                                }
                                item("about-title") { SectionTitle("關於") }
                                item("about") {
                                    HaaruCard(preferences.style) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(HaaruIcons.Cup, null, Modifier.size(36.dp), tint = colors.primary)
                                            Spacer(Modifier.width(12.dp))
                                            Column {
                                                Text("Haaru", fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                                                Text("${state.snapshot.moduleVersion.ifBlank { BuildConfig.VERSION_NAME }} · Cekxri", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                            }
                                        }
                                        Text("城堡之下的SIM國度.咖啡廳☕", color = colors.onSurfaceVariant, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 12.dp)) {
                HaaruNavigation(backdrop, page, preferences.glass, reduced) { page = it }
            }
            picker?.let { request ->
                PickerSheet(request, preferences.style, reduced, { picker = null }) { key -> picker = null; request.onSelect(key) }
            }
            confirmation?.let { request ->
                AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(request.title) },
                    text = { Text(request.message) }, confirmButton = {
                        TextButton(onClick = { confirmation = null; request.confirm() }) { Text("確認") }
                    }, dismissButton = { TextButton({ confirmation = null }) { Text("取消") } })
            }
        }
        }
    }
}

@Composable
private fun StatusHero(state: HaaruState) {
    val colors = MaterialTheme.colorScheme
    val snapshot = state.snapshot
    val engineError = snapshot.globalEngine?.error?.isNotBlank() == true
    val title = when {
        state.busy && !state.fresh -> "讀取～中 ☕"
        snapshot.upgradePending -> "更新完成，等你重啟～"
        snapshot.readOnly -> "Haaru 目前已停用"
        !state.fresh && state.diagnostics.contains("安裝") -> "還沒安裝模組～🍞"
        !state.fresh -> "等模組連上～☕"
        snapshot.globalConfirmed && snapshot.enabled -> "運行～中 🍧"
        snapshot.globalConfirmed -> "原始 SIM 已還原"
        engineError -> "引擎還沒準備好～🍛"
        !snapshot.enabled -> "未設定～哦 🍞"
        else -> "等系統確認～☕"
    }
    val subtitle = when {
        snapshot.upgradePending -> "重新啟動手機後，就能使用新版。"
        snapshot.readOnly -> "啟用 Haaru 模組後，重新啟動手機。"
        !state.fresh && state.busy -> "模組服務正在連接。"
        !state.fresh -> friendlyError(state.diagnostics)
        snapshot.globalConfirmed -> if (snapshot.enabled) "SIM 方案已由系統確認。" else "已停止套用，已存方案仍保留。"
        engineError -> friendlyEngineError(snapshot.globalEngine?.error.orEmpty())
        !snapshot.enabled -> "選好 SIM 的地區，再儲存就好了。"
        else -> "方案已儲存，尚未確認生效。"
    }
    val container = if (state.fresh && engineError) colors.errorContainer.copy(alpha = .65f) else colors.primaryContainer
    val content = if (state.fresh && engineError) colors.onErrorContainer else colors.onPrimaryContainer
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(container).heightIn(min = 118.dp).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = content)
            Text(subtitle, fontSize = 12.sp, lineHeight = 18.sp, color = content.copy(alpha = .77f))
        }
        Icon(if (snapshot.globalConfirmed && snapshot.enabled) HaaruIcons.Check else HaaruIcons.Cup, null,
            Modifier.padding(start = 10.dp).size(42.dp), tint = content.copy(alpha = .4f))
    }
}

@Composable
private fun InlineNotice(message: String, failed: Boolean) {
    Text(message, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SimEditor(sim: SimCard, state: HaaruState, style: UiStyle,
                      open: (String, List<Pair<String, String>>, String, (String) -> Unit) -> Unit,
                      confirm: (String, String, () -> Unit) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val draft = state.draft(sim.slot)
    val profile = state.snapshot.profiles.firstOrNull { it.slot == sim.slot }
    var allCarriers by rememberSaveable(sim.slot) { mutableStateOf(false) }
    val countryOptions = remember { listOf("" to "保持原始 SIM 國家", "custom" to "自訂國家碼") + HaaruPresets.countries.map { it.code to HaaruPresets.countryLabel(it.code) } }
    val carrierOptions = remember(draft.country, allCarriers) {
        listOf("" to "保持原始電信商名稱", "custom" to "自訂電信商名稱") +
            HaaruPresets.carriers.filter { allCarriers || draft.country.isBlank() || it.region == draft.country }
                .map { it.id to HaaruPresets.carrierLabel(it) }
    }
    HaaruCard(style) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SIM ${sim.slot + 1}", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Text(sim.originalCarrierName.ifBlank { "原始電信商" }, fontSize = 12.sp, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (sim.country.isNotBlank()) Text(sim.country, fontSize = 12.sp, color = colors.primary)
        }
        if (!sim.ready) Text("暫時讀不到這張 SIM，請重新讀取。", color = colors.error, style = MaterialTheme.typography.bodySmall)
        SelectionRow("國家", if (draft.customCountry) "自訂 · ${draft.country.ifBlank { "待輸入" }}" else
            draft.country.takeIf { it.isNotBlank() }?.let(HaaruPresets::countryLabel) ?: "保持原始 SIM 國家", HaaruIcons.Globe, !state.busy) {
            open("選擇國家", countryOptions, if (draft.customCountry) "custom" else draft.country) { choice ->
                state.edit(sim.slot) { if (choice == "custom") it.copy(customCountry = true) else it.copy(country = choice, customCountry = false) }
            }
        }
        if (draft.customCountry) HaaruField(style, draft.country, "兩位國家碼，例如 GB") { value ->
            state.edit(sim.slot) { it.copy(country = value.uppercase().filter { c -> c in 'A'..'Z' }.take(2)) }
        }
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f))
        SelectionRow("電信商", draft.carrier.ifBlank { "保持原始電信商名稱" }, HaaruIcons.Sim, !state.busy) {
            open("選擇電信商", carrierOptions, if (draft.customCarrier) "custom" else HaaruPresets.carriers.firstOrNull { it.displayName == draft.carrier }?.id.orEmpty()) { choice ->
                state.edit(sim.slot) { when (choice) {
                    "custom" -> it.copy(customCarrier = true)
                    "" -> it.copy(carrier = "", customCarrier = false)
                    else -> it.copy(carrier = HaaruPresets.carriers.first { preset -> preset.id == choice }.displayName, customCarrier = false)
                } }
            }
        }
        if (draft.customCarrier) HaaruField(style, draft.carrier, "自訂名稱，最多 80 字元") { value ->
            state.edit(sim.slot) { it.copy(carrier = value.filter { c -> c.code >= 32 && c.code != 127 }.take(80)) }
        }
        SettingSwitch(style, "顯示全部電信商", "保留 74 個預設名稱", allCarriers) { allCarriers = it }
        HaaruButton(style, if (state.busy) "正在確認…" else "儲存 SIM ${sim.slot + 1}",
            state.writable && sim.ready && (draft.country.isNotBlank() || draft.carrier.isNotBlank()) &&
                (draft.country.isBlank() || draft.country.matches(Regex("[A-Z]{2}"))), modifier = Modifier.fillMaxWidth()) {
            state.run("set", sim.slot, draft.country, draft.carrier)
        }
        if (profile != null) {
            Text(if (profile.enabled) "已存方案已啟用" else "已存方案已停止套用", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (!profile.enabled && (profile.country.isNotBlank() || profile.carrier.isNotBlank())) HaaruButton(style, "重新套用這張 SIM 的已存方案", state.writable,
                secondary = true, modifier = Modifier.fillMaxWidth()) { state.run("set", sim.slot, profile.country, profile.carrier) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { confirm("還原 SIM ${sim.slot + 1}？", "恢復原始 SIM 回報，已存方案會保留。") { state.run("reset", sim.slot) } },
                    enabled = !state.busy && !state.snapshot.upgradePending, modifier = Modifier.weight(1f)) { Text("還原 SIM") }
                TextButton(onClick = { confirm("清除 SIM ${sim.slot + 1} 設定？", "恢復原始 SIM 回報，並刪除此卡的已存方案。") { state.run("forget", sim.slot) } },
                    enabled = !state.busy && !state.snapshot.upgradePending, modifier = Modifier.weight(1f)) { Text("清除設定") }
            }
        }
    }
}

@Composable
private fun ToolRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Diagnostics(state: HaaruState, preferenceError: String, style: UiStyle) {
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    HaaruCard(style) {
        Text("引擎與模組", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text("Android ${state.snapshot.globalEngine?.sdk ?: "—"} · Hook ${state.snapshot.globalEngine?.hooks ?: 0} · ${if (state.snapshot.globalConfirmed) "已確認" else "未確認"}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ expanded = !expanded }) { Text(if (expanded) "收起詳細資料" else "查看詳細資料") }
            TextButton({
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                    ClipData.newPlainText("Haaru diagnostics", state.diagnostics + if (preferenceError.isNotBlank()) "\nPreferences: $preferenceError" else ""))
                copied = true
            }, enabled = state.diagnostics.isNotBlank()) { Text(if (copied) "已複製" else "複製診斷") }
        }
        if (expanded) androidx.compose.foundation.text.selection.SelectionContainer {
            Text(state.diagnostics.ifBlank { "尚未讀取診斷。" }, Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(request: Picker, style: UiStyle, reduced: Boolean, dismiss: () -> Unit, select: (String) -> Unit) {
    val content: @Composable () -> Unit = {
        var query by remember(request.title) { mutableStateOf("") }
        val options = remember(request.options, query) {
            request.options.filter { it.first.contains(query.trim(), true) || it.second.contains(query.trim(), true) }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding().navigationBarsPadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(request.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                TextButton(dismiss) { Text("取消") }
            }
            HaaruField(style, query, "搜尋") { query = it.take(120) }
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 20.dp)) {
                if (options.isEmpty()) item { Text("沒有符合的選項", Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(options, key = { it.first }) { option ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { select(option.first) }
                        .heightIn(min = 56.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(request.selected == option.first, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(option.second, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    if (reduced) Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.padding(top = 16.dp)) { content() }
            }
        }
    } else ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) { content() }
}

private fun appearanceName(value: Appearance) = when (value) { Appearance.SYSTEM -> "跟隨系統"; Appearance.LIGHT -> "淺色"; Appearance.DARK -> "深色" }
private fun paletteName(value: Palette) = when (value) { Palette.MINT -> "薄荷"; Palette.BLUE -> "霧藍"; Palette.VIOLET -> "淡紫"; Palette.ROSE -> "玫瑰" }

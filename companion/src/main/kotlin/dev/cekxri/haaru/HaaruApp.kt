/* SPDX-License-Identifier: Apache-2.0 */
package dev.cekxri.haaru

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import haaru.ui.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    var colourPicker by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var preferenceNotice by remember { mutableStateOf("") }
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(view.context) { view.context.haaruActivity() }
    var foreground by remember { mutableStateOf(true) }
    val scene = remember(context) { SculptureController(context) }
    val density = LocalDensity.current.density
    val scrollOffsets = remember { FloatArray(4) }
    var sceneBounds by remember { mutableStateOf(Rect.Zero) }
    val lists = List(3) { rememberLazyListState() }
    val dark = when (preferences.appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.DARK -> true
        Appearance.LIGHT -> false
    }
    val reduced = preferences.reduceMotion || bridge.systemReducedMotion
    BackHandler(enabled = page == 3) { page = 2 }
    DisposableEffect(scene, activity) {
        val owner = activity as? androidx.lifecycle.LifecycleOwner
        val observer = LifecycleEventObserver { source, _ ->
            foreground = source.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer); scene.close() }
    }
    SideEffect {
        activity?.let {
            val controller = WindowCompat.getInsetsController(it.window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    fun update(value: UiPreferences) {
        if (state.snapshot.readOnly || state.snapshot.upgradePending) {
            preferenceNotice = if (state.snapshot.upgradePending) "更新完成後，請重新啟動手機再操作。"
                else "模組目前唯讀，請先確認 Haaru 已啟用。"
            return
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
    MiuixAppTheme(dark, manualSeed(preferences), HaaruDisplayFont,
        dynamicColour = preferences.dynamicColour, colourStyle = preferences.colourStyle) {
        CompositionLocalProvider(LocalHaaruReducedMotion provides reduced) {
            val colours = HaaruTheme.colours
            val backdrop = rememberLayerBackdrop()
            SideEffect {
                scene.update(page, colours, reduced, foreground)
                scene.scroll(scrollOffsets[page], density)
            }
            BoxWithConstraints(Modifier.fillMaxSize().background(colours.background)
                .pointerInput(scene, sceneBounds) {
                    try { awaitPointerEventScope {
                        var following = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            val p = change.position
                            if (change.pressed && !change.previousPressed) {
                                following = sceneBounds.contains(p) &&
                                    p.y > sceneBounds.top + sceneBounds.height * .16f &&
                                    p.y < sceneBounds.top + sceneBounds.height * .84f
                            }
                            if (following && sceneBounds.width > 0f && sceneBounds.height > 0f) {
                                scene.pointer((p.x - sceneBounds.left) / sceneBounds.width,
                                    (p.y - sceneBounds.top) / sceneBounds.height, change.pressed)
                            }
                            if (!change.pressed) following = false
                        }
                    } } finally { scene.pointer(.67f, .51f, false) }
                }) {
                val expanded = maxWidth >= 700.dp
                val visualWidth = if (expanded) (maxWidth * .40f).coerceAtMost(520.dp) else maxWidth
                val workInset = if (expanded) visualWidth else 0.dp
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                    SculptureScene(scene, Modifier.align(if (expanded) Alignment.TopStart else Alignment.TopCenter)
                        .padding(start = if (expanded && page != 3) 80.dp else 0.dp)
                        .statusBarsPadding().width(if (expanded) visualWidth - 80.dp else visualWidth)
                        .height(if (expanded) 520.dp else 370.dp)
                        .onGloballyPositioned { sceneBounds = it.boundsInRoot() })
                    AnimatedContent(page, modifier = Modifier.fillMaxSize().padding(start = workInset), label = "nativeSurface",
                        transitionSpec = {
                            val direction = if (targetState > initialState) 1 else -1
                            if (reduced) EnterTransition.None togetherWith ExitTransition.None
                            else slideInHorizontally(spring(dampingRatio = .86f, stiffness = 220f)) { direction * it / 3 } togetherWith
                                slideOutHorizontally(spring(dampingRatio = .92f, stiffness = 240f)) { -direction * it / 4 }
                        }) { currentPage ->
                        if (currentPage == 3) {
                            AboutPage(state.snapshot.moduleVersion, { page = 2 }) { offset ->
                                scrollOffsets[3] = offset
                                if (page == 3) scene.scroll(offset, density)
                            }
                        } else {
                            val scrollConnection = remember(currentPage) {
                                object : NestedScrollConnection {
                                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                        scrollOffsets[currentPage] = (scrollOffsets[currentPage] - consumed.y).coerceAtLeast(0f)
                                        if (page == currentPage) scene.scroll(scrollOffsets[currentPage], density)
                                        return Offset.Zero
                                    }
                                }
                            }
                            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                            LazyColumn(state = lists[currentPage],
                                modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding().nestedScroll(scrollConnection),
                                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp,
                                    bottom = if (expanded) 30.dp + bottom else 104.dp + bottom),
                                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                item("header") { NativePageHeader(currentPage, state) }
                                if (!expanded) item("sculpturalSpace") { Spacer(Modifier.height(192.dp)) }
                                item("context") { PageContext(currentPage, state) }
                                when (currentPage) {
                                    0 -> {
                                        if (state.notice.isNotBlank() && (state.fresh || !state.failed || state.notice.startsWith("設定已儲存")))
                                            item("notice") { InlineNotice(state.notice, state.failed) }
                                        if (state.fresh && state.snapshot.cards.isEmpty()) item("empty") {
                                            HaaruCard(UiStyle.MIUIX) {
                                                Text("沒有可用的 SIM", color = colours.onSurface, fontWeight = FontWeight.SemiBold)
                                                Text("插入 SIM 後重新讀取，這裡會顯示可編輯的卡片。", color = colours.onSurfaceVariant)
                                                HaaruButton(UiStyle.MIUIX, "重新讀取", !state.busy, secondary = true) { state.run("status") }
                                            }
                                        }
                                        if (state.snapshot.cards.isNotEmpty()) {
                                            item("slots") {
                                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    state.snapshot.cards.forEach { card ->
                                                        val selected = selectedSlot == card.slot
                                                        HaaruSimChip(selected, { selectedSlot = card.slot }) {
                                                            Text("SIM ${card.slot + 1}", color = if (selected) colours.onPrimaryContainer else colours.onSurface,
                                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                                        }
                                                    }
                                                }
                                            }
                                            item("plan-$selectedSlot") {
                                                state.snapshot.cards.firstOrNull { it.slot == selectedSlot }?.let { card ->
                                                    SimPlanPanel(card, state,
                                                        open = { title, options, selected, select -> picker = Picker(title, options, selected, select) },
                                                        confirm = { title, message, action -> confirmation = Confirmation(title, message, action) })
                                                }
                                            }
                                            item("scope") {
                                                Text("只改變系統回報的 SIM 地區與電信商名稱。", color = colours.onSurfaceVariant,
                                                    fontSize = 13.sp, lineHeight = 19.sp)
                                            }
                                        }
                                    }
                                    1 -> {
                                        if (state.notice.isNotBlank()) item("notice") { InlineNotice(state.notice, state.failed) }
                                        item("tools") {
                                            ToolsPanel(state, bridge.preferenceError.orEmpty()) { title, message, action ->
                                                confirmation = Confirmation(title, message, action)
                                            }
                                        }
                                    }
                                    2 -> {
                                        item("appearance") {
                                            AppearancePanel(preferences, state,
                                                onAppearance = { picker = Picker("明暗", Appearance.entries.map { it.name to appearanceName(it) },
                                                    preferences.appearance.name) { update(preferences.copy(appearance = Appearance.valueOf(it))) } },
                                                onStyle = { picker = Picker("色彩風格", ColourStyle.entries.map { it.name to colourStyleName(it) },
                                                    preferences.colourStyle.name) { update(preferences.copy(colourStyle = ColourStyle.valueOf(it))) } },
                                                onPalette = { colourPicker = true }, onUpdate = ::update)
                                        }
                                        if (preferenceNotice.isNotBlank() || bridge.preferenceError != null) item("preferenceNotice") {
                                            InlineNotice(if (bridge.preferenceError != null) "外觀尚未儲存，等模組服務連上後再試。" else preferenceNotice,
                                                bridge.preferenceError != null)
                                        }
                                        item("about") { AboutInformation(state.snapshot.moduleVersion) { page = 3 } }
                                    }
                                }
                            }
                        }
                    }
                }
                if (page != 3) {
                    if (expanded) HaaruNavigationRail(page) { page = it }
                    else Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 12.dp)) {
                        HaaruNavigation(backdrop, page, preferences.glass, reduced) { page = it }
                    }
                }
                picker?.let { request ->
                    PickerSheet(request, UiStyle.MIUIX, reduced, { picker = null }) { key -> picker = null; request.onSelect(key) }
                }
                if (colourPicker) ManualColourSheet(preferences, reduced, { colourPicker = false }) { palette, custom ->
                    colourPicker = false
                    update(preferences.copy(palette = palette, customColour = custom, dynamicColour = false))
                }
                confirmation?.let { request ->
                    HaaruConfirmationDialog(request.title, request.message, reduced,
                        dismiss = { confirmation = null }, confirm = { confirmation = null; request.confirm() })
                }
            }
        }
    }
}

@Composable
private fun NativePageHeader(page: Int, state: HaaruState) {
    val c = HaaruTheme.colours
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when (page) { 1 -> "工具"; 2 -> "設定"; else -> "Haaru" }, fontFamily = HaaruDisplayFont,
                fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, color = c.onBackground)
            Text(if (page == 0) "城堡之下的SIM國度.咖啡廳☕" else "Haaru · ${state.snapshot.moduleVersion.ifBlank { BuildConfig.VERSION_NAME }}",
                fontSize = 12.sp, lineHeight = 18.sp, color = c.onSurfaceVariant)
        }
        IconButton(onClick = { state.run("status") }, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(HaaruIcons.Refresh, "重新讀取模組", Modifier.size(23.dp), tint = c.onSurface)
        }
    }
}

@Composable
private fun PageContext(page: Int, state: HaaruState) {
    val c = HaaruTheme.colours
    val presentation = presentStatus(state)
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(when (page) { 1 -> "讓方案回到你需要的樣子"; 2 -> "讓每種材質，跟著你的色彩"; else -> presentation.title },
            color = if (page == 0 && presentation.error) c.error else c.onBackground,
            fontSize = if (page == 0) 24.sp else 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
        Text(when (page) {
            1 -> "重新套用、還原與清除各有不同作用；詳細診斷在下方。"
            2 -> "跟隨桌布，也可以選一種只屬於你的手動配色。"
            else -> presentation.description
        }, color = c.onSurfaceVariant, fontSize = 14.sp, lineHeight = 22.sp)
    }
}

@Composable
private fun AppearancePanel(preferences: UiPreferences, state: HaaruState, onAppearance: () -> Unit,
                            onStyle: () -> Unit, onPalette: () -> Unit, onUpdate: (UiPreferences) -> Unit) {
    val c = HaaruTheme.colours
    val enabled = !state.snapshot.readOnly && !state.snapshot.upgradePending
    HaaruCard(UiStyle.MIUIX) {
        SelectionRow("明暗", appearanceName(preferences.appearance), HaaruIcons.Cup, enabled, onAppearance)
        HorizontalDivider(color = c.outlineVariant)
        SelectionRow("色彩風格", colourStyleName(preferences.colourStyle), HaaruIcons.Settings, enabled, onStyle)
        SettingSwitch(UiStyle.MIUIX, "Google Monet 動態色彩",
            if (LocalDynamicColourActive.current) "正在使用系統桌布色彩"
            else if (!preferences.dynamicColour) "已關閉，可選擇手動色彩"
            else "系統未提供桌布色彩，使用手動配色", preferences.dynamicColour, enabled) {
            onUpdate(preferences.copy(dynamicColour = it))
        }
        SelectionRow("手動色彩", if (preferences.customColour.isNotEmpty()) preferences.customColour else paletteName(preferences.palette),
            HaaruIcons.Globe, enabled && !preferences.dynamicColour, onPalette)
        HorizontalDivider(color = c.outlineVariant)
        SettingSwitch(UiStyle.MIUIX, "液態玻璃導航", "背景取樣與折射", preferences.glass, enabled) {
            onUpdate(preferences.copy(glass = it))
        }
        SettingSwitch(UiStyle.MIUIX, "減少動態效果", "停止模型漂浮，直接切換畫面", preferences.reduceMotion, enabled) {
            onUpdate(preferences.copy(reduceMotion = it))
        }
    }
}

@Composable
private fun InlineNotice(message: String, failed: Boolean) {
    Text(message, Modifier.fillMaxWidth().padding(horizontal = 2.dp), fontSize = 13.sp, lineHeight = 20.sp,
        color = if (failed) HaaruTheme.colours.error else HaaruTheme.colours.onSurfaceVariant)
}

@Composable
private fun HaaruNavigationRail(selected: Int, onSelect: (Int) -> Unit) {
    val c = HaaruTheme.colours
    Column(Modifier.width(80.dp).fillMaxHeight().statusBarsPadding().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf("首頁", "工具", "設定").forEachIndexed { index, name ->
            Column(Modifier.width(64.dp).clip(RoundedCornerShape(18.dp))
                .background(if (selected == index) c.primaryContainer else c.background)
                .semantics { this.selected = selected == index }
                .clickable(role = Role.Tab) { onSelect(index) }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(listOf(HaaruIcons.Home, HaaruIcons.Tools, HaaruIcons.Settings)[index], null,
                    Modifier.size(24.dp), tint = if (selected == index) c.onPrimaryContainer else c.onSurface)
                Spacer(Modifier.height(7.dp))
                Text(name, color = if (selected == index) c.onPrimaryContainer else c.onSurface,
                    fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PickerSheet(request: Picker, style: UiStyle, reduced: Boolean, dismiss: () -> Unit, select: (String) -> Unit) {
    var pending by remember { mutableStateOf<String?>(null) }
    HaaruSheet(reduced, { pending?.let(select) ?: dismiss() }) { close ->
        var query by remember(request.title) { mutableStateOf("") }
        val options = remember(request.options, query) {
            request.options.filter { it.first.contains(query.trim(), true) || it.second.contains(query.trim(), true) }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding().navigationBarsPadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(request.title, Modifier.weight(1f), style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.SemiBold,
                    color = HaaruTheme.colours.onSurface)
                HaaruTextButton(close) { Text("取消") }
            }
            HaaruField(style, query, "搜尋") { query = it.take(120) }
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 20.dp)) {
                if (options.isEmpty()) item { Text("沒有符合的選項", Modifier.padding(vertical = 20.dp), color = HaaruTheme.colours.onSurfaceVariant) }
                items(options, key = { it.first }) { option ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { pending = option.first; close() }
                        .heightIn(min = 56.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(request.selected == option.first, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(option.second, Modifier.weight(1f), color = HaaruTheme.colours.onSurface,
                            style = MiuixTheme.textStyles.body2)
                    }
                }
            }
        }
    }

}

private fun appearanceName(value: Appearance) = when (value) { Appearance.SYSTEM -> "跟隨系統"; Appearance.LIGHT -> "淺色"; Appearance.DARK -> "深色" }

private tailrec fun Context.haaruActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.haaruActivity() else null
    else -> null
}

package dev.cekxri.haaru

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import haaru.ui.HaaruTheme
import haaru.ui.HaaruPresets
import haaru.ui.SimCard
import haaru.ui.SimPlanInput
import haaru.ui.simSelectionToken
import haaru.ui.SimProfile
import haaru.ui.UiStyle
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import java.util.Locale
import kotlinx.serialization.json.*

/** The editor owns only local selection state. All durable actions use HaaruState. */
@Composable
internal fun SimPlanPanel(
    sim: SimCard,
    state: HaaruState,
    open: (String, List<Pair<String, String>>, String, (String) -> Unit) -> Unit,
    confirm: (String, String, () -> Unit) -> Unit
) {
    val colours = HaaruTheme.colours
    val draft = state.draft(sim.slot)
    val profile = state.snapshot.profiles.firstOrNull { it.slot == sim.slot }
    val enabledProfile = profile?.takeIf { it.enabled && it.matchesCurrentSim == true }
    val keepsCountry = !enabledProfile?.country.isNullOrBlank()
    val keepsCarrier = !enabledProfile?.carrier.isNullOrBlank()
    val stableCard = simSelectionToken.matches(sim.selectionToken)
    val canEdit = stableCard && !state.busy
    val emptyCountryLabel = if (keepsCountry) "保留已存國家" else "保持原始 SIM 國家"
    val emptyCarrierLabel = if (keepsCarrier) "保留已存電信商名稱" else "保持原始電信商名稱"
    var allCarriers by rememberSaveable(sim.slot, sim.selectionToken) { mutableStateOf(false) }
    val countries = remember(emptyCountryLabel) {
        listOf("" to emptyCountryLabel, "custom" to "自訂國家碼") +
            HaaruPresets.countries.map { it.code to HaaruPresets.countryLabel(it.code) }
    }
    val carriers = remember(draft.country, allCarriers, emptyCarrierLabel) {
        listOf("" to emptyCarrierLabel, "custom" to "自訂電信商名稱") +
            HaaruPresets.carriers.filter { allCarriers || draft.country.isBlank() || it.region == draft.country }
                .map { it.id to HaaruPresets.carrierLabel(it) }
    }
    val countryValid = SimPlanInput.validCountry(draft.country)
    val carrierValid = SimPlanInput.validCarrier(draft.carrier)
    val canSave = state.writable && stableCard && sim.ready && countryValid && carrierValid &&
        (draft.country.isNotBlank() || draft.carrier.isNotBlank())
    val savedDraft = draftFromProfile(enabledProfile)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SIM ${sim.slot + 1} 方案", fontSize = 21.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold, color = colours.onSurface)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 340.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.2f) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(Modifier.weight(.35f)) { PlanReadout("SIM 回報國家", sim.country.ifBlank { "系統未提供" }) }
                        Column(Modifier.weight(.65f)) { PlanReadout("原始電信商名稱", sim.originalCarrierName.ifBlank { "尚未讀取" }) }
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlanReadout("SIM 回報國家", sim.country.ifBlank { "系統未提供" })
                    PlanReadout("原始電信商名稱", sim.originalCarrierName.ifBlank { "尚未讀取" })
                }
            }
            if (!sim.ready) PlanMessage("暫時讀不到這張 SIM，請重新讀取後再儲存。", error = true)
        }

        HaaruCard(UiStyle.MIUIX) {
            SelectionRow("國家", if (draft.customCountry) "自訂 · ${draft.country.ifBlank { "待輸入" }}"
                else draft.country.takeIf { it.isNotBlank() }?.let(HaaruPresets::countryLabel)
                    ?: emptyCountryLabel, HaaruIcons.Globe, enabled = canEdit) {
                open("選擇國家", countries, if (draft.customCountry) "custom" else draft.country) { choice ->
                    state.edit(sim.slot, sim.selectionToken) {
                        if (choice == "custom") it.copy(customCountry = true)
                        else it.copy(country = choice, customCountry = false)
                    }
                }
            }
            if (draft.customCountry) HaaruField(UiStyle.MIUIX, draft.country, "兩位國家碼，例如 GB") { value ->
                state.edit(sim.slot, sim.selectionToken) { it.copy(country = value.uppercase(Locale.ROOT).filter { c -> c in 'A'..'Z' }.take(2)) }
            }
            HorizontalDivider(color = colours.outlineVariant)
            SelectionRow("電信商名稱", draft.carrier.ifBlank { emptyCarrierLabel }, HaaruIcons.Sim,
                enabled = canEdit) {
                val selected = if (draft.customCarrier) "custom"
                    else HaaruPresets.carriers.firstOrNull { it.displayName == draft.carrier }?.id.orEmpty()
                open("選擇電信商", carriers, selected) { choice ->
                    state.edit(sim.slot, sim.selectionToken) {
                        when (choice) {
                            "custom" -> it.copy(customCarrier = true)
                            "" -> it.copy(carrier = "", customCarrier = false)
                            else -> it.copy(carrier = HaaruPresets.carriers.first { preset -> preset.id == choice }.displayName,
                                customCarrier = false)
                        }
                    }
                }
            }
            if (draft.customCarrier) HaaruField(UiStyle.MIUIX, draft.carrier, "自訂名稱，最多 80 字元") { value ->
                state.edit(sim.slot, sim.selectionToken) { it.copy(carrier = SimPlanInput.limitCarrier(value)) }
            }
            SettingSwitch(UiStyle.MIUIX, "顯示全部電信商", "關閉時依所選國家篩選", allCarriers, enabled = canEdit) {
                allCarriers = it
            }
            PlanMessage("這些選擇是草稿。儲存後，還需等系統確認是否已套用。")
            if (keepsCountry || keepsCarrier) PlanMessage("已啟用方案的空白欄位會保留已存值；需要完整恢復原始回報時，請使用「還原 SIM」。")
            HaaruButton(UiStyle.MIUIX, if (state.busy) "正在確認…" else "儲存 SIM ${sim.slot + 1}",
                enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                state.run("set", sim.slot, draft.country, draft.carrier, sim.selectionToken)
            }
            val reason = when {
                state.busy -> ""
                state.snapshot.upgradePending -> "更新完成後，請完整重新啟動手機再操作。"
                state.snapshot.readOnly -> "模組目前唯讀，請先在模組管理器確認 Haaru 已啟用。"
                !state.fresh -> "請先重新讀取模組狀態。"
                !sim.ready -> "這張 SIM 尚未準備好，暫時不能儲存。"
                !stableCard -> "模組尚未提供 SIM 身分確認。請更新模組並重新啟動手機。"
                !countryValid -> "請輸入有效的兩位 ISO 國家碼，例如 GB。"
                !carrierValid -> "電信商名稱限 80 字元內的單行文字。"
                draft.country.isBlank() && draft.carrier.isBlank() -> "選擇國家或電信商名稱後，就可以儲存。"
                else -> ""
            }
            if (reason.isNotBlank()) PlanMessage(reason)
            if (draft != savedDraft) HaaruTextButton(
                onClick = { state.edit(sim.slot, sim.selectionToken) { savedDraft } },
                enabled = state.fresh && canEdit, modifier = Modifier.fillMaxWidth()
            ) { Text(if (enabledProfile != null) "載入已啟用方案" else "清空未儲存草稿", color = if (state.fresh && canEdit) colours.onSurfaceVariant
                else colours.onSurface.copy(alpha = .38f)) }
        }

        if (profile != null) SavedSimPlan(sim, profile, state, confirm)
    }
}

@Composable
private fun SavedSimPlan(sim: SimCard, profile: SimProfile, state: HaaruState,
                         confirm: (String, String, () -> Unit) -> Unit) {
    val slot = sim.slot
    val colours = HaaruTheme.colours
    val canRestore = !state.busy && !state.snapshot.upgradePending
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("已儲存方案", fontSize = 18.sp, lineHeight = 25.sp,
            fontWeight = FontWeight.SemiBold, color = colours.onSurface)
        PlanReadout("國家", profile.country.takeIf { it.isNotBlank() }?.let(HaaruPresets::countryLabel) ?: "保持原始 SIM 國家")
        PlanReadout("電信商名稱", profile.carrier.ifBlank { "保持原始電信商名稱" })
        PlanMessage(if (profile.enabled) "此方案已啟用。是否生效，以系統確認狀態為準。"
            else "此方案已停止套用，選擇仍保留。")
        if (profile.error.isNotBlank()) PlanMessage("此卡的已存方案回報錯誤，請查看工具頁的診斷。", error = true)
        if (profile.matchesCurrentSim == false) {
            PlanMessage("此方案屬於先前辨識的 SIM。請重新選擇這張卡的設定。")
        }
        if (!profile.enabled && profile.matchesCurrentSim == true &&
            (profile.country.isNotBlank() || profile.carrier.isNotBlank())) {
            HaaruButton(UiStyle.MIUIX, "重新套用這張 SIM 的已存方案",
                enabled = state.writable && sim.ready && simSelectionToken.matches(sim.selectionToken),
                secondary = true, modifier = Modifier.fillMaxWidth()) {
                state.reapplySavedPlan(sim, profile)
            }
        }
        HorizontalDivider(color = colours.outlineVariant)
        PlanAction("還原 SIM", "恢復原始回報，保留此卡的已存方案。", canRestore) {
            confirm("還原 SIM ${slot + 1}？", "恢復原始 SIM 回報，已存方案會保留。") {
                state.run("reset", slot)
            }
        }
        PlanAction("清除設定", "還原此卡，並刪除它的已存方案。", canRestore) {
            confirm("清除 SIM ${slot + 1} 設定？", "恢復原始 SIM 回報，並刪除此卡的已存方案。") {
                state.run("forget", slot)
            }
        }
    }
}

@Composable
internal fun ToolsPanel(state: HaaruState, preferenceError: String,
                        confirm: (String, String, () -> Unit) -> Unit) {
    val colours = HaaruTheme.colours
    val canRestore = !state.busy && !state.snapshot.upgradePending
    val hasSavedPlans = state.snapshot.profiles.any { it.country.isNotBlank() || it.carrier.isNotBlank() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("已存方案", fontSize = 21.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold, color = colours.onSurface)
            PlanMessage(if (hasSavedPlans) "一次重新套用所有已存方案；未儲存的草稿不會送出。"
                else "還沒有已存方案。先在首頁選好 SIM 並儲存。")
            HaaruButton(UiStyle.MIUIX, "重新套用已存方案", enabled = state.writable && hasSavedPlans,
                modifier = Modifier.fillMaxWidth()) {
                state.reapplySavedPlans()
            }
        }
        HaaruCard(UiStyle.MIUIX) {
            PlanAction("還原全部 SIM", "停止所有覆寫，保留已存方案，之後可以再套用。", canRestore) {
                confirm("還原全部 SIM？", "恢復原始 SIM 回報；已存方案會保留，可稍後重新套用。") {
                    state.run("disable")
                }
            }
            HorizontalDivider(color = colours.outlineVariant)
            PlanAction("清除全部 SIM 方案", "還原所有 SIM，並刪除全部已存 SIM 方案。外觀設定保留。", canRestore) {
                confirm("清除全部 SIM 方案？", "所有 SIM 都會恢復原始回報，已存 SIM 方案也會刪除。外觀設定保留。") {
                    state.run("clear")
                }
            }
        }
        if (state.snapshot.upgradePending) PlanMessage("新版模組等待重新啟動；目前只可讀取狀態。")
        PlanDiagnostics(state, preferenceError)
    }
}

@Composable
private fun PlanReadout(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = 18.sp, color = HaaruTheme.colours.onSurfaceVariant)
        Text(value, fontSize = 16.sp, lineHeight = 23.sp, color = HaaruTheme.colours.onSurface)
    }
}

@Composable
private fun PlanMessage(message: String, error: Boolean = false) {
    val modifier = Modifier.fillMaxWidth().then(if (error) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
    Text(message, modifier,
        fontSize = 13.sp, lineHeight = 20.sp,
        color = if (error) HaaruTheme.colours.error else HaaruTheme.colours.onSurfaceVariant)
}

/** Full-width labelled actions remain readable on narrow screens and at larger font scales. */
@Composable
private fun PlanAction(label: String, explanation: String, enabled: Boolean, action: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HaaruButton(UiStyle.MIUIX, label, enabled = enabled, secondary = true,
            modifier = Modifier.fillMaxWidth(), onClick = action)
        PlanMessage(explanation)
    }
}

/** Detailed engine data is secondary; opening or copying it performs no module mutation. */
@Composable
private fun PlanDiagnostics(state: HaaruState, preferenceError: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val payload = remember(state.diagnostics, preferenceError) {
        shareableDiagnostics(state.diagnostics, preferenceError.isNotBlank())
    }
    var copied by remember(payload) { mutableStateOf(false) }
    var copyFailed by remember(payload) { mutableStateOf(false) }
    val context = LocalContext.current
    val colours = HaaruTheme.colours
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("診斷", fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium, color = colours.onSurface)
        PlanMessage("需要排查問題時，再查看模組與電話引擎的詳細回報。")
        if (state.snapshot.warnings.isNotEmpty()) PlanMessage("有 ${state.snapshot.warnings.size} 項模組提示，展開後可以查看。")
        if (preferenceError.isNotBlank()) PlanMessage("外觀尚未儲存，詳細原因在下方診斷。", error = true)
        HaaruButton(UiStyle.MIUIX, if (expanded) "收起詳細資料" else "查看詳細資料", secondary = true,
            modifier = Modifier.fillMaxWidth()) { expanded = !expanded }
        if (expanded) {
            val engine = state.snapshot.globalEngine
            PlanReadout("模組版本", state.snapshot.moduleVersion.ifBlank { "尚未讀取" })
            PlanReadout("系統確認", state.engineLabel)
            PlanReadout("Android SDK / Hook", "${engine?.sdk ?: "未回報"} / ${engine?.hooks ?: "未回報"}")
            state.snapshot.warnings.forEach { warning -> PlanMessage(warning) }
            state.snapshot.profiles.filter { it.error.isNotBlank() }.forEach { profile ->
                PlanMessage("SIM ${profile.slot + 1}：${profile.error}", error = true)
            }
            SelectionContainer {
                Text(payload.ifBlank { "尚未讀取診斷。" },
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    color = colours.onSurface, fontSize = 12.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace)
            }
        }
        HaaruTextButton(onClick = {
            copied = runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: error("Clipboard is unavailable")
                clipboard.setPrimaryClip(ClipData.newPlainText("Haaru diagnostics", payload))
                true
            }.getOrDefault(false)
            copyFailed = !copied
        }, enabled = payload.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (copied) "已複製診斷" else "複製去識別診斷", color = if (payload.isNotBlank()) colours.onSurfaceVariant
                else colours.onSurface.copy(alpha = .38f))
        }
        if (copyFailed) PlanMessage("暫時無法使用剪貼簿，請稍後再試。", error = true)
    }
}

/** Export only the fields needed to diagnose engine startup. */
internal fun shareableDiagnostics(raw: String, appearanceIssue: Boolean): String {
    if (raw.isBlank() && !appearanceIssue) return ""
    val report = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    val engine = report?.get("globalEngine") as? JsonObject
    val version = (report?.get("moduleVersion") as? JsonPrimitive)?.contentOrNull
    val stage = (engine?.get("stage") as? JsonPrimitive)?.contentOrNull
    val error = (engine?.get("error") as? JsonPrimitive)?.contentOrNull.orEmpty()
    val errorKind = when {
        error.contains("without an FD retention path", ignoreCase = true) -> "startup-fd-retention-unavailable"
        error.contains("post-specialisation validation") || error.contains("did not survive process specialisation") -> "startup-disconnect"
        error.contains("round-trip failed") -> "startup-round-trip-failed"
        error.contains("disconnected", ignoreCase = true) -> "disconnected"
        error.isNotBlank() -> "engine-error"
        else -> "none"
    }
    return buildJsonObject {
        put("statusAvailable", report != null)
        if (version != null && Regex("[0-9]{1,3}\\.[0-9]{1,3}(?:\\.[0-9]{1,3})?").matches(version)) put("moduleVersion", version)
        for (key in listOf("readOnly", "enabled", "upgradePending")) {
            (report?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let { put(key, it) }
        }
        if (engine != null) put("engine", buildJsonObject {
            for (key in listOf("sdk", "hooks", "requiredHooks")) {
                (engine[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 0..1000 }?.let { put(key, it) }
            }
            for (key in listOf("connected", "initialized", "applied", "coverageComplete", "fdExemptionReported", "fdIgnoreListAvailable")) {
                (engine[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let { put(key, it) }
            }
            if (stage in setOf("companion", "hook-dex", "property-baseline", "property-guards", "connection-retention",
                    "specialization", "connection-validation", "property-restore", "art-symbols", "dex-loader",
                    "dex-cookie-access", "lsplant", "application-attach-hook", "awaiting-application-attach",
                    "central-service-hooks", "engine-thread", "connection", "runtime")) put("stage", stage)
            put("errorKind", errorKind)
        })
        put("appearanceIssue", appearanceIssue)
    }.toString()
}

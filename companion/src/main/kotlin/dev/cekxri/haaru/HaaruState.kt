package dev.cekxri.haaru

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import haaru.ui.*

/** RAM-only editing state. Durable changes are accepted only after root verification. */
@Stable
internal class HaaruState(private val bridge: UiBridge, private val scope: CoroutineScope) {
    var snapshot by mutableStateOf(UiSnapshot())
        private set
    var busy by mutableStateOf(false)
        private set
    var fresh by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set
    var failed by mutableStateOf(false)
        private set
    var diagnostics by mutableStateOf("")
        private set
    val drafts = mutableStateMapOf<Int, SimDraft>()
    private var poll: Job? = null
    val writable get() = fresh && !snapshot.readOnly && !snapshot.upgradePending && !busy

    fun draft(slot: Int) = drafts[slot] ?: SimDraft()
    fun edit(slot: Int, transform: (SimDraft) -> SimDraft) { drafts[slot] = transform(draft(slot)) }
    fun run(action: String, slot: Int = 0, country: String = "", carrier: String = "") {
        if (busy) return
        if (action != "status" && snapshot.upgradePending) {
            notice = "更新完成後，請重新啟動手機再操作。"; failed = true; return
        }
        if (action in listOf("set", "enable", "refresh") && !writable) {
            notice = if (snapshot.readOnly) "請先啟用 Haaru 模組。" else "請先重新讀取模組狀態。"
            failed = true; return
        }
        if (action == "set" && ((country.isBlank() && carrier.isBlank()) ||
                    (country.isNotBlank() && !country.matches(Regex("[A-Z]{2}"))) ||
                    carrier.length > 80 || carrier.any { it.code < 32 || it.code == 127 })) {
            notice = "請選擇國家或電信商；國家碼為兩個英文字母。"; failed = true; return
        }
        poll?.cancel()
        val submittedDraft = drafts[slot]
        busy = true
        failed = false
        notice = ""
        scope.launch {
            var verified = false
            try {
                val result = bridge.command(action, slot, country, carrier)
                val response = Json.parseToJsonElement(result).jsonObject
                check(response["ok"]?.jsonPrimitive?.booleanOrNull == true) { "模組尚未確認操作" }
                if (action != "status") {
                    check(response["verified"]?.jsonPrimitive?.booleanOrNull == true) { "操作尚未確認" }
                    verified = true
                    // A successful reset survives a failure of the following observation.
                    if (action in listOf("reset", "forget")) drafts[slot] = SimDraft()
                    if (action in listOf("disable", "clear")) drafts.clear()
                    if (action == "set" && drafts[slot] == submittedDraft) drafts[slot] = SimDraft(country, carrier,
                        country.isNotBlank() && HaaruPresets.countries.none { it.code == country },
                        carrier.isNotBlank() && HaaruPresets.carriers.none { it.displayName == carrier })
                }
                accept(if (action == "status") result else bridge.command("status"))
                snapshot.cards.forEach { card ->
                    if (!drafts.containsKey(card.slot)) {
                        val profile = snapshot.profiles.firstOrNull { it.slot == card.slot && it.enabled }
                        val iso = profile?.country.orEmpty(); val label = profile?.carrier.orEmpty()
                        drafts[card.slot] = SimDraft(iso, label,
                            iso.isNotBlank() && HaaruPresets.countries.none { it.code == iso },
                            label.isNotBlank() && HaaruPresets.carriers.none { it.displayName == label })
                    }
                }
                if (action != "status") {
                    notice = if (snapshot.globalConfirmed) when (action) {
                        "disable", "reset" -> "已還原，已存方案仍保留。"
                        "forget", "clear" -> "已還原並清除設定。"
                        else -> "已儲存，系統已確認套用。"
                    } else "設定已儲存，正在等待系統確認。"
                    if (!snapshot.globalConfirmed) startPolling()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                fresh = false
                failed = true
                diagnostics = failure.message.orEmpty()
                notice = if (verified) "設定已儲存，暫時讀不到系統回報；請重新讀取。"
                    else friendlyError(diagnostics)
            } finally { busy = false }
        }
    }

    private fun accept(raw: String) {
        val data = Json.parseToJsonElement(raw).jsonObject
        check(data["ok"]?.jsonPrimitive?.booleanOrNull == true) { "狀態尚未確認" }
        snapshot = UiSnapshot.parse(raw)
        diagnostics = raw
        fresh = true
    }

    private fun startPolling() {
        poll = scope.launch {
            repeat(5) {
                delay(1500)
                try {
                    accept(bridge.command("status"))
                    if (snapshot.globalConfirmed) { notice = "系統已確認目前設定。"; failed = false; return@launch }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    fresh = false; diagnostics = failure.message.orEmpty()
                    notice = "設定已儲存，暫時讀不到系統回報。"; failed = true; return@launch
                }
            }
            notice = "設定已儲存，系統仍未確認套用；請查看工具中的診斷。"
        }
    }
}

internal fun friendlyError(raw: String): String = when {
    raw.contains("安裝") || raw.contains("not installed", true) -> "請先安裝 Haaru 模組並重新啟動手機。"
    raw.contains("sdk", true) || raw.contains("ACK", true) || raw.contains("engine", true) -> friendlyEngineError(raw)
    raw.contains("服務") || raw.contains("connect", true) || raw.contains("Root", true) || raw.contains("permission", true) || raw.contains("denied", true) -> "模組服務尚未連接，請稍後重新讀取。"
    raw.contains("逾時") || raw.contains("timeout", true) -> "模組回應有點久，請重新讀取。"
    raw.contains("Context") || raw.contains("object reference") -> "暫時讀不到 SIM 資料，請重新讀取。"
    raw.any { it in '\u3400'..'\u9FFF' } && raw.none { it in '<'..'>' } && raw.length < 90 &&
        !raw.contains("java.") && !raw.contains("android.") && !raw.contains("Exception") -> raw
    else -> "模組服務尚未連接，請稍後重新讀取。"
}

/** A working app-to-module connection does not imply a working phone-process hook. */
internal fun friendlyEngineError(raw: String): String = when {
    raw.contains("disconnect", true) -> "電話引擎已斷線，請更新模組並重新啟動手機。"
    raw.contains("sdk", true) -> "電話引擎尚未初始化，請更新模組並重新啟動手機。"
    raw.contains("ACK", true) -> "尚未收到電話引擎確認，請重新讀取。"
    else -> "電話引擎暫時未就緒，請重新啟動手機後再試。"
}

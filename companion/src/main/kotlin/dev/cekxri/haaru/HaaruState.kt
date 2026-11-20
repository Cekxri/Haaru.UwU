package dev.cekxri.haaru

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import haaru.ui.*

/** RAM-only drafts are tied to the observed card. Saved plans and engine receipts are separate. */
@Stable
internal class HaaruState(
    private val bridge: UiBridge,
    private val scope: CoroutineScope,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val pollMillis: Long = 4_000L
) {
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
    var statusReadError by mutableStateOf("")
        private set
    private var receiptCurrent by mutableStateOf(false)
    private var observedAt: Long? = null
    private var receiptExpiresAt: Long? = null
    private var observationGeneration = 0L
    private var foreground = true
    private val drafts = mutableStateMapOf<Int, SimDraft>()
    private var draftTokens = emptyMap<Int, String>()

    val writable get() = fresh && !snapshot.readOnly && !snapshot.upgradePending && !busy
    val globalConfirmed get() = fresh && receiptCurrent && snapshot.globalConfirmed
    val engineLabel get() = when {
        !fresh -> "模組狀態待重新讀取"
        snapshot.globalConfirmed && !globalConfirmed -> "系統回報待更新"
        else -> snapshot.engineLabel
    }

    fun draft(slot: Int) = drafts[slot] ?: SimDraft()

    fun edit(slot: Int, selectionToken: String, transform: (SimDraft) -> SimDraft) {
        if (!simSelectionToken.matches(selectionToken) || draftTokens[slot] != selectionToken) return
        drafts[slot] = transform(draft(slot))
    }

    /** Cancels the authority of in-flight observations when the window leaves the foreground. */
    fun invalidateStatus() {
        foreground = false
        ++observationGeneration
        clearObservation()
    }

    suspend fun monitorStatus(): Unit = coroutineScope {
        foreground = true
        launch {
            while (isActive) {
                if (!busy) observeStatus(observationGeneration, manual = false)
                delay(pollMillis)
            }
        }
        while (isActive) {
            expireStatus()
            delay(500)
        }
    }

    internal fun expireStatus() {
        val now = monotonicMillis()
        if (receiptExpiresAt?.let { now >= it } == true) receiptCurrent = false
        if (observedAt?.let { now - it >= STATUS_VALIDITY_MS } == true) clearObservation()
    }

    fun reapplySavedPlans() = run("enable")

    fun reapplySavedPlan(card: SimCard, profile: SimProfile) {
        if (profile.slot != card.slot || profile.enabled || profile.matchesCurrentSim != true || profile !in snapshot.profiles) {
            reject("已存方案已變更，請重新讀取後再套用。")
            return
        }
        run("set", card.slot, profile.country, profile.carrier, card.selectionToken, preserveDraft = true)
    }

    fun run(action: String, slot: Int = 0, country: String = "", carrier: String = "", selectionToken: String = "",
            preserveDraft: Boolean = false) {
        if (busy || !foreground) return
        expireStatus()
        if (action != "status" && snapshot.upgradePending) {
            reject("更新完成後，請重新啟動手機再操作。")
            return
        }
        if (action in listOf("set", "enable", "refresh") && !writable) {
            reject(if (snapshot.readOnly) "請先啟用 Haaru 模組。" else "請先重新讀取模組狀態。")
            return
        }
        if (action == "set") {
            val card = snapshot.cards.singleOrNull { it.slot == slot }
            when {
                !simSelectionToken.matches(selectionToken) || card?.selectionToken != selectionToken -> {
                    reject("SIM 已變更或無法確認，請重新讀取後再儲存。")
                    return
                }
                !card.ready -> {
                    reject("這張 SIM 尚未準備好，請重新讀取後再儲存。")
                    return
                }
                !SimPlanInput.validCountry(country) -> {
                    reject("請輸入有效的兩位 ISO 國家碼，例如 GB。")
                    return
                }
                !SimPlanInput.validCarrier(carrier) || (country.isBlank() && carrier.isBlank()) -> {
                    reject("請選擇國家或電信商，名稱限 80 字元內的單行文字。")
                    return
                }
            }
        }

        val generation = ++observationGeneration
        val submittedDraft = drafts[slot]
        busy = true
        failed = false
        notice = ""
        scope.launch {
            try {
                if (action == "status") {
                    observeStatus(generation, manual = true)
                    return@launch
                }
                receiptCurrent = false
                val response = Json.parseToJsonElement(bridge.command(action, slot, country, carrier, selectionToken)).jsonObject
                check(response.confirmed("ok") && response.confirmed("verified")) {
                    response["error"]?.jsonPrimitive?.contentOrNull ?: "操作尚未確認"
                }
                when (action) {
                    "reset", "forget" -> drafts[slot] = SimDraft()
                    "disable", "clear" -> drafts.clear()
                    "set" -> if (!preserveDraft && draftTokens[slot] == selectionToken && drafts[slot] == submittedDraft) {
                        drafts[slot] = planDraft(country, carrier)
                    }
                }
                val result = observeStatus(generation, manual = false)
                notice = if (result) "設定已儲存。請查看目前的系統確認狀態。"
                    else "設定已儲存，暫時讀不到系統回報。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isCurrent(generation)) markStatusFailure(failure)
                reject(friendlyError(failure.message.orEmpty()))
            } finally {
                busy = false
            }
        }
    }

    private fun reject(message: String) {
        notice = message
        failed = true
    }

    private fun clearObservation() {
        fresh = false
        receiptCurrent = false
        observedAt = null
        receiptExpiresAt = null
    }

    private fun markStatusFailure(failure: Exception) {
        clearObservation()
        statusReadError = failure.message.orEmpty()
        diagnostics = statusReadError
    }

    private fun isCurrent(generation: Long) = foreground && generation == observationGeneration

    private suspend fun observeStatus(generation: Long, manual: Boolean): Boolean = try {
        val raw = bridge.command("status")
        if (isCurrent(generation)) {
            accept(raw)
            true
        } else false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (isCurrent(generation)) {
            markStatusFailure(failure)
            if (manual) reject(friendlyError(diagnostics))
        }
        false
    }

    private fun accept(raw: String) {
        val data = Json.parseToJsonElement(raw).jsonObject
        check(data.confirmed("ok")) { "狀態尚未確認" }
        val observed = UiSnapshot.parse(raw)
        val cards = observed.cards.filter { simSelectionToken.matches(it.selectionToken) }
        val currentTokens = cards.associate { it.slot to it.selectionToken }
        drafts.keys.toList().forEach { slot ->
            if (currentTokens[slot] == null || currentTokens[slot] != draftTokens[slot]) drafts.remove(slot)
        }
        draftTokens = currentTokens
        cards.forEach { card ->
            if (!drafts.containsKey(card.slot)) {
                val profile = observed.profiles.firstOrNull { it.slot == card.slot && it.enabled && it.matchesCurrentSim == true }
                drafts[card.slot] = draftFromProfile(profile)
            }
        }
        val now = monotonicMillis()
        val stamp = observed.globalEngine?.timestampMs ?: 0
        val receiptAge = wallClockMillis() - stamp
        receiptExpiresAt = if (stamp > 0 && receiptAge in -1_000L until STATUS_VALIDITY_MS) {
            now + STATUS_VALIDITY_MS - receiptAge.coerceAtLeast(0L)
        } else null
        receiptCurrent = receiptExpiresAt != null
        snapshot = observed
        diagnostics = raw
        statusReadError = ""
        observedAt = now
        fresh = true
    }

    private fun JsonObject.confirmed(key: String) = (this[key] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true

    private companion object {
        const val STATUS_VALIDITY_MS = 10_000L
    }
}

internal fun draftFromProfile(profile: SimProfile?) = planDraft(profile?.country.orEmpty(), profile?.carrier.orEmpty())

private fun planDraft(country: String, carrier: String) = SimDraft(country, carrier,
    country.isNotBlank() && HaaruPresets.countries.none { it.code == country },
    carrier.isNotBlank() && HaaruPresets.carriers.none { it.displayName == carrier })

internal fun moduleIsAbsent(raw: String) =
    raw.contains("Module directory is absent", true) || raw.contains("Module not installed", true)

internal fun friendlyError(raw: String): String = when {
    raw.contains("SIM changed", true) || raw.contains("SIM selection token", true) -> "SIM 已變更或無法確認，請重新讀取後再儲存。"
    raw.contains("valid two-letter ISO", true) -> "請輸入有效的兩位 ISO 國家碼，例如 GB。"
    moduleIsAbsent(raw) -> "請先安裝 Haaru 模組，再重新讀取。"
    raw.contains("sdk", true) || raw.contains("ACK", true) || raw.contains("engine", true) -> friendlyEngineError(raw)
    raw.contains("逾時") || raw.contains("timeout", true) || raw.contains("timed out", true) -> "模組回應有點久，請重新讀取。"
    raw.contains("服務") || raw.contains("connect", true) || raw.contains("Root", true) || raw.contains("permission", true) || raw.contains("denied", true) -> "模組服務尚未連接，請稍後重新讀取。"
    raw.contains("Context") || raw.contains("object reference") -> "暫時讀不到 SIM 資料，請重新讀取。"
    raw.any { it in '\u3400'..'\u9FFF' } && raw.none { it in '<'..'>' } && raw.length < 90 &&
        !raw.contains("java.") && !raw.contains("android.") && !raw.contains("Exception") -> raw
    else -> "模組服務尚未連接，請稍後重新讀取。"
}

/** A working app-to-module connection does not imply a working phone-process hook. */
internal fun friendlyEngineError(raw: String): String = when {
    raw.contains("Combined SIM operator names exceed", true) ->
        "多張卡的電信商名稱總長超出系統限制，請縮短自訂名稱再套用。"
    raw.contains("disconnect", true) -> "電話引擎尚未連上，請到工具頁查看原因。"
    raw.contains("sdk", true) -> "電話引擎未完成初始化，請到工具頁查看原因。"
    raw.contains("ACK", true) -> "正在等待電話引擎回報，可到工具頁查看狀態。"
    else -> "電話引擎回報錯誤，請到工具頁查看原因。"
}

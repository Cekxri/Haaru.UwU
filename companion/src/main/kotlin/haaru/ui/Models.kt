package haaru.ui

import kotlinx.serialization.json.*

enum class UiStyle { MIUIX, MATERIAL3 }
enum class Appearance { SYSTEM, LIGHT, DARK }
enum class ColourStyle { EXPRESSIVE, VIBRANT, TONAL_SPOT, NEUTRAL, CMF }
enum class Palette { VIOLET, LAVENDER, WISTERIA, IRIS, PLUM, LILAC, ORCHID, MAUVE, PERIWINKLE, MINT, BLUE, ROSE, SAGE, CAFE, SLATE }

data class UiPreferences(
    val style: UiStyle = UiStyle.MIUIX,
    val appearance: Appearance = Appearance.SYSTEM,
    val palette: Palette = Palette.VIOLET,
    val glass: Boolean = true,
    val reduceMotion: Boolean = false,
    val dynamicColour: Boolean = false,
    val customColour: String = "",
    val colourStyle: ColourStyle = ColourStyle.EXPRESSIVE
) {
    fun encode(): String = buildJsonObject {
        put("style", UiStyle.MIUIX.name); put("appearance", appearance.name)
        put("palette", palette.name); put("glass", glass); put("reduceMotion", reduceMotion)
        put("dynamicColour", dynamicColour); put("customColour", canonicalCustomColour(customColour))
        put("colourStyle", colourStyle.name)
    }.toString()
    companion object {
        fun decode(value: String?): UiPreferences = runCatching {
            val json = Json.parseToJsonElement(value ?: "{}").jsonObject
            UiPreferences(
                // Earlier MATERIAL3 preferences are read compatibly, then saved as MIUIX.
                UiStyle.MIUIX,
                enumValues<Appearance>().firstOrNull { it.name == json["appearance"]?.jsonPrimitive?.content } ?: Appearance.SYSTEM,
                enumValues<Palette>().firstOrNull { it.name == json["palette"]?.jsonPrimitive?.content } ?: Palette.VIOLET,
                json["glass"]?.jsonPrimitive?.booleanOrNull ?: true,
                json["reduceMotion"]?.jsonPrimitive?.booleanOrNull ?: false,
                // Preserve the wallpaper default for saved legacy preferences.
                json["dynamicColour"]?.jsonPrimitive?.booleanOrNull ?: json.isNotEmpty(),
                canonicalCustomColour(json["customColour"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                enumValues<ColourStyle>().firstOrNull { it.name == json["colourStyle"]?.jsonPrimitive?.contentOrNull }
                    ?: ColourStyle.EXPRESSIVE
            )
        }.getOrDefault(UiPreferences())
    }
}

interface UiBridge {
    val connected: Boolean
    val systemDark: Boolean
    val systemReducedMotion: Boolean
    suspend fun readPreferences(): String?
    suspend fun writePreferences(value: String)
    suspend fun command(action: String, slot: Int = 0, country: String = "", carrier: String = "",
                        selectionToken: String = ""): String
}

internal val simSelectionToken = Regex("[a-f0-9]{64}")

// Use the same ISO list as the module to reject invalid values before queuing IPC.
internal object SimPlanInput {
    private val countries = java.util.Locale.getISOCountries().toSet()
    fun validCountry(country: String): Boolean = country.isEmpty() || country in countries
    fun validCarrier(carrier: String): Boolean = carrier.codePointCount(0, carrier.length) <= 80 &&
        carrier.codePoints().noneMatch { Character.isISOControl(it) || it in 0xd800..0xdfff }

    fun limitCarrier(value: String): String {
        val singleLine = value.filterNot { it.isISOControl() }
        val count = singleLine.codePointCount(0, singleLine.length)
        return singleLine.substring(0, singleLine.offsetByCodePoints(0, minOf(80, count)))
    }
}

data class SimCard(val slot: Int, val country: String, val configCountry: String, val carrier: String,
                   val ready: Boolean, val error: String = "", val originalCarrierName: String = "",
                   val selectionToken: String = "")
data class SimProfile(val slot: Int, val country: String, val carrier: String,
                      val enabled: Boolean = false, val countryOverride: Boolean = country.isNotBlank(),
                      val carrierOverride: Boolean = carrier.isNotBlank(), val error: String = "",
                      val matchesCurrentSim: Boolean? = null)
data class SimDraft(val country: String = "", val carrier: String = "",
                    val customCountry: Boolean = false, val customCarrier: Boolean = false)
data class GlobalEngineStatus(val ok: Boolean = false, val initialized: Boolean = false,
                              val applied: Boolean = false, val hooks: Int = 0, val sdk: Int? = null,
                              val bootId: String = "", val configHash: String = "", val error: String = "",
                              val requiredHooks: Int = 0, val coverageComplete: Boolean = false,
                              val supportsCountry: Boolean = false, val supportsCarrierName: Boolean = false,
                              val timestampMs: Long = 0) {
    fun confirmed(currentConfigHash: String): Boolean = ok && initialized && applied && coverageComplete &&
        requiredHooks >= 3 && hooks == requiredHooks &&
        (sdk ?: 0) >= 29 && error.isBlank() &&
        bootId.isNotBlank() && configHash.isNotBlank() && configHash == currentConfigHash
}

data class UiSnapshot(val enabled: Boolean = false, val cards: List<SimCard> = emptyList(),
                      val profiles: List<SimProfile> = emptyList(), val warnings: List<String> = emptyList(),
                      val global: Boolean = false, val engine: String = "",
                      val currentConfigHash: String = "", val globalEngine: GlobalEngineStatus? = null,
                      val moduleVersion: String = "", val moduleVersionCode: Int = 0,
                      val upgradePending: Boolean = false, val readOnly: Boolean = false) {
    private val enabledProfiles get() = profiles.filter { it.enabled }
    val hasBlockedPlans get() = enabledProfiles.any { profile ->
        profile.error.isNotBlank() || profile.matchesCurrentSim == false ||
            cards.none { it.slot == profile.slot && it.ready }
    }
    val globalConfirmed get() = !readOnly && !upgradePending && global && engine == "system-global-volatile" &&
        enabled == enabledProfiles.isNotEmpty() && !hasBlockedPlans &&
        globalEngine?.confirmed(currentConfigHash) == true && enabledProfiles.all { profile ->
            (!profile.countryOverride || globalEngine.supportsCountry) &&
                (!profile.carrierOverride || globalEngine.supportsCarrierName)
        }
    val engineLabel get() = when {
        hasBlockedPlans -> "有方案未能套用"
        globalEngine?.error?.isNotBlank() == true -> "全域引擎回報錯誤"
        globalConfirmed -> if (enabled) "系統全域設定已確認" else "系統全域還原已確認"
        globalEngine?.ok != true || globalEngine?.initialized != true -> "全域引擎尚未準備"
        else -> "全域套用待確認"
    }
    val engineDescription get() = when {
        hasBlockedPlans -> "至少一張 SIM 的已啟用方案未能套用。請查看該卡的診斷。"
        globalEngine?.error?.isNotBlank() == true -> globalEngine?.error.orEmpty()
        globalConfirmed -> "目前設定已由系統全域引擎確認。下方保留 SIM 原始資料供核對。"
        globalEngine?.initialized != true -> "尚未取得全域引擎初始化回報；保存設定不代表已套用。"
        else -> "設定已保存，等待目前設定的系統全域套用確認。"
    }
    companion object {
        fun parse(value: String): UiSnapshot {
            val data = Json.parseToJsonElement(value).jsonObject
            fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
            val cards = data["subscriptions"]?.jsonArray.orEmpty().mapNotNull { item ->
                val card = item.jsonObject
                val slot = card["slot"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val id = card["subId"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                if (slot !in 0..7 || id < 0) return@mapNotNull null
                SimCard(slot, card.text("country").uppercase(), card.text("configCountry").uppercase(),
                        card.text("carrierName"), card.text("error").isBlank() && (card["ready"]?.jsonPrimitive?.booleanOrNull ?: false),
                        card.text("error"), card.text("originalCarrierName"),
                        (card["selectionToken"] as? JsonPrimitive)?.takeIf { it.isString }
                            ?.contentOrNull?.takeIf(simSelectionToken::matches).orEmpty())
            }
            val profiles = data["profiles"]?.jsonArray.orEmpty().mapNotNull { item ->
                val p = item.jsonObject
                val slot = p["slot"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                if (slot !in 0..7) return@mapNotNull null
                SimProfile(slot, p.text("country").uppercase(), p.text("carrierName"),
                    p["enabled"]?.jsonPrimitive?.booleanOrNull ?: (data["enabled"]?.jsonPrimitive?.booleanOrNull ?: false),
                    p["countryOverride"]?.jsonPrimitive?.booleanOrNull ?: p.text("country").isNotBlank(),
                    p["carrierOverride"]?.jsonPrimitive?.booleanOrNull ?: p.text("carrierName").isNotBlank(),
                    p.text("error"),
                    (p["matchesCurrentSim"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull)
            }
            val globalEngine = runCatching {
                val e = data["globalEngine"]?.jsonObject ?: return@runCatching null
                require(listOf("ok", "initialized", "applied").all {
                    val value = e[it] as? JsonPrimitive
                    value != null && !value.isString && value.booleanOrNull != null
                })
                require(listOf("hooks", "sdk").all {
                    val value = e[it] as? JsonPrimitive
                    value != null && !value.isString && value.intOrNull != null
                })
                require(listOf("bootId", "configHash", "error").all {
                    (e[it] as? JsonPrimitive)?.isString == true
                })
                GlobalEngineStatus(e["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
                    e["initialized"]?.jsonPrimitive?.booleanOrNull ?: false,
                    e["applied"]?.jsonPrimitive?.booleanOrNull ?: false,
                    e["hooks"]?.jsonPrimitive?.intOrNull ?: 0, e["sdk"]?.jsonPrimitive?.intOrNull,
                    e.text("bootId"), e.text("configHash"), e.text("error"),
                    requiredHooks = (e["requiredHooks"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: 0,
                    coverageComplete = (e["coverageComplete"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } ?: false,
                    supportsCountry = (e["supportsCountry"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } ?: false,
                    supportsCarrierName = (e["supportsCarrierName"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } ?: false,
                    timestampMs = (e["timestampMs"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0)
            }.getOrNull()
            return UiSnapshot(data["enabled"]?.jsonPrimitive?.booleanOrNull ?: false, cards, profiles,
                              data["warnings"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                              (data["global"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } ?: false,
                              (data["engine"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty(),
                              (data["currentConfigHash"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty(), globalEngine,
                              (data["moduleVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty(),
                              (data["moduleVersionCode"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: 0,
                              (data["upgradePending"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } ?: false,
                              (data["readOnly"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } ?: false)
        }
    }
}


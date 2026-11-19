package haaru.ui

import kotlinx.serialization.json.*

enum class UiStyle { MIUIX, MATERIAL3 }
enum class Appearance { SYSTEM, LIGHT, DARK }
enum class Palette { VIOLET, LAVENDER, WISTERIA, IRIS, PLUM, LILAC, ORCHID, MAUVE, PERIWINKLE, MINT, BLUE, ROSE, SAGE, CAFE, SLATE }

data class UiPreferences(
    val style: UiStyle = UiStyle.MIUIX,
    val appearance: Appearance = Appearance.SYSTEM,
    val palette: Palette = Palette.VIOLET,
    val glass: Boolean = true,
    val reduceMotion: Boolean = false,
    val dynamicColour: Boolean = true,
    val customColour: String = ""
) {
    fun encode(): String = buildJsonObject {
        put("style", style.name); put("appearance", appearance.name)
        put("palette", palette.name); put("glass", glass); put("reduceMotion", reduceMotion)
        put("dynamicColour", dynamicColour); put("customColour", canonicalCustomColour(customColour))
    }.toString()
    companion object {
        fun decode(value: String?): UiPreferences = runCatching {
            val json = Json.parseToJsonElement(value ?: "{}").jsonObject
            UiPreferences(
                enumValues<UiStyle>().firstOrNull { it.name == json["style"]?.jsonPrimitive?.content } ?: UiStyle.MIUIX,
                enumValues<Appearance>().firstOrNull { it.name == json["appearance"]?.jsonPrimitive?.content } ?: Appearance.SYSTEM,
                enumValues<Palette>().firstOrNull { it.name == json["palette"]?.jsonPrimitive?.content } ?: Palette.VIOLET,
                json["glass"]?.jsonPrimitive?.booleanOrNull ?: true,
                json["reduceMotion"]?.jsonPrimitive?.booleanOrNull ?: false,
                json["dynamicColour"]?.jsonPrimitive?.booleanOrNull ?: true,
                canonicalCustomColour(json["customColour"]?.jsonPrimitive?.contentOrNull.orEmpty())
            )
        }.getOrDefault(UiPreferences())
    }
}

interface UiBridge {
    val connected: Boolean
    val systemDark: Boolean
    val systemReducedMotion: Boolean
    fun loadPreferences(): String?
    fun savePreferences(value: String)
    val preferenceError: String? get() = null
    suspend fun flushPreferences() {}
    suspend fun command(action: String, slot: Int = 0, country: String = "", carrier: String = ""): String
}

data class SimCard(val slot: Int, val country: String, val configCountry: String, val carrier: String,
                   val ready: Boolean, val error: String = "", val originalCarrierName: String = "")
data class SimProfile(val slot: Int, val country: String, val carrier: String,
                      val enabled: Boolean = false, val countryOverride: Boolean = country.isNotBlank(),
                      val carrierOverride: Boolean = carrier.isNotBlank(), val error: String = "")
data class SimDraft(val country: String = "", val carrier: String = "",
                    val customCountry: Boolean = false, val customCarrier: Boolean = false)
data class GlobalEngineStatus(val ok: Boolean = false, val initialized: Boolean = false,
                              val applied: Boolean = false, val hooks: Int = 0, val sdk: Int? = null,
                              val bootId: String = "", val configHash: String = "", val error: String = "",
                              val requiredHooks: Int = 0, val coverageComplete: Boolean = false,
                              val supportsCountry: Boolean = false, val supportsCarrierName: Boolean = false) {
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
    val globalConfirmed get() = global && engine == "system-global-volatile" &&
        globalEngine?.confirmed(currentConfigHash) == true && profiles.filter { it.enabled }.all { profile ->
            (!profile.countryOverride || globalEngine.supportsCountry) &&
                (!profile.carrierOverride || globalEngine.supportsCarrierName)
        }
    val engineLabel get() = when {
        globalEngine?.error?.isNotBlank() == true -> "全域引擎回報錯誤"
        globalConfirmed -> if (enabled) "系統全域設定已確認" else "系統全域還原已確認"
        globalEngine?.ok != true || globalEngine?.initialized != true -> "全域引擎尚未準備"
        else -> "全域套用待確認"
    }
    val engineDescription get() = when {
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
                        card.text("error"), card.text("originalCarrierName"))
            }
            val profiles = data["profiles"]?.jsonArray.orEmpty().mapNotNull { item ->
                val p = item.jsonObject
                val slot = p["slot"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                if (slot !in 0..7) return@mapNotNull null
                SimProfile(slot, p.text("country").uppercase(), p.text("carrierName"),
                    p["enabled"]?.jsonPrimitive?.booleanOrNull ?: (data["enabled"]?.jsonPrimitive?.booleanOrNull ?: false),
                    p["countryOverride"]?.jsonPrimitive?.booleanOrNull ?: p.text("country").isNotBlank(),
                    p["carrierOverride"]?.jsonPrimitive?.booleanOrNull ?: p.text("carrierName").isNotBlank(),
                    p.text("error"))
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
                    supportsCarrierName = (e["supportsCarrierName"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } ?: false)
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


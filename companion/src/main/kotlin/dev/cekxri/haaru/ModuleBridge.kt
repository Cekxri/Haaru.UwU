package dev.cekxri.haaru

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import haaru.ui.SimPlanInput
import haaru.ui.UiBridge
import haaru.ui.simSelectionToken

/** Unprivileged UI client. The signed, module-owned server performs bounded operations. */
class ModuleBridge(private val context: Context) : UiBridge {
    private val commandLock = Mutex()
    override var connected by mutableStateOf(false)
        private set
    override val systemDark: Boolean
        get() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    override val systemReducedMotion: Boolean
        get() = context.systemMotionRemoved()

    override suspend fun readPreferences(): String? = appearanceFromReply(execute(listOf("prefs")))

    override suspend fun writePreferences(value: String) {
        val canonical = decodeStoredPreferences(value).encode()
        verifyAppearanceWrite(execute(listOf("saveprefs", hex(canonical))), canonical)
    }

    override suspend fun command(action: String, slot: Int, country: String, carrier: String, selectionToken: String): String {
        val args = when (action) {
            "status", "enable", "disable", "refresh", "clear", "prefs" -> listOf(action)
            "reset", "forget" -> { require(slot in 0..7) { "SIM 插槽無效" }; listOf(action, slot.toString()) }
            "set" -> {
                require(slot in 0..7) { "SIM 插槽無效" }
                val iso = country.trim().uppercase(java.util.Locale.ROOT).takeUnless { it == "-" }.orEmpty()
                require(SimPlanInput.validCountry(iso)) { "請輸入有效的兩位 ISO 國家碼，例如 GB" }
                val label = carrier.trim()
                require(SimPlanInput.validCarrier(label)) { "名稱請使用 80 字元內的單行文字" }
                require(iso.isNotEmpty() || label.isNotEmpty()) { "請至少選擇國家或電信商" }
                require(simSelectionToken.matches(selectionToken)) { "SIM 已變更，請重新讀取後再儲存" }
                listOf("set", slot.toString(), iso.ifEmpty { "-" }, if (label.isEmpty()) "-" else hex(label), selectionToken)
            }
            else -> error("不支援的操作")
        }
        return execute(args).toString()
    }

    private suspend fun execute(args: List<String>): JsonObject = commandLock.withLock {
        val payload = try {
            withContext(Dispatchers.IO) { request(args) }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            connected = false
            throw failure
        }
        connected = true
        val problem = (payload["error"] as? JsonPrimitive)?.contentOrNull?.take(1200)
        val partial = payload["partial"]?.let { it != JsonPrimitive(false) } == true
        val hasErrors = payload["errors"]?.let { it !is JsonArray || it.isNotEmpty() } == true
        if (payload["ok"] != JsonPrimitive(true) || partial || hasErrors) error(problem ?: "操作未完成，請重新讀取")
        if (args.first() !in listOf("status", "prefs", "saveprefs") && payload["verified"] != JsonPrimitive(true))
            error("尚未取得保存確認，請重新讀取")
        payload
    }

    private fun request(args: List<String>): JsonObject {
        val request = buildJsonObject { put("protocol", 1); put("args", JsonArray(args.map(::JsonPrimitive))) }
            .toString().toByteArray(Charsets.UTF_8)
        require(request.size < 8192) { "操作內容超出長度限制" }
        return LocalSocket().use { socket ->
            try { socket.connect(LocalSocketAddress("haaru.control.v1", LocalSocketAddress.Namespace.ABSTRACT)) }
            catch (failure: java.io.IOException) {
                throw java.io.IOException("模組服務尚未連接，請確認 Haaru 模組已安裝並重新啟動手機", failure)
            }
            socket.soTimeout = 15000
            require(socket.peerCredentials.uid == 0) { "模組服務身分未確認" }
            socket.outputStream.write(request)
            socket.outputStream.write(10)
            socket.outputStream.flush()
            val output = java.io.ByteArrayOutputStream()
            val input = socket.inputStream.buffered(4096)
            while (true) {
                val byte = input.read()
                if (byte == 10) break
                if (byte < 0) error("模組服務中斷，請重新讀取")
                require(output.size() < 65536) { "模組回報超出長度限制" }
                output.write(byte)
            }
            runCatching { Json.parseToJsonElement(output.toString("UTF-8")).jsonObject }.getOrNull()
                ?: error("未取得有效的模組狀態")
        }
    }

    private fun hex(value: String): String {
        require(value.none { it.isSurrogate() } || value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value) { "文字包含無效字元" }
        return value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal fun Context.systemMotionRemoved(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

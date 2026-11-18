package dev.cekxri.haaru

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import haaru.ui.UiBridge
import haaru.ui.UiPreferences

/** Unprivileged UI client. The signed, module-owned server performs bounded operations. */
class ModuleBridge(private val context: Context) : UiBridge, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandLock = Mutex()
    private val preferenceLock = Mutex()
    private var preferenceJob: Job? = null
    private var cachedPreferences: String? by mutableStateOf(null)
    override var connected by mutableStateOf(false)
        private set
    override var preferenceError: String? by mutableStateOf(null)
        private set
    override val systemDark: Boolean
        get() = context.resources.configuration.uiMode and 0x30 == 0x20
    override val systemReducedMotion: Boolean
        get() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    suspend fun bootstrap() {
        try {
            val data = Json.parseToJsonElement(execute(listOf("prefs"))).jsonObject
            cachedPreferences = data["appearance"]?.takeUnless { it == JsonNull }?.toString()
            connected = true
            preferenceError = null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            preferenceError = e.message ?: "模組服務尚未連接"
        }
    }
    override fun loadPreferences(): String? = cachedPreferences
    override fun savePreferences(value: String) {
        val canonical = UiPreferences.decode(value).encode()
        cachedPreferences = canonical
        preferenceJob?.cancel()
        preferenceJob = scope.launch {
            delay(150)
            preferenceLock.withLock {
                try { execute(listOf("saveprefs", hex(canonical))); preferenceError = null }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    preferenceError = e.message ?: "外觀尚未保存"
                }
            }
        }
    }
    override suspend fun flushPreferences() { preferenceJob?.join() }

    override suspend fun command(action: String, slot: Int, country: String, carrier: String): String {
        val args = when (action) {
            "status", "enable", "disable", "refresh", "clear", "prefs" -> listOf(action)
            "reset", "forget" -> { require(slot in 0..7) { "SIM 插槽無效" }; listOf(action, slot.toString()) }
            "set" -> {
                require(slot in 0..7) { "SIM 插槽無效" }
                val iso = country.trim().uppercase(java.util.Locale.ROOT).let { if (it.isEmpty() || it == "-") "-" else it }
                require(iso == "-" || iso.matches(Regex("[A-Z]{2}"))) { "請輸入兩個英文字母的國家碼" }
                val label = carrier.trim()
                require(label.length <= 80 && label.none { it.code < 32 || it.code == 127 }) { "名稱請使用 80 字元內的單行文字" }
                require(iso != "-" || label.isNotEmpty()) { "請至少選擇國家或電信商" }
                listOf("set", slot.toString(), iso, if (label.isEmpty()) "-" else hex(label))
            }
            else -> error("不支援的操作")
        }
        return execute(args)
    }

    private suspend fun execute(args: List<String>): String = commandLock.withLock {
        withContext(Dispatchers.IO) {
            val request = buildJsonObject { put("protocol", 1); put("args", JsonArray(args.map(::JsonPrimitive))) }
                .toString().toByteArray(Charsets.UTF_8)
            require(request.size < 8192) { "操作內容超出長度限制" }
            LocalSocket().use { socket ->
                try { socket.connect(LocalSocketAddress("haaru.control.v1", LocalSocketAddress.Namespace.ABSTRACT)) }
                catch (e: java.io.IOException) { throw java.io.IOException("模組服務尚未連接，請確認 Haaru 模組已安裝並重新啟動手機", e) }
                socket.soTimeout = 15000
                require(socket.peerCredentials.uid == 0) { "模組服務身分未確認" }
                socket.outputStream.write(request); socket.outputStream.write(10); socket.outputStream.flush()
                val output = java.io.ByteArrayOutputStream()
                val input = socket.inputStream.buffered(4096)
                while (true) {
                    val byte = input.read()
                    if (byte == 10) break
                    if (byte < 0) error("模組服務中斷，請重新讀取")
                    require(output.size() < 65536) { "模組回報超出長度限制" }
                    output.write(byte)
                }
                val raw = output.toString("UTF-8")
                val payload = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?: error("未取得有效的模組狀態")
                val problem = payload["error"]?.jsonPrimitive?.contentOrNull?.take(1200)
                if (payload["ok"]?.jsonPrimitive?.booleanOrNull != true || payload["partial"]?.jsonPrimitive?.booleanOrNull == true
                    || payload["errors"]?.jsonArray?.isNotEmpty() == true) error(problem ?: "操作未完成，請重新讀取")
                if (args.first() !in listOf("status", "prefs") && payload["verified"]?.jsonPrimitive?.booleanOrNull != true)
                    error("尚未取得保存確認，請重新讀取")
                connected = true
                raw
            }
        }
    }
    private fun hex(value: String): String {
        require(value.none { it.isSurrogate() } || value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value) { "文字包含無效字元" }
        return value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    override fun close() { scope.cancel() }
}

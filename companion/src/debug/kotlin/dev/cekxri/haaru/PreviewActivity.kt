/* SPDX-License-Identifier: Apache-2.0 */
package dev.cekxri.haaru

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import haaru.ui.Appearance
import haaru.ui.UiBridge
import haaru.ui.UiPreferences
import kotlinx.serialization.json.*

/** Debug-only rendering entry point. It cannot contact or alter a phone module. */
class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val bridge = PreviewBridge()
        setContent {
            Box(Modifier.fillMaxSize()) {
                HaaruApp(bridge)
                BasicText(
                    "介面預覽 · 模擬資料",
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
                        .padding(top = 4.dp)
                        .background(Color(0xE6272132), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    style = TextStyle(color = Color(0xFFE7DDEE), fontSize = 10.sp)
                )
            }
        }
    }
}

/** Preview data and appearance choices live only in RAM; SIM commands are refused. */
private class PreviewBridge : UiBridge {
    override val connected = true
    override val systemDark = true
    override val systemReducedMotion = false
    private var appearance = UiPreferences(appearance = Appearance.DARK).encode()

    override fun loadPreferences(): String = appearance
    override fun savePreferences(value: String) { appearance = value }

    override suspend fun command(action: String, slot: Int, country: String, carrier: String): String {
        if (action != "status") return buildJsonObject {
            put("ok", false)
            put("verified", false)
            put("error", "介面預覽不執行 SIM 操作")
        }.toString()
        return buildJsonObject {
            put("ok", true)
            put("verified", false)
            put("enabled", false)
            put("global", true)
            put("engine", "system-global-volatile")
            put("moduleVersion", BuildConfig.VERSION_NAME)
            put("moduleVersionCode", BuildConfig.VERSION_CODE)
            put("readOnly", false)
            put("upgradePending", false)
            put("currentConfigHash", "haaru-ui-preview-only")
            putJsonArray("subscriptions") {
                for ((index, iso) in listOf("GB", "JP").withIndex()) addJsonObject {
                    put("slot", index)
                    put("subId", index + 1)
                    put("country", iso)
                    put("configCountry", "")
                    put("carrierName", "Preview SIM ${if (index == 0) "A" else "B"}")
                    put("originalCarrierName", "Preview SIM ${if (index == 0) "A" else "B"}")
                    put("ready", true)
                    put("error", "")
                }
            }
            putJsonArray("profiles") {}
            putJsonArray("warnings") {}
            putJsonObject("globalEngine") {
                put("ok", true)
                put("initialized", true)
                put("applied", false)
                put("hooks", 3)
                put("requiredHooks", 3)
                put("coverageComplete", true)
                put("supportsCountry", true)
                put("supportsCarrierName", true)
                put("sdk", Build.VERSION.SDK_INT)
                put("bootId", "haaru-ui-preview-only")
                put("configHash", "haaru-ui-preview-only")
                put("error", "")
            }
        }.toString()
    }
}

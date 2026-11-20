package dev.cekxri.haaru

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import haaru.ui.*
import org.junit.Assert.*
import org.junit.Test

class HaaruStateTest {
    private val initial = """{"ok":true,"enabled":true,"subscriptions":[
        {"slot":0,"subId":1,"ready":true,"error":"","country":"TW","originalCarrierName":"Original One"},
        {"slot":1,"subId":2,"ready":true,"country":"TW","originalCarrierName":"Original Two"}],
        "profiles":[{"slot":0,"enabled":true,"country":"GB","carrierName":"EE"},
        {"slot":1,"enabled":true,"country":"JP","carrierName":"SoftBank"}]}"""
    private class FakeBridge(val initial: String, val verified: Boolean = true) : UiBridge {
        override val connected = true
        override val systemDark = false
        override val systemReducedMotion = false
        override fun loadPreferences(): String? = null
        override fun savePreferences(value: String) {}
        val actions = mutableListOf<String>()
        override suspend fun command(action: String, slot: Int, country: String, carrier: String): String {
            actions += action
            if (action == "status") {
                if (actions.size == 1) return initial
                error("observation failed")
            }
            return """{"ok":true,"verified":$verified}"""
        }
    }
    private fun withState(verified: Boolean = true, block: (HaaruState, FakeBridge) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val bridge = FakeBridge(initial, verified)
            val state = HaaruState(bridge, scope)
            state.run("status")
            block(state, bridge)
        } finally { scope.cancel() }
    }
    @Test fun blankErrorDoesNotHideReadySimAndOriginalCarrier() {
        val card = UiSnapshot.parse(initial).cards.first()
        assertTrue(card.ready)
        assertEquals("Original One", card.originalCarrierName)
    }
    @Test fun verifiedResetClearsOnlyThatDraftDespiteLaterReadFailure() = withState { state, _ ->
        state.run("reset", 0)
        assertEquals(SimDraft(), state.draft(0))
        assertEquals("JP", state.draft(1).country)
        assertFalse(state.fresh)
        assertTrue(state.notice.startsWith("設定已儲存"))
    }
    @Test fun unverifiedResetCannotClearDraftOrClaimSuccess() = withState(false) { state, _ ->
        state.run("reset", 0)
        assertEquals("GB", state.draft(0).country)
        assertTrue(state.failed)
    }
    @Test fun verifiedClearClearsAllDraftsDespiteLaterReadFailure() = withState { state, _ ->
        state.run("clear")
        assertTrue(state.drafts.isEmpty())
        assertFalse(state.fresh)
    }
    @Test fun invalidCountryCannotReachRoot() = withState { state, bridge ->
        state.run("set", 0, "GB;", "EE")
        assertEquals(listOf("status"), bridge.actions)
        assertTrue(state.failed)
    }
    @Test fun verifiedSaveCannotOverwriteEditsMadeWhileItWasRunning() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bridge = object : UiBridge {
            override val connected = true
            override val systemDark = false
            override val systemReducedMotion = false
            override fun loadPreferences(): String? = null
            override fun savePreferences(value: String) {}
            override suspend fun command(action: String, slot: Int, country: String, carrier: String): String {
                if (action == "status") return initial
                release.await()
                return """{"ok":true,"verified":true}"""
            }
        }
        try {
            val state = HaaruState(bridge, scope)
            state.run("status")
            state.run("set", 0, "GB", "EE")
            state.edit(0) { it.copy(country = "FR") }
            release.complete(Unit)
            assertEquals("FR", state.draft(0).country)
            assertEquals("JP", state.draft(1).country)
        } finally { scope.cancel() }
    }
    @Test fun shellAndFrameworkErrorsStayOutOfNormalUiText() {
        assertFalse(friendlyError("Attempt to invoke android.content.Context on a null object reference").contains("android.content"))
        assertFalse(friendlyError("Global engine ACK unavailable: Invalid global engine sdk").contains("ACK"))
        assertFalse(friendlyError("Root permission denied").contains("授權"))
        assertEquals("模組服務尚未連接，請稍後重新讀取。", friendlyError("模組服務尚未連接"))
    }
    @Test fun shortEnglishSocketErrorsCannotAppearAsUiMessages() {
        listOf("socket not created", "Connection refused", "java.net.SocketException: Connection reset").forEach { raw ->
            assertEquals("模組服務尚未連接，請稍後重新讀取。", friendlyError(raw))
        }
        assertEquals("請輸入兩個英文字母的國家碼", friendlyError("請輸入兩個英文字母的國家碼"))
    }
    @Test fun connectedModuleCanReportDisconnectedPhoneEngineWithoutClaimingIpcFailure() {
        val engine = friendlyEngineError("Phone engine disconnected")
        assertEquals("電話引擎尚未連上，請到工具頁查看原因。", engine)
        assertFalse(engine.contains("模組服務尚未連接"))
        assertEquals(engine, friendlyError("Phone engine disconnected"))
        assertNotEquals(engine, friendlyError("socket not created"))
    }
}

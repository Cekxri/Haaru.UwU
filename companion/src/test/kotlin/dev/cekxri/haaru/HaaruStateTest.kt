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
    private val firstToken = "1".repeat(64)
    private val secondToken = "2".repeat(64)
    private val replacementToken = "3".repeat(64)
    private val initial = """{"ok":true,"enabled":true,"subscriptions":[
        {"slot":0,"subId":1,"ready":true,"error":"","country":"TW","originalCarrierName":"Original One","selectionToken":"$firstToken"},
        {"slot":1,"subId":2,"ready":true,"country":"TW","originalCarrierName":"Original Two","selectionToken":"$secondToken"}],
        "profiles":[{"slot":0,"enabled":true,"country":"GB","carrierName":"EE","matchesCurrentSim":true},
        {"slot":1,"enabled":true,"country":"JP","carrierName":"SoftBank","matchesCurrentSim":true}]}"""
    private class FakeBridge(val initial: String, val verified: Boolean = true) : UiBridge {
        var nextStatus: String? = null
        var lastSelectionToken = ""
        var commandError: String? = null
        override val connected = true
        override val systemDark = false
        override val systemReducedMotion = false
        override suspend fun readPreferences(): String? = null
        override suspend fun writePreferences(value: String) {}
        val actions = mutableListOf<String>()
        override suspend fun command(action: String, slot: Int, country: String, carrier: String,
                                         selectionToken: String): String {
            actions += action
            if (action == "status") {
                if (actions.size == 1) return initial
                return nextStatus ?: error("observation failed")
            }
            lastSelectionToken = selectionToken
            return commandError?.let { """{"ok":false,"error":"$it"}""" }
                ?: """{"ok":true,"verified":$verified}"""
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
        assertEquals(SimDraft(), state.draft(0))
        assertEquals(SimDraft(), state.draft(1))
        assertFalse(state.fresh)
    }
    @Test fun invalidCountryCannotReachRoot() = withState { state, bridge ->
        state.run("set", 0, "ZZ", "EE", firstToken)
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
            override suspend fun readPreferences(): String? = null
            override suspend fun writePreferences(value: String) {}
            override suspend fun command(action: String, slot: Int, country: String, carrier: String,
                                         selectionToken: String): String {
                if (action == "status") return initial
                release.await()
                return """{"ok":true,"verified":true}"""
            }
        }
        try {
            val state = HaaruState(bridge, scope)
            state.run("status")
            state.run("set", 0, "GB", "EE", firstToken)
            state.edit(0, firstToken) { it.copy(country = "FR") }
            release.complete(Unit)
            assertEquals("FR", state.draft(0).country)
            assertEquals("JP", state.draft(1).country)
        } finally { scope.cancel() }
    }
    @Test fun reapplyAllEnablesInactivePlansAlongsideAnActivePlan() {
        val mixed = initial.replace(""""slot":1,"enabled":true""", """"slot":1,"enabled":false""")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val bridge = FakeBridge(mixed).apply { nextStatus = this@HaaruStateTest.initial }
            val state = HaaruState(bridge, scope)
            state.run("status")
            assertTrue(state.snapshot.enabled)
            assertFalse(state.snapshot.profiles[1].enabled)
            state.reapplySavedPlans()
            assertEquals(listOf("status", "enable", "status"), bridge.actions)
            assertTrue(state.snapshot.profiles.all { it.enabled })
        } finally { scope.cancel() }
    }

    @Test fun reapplyOneUsesTheSavedPlanWithoutOverwritingAWorkingDraft() {
        val stopped = initial.replace(""""slot":0,"enabled":true""", """"slot":0,"enabled":false""")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val bridge = FakeBridge(stopped).apply { nextStatus = this@HaaruStateTest.initial }
            val state = HaaruState(bridge, scope)
            state.run("status")
            state.edit(0, firstToken) { it.copy(country = "FR") }
            state.reapplySavedPlan(state.snapshot.cards[0], state.snapshot.profiles[0])
            assertEquals(listOf("status", "set", "status"), bridge.actions)
            assertEquals(firstToken, bridge.lastSelectionToken)
            assertEquals("GB", state.snapshot.profiles[0].country)
            assertEquals("FR", state.draft(0).country)
        } finally { scope.cancel() }
    }

    @Test fun lateEditorCallbackCannotChangeTheReplacementCardDraftOrSendASave() = withState { state, bridge ->
        state.edit(0, firstToken) { it.copy(country = "FR") }
        bridge.nextStatus = initial.replace(firstToken, replacementToken)
            .replace(""""carrierName":"EE","matchesCurrentSim":true""", """"carrierName":"EE","matchesCurrentSim":false""")
        state.run("status")
        assertEquals(SimDraft(), state.draft(0))
        state.edit(0, firstToken) { it.copy(country = "AU") }
        assertEquals(SimDraft(), state.draft(0))
        state.run("set", 0, "AU", "", firstToken)
        assertEquals(listOf("status", "status"), bridge.actions)
        assertTrue(state.failed)
        assertEquals("JP", state.draft(1).country)
    }

    @Test fun aSwapThatHappensAfterTheLastReadUsesTheServerBoundToken() = withState { state, bridge ->
        bridge.commandError = "SIM changed since status was read"
        state.run("set", 0, "FR", "", firstToken)
        assertEquals(listOf("status", "set"), bridge.actions)
        assertEquals(firstToken, bridge.lastSelectionToken)
        assertEquals("GB", state.draft(0).country)
        assertFalse(state.fresh)
        assertTrue(state.notice.contains("SIM 已變更"))
    }

    @Test fun expiredReceiptLosesItsHeadlineBeforeTheWholeStatusExpires() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val raw = initial.trimEnd().dropLast(1) + """, "moduleVersion":"0.8.1","global":true,
                "engine":"system-global-volatile","currentConfigHash":"hash",
                "globalEngine":{"ok":true,"initialized":true,"applied":true,"hooks":8,"sdk":36,
                "requiredHooks":8,"coverageComplete":true,"supportsCountry":true,"supportsCarrierName":true,
                "bootId":"boot","configHash":"hash","error":"","timestampMs":96000}}"""
            var elapsed = 0L
            val bridge = FakeBridge(raw)
            val state = HaaruState(bridge, scope, { elapsed }, { 100_000L })
            state.run("status")
            assertTrue(presentStatus(state).confirmed)
            elapsed = 6_000L
            state.expireStatus()
            assertTrue(state.fresh)
            assertFalse(presentStatus(state).confirmed)
            assertEquals("系統回報待更新", state.engineLabel)
            elapsed = 10_000L
            state.run("set", 0, "FR", "", firstToken)
            assertFalse(state.fresh)
            assertFalse(presentStatus(state).confirmed)
            assertEquals(listOf("status"), bridge.actions)
        } finally { scope.cancel() }
    }

    @Test fun lateStatusCannotRestoreFreshnessWhenActivityLeavesForeground() = runBlocking {
        val pending = CompletableDeferred<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fake = FakeBridge(initial)
        val bridge = object : UiBridge by fake {
            override suspend fun command(action: String, slot: Int, country: String, carrier: String,
                                         selectionToken: String): String = pending.await()
        }
        try {
            val state = HaaruState(bridge, scope)
            state.run("status")
            state.invalidateStatus()
            pending.complete(initial)
            assertFalse(state.fresh)
            assertFalse(state.busy)
        } finally { scope.cancel() }
    }

    @Test fun carrierInputCountsUnicodeCharactersWithoutSplittingThem() {
        val character = "\uD83C\uDF19"
        val full = character.repeat(80)
        assertTrue(SimPlanInput.validCarrier(full))
        assertFalse(SimPlanInput.validCarrier(full + character))
        assertFalse(SimPlanInput.validCarrier("\uD83C"))
        assertEquals(full, SimPlanInput.limitCarrier(full + character))
    }

    @Test fun sharedDiagnosticsKeepsEngineStageWithoutExportingSimOrDeviceValues() {
        val privateValue = "fixture-private"
        val raw = """{"ok":true,"moduleVersion":"0.8.2","bootId":"$privateValue","subscriptions":[{"subId":47291,"selectionToken":"$privateValue","originalCarrierName":"$privateValue"}],"globalEngine":{"phonePid":64919,"timestampMs":1795000000000,"sdk":36,"hooks":0,"stage":"specialization","connected":false,"error":"Phone companion disconnected before post-specialisation validation $privateValue"}}"""
        val shared = shareableDiagnostics(raw, appearanceIssue = false)
        assertTrue(shared.contains("specialization"))
        assertTrue(shared.contains("startup-disconnect"))
        for (value in listOf(privateValue, "47291", "64919", "1795000000000", "bootId", "selectionToken")) {
            assertFalse(shared.contains(value))
        }
    }

    @Test fun shellAndFrameworkErrorsStayOutOfNormalUiText() {
        assertFalse(friendlyError("Attempt to invoke android.content.Context on a null object reference").contains("android.content"))
        assertFalse(friendlyError("Global engine ACK unavailable: Invalid global engine sdk").contains("ACK"))
        assertFalse(friendlyError("Root permission denied").contains("授權"))
        assertEquals("模組服務尚未連接，請稍後重新讀取。", friendlyError("模組服務尚未連接"))
        val socketError = "模組服務尚未連接，請確認 Haaru 模組已安裝並重新啟動手機"
        assertFalse(moduleIsAbsent(socketError))
        assertEquals("模組服務尚未連接，請稍後重新讀取。", friendlyError(socketError))
        assertTrue(moduleIsAbsent("Module directory is absent"))
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
        assertTrue(friendlyEngineError("Combined SIM operator names exceed Android's 91-byte property limit.")
            .contains("請縮短自訂名稱"))
    }
}

/* SPDX-License-Identifier: Apache-2.0 */
package dev.cekxri.haaru

import haaru.ui.Appearance
import haaru.ui.Palette
import haaru.ui.UiBridge
import haaru.ui.UiPreferences
import haaru.ui.UiStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AppearancePreferencesTest {
    @Test fun initialReadFailureNeverWritesDefaultsAndRereadRestoresStoredValues() = scenario { model, bridge ->
        model.update { error("An edit before the first read must not execute") }
        model.reread()
        val read = bridge.nextRead()
        assertEquals(PreferencePhase.READING, model.state.value.phase)
        model.update { error("An edit during the first read must not execute") }
        read.reply.completeExceptionally(IllegalStateException("Service unavailable"))
        assertFalse(model.at(PreferencePhase.READ_FAILED).editable)
        model.update { error("An edit after a failed read must not execute") }
        assertTrue(bridge.requests.tryReceive().isFailure)

        model.reread()
        bridge.nextRead().reply.complete("""{"style":"MATERIAL3","appearance":"DARK","palette":"MINT","glass":false}""")
        val loaded = model.at(PreferencePhase.READY)
        assertTrue(loaded.editable)
        assertEquals(UiStyle.MIUIX, loaded.value.style)
        assertEquals(Appearance.DARK, loaded.value.appearance)
        assertEquals(Palette.MINT, loaded.value.palette)
        assertFalse(loaded.value.glass)
        assertTrue(loaded.value.dynamicColour)
        assertTrue(bridge.requests.tryReceive().isFailure)
    }

    @Test fun aNewRereadRequestedDuringAnOldReadRunsBeforeUnlockingEdits() = scenario { model, bridge ->
        model.reread()
        val first = bridge.nextRead()
        model.reread()
        first.reply.complete(UiPreferences(palette = Palette.SLATE).encode())
        val second = bridge.nextRead()
        assertFalse(model.state.value.editable)
        second.reply.complete(UiPreferences(palette = Palette.IRIS).encode())
        assertEquals(Palette.IRIS, model.at(PreferencePhase.READY).value.palette)
        assertTrue(bridge.requests.tryReceive().isFailure)
    }

    @Test fun changesComposeWithTheLatestValueAndWaitForTheirOwnVerifiedWrite() = scenario { model, bridge ->
        model.reread()
        bridge.nextRead().reply.complete(null)
        assertEquals(UiPreferences(), model.at(PreferencePhase.READY).value)
        model.update { it.copy(glass = false) }
        val first = bridge.nextWrite()
        assertFalse(UiPreferences.decode(first.value).glass)

        model.update { it.copy(palette = Palette.PLUM) }
        model.update { it.copy(reduceMotion = true) }
        first.reply.complete(Unit)
        val second = bridge.nextWrite()
        assertEquals(UiPreferences(glass = false, palette = Palette.PLUM, reduceMotion = true), UiPreferences.decode(second.value))
        assertEquals(PreferencePhase.SAVING, model.state.value.phase)
        assertTrue(bridge.requests.tryReceive().isFailure)

        second.reply.complete(Unit)
        assertEquals(UiPreferences.decode(second.value), model.at(PreferencePhase.SAVED).value)
    }

    @Test fun rereadWaitsForAcceptedWritesAndAnUnconfirmedWriteRequiresReconciliation() = scenario { model, bridge ->
        val previous = UiPreferences(palette = Palette.LAVENDER)
        model.reread()
        bridge.nextRead().reply.complete(previous.encode())
        model.at(PreferencePhase.READY)

        model.update { it.copy(glass = false) }
        val write = bridge.nextWrite()
        model.update { it.copy(reduceMotion = true) }
        write.reply.completeExceptionally(IllegalStateException("Acknowledgement lost"))
        val failed = model.at(PreferencePhase.SAVE_FAILED)
        assertEquals(previous, failed.value)
        assertFalse(failed.editable)
        assertTrue(bridge.requests.tryReceive().isFailure)

        model.reread()
        // The first write reached the module even though its reply was lost.
        bridge.nextRead().reply.complete(write.value)
        val reconciled = model.at(PreferencePhase.READY)
        assertFalse(reconciled.value.glass)
        assertFalse(reconciled.value.reduceMotion)

        model.update { it.copy(reduceMotion = true) }
        val accepted = bridge.nextWrite()
        model.reread()
        assertFalse(model.state.value.editable)
        assertTrue(bridge.requests.tryReceive().isFailure)
        accepted.reply.complete(Unit)
        bridge.nextRead().reply.complete(accepted.value)
        assertTrue(model.at(PreferencePhase.READY).value.reduceMotion)
    }

    @Test fun readOnlyUnlocksForNullOrKnownLegacyAndCanonicalValues() {
        assertNull(appearanceFromReply(JsonObject(mapOf("appearance" to JsonNull))))
        assertThrows(IllegalStateException::class.java) { appearanceFromReply(JsonObject(emptyMap())) }
        assertThrows(IllegalStateException::class.java) { appearanceFromReply(json("""{"appearance":"{}"}""")) }
        val legacy = decodeStoredPreferences(appearanceFromReply(json("""{"appearance":{"customColour":"#aBcDeF","style":"MATERIAL3"}}""")))
        assertEquals("#ABCDEF", legacy.customColour)
        assertEquals(UiStyle.MIUIX, legacy.style)
        for (raw in listOf("""{"futureSetting":true}""", """{"glass":"true"}""", """{"palette":"UNKNOWN"}""")) {
            assertThrows(IllegalStateException::class.java) { decodeStoredPreferences(raw) }
        }
    }

    @Test fun saveRequiresMatchingCanonicalValuesAndAnExplicitModuleFileAcknowledgement() {
        val expected = UiPreferences(palette = Palette.IRIS, glass = false).encode()
        val reordered = JsonObject(json(expected).entries.reversed().associate { it.toPair() })
        val valid = mapOf("ok" to JsonPrimitive(true), "verified" to JsonPrimitive(true),
            "verification" to JsonPrimitive("module-files"), "appearance" to reordered)
        verifyAppearanceWrite(JsonObject(valid), expected)

        val falseReceipts = listOf(
            valid - "appearance", valid - "verified", valid + ("verified" to JsonPrimitive("true")),
            valid + ("ok" to JsonPrimitive(false)), valid + ("verification" to JsonPrimitive("unverified")),
            valid + ("appearance" to json(UiPreferences(palette = Palette.PLUM).encode())),
        )
        for (receipt in falseReceipts) {
            assertThrows(IllegalStateException::class.java) { verifyAppearanceWrite(JsonObject(receipt), expected) }
        }
    }

    private fun scenario(block: suspend (AppearancePreferences, ControlledBridge) -> Unit) = runBlocking {
        withTimeout(5_000) {
            val owner = CoroutineScope(coroutineContext + Job())
            val bridge = ControlledBridge()
            try { block(AppearancePreferences(owner, bridge), bridge) }
            finally { owner.cancel() }
        }
    }

    private suspend fun AppearancePreferences.at(phase: PreferencePhase) = state.first { it.phase == phase }
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private sealed interface Request
    private class Read(val reply: CompletableDeferred<String?> = CompletableDeferred()) : Request
    private class Write(val value: String, val reply: CompletableDeferred<Unit> = CompletableDeferred()) : Request

    private class ControlledBridge : UiBridge {
        override val connected = true
        override val systemDark = false
        override val systemReducedMotion = false
        val requests = Channel<Request>(Channel.UNLIMITED)
        override suspend fun readPreferences(): String? = Read().also { requests.send(it) }.reply.await()
        override suspend fun writePreferences(value: String) { Write(value).also { requests.send(it) }.reply.await() }
        override suspend fun command(action: String, slot: Int, country: String, carrier: String, selectionToken: String): String =
            error("Appearance must not issue SIM commands")
        suspend fun nextRead() = requests.receive() as Read
        suspend fun nextWrite() = requests.receive() as Write
    }
}

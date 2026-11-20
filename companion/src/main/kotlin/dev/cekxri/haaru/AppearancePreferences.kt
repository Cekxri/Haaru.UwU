/* SPDX-License-Identifier: Apache-2.0 */
package dev.cekxri.haaru

import haaru.ui.UiBridge
import haaru.ui.UiPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

internal enum class PreferencePhase { READING, READY, SAVING, SAVED, READ_FAILED, SAVE_FAILED }

internal data class PreferenceState(
    val value: UiPreferences = UiPreferences(),
    val phase: PreferencePhase = PreferencePhase.READING,
    val readQueued: Boolean = false,
) {
    val editable: Boolean get() = !readQueued && when (phase) {
        PreferencePhase.READY, PreferencePhase.SAVING, PreferencePhase.SAVED -> true
        else -> false
    }
}

/** Main-thread owner of appearance state, retained by the activity's ViewModel. */
internal class AppearancePreferences(scope: CoroutineScope, private val bridge: UiBridge) {
    private val mutableState = MutableStateFlow(PreferenceState())
    val state = mutableState.asStateFlow()
    private val work = Channel<Unit>(Channel.CONFLATED)
    private var confirmed = UiPreferences()
    private var pending: UiPreferences? = null
    private var readRequested = false

    init {
        scope.launch {
            for (signal in work) {
                while (true) {
                    val candidate = pending
                    when {
                        candidate != null -> save(candidate)
                        readRequested -> read()
                        else -> break
                    }
                }
            }
        }
    }

    fun reread() {
        val current = state.value
        if (readRequested) return
        readRequested = true
        val phase = if (current.phase == PreferencePhase.SAVING) current.phase else PreferencePhase.READING
        mutableState.value = current.copy(phase = phase, readQueued = true)
        work.trySend(Unit)
    }

    fun update(change: (UiPreferences) -> UiPreferences) {
        val current = state.value
        if (!current.editable) return
        val candidate = change(current.value)
        if (candidate == current.value) return
        pending = candidate
        mutableState.value = current.copy(value = candidate, phase = PreferencePhase.SAVING)
        work.trySend(Unit)
    }

    private suspend fun save(candidate: UiPreferences) {
        pending = null
        try {
            bridge.writePreferences(candidate.encode())
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // The write may have reached the server even if its reply was lost.
            // Discard dependent edits until the stored value has been read again.
            pending = null
            mutableState.value = PreferenceState(confirmed, PreferencePhase.SAVE_FAILED, state.value.readQueued)
            return
        }
        confirmed = candidate
        if (pending == null) {
            val readQueued = state.value.readQueued
            val phase = if (readQueued) PreferencePhase.READING else PreferencePhase.SAVED
            mutableState.value = PreferenceState(confirmed, phase, readQueued)
        }
    }

    private suspend fun read() {
        readRequested = false
        val result = try {
            confirmed = decodeStoredPreferences(bridge.readPreferences())
            PreferencePhase.READY
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            PreferencePhase.READ_FAILED
        }
        val phase = if (readRequested) PreferencePhase.READING else result
        mutableState.value = PreferenceState(confirmed, phase, readRequested)
    }
}

internal fun appearanceFromReply(payload: JsonObject): String? = when (val value = payload["appearance"]) {
    JsonNull -> null
    is JsonObject -> value.toString()
    else -> error("模組未回報有效的外觀設定")
}

internal fun verifyAppearanceWrite(payload: JsonObject, canonical: String) {
    check(payload["ok"] == JsonPrimitive(true) && payload["verified"] == JsonPrimitive(true) &&
        payload["verification"] == JsonPrimitive("module-files") &&
        payload["appearance"] == Json.parseToJsonElement(canonical)) { "未取得模組的外觀儲存確認" }
}

internal fun decodeStoredPreferences(raw: String?): UiPreferences {
    if (raw == null) return UiPreferences()
    val stored = Json.parseToJsonElement(raw) as? JsonObject ?: error("模組未回報有效的外觀設定")
    val preferences = UiPreferences.decode(raw)
    val canonical = Json.parseToJsonElement(preferences.encode()) as JsonObject
    // Allow legacy fields and omitted keys. Any present value we cannot round trip keeps editing locked.
    check(stored.all { (key, value) ->
        when (key) {
            "style" -> value == JsonPrimitive("MATERIAL3") || value == canonical[key]
            "customColour" -> value is JsonPrimitive && value.isString &&
                JsonPrimitive(value.content.uppercase(Locale.ROOT)) == canonical[key]
            else -> value == canonical[key]
        }
    }) { "無法讀取模組的外觀設定" }
    return preferences
}

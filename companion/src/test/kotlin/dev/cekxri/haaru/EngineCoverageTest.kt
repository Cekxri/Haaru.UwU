package dev.cekxri.haaru

import haaru.ui.*
import org.junit.Assert.*
import org.junit.Test

class EngineCoverageTest {
    private val complete = GlobalEngineStatus(ok = true, initialized = true, applied = true, hooks = 8,
        sdk = 36, bootId = "current-boot", configHash = "current-settings", requiredHooks = 8,
        coverageComplete = true, supportsCountry = true, supportsCarrierName = true)

    @Test fun dynamicCoverageAcceptsAllDiscoveredHooksAndRejectsPartialCoverage() {
        assertTrue(complete.confirmed("current-settings"))
        assertFalse(complete.copy(hooks = 7).confirmed("current-settings"))
        assertFalse(complete.copy(coverageComplete = false).confirmed("current-settings"))
        assertFalse(complete.copy(hooks = 2, requiredHooks = 2).confirmed("current-settings"))
    }
    @Test fun onlyRequestedFieldsNeedToBeSupported() {
        val countryOnly = UiSnapshot(enabled = true, global = true, engine = "system-global-volatile",
            currentConfigHash = "current-settings", globalEngine = complete.copy(supportsCarrierName = false),
            profiles = listOf(SimProfile(0, "GB", "", enabled = true)))
        assertTrue(countryOnly.globalConfirmed)
        assertFalse(countryOnly.copy(profiles = listOf(SimProfile(0, "GB", "EE", enabled = true))).globalConfirmed)
    }
    @Test fun legacyAckCannotClaimCoverageThatItDidNotReport() {
        val raw = """{"ok":true,"global":true,"engine":"system-global-volatile","currentConfigHash":"current-settings",
            "globalEngine":{"ok":true,"initialized":true,"applied":true,"hooks":8,"sdk":36,
            "bootId":"current-boot","configHash":"current-settings","error":""}}"""
        assertFalse(UiSnapshot.parse(raw).globalConfirmed)
    }
}

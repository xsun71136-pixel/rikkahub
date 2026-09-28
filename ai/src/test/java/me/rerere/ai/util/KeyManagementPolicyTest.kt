package me.rerere.ai.util

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class KeyManagementPolicyTest {
    @Test fun legacyDefaults() {
        assertEquals(KeyManagementPolicy(), Json.decodeFromString<KeyManagementPolicy>("{}"))
        assertEquals(KeyFailureAction.SUSPEND, KeyManagementPolicy().action(KeyVerdict.INVALID))
        assertEquals(KeyFailureAction.SUSPEND, KeyManagementPolicy().action(KeyVerdict.QUOTA))
        assertEquals(KeyFailureAction.COOLDOWN, KeyManagementPolicy().action(KeyVerdict.COOLDOWN))
    }
    @Test fun disabledPolicyNeverPenalizes() {
        KeyVerdict.entries.forEach { assertEquals(KeyFailureAction.IGNORE, KeyManagementPolicy(enabled = false).action(it)) }
    }
    @Test fun actionsAreIndependent() {
        val p = KeyManagementPolicy(invalidAction = KeyFailureAction.IGNORE, quotaAction = KeyFailureAction.COOLDOWN, rateLimitAction = KeyFailureAction.SUSPEND)
        assertEquals(KeyFailureAction.IGNORE, p.action(KeyVerdict.INVALID))
        assertEquals(KeyFailureAction.COOLDOWN, p.action(KeyVerdict.QUOTA))
        assertEquals(KeyFailureAction.SUSPEND, p.action(KeyVerdict.COOLDOWN))
        assertEquals(KeyFailureAction.IGNORE, p.action(KeyVerdict.NEUTRAL))
    }
    @Test fun cooldownGrowthAndCap() {
        val p = KeyManagementPolicy()
        assertEquals(60000L, p.cooldownDurationMs(1))
        assertEquals(120000L, p.cooldownDurationMs(2))
        assertEquals(1800000L, p.cooldownDurationMs(32))
    }
    @Test fun fixedCooldownAndNegativeCount() {
        val p = KeyManagementPolicy(cooldownMultiplier = 1.0)
        assertEquals(60000L, p.cooldownDurationMs(0))
        assertEquals(60000L, p.cooldownDurationMs(Int.MAX_VALUE))
    }
    @Test fun boundsAndNonFinite() {
        val p = KeyManagementPolicy(suspendHours = -1, cooldownSeconds = 4000, maxCooldownSeconds = 1,
            cooldownMultiplier = Double.NaN, maxSwitches = 999, switchDelayMs = -1).clamped()
        assertEquals(1, p.suspendHours)
        assertEquals(3600, p.cooldownSeconds)
        assertEquals(3600, p.maxCooldownSeconds)
        assertEquals(2.0, p.cooldownMultiplier, 0.0)
        assertEquals(20, p.maxSwitches)
        assertEquals(0L, p.switchDelayMs)
    }
    @Test fun manualRecoveryRoundTrips() {
        val policy = KeyManagementPolicy(manualRecoveryOnly = true, autoSwitch = false)
        assertEquals(policy, Json.decodeFromString<KeyManagementPolicy>(Json.encodeToString(policy)))
    }
    @Test fun neutralStatusesRemainNeutralRegardlessOfAction() {
        assertEquals(KeyVerdict.NEUTRAL, KeyHealthRegistry.classify(ProviderHttpException(503, "invalid api key")))
        assertEquals(KeyVerdict.NEUTRAL, KeyHealthRegistry.classify(ProviderHttpException(403, "model not permitted")))
    }
}

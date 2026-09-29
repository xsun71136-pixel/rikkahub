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

    @Test fun independentRulesPreserveLegacyFallback() {
        val p = KeyManagementPolicy(suspendHours = 12, quotaRule = KeyFailureRule(suspendMinutes = 5))
        assertEquals(720, p.ruleFor(0).suspendMinutes)
        assertEquals(5, p.ruleFor(1).suspendMinutes)
        assertEquals(60000L, p.ruleFor(2).durationMs(1))
    }
    @Test fun perRuleDurationAndLimits() {
        assertEquals(300000L, KeyFailureRule(suspendMinutes = 5).durationMs(1))
        assertEquals(Long.MAX_VALUE, KeyFailureRule(manualRecoveryOnly = true).durationMs(1))
        assertEquals(0L, KeyFailureRule(action = KeyFailureAction.IGNORE).durationMs(1))
        val rule = KeyFailureRule(action = KeyFailureAction.COOLDOWN, cooldownSeconds = 10, maxCooldownSeconds = 25)
        assertEquals(10000L, rule.durationMs(1))
        assertEquals(20000L, rule.durationMs(2))
        assertEquals(25000L, rule.durationMs(3))
    }
    @Test fun customConditionsAreAndAcrossGroups() {
        val rule = CustomKeyRule(statusCodes = setOf(403, 418), keywords = listOf("expired", "revoked"))
        assertTrue(rule.matches(403, "KEY EXPIRED"))
        assertTrue(rule.matches(418, "token revoked"))
        assertFalse(rule.matches(401, "expired"))
        assertFalse(rule.matches(403, "model denied"))
        assertFalse(rule.matches(null, "expired"))
    }
    @Test fun noCatchAllOrServerPenalty() {
        assertFalse(CustomKeyRule().matches(401, "anything"))
        assertFalse(CustomKeyRule(statusCodes = setOf(503)).matches(401, "anything"))
        assertFalse(CustomKeyRule(statusCodes = setOf(503), keywords = listOf("expired")).clamped().matches(403, "expired"))
        assertFalse(CustomKeyRule(keywords = listOf("expired")).matches(503, "expired"))
        assertFalse(CustomKeyRule(enabled = false, keywords = listOf("expired")).matches(403, "expired"))
    }
    @Test fun firstCustomMatchWinsAndIgnoreOverridesDefault() {
        try {
            KeyRotationPolicy.configure(KeyManagementPolicy(customRules = listOf(
                CustomKeyRule(statusCodes = setOf(401), behavior = KeyFailureRule(action = KeyFailureAction.IGNORE)),
                CustomKeyRule(statusCodes = setOf(401), behavior = KeyFailureRule()),
            )))
            val decision = KeyHealthRegistry.decision(ProviderHttpException(401))
            assertEquals(KeyFailureAction.IGNORE, decision.rule.action)
            assertEquals("custom", decision.reason)
            assertFalse(KeyRotationPolicy.isKeyLevelError(ProviderHttpException(401)))
        } finally { KeyRotationPolicy.configure(KeyManagementPolicy()) }
    }
    @Test fun cancellationAndPoolExhaustionCannotMatchCustom() {
        try {
            KeyRotationPolicy.configure(KeyManagementPolicy(customRules = listOf(CustomKeyRule(keywords = listOf("match")))))
            assertFalse(KeyRotationPolicy.isKeyLevelError(kotlinx.coroutines.CancellationException("match")))
            assertFalse(KeyRotationPolicy.isKeyLevelError(RuntimeException("match", kotlinx.coroutines.CancellationException())))
            assertFalse(KeyRotationPolicy.isKeyLevelError(AllKeysSuspendedException("match")))
            assertFalse(KeyRotationPolicy.isKeyLevelError(ProviderHttpException(503, "match")))
        } finally { KeyRotationPolicy.configure(KeyManagementPolicy()) }
    }
    @Test fun customServerGuardAndOffSwitch() {
        try {
            val p = KeyManagementPolicy(customRules = listOf(CustomKeyRule(statusCodes = setOf(403))))
            KeyRotationPolicy.configure(p)
            assertTrue(KeyRotationPolicy.isKeyLevelError(ProviderHttpException(403)))
            KeyRotationPolicy.configure(p.copy(enabled = false))
            assertFalse(KeyRotationPolicy.isKeyLevelError(ProviderHttpException(403)))
        } finally { KeyRotationPolicy.configure(KeyManagementPolicy()) }
    }
    @Test fun newFieldsRoundTrip() {
        val p = KeyManagementPolicy(invalidRule = KeyFailureRule(suspendMinutes = 2),
            customRules = listOf(CustomKeyRule(name = "expired", keywords = listOf("expired"))))
        assertEquals(p, Json.decodeFromString<KeyManagementPolicy>(Json.encodeToString(p)))
    }
}

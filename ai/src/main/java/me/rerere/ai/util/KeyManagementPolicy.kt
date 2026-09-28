package me.rerere.ai.util

import kotlinx.serialization.Serializable
import kotlin.math.pow

@Serializable
enum class KeyFailureAction { IGNORE, COOLDOWN, SUSPEND }

/** Global policy. Changes affect future failures; existing records retain their expiry. */
@Serializable
data class KeyManagementPolicy(
    val enabled: Boolean = true,
    val invalidAction: KeyFailureAction = KeyFailureAction.SUSPEND,
    val quotaAction: KeyFailureAction = KeyFailureAction.SUSPEND,
    val rateLimitAction: KeyFailureAction = KeyFailureAction.COOLDOWN,
    val suspendHours: Int = 24,
    val manualRecoveryOnly: Boolean = false,
    val cooldownSeconds: Int = 60,
    val maxCooldownSeconds: Int = 1800,
    val cooldownMultiplier: Double = 2.0,
    val autoSwitch: Boolean = true,
    val maxSwitches: Int = 8,
    val switchDelayMs: Long = 300,
) {
    fun clamped(): KeyManagementPolicy = copy(
        suspendHours = suspendHours.coerceIn(1, 720),
        cooldownSeconds = cooldownSeconds.coerceIn(1, 3600),
        maxCooldownSeconds = maxCooldownSeconds.coerceIn(cooldownSeconds.coerceIn(1, 3600), 86400),
        cooldownMultiplier = if (cooldownMultiplier.isFinite()) cooldownMultiplier.coerceIn(1.0, 5.0) else 2.0,
        maxSwitches = maxSwitches.coerceIn(0, 20),
        switchDelayMs = switchDelayMs.coerceIn(0, 10000),
    )

    internal fun action(verdict: KeyVerdict): KeyFailureAction = if (!enabled) KeyFailureAction.IGNORE else when (verdict) {
        KeyVerdict.INVALID -> invalidAction
        KeyVerdict.QUOTA -> quotaAction
        KeyVerdict.COOLDOWN -> rateLimitAction
        KeyVerdict.NEUTRAL -> KeyFailureAction.IGNORE
    }

    fun cooldownDurationMs(failures: Int): Long {
        val c = clamped()
        return (c.cooldownSeconds * 1000.0 * c.cooldownMultiplier.pow((failures.coerceIn(1, 32) - 1)))
            .coerceAtMost(c.maxCooldownSeconds * 1000.0).toLong()
    }
}

package me.rerere.ai.util

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.pow

/** Per-error behavior. Minutes allow short suspensions; zero does NOT mean forever. */
@Serializable
data class KeyFailureRule(
    val action: KeyFailureAction = KeyFailureAction.SUSPEND,
    val suspendMinutes: Int = 1440,
    val manualRecoveryOnly: Boolean = false,
    val cooldownSeconds: Int = 60,
    val maxCooldownSeconds: Int = 1800,
    val multiplier: Double = 2.0,
) {
    fun clamped(): KeyFailureRule = copy(
        suspendMinutes = suspendMinutes.coerceIn(1, 43200),
        cooldownSeconds = cooldownSeconds.coerceIn(1, 86400),
        maxCooldownSeconds = maxCooldownSeconds.coerceIn(cooldownSeconds.coerceIn(1, 86400), 604800),
        multiplier = if (multiplier.isFinite()) multiplier.coerceIn(1.0, 5.0) else 2.0,
    )
    fun durationMs(failures: Int): Long {
        val c = clamped()
        return when (c.action) {
            KeyFailureAction.IGNORE -> 0L
            KeyFailureAction.SUSPEND -> if (c.manualRecoveryOnly) Long.MAX_VALUE else c.suspendMinutes * 60000L
            KeyFailureAction.COOLDOWN -> (c.cooldownSeconds * 1000.0 * c.multiplier.pow(failures.coerceIn(1, 32) - 1))
                .coerceAtMost(c.maxCooldownSeconds * 1000.0).toLong()
        }
    }
}

/** First match wins. Both nonempty condition groups must match. No regex or catch-all. */
@Serializable
data class CustomKeyRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val statusCodes: Set<Int> = emptySet(),
    val keywords: List<String> = emptyList(),
    val behavior: KeyFailureRule = KeyFailureRule(),
) {
    fun clamped(): CustomKeyRule = copy(
        enabled = enabled && statusCodes.all { it in 400..499 },
        name = name.trim().take(80),
        statusCodes = statusCodes.filter { it in 400..499 }.toSet(),
        keywords = keywords.map { it.trim().take(120) }.filter { it.isNotEmpty() }.distinct().take(30),
        behavior = behavior.clamped(),
    )
    internal fun matches(status: Int?, text: String): Boolean {
        val c = clamped()
        if (!c.enabled || (status != null && status >= 500)) return false
        if (c.statusCodes.isEmpty() && c.keywords.isEmpty()) return false
        return (c.statusCodes.isEmpty() || status in c.statusCodes) &&
            (c.keywords.isEmpty() || c.keywords.any { text.contains(it, ignoreCase = true) })
    }
}

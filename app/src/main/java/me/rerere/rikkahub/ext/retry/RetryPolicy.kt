package me.rerere.rikkahub.ext.retry

import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * 自动重试判定与退避策略（自定义插件层，移植自 kelivo retry_policy.dart，
 * 见 docs/custom/02-auto-retry.md）。
 *
 * 上游只对 IOException 重试，而 provider 实现在 HTTP 失败时抛出的是普通
 * Exception("Failed to get response: <code> <body>")，导致 429/5xx 等最常见的
 * 可重试错误从不触发重试。本策略按 状态码 + 关键词 + 停止词 综合判定。
 */
object RetryPolicy {

    // rikkahub provider: "Failed to get response: 429 {...}"
    private val RESPONSE_CODE_REGEX = Regex("""response:\s*(\d{3})""", RegexOption.IGNORE_CASE)

    // 通用形式: "HTTP 503" / "status code: 503" / "Error code: 503"
    private val GENERIC_STATUS_REGEX =
        Regex("""(?:HTTP|status\s*code|error\s*code)[:\s]+(\d{3})""", RegexOption.IGNORE_CASE)

    /** 从异常信息中提取 HTTP 状态码（无法提取返回 null）。 */
    fun extractHttpStatus(error: Throwable): Int? {
        val text = error.message ?: error.toString()
        val full = buildString {
            append(text)
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 3) {
                append(' ')
                append(cause.message ?: cause.toString())
                cause = cause.cause
                depth++
            }
        }
        val match = RESPONSE_CODE_REGEX.find(full) ?: GENERIC_STATUS_REGEX.find(full)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun isNetworkTransportError(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            if (current is IOException) return true
            current = current.cause
            depth++
        }
        return false
    }

    private fun containsKeyword(text: String, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val lower = text.lowercase()
        return keywords.any { it.isNotBlank() && lower.contains(it.lowercase()) }
    }

    /**
     * 是否应当重试（kelivo 判定顺序）：
     * 1. 用户取消 → 否
     * 2. 命中停止关键词（余额不足/无效 Key 等）→ 否
     * 3. 网络传输类错误 → 由 retryOnNetworkError 决定
     * 4. 命中重试关键词（限流/繁忙等）→ 是
     * 5. HTTP 状态码在重试集合中 → 是
     * 6. 其他 → 否
     */
    fun shouldRetry(error: Throwable, config: AutoRetryConfig): Boolean {
        if (error is CancellationException) return false
        val text = buildString {
            append(error.message ?: "")
            append(' ')
            append(error.toString())
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 3) {
                append(' ')
                append(cause.message ?: "")
                cause = cause.cause
                depth++
            }
        }
        if (containsKeyword(text, config.stopKeywords)) return false
        val status = extractHttpStatus(error)
        if (isNetworkTransportError(error)) return config.retryOnNetworkError
        if (containsKeyword(text, config.retryKeywords)) return true
        if (status != null && status in config.retryStatusCodes) return true
        return false
    }

    /**
     * 第 [attemptIndex] 次重试（0 起）前的延迟：
     * initialDelay * multiplier^attempt，封顶 maxDelay，jitter ±20%。
     */
    fun backoffDelay(attemptIndex: Int, config: AutoRetryConfig): Long {
        val c = config.clamped()
        val factor = if (attemptIndex <= 0) 1.0 else c.multiplier.pow(attemptIndex)
        var ms = c.initialDelayMs * factor
        if (!ms.isFinite() || ms > c.maxDelayMs) ms = c.maxDelayMs.toDouble()
        if (ms < 0) ms = 0.0
        if (c.jitter) {
            ms *= 0.8 + Random.nextDouble() * 0.4
        }
        return min(ms.toLong().coerceAtLeast(0), c.maxDelayMs)
    }
}

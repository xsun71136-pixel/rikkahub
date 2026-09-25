package me.rerere.rikkahub.ext.retry

import kotlinx.serialization.Serializable

/**
 * 高级自动重试配置（自定义插件层，参考 kelivo AutoRetryOptions，
 * 见 docs/custom/02-auto-retry.md）。
 *
 * 挂载于 NetworkSetting.autoRetry 字段；既有 enableAutoRetry 保留为总开关。
 * 全部字段带默认值 → 旧版本 DataStore JSON 反序列化无缝兼容（JsonInstant
 * ignoreUnknownKeys=true）。
 */
@Serializable
data class AutoRetryConfig(
    /** 首次请求之外的额外重试次数，3 = 最多请求 4 次。0..10 */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** 首次重试前的延迟（毫秒） */
    val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    /** 指数退避倍率 */
    val multiplier: Double = DEFAULT_MULTIPLIER,
    /** 单次延迟上限（毫秒） */
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    /** 每次延迟随机 ±20%，避免多客户端同时重试造成拥塞 */
    val jitter: Boolean = true,
    /** 是否重试网络传输类错误（超时/断连等 IOException） */
    val retryOnNetworkError: Boolean = true,
    /** 命中即重试的 HTTP 状态码 */
    val retryStatusCodes: Set<Int> = DEFAULT_RETRY_STATUS_CODES,
    /** 错误文本命中即重试的关键词（如限流提示） */
    val retryKeywords: List<String> = DEFAULT_RETRY_KEYWORDS,
    /** 错误文本命中即立刻失败的关键词（如余额不足，重试无意义） */
    val stopKeywords: List<String> = DEFAULT_STOP_KEYWORDS,
) {
    companion object {
        const val MIN_MAX_RETRIES = 0
        const val MAX_MAX_RETRIES = 10
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_INITIAL_DELAY_MS = 1000L
        const val DEFAULT_MULTIPLIER = 2.0
        const val DEFAULT_MAX_DELAY_MS = 30000L
        const val MIN_MULTIPLIER = 1.0
        const val MAX_MULTIPLIER = 5.0

        val DEFAULT_RETRY_STATUS_CODES = setOf(408, 425, 429, 500, 502, 503, 504, 529)

        val DEFAULT_RETRY_KEYWORDS = listOf(
            "并发", "稍后", "重试", "访问量过大", "繁忙", "限流",
            "rate limit", "too many requests", "overloaded", "try again",
            "timeout", "超时",
        )

        val DEFAULT_STOP_KEYWORDS = listOf(
            "余额", "不足", "额度", "欠费", "未实名",
            "balance", "insufficient", "quota", "invalid api key",
            "unauthorized", "permission",
        )
    }

    fun clamped(): AutoRetryConfig = copy(
        maxRetries = maxRetries.coerceIn(MIN_MAX_RETRIES, MAX_MAX_RETRIES),
        initialDelayMs = initialDelayMs.coerceAtLeast(0),
        multiplier = if (!multiplier.isFinite() || multiplier <= 0) DEFAULT_MULTIPLIER
        else multiplier.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER),
        maxDelayMs = maxDelayMs.coerceAtLeast(0),
    )
}

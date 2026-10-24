package me.rerere.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import me.rerere.ai.ui.UIMessage
import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 请求模型失败时的重试策略：最多重试 [maxRetries] 次，等待时间从 [initialDelay] 开始按 [backoffFactor] 退避，
 * 单次等待不超过 [maxDelay]；[jitter] 开启时每次等待在 ±20% 内浮动，避免多个客户端同时重试撞在一起。
 *
 * 默认只重试 [IOException]，也就是连不上、超时、读到一半断开这类网络故障。
 * [responseRetryCheck] 在请求"成功"后检查响应内容，返回非空说明命中了需要重试的内容（比如网关用 200
 * 返回"繁忙"），会以 [RetryableResponseException] 触发重试；调用方负责跳过工具调用等不该检查的消息。
 */
data class RetryPolicy(
    val maxRetries: Int,
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
    val backoffFactor: Double = 2.0,
    val jitter: Boolean = false,
    val retryOn: (Throwable) -> Boolean = { it is IOException },
    val responseRetryCheck: (UIMessage) -> String? = { null },
) {
    companion object {
        val NONE = RetryPolicy(maxRetries = 0)

        /** 网络自动重试：最多 3 次，间隔 1s、2s、4s */
        val NETWORK = RetryPolicy(maxRetries = 3)
    }
}

/** 响应内容命中了重试关键字，按配置要重新发起请求；不是真实故障，只用于驱动重试流程 */
class RetryableResponseException(message: String) : RuntimeException(message)

/** 第 [retry] 次重试（共 [maxRetries] 次）将在 [delay] 后开始，[cause] 是上一次失败的原因 */
data class RetryAttempt(
    val cause: Throwable,
    val retry: Int,
    val maxRetries: Int,
    val delay: Duration,
)

/**
 * 按 [policy] 执行 [block]：失败且允许重试时先调用 [onRetry]，等待后再执行一次。
 */
suspend fun <T> withRetry(
    policy: RetryPolicy,
    onRetry: suspend (RetryAttempt) -> Unit = {},
    block: suspend () -> T,
): T {
    var retries = 0
    while (true) {
        try {
            return block()
        } catch (error: Throwable) {
            // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
            // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
            currentCoroutineContext().ensureActive()
            if (error is CancellationException || retries >= policy.maxRetries || !policy.retryOn(error)) {
                throw error
            }
            retries++
            val backoff = computeBackoff(policy, retries)
            onRetry(RetryAttempt(error, retries, policy.maxRetries, backoff))
            delay(backoff)
        }
    }
}

/** 第 [retry] 次（从 1 开始）重试前等待的时长：指数退避、封顶 [RetryPolicy.maxDelay]、可选 ±20% 抖动 */
private fun computeBackoff(policy: RetryPolicy, retry: Int): Duration {
    var factor = 1.0
    repeat(retry - 1) { factor *= policy.backoffFactor.coerceAtLeast(1.0) }
    var backoff = policy.initialDelay * factor
    if (backoff > policy.maxDelay) backoff = policy.maxDelay
    if (policy.jitter && backoff > Duration.ZERO) {
        val ratio = 0.8 + Random.nextDouble() * 0.4
        backoff = backoff * ratio
    }
    return backoff
}

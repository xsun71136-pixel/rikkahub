package me.rerere.ai.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.MultiKeyStateCenter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 多 Key 并发策略的装饰器：同一请求同时用两个密钥发，谁先回用谁。
 *
 * 只在「多 Key 已开启 + 并发策略 + 可用密钥 ≥ 2」时生效，其余情况直接透传。
 * 每条腿把密钥固定成单个再交给内层 provider，选 key、计数和失败上报都在内层按单 key 完成，
 * 这一层只负责竞速。
 */
class MultiKeyRaceProvider<T : ProviderSetting.ApiKey>(
    private val delegate: Provider<T>,
) : Provider<T> {

    override suspend fun listModels(providerSetting: T): List<Model> = delegate.listModels(providerSetting)

    override suspend fun getQuota(providerSetting: T): ProviderQuota? = delegate.getQuota(providerSetting)

    override suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val pair = racePairOf(providerSetting)
            ?: return delegate.generateText(providerSetting, messages, params)
        return race(
            first = { delegate.generateText(providerSetting.pin(pair.first), messages, params) },
            second = { delegate.generateText(providerSetting.pin(pair.second), messages, params) },
        )
    }

    override suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        val pair = racePairOf(providerSetting)
            ?: return delegate.streamText(providerSetting, messages, params)
        return raceFlows(
            first = { delegate.streamText(providerSetting.pin(pair.first), messages, params) },
            second = { delegate.streamText(providerSetting.pin(pair.second), messages, params) },
        )
    }

    override suspend fun generateEmbedding(
        providerSetting: T,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult = delegate.generateEmbedding(providerSetting, params)

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = delegate.generateImage(providerSetting, params)

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> = delegate.editImage(providerSetting, params)

    /** 不满足并发条件时返回 null，调用方退化为单请求 */
    private fun racePairOf(setting: T): Pair<String, String>? {
        val config = setting.multiKey
        if (!config.enabled || config.strategy != MultiKeyStrategy.PARALLEL) return null
        return MultiKeyStateCenter.pickRacePair(setting)
    }

    /** 把密钥固定为 [key] 的副本；泛型在 ProviderManager 里已收窄，运行时是同一个具体类型 */
    @Suppress("UNCHECKED_CAST")
    private fun T.pin(key: String): T = withApiKey(key) as T

    /** 两个请求竞速：先成功的获胜并取消另一个；一方失败等另一方，双败抛最后错误 */
    private suspend fun <R> race(
        first: suspend () -> R,
        second: suspend () -> R,
    ): R = coroutineScope {
        val results = Channel<Result<R>>(capacity = 2)
        // runCatching 会吃掉 CancellationException，导致取消变成一条假失败结果，必须显式传播
        suspend fun runLeg(block: suspend () -> R): Result<R> = try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
        val jobs = listOf(
            launch { results.send(runLeg(first)) },
            launch { results.send(runLeg(second)) },
        )
        var lastError: Throwable? = null
        repeat(2) {
            // 协程被取消时 receive 直接抛 CancellationException，不会走到结果分支
            val result = results.receive()
            result.onSuccess { value ->
                jobs.forEach { it.cancel() }
                return@coroutineScope value
            }.onFailure { error ->
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("Both race requests failed")
    }

    /**
     * 两条流竞速：谁先产出首个 chunk 谁获胜，另一条取消；产出前失败的淘汰，双败抛错。
     * 获胜后中途失败时已经发出的内容收不回，错误直接抛给上层（重试会整步重来）。
     */
    private fun raceFlows(
        first: suspend () -> Flow<StreamChunk>,
        second: suspend () -> Flow<StreamChunk>,
    ): Flow<StreamChunk> = channelFlow {
        val winner = AtomicInteger(-1)
        val failures = AtomicInteger(0)
        val lastError = AtomicReference<Throwable?>(null)
        var jobFirst: Job? = null
        var jobSecond: Job? = null

        suspend fun runLeg(index: Int, factory: suspend () -> Flow<StreamChunk>) {
            try {
                factory().collect { chunk ->
                    winner.compareAndSet(-1, index)
                    if (winner.get() == index) {
                        send(chunk)
                    } else {
                        // 输了就退出，结束这条腿的上游
                        throw CancellationException("Lost the race")
                    }
                }
                // 赢家正常收尾：掐掉另一条还在等的腿
                if (winner.get() == index) {
                    (if (index == 0) jobSecond else jobFirst)?.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (winner.get() == index) throw e
                failures.incrementAndGet()
                lastError.set(e)
                if (failures.get() == 2) {
                    // 两条腿都没能产出，整个请求失败
                    throw e
                }
                // 另一条腿还有机会，这条腿安静结束
            }
        }

        coroutineScope {
            jobFirst = launch { runLeg(0, first) }
            jobSecond = launch { runLeg(1, second) }
            jobFirst.join()
            jobSecond.join()
        }
    }
}

package me.rerere.ai.util

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderKeyStrategy
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.activeApiKeyValuesForRequest
import me.rerere.ai.provider.getProviderKeyStrategy
import me.rerere.ai.provider.isMultiKeyEnabled
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * [自定义修改] 多 Key 轮换策略注册表（docs/custom/03-multi-key.md）。
 *
 * app 层根据 Settings 同步每个启用了多 Key 的 provider 的策略；
 * 未注册的 provider 返回 null → 轮盘保持上游原行为（LRU/随机），零回归。
 *
 * 同时承担 Key 健康管理门面：
 * - 选 Key 时跳过已停用（无效/无额度）与冷却中的 Key（[KeyHealthRegistry]）；
 * - 记录每次选择为"在途 Key"，请求结束后由上层 [reportSuccess]/[reportFailure] 归因；
 * - 全部 Key 停用时抛 [AllKeysSuspendedException]，由 GenerationLoop 转为可读错误。
 */
object KeyRotationPolicy {
    private val strategies = ConcurrentHashMap<String, ProviderKeyStrategy>()
    private val roundRobinCounters = ConcurrentHashMap<String, AtomicInteger>()

    /** 在途 Key 归因窗口：超过该时长的失败不再归因（避免陈旧记录误伤）。 */
    private const val IN_FLIGHT_TTL_MS = 5 * 60_000L

    private class InFlight(val key: String, val at: Long)

    private val inFlight = ConcurrentHashMap<String, InFlight>()

    /** 启动时加载持久化的停用/冷却记录（RikkaHubApp 调用）。 */
    fun init(context: Context) = KeyHealthRegistry.init(context)

    /** UI 观察健康状态变化（Key 管理器徽标、可用计数）。 */
    val healthFlow: kotlinx.coroutines.flow.StateFlow<Map<String, Map<String, KeyHealthRecord>>> =
        KeyHealthRegistry.health

    fun sync(providers: List<ProviderSetting>) {
        val active = providers
            .filter { it.isMultiKeyEnabled() }
            .associate { it.id.toString() to it.getProviderKeyStrategy() }
        strategies.keys.retainAll(active.keys.toSet())
        strategies.putAll(active)
    }

    fun strategyOf(providerId: String): ProviderKeyStrategy? =
        if (providerId.isEmpty()) null else strategies[providerId]

    internal fun nextRoundRobinIndex(providerId: String): Int {
        val counter = roundRobinCounters.getOrPut(providerId) { AtomicInteger(0) }
        return counter.getAndUpdate { if (it == Int.MAX_VALUE) 0 else it + 1 }
    }

    /** 按注册策略选 Key；返回 null 表示该 provider 未启用多 Key 策略，走原逻辑。 */
    internal fun pickByStrategy(keys: List<String>, providerId: String): String? {
        if (keys.isEmpty()) return null
        val strategy = strategyOf(providerId) ?: return null
        // [自定义修改] Key 健康过滤：停用（无效/无额度）的 Key 不再参与选择；
        // 全部冷却时选最快恢复的一个兜底；全部停用时抛出明确异常。
        val ready = KeyHealthRegistry.filterReady(providerId, keys)
        val pool = ready.ifEmpty {
            KeyHealthRegistry.coolingSorted(providerId, keys).take(1).ifEmpty {
                throw AllKeysSuspendedException(providerId)
            }
        }
        val picked = when (strategy) {
            ProviderKeyStrategy.RANDOM -> pool.random()
            ProviderKeyStrategy.ROUND_ROBIN ->
                pool[Math.floorMod(nextRoundRobinIndex(providerId), pool.size)]
        }
        inFlight[providerId] = InFlight(picked, System.currentTimeMillis())
        return picked
    }

    /** 请求成功：清除在途 Key 的健康标记（实测可用，即使之前被停用也恢复）。 */
    fun reportSuccess(providerId: String) {
        val key = takeInFlight(providerId) ?: return
        KeyHealthRegistry.clearKey(providerId, key)
    }

    /** 请求失败：Key 级故障（无效/额度/限流）时停用或冷却在途 Key；其余错误不惩罚。 */
    fun reportFailure(providerId: String, error: Throwable) {
        val key = takeInFlight(providerId) ?: return
        val verdict = KeyHealthRegistry.classify(error)
        if (verdict == KeyVerdict.NEUTRAL) return
        KeyHealthRegistry.mark(
            providerId = providerId,
            keyValue = key,
            verdict = verdict,
            reason = error.message?.lineSequence()?.firstOrNull()?.take(80) ?: "",
        )
    }

    private fun takeInFlight(providerId: String): String? {
        val flight = inFlight.remove(providerId) ?: return null
        if (System.currentTimeMillis() - flight.at > IN_FLIGHT_TTL_MS) return null
        return flight.key
    }

    /** 错误是否属于 Key 级故障（无效/额度/限流）——上层据此决定是否切换 Key 重试。 */
    fun isKeyLevelError(error: Throwable): Boolean {
        val verdict = KeyHealthRegistry.classify(error)
        return verdict == KeyVerdict.INVALID ||
                verdict == KeyVerdict.QUOTA ||
                verdict == KeyVerdict.COOLDOWN
    }

    /** 该 provider（启用多 Key 时）是否还有立即可用的备选 Key。 */
    fun hasReadyAlternative(provider: ProviderSetting): Boolean {
        if (!provider.isMultiKeyEnabled()) return false
        val keys = provider.activeApiKeyValuesForRequest()
        if (keys.size < 2) return false
        return KeyHealthRegistry.filterReady(provider.id.toString(), keys).isNotEmpty()
    }

    /** UI：手动恢复单个 Key。 */
    fun clearKeyHealth(providerId: String, keyValue: String) =
        KeyHealthRegistry.clearKey(providerId, keyValue)

    /** UI：恢复该 provider 的全部 Key。 */
    fun clearProviderHealth(providerId: String) = KeyHealthRegistry.clearProvider(providerId)
}

interface KeyRoulette {
    fun next(keys: String, providerId: String = ""): String

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * LRU 轮询，持久化存储到 cacheDir/lru_key_roulette.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex() // 空格换行和逗号

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private class DefaultKeyRoulette : KeyRoulette {
    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        // [自定义修改] 多 Key 模式指定了策略时优先按策略选取
        KeyRotationPolicy.pickByStrategy(keyList, providerId)?.let { return it }
        return if (keyList.isNotEmpty()) {
            keyList.random()
        } else {
            keys
        }
    }
}

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天

// 全局文件锁，防止多个 provider 实例并发读写同一文件
private object LruFileLock

// 文件结构: Map<providerId, Map<apiKey, lastUsedTimestamp>>
private typealias LruCache = Map<String, Map<String, Long>>

private class LruKeyRoulette(
    private val context: Context,
) : KeyRoulette {

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys

        // [自定义修改] 多 Key 模式指定了策略时优先按策略选取（跳过 LRU 记账）
        KeyRotationPolicy.pickByStrategy(keyList, providerId)?.let { return it }

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()

            // 取本 provider 的记录，过滤掉已过期条目和不在当前 key 列表中的条目
            val providerCache = (allCache[providerId] ?: emptyMap())
                .filter { (k, lastUsed) -> k in keyList && now - lastUsed < EXPIRE_DURATION_MS }
                .toMutableMap()

            // 优先选从未使用的 key，否则选最久未使用的
            val selected = keyList.firstOrNull { it !in providerCache }
                ?: providerCache.minByOrNull { it.value }!!.key

            providerCache[selected] = now
            allCache[providerId] = providerCache

            // 清理整个 provider 条目均已过期的记录
            allCache.entries.removeIf { (id, cache) ->
                id != providerId && cache.values.all { now - it >= EXPIRE_DURATION_MS }
            }

            saveCache(allCache)
            return selected
        }
    }

    private fun loadCache(): LruCache {
        return try {
            val file = File(context.cacheDir, LRU_CACHE_FILE)
            if (!file.exists()) return emptyMap()
            Json.decodeFromString(file.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveCache(cache: LruCache) {
        try {
            File(context.cacheDir, LRU_CACHE_FILE).writeText(Json.encodeToString(cache))
        } catch (_: Exception) {
        }
    }
}

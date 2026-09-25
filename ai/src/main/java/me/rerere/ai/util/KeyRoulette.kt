package me.rerere.ai.util

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderKeyStrategy
import me.rerere.ai.provider.ProviderSetting
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
 */
object KeyRotationPolicy {
    private val strategies = ConcurrentHashMap<String, ProviderKeyStrategy>()
    private val roundRobinCounters = ConcurrentHashMap<String, AtomicInteger>()

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
        return when (strategyOf(providerId)) {
            ProviderKeyStrategy.RANDOM -> keys.random()
            ProviderKeyStrategy.ROUND_ROBIN ->
                keys[Math.floorMod(nextRoundRobinIndex(providerId), keys.size)]
            null -> null
        }
    }
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

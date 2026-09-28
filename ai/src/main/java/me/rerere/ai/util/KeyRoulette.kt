package me.rerere.ai.util

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderKeyStrategy
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.activeApiKeyValuesForRequest
import me.rerere.ai.provider.getProviderKeyStrategy
import me.rerere.ai.provider.isMultiKeyEnabled
import me.rerere.ai.provider.getApiKeyValue
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Request-local selection. No global last-key slot: parallel conversations cannot misattribute failures. */
object KeyRotationPolicy {
    private val roundRobinCounters = ConcurrentHashMap<String, AtomicInteger>()

    fun init(context: Context) = KeyHealthRegistry.init(context)
    val healthFlow = KeyHealthRegistry.health

    fun manages(provider: ProviderSetting): Boolean = provider.isMultiKeyEnabled() &&
        !(provider is ProviderSetting.Google && provider.vertexAI && provider.useServiceAccount)

    fun pick(provider: ProviderSetting): String {
        val id = provider.id.toString()
        val ready = KeyHealthRegistry.filterReady(id, provider.activeApiKeyValuesForRequest())
        // Empty/disabled/exhausted/cooling pools must not fall back to the old raw key field.
        if (ready.isEmpty()) throw AllKeysSuspendedException(id)
        return when (provider.getProviderKeyStrategy()) {
            ProviderKeyStrategy.RANDOM -> ready.random()
            ProviderKeyStrategy.ROUND_ROBIN -> {
                val counter = roundRobinCounters.getOrPut(id) { AtomicInteger(0) }
                val index = counter.getAndUpdate { if (it == Int.MAX_VALUE) 0 else it + 1 }
                ready[Math.floorMod(index, ready.size)]
            }
        }
    }

    fun reportFailure(providerId: String, keyValue: String, error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) return
        val verdict = KeyHealthRegistry.classify(error)
        if (verdict == KeyVerdict.NEUTRAL) return
        // Persist only a category, not an upstream response that may contain credentials.
        KeyHealthRegistry.mark(providerId, keyValue, verdict, verdict.name.lowercase())
    }

    fun isKeyLevelError(error: Throwable): Boolean =
        error !is kotlinx.coroutines.CancellationException && KeyHealthRegistry.classify(error) != KeyVerdict.NEUTRAL

    fun hasReadyAlternative(provider: ProviderSetting): Boolean = manages(provider) &&
        KeyHealthRegistry.filterReady(provider.id.toString(), provider.activeApiKeyValuesForRequest()).isNotEmpty()

    fun clearKeyHealth(providerId: String, keyValue: String) = KeyHealthRegistry.clearKey(providerId, keyValue)
    fun clearProviderHealth(providerId: String) = KeyHealthRegistry.clearProvider(providerId)
}

interface KeyRoulette {
    fun next(keys: String, providerId: String = ""): String

    // Read the request's settings, not an eventually-synchronized global registry.
    // A pinned/single-test copy has multiKeyEnabled=false, hence cannot be rotated again.
    fun next(provider: ProviderSetting): String = if (KeyRotationPolicy.manages(provider)) {
        KeyRotationPolicy.pick(provider)
    } else {
        next(provider.getApiKeyValue(), provider.id.toString())
    }

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

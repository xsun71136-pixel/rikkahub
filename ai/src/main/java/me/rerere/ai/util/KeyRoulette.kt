package me.rerere.ai.util

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.firstApiKey
import java.io.File

interface KeyRoulette {
    fun next(keys: String, providerId: String = ""): String

    /**
     * 按提供商配置挑选本次请求用的密钥。
     *
     * 多 Key 模式关闭时只用第一个密钥；开启时由实现按策略（轮询/随机）在可用密钥里挑，
     * 并记录使用次数。失败上报走 [reportFailure]。
     */
    fun next(setting: ProviderSetting.ApiKey): String =
        if (setting.multiKey.enabled) {
            next(setting.apiKey, setting.id.toString())
        } else {
            setting.firstApiKey()
        }

    /** 请求失败时上报；命中暂停规则的错误会让这个密钥先跳过一段时间。默认什么都不记 */
    fun reportFailure(setting: ProviderSetting.ApiKey, key: String, error: Throwable) = Unit

    /** 请求成功时上报；默认什么都不记 */
    fun reportSuccess(setting: ProviderSetting.ApiKey, key: String) = Unit

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * LRU 轮询，持久化存储到 cacheDir/lru_key_roulette.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)

        /**
         * 多 Key 轮询：读取 [ProviderSetting.ApiKey.multiKey] 的策略和暂停规则，
         * 运行状态（使用次数、暂停到期时间）存到 [MultiKeyStateCenter]
         */
        fun multiKey(context: Context): KeyRoulette = MultiKeyRoulette(context)
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex() // 空格换行和逗号

internal fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

/**
 * 流式请求的结果上报：流正常结束算成功，异常结束上报失败（多 Key 的暂停规则在这里生效），
 * 取消不算失败。异常会原样继续往下游抛。
 */
fun <T> Flow<T>.reportKeyResult(
    roulette: KeyRoulette,
    setting: ProviderSetting.ApiKey,
    key: String?,
): Flow<T> = onCompletion { cause ->
    // 服务账号等不经过密钥的鉴权方式没有 key 可报
    if (key == null) return@onCompletion
    when {
        cause == null -> roulette.reportSuccess(setting, key)
        cause is CancellationException -> Unit
        else -> roulette.reportFailure(setting, key, cause)
    }
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

/**
 * 多 Key 模式的选择器：可用密钥里按策略挑一个，选中即记录使用次数和最后使用时间；
 * [reportFailure] 把命中暂停规则的失败密钥暂停一段时间（或直到手动恢复）。
 */
private class MultiKeyRoulette(
    context: Context,
) : KeyRoulette {
    private val store = MultiKeyStateCenter.also { it.init(context) }

    override fun next(keys: String, providerId: String): String {
        // 老式调用没有携带配置，等价于多 Key 关闭
        return splitKey(keys).firstOrNull() ?: keys
    }

    override fun next(setting: ProviderSetting.ApiKey): String {
        if (!setting.multiKey.enabled) return setting.firstApiKey()
        return store.pickNext(setting)
    }

    override fun reportFailure(setting: ProviderSetting.ApiKey, key: String, error: Throwable) {
        if (!setting.multiKey.enabled) return
        store.reportFailure(setting, key, error)
    }

    override fun reportSuccess(setting: ProviderSetting.ApiKey, key: String) {
        if (!setting.multiKey.enabled) return
        store.reportSuccess(setting, key)
    }
}

package me.rerere.ai.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.MultiKeyStrategy
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.splitApiKeys
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "MultiKeyStateCenter"
private const val STATE_FILE = "multi_key_state.json"

/** 单个密钥的运行状态：使用记录和暂停信息（运行态，和 KeyRoulette 的缓存同级存放） */
@Serializable
data class MultiKeyState(
    val uses: Int = 0,
    val lastUsedAt: Long = 0,
    // 暂停到的毫秒时间戳；0 = 未暂停
    val pausedUntil: Long = 0,
    // 暂停时长配置为"手动恢复"时置 true，只能手动恢复
    val pauseForever: Boolean = false,
    // 最近一次触发暂停的错误原文，界面展示用
    val lastError: String = "",
) {
    /** 此刻是否处于暂停中（到期的暂停视为已恢复） */
    fun isPaused(now: Long = System.currentTimeMillis()): Boolean = pauseForever || pausedUntil > now
}

/**
 * 多 Key 模式的运行状态中心：providerId -> (key -> state)。
 *
 * 内存里的 [states] 是唯一权威，变更后顺带落盘到 cacheDir/multi_key_state.json（丢了对使用没影响，
 * 所以不进 settings）；所有 provider 实例和设置界面共享这一份。
 */
object MultiKeyStateCenter {
    private val json = Json { ignoreUnknownKeys = true }

    private val initialized = AtomicBoolean(false)
    private var file: File? = null

    // Map<providerId, Map<apiKey, MultiKeyState>>
    private val _states = MutableStateFlow<Map<String, Map<String, MultiKeyState>>>(emptyMap())

    /** 全部 provider 的密钥状态，设置界面监听它刷新 */
    val states: StateFlow<Map<String, Map<String, MultiKeyState>>> = _states

    /** 幂等：传入 cacheDir 并加载已落盘的状态 */
    fun init(context: Context) {
        if (initialized.compareAndSet(false, true)) {
            file = File(context.cacheDir, STATE_FILE)
            loadFromDisk()
        }
    }

    /** 某个 provider 的密钥状态（key 列表本身在 ProviderSetting.apiKey 里） */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun statesFor(providerId: String): StateFlow<Map<String, MultiKeyState>> =
        object : StateFlow<Map<String, MultiKeyState>> {
            override val replayCache: List<Map<String, MultiKeyState>>
                get() = listOf(value)

            override val value: Map<String, MultiKeyState>
                get() = _states.value[providerId] ?: emptyMap()

            @OptIn(ExperimentalCoroutinesApi::class)
            override suspend fun collect(collector: FlowCollector<Map<String, MultiKeyState>>): Nothing {
                _states.collect { all -> collector.emit(all[providerId] ?: emptyMap()) }
            }
        }

    /**
     * 挑本次请求用的密钥：在可用密钥（未暂停或暂停已到期）里按策略选择，选中即记录使用。
     * 全部暂停时回退到第一个密钥：请求照常发出，由服务端报错（界面上有全部暂停的提示）。
     */
    fun pickNext(setting: ProviderSetting.ApiKey): String = synchronized(this) {
        val providerId = setting.id.toString()
        val keys = setting.splitApiKeys()
        if (keys.isEmpty()) return setting.apiKey.trim()

        val now = System.currentTimeMillis()
        val providerStates = _states.value[providerId] ?: emptyMap()
        val available = keys.filter { key -> providerStates[key]?.isPaused(now) != true }
        // 全部暂停时退化为全集，让请求发出去自然报错
        val pool = available.ifEmpty { keys }

        val selected = when (setting.multiKey.strategy) {
            MultiKeyStrategy.RANDOM -> pool.random()
            // 轮询和并发都优先挑最久没用过的；并发的第二个 key 由 pickRacePair 再挑
            MultiKeyStrategy.ROUND_ROBIN, MultiKeyStrategy.PARALLEL ->
                pool.minByOrNull { key -> providerStates[key]?.lastUsedAt ?: 0L } ?: pool.first()
        }
        recordUseLocked(providerId, selected)
        selected
    }

    /**
     * 并发竞速用的两个不同可用密钥（按最久未用排序）；纯选择不记录使用，使用记录由内层各条
     * 请求自己完成。可用密钥凑不够两个时返回 null，调用方退化为单请求。
     */
    fun pickRacePair(setting: ProviderSetting.ApiKey): Pair<String, String>? = synchronized(this) {
        val now = System.currentTimeMillis()
        val providerStates = _states.value[setting.id.toString()] ?: emptyMap()
        val available = setting.splitApiKeys()
            .filter { key -> providerStates[key]?.isPaused(now) != true }
        if (available.size < 2) return null
        val first = available.minByOrNull { providerStates[it]?.lastUsedAt ?: 0L } ?: available.first()
        val second = available.filter { it != first }
            .minByOrNull { providerStates[it]?.lastUsedAt ?: 0L }
            ?: return null
        first to second
    }

    /** 请求失败：命中暂停规则时暂停这个密钥，否则只记一条最近错误 */
    fun reportFailure(setting: ProviderSetting.ApiKey, key: String, error: Throwable) {
        val shouldPause = matchesAnyRule(setting.multiKey.pauseRules, error)
        synchronized(this) {
            val providerId = setting.id.toString()
            val providerStates = (_states.value[providerId] ?: emptyMap()).toMutableMap()
            val current = providerStates[key] ?: MultiKeyState()
            val now = System.currentTimeMillis()
            val pauseMin = setting.multiKey.pauseDurationMinutes
            providerStates[key] = if (shouldPause) {
                current.copy(
                    pausedUntil = if (pauseMin < 0) 0 else now + pauseMin * 60_000L,
                    pauseForever = pauseMin < 0,
                    lastError = error.message?.take(200) ?: error.javaClass.simpleName,
                )
            } else {
                current.copy(lastError = error.message?.take(200) ?: error.javaClass.simpleName)
            }
            _states.value = _states.value + (providerId to providerStates)
        }
        persist()
    }

    /** 请求成功：清掉这个密钥最近一次的失败记录（暂停状态不清，等它自然到期或手动恢复） */
    fun reportSuccess(setting: ProviderSetting.ApiKey, key: String) {
        var changed = false
        synchronized(this) {
            val providerId = setting.id.toString()
            val providerStates = (_states.value[providerId] ?: emptyMap()).toMutableMap()
            val current = providerStates[key]
            if (current != null && current.lastError.isNotEmpty()) {
                providerStates[key] = current.copy(lastError = "")
                _states.value = _states.value + (providerId to providerStates)
                changed = true
            }
        }
        if (changed) persist()
    }

    /** 恢复这个 provider 所有被暂停的密钥 */
    fun recoverAll(providerId: String) {
        var changed = false
        synchronized(this) {
            val providerStates = (_states.value[providerId] ?: emptyMap()).toMutableMap()
            providerStates.replaceAll { _, state ->
                if (state.isPaused()) {
                    changed = true
                    state.copy(pausedUntil = 0, pauseForever = false)
                } else {
                    state
                }
            }
            if (changed) _states.value = _states.value + (providerId to providerStates)
        }
        if (changed) persist()
    }

    /** 恢复单个密钥 */
    fun recover(providerId: String, key: String) {
        var changed = false
        synchronized(this) {
            val providerStates = (_states.value[providerId] ?: emptyMap()).toMutableMap()
            val current = providerStates[key]
            if (current != null && current.isPaused()) {
                providerStates[key] = current.copy(pausedUntil = 0, pauseForever = false)
                _states.value = _states.value + (providerId to providerStates)
                changed = true
            }
        }
        if (changed) persist()
    }

    /** 密钥从配置里删掉后清掉它的运行状态，重新加回来时从头计 */
    fun pruneKeys(providerId: String, keys: List<String>) {
        var changed = false
        synchronized(this) {
            val providerStates = _states.value[providerId] ?: return
            val pruned = providerStates.filterKeys { it in keys }
            if (pruned.size != providerStates.size) {
                _states.value = _states.value + (providerId to pruned)
                changed = true
            }
        }
        if (changed) persist()
    }

    /** 选中即计一次使用；调用时必须持有锁 */
    private fun recordUseLocked(providerId: String, key: String) {
        val providerStates = (_states.value[providerId] ?: emptyMap()).toMutableMap()
        val current = providerStates[key] ?: MultiKeyState()
        providerStates[key] = current.copy(uses = current.uses + 1, lastUsedAt = System.currentTimeMillis())
        _states.value = _states.value + (providerId to providerStates)
        persist()
    }

    // ---- 暂停规则匹配 ----

    /**
     * 规则按空白分词，全部词命中才算命中：
     * 纯数字词匹配 HTTP 状态码（或错误文本里的独立数字），其余词忽略大小写包含在错误文本里。
     * 例："429 insufficient_quota" 命中 `HttpException(code=429, message="... insufficient_quota ...")`。
     */
    internal fun matchesAnyRule(rules: List<String>, error: Throwable): Boolean {
        val code = (error as? HttpException)?.code
        val text = buildString {
            if (code != null) {
                append(code).append(' ')
            }
            error.message?.let { append(it) }
        }
        if (text.isBlank()) return false
        return rules.any { rule -> matchesRule(rule, text) }
    }

    private fun matchesRule(rule: String, text: String): Boolean {
        val tokens = rule.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        return tokens.all { token ->
            if (token.all { ch -> ch.isDigit() }) {
                Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(text)
            } else {
                text.contains(token, ignoreCase = true)
            }
        }
    }

    // ---- 落盘 ----

    private fun loadFromDisk() {
        runCatching {
            val f = file ?: return@runCatching
            if (!f.exists()) return@runCatching
            val loaded: Map<String, Map<String, MultiKeyState>> = json.decodeFromString(f.readText())
            _states.value = loaded
        }.onFailure {
            Log.w(TAG, "Failed to load multi-key state", it)
        }
    }

    private fun persist() {
        runCatching {
            val f = file ?: return@runCatching
            f.writeText(json.encodeToString(_states.value))
        }.onFailure {
            Log.w(TAG, "Failed to persist multi-key state", it)
        }
    }
}

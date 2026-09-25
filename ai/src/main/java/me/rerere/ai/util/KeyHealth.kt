package me.rerere.ai.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Key 健康注册表（自定义插件层，docs/custom/03-multi-key.md）。
 *
 * 目标：无额度/无效的 Key 自动停用，不再被反复调用；限流 Key 进入指数冷却；
 * 单个 Key 失效不阻塞整个消息进度（上层 GenerationLoop 依据本表切换 Key 重试）。
 *
 * 记录持久化到 filesDir/ai_key_health.json（与上游 LRU Key 缓存同一模式），
 * 重启后停用/冷却状态仍然生效；停用记录 24h 后自动过期给出自愈机会。
 */

private const val TAG = "KeyHealth"
private const val HEALTH_FILE = "ai_key_health.json"

/** 冷却基数与上限：重复 429 按 1min → 2min → 4min … 指数增长，封顶 30min。 */
private const val COOLDOWN_BASE_MS = 60_000L
private const val COOLDOWN_MAX_MS = 30 * 60_000L

/** 停用（无效/无额度）记录的自动过期时间：24h 后允许再次尝试（额度可能已恢复）。 */
private const val SUSPEND_TTL_MS = 24 * 60 * 60_000L

@Serializable
enum class KeyHealthState {
    /** Key 无效（401/403、invalid api key 等）→ 长期停用 */
    @SerialName("invalid")
    INVALID,

    /** 无额度/欠费（402、quota/insufficient 等）→ 长期停用 */
    @SerialName("quota")
    QUOTA,

    /** 限流（429）→ 短期冷却，指数递增，到期自动恢复 */
    @SerialName("cooldown")
    COOLDOWN,
}

@Serializable
data class KeyHealthRecord(
    val state: KeyHealthState,
    /** 到期时间（epoch millis）；到期后视为健康并惰性清理。 */
    val until: Long,
    /** 连续失败次数（冷却指数退避用）。 */
    val fails: Int = 1,
    /** 最近一次失败原因摘要（UI 展示用）。 */
    val reason: String = "",
    val updatedAt: Long = 0,
)

/** 该 provider 的启用 Key 全部被停用（无效/无额度），无法继续选择。 */
class AllKeysSuspendedException(val providerId: String) :
    RuntimeException("All API keys suspended for provider $providerId (invalid/quota)")

/** 错误归类：Key 级致命（停用）/ 限流（冷却）/ 与 Key 无关（不惩罚）。 */
internal enum class KeyVerdict { INVALID, QUOTA, COOLDOWN, NEUTRAL }

object KeyHealthRegistry {

    private val _health =
        MutableStateFlow<Map<String, Map<String, KeyHealthRecord>>>(emptyMap())

    /** providerId → (keyValue → 记录)。UI 收集此流展示停用/冷却徽标。 */
    val health: StateFlow<Map<String, Map<String, KeyHealthRecord>>> = _health.asStateFlow()

    @Volatile
    private var storeFile: File? = null

    private val json = Json { ignoreUnknownKeys = true }

    // rikkahub provider 错误格式: "Failed to get response: 429 {...}"
    private val RESPONSE_CODE_REGEX = Regex("""response:\s*(\d{3})""", RegexOption.IGNORE_CASE)

    // 通用形式: "HTTP 503" / "status code: 503" / "Error code: 503"
    private val GENERIC_STATUS_REGEX =
        Regex("""(?:HTTP|status\s*code|error\s*code)[:\s]+(\d{3})""", RegexOption.IGNORE_CASE)

    /** 命中即判定"无额度"（优先于状态码判定，429+quota 文案按额度停用而非冷却）。 */
    private val QUOTA_MARKERS = listOf(
        "insufficient_quota", "insufficient quota", "exceeded your current quota",
        "quota", "insufficient", "balance", "arrears", "out of credits",
        "余额", "额度", "欠费", "未实名",
    )

    /** 命中即判定"Key 无效"。 */
    private val INVALID_MARKERS = listOf(
        "invalid api key", "invalid_api_key", "incorrect api key", "api key not valid",
        "invalid x-api-key", "invalid authentication", "unauthorized",
        "permission denied", "access denied", "forbidden",
        "密钥无效", "无效的密钥", "无效key", "key无效", "key 无效",
    )

    fun init(context: Context) {
        storeFile = File(context.filesDir, HEALTH_FILE)
        runCatching {
            val file = storeFile ?: return
            if (!file.exists()) return
            val loaded: Map<String, Map<String, KeyHealthRecord>> =
                json.decodeFromString(file.readText())
            val now = System.currentTimeMillis()
            _health.value = loaded
                .mapValues { (_, records) -> records.filterValues { it.until > now } }
                .filterValues { it.isNotEmpty() }
        }.onFailure { Log.w(TAG, "load health file failed", it) }
    }

    /** 提取 HTTP 状态码（消息 + 至多 3 层 cause）。 */
    private fun extractStatus(error: Throwable): Int? {
        val full = collectText(error)
        val match = RESPONSE_CODE_REGEX.find(full) ?: GENERIC_STATUS_REGEX.find(full)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun collectText(error: Throwable): String = buildString {
        append(error.message ?: "")
        append(' ')
        append(error.toString())
        var cause = error.cause
        var depth = 0
        while (cause != null && depth < 3) {
            append(' ')
            append(cause.message ?: cause.toString())
            cause = cause.cause
            depth++
        }
    }

    /** 归类错误：401/403→无效；402→额度；额度文案→额度；无效文案→无效；429→冷却；其余→不惩罚。 */
    internal fun classify(error: Throwable): KeyVerdict {
        val text = collectText(error).lowercase()
        val status = extractStatus(error)
        return when {
            status == 401 || status == 403 -> KeyVerdict.INVALID
            status == 402 -> KeyVerdict.QUOTA
            QUOTA_MARKERS.any { it in text } -> KeyVerdict.QUOTA
            INVALID_MARKERS.any { it in text } -> KeyVerdict.INVALID
            status == 429 -> KeyVerdict.COOLDOWN
            else -> KeyVerdict.NEUTRAL
        }
    }

    /** 当前有效（未到期）记录；已到期条目惰性清理。 */
    fun recordOf(providerId: String, keyValue: String): KeyHealthRecord? {
        val record = _health.value[providerId]?.get(keyValue) ?: return null
        if (record.until <= System.currentTimeMillis()) {
            clearKey(providerId, keyValue)
            return null
        }
        return record
    }

    /** 立即可用的 Key（无记录或已到期）。 */
    fun filterReady(providerId: String, keys: List<String>): List<String> =
        keys.filter { recordOf(providerId, it) == null }

    /** 冷却中的 Key，按到期时间升序（全部冷却时选最快恢复的兜底）。 */
    fun coolingSorted(providerId: String, keys: List<String>): List<String> =
        keys.mapNotNull { key ->
            recordOf(providerId, key)
                ?.takeIf { it.state == KeyHealthState.COOLDOWN }
                ?.let { key to it.until }
        }
            .sortedBy { it.second }
            .map { it.first }

    /** 标记一次 Key 级失败；NEUTRAL 不做任何事。 */
    internal fun mark(providerId: String, keyValue: String, verdict: KeyVerdict, reason: String) {
        val now = System.currentTimeMillis()
        val previous = _health.value[providerId]?.get(keyValue)
        val record = when (verdict) {
            KeyVerdict.INVALID ->
                KeyHealthRecord(KeyHealthState.INVALID, now + SUSPEND_TTL_MS, 1, reason, now)

            KeyVerdict.QUOTA ->
                KeyHealthRecord(KeyHealthState.QUOTA, now + SUSPEND_TTL_MS, 1, reason, now)

            KeyVerdict.COOLDOWN -> {
                val fails = if (previous?.state == KeyHealthState.COOLDOWN) previous.fails + 1 else 1
                val duration = (COOLDOWN_BASE_MS * (1L shl (fails - 1).coerceAtMost(5)))
                    .coerceAtMost(COOLDOWN_MAX_MS)
                KeyHealthRecord(KeyHealthState.COOLDOWN, now + duration, fails, reason, now)
            }

            KeyVerdict.NEUTRAL -> return
        }
        Log.i(TAG, "mark provider=$providerId state=${record.state} until=${record.until} reason=$reason")
        update { current ->
            current + (providerId to ((current[providerId] ?: emptyMap()) + (keyValue to record)))
        }
    }

    fun clearKey(providerId: String, keyValue: String) {
        val records = _health.value[providerId] ?: return
        if (keyValue !in records) return
        update { current ->
            val remaining = (current[providerId] ?: emptyMap()) - keyValue
            if (remaining.isEmpty()) current - providerId else current + (providerId to remaining)
        }
    }

    fun clearProvider(providerId: String) {
        if (providerId !in _health.value) return
        update { it - providerId }
    }

    private fun update(transform: (Map<String, Map<String, KeyHealthRecord>>) -> Map<String, Map<String, KeyHealthRecord>>) {
        _health.value = transform(_health.value)
        persist()
    }

    private fun persist() {
        runCatching {
            storeFile?.writeText(json.encodeToString(_health.value))
        }.onFailure { Log.w(TAG, "persist health file failed", it) }
    }
}

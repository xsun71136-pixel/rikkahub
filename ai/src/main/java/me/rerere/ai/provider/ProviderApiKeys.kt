package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 多 Key 模式数据模型（自定义插件层，移植自 FLIT-feature 并按 rikkahub 的
 * 三种 ProviderSetting 适配，见 docs/custom/03-multi-key.md）。
 *
 * 设计要点：启用多 Key 后，把启用的 Key 逗号拼接同步回旧的 [apiKey] 字符串字段，
 * 上游各 provider 实现里的 KeyRoulette 会按分隔符拆分并逐请求轮换——
 * 因此模型拉取、连接测试、聊天生成等所有请求路径无需逐个修改即可支持多 Key。
 */

private val PROVIDER_API_KEY_SPLIT_REGEX = "[\\s,]+".toRegex()

@Serializable
data class ProviderApiKey(
    val id: Uuid = Uuid.random(),
    val value: String = "",
    val enabled: Boolean = true,
    val alias: String = "",
)

@Serializable
enum class ProviderKeyStrategy {
    @SerialName("random")
    RANDOM,

    @SerialName("round_robin")
    ROUND_ROBIN,
}

/** 按空格/逗号/换行拆分原始 Key 串（与上游 KeyRoulette 的分隔约定一致）。 */
fun splitProviderApiKeys(raw: String): List<String> {
    return raw
        .split(PROVIDER_API_KEY_SPLIT_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

/** 去空白、去重、丢弃空值。 */
fun List<ProviderApiKey>.normalizedProviderApiKeys(): List<ProviderApiKey> {
    val seen = mutableSetOf<String>()
    return map { key ->
        key.copy(
            value = key.value.trim(),
            alias = key.alias.trim(),
        )
    }
        .filter { it.value.isNotBlank() }
        .filter { seen.add(it.value) }
}

fun ProviderSetting.getApiKeyValue(): String = when (this) {
    is ProviderSetting.OpenAI -> apiKey
    is ProviderSetting.Google -> apiKey
    is ProviderSetting.Claude -> apiKey
}

fun ProviderSetting.isMultiKeyEnabled(): Boolean = when (this) {
    is ProviderSetting.OpenAI -> multiKeyEnabled
    is ProviderSetting.Google -> multiKeyEnabled
    is ProviderSetting.Claude -> multiKeyEnabled
}

fun ProviderSetting.getProviderApiKeys(): List<ProviderApiKey> = when (this) {
    is ProviderSetting.OpenAI -> apiKeys
    is ProviderSetting.Google -> apiKeys
    is ProviderSetting.Claude -> apiKeys
}

fun ProviderSetting.getProviderKeyStrategy(): ProviderKeyStrategy = when (this) {
    is ProviderSetting.OpenAI -> keyStrategy
    is ProviderSetting.Google -> keyStrategy
    is ProviderSetting.Claude -> keyStrategy
}

fun ProviderSetting.copyWithApiKeyConfig(
    apiKey: String = getApiKeyValue(),
    multiKeyEnabled: Boolean = isMultiKeyEnabled(),
    apiKeys: List<ProviderApiKey> = getProviderApiKeys(),
    keyStrategy: ProviderKeyStrategy = getProviderKeyStrategy(),
): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(
        apiKey = apiKey,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyStrategy = keyStrategy,
    )

    is ProviderSetting.Google -> copy(
        apiKey = apiKey,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyStrategy = keyStrategy,
    )

    is ProviderSetting.Claude -> copy(
        apiKey = apiKey,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyStrategy = keyStrategy,
    )
}

/** 把启用的 Key 逗号拼接写回旧 apiKey 字段，让上游 KeyRoulette 通道直接生效。 */
fun ProviderSetting.syncEnabledApiKeysToLegacyField(): ProviderSetting {
    if (!isMultiKeyEnabled()) return this
    val normalizedKeys = getProviderApiKeys().normalizedProviderApiKeys()
    val enabledKeys = normalizedKeys
        .filter { it.enabled }
        .joinToString(",") { it.value }
    // 全部禁用时保留原始值，避免出现空 Key 请求
    return copyWithApiKeyConfig(
        apiKey = enabledKeys.ifBlank { getApiKeyValue() },
        apiKeys = normalizedKeys,
    )
}

/** 当前参与请求轮换的 Key 值列表（未启用多 Key 时为空）。 */
fun ProviderSetting.activeApiKeyValuesForRequest(): List<String> {
    if (!isMultiKeyEnabled()) return emptyList()
    return getProviderApiKeys()
        .normalizedProviderApiKeys()
        .filter { it.enabled }
        .map { it.value }
}

/** 构造只含单个 Key 的副本（用于单 Key 连通测试）。 */
fun ProviderSetting.withSingleApiKeyForRequest(apiKey: String): ProviderSetting {
    return copyWithApiKeyConfig(
        apiKey = apiKey.trim(),
        multiKeyEnabled = false,
        apiKeys = emptyList(),
    )
}

/** 从当前 apiKey 字符串导入拆分出的 Key 并开启多 Key 模式。 */
fun ProviderSetting.enableMultiKeyFromCurrentValue(): ProviderSetting {
    val existingKeys = getProviderApiKeys().normalizedProviderApiKeys()
    val importedKeys = splitProviderApiKeys(getApiKeyValue()).map { value ->
        ProviderApiKey(value = value)
    }
    return copyWithApiKeyConfig(
        multiKeyEnabled = true,
        apiKeys = (existingKeys.ifEmpty { importedKeys }).normalizedProviderApiKeys(),
    ).syncEnabledApiKeysToLegacyField()
}

/** Key 掩码显示（前 6 后 4），防肩窥。 */
fun maskProviderApiKey(value: String): String {
    val v = value.trim()
    return when {
        v.length <= 10 -> v.mapIndexed { i, c -> if (i < 2 || i >= v.length - 2) c else '•' }.joinToString("")
        else -> "${v.take(6)}${"•".repeat((v.length - 10).coerceIn(4, 24))}${v.takeLast(4)}"
    }
}

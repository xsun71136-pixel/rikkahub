package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid

/** 账号登录得到的 OAuth 凭据 */
@Serializable
data class OAuthCredential(
    val accessToken: String,
    val refreshToken: String? = null,
    // access token 过期时间（毫秒时间戳），0 表示未知
    val expiresAt: Long = 0L,
    // 厂商侧的账号/工作区 ID，ChatGPT 请求需要带上
    val accountId: String? = null,
    // 以下仅用于界面展示
    val email: String? = null,
    val plan: String? = null,
) {
    override fun toString(): String =
        "OAuthCredential(accessToken=${accessToken.masked()}, refreshToken=${refreshToken?.masked()}, " +
            "expiresAt=$expiresAt, accountId=$accountId, email=$email, plan=$plan)"

    private fun String.masked(): String = if (length <= 8) "***" else "${take(4)}***${takeLast(4)}"
}

@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h")
}

/** 多 Key 模式下密钥的调用方式 */
@Serializable
enum class MultiKeyStrategy {
    /** 按顺序轮着用，优先挑最久没用过的那个 */
    @SerialName("round_robin")
    ROUND_ROBIN,

    /** 每次随机挑一个可用密钥 */
    @SerialName("random")
    RANDOM,

    /** 同一请求同时发两个，谁先回用谁；更快但双倍消耗 */
    @SerialName("parallel")
    PARALLEL,
}

/**
 * 多 Key 模式配置（[ProviderSetting.ApiKey]）。
 *
 * 关闭时只用第一个密钥，保持单 Key 行为；开启后多个密钥按 [strategy] 轮流使用。
 * 请求失败且错误命中 [pauseRules] 之一时，那个密钥先跳过 [pauseDurationMinutes]（-1 = 手动恢复）。
 * 密钥列表本身存在 [ProviderSetting.ApiKey.apiKey] 里，用空白/逗号分隔。
 */
@Serializable
data class MultiKeyConfig(
    val enabled: Boolean = false,
    val strategy: MultiKeyStrategy = MultiKeyStrategy.ROUND_ROBIN,
    val pauseDurationMinutes: Int = 5,
    val pauseRules: List<String> = DEFAULT_PAUSE_RULES,
) {
    companion object {
        val DEFAULT_PAUSE_RULES = listOf(
            "429 insufficient_quota",
            "401 invalid_api_key",
            "rate_limit_exceeded",
        )
    }
}

/**
 * 是否支持模型内置联网搜索（[BuiltInTools.Search]）。
 *
 * OpenAI 兼容提供商只有 Responses API 有 `web_search` 工具，Chat Completions 没有。
 */
val ProviderSetting.supportsBuiltInSearch: Boolean
    get() = when (this) {
        is ProviderSetting.OpenAI -> useResponseApi
        is ProviderSetting.Google -> true
        is ProviderSetting.Claude -> true
        is ProviderSetting.Account -> true
    }

@Serializable
sealed class ProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val customHeaders: List<CustomHeader>

    abstract val builtIn: Boolean
    abstract val description:
        @Composable()
        () -> Unit
    abstract val shortDescription:
        @Composable()
        () -> Unit

    fun addModel(model: Model): ProviderSetting = copyProvider(models = models + model)

    fun editModel(model: Model): ProviderSetting =
        copyProvider(models = models.map { if (it.id == model.id) model.copy() else it })

    fun delModel(model: Model): ProviderSetting = copyProvider(models = models.filter { it.id != model.id })

    fun moveMove(from: Int, to: Int): ProviderSetting =
        copyProvider(models = models.toMutableList().apply { add(to, removeAt(from)) })

    abstract fun copyProvider(
        id: Uuid = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        customHeaders: List<CustomHeader> = this.customHeaders,
        builtIn: Boolean = this.builtIn,
        description: @Composable (() -> Unit) = this.description,
        shortDescription: @Composable (() -> Unit) = this.shortDescription,
    ): ProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override val quotaQuery: QuotaQuery = QuotaQuery(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override var apiKey: String = "",
        override var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        var includeHistoryReasoning: Boolean = true,
        var responsesPath: String = "/responses",
        override val multiKey: MultiKeyConfig = MultiKeyConfig(),
    ) : ApiKey() {
        override fun withQuotaQuery(query: QuotaQuery): ApiKey = copy(quotaQuery = query)

        override fun withMultiKey(config: MultiKeyConfig): ApiKey = copy(multiKey = config)

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription
            )
        }
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override val quotaQuery: QuotaQuery =
            QuotaQuery(headers = listOf(CustomHeader("x-goog-api-key", "{{apiKey}}"))),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override var apiKey: String = "",
        override var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        var vertexAI: Boolean = false,
        var useServiceAccount: Boolean = false,
        var privateKey: String = "", // only for vertex AI service account
        var serviceAccountEmail: String = "", // only for vertex AI service account
        var location: String = "us-central1", // only for vertex AI service account
        var projectId: String = "", // only for vertex AI service account
        var useInteractionsApi: Boolean = false, // ignored when vertex AI is enabled
        override val multiKey: MultiKeyConfig = MultiKeyConfig(),
    ) : ApiKey() {
        override fun withQuotaQuery(query: QuotaQuery): ApiKey = copy(quotaQuery = query)

        override fun withMultiKey(config: MultiKeyConfig): ApiKey = copy(multiKey = config)

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override val quotaQuery: QuotaQuery = QuotaQuery(headers = listOf(CustomHeader("x-api-key", "{{apiKey}}"))),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override var apiKey: String = "",
        override var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = false,
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
        override val multiKey: MultiKeyConfig = MultiKeyConfig(),
    ) : ApiKey() {
        override fun withQuotaQuery(query: QuotaQuery): ApiKey = copy(quotaQuery = query)

        override fun withMultiKey(config: MultiKeyConfig): ApiKey = copy(multiKey = config)

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    /** 用 API Key 鉴权、地址可以自填的提供商 */
    @Serializable
    sealed class ApiKey : ProviderSetting() {
        abstract val apiKey: String
        abstract val baseUrl: String

        /** 用户自定义的额度查询 */
        abstract val quotaQuery: QuotaQuery

        /** 多 Key 模式配置 */
        abstract val multiKey: MultiKeyConfig

        abstract fun withQuotaQuery(query: QuotaQuery): ApiKey

        abstract fun withMultiKey(config: MultiKeyConfig): ApiKey
    }

    /**
     * 绑定官方账号的提供商。
     *
     * 没有 baseUrl 和 apiKey：请求地址由具体类型写死，凭据只能通过账号登录获得，保证 token
     * 不会被发到第三方地址。凭据由 app 层的账号管理负责登录、刷新和写回，界面编辑不应改动它。
     */
    @Serializable
    sealed class Account : ProviderSetting() {
        /** 未登录时为 null */
        abstract val credential: OAuthCredential?

        abstract fun withCredential(credential: OAuthCredential?): Account
    }

    @Serializable
    @SerialName("chatgpt")
    data class ChatGPT(
        override val id: Uuid = Uuid.random(),
        override val enabled: Boolean = true,
        override val name: String = "ChatGPT",
        override val models: List<Model> = emptyList(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override val credential: OAuthCredential? = null,
    ) : Account() {
        override fun withCredential(credential: OAuthCredential?): Account = copy(credential = credential)

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    @Serializable
    @SerialName("grok")
    data class Grok(
        override val id: Uuid = Uuid.random(),
        override val enabled: Boolean = true,
        override val name: String = "Grok",
        override val models: List<Model> = emptyList(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override val credential: OAuthCredential? = null,
    ) : Account() {
        override fun withCredential(credential: OAuthCredential?): Account = copy(credential = credential)

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Google::class,
                Claude::class,
            )
        }
    }
}

private val API_KEY_SPLIT_REGEX = "[\\s,]+".toRegex() // 空格换行和逗号

/** [ProviderSetting.ApiKey.apiKey] 里配置的全部密钥：按空白/逗号分隔，去空白去重，保持书写顺序 */
fun ProviderSetting.ApiKey.splitApiKeys(): List<String> =
    apiKey.split(API_KEY_SPLIT_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

/** 多 Key 关闭时的行为：只用第一个密钥 */
fun ProviderSetting.ApiKey.firstApiKey(): String = splitApiKeys().firstOrNull() ?: apiKey.trim()

/**
 * 返回把密钥固定为 [key] 的副本（多 Key 的测试和并发竞速用：内层请求看到的只有这一个密钥，
 * 选择、计数和失败上报都在内层按单 key 完成）。
 */
fun ProviderSetting.ApiKey.withApiKey(key: String): ProviderSetting.ApiKey = when (this) {
    is ProviderSetting.OpenAI -> copy(apiKey = key)
    is ProviderSetting.Google -> copy(apiKey = key)
    is ProviderSetting.Claude -> copy(apiKey = key)
}

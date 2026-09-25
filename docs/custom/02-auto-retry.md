# 功能 2：可自定义自动重试（参考 kelivo）

## 用户报告的现象

"目前的自动重试基本上没用。" 需要保留原开关，同时长按可进入 kelivo 式的可自定义自动重试配置。

## 根因分析（为什么现有自动重试没用）

`GenerationLoop.awaitNetworkRetryOrThrow`：

```kotlin
if (!enabled || error !is IOException || retryCount >= MAX_PROVIDER_NETWORK_RETRIES) throw error
```

只重试 `IOException`（3 次、1s 起步翻倍退避、无抖动、不可配置）。
而 rikkahub 的 provider 实现在 HTTP 失败时抛的是**普通 Exception**：

```kotlin
// ChatCompletionsAPI.kt 等
throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
```

因此 429（限流）、500/502/503/504（网关）、529（过载）以及"HTTP 200 但 body 是
并发过高/繁忙提示"这类最常见的可重试错误**全部不会触发重试**。

## kelivo 的方案（lib/core/models/auto_retry_options.dart + retry_policy.dart）

- 配置项：enabled、maxRetries(0-10)、initialDelayMs、multiplier、maxDelayMs、
  jitter(±20%)、retryOnNetworkError、retryStatusCodes、retryKeywords、stopKeywords。
- 判定顺序：用户取消 → 不重试；命中 stopKeywords（余额不足/invalid api key 等）→
  不重试；网络类错误 → 按 retryOnNetworkError；命中 retryKeywords（并发/限流/
  rate limit 等）→ 重试；HTTP 状态码在 retryStatusCodes → 重试。
- 退避：`initialDelay * multiplier^attempt`，封顶 maxDelay，jitter ±20%。
- 流式请求只在**尚未吐出任何内容**时才重放（避免重复内容）——rikkahub 的
  `responseBaseMessages` 快照机制天然满足（每次尝试从头合并）。

## 实现方案（风格适配本项目）

### 数据模型：`ext/retry/AutoRetryConfig.kt`

```kotlin
@Serializable
data class AutoRetryConfig(
    val maxRetries: Int = 3,             // 0..10
    val initialDelayMs: Long = 1000,
    val multiplier: Double = 2.0,
    val maxDelayMs: Long = 30000,
    val jitter: Boolean = true,
    val retryOnNetworkError: Boolean = true,
    val retryStatusCodes: Set<Int> = setOf(408, 425, 429, 500, 502, 503, 504, 529),
    val retryKeywords: List<String> = listOf("并发", "稍后", "重试", "访问量过大", "繁忙",
        "限流", "rate limit", "too many requests", "overloaded", "try again", "timeout", "超时"),
    val stopKeywords: List<String> = listOf("余额", "不足", "额度", "欠费", "balance",
        "insufficient", "quota", "invalid api key", "unauthorized", "permission", "未实名"),
)
```

挂载点：`NetworkSetting` 新增字段 `autoRetry: AutoRetryConfig = AutoRetryConfig()`。
`JsonInstant` 配置了 `ignoreUnknownKeys=true` + 默认值 → 旧配置无缝兼容。
既有 `enableAutoRetry` 保留为**总开关**（用户要求的"原来的自动重试"）。

### 策略引擎：`ext/retry/RetryPolicy.kt`

- `extractHttpStatus(error)`：适配 rikkahub 错误文本
  （`Failed to get response: 429 ...`）以及通用 `HTTP 429` / `status code: 429` 形式。
- `shouldRetry(error, config)`：按 kelivo 判定顺序移植（CancellationException 直接放行）。
- `backoffDelay(attempt, config)`：指数退避 + 封顶 + ±20% jitter。

### 引擎接入：`GenerationLoop.awaitNetworkRetryOrThrow`

- `IOException` 判定替换为 `RetryPolicy.shouldRetry(error, settings.networkSetting.autoRetry)`
  （网络错误分支仍尊重 `retryOnNetworkError`）。
- 次数/延迟从配置读取；`processingStatus` 继续显示既有的
  `chat_generation_network_retrying`（"xxx，正在重试（n/m）…"）文案。
- 每次重试重新走 `providerImpl.streamText` → provider 内 `KeyRoulette` 会重新选 Key，
  与功能 3 联动实现"失败自动换 Key"。

### UI：长按"自动重试"行 → 高级配置 Sheet

`ext/retry/AutoRetrySettingsSheet.kt`（ModalBottomSheet，Material3，本项目组件风格）：

- 总开关行（与设置页开关联动）+ 说明文案。
- 数值项（Slider + 数值徽标）：最大重试次数(0-10)、初始延迟(ms)、倍率(1.0-5.0)、
  最大延迟(ms)。
- 开关行：抖动(jitter)、网络错误重试。
- Chips 编辑区（可删 + 输入添加）：重试状态码、重试关键词、停止关键词；
  均带"恢复默认"按钮。
- 底部"保存"即时写入 Settings（沿用 `vm.updateSettings`），Toaster 成功提示。

设置页触点（`SettingPreferencesNetworkPage.kt`）：

- 自动重试 `CardGroup` item 增加长按（`combinedClickable(onLongClick=…)`）。
- supporting 文案追加"长按可自定义重试策略"。

## 行为对照

| 错误场景 | 修复前 | 修复后（默认配置） |
|----------|--------|--------------------|
| SocketTimeout / 连接失败 (IOException) | 重试 3 次 | 重试（尊重 retryOnNetworkError） |
| HTTP 429 / 5xx / 529 | **不重试** | 重试，指数退避 + jitter |
| HTTP 200 body 含"并发过高/繁忙" | 不重试 | 命中关键词重试 |
| HTTP 401 / body 含"余额不足" | 不重试 | stopKeywords 命中，立即失败（正确） |
| 用户手动停止 | - | CancellationException 不重试 |

## 验证

1. 配错 baseUrl 端口（连接拒绝）→ 出现"正在重试 (1/3)"，按退避节奏。
2. 使用会返回 429 的端点 → 现在会重试（修复前直接失败）。
3. 长按设置行 → Sheet 调整 maxRetries=1 → 生成失败只重试 1 次。
4. stopKeywords 加 "test-stop"，构造含该词的错误 → 不重试。
5. 重启应用配置保留（DataStore 序列化兼容）。

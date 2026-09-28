# RikkaHub 三项功能与云端构建最终实施说明

> **文档性质：唯一维护依据（FINAL / AUTHORITATIVE）**
>
> 后续如果官方源码继续更新，先阅读本文，再按本文的“新版移植流程”逐项迁移。本文只记录最终正确的实现、文件位置、数据流、约束、静态检查、云端构建环境和已成功发布的产物；不以旧版 FULL-SPEC、旧补丁或聊天记录作为实施依据。

---

## 0. 当前最终状态

### 0.1 仓库

- GitHub：`https://github.com/xsun71136-pixel/rikkahub`
- 分支：`master`
- 工作区：`/workspace/rikkahub`
- 上游仓库：`https://github.com/rikkahub/rikkahub`
- 本轮官方/fork 代码基线：`00c8d53a5c74554c92848cf821d551a17f576995`
- 三项功能实现提交：`9371fd28d95073fa8c262f9fc994e75961a7a8a2`
- 最终 CI 配置提交：`df99692a684360c4b23b4f9a17e3d665c3825e46`
- 最终源码/CI 提交（文档更新前已验证）：`df99692a684360c4b23b4f9a17e3d665c3825e46`
- 文档提交：以本文件所在的最新 Git 提交为准；不要把文档提交误当作源码功能提交。

最终 HEAD 包含：

1. 三项功能实现；
2. 完整静态检查脚本与测试源码；
3. 能处理空/非法 Firebase 配置的云端工作流；
4. 正确的 Firebase build-only 占位配置结构；
5. 已验证的签名 Secrets 使用方式。

### 0.2 已成功云端构建

- Actions：`https://github.com/xsun71136-pixel/rikkahub/actions/runs/36479462858`
- 结果：`Gradle Build = success`，`Publish nightly prerelease = success`
- Nightly Release：`https://github.com/xsun71136-pixel/rikkahub/releases/tag/nightly`
- Release 为 prerelease，tag 固定为 `nightly`。

成功发布的 APK：

| 文件 | 大小 |
|---|---:|
| `app-arm64-v8a-release.apk` | 40,304,566 B |
| `app-universal-release.apk` | 50,224,668 B |
| `app-x86_64-release.apk` | 40,971,060 B |

已下载并核验 arm64 APK：

```text
下载地址：
https://github.com/xsun71136-pixel/rikkahub/releases/download/nightly/app-arm64-v8a-release.apk

SHA-256：
642e3cda59b084e32930935a55cd9586e7c0278df318ad700f0637c9262bebf3

ZIP：有效
APK Sig Block 42：存在
v2：存在
v1 META-INF/*.RSA：无（当前构建为 v2-only，属于正常结果）
```

### 0.3 本轮没有改变的官方能力

迁移时必须保留当前官方实现，不能用旧文件整文件覆盖：

- chart_display 工具；
- workspace / web 模块；
- 工具审批和工具恢复流程；
- 消息队列与后台生成；
- `ConversationSession` 的初始化、元数据并发保护和生成收尾；
- 文件夹、搜索、翻译、附件、图表及其他官方功能；
- 当前 Firebase、Crashlytics、Gradle、签名和版本结构。

---

## 1. 本轮功能总览

| 编号 | 功能 | 最终方案 |
|---|---|---|
| 1 | 已生成消息因异常闪退、退出、切换页面而丢失 | 流式消息周期草稿落库 + 页面退后台 flush + 崩溃线程尽力 flush + NonCancellable 终态保存 + 索引安全 |
| 2 | 重试策略 | 配置驱动的网络/HTTP 重试 + 状态码/关键词判定 + 指数退避 + 抖动 + 部分响应默认不重放 |
| 3 | 多 Key 模式 | Key 条目管理 + 启停 + 随机/轮询 + Key 健康状态 + 401/402/429 分类 + 请求级 Key 归因 + 自动切换 |

设计原则：

- 新增逻辑集中在扩展包，官方文件只做必要挂接；
- 所有新增序列化字段都有默认值，旧配置可以继续读取；
- 不用全局“最后使用的 Key”推断并发请求的失败归属；
- 不允许禁用/停用/冷却的 Key 通过旧 `apiKey` 字段偷偷回退使用；
- 不把网络错误、普通 403、5xx 错误错误地永久停用 Key；
- 取消、下游转换异常和工具/UI 异常不能被误判为普通网络重试；
- 周期草稿保存是降低损失窗口的机制，不承诺强杀、断电或存储损坏时绝对零丢失。

---

## 2. 功能一：消息防丢失与闪退修复

### 2.1 原始生命周期与最终改造

官方生成过程的关键位置：

```text
GenerationLoop.generateText()
    -> GenerationChunk.Messages
    -> ChatService.handleMessageComplete().collect
    -> session.updateConversation(内存)
    -> 生成 onCompletion
    -> ConversationSession.finishGeneration()
    -> ChatService.saveConversation()
    -> ConversationRepository.update/insert
```

最终改造后：

```text
每个流式 chunk
    -> 更新 ConversationSession 内存状态
    -> StreamDraftSaver.schedule(conversationId, snapshot)
    -> 应用级周期 worker 约 2500ms flush
    -> Room 事务只更新 message_node 表
    -> 不重复更新 conversation 元数据
    -> 不触碰 FTS

生成成功 / 失败 / 取消
    -> ConversationSession.finishGeneration()
    -> finishReasoning()
    -> NonCancellable
    -> StreamDraftSaver 与终态保存使用同一会话锁
    -> ConversationRepository 全量保存
    -> 最终补齐 FTS

Activity.onStop
    -> StreamDraftSaver.flushActive()

未捕获异常
    -> CrashHandler 回调
    -> StreamDraftSaver.flushActiveBlocking(1500ms)
```

### 2.2 新增安全访问器

文件：

```text
app/src/main/java/me/rerere/rikkahub/ext/resilience/SafeMessageAccess.kt
```

提供：

```kotlin
MessageNode.clampedSelectIndex
MessageNode.safeCurrentMessage
Conversation.safeCurrentMessages
```

规则：

- 空 `messages` 返回 `-1` 或 `null`；
- 非法 `selectIndex` 使用 `coerceIn(0, lastIndex)`；
- `safeCurrentMessages` 跳过空节点；
- UI 组合期不能直接访问可能越界的 `messages[selectIndex]`；
- 该层只负责读取安全，不修改数据库数据。

### 2.3 数据模型修改

文件：

```text
app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt
```

修改点：

1. `Conversation.currentMessages`
   - `map` 改为安全的 `mapNotNull`；
   - 空节点跳过；
   - `selectIndex` 钳制到合法范围。

2. `Conversation.updateCurrentMessages`
   - 因为 `currentMessages` 会跳过空节点，所以更新前先过滤空节点；
   - 保持“当前消息列表下标”和“messageNodes 下标”一致；
   - 对旧节点的 `selectIndex` 先做合法化；
   - 保持流式更新时同一助手消息 ID 的覆盖语义。

3. `MessageNode.currentMessage`
   - 空节点仍抛出明确异常，供服务层发现损坏状态；
   - 非空节点的非法 `selectIndex` 自动 clamp，不再因旧数据或陈旧快照直接越界崩溃。

### 2.4 StreamDraftSaver

文件：

```text
app/src/main/java/me/rerere/rikkahub/ext/resilience/StreamDraftSaver.kt
```

最终实现要点：

- 周期 worker，不是 debounce：持续生成时也会持续保存；
- `DRAFT_INTERVAL_MS = 2500L`；
- `pending: ConcurrentHashMap<Uuid, Conversation>` 保存每个会话最新快照；
- `lastDraft` 保存最近写入的节点引用，用于引用级增量判断；
- 使用固定 `Array(64) { Mutex() }`，避免动态锁表删除造成并发竞态；
- 每个会话通过 `lockFor(conversationId)` 串行化草稿和终态保存；
- worker 安装后再启动，避免快速 chunk 到达时丢失 worker 状态；
- 写入成功后只移除“仍然是本次写入快照”的 pending；
- 写入期间如果有新 chunk，新快照不会被旧写入清掉，下一轮继续写；
- 写失败保留 pending，下次 flush 重试；
- `CancellationException` 必须继续抛出，不能把取消当作普通写失败吞掉；
- 落盘副本对消息执行 `finishReasoning()`，不修改前台正在显示的内存消息；
- 提供 `flushAllBlocking(timeoutMs)` 给崩溃回调使用；
- 提供 `flushActive()` / `flushActiveBlocking()` 给 Activity/Application 使用。

`persist()` 的正确顺序：

```text
取得会话锁
    -> 从 latest() 读取锁内最新状态
    -> 将该状态加入 pending
    -> 执行最终 conversation save
    -> 清理本会话 lastDraft
    -> 只删除与本次 snapshot 同引用的 pending
释放会话锁
```

这保证普通编辑、删除、分支切换或终态保存不会被迟到的旧草稿覆盖。

### 2.5 Repository 草稿写入

文件：

```text
app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt
```

新增：

```kotlin
suspend fun writeMessageNodesDraft(
    conversationId: Uuid,
    nodes: List<MessageNode>,
    previous: List<MessageNode>?,
)
```

规则：

- 使用 `database.withTransaction`；
- 会话不存在时直接结束，不用草稿重新创建已删除会话；
- 首次写入没有 previous 时，从数据库读取旧节点 ID；
- 删除已经消失的节点；
- 只 `insert` 引用或内容变化的节点；
- `node_index` 始终按当前列表顺序写入；
- `selectIndex` 在写入前 clamp；
- 只操作 `message_node`，不更新 conversation 行；
- 不调用 FTS；
- 生成结束时由正常全量保存重建 FTS。

### 2.6 ChatService、页面和崩溃挂接

文件：

```text
app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
app/src/main/java/me/rerere/rikkahub/RouteActivity.kt
app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt
app/src/main/java/me/rerere/rikkahub/utils/CrashHandler.kt
```

`ChatService`：

- 构造 `StreamDraftSaver(appScope, conversationRepo)`；
- 每次 `GenerationChunk.Messages` 更新内存后调用 `draftSaver.schedule()`；
- `saveConversation()` 使用 `NonCancellable`；
- `saveConversation()` 使用 `sessionManager.withSession()` 持有活跃 session 引用，防止页面切换期间 session 被空闲清理；
- 普通保存与草稿保存共享会话锁；
- 分支切换前，如果会话仍在生成，先停止当前生成并保存已经收到的内容；
- 删除/工具收尾路径对空节点做保护；
- 生成结束仍使用官方 `ConversationSession.finishGeneration()`，保证成功、失败、取消都能终态落库。

`RouteActivity.onStop()`：

```text
调用 StreamDraftSaver.flushActive()
```

这是尽力保存，不依赖重新解析 Koin 或懒加载 ChatService。

`RikkaHubApp`：

```text
KeyRotationPolicy.init(this)
CrashHandler.install(this) {
    StreamDraftSaver.flushActiveBlocking(1500)
}
```

崩溃回调只使用已经存在的静态 active saver，不在崩溃线程上初始化完整依赖图。

### 2.7 UI 安全修改文件

```text
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageBranch.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt
```

正确规则：

- `ChatMessage` 先判空，再读取当前消息；
- `ChatMessageBranch` 先 clamp，再显示左右分支；
- `ChatList` 对空节点直接跳过；
- 预览和搜索使用 `safeCurrentMessage`；
- 分支切换通过 `ChatVM -> ChatService.selectMessageNode()`；
- 不从 UI 旧 `Conversation` 快照整段写回；
- 服务层基于最新 session 状态校验 `nodeId/selectIndex` 后保存；
- 分支切换期间生成中的任务先停止，避免旧生成继续写入错误分支。

### 2.8 功能一的边界

该方案保证：

- 正常页面切换不会因为保存窗口而丢失已落库内容；
- 正常取消、失败、成功都有终态保存；
- 进程退后台时会尽力 flush；
- 崩溃处理器会在限定时间内尽力 flush；
- 非法分支索引和空节点不会直接导致 Compose 组合期崩溃。

该方案不承诺：

- 强制 kill、断电、文件系统损坏时的绝对零丢失；
- 2.5 秒窗口内的增量一定已经写入磁盘；
- 网络服务端已经产生的流可以断点续传。

---

## 3. 功能二：高级自动重试

### 3.1 配置模型

文件：

```text
app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetryConfig.kt
app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt
```

`NetworkSetting` 新增：

```kotlin
val autoRetry: AutoRetryConfig = AutoRetryConfig()
```

旧字段继续存在：

```kotlin
val enableAutoRetry: Boolean = true
```

`AutoRetryConfig` 字段：

| 字段 | 默认值 | 作用 |
|---|---:|---|
| `maxRetries` | 3 | 首次请求之外的额外次数，范围 0..10 |
| `initialDelayMs` | 1000 | 第一次重试等待 |
| `multiplier` | 2.0 | 指数退避倍率，范围 1..5 |
| `maxDelayMs` | 30000 | 单次等待上限，范围 0..120000 |
| `jitter` | true | 等待时间乘以 0.8..1.2 |
| `retryOnNetworkError` | true | 是否重试 IOException/传输异常 |
| `retryAfterPartialResponse` | false | 是否允许收到部分响应后重放 |
| `retryStatusCodes` | 408、425、429、500、502、503、504、520、521、522、524、529 | HTTP 状态码重试集合 |
| `retryKeywords` | 并发、限流、rate limit、timeout、capacity 等 | 无状态码时的重试词 |
| `stopKeywords` | 余额不足、insufficient_quota、invalid api key、context length 等 | 命中后立即停止 |

所有字段都有默认值，旧设置 JSON 读取时不需要迁移脚本。

`clamped()` 在使用前统一约束：

- 次数限制在 0..10；
- 初始等待限制在 0..10000ms；
- 倍率限制在 1..5，NaN/Infinity 回退到 2.0；
- 最大等待限制在 0..120000ms；
- 状态码只保留 400..599；
- 关键词 trim、去空、去重。

### 3.2 重试判定顺序

文件：

```text
app/src/main/java/me/rerere/rikkahub/ext/retry/RetryPolicy.kt
```

最终判定顺序：

```text
1. CancellationException / AllKeysSuspendedException -> 不重试
2. 命中 stopKeywords -> 不重试
3. 能提取 HTTP 状态码 -> 只看 retryStatusCodes
4. 无 HTTP 状态码且属于 IOException -> 看 retryOnNetworkError
5. 无状态码且命中 retryKeywords -> 重试
6. 其他异常 -> 不重试
```

状态码来源支持：

- `ProviderHttpException.statusCode`；
- cause 链中的 `ProviderHttpException`；
- `response: 429`；
- `HTTP 503`；
- `status code: 503`；
- `error code: 503`。

退避：

```text
delay = initialDelayMs * multiplier ^ attemptIndex
delay <= maxDelayMs
jitter 开启时再乘 0.8..1.2
```

### 3.3 ProviderHttpException 与流式错误

文件：

```text
ai/src/main/java/me/rerere/ai/util/ProviderHttpException.kt
```

定义：

```kotlin
class ProviderHttpException(
    val statusCode: Int,
    detail: String = "",
    cause: Throwable? = null,
) : RuntimeException("HTTP $statusCode: ${detail.take(2048)}", cause)
```

流式失败统一经过：

```kotlin
providerStreamFailure(response, cause)
```

已接入：

```text
ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt
ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt
ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt
ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleProvider.kt
```

效果：

- 空错误体仍能保留 HTTP 状态；
- HTML 或非法 JSON 不会吞掉状态码；
- SSE `response != null` 且失败时不会被当作正常结束；
- 非流式 HTTP 失败也使用 `ProviderHttpException`。

### 3.4 GenerationLoop 接线

文件：

```text
app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt
```

重要常量：

```kotlin
private const val KEY_SWITCH_BUDGET = 8
private const val KEY_SWITCH_DELAY_MS = 300L
```

普通重试：

- 由 `settings.networkSetting.enableAutoRetry` 控制；
- 由 `settings.networkSetting.autoRetry` 提供次数和判定；
- 非流式和流式均使用同一 `RetryPolicy`；
- 每次请求前由 `prepareKeyAttempt()` 选定本次 Key；
- 取消会在进入下一次重试前通过 `ensureActive()` 截断。

流式重试：

- 预先创建/复用同一个 assistant message ID；
- 每次 attempt 从相同 `responseBaseMessages` 开始；
- 防止第二次响应追加到第一次半截响应后；
- `StreamChunkHandlingException` 表示下游消息处理/UI 转换失败，不进入网络重试；
- 如果已经收到文本、思考或工具部分，默认 `retryAfterPartialResponse=false`，直接保留已收到内容并结束本次重试；
- 用户主动开启该选项后才允许重放；重放是新请求，不是服务端续传，可能重复计费；
- 多 Key 故障切换也受“部分响应默认不重放”保护。

### 3.5 高级设置入口

文件：

```text
app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetrySettingsSheet.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesNetworkPage.kt
```

入口：

```text
设置
  -> 偏好设置
    -> 网络
      -> 自动重试行：点击或长按
```

界面可配置：

- 总开关；
- 最大重试次数；
- 初始等待；
- 指数倍率；
- 最大等待；
- 抖动；
- 网络错误开关；
- 部分响应后是否重试；
- 状态码列表；
- 重试关键词；
- 停止关键词；
- 恢复默认值。

### 3.6 重试与多 Key 的关系

两者不是同一个开关：

- 普通网络/服务端重试受 `enableAutoRetry` 和 `AutoRetryConfig` 控制；
- Key 级失效切换由 Key 池是否有可用替代 Key 决定；
- Key 切换单次等待为 300ms；
- Key 切换预算至少 8 次，并与当前 attempt 计数共用，不是无限循环；
- 如果已经收到部分响应，默认不切换重放；
- `AllKeysSuspendedException` 不进入普通重试。

---

## 4. 功能三：多 Key 模式

### 4.1 ProviderSetting 数据字段

文件：

```text
ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt
```

以下三种 Provider 都新增相同字段，默认值如下：

```kotlin
var multiKeyEnabled: Boolean = false
var apiKeys: List<ProviderApiKey> = emptyList()
var keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.RANDOM
```

Provider：

```text
ProviderSetting.OpenAI
ProviderSetting.Google
ProviderSetting.Claude
```

### 4.2 Key 数据模型与兼容字段

文件：

```text
ai/src/main/java/me/rerere/ai/provider/ProviderApiKeys.kt
```

模型：

```kotlin
@Serializable
data class ProviderApiKey(
    val id: Uuid = Uuid.random(),
    val value: String = "",
    val enabled: Boolean = true,
    val alias: String = "",
)
```

策略：

```kotlin
enum class ProviderKeyStrategy {
    RANDOM,
    ROUND_ROBIN,
}
```

兼容规则：

- 旧版只有一个 `apiKey`，默认 `multiKeyEnabled=false`，旧配置直接继续工作；
- 打开多 Key 时，可以把当前 `apiKey` 按空格、逗号、换行导入条目；
- 条目 trim、去空、去重；单条编辑不允许包含分隔符；
- 启用条目会同步拼接回旧 `apiKey` 字段，方便旧版导出/兼容；
- 多 Key 请求的真实权威是 `apiKeys` 中 `enabled=true` 的条目，不是兼容字段；
- 全部条目禁用时兼容 `apiKey` 也被清空；
- 空池、全部禁用、全部健康停用或全部冷却时抛出 `AllKeysSuspendedException`，不会回退旧字段；
- `withSingleApiKeyForRequest()` 会生成关闭多 Key 的单 Key 副本，防止一次请求内部再次轮换。

### 4.3 KeyRotationPolicy

文件：

```text
ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt
```

核心方法：

```kotlin
KeyRotationPolicy.manages(provider)
KeyRotationPolicy.pick(provider)
KeyRotationPolicy.reportFailure(providerId, keyValue, error)
KeyRotationPolicy.isKeyLevelError(error)
KeyRotationPolicy.hasReadyAlternative(provider)
KeyRotationPolicy.clearKeyHealth(providerId, keyValue)
KeyRotationPolicy.clearProviderHealth(providerId)
```

选择规则：

```text
读取 provider.id
    -> 读取 apiKeys(enabled=true)
    -> 读取 KeyHealthRegistry
    -> 过滤未到期的 INVALID / QUOTA / COOLDOWN
    -> 没有 ready Key 时明确失败
    -> RANDOM：ready.random()
    -> ROUND_ROBIN：按 providerId 的 AtomicInteger 顺序选择
```

最终实现**不强行选择冷却中 Key**，也不从旧 `apiKey` 回退。

`Google` 的以下路径不纳入 API Key 池：

```text
provider.vertexAI && provider.useServiceAccount
```

### 4.4 请求级 Key 固定与并发安全

`GenerationLoop` 每次 attempt：

```text
原始 ProviderSetting
    -> KeyRotationPolicy.pick(provider)
    -> withSingleApiKeyForRequest(selectedKey)
    -> providerImpl.streamText/generateText(singleKeyProvider)
```

失败时使用当前 attempt 的 `requestProvider.getApiKeyValue()` 归因：

```text
reportFailure(provider.id, requestProvider.apiKey, error)
```

因此：

- 两个并行会话互不覆盖 Key 归因；
- 重试 attempt 重新选择 Key；
- 不存在 provider 全局 `lastKey` 或 in-flight 单槽位；
- 单 Key 测试使用测试条目的实际值归因；
- 取消不惩罚 Key；
- 成功请求不会因为另一个并行请求失败而误伤。

所有认证入口已改为：

```kotlin
keyRoulette.next(providerSetting)
```

涉及 Provider 文件：

```text
ai/src/main/java/me/rerere/ai/provider/providers/openai/OpenAIProvider.kt
ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt
ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt
ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleProvider.kt
ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt
```

### 4.5 KeyHealthRegistry

文件：

```text
ai/src/main/java/me/rerere/ai/util/KeyHealth.kt
```

持久化文件：

```text
应用私有 filesDir/ai_key_health.json
```

状态：

```text
INVALID  -> 无效 Key，停用 24 小时
QUOTA    -> 无额度/欠费，停用 24 小时
COOLDOWN -> 429 限流，按 1m/2m/4m/... 指数冷却，最多 30m
```

分类顺序：

```text
HTTP >= 500                         -> NEUTRAL，不惩罚 Key
HTTP 401                            -> INVALID
HTTP 402                            -> QUOTA
明确额度词                          -> QUOTA
明确无效 Key 词                     -> INVALID
HTTP 429                            -> COOLDOWN
其他                                -> NEUTRAL
```

明确额度词示例：

```text
insufficient_quota
insufficient quota
exceeded your current quota
insufficient balance
insufficient credits
out of credits
credit balance is too low
余额不足
额度不足
欠费
```

明确无效词示例：

```text
invalid api key
invalid_api_key
incorrect api key
api key not valid
invalid x-api-key
invalid authentication
密钥无效
无效的密钥
无效key
```

持久化安全：

- `AtomicFile` 写入；
- 状态变更使用 `@Synchronized`；
- 错误摘要只记录 `invalid/quota/cooldown` 分类，不存上游原始响应；
- 旧记录启动时过滤掉已经到期的记录；
- 选择时只允许 ready Key；
- UI 可手动恢复单个 Key 或全部 Key。

### 4.6 多 Key UI

文件：

```text
app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderMultiKeySection.kt
app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderKeyManagerSheet.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt
```

入口：

```text
设置
  -> Provider 配置
    -> 多 Key 模式
      -> 管理 Key
```

功能：

- 打开/关闭多 Key；
- 从当前 `apiKey` 导入；
- 手动添加；
- 批量粘贴导入；
- 导入自动去重；
- 编辑 Key；
- 修改别名；
- 单独启用/禁用；
- 删除；
- 随机/轮询策略；
- 单 Key 连接测试；
- 显示 masked Key；
- 显示 invalid/quota/cooldown 状态；
- 恢复单个 Key；
- 恢复全部 Key；
- 导入对话框初始文本为空，不自动读取系统剪贴板。

Provider 类型转换时必须保留：

```text
multiKeyEnabled
apiKeys
keyStrategy
```

### 4.7 多 Key 生命周期

```text
用户启用多 Key
    -> enableMultiKeyFromCurrentValue()
    -> 生成 ProviderApiKey 列表
    -> syncEnabledApiKeysToLegacyField()

开始请求
    -> pick ready Key
    -> 生成单 Key ProviderSetting 副本
    -> 发送请求

请求成功
    -> 本次请求结束，不清除其他 Key 的失败状态

请求失败
    -> 根据本次 requestProvider 的真实 Key 分类
    -> INVALID/QUOTA：停用 24h
    -> COOLDOWN：指数冷却
    -> NEUTRAL：不处罚
    -> 有 ready alternative：300ms 后重新选择
    -> 没有 alternative：明确结束

用户手动启用/测试
    -> clearKeyHealth()
    -> 使用单 Key ProviderSetting 测试
    -> 失败只归因测试条目
```

---

## 5. 最终文件清单

### 5.1 新增主源码文件

```text
ai/src/main/java/me/rerere/ai/provider/ProviderApiKeys.kt
ai/src/main/java/me/rerere/ai/util/KeyHealth.kt
ai/src/main/java/me/rerere/ai/util/ProviderHttpException.kt

app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderKeyManagerSheet.kt
app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderMultiKeySection.kt
app/src/main/java/me/rerere/rikkahub/ext/resilience/SafeMessageAccess.kt
app/src/main/java/me/rerere/rikkahub/ext/resilience/StreamDraftSaver.kt
app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetryConfig.kt
app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetrySettingsSheet.kt
app/src/main/java/me/rerere/rikkahub/ext/retry/RetryPolicy.kt
```

### 5.2 新增测试源码

```text
ai/src/test/java/me/rerere/ai/util/ManagedKeysTest.kt
app/src/test/java/me/rerere/rikkahub/ext/resilience/MessageResilienceTest.kt
app/src/test/java/me/rerere/rikkahub/ext/resilience/StreamDraftSaverTest.kt
app/src/test/java/me/rerere/rikkahub/ext/retry/RetryPolicyTest.kt
```

共 18 个 `@Test`，覆盖：

- 旧配置兼容；
- 多 Key 去重；
- 禁用池不能回退旧 Key；
- 轮询与单 Key 副本；
- 401/403/429/402/5xx 分类；
- 取消不重试；
- HTTP 状态码保留；
- 退避和边界；
- 非法分支索引；
- 空节点索引对齐；
- 写入期间到达新 chunk；
- 终态保存不能被旧草稿覆盖；
- 写失败后 pending 保留。

### 5.3 修改的核心官方文件

```text
ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt
ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt
ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleProvider.kt
ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt
ai/src/main/java/me/rerere/ai/provider/providers/openai/OpenAIProvider.kt
ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt
ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt

app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt
app/src/main/java/me/rerere/rikkahub/RouteActivity.kt
app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt
app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt
app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt
app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt
app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageBranch.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesNetworkPage.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt
app/src/main/java/me/rerere/rikkahub/utils/CrashHandler.kt
```

### 5.4 资源文件

```text
app/src/main/res/values/strings.xml
app/src/main/res/values-zh/strings.xml
```

新增并同步英文/中文字符串，包括：

- 消息错误和操作提示；
- 自动重试设置；
- HTTP/网络重试状态；
- Key 管理、健康状态、导入、测试、删除；
- 部分响应后重试的风险提示。

### 5.5 CI 文件和辅助文件

```text
.github/workflows/daily-build.yml
scripts/check_custom_static.py
docs/custom/static-check-report.json
```

---

## 6. 静态检查与正确验收方式

### 6.1 静态门禁

脚本：

```text
scripts/check_custom_static.py
```

执行：

```bash
cd /workspace/rikkahub
PYTHONPATH=/workspace/static-tools \
python3 scripts/check_custom_static.py \
  --report docs/custom/static-check-report.json
```

最终结果：

```text
检查项：253
失败：0
XML：24 个成功解析
Kotlin 文件：36 个参与静态检查
新增字符串：63 个
compiled：false（该字段表示本脚本本身不编译）
kotlin_tests_executed：false（该次门禁不运行 Kotlin 测试）
```

检查内容：

- `git diff --check`；
- XML 格式；
- 字符串资源重名；
- 英文/中文字符串对应；
- `%s/%d/%f` 占位符一致；
- `R.string` 引用存在；
- Kotlin 重复 import；
- 冲突标记；
- 草稿 worker 周期保存；
- pending 引用安全；
- 草稿/终态共享锁；
- Room 事务和会话存在性；
- NonCancellable 终态保存；
- 崩溃路径不解析懒加载 DI；
- 请求级 Key 归因；
- 空池不回退旧 Key；
- Vertex Service Account 绕过 Key 池；
- AtomicFile 健康状态；
- 5xx 不处罚 Key；
- HTTP 状态优先于 IOException；
- 部分响应默认保护；
- 取消保护；
- Provider SSE 错误接线；
- 剪贴板导入不自动读取；
- 构建文件没有被静态功能补丁意外改写。

### 6.2 Kotlin 静态语法说明

使用预编译 Tree-sitter Kotlin 做结构扫描，不代替 Kotlin 编译器。

官方基线中 `ProviderSetting.kt` 和 `GoogleProvider.kt` 已有解析器限制；最终版本与基线诊断数量一致，没有新增解析问题。

### 6.3 后续真正编译验收

必须由 GitHub Actions 完成：

```bash
./gradlew assembleRelease
```

不要把“静态门禁通过”当作“设备运行验证通过”。以下仍需在真实设备上人工验证：

```text
长回复期间切换页面
退后台后返回
主动停止生成
异常退出后重新打开
429 / 500 / 超时
部分响应后的重试开关
两个会话并行使用同一 Provider
Key 全禁用、全停用、冷却和手动恢复
Provider 类型转换
旧配置导入
真实 Firebase 行为
```

---

## 7. 最终云端构建环境

### 7.1 Android/Gradle 项目版本

当前 `app`：

```text
applicationId = me.rerere.rikkahub
versionCode = 190
versionName = 2.5.5
minSdk = 26
targetSdk = 37
compileSdk = Android 37 minorApiLevel 2
ABI = arm64-v8a, x86_64
split = arm64-v8a / x86_64 / universal
```

当前版本锁定：

```text
Gradle Wrapper = 9.6.0
Android Gradle Plugin = 9.4.0
Kotlin = 2.4.20
Compose BOM = 2026.09.00
```

Gradle Wrapper 文件：

```text
gradle/wrapper/gradle-wrapper.properties
```

### 7.2 GitHub Actions Runner

工作流：

```text
.github/workflows/daily-build.yml
```

Runner：

```text
ubuntu-latest
```

工具链：

```text
JDK：Temurin 17
action：actions/setup-java@v4
Node：22
action：actions/setup-node@v4
pnpm：11
action：pnpm/action-setup@v4
```

前端依赖：

```bash
cd web-ui
pnpm install --frozen-lockfile
```

原因：`web` 模块的 `preBuild` 会执行前端构建，必须先安装 `web-ui` 依赖。

Gradle 缓存：

```text
~/.gradle/caches
~/.gradle/wrapper
```

缓存 key：

```text
${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
```

### 7.3 构建文件准备

工作流在 Runner 上创建以下临时文件：

```text
app/app.key
local.properties
app/google-services.json
```

签名：

```bash
echo "${{ secrets.KEY_BASE64 }}" | base64 -d > app/app.key
echo "${{ secrets.SIGNING_CONFIG }}" > local.properties
```

`SIGNING_CONFIG` 的正确语义：

```properties
storeFile=app.key
storePassword=<GitHub Secret 中的签名密码>
keyAlias=rikkahub
keyPassword=<GitHub Secret 中的签名密码>
```

注意：

- JKS 不进 Git；
- 密码不进源码和本文档；
- `KEY_BASE64` 与 `SIGNING_CONFIG` 只保存在 GitHub Actions Secrets；
- 本地已验证的 JKS 路径为 Termux：`~/rikkahub_fork.jks`；
- JKS alias：`rikkahub`；
- 当前 APK 的签名使用该项目专用 JKS；
- 密钥文件、密码和 PAT 不应写入任何 Markdown、源码或 Release 日志。

### 7.4 Firebase 配置

Secret：

```text
GOOGLE_SERVICES_JSON
```

工作流逻辑：

```bash
if printf '%s' "$GOOGLE_SERVICES_JSON" | jq -e . >/dev/null 2>&1; then
    printf '%s' "$GOOGLE_SERVICES_JSON" > app/google-services.json
else
    写入合法的 build-only Firebase placeholder
fi
```

合法 placeholder 的结构必须是：

```json
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "rikkahub-build",
    "storage_bucket": "rikkahub-build.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000000",
        "android_client_info": {
          "package_name": "me.rerere.rikkahub"
        }
      },
      "api_key": [
        {
          "current_key": "AIzaSyDUMMY_RIKKAHUB_BUILD_ONLY_KEY"
        }
      ]
    }
  ],
  "configuration_version": "1"
}
```

关键结构要求：`api_key` 必须放在与 `package_name` 匹配的 `client` 节点内部。

该 placeholder 只保证 Google Services Gradle 插件和 Release 构建通过，不代表真实 Firebase 项目配置。若需要真实 Analytics/Crashlytics，配置真实 `GOOGLE_SERVICES_JSON` Secret。

### 7.5 最终 Gradle 与发布步骤

```bash
chmod +x gradlew
./gradlew assembleRelease
```

发布：

```text
softprops/action-gh-release@v2
 tag_name: nightly
 name: Nightly Build
 prerelease: true
 files: app/build/outputs/apk/release/*.apk
```

工作流触发：

- 每天 UTC 09:00、18:00；
- 支持 `workflow_dispatch` 手动触发；
- 手动触发时无条件构建；
- 定时触发时检查过去 24 小时是否有提交。

---

## 8. 后续官方源码更新时的唯一移植流程

### 第一步：确认基线

```bash
cd /workspace/rikkahub
git fetch origin master
git log -1 --oneline origin/master
git status --short
```

确认没有未保存本地改动，再开始迁移。若远端已经包含别人新提交，不能直接覆盖。

### 第二步：先读新版对应文件

优先重新阅读：

```text
app/.../ChatService.kt
app/.../ConversationSession.kt
app/.../ConversationRepository.kt
app/.../Conversation.kt
app/.../GenerationLoop.kt
ai/.../ProviderSetting.kt
ai/.../KeyRoulette.kt
ai/.../OpenAIProvider.kt
ai/.../GoogleProvider.kt
ai/.../ClaudeProvider.kt
```

不要用旧版整文件替换；只寻找本文列出的函数/调用点。

### 第三步：复制新增扩展文件

整体复制以下目录/文件：

```text
ai/src/main/java/me/rerere/ai/provider/ProviderApiKeys.kt
ai/src/main/java/me/rerere/ai/util/KeyHealth.kt
ai/src/main/java/me/rerere/ai/util/ProviderHttpException.kt
app/src/main/java/me/rerere/rikkahub/ext/keys/
app/src/main/java/me/rerere/rikkahub/ext/resilience/
app/src/main/java/me/rerere/rikkahub/ext/retry/
```

如果官方包名、`UIMessage`、Provider 泛型或 Room Entity 改名，按新版 API 调整 import 和类型，不改本文的行为约束。

### 第四步：按功能一挂接

顺序：

```text
1. Conversation/MessageNode 安全索引
2. ConversationRepository.writeMessageNodesDraft
3. StreamDraftSaver
4. ChatService chunk schedule
5. ChatService saveConversation 锁与 NonCancellable
6. RouteActivity.onStop
7. RikkaHubApp/CrashHandler
8. ChatMessage/ChatList/ChatMessageBranch
9. ChatPage/ChatVM 分支切换
```

检查：

```bash
grep -RIn "messages\[.*selectIndex\|currentMessage" app/src/main/java
```

每个 UI 消费点都必须能证明空节点和非法索引安全。

### 第五步：按功能二挂接

顺序：

```text
1. AutoRetryConfig
2. NetworkSetting.autoRetry
3. RetryPolicy
4. ProviderHttpException
5. 四个 Provider 非流式/流式错误接线
6. GenerationLoop 普通重试
7. GenerationLoop 流式重试
8. AutoRetrySettingsSheet
9. 网络设置入口
10. en/zh 字符串
```

检查：

```text
取消不重试；
下游消息处理异常不重试；
HTTP 状态码不因 IOException 包装丢失；
部分响应默认不重放；
次数和延迟有边界；
```

### 第六步：按功能三挂接

顺序：

```text
1. ProviderSetting 三类新增字段
2. ProviderApiKeys
3. KeyHealth
4. KeyRoulette / KeyRotationPolicy
5. 五个认证 Provider 调用 next(providerSetting)
6. GenerationLoop 请求级 Key 副本与失败归因
7. ProviderConfigure 类型转换和入口
8. ProviderMultiKeySection
9. ProviderKeyManagerSheet
10. RikkaHubApp 初始化
11. en/zh 字符串
```

检查：

```text
所有请求是否读取实际启用 Key；
禁用 Key 是否绝不会从旧 apiKey 回退；
并发请求是否按 requestProvider 归因；
403/5xx 是否不误停用；
全池耗尽是否明确失败；
Vertex Service Account 是否绕过 Key 池；
```

### 第七步：静态门禁

```bash
PYTHONPATH=/workspace/static-tools \
python3 scripts/check_custom_static.py \
  --report docs/custom/static-check-report.json
```

必须满足：

```text
failures = []
git diff --check = success
```

### 第八步：提交与云端构建

源码和文档检查通过后：

```bash
git add <明确的源码、资源、文档和 workflow 文件>
git commit -m "feat: ... [skip ci]"
git push origin master
```

然后通过 GitHub Actions 的 `workflow_dispatch` 构建，不要把签名文件放进仓库。

### 第九步：构建后检查

必须检查：

```text
Actions 运行结果为 success
Gradle Build 为 success
Publish nightly prerelease 为 success
Release 有 arm64/universal/x86_64 三个 APK
APK ZIP 有效
APK Sig Block 42 存在
arm64 SHA-256 已记录
```

---

## 9. 维护禁忌与最终规则

1. 不要把旧版 `FULL-SPEC.md` 的 diff 直接套在新官方源码上；以本文的函数锚点和行为规则为准。
2. 不要整文件覆盖新版 `ChatService.kt`、`GenerationLoop.kt`、`ProviderSetting.kt` 或 Provider 实现。
3. 不要删掉新版 `ConversationSession.finishGeneration()` 的 `NonCancellable` 逻辑。
4. 不要用 debounce 代替周期草稿保存。
5. 不要在草稿线程写 conversation 元数据或每次刷新 FTS。
6. 不要在保存 pending 后无条件清空 pending；必须按对象引用判断是否是旧快照。
7. 不要使用全局 `lastKey` 归因并发请求。
8. 不要把 403、5xx、普通网络异常直接标记为 Key 无效。
9. 不要在全 Key 禁用时回退旧 `apiKey`。
10. 不要允许已经收到部分内容的请求默认自动重放。
11. 不要把签名 JKS、签名密码、PAT、真实 Firebase JSON 写入 Git 或本文档。
12. 不要把 `GOOGLE_SERVICES_JSON` 的 build-only placeholder 当作真实 Firebase 配置。
13. 不要把静态检查通过当作设备实测通过。
14. 每次官方更新后，都要重新跑静态门禁并通过一次云端 Release 构建。

---

## 10. 最终交付索引

```text
唯一实施文档：
docs/custom/2026-09-three-features-plan.md

静态门禁：
scripts/check_custom_static.py

静态报告：
docs/custom/static-check-report.json

源码基线：
00c8d53a5c74554c92848cf821d551a17f576995

功能实现：
9371fd28d95073fa8c262f9fc994e75961a7a8a2

源码/CI 最终提交（文档提交之前）：
df99692a684360c4b23b4f9a17e3d665c3825e46

当前远端 HEAD：以 `master` 最新提交为准；文档提交是 docs-only 提交，不改变源码构建结果。

成功构建：
Actions run 36479462858

Nightly Release：
https://github.com/xsun71136-pixel/rikkahub/releases/tag/nightly
```

以后修改这三个功能时，先只看本文件，再检查当前远端 HEAD 是否已经比本文记录更新；如果更新，先把本文和新版源码对齐，再开始改代码。

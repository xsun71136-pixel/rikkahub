# 官方文件触点清单（PATCHES）

> 上游同步后按本清单逐条回归。所有自定义修改点在代码内都带有 `[自定义修改]` 注释标记，
> 可用 `grep -rn "\[自定义修改\]" app/ ai/` 快速定位全部触点。

## 新增文件（不与上游冲突，直接保留）

| 文件 | 功能 |
|------|------|
| `app/src/main/java/me/rerere/rikkahub/ext/resilience/SafeMessageAccess.kt` | 1 |
| `app/src/main/java/me/rerere/rikkahub/ext/resilience/StreamDraftSaver.kt` | 1 |
| `app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetryConfig.kt` | 2 |
| `app/src/main/java/me/rerere/rikkahub/ext/retry/RetryPolicy.kt` | 2 |
| `app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetrySettingsSheet.kt` | 2 |
| `app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderMultiKeySection.kt` | 3 |
| `app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderKeyManagerSheet.kt` | 3 |
| `ai/src/main/java/me/rerere/ai/provider/ProviderApiKeys.kt` | 3 |
| `ai/src/main/java/me/rerere/ai/util/KeyHealth.kt` | 3（Key 健康注册表：停用/冷却/归因/持久化） |
| `docs/custom/*` | 文档 |

## 官方文件修改点

### 功能 1：消息防丢失与崩溃修复（01-message-resilience.md）

| 文件 | 位置/锚点 | 修改 | 意图 |
|------|-----------|------|------|
| `data/model/Conversation.kt` | `Conversation.currentMessages` getter | `map { messages[selectIndex] }` → `mapNotNull` + clamp | 非法索引不崩溃 |
| `data/model/Conversation.kt` | `MessageNode.currentMessage` getter | 移除 `selectIndex !in indices` 抛错分支，改 clamp | 同上（空节点仍抛错） |
| `ui/components/message/ChatMessage.kt` | `ChatMessage` 开头 `val message =` | 空节点 early return + clamp 索引 | 渲染防御 |
| `ui/components/message/ChatMessageBranch.kt` | `ChatMessageBranchSelector` 开头 | 局部 shadow 变量 clamp selectIndex | 切换器防御 |
| `ui/pages/chat/ChatList.kt` | `itemsIndexed` 内容开头 | `if (node.messages.isEmpty()) return@itemsIndexed` | 跳过损坏节点 |
| `ui/pages/chat/ChatList.kt` | 导出 `selectedMessages` / 搜索 filter | 加空节点过滤；搜索改用 `safeCurrentMessage` | 防御 |
| `ui/pages/chat/ChatPage.kt` | `onUpdateMessage = { newNode -> ... }` | 整段替换为 `vm.selectMessageNode(newNode.id, newNode.selectIndex)` | **根因修复**：不再用 UI 陈旧快照整段覆盖会话 |
| `ui/pages/chat/ChatVM.kt` | `updateConversation` 之后 | 新增 `selectMessageNode(nodeId, selectIndex)` 方法 | 走服务层新鲜状态 + 校验 + 落库 |
| `service/ChatService.kt` | 类顶部字段区 | 新增 `draftSaver` 字段 + `flushAllDraftsBlocking()` | 草稿保存器 |
| `service/ChatService.kt` | `handleMessageComplete` 的 `collect` 内 `GenerationChunk.Messages` 分支 | `updateConversation(...)` 后加一行 `draftSaver.schedule(conversationId, updatedConversation)` | 流式节流落库 |
| `service/ChatService.kt` | `handleMessageComplete` 的 `.onCompletion` 开头 | `runCatching { draftSaver.cancelAndAwait(conversationId) }` | 终态全量保存前停草稿，防旧盖新 |
| `service/ChatService.kt` | `cleanup()` | 先 `draftSaver.flushAllBlocking(800)` | 退出清理前抢救 |
| `service/ChatService.kt` | `checkInvalidMessages` 内 `node.copy(...)` | `selectIndex - 1` → `(selectIndex - 1).coerceAtLeast(0)` | 防 -1 悬空索引 |
| `data/repository/ConversationRepository.kt` | `saveMessageNodes` 之前 | 新增 3 个 additive 方法：`replaceMessageNodesDraft` / `upsertMessageNodeDraft` / `deleteMessageNodeDraft` | 轻量草稿写入（跳过 FTS） |
| `utils/CrashHandler.kt` | `install()` | 增加可选参数 `onCrash: (() -> Unit)? = null`，在 markCrashed 前调用 | 崩溃回调挂点 |
| `RikkaHubApp.kt` | `onCreate` 的 `CrashHandler.install(this)` | 传入回调：`getKoin().getOrNull<ChatService>()?.flushAllDraftsBlocking(1500)`；新增 import `org.koin.java.KoinJavaComponent.getKoin` | 崩溃时应急保存 |
| `service/ChatService.kt` | `flushAllDraftsBlocking` 之后 | 新增 `flushAllDrafts()`（appScope 异步 flush） | 后台 flush 入口（第二轮打磨） |
| `RouteActivity.kt` | `onNewIntent` 之后 | 新增 `onStop()`：`KoinJavaComponent.getKoin().getOrNull<ChatService>()?.flushAllDrafts()` | 退后台立即落盘草稿，后台被杀不丢（第二轮打磨） |

### 功能 2：可自定义自动重试（02-auto-retry.md）

| 文件 | 位置/锚点 | 修改 | 意图 |
|------|-----------|------|------|
| `data/datastore/PreferencesStore.kt` | `NetworkSetting` data class | 追加字段 `autoRetry: ext.retry.AutoRetryConfig = AutoRetryConfig()` | 配置持久化（默认值兼容旧数据） |
| `data/ai/GenerationLoop.kt` | 文件顶部常量 | 删除 `MAX_PROVIDER_NETWORK_RETRIES` / `INITIAL_PROVIDER_RETRY_DELAY_MS` | 改由配置驱动 |
| `data/ai/GenerationLoop.kt` | stream 分支 `awaitNetworkRetryOrThrow(...)` 调用 | 增加实参 `retryConfig = settings.networkSetting.autoRetry` | 传入配置 |
| `data/ai/GenerationLoop.kt` | `executeProviderRequestWithRetry(...)` 调用与定义 | 增加 `retryConfig` 参数并透传 | 同上 |
| `data/ai/GenerationLoop.kt` | `awaitNetworkRetryOrThrow` 函数体 | `error !is IOException` 判定 → `RetryPolicy.shouldRetry(error, config)`；固定退避 → `RetryPolicy.backoffDelay`；次数上限 → `config.maxRetries` | **核心**：让 429/5xx/关键词错误也能重试 |
| `data/ai/GenerationLoop.kt` | 新增 `getRetryErrorMessage(error)` | 非 IOException 错误生成可读状态文案 | 状态栏提示 |
| `ui/pages/setting/SettingPreferencesNetworkPage.kt` | 自动重试 `CardGroup` item | item 增加 `modifier = Modifier.combinedClickable(onClick/onLongClick → showAutoRetrySheet = true)`；新增 `showAutoRetrySheet` 状态、`AutoRetrySettingsSheet(...)` 调用、`combinedClickable` 与 sheet 的 import | 长按入口 |
| `res/values/strings.xml`、`res/values-zh/strings.xml` | `setting_page_preferences_network_auto_retry_desc` | 文案更新（提及长按） | - |
| 同上 | 文件尾部自定义块 | 新增 `auto_retry_*` 字符串 | - |

### 功能 3：多 Key 模式（03-multi-key.md）

| 文件 | 位置/锚点 | 修改 | 意图 |
|------|-----------|------|------|
| `ai/.../provider/ProviderSetting.kt` | `OpenAI` / `Google` / `Claude` 三个 data class 构造参数**末尾** | 各追加 `multiKeyEnabled=false`、`apiKeys=emptyList()`、`keyStrategy=RANDOM` | 结构化多 Key 存储（序列化兼容） |
| `ai/.../util/KeyRoulette.kt` | 文件头 | 新增 `KeyRotationPolicy` 注册表对象（sync/strategyOf/pickByStrategy/轮询计数器） | 策略支持 |
| `ai/.../util/KeyRoulette.kt` | `DefaultKeyRoulette.next` / `LruKeyRoulette.next` 开头 | 先查 `KeyRotationPolicy.pickByStrategy`，命中即返回；未注册走上游原逻辑 | 零回归接入 |
| `ai/.../util/KeyRoulette.kt` | `KeyRotationPolicy`（第二轮打磨） | 扩展为 Key 健康门面：`init/healthFlow/reportSuccess/reportFailure/isKeyLevelError/hasReadyAlternative/clearKeyHealth/clearProviderHealth`；`pickByStrategy` 过滤停用/冷却 Key、记录在途 Key、全停用时抛 `AllKeysSuspendedException` | 失效 Key 自动停用不再被调用 |
| `data/ai/GenerationLoop.kt`（第二轮打磨） | stream 成功分支 / `executeProviderRequestWithRetry` 成功返回 | `KeyRotationPolicy.reportSuccess(provider.id)` | 成功 Key 恢复健康 |
| `data/ai/GenerationLoop.kt`（第二轮打磨） | `awaitNetworkRetryOrThrow` | 新增 `provider` 参数；开头映射 `AllKeysSuspendedException` → 可读错误；`reportFailure` 归因；`canSwitchKey`（Key 级故障且有可用备选）时**无视停止关键词与总开关**切换 Key 重试（预算 `KEY_SWITCH_BUDGET=8`、延迟 300ms、状态栏 `chat_generation_key_switching`） | Key 失效不阻塞消息进度 |
| `RikkaHubApp.kt`（第二轮打磨） | `onCreate` DatabaseUtil 之后 | `KeyRotationPolicy.init(this)` | 加载持久化的 Key 健康记录 |
| `ui/pages/setting/components/ProviderConfigure.kt` | `ProviderConfigureOpenAI` / `ProviderConfigureClaude` 的 apiKey `OutlinedTextField` 之后、baseUrl 之前 | 插入 `ProviderMultiKeySection(provider, onEdit = { onEdit(it as ProviderSetting.Xxx) })` | 开关入口 |
| 同上 | `ProviderConfigureGoogle` 的 `if (!(vertexAI && useServiceAccount))` 块内 apiKey 字段后 | 同上（Google 版） | 同上 |
| 同上 | `convertTo()` | 读取源 provider 的多 Key 三字段并传入目标构造 | 类型转换不丢 Key |
| 同上 | import 区 | 增加 `ext.keys.ProviderMultiKeySection`、`ai.provider.{isMultiKeyEnabled,getProviderApiKeys,getProviderKeyStrategy}` | - |
| `RikkaHubApp.kt` | `onCreate` 尾部（incrementLaunchCount 之后） | AppScope collect `settingsFlow` → `KeyRotationPolicy.sync(settings.providers)` | 策略注册表跟随设置 |
| `res/values/strings.xml`、`res/values-zh/strings.xml` | 文件尾部自定义块 | 新增 `common_edit`、`setting_provider_page_multi_key_*` 字符串 | - |
| 同上（第二轮打磨） | `chat_generation_network_retrying` 附近 / 尾部自定义块 | 新增 `chat_generation_key_switching`、`error_all_keys_suspended`、`setting_provider_page_multi_key_health_*`、`_restore_all`、`_test_success`、`auto_retry_items_count/expand/collapse`；`_manager_with_count` 改为"可用"语义 | - |

### 构建：Firebase 移除（云端构建无需 google-services.json）

| 文件 | 修改 |
|------|------|
| `build.gradle.kts`（根） | 删除 `google.services`、`firebase.crashlytics` 两行 plugin alias |
| `app/build.gradle.kts` | 删除两个 plugin alias + `// Firebase` 依赖块（bom/analytics/crashlytics）；版本号提升 `190 / 2.5.4-ext1`；release `signingConfig` 改为**仅当 storeFile 已配置时才赋值**（secret 缺失时产出未签名包而不是构建失败） |
| `gradle/libs.versions.toml` | 删除 google-services / firebase-bom / firebase-crashlytics 的 versions、libraries、plugins 条目 |
| `di/AppModule.kt` | 删除 `Firebase.crashlytics` / `Firebase.analytics` 两个 single 与 firebase imports |
| `di/ViewModelModule.kt` | ChatVM 构造删除 `analytics = get(),` |
| `ui/pages/chat/ChatVM.kt` | 删除 `FirebaseAnalytics` import、构造参数 `analytics`、5 处 `analytics.logEvent(...)` |

> `.github/workflows/daily-build.yml` 未改动：其 `echo '${{ secrets.GOOGLE_SERVICES_JSON }}' > app/google-services.json`
> 步骤在 secret 缺失时只会写一个空文件，插件已移除后无影响。

## 回归步骤（上游 Sync 后）

1. `git merge upstream/master`（或 Sync fork 后重新下载）。
2. 新增文件不会冲突；官方文件冲突时按上表"意图"列在新代码里找等价位置重套。
3. 快速自检：`grep -rn "\[自定义修改\]" app/ ai/` 应覆盖上表所有代码触点。
4. 触发云端构建验证编译。
5. 按 01/02/03 文档末尾"验证"清单冒烟。

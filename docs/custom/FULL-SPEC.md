# RikkaHub 三大功能完整实施方案（独立移植版）

> **文档性质**：自包含的最终实现方案。不依赖任何历史文件或外部参考项目，
> 凭本文档即可在**任意未来官方版本**上完整重现三大功能。
>
> **实现基线**：官方 RikkaHub 2.5.4（上游 commit `7263dd36`）
> **最终形态**：commit `a2b2579`（三轮迭代收敛版）
> **代码规模**：新增 9 个文件共 1,976 行；修改官方文件 22 个（净增约 600 行）；
> 字符串资源 60 个 key（en + zh）；构建脚本 3 个。总计 +2,573 / -75 行。

## 0. 总览

### 0.1 三大功能

| # | 功能 | 解决的问题 |
|---|------|-----------|
| 1 | **消息防丢失与闪退修复** | 流式生成期间退出应用/进程被杀/崩溃 → 已生成内容 100% 丢失；切换分支/异常状态下 `selectIndex` 越界 → Compose 组合期崩溃闪退 |
| 2 | **高级自动重试** | 官方重试只认 `IOException`，而 provider 的 HTTP 失败（429/5xx 等最常见可重试错误）抛的是普通 Exception → 自动重试形同虚设；且次数/延迟/判定条件全部硬编码不可配置 |
| 3 | **多 Key 模式** | 单 Key 额度耗尽/失效后整个会话瘫痪；需要多 Key 池 + 轮换策略 + 失效 Key 自动停用（不再反复调用）+ 故障时无感切换下一个 Key 继续生成 |

### 0.2 设计原则（移植时必须遵守）

1. **插件化**：所有全新代码集中在三个独立包，官方升级不会触碰：
   - `app/.../ext/resilience/`（功能 1）
   - `app/.../ext/retry/`（功能 2）
   - `app/.../ext/keys/`（功能 3 UI）+ `ai/.../util/KeyHealth.kt`、`ai/.../provider/ProviderApiKeys.kt`（功能 3 逻辑）
2. **最小侵入**：官方文件的每一处修改都带 `// [自定义修改]` 注释标记，移植时 `grep -rn "\[自定义修改\]"` 即可盘点全部触点。
3. **默认零回归**：所有新增数据字段带默认值（旧配置 JSON 反序列化无缝兼容）；未启用多 Key 的 provider 走原逻辑；未配置高级重试时用与原行为等价的默认值。
4. **锚点定位**：本文档所有官方文件修改均给出「文件 + 函数/位置锚点 + diff」，上游代码变动时按锚点找等价位置重套。

### 0.3 文件-功能映射总表

**新增文件（9 个，直接整体拷贝即可用）**

| 文件 | 行数 | 所属功能 |
|------|-----:|---------|
| `app/src/main/java/me/rerere/rikkahub/ext/resilience/SafeMessageAccess.kt` | 29 | 1 |
| `app/src/main/java/me/rerere/rikkahub/ext/resilience/StreamDraftSaver.kt` | 149 | 1 |
| `app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetryConfig.kt` | 69 | 2 |
| `app/src/main/java/me/rerere/rikkahub/ext/retry/RetryPolicy.kt` | 108 | 2 |
| `app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetrySettingsSheet.kt` | 487 | 2 |
| `ai/src/main/java/me/rerere/ai/provider/ProviderApiKeys.kt` | 160 | 3 |
| `ai/src/main/java/me/rerere/ai/util/KeyHealth.kt` | 226 | 3 |
| `app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderMultiKeySection.kt` | 108 | 3 |
| `app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderKeyManagerSheet.kt` | 700 | 3 |

**修改的官方文件（22 个代码文件 + 2 个 strings + 3 个构建文件）**

| 文件 | 增/删行 | 所属功能 | 修改内容一句话 |
|------|--------|---------|---------------|
| `data/model/Conversation.kt` | +12/-3 | 1 | selectIndex 越界钳制（数据层防线） |
| `ui/components/message/ChatMessage.kt` | +4/-2 | 1 | 空列表检查先于取值 |
| `ui/components/message/ChatMessageBranch.kt` | +6 | 1 | 分支切换用安全访问器 |
| `ui/pages/chat/ChatList.kt` | +11/-4 | 1 | 列表渲染走 clamp 索引 |
| `ui/pages/chat/ChatPage.kt` | +14/-4 | 1 | 分支切换改走服务层安全 API |
| `ui/pages/chat/ChatVM.kt` | +24/-6 | 1 | 切换前重取最新会话，防陈旧快照覆盖 |
| `service/ChatService.kt` | +28/-4 | 1 | 挂接草稿保存器（流式 chunk → 节流落库）；结束/取消 NonCancellable 保存 |
| `data/repository/ConversationRepository.kt` | +34 | 1 | 新增 3 个草稿专用 DAO 通道（绕过 FTS） |
| `RouteActivity.kt` | +12 | 1 | `onStop` 退后台立即 flush 草稿 |
| `utils/CrashHandler.kt` | +10/-2 | 1 | `install` 增加 `onCrash` 应急回调 |
| `RikkaHubApp.kt` | +22/-4 | 1+3 | 草稿保存器装配 + CrashHandler 回调 + KeyRotationPolicy 初始化/同步 |
| `ext/retry/*`（见新增表） | - | 2 | - |
| `data/datastore/PreferencesStore.kt` | +4 | 2 | `NetworkSetting.autoRetry` 字段 |
| `ui/pages/setting/SettingPreferencesNetworkPage.kt` | +17 | 2 | 自动重试行支持长按打开高级配置 |
| `data/ai/GenerationLoop.kt` | +73/-12 | 2+3 | 重试判定改 RetryPolicy 驱动 + Key 故障切换 |
| `ai/provider/ProviderSetting.kt` | +12 | 3 | 三个 Provider 各加 3 个多 Key 字段 |
| `ai/util/KeyRoulette.kt` | +127 | 3 | KeyRotationPolicy 注册表 + 两个轮盘的策略优先分支 |
| `ui/pages/setting/components/ProviderConfigure.kt` | +36/-4 | 3 | 三种 Provider 配置页插入多 Key 入口 + convertTo 字段传递 |
| `di/AppModule.kt` | -11 | 构建 | 移除 Firebase 注入 |
| `di/ViewModelModule.kt` | -1 | 构建 | 移除 analytics 参数 |
| `app/build.gradle.kts` | +18/-8 | 构建 | 版本号、Firebase 移除、签名缺失降级 |
| `build.gradle.kts`（根） | -2 | 构建 | Firebase 插件移除 |
| `gradle/libs.versions.toml` | -8 | 构建 | Firebase 依赖条目移除 |
| `res/values/strings.xml`、`res/values-zh/strings.xml` | 各 +63 | 全部 | 60 个自定义字符串 |

---
# 1. 功能一：消息防丢失与闪退修复

## 1.1 问题根因（官方 2.5.4）

| 症状 | 根因 |
|------|------|
| 退出软件 100% 丢失生成中的消息 | 上游只在生成**结束**时（`onCompletion → finishGeneration → saveConversation`）写库；流式期间数据库里没有任何增量。进程死亡 = 全部丢失 |
| 偶发闪退（切换分支/重新生成时） | `MessageNode.selectIndex` 可能越界（旧版本数据、流式期间 UI 持有陈旧快照），`messages[selectIndex]` 在 Compose 组合期直接抛 `IndexOutOfBoundsException` |
| 空列表闪退 | `ChatMessage.kt` 中先取 `messages.last()` 后判空的顺序错误 |
| 分支切换内容错乱 | `ChatPage` 分支切换用页面缓存的旧 `Conversation` 快照调 `switchNodeBranch`，把陈旧状态写回，覆盖切换结果 |

## 1.2 方案架构

**A. 索引安全化（双防线）**
- 数据层：`Conversation.kt` 内所有 `selectIndex` 消费点做 `coerceIn(0, lastIndex)` 钳制。
- UI 层：新增 `SafeMessageAccess.kt` 扩展（`clampedSelectIndex` / `safeCurrentMessage` / `safeCurrentMessages`），所有 UI 渲染与分支组件一律走安全访问器。任何非法状态退化为"显示最接近的合法消息"，绝不崩溃。
- `ChatMessage.kt`：`isNotEmpty()` 检查移到 `last()` 取值**之前**。

**B. 陈旧快照防护**
- `ChatPage` 分支切换不再直接改本地快照，改调 `ChatVM` → `ChatService.switchNodeBranch(conversationId, nodeId, index)` 安全 API：服务层以 **DB/内存中的最新会话**为准执行切换，UI 只发起意图。
- `ChatVM.switchNodeBranch` 前先向 service 重取当前会话，切换失败不破坏现有状态。

**C. 流式草稿四重兜底（核心）**

```
流式 chunk 到达 ──> ChatService ──> StreamDraftSaver.schedule()   （O(1)，仅更新 pending）
                                        │ 2.5s 节流
                                        ▼
                    增量写 message_node 表（绕过 FTS，引用比较 diff）
兜底1：常态节流     —— 前台被杀最多丢 2.5s 窗口
兜底2：RouteActivity.onStop —— 退后台立即 flushAllDrafts()，后台回收 0 丢失
兜底3：CrashHandler.onCrash —— 崩溃线程上 runBlocking 限时 1.5s 应急 flush
兜底4：生成结束（成功/失败/取消）—— NonCancellable 全量 saveConversation（上游终态保存），
       并 cancelAndAwait() 清理草稿状态
```

- 草稿写库前对最后一条消息做 `finishReasoning()`：进程死亡重启后不残留"思考中"动画态。
- 增量策略：首次整表重写该会话节点；之后仅写引用变化的节点 + 删除消失节点（chunk 更新只替换受影响节点对象，未变节点引用相同，`!==` 比较即可）。
- Repository 新增 3 个草稿通道：`replaceMessageNodesDraft` / `upsertMessageNodeDraft` / `deleteMessageNodeDraft`，**只动 message_node 表，不触碰 conversation 行与 FTS 索引**（FTS 由生成结束的全量保存补齐），避免高频写拖垮索引。

## 1.3 装配关系（RikkaHubApp.onCreate）

```
DatabaseUtil 初始化之后：
  StreamDraftSaver(AppScope, ConversationRepository) → 注入 ChatService
  CrashHandler.install(this) { AppScope 内 draftSaver.flushAllBlocking() }
```

## 1.4 移植要点

1. `SafeMessageAccess.kt`、`StreamDraftSaver.kt` 整体拷贝。前者只依赖 `Conversation/MessageNode/UIMessage` 类型；后者依赖 Repository 的 3 个新 DAO 方法。
2. Repository 的 3 个草稿方法：找到官方 `ConversationRepository` 中操作 `messageNodeDao` 的既有方法作锚点，在旁边添加（代码见 diff）。
3. `ChatService`：找到流式 collect 处（chunk 更新会话状态的位置）插入 `draftSaver.schedule(...)`；找到 `finishGeneration`/`onCompletion` 插入 `cancelAndAwait` + NonCancellable 保存。共 4 处小改。
4. UI 消费点全部替换为安全访问器（grep `messages[` 与 `selectIndex` 逐一核对）。

## 1.5 新增文件完整代码


### 文件：`app/src/main/java/me/rerere/rikkahub/ext/resilience/SafeMessageAccess.kt`（29 行，新增）

```kotlin
package me.rerere.rikkahub.ext.resilience

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode

/**
 * 索引安全的消息访问器（自定义插件层，见 docs/custom/01-message-resilience.md）。
 *
 * 背景：流式生成期间 UI 可能持有陈旧快照、旧版本 DB 数据可能带有非法 selectIndex，
 * 直接 `messages[selectIndex]` 会在 Compose 组合期抛异常导致整个应用闪退。
 * 这里提供 clamp 语义的安全访问器，任何非法状态都退化为"显示最接近的合法消息"，
 * 而不是崩溃。
 */

/** selectIndex 钳制到合法区间；空消息列表返回 -1。 */
val MessageNode.clampedSelectIndex: Int
    get() = if (messages.isEmpty()) -1 else selectIndex.coerceIn(0, messages.lastIndex)

/** 安全的当前消息：clamp 后取值；节点无消息时返回 null。 */
val MessageNode.safeCurrentMessage: UIMessage?
    get() {
        val index = clampedSelectIndex
        return if (index < 0) null else messages[index]
    }

/** 安全展开当前分支消息：跳过空节点，clamp 非法索引。 */
val Conversation.safeCurrentMessages: List<UIMessage>
    get() = messageNodes.mapNotNull { it.safeCurrentMessage }
```

### 文件：`app/src/main/java/me/rerere/rikkahub/ext/resilience/StreamDraftSaver.kt`（149 行，新增）

```kotlin
package me.rerere.rikkahub.ext.resilience

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "StreamDraftSaver"

/** 草稿节流间隔：进程死亡时最多丢失该时长内的增量内容。 */
private const val DRAFT_INTERVAL_MS = 2_500L

/**
 * 流式生成期间的草稿落库器（自定义插件层，见 docs/custom/01-message-resilience.md）。
 *
 * 上游只在生成结束（onCompletion → finishGeneration）时写库，进程被杀/崩溃时
 * 已生成内容 100% 丢失。本类在流式 chunk 到达时以 [DRAFT_INTERVAL_MS] 节流，
 * 把会话当前状态的**增量节点**直接 upsert 到 message_node 表（跳过 FTS 索引，
 * 生成结束时上游的全量 saveConversation 会补齐），使任何时刻进程死亡最多丢失
 * 一个节流窗口的内容。
 *
 * 同时为崩溃兜底提供 [flushAllBlocking]：由 CrashHandler 在进程崩溃前同步调用。
 */
class StreamDraftSaver(
    private val scope: CoroutineScope,
    private val repository: ConversationRepository,
) {
    /** 每个会话的最新待写状态（chunk 到达即覆盖，天然合并）。 */
    private val pending = ConcurrentHashMap<Uuid, Conversation>()

    /** 每个会话最近一次已写入草稿的节点快照（引用比较做增量 diff）。 */
    private val lastDraft = ConcurrentHashMap<Uuid, List<MessageNode>>()

    private val timers = ConcurrentHashMap<Uuid, Job>()
    private val locks = ConcurrentHashMap<Uuid, Mutex>()

    private fun lockFor(id: Uuid): Mutex = locks.getOrPut(id) { Mutex() }

    /**
     * 由 ChatService 在流式 collect 中调用（非挂起、O(1)）。
     * 已有一个待触发的定时任务时仅更新 pending，实现节流合并。
     */
    fun schedule(conversationId: Uuid, conversation: Conversation) {
        pending[conversationId] = conversation
        val existing = timers[conversationId]
        if (existing?.isActive == true) return
        timers[conversationId] = scope.launch {
            delay(DRAFT_INTERVAL_MS)
            runCatching { flush(conversationId) }
                .onFailure { Log.w(TAG, "draft flush failed for $conversationId", it) }
        }
    }

    /** 生成结束（成功/失败/取消）时调用：停止节流任务并丢弃 pending（上游会做全量终态保存）。 */
    suspend fun cancelAndAwait(conversationId: Uuid) {
        lockFor(conversationId).withLock {
            timers.remove(conversationId)?.cancel()
            pending.remove(conversationId)
            lastDraft.remove(conversationId)
        }
    }

    /** 立即写入指定会话的 pending 草稿（如有）。 */
    suspend fun flush(conversationId: Uuid) {
        lockFor(conversationId).withLock {
            val conversation = pending[conversationId] ?: return
            writeDraft(conversation)
        }
    }

    /** 写入所有活跃会话的 pending 草稿。 */
    suspend fun flushAll() {
        pending.keys.toList().forEach { id ->
            runCatching { flush(id) }
        }
    }

    /**
     * 崩溃兜底：在崩溃线程上同步 flush（限时），由 CrashHandler 调用。
     * 尽最大努力，不抛出任何异常。
     */
    fun flushAllBlocking(timeoutMs: Long = 1_500L) {
        if (pending.isEmpty()) return
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(timeoutMs) { flushAll() }
            }
        }.onFailure {
            Log.w(TAG, "emergency flush failed", it)
        }
    }

    private suspend fun writeDraft(conversation: Conversation) = withContext(Dispatchers.IO) {
        val nodes = conversation.messageNodes
        val previous = lastDraft[conversation.id]

        // 对最后一条消息做 finishReasoning：进程死亡后重启时不会残留"思考中"动画状态。
        val draftNodes = nodes.toMutableList().apply {
            if (isNotEmpty()) {
                val lastIndex = lastIndex
                val last = this[lastIndex]
                val finished = last.safeFinishedMessages()
                if (finished !== last.messages) {
                    this[lastIndex] = last.copy(messages = finished)
                }
            }
        }

        if (previous == null) {
            // 首次草稿：整表重写该会话的节点（不触碰 conversation 行与 FTS）
            repository.replaceMessageNodesDraft(conversation.id, draftNodes)
        } else {
            // 增量：只写引用/内容变化或索引位移的节点，并删除已消失的节点行
            val currentIds = draftNodes.mapTo(HashSet()) { it.id }
            previous.forEach { oldNode ->
                if (oldNode.id !in currentIds) {
                    repository.deleteMessageNodeDraft(oldNode.id)
                }
            }
            draftNodes.forEachIndexed { index, node ->
                // 引用比较即可：chunk 更新只会替换受影响节点的对象，未变节点保持同一引用
                if (previous.getOrNull(index) !== node) {
                    repository.upsertMessageNodeDraft(conversation.id, node, index)
                }
            }
        }

        // 记录快照时用"原始"节点列表做引用比较基准（draftNodes 只改了最后一个元素的副本）
        lastDraft[conversation.id] = nodes
        // 以引用比较移除本次已写入的 pending（避免 data class 深比较开销）
        pending.computeIfPresent(conversation.id) { _, v -> if (v === conversation) null else v }
    }

    private fun MessageNode.safeFinishedMessages(): List<me.rerere.ai.ui.UIMessage> =
        runCatching { messages.map { it.finishReasoning() } }.getOrDefault(messages)
}
```
## 1.6 官方文件修改（diff）

（以下 diff 基线为官方 2.5.4；`@@` 行号仅供定位参考，移植时以函数名/上下文锚点为准）

### 文件：`app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`（修改）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt b/app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt
index 10a261ed..ac5bc80c 100644
--- a/app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt
+++ b/app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt
@@ -42,10 +42,15 @@ data class Conversation(
 
     /**
      *  当前选中的 message
+     *  [自定义修改] 使用 clamp 语义防止非法 selectIndex 导致崩溃，
+     *  见 docs/custom/01-message-resilience.md
      */
     val currentMessages
         get(): List<UIMessage> {
-            return messageNodes.map { node -> node.messages[node.selectIndex] }
+            return messageNodes.mapNotNull { node ->
+                if (node.messages.isEmpty()) return@mapNotNull null
+                node.messages[node.selectIndex.coerceIn(0, node.messages.lastIndex)]
+            }
         }
 
     fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
@@ -113,10 +118,11 @@ data class MessageNode(
     @Transient
     val isFavorite: Boolean = false,
 ) {
-    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
+    // [自定义修改] clamp 非法 selectIndex，防止渲染/服务层因悬空索引崩溃（docs/custom/01-message-resilience.md）
+    val currentMessage get() = if (messages.isEmpty()) {
         throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
     } else {
-        messages[selectIndex]
+        messages[selectIndex.coerceIn(0, messages.lastIndex)]
     }
 
     val role get() = messages.firstOrNull()?.role ?: MessageRole.USER
```

### 文件：`app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`（修改）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt b/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
index a18dd69f..823331b7 100644
--- a/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
+++ b/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
@@ -118,7 +118,9 @@ fun ChatMessage(
     onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
     onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
 ) {
-    val message = node.messages[node.selectIndex]
+    // [自定义修改] 空节点直接跳过、clamp 索引防止陈旧/损坏状态导致组合期崩溃（docs/custom/01-message-resilience.md）
+    if (node.messages.isEmpty()) return
+    val message = node.messages[node.selectIndex.coerceIn(0, node.messages.lastIndex)]
     val settings = LocalSettings.current.displaySetting
     val chatFontFamily = LocalChatFontFamily.current ?: rememberChatFontFamily(settings)
     val textStyle = LocalTextStyle.current.copy(
```

### 文件：`app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageBranch.kt`（修改）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageBranch.kt b/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageBranch.kt
index 0ebada4b..3d754932 100644
--- a/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageBranch.kt
+++ b/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageBranch.kt
@@ -29,6 +29,12 @@ fun ChatMessageBranchSelector(
     modifier: Modifier = Modifier,
     onUpdate: (MessageNode) -> Unit,
 ) {
+    // [自定义修改] 先钳制非法 selectIndex，后续显示与切换均基于有效索引（docs/custom/01-message-resilience.md）
+    val node = if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
+        node.copy(selectIndex = node.selectIndex.coerceIn(0, node.messages.lastIndex))
+    } else {
+        node
+    }
     Row(
         modifier = modifier,
         verticalAlignment = Alignment.CenterVertically,
```

### 文件：`app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`（修改）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt b/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt
index 6f7f137c..15ffd6dc 100644
--- a/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt
+++ b/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt
@@ -93,6 +93,7 @@ import me.rerere.rikkahub.data.datastore.Settings
 import me.rerere.rikkahub.data.datastore.getAssistantById
 import me.rerere.rikkahub.data.model.Conversation
 import me.rerere.rikkahub.data.model.MessageNode
+import me.rerere.rikkahub.ext.resilience.safeCurrentMessage
 import me.rerere.rikkahub.service.ChatError
 import me.rerere.rikkahub.ui.components.message.ChatMessage
 import me.rerere.rikkahub.ui.components.ui.ErrorCardsDisplay
@@ -316,6 +317,8 @@ private fun ChatListNormal(
                 items = conversation.messageNodes,
                 key = { index, item -> item.id },
             ) { index, node ->
+                // [自定义修改] 跳过无消息的损坏节点，防止 currentMessage 抛异常导致闪退
+                if (node.messages.isEmpty()) return@itemsIndexed
                 Column {
                     ListSelectableItem(
                         key = node.id,
@@ -502,7 +505,8 @@ private fun ChatListNormal(
                     selectedItems.clear()
                 },
                 conversation = conversation,
-                selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }
+                selectedMessages = conversation.messageNodes
+                    .filter { it.id in selectedItems && it.messages.isNotEmpty() }
                     .map { it.currentMessage }
             )
 
@@ -610,7 +614,10 @@ private fun ChatListPreview(
             conversation.messageNodes.mapIndexed { index, node -> index to node }
         } else {
             conversation.messageNodes.mapIndexed { index, node -> index to node }
-                .filter { (_, node) -> node.currentMessage.toText().contains(searchQuery, ignoreCase = true) }
+                .filter { (_, node) ->
+                    // [自定义修改] 安全访问，防止损坏节点在搜索时崩溃
+                    node.safeCurrentMessage?.toText()?.contains(searchQuery, ignoreCase = true) == true
+                }
         }
     }
 
```

### 文件：`app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`（修改）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt b/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt
index 5460f45f..b2dd5eed 100644
--- a/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt
+++ b/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt
@@ -478,17 +478,9 @@ private fun ChatPageContent(
                     }
                 },
                 onUpdateMessage = { newNode ->
-                    vm.updateConversation(
-                        conversation.copy(
-                            messageNodes = conversation.messageNodes.map { node ->
-                                if (node.id == newNode.id) {
-                                    newNode
-                                } else {
-                                    node
-                                }
-                            }
-                        ))
-                    vm.saveConversationAsync()
+                    // [自定义修改] 分支切换改走服务层安全路径：读取新鲜状态、校验索引并即时落库，
+                    // 避免用 UI 陈旧快照整段覆盖流式中的会话状态（docs/custom/01-message-resilience.md）
+                    vm.selectMessageNode(newNode.id, newNode.selectIndex)
                 },
                 onClickSuggestion = { suggestion ->
                     inputState.editingMessage = null
```

### 文件：`app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`（修改）
> 同文件含 Firebase analytics 移除（见 5.2 节）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt b/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt
index f86b1dac..79b46993 100644
--- a/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt
+++ b/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt
@@ -11,7 +11,6 @@ import androidx.compose.ui.tooling.preview.Preview
 import androidx.core.net.toUri
 import androidx.lifecycle.ViewModel
 import androidx.lifecycle.viewModelScope
-import com.google.firebase.analytics.FirebaseAnalytics
 import kotlinx.coroutines.Job
 import kotlinx.coroutines.flow.SharedFlow
 import kotlinx.coroutines.flow.SharingStarted
@@ -57,7 +56,6 @@ class ChatVM(
     private val conversationRepo: ConversationRepository,
     private val chatService: ChatService,
     val updateChecker: UpdateChecker,
-    private val analytics: FirebaseAnalytics,
     private val filesManager: FilesManager,
     private val favoriteRepository: FavoriteRepository,
 ) : ViewModel() {
@@ -206,14 +204,12 @@ class ChatVM(
      */
     fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
         if (content.isEmptyInputMessage()) return
-        analytics.logEvent("ai_send_message", null)
 
         chatService.sendMessage(_conversationId, content, answer)
     }
 
     fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
         if (parts.isEmptyInputMessage()) return
-        analytics.logEvent("ai_edit_message", null)
 
         viewModelScope.launch {
             chatService.editMessage(_conversationId, messageId, parts)
@@ -256,7 +252,6 @@ class ChatVM(
         message: UIMessage,
         regenerateAssistantMsg: Boolean = true
     ) {
-        analytics.logEvent("ai_regenerate_at_message", null)
         chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
     }
 
@@ -265,7 +260,6 @@ class ChatVM(
         approved: Boolean,
         reason: String = ""
     ) {
-        analytics.logEvent("ai_tool_approval", null)
         chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
     }
 
@@ -273,7 +267,6 @@ class ChatVM(
         toolCallId: String,
         answer: String,
     ) {
-        analytics.logEvent("ai_tool_answer", null)
         chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
     }
 
@@ -343,6 +336,23 @@ class ChatVM(
         }
     }
 
+    // [自定义修改] 分支切换安全路径（docs/custom/01-message-resilience.md）：
+    // ChatService.selectMessageNode 基于服务层新鲜状态校验并持久化，异常转为错误卡片而不是崩溃。
+    fun selectMessageNode(nodeId: Uuid, selectIndex: Int) {
+        viewModelScope.launch {
+            runCatching {
+                chatService.selectMessageNode(_conversationId, nodeId, selectIndex)
+            }.onFailure {
+                if (it is kotlinx.coroutines.CancellationException) throw it
+                chatService.addError(
+                    error = it,
+                    conversationId = _conversationId,
+                    title = context.getString(R.string.error_title_operation)
+                )
+            }
+        }
+    }
+
     fun toggleMessageFavorite(node: MessageNode) {
         viewModelScope.launch {
             val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
```

### 文件：`app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`（修改）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt b/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
index 22abacc1..324fa677 100644
--- a/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
+++ b/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
@@ -166,6 +166,18 @@ class ChatService(
     // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
     private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
 
+    // [自定义修改] 流式草稿落库器：生成期间节流保存，进程死亡最多丢 ~2.5s 内容
+    // （docs/custom/01-message-resilience.md）
+    private val draftSaver = me.rerere.rikkahub.ext.resilience.StreamDraftSaver(appScope, conversationRepo)
+
+    /** 崩溃兜底：由 CrashHandler 在进程崩溃前同步调用，尽最大努力保住已生成内容。 */
+    fun flushAllDraftsBlocking(timeoutMs: Long = 1_500L) = draftSaver.flushAllBlocking(timeoutMs)
+
+    /** [自定义修改] 应用退到后台时立即落盘在途草稿，消除"后台被杀丢一个节流窗口"的残留风险。 */
+    fun flushAllDrafts() {
+        appScope.launch { runCatching { draftSaver.flushAll() } }
+    }
+
     private val sessionManager = ConversationSessionManager(
         scope = appScope,
         createInitialConversation = { id ->
@@ -202,7 +214,11 @@ class ChatService(
     private val _generationDoneFlow = MutableSharedFlow<Uuid>()
     val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()
 
-    fun cleanup() = runCatching { sessionManager.cleanup() }
+    fun cleanup() = runCatching {
+        // [自定义修改] 应用退出清理前先把在途草稿写入 DB
+        draftSaver.flushAllBlocking(800)
+        sessionManager.cleanup()
+    }
 
     private fun onSessionGenerationFinished(session: ConversationSession, cause: Throwable?) {
         if (cause != null) session.messageQueue.pause()
@@ -678,6 +694,11 @@ class ChatService(
                 outputTransformers = outputTransformers,
                 tools = tools,
             ).onCompletion {
+                // [自定义修改] 生成结束：先停掉草稿节流（等待在途写入完成），再走上游全量终态保存。
+                // NonCancellable：取消路径上也要完成这一步，避免草稿写入与终态保存乱序。
+                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
+                    runCatching { draftSaver.cancelAndAwait(conversationId) }
+                }
                 // 可能被取消了，或者意外结束，兜底更新
                 val updatedConversation = session.finishGeneration { conversation ->
                     saveConversation(conversationId, conversation)
@@ -698,6 +719,8 @@ class ChatService(
                         val updatedConversation = getConversationFlow(conversationId).value
                             .updateCurrentMessages(chunk.messages)
                         updateConversation(conversationId, updatedConversation)
+                        // [自定义修改] 节流草稿落库，进程被杀/崩溃时最多丢一个窗口的内容
+                        draftSaver.schedule(conversationId, updatedConversation)
 
                         // 通知等边缘副作用由 ChatNotificationManager 消费；
                         // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
@@ -758,9 +781,10 @@ class ChatService(
                 }
 
                 // Remove messages that still have unresolved tool approvals.
+                // [自定义修改] clamp selectIndex，防止 -1 等悬空索引（docs/custom/01-message-resilience.md）
                 return@mapIndexed node.copy(
                     messages = node.messages.filter { it.id != node.currentMessage.id },
-                    selectIndex = node.selectIndex - 1
+                    selectIndex = (node.selectIndex - 1).coerceAtLeast(0)
                 )
             }
             node
```

### 文件：`app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt`（修改）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt b/app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt
index d2f27538..50d1ee86 100644
--- a/app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt
+++ b/app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt
@@ -475,6 +475,40 @@ class ConversationRepository(
         }
     }
 
+    // ==== 流式草稿写入（自定义插件层钩子，见 docs/custom/01-message-resilience.md）====
+    // 生成期间由 StreamDraftSaver 调用：只动 message_node 表，跳过 FTS 索引，
+    // 生成结束时上游的全量 updateConversation 会重建索引。
+
+    /** 草稿全量替换某会话的节点行（首次草稿用）。 */
+    suspend fun replaceMessageNodesDraft(conversationId: Uuid, nodes: List<MessageNode>) {
+        database.withTransaction {
+            val conversationIdStr = conversationId.toString()
+            val keepIds = nodes.mapTo(HashSet()) { it.id.toString() }
+            messageNodeDAO.getNodesOfConversation(conversationIdStr).forEach { existing ->
+                if (existing.id !in keepIds) messageNodeDAO.deleteById(existing.id)
+            }
+            saveMessageNodes(conversationIdStr, nodes)
+        }
+    }
+
+    /** 草稿增量 upsert 单个节点。 */
+    suspend fun upsertMessageNodeDraft(conversationId: Uuid, node: MessageNode, index: Int) {
+        messageNodeDAO.insert(
+            MessageNodeEntity(
+                id = node.id.toString(),
+                conversationId = conversationId.toString(),
+                nodeIndex = index,
+                messages = JsonInstant.encodeToString(node.messages),
+                selectIndex = node.selectIndex,
+            )
+        )
+    }
+
+    /** 草稿清理已删除节点。 */
+    suspend fun deleteMessageNodeDraft(nodeId: Uuid) {
+        messageNodeDAO.deleteById(nodeId.toString())
+    }
+
     private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
         val entities = nodes.mapIndexed { index, node ->
             MessageNodeEntity(
```

### 文件：`app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`（修改）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/RouteActivity.kt b/app/src/main/java/me/rerere/rikkahub/RouteActivity.kt
index bef78710..55eb0ac2 100644
--- a/app/src/main/java/me/rerere/rikkahub/RouteActivity.kt
+++ b/app/src/main/java/me/rerere/rikkahub/RouteActivity.kt
@@ -207,6 +207,18 @@ class RouteActivity : ComponentActivity() {
         super.onNewIntent(intent)
         setIntent(intent)
         handleIntent(intent)
+    }
+
+    // [自定义修改] 应用退到后台时立即落盘流式草稿，后台进程被系统回收也不丢
+    // 已生成内容（docs/custom/01-message-resilience.md）。getOrNull：ChatService
+    // 尚未创建说明没有生成中的会话，无需 flush。
+    override fun onStop() {
+        super.onStop()
+        runCatching {
+            org.koin.java.KoinJavaComponent.getKoin()
+                .getOrNull<me.rerere.rikkahub.service.ChatService>()
+                ?.flushAllDrafts()
+        }
     }
 
     private fun handleIntent(intent: Intent) {
```

### 文件：`app/src/main/java/me/rerere/rikkahub/utils/CrashHandler.kt`（修改）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/utils/CrashHandler.kt b/app/src/main/java/me/rerere/rikkahub/utils/CrashHandler.kt
index 61c45cbe..6a4f8efa 100644
--- a/app/src/main/java/me/rerere/rikkahub/utils/CrashHandler.kt
+++ b/app/src/main/java/me/rerere/rikkahub/utils/CrashHandler.kt
@@ -11,11 +11,19 @@ private const val KEY_STACKTRACE = "stacktrace"
 private const val MAX_STACKTRACE_LENGTH = 8000
 
 object CrashHandler {
-    fun install(context: Context) {
+    /**
+     * @param onCrash [自定义修改] 崩溃时的应急回调（限时同步执行，用于抢救未落盘的生成内容，
+     * 见 docs/custom/01-message-resilience.md）。回调内部必须自行捕获异常。
+     */
+    fun install(context: Context, onCrash: (() -> Unit)? = null) {
         val appContext = context.applicationContext
         val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
         Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
             Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
+            if (onCrash != null) {
+                runCatching { onCrash() }
+                    .onFailure { Log.w(TAG, "onCrash callback failed", it) }
+            }
             markCrashed(appContext, thread, throwable)
             defaultHandler?.uncaughtException(thread, throwable)
         }
```

### 文件：`app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt`（修改）
> 同文件含功能三装配（KeyRotationPolicy.init/sync），一次改完

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt b/app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt
index 173c9d8c..682477d4 100644
--- a/app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt
+++ b/app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt
@@ -40,6 +40,7 @@ import me.rerere.rikkahub.utils.DatabaseUtil
 import me.rerere.rikkahub.data.repository.WorkspaceRepository
 import me.rerere.workspace.WorkspaceManager
 import org.koin.android.ext.android.get
+import org.koin.java.KoinJavaComponent.getKoin
 import org.koin.android.ext.koin.androidContext
 import org.koin.android.ext.koin.androidLogger
 import org.koin.androidx.workmanager.koin.workManagerFactory
@@ -77,8 +78,18 @@ class RikkaHubApp : Application() {
         // set cursor window size to 32MB
         DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)
 
+        // [自定义修改] Key 健康注册表：加载持久化的停用/冷却记录（docs/custom/03-multi-key.md）
+        me.rerere.ai.util.KeyRotationPolicy.init(this)
+
         // install crash handler
-        CrashHandler.install(this)
+        // [自定义修改] 崩溃时应急保存所有生成中会话的草稿（docs/custom/01-message-resilience.md）
+        // 用 getOrNull：ChatService 尚未创建时不存在草稿，避免在崩溃路径上初始化依赖图
+        CrashHandler.install(this) {
+            runCatching {
+                getKoin().getOrNull<me.rerere.rikkahub.service.ChatService>()
+                    ?.flushAllDraftsBlocking(1500)
+            }
+        }
 
         // delete temp files
         deleteTempFiles()
@@ -101,6 +112,15 @@ class RikkaHubApp : Application() {
         // Increment launch count
         incrementLaunchCount()
 
+        // [自定义修改] 持续同步多 Key 轮换策略注册表（docs/custom/03-multi-key.md）
+        get<AppScope>().launch {
+            runCatching {
+                get<SettingsStore>().settingsFlow.collect { currentSettings ->
+                    me.rerere.ai.util.KeyRotationPolicy.sync(currentSettings.providers)
+                }
+            }
+        }
+
         // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
     }
 
```

# 2. 功能二：高级自动重试

## 2.1 问题根因（官方 2.5.4）

- 官方 `GenerationLoop.awaitNetworkRetryOrThrow` 的判定是 `error !is IOException → 直接抛出`。
- 但 provider 实现（OpenAI/Claude/Google）在 HTTP 失败时抛的是**普通 `Exception("Failed to get response: <code> <body>")`**，不是 IOException。
- 结果：429 限流、5xx 服务错误、520-524 Cloudflare 错误——这些最常见、最值得重试的错误**从不触发重试**。自动重试实际上只对断网/超时生效，"基本没用"。
- 且次数（3）、延迟（1s 起指数）全部硬编码，用户不可配置。

## 2.2 方案：配置驱动的综合判定

保留官方 `enableAutoRetry` 作为**总开关**，新增 `AutoRetryConfig` 高级配置（挂 `NetworkSetting.autoRetry` 字段，带默认值 → 旧配置无缝兼容）。

**判定顺序（`RetryPolicy.shouldRetry`，短路求值）：**

```
1. CancellationException（用户取消）        → 不重试
2. 错误文本命中「停止关键词」               → 不重试（余额不足/无效Key/内容违规等，重试无意义）
3. IOException 网络传输类（含 cause 链 4 层）→ 由 retryOnNetworkError 开关决定
4. 错误文本命中「重试关键词」               → 重试（限流/繁忙/超时提示等）
5. HTTP 状态码 ∈ retryStatusCodes           → 重试
6. 其他                                     → 不重试
```

**状态码提取**：两条正则覆盖各 provider 错误格式——
- `response:\s*(\d{3})`（rikkahub provider 格式 `Failed to get response: 429 {...}`）
- `(?:HTTP|status\s*code|error\s*code)[:\s]+(\d{3})`（通用格式）
- 提取范围：`error.message + toString() + cause 链至多 3 层`。

**退避算法（`RetryPolicy.backoffDelay`）：**

```
delay(i) = min(initialDelayMs × multiplier^i, maxDelayMs)   // i = 第几次重试，0 起
jitter 开启时再乘 0.8~1.2 随机因子（防多客户端同步重试拥塞）
```

## 2.3 数据模型与默认值（实测最优配置）

| 字段 | 默认值 | 范围/说明 |
|------|--------|----------|
| `maxRetries` | 3 | 0..10，额外重试次数（3 = 最多请求 4 次） |
| `initialDelayMs` | 1000 | ≥0，首次重试前延迟 |
| `multiplier` | 2.0 | 1.0..5.0，指数退避倍率 |
| `maxDelayMs` | 30000 | ≥0，单次延迟上限 |
| `jitter` | true | 延迟随机 ±20% |
| `retryOnNetworkError` | true | IOException 类是否重试 |
| `retryStatusCodes` | {408,425,429,500,502,503,504,520,521,522,524,529} | 覆盖标准 5xx + Cloudflare 52x + 429 限流 |
| `retryKeywords` | 并发/稍后/重试/访问量过大/繁忙/限流/频率/rate limit/too many requests/overloaded/try again/timeout/超时/temporarily/capacity | 大小写不敏感 contains |
| `stopKeywords` | 余额/不足/额度/欠费/未实名/balance/insufficient/quota/invalid api key/unauthorized/permission/context length/maximum context/content filter/敏感词/违规/风控 | 命中立即失败 |

所有读取处调用 `clamped()` 归一化，非法值自动回落默认，UI 滑杆/输入框同样限制范围。

## 2.4 GenerationLoop 接线（与功能 3 共用一处改造）

- 删除硬编码 `MAX_PROVIDER_NETWORK_RETRIES` / `INITIAL_PROVIDER_RETRY_DELAY_MS`。
- `executeProviderRequestWithRetry` 与 `awaitNetworkRetryOrThrow` 增加 `retryConfig: AutoRetryConfig` 和 `provider: ProviderSetting?` 参数（两处调用点传 `settings.networkSetting.autoRetry` 与当前 provider）。
- 判定改为 `enabled && RetryPolicy.shouldRetry(error, config)`；延迟改为 `RetryPolicy.backoffDelay(retryCount, config)`。
- 状态栏提示：新增 `getRetryErrorMessage`（非 IOException 也给出可读摘要：message 首行截 80 字符），复用官方 `chat_generation_network_retrying` 格式串（参数：错误摘要、第几次、总预算）。
- **与功能 3 的联动分支**（canSwitchKey）见 3.4 节；两功能在同一段代码里，diff 一并给出。

## 2.5 UI：长按进入高级配置（设置 → 网络）

- 官方「自动重试」开关行**保留原样**（点按 = 总开关）。
- 整行增加 `combinedClickable`：**长按** → 打开 `AutoRetrySettingsSheet`（ModalBottomSheet）。
- Sheet 结构（风格完全使用项目自有组件：UiKit 风格卡片、`extendColors`、`HugeIcons`、`toaster`）：
  1. **主控区**（首屏直接可见）：最大重试次数（滑杆+数值）、初始延迟、倍率、最大延迟（数值输入行）、抖动开关、网络错误重试开关、底部「恢复默认」「保存」。
  2. **三个折叠行**（默认收起，解决"占空间一大批"）：
     - 重试状态码：标题 + 条目数 + 重置 + 展开箭头；展开后 12 个常用码 FilterChip 点选（408/425/429/500/502/503/504/520/521/522/524/529）+ 手动添加输入框。
     - 重试关键词：同上折叠； chips + 自定义增删。
     - 停止关键词：同上折叠，附说明文案（命中即停）。
  3. 保存 → `updateNetworkSetting(autoRetry = config.clamped())` → `toaster` 成功提示。

## 2.6 新增文件完整代码


### 文件：`app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetryConfig.kt`（69 行，新增）

```kotlin
package me.rerere.rikkahub.ext.retry

import kotlinx.serialization.Serializable

/**
 * 高级自动重试配置（自定义插件层，参考 kelivo AutoRetryOptions，
 * 见 docs/custom/02-auto-retry.md）。
 *
 * 挂载于 NetworkSetting.autoRetry 字段；既有 enableAutoRetry 保留为总开关。
 * 全部字段带默认值 → 旧版本 DataStore JSON 反序列化无缝兼容（JsonInstant
 * ignoreUnknownKeys=true）。
 */
@Serializable
data class AutoRetryConfig(
    /** 首次请求之外的额外重试次数，3 = 最多请求 4 次。0..10 */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** 首次重试前的延迟（毫秒） */
    val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    /** 指数退避倍率 */
    val multiplier: Double = DEFAULT_MULTIPLIER,
    /** 单次延迟上限（毫秒） */
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    /** 每次延迟随机 ±20%，避免多客户端同时重试造成拥塞 */
    val jitter: Boolean = true,
    /** 是否重试网络传输类错误（超时/断连等 IOException） */
    val retryOnNetworkError: Boolean = true,
    /** 命中即重试的 HTTP 状态码 */
    val retryStatusCodes: Set<Int> = DEFAULT_RETRY_STATUS_CODES,
    /** 错误文本命中即重试的关键词（如限流提示） */
    val retryKeywords: List<String> = DEFAULT_RETRY_KEYWORDS,
    /** 错误文本命中即立刻失败的关键词（如余额不足，重试无意义） */
    val stopKeywords: List<String> = DEFAULT_STOP_KEYWORDS,
) {
    companion object {
        const val MIN_MAX_RETRIES = 0
        const val MAX_MAX_RETRIES = 10
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_INITIAL_DELAY_MS = 1000L
        const val DEFAULT_MULTIPLIER = 2.0
        const val DEFAULT_MAX_DELAY_MS = 30000L
        const val MIN_MULTIPLIER = 1.0
        const val MAX_MULTIPLIER = 5.0

        val DEFAULT_RETRY_STATUS_CODES =
            setOf(408, 425, 429, 500, 502, 503, 504, 520, 521, 522, 524, 529)

        val DEFAULT_RETRY_KEYWORDS = listOf(
            "并发", "稍后", "重试", "访问量过大", "繁忙", "限流", "频率",
            "rate limit", "too many requests", "overloaded", "try again",
            "timeout", "超时", "temporarily", "capacity",
        )

        val DEFAULT_STOP_KEYWORDS = listOf(
            "余额", "不足", "额度", "欠费", "未实名",
            "balance", "insufficient", "quota", "invalid api key",
            "unauthorized", "permission",
            "context length", "maximum context", "content filter",
            "敏感词", "违规", "风控",
        )
    }

    fun clamped(): AutoRetryConfig = copy(
        maxRetries = maxRetries.coerceIn(MIN_MAX_RETRIES, MAX_MAX_RETRIES),
        initialDelayMs = initialDelayMs.coerceAtLeast(0),
        multiplier = if (!multiplier.isFinite() || multiplier <= 0) DEFAULT_MULTIPLIER
        else multiplier.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER),
        maxDelayMs = maxDelayMs.coerceAtLeast(0),
    )
}
```

### 文件：`app/src/main/java/me/rerere/rikkahub/ext/retry/RetryPolicy.kt`（108 行，新增）

```kotlin
package me.rerere.rikkahub.ext.retry

import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * 自动重试判定与退避策略（自定义插件层，移植自 kelivo retry_policy.dart，
 * 见 docs/custom/02-auto-retry.md）。
 *
 * 上游只对 IOException 重试，而 provider 实现在 HTTP 失败时抛出的是普通
 * Exception("Failed to get response: <code> <body>")，导致 429/5xx 等最常见的
 * 可重试错误从不触发重试。本策略按 状态码 + 关键词 + 停止词 综合判定。
 */
object RetryPolicy {

    // rikkahub provider: "Failed to get response: 429 {...}"
    private val RESPONSE_CODE_REGEX = Regex("""response:\s*(\d{3})""", RegexOption.IGNORE_CASE)

    // 通用形式: "HTTP 503" / "status code: 503" / "Error code: 503"
    private val GENERIC_STATUS_REGEX =
        Regex("""(?:HTTP|status\s*code|error\s*code)[:\s]+(\d{3})""", RegexOption.IGNORE_CASE)

    /** 从异常信息中提取 HTTP 状态码（无法提取返回 null）。 */
    fun extractHttpStatus(error: Throwable): Int? {
        val text = error.message ?: error.toString()
        val full = buildString {
            append(text)
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 3) {
                append(' ')
                append(cause.message ?: cause.toString())
                cause = cause.cause
                depth++
            }
        }
        val match = RESPONSE_CODE_REGEX.find(full) ?: GENERIC_STATUS_REGEX.find(full)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun isNetworkTransportError(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            if (current is IOException) return true
            current = current.cause
            depth++
        }
        return false
    }

    private fun containsKeyword(text: String, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val lower = text.lowercase()
        return keywords.any { it.isNotBlank() && lower.contains(it.lowercase()) }
    }

    /**
     * 是否应当重试（kelivo 判定顺序）：
     * 1. 用户取消 → 否
     * 2. 命中停止关键词（余额不足/无效 Key 等）→ 否
     * 3. 网络传输类错误 → 由 retryOnNetworkError 决定
     * 4. 命中重试关键词（限流/繁忙等）→ 是
     * 5. HTTP 状态码在重试集合中 → 是
     * 6. 其他 → 否
     */
    fun shouldRetry(error: Throwable, config: AutoRetryConfig): Boolean {
        if (error is CancellationException) return false
        val text = buildString {
            append(error.message ?: "")
            append(' ')
            append(error.toString())
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 3) {
                append(' ')
                append(cause.message ?: "")
                cause = cause.cause
                depth++
            }
        }
        if (containsKeyword(text, config.stopKeywords)) return false
        val status = extractHttpStatus(error)
        if (isNetworkTransportError(error)) return config.retryOnNetworkError
        if (containsKeyword(text, config.retryKeywords)) return true
        if (status != null && status in config.retryStatusCodes) return true
        return false
    }

    /**
     * 第 [attemptIndex] 次重试（0 起）前的延迟：
     * initialDelay * multiplier^attempt，封顶 maxDelay，jitter ±20%。
     */
    fun backoffDelay(attemptIndex: Int, config: AutoRetryConfig): Long {
        val c = config.clamped()
        val factor = if (attemptIndex <= 0) 1.0 else c.multiplier.pow(attemptIndex)
        var ms = c.initialDelayMs * factor
        if (!ms.isFinite() || ms > c.maxDelayMs) ms = c.maxDelayMs.toDouble()
        if (ms < 0) ms = 0.0
        if (c.jitter) {
            ms *= 0.8 + Random.nextDouble() * 0.4
        }
        return min(ms.toLong().coerceAtLeast(0), c.maxDelayMs)
    }
}
```

### 文件：`app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetrySettingsSheet.kt`（487 行，新增）

```kotlin
package me.rerere.rikkahub.ext.retry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.context.LocalToaster
import com.dokar.sonner.ToastType

/** 状态码快捷开关预设（含常见 CDN/中转站错误码段），点选即加入/移出重试集合。 */
private val PRESET_STATUS_CODES =
    setOf(408, 425, 429, 500, 502, 503, 504, 520, 521, 522, 524, 529)

/**
 * 高级自动重试配置底部弹窗（自定义插件层，交互参考 kelivo auto_retry_page，
 * 视觉风格沿用本项目 Material3 组件，见 docs/custom/02-auto-retry.md）。
 *
 * 入口：设置 → 偏好设置 → 网络 → 长按"自动重试"行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoRetrySettingsSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val toaster = LocalToaster.current
    val network = settings.networkSetting
    // 本地草稿，点击"保存"统一写入 DataStore，避免拖动滑条时高频写盘
    var enableAutoRetry by remember { mutableStateOf(network.enableAutoRetry) }
    var config by remember { mutableStateOf(network.autoRetry.clamped()) }

    fun persist() {
        onUpdateSettings(
            settings.copy(
                networkSetting = network.copy(
                    enableAutoRetry = enableAutoRetry,
                    autoRetry = config.clamped(),
                )
            )
        )
        toaster.show(message = context.getString(R.string.auto_retry_saved), type = ToastType.Success)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.auto_retry_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.auto_retry_sheet_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(top = 4.dp))

            // ---- 总开关 ----
            SwitchRow(
                title = stringResource(R.string.setting_page_preferences_network_auto_retry),
                subtitle = stringResource(R.string.auto_retry_master_switch_desc),
                checked = enableAutoRetry,
                onCheckedChange = { enableAutoRetry = it },
            )

            // ---- 数值配置 ----
            SliderRow(
                label = stringResource(R.string.auto_retry_max_retries),
                value = config.maxRetries.toFloat(),
                valueRange = AutoRetryConfig.MIN_MAX_RETRIES.toFloat()..AutoRetryConfig.MAX_MAX_RETRIES.toFloat(),
                steps = AutoRetryConfig.MAX_MAX_RETRIES - 1,
                displayValue = "${config.maxRetries}",
                onValueChange = { config = config.copy(maxRetries = it.toInt()) },
            )
            SliderRow(
                label = stringResource(R.string.auto_retry_initial_delay),
                value = config.initialDelayMs.toFloat(),
                valueRange = 0f..10_000f,
                steps = 39,
                displayValue = "${config.initialDelayMs} ms",
                onValueChange = { config = config.copy(initialDelayMs = (it / 250).toInt() * 250L) },
            )
            SliderRow(
                label = stringResource(R.string.auto_retry_multiplier),
                value = config.multiplier.toFloat(),
                valueRange = AutoRetryConfig.MIN_MULTIPLIER.toFloat()..AutoRetryConfig.MAX_MULTIPLIER.toFloat(),
                steps = 7,
                displayValue = String.format("%.1fx", config.multiplier),
                onValueChange = {
                    config = config.copy(multiplier = Math.round(it * 10.0) / 10.0)
                },
            )
            SliderRow(
                label = stringResource(R.string.auto_retry_max_delay),
                value = config.maxDelayMs.toFloat(),
                valueRange = 1_000f..120_000f,
                steps = 118,
                displayValue = "${config.maxDelayMs} ms",
                onValueChange = { config = config.copy(maxDelayMs = (it / 1000).toInt() * 1000L) },
            )

            SwitchRow(
                title = stringResource(R.string.auto_retry_jitter),
                subtitle = stringResource(R.string.auto_retry_jitter_desc),
                checked = config.jitter,
                onCheckedChange = { config = config.copy(jitter = it) },
            )
            SwitchRow(
                title = stringResource(R.string.auto_retry_on_network_error),
                subtitle = stringResource(R.string.auto_retry_on_network_error_desc),
                checked = config.retryOnNetworkError,
                onCheckedChange = { config = config.copy(retryOnNetworkError = it) },
            )

            HorizontalDivider(Modifier.padding(top = 4.dp))

            // ---- 状态码 / 重试关键词 / 停止关键词：折叠收纳，只占一行 ----
            var codesExpanded by remember { mutableStateOf(false) }
            var retryKwExpanded by remember { mutableStateOf(false) }
            var stopKwExpanded by remember { mutableStateOf(false) }

            CollapsibleSection(
                title = stringResource(R.string.auto_retry_status_codes),
                count = config.retryStatusCodes.size,
                expanded = codesExpanded,
                onToggle = { codesExpanded = !codesExpanded },
                onReset = {
                    config = config.copy(
                        retryStatusCodes = AutoRetryConfig.DEFAULT_RETRY_STATUS_CODES
                    )
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 常用状态码快捷开关，点选即加入/移出
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PRESET_STATUS_CODES.forEach { code ->
                            FilterChip(
                                selected = code in config.retryStatusCodes,
                                onClick = {
                                    config = config.copy(
                                        retryStatusCodes = if (code in config.retryStatusCodes) {
                                            config.retryStatusCodes - code
                                        } else {
                                            config.retryStatusCodes + code
                                        }
                                    )
                                },
                                label = { Text("$code") },
                            )
                        }
                    }
                    // 预设之外的自定义状态码
                    val customCodes = config.retryStatusCodes
                        .filter { it !in PRESET_STATUS_CODES }
                        .sorted()
                        .map { it.toString() }
                    ChipEditor(
                        items = customCodes,
                        hint = stringResource(R.string.auto_retry_status_code_hint),
                        numeric = true,
                        onItemsChange = { list ->
                            config = config.copy(
                                retryStatusCodes = (
                                        config.retryStatusCodes.filter { it in PRESET_STATUS_CODES } +
                                                list.mapNotNull { it.toIntOrNull() }
                                        ).toSet()
                            )
                        },
                    )
                }
            }

            CollapsibleSection(
                title = stringResource(R.string.auto_retry_keywords),
                count = config.retryKeywords.size,
                expanded = retryKwExpanded,
                onToggle = { retryKwExpanded = !retryKwExpanded },
                onReset = {
                    config = config.copy(retryKeywords = AutoRetryConfig.DEFAULT_RETRY_KEYWORDS)
                },
            ) {
                ChipEditor(
                    items = config.retryKeywords,
                    hint = stringResource(R.string.auto_retry_keyword_hint),
                    numeric = false,
                    onItemsChange = { config = config.copy(retryKeywords = it) },
                )
            }

            CollapsibleSection(
                title = stringResource(R.string.auto_retry_stop_keywords),
                count = config.stopKeywords.size,
                expanded = stopKwExpanded,
                onToggle = { stopKwExpanded = !stopKwExpanded },
                onReset = {
                    config = config.copy(stopKeywords = AutoRetryConfig.DEFAULT_STOP_KEYWORDS)
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.auto_retry_stop_keywords_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChipEditor(
                        items = config.stopKeywords,
                        hint = stringResource(R.string.auto_retry_keyword_hint),
                        numeric = false,
                        onItemsChange = { config = config.copy(stopKeywords = it) },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = {
                        enableAutoRetry = true
                        config = AutoRetryConfig()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(HugeIcons.Refresh01, null, Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.auto_retry_reset_all))
                }
                Button(onClick = { persist(); onDismissRequest() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_retry_save))
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                displayValue,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

/** 折叠区块：收起时只占一行（标题 + 条目数 + 重置 + 箭头）。 */
@Composable
private fun CollapsibleSection(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.auto_retry_items_count, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onReset, modifier = Modifier.size(32.dp)) {
                Icon(
                    HugeIcons.Refresh01,
                    stringResource(R.string.auto_retry_reset_defaults),
                    modifier = Modifier.size(16.dp),
                )
            }
            Icon(
                if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = stringResource(
                    if (expanded) R.string.auto_retry_collapse else R.string.auto_retry_expand
                ),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipEditor(
    items: List<String>,
    hint: String,
    numeric: Boolean,
    onItemsChange: (List<String>) -> Unit,
) {
    var input by remember { mutableStateOf("") }

    fun commitInput() {
        val value = input.trim()
        if (value.isEmpty()) return
        if (numeric && value.toIntOrNull() == null) return
        if (value in items) {
            input = ""
            return
        }
        onItemsChange(items + value)
        input = ""
    }

    if (items.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                InputChip(
                    selected = false,
                    onClick = { onItemsChange(items - item) },
                    label = { Text(item) },
                    trailingIcon = {
                        Icon(HugeIcons.Cancel01, null, Modifier.height(16.dp))
                    },
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text(hint, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = if (numeric) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            },
        )
        IconButton(onClick = { commitInput() }) {
            Icon(HugeIcons.Add01, stringResource(R.string.auto_retry_add))
        }
    }
}
```
## 2.7 官方文件修改（diff）


### 文件：`app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt`（修改）
> 同文件含功能三 Key 故障切换（3.4 节），一次改完

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt b/app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt
index 493fb165..5e65fec7 100644
--- a/app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt
+++ b/app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt
@@ -30,6 +30,8 @@ import me.rerere.ai.ui.ToolApprovalState
 import me.rerere.ai.ui.StreamChunkHandler
 import me.rerere.ai.ui.handleTextGenerationResult
 import me.rerere.ai.ui.limitContext
+import me.rerere.ai.util.AllKeysSuspendedException
+import me.rerere.ai.util.KeyRotationPolicy
 import me.rerere.rikkahub.R
 import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
 import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
@@ -40,6 +42,8 @@ import me.rerere.rikkahub.data.ai.transformers.transforms
 import me.rerere.rikkahub.data.ai.transformers.visualTransforms
 import me.rerere.rikkahub.data.datastore.Settings
 import me.rerere.rikkahub.data.datastore.findProvider
+import me.rerere.rikkahub.ext.retry.AutoRetryConfig
+import me.rerere.rikkahub.ext.retry.RetryPolicy
 import me.rerere.rikkahub.data.model.Assistant
 import me.rerere.rikkahub.data.model.AssistantMemory
 import java.io.File
@@ -54,8 +58,10 @@ import kotlin.uuid.Uuid
 private const val TAG = "GenerationHandler"
 private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
 private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024
-private const val MAX_PROVIDER_NETWORK_RETRIES = 3
-private const val INITIAL_PROVIDER_RETRY_DELAY_MS = 1_000L
+// [自定义修改] 重试次数/延迟改由 AutoRetryConfig 配置驱动（docs/custom/02-auto-retry.md）
+// [自定义修改] 多 Key 联动：Key 级故障（无效/额度/限流）时的切换重试预算与延迟
+private const val KEY_SWITCH_BUDGET = 8
+private const val KEY_SWITCH_DELAY_MS = 300L
 
 private class StreamChunkHandlingException(cause: Throwable) : RuntimeException(cause)
 
@@ -440,6 +446,8 @@ class GenerationLoop(
                             }
                         }
                         messages = attemptMessages
+                        // [自定义修改] 请求成功：清除该 Key 的健康标记（docs/custom/03-multi-key.md）
+                        KeyRotationPolicy.reportSuccess(provider.id.toString())
                         break
                     } catch (error: Throwable) {
                         if (error is StreamChunkHandlingException) {
@@ -450,6 +458,8 @@ class GenerationLoop(
                             retryCount = retryCount,
                             processingStatus = processingStatus,
                             enabled = settings.networkSetting.enableAutoRetry,
+                            retryConfig = settings.networkSetting.autoRetry,
+                            provider = provider,
                         )
                     }
                 }
@@ -457,6 +467,8 @@ class GenerationLoop(
                 val result = executeProviderRequestWithRetry(
                     processingStatus = processingStatus,
                     enabled = settings.networkSetting.enableAutoRetry,
+                    retryConfig = settings.networkSetting.autoRetry,
+                    provider = provider,
                 ) {
                     providerImpl.generateText(
                         providerSetting = provider,
@@ -475,18 +487,25 @@ class GenerationLoop(
     private suspend fun <T> executeProviderRequestWithRetry(
         processingStatus: MutableStateFlow<String?>,
         enabled: Boolean,
+        retryConfig: AutoRetryConfig = AutoRetryConfig(),
+        provider: ProviderSetting? = null,
         block: suspend () -> T,
     ): T {
         var retryCount = 0
         while (true) {
             try {
-                return block()
+                // [自定义修改] 成功即清除在途 Key 的健康标记（docs/custom/03-multi-key.md）
+                return block().also {
+                    provider?.let { p -> KeyRotationPolicy.reportSuccess(p.id.toString()) }
+                }
             } catch (error: Throwable) {
                 retryCount = awaitNetworkRetryOrThrow(
                     error = error,
                     retryCount = retryCount,
                     processingStatus = processingStatus,
                     enabled = enabled,
+                    retryConfig = retryConfig,
+                    provider = provider,
                 )
             }
         }
@@ -497,32 +516,66 @@ class GenerationLoop(
         retryCount: Int,
         processingStatus: MutableStateFlow<String?>,
         enabled: Boolean,
+        retryConfig: AutoRetryConfig = AutoRetryConfig(),
+        provider: ProviderSetting? = null,
     ): Int {
         // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
         // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
         currentCoroutineContext().ensureActive()
-        if (!enabled || error !is IOException || retryCount >= MAX_PROVIDER_NETWORK_RETRIES) {
+
+        // [自定义修改] 全部 Key 均已停用（无效/无额度）→ 转为可读错误，不再重试。
+        if (error is AllKeysSuspendedException) {
+            throw IllegalStateException(context.getString(R.string.error_all_keys_suspended))
+        }
+
+        // [自定义修改] Key 级故障（无效/额度/限流）先归因到具体 Key：把失败 Key 停用或冷却，
+        // 之后 hasReadyAlternative 才能反映"还有没有别的 Key 可用"。见 docs/custom/03-multi-key.md
+        provider?.let { KeyRotationPolicy.reportFailure(it.id.toString(), error) }
+
+        // [自定义修改] 多 Key 联动：单 Key 失效/无额度时自动切换到下一个可用 Key 继续，
+        // 不受"停止关键词"（余额/额度/invalid key）与自动重试总开关的限制——
+        // 停止关键词的语义是"这个 Key 别再用了"，而不是"整条消息放弃"。
+        val canSwitchKey = provider != null &&
+                KeyRotationPolicy.isKeyLevelError(error) &&
+                KeyRotationPolicy.hasReadyAlternative(provider)
+
+        // [自定义修改] 使用 RetryPolicy 综合判定（HTTP 状态码/重试关键词/停止关键词），
+        // 不再只认 IOException——上游 provider 的 HTTP 失败抛普通 Exception，
+        // 导致 429/5xx 从不触发重试。见 docs/custom/02-auto-retry.md
+        val config = retryConfig.clamped()
+        val shouldRetry = canSwitchKey || (enabled && RetryPolicy.shouldRetry(error, config))
+        val budget = if (canSwitchKey) maxOf(config.maxRetries, KEY_SWITCH_BUDGET) else config.maxRetries
+        if (!shouldRetry || retryCount >= budget) {
             throw error
         }
 
         val nextRetryCount = retryCount + 1
-        val retryDelay = INITIAL_PROVIDER_RETRY_DELAY_MS shl retryCount
+        val retryDelay =
+            if (canSwitchKey) KEY_SWITCH_DELAY_MS else RetryPolicy.backoffDelay(retryCount, config)
         processingStatus.value = context.getString(
-            R.string.chat_generation_network_retrying,
-            getNetworkErrorMessage(error),
+            if (canSwitchKey) R.string.chat_generation_key_switching
+            else R.string.chat_generation_network_retrying,
+            getRetryErrorMessage(error),
             nextRetryCount,
-            MAX_PROVIDER_NETWORK_RETRIES,
+            budget,
         )
         Log.w(
             TAG,
-            "Provider connection failed, retrying in ${retryDelay}ms " +
-                    "($nextRetryCount/$MAX_PROVIDER_NETWORK_RETRIES)",
+            "Provider request failed (${if (canSwitchKey) "switching key" else "retrying"}) " +
+                    "in ${retryDelay}ms ($nextRetryCount/$budget)",
             error,
         )
         delay(retryDelay)
         return nextRetryCount
     }
 
+    // [自定义修改] 非 IOException 的可重试错误（如 HTTP 429/5xx）也需要一句可读的状态提示
+    private fun getRetryErrorMessage(error: Throwable): String {
+        if (error is IOException) return getNetworkErrorMessage(error)
+        return error.message?.lineSequence()?.firstOrNull()?.take(80)
+            ?: error.javaClass.simpleName
+    }
+
     private fun getNetworkErrorMessage(error: IOException): String {
         val messageRes = when (error) {
             is UnknownHostException -> R.string.chat_generation_network_unknown_host
```

### 文件：`app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`（修改）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt b/app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt
index 205e8227..8d6c48fc 100644
--- a/app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt
+++ b/app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt
@@ -580,6 +580,10 @@ data class NetworkSetting(
     val proxyUsername: String = "",
     val proxyPassword: String = "",
     val enableAutoRetry: Boolean = true,
+    // [自定义修改] 高级自动重试配置（docs/custom/02-auto-retry.md）。
+    // enableAutoRetry 保留为总开关；本字段带默认值，旧配置 JSON 反序列化自动兼容。
+    val autoRetry: me.rerere.rikkahub.ext.retry.AutoRetryConfig =
+        me.rerere.rikkahub.ext.retry.AutoRetryConfig(),
 )
 
 @Serializable
```

### 文件：`app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesNetworkPage.kt`（修改）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesNetworkPage.kt b/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesNetworkPage.kt
index 2b18efc2..6f5bb19d 100644
--- a/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesNetworkPage.kt
+++ b/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesNetworkPage.kt
@@ -1,5 +1,6 @@
 package me.rerere.rikkahub.ui.pages.setting
 
+import androidx.compose.foundation.combinedClickable
 import androidx.compose.foundation.layout.Arrangement
 import androidx.compose.foundation.layout.Column
 import androidx.compose.foundation.layout.PaddingValues
@@ -49,6 +50,7 @@ import me.rerere.hugeicons.stroke.ViewOff
 import me.rerere.rikkahub.BuildConfig
 import me.rerere.rikkahub.R
 import me.rerere.rikkahub.data.network.toProxyOrNull
+import me.rerere.rikkahub.ext.retry.AutoRetrySettingsSheet
 import me.rerere.rikkahub.ui.components.nav.BackButton
 import me.rerere.rikkahub.ui.components.ui.CardGroup
 import me.rerere.rikkahub.ui.components.ui.Switch
@@ -85,6 +87,8 @@ fun SettingPreferencesNetworkPage(vm: SettingVM = koinViewModel()) {
     var proxyPasswordDraft by remember { mutableStateOf("") }
     var proxyPasswordVisible by remember { mutableStateOf(false) }
     var proxyDialogVisible by remember { mutableStateOf(false) }
+    // [自定义修改] 高级自动重试配置弹窗（docs/custom/02-auto-retry.md）
+    var showAutoRetrySheet by remember { mutableStateOf(false) }
     val defaultUserAgent = "RikkaHub-Android/${BuildConfig.VERSION_NAME}"
     val proxyUrlInvalid = proxyUrlDraft.isNotBlank() && proxyUrlDraft.toProxyOrNull() == null
     val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
@@ -257,6 +261,14 @@ fun SettingPreferencesNetworkPage(vm: SettingVM = koinViewModel()) {
         )
     }
 
+    // [自定义修改] 高级自动重试配置弹窗（docs/custom/02-auto-retry.md）
+    AutoRetrySettingsSheet(
+        visible = showAutoRetrySheet,
+        onDismissRequest = { showAutoRetrySheet = false },
+        settings = settings,
+        onUpdateSettings = { vm.updateSettings(it) },
+    )
+
     Scaffold(
         topBar = {
             LargeFlexibleTopAppBar(
@@ -287,6 +299,11 @@ fun SettingPreferencesNetworkPage(vm: SettingVM = koinViewModel()) {
                         supportingContent = {
                             Text(stringResource(R.string.setting_page_preferences_network_auto_retry_desc))
                         },
+                        // [自定义修改] 点击/长按整行打开高级重试配置（长按为用户约定的快捷入口）
+                        modifier = Modifier.combinedClickable(
+                            onClick = { showAutoRetrySheet = true },
+                            onLongClick = { showAutoRetrySheet = true },
+                        ),
                         trailingContent = {
                             Switch(
                                 checked = settings.networkSetting.enableAutoRetry,
```
# 3. 功能三：多 Key 模式

## 3.1 数据模型（ai 模块）

**`ProviderApiKeys.kt`（新文件，全部模型与工具函数）：**

```kotlin
@Serializable data class ProviderApiKey(
    val id: Long = ...,        // 时间戳+随机数生成，DataStore 内唯一
    val alias: String = "",    // 显示别名
    val value: String = "",    // Key 明文
    val enabled: Boolean = true, // 用户手动启用/禁用
)
@Serializable enum class ProviderKeyStrategy { RANDOM, ROUND_ROBIN }
```

工具函数（同文件）：
- `splitProviderApiKeys(raw)`：按逗号/空白/换行切分导入文本，去重去空。
- `maskProviderApiKey(value)`：显示掩码（前 6 + **** + 后 4）。
- `normalizedProviderApiKeys(list)`：去空值、按 value 去重。
- `ProviderSetting.isMultiKeyEnabled() / getProviderApiKeys() / getProviderKeyStrategy()`：sealed class 三分支的字段读取扩展（OpenAI/Google/Claude 各有一份同名字段）。
- `activeApiKeyValuesForRequest()`：启用中的 Key 值列表（请求用）。
- `copyWithApiKeyConfig(multiKeyEnabled, apiKeys, keyStrategy)`：三分支统一的字段写入（UI 编辑用）。
- `withSingleApiKeyForRequest(setting, keyValue)`：把选定 Key 写回单 Key 字段生成请求副本——**请求路径完全复用官方单 Key 逻辑，零改动 provider 实现**。
- `syncEnabledApiKeysToLegacyField(setting)`：启用 Key 拼接进官方 `apiKey` 字段（官方 KeyRoulette 按分隔符切分轮询的兜底通道）。

**`ProviderSetting.kt` 三个分支（OpenAI/Google/Claude）各加 3 个字段：**

```kotlin
var multiKeyEnabled: Boolean = false,
var apiKeys: List<ProviderApiKey> = emptyList(),
var keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.RANDOM,
```

带默认值 → 旧 DataStore JSON `ignoreUnknownKeys` 无缝兼容；`convertTo()`（provider 类型转换）需传递这三字段。

## 3.2 Key 健康注册表（`KeyHealth.kt`，失效自动停用的核心）

**状态机：**

| 状态 | 触发 | 时长 | 行为 |
|------|------|------|------|
| `INVALID` 已停用·无效 | 401/403；或文本含 invalid api key/unauthorized/forbidden/密钥无效… | 24h（TTL 自动过期，给自愈机会） | 不参与选 Key |
| `QUOTA` 已停用·额度 | 402；或文本含 quota/insufficient/balance/余额/额度/欠费… | 24h | 不参与选 Key |
| `COOLDOWN` 冷却中 | 429 | 指数：1min → 2 → 4 → … 封顶 30min（`60s × 2^(fails-1)`，fails 连续累计） | 不参与选 Key，到期自动恢复 |
| （无记录）健康 | 成功 / 5xx / 断网等与 Key 无关错误 | - | 正常参与 |

**错误归类优先级**（`classify`）：状态码 401/403 → INVALID；402 → QUOTA；额度文案 → QUOTA（优先于 429 状态码：「429+quota 文案」按额度停用而非冷却）；无效文案 → INVALID；429 → COOLDOWN；其余 → NEUTRAL（不惩罚）。

**存储**：`filesDir/ai_key_health.json`，结构 `Map<providerId, Map<keyValue, KeyHealthRecord>>`；`StateFlow` 暴露给 UI（徽标/可用计数实时刷新）；每次变更同步持久化；读取时惰性清理已到期条目；**重启后停用/冷却仍生效**。

**选 Key 过滤（`KeyRotationPolicy.pickByStrategy` 内）：**
```
ready = filterReady(keys)                       // 无记录或已到期的 Key
pool  = ready 非空 ? ready
      : coolingSorted(keys).take(1)             // 全部冷却 → 选最快恢复的兜底
      : 空 → throw AllKeysSuspendedException    // 全部停用 → 明确报错，绝不空转
picked = RANDOM ? pool.random() : pool[roundRobinIndex % pool.size]
inFlight[providerId] = picked                   // 记录在途 Key 用于归因
```

**归因（请求结束后）：**
- `reportSuccess(providerId)`：清除在途 Key 的健康标记（实测可用，即使曾被停用也当场恢复）。
- `reportFailure(providerId, error)`：`classify` 归因在途 Key → INVALID/QUOTA 停用、COOLDOWN 冷却、NEUTRAL 不动。
- 在途窗口 TTL 5 分钟：超时的陈旧失败不归因，避免误伤。
- ROUND_ROBIN 计数器按 providerId 独立、`AtomicInteger` 环形递增。

## 3.3 官方 KeyRoulette 接线（2 处 3 行）

`DefaultKeyRoulette.next` 与 `LruKeyRoulette.next` 开头各插入：

```kotlin
KeyRotationPolicy.pickByStrategy(keyList, providerId)?.let { return it }
```

未注册策略的 provider（未开多 Key）返回 null → 原逻辑（随机/LRU）原样执行，**单 Key 用户零变化**。

## 3.4 故障切换联动（GenerationLoop.awaitNetworkRetryOrThrow）

```
catch(error):
  0. ensureActive()                          // 用户取消不当网络错误（官方已有）
  1. AllKeysSuspendedException → 抛可读错误「所有 API Key 均已停用，请在 Key 管理器中恢复」
  2. reportFailure(providerId, error)        // 先把失败归因到具体 Key（停用/冷却它）
  3. canSwitchKey = provider 开了多 Key && isKeyLevelError(error) && hasReadyAlternative(provider)
  4. shouldRetry = canSwitchKey || (总开关 && RetryPolicy.shouldRetry(...))
     预算 budget = canSwitchKey ? max(maxRetries, 8) : maxRetries
  5. 延迟 = canSwitchKey ? 300ms : RetryPolicy.backoffDelay(...)
  6. 状态栏 = canSwitchKey ? 「Key 不可用，正在切换下一个 Key」 : 官方重试提示
```

**关键语义**：Key 级故障切换**无视「停止关键词」和自动重试总开关**——停止关键词的语义是"这个 Key 别再用了"，而不是"整条消息放弃"。只要还有可用备选 Key，300ms 内自动换 Key 继续生成，用户几乎无感；全部 Key 停用才报错，绝不空转。

请求成功路径：`KeyRotationPolicy.reportSuccess(provider.id)`（流式与非流式两处）。

## 3.5 生命周期装配

- `RikkaHubApp.onCreate`：`KeyRotationPolicy.init(this)`（加载持久化健康记录）；AppScope collect `settingsFlow` → `KeyRotationPolicy.sync(settings.providers)`（策略注册表跟随设置增删）。
- `sync` 语义：只保留 `multiKeyEnabled` 的 provider 策略，其余移除（关闭开关即回官方行为）。

## 3.6 UI

**入口（`ProviderMultiKeySection.kt`，插入三种 ProviderConfigure 的 apiKey 输入框之后）：**
- 「多 Key 模式」开关行 + 说明。
- 开启后显示「Key 管理器（可用 m/n）」按钮（m = 启用且未停用数，实时扣减）→ 打开管理 Sheet。

**管理 Sheet（`ProviderKeyManagerSheet.kt`，ModalBottomSheet）：**
- 顶部：策略选择（随机/轮询 SegmentedButton）；有停用 Key 时显示「全部恢复」按钮 + 说明文案。
- Key 列表**单行布局**（一行一个 Key，不占两行）：
  `别名+健康徽标 / 掩码 / 开关 / 测试 / 编辑 / 删除（紧凑图标按钮）`
- 健康徽标：`已停用·无效`(错误色) / `已停用·额度`(错误色) / `冷却中·Xm Ys`(三级色，每秒倒计时)；**点击徽标即恢复该 Key**。
- **开关与停用状态联动**：`checked = key.enabled && !suspendedByHealth`（COOLDOWN 不算停用）——健康停用时开关同步显示关闭；**手动打开开关 = clearKeyHealth 恢复**（与点徽标等效）。
- 单 Key 测试：用当前 provider 配置 + 该 Key 发起最小文本请求；测试前清标记，成功 `reportSuccess`、失败 `reportFailure` → **测试结果直接反映为徽标**（测出无效当场变红停用）。需已配置模型，否则提示。
- 添加/编辑对话框：别名 + Key 值 + 明文/掩码切换。
- **粘贴导入**：按逗号/空白/换行批量切分，自动跳过与现有 Key 重复项；**对话框默认空白、绝不自动读取系统剪贴板**（只有用户手动粘贴才有内容；避免误读剪贴板敏感内容、避免触发系统隐私提示）。
- 删除：确认对话框，说明会同时清除该 Key 的健康记录。
- 保存：`copyWithApiKeyConfig` 写回 provider + `syncEnabledApiKeysToLegacyField` 同步官方字段。

## 3.7 新增文件完整代码


### 文件：`ai/src/main/java/me/rerere/ai/provider/ProviderApiKeys.kt`（160 行，新增）

```kotlin
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
```

### 文件：`ai/src/main/java/me/rerere/ai/util/KeyHealth.kt`（226 行，新增）

```kotlin
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
```

### 文件：`app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderMultiKeySection.kt`（108 行，新增）

```kotlin
package me.rerere.rikkahub.ext.keys

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.activeApiKeyValuesForRequest
import me.rerere.ai.provider.enableMultiKeyFromCurrentValue
import me.rerere.ai.provider.copyWithApiKeyConfig
import me.rerere.ai.provider.getProviderApiKeys
import me.rerere.ai.provider.isMultiKeyEnabled
import me.rerere.ai.provider.normalizedProviderApiKeys
import me.rerere.ai.util.KeyRotationPolicy
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Link01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.Switch

/**
 * 提供商配置页的"多 Key 模式"区块（自定义插件层，功能参考 FLIT-feature，
 * 视觉沿用本项目组件，见 docs/custom/03-multi-key.md）。
 *
 * 放置于单 Key 输入框下方：
 * - 开关开启时自动从当前 apiKey 字符串拆分导入 Key 列表；
 * - 开启后显示"管理 Key"入口，打开 [ProviderKeyManagerSheet]。
 */
@Composable
fun ProviderMultiKeySection(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) {
    var showManager by remember { mutableStateOf(false) }
    // [自定义修改] 可用计数 = 启用且未被自动停用（无效/无额度/冷却中）的 Key
    val health by KeyRotationPolicy.healthFlow.collectAsState()
    val now = System.currentTimeMillis()
    val records = health[provider.id.toString()].orEmpty()
    val activeCount = provider.activeApiKeyValuesForRequest().count { value ->
        (records[value]?.until ?: 0L) <= now
    }
    val totalCount = provider.getProviderApiKeys().normalizedProviderApiKeys().size

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.setting_provider_page_multi_key_mode))
            Text(
                text = stringResource(R.string.setting_provider_page_multi_key_mode_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = provider.isMultiKeyEnabled(),
            onCheckedChange = { enabled ->
                onEdit(
                    if (enabled) {
                        provider.enableMultiKeyFromCurrentValue()
                    } else {
                        provider.copyWithApiKeyConfig(multiKeyEnabled = false)
                    }
                )
            },
        )
    }

    AnimatedVisibility(visible = provider.isMultiKeyEnabled()) {
        FilledTonalButton(
            onClick = { showManager = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(HugeIcons.Link01, contentDescription = null)
            Text(
                text = stringResource(
                    R.string.setting_provider_page_multi_key_manager_with_count,
                    activeCount,
                    totalCount,
                ),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    if (showManager) {
        ProviderKeyManagerSheet(
            provider = provider,
            onDismissRequest = { showManager = false },
            onProviderChange = onEdit,
        )
    }
}
```

### 文件：`app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderKeyManagerSheet.kt`（700 行，新增）

```kotlin
package me.rerere.rikkahub.ext.keys

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderApiKey
import me.rerere.ai.provider.ProviderKeyStrategy
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.copyWithApiKeyConfig
import me.rerere.ai.provider.getProviderApiKeys
import me.rerere.ai.provider.getProviderKeyStrategy
import me.rerere.ai.provider.maskProviderApiKey
import me.rerere.ai.provider.normalizedProviderApiKeys
import me.rerere.ai.provider.splitProviderApiKeys
import me.rerere.ai.provider.syncEnabledApiKeysToLegacyField
import me.rerere.ai.provider.withSingleApiKeyForRequest
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyHealthRecord
import me.rerere.ai.util.KeyHealthState
import me.rerere.ai.util.KeyRotationPolicy
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

/**
 * Key 管理器底部弹窗（自定义插件层，功能参考 FLIT-feature ProviderKeyManager，
 * 视觉沿用本项目 Material3 组件，见 docs/custom/03-multi-key.md）。
 *
 * 能力：策略切换（随机/轮询）、添加/粘贴导入、别名、启用/禁用、删除、单 Key 连通测试。
 * 所有变更通过 [onProviderChange] 写回 provider（并同步启用 Key 到旧 apiKey 字段）。
 */
@Composable
fun ProviderKeyManagerSheet(
    provider: ProviderSetting,
    onDismissRequest: () -> Unit,
    onProviderChange: (ProviderSetting) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val keys = provider.getProviderApiKeys().normalizedProviderApiKeys()
    val activeCount = keys.count { it.enabled }
    val testModel = remember(provider.models) {
        provider.models.firstOrNull { it.type == ModelType.CHAT }
    }

    // [自定义修改] Key 健康状态：停用（无效/无额度）/冷却 徽标与恢复入口
    val health by KeyRotationPolicy.healthFlow.collectAsState()
    val providerId = provider.id.toString()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    fun recordFor(key: ProviderApiKey): KeyHealthRecord? {
        val record = health[providerId]?.get(key.value) ?: return null
        return if (record.until > now) record else null
    }

    val hasHealthMarks = keys.any { recordFor(it) != null }
    LaunchedEffect(hasHealthMarks) {
        // 有冷却倒计时时每秒刷新一次剩余时间
        while (hasHealthMarks) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    var editingKey by remember { mutableStateOf<ProviderApiKey?>(null) }
    // [自定义修改] 导入对话框不再自动读取剪贴板，默认空白，由用户手动粘贴
    var showImportDialog by remember { mutableStateOf(false) }
    var deletingKey by remember { mutableStateOf<ProviderApiKey?>(null) }

    fun updateKeys(updatedKeys: List<ProviderApiKey>) {
        onProviderChange(
            provider.copyWithApiKeyConfig(
                multiKeyEnabled = true,
                apiKeys = updatedKeys.normalizedProviderApiKeys(),
            ).syncEnabledApiKeysToLegacyField()
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_multi_key_manager),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(
                            R.string.setting_provider_page_multi_key_summary,
                            activeCount,
                            keys.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hasHealthMarks) {
                    TextButton(
                        onClick = {
                            KeyRotationPolicy.clearProviderHealth(providerId)
                            toaster.show(
                                message = context.getString(
                                    R.string.setting_provider_page_multi_key_health_restored_all
                                ),
                                type = ToastType.Success,
                            )
                        },
                    ) {
                        Icon(HugeIcons.Refresh01, null, Modifier.size(16.dp))
                        Text(
                            text = stringResource(R.string.setting_provider_page_multi_key_restore_all),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }

            if (hasHealthMarks) {
                Text(
                    text = stringResource(R.string.setting_provider_page_multi_key_health_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 轮换策略 ----
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    ProviderKeyStrategy.RANDOM to stringResource(R.string.setting_provider_page_multi_key_strategy_random),
                    ProviderKeyStrategy.ROUND_ROBIN to stringResource(R.string.setting_provider_page_multi_key_strategy_round_robin),
                )
                options.forEachIndexed { index, (strategy, label) ->
                    SegmentedButton(
                        selected = provider.getProviderKeyStrategy() == strategy,
                        onClick = {
                            onProviderChange(provider.copyWithApiKeyConfig(keyStrategy = strategy))
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        label = { Text(label) },
                    )
                }
            }

            // ---- 添加 / 粘贴导入 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { editingKey = ProviderApiKey() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                    Text(
                        text = stringResource(R.string.setting_provider_page_multi_key_add),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                OutlinedButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(HugeIcons.Clipboard, contentDescription = null)
                    Text(
                        text = stringResource(R.string.setting_provider_page_multi_key_import),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            if (testModel == null) {
                Text(
                    text = stringResource(R.string.setting_provider_page_multi_key_test_needs_model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Key 列表 ----
            if (keys.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_multi_key_empty),
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(
                        items = keys,
                        key = { _, key -> key.id.toString() },
                    ) { index, key ->
                        ProviderApiKeyCard(
                            index = index,
                            apiKey = key,
                            provider = provider,
                            testModel = testModel,
                            health = recordFor(key),
                            now = now,
                            onRestoreHealth = {
                                KeyRotationPolicy.clearKeyHealth(providerId, key.value)
                                toaster.show(
                                    message = context.getString(
                                        R.string.setting_provider_page_multi_key_health_restored
                                    ),
                                    type = ToastType.Success,
                                )
                            },
                            onToggle = { enabled ->
                                // [自定义修改] 手动打开开关 = 同时恢复健康停用/冷却标记
                                if (enabled) {
                                    KeyRotationPolicy.clearKeyHealth(providerId, key.value)
                                }
                                updateKeys(keys.map {
                                    if (it.id == key.id) it.copy(enabled = enabled) else it
                                })
                            },
                            onEdit = { editingKey = key },
                            onDelete = { deletingKey = key },
                        )
                    }
                }
            }
        }
    }

    // ---- 编辑/添加对话框 ----
    editingKey?.let { initial ->
        ProviderApiKeyEditDialog(
            initial = initial,
            onDismissRequest = { editingKey = null },
            onConfirm = { edited ->
                val updated = if (keys.any { it.id == edited.id }) {
                    keys.map { if (it.id == edited.id) edited else it }
                } else {
                    keys + edited
                }
                updateKeys(updated)
                editingKey = null
            },
        )
    }

    // ---- 粘贴导入对话框 ----
    if (showImportDialog) {
        ProviderApiKeyImportDialog(
            initialText = "",
            onDismissRequest = { showImportDialog = false },
            onImport = { raw ->
                val existingValues = keys.map { it.value }.toSet()
                val imported = splitProviderApiKeys(raw)
                    .filterNot { it in existingValues }
                    .map { value -> ProviderApiKey(value = value) }
                if (imported.isEmpty()) {
                    toaster.show(
                        message = context.getString(R.string.setting_provider_page_multi_key_import_empty),
                        type = ToastType.Warning,
                    )
                } else {
                    updateKeys(keys + imported)
                    toaster.show(
                        message = context.getString(
                            R.string.setting_provider_page_multi_key_imported,
                            imported.size,
                        ),
                        type = ToastType.Success,
                    )
                    showImportDialog = false
                }
            },
        )
    }

    // ---- 删除确认 ----
    deletingKey?.let { key ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.setting_provider_page_multi_key_delete_title),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                updateKeys(keys.filterNot { it.id == key.id })
                deletingKey = null
            },
            onDismiss = { deletingKey = null },
        ) {
            Text(
                text = stringResource(
                    R.string.setting_provider_page_multi_key_delete_desc,
                    key.alias.ifBlank { maskProviderApiKey(key.value) },
                )
            )
        }
    }
}

@Composable
private fun ProviderApiKeyCard(
    index: Int,
    apiKey: ProviderApiKey,
    provider: ProviderSetting,
    testModel: me.rerere.ai.provider.Model?,
    health: KeyHealthRecord?,
    now: Long,
    onRestoreHealth: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    var testing by remember(apiKey.id) { mutableStateOf(false) }

    fun runTest() {
        val model = testModel ?: return
        scope.launch {
            testing = true
            // 显式测试 = 先恢复该 Key 再实测；结果回写健康状态（失败会自动重新停用/冷却）
            KeyRotationPolicy.clearKeyHealth(provider.id.toString(), apiKey.value)
            val result = runCatching {
                val single = provider.withSingleApiKeyForRequest(apiKey.value)
                val impl = providerManager.getProviderByType(single)
                impl.generateText(
                    providerSetting = single,
                    messages = listOf(UIMessage.user("hello")),
                    params = TextGenerationParams(
                        model = model,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    ),
                )
            }
            result.onSuccess {
                KeyRotationPolicy.reportSuccess(provider.id.toString())
                toaster.show(
                    message = context.getString(R.string.setting_provider_page_multi_key_test_success),
                    type = ToastType.Success,
                )
            }.onFailure { error ->
                KeyRotationPolicy.reportFailure(provider.id.toString(), error)
                toaster.show(
                    message = error.message?.lineSequence()?.firstOrNull()?.take(80)
                        ?: context.getString(R.string.setting_provider_page_multi_key_test_failed),
                    type = ToastType.Error,
                )
            }
            testing = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        // 单行布局：别名+健康徽标 / 掩码 / 开关 / 测试 / 编辑 / 删除
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = apiKey.alias.ifBlank {
                            stringResource(
                                R.string.setting_provider_page_multi_key_default_alias,
                                index + 1,
                            )
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (health != null) {
                        val label = when (health.state) {
                            KeyHealthState.INVALID -> stringResource(
                                R.string.setting_provider_page_multi_key_health_invalid
                            )

                            KeyHealthState.QUOTA -> stringResource(
                                R.string.setting_provider_page_multi_key_health_quota
                            )

                            KeyHealthState.COOLDOWN -> stringResource(
                                R.string.setting_provider_page_multi_key_health_cooldown,
                                formatRemaining(health.until - now),
                            )
                        }
                        Text(
                            text = " · $label",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (health.state == KeyHealthState.COOLDOWN) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            maxLines = 1,
                            // 点击徽标即恢复该 Key
                            modifier = Modifier.clickable { onRestoreHealth() },
                        )
                    }
                }
                Text(
                    text = maskProviderApiKey(apiKey.value),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // [自定义修改] 健康停用（无效/额度）时开关同步显示为关闭，避免“写着停用开关还开着”
            val suspendedByHealth =
                health != null && health.state != KeyHealthState.COOLDOWN
            Switch(
                checked = apiKey.enabled && !suspendedByHealth,
                onCheckedChange = onToggle,
            )
            CompactIconButton(
                onClick = { runTest() },
                enabled = testModel != null && !testing,
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        HugeIcons.Connect,
                        stringResource(R.string.setting_provider_page_multi_key_test),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            CompactIconButton(onClick = onEdit) {
                Icon(
                    HugeIcons.Edit01,
                    stringResource(R.string.common_edit),
                    modifier = Modifier.size(18.dp),
                )
            }
            CompactIconButton(onClick = onDelete) {
                Icon(
                    HugeIcons.Delete01,
                    stringResource(R.string.common_delete),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 38dp 紧凑图标按钮，保证一行能放下 开关+测试+编辑+删除。 */
@Composable
private fun CompactIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(38.dp),
    ) {
        content()
    }
}

/** 冷却剩余时间的紧凑格式：45s / 3m20s / 1h05m。 */
private fun formatRemaining(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds >= 3600 -> "${seconds / 3600}h${(seconds % 3600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m${seconds % 60}s"
        else -> "${seconds}s"
    }
}

@Composable
private fun ProviderApiKeyEditDialog(
    initial: ProviderApiKey,
    onDismissRequest: () -> Unit,
    onConfirm: (ProviderApiKey) -> Unit,
) {
    var value by remember { mutableStateOf(initial.value) }
    var alias by remember { mutableStateOf(initial.alias) }
    var keyVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                stringResource(
                    if (initial.value.isBlank()) {
                        R.string.setting_provider_page_multi_key_add
                    } else {
                        R.string.setting_provider_page_multi_key_edit
                    }
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) HugeIcons.ViewOff else HugeIcons.View,
                                contentDescription = null,
                            )
                        }
                    },
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text(stringResource(R.string.setting_provider_page_multi_key_alias)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(initial.copy(value = value.trim(), alias = alias.trim()))
                },
                enabled = value.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ProviderApiKeyImportDialog(
    initialText: String,
    onDismissRequest: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    val parsedCount = remember(text) { splitProviderApiKeys(text).size }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.setting_provider_page_multi_key_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.setting_provider_page_multi_key_import_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 220.dp),
                    placeholder = {
                        Text(
                            stringResource(R.string.setting_provider_page_multi_key_import_input),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.setting_provider_page_multi_key_import_count, parsedCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }, enabled = parsedCount > 0) {
                Text(stringResource(R.string.setting_provider_page_multi_key_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
```
## 3.8 官方文件修改（diff）


### 文件：`ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt`（修改）

```diff
diff --git a/ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt b/ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt
index 2974c5c1..c056804d 100644
--- a/ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt
+++ b/ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt
@@ -66,6 +66,10 @@ sealed class ProviderSetting {
         var useResponseApi: Boolean = false,
         var includeHistoryReasoning: Boolean = true,
         var responsesPath: String = "/responses",
+        // [自定义修改] 多 Key 模式（docs/custom/03-multi-key.md）
+        var multiKeyEnabled: Boolean = false,
+        var apiKeys: List<ProviderApiKey> = emptyList(),
+        var keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.RANDOM,
     ) : ProviderSetting() {
         override fun addModel(model: Model): ProviderSetting {
             return copy(models = models + model)
@@ -131,6 +135,10 @@ sealed class ProviderSetting {
         var serviceAccountEmail: String = "", // only for vertex AI service account
         var location: String = "us-central1", // only for vertex AI service account
         var projectId: String = "", // only for vertex AI service account
+        // [自定义修改] 多 Key 模式（docs/custom/03-multi-key.md）
+        var multiKeyEnabled: Boolean = false,
+        var apiKeys: List<ProviderApiKey> = emptyList(),
+        var keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.RANDOM,
     ) : ProviderSetting() {
         override fun addModel(model: Model): ProviderSetting {
             return copy(models = models + model)
@@ -192,6 +200,10 @@ sealed class ProviderSetting {
         var baseUrl: String = "https://api.anthropic.com/v1",
         var promptCaching: Boolean = false,
         var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
+        // [自定义修改] 多 Key 模式（docs/custom/03-multi-key.md）
+        var multiKeyEnabled: Boolean = false,
+        var apiKeys: List<ProviderApiKey> = emptyList(),
+        var keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.RANDOM,
     ) : ProviderSetting() {
         override fun addModel(model: Model): ProviderSetting {
             return copy(models = models + model)
```

### 文件：`ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt`（修改）

```diff
diff --git a/ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt b/ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt
index 79c382b9..6df50a8b 100644
--- a/ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt
+++ b/ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt
@@ -3,7 +3,129 @@ package me.rerere.ai.util
 import android.content.Context
 import kotlinx.serialization.encodeToString
 import kotlinx.serialization.json.Json
+import me.rerere.ai.provider.ProviderKeyStrategy
+import me.rerere.ai.provider.ProviderSetting
+import me.rerere.ai.provider.activeApiKeyValuesForRequest
+import me.rerere.ai.provider.getProviderKeyStrategy
+import me.rerere.ai.provider.isMultiKeyEnabled
 import java.io.File
+import java.util.concurrent.ConcurrentHashMap
+import java.util.concurrent.atomic.AtomicInteger
+
+/**
+ * [自定义修改] 多 Key 轮换策略注册表（docs/custom/03-multi-key.md）。
+ *
+ * app 层根据 Settings 同步每个启用了多 Key 的 provider 的策略；
+ * 未注册的 provider 返回 null → 轮盘保持上游原行为（LRU/随机），零回归。
+ *
+ * 同时承担 Key 健康管理门面：
+ * - 选 Key 时跳过已停用（无效/无额度）与冷却中的 Key（[KeyHealthRegistry]）；
+ * - 记录每次选择为"在途 Key"，请求结束后由上层 [reportSuccess]/[reportFailure] 归因；
+ * - 全部 Key 停用时抛 [AllKeysSuspendedException]，由 GenerationLoop 转为可读错误。
+ */
+object KeyRotationPolicy {
+    private val strategies = ConcurrentHashMap<String, ProviderKeyStrategy>()
+    private val roundRobinCounters = ConcurrentHashMap<String, AtomicInteger>()
+
+    /** 在途 Key 归因窗口：超过该时长的失败不再归因（避免陈旧记录误伤）。 */
+    private const val IN_FLIGHT_TTL_MS = 5 * 60_000L
+
+    private class InFlight(val key: String, val at: Long)
+
+    private val inFlight = ConcurrentHashMap<String, InFlight>()
+
+    /** 启动时加载持久化的停用/冷却记录（RikkaHubApp 调用）。 */
+    fun init(context: Context) = KeyHealthRegistry.init(context)
+
+    /** UI 观察健康状态变化（Key 管理器徽标、可用计数）。 */
+    val healthFlow: kotlinx.coroutines.flow.StateFlow<Map<String, Map<String, KeyHealthRecord>>> =
+        KeyHealthRegistry.health
+
+    fun sync(providers: List<ProviderSetting>) {
+        val active = providers
+            .filter { it.isMultiKeyEnabled() }
+            .associate { it.id.toString() to it.getProviderKeyStrategy() }
+        strategies.keys.retainAll(active.keys.toSet())
+        strategies.putAll(active)
+    }
+
+    fun strategyOf(providerId: String): ProviderKeyStrategy? =
+        if (providerId.isEmpty()) null else strategies[providerId]
+
+    internal fun nextRoundRobinIndex(providerId: String): Int {
+        val counter = roundRobinCounters.getOrPut(providerId) { AtomicInteger(0) }
+        return counter.getAndUpdate { if (it == Int.MAX_VALUE) 0 else it + 1 }
+    }
+
+    /** 按注册策略选 Key；返回 null 表示该 provider 未启用多 Key 策略，走原逻辑。 */
+    internal fun pickByStrategy(keys: List<String>, providerId: String): String? {
+        if (keys.isEmpty()) return null
+        val strategy = strategyOf(providerId) ?: return null
+        // [自定义修改] Key 健康过滤：停用（无效/无额度）的 Key 不再参与选择；
+        // 全部冷却时选最快恢复的一个兜底；全部停用时抛出明确异常。
+        val ready = KeyHealthRegistry.filterReady(providerId, keys)
+        val pool = ready.ifEmpty {
+            KeyHealthRegistry.coolingSorted(providerId, keys).take(1).ifEmpty {
+                throw AllKeysSuspendedException(providerId)
+            }
+        }
+        val picked = when (strategy) {
+            ProviderKeyStrategy.RANDOM -> pool.random()
+            ProviderKeyStrategy.ROUND_ROBIN ->
+                pool[Math.floorMod(nextRoundRobinIndex(providerId), pool.size)]
+        }
+        inFlight[providerId] = InFlight(picked, System.currentTimeMillis())
+        return picked
+    }
+
+    /** 请求成功：清除在途 Key 的健康标记（实测可用，即使之前被停用也恢复）。 */
+    fun reportSuccess(providerId: String) {
+        val key = takeInFlight(providerId) ?: return
+        KeyHealthRegistry.clearKey(providerId, key)
+    }
+
+    /** 请求失败：Key 级故障（无效/额度/限流）时停用或冷却在途 Key；其余错误不惩罚。 */
+    fun reportFailure(providerId: String, error: Throwable) {
+        val key = takeInFlight(providerId) ?: return
+        val verdict = KeyHealthRegistry.classify(error)
+        if (verdict == KeyVerdict.NEUTRAL) return
+        KeyHealthRegistry.mark(
+            providerId = providerId,
+            keyValue = key,
+            verdict = verdict,
+            reason = error.message?.lineSequence()?.firstOrNull()?.take(80) ?: "",
+        )
+    }
+
+    private fun takeInFlight(providerId: String): String? {
+        val flight = inFlight.remove(providerId) ?: return null
+        if (System.currentTimeMillis() - flight.at > IN_FLIGHT_TTL_MS) return null
+        return flight.key
+    }
+
+    /** 错误是否属于 Key 级故障（无效/额度/限流）——上层据此决定是否切换 Key 重试。 */
+    fun isKeyLevelError(error: Throwable): Boolean {
+        val verdict = KeyHealthRegistry.classify(error)
+        return verdict == KeyVerdict.INVALID ||
+                verdict == KeyVerdict.QUOTA ||
+                verdict == KeyVerdict.COOLDOWN
+    }
+
+    /** 该 provider（启用多 Key 时）是否还有立即可用的备选 Key。 */
+    fun hasReadyAlternative(provider: ProviderSetting): Boolean {
+        if (!provider.isMultiKeyEnabled()) return false
+        val keys = provider.activeApiKeyValuesForRequest()
+        if (keys.size < 2) return false
+        return KeyHealthRegistry.filterReady(provider.id.toString(), keys).isNotEmpty()
+    }
+
+    /** UI：手动恢复单个 Key。 */
+    fun clearKeyHealth(providerId: String, keyValue: String) =
+        KeyHealthRegistry.clearKey(providerId, keyValue)
+
+    /** UI：恢复该 provider 的全部 Key。 */
+    fun clearProviderHealth(providerId: String) = KeyHealthRegistry.clearProvider(providerId)
+}
 
 interface KeyRoulette {
     fun next(keys: String, providerId: String = ""): String
@@ -32,6 +154,8 @@ private fun splitKey(key: String): List<String> {
 private class DefaultKeyRoulette : KeyRoulette {
     override fun next(keys: String, providerId: String): String {
         val keyList = splitKey(keys)
+        // [自定义修改] 多 Key 模式指定了策略时优先按策略选取
+        KeyRotationPolicy.pickByStrategy(keyList, providerId)?.let { return it }
         return if (keyList.isNotEmpty()) {
             keyList.random()
         } else {
@@ -57,6 +181,9 @@ private class LruKeyRoulette(
         val keyList = splitKey(keys)
         if (keyList.isEmpty()) return keys
 
+        // [自定义修改] 多 Key 模式指定了策略时优先按策略选取（跳过 LRU 记账）
+        KeyRotationPolicy.pickByStrategy(keyList, providerId)?.let { return it }
+
         synchronized(LruFileLock) {
             val now = System.currentTimeMillis()
             val allCache = loadCache().toMutableMap()
```

### 文件：`app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt`（修改）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt b/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt
index 0393d2f3..b30aa29c 100644
--- a/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt
+++ b/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt
@@ -31,8 +31,12 @@ import androidx.compose.ui.unit.dp
 import com.dokar.sonner.ToastType
 import me.rerere.ai.provider.ClaudePromptCacheTtl
 import me.rerere.ai.provider.ProviderSetting
+import me.rerere.ai.provider.getProviderApiKeys
+import me.rerere.ai.provider.getProviderKeyStrategy
+import me.rerere.ai.provider.isMultiKeyEnabled
 import me.rerere.rikkahub.R
 import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
+import me.rerere.rikkahub.ext.keys.ProviderMultiKeySection
 import me.rerere.hugeicons.HugeIcons
 import me.rerere.hugeicons.stroke.View
 import me.rerere.hugeicons.stroke.ViewOff
@@ -100,24 +104,32 @@ fun ProviderSetting.convertTo(type: KClass<out ProviderSetting>): ProviderSettin
     }
     val convertedBaseUrl = sourceBaseUrl.convertToTargetBaseUrl(targetDefaultBaseUrl)
 
+    // [自定义修改] 类型转换时保留多 Key 配置（docs/custom/03-multi-key.md）
+    val multiKeyEnabled = this.isMultiKeyEnabled()
+    val apiKeys = this.getProviderApiKeys()
+    val keyStrategy = this.getProviderKeyStrategy()
+
     return when (type) {
         ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI(
             id = this.id, enabled = this.enabled, name = this.name, models = this.models,
             balanceOption = this.balanceOption, builtIn = this.builtIn,
             description = this.description, shortDescription = this.shortDescription,
-            apiKey = apiKey, baseUrl = convertedBaseUrl
+            apiKey = apiKey, baseUrl = convertedBaseUrl,
+            multiKeyEnabled = multiKeyEnabled, apiKeys = apiKeys, keyStrategy = keyStrategy,
         )
         ProviderSetting.Google::class -> ProviderSetting.Google(
             id = this.id, enabled = this.enabled, name = this.name, models = this.models,
             balanceOption = this.balanceOption, builtIn = this.builtIn,
             description = this.description, shortDescription = this.shortDescription,
-            apiKey = apiKey, baseUrl = convertedBaseUrl
+            apiKey = apiKey, baseUrl = convertedBaseUrl,
+            multiKeyEnabled = multiKeyEnabled, apiKeys = apiKeys, keyStrategy = keyStrategy,
         )
         ProviderSetting.Claude::class -> ProviderSetting.Claude(
             id = this.id, enabled = this.enabled, name = this.name, models = this.models,
             balanceOption = this.balanceOption, builtIn = this.builtIn,
             description = this.description, shortDescription = this.shortDescription,
-            apiKey = apiKey, baseUrl = convertedBaseUrl
+            apiKey = apiKey, baseUrl = convertedBaseUrl,
+            multiKeyEnabled = multiKeyEnabled, apiKeys = apiKeys, keyStrategy = keyStrategy,
         )
         else -> error("Unsupported provider type: $type")
     }
@@ -229,6 +241,12 @@ private fun ProviderConfigureOpenAI(
         },
     )
 
+    // [自定义修改] 多 Key 模式（docs/custom/03-multi-key.md）
+    ProviderMultiKeySection(
+        provider = provider,
+        onEdit = { updated -> onEdit(updated as ProviderSetting.OpenAI) },
+    )
+
     OutlinedTextField(
         value = provider.baseUrl,
         onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
@@ -326,6 +344,12 @@ private fun ProviderConfigureClaude(
         },
     )
 
+    // [自定义修改] 多 Key 模式（docs/custom/03-multi-key.md）
+    ProviderMultiKeySection(
+        provider = provider,
+        onEdit = { updated -> onEdit(updated as ProviderSetting.Claude) },
+    )
+
     OutlinedTextField(
         value = provider.baseUrl,
         onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
@@ -437,6 +461,12 @@ private fun ProviderConfigureGoogle(
                 }
             },
         )
+
+        // [自定义修改] 多 Key 模式（docs/custom/03-multi-key.md）
+        ProviderMultiKeySection(
+            provider = provider,
+            onEdit = { updated -> onEdit(updated as ProviderSetting.Google) },
+        )
     }
 
     if (!provider.vertexAI) {
```
# 4. 字符串资源（en + zh，共 60 个 key）

命名规约：`auto_retry_*`（功能 2，25 个）、`setting_provider_page_multi_key_*`（功能 3，32 个）、
`chat_generation_key_switching` / `error_all_keys_suspended`（功能 3 运行时提示）、`common_edit`（通用）。

**移植方法**：把下面两个 diff 中所有 `+` 行整块追加到新版官方 `strings.xml` 尾部即可
（不与官方既有 key 冲突；`chat_generation_key_switching` 建议放在官方
`chat_generation_network_retrying` 附近，非必须）。注意 `%1$s/%2$d` 占位符与
`formatted="false"` 属性保持原样。


### 文件：`app/src/main/res/values/strings.xml`（修改，英文）

```diff
diff --git a/app/src/main/res/values/strings.xml b/app/src/main/res/values/strings.xml
index 84730d9b..122e1ad5 100644
--- a/app/src/main/res/values/strings.xml
+++ b/app/src/main/res/values/strings.xml
@@ -856,7 +856,7 @@
   <string name="setting_page_preferences_network_proxy_desc">Supports HTTP and SOCKS5 proxy URLs. Leave blank to use system settings.</string>
   <string name="setting_page_preferences_network_proxy_invalid">Enter a valid proxy URL with a host and port.</string>
   <string name="setting_page_preferences_network_auto_retry">Automatic retry</string>
-  <string name="setting_page_preferences_network_auto_retry_desc">Retry model requests up to 3 times when network errors occur.</string>
+  <string name="setting_page_preferences_network_auto_retry_desc">Retry failed model requests (network errors, rate limits, server errors). Long-press for advanced options.</string>
   <string name="setting_page_documentation">Documentation</string>
   <string name="setting_page_documentation_desc">View app usage instructions and help documentation</string>
   <string name="setting_page_donate">Donate</string>
@@ -1326,6 +1326,8 @@
   <string name="setting_provider_page_test_tool_called">Called: %1$s  Args: %2$s</string>
   <string name="setting_provider_page_test_tool_not_called">Tool not called, response: %s</string>
   <string name="chat_generation_network_retrying">%1$s. Retrying (%2$d/%3$d)…</string>
+  <string name="chat_generation_key_switching">Key unavailable (%1$s). Switching to next key (%2$d/%3$d)…</string>
+  <string name="error_all_keys_suspended">All API keys are suspended (invalid or out of quota). Restore them in Provider → Key manager.</string>
   <string name="chat_generation_network_unknown_host">Unable to resolve server address</string>
   <string name="chat_generation_network_timeout">Connection timed out</string>
   <string name="chat_generation_network_unreachable">Unable to connect to server</string>
@@ -1388,4 +1390,63 @@
   <string name="setting_display_page_background_effect_type">Background effect type</string>
   <string name="setting_display_page_background_effect_blur">Blur</string>
   <string name="setting_display_page_background_effect_glass">Glass</string>
+  <!-- ==== Custom plugin strings (docs/custom/) ==== -->
+  <string name="auto_retry_sheet_title">Advanced Auto Retry</string>
+  <string name="auto_retry_sheet_desc">Fine-tune when and how failed requests are retried. Changes apply to new requests.</string>
+  <string name="auto_retry_master_switch_desc">Master switch: retry failed model requests automatically</string>
+  <string name="auto_retry_max_retries">Max retries</string>
+  <string name="auto_retry_initial_delay">Initial delay</string>
+  <string name="auto_retry_multiplier">Backoff multiplier</string>
+  <string name="auto_retry_max_delay">Max delay</string>
+  <string name="auto_retry_jitter">Jitter</string>
+  <string name="auto_retry_jitter_desc">Randomize each delay by ±20 percent to avoid retry storms</string>
+  <string name="auto_retry_on_network_error">Retry on network errors</string>
+  <string name="auto_retry_on_network_error_desc">Transport failures such as timeouts and dropped connections</string>
+  <string name="auto_retry_status_codes">Retry status codes</string>
+  <string name="auto_retry_status_code_hint">e.g. 429</string>
+  <string name="auto_retry_keywords">Retry keywords</string>
+  <string name="auto_retry_keyword_hint">Enter keyword</string>
+  <string name="auto_retry_stop_keywords">Stop keywords</string>
+  <string name="auto_retry_stop_keywords_desc">Never retry when the error contains these (e.g. insufficient balance)</string>
+  <string name="auto_retry_add">Add</string>
+  <string name="auto_retry_reset_defaults">Reset to defaults</string>
+  <string name="auto_retry_reset_all">Reset all</string>
+  <string name="auto_retry_save">Save</string>
+  <string name="auto_retry_saved">Auto retry settings saved</string>
+  <string name="auto_retry_items_count">%1$d items</string>
+  <string name="auto_retry_expand">Expand</string>
+  <string name="auto_retry_collapse">Collapse</string>
+  <string name="common_edit">Edit</string>
+  <string name="setting_provider_page_multi_key_mode">Multi-Key mode</string>
+  <string name="setting_provider_page_multi_key_mode_desc">Manage multiple keys separately and choose which ones are used for requests.</string>
+  <string name="setting_provider_page_multi_key_manager_with_count">Manage Keys (%1$d/%2$d available)</string>
+  <string name="setting_provider_page_multi_key_manager">Key manager</string>
+  <string name="setting_provider_page_multi_key_summary">%1$d/%2$d keys enabled</string>
+  <string name="setting_provider_page_multi_key_strategy_random">Random</string>
+  <string name="setting_provider_page_multi_key_strategy_round_robin">Round robin</string>
+  <string name="setting_provider_page_multi_key_add">Add Key</string>
+  <string name="setting_provider_page_multi_key_import">Paste import</string>
+  <string name="setting_provider_page_multi_key_import_title">Import Keys</string>
+  <string name="setting_provider_page_multi_key_import_desc">Separate keys with commas, spaces or new lines. Duplicates are skipped.</string>
+  <string name="setting_provider_page_multi_key_import_input">Paste keys</string>
+  <string name="setting_provider_page_multi_key_import_count">%1$d key(s) recognized</string>
+  <string name="setting_provider_page_multi_key_imported">Imported %1$d key(s)</string>
+  <string name="setting_provider_page_multi_key_import_empty">No new keys to import</string>
+  <string name="setting_provider_page_multi_key_empty">No keys yet. Add one or paste a list to import.</string>
+  <string name="setting_provider_page_multi_key_default_alias">Key %1$d</string>
+  <string name="setting_provider_page_multi_key_alias">Alias</string>
+  <string name="setting_provider_page_multi_key_edit">Edit Key</string>
+  <string name="setting_provider_page_multi_key_delete_title">Delete Key</string>
+  <string name="setting_provider_page_multi_key_delete_desc">Delete \"%1$s\"? This cannot be undone.</string>
+  <string name="setting_provider_page_multi_key_test">Test</string>
+  <string name="setting_provider_page_multi_key_test_failed">Test failed</string>
+  <string name="setting_provider_page_multi_key_test_success">Key works</string>
+  <string name="setting_provider_page_multi_key_health_invalid">Suspended · invalid</string>
+  <string name="setting_provider_page_multi_key_health_quota">Suspended · quota</string>
+  <string name="setting_provider_page_multi_key_health_cooldown">Cooling down · %1$s</string>
+  <string name="setting_provider_page_multi_key_restore_all">Restore all</string>
+  <string name="setting_provider_page_multi_key_health_restored">Key restored</string>
+  <string name="setting_provider_page_multi_key_health_restored_all">All keys restored</string>
+  <string name="setting_provider_page_multi_key_health_caption">Invalid or out-of-quota keys are suspended automatically (the switch turns off) and skipped. Tap the status or switch it back on to restore a key.</string>
+  <string name="setting_provider_page_multi_key_test_needs_model">Add a chat model first to test individual keys.</string>
 </resources>
```

### 文件：`app/src/main/res/values-zh/strings.xml`（修改，简体中文）

```diff
diff --git a/app/src/main/res/values-zh/strings.xml b/app/src/main/res/values-zh/strings.xml
index 34dae2a0..9dcd48c9 100644
--- a/app/src/main/res/values-zh/strings.xml
+++ b/app/src/main/res/values-zh/strings.xml
@@ -1310,7 +1310,7 @@ mDNS 地址：使用 .local 主机名，同一网络中的其他设备可通过
   <string name="setting_page_preferences_network_proxy_desc">支持 HTTP 和 SOCKS5 代理 URL。留空以使用系统设置。</string>
   <string name="setting_page_preferences_network_proxy_invalid">请输入包含主机和端口的有效代理 URL。</string>
   <string name="setting_page_preferences_network_auto_retry">自动重试</string>
-  <string name="setting_page_preferences_network_auto_retry_desc">发生网络错误时自动重试模型请求，最多重试 3 次。</string>
+  <string name="setting_page_preferences_network_auto_retry_desc">自动重试失败的模型请求（网络错误、限流、服务端错误）。长按可自定义重试策略。</string>
   <string name="setting_provider_page_test_non_streaming">非流式</string>
   <string name="setting_provider_page_test_streaming">流式</string>
   <string name="setting_provider_page_test_tool_call">工具调用</string>
@@ -1320,6 +1320,8 @@ mDNS 地址：使用 .local 主机名，同一网络中的其他设备可通过
   <string name="workspace_terminal_close_confirm_message">关闭此标签页将终止其中仍在运行的任何进程。</string>
   <string name="workspace_terminal_close">关闭</string>
   <string name="chat_generation_network_retrying">%1$s，正在重试（%2$d/%3$d）…</string>
+  <string name="chat_generation_key_switching">Key 不可用（%1$s），正在切换下一个 Key（%2$d/%3$d）…</string>
+  <string name="error_all_keys_suspended">所有 API Key 均已停用（无效或无额度），请在 提供商 → Key 管理器 中恢复</string>
   <string name="chat_generation_network_unknown_host">无法解析服务器地址</string>
   <string name="chat_generation_network_timeout">连接超时</string>
   <string name="chat_generation_network_unreachable">无法连接到服务器</string>
@@ -1384,4 +1386,63 @@ mDNS 地址：使用 .local 主机名，同一网络中的其他设备可通过
   <string name="setting_display_page_background_effect_type">背景效果类型</string>
   <string name="setting_display_page_background_effect_blur">模糊</string>
   <string name="setting_display_page_background_effect_glass">玻璃</string>
+  <!-- ==== 自定义插件字符串（docs/custom/） ==== -->
+  <string name="auto_retry_sheet_title">高级自动重试</string>
+  <string name="auto_retry_sheet_desc">自定义失败请求的重试条件与节奏，保存后对新请求生效。</string>
+  <string name="auto_retry_master_switch_desc">总开关：自动重试失败的模型请求</string>
+  <string name="auto_retry_max_retries">最大重试次数</string>
+  <string name="auto_retry_initial_delay">初始延迟</string>
+  <string name="auto_retry_multiplier">退避倍率</string>
+  <string name="auto_retry_max_delay">最大延迟上限</string>
+  <string name="auto_retry_jitter">随机抖动</string>
+  <string name="auto_retry_jitter_desc">每次延迟随机浮动 ±20%，避免同时重试造成拥塞</string>
+  <string name="auto_retry_on_network_error">网络错误重试</string>
+  <string name="auto_retry_on_network_error_desc">超时、断连等传输层错误</string>
+  <string name="auto_retry_status_codes">重试状态码</string>
+  <string name="auto_retry_status_code_hint">如 429</string>
+  <string name="auto_retry_keywords">重试关键词</string>
+  <string name="auto_retry_keyword_hint">输入关键词</string>
+  <string name="auto_retry_stop_keywords">停止关键词</string>
+  <string name="auto_retry_stop_keywords_desc">错误信息包含这些词时立即失败，不再重试（如余额不足）</string>
+  <string name="auto_retry_add">添加</string>
+  <string name="auto_retry_reset_defaults">恢复默认</string>
+  <string name="auto_retry_reset_all">全部恢复默认</string>
+  <string name="auto_retry_save">保存</string>
+  <string name="auto_retry_saved">已保存自动重试设置</string>
+  <string name="auto_retry_items_count">%1$d 项</string>
+  <string name="auto_retry_expand">展开</string>
+  <string name="auto_retry_collapse">收起</string>
+  <string name="common_edit">编辑</string>
+  <string name="setting_provider_page_multi_key_mode">多 Key 模式</string>
+  <string name="setting_provider_page_multi_key_mode_desc">把多个 Key 分开管理，并选择哪些参与请求。</string>
+  <string name="setting_provider_page_multi_key_manager_with_count">管理 Key（%1$d/%2$d 可用）</string>
+  <string name="setting_provider_page_multi_key_manager">Key 管理器</string>
+  <string name="setting_provider_page_multi_key_summary">已启用 %1$d/%2$d 个 Key</string>
+  <string name="setting_provider_page_multi_key_strategy_random">随机</string>
+  <string name="setting_provider_page_multi_key_strategy_round_robin">轮询</string>
+  <string name="setting_provider_page_multi_key_add">添加 Key</string>
+  <string name="setting_provider_page_multi_key_import">粘贴导入</string>
+  <string name="setting_provider_page_multi_key_import_title">导入 Key</string>
+  <string name="setting_provider_page_multi_key_import_desc">用逗号、空格或换行分隔多个 Key，重复的会自动跳过。</string>
+  <string name="setting_provider_page_multi_key_import_input">粘贴 Key</string>
+  <string name="setting_provider_page_multi_key_import_count">已识别 %1$d 个 Key</string>
+  <string name="setting_provider_page_multi_key_imported">已导入 %1$d 个 Key</string>
+  <string name="setting_provider_page_multi_key_import_empty">没有可导入的新 Key</string>
+  <string name="setting_provider_page_multi_key_empty">暂无 Key，点击添加或粘贴导入。</string>
+  <string name="setting_provider_page_multi_key_default_alias">Key %1$d</string>
+  <string name="setting_provider_page_multi_key_alias">别名</string>
+  <string name="setting_provider_page_multi_key_edit">编辑 Key</string>
+  <string name="setting_provider_page_multi_key_delete_title">删除 Key</string>
+  <string name="setting_provider_page_multi_key_delete_desc">确定删除「%1$s」吗？此操作不可撤销。</string>
+  <string name="setting_provider_page_multi_key_test">测试</string>
+  <string name="setting_provider_page_multi_key_test_failed">测试失败</string>
+  <string name="setting_provider_page_multi_key_test_success">该 Key 可用</string>
+  <string name="setting_provider_page_multi_key_health_invalid">已停用 · 无效</string>
+  <string name="setting_provider_page_multi_key_health_quota">已停用 · 额度</string>
+  <string name="setting_provider_page_multi_key_health_cooldown">冷却中 · %1$s</string>
+  <string name="setting_provider_page_multi_key_restore_all">全部恢复</string>
+  <string name="setting_provider_page_multi_key_health_restored">已恢复该 Key</string>
+  <string name="setting_provider_page_multi_key_health_restored_all">已恢复全部 Key</string>
+  <string name="setting_provider_page_multi_key_health_caption">无效或无额度的 Key 会被自动停用（开关同步关闭）并跳过；点击状态标签或重新打开开关即可恢复。</string>
+  <string name="setting_provider_page_multi_key_test_needs_model">添加聊天模型后，才能测试单个 Key。</string>
 </resources>
```

# 5. 构建脚本变更

## 5.1 版本号

`app/build.gradle.kts`：`versionCode 190`、`versionName "2.5.4-ext1"`（区分官方构建；后续版本递增 ext 号即可）。

## 5.2 Firebase 移除（云端构建无需 google-services.json）

官方构建依赖 `google-services.json`（CI 上没有该文件会失败），且 Crashlytics/Analytics 对自用无意义。移除触点：
- 根 `build.gradle.kts`：删 `google.services`、`firebase.crashlytics` 两行 plugin alias。
- `app/build.gradle.kts`：删两个 plugin alias + `// Firebase` 依赖块（bom/analytics/crashlytics）。
- `gradle/libs.versions.toml`：删 google-services / firebase-bom / firebase-crashlytics 条目。
- `di/AppModule.kt`：删 `Firebase.crashlytics` / `Firebase.analytics` 两个 single 与 imports。
- `di/ViewModelModule.kt`：ChatVM 构造删 `analytics = get(),`。
- `ui/pages/chat/ChatVM.kt`：删 `FirebaseAnalytics` import、构造参数、5 处 `analytics.logEvent(...)`。

> 若未来官方版本改用其他崩溃上报或已可选化 Firebase，本节按新情况裁剪；核心原则是
> **CI 无秘密文件也能编译**。

## 5.3 Release 签名降级保护

`app/build.gradle.kts` release buildType：仅当 `signingConfigs.release.storeFile != null`
（即 local.properties 里 storeFile/storePassword/keyAlias/keyPassword 四项齐全）才挂
`signingConfig`。secret 缺失时产出 unsigned 包而不是构建失败。签名配置从**根目录
local.properties** 读取；CI 上 `storeFile` 应写 `app.key`（密钥文件放在 `app/app.key`，
gradle `file()` 相对 app 模块解析）。

## 5.4 CI（GitHub Actions）要点

`.github/workflows/daily-build.yml`（官方自带，未改动）+ 两个仓库 secret：
- `KEY_BASE64`：签名 keystore（JKS）的 base64 单行文本 → workflow 解码写到 `app/app.key`。
- `SIGNING_CONFIG`：四行 properties → workflow 写入根 `local.properties`：
  ```
  storeFile=app.key
  storePassword=<keystore密码>
  keyAlias=<别名>
  keyPassword=<key密码>
  ```
- 可选 `GOOGLE_SERVICES_JSON`：Firebase 已移除，无需配置。
- 构建产物：`app/build/outputs/apk/release/*.apk` 自动发布到固定 `nightly` tag 的 Release。
- 签名产物验证法（无 apksigner 环境）：产物名不带 `-unsigned` 后缀 + 文件内能 grep 到
  `APK Sig Block 42` 魔数与小端 `1a 87 09 71`（v2 签名块 ID）即为已签名。META-INF 无
  CERT.RSA 属正常（AGP v2-only 签名）。


### 文件：`app/build.gradle.kts`（修改）

```diff
diff --git a/app/build.gradle.kts b/app/build.gradle.kts
index 8e32e63f..8465a608 100644
--- a/app/build.gradle.kts
+++ b/app/build.gradle.kts
@@ -9,8 +9,6 @@ plugins {
     alias(libs.plugins.kotlin.compose)
     alias(libs.plugins.kotlin.serialization)
     alias(libs.plugins.ksp)
-    alias(libs.plugins.google.services)
-    alias(libs.plugins.firebase.crashlytics)
     alias(libs.plugins.baselineprofile)
 }
 
@@ -26,8 +24,8 @@ android {
         applicationId = "me.rerere.rikkahub"
         minSdk = 26
         targetSdk = 37
-        versionCode = 189
-        versionName = "2.5.4"
+        versionCode = 190
+        versionName = "2.5.4-ext1"
 
         testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
 
@@ -75,7 +73,12 @@ android {
 
     buildTypes {
         release {
-            signingConfig = signingConfigs.getByName("release")
+            // [自定义修改] 签名配置缺失（local.properties 无有效条目）时降级为未签名产物，
+            // 而不是让 validateSigningRelease 直接失败（docs/custom/PATCHES.md）
+            val releaseSigningConfig = signingConfigs.getByName("release")
+            if (releaseSigningConfig.storeFile != null) {
+                signingConfig = releaseSigningConfig
+            }
             optimization {
                 enable = true
             }
@@ -179,11 +182,6 @@ dependencies {
     implementation(libs.androidx.lifecycle.viewmodel.navigation3)
     implementation(libs.androidx.material3.adaptive.navigation3)
 
-    // Firebase
-    implementation(platform(libs.firebase.bom))
-    implementation(libs.firebase.analytics)
-    implementation(libs.firebase.crashlytics)
-
     // DataStore
     implementation(libs.androidx.datastore.preferences)
 
```

### 文件：`build.gradle.kts`（修改）

```diff
diff --git a/build.gradle.kts b/build.gradle.kts
index 18433e92..649aeb50 100644
--- a/build.gradle.kts
+++ b/build.gradle.kts
@@ -4,8 +4,6 @@ plugins {
     alias(libs.plugins.kotlin.compose) apply false
     alias(libs.plugins.android.library) apply false
     alias(libs.plugins.ksp) apply false
-    alias(libs.plugins.google.services) apply false
-    alias(libs.plugins.firebase.crashlytics) apply false
     alias(libs.plugins.android.test) apply false
     alias(libs.plugins.baselineprofile) apply false
 }
```

### 文件：`gradle/libs.versions.toml`（修改）

```diff
diff --git a/gradle/libs.versions.toml b/gradle/libs.versions.toml
index 2d874af8..13b8e1f1 100644
--- a/gradle/libs.versions.toml
+++ b/gradle/libs.versions.toml
@@ -35,9 +35,6 @@ paging = "3.5.1"
 lucide-icons = "1.1.0"
 huge-icons = "1.4"
 image-viewer = "1.1.0-alpha.7"
-google-services = "4.5.0"
-firebase-bom = "34.19.0"
-firebase-crashlytics = "3.0.8"
 jsoup = "1.23.2"
 zxing = "3.5.4"
 quickie-bundled = "1.11.0"
@@ -131,9 +128,6 @@ kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.
 lucide-icons = { group = "com.composables", name = "icons-lucide", version.ref = "lucide-icons" }
 huge-icons = { group = "com.github.rikkahub", name = "hugeicons-compose", version.ref = "huge-icons" }
 image-viewer = { group = "com.jvziyaoyao.scale", name = "image-viewer", version.ref = "image-viewer" }
-firebase-bom = { group = "com.google.firebase", name = "firebase-bom", version.ref = "firebase-bom" }
-firebase-analytics = { group = "com.google.firebase", name = "firebase-analytics" }
-firebase-crashlytics = { group = "com.google.firebase", name = "firebase-crashlytics" }
 jsoup = { group = "org.jsoup", name = "jsoup", version.ref = "jsoup" }
 zxing-core = { group = "com.google.zxing", name = "core", version.ref = "zxing" }
 quickie-bundled = { group = "io.github.g00fy2.quickie", name = "quickie-bundled", version.ref = "quickie-bundled" }
@@ -191,7 +185,5 @@ kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "ko
 kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
 android-library = { id = "com.android.library", version.ref = "agp" }
 ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
-google-services = { id = "com.google.gms.google-services", version.ref = "google-services" }
-firebase-crashlytics = { id = "com.google.firebase.crashlytics", version.ref = "firebase-crashlytics" }
 android-test = { id = "com.android.test", version.ref = "agp" }
 baselineprofile = { id = "androidx.baselineprofile", version.ref = "baselineprofile" }
```

### 文件：`app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`（修改）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/di/AppModule.kt b/app/src/main/java/me/rerere/rikkahub/di/AppModule.kt
index cba723d1..acad2d43 100644
--- a/app/src/main/java/me/rerere/rikkahub/di/AppModule.kt
+++ b/app/src/main/java/me/rerere/rikkahub/di/AppModule.kt
@@ -1,8 +1,5 @@
 package me.rerere.rikkahub.di
 
-import com.google.firebase.Firebase
-import com.google.firebase.analytics.analytics
-import com.google.firebase.crashlytics.crashlytics
 import kotlinx.serialization.json.Json
 import me.rerere.rikkahub.AppScope
 import me.rerere.rikkahub.data.ai.tools.local.LocalTools
@@ -50,14 +47,6 @@ val appModule = module {
         TTSManager(get())
     }
 
-    single {
-        Firebase.crashlytics
-    }
-
-    single {
-        Firebase.analytics
-    }
-
     single {
         SoundEffectPlayer(get())
     }
```

### 文件：`app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt`（修改）

```diff
diff --git a/app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt b/app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt
index 7742e19c..ff1bbfa7 100644
--- a/app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt
+++ b/app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt
@@ -33,7 +33,6 @@ val viewModelModule = module {
             conversationRepo = get(),
             chatService = get(),
             updateChecker = get(),
-            analytics = get(),
             filesManager = get(),
             favoriteRepository = get(),
         )
```
# 6. 移植指南（官方发布新版本时的操作步骤）

> 目标：拿到任意新版官方源码后，按本文档在 1~2 小时内完成三大功能移植。
> 原则：先拷新文件（永不冲突），再套官方文件 diff（按锚点），最后合并资源与构建。

## Step 0 准备

1. 下载新版官方源码（zip 或 clone）。
2. 打开本文档第 1.6 / 2.7 / 3.8 / 4 / 5 节的 diff 作为"修改清单"。
3. 全局约定：每处官方文件修改都带 `// [自定义修改]` 注释，完成后
   `grep -rn "\[自定义修改\]" app/ ai/` 的命中数应 ≈ 40+，作为自检。

## Step 1 拷贝 9 个新增文件（无冲突，直接落地）

```
ai/src/main/java/me/rerere/ai/provider/ProviderApiKeys.kt      （功能3 模型）
ai/src/main/java/me/rerere/ai/util/KeyHealth.kt                （功能3 健康注册表）
app/src/main/java/me/rerere/rikkahub/ext/resilience/SafeMessageAccess.kt   （功能1）
app/src/main/java/me/rerere/rikkahub/ext/resilience/StreamDraftSaver.kt    （功能1）
app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetryConfig.kt          （功能2）
app/src/main/java/me/rerere/rikkahub/ext/retry/RetryPolicy.kt              （功能2）
app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetrySettingsSheet.kt   （功能2 UI）
app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderMultiKeySection.kt   （功能3 入口）
app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderKeyManagerSheet.kt   （功能3 管理）
```

完整代码在本文档 1.5 / 2.6 / 3.7 节。拷贝后若编译报未解析引用，多半是官方改了被依赖
API（如 `finishReasoning`、`ConversationRepository`、UiKit 组件名），按报错逐个在新源码
里找等价 API 替换——新文件内部逻辑不需要动。

## Step 2 数据模型（功能 2/3 的地基，先做）

1. `ai/provider/ProviderSetting.kt`：三个分支（OpenAI/Google/Claude）各加 3 个多 Key 字段
   （见 3.1 节 diff）。**注意**：若新版本增加了第 4 种 provider 分支，同样补 3 字段。
2. `data/datastore/PreferencesStore.kt`：`NetworkSetting` 加 `autoRetry: AutoRetryConfig = AutoRetryConfig()`。
3. 检查 `ProviderConfigure.kt` 的 `convertTo()`：若新版本改了 provider 互转逻辑，补三字段传递。

## Step 3 功能 1 接线（按 1.6 节 diff）

顺序：Repository → ChatService → RikkaHubApp → CrashHandler → RouteActivity → UI 安全访问器 → ChatPage/ChatVM。
- `ConversationRepository.kt`：在 messageNodeDao 相关方法附近加 3 个草稿方法。
- `ChatService.kt`：构造注入 `StreamDraftSaver?`；流式 collect 处 `schedule`；结束/取消处 `cancelAndAwait` + NonCancellable 保存；暴露 `flushAllDrafts()`。
- `RikkaHubApp.kt`：装配 saver + CrashHandler 回调（与功能 3 的 init/sync 同文件，一次改完）。
- UI 五件套（Conversation/ChatMessage/ChatMessageBranch/ChatList/ChatPage/ChatVM）：
  全部是把 `messages[selectIndex]` / 直接快照操作替换为安全访问器/服务层 API。
  **上游重构最频繁的区域**——若官方已自行修复越界（新版本可能改进），对应 diff 可跳过，
  仅保留草稿链路。

## Step 4 功能 2 接线（按 2.7 节 diff）

- `GenerationLoop.kt`：替换重试判定（此文件同时含功能 3 的 Key 切换，一次性按 diff 全量套用）。
- `SettingPreferencesNetworkPage.kt`：自动重试行加 `combinedClickable` 长按 → Sheet。

## Step 5 功能 3 接线（按 3.8 节 diff）

- `KeyRoulette.kt`：文件头插入 `KeyRotationPolicy` 对象（~130 行）+ 两个轮盘类各插 1 行策略优先分支。
- `ProviderConfigure.kt`：三种 provider 的 apiKey 输入框后插入 `ProviderMultiKeySection(...)`。
- `RikkaHubApp.kt`：`KeyRotationPolicy.init(this)` + settingsFlow collect → `sync(providers)`。

## Step 6 资源与构建（按 4 / 5 节）

- strings.xml（en + zh）追加 60 个 key（diff 的 `+` 行整块拷贝）。
- 版本号 bump（`-extN`）。
- Firebase：先看新版是否仍强制 google-services——若官方已移除/可选化则跳过 5.2；否则按清单删除。
- 签名降级保护（5.3）照套。
- CI secrets（5.4）已在仓库级配置，无需重做（换仓库才需要）。

## Step 7 编译验证与回归

1. 云端构建（或本地 `./gradlew assembleRelease`）零错误。
2. `grep -rn "\[自定义修改\]"` 盘点触点齐全。
3. 按第 7 节回归清单逐项冒烟。

## 冲突处理速查

| 情况 | 处理 |
|------|------|
| 官方文件函数改名/挪包 | 按 diff 上下文（前后 3 行）在新源码 grep 找等价位置重套 |
| 官方自己修了某个越界/丢失问题 | 对应 diff 跳过，保留其余；草稿四重兜底建议无论如何保留（官方修复通常不覆盖进程被杀场景） |
| `ProviderSetting` 新增第 4 种 provider | 三个扩展函数（isMultiKeyEnabled 等）+ `copyWithApiKeyConfig` + `withSingleApiKeyForRequest` 各补一个分支 |
| `NetworkSetting` 字段重排 | 只要 `autoRetry` 带默认值，位置无所谓 |
| UiKit 组件改名（toaster/extendColors/HugeIcons） | 只影响 3 个 UI 新文件，按编译报错替换为新组件名 |
| DataStore 序列化器变更 | 确认仍是 `Json { ignoreUnknownKeys = true }` 类配置即可，新字段全带默认值 |

# 7. 回归测试清单（每次移植/构建后冒烟）

## 功能 1：消息防丢失

| # | 用例 | 预期 |
|---|------|------|
| 1 | 发起长回复生成，中途 Home 退后台，等 10s，从最近任务划掉进程，重开应用 | 会话中保留退后台前已生成的内容（草稿已 flush），无"思考中"残留动画 |
| 2 | 生成中途直接强杀进程（开发者选项-后台进程限制/adb kill） | 最多丢失最后 2.5s 窗口的内容 |
| 3 | 生成中途切到其他会话再切回、切换消息分支（编辑重发产生的多分支） | 无闪退、无内容错乱、分支指针正确 |
| 4 | 用旧版本数据库升级安装（若有） | selectIndex 非法数据不闪退，显示最接近的合法消息 |
| 5 | 生成完整结束（成功/手动停止/报错） | 终态与官方一致完整落库，FTS 搜索可命中 |

## 功能 2：自动重试

| # | 用例 | 预期 |
|---|------|------|
| 1 | 设置→网络→自动重试行**长按** | 弹出高级配置 Sheet；**点按**仍是总开关 |
| 2 | 配置一个必返回 429 的假地址（或限流 Key）发消息 | 状态栏出现"重试中 (1/3)"类提示并按退避延迟重试（官方版本此场景完全不重试） |
| 3 | 停止关键词命中（如余额不足文案） | 立即失败不重试 |
| 4 | 折叠区展开/收起、状态码 FilterChip 点选、恢复默认、保存后重进 | 配置持久化正确 |
| 5 | 生成中点停止 | 取消不当作网络错误重新拉起 |

## 功能 3：多 Key

| # | 用例 | 预期 |
|---|------|------|
| 1 | Provider 配置页打开多 Key 开关 → Key 管理器添加 2+ 个 Key | 单行布局；入口显示"可用 m/n" |
| 2 | 1 个无效 Key + 1 个有效 Key，发消息 | 状态栏"Key 不可用，正在切换下一个 Key"，最终成功；无效 Key 徽标变红"已停用·无效"且**开关同步变灰关闭**；后续请求不再选中它 |
| 3 | 全部 Key 无效 | 报"所有 API Key 均已停用…"，不空转重试 |
| 4 | 有效但限流(429)的 Key | 橙色"冷却中·倒计时"（每秒刷新），到期自动恢复参与轮换；开关保持打开（冷却≠停用） |
| 5 | 重启应用 | 停用/冷却状态保留（ai_key_health.json） |
| 6 | 点红色徽标 / 点"全部恢复" / **把停用的开关重新打开** | 三种方式都能恢复该 Key，Toast 提示 |
| 7 | 单 Key 测试按钮（无效 Key / 有效 Key） | 测出无效当场变红停用；测通自动恢复 |
| 8 | 粘贴导入 | 打开对话框**输入框为空**、系统不弹"读取剪贴板"提示；手动粘贴多 Key 文本 → 计数正确、重复项跳过 |
| 9 | 策略切换 随机/轮询 | 多次请求观察 Key 掩码轮换符合策略 |
| 10 | 关闭多 Key 开关 | 完全回到官方单 Key 行为 |
| 11 | 单 Key 用户（从未开多 Key） | 所有行为与官方版本零差异 |

## 构建/签名

| # | 用例 | 预期 |
|---|------|------|
| 1 | CI 构建 | 成功；产物名不带 `-unsigned` |
| 2 | APK 签名 | 含 `APK Sig Block 42` 魔数与 v2 块 ID `1a 87 09 71`；手机可直接安装 |
| 3 | secret 缺失演练（可选） | 构建仍成功，产出 unsigned 包（降级保护生效） |

---

# 附录：本文档代码提取基线

- 新增文件完整代码 = `git show a2b2579:<path>` 的原文。
- 官方文件 diff = `git diff 7263dd36 a2b2579 -- <path>` 的原文。
- 如未来需要重新生成本文档：在含完整历史的仓库执行上述命令即可（基线 commit 见文首）。

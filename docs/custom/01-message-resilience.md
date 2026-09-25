# 功能 1：消息防丢失与切换崩溃修复

## 用户报告的现象

1. 切换消息分支（◀ 1/2 ▶）时偶发闪退。
2. 生成过程中退出软件（划掉任务 / 系统杀进程 / 闪退），已生成的内容 100% 丢失。

## 根因分析（基于 2.5.4 源码）

### A. 分支切换用"陈旧快照"整段覆盖会话状态

`ChatPage.kt` 的 `onUpdateMessage` 回调：

```kotlin
onUpdateMessage = { newNode ->
    vm.updateConversation(conversation.copy(messageNodes = ...))  // conversation 是 UI 组合期的旧快照
    vm.saveConversationAsync()
}
```

- `conversation` 是 Compose 组合时捕获的快照。流式生成期间 chunk 每几十毫秒更新一次
  session 状态，用户点击切换分支时快照可能已过期。
- 整段覆盖会把过期快照写回 session（内容回退、节点错位），与
  `GenerationLoop → updateCurrentMessages`（按 index 对齐）竞争后可能产生
  `selectIndex` 悬空、节点列表错乱等非法状态。
- 服务层已有正确的 API：`ChatService.selectMessageNode(conversationId, nodeId, selectIndex)`
  （读取新鲜状态 → 校验索引 → 保存 DB），但 UI 没有使用它。

### B. 裸索引访问导致 Compose 崩溃

- `ChatMessage.kt:121`：`val message = node.messages[node.selectIndex]` — 越界即
  `IndexOutOfBoundsException`，发生在组合期 → 整个应用闪退。
- `Conversation.kt` 的 `MessageNode.currentMessage`：`selectIndex` 非法时主动
  `throw IllegalStateException` — 被 `ChatList`（组合期）、`ChatService` 多处调用。
- `Conversation.currentMessages`：`node.messages[node.selectIndex]` 同样裸索引。
- `ChatService.checkInvalidMessages`：`selectIndex = node.selectIndex - 1` 可产生 -1
  （后续虽有 clamp，但依赖执行顺序，脆弱）。

任何来源的非法状态（A 的竞争、旧版本 DB 数据、恢复的备份）都会在渲染时直接崩溃。

### C. 流式内容只存内存，进程死亡即全丢

- 流式 chunk 仅调用 `updateConversation`（内存 StateFlow）。
- 落库只发生在 `onCompletion → finishGeneration → saveConversation`（正常结束/取消时）。
- **进程被杀（划掉任务且 ROM 杀死 FGS、系统内存回收）或应用崩溃时，`onCompletion`
  不会执行** → 本次生成内容全部丢失。既有 `CrashHandler` 只写崩溃标记，不保存会话。

## 修复方案

### 1. 索引安全访问（防御层）

新文件 `ext/resilience/SafeMessageAccess.kt`：

- `MessageNode.clampedSelectIndex`：`selectIndex.coerceIn(0, messages.lastIndex)`。
- `MessageNode.safeCurrentMessage`：clamp 后取值；空节点返回 null。
- `Conversation.safeCurrentMessages`：跳过空节点、clamp 索引。

官方文件小改（详见 PATCHES.md）：

- `Conversation.kt`：`currentMessages` 与 `MessageNode.currentMessage` 改为 clamp 语义
  （空节点仍抛错，但 UI 层已先行过滤，正常不可达）。
- `ChatMessage.kt`：改用 clamp 取值。
- `ChatList.kt`：渲染前跳过 `messages.isEmpty()` 的节点；导出/搜索路径改用安全访问器。
- `ChatService.checkInvalidMessages`：`selectIndex - 1` 改为 clamp。

### 2. 分支切换走服务层安全路径（根因修复）

- `ChatPage.onUpdateMessage` 改为调用 `vm.selectMessageNode(newNode.id, newNode.selectIndex)`。
- `ChatVM` 新增 `selectMessageNode(nodeId, selectIndex)`：调用既有
  `ChatService.selectMessageNode`（新鲜读取 + 校验 + 落库），异常吞掉并转错误卡片。

效果：切换分支不再可能用旧快照覆盖流式状态，且切换结果即时持久化。

### 3. 流式草稿落库（防丢失核心）

新文件 `ext/resilience/StreamDraftSaver.kt`：

- 生成期间以 **2.5s 节流合并**（trailing edge）把 session 当前状态写入 DB。
- 轻量写入路径：只 upsert 变更节点行（`MessageNodeDAO.insert` 本身就是
  `OnConflictStrategy.REPLACE`），**跳过 FTS 索引**（生成结束时既有的全量
  `saveConversation` 会补索引）。
- 落库前对最后一条消息执行 `finishReasoning()`，进程死亡后重启不会出现
  "思考中"卡死动画。
- `ConversationRepository` 新增 additive 方法 `upsertMessageNodeDraft(conversationId, node, index)`。

挂接点（`ChatService.handleMessageComplete` 的 collect 内）：

```kotlin
is GenerationChunk.Messages -> {
    ...
    updateConversation(conversationId, updatedConversation)
    draftSaver.schedule(conversationId, updatedConversation)   // ← 新增一行
    ...
}
```

生成结束（onCompletion/失败/取消）时 `draftSaver.cancel(conversationId)`，
既有 `finishGeneration` 全量落库保持不变。

### 4. 崩溃应急保存（兜底层）

- `StreamDraftSaver` 暴露 `flushNowBlocking(timeoutMs)`：同步把指定会话/所有活跃会话
  的当前状态写库。
- `CrashHandler.install` 增加 `onCrash` 回调参数：崩溃线程里 `runBlocking`（限时
  1500ms）执行 flush，然后再走系统默认 handler。
- `RikkaHubApp` 安装时注入回调：从 Koin 取 `ChatService`，flush 所有生成中的会话。

## 防护矩阵

| 场景 | 修复前 | 修复后 |
|------|--------|--------|
| 生成中划掉任务/ROM 杀进程 | 全丢 | 最多丢 ~2.5s 增量 |
| 生成中应用崩溃 | 全丢 + 可能连锁损坏 | 应急 flush 保住内容；渲染层不再因非法索引崩溃 |
| 切换分支（生成中/结束后） | 旧快照覆盖、可能闪退 | 服务层新鲜读取 + 即时落库 |
| 旧 DB/备份中的非法 selectIndex | 渲染即崩 | clamp 兜底正常显示 |

## 验证

1. 发送长回复请求，生成中划掉任务 → 重开应用 → 内容保留到最后 ~2.5s。
2. 生成中反复快速点击分支切换 ◀▶ → 无闪退、内容不回退、重启后 selectIndex 保留。
3. 生成中 `adb shell am kill <pkg>`（或开发者选项"不保留活动"+切后台）→ 重开内容保留。
4. 构造非法 selectIndex 数据（备份注入）→ 打开会话不崩溃，正常渲染 clamp 后的消息。

## 第二轮打磨（2026-09-25）：后台/退出兜底 flush

**问题**：原方案在崩溃（CrashHandler）和 cleanup 时 flush，但应用被系统从后台回收、
或用户直接划掉任务却未触发 cleanup 时，仍可能丢失最后一个节流窗口（~2.5s）的内容。

**改进**：
- `ChatService.flushAllDrafts()`：非阻塞版 flush（appScope 异步），供生命周期回调调用。
- `RouteActivity.onStop()`：应用退到后台即 `getOrNull<ChatService>()?.flushAllDrafts()`，
  把在途草稿立即落盘。配合既有 CrashHandler（崩溃）、cleanup（正常退出）形成三重兜底：
  **前台被杀** ≤ 一个节流窗口；**退后台被杀** 0 丢失；**崩溃** 尽力抢救。
- `getOrNull` 语义：ChatService 未创建说明没有生成中的会话，无需 flush，也不会误初始化依赖图。

**验证补充**：
- 生成中按 Home 退后台 → `am kill` → 重开：内容保留到退后台那一刻（0 丢失）。
- 生成中划掉最近任务 → 重开：内容保留（onStop 已 flush）。

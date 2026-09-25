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

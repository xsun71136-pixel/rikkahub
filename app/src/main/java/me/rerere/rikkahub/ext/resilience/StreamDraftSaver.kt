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

package me.rerere.rikkahub.ext.resilience

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val DRAFT_INTERVAL_MS = 2_500L

/**
 * Periodic snapshots, not debounce: a continuous stream is still persisted.
 * See docs/custom/2026-09-three-features-plan.md. Hard kills/disk failures are not
 * guaranteed recoverable; emergency flushing is best effort only.
 */
class StreamDraftSaver internal constructor(
    private val scope: CoroutineScope,
    private val writeDraft: suspend (Uuid, List<MessageNode>, List<MessageNode>?) -> Unit,
    private val intervalMs: Long = DRAFT_INTERVAL_MS,
) {
    constructor(scope: CoroutineScope, repository: ConversationRepository) :
        this(scope, repository::writeMessageNodesDraft)

    companion object {
        @Volatile private var active: StreamDraftSaver? = null
        fun flushActiveBlocking(timeoutMs: Long = 1_500L) {
            active?.flushAllBlocking(timeoutMs)
        }
        fun flushActive() {
            active?.let { saver -> saver.scope.launch(Dispatchers.IO) { saver.flushAll() } }
        }
    }

    private val pending = ConcurrentHashMap<Uuid, Conversation>()
    private val lastDraft = ConcurrentHashMap<Uuid, List<MessageNode>>()
    // Bounded lock table: never remove a lock while another writer could hold it.
    private val locks = Array(64) { Mutex() }
    private val workerGuard = Any()
    private var worker: Job? = null

    init { active = this }

    private fun lockFor(id: Uuid) = locks[Math.floorMod(id.hashCode(), locks.size)]

    fun schedule(conversationId: Uuid, conversation: Conversation) {
        synchronized(workerGuard) {
            pending[conversationId] = conversation
            if (worker != null) return
            // Install before start, including for immediate dispatchers.
            worker = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                while (true) {
                    delay(intervalMs)
                    flushAll()
                    synchronized(workerGuard) {
                        if (pending.isEmpty()) {
                            worker = null
                            return@launch
                        }
                    }
                }
            }
            worker?.start()
        }
    }

    /** Serialize ALL ordinary/terminal saves with drafts; keep pending on failure. */
    suspend fun persist(
        conversationId: Uuid,
        latest: () -> Conversation,
        save: suspend (Conversation) -> Unit,
    ) {
        lockFor(conversationId).withLock {
            // Read after acquiring the lock; a chunk may have arrived while waiting.
            val snapshot = latest()
            schedule(conversationId, snapshot)
            save(snapshot)
            lastDraft.remove(conversationId)
            removeIfSame(conversationId, snapshot)
        }
    }

    suspend fun flush(conversationId: Uuid) {
        lockFor(conversationId).withLock {
            val conversation = pending[conversationId] ?: return
            val nodes = conversation.messageNodes
            // Only the persisted copy is finalized. The live stream is unchanged.
            val draftNodes = nodes.map { node ->
                val finished = node.messages.map { it.finishReasoning() }
                if (finished == node.messages) node else node.copy(messages = finished)
            }
            writeDraft(conversationId, draftNodes, lastDraft[conversationId])
            lastDraft[conversationId] = nodes
            removeIfSame(conversationId, conversation)
        }
    }

    private fun removeIfSame(id: Uuid, snapshot: Conversation) {
        pending.computeIfPresent(id) { _, current -> if (current === snapshot) null else current }
    }

    suspend fun flushAll() {
        for (id in pending.keys.toList()) {
            try {
                flush(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("StreamDraftSaver", "Unable to persist draft $id; retaining pending snapshot", error)
            }
        }
    }

    fun flushAllBlocking(timeoutMs: Long = 1_500L) {
        if (pending.isEmpty()) return
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(timeoutMs) { flushAll() }
            }
        }.onFailure { Log.w("StreamDraftSaver", "Emergency flush failed", it) }
    }
}

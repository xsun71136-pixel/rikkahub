package me.rerere.rikkahub.ext.resilience

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class StreamDraftSaverTest {
    private fun snapshot(id: Uuid, text: String) = Conversation.ofId(
        id, messages = listOf(MessageNode(messages = listOf(UIMessage.user(text)))),
    )

    @Test fun chunkArrivingDuringWriteIsNotDiscarded() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val written = mutableListOf<String>()
            val saver = StreamDraftSaver(scope, { _, nodes, _ ->
                if (written.isEmpty()) { entered.complete(Unit); release.await() }
                written += nodes.single().currentMessage.toText()
            }, 60_000L)
            val id = Uuid.random()
            saver.schedule(id, snapshot(id, "first"))
            val writing = launch { saver.flush(id) }
            entered.await()
            saver.schedule(id, snapshot(id, "newest"))
            release.complete(Unit)
            writing.join()
            saver.flush(id)
            assertEquals(listOf("first", "newest"), written)
        } finally { scope.cancel() }
    }

    @Test fun terminalSaveCannotBeOverwrittenByAnOlderDraft() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val written = mutableListOf<String>()
            val saver = StreamDraftSaver(scope, { _, nodes, _ ->
                entered.complete(Unit)
                release.await()
                written += nodes.single().currentMessage.toText()
            }, 60_000L)
            val id = Uuid.random()
            saver.schedule(id, snapshot(id, "partial"))
            val writing = launch { saver.flush(id) }
            entered.await()
            val saving = launch { saver.persist(id, { snapshot(id, "final") }) { written += it.currentMessages.single().toText() } }
            release.complete(Unit)
            writing.join()
            saving.join()
            saver.flush(id)
            assertEquals(listOf("partial", "final"), written)
        } finally { scope.cancel() }
    }

    @Test fun failedWriteRetainsPendingSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var attempts = 0
            val saver = StreamDraftSaver(scope, { _, _, _ ->
                attempts++
                if (attempts == 1) throw IllegalStateException("simulated disk failure")
            }, 60_000L)
            val id = Uuid.random()
            saver.schedule(id, snapshot(id, "recoverable"))
            try { saver.flush(id); fail("Expected failure") } catch (_: IllegalStateException) { }
            saver.flush(id)
            saver.flush(id)
            assertEquals(2, attempts)
        } finally { scope.cancel() }
    }
}

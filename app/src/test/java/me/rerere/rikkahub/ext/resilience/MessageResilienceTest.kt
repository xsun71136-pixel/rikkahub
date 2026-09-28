package me.rerere.rikkahub.ext.resilience

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class MessageResilienceTest {
    @Test fun invalidBranchClampsInsteadOfCrashing() {
        val first = UIMessage.user("first")
        val last = UIMessage.user("last")
        val node = MessageNode(messages = listOf(first, last), selectIndex = 100)
        assertEquals(last, node.currentMessage)
        assertEquals(first, node.copy(selectIndex = -1).currentMessage)
        assertNull(node.copy(messages = emptyList()).safeCurrentMessage)
    }

    @Test fun skippedEmptyNodeDoesNotShiftStreamingUpdate() {
        val message = UIMessage.user("keep me")
        val node = MessageNode(messages = listOf(message), selectIndex = -100)
        val conversation = Conversation.ofId(Uuid.random(), messages = listOf(MessageNode(messages = emptyList()), node))
        assertEquals(listOf(message), conversation.currentMessages)
        val updated = conversation.updateCurrentMessages(conversation.currentMessages)
        assertEquals(1, updated.messageNodes.size)
        assertEquals(node.id, updated.messageNodes.single().id)
        assertEquals(0, updated.messageNodes.single().selectIndex)
        assertEquals(message, updated.currentMessages.single())
    }
}

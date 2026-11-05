package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class TitleWriteGuardR20Test {
    private fun conversation() = Conversation.ofId(Uuid.random(), assistantId = Uuid.random())
        .copy(messageNodes = listOf(UIMessage.user("current branch").toMessageNode())).withManualTitle("manual")
    private fun guard(c: Conversation) = TitleWriteGuard(c.title, c.titleState.source,
        c.currentMessages.titleBranchAnchor(), force = true, revision = c.titleState.revision)
    @Test fun `forced regenerate may replace the captured manual title`() {
        val c = conversation()
        assertTrue(guard(c).allows(c))
        assertFalse(c.withForcedTitle("replacement", "fp").titleState.isAuto)
    }
    @Test fun `late forced regenerate cannot overwrite a later rename`() {
        val c = conversation()
        assertFalse(guard(c).allows(c.withManualTitle("new manual")))
    }
    @Test fun `same text manual rename still invalidates the forced request`() {
        val c = conversation()
        assertFalse(guard(c).allows(c.withManualTitle(c.title)))
    }
    @Test fun `forced regenerate cannot land on a different branch`() {
        val c = conversation()
        assertFalse(guard(c).allows(c.copy(messageNodes = listOf(UIMessage.user("other branch").toMessageNode()))))
    }
}

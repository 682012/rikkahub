package me.rerere.rikkahub.service

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.NetworkRouteChangedException
import me.rerere.ai.provider.NetworkUnavailableException
import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.WebSessionException
import me.rerere.ai.provider.stream.StreamTruncatedException
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.rikkahub.data.workspace.WorkspacePrivilegeException
import me.rerere.rikkahub.data.workspace.WorkspacePrivilegeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ChatAutoRecoveryTest {
    // ---- 能自动接上的失败 -------------------------------------------------------

    @Test
    fun `transport and half stream failures stay recoverable`() {
        listOf(
            IOException("timeout"),
            SocketTimeoutException("read timed out"),
            StreamTruncatedException("stream closed before finish_reason"),
            NetworkUnavailableException(),
            NetworkRouteChangedException(epoch = 3),
        ).forEach { error ->
            assertNull(error.toString(), ChatAutoRecovery.blockReason(error))
            assertTrue(ChatAutoRecovery.mayAutoRecover(error))
        }
    }

    @Test
    fun `server side and rate limit failures keep the existing retry budget`() {
        // 408 / 429 / 5xx 仍然交给 RecoveryPolicy 的 SERVER：按 retry.maxRetries 限次，
        // 自动恢复只负责把整轮次数再夹一遍，不在这里另立一套
        listOf(408, 429, 500, 503).forEach { status ->
            assertNull("status=$status", ChatAutoRecovery.blockReason(IOException("io"), httpStatus = status))
        }
    }

    @Test
    fun `real HTTP response codes are picked up without a caller hint`() {
        listOf(408, 429, 500, 503, 599).forEach { status ->
            assertTrue("status=$status", ChatAutoRecovery.mayAutoRecover(HttpException("temporary", status)))
        }
        assertEquals(ChatRecoveryBlocker.AUTH,
            ChatAutoRecovery.blockReason(HttpException("unauthorized", 401), httpStatus = 503))
        assertEquals(ChatRecoveryBlocker.QUOTA,
            ChatAutoRecovery.blockReason(HttpException("insufficient_quota", 429)))
        assertEquals(ChatRecoveryBlocker.REQUEST_FORMAT,
            ChatAutoRecovery.blockReason(HttpException("invalid", 422)))
        assertFalse(ChatAutoRecovery.mayAutoRecover(HttpException("text mentions 503 without a response code")))
    }

    @Test
    fun `recovery preserves older regeneration variants before and after first output`() {
        val old = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("old answer")))
        val node = Uuid.random()
        val beforeOutput = ChatReconnectTicket(1, 1, null, node, null,
            failedTail = old, regenerateNodeId = node, previousSelectIndex = 1)
        assertEquals(listOf(old), beforeOutput.restoreReplyVariants(listOf(old)))
        assertEquals(node, beforeOutput.retryRegenerateNodeId)
        assertEquals(1, beforeOutput.restoredSelectIndex(current = 3, variants = 3))
        val partial = old.copy(id = Uuid.random(), parts = listOf(UIMessagePart.Text("partial")))
        val afterOutput = beforeOutput.copy(interruptedMessageId = partial.id, failedTail = partial)
        assertEquals(listOf(old), afterOutput.restoreReplyVariants(listOf(old, partial)))
        val completed = partial.copy(parts = listOf(UIMessagePart.Text("finished step")))
        val checkpointed = afterOutput.copy(checkpoint = completed)
        assertEquals(listOf(old, completed), checkpointed.restoreReplyVariants(listOf(old, partial)))
        assertNull(checkpointed.retryRegenerateNodeId)
        assertEquals(3, checkpointed.restoredSelectIndex(current = 3, variants = 4))
    }

    @Test
    fun `partial generated media cannot be billed again by automatic replay`() {
        val checkpoint = ChatReconnectCheckpoint()
        val pending = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Image("file:///generated.png")))
        checkpoint.observe(pending)
        assertFalse(checkpoint.canResume)
        checkpoint.observe(pending.copy(finishedAt = LocalDateTime(2026, 11, 5, 0, 0, 1)))
        assertTrue(checkpoint.canResume)
        assertEquals(pending.parts, checkpoint.message?.parts)
    }

    // ---- 不能自动接上的失败 -----------------------------------------------------

    @Test
    fun `user stop and pending approval are never recovered`() {
        assertEquals(
            ChatRecoveryBlocker.USER_STOPPED,
            ChatAutoRecovery.blockReason(IOException("canceled"), cancelled = true),
        )
        assertEquals(
            ChatRecoveryBlocker.USER_STOPPED,
            ChatAutoRecovery.blockReason(CancellationException("stopped")),
        )
        assertEquals(
            ChatRecoveryBlocker.APPROVAL_PENDING,
            ChatAutoRecovery.blockReason(IOException("io"), awaitingApproval = true),
        )
    }

    @Test
    fun `auth format and quota failures are not retried`() {
        assertEquals(ChatRecoveryBlocker.AUTH, ChatAutoRecovery.blockReason(WebSessionException("session expired")))
        // WebProtocolException 是 IOException，但它是协议/格式违规，重发结果一样
        assertEquals(
            ChatRecoveryBlocker.REQUEST_FORMAT,
            ChatAutoRecovery.blockReason(WebProtocolException("response was not JSON")),
        )
        assertEquals(ChatRecoveryBlocker.REQUEST_FORMAT, ChatAutoRecovery.blockReason(HttpException("bad request")))
        assertEquals(ChatRecoveryBlocker.AUTH, ChatAutoRecovery.blockReason(IOException("io"), httpStatus = 401))
        assertEquals(ChatRecoveryBlocker.AUTH, ChatAutoRecovery.blockReason(IOException("io"), httpStatus = 403))
        assertEquals(ChatRecoveryBlocker.QUOTA, ChatAutoRecovery.blockReason(IOException("io"), httpStatus = 402))
        assertEquals(ChatRecoveryBlocker.REQUEST_FORMAT, ChatAutoRecovery.blockReason(IOException("io"), httpStatus = 400))
        assertEquals(ChatRecoveryBlocker.REQUEST_FORMAT, ChatAutoRecovery.blockReason(IOException("io"), httpStatus = 422))
        // 鉴权失败即使长得像 IO 也不能建 ticket
        assertFalse(ChatAutoRecovery.mayAutoRecover(WebProtocolException("login required")))
        assertFalse(ChatAutoRecovery.mayAutoRecover(IOException("io"), httpStatus = 401))
    }

    @Test
    fun `permission and local storage failures are not retried`() {
        assertEquals(
            ChatRecoveryBlocker.PERMISSION,
            ChatAutoRecovery.blockReason(
                WorkspacePrivilegeException(WorkspacePrivilegeFailure.SHIZUKU_PERMISSION_DENIED, "denied")
            ),
        )
        assertEquals(ChatRecoveryBlocker.STORAGE, ChatAutoRecovery.blockReason(InboxPersistenceException("disk full")))
        assertEquals(ChatRecoveryBlocker.STORAGE, ChatAutoRecovery.blockReason(FileNotFoundException("gone")))
    }

    @Test
    fun `unknown side effects block replay even for a plain network error`() {
        assertEquals(
            ChatRecoveryBlocker.UNKNOWN_SIDE_EFFECT,
            ChatAutoRecovery.blockReason(IOException("timeout"), canResume = false),
        )
        assertEquals(
            ChatRecoveryBlocker.UNKNOWN_SIDE_EFFECT,
            ChatAutoRecovery.blockReason(IOException("timeout"), hasCommittedSideEffects = true),
        )
    }

    @Test
    fun `a started tool stops the resume until its result is recorded`() {
        val tool = UIMessagePart.Tool("id", "write_file", "{}", startedAt = Instant.fromEpochSeconds(1))
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(tool),
            finishedAt = LocalDateTime(2026, 11, 5, 0, 0, 1),
        )
        val checkpoint = ChatReconnectCheckpoint()
        checkpoint.observe(message)
        assertFalse(checkpoint.canResume)
        // 工具已开始但结果未知：网络回来也不许重放这一步
        assertFalse(
            ChatAutoRecovery.mayAutoRecover(
                IOException("disconnected"),
                canResume = checkpoint.canResume,
                hasCommittedSideEffects = true,
            )
        )

        val recorded = message.copy(parts = listOf(tool.copy(output = listOf(UIMessagePart.Text("已写入")))))
        checkpoint.observe(recorded)
        assertTrue(checkpoint.canResume)
        // 结果已经落库，之后断在一段纯文本上就可以续跑
        assertTrue(ChatAutoRecovery.mayAutoRecover(IOException("disconnected"), canResume = checkpoint.canResume))
    }

    // ---- 每会话次数与过期清理 ---------------------------------------------------

    @Test
    fun `each conversation gets a bounded number of attempts`() {
        val ledger = ChatRecoveryLedger(maxAttempts = 3)
        val id = Uuid.random()
        repeat(3) { assertTrue("attempt ${it + 1}", ledger.noteFailure(id)) }
        assertEquals(3, ledger.attempts(id))
        // 第 4 次失败不再建 ticket：这一轮已经用满，只能报给用户
        assertFalse(ledger.noteFailure(id))
        // 第 3 次留下的 ticket 仍然有效，网络回来还能把这轮跑完
        assertTrue(ledger.resumeAllowed(id))

        ledger.clear(id)
        assertFalse(ledger.resumeAllowed(id))
        assertTrue(ledger.noteFailure(id))
    }

    @Test
    fun `budget is per conversation and refilled after clear`() {
        val ledger = ChatRecoveryLedger(maxAttempts = 2)
        val a = Uuid.random()
        val b = Uuid.random()
        assertTrue(ledger.noteFailure(a))
        assertTrue(ledger.noteFailure(a))
        assertFalse(ledger.noteFailure(a))
        // 别的会话不受影响
        assertTrue(ledger.noteFailure(b))
        assertEquals(0, ledger.attempts(Uuid.random()))

        // 成功/停止/发新消息之后重新给满额度
        ledger.clear(a)
        assertEquals(0, ledger.attempts(a))
        assertTrue(ledger.noteFailure(a))
    }

    @Test
    fun `ticket goes stale after its ttl`() {
        var now = 1_000_000L
        val ledger = ChatRecoveryLedger(maxAttempts = 3, ticketTtlMillis = 60_000L, clock = { now })
        val id = Uuid.random()
        assertTrue(ledger.noteFailure(id))
        assertTrue(ledger.resumeAllowed(id))
        assertFalse(ledger.isStale(id))

        now += 60_001L
        assertTrue(ledger.isStale(id))
        assertFalse(ledger.resumeAllowed(id))
        // 过期不等于次数用完：这次的 ticket 只是不该再续跑
        assertEquals(1, ledger.attempts(id))
    }

    @Test
    fun `sweep removes only expired conversations`() {
        var now = 1_000_000L
        val ledger = ChatRecoveryLedger(maxAttempts = 3, ticketTtlMillis = 60_000L, clock = { now })
        val old = Uuid.random()
        assertTrue(ledger.noteFailure(old))
        now += 30_000L
        val fresh = Uuid.random()
        assertTrue(ledger.noteFailure(fresh))
        now += 40_000L

        assertEquals(listOf(old), ledger.sweep())
        assertEquals(setOf(fresh), ledger.tracked())
        // 没有 ticket 的会话也不算"过期"，不会被 sweep 影响
        assertTrue(ledger.tracked().none { it == old })
        assertTrue(ledger.isStale(Uuid.random()))
    }

    @Test
    fun `configured recovery limit is honored and a backwards clock is not fresh`() {
        var now = 1000L
        val ledger = ChatRecoveryLedger(clock = { now })
        val id = Uuid.random()
        assertTrue(ledger.noteFailure(id, limit = 1))
        assertFalse(ledger.noteFailure(id, limit = 1))
        now = 999L
        assertFalse(ledger.hasFreshTicket(id))
        assertFalse(ledger.resumeAllowed(id))
        ledger.clear(id)
        assertFalse(ledger.noteFailure(id, limit = 0))
    }


    @Test
    fun `durably appended notice remains the checkpoint when the following stream fails`() {
        val checkpoint = ChatReconnectCheckpoint()
        val notice = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Notice("job", "finished")))
        checkpoint.observe(notice)
        checkpoint.observe(notice.copy(parts = notice.parts + UIMessagePart.Text("half answer")))
        assertEquals(notice, checkpoint.message)
        assertTrue(checkpoint.canResume)
    }

}

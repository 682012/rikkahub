package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * m1 运行中插话：收件箱快照/确认 + 纯判定 helper。
 *
 * 重点是不破坏已有排队语义：插话只拿队首连续的、明确标了 deliverDuringRun 的纯文本输入，
 * 遇到普通排队输入就停；确认只删完全相等的旧快照；暂停/inFlight 时不取。
 */
class InboxSteeringTest {

    // ---------- 入队与老数据兼容 ----------

    @Test
    fun `deliverDuringRun defaults to false and stays queued-only`() {
        val inbox = ConversationInbox()
        val normal = inbox.enqueue(parts = listOf(text("普通排队")))
        assertNotNull(normal)
        assertFalse(normal!!.deliverDuringRun)
        // 默认 false：正在生成时不会抢先进这一轮
        assertTrue(inbox.peekSteering().isEmpty())
    }

    @Test
    fun `enqueue passes deliverDuringRun to the message`() {
        val inbox = ConversationInbox()
        val steering = inbox.enqueue(parts = listOf(text("插一句")), deliverDuringRun = true)
        assertTrue(steering!!.deliverDuringRun)
        assertEquals(listOf(steering), inbox.peekSteering())
    }

    @Test
    fun `old persisted json without the flag decodes as false`() {
        val id = Uuid.random()
        val json = """{"id":"$id","parts":[{"type":"text","text":"老数据"}],"answer":true,"isEditing":false}"""
        val decoded = Json.decodeFromString(QueuedMessage.serializer(), json)
        assertFalse(decoded.deliverDuringRun)
        assertEquals(listOf("老数据"), decoded.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
    }

    // ---------- peekSteering ----------

    @Test
    fun `peek takes only the contiguous steering head and never removes`() {
        val inbox = ConversationInbox()
        val first = inbox.enqueue(parts = listOf(text("一")), deliverDuringRun = true)!!
        val second = inbox.enqueue(parts = listOf(text("二")), deliverDuringRun = true)!!
        val normal = inbox.enqueue(parts = listOf(text("普通排队")))!!

        val peeked = inbox.peekSteering()

        assertEquals(listOf(first, second), peeked)
        // 不移除：快照之后队列还是原样（主代理 flush 成功才 ack）
        assertEquals(listOf(first, second, normal), inbox.state.value.messages)
        // 再取一次结果一样（幂等快照）
        assertEquals(listOf(first, second), inbox.peekSteering())
    }

    @Test
    fun `a normal queued input at the head stops steering - no queue jumping`() {
        val inbox = ConversationInbox()
        val normal = inbox.enqueue(parts = listOf(text("普通排队")))!!
        inbox.enqueue(parts = listOf(text("插一句")), deliverDuringRun = true)

        assertTrue(inbox.peekSteering().isEmpty())
        // 普通输入也不会被快照顺手拿走
        assertEquals(normal, inbox.state.value.messages.first())
    }

    @Test
    fun `editing answer false and non text input are not steering candidates`() {
        val inbox = ConversationInbox()
        val editing = inbox.enqueue(parts = listOf(text("编辑中")), deliverDuringRun = true)!!
        inbox.beginEdit(editing.id)
        assertTrue(inbox.peekSteering().isEmpty())
        inbox.finishEdit(editing.id)
        assertEquals(1, inbox.peekSteering().size)

        val other = ConversationInbox()
        other.enqueue(parts = listOf(text("不要回答")), answer = false, deliverDuringRun = true)
        assertTrue(other.peekSteering().isEmpty())

        val withImage = ConversationInbox()
        withImage.enqueue(
            parts = listOf(text("带图"), UIMessagePart.Image(url = "file:///tmp/a.png")),
            deliverDuringRun = true,
        )
        assertTrue(withImage.peekSteering().isEmpty())
    }

    @Test
    fun `paused inbox never steers`() {
        val inbox = ConversationInbox()
        inbox.enqueue(parts = listOf(text("插一句")), deliverDuringRun = true)
        inbox.pause(InboxPauseReason.USER)
        assertTrue(inbox.peekSteering().isEmpty())
        inbox.resume()
        assertEquals(1, inbox.peekSteering().size)
    }

    @Test
    fun `in flight message blocks steering`() {
        val inbox = ConversationInbox(storage = SteeringInboxStorage())
        inbox.enqueue(parts = listOf(text("正在发")))
        assertNotNull(inbox.takeNext())
        inbox.enqueue(parts = listOf(text("插一句")), deliverDuringRun = true)
        assertTrue(inbox.peekSteering().isEmpty())
    }

    @Test
    fun `at most eight messages and sixteen thousand chars per peek`() {
        val inbox = ConversationInbox()
        repeat(9) { inbox.enqueue(parts = listOf(text("n$it")), deliverDuringRun = true) }
        assertEquals(STEERING_MAX_MESSAGES, inbox.peekSteering().size)

        val long = ConversationInbox()
        long.enqueue(parts = listOf(text("a".repeat(10_000))), deliverDuringRun = true)
        long.enqueue(parts = listOf(text("b".repeat(10_000))), deliverDuringRun = true)
        // 第二条会超上限：不带进去，但仍然留在队首继续排队
        assertEquals(1, long.peekSteering().size)
        assertEquals(2, long.state.value.messages.size)
    }

    @Test
    fun `an over long first message is left for the next turn instead of being swallowed`() {
        val inbox = ConversationInbox()
        val huge = inbox.enqueue(parts = listOf(text("x".repeat(STEERING_MAX_TEXT + 1))), deliverDuringRun = true)!!
        assertTrue(inbox.peekSteering().isEmpty())
        // 正常下一轮照常发它
        assertEquals(huge.id, (inbox.takeNext() as InboxTurn.Message).message.id)
    }

    // ---------- acknowledgeSteering ----------

    @Test
    fun `acknowledge removes exactly the delivered snapshot and completes persisted`() {
        val inbox = ConversationInbox(storage = SteeringInboxStorage())
        val receipt = CompletableDeferred<Boolean>()
        val steering = inbox.enqueue(parts = listOf(text("插一句")), persisted = receipt, deliverDuringRun = true)!!
        val normal = inbox.enqueue(parts = listOf(text("普通排队")))!!
        inbox.post(key = "k", notice = UIMessagePart.Notice(title = "t", text = "n"))

        val snapshot = inbox.peekSteering()
        assertEquals(listOf(steering), snapshot)
        inbox.acknowledgeSteering(snapshot)

        assertTrue("persisted must be acknowledged", receipt.isCompleted)
        // await() 是挂起函数，这里用 runBlocking 取真实结果，别在非挂起测试里直接调
        assertTrue(receipt.isCompleted && runBlocking { receipt.await() })
        val remaining = inbox.state.value.items
        assertFalse(remaining.contains(steering))
        // 普通输入和通知都还在
        assertTrue(remaining.contains(normal))
        assertEquals(1, inbox.state.value.notices.size)
    }

    @Test
    fun `acknowledge keeps a message the user edited during delivery`() {
        val inbox = ConversationInbox()
        val steering = inbox.enqueue(parts = listOf(text("原文")), deliverDuringRun = true)!!
        val snapshot = inbox.peekSteering()
        inbox.finishEdit(steering.id, listOf(text("改过的")))
        inbox.acknowledgeSteering(snapshot)
        // 旧快照不等于改过的那条，不能被删掉
        assertEquals(listOf("改过的"), inbox.state.value.messages.map {
            (it.parts.first() as UIMessagePart.Text).text
        })
    }

    @Test
    fun `repeated acknowledge is a no-op`() {
        val inbox = ConversationInbox()
        val steering = inbox.enqueue(parts = listOf(text("插一句")), deliverDuringRun = true)!!
        val snapshot = inbox.peekSteering()
        inbox.acknowledgeSteering(snapshot)
        inbox.acknowledgeSteering(snapshot)
        assertTrue(inbox.state.value.messages.isEmpty())
    }

    @Test
    fun `steering ack does not touch the in flight message`() {
        val inbox = ConversationInbox(storage = SteeringInboxStorage())
        val sending = inbox.enqueue(parts = listOf(text("正在发")))!!
        inbox.takeNext()
        val steering = inbox.enqueue(parts = listOf(text("插一句")), deliverDuringRun = true)!!
        // inFlight 存在时 peek 为空，但即便调用方误用旧快照 ack 也只该删自己
        inbox.acknowledgeSteering(listOf(steering))
        assertEquals(sending.id, inbox.state.value.inFlight?.id)
        assertFalse(inbox.state.value.messages.any { it.id == steering.id })
    }

    @Test
    fun `ordinary queue order survives steering`() {
        val inbox = ConversationInbox()
        val first = inbox.enqueue(parts = listOf(text("第一条")))!!
        val steering = inbox.enqueue(parts = listOf(text("插一句")), deliverDuringRun = true)!!
        val second = inbox.enqueue(parts = listOf(text("第二条")))!!

        // 队首是普通输入时插话不许抢序
        assertTrue(inbox.peekSteering().isEmpty())

        // takeNext 是纯 FIFO：普通输入 -> 中间那条插话（没插进这一轮就正常开自己的下一轮）
        // -> 普通输入。插话只是在「正在生成」时可以被提前取走，绝不能跳过或吞掉任何一条。
        assertEquals(first.id, (inbox.takeNext() as InboxTurn.Message).message.id)
        assertEquals(steering.id, (inbox.takeNext() as InboxTurn.Message).message.id)
        assertEquals(second.id, (inbox.takeNext() as InboxTurn.Message).message.id)
        assertTrue(inbox.state.value.messages.isEmpty())
    }

    // ---------- 纯 helper ----------

    @Test
    fun `shouldSteerInput requires every precondition`() {
        val parts = listOf(text("插一句"))
        assertTrue(shouldSteerInput(parts, answer = true, generating = true, awaitingApproval = false, requested = true))
        assertFalse(shouldSteerInput(parts, answer = true, generating = true, awaitingApproval = false, requested = false))
        assertFalse(shouldSteerInput(parts, answer = true, generating = false, awaitingApproval = false, requested = true))
        assertFalse(shouldSteerInput(parts, answer = true, generating = true, awaitingApproval = true, requested = true))
        assertFalse(shouldSteerInput(parts, answer = false, generating = true, awaitingApproval = false, requested = true))
        assertFalse(shouldSteerInput(listOf(text("   ")), true, true, false, true))
        assertFalse(shouldSteerInput(listOf(UIMessagePart.Image(url = "file:///tmp/a.png")), true, true, false, true))
        assertFalse(
            shouldSteerInput(listOf(text("x".repeat(STEERING_MAX_TEXT + 1))), true, true, false, true),
        )
    }

    @Test
    fun `toSteeringPart keeps the exact user text and stamps the queue id`() {
        val inbox = ConversationInbox()
        val steering = inbox.enqueue(parts = listOf(text("用户原话")), deliverDuringRun = true)!!
        val part = steering.toSteeringPart()
        assertEquals("用户原话", part.text)
        assertNotNull(part.title)
        assertEquals(
            steering.id.toString(),
            part.metadata?.get(STEERING_ID_KEY)?.let { (it as kotlinx.serialization.json.JsonPrimitive).content },
        )
        assertEquals(
            STEERING_SOURCE_USER,
            part.metadata?.get(STEERING_SOURCE_KEY)?.let { (it as kotlinx.serialization.json.JsonPrimitive).content },
        )
    }

    @Test
    fun `isSteeringDeliveredIn matches metadata and exact text only`() {
        val inbox = ConversationInbox()
        val steering = inbox.enqueue(parts = listOf(text("看一下")), deliverDuringRun = true)!!
        val part = steering.toSteeringPart()

        assertTrue(steering.isSteeringDeliveredIn(listOf(assistant(part))))
        // 相似但不同的文本不算
        assertFalse(steering.isSteeringDeliveredIn(listOf(assistant(UIMessagePart.Notice(title = "用户插话", text = "看一下！")))))
        // 相同文本但没有 metadata 不算
        assertFalse(steering.isSteeringDeliveredIn(listOf(assistant(UIMessagePart.Notice(title = "用户插话", text = "看一下")))))
        // 只是普通历史消息不算
        assertFalse(steering.isSteeringDeliveredIn(listOf(UIMessage.user("看一下"))))
        assertFalse(steering.isSteeringDeliveredIn(emptyList()))
    }

    @Test
    fun `clearSteeringMode turns the flag off but keeps the message queued`() {
        val inbox = ConversationInbox()
        val steering = inbox.enqueue(parts = listOf(text("插一句")), deliverDuringRun = true)!!
        assertNotNull(inbox.clearSteeringMode(steering.id))
        assertTrue(inbox.peekSteering().isEmpty())
        assertEquals(1, inbox.state.value.messages.size)
        assertNull(inbox.clearSteeringMode(steering.id))
    }

    // ---------- 文本上限口径 / 重启不丢不重 ----------

    @Test
    fun `the text limit counts the newline that joins text parts`() {
        // 拼接后 8000 + 1 + 8000 = 16001 字：各自长度都不超，合起来超了
        val tooLong = listOf(text("a".repeat(8_000)), text("b".repeat(8_000)))
        assertFalse(shouldSteerInput(tooLong, true, true, false, true))
        // 7999 + 1 + 8000 = 16000：正好在上限内
        val exact = listOf(text("a".repeat(7_999)), text("b".repeat(8_000)))
        assertTrue(shouldSteerInput(exact, true, true, false, true))

        // 收件箱快照用同一口径，不能把拼接后超长的输入塞进这一轮
        val inbox = ConversationInbox()
        val steering = inbox.enqueue(parts = tooLong, deliverDuringRun = true)!!
        assertTrue(inbox.peekSteering().isEmpty())
        // 不吞：它照常排队等下一轮
        assertEquals(steering.id, (inbox.takeNext() as InboxTurn.Message).message.id)

        val fits = ConversationInbox()
        fits.enqueue(parts = exact, deliverDuringRun = true)
        assertEquals(1, fits.peekSteering().size)
    }

    @Test
    fun `malformed or foreign notice metadata never crashes and never counts as delivered`() {
        val inbox = ConversationInbox()
        val steering = inbox.enqueue(parts = listOf(text("看一下")), deliverDuringRun = true)!!
        val id = steering.id.toString()

        fun notice(metadata: JsonObject?) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Notice(title = STEERING_NOTICE_TITLE, text = "看一下", metadata = metadata)),
        )

        // metadata 是外部历史数据：不是字符串 primitive 时 jsonPrimitive 会抛异常，必须按形状判
        val junk = listOf(
            notice(buildJsonObject { put(STEERING_ID_KEY, JsonObject(emptyMap())) }),
            notice(buildJsonObject { put(STEERING_ID_KEY, JsonArray(emptyList())) }),
            notice(buildJsonObject { put(STEERING_ID_KEY, JsonNull) }),
            // 少了来源（不是 USER）
            notice(buildJsonObject { put(STEERING_ID_KEY, id) }),
            // id 对、来源是别的
            notice(buildJsonObject { put(STEERING_ID_KEY, id); put(STEERING_SOURCE_KEY, "TOOL") }),
            // 只有来源没有 id
            notice(buildJsonObject { put(STEERING_SOURCE_KEY, STEERING_SOURCE_USER) }),
            notice(null),
        )
        junk.forEach { history -> assertFalse(steering.isSteeringDeliveredIn(listOf(history))) }
        assertFalse(steering.isSteeringDeliveredIn(junk))

        // 三个条件都满足才算已交付：队列 id + source=USER + 正文完全相等
        val real = buildJsonObject {
            put(STEERING_ID_KEY, id)
            put(STEERING_SOURCE_KEY, STEERING_SOURCE_USER)
        }
        assertTrue(steering.isSteeringDeliveredIn(listOf(notice(real))))
        assertFalse(steering.isSteeringDeliveredIn(listOf(notice(buildJsonObject {
            put(STEERING_ID_KEY, Uuid.random().toString())
            put(STEERING_SOURCE_KEY, STEERING_SOURCE_USER)
        }))))
    }

    @Test
    fun `an unacknowledged steering message survives a restart and is not injected twice`() {
        val storage = SteeringInboxStorage()
        val first = ConversationInbox(storage = storage)
        val steering = first.enqueue(parts = listOf(text("插一句")), deliverDuringRun = true)!!
        assertEquals(listOf(steering.id), first.peekSteering().map { it.id })

        // 进程被杀：快照还没 ack，盘上的队列必须还在（不丢输入）
        val restarted = ConversationInbox(storage = storage)
        assertTrue(restarted.state.value.items.isEmpty())
        val restored = restarted.restore()
        assertNotNull(restored)
        assertEquals(listOf(steering.id), restored!!.messages.map { it.id })

        // 重启后重新快照：内容完全一样，可以再插一次
        val again = restarted.peekSteering().single()
        assertEquals(steering.id, again.id)
        assertEquals(steering, again)

        // 如果这条其实已经进了历史（flush 成功但没来得及 ack），先认出来，别重复注入
        val history = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(again.toSteeringPart())))
        assertTrue(again.isSteeringDeliveredIn(history))
        restarted.acknowledgeSteering(listOf(again))
        assertTrue(restarted.state.value.messages.isEmpty())
        assertTrue(storage.state!!.items.isEmpty())

        // 再重启一次也是空的，重复 ack 不会把队列搞乱
        val third = ConversationInbox(storage = storage)
        val thirdState = third.restore()
        assertNotNull(thirdState)
        assertTrue(thirdState!!.messages.isEmpty())
        assertTrue(third.peekSteering().isEmpty())
    }

    @Test
    fun `steering acknowledged only after the message is persisted`() {
        val storage = SteeringInboxStorage()
        val inbox = ConversationInbox(storage = storage)
        val receipt = CompletableDeferred<Boolean>()
        val steering = inbox.enqueue(
            parts = listOf(text("插一句")),
            persisted = receipt,
            deliverDuringRun = true,
        )!!
        val snapshot = inbox.peekSteering()
        assertTrue("peek must not complete the receipt", !receipt.isCompleted)
        inbox.acknowledgeSteering(snapshot)
        assertTrue(receipt.isCompleted)
        assertTrue(runBlocking { receipt.await() })
        assertFalse(storage.state!!.items.contains(steering))
    }

    private fun text(value: String) = UIMessagePart.Text(value)

    private fun assistant(part: UIMessagePart) =
        UIMessage(role = me.rerere.ai.core.MessageRole.ASSISTANT, parts = listOf(part))
}

/** 只存最近一次写入的内存持久层 */
internal class SteeringInboxStorage : ConversationInboxStorage {
    var state: InboxState? = null
    override fun read(): InboxState? = state
    override fun write(state: InboxState) {
        this.state = state
    }
}

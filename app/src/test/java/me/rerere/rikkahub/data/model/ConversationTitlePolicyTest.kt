package me.rerere.rikkahub.data.model

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
 * 即时标题的策略层：什么时候该起标题、什么时候只是精修一次、手改和同一条消息不会互相覆盖，
 * 以及写回之前怎么核对分支没被换掉。全部是纯函数，不碰数据库和模型。
 */
class ConversationTitlePolicyTest {
    private fun user(text: String) = UIMessage.user(text)
    private fun assistant(text: String) = UIMessage.assistant(text)

    private fun conversationOf(vararg messages: UIMessage) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = messages.map { it.toMessageNode() },
    )

    private val longAnswer = assistant("这是一段足够长的回答，用来表示模型真的给出了有实质内容的结果。")

    // ---- 立即触发：用户一发出消息就该起标题 ----

    @Test
    fun `a sent user message yields an early title without waiting for the answer`() {
        val userMessage = user("帮我把这周的会议纪要整理成待办")
        val conversation = conversationOf(userMessage)
        val trigger = requireNotNull(TitleTrigger.of(userMessage))
        assertTrue("用户文字就是早标题的依据", trigger.hasText)

        val decision = conversation.titleDecision(
            fingerprint = titleFingerprint(conversation.currentMessages),
            newContext = false,
            force = false,
            hasContent = true,
        )
        assertTrue("没有回答也要起标题", decision.request)
        assertFalse("第一版标题不是精修", decision.refine)
        assertFalse("早标题只用用户原话", decision.fullContext)

        val titled = conversation.withEarlyTitle("会议纪要待办", trigger)
        assertEquals("会议纪要待办", titled.title)
        assertTrue(titled.titleState.pendingEarly)
        assertEquals(trigger.userText, titled.titleState.anchor)
        assertEquals(1, titled.titleState.autoTitles)
        assertTrue(titled.titleState.isAuto)
        // 状态挂在会话实例上，不是全局表里的弱键
        assertNotNull(titled.automaticTitleState)
        assertNull("原来的实例没有被就地修改", conversation.automaticTitleState)
    }

    @Test
    fun `images and files alone still request an early title from the full conversation`() {
        val imageOnly = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///x.png")))
        val trigger = requireNotNull(TitleTrigger.of(imageOnly))
        assertFalse("没有文字快照", trigger.hasText)

        // 没有文字也照样请求：生成时退回最近几条真实对话，不必干等到一轮结束
        val conversation = conversationOf(imageOnly)
        val decision = conversation.titleDecision(
            fingerprint = titleFingerprint(conversation.currentMessages),
            newContext = false,
            force = false,
            hasContent = true,
        )
        assertTrue(decision.request)
        assertFalse("没有文字快照时不能用空字符串当输入", decision.fullContext)
        // ChatService 据此把 null 传给生成器 -> 用完整上下文
        assertNull(trigger.userText.takeIf { it.isNotBlank() })
    }

    @Test
    fun `assistant messages are never a title trigger`() {
        assertNull(TitleTrigger.of(assistant("reply")))
        // 没有文字的输入（只有图片/文件）也拿不到快照
        val mediaOnly = UIMessage(role = MessageRole.USER, parts = emptyList())
        assertEquals("", mediaOnly.toText().titleSnapshot())
        assertFalse(TitleTrigger(mediaOnly.id, "").hasText)
    }

    @Test
    fun `user text snapshot is collapsed and bounded`() {
        val messy = "  第一行\n\n第二行\t第三行  "
        assertEquals("第一行 第二行 第三行", messy.titleSnapshot())
        val long = (1..500).joinToString(" ") { "word$it" }
        val snapshot = long.titleSnapshot(limit = 50)
        assertTrue(snapshot.length <= 50)
        assertFalse("不要切断半个词", snapshot.endsWith("w"))
    }

    // ---- 自适应精修：只在有实质新内容时再起一次 ----

    @Test
    fun `the early title is refined once after a real answer`() {
        val userMessage = user("帮我选一台拍照好的手机")
        val early = conversationOf(userMessage)
            .withEarlyTitle("选手机", requireNotNull(TitleTrigger.of(userMessage)))
        val answered = early.copy(
            messageNodes = early.messageNodes + longAnswer.toMessageNode(),
        )

        assertTrue(isMateriallyNewContext(answered.currentMessages, answered.titleState.anchor))
        val decision = answered.titleDecision(
            fingerprint = titleFingerprint(answered.currentMessages),
            newContext = true,
            force = false,
            hasContent = true,
        )
        assertTrue(decision.request)
        assertTrue(decision.refine)
        assertTrue("精修用完整对话", decision.fullContext)

        val refined = requireNotNull(answered.refinedTitle("拍照手机对比", "fp-2"))
        assertEquals("拍照手机对比", refined.title)
        assertFalse(refined.titleState.pendingEarly)
        assertEquals(2, refined.titleState.autoTitles)
        assertEquals("锚点保留，下一次判断还用它", early.titleState.anchor, refined.titleState.anchor)
    }

    @Test
    fun `a short acknowledgement is not a materially new context`() {
        val userMessage = user("帮我选一台拍照好的手机")
        val early = conversationOf(userMessage)
            .withEarlyTitle("选手机", requireNotNull(TitleTrigger.of(userMessage)))
        val answered = early.copy(messageNodes = early.messageNodes + assistant("好的").toMessageNode())
        assertFalse(isMateriallyNewContext(answered.currentMessages, answered.titleState.anchor))
    }

    @Test
    fun `a user message that follows the answer belongs to the next turn`() {
        val early = conversationOf(user("第一问"))
            .withEarlyTitle("第一问", TitleTrigger(Uuid.random(), "第一问"))
        val withNextQuestion = early.copy(
            messageNodes = early.messageNodes + longAnswer.toMessageNode() + user("第二问").toMessageNode(),
        )
        // 用户又发了新消息：那是下一条早标题的事，这一轮不精修
        assertFalse(isMateriallyNewContext(withNextQuestion.currentMessages, withNextQuestion.titleState.anchor))
    }

    @Test
    fun `editing the starting user message drops the refinement`() {
        val early = conversationOf(user("原始问题"))
            .withEarlyTitle("原始问题", TitleTrigger(Uuid.random(), "原始问题"))
        val rewritten = early.copy(
            messageNodes = early.messageNodes.dropLast(1) + user("完全改写的问题").toMessageNode() +
                longAnswer.toMessageNode(),
        )
        assertFalse(isMateriallyNewContext(rewritten.currentMessages, rewritten.titleState.anchor))
    }

    @Test
    fun `only the first answer refines and later turns do not keep re-titling`() {
        val userMessage = user("帮我选一台拍照好的手机")
        var conversation = conversationOf(userMessage)
            .withEarlyTitle("选手机", requireNotNull(TitleTrigger.of(userMessage)))
        conversation = conversation.copy(messageNodes = conversation.messageNodes + longAnswer.toMessageNode())

        // 第一次回答：精修一次
        val once = requireNotNull(conversation.refinedTitle("拍照手机对比", "fp-answer-1"))
        // 同一段回答又跑了一遍标题请求：指纹没变，不再请求
        assertEquals(
            TitleDecision.Skip,
            once.titleDecision(fingerprint = "fp-answer-1", newContext = true, force = false, hasContent = true),
        )

        // 后面又聊了几轮：没有新的临时标题，也不按同一段内容再起标题
        val later = once.copy(
            messageNodes = once.messageNodes + user("再看下第二款").toMessageNode() + longAnswer.toMessageNode(),
        )
        assertFalse(later.titleDecision("fp-answer-3", newContext = true, force = false, hasContent = true).request)
        assertEquals("拍照手机对比", later.title)
    }

    @Test
    fun `a later turn can start a new early title and refine once more`() {
        val first = user("帮我看下这台手机")
        var conversation = conversationOf(first).withEarlyTitle("看手机", requireNotNull(TitleTrigger.of(first)))
        conversation = conversation.copy(messageNodes = conversation.messageNodes + longAnswer.toMessageNode())
        conversation = requireNotNull(conversation.refinedTitle("看手机参数", "fp-1"))

        // 用户发了新问题：新的早标题可以覆盖自动标题
        val second = user("再帮我挑个配套的耳机")
        val retitled = conversation.withEarlyTitle("挑耳机", requireNotNull(TitleTrigger.of(second)))
        assertEquals("挑耳机", retitled.title)
        assertEquals(3, retitled.titleState.autoTitles)
    }

    // ---- 状态跟着会话实例走：流式复制不能把它弄丢 ----

    @Test
    fun `title state survives the copies made while streaming`() {
        val userMessage = user("帮我选一台拍照好的手机")
        var conversation = conversationOf(userMessage)
            .withEarlyTitle("选手机", requireNotNull(TitleTrigger.of(userMessage)))

        // 流式生成每来一段都会 copy 出一个新实例（updateCurrentMessages 就是这么干的）
        var streaming = assistant("正在")
        repeat(5) { step ->
            streaming = streaming.copy(parts = listOf(UIMessagePart.Text(streaming.toText() + "输出$step")))
            conversation = conversation.updateCurrentMessages(conversation.currentMessages + streaming)
            assertTrue("第 $step 次复制之后仍然算自动标题", conversation.titleState.isAuto)
            assertTrue("还在等精修", conversation.titleState.pendingEarly)
            assertEquals("选手机", conversation.title)
        }

        // 回答写完：精修照常发生（复制丢状态时这里会当成手改，永远不再精修）
        val decision = conversation.titleDecision(
            fingerprint = "fp-answer",
            newContext = isMateriallyNewContext(
                conversation.currentMessages,
                conversation.titleState.anchor,
            ),
            force = false,
            hasContent = true,
        )
        assertTrue(decision.refine)
        assertNotNull(conversation.refinedTitle("拍照手机对比", "fp-answer"))
    }

    // ---- 手改保护：用户改过之后自动标题一律不写 ----

    @Test
    fun `a manual rename wins over a late automatic title`() {
        val userMessage = user("帮我写一份周报")
        val early = conversationOf(userMessage)
            .withEarlyTitle("周报", requireNotNull(TitleTrigger.of(userMessage)))
        val renamed = early.withManualTitle("我的周报")
        assertFalse(renamed.titleState.isAuto)
        assertFalse(renamed.titleState.pendingEarly)

        // 晚到的自动标题（早标题或结束时的精修）都不写
        assertFalse(renamed.titleDecision("fp", newContext = true, force = false, hasContent = true).request)
        assertNull(renamed.refinedTitle("自动周报", "fp"))
        assertEquals("我的周报", renamed.title)
    }

    @Test
    fun `a manual rename to the same string still wins`() {
        val userMessage = user("帮我写一份周报")
        val early = conversationOf(userMessage)
            .withEarlyTitle("周报", requireNotNull(TitleTrigger.of(userMessage)))
        // 用户把标题改成和自动标题一模一样：标题没变，但它是用户定的，之后不许自动改
        val renamed = early.withManualTitle("周报")
        assertEquals("周报", renamed.title)
        assertFalse(renamed.titleState.isAuto)
        assertNull(renamed.refinedTitle("自动周报", "fp"))
        assertFalse(
            renamed.titleDecision(titleFingerprint(renamed.currentMessages), newContext = true, force = false,
                hasContent = true).request,
        )
    }

    @Test
    fun `manual regenerate still works on a hand renamed conversation`() {
        val renamed = conversationOf(user("问题")).withManualTitle("我的标题")
        val decision = renamed.titleDecision(
            fingerprint = titleFingerprint(renamed.currentMessages),
            newContext = false,
            force = true,
            hasContent = true,
        )
        assertTrue("用户自己点的重新生成不受自动开关和手改影响", decision.request)
        assertTrue("重新生成不做精修，直接换掉", decision.force)
        assertFalse(decision.refine)
        assertTrue("重新生成用完整对话", decision.fullContext)
    }

    @Test
    fun `a forced title writes even when the content was already titled`() {
        val initial = conversationOf(user("问题"), longAnswer)
            .withEarlyTitle("已有标题", TitleTrigger(Uuid.random(), "问题"))
        val conversation = requireNotNull(initial.refinedTitle("已有标题", "fp"))
        // 同一段内容已经起过标题：自动调用跳过，手动点的照做
        assertFalse(
            conversation.titleDecision("fp", newContext = true, force = false, hasContent = true).request,
        )
        assertTrue(conversation.titleDecision("fp", newContext = true, force = true, hasContent = true).request)

        val forced = conversation.withForcedTitle("重新生成的标题", "fp")
        assertEquals("重新生成的标题", forced.title)
        assertTrue("自动来源不变", forced.titleState.isAuto)
        assertFalse("重新生成之后不再等精修", forced.titleState.pendingEarly)
        assertEquals(3, forced.titleState.autoTitles)
    }

    @Test
    fun `a forced title on a hand renamed conversation stays manual`() {
        val renamed = conversationOf(user("问题")).withManualTitle("我的标题")
        val forced = renamed.withForcedTitle("换个标题", "fp-2")
        assertEquals("换个标题", forced.title)
        assertFalse("重新生成过的仍是用户定的，自动标题不能盖掉", forced.titleState.isAuto)
        assertEquals("我的标题", renamed.title)
    }

    @Test
    fun `an empty conversation never requests a title`() {
        val empty = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        assertEquals(
            TitleDecision.Skip,
            empty.titleDecision("", newContext = false, force = false, hasContent = false),
        )
    }

    @Test
    fun `legacy conversations without in-memory title state infer from the title`() {
        val legacy = conversationOf(user("旧会话"))
        assertNull("数据库里没有这一列，加载回来是 null", legacy.automaticTitleState)
        assertTrue("空白标题的会话照常自动生成", legacy.titleState.isAuto)
        assertEquals(0, legacy.titleState.autoTitles)
        assertEquals("", legacy.titleState.anchor)

        // 已经有标题的旧会话（重启后加载回来的）不再被自动覆盖
        val titled = legacy.copy(title = "旧标题")
        assertFalse(titled.titleState.isAuto)
        assertFalse(
            titled.titleDecision(titleFingerprint(titled.currentMessages), newContext = true, force = false,
                hasContent = true).request,
        )
    }

    @Test
    fun `same user message repeated does not count as a new title round`() {
        val message = user("同一句话")
        val trigger = requireNotNull(TitleTrigger.of(message))
        val first = conversationOf(message).withEarlyTitle("标题一", trigger)
        // 重试同一轮：同一条消息再起一次早标题，计数不继续加
        val retried = first.withEarlyTitle("标题二", trigger)
        assertEquals(1, retried.titleState.autoTitles)
        assertEquals("标题二", retried.title)
    }

    @Test
    fun `fingerprint tracks content changes on the current branch`() {
        val message = user("问题")
        val a = conversationOf(message)
        val same = a.updateCurrentMessages(listOf(message))
        assertEquals(titleFingerprint(a.currentMessages), titleFingerprint(same.currentMessages))
        val changed = a.updateCurrentMessages(listOf(user("换了问题")))
        assertFalse(titleFingerprint(a.currentMessages) == titleFingerprint(changed.currentMessages))
    }

    // ---- 分支 / 删除：旧分支上的标题状态不能盖到新分支 ----

    @Test
    fun `switching branch keeps the title of the new branch decision`() {
        val pinned = conversationOf(user("A 分支"))
            .withEarlyTitle("A 标题", TitleTrigger(Uuid.random(), "A 分支"))
        // 自动标题不会因为切分支就反复重命名；旧分支的晚到结果由写入 guard 拦截。
        assertEquals(
            false,
            pinned.copy(messageNodes = listOf(user("B 分支").toMessageNode()))
                .titleDecision("fp-b", newContext = false, force = false, hasContent = true).request,
        )
    }

    // ---- 写回前的分支核对 ----

    @Test
    fun `an early title guard holds while the same turn is intact`() {
        val userMessage = user("帮我看看这台手机")
        val captured = conversationOf(userMessage)
        val branch = requireNotNull(captured.currentMessages.titleBranchAnchor())
        val guard = TitleWriteGuard(captured.title, captured.titleState.source, branch, force = false)

        // 回答还在流式生成：同一条用户消息，仍然算这一轮
        val answered = captured.copy(
            messageNodes = captured.messageNodes + assistant("正在生成").toMessageNode(),
        )
        assertTrue(guard.allows(answered))
    }

    @Test
    fun `a guard drops the title when the user message changed`() {
        val userMessage = user("原始问题")
        val captured = conversationOf(userMessage)
        val guard = TitleWriteGuard(
            title = captured.title,
            source = captured.titleState.source,
            branch = requireNotNull(captured.currentMessages.titleBranchAnchor()),
            force = false,
        )

        val edited = captured.copy(
            messageNodes = listOf(user("完全改写的问题").copy(id = userMessage.id).toMessageNode()),
        )
        assertFalse("文本被改写：锚点不成立", guard.allows(edited))

        val replaced = captured.copy(messageNodes = listOf(user("另一条分支").toMessageNode()))
        assertFalse("切到另一条分支：选中的消息 id 变了", guard.allows(replaced))

        val nextTurn = captured.copy(
            messageNodes = captured.messageNodes + longAnswer.toMessageNode() + user("下一问").toMessageNode(),
        )
        assertFalse("用户又发了一条：那是下一轮", guard.allows(nextTurn))
    }

    @Test
    fun `a refine guard requires the same answer branch`() {
        val userMessage = user("帮我选一台拍照好的手机")
        val answer = longAnswer.copy(id = Uuid.random())
        val captured = conversationOf(userMessage, answer)
        val guard = TitleWriteGuard(
            title = "",
            source = ConversationTitleSource.AUTO,
            branch = requireNotNull(captured.currentMessages.titleBranchAnchor(answerMessageId = answer.id)),
            force = false,
        )
        assertTrue(guard.allows(captured))

        // 重新生成了回答：另一条分支上的标题不能顶掉这一条
        val regenerated = captured.copy(
            messageNodes = listOf(
                userMessage.toMessageNode(),
                MessageNode(messages = listOf(answer, assistant("另一条分支的回答，内容完全不同")), selectIndex = 1),
            ),
        )
        assertFalse(guard.allows(regenerated))
    }

    @Test
    fun `a guard drops the title when the title or its source changed meanwhile`() {
        val userMessage = user("问题")
        val captured = conversationOf(userMessage)
            .withEarlyTitle("自动标题", requireNotNull(TitleTrigger.of(userMessage)))
        val guard = TitleWriteGuard(
            title = captured.title,
            source = captured.titleState.source,
            branch = requireNotNull(captured.currentMessages.titleBranchAnchor()),
            force = false,
        )
        assertTrue(guard.allows(captured))

        // 用户手改成同一个字符串：标题没变，来源变了，自动标题不许再写
        val renamed = captured.withManualTitle("自动标题")
        assertFalse(guard.allows(renamed))

        // 别的路径改了标题
        val retitled = captured.withEarlyTitle("另一个标题", requireNotNull(TitleTrigger.of(userMessage)))
        assertFalse(guard.allows(retitled))

        // 用户手动重新生成不看这些
        assertTrue(guard.copy(force = true).allows(renamed))
    }
}

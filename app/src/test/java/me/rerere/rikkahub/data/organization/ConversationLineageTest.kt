package me.rerere.rikkahub.data.organization

import java.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 旧会话家族识别的边界：只有「同助手 + 同一条开头用户消息 ID + 时间说得通」才算分叉。
 *
 * 时间全部取真实 [UIMessage.createdAt]（默认就是构造那一刻），会话创建时间要么直接用这条消息的
 * 时间，要么在它之后几十秒——模拟「先有消息、后有会话/分叉」的真实先后，不编造历史日期。
 */
class ConversationLineageTest {
    private val assistant = Uuid.random()
    private val anotherAssistant = Uuid.random()

    // ---- 构造 ----

    private fun instantOf(message: UIMessage): Instant =
        message.createdAt.toInstant(TimeZone.currentSystemDefault()).toJavaInstant()

    private fun node(vararg messages: UIMessage) = MessageNode(messages = messages.toList())

    private fun conversation(
        nodes: List<MessageNode>,
        id: Uuid = Uuid.random(),
        createAt: Instant = Instant.now(),
        assistantId: Uuid = assistant,
        lineageId: Uuid? = null,
        pinned: Boolean = false,
        title: String = "",
    ) = Conversation(
        id = id,
        assistantId = assistantId,
        title = title,
        messageNodes = nodes,
        createAt = createAt,
        updateAt = createAt,
        isPinned = pinned,
        lineageId = lineageId,
    )

    /** 一条普通旧会话：领先节点（预设/问候） + 第一条真正的用户消息 + 一条回复。 */
    private fun chat(
        firstUser: UIMessage,
        leading: List<UIMessage> = emptyList(),
        reply: String = "好的",
        id: Uuid = Uuid.random(),
        createAt: Instant = instantOf(firstUser),
        assistantId: Uuid = assistant,
        lineageId: Uuid? = null,
        pinned: Boolean = false,
        title: String = "",
    ) = conversation(
        nodes = leading.map { node(it) } + node(firstUser) + node(UIMessage.assistant(reply)),
        id = id,
        createAt = createAt,
        assistantId = assistantId,
        lineageId = lineageId,
        pinned = pinned,
        title = title,
    )

    private fun infer(
        conversations: List<Conversation>,
        presetMessageIds: Set<Uuid> = emptySet(),
    ) = ConversationLineageResolver.infer(conversations, presetMessageIds)

    // ---- 认得出的分叉 ----

    @Test
    fun `real fork sharing the first user message collapses to the source`() {
        val first = UIMessage.user("帮我写一份周报")
        val source = chat(firstUser = first)
        val fork = chat(firstUser = first, createAt = instantOf(first).plusSeconds(60))

        val result = infer(listOf(source, fork))

        assertEquals(mapOf(fork.id to source.id), result)
        // 根自己不用回写：null 已经等价于「自己是根」
        assertFalse(result.containsKey(source.id))
    }

    @Test
    fun `nested forks all collapse to the earliest conversation`() {
        val first = UIMessage.user("继续这个方案")
        val source = chat(firstUser = first)
        val firstFork = chat(firstUser = first, createAt = instantOf(first).plusSeconds(60))
        val secondFork = chat(firstUser = first, createAt = instantOf(first).plusSeconds(120))

        val result = infer(listOf(secondFork, source, firstFork))

        assertEquals(mapOf(firstFork.id to source.id, secondFork.id to source.id), result)
    }

    @Test
    fun `fork before the user edited the first message still matches one variant`() {
        // 用户后来重新生成了开头那条消息，源会话多出一个变体；早先的分叉只有旧的那条
        val older = UIMessage.user("第一版问题")
        val newer = UIMessage.user("改过的问题")
        val source = conversation(
            nodes = listOf(
                MessageNode(messages = listOf(older, newer), selectIndex = 1),
                node(UIMessage.assistant("回答")),
            ),
            createAt = instantOf(older),
        )
        val fork = conversation(
            nodes = listOf(node(older), node(UIMessage.assistant("回答"))),
            createAt = instantOf(older).plusSeconds(60),
        )

        assertEquals(mapOf(fork.id to source.id), infer(listOf(source, fork)))
    }

    @Test
    fun `pinned conversations are still recognized`() {
        val first = UIMessage.user("钉住的那条")
        val source = chat(firstUser = first, pinned = true)
        val fork = chat(firstUser = first, createAt = instantOf(first).plusSeconds(60), pinned = true)

        assertEquals(mapOf(fork.id to source.id), infer(listOf(source, fork)))
    }

    @Test
    fun `equal creation times are broken by id so the result is stable`() {
        val first = UIMessage.user("同一毫秒建的两条")
        // 真实先后：会话是在这条消息发出来的同一刻建的，两条并列
        val sameMoment = instantOf(first)
        val a = chat(firstUser = first, createAt = sameMoment)
        val b = chat(firstUser = first, createAt = sameMoment)

        val root = listOf(a, b).minByOrNull { it.id.toString() }!!
        val child = if (root.id == a.id) b else a
        val expected = mapOf(child.id to root.id)

        assertEquals(expected, infer(listOf(a, b)))
        // 输入顺序不影响结果
        assertEquals(expected, infer(listOf(b, a)))
    }

    // ---- 预设消息 ----

    @Test
    fun `preset messages are skipped and unrelated users are not merged`() {
        val presetUser = UIMessage.user("你好")
        val presetAssistant = UIMessage.assistant("在的，今天想聊什么？")
        val presetIds = setOf(presetUser.id, presetAssistant.id)
        val a = chat(
            firstUser = UIMessage.user("帮我看看这段代码"),
            leading = listOf(presetUser, presetAssistant),
        )
        val b = chat(
            firstUser = UIMessage.user("明天天气怎么样"),
            leading = listOf(presetUser, presetAssistant),
        )

        assertEquals(emptyMap<Uuid, Uuid>(), infer(listOf(a, b), presetIds))
    }

    @Test
    fun `preset prefix does not stop a real fork from matching`() {
        val presetUser = UIMessage.user("你好")
        val presetAssistant = UIMessage.assistant("在的")
        val presetIds = setOf(presetUser.id, presetAssistant.id)
        val first = UIMessage.user("帮我规划一下周末")
        val source = chat(firstUser = first, leading = listOf(presetUser, presetAssistant))
        val fork = chat(
            firstUser = first,
            leading = listOf(presetUser, presetAssistant),
            createAt = instantOf(first).plusSeconds(60),
        )

        assertEquals(mapOf(fork.id to source.id), infer(listOf(source, fork), presetIds))
    }

    @Test
    fun `only preset messages never makes a family`() {
        val presetUser = UIMessage.user("你好")
        val presetAssistant = UIMessage.assistant("在的")
        val presetIds = setOf(presetUser.id, presetAssistant.id)
        val a = conversation(
            nodes = listOf(node(presetUser), node(presetAssistant), node(UIMessage.assistant("还在吗"))),
        )
        val b = conversation(
            nodes = listOf(node(presetUser), node(presetAssistant), node(UIMessage.assistant("还在吗"))),
        )

        assertEquals(emptyMap<Uuid, Uuid>(), infer(listOf(a, b), presetIds))
    }

    @Test
    fun `deleted preset template does not merge unrelated chats`() {
        // 预设早被用户删掉了，presetMessageIds 里没有它；两条会话的开头仍是同一份模板消息
        val deletedTemplate = UIMessage.user("你好")
        val a = conversation(
            nodes = listOf(
                node(deletedTemplate),
                node(UIMessage.user("问题 A")),
                node(UIMessage.assistant("答 A")),
            ),
            createAt = instantOf(deletedTemplate).plusSeconds(30),
        )
        val b = conversation(
            nodes = listOf(
                node(deletedTemplate),
                node(UIMessage.user("问题 B")),
                node(UIMessage.assistant("答 B")),
            ),
            createAt = instantOf(deletedTemplate).plusSeconds(90),
        )

        assertEquals(emptyMap<Uuid, Uuid>(), infer(listOf(a, b)))
    }

    @Test
    fun `root deleted leaves its legacy forks unrecognized`() {
        // 源会话已经删了，只剩两条分叉：它们的开头消息比自己建得还早，看起来和老模板一样 → 不认
        val first = UIMessage.user("源会话的开头")
        val forkA = chat(firstUser = first, createAt = instantOf(first).plusSeconds(60))
        val forkB = chat(firstUser = first, createAt = instantOf(first).plusSeconds(600))

        assertEquals(emptyMap<Uuid, Uuid>(), infer(listOf(forkA, forkB)))
    }

    // ---- 不该认亲的相似 ----

    @Test
    fun `identical short greeting and title are not evidence`() {
        val a = chat(firstUser = UIMessage.user("你好"), title = "日常")
        val b = chat(firstUser = UIMessage.user("你好"), title = "日常(1)")

        assertEquals(emptyMap<Uuid, Uuid>(), infer(listOf(a, b)))
    }

    @Test
    fun `shared leading assistant message alone is not evidence`() {
        val greeting = UIMessage.assistant("欢迎回来")
        val a = conversation(nodes = listOf(node(greeting), node(UIMessage.user("问 A"))))
        val b = conversation(nodes = listOf(node(greeting), node(UIMessage.user("问 B"))))

        assertEquals(emptyMap<Uuid, Uuid>(), infer(listOf(a, b)))
    }

    @Test
    fun `leading assistant system and tool messages are skipped before the user node`() {
        val greeting = UIMessage.assistant("欢迎回来")
        val system = UIMessage.system("你是一个助手")
        val tool = UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("工具输出")))
        val first = UIMessage.user("帮我看下这个报错")
        val leading = listOf(greeting, system, tool)
        val source = chat(firstUser = first, leading = leading)
        val fork = chat(
            firstUser = first,
            leading = leading,
            createAt = instantOf(first).plusSeconds(60),
        )

        assertEquals(mapOf(fork.id to source.id), infer(listOf(source, fork)))
    }

    @Test
    fun `empty and malformed openings are ignored instead of guessed`() {
        val emptyNodes = conversation(nodes = emptyList())
        val emptyNode = conversation(nodes = listOf(MessageNode(messages = emptyList())))
        val badSelectIndex = conversation(
            nodes = listOf(MessageNode(messages = listOf(UIMessage.user("越界")), selectIndex = 7)),
        )
        val normal = chat(firstUser = UIMessage.user("正常的一条"))

        assertEquals(
            emptyMap<Uuid, Uuid>(),
            infer(listOf(emptyNodes, emptyNode, badSelectIndex, normal)),
        )
    }

    // ---- 助手隔离 ----

    @Test
    fun `same first message id under another assistant is never merged`() {
        val first = UIMessage.user("被导到别的助手")
        val here = chat(firstUser = first)
        val there = chat(firstUser = first, assistantId = anotherAssistant)

        assertEquals(emptyMap<Uuid, Uuid>(), infer(listOf(here, there)))
    }

    @Test
    fun `families are computed per assistant`() {
        val first = UIMessage.user("两边都有")
        val hereSource = chat(firstUser = first)
        val hereFork = chat(firstUser = first, createAt = instantOf(first).plusSeconds(60))
        val thereOnly = chat(firstUser = first, assistantId = anotherAssistant)

        val result = infer(listOf(hereSource, thereOnly, hereFork))

        assertEquals(mapOf(hereFork.id to hereSource.id), result)
    }

    // ---- 显式 lineageId 优先 ----

    @Test
    fun `explicit lineage wins and two families are not forced together`() {
        val first = UIMessage.user("两边家族的开头撞上了")
        val rootA = chat(firstUser = first)
        val forkA = chat(
            firstUser = first,
            lineageId = rootA.id,
            createAt = instantOf(first).plusSeconds(60),
        )
        val rootB = chat(firstUser = first)
        val forkB = chat(
            firstUser = first,
            lineageId = rootB.id,
            createAt = instantOf(first).plusSeconds(70),
        )
        val legacy = chat(firstUser = first, createAt = instantOf(first).plusSeconds(90))

        val result = infer(listOf(rootA, forkA, rootB, forkB, legacy))

        // 证据同时指向两个既有家族 → 这条旧会话孤零零留着，谁都不并
        assertEquals(emptyMap<Uuid, Uuid>(), result)
        assertFalse(result.containsKey(rootA.id))
        assertFalse(result.containsKey(forkA.id))
    }

    @Test
    fun `legacy root is confirmed by an explicit fork without being rewritten`() {
        val first = UIMessage.user("显式根那条")
        val root = chat(firstUser = first)
        val fork = chat(
            firstUser = first,
            lineageId = root.id,
            createAt = instantOf(first).plusSeconds(60),
        )
        val oldSibling = chat(firstUser = first, createAt = instantOf(first).plusSeconds(120))

        val result = infer(listOf(root, fork, oldSibling))

        // 根自己不用写；旧分叉挂到既有的显式家族上
        assertEquals(mapOf(oldSibling.id to root.id), result)
    }

    @Test
    fun `deleted explicit root does not pull legacy conversations in`() {
        // 根已经删了：剩下的分叉留着根的 lineageId，但它们的开头消息比自己建得早，
        // 和老模板无法区分 → 宁可漏认，也不把无关旧会话吸进这个家族
        val first = UIMessage.user("根已经删掉")
        val deletedRootId = Uuid.random()
        val survivor = chat(
            firstUser = first,
            lineageId = deletedRootId,
            createAt = instantOf(first).plusSeconds(60),
        )
        val legacy = chat(firstUser = first, createAt = instantOf(first).plusSeconds(120))

        val result = infer(listOf(survivor, legacy))

        assertEquals(emptyMap<Uuid, Uuid>(), result)
        // 显式的会话本来就对，不该出现在回写清单里
        assertFalse(result.containsKey(survivor.id))
    }

    // ---- 退化输入 ----

    @Test
    fun `nothing to do for empty or single conversation input`() {
        assertEquals(emptyMap<Uuid, Uuid>(), infer(emptyList()))
        assertEquals(emptyMap<Uuid, Uuid>(), infer(listOf(chat(firstUser = UIMessage.user("就一条")))))
    }
}

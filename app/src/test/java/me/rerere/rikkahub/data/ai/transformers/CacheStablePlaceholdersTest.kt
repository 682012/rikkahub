package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CacheStablePlaceholdersTest {

    private class Fixture(maxEntries: Int = RuntimeBindingCache.DEFAULT_MAX_ENTRIES) {
        var today: LocalDate = LocalDate.of(2026, 1, 1)
        var battery: String? = "80"

        val renderer = CacheStablePlaceholders(
            cache = RuntimeBindingCache(maxEntries),
            today = { today },
            formatDate = { it.toString() },
        )

        fun run(
            messages: List<UIMessage>,
            staticKeys: List<String> = listOf("nickname"),
            resolveStatic: (String) -> String? = { key -> if (key == "nickname") "Alice" else null },
        ): List<UIMessage> = renderer.render(
            messages = messages,
            staticKeys = staticKeys,
            resolveStatic = resolveStatic,
            batteryLevel = { battery },
        )
    }

    private fun message(
        role: MessageRole,
        text: String,
        createdAt: LocalDateTime = LocalDateTime(2026, 1, 1, 10, 0),
        isSynthetic: Boolean = false,
    ) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = createdAt,
        isSynthetic = isSynthetic,
    )

    private fun textOf(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    private fun systemPrompt() = "You are helpful.\nBattery: {battery_level}\nDate: {cur_date}"

    @Test
    fun `system keeps a stable reference instead of the live value`() {
        val fixture = Fixture()
        val system = message(MessageRole.SYSTEM, systemPrompt())

        val result = fixture.run(listOf(system, message(MessageRole.USER, "hi"))).first()

        assertEquals(
            "You are helpful.\nBattery: <runtime value: battery_level>\nDate: <runtime value: cur_date>",
            textOf(result),
        )
        assertFalse(textOf(result).contains("80"))
    }

    @Test
    fun `user message gets the runtime bindings block with sorted keys`() {
        val fixture = Fixture()
        val user = message(MessageRole.USER, "hi")

        val result = fixture.run(listOf(message(MessageRole.SYSTEM, systemPrompt()), user))

        assertEquals(
            "hi\n\n<runtime_bindings>\nbattery_level: 80\ncur_date: 2026-01-01\n</runtime_bindings>",
            textOf(result.last()),
        )
    }

    @Test
    fun `tool steps inside one turn rebuild the same user message byte for byte`() {
        val fixture = Fixture()
        val system = message(MessageRole.SYSTEM, systemPrompt())
        val user = message(MessageRole.USER, "run the build")

        val firstStep = fixture.run(listOf(system, user))
        // 工具执行期间时钟跨天、电量掉到 5%
        fixture.today = LocalDate.of(2026, 1, 2)
        fixture.battery = "5"
        val thirdStep = fixture.run(
            listOf(
                system,
                user,
                message(MessageRole.ASSISTANT, "calling tool"),
                message(MessageRole.TOOL, "result"),
            )
        )

        assertEquals(textOf(firstStep.first()), textOf(thirdStep.first()))
        assertEquals(textOf(firstStep.last()), textOf(thirdStep[1]))
        assertTrue(textOf(thirdStep[1]).contains("battery_level: 80"))
        assertTrue(textOf(thirdStep[1]).contains("cur_date: 2026-01-01"))
    }

    @Test
    fun `history stays unchanged when a new user message arrives on a new day`() {
        val fixture = Fixture()
        val system = message(MessageRole.SYSTEM, systemPrompt())
        val firstUser = message(MessageRole.USER, "first")
        val firstRound = fixture.run(listOf(system, firstUser))

        fixture.today = LocalDate.of(2026, 1, 3)
        fixture.battery = "42"
        val secondUser = message(
            MessageRole.USER,
            "second",
            createdAt = LocalDateTime(2026, 1, 3, 9, 0),
        )
        val secondRound = fixture.run(
            listOf(system, firstUser, message(MessageRole.ASSISTANT, "ok"), secondUser)
        )

        // system 与上一条 user 的绑定一字不动
        assertEquals(textOf(firstRound.first()), textOf(secondRound.first()))
        assertEquals(textOf(firstRound.last()), textOf(secondRound[1]))
        assertTrue(textOf(secondRound[3]).contains("battery_level: 42"))
        assertTrue(textOf(secondRound[3]).contains("cur_date: 2026-01-03"))
    }

    @Test
    fun `history placeholders use the message date and unknown battery when nothing is cached`() {
        // 新进程/缓存已淘汰: 历史消息从没见过
        val fixture = Fixture()
        val system = message(MessageRole.SYSTEM, systemPrompt())
        val historyUser = message(
            MessageRole.USER,
            "check {cur_date} and {battery_level}",
            createdAt = LocalDateTime(2025, 12, 24, 8, 30),
        )
        val currentUser = message(
            MessageRole.USER,
            "and now?",
            createdAt = LocalDateTime(2026, 1, 1, 10, 0),
        )

        val result = fixture.run(
            listOf(system, historyUser, message(MessageRole.ASSISTANT, "ok"), currentUser)
        )

        // 历史文本按消息本地日期解析, 电量明确 unknown, 不伪造
        assertTrue(textOf(result[1]).contains("check 2025-12-24 and unknown"))
        assertFalse(textOf(result[1]).contains("2026-01-01"))
        // 历史消息不是本轮, 不追加绑定块
        assertFalse(textOf(result[1]).contains(RuntimeBindings.BLOCK_TAG))
        // 本轮 user 消息仍然拿到当前值
        assertTrue(textOf(result[3]).contains("battery_level: 80"))
    }

    @Test
    fun `other placeholders keep working including double braces and case`() {
        val fixture = Fixture()
        val messages = listOf(
            message(MessageRole.SYSTEM, "hi {nickname} / {{NICKNAME}}"),
            message(MessageRole.USER, "hello {NickName}"),
        )

        val result = fixture.run(messages)

        assertEquals("hi Alice / Alice", textOf(result.first()))
        // system 里没有易变占位符: 不新增任何 prompt
        assertEquals("hello Alice", textOf(result.last()))
    }

    @Test
    fun `no dynamic system placeholder means no bindings block at all`() {
        val fixture = Fixture()
        val messages = listOf(
            message(MessageRole.SYSTEM, "You are helpful, {nickname}"),
            message(MessageRole.USER, "just a message"),
        )

        val result = fixture.run(messages)

        assertFalse(textOf(result.last()).contains(RuntimeBindings.BLOCK_TAG))
        assertEquals("just a message", textOf(result.last()))
    }

    @Test
    fun `notice style synthetic user messages do not receive the bindings`() {
        val fixture = Fixture()
        val realUser = message(MessageRole.USER, "real")
        val notice = message(MessageRole.USER, "background job finished", isSynthetic = true)

        val result = fixture.run(listOf(message(MessageRole.SYSTEM, systemPrompt()), realUser, notice))

        assertTrue(textOf(result[1]).contains(RuntimeBindings.BLOCK_TAG))
        assertFalse(textOf(result[2]).contains(RuntimeBindings.BLOCK_TAG))
    }

    @Test
    fun `an aborted step does not change what the next step sees`() {
        val fixture = Fixture()
        val messages = listOf(message(MessageRole.SYSTEM, systemPrompt()), message(MessageRole.USER, "hi"))

        val aborted = fixture.run(messages)
        fixture.battery = "11"
        val retried = fixture.run(messages)

        assertEquals(textOf(aborted.first()), textOf(retried.first()))
        assertEquals(textOf(aborted.last()), textOf(retried.last()))
    }

    @Test
    fun `bindings are frozen per message so different turns do not share values`() {
        val fixture = Fixture()
        val system = message(MessageRole.SYSTEM, systemPrompt())
        val userA = message(MessageRole.USER, "a")
        val userB = message(MessageRole.USER, "b")

        fixture.battery = "90"
        val fromA = fixture.run(listOf(system, userA))
        fixture.battery = "10"
        val fromB = fixture.run(listOf(system, userB))

        assertTrue(textOf(fromA.last()).contains("battery_level: 90"))
        assertTrue(textOf(fromB.last()).contains("battery_level: 10"))
        // A 再发一次仍然还是 90
        val againA = fixture.run(listOf(system, userA))
        assertEquals(textOf(fromA.last()), textOf(againA.last()))
    }

    @Test
    fun `runtime binding cache stays bounded`() {
        val fixture = Fixture(maxEntries = 2)
        val system = message(MessageRole.SYSTEM, systemPrompt())
        val users = (1..3).map { message(MessageRole.USER, "msg $it") }

        users.forEach { fixture.run(listOf(system, it)) }

        assertEquals(2, fixture.renderer.cacheSize)
        // 已淘汰的消息重新作为本轮消息时按当前读数重建, 缓存不会因此增长
        fixture.battery = "33"
        val rerun = textOf(fixture.run(listOf(system, users.first()))[1])
        assertTrue(rerun.contains("battery_level: 33"))
        assertEquals(2, fixture.renderer.cacheSize)
    }

    @Test
    fun `missing battery reads as unknown instead of a fake number`() {
        val fixture = Fixture()
        fixture.battery = null

        val result = fixture.run(
            listOf(message(MessageRole.SYSTEM, systemPrompt()), message(MessageRole.USER, "hi"))
        )

        assertTrue(textOf(result.last()).contains("battery_level: ${RuntimeBindings.UNKNOWN}"))
    }

    @Test
    fun `runtime binding values are pure and sorted`() {
        val values = runtimeBindingValues(
            isCurrentTurn = true,
            createdAt = LocalDate.of(2026, 1, 1),
            now = LocalDate.of(2026, 2, 2),
            batteryLevel = "77",
            formatDate = { it.toString() },
        )
        assertEquals(listOf("battery_level", "cur_date"), values.keys.toList())
        assertEquals("77", values["battery_level"])
        assertEquals("2026-02-02", values["cur_date"])

        val history = runtimeBindingValues(
            isCurrentTurn = false,
            createdAt = LocalDate.of(2025, 5, 6),
            now = LocalDate.of(2026, 2, 2),
            batteryLevel = "77",
            formatDate = { it.toString() },
        )
        assertEquals(RuntimeBindings.UNKNOWN, history["battery_level"])
        assertEquals("2025-05-06", history["cur_date"])

        // 绑定块 key 排序, 与 map 插入顺序无关
        assertEquals(
            "<runtime_bindings>\nbattery_level: 1\ncur_date: 2\n</runtime_bindings>",
            renderRuntimeBindingsBlock(mapOf("cur_date" to "2", "battery_level" to "1")).trimStart(),
        )
    }
}

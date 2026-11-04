package me.rerere.rikkahub.data.companion

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r15：六只各有自己的欲望、小毛病和节奏；点击是现场生成，不是从 8 句里挑。
 *
 * 这里钉的都是“能看出来”的性质：六份人设不一样、overlay 不碰用户自定义、方向说明里
 * 没有可播放台词、轮换不撞车、最近一轮不会被历史预算挤掉、长度规则不再自相矛盾。
 */
class CompanionLivelyR15Test {
    private val session = Uuid.random()

    private val builtInPresets = listOf(
        FloatingCompanionPreset.DRAGON_GIRL to CompanionCatalog.GPT,
        FloatingCompanionPreset.SUN_READER to CompanionCatalog.CLAUDE,
        FloatingCompanionPreset.WHALE_MAID to CompanionCatalog.DEEPSEEK,
        FloatingCompanionPreset.GEMINI_CAT to CompanionCatalog.GEMINI,
        FloatingCompanionPreset.GLM_FOX to CompanionCatalog.GLM,
        FloatingCompanionPreset.QWEN_GIRL to CompanionCatalog.QWEN,
    )

    private fun build(
        trigger: CompanionTrigger = CompanionTrigger(CompanionTriggerKind.TAP),
        persona: String = CompanionPersonas.builtIn(FloatingCompanionPreset.WHALE_MAID),
        name: String = "小鲸",
        memory: List<CompanionExchange> = emptyList(),
        tapImprovHint: String? = null,
    ) = CompanionPromptBuilder.build(
        persona = persona,
        name = name,
        trigger = trigger,
        memory = memory,
        sessionId = session,
        languageTag = "zh-CN",
        tapImprovHint = tapImprovHint,
    )

    @Test
    fun `six built-in characters each get their own roleplay layer`() {
        val overlays = builtInPresets.map { (preset, _) -> CompanionPersonas.builtInOverlay(preset) }
        overlays.forEach { overlay ->
            assertTrue("overlay 为空", overlay.contains("想要的"))
            assertTrue(overlay.contains("小毛病"))
            assertTrue(overlay.contains("关系节奏"))
            assertTrue(overlay.contains("被戳时的本色"))
        }
        assertEquals("六只不能共用同一段叠加", 6, overlays.toSet().size)
    }

    @Test
    fun `roleplay layers have distinct wants flaws and habits`() {
        val ids = CompanionRoleplayProfiles.all.map { it.profileId }
        assertEquals(6, ids.toSet().size)
        CompanionRoleplayProfiles.all.forEach { roleplay ->
            assertTrue(roleplay.profileId, roleplay.wants.isNotBlank())
            assertTrue(roleplay.profileId, roleplay.flaw.isNotBlank())
            assertTrue(roleplay.profileId, roleplay.habit.isNotBlank())
            assertTrue(roleplay.profileId, roleplay.pacing.isNotBlank())
            assertTrue(roleplay.profileId, roleplay.tapMood.isNotBlank())
            assertTrue(roleplay.profileId, roleplay.avoid.isNotEmpty())
        }
        assertEquals(6, CompanionRoleplayProfiles.all.map { it.wants }.toSet().size)
        assertEquals(6, CompanionRoleplayProfiles.all.map { it.flaw }.toSet().size)
        assertEquals(6, CompanionRoleplayProfiles.all.map { it.habit }.toSet().size)
    }

    @Test
    fun `custom and logo keep the old fallback without any roleplay overlay`() {
        listOf(FloatingCompanionPreset.CUSTOM, FloatingCompanionPreset.LOGO).forEach { preset ->
            assertEquals("", CompanionPersonas.builtInOverlay(preset))
            assertTrue(CompanionPersonas.roleplayId(preset) == null)
            val persona = CompanionPersonas.builtIn(preset)
            assertTrue(persona.isNotBlank())
            assertFalse(persona.contains("角色补充设定"))
        }
        assertEquals("", CompanionRoleplayProfiles.textFor(null))
        assertEquals("", CompanionRoleplayProfiles.textFor("nobody"))
    }

    @Test
    fun `built-in persona keeps the catalog identity and appends the layer`() {
        builtInPresets.forEach { (preset, profile) ->
            val persona = CompanionPersonas.builtIn(preset)
            assertTrue(persona.startsWith(profile.persona.trim()))
            assertTrue(persona.contains("【角色补充设定"))
            assertTrue(persona.length > profile.persona.length)
        }
    }

    @Test
    fun `tap directions never contain a playable line`() {
        CompanionRoleplayProfiles.all.forEach { roleplay ->
            val lines = CompanionCatalog.byId(roleplay.profileId)!!.tapLines
            (0..20).forEach { turn ->
                val direction = CompanionTapImagination.direction(roleplay.profileId, turn, true)
                val text = CompanionTapImagination.hint(roleplay.profileId, turn, true)
                assertTrue(text.length <= CompanionLimits.TAP_HINT_MAX_CHARS)
                lines.forEach { line -> assertFalse("$text <- 台词 $line", text.contains(line)) }
                listOf('"', '“', '”', '「', '」', '‘', '’').forEach { quote ->
                    assertFalse(text, text.contains(quote))
                }
                assertFalse(text, text.contains("我在"))
                assertFalse(text, text.contains("别戳"))
                assertTrue(direction.label.isNotBlank())
                assertTrue(direction.instruction.isNotBlank())
                assertTrue(
                    text,
                    text.contains("不提问") || text.contains("最多问一次"),
                )
            }
        }
    }

    @Test
    fun `tap rotation does not repeat the neighbour and covers every beat`() {
        CompanionRoleplayProfiles.all.forEach { roleplay ->
            val id = roleplay.profileId
            val beats = (1..7).map { turn ->
                CompanionTapImagination.direction(id, turn, hasHistory = true).beat
            }
            assertEquals("$id 七次应该覆盖全部方向", CompanionTapImagination.beats().toSet(), beats.toSet())
            (0..30).forEach { turn ->
                assertNotEquals(
                    "$id turn=$turn 连续两次同方向",
                    CompanionTapImagination.direction(id, turn, true).beat,
                    CompanionTapImagination.direction(id, turn + 1, true).beat,
                )
            }
            val questions = (1..7).count {
                CompanionTapImagination.isQuestionBeat(CompanionTapImagination.direction(id, it, true).beat)
            }
            assertTrue("$id 不该每轮都发问", questions <= 2)
        }
    }

    @Test
    fun `callback needs a previous line and characters never share one hint`() {
        (0..30).forEach { turn ->
            assertNotEquals(
                CompanionTapBeat.CALLBACK,
                CompanionTapImagination.direction("gpt", turn, hasHistory = false).beat,
            )
        }
        val withHistory = (1..7).map { CompanionTapImagination.direction("gpt", it, true).beat }
        assertTrue(withHistory.contains(CompanionTapBeat.CALLBACK))

        val hints = CompanionRoleplayProfiles.all.map {
            CompanionTapImagination.hint(it.profileId, turn = 2, hasHistory = true)
        }
        assertEquals("同一时刻六只的方向说明应该各有味道", 6, hints.toSet().size)
        CompanionRoleplayProfiles.all.forEach { roleplay ->
            assertTrue(CompanionTapImagination.hint(roleplay.profileId, 2, true).isNotBlank())
        }
        assertTrue(CompanionTapImagination.hint(null, 2, true).isNotBlank())
    }

    @Test
    fun `tap hint only rides along with the tap trigger`() {
        val hint = CompanionTapImagination.hint("gpt", 3, true)
        val tap = build(trigger = CompanionTrigger(CompanionTriggerKind.TAP), tapImprovHint = hint)
        assertTrue(tap.userPrompt.contains("tapped"))
        assertTrue(tap.userPrompt.contains(hint))
        assertFalse(tap.systemPrompt.contains(hint))

        val input = build(
            trigger = CompanionTrigger(CompanionTriggerKind.INPUT, detail = "今天有点累"),
            tapImprovHint = hint,
        )
        assertFalse(input.userPrompt.contains(hint))
        assertTrue(input.userPrompt.contains("今天有点累"))

        val event = build(
            trigger = CompanionTrigger(CompanionTriggerKind.EVENT, event = CompanionEventKind.GENERATION_FAILED),
            tapImprovHint = hint,
        )
        assertFalse(event.userPrompt.contains(hint))
    }

    @Test
    fun `tap without a hint keeps the r14 prompt`() {
        val request = build()
        assertEquals(
            "The user just tapped you. Say one short line that fits the moment.",
            request.userPrompt,
        )
        assertFalse(request.userPrompt.contains("improvise"))
        assertTrue(request.history.isEmpty())
        assertEquals(session, request.sessionId)
    }

    @Test
    fun `history keeps the newest exchange even when older ones are huge`() {
        val fat = "x".repeat(CompanionLimits.MAX_HISTORY_CHARS - 1)
        val memory = listOf(
            CompanionExchange(fat, fat),
            CompanionExchange(fat, fat),
            CompanionExchange(fat, fat),
            CompanionExchange("最新的问题", "最新的回答"),
        )
        val request = build(memory = memory)
        assertEquals(listOf("最新的问题", "最新的回答"), request.history.map { it.text })
        assertTrue(request.history.none { it.text.startsWith("x") })
    }

    @Test
    fun `history stays in order and inside the window`() {
        val memory = (1..10).map { CompanionExchange("q-$it", "a-$it") }
        val request = build(memory = memory)
        assertEquals(CompanionLimits.MEMORY_EXCHANGES * 2, request.history.size)
        assertEquals("q-7", request.history.first().text)
        assertEquals("a-10", request.history.last().text)
        assertEquals(CompanionPromptRole.USER, request.history.first().role)
        assertEquals(CompanionPromptRole.ASSISTANT, request.history.last().role)
    }

    @Test
    fun `input text and tap hint never leak into the system prompt`() {
        val secret = "我的密码是 1234"
        val request = build(
            trigger = CompanionTrigger(CompanionTriggerKind.INPUT, detail = secret),
            tapImprovHint = CompanionTapImagination.hint("deepseek", 4, true),
        )
        assertFalse(request.systemPrompt.contains("1234"))
        assertFalse(request.systemPrompt.contains("密码"))
        assertFalse(request.systemPrompt.contains("这一轮换个说法"))
        assertTrue(request.userPrompt.contains(secret))
    }

    @Test
    fun `length rules are one range instead of the old forty character cap`() {
        val request = build()
        assertFalse(request.systemPrompt.contains("40 characters"))
        assertTrue(request.systemPrompt.contains("${CompanionLimits.REPLY_TARGET_MIN_CHARS}–${CompanionLimits.REPLY_TARGET_MAX_CHARS}"))
        assertTrue(request.systemPrompt.contains("never more than ${CompanionLimits.MAX_REPLY_CHARS}"))
        assertTrue(request.systemPrompt.contains("never reuse the previous line"))
        assertTrue(request.systemPrompt.contains("do not offer several candidate lines"))
        assertTrue(request.systemPrompt.contains("no bracketed stage directions"))
        assertTrue(request.systemPrompt.contains("我在"))
        assertTrue(request.systemPrompt.contains("zh-CN"))

        val longLine = "鲸".repeat(CompanionLimits.MAX_REPLY_CHARS + 30)
        assertEquals(CompanionLimits.MAX_REPLY_CHARS, cleanCompanionLine(longLine).length)
        assertEquals(160, CompanionLimits.MAX_REPLY_CHARS)
        // 8 句离线台词仍然不喂给模型
        CompanionRoleplayProfiles.all.forEach { roleplay ->
            val persona = CompanionPersonas.builtIn(
                builtInPresets.first { it.second.id == roleplay.profileId }.first
            )
            CompanionCatalog.byId(roleplay.profileId)!!.tapLines.forEach { line ->
                assertFalse(line, persona.contains(line))
            }
        }
    }

    @Test
    fun `custom speaking quirks are not replaced by default style rules`() {
        val persona = "每句用在呢开头，大约60字，可以用括号写小动作。"
        val request = CompanionPromptBuilder.build(persona, "阿青", CompanionTrigger(CompanionTriggerKind.TAP), emptyList(), session, "zh-CN", useBuiltInStyle = false)
        assertTrue(request.systemPrompt.contains(persona))
        assertFalse(request.systemPrompt.contains("no bracketed stage directions"))
        assertFalse(request.systemPrompt.contains("No assistant or customer-service opening"))
        assertFalse(request.systemPrompt.contains("this overrides"))
    }

    @Test
    fun `legacy replies between eighty and one hundred sixty chars stay complete`() {
        val line = "Honestly that's the most relatable thing I've heard all week, I'd have done the same thing."
        assertTrue(line.length > 80)
        assertTrue(line.length < 160)
        assertEquals(line, cleanCompanionLine(line))
    }

    @Test
    fun `direction indexing is safe at integer boundaries`() {
        for (turn in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
            val direction = CompanionTapImagination.direction("gpt", turn, true)
            assertTrue(direction.beat in CompanionTapImagination.beats())
        }
    }

}

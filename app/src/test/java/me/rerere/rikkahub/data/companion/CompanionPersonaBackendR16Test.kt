package me.rerere.rikkahub.data.companion

import java.io.File
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.datastore.CompanionModelMode
import me.rerere.rikkahub.data.datastore.FloatingCompanionLimits
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import me.rerere.rikkahub.data.datastore.characterKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r16 完整人设后端的合同测试：只读目录、默认全文/hash、单次替换、用户覆盖整段优先、
 * owner 分格保存、旧 run 版本隔离。纯 JVM，目录可以直接注入，不依赖 Android。
 */
class CompanionPersonaBackendR16Test {

    private val defaultPresets = mapOf(
        FloatingCompanionPreset.DRAGON_GIRL to "gpt",
        FloatingCompanionPreset.SUN_READER to "claude",
        FloatingCompanionPreset.WHALE_MAID to "deepseek",
        FloatingCompanionPreset.GEMINI_CAT to "gemini",
        FloatingCompanionPreset.GLM_FOX to "glm",
        FloatingCompanionPreset.QWEN_GIRL to "qwen",
    )

    private val catalog: CompanionPersonaCatalog by lazy {
        val candidates = listOf(
            // Gradle 单测的工作目录是 app/ 模块；部分纯 JVM 检查从仓库根跑
            File("src/main/assets/companions/personas/companions-personas.json"),
            File("app/src/main/assets/companions/personas/companions-personas.json"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("built-in persona catalog missing; tried ${candidates.joinToString { it.absolutePath }}")
        CompanionPersonaCatalog.parse(file.readText())
    }

    private fun installCatalog() {
        CompanionPersonas.install(catalog)
    }

    @Test
    fun `r17 formal names match ui and default prompt while custom names survive switching`() {
        installCatalog()
        val expected = mapOf("gpt" to "白祈", "claude" to "橙音", "deepseek" to "汐澜",
            "gemini" to "星璃", "glm" to "墨眠", "qwen" to "云笙")
        defaultPresets.forEach { (preset, id) ->
            assertEquals(expected[id], CompanionCatalog.portraitDisplayName(preset))
            assertEquals(expected[id], CompanionPersonas.builtInResolved(preset).name)
            assertTrue(CompanionPersonas.builtInResolved(preset).prompt.contains("【角色名】${expected[id]}"))
        }
        val custom = FloatingCompanionSettings(preset = FloatingCompanionPreset.WHALE_MAID,
            name = "我的小鲸", aiPersonaPrompt = "只保留我自己的全文")
        val restored = custom.selectPreset(FloatingCompanionPreset.DRAGON_GIRL)
            .selectPreset(FloatingCompanionPreset.WHALE_MAID).normalized()
        assertEquals("我的小鲸", restored.name)
        assertEquals("我的小鲸", CompanionPersonas.resolve(restored).name)
        assertEquals("只保留我自己的全文", CompanionPersonas.resolve(restored).prompt)
    }

    @After
    fun tearDown() {
        CompanionPersonaCatalogStore.resetForTest()
    }

    @Test
    fun `default catalog resolves six full personas with shipping hashes`() {
        installCatalog()
        assertEquals(6, catalog.entries.size)
        defaultPresets.forEach { (preset, id) ->
            val entry = catalog.entryById(id)!!
            val resolved = CompanionPersonas.resolve(
                FloatingCompanionSettings(preset = preset, aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION),
            )
            assertEquals(PersonaSource.BUILTIN, resolved.source)
            assertEquals(id, resolved.id)
            assertEquals(CompanionCatalog.profile(preset)!!.displayName, resolved.name)
            assertEquals(entry.version, resolved.version)
            // 默认名字下解析结果与 prompts/<id>.txt 逐字节相同，所以 hash 必须等于包里的 prompt_sha256
            assertEquals(entry.promptSha256, CompanionPersonaText.sha256(entry.defaultEffectivePrompt))
            assertEquals(CompanionPersonaText.render(entry.promptTemplate, resolved.name), resolved.prompt)
            assertEquals(CompanionPersonaText.sha256(resolved.prompt), resolved.hash)
            // 只读完整卡，不再叠旧 Catalog 短人设或 r15 角色叠加段
            assertFalse(resolved.prompt.contains("【角色补充设定"))
            assertEquals(preset.characterKey, resolved.ownerCharacterKey)
        }
    }

    @Test
    fun `built in resolved ignores the user override and keeps the default name`() {
        installCatalog()
        val custom = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.WHALE_MAID,
            name = "不是小鲸",
            aiPersonaPrompt = "用户自己写的另一段",
        )
        val userResolved = CompanionPersonas.resolve(custom)
        assertEquals(PersonaSource.USER_OVERRIDE, userResolved.source)

        val builtIn = CompanionPersonas.builtInResolved(FloatingCompanionPreset.WHALE_MAID)
        assertEquals("汐澜", builtIn.name)
        assertEquals(CompanionPersonaText.render(catalog.entryById("deepseek")!!.promptTemplate, "汐澜"), builtIn.prompt)
        assertFalse("内置全文不能被自定义遮住", builtIn.prompt.contains("用户自己写的另一段"))
    }

    @Test
    fun `placeholders are replaced once and never expanded twice`() {
        installCatalog()
        val plain = CompanionPersonas.resolve(
            FloatingCompanionSettings(preset = FloatingCompanionPreset.WHALE_MAID, name = "阿鲸"),
        )
        assertEquals(PersonaSource.BUILTIN, plain.source)
        // 模板里的 {{char}}/{{user}} 全部被替换掉，不会留下未展开的占位符
        assertFalse(plain.prompt.contains("{{char}}"))
        assertFalse(plain.prompt.contains("{{user}}"))
        assertTrue(plain.prompt.contains("阿鲸"))

        val tricky = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.WHALE_MAID,
            name = "小{{user}}鲸",
        )
        val resolved = CompanionPersonas.resolve(tricky)
        // 名字里的 {{user}} 原样保留，不会被二次展开成“小你鲸”
        assertFalse(resolved.prompt.contains("小你鲸"))
        assertTrue(resolved.prompt.contains("小{{user}}鲸"))
        assertFalse(resolved.prompt.contains("{{char}}"))
        // 稳定前缀：同样的设置解析两次，结果完全一样（没有随机数/时间戳）
        assertEquals(resolved.prompt, CompanionPersonas.resolve(tricky).prompt)
    }

    @Test
    fun `user override is the whole persona and is never trimmed`() {
        installCatalog()
        val raw = "  " + "鲸".repeat(FloatingCompanionLimits.MAX_PERSONA_LENGTH - 4) + "\n "
        val settings = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.WHALE_MAID,
            aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION,
        ).withPersonaOverride("whale_maid", raw)

        assertEquals(raw, settings.aiPersonaPrompt)
        val resolved = CompanionPersonas.resolve(settings)
        assertEquals(PersonaSource.USER_OVERRIDE, resolved.source)
        assertEquals(raw, resolved.prompt)
        assertEquals(CompanionPersonaText.sha256(raw), resolved.hash)
        assertEquals("汐澜", resolved.name)
        // 用户覆盖就是这一整段：不 append 内置正文，也不借旧 Catalog/角色叠加段
        val builtInEntry = catalog.entryById("deepseek")!!
        assertFalse(resolved.prompt.contains(builtInEntry.promptTemplate.take(60)))
        assertFalse(resolved.prompt.contains("【角色补充设定"))
    }

    @Test
    fun `saving above the limit fails without touching the existing persona`() {
        val settings = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.WHALE_MAID,
            aiPersonaPrompt = "原来的那段",
        )
        val tooLong = "鲸".repeat(FloatingCompanionLimits.MAX_PERSONA_LENGTH + 1)
        assertTrue(
            runCatching { settings.withPersonaOverride("whale_maid", tooLong) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertEquals("原来的那段", settings.aiPersonaPrompt)
    }

    @Test
    fun `override saved for another owner does not leak into the selected one`() {
        installCatalog()
        val sunReader = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.SUN_READER,
            aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION,
        )
        val edited = sunReader.withPersonaOverride("whale_maid", "只属于小鲸")
        // 当前选中的小橙仍是完整内置卡
        val resolved = CompanionPersonas.resolve(edited)
        assertEquals(PersonaSource.BUILTIN, resolved.source)
        assertEquals("claude", resolved.id)
        assertEquals("橙音", resolved.name)
        // 切到小鲸时才用那一格；小橙自己的覆盖格没有被写
        val switched = edited.selectPreset(FloatingCompanionPreset.WHALE_MAID)
        assertEquals(PersonaSource.USER_OVERRIDE, CompanionPersonas.resolve(switched).source)
        assertEquals("只属于小鲸", CompanionPersonas.resolve(switched).prompt)
        assertEquals("", edited.personaOverrideOf("sun_reader"))
    }

    @Test
    fun `custom and logo keep the old fallback when the catalog is installed`() {
        installCatalog()
        listOf(FloatingCompanionPreset.LOGO, FloatingCompanionPreset.CUSTOM).forEach { preset ->
            val fallback = CompanionPersonas.builtInResolved(preset)
            assertEquals(PersonaSource.FALLBACK, fallback.source)
            assertNull(fallback.id)
            assertTrue(fallback.prompt.isNotBlank())
        }
        // 旧 cat 仍是小鲸，不会被有猫耳的小星顶替
        assertEquals("deepseek", CompanionPersonas.builtInResolved(FloatingCompanionPreset.CAT).id)
        assertEquals("gpt", CompanionPersonas.builtInResolved(FloatingCompanionPreset.DRAGON).id)
    }

    @Test
    fun `system prompt builder shares the same app boundary for ui and request`() {
        val persona = "测试人设正文"
        val fromUi = CompanionPromptBuilder.buildSystemPrompt(persona, "小白龙", "zh-CN")
        val request = CompanionPromptBuilder.build(
            persona = persona,
            name = "小白龙",
            trigger = CompanionTrigger(CompanionTriggerKind.TAP),
            memory = emptyList(),
            sessionId = Uuid.random(),
            languageTag = "zh-CN",
            useBuiltInStyle = false,
        )
        assertEquals(fromUi, request.systemPrompt)
        assertTrue(fromUi.contains("The selected persona above defines your identity"))
        assertTrue(fromUi.contains("cannot see the user's chats"))
    }

    @Test
    fun `a catalog version change invalidates the old run before it can write back`() = runTest {
        installCatalog()
        val source = RecordingSource()
        val gate = CompletableDeferred<Unit>()
        source.onGenerate = {
            try {
                gate.await()
            } catch (_: Throwable) {
                // 不合作的 source：取消被吞掉，照样返回“成品”
            }
            "旧版本的迟到台词"
        }
        val memory = CompanionMemory(capacity = 4)
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            memory = memory,
        )
        val settings = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.WHALE_MAID,
            aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION,
        )
        controller.setVisible(true)
        assertTrue(controller.submit(CompanionTrigger(CompanionTriggerKind.TAP), settings))
        runCurrent()
        assertEquals(CompanionAiPhase.THINKING, controller.state.value.phase)

        // 同一份正文、只升版本号：旧 run 必须立刻失效
        CompanionPersonas.install(
            CompanionPersonaCatalog(
                entries = catalog.entries.map { it.copy(version = "1.0.1") },
                packVersion = "1.0.1",
                schema = "six-companions-personas/v1",
            ),
        )
        controller.personaCatalogChanged()
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("迟到的旧版本台词不能回填", CompanionAiPhase.IDLE, controller.state.value.phase)
        assertEquals("", controller.state.value.text)
        assertEquals(0, memory.size)
        assertTrue(controller.interactions.value.none { it.phase == CompanionAiPhase.COMPLETED })
    }

    @Test
    fun `request uses the same normalized persona as the settings viewer`() = runTest {
        installCatalog()
        val source = RecordingSource()
        val settings = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.CUSTOM,
            customImagePath = "https://invalid.example/image.png",
            aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION,
        )
        val expected = CompanionPersonas.resolve(settings.normalized())
        val controller = CompanionAiController(scope = this, source = source,
            clock = { testScheduler.currentTime })
        controller.setVisible(true)
        assertTrue(controller.submit(CompanionTrigger(CompanionTriggerKind.TAP), settings))
        advanceUntilIdle()
        assertEquals(1, source.requests.size)
        assertEquals(expected, source.requests.single().resolvedPersona)
        assertEquals(PersonaSource.BUILTIN, source.requests.single().resolvedPersona!!.source)
        assertTrue(source.requests.single().systemPrompt.contains(expected.prompt))
    }

    @Test
    fun `catalog change during model lookup does not send an outdated paid request`() = runTest {
        installCatalog()
        val gate = CompletableDeferred<Unit>()
        val source = RecordingSource().apply { beforeDescribe = { gate.await() } }
        val settings = FloatingCompanionSettings(aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION)
        val controller = CompanionAiController(scope = this, source = source,
            clock = { testScheduler.currentTime })
        controller.setVisible(true)
        assertTrue(controller.submit(CompanionTrigger(CompanionTriggerKind.TAP), settings))
        runCurrent()
        CompanionPersonas.install(CompanionPersonaCatalog(catalog.entries.map { it.copy(version = "next") }))
        // Deliberately do not dispatch the UI revision observer before lookup resumes.
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, source.requests.size)
        assertEquals(CompanionAiPhase.IDLE, controller.state.value.phase)
    }

    private class RecordingSource : CompanionReplySource {
        var beforeDescribe: suspend () -> Unit = {}
        var onGenerate: suspend () -> String = { "好" }
        val requests = mutableListOf<CompanionRequest>()

        override suspend fun describeModel(conversationId: Uuid?): CompanionModelDescription {
            beforeDescribe()
            return CompanionModelDescription(
                label = "test-model · Test",
                source = CompanionModelMode.FOLLOW_CONVERSATION,
            )
        }

        override suspend fun generate(
            conversationId: Uuid?,
            request: CompanionRequest,
            onDelta: (CompanionDelta) -> Unit,
        ): String {
            requests += request
            return onGenerate()
        }
    }
}

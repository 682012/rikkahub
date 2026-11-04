package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WorkspaceReminderTransformerTest {

    @Test
    fun `os-release should prefer pretty name`() {
        val content = """
            NAME="Alpine Linux"
            ID=alpine
            VERSION_ID=3.24.1
            PRETTY_NAME="Alpine Linux v3.24"
            HOME_URL="https://alpinelinux.org/"
        """.trimIndent()
        assertEquals("Alpine Linux v3.24", parseOsReleaseName(content))
    }

    @Test
    fun `os-release should fall back to name and version`() {
        val content = """
            # comment
            NAME='Ubuntu'
            VERSION_ID="24.04"
        """.trimIndent()
        assertEquals("Ubuntu 24.04", parseOsReleaseName(content))
    }

    @Test
    fun `os-release without name should return null`() {
        assertNull(parseOsReleaseName("ID=unknown\nnot a key value line\n"))
    }

    @Test
    fun `os-release name should be sanitized and truncated`() {
        val name = parseOsReleaseName("PRETTY_NAME=\"Distro\u0007 ${"x".repeat(200)}\"")!!
        assertEquals(80, name.length)
        assertEquals("Distro x", name.take(8))
    }

    @Test
    fun `remote environment should be loaded once per config`() = runTest {
        val cache = RemoteEnvironmentCache()
        var loads = 0
        val load = suspend { RemoteEnvironment("/bin/bash", "Debian ${++loads}") }

        assertEquals("Debian 1", cache.get("w", "config-a", load).getOrThrow().distro)
        assertEquals("Debian 1", cache.get("w", "config-a", load).getOrThrow().distro)
        // 连接配置改了, 可能已经换了一台机器
        assertEquals("Debian 2", cache.get("w", "config-b", load).getOrThrow().distro)
        assertEquals(2, loads)
    }

    @Test
    fun `remote failure should be reused until it expires`() = runTest {
        var now = 0L
        val cache = RemoteEnvironmentCache(failureTtlMillis = 60_000, now = { now })
        var loads = 0
        val failing = suspend {
            loads++
            throw IOException("unreachable")
        }

        assertTrue(cache.get("w", "config", failing).isFailure)
        now = 59_999
        assertTrue(cache.get("w", "config", failing).isFailure)
        assertEquals(1, loads)

        now = 60_000
        val recovered = cache.get("w", "config") { RemoteEnvironment("/bin/sh", null) }
        assertEquals("/bin/sh", recovered.getOrThrow().shell)
    }

    @Test
    fun `agents prompt is reused byte for byte while the file stamps are unchanged`() = runTest {
        val cache = AgentsPromptCache()
        var loads = 0
        var stamps = listOf(AgentsFileStamp("/workspace/AGENTS.md", modifiedAt = 1_000, size = 42))
        val load = suspend { "instructions ${++loads}" }

        assertEquals("instructions 1", cache.get("w", "/workspace", { stamps }, load))
        assertEquals("instructions 1", cache.get("w", "/workspace", { stamps }, load))
        assertEquals(1, loads)

        // mtime 变了说明 agent 自己或用户改过文件, 下一次要重新读
        stamps = listOf(AgentsFileStamp("/workspace/AGENTS.md", modifiedAt = 2_000, size = 42))
        assertEquals("instructions 2", cache.get("w", "/workspace", { stamps }, load))
        assertEquals(2, loads)
    }

    @Test
    fun `agents prompt cache is per working directory`() = runTest {
        val cache = AgentsPromptCache()
        var loads = 0
        val stamps = listOf(AgentsFileStamp("/workspace/proj/AGENTS.md", modifiedAt = 1_000, size = 10))
        val load = suspend { "instructions ${++loads}" }

        assertEquals("instructions 1", cache.get("w", "/workspace", { stamps }, load))
        assertEquals("instructions 2", cache.get("w", "/workspace/proj", { stamps }, load))
        assertEquals("instructions 1", cache.get("w", "/workspace", { stamps }, load))
        assertEquals(2, loads)
    }

    @Test
    fun `agents prompt is not cached when the file stamps are unknown`() = runTest {
        val cache = AgentsPromptCache()
        var loads = 0
        val load = suspend { "instructions ${++loads}" }

        // 没有本地落点时判断不了新旧, 宁可每步重读
        assertEquals("instructions 1", cache.get("w", "/workspace", { null }, load))
        assertEquals("instructions 2", cache.get("w", "/workspace", { null }, load))
        assertEquals(2, loads)
    }

    @Test
    fun `stale stamps are revalidated once the entry is old enough`() = runTest {
        var now = 1_000L
        val cache = AgentsPromptCache(revalidateMillis = 30_000, now = { now })
        val stamps = listOf(AgentsFileStamp("/workspace/AGENTS.md", modifiedAt = 1_000, size = 42))

        // 文件在 mtime 粒度内被改过: stamp 没变, 内容其实换了
        var content = "instructions v1"
        var loads = 0
        val load = suspend { loads++; content }

        assertEquals("instructions v1", cache.get("w", "/workspace", { stamps }, load))
        assertEquals("instructions v1", cache.get("w", "/workspace", { stamps }, load))
        assertEquals(1, loads)

        content = "instructions v2"
        now = 1_000 + 29_999
        assertEquals("instructions v1", cache.get("w", "/workspace", { stamps }, load))

        // 到点复核: 重新读一次, 内容变了就用新的, 不会一直沿用旧内容
        now = 1_000 + 30_000
        assertEquals("instructions v2", cache.get("w", "/workspace", { stamps }, load))
        assertEquals(2, loads)
        assertEquals("instructions v2", cache.get("w", "/workspace", { stamps }, load))
    }

    @Test
    fun `stale entries are dropped instead of served when the stamps can no longer be checked`() = runTest {
        val cache = AgentsPromptCache(now = { 1_000 })
        val stamps = listOf(AgentsFileStamp("/workspace/AGENTS.md", modifiedAt = 1_000, size = 42))

        assertEquals("v1", cache.get("w", "/workspace", { stamps }, { "v1" }))
        // 这次判断不了新旧, 不能把上次的 v1 顶上来
        assertEquals("v2", cache.get("w", "/workspace", { null }, { "v2" }))
        assertEquals("v3", cache.get("w", "/workspace", { stamps }, { "v3" }))
    }

    @Test
    fun `the cache keeps a bounded number of working directories`() = runTest {
        var now = 0L
        val cache = AgentsPromptCache(maxEntries = 4, now = { now })
        repeat(10) { index ->
            now += 1_000
            cache.get("w", "/workspace/proj$index", { stampsOf(1_000) }, { "instructions $index" })
        }

        // 最老的目录已被淘汰: 再问它就要重新读, 不是无限堆积
        assertEquals("reloaded", cache.get("w", "/workspace/proj", { stampsOf(1_000) }, { "reloaded" }))
        // 最近用过的几个还在缓存里
        assertEquals("instructions 9", cache.get("w", "/workspace/proj9", { stampsOf(1_000) }, { "reloaded" }))
    }

    private fun stampsOf(modifiedAt: Long) =
        listOf(AgentsFileStamp("/workspace/AGENTS.md", modifiedAt = modifiedAt, size = 42))

    @Test
    fun `identical AGENTS dot md content is injected once`() {
        val prompt = buildAgentsInstructions(
            listOf(
                AgentsSource("/root/.agents/AGENTS.md", "Use pnpm.\n"),
                AgentsSource("/workspace/AGENTS.md", "Use pnpm.\n\n"),
                AgentsSource("/workspace/proj/AGENTS.md", "Run ./gradlew test before committing.\n"),
            )
        )

        // 同一份内容出现在多个位置(软链/复制的模板)时只注入一次, 不重复占上下文
        assertEquals(1, prompt.split("Use pnpm.").size - 1)
        assertEquals(2, prompt.split("AGENTS.md source:").size - 1)
        assertTrue(prompt.contains("AGENTS.md source: /root/.agents/AGENTS.md"))
        assertTrue(prompt.contains("AGENTS.md source: /workspace/proj/AGENTS.md"))
    }

    @Test
    fun `nothing is injected when there is no AGENTS dot md`() {
        assertEquals("", buildAgentsInstructions(emptyList()))
        assertEquals("", buildAgentsInstructions(listOf(AgentsSource("/workspace/AGENTS.md", "  \n"))))
    }

    @Test
    fun `an oversized AGENTS dot md is truncated with a pointer to the file`() {
        val long = (1..20_000).joinToString("\n") { "line $it" }

        val prompt = buildAgentsInstructions(listOf(AgentsSource("/workspace/AGENTS.md", long)))

        assertTrue(prompt.contains("truncated"))
        assertTrue(prompt.contains("workspace_read_file(path=\"/workspace/AGENTS.md\")"))
        assertTrue(prompt.length < MAX_AGENTS_TOTAL_CHARS + 2_000)
        // 截断处退到完整行, 不留半行
        val tail = prompt.substringAfter("<workspace_instructions>").substringBefore("[truncated").trim()
        assertTrue(tail.lineSequence().last().matches(Regex("line \\d+")))
    }

    @Test
    fun `files beyond the size budget are listed instead of silently dropped`() {
        val prompt = buildAgentsInstructions(
            listOf(
                AgentsSource("/root/.agents/AGENTS.md", "x".repeat(MAX_AGENTS_TOTAL_CHARS)),
                AgentsSource("/workspace/AGENTS.md", "later instructions"),
            )
        )

        assertTrue(prompt.contains("Not included (size budget): /workspace/AGENTS.md"))
        assertFalse(prompt.contains("later instructions"))
    }

    @Test
    fun `tool use guidance is static and follows the workspace backend`() {
        val local = StringBuilder().apply { appendToolUseGuidance(ToolUseGuidance.LOCAL) }.toString()
        val localAgain = StringBuilder().apply { appendToolUseGuidance(ToolUseGuidance.LOCAL) }.toString()
        assertEquals(local, localAgain)

        // 四条要点都在: 并行、先搜后读、现成入口优先、不重读、失败换路
        assertTrue(local.contains("parallel"))
        assertTrue(local.contains("workspace_grep"))
        assertTrue(local.contains("workspace_glob"))
        assertTrue(local.contains("offset"))
        assertTrue(local.contains("entry point"))
        assertTrue(local.contains("never repeat an identical call"))

        // SSH 工作区没有 workspace_grep / workspace_glob, 不能提这两个工具
        val ssh = StringBuilder().apply { appendToolUseGuidance(ToolUseGuidance.SSH) }.toString()
        assertFalse(ssh.contains("workspace_grep"))
        assertFalse(ssh.contains("workspace_glob"))
        assertTrue(ssh.contains("workspace_shell"))
        assertTrue(ssh.contains("offset"))

        // HTTP 远程执行只有 shell 与 job 工具: 不能提读文件与搜索工具
        val shellOnly = StringBuilder().apply { appendToolUseGuidance(ToolUseGuidance.SHELL_ONLY) }.toString()
        assertFalse(shellOnly.contains("workspace_read_file"))
        assertFalse(shellOnly.contains("workspace_grep"))
        assertTrue(shellOnly.contains("workspace_shell"))

        // 英文 120 词以内
        listOf(local, ssh, shellOnly).forEach { text ->
            val words = text.split(Regex("[^A-Za-z0-9_`/]+")).filter { it.isNotBlank() }
            assertTrue("${words.size} words", words.size <= 120)
        }
    }
}

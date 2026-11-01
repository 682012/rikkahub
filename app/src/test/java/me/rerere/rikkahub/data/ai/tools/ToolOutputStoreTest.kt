package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FileFolders
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.coroutines.cancellation.CancellationException

/**
 * 大工具输出外置 + 分页读回。
 *
 * 全部用临时目录，不碰 Android Context：外置/读回是纯文件逻辑，真正的风险点在
 * 「全文能不能拼回来」「别人的输出能不能读到」「写盘失败会不会把输出弄丢」，
 * 这三件事都能在 JVM 上直接验。
 */
class ToolOutputStoreTest {
    private lateinit var root: File
    private lateinit var dir: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "tool-output-store-test-${UUID.randomUUID()}")
        dir = File(root, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---------------------------------------------------------------- helpers

    private fun newStore(owner: String = OWNER) = ToolOutputStore(dir, owner)

    private fun toolReturning(
        parts: List<UIMessagePart>,
        name: String = "workspace_shell",
    ): Tool = Tool(name = name, description = "fake tool") { parts }

    private fun call(tool: Tool, args: JsonObject): List<UIMessagePart> = runBlocking { tool.execute(args) }

    private fun textOf(parts: List<UIMessagePart>): String =
        parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    private fun outputIdIn(text: String): String {
        val match = Regex("\"output_id\":\"([0-9a-fA-F-]{36})\"").find(text)
        assertNotNull("no output_id in: $text", match)
        return match!!.groupValues[1]
    }

    private fun offloadedId(store: ToolOutputStore, text: String): String =
        outputIdIn(textOf(call(store.wrap(toolReturning(listOf(UIMessagePart.Text(text)))), JsonObject(emptyMap()))))

    private fun readPage(tool: Tool, id: String, offset: Int? = null, limit: Int? = null): JsonObject {
        val args = buildJsonObject {
            put("output_id", id)
            offset?.let { put("offset", it) }
            limit?.let { put("limit", it) }
        }
        return Json.parseToJsonElement(textOf(call(tool, args))).jsonObject
    }

    /** 20K 左右的真实中文 + emoji 文本，UTF-8 多字节与代理对都在里面。 */
    private fun bigText(minChars: Int = 20_000): String = buildString {
        var line = 0
        while (length < minChars) {
            append("第").append(line).append("行：中文内容🙂\temoji 与制表符\n")
            line++
        }
    }

    /** 纯 ASCII、定长行：需要精确字符偏移的用例用它，页边界不会碰到代理对。 */
    private fun flatText(minChars: Int = 20_000): String = buildString {
        while (length < minChars) append("0123456789")
    }

    // ---------------------------------------------------------------- wrap

    @Test
    fun `small output passes through untouched`() {
        val parts = listOf(UIMessagePart.Text("hello"), UIMessagePart.Image("file:///a.png"))
        val out = call(newStore().wrap(toolReturning(parts)), JsonObject(emptyMap()))
        assertEquals(parts, out)
    }

    @Test
    fun `threshold applies to the total across text parts`() {
        // 单条 9K 都不到阈值，合计 18K 必须外置
        val parts = listOf(UIMessagePart.Text("a".repeat(9_000)), UIMessagePart.Text("b".repeat(9_000)))
        val out = call(newStore().wrap(toolReturning(parts)), JsonObject(emptyMap()))
        assertTrue(textOf(out).contains("[Tool output offloaded"))
        assertEquals(1, out.count { it is UIMessagePart.Text })
    }

    @Test
    fun `preview keeps head and tail of the full text`() {
        val full = "H".repeat(9_000) + "MIDDLE-MARKER" + "T".repeat(9_000)
        val notice = textOf(call(newStore().wrap(toolReturning(listOf(UIMessagePart.Text(full)))), JsonObject(emptyMap())))
        assertTrue(notice.contains(full.take(200)))
        assertTrue(notice.contains(full.takeLast(200)))
        assertFalse(notice.contains("MIDDLE-MARKER"))
        // 预览必须真的比原文小，否则外置毫无意义
        assertTrue("preview ${notice.length} should be smaller than ${full.length}", notice.length < full.length)
        assertTrue(notice.contains("--- head"))
        assertTrue(notice.contains("--- tail"))
    }

    @Test
    fun `non text parts survive the offload`() {
        val image = UIMessagePart.Image("file:///img.png")
        val document = UIMessagePart.Document("file:///a.pdf", "a.pdf")
        val out = call(
            newStore().wrap(toolReturning(listOf(UIMessagePart.Text(bigText()), image, document))),
            JsonObject(emptyMap()),
        )
        assertTrue(out.first() is UIMessagePart.Text)
        assertEquals(listOf(image, document), out.filterNot { it is UIMessagePart.Text })
    }

    @Test
    fun `wrapper keeps the original tool metadata`() {
        val original = Tool(
            name = "workspace_shell",
            description = "run a shell command",
            parameters = { InputSchema.Obj(buildJsonObject { put("command", buildJsonObject { put("type", "string") }) }, listOf("command")) },
            systemPrompt = { _, _ -> "system prompt" },
            needsApproval = { true },
        ) { emptyList() }

        val wrapped = newStore().wrap(original)
        assertEquals(original.name, wrapped.name)
        assertEquals(original.description, wrapped.description)
        assertNotNull(wrapped.parameters())
        // 审批语义与提示词原样继承（同一个 lambda 实例）
        assertSame(original.systemPrompt, wrapped.systemPrompt)
        assertSame(original.needsApproval, wrapped.needsApproval)
        assertTrue(wrapped.needsApproval(JsonObject(emptyMap())))
    }

    @Test
    fun `search_web stays exempt from offloading`() {
        val parts = listOf(UIMessagePart.Text("{\"results\":\"".padEnd(20_000, 'x')))
        val out = call(newStore().wrap(toolReturning(parts, name = "search_web")), JsonObject(emptyMap()))
        assertEquals(parts, out)
    }

    @Test
    fun `reader tool is never wrapped into itself`() {
        val store = newStore()
        val reader = store.createReaderTool()
        assertEquals(TOOL_OUTPUT_READ_TOOL_NAME, reader.name)
        assertSame(reader, store.wrap(reader))
        // 名字撞车后加序号也不该被当成普通工具再包一层
        val renamed = store.createReaderTool(name = "${TOOL_OUTPUT_READ_TOOL_NAME}_2")
        assertSame(renamed, store.wrap(renamed))

        // 即便读回来的一页很大，也不能再被外置一次
        val id = offloadedId(store, bigText())
        val page = textOf(call(store.wrap(store.createReaderTool()), buildJsonObject { put("output_id", id); put("limit", TOOL_OUTPUT_READ_MAX_LIMIT) }))
        assertFalse(page.contains("[Tool output offloaded"))
        assertTrue(page.contains("\"text\""))
    }

    // ---------------------------------------------------------------- 读回

    @Test
    fun `large utf8 output is fully recoverable by paging`() {
        val full = bigText()
        val store = newStore()
        val notice = textOf(call(store.wrap(toolReturning(listOf(UIMessagePart.Text(full)))), JsonObject(emptyMap())))
        val id = outputIdIn(notice)

        // 磁盘上落的就是完整的 UTF-8 全文
        val file = File(dir, "$id.txt")
        assertTrue(file.isFile)
        assertEquals(full, file.readText(Charsets.UTF_8))

        val reader = store.createReaderTool()
        val rebuilt = StringBuilder()
        var offset = 0
        var pages = 0
        while (true) {
            val json = readPage(reader, id, offset = offset, limit = 4_000)
            assertEquals(full.length, json["totalChars"]!!.jsonPrimitive.content.toInt())
            assertEquals(offset, json["offset"]!!.jsonPrimitive.content.toInt())
            rebuilt.append(json["text"]!!.jsonPrimitive.content)
            pages++
            assertTrue("too many pages: $pages", pages < 100)
            if (json["eof"]!!.jsonPrimitive.content.toBoolean()) {
                assertEquals(JsonNull, json["nextOffset"])
                break
            }
            offset = json["nextOffset"]!!.jsonPrimitive.content.toInt()
            assertTrue(offset > 0)
        }
        assertEquals(full, rebuilt.toString())
    }

    @Test
    fun `pages do not split surrogate pairs`() {
        // 让每页边界都落在 emoji 中间：offset 故意给奇数位置
        val full = "🙂".repeat(9_000) // 18000 chars，超过阈值
        val store = newStore()
        val id = offloadedId(store, full)
        val reader = store.createReaderTool()
        val json = readPage(reader, id, offset = 1, limit = 4_000)
        val page = json["text"]!!.jsonPrimitive.content
        assertFalse(page.first().isLowSurrogate())
        assertTrue(page.length >= 4_000)
    }

    @Test
    fun `offset past the end returns an empty final page`() {
        val full = bigText()
        val store = newStore()
        val id = offloadedId(store, full)
        val json = readPage(store.createReaderTool(), id, offset = full.length + 500)
        assertEquals("", json["text"]!!.jsonPrimitive.content)
        assertTrue(json["eof"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(JsonNull, json["nextOffset"])
        assertEquals(full.length, json["totalChars"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `id is stable and printed in machine readable form`() {
        val full = bigText()
        val notice = textOf(call(newStore().wrap(toolReturning(listOf(UIMessagePart.Text(full)))), JsonObject(emptyMap())))
        val id = outputIdIn(notice)
        assertTrue(notice.contains("\"totalChars\":${full.length}"))
        assertTrue(notice.contains("/${FileFolders.TOOL_OUTPUTS}/$id.txt"))
        assertTrue(notice.contains("{\"output_id\":\"$id\",\"offset\":0,\"limit\":$TOOL_OUTPUT_READ_DEFAULT_LIMIT}"))
    }

    // ---------------------------------------------------------------- 隔离与校验

    @Test
    fun `same conversation can read the output after a restart with a fresh store`() {
        val full = flatText()
        val id = offloadedId(newStore(), full)
        // 冷启动清理按天/按量清理，不再整目录删——所以这条才是真实的"重启进程"路径
        pruneToolOutputs(dir)
        // 新进程/新一轮请求：只有磁盘上的 manifest，没有任何内存状态
        val json = readPage(ToolOutputStore(dir, OWNER).createReaderTool(), id, limit = 100)
        assertEquals(id, json["output_id"]!!.jsonPrimitive.content)
        assertEquals(full.substring(0, 100), json["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `expired output is pruned and reported as cleaned up`() {
        val full = flatText()
        val id = offloadedId(newStore(), full)
        // 把两个文件的 mtime 推到保留期之外：冷启动清理按 manifest 的 mtime 判断
        val expired = System.currentTimeMillis() - (TOOL_OUTPUT_RETENTION_DAYS + 1) * 86_400_000L
        assertTrue(File(dir, "$id.json").setLastModified(expired))
        assertTrue(File(dir, "$id.txt").setLastModified(expired))

        pruneToolOutputs(dir)

        assertFalse(File(dir, "$id.json").exists())
        assertFalse(File(dir, "$id.txt").exists())
        val response = textOf(call(newStore().createReaderTool(), buildJsonObject { put("output_id", id) }))
        // 文案必须说"已被清理"，否则模型会拿同一个 id 反复重试
        assertTrue(response, response.contains("cleaned up"))
        assertFalse(response.contains("\"text\""))
    }

    @Test
    fun `rotation keeps the newest outputs and drops their manifests with them`() {
        val store = newStore()
        val ids = (1..5).map { offloadedId(store, flatText() + "\noutput $it") }
        // 提前把 mtime 拉成递增顺序，避免同一毫秒内写入导致排序不稳定
        ids.forEachIndexed { index, id ->
            val stamp = System.currentTimeMillis() - (ids.size - index) * 1_000L
            File(dir, "$id.json").setLastModified(stamp)
            File(dir, "$id.txt").setLastModified(stamp)
        }

        pruneToolOutputs(dir, maxFiles = 2, retentionDays = 0)

        val left = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }
        assertEquals(ids.takeLast(2).toSet(), left.toSet())
        // manifest 被删时全文一起删，别留孤儿
        ids.takeLast(2).forEach { assertTrue(File(dir, "$it.txt").isFile) }
        ids.take(3).forEach { assertFalse(File(dir, "$it.txt").exists()) }
    }

    @Test
    fun `owner is conversation scoped and ignores the assistant`() {
        val conversation = Uuid.random()
        // 同一个会话永远得到同一个 namespace，换助手也不变（owner 里不能有 assistant.id）
        assertEquals(toolOutputOwnerOf(conversation), toolOutputOwnerOf(conversation))
        assertTrue(toolOutputOwnerOf(conversation).startsWith("conversation:"))
        assertEquals("conversation:unscoped", toolOutputOwnerOf(null))
        // 不同会话互不可读
        val other = Uuid.random()
        assertFalse(toolOutputOwnerOf(conversation) == toolOutputOwnerOf(other))

        val id = offloadedId(ToolOutputStore(dir, toolOutputOwnerOf(conversation)), bigText())
        val denied = textOf(
            call(
                ToolOutputStore(dir, toolOutputOwnerOf(other)).createReaderTool(),
                buildJsonObject { put("output_id", id) },
            )
        )
        assertTrue(denied, denied.contains("another conversation"))
    }

    @Test
    fun `another conversation cannot read the output`() {
        val id = offloadedId(ToolOutputStore(dir, "conv-a"), bigText())
        val response = textOf(
            call(
                ToolOutputStore(dir, "conv-b").createReaderTool(),
                buildJsonObject { put("output_id", id) },
            )
        )
        assertTrue(response, response.contains("another conversation"))
        assertFalse(response.contains("\"text\""))
    }

    @Test
    fun `reader rejects non uuid ids and path traversal`() {
        val reader = newStore().createReaderTool()
        listOf(
            "",
            "abc",
            "..",
            "../manifest",
            "../../secret.txt",
            "/etc/passwd",
            "${UUID.randomUUID()}/../../etc/passwd",
        ).forEach { bad ->
            val response = textOf(call(reader, buildJsonObject { put("output_id", bad) }))
            assertTrue("`$bad` should be rejected, got: $response", response.contains("must be") || response.contains("required"))
        }
    }

    @Test
    fun `unknown uuid is reported as unknown`() {
        val response = textOf(
            call(newStore().createReaderTool(), buildJsonObject { put("output_id", UUID.randomUUID().toString()) })
        )
        assertTrue(response, response.contains("Unknown"))
    }

    @Test
    fun `output whose manifest vanished is reported as cleaned up on restart`() {
        val full = flatText()
        val id = offloadedId(newStore(), full)
        // 只丢 manifest（写盘中断、目录被外部动过）：全文还在，但没有 manifest 就证明不了归属
        assertTrue(File(dir, "$id.json").delete())

        val response = textOf(
            call(ToolOutputStore(dir, OWNER).createReaderTool(), buildJsonObject { put("output_id", id) })
        )
        // 没有 manifest 也要说清「读不到是因为清理/失效」，不能让模型拿同一个 id 反复重试
        assertTrue(response, response.contains("cleaned up"))
        assertFalse(response, response.contains("\"text\""))
        // 全文一个字都不许回：manifest 丢了就没有 owner 可校验
        assertFalse(response, response.contains(full.take(50)))
    }

    @Test
    fun `unknown and cleaned up ids get the same non committing answer`() {
        val id = offloadedId(newStore(), flatText())
        val expired = System.currentTimeMillis() - (TOOL_OUTPUT_RETENTION_DAYS + 1) * 86_400_000L
        assertTrue(File(dir, "$id.json").setLastModified(expired))
        assertTrue(File(dir, "$id.txt").setLastModified(expired))
        pruneToolOutputs(dir)
        // 清理后不留墓碑：目录里不该再有记录这个 id 的文件（磁盘增长必须有界）
        assertFalse(dir.listFiles().orEmpty().any { it.name.contains(id) })

        val unknownId = UUID.randomUUID().toString()
        val reader = newStore().createReaderTool()
        val filesBefore = dir.listFiles().orEmpty().size
        val cleaned = textOf(call(reader, buildJsonObject { put("output_id", id) }))
        val unknown = textOf(call(reader, buildJsonObject { put("output_id", unknownId) }))
        // 读失败不落任何文件
        assertEquals(filesBefore, dir.listFiles().orEmpty().size)

        // 「曾经存在但被清理」和「id 根本不存在」必须给一模一样的回答，否则清理历史就成了存在性探针
        assertEquals(cleaned.replace(id, "<id>"), unknown.replace(unknownId, "<id>"))
        // 也不编造 manifest 里的信息（工具名、总字符数都已经随 manifest 删掉了）
        assertFalse(cleaned, cleaned.contains("workspace_shell"))
        assertTrue(cleaned, cleaned.contains("Unknown"))
        assertTrue(cleaned, cleaned.contains("cleaned up"))
        assertFalse(cleaned, cleaned.contains("\"text\""))
    }

    @Test
    fun `reader rejects bad offset and limit but clamps oversized limit`() {
        val full = bigText()
        val store = newStore()
        val id = offloadedId(store, full)
        val reader = store.createReaderTool()

        val negative = textOf(call(reader, buildJsonObject { put("output_id", id); put("offset", -5) }))
        assertTrue(negative, negative.contains("offset"))

        val zeroLimit = textOf(call(reader, buildJsonObject { put("output_id", id); put("limit", 0) }))
        assertTrue(zeroLimit, zeroLimit.contains("limit"))

        val junkLimit = textOf(call(reader, buildJsonObject { put("output_id", id); put("limit", "abc") }))
        assertTrue(junkLimit, junkLimit.contains("limit"))

        val clamped = readPage(reader, id, limit = 999_999)
        assertTrue(clamped["text"]!!.jsonPrimitive.content.length <= TOOL_OUTPUT_READ_MAX_LIMIT)
    }

    @Test
    fun `string numbers are accepted like numbers`() {
        val full = flatText()
        val store = newStore()
        val id = offloadedId(store, full)
        val json = readPage(store.createReaderTool(), id, offset = 0, limit = 500)
        assertEquals(full.substring(0, 500), json["text"]!!.jsonPrimitive.content)

        val asStrings = call(
            store.createReaderTool(),
            buildJsonObject {
                put("output_id", id)
                put("offset", "500")
                put("limit", "500")
            },
        )
        val parsed = Json.parseToJsonElement(textOf(asStrings)).jsonObject
        assertEquals(500, parsed["offset"]!!.jsonPrimitive.content.toInt())
        assertEquals(500, parsed["text"]!!.jsonPrimitive.content.length)
        assertEquals(full.substring(500, 1000), parsed["text"]!!.jsonPrimitive.content)
    }

    // ---------------------------------------------------------------- 失败路径

    @Test
    fun `io failure keeps the original output`() {
        // baseDir 位置被一个普通文件占住：mkdirs 必然失败
        val blocked = File(root, "blocked").apply { writeText("not a directory") }
        val store = ToolOutputStore(blocked, OWNER)
        val parts = listOf(UIMessagePart.Text(bigText()), UIMessagePart.Image("file:///a.png"))
        val out = call(store.wrap(toolReturning(parts)), JsonObject(emptyMap()))
        assertEquals(parts, out)
    }

    @Test
    fun `cancellation is propagated instead of being swallowed`() {
        val cancelled = Tool(name = "workspace_shell", description = "fake") {
            throw CancellationException("cancelled")
        }
        try {
            call(newStore().wrap(cancelled), JsonObject(emptyMap()))
            fail("CancellationException should propagate")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    @Test
    fun `workspace mount path is used when the base dir is tool_outputs`() {
        val id = offloadedId(newStore(), bigText())
        val json = readPage(newStore().createReaderTool(), id, limit = 10)
        assertEquals("/${FileFolders.TOOL_OUTPUTS}/$id.txt", json["path"]!!.jsonPrimitive.content)
        assertEquals("workspace_shell", json["tool"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deep and multi byte offsets page without loading the whole file`() {
        val full = bigText(60_000)
        val store = newStore()
        val id = offloadedId(store, full)

        listOf(0, 1, 3_999, 4_000, 12_345, 30_001, 59_999).forEach { offset ->
            val json = readPage(store.createReaderTool(), id, offset = offset, limit = 500)
            val start = json["offset"]!!.jsonPrimitive.content.toInt()
            val page = json["text"]!!.jsonPrimitive.content
            // 页首可能为对齐代理对回退一位，但返回内容必须始终是全文的同位置切片
            assertEquals("offset=$offset", full.substring(start, start + page.length), page)
            assertFalse("offset=$offset", page.firstOrNull()?.isLowSurrogate() ?: false)
        }

        // 大文件（2M 字符）的深处读回：验证按字符跳过而不是整读
        val huge = flatText(2_000_000)
        val hugeId = offloadedId(store, huge)
        val deep = readPage(store.createReaderTool(), hugeId, offset = 1_500_001, limit = 100)
        assertEquals(1_500_001, deep["offset"]!!.jsonPrimitive.content.toInt())
        assertEquals(huge.substring(1_500_001, 1_500_101), deep["text"]!!.jsonPrimitive.content)
    }

    private companion object {
        const val OWNER = "conv-owner"
    }
}

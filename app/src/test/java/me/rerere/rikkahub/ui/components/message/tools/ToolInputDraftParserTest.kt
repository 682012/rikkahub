package me.rerere.rikkahub.ui.components.message.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式入参草稿解析的关键行为：跨 chunk 转义、残缺 escape、代理对、网关包装、增量续扫。
 *
 * 这些断言守的底线是：草稿只等于"目前能可靠解码出来的前缀"，不允许出现猜补出来的字符。
 */
class ToolInputDraftParserTest {
    private val writeJson = """{"path":"src/Main.kt","text":"fun main() {\n  println(\"hi\") }"}"""
    private val writeBody = "fun main() {\n  println(\"hi\") }"

    @Test
    fun writeFileStreamedCharByCharEndsComplete() {
        val tracker = ToolInputDraftTracker()
        var last: ToolInputDraft? = null
        for (index in writeJson.indices) {
            last = tracker.draft(WRITE_FILE_TOOL_NAME, writeJson.substring(0, index + 1))
        }
        val draft = requireDraft(last)
        assertEquals(ToolInputDraftKind.WRITE_FILE, draft.kind)
        assertEquals("src/Main.kt", draft.path)
        // 跨 chunk 的 \n 与 \" 都要解对
        assertEquals(writeBody, draft.body)
        assertEquals(ToolInputStreamState.COMPLETE, draft.streamState)
        assertFalse(draft.isDraft)
        assertEquals(writeJson.length, draft.receivedChars)
    }

    @Test
    fun halfStreamedBodyKeepsOnlyDecodedPrefix() {
        // 停在 "println" 中间：JSON 未闭合，但已经生成的代码要能看
        val cut = writeJson.indexOf("println") + 3
        val draft = requireDraft(parseToolInputDraft(WRITE_FILE_TOOL_NAME, writeJson.substring(0, cut)))
        assertTrue(draft.isDraft)
        assertEquals("src/Main.kt", draft.path)
        assertEquals("fun main() {\n  pri", draft.body)
        assertTrue(writeBody.startsWith(draft.body.orEmpty()))
    }

    @Test
    fun missingClosingBraceAfterCancelIsStillADraft() {
        // 断流：少最后那个 '}'，输入从此不再变化，也要继续当草稿展示
        val draft = requireDraft(parseToolInputDraft(WRITE_FILE_TOOL_NAME, writeJson.dropLast(1)))
        assertEquals(ToolInputStreamState.INCOMPLETE, draft.streamState)
        assertTrue(draft.isDraft)
        assertEquals(writeBody, draft.body)
    }

    @Test
    fun decodesEscapesAndSurrogatePairsAcrossChunks() {
        val json = """{"path":"a.md","text":"a\nb\u4f60\uD83D\uDE00c"}"""
        val expected = "a\nb\u4f60\uD83D\uDE00c"
        assertEquals(expected, parseToolInputDraft(WRITE_FILE_TOOL_NAME, json)?.body)

        // 逐字符喂，结果必须一样
        val tracker = ToolInputDraftTracker()
        var last: ToolInputDraft? = null
        for (index in json.indices) {
            last = tracker.draft(WRITE_FILE_TOOL_NAME, json.substring(0, index + 1))
        }
        assertEquals(expected, requireDraft(last).body)
    }

    @Test
    fun incompleteUnicodeEscapeIsNotGuessed() {
        assertEquals("abc", parseToolInputDraft(WRITE_FILE_TOOL_NAME, """{"path":"p","text":"abc\u12""")?.body)
        assertEquals("abc", parseToolInputDraft(WRITE_FILE_TOOL_NAME, """{"path":"p","text":"abc\""")?.body)
    }

    @Test
    fun loneHighSurrogateWaitsForNextChunk() {
        // \uD83D 已完整，但低位可能在下个 chunk：先别吐半个代理对
        assertEquals("abc", parseToolInputDraft(WRITE_FILE_TOOL_NAME, """{"path":"p","text":"abc\uD83D""")?.body)
        // 字符串已经闭合，落单的高位代理补 U+FFFD，不装作无关
        assertEquals(
            "abc\uFFFD",
            parseToolInputDraft(WRITE_FILE_TOOL_NAME, """{"path":"p","text":"abc\uD83D"}""")?.body,
        )
    }

    @Test
    fun editFileUsesNewTextAndKeepsOldText() {
        val draft = requireDraft(
            parseToolInputDraft(
                EDIT_FILE_TOOL_NAME,
                "{\"path\":\"a.md\",\"old_text\":\"old\",\"new_text\":\"new line\"",
            )
        )
        assertEquals(ToolInputDraftKind.EDIT_FILE, draft.kind)
        assertEquals("a.md", draft.path)
        assertEquals("new_text", draft.bodyKey)
        assertEquals("new line", draft.body)
        assertEquals("old", draft.previousBody)
        assertTrue(draft.isDraft)
    }

    @Test
    fun writeFileFallsBackToContentKey() {
        val draft = requireDraft(parseToolInputDraft(WRITE_FILE_TOOL_NAME, """{"path":"a","content":"c"}"""))
        assertEquals("content", draft.bodyKey)
        assertEquals("c", draft.body)
    }

    @Test
    fun emptyPathIsNotShown() {
        val draft = requireDraft(parseToolInputDraft(WRITE_FILE_TOOL_NAME, """{"path":"","text":"x"}"""))
        assertNull(draft.path)
        assertEquals("x", draft.body)
    }

    @Test
    fun gatewayObjectArgumentsReachInnerFields() {
        val json = "{\"name\":\"workspace_write_file\",\"arguments\":{\"path\":\"w/a.md\",\"text\":\"# hi\""
        val draft = requireDraft(parseToolInputDraft("tool_call", json))
        assertEquals(ToolInputDraftKind.WRITE_FILE, draft.kind)
        assertEquals("w/a.md", draft.path)
        assertEquals("# hi", draft.body)
        assertTrue(draft.isDraft)
    }

    @Test
    fun gatewayStringArgumentsReachInnerFields() {
        val draft = requireDraft(
            parseToolInputDraft(
                "tool_call_2",
                """{"name":"workspace_edit_file","arguments":"{\"path\":\"w/a.md\",\"new_text\":\"x"}""",
            )
        )
        assertEquals(ToolInputDraftKind.EDIT_FILE, draft.kind)
        assertEquals("w/a.md", draft.path)
        assertEquals("x", draft.body)
    }

    @Test
    fun gatewayWithUnfinishedNameFallsBackToRawPreview() {
        // 网关的 name 还没流全：只能给通用 raw 预览，但仍要有草稿可看
        val draft = requireDraft(parseToolInputDraft("tool_call", """{"name":"workspace_write"""))
        assertEquals(ToolInputDraftKind.OTHER, draft.kind)
        assertTrue(draft.nameIncomplete)
        assertNull(draft.body)
    }

    @Test
    fun unfinishedDirectToolNameFallsBackToRawPreview() {
        val draft = requireDraft(parseToolInputDraft("workspace_write_fil", """{"path":"a"}"""))
        assertEquals(ToolInputDraftKind.OTHER, draft.kind)
        assertTrue(draft.nameIncomplete)
    }

    @Test
    fun otherToolsAreLeftToExistingRenderers() {
        assertNull(parseToolInputDraft("workspace_shell", """{"command":"ls"}"""))
        assertNull(parseToolInputDraft("tool_call", """{"name":"workspace_read_file","arguments":{"path":"a"}}"""))
        assertNull(parseToolInputDraft(WRITE_FILE_TOOL_NAME, "   "))
    }

    @Test
    fun shallowerKeyWinsOverNestedOne() {
        assertEquals(
            "outer",
            parseToolInputDraft(WRITE_FILE_TOOL_NAME, """{"text":"outer","meta":{"text":"inner"}}""")?.body,
        )
        assertEquals(
            "outer",
            parseToolInputDraft(WRITE_FILE_TOOL_NAME, """{"meta":{"text":"inner"},"text":"outer"}""")?.body,
        )
    }

    @Test
    fun incrementalScanMatchesOneShotScan() {
        val json = """{"path":"docs/readme.md","text":"# title\n\n- a\n- b\n\n```kt\nval x = 1\n```"}"""
        val oneShot = requireDraft(parseToolInputDraft(WRITE_FILE_TOOL_NAME, json))
        for (step in listOf(1, 3, 17, 200)) {
            val tracker = ToolInputDraftTracker()
            var last: ToolInputDraft? = null
            var end = 0
            while (end < json.length) {
                end = minOf(json.length, end + step)
                last = tracker.draft(WRITE_FILE_TOOL_NAME, json.substring(0, end))
            }
            val draft = requireDraft(last)
            assertEquals(oneShot.body, draft.body)
            assertEquals(oneShot.path, draft.path)
            assertEquals(oneShot.streamState, draft.streamState)
        }
    }

    @Test
    fun restartingWithShorterInputRescansFromScratch() {
        val tracker = ToolInputDraftTracker()
        val full = requireDraft(parseToolInputDraft(WRITE_FILE_TOOL_NAME, writeJson)).body
        tracker.draft(WRITE_FILE_TOOL_NAME, writeJson)
        // 重新生成 / 重建：入参回退，不能继续拼旧状态
        val restarted = requireDraft(tracker.draft(WRITE_FILE_TOOL_NAME, """{"path":"other.kt","text":"re"""))
        assertEquals("other.kt", restarted.path)
        assertEquals("re", restarted.body)
        val again = requireDraft(tracker.draft(WRITE_FILE_TOOL_NAME, writeJson))
        assertEquals(full, again.body)
    }

    @Test
    fun hugeBodyIsBoundedButStillShownAsDraft() {
        val body = "x".repeat(200_050)
        val draft = requireDraft(parseToolInputDraft(WRITE_FILE_TOOL_NAME, """{"path":"big.txt","text":"$body"""))
        assertTrue(draft.isDraft)
        assertEquals(200_000, draft.body.orEmpty().length)
        assertTrue(draft.bodyTruncated)
    }

    @Test
    fun tailLinesKeepTheNewestLines() {
        val text = "1\n2\n3\n4\n5\n6\n7\n8"
        assertEquals("7\n8", text.draftTailLines(2))
        assertEquals("5\n6\n7\n8", text.draftTailLines(4))
        assertEquals("", "".draftTailLines(3))
        val wide = "a".repeat(50) + "b".repeat(50)
        assertEquals(20, wide.draftTailLines(4, maxChars = 20).length)
    }

    @Test
    fun nonJsonInputDoesNotCrash() {
        val draft = requireDraft(parseToolInputDraft(WRITE_FILE_TOOL_NAME, "not json at all"))
        assertNull(draft.body)
        assertTrue(draft.isDraft)
    }

    private fun requireDraft(value: ToolInputDraft?): ToolInputDraft {
        assertNotNull(value)
        return value ?: error("draft must not be null")
    }
}

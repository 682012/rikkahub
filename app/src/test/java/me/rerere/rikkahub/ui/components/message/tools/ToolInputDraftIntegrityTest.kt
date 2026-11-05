package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ToolInputDraftIntegrityTest {
    @Test fun everyRealJsonPrefixOnlyShowsReceivedDecodedCode() {
        val code = "fun main() {\n  println(\"中文 😀 \\\\ end\")\n}\t"
        val raw = JsonObject(mapOf("path" to JsonPrimitive("demo.kt"), "text" to JsonPrimitive(code))).toString()
        val tracker = ToolInputDraftTracker()
        for (n in 1..raw.length) {
            val draft = tracker.draft("workspace_write_file", raw.take(n))
            draft?.body?.let { assertTrue("prefix $n invented code", code.startsWith(it)) }
        }
        assertEquals(code, tracker.draft("workspace_write_file", raw)!!.body)
    }

    @Test fun sameTailWindowDoesNotHideAnEditedMiddle() {
        val original = "a".repeat(6_000)
        val tracker = ToolInputDraftTracker()
        tracker.draft("workspace_write_file", "{\"text\":\"$original")
        val rewritten = original.replaceRange(1_000, 1_001, "Z") + "new"
        val result = tracker.draft("workspace_write_file", "{\"text\":\"$rewritten")!!
        assertEquals(rewritten, result.body)
        val sameLengthRewrite = rewritten.replaceRange(2_000, 2_001, "Y")
        assertEquals(sameLengthRewrite, tracker.draft("workspace_write_file", "{\"text\":\"$sameLengthRewrite")!!.body)
    }

    @Test fun shellAndGatewayCodePrefixesAreVisibleBeforeJsonCompletes() {
        assertEquals("echo hi\nnext", parseToolInputDraft("workspace_shell", "{\"command\":\"echo hi\\nnext")!!.body)
        val gateway = "{\"name\":\"mcp__workspace__workspace_write_file\",\"arguments\":{\"path\":\"a.kt\",\"text\":\"val x"
        val draft = parseToolInputDraft("tool_call", gateway)!!
        assertEquals(ToolInputDraftKind.WRITE_FILE, draft.kind)
        assertEquals("val x", draft.body)
        assertNotNull(parseToolInputDraft("tool_call", "{\"name\":\"external\",\"arguments\":{\"script\":\"console.log(1)"))
    }

    @Test fun previewCapNeverTruncatesCopyOrExport() {
        val raw = "{\"path\":\"big.txt\",\"text\":\"" + "x".repeat(210_000)
        val draft = parseToolInputDraft("workspace_write_file", raw)!!
        assertTrue(draft.bodyTruncated)
        assertEquals(raw, draftCopyOrExportText(draft, raw))
        val shortRaw = "{\"text\":\"hello"
        val short = parseToolInputDraft("workspace_write_file", shortRaw)!!
        assertEquals("hello", draftCopyOrExportText(short, shortRaw))
    }
}

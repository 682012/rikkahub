package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceArtifactLinkTest {

    @Test
    fun `sandbox workspace link becomes artifact target`() {
        val link = WorkspaceArtifactLink.parse("sandbox:/workspace/out/chart.png")
        assertTrue(link is WorkspaceArtifactLink.WorkspaceFile)
        link as WorkspaceArtifactLink.WorkspaceFile
        assertEquals("/workspace/out/chart.png", link.path)
        assertEquals("out/chart.png", WorkspaceArtifactLink.relativeToWorkspace(link.path))
        assertNull(link.query)
        assertNull(link.fragment)
    }

    @Test
    fun `sandbox authority forms all normalize to workspace path`() {
        listOf(
            "sandbox:/workspace/a.txt",
            "sandbox:///workspace/a.txt",
            "sandbox://workspace/a.txt",
            "sandbox:workspace/a.txt",
        ).forEach { raw ->
            val link = WorkspaceArtifactLink.parse(raw)
            assertTrue("$raw -> $link", link is WorkspaceArtifactLink.WorkspaceFile)
            assertEquals("/workspace/a.txt", (link as WorkspaceArtifactLink.WorkspaceFile).path)
        }
    }

    @Test
    fun `bare workspace path is treated as artifact`() {
        val link = WorkspaceArtifactLink.parse("/workspace/notes.md")
        assertEquals("/workspace/notes.md", (link as WorkspaceArtifactLink.WorkspaceFile).path)
    }

    @Test
    fun `percent encoded space and chinese are decoded exactly once`() {
        val link = WorkspaceArtifactLink.parse("sandbox:/workspace/%E6%8A%A5%E5%91%8A%201.pdf")
        link as WorkspaceArtifactLink.WorkspaceFile
        assertEquals("/workspace/报告 1.pdf", link.path)
    }

    @Test
    fun `double encoded traversal stays literal after single decode`() {
        // Markdown 已经把链接编码过一次；只解码一次时 %252e%252e 变成字面量 %2e%2e，
        // 落到文件系统就是普通文件名，不是 ..
        val link = WorkspaceArtifactLink.parse("sandbox:/workspace/%252e%252e/etc/passwd")
        link as WorkspaceArtifactLink.WorkspaceFile
        assertEquals("/workspace/%2e%2e/etc/passwd", link.path)
        assertTrue(!link.path.contains("../"))
    }

    @Test
    fun `encoded traversal is rejected`() {
        val link = WorkspaceArtifactLink.parse("sandbox:/workspace/%2e%2e/%2e%2e/etc/passwd")
        assertEquals(
            WorkspaceArtifactLink.Reason.OUTSIDE_WORKSPACE,
            (link as WorkspaceArtifactLink.Rejected).reason,
        )
    }

    @Test
    fun `plain traversal and backslash are rejected`() {
        assertEquals(
            WorkspaceArtifactLink.Reason.OUTSIDE_WORKSPACE,
            (WorkspaceArtifactLink.parse("/workspace/../etc/shadow") as WorkspaceArtifactLink.Rejected).reason,
        )
        assertEquals(
            WorkspaceArtifactLink.Reason.OUTSIDE_WORKSPACE,
            (WorkspaceArtifactLink.parse("/workspace/a\\..\\b") as WorkspaceArtifactLink.Rejected).reason,
        )
    }

    @Test
    fun `absolute paths outside workspace are rejected`() {
        listOf("/etc/passwd", "/skills/skill.md", "/data/workspace/x.txt", "sandbox:/etc/passwd").forEach { raw ->
            val link = WorkspaceArtifactLink.parse(raw)
            assertTrue("$raw -> $link", link is WorkspaceArtifactLink.Rejected)
        }
    }

    @Test
    fun `file scheme is never accepted`() {
        val link = WorkspaceArtifactLink.parse("file:///workspace/out.png")
        assertEquals(
            WorkspaceArtifactLink.Reason.OUTSIDE_WORKSPACE,
            (link as WorkspaceArtifactLink.Rejected).reason,
        )
    }

    @Test
    fun `workspace root and empty links are not files`() {
        assertEquals(
            WorkspaceArtifactLink.Reason.NOT_A_FILE,
            (WorkspaceArtifactLink.parse("sandbox:/workspace") as WorkspaceArtifactLink.Rejected).reason,
        )
        assertEquals(
            WorkspaceArtifactLink.Reason.NOT_A_FILE,
            (WorkspaceArtifactLink.parse("/workspace/") as WorkspaceArtifactLink.Rejected).reason,
        )
        assertEquals(
            WorkspaceArtifactLink.Reason.MALFORMED,
            (WorkspaceArtifactLink.parse("   ") as WorkspaceArtifactLink.Rejected).reason,
        )
    }

    @Test
    fun `query and fragment are split before decoding`() {
        val link = WorkspaceArtifactLink.parse("sandbox:/workspace/report%20v2.html?v=1&x=%E4%B8%AD#sec-two")
        link as WorkspaceArtifactLink.WorkspaceFile
        assertEquals("/workspace/report v2.html", link.path)
        assertEquals("v=1&x=%E4%B8%AD", link.query)
        assertEquals("sec-two", link.fragment)
    }

    @Test
    fun `fragment containing question mark does not leak into query`() {
        val link = WorkspaceArtifactLink.parse("/workspace/a.md#what?ever")
        link as WorkspaceArtifactLink.WorkspaceFile
        assertEquals("/workspace/a.md", link.path)
        assertNull(link.query)
        assertEquals("what?ever", link.fragment)
    }

    @Test
    fun `http links stay web and custom schemes stay external`() {
        assertTrue(WorkspaceArtifactLink.parse("https://example.com/a?b=1") is WorkspaceArtifactLink.Web)
        assertTrue(WorkspaceArtifactLink.parse("http://example.com") is WorkspaceArtifactLink.Web)
        val mail = WorkspaceArtifactLink.parse("mailto:someone@example.com")
        assertEquals("mailto", (mail as WorkspaceArtifactLink.ExternalScheme).scheme)
        val mqq = WorkspaceArtifactLink.parse("mqqopensdkapi://bizAgent/qm")
        assertTrue(mqq is WorkspaceArtifactLink.ExternalScheme)
    }

    @Test
    fun `windows drive like path is not treated as scheme`() {
        // 冒号出现在非首段时不是 scheme；这里仍然按裸路径处理并被拒
        assertTrue(WorkspaceArtifactLink.parse("/workspace/C:/x.txt") is WorkspaceArtifactLink.WorkspaceFile)
    }

    @Test
    fun `dot segments are normalized away`() {
        val link = WorkspaceArtifactLink.parse("/workspace/./a/./b.txt")
        assertEquals("/workspace/a/b.txt", (link as WorkspaceArtifactLink.WorkspaceFile).path)
    }

    @Test
    fun `nul byte is rejected`() {
        val link = WorkspaceArtifactLink.parse("/workspace/a%00b.txt")
        assertEquals(
            WorkspaceArtifactLink.Reason.OUTSIDE_WORKSPACE,
            (link as WorkspaceArtifactLink.Rejected).reason,
        )
    }

    @Test
    fun `malformed percent sequences are kept literally`() {
        assertEquals("/workspace/100%.txt", WorkspaceArtifactLink.percentDecode("/workspace/100%.txt"))
        assertEquals("/workspace/a b.txt", WorkspaceArtifactLink.percentDecode("/workspace/a%20b.txt"))
        assertEquals("/workspace/中文.txt", WorkspaceArtifactLink.percentDecode("/workspace/%E4%B8%AD%E6%96%87.txt"))
        // + 在路径里不是空格
        assertEquals("/workspace/a+b.txt", WorkspaceArtifactLink.percentDecode("/workspace/a+b.txt"))
    }

    @Test
    fun `artifact kind is chosen by extension only`() {
        assertEquals(WorkspaceArtifactKind.IMAGE, WorkspaceArtifactLink.kindOf("/workspace/a.PNG"))
        assertEquals(WorkspaceArtifactKind.TEXT, WorkspaceArtifactLink.kindOf("/workspace/a.md"))
        assertEquals(WorkspaceArtifactKind.HTML, WorkspaceArtifactLink.kindOf("/workspace/a.html"))
        assertEquals(WorkspaceArtifactKind.PDF, WorkspaceArtifactLink.kindOf("/workspace/a.pdf"))
        assertEquals(WorkspaceArtifactKind.OTHER, WorkspaceArtifactLink.kindOf("/workspace/a.bin"))
        assertEquals(WorkspaceArtifactKind.OTHER, WorkspaceArtifactLink.kindOf("/workspace/README"))
    }
}

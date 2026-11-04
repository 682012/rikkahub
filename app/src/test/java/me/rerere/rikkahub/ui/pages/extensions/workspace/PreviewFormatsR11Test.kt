package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.rikkahub.ui.components.message.LocalDocumentPreviewKind
import me.rerere.rikkahub.ui.components.message.localDocumentPreviewKind
import me.rerere.rikkahub.ui.components.message.readSiblingWebAsset
import me.rerere.rikkahub.utils.WorkspaceArtifactKind
import me.rerere.rikkahub.utils.WorkspaceArtifactLink
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PreviewFormatsR11Test {
    @Test fun recognizesAdditionalPreviewTypes() {
        assertEquals(WorkspaceArtifactKind.HTML, WorkspaceArtifactLink.kindOf("/workspace/page.XHTML"))
        assertEquals(ArtifactDocumentKind.OFFICE_TEXT, artifactDocumentKindOf("book.epub", "application/epub+zip"))
        assertEquals(TextArtifactFormat.MARKDOWN, textArtifactFormat("notes.mdown"))
        assertEquals(TextArtifactFormat.TABLE, textArtifactFormat("data.tsv"))
        assertEquals(TextArtifactFormat.JSON, textArtifactFormat("notebook.ipynb"))
    }

    @Test fun csvPreservesQuotedCommasMultilineAndEscapes() {
        val parsed = parseDelimitedPreview("name,note\r\n\"a,b\",\"hello\n\"\"world\"\"\"\r\n", ',')
        assertEquals(listOf(listOf("name", "note"), listOf("a,b", "hello\n\"world\"")), parsed.rows)
        assertFalse(parsed.truncated)
        assertTrue(parseDelimitedPreview("a\nb\nc", ',', maxRows = 2).truncated)
    }

    @Test fun webAssetsAreOriginAndDirectoryScoped() {
        assertEquals("assets/site.css", htmlPreviewResourcePath("https://p.invalid/assets/site.css", "p.invalid"))
        listOf("file:///etc/passwd", "https://other.invalid/a.js", "https://p.invalid/.env",
            "https://p.invalid/%2e%2e/private.json", "https://p.invalid/a%5Cb.js", "https://p.invalid/a%252fb.js",
            "https://p.invalid/key.pem").forEach { assertNull(it, htmlPreviewResourcePath(it, "p.invalid")) }
        assertFalse(isExternalPreviewResourceAllowed("http://cdn.example/x.js"))
        assertFalse(isExternalPreviewResourceAllowed("https://127.0.0.1/a"))
        assertTrue(htmlPreviewCsp(false).contains("worker-src 'none'"))
        assertFalse(htmlPreviewCsp(false).contains("https:"))
    }

    @Test fun epubRespectsSpineOrderWithoutExtractingFiles() {        val file = File.createTempFile("r11-book", ".epub")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                val entries = mapOf(
                    "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>",
                    "OPS/book.opf" to "<package><manifest><item id=\"a\" href=\"a.xhtml\"/><item id=\"b\" href=\"b.xhtml\"/></manifest><spine><itemref idref=\"b\"/><itemref idref=\"a\"/></spine></package>",
                    "OPS/a.xhtml" to "<html><head><title>A</title></head><body><p>First</p></body></html>",
                    "OPS/b.xhtml" to "<html><head><title>B</title></head><body><p>Second</p><script>hidden()</script></body></html>",
                )
                entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            }
            val book = readOfficeText(file)
            assertEquals(listOf("B", "A"), book.sections.map { it.label })
            assertTrue(book.sections[0].lines.joinToString().contains("Second"))
            assertFalse(book.sections[0].lines.joinToString().contains("hidden"))
        } finally { file.delete() }
    }

    @Test fun assetPathRejectsDotSegmentsAndNonWebFiles() {
        assertTrue(isPreviewAssetPath("assets/app.css"))
        assertTrue(isPreviewAssetPath("a/b/c.webp"))
        listOf("", "/abs.js", "../x.js", "a/../../x.js", ".hidden.js", "a//b.js",
            "assets\\a.js", "a%b.js", "key.pem", "app.apk", "a".repeat(600) + ".js").forEach {
            assertFalse(it, isPreviewAssetPath(it))
        }
        // 查询串不影响目录作用域判定
        assertEquals("assets/site.css", htmlPreviewResourcePath("https://p.invalid/assets/site.css?v=2", "p.invalid"))
        assertNull(htmlPreviewResourcePath("https://p.invalid:8443/assets/site.css", "p.invalid"))
    }

    @Test fun fallbackCspIsInjectedIntoHead() {
        val injected = injectPreviewCsp(
            "<!doctype html><html><head><title>t</title></head><body>x</body></html>",
            htmlPreviewCsp(false),
        )
        val metaAt = injected.indexOf("<meta http-equiv=\"Content-Security-Policy\"")
        assertTrue(metaAt > injected.indexOf("<head>"))
        assertTrue(metaAt < injected.indexOf("<title>"))
        assertTrue(injected.contains("frame-src 'none'"))
        // 没有 head 时也不能把 meta 放到 doctype 前面（会进 quirks mode）
        val bare = injectPreviewCsp("<!doctype html><p>x</p>", "default-src 'self'")
        assertTrue(bare.indexOf("<!doctype") < bare.indexOf("<meta"))
    }

    @Test fun chatAttachmentsPreviewOnlySafeFormats() {
        assertEquals(LocalDocumentPreviewKind.HTML, localDocumentPreviewKind("page.html", "text/html"))
        assertEquals(LocalDocumentPreviewKind.HTML, localDocumentPreviewKind("drawing.svg", "image/svg+xml"))
        assertEquals(LocalDocumentPreviewKind.PDF, localDocumentPreviewKind("doc.PDF", "application/pdf"))
        assertEquals(LocalDocumentPreviewKind.TEXT, localDocumentPreviewKind("notes.md", "text/markdown"))
        assertEquals(LocalDocumentPreviewKind.TEXT, localDocumentPreviewKind("pasted_text.txt", "text/plain"))
        assertNull(localDocumentPreviewKind("report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertNull(localDocumentPreviewKind("archive.zip", "application/zip"))
    }

    @Test fun siblingAssetReaderStaysInsideDocumentFolder() {
        val dir = java.nio.file.Files.createTempDirectory("r11-asset").toFile().canonicalFile
        try {
            java.io.File(dir, "assets").mkdirs()
            val ok = java.io.File(dir, "assets/app.js")
            ok.writeText("console.log(1)")
            assertEquals("console.log(1)", String(readSiblingWebAsset(dir, "assets/app.js")))
            listOf("../secret.js", "/etc/passwd.css", ".env.js").forEach {
                assertThrows(IllegalArgumentException::class.java) { readSiblingWebAsset(dir, it) }
            }
            // 目录外（哪怕扩展名合法）读不到
            val outside = java.io.File(dir.parentFile, "outside-${dir.name}.css")
            outside.writeText("body{}")
            try {
                // 符号链接指向目录外：canonical 检查必须挡住
                java.nio.file.Files.createSymbolicLink(
                    java.io.File(dir, "link.css").toPath(),
                    outside.toPath(),
                )
                assertThrows(IllegalArgumentException::class.java) { readSiblingWebAsset(dir, "link.css") }
            } catch (_: UnsupportedOperationException) {
                // 某些文件系统不支持符号链接，跳过这一条
            } catch (_: java.io.IOException) {
                // 同上
            } finally {
                outside.delete()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}

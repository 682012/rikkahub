package me.rerere.rikkahub.ui.pages.extensions.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 用**真实**的 docx/xlsx/pptx/odt/tar/tar.gz 文件跑解析，不用 mock：
 * 这些格式都是 ZIP/XML/512 字节块，构造真实文件比伪造解析器返回值更能证明预览真的能读。
 */
class ArtifactPreviewFormatsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    // -----------------------------------------------------------------------------------------
    // 格式判定
    // -----------------------------------------------------------------------------------------

    @Test
    fun kindsFollowExtensionAndMime() {
        assertEquals(ArtifactDocumentKind.PDF, artifactDocumentKindOf("report.pdf", "application/octet-stream"))
        assertEquals(ArtifactDocumentKind.PDF, artifactDocumentKindOf("/workspace/out/REPORT.PDF", ""))
        assertEquals(ArtifactDocumentKind.PDF, artifactDocumentKindOf("noext", "application/pdf"))

        assertEquals(ArtifactDocumentKind.OFFICE_TEXT, artifactDocumentKindOf("a.docx", ""))
        assertEquals(ArtifactDocumentKind.OFFICE_TEXT, artifactDocumentKindOf("a.xlsx", ""))
        assertEquals(ArtifactDocumentKind.OFFICE_TEXT, artifactDocumentKindOf("a.pptx", ""))
        assertEquals(ArtifactDocumentKind.OFFICE_TEXT, artifactDocumentKindOf("a.odt", ""))
        assertEquals(
            ArtifactDocumentKind.OFFICE_TEXT,
            artifactDocumentKindOf("a.bin", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        )

        assertEquals(ArtifactDocumentKind.ARCHIVE, artifactDocumentKindOf("a.zip", ""))
        assertEquals(ArtifactDocumentKind.ARCHIVE, artifactDocumentKindOf("a.tar", ""))
        assertEquals(ArtifactDocumentKind.ARCHIVE, artifactDocumentKindOf("a.tar.gz", ""))
        assertEquals(ArtifactDocumentKind.ARCHIVE, artifactDocumentKindOf("a.tgz", ""))
        assertEquals(ArtifactDocumentKind.ARCHIVE, artifactDocumentKindOf("noext", "application/gzip"))

        assertEquals(ArtifactDocumentKind.AUDIO, artifactDocumentKindOf("a.mp3", ""))
        assertEquals(ArtifactDocumentKind.AUDIO, artifactDocumentKindOf("a.opus", "audio/ogg"))
        assertEquals(ArtifactDocumentKind.AUDIO, artifactDocumentKindOf("noext", "audio/mpeg"))

        assertEquals(ArtifactDocumentKind.VIDEO, artifactDocumentKindOf("a.mp4", ""))
        assertEquals(ArtifactDocumentKind.VIDEO, artifactDocumentKindOf("a.mkv", "application/octet-stream"))
        assertEquals(ArtifactDocumentKind.VIDEO, artifactDocumentKindOf("noext", "video/mp4"))

        assertEquals(ArtifactDocumentKind.BINARY, artifactDocumentKindOf("a.bin", ""))
        assertEquals(ArtifactDocumentKind.BINARY, artifactDocumentKindOf("a.doc", "application/msword"))
        assertEquals(ArtifactDocumentKind.BINARY, artifactDocumentKindOf("a.gz", "application/gzip"))
        assertEquals(ArtifactDocumentKind.BINARY, artifactDocumentKindOf("a.7z", ""))
        // 文本/图片由主页面自己处理（ZoomableAsyncImage / 只读文本），这里不抢
        assertEquals(ArtifactDocumentKind.BINARY, artifactDocumentKindOf("a.md", "text/markdown"))
        assertEquals(ArtifactDocumentKind.BINARY, artifactDocumentKindOf("a.png", "image/png"))
    }

    // -----------------------------------------------------------------------------------------
    // Office 文本
    // -----------------------------------------------------------------------------------------

    @Test
    fun docxTextIsExtractedFromRealZip() {
        val file = zipFile(
            "word/document.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>第一段 &amp; 符号</w:t></w:r></w:p>
                    <w:p><w:r><w:t xml:space="preserve">Second </w:t></w:r><w:r><w:t>paragraph</w:t></w:r></w:p>
                    <w:p><w:r><w:t>&#x7B2C;&#19977;</w:t></w:r></w:p>
                    <w:p><w:r><w:t></w:t></w:r></w:p>
                  </w:body>
                </w:document>
            """.trimIndent(),
            "word/styles.xml" to "<styles/>",
            "docProps/app.xml" to "<Properties/>",
        )

        val document = readOfficeText(file)

        assertEquals(OfficeTextFormat.DOCX, document.format)
        assertEquals(listOf("正文"), document.sections.map { it.label })
        assertEquals(listOf("第一段 & 符号", "Second paragraph", "第三"), document.sections.single().lines)
        assertFalse(document.truncated)
        assertFalse(document.isEmpty)
    }

    @Test
    fun docxRejectsDoctypeAndEntityDeclarations() {
        val file = zipFile(
            "word/document.xml" to """
                <?xml version="1.0"?>
                <!DOCTYPE w:document [<!ENTITY boom "boom">]>
                <w:document xmlns:w="x"><w:body><w:p><w:r><w:t>&boom;</w:t></w:r></w:p></w:body></w:document>
            """.trimIndent(),
        )

        val error = assertThrows(ArtifactPreviewException::class.java) { readOfficeText(file) }
        assertTrue(error.message.orEmpty().contains("DTD"))
    }

    @Test
    fun xlsxSharesSheetNamesAndCellText() {
        val file = zipFile(
            "xl/workbook.xml" to """
                <?xml version="1.0"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
                    <sheet name="数据" sheetId="2" r:id="rId2"/>
                  </sheets>
                </workbook>
            """.trimIndent(),
            "xl/sharedStrings.xml" to """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>Name</t></si>
                  <si><t>张三</t></si>
                  <si><r><t>Cell</t></r><r><t>Text</t></r></si>
                </sst>
            """.trimIndent(),
        )

        val document = readOfficeText(file)

        assertEquals(OfficeTextFormat.XLSX, document.format)
        assertEquals(listOf("工作表", "单元格文本"), document.sections.map { it.label })
        assertEquals(listOf("Sheet1", "数据"), document.sections[0].lines)
        assertEquals(listOf("Name", "张三", "CellText"), document.sections[1].lines)
    }

    @Test
    fun pptxSlidesAreOrderedNumerically() {
        val slide = { text: String ->
            """
            <p:sld xmlns:p="p" xmlns:a="a">
              <p:cSld><p:spTree><p:sp><p:txBody>
                <a:p><a:r><a:t>$text</a:t></a:r></a:p>
              </p:txBody></p:sp></p:spTree></p:cSld>
            </p:sld>
            """.trimIndent()
        }
        val file = zipFile(
            "ppt/slides/slide10.xml" to slide("十"),
            "ppt/slides/slide2.xml" to slide("二"),
            "ppt/slides/slide1.xml" to slide("一"),
        )

        val document = readOfficeText(file)

        assertEquals(OfficeTextFormat.PPTX, document.format)
        assertEquals(listOf("幻灯片 1", "幻灯片 2", "幻灯片 10"), document.sections.map { it.label })
        assertEquals(listOf("一", "二", "十"), document.sections.map { it.lines.single() })
    }

    @Test
    fun odtKeepsSpacesAndParagraphs() {
        val file = zipFile(
            "mimetype" to "application/vnd.oasis.opendocument.text",
            "content.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-content xmlns:office="office" xmlns:text="text">
                  <office:body><office:text>
                    <text:h text:outline-level="1">标题</text:h>
                    <text:p>第一段<text:s/>有空格</text:p>
                    <text:p>第二段<text:tab/>制表</text:p>
                  </office:text></office:body>
                </office:document-content>
            """.trimIndent(),
        )

        val document = readOfficeText(file)

        assertEquals(OfficeTextFormat.ODT, document.format)
        assertEquals(listOf("标题", "第一段 有空格", "第二段\t制表"), document.sections.single().lines)
    }

    @Test
    fun officeZipWithTooManyEntriesIsRejected() {
        val file = zipFileWithManyEntries(ArtifactPreviewLimits.MAX_ARCHIVE_ENTRIES + 1)
        val error = assertThrows(ArtifactPreviewException::class.java) { readOfficeText(file) }
        assertTrue(error.message.orEmpty().contains("条目"))
    }

    @Test
    fun readBudgetStopsRunawayExtraction() {
        val budget = ArtifactReadBudget(maxBytes = 10)
        budget.take(10)
        assertEquals(10L, budget.consumedBytes)
        assertThrows(ArtifactPreviewException::class.java) { budget.take(1) }
    }

    @Test
    fun nonZipOfficeFileFailsWithClearMessage() {
        val file = tempFolder.newFile("broken.docx")
        file.writeText("this is not a zip")
        val error = assertThrows(ArtifactPreviewException::class.java) { readOfficeText(file) }
        assertTrue(error.message.orEmpty().contains("ZIP"))
    }

    @Test
    fun xmlTextStopsAtCharacterBudget() {
        val xml = "<a><p><t>${"字".repeat(100)}</t></p></a>"
        val result = xmlTextLines(xml = xml, textTagLocals = setOf("t"), breakTagLocals = setOf("p"), maxChars = 10)
        assertEquals(1, result.lines.size)
        assertEquals(10, result.lines.single().length)
        assertTrue(result.truncated)
    }

    @Test
    fun unknownEntitiesStayLiteralInsteadOfExpanding() {
        val xml = "<a><p><t>&unknown; &amp; &#65;</t></p></a>"
        val result = xmlTextLines(xml = xml, textTagLocals = setOf("t"), breakTagLocals = setOf("p"))
        assertEquals(listOf("&unknown; & A"), result.lines)
    }

    // -----------------------------------------------------------------------------------------
    // 压缩包清单
    // -----------------------------------------------------------------------------------------

    @Test
    fun zipListingOnlyListsEntries() {
        val file = zipFile(
            "a.txt" to "hello",
            "dir/b.bin" to "0123456789",
        )
        val listing = readArchiveListing(file)

        assertEquals(ArchiveFormat.ZIP, listing.format)
        assertEquals(listOf("a.txt", "dir/b.bin"), listing.entries.map { it.path })
        assertEquals(5L, listing.entries[0].sizeBytes)
        assertEquals(15L, listing.totalUncompressedBytes)
        assertEquals(2, listing.fileCount)
        assertFalse(listing.truncated)
    }

    @Test
    fun archiveListingTruncatesAtEntryLimit() {
        val file = zipFileWithManyEntries(ArtifactPreviewLimits.MAX_ARCHIVE_ENTRIES + 5)
        val listing = readArchiveListing(file)
        assertEquals(ArtifactPreviewLimits.MAX_ARCHIVE_ENTRIES, listing.entries.size)
        assertTrue(listing.truncated)
    }

    @Test
    fun tarListingReadsPrefixAndSizes() {
        val bytes = ByteArrayOutputStream().also { out ->
            writeTarEntry(out, name = "a.txt", size = 5, typeFlag = '0', content = "hello")
            writeTarEntry(out, name = "notes.md", size = 3, typeFlag = '0', prefix = "deep/dir", content = "abc")
            writeTarEntry(out, name = "empty/", size = 0, typeFlag = '5')
            out.write(ByteArray(1024))
        }.toByteArray()
        val file = tempFolder.newFile("bundle.tar").apply { writeBytes(bytes) }

        val listing = readArchiveListing(file)

        assertEquals(ArchiveFormat.TAR, listing.format)
        assertEquals(
            listOf("a.txt", "deep/dir/notes.md", "empty"),
            listing.entries.map { it.path },
        )
        assertEquals(listOf(false, false, true), listing.entries.map { it.isDirectory })
        assertEquals(8L, listing.totalUncompressedBytes)
        assertFalse(listing.truncated)
    }

    @Test
    fun tarGzListingIsSupported() {
        val tarBytes = ByteArrayOutputStream().also { out ->
            writeTarEntry(out, name = "log.txt", size = 4, typeFlag = '0', content = "log!")
            out.write(ByteArray(1024))
        }.toByteArray()
        val gz = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(tarBytes) }
        }.toByteArray()
        val file = tempFolder.newFile("bundle.tar.gz").apply { writeBytes(gz) }

        val listing = readArchiveListing(file)

        assertEquals(ArchiveFormat.TAR_GZ, listing.format)
        assertEquals(listOf("log.txt"), listing.entries.map { it.path })
        assertEquals(4L, listing.totalUncompressedBytes)
    }

    @Test
    fun unknownArchiveFormatIsRejected() {
        val file = tempFolder.newFile("fake.zip").apply { writeText("not an archive") }
        assertThrows(ArtifactPreviewException::class.java) { readArchiveListing(file) }
    }

    // -----------------------------------------------------------------------------------------
    // 二进制 / 元信息
    // -----------------------------------------------------------------------------------------

    @Test
    fun hexPreviewShowsOffsetsAndAscii() {
        val lines = hexPreviewLines("Hello, R7!".toByteArray(Charsets.US_ASCII))
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("00000000  "))
        assertTrue(lines.single().contains("48 65 6c 6c 6f"))
        assertTrue(lines.single().endsWith("|Hello, R7!|"))
    }

    @Test
    fun binaryPreviewReadsOnlyHeadAndReportsSize() {
        val file = tempFolder.newFile("blob.bin")
        file.writeBytes(ByteArray(5000) { 0x41 })
        val preview = readBinaryPreview(file)

        assertEquals(5000L, preview.sizeBytes)
        assertEquals(ArtifactPreviewLimits.HEX_PREVIEW_BYTES, preview.shownBytes)
        assertEquals(ArtifactPreviewLimits.HEX_PREVIEW_BYTES / 16, preview.lines.size)
        assertTrue(preview.truncated)
        assertTrue(preview.lines.first().endsWith("|AAAAAAAAAAAAAAAA|"))
    }

    @Test
    fun byteSizeFormattingIsReadable() {
        assertEquals("512 B", formatByteSize(512))
        assertEquals("1.5 KB", formatByteSize(1536))
        assertEquals("2.0 MB", formatByteSize(2L * 1024 * 1024))
    }

    // -----------------------------------------------------------------------------------------
    // PDF 失败原因
    // -----------------------------------------------------------------------------------------

    @Test
    fun pdfFailuresAreClassified() {
        assertEquals(
            PdfPreviewFailure.PASSWORD,
            pdfPreviewFailureOf(IOException("Password required or incorrect password.")),
        )
        assertEquals(
            PdfPreviewFailure.PASSWORD,
            pdfPreviewFailureOf(RuntimeException("java.io.IOException: PDF is encrypted")),
        )
        assertEquals(PdfPreviewFailure.CORRUPT, pdfPreviewFailureOf(IOException("broken")))
        assertEquals(PdfPreviewFailure.CORRUPT, pdfPreviewFailureOf(RuntimeException("cannot open document")))
        assertEquals(PdfPreviewFailure.UNKNOWN, pdfPreviewFailureOf(IllegalStateException("whatever")))
    }

    // -----------------------------------------------------------------------------------------
    // 测试脚手架：真的写文件
    // -----------------------------------------------------------------------------------------

    private fun zipFile(vararg entries: Pair<String, String>): File {
        val file = tempFolder.newFile()
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return file
    }

    private fun zipFileWithManyEntries(count: Int): File {
        val file = tempFolder.newFile()
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            repeat(count) { index ->
                out.putNextEntry(ZipEntry("part/$index.xml"))
                out.write("<a/>".toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return file
    }

    /** 手工写 512 字节 tar 头 + 数据块，覆盖 ustar 的 prefix/size 字段 */
    private fun writeTarEntry(
        out: OutputStream,
        name: String,
        size: Long,
        typeFlag: Char,
        prefix: String = "",
        content: String = "",
    ) {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        nameBytes.copyInto(header, 0, 0, minOf(nameBytes.size, 100))
        "0000644\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 100)
        "0000000\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 108)
        "0000000\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 116)
        size.toString(8).padStart(11, '0').plus("\u0000").toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        "00000000000\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 136)
        header[156] = typeFlag.code.toByte()
        "ustar\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
        "00".toByteArray(Charsets.US_ASCII).copyInto(header, 263)
        if (prefix.isNotEmpty()) {
            prefix.toByteArray(Charsets.UTF_8).copyInto(header, 345)
        }
        // checksum：先填空格再算，写回 6 位八进制
        for (index in 148 until 156) header[index] = ' '.code.toByte()
        val checksum = header.sumOf { it.toInt() and 0xFF }
        checksum.toString(8).padStart(6, '0').plus("\u0000 ").toByteArray(Charsets.US_ASCII).copyInto(header, 148)

        out.write(header)
        val data = content.toByteArray(Charsets.UTF_8)
        if (data.isNotEmpty()) out.write(data)
        val padding = (512 - data.size % 512) % 512
        if (padding > 0) out.write(ByteArray(padding))
    }
}

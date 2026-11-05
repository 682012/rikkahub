package me.rerere.document

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtraDocumentParserTest {
    @get:Rule val temp = TemporaryFolder()
    private fun archive(name: String, entries: Map<String, String>) = temp.newFile(name).also { f ->
        ZipOutputStream(f.outputStream()).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }
    @Test fun xlsxRetainsSheetsAndCellAddresses() {
        val file = archive("table.xlsx", mapOf(
            "xl/workbook.xml" to "<workbook><sheets><sheet name='账单' r:id='r1'/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id='r1' Target='worksheets/sheet1.xml'/></Relationships>",
            "xl/sharedStrings.xml" to "<sst><si><t>项目</t></si></sst>",
            "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row><c r='A1' t='s'><v>0</v></c><c r='C1'><v>42</v></c><c r='D1' t='inlineStr'><is><t>中文</t></is></c></row></sheetData></worksheet>"))
        val result = ExtraDocumentParser.parse(file, "application/octet-stream")
        listOf("账单", "A1: 项目", "C1: 42", "D1: 中文").forEach { assertTrue(result, it in result) }
    }
    @Test fun openDocumentAndHtml() {
        val file = archive("book.odt", mapOf("content.xml" to "<office:document><text:p>你好文档</text:p></office:document>"))
        assertTrue(ExtraDocumentParser.parse(file, "application/octet-stream").contains("你好文档"))
        val html = temp.newFile("a.html").apply { writeText("<html><body><p>可读</p><script>bad()</script></body></html>") }
        assertTrue(ExtraDocumentParser.parse(html, "text/html").contains("可读"))
        assertFalse(ExtraDocumentParser.parse(html, "text/html").contains("bad"))
    }
    @Test fun textBomAndTruncation() {
        val file = temp.newFile("data.txt").apply { writeText("\uFEFF" + "x".repeat(110000)) }
        val text = ExtraDocumentParser.parse(file, "text/plain")
        assertTrue(text.startsWith("x")); assertTrue(text.contains("截断"))
    }
    @Test fun rejectsUnknownBinaryAndExternalEntity() {
        val file = temp.newFile("data.bin").apply { writeBytes(byteArrayOf(0,1,2)) }
        assertTrue(runCatching { ExtraDocumentParser.parse(file, "application/octet-stream") }.isFailure)
        val odt = archive("evil.odt", mapOf("content.xml" to "<!DOCTYPE x [<!ENTITY ext SYSTEM 'file:///etc/passwd'>]><text:p>&ext;</text:p>"))
        assertTrue(runCatching { ExtraDocumentParser.parse(odt, "application/octet-stream") }.isFailure)
    }
}

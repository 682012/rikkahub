package me.rerere.document

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

/** Text extraction only: no macros, formulas, external entities or archive entries are executed. */
object ExtraDocumentParser {
    private const val LIMIT = 100000
    private const val ENTRY_LIMIT = 4 * 1024 * 1024
    private val textTypes = setOf("txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "log", "kt", "py", "js", "ts", "java", "c", "cpp", "css", "sql", "sh")
    fun supports(name: String, mime: String): Boolean = name.substringAfterLast('.', "").lowercase() in
        textTypes + setOf("xlsx", "ods", "odt", "odp", "html", "htm") || mime.startsWith("text/") ||
        mime.substringBefore(';') in setOf("application/json", "application/xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.presentation")

    fun parse(file: File, mime: String, originalName: String = file.name): String {
        require(file.isFile && file.length() <= 32L * 1024 * 1024) { "文件不存在或超过32 MiB文本解析上限" }
        require(supports(originalName, mime)) { "暂不支持此二进制格式，请转为PDF、DOCX或纯文本" }
        val extension = originalName.substringAfterLast('.', "").lowercase()
        val text = when {
            extension == "xlsx" || mime.contains("spreadsheetml") -> xlsx(file)
            extension in setOf("ods", "odt", "odp") || mime.contains("opendocument") -> odf(file)
            extension in setOf("html", "htm") || mime.startsWith("text/html") -> {
                val doc = Jsoup.parse(file, null)
                doc.select("script,style,noscript,iframe").remove()
                doc.wholeText()
            }
            else -> file.bufferedReader().use { reader ->
                val buffer = CharArray(LIMIT + 1)
                var count = 0
                while (count < buffer.size) {
                    val n = reader.read(buffer, count, buffer.size - count)
                    if (n < 0) break
                    count += n
                }
                String(buffer, 0, count).removePrefix("\uFEFF").also { require('\u0000' !in it) { "文件不是UTF-8文本" } }
            }
        }
        return text.take(LIMIT) + if (text.length > LIMIT) "\n[正文已截断，仅显示前 $LIMIT 字符]" else ""
    }

    private class Archive(val zip: ZipFile) {
        var total = 0
        fun xml(path: String): org.jsoup.nodes.Document? {
            require(!path.startsWith('/') && path.split('/').none { it == ".." }) { "非法文档路径" }
            val entry = zip.getEntry(path) ?: return null
            require(entry.size <= ENTRY_LIMIT) { "压缩条目超过4 MiB" }
            val bytes = java.io.ByteArrayOutputStream()
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    check(!Thread.currentThread().isInterrupted) { "解析已取消" }
                    val n = input.read(buffer); if (n < 0) break
                    total += n
                    require(bytes.size() + n <= ENTRY_LIMIT && total <= 16 * 1024 * 1024) { "解压内容超过上限" }
                    bytes.write(buffer, 0, n)
                }
            }
            val text = bytes.toString("UTF-8")
            require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "不支持含外部实体的文档" }
            return Jsoup.parse(text, "", Parser.xmlParser())
        }
    }

    private fun xlsx(file: File): String = ZipFile(file).use { zip ->
        val archive = Archive(zip)
        val strings = archive.xml("xl/sharedStrings.xml")?.getElementsByTag("si")?.map { it.getElementsByTag("t").joinToString("") { t -> t.text() } }.orEmpty()
        val relations = archive.xml("xl/_rels/workbook.xml.rels")?.getElementsByTag("Relationship")
            ?.filter { !it.attr("TargetMode").equals("External", true) }
            ?.associate { it.attr("Id") to it.attr("Target") }.orEmpty()
        val sheets = archive.xml("xl/workbook.xml")?.getElementsByTag("sheet").orEmpty()
        val out = StringBuilder()
        for (sheet in sheets) {
            if (out.length > LIMIT) break
            val target = relations[sheet.attr("r:id")] ?: continue
            val path = if (target.startsWith("/xl/")) target.removePrefix("/") else "xl/$target"
            val doc = archive.xml(path) ?: continue
            out.append("# ").append(sheet.attr("name")).append('\n')
            for (row in doc.getElementsByTag("row")) {
                if (out.length > LIMIT) break
                row.getElementsByTag("c").forEach { cell ->
                    if (out.length <= LIMIT) {
                        val value = cell.getElementsByTag("v").firstOrNull()?.text().orEmpty()
                        val text = when (cell.attr("t")) {
                            "s" -> value.toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
                            "inlineStr" -> cell.getElementsByTag("t").joinToString("") { it.text() }
                            "b" -> if (value == "1") "TRUE" else "FALSE"
                            else -> value
                        }
                        // Cell coordinates retain missing-column positions without huge padding arrays.
                        out.append(cell.attr("r")).append(": ").append(text.take(LIMIT)).append("\t")
                    }
                }
                out.append('\n')
            }
        }
        require(out.isNotBlank()) { "没有可提取的工作表（加密文件不支持）" }
        out.toString()
    }

    private fun odf(file: File): String = ZipFile(file).use { zip ->
        val doc = Archive(zip).xml("content.xml") ?: error("文档缺少content.xml")
        val out = StringBuilder()
        for (element in doc.allElements) {
            if (out.length > LIMIT) break
            val tag = element.tagName().substringAfter(':')
            when (tag) {
                "table" -> out.append("# ").append(element.attr("table:name")).append('\n')
                "p", "h" -> out.append(element.text().take(LIMIT)).append('\n')
            }
        }
        out.toString()
    }
}

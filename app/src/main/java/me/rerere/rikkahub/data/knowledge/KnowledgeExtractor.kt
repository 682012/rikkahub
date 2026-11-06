package me.rerere.rikkahub.data.knowledge

import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.ExtraDocumentParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import java.io.File

data class ExtractedText(
    val text: String,
    val truncated: Boolean,
)

/**
 * 从本地文件抽正文。只走两条已验证的路：
 * - 纯文本类：自己按 UTF-8 读到 1 MiB 字符（超了截断并标记）；
 * - html/pdf/docx/pptx/epub：复用 document 模块已经给聊天附件用的解析器。
 *
 * 解压型格式（docx/pptx/epub）的 bomb 上限由 document 模块自己保证；这里再加文件 10 MiB 的入口限制。
 */
object KnowledgeTextExtractor {
    fun extract(file: File, displayName: String, mime: String?, format: KnowledgeFormat): ExtractedText =
        when (format) {
            KnowledgeFormat.TEXT,
            KnowledgeFormat.MARKDOWN,
            KnowledgeFormat.CSV,
            KnowledgeFormat.JSON,
            -> readPlainText(file)

            KnowledgeFormat.HTML ->
                cap(ExtraDocumentParser.parse(file, mime ?: "text/html", displayName))

            KnowledgeFormat.PDF -> cap(PdfParser.parserPdf(file))
            KnowledgeFormat.DOCX -> cap(DocxParser.parse(file))
            KnowledgeFormat.PPTX -> cap(PptxParser.parse(file))
            KnowledgeFormat.EPUB -> cap(EpubParser.parse(file))
        }

    private fun readPlainText(file: File): ExtractedText {
        val builder = StringBuilder()
        var truncated = false
        file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(16 * 1024)
            while (builder.length <= KnowledgeLimits.MAX_TEXT_CHARS) {
                val read = reader.read(buffer)
                if (read < 0) break
                val room = KnowledgeLimits.MAX_TEXT_CHARS - builder.length
                if (read <= room) {
                    builder.append(buffer, 0, read)
                } else {
                    builder.append(buffer, 0, room)
                    truncated = true
                    break
                }
            }
            if (!truncated && reader.read() >= 0) truncated = true
        }
        require('\u0000' !in builder) { "文件不是 UTF-8 文本（包含 NUL 字节）" }
        return ExtractedText(builder.toString(), truncated)
    }

    private fun cap(text: String): ExtractedText {
        // ExtraDocumentParser 自己最多返回 10 万字符，这里再按统一上限兜一次
        val truncated = text.length > KnowledgeLimits.MAX_TEXT_CHARS
        return ExtractedText(
            text = if (truncated) text.take(KnowledgeLimits.MAX_TEXT_CHARS) else text,
            truncated = truncated,
        )
    }
}

package me.rerere.rikkahub.data.knowledge

/**
 * 支持的来源格式。这里**只列真的实现了读取的格式**：
 *
 * - 纯文本类（txt/md/csv/tsv/json/jsonl/log/xml/yaml/yml）：仓库自己按 UTF-8 读取；
 * - html/htm：走 document 模块的 ExtraDocumentParser（Jsoup 提正文）；
 * - pdf / docx / pptx / epub：走 document 模块已有的 PdfParser / DocxParser / PptxParser / EpubParser。
 *
 * 没实现读取的格式（例如 xlsx、doc、图片 OCR）不在这里假列。
 */
object KnowledgeFormats {
    private val TEXT_EXTENSIONS = listOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "log", "xml", "yaml", "yml",
    )
    private val HTML_EXTENSIONS = listOf("html", "htm")
    private val OTHER = mapOf(
        "pdf" to KnowledgeFormat.PDF,
        "docx" to KnowledgeFormat.DOCX,
        "pptx" to KnowledgeFormat.PPTX,
        "epub" to KnowledgeFormat.EPUB,
    )

    val supportedExtensions: List<String> =
        (TEXT_EXTENSIONS + HTML_EXTENSIONS + OTHER.keys).sorted()

    /** SAF 选择器用的 MIME 过滤；只放能读的类型。 */
    val pickerMimeTypes: Array<String> = arrayOf(
        "text/*",
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/epub+zip",
    )

    fun formatOf(displayName: String?, mime: String?): KnowledgeFormat? {
        val extension = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        when {
            extension in TEXT_EXTENSIONS -> return when (extension) {
                "md", "markdown" -> KnowledgeFormat.MARKDOWN
                "csv", "tsv" -> KnowledgeFormat.CSV
                "json", "jsonl" -> KnowledgeFormat.JSON
                else -> KnowledgeFormat.TEXT
            }

            extension in HTML_EXTENSIONS -> return KnowledgeFormat.HTML
            else -> OTHER[extension]?.let { return it }
        }
        // 没有扩展名时退回 MIME（内容提供方经常只给 MIME）
        val base = mime?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when {
            base == "text/html" || base == "application/xhtml+xml" -> KnowledgeFormat.HTML
            base == "text/markdown" -> KnowledgeFormat.MARKDOWN
            base.startsWith("text/") -> KnowledgeFormat.TEXT
            base == "application/pdf" -> KnowledgeFormat.PDF
            base == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> KnowledgeFormat.DOCX
            base == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> KnowledgeFormat.PPTX
            base == "application/epub+zip" -> KnowledgeFormat.EPUB
            base == "application/json" -> KnowledgeFormat.JSON
            else -> null
        }
    }

    fun supports(displayName: String?, mime: String?): Boolean = formatOf(displayName, mime) != null

    fun unsupportedReason(): KnowledgeRejectReason =
        KnowledgeRejectReason.UnsupportedFormat(supportedExtensions)
}

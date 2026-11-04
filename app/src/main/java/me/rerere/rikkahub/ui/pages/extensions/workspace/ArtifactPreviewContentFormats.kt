package me.rerere.rikkahub.ui.pages.extensions.workspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 工作区产物「应用内多格式预览」的纯 JVM 逻辑：格式判定、Office 文本提取、压缩包清单、
 * 二进制十六进制预览。
 *
 * 放在独立文件里（而不是和 Composable 混在一起）是为了两件事：
 * 1. 这一层只用 `java.io` / `java.util.zip`，可以在普通 JVM 单测里用**真实**的 docx/xlsx/pptx/tar 文件跑；
 * 2. Android 侧（PdfRenderer / MediaPlayer / Compose）拿到的只是这里产出的数据，出问题边界清楚。
 *
 * ### 安全边界
 * - 不联网、不落盘额外文件、不解压到工作区根目录：压缩包只**读清单**，绝不 sudo/exec/解压。
 * - ZIP 解压前按中央目录的声明大小和实际读取量双重限流（[ArtifactPreviewLimits]），条目数和总字节都有上限，
 *   声明的 size 可以撒谎，所以真实读取时再按字节累计一次。
 * - XML 只用本文件的受限扫描器：**拒绝 DOCTYPE / ENTITY 声明**（直接报错，不做任何实体展开），
 *   只解码 5 个预定义实体和数字字符引用，因此不存在 XXE / billion-laughs 的路径。
 *   没用 `XmlPullParser` 的原因：它是 Android 运行时 API，JVM 单测里跑不起来，而这里的解析只需要
 *   「标签 + 文本 + 属性」三种 token；自己写 100 行反而能把「禁 DTD」这条规则用测试钉死。
 *
 * 说明：`document` 模块里已有给 AI 用的 `DocxParser/PptxParser`，但没有条目数/解压总量限制、也没有
 * xlsx/odt，所以预览这层单独实现，避免把没有预算的解析器接到用户可点的预览按钮上。
 */
internal object ArtifactPreviewLimits {
    /** 压缩包最多列多少条目，超过就显示前 N 项 */
    const val MAX_ARCHIVE_ENTRIES = 2_000

    /** 单次预览里从压缩包读出的总字节上限（解压后） */
    const val MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024

    /** 单个条目解压上限 */
    const val MAX_ENTRY_BYTES = 16L * 1024 * 1024

    /** 单个段落/整篇文本最多保留多少字符 */
    const val MAX_TEXT_CHARS = 200_000

    /** 未知二进制预览的字节数 */
    const val HEX_PREVIEW_BYTES = 4 * 1024

    /** Office 文本预览最多显示多少节（PPT 页数等） */
    const val MAX_SECTIONS = 80

    /** PDF 单页渲染的上限，避免把超大页面整页解码进内存 */
    const val MAX_RENDER_SIDE = 2_400
    const val MAX_RENDER_PIXELS = 4_000_000L
    const val MAX_RENDER_SCALE = 3f
}

/** 预览侧的失败，带上给用户看的明确原因；调用方直接显示 message，不回退到浏览器 */
internal class ArtifactPreviewException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 解压预算：不管 ZIP 中央目录怎么写，真正读出来的字节都要过这里。
 */
internal class ArtifactReadBudget(private val maxBytes: Long = ArtifactPreviewLimits.MAX_UNCOMPRESSED_BYTES) {
    var consumedBytes: Long = 0L
        private set

    fun take(count: Int) {
        if (count <= 0) return
        consumedBytes += count
        if (consumedBytes > maxBytes) {
            throw ArtifactPreviewException("解压后的内容超过 ${formatByteSize(maxBytes)}，已停止提取")
        }
    }
}

/** 应用内文档预览的分支；由主页面按 `kind` 决定要不要调 [ArtifactDocumentPreview] */
enum class ArtifactDocumentKind {
    /** `android.graphics.pdf.PdfRenderer` 分页渲染 */
    PDF,

    /** docx/xlsx/pptx/odt 的 ZIP+XML 文本提取（不是排版还原） */
    OFFICE_TEXT,

    /** ZIP/TAR 目录清单，只列表不解压 */
    ARCHIVE,

    /** 原生 MediaPlayer 播放 */
    AUDIO,

    /** 原生 VideoView 播放 */
    VIDEO,

    /** 未知二进制：元信息 + 前 4KB 十六进制 */
    BINARY,
}

private val PDF_EXTENSIONS = setOf("pdf")
private val OFFICE_EXTENSIONS = setOf("epub", "docx", "docm", "xlsx", "xlsm", "pptx", "pptm", "odt", "ods", "odp")
private val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "tgz")
private val AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "mid", "midi", "mka", "3ga", "awb", "wma",
)
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "3gp", "3g2", "mov", "ts", "mts", "avi", "flv", "wmv")

/**
 * 按扩展名 + MIME 判定预览分支。只做分类，不读文件内容。
 *
 * `.gz` / `.bz2` / `.xz` 这类单文件压缩不支持列清单，落到 BINARY（十六进制），不假装能解开。
 */
fun artifactDocumentKindOf(fileName: String, mimeType: String): ArtifactDocumentKind {
    val name = fileName.substringAfterLast('/').lowercase(Locale.US)
    val extension = if ('.' in name) name.substringAfterLast('.') else ""
    val mime = mimeType.lowercase(Locale.US).substringBefore(';').trim()
    return when {
        extension in PDF_EXTENSIONS || mime == "application/pdf" -> ArtifactDocumentKind.PDF

        extension in OFFICE_EXTENSIONS ||
            mime.startsWith("application/vnd.openxmlformats-officedocument") ||
            mime.startsWith("application/vnd.oasis.opendocument") -> ArtifactDocumentKind.OFFICE_TEXT

        extension in ARCHIVE_EXTENSIONS ||
            name.endsWith(".tar.gz") ||
            mime == "application/zip" ||
            mime == "application/x-zip-compressed" ||
            mime == "application/x-tar" ||
            (mime == "application/gzip" && extension.isEmpty()) -> ArtifactDocumentKind.ARCHIVE

        extension in AUDIO_EXTENSIONS || mime.startsWith("audio/") -> ArtifactDocumentKind.AUDIO

        extension in VIDEO_EXTENSIONS || mime.startsWith("video/") -> ArtifactDocumentKind.VIDEO

        else -> ArtifactDocumentKind.BINARY
    }
}

// ---------------------------------------------------------------------------------------------
// Office 文本提取
// ---------------------------------------------------------------------------------------------

internal enum class OfficeTextFormat(val displayName: String) {
    EPUB("EPUB"),
    DOCX("Word"),
    XLSX("Excel"),
    PPTX("PowerPoint"),
    ODT("OpenDocument"),
}

internal data class OfficeTextSection(val label: String, val lines: List<String>)

internal data class OfficeTextDocument(
    val format: OfficeTextFormat,
    val sections: List<OfficeTextSection>,
    val truncated: Boolean,
) {
    val isEmpty: Boolean get() = sections.all { it.lines.isEmpty() }
}

/**
 * 从 docx/xlsx/pptx/odt 里提取**文本**。提取不到排版、图片、表格样式，界面上要说清楚。
 *
 * 只用 ZIP 中央目录挑选需要的 part，再走受限 XML 扫描；每一步都过 [ArtifactReadBudget]。
 */
internal fun readOfficeText(file: File): OfficeTextDocument {
    val budget = ArtifactReadBudget()
    return openZip(file) { zip ->
        val names = zip.entryNames()
        when {
            file.extension.equals("epub", true) -> readEpubText(zip, names, budget)

            "word/document.xml" in names -> {
                val text = xmlTextLines(
                    xml = zip.readEntryText("word/document.xml", budget),
                    textTagLocals = setOf("t"),
                    breakTagLocals = setOf("p", "br"),
                )
                OfficeTextDocument(
                    format = OfficeTextFormat.DOCX,
                    sections = listOf(OfficeTextSection("正文", text.lines)),
                    truncated = text.truncated,
                )
            }

            "xl/workbook.xml" in names || "xl/sharedStrings.xml" in names -> readXlsxText(zip, names, budget)

            names.any { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") } -> readPptxText(zip, names, budget)

            "content.xml" in names -> {
                val text = xmlTextLines(
                    xml = zip.readEntryText("content.xml", budget),
                    textTagLocals = setOf("p", "h", "span"),
                    breakTagLocals = setOf("p", "h", "line-break"),
                    inlineTextTags = mapOf("s" to " ", "tab" to "\t"),
                )
                OfficeTextDocument(
                    format = OfficeTextFormat.ODT,
                    sections = listOf(OfficeTextSection("正文", text.lines)),
                    truncated = text.truncated,
                )
            }

            else -> throw ArtifactPreviewException("这个压缩包里没有可提取的文档正文")
        }
    }
}

private fun readEpubText(zip: ZipFile, names: List<String>, budget: ArtifactReadBudget): OfficeTextDocument {
    val container = zip.readEntryText("META-INF/container.xml", budget)
    val descriptor = org.jsoup.Jsoup.parse(container, "", org.jsoup.parser.Parser.xmlParser())
    val packageName = descriptor.selectFirst("rootfile")?.attr("full-path")
        ?.takeIf { it in names } ?: throw ArtifactPreviewException("EPUB 没有有效的内容目录")
    val book = org.jsoup.Jsoup.parse(zip.readEntryText(packageName, budget), "", org.jsoup.parser.Parser.xmlParser())
    val parent = packageName.substringBeforeLast('/', "")
    val manifest = book.select("manifest > item").associate { it.attr("id") to it.attr("href") }
    val chapters = book.select("spine > itemref").mapNotNull { ref -> manifest[ref.attr("idref")] }
    var remaining = ArtifactPreviewLimits.MAX_TEXT_CHARS
    var truncated = chapters.size > ArtifactPreviewLimits.MAX_SECTIONS
    val sections = chapters.take(ArtifactPreviewLimits.MAX_SECTIONS).mapNotNull { href ->
        val relative = java.net.URI(href).path ?: return@mapNotNull null
        val resolved = java.nio.file.Paths.get(parent).resolve(relative).normalize().toString().replace('\\', '/')
        if (resolved.startsWith("../") || resolved.startsWith('/') || resolved !in names) return@mapNotNull null
        if (remaining <= 0) { truncated = true; return@mapNotNull null }
        val document = org.jsoup.Jsoup.parse(zip.readEntryText(resolved, budget))
        document.select("script,style").remove()
        val text = document.body().wholeText().trim()
        if (text.length > remaining) truncated = true
        val shown = text.take(remaining)
        remaining -= shown.length
        OfficeTextSection(document.title().ifBlank { href }, shown.lines())
    }
    if (sections.isEmpty()) throw ArtifactPreviewException("EPUB 没有可显示的章节（可能受 DRM 保护）")
    return OfficeTextDocument(OfficeTextFormat.EPUB, sections, truncated)
}

private fun readXlsxText(zip: ZipFile, names: List<String>, budget: ArtifactReadBudget): OfficeTextDocument {
    val sheetNames = if ("xl/workbook.xml" in names) {
        xmlAttributes(
            xml = zip.readEntryText("xl/workbook.xml", budget),
            tagLocalName = "sheet",
            attributeName = "name",
        )
    } else {
        emptyList()
    }

    val shared = if ("xl/sharedStrings.xml" in names) {
        xmlTextLines(
            xml = zip.readEntryText("xl/sharedStrings.xml", budget),
            textTagLocals = setOf("t"),
            breakTagLocals = setOf("si", "row"),
        )
    } else {
        XmlTextResult(emptyList(), truncated = false)
    }

    val sections = buildList {
        if (sheetNames.isNotEmpty()) add(OfficeTextSection("工作表", sheetNames))
        if (shared.lines.isNotEmpty()) add(OfficeTextSection("单元格文本", shared.lines))
    }
    return OfficeTextDocument(
        format = OfficeTextFormat.XLSX,
        sections = sections,
        truncated = shared.truncated,
    )
}

private fun readPptxText(zip: ZipFile, names: List<String>, budget: ArtifactReadBudget): OfficeTextDocument {
    val slides = names
        .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
        .sortedBy { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
    var truncated = slides.size > ArtifactPreviewLimits.MAX_SECTIONS
    val sections = ArrayList<OfficeTextSection>()
    for (slide in slides.take(ArtifactPreviewLimits.MAX_SECTIONS)) {
        val text = xmlTextLines(
            xml = zip.readEntryText(slide, budget),
            textTagLocals = setOf("t"),
            breakTagLocals = setOf("p", "br"),
        )
        truncated = truncated || text.truncated
        val number = Regex("(\\d+)").find(slide)?.groupValues?.get(1) ?: slide
        sections.add(OfficeTextSection("幻灯片 $number", text.lines))
    }
    return OfficeTextDocument(
        format = OfficeTextFormat.PPTX,
        sections = sections,
        truncated = truncated,
    )
}

// ---------------------------------------------------------------------------------------------
// 压缩包清单（ZIP / TAR / TAR.GZ），只列表不解压
// ---------------------------------------------------------------------------------------------

internal enum class ArchiveFormat(val displayName: String) {
    ZIP("ZIP"),
    TAR("TAR"),
    TAR_GZ("TAR.GZ"),
}

internal data class ArchiveEntryInfo(val path: String, val sizeBytes: Long, val isDirectory: Boolean)

internal data class ArchiveListing(
    val format: ArchiveFormat,
    val entries: List<ArchiveEntryInfo>,
    val totalUncompressedBytes: Long,
    val truncated: Boolean,
) {
    val fileCount: Int get() = entries.count { !it.isDirectory }
}

internal fun readArchiveListing(file: File): ArchiveListing {
    val header = file.readHeaderBytes(512)
    return when {
        header.size >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() -> readZipListing(file)
        header.size >= 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte() ->
            GZIPInputStream(file.inputStream().buffered()).use { readTarListing(it, ArchiveFormat.TAR_GZ) }

        header.size >= 262 && String(header, 257, 5, Charsets.US_ASCII) == "ustar" ->
            file.inputStream().buffered().use { readTarListing(it, ArchiveFormat.TAR) }

        else -> throw ArtifactPreviewException("不是可识别的 ZIP / TAR 压缩包")
    }
}

private fun readZipListing(file: File): ArchiveListing {
    val archive = try {
        ZipFile(file)
    } catch (error: IOException) {
        throw ArtifactPreviewException("这个 ZIP 已损坏，无法读取目录", error)
    }
    try {
        val entries = ArrayList<ArchiveEntryInfo>()
        var total = 0L
        var truncated = false
        val iterator = archive.entries()
        while (iterator.hasMoreElements()) {
            val entry = iterator.nextElement()
            if (entries.size >= ArtifactPreviewLimits.MAX_ARCHIVE_ENTRIES) {
                truncated = true
                break
            }
            val size = entry.size.coerceAtLeast(0L)
            total += size
            entries.add(ArchiveEntryInfo(entry.name, size, entry.isDirectory))
        }
        return ArchiveListing(ArchiveFormat.ZIP, entries, total, truncated)
    } finally {
        runCatching { archive.close() }
    }
}

private fun readTarListing(input: InputStream, format: ArchiveFormat): ArchiveListing {
    val entries = ArrayList<ArchiveEntryInfo>()
    val header = ByteArray(512)
    var total = 0L
    var truncated = false
    var skippedBytes = 0L
    var zeroBlocks = 0

    while (true) {
        if (!input.readFully(header)) break
        if (header.all { it == 0.toByte() }) {
            zeroBlocks++
            if (zeroBlocks >= 2) break
            continue
        }
        zeroBlocks = 0

        val name = readTarString(header, 0, 100)
        val prefix = if (String(header, 257, 5, Charsets.US_ASCII) == "ustar") readTarString(header, 345, 155) else ""
        val path = if (prefix.isEmpty()) name else "$prefix/$name"
        val size = readTarSize(header)
        val typeFlag = header[156].toInt().toChar()

        if (entries.size >= ArtifactPreviewLimits.MAX_ARCHIVE_ENTRIES) {
            truncated = true
            break
        }
        if (path.isNotBlank()) {
            entries.add(ArchiveEntryInfo(path.trimEnd('/'), size, typeFlag == '5' || path.endsWith("/")))
            total += size
        }

        val dataBytes = (size + 511) / 512 * 512
        if (dataBytes > 0) {
            skippedBytes += dataBytes
            if (skippedBytes > ArtifactPreviewLimits.MAX_UNCOMPRESSED_BYTES) {
                truncated = true
                break
            }
            if (!input.skipFully(dataBytes)) break
        }
    }
    return ArchiveListing(format, entries, total, truncated)
}

private fun readTarString(header: ByteArray, offset: Int, length: Int): String {
    var end = offset
    val limit = minOf(offset + length, header.size)
    while (end < limit && header[end] != 0.toByte()) end++
    return String(header, offset, end - offset, Charsets.UTF_8).trim()
}

/** tar 的 size 是 12 字节八进制；GNU 的大文件用 base-256 编码，最高位为 1 */
private fun readTarSize(header: ByteArray): Long {
    if (header[124].toInt() and 0x80 != 0) {
        var value = 0L
        for (index in 125 until 136) {
            value = (value shl 8) or (header[index].toInt() and 0xFF).toLong()
        }
        return if (value < 0) 0L else value
    }
    val text = String(header, 124, 12, Charsets.US_ASCII).trim().trimEnd('\u0000', ' ')
    return text.toLongOrNull(8) ?: 0L
}

private fun InputStream.readFully(target: ByteArray): Boolean {
    var read = 0
    while (read < target.size) {
        val count = read(target, read, target.size - read)
        if (count < 0) return false
        read += count
    }
    return true
}

private fun InputStream.skipFully(bytes: Long): Boolean {
    var remaining = bytes
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (read() < 0) {
            return false
        } else {
            remaining--
        }
    }
    return true
}

private fun File.readHeaderBytes(count: Int): ByteArray {
    return runCatching {
        inputStream().use { input ->
            val buffer = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = input.read(buffer, read, count - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))
}

// ---------------------------------------------------------------------------------------------
// 未知二进制：元信息 + 前 4KB 十六进制
// ---------------------------------------------------------------------------------------------

internal data class BinaryPreview(
    val sizeBytes: Long,
    val shownBytes: Int,
    val lines: List<String>,
) {
    val truncated: Boolean get() = sizeBytes > shownBytes
}

internal fun readBinaryPreview(
    file: File,
    maxBytes: Int = ArtifactPreviewLimits.HEX_PREVIEW_BYTES,
): BinaryPreview {
    val size = file.length()
    val bytes = runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(maxBytes)
            var read = 0
            while (read < maxBytes) {
                val n = input.read(buffer, read, maxBytes - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
    }.getOrElse { throw ArtifactPreviewException("无法读取文件内容：${it.message ?: "未知错误"}", it) }
    return BinaryPreview(sizeBytes = size, shownBytes = bytes.size, lines = hexPreviewLines(bytes))
}

internal fun hexPreviewLines(
    bytes: ByteArray,
    bytesPerLine: Int = 16,
): List<String> {
    if (bytes.isEmpty()) return emptyList()
    val perLine = bytesPerLine.coerceAtLeast(1)
    val lines = ArrayList<String>((bytes.size + perLine - 1) / perLine)
    val hex = StringBuilder(perLine * 3)
    var offset = 0
    while (offset < bytes.size) {
        val end = minOf(offset + perLine, bytes.size)
        hex.setLength(0)
        val ascii = StringBuilder(perLine)
        for (index in offset until end) {
            val value = bytes[index].toInt() and 0xFF
            hex.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F]).append(' ')
            ascii.append(if (value in 0x20..0x7E) value.toChar() else '.')
        }
        while (hex.length < perLine * 3) hex.append(' ')
        lines.add(
            "${offset.toString(16).padStart(8, '0')}  $hex |$ascii|",
        )
        offset = end
    }
    return lines
}

private const val HEX_DIGITS = "0123456789abcdef"

internal fun formatByteSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

// ---------------------------------------------------------------------------------------------
// PDF 失败原因（文案在 UI 层用 string resource 呈现）
// ---------------------------------------------------------------------------------------------

enum class PdfPreviewFailure { PASSWORD, CORRUPT, UNKNOWN }

fun pdfPreviewFailureOf(error: Throwable): PdfPreviewFailure {
    val text = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            append(current.message.orEmpty()).append(' ')
            current = current.cause
            depth++
        }
    }.lowercase(Locale.US)
    return when {
        "password" in text || "encrypt" in text -> PdfPreviewFailure.PASSWORD
        error is IOException ||
            "corrupt" in text ||
            "cannot open" in text ||
            "malformed" in text ||
            "format" in text ||
            "premature" in text ||
            "bad " in text -> PdfPreviewFailure.CORRUPT

        else -> PdfPreviewFailure.UNKNOWN
    }
}

// ---------------------------------------------------------------------------------------------
// 受限 XML 扫描：拒绝 DTD/实体，只产出 Start / End / Text
// ---------------------------------------------------------------------------------------------

internal sealed interface ArtifactXmlEvent {
    data class Start(val name: String, val attributes: Map<String, String>) : ArtifactXmlEvent
    data class End(val name: String) : ArtifactXmlEvent
    data class Text(val value: String) : ArtifactXmlEvent
}

/**
 * 逐个 token 扫 XML。
 *
 * - `<!DOCTYPE` / `<!ENTITY` 等任何 `<!` 声明直接抛 [ArtifactPreviewException]：不解析、不展开；
 * - 注释、处理指令跳过，CDATA 当纯文本；
 * - 自闭合标签先发 Start 再发 End，调用方不用分支处理。
 */
internal fun scanArtifactXml(xml: String, onEvent: (ArtifactXmlEvent) -> Unit) {
    var index = 0
    val length = xml.length
    while (index < length) {
        val tagStart = xml.indexOf('<', index)
        if (tagStart < 0) {
            if (index < length) onEvent(ArtifactXmlEvent.Text(decodeArtifactXmlEntities(xml.substring(index))))
            return
        }
        if (tagStart > index) {
            onEvent(ArtifactXmlEvent.Text(decodeArtifactXmlEntities(xml.substring(index, tagStart))))
        }
        when {
            xml.startsWith("<!--", tagStart) -> {
                val end = xml.indexOf("-->", tagStart + 4)
                index = if (end < 0) length else end + 3
            }

            xml.startsWith("<![CDATA[", tagStart) -> {
                val end = xml.indexOf("]]>", tagStart + 9)
                onEvent(ArtifactXmlEvent.Text(xml.substring(tagStart + 9, if (end < 0) length else end)))
                index = if (end < 0) length else end + 3
            }

            xml.startsWith("<!", tagStart) ->
                throw ArtifactPreviewException("文件里包含 DTD 或实体声明，已停止解析")

            xml.startsWith("<?", tagStart) -> {
                val end = xml.indexOf("?>", tagStart + 2)
                index = if (end < 0) length else end + 2
            }

            else -> {
                val tagEnd = findTagEnd(xml, tagStart + 1)
                if (tagEnd < 0) return
                val raw = xml.substring(tagStart + 1, tagEnd)
                val selfClosing = raw.endsWith("/")
                val body = if (selfClosing) raw.dropLast(1) else raw
                if (body.startsWith("/")) {
                    onEvent(ArtifactXmlEvent.End(body.drop(1).trim().substringBefore(' ')))
                } else {
                    val name = body.takeWhile { !it.isWhitespace() }
                    if (name.isNotEmpty()) {
                        onEvent(ArtifactXmlEvent.Start(name, parseArtifactXmlAttributes(body)))
                        if (selfClosing) onEvent(ArtifactXmlEvent.End(name))
                    }
                }
                index = tagEnd + 1
            }
        }
    }
}

/** 找标签的 `>`，跳过引号里的内容 */
private fun findTagEnd(xml: String, from: Int): Int {
    var index = from
    var quote: Char? = null
    while (index < xml.length) {
        val char = xml[index]
        when {
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == '>' -> return index
        }
        index++
    }
    return -1
}

private val ATTRIBUTE_PATTERN =
    Regex("([A-Za-z_:][A-Za-z0-9_:.\\-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

private fun parseArtifactXmlAttributes(tagBody: String): Map<String, String> {
    val attributes = LinkedHashMap<String, String>()
    for (match in ATTRIBUTE_PATTERN.findAll(tagBody)) {
        val name = match.groupValues[1]
        val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
        attributes[name] = decodeArtifactXmlEntities(value)
    }
    return attributes
}

/** 只认 5 个预定义实体 + 数字字符引用；不认识的实体原样保留，绝不查外部实体表 */
internal fun decodeArtifactXmlEntities(raw: String): String {
    if ('&' !in raw) return raw
    val result = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        if (char != '&') {
            result.append(char)
            index++
            continue
        }
        val semicolon = raw.indexOf(';', index + 1)
        if (semicolon < 0 || semicolon - index > 10) {
            result.append(char)
            index++
            continue
        }
        val name = raw.substring(index + 1, semicolon)
        val decoded = when (name) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> when {
                name.startsWith("#x") || name.startsWith("#X") ->
                    name.substring(2).toIntOrNull(16)?.takeIf { it in 0..0x10FFFF }?.let(::codePointToString)

                name.startsWith("#") ->
                    name.substring(1).toIntOrNull()?.takeIf { it in 0..0x10FFFF }?.let(::codePointToString)

                else -> null
            }
        }
        if (decoded == null) {
            result.append(char)
            index++
        } else {
            result.append(decoded)
            index = semicolon + 1
        }
    }
    return result.toString()
}

private fun codePointToString(codePoint: Int): String =
    if (codePoint in 0xD800..0xDFFF) "\uFFFD" else String(Character.toChars(codePoint))

internal data class XmlTextResult(val lines: List<String>, val truncated: Boolean)

/**
 * 把 XML 里的可见文本抽成行。
 *
 * @param textTagLocals 这些标签里的字符数据算正文（docx 的 `t`、pptx 的 `t`、odt 的 `p`/`h`/`span`）
 * @param breakTagLocals 这些标签结束时换行（`p`、`si`、`br` 等，按 localName 比较，忽略命名空间前缀）
 * @param inlineTextTags 自闭合内联标签替换成的字符（odt 的 `<text:s/>` → 空格）
 */
internal fun xmlTextLines(
    xml: String,
    textTagLocals: Set<String>,
    breakTagLocals: Set<String> = emptySet(),
    inlineTextTags: Map<String, String> = emptyMap(),
    maxChars: Int = ArtifactPreviewLimits.MAX_TEXT_CHARS,
): XmlTextResult {
    val lines = ArrayList<String>()
    val current = StringBuilder()
    var activeDepth = 0
    var usedChars = 0
    var truncated = false

    fun flush() {
        val line = current.toString().trim()
        current.setLength(0)
        if (line.isNotEmpty()) lines.add(line)
    }

    scanArtifactXml(xml) { event ->
        when (event) {
            is ArtifactXmlEvent.Start -> {
                val local = event.name.substringAfter(':')
                if (local in textTagLocals) activeDepth++
                inlineTextTags[local]?.let { inline ->
                    if (activeDepth > 0 && usedChars < maxChars) {
                        current.append(inline)
                        usedChars++
                    }
                }
            }

            is ArtifactXmlEvent.End -> {
                val local = event.name.substringAfter(':')
                if (local in textTagLocals && activeDepth > 0) activeDepth--
                if (local in breakTagLocals) flush()
            }

            is ArtifactXmlEvent.Text -> {
                if (activeDepth <= 0) return@scanArtifactXml
                if (usedChars >= maxChars) {
                    truncated = true
                    return@scanArtifactXml
                }
                val slice = event.value.take(maxChars - usedChars)
                if (slice.length < event.value.length) truncated = true
                current.append(slice)
                usedChars += slice.length
            }
        }
    }
    flush()
    return XmlTextResult(lines, truncated)
}

/** 某个标签的某个属性值列表，例如 xlsx 工作簿里的 sheet name */
internal fun xmlAttributes(xml: String, tagLocalName: String, attributeName: String): List<String> {
    val values = ArrayList<String>()
    scanArtifactXml(xml) { event ->
        if (event is ArtifactXmlEvent.Start && event.name.substringAfter(':') == tagLocalName) {
            val value = event.attributes.entries
                .firstOrNull { it.key.substringAfter(':') == attributeName }
                ?.value
                ?.trim()
            if (!value.isNullOrEmpty() && values.size < ArtifactPreviewLimits.MAX_SECTIONS) values.add(value)
        }
    }
    return values
}

// ---------------------------------------------------------------------------------------------
// ZIP 读取
// ---------------------------------------------------------------------------------------------

private fun <T> openZip(file: File, block: (ZipFile) -> T): T {
    return try {
        ZipFile(file).use(block)
    } catch (error: ArtifactPreviewException) {
        throw error
    } catch (error: IOException) {
        throw ArtifactPreviewException("这个文件不是可读取的 ZIP/Office 文档", error)
    }
}

private fun ZipFile.entryNames(): List<String> {
    val names = ArrayList<String>()
    val iterator = entries()
    while (iterator.hasMoreElements()) {
        names.add(iterator.nextElement().name)
        if (names.size > ArtifactPreviewLimits.MAX_ARCHIVE_ENTRIES) {
            throw ArtifactPreviewException("压缩包条目超过 ${ArtifactPreviewLimits.MAX_ARCHIVE_ENTRIES} 个，已停止提取")
        }
    }
    return names
}

private fun ZipFile.readEntryText(entryName: String, budget: ArtifactReadBudget): String {
    val entry: ZipEntry = getEntry(entryName)
        ?: throw ArtifactPreviewException("缺少文档部件：$entryName")
    if (entry.size > ArtifactPreviewLimits.MAX_ENTRY_BYTES) {
        throw ArtifactPreviewException("文档部件过大：$entryName")
    }
    getInputStream(entry).use { input ->
        val output = ByteArrayOutputStream(64 * 1024)
        val buffer = ByteArray(32 * 1024)
        var written = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            written += count
            // 中央目录里的 size 可以撒谎，读出来的字节再算一次
            if (written > ArtifactPreviewLimits.MAX_ENTRY_BYTES) {
                throw ArtifactPreviewException("文档部件过大：$entryName")
            }
            budget.take(count)
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

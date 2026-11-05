package me.rerere.mediagen.model

import java.nio.charset.StandardCharsets

/**
 * 纯 Kotlin/JVM 的文件格式嗅探（magic number 级别），不依赖 Android，也不依赖任何解码库。
 *
 * ## 边界（重要）
 * - **只做识别，不做解码**。结论只说明"头部字节长得像某个容器/格式"，不保证文件完整、可解码、
 *   像素或时长合法。真正可用性必须由 App 侧补 decoder 验证（例如 Android 的 `BitmapFactory` /
 *   `ImageDecoder`，视频用 `MediaMetadataRetriever` / `MediaCodec`）。把这里的结论当作"文件可用"是错误的。
 * - **不信任外部声明的 MIME**。HTTP `Content-Type`、文件扩展名、provider 返回值都属于"声明"，只能当提示，
 *   不能当真实证据；本类只看字节。调用方可以拿声明值做交叉核对，但不能反过来用它替代 [detect]。
 * - **不猜**。证据不足或内容明显不是媒体（空、HTML、JSON、纯文本等）时返回 [Unknown]，字段为 null。
 * - 只读取前 [MAX_HEADER_BYTES]（64 KiB）。传完整文件也可以，超出窗口的字节不会被使用。
 *
 * 支持的格式：PNG（APNG 提示 + 尺寸）、JPEG（尺寸，尽力而为）、GIF（尺寸 + 多帧提示）、
 * WebP（VP8 / VP8L / VP8X，含动画提示与尺寸）、MP4（ISOBMFF，按 ftyp brand 给内容提示）、
 * WebM / Matroska（按 EBML DocType）、SVG（XML 根元素为 svg，显式拒绝 HTML）。
 */
sealed class MediaFileSignature {
    companion object {

    /** 识别只读取头部的最大字节数（64 KiB）。 */
    const val MAX_HEADER_BYTES: Int = 64 * 1024

    /** 定长签名比较所需的最小字节数。 */
    private const val MIN_SIGNATURE_BYTES: Int = 12

    /** 单个容器内最多扫描多少个结构单元，避免畸形输入导致长循环。 */
    private const val MAX_STRUCTURES_SCANNED: Int = 64

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val RIFF_SIGNATURE = ascii("RIFF")
    private val WEBP_SIGNATURE = ascii("WEBP")
    private val EBML_SIGNATURE = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    private val ISO_BMFF_FIRST_BOXES =
        setOf("ftyp", "styp", "moov", "mdat", "free", "skip", "wide")

    private val VIDEO_BRANDS = setOf(
        "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1",
        "M4V ", "M4VH", "M4VP", "dash", "hvc1", "hev1", "av01", "vp09", "qt  ",
    )
    private val AUDIO_BRANDS = setOf("M4A ", "M4B ", "M4P ", "mp71", "aac ", "F4A ", "F4B ")
    private val IMAGE_BRANDS = setOf("mif1", "msf1", "heic", "heix", "hevc", "hevx", "avif", "avis")

    /**
     * 从头部字节识别格式。
     *
     * 传入的 [bytes] 应当是文件或响应体的头部（建议至少 1 KiB）。整文件也可以，但只用前 [MAX_HEADER_BYTES]。
     * 空数组、过短无法可靠比较的数组、以及 HTML/JSON 等非媒体内容都返回 [Unknown]。
     */
    fun detect(bytes: ByteArray?): MediaFileSignature {
        if (bytes == null || bytes.isEmpty()) return Unknown(detectedBytes = 0)
        val used = bytes.size.coerceAtMost(MAX_HEADER_BYTES)
        if (used < MIN_SIGNATURE_BYTES) return Unknown(detectedBytes = used)

        // 复制到固定窗口，保证所有解析都在 64 KiB 内进行。
        val head = if (bytes.size > MAX_HEADER_BYTES) bytes.copyOf(MAX_HEADER_BYTES) else bytes
        return detectPng(head, used)
            ?: detectJpeg(head, used)
            ?: detectGif(head, used)
            ?: detectWebp(head, used)
            ?: detectIsoBmff(head, used)
            ?: detectEbml(head, used)
            ?: detectSvg(head, used)
            ?: Unknown(detectedBytes = used)
    }

    // ------------------------------------------------------------------ PNG

    private fun detectPng(bytes: ByteArray, used: Int): MediaFileSignature? {
        if (!startsWith(bytes, PNG_SIGNATURE)) return null
        // IHDR 必须是第一个 chunk，否则不是合法 PNG，不猜。
        if (!matchesAt(bytes, 12, ascii("IHDR"))) return null

        val width = readUInt32BE(bytes, 16)?.takeIf { it > 0 }
        val height = readUInt32BE(bytes, 20)?.takeIf { it > 0 }

        var animated = false
        var offset = 8
        var scanned = 0
        while (scanned < MAX_STRUCTURES_SCANNED && offset + 8 <= bytes.size) {
            val length = readUInt32BE(bytes, offset) ?: break
            val type = asciiAt(bytes, offset + 4) ?: break
            // acTL 只出现在 APNG 中，且在第一个 IDAT 之前。
            if (type == "acTL") animated = true
            if (type == "IDAT" || type == "IEND") break
            val next = offset.toLong() + 12L + length.toLong()
            if (next > bytes.size) break
            offset = next.toInt()
            scanned++
        }

        return PNG(isAnimatedHint = animated, width = width, height = height, detectedBytes = used)
    }

    // ----------------------------------------------------------------- JPEG

    private fun detectJpeg(bytes: ByteArray, used: Int): MediaFileSignature? {
        if (!startsWith(bytes, JPEG_SIGNATURE)) return null
        // SOI 之后必须是另一个 marker（0xFF），否则只是碰巧以 FFD8 开头的二进制。
        if ((bytes[2].toInt() and 0xFF) != 0xFF) return null

        var width: Int? = null
        var height: Int? = null
        var offset = 2
        var scanned = 0
        while (scanned < MAX_STRUCTURES_SCANNED && offset + 1 < bytes.size) {
            if ((bytes[offset].toInt() and 0xFF) != 0xFF) break
            // 跳过 marker 前的填充 0xFF。
            var markerIndex = offset + 1
            while (markerIndex < bytes.size && (bytes[markerIndex].toInt() and 0xFF) == 0xFF) markerIndex++
            if (markerIndex >= bytes.size) break
            val marker = bytes[markerIndex].toInt() and 0xFF

            // 没有长度字段的 marker。
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                offset = markerIndex + 1
                scanned++
                continue
            }
            // EOI 或进入熵编码数据后不会再有更可靠的尺寸信息。
            if (marker == 0xD9 || marker == 0xDA) break

            val lengthOffset = markerIndex + 1
            val segmentLength = readUInt16BE(bytes, lengthOffset) ?: break
            if (segmentLength < 2) break

            val isStartOfFrame = marker in 0xC0..0xCF &&
                marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isStartOfFrame) {
                // SOF 段: 长度(2) 精度(1) 高(2) 宽(2) 分量数(1) ...
                height = readUInt16BE(bytes, lengthOffset + 3)?.takeIf { it > 0 }
                width = readUInt16BE(bytes, lengthOffset + 5)?.takeIf { it > 0 }
                break
            }

            val next = lengthOffset.toLong() + segmentLength.toLong()
            if (next > bytes.size) break
            offset = next.toInt()
            scanned++
        }

        return JPEG(width = width, height = height, detectedBytes = used)
    }

    // ------------------------------------------------------------------ GIF

    private fun detectGif(bytes: ByteArray, used: Int): MediaFileSignature? {
        val version = when {
            startsWith(bytes, ascii("GIF87a")) -> "GIF87a"
            startsWith(bytes, ascii("GIF89a")) -> "GIF89a"
            else -> return null
        }
        val width = readUInt16LE(bytes, 6)?.takeIf { it > 0 }
        val height = readUInt16LE(bytes, 8)?.takeIf { it > 0 }
        return GIF(
            version = version,
            isAnimatedHint = looksLikeAnimatedGif(bytes),
            width = width,
            height = height,
            detectedBytes = used,
        )
    }

    /**
     * 粗略多帧提示：窗口内出现 NETSCAPE2.0 应用扩展，或出现第二个 image descriptor。
     * 只是保守信号，不解析 LZW 数据块，因此 `false` 不保证是单帧。
     */
    private fun looksLikeAnimatedGif(bytes: ByteArray): Boolean {
        val netscape = ascii("NETSCAPE2.0")
        var descriptorCount = 0
        var index = 13 // header(6) + 逻辑屏幕描述符(7)
        while (index < bytes.size) {
            when (bytes[index].toInt() and 0xFF) {
                0x21 -> if (matchesAt(bytes, index + 1, netscape)) return true
                0x2C -> {
                    descriptorCount++
                    if (descriptorCount > 1) return true
                }

                0x3B -> return false // trailer
            }
            index++
        }
        return false
    }

    // ----------------------------------------------------------------- WebP

    private fun detectWebp(bytes: ByteArray, used: Int): MediaFileSignature? {
        if (!startsWith(bytes, RIFF_SIGNATURE)) return null
        if (!matchesAt(bytes, 8, WEBP_SIGNATURE) || bytes.size < 20) return null

        return when (asciiAt(bytes, 12)) {
            "VP8 " -> {
                // 关键帧: chunk data 起始 = 20；3 字节 frame tag 后是 0x9D 0x01 0x2A，再是 14 位宽/高。
                val hasSyncCode = bytes.size >= 26 &&
                    (bytes[23].toInt() and 0xFF) == 0x9D &&
                    (bytes[24].toInt() and 0xFF) == 0x01 &&
                    (bytes[25].toInt() and 0xFF) == 0x2A
                WebP(
                    isAnimatedHint = false,
                    width = if (hasSyncCode) readUInt16LE(bytes, 26)?.and(0x3FFF)?.takeIf { it > 0 } else null,
                    height = if (hasSyncCode) readUInt16LE(bytes, 28)?.and(0x3FFF)?.takeIf { it > 0 } else null,
                    detectedBytes = used,
                )
            }

            "VP8L" -> {
                // 无损: data 起始 = 20 是 signature 0x2F，随后 14 位(宽-1) + 14 位(高-1)。
                val hasSignature = bytes.size >= 25 && (bytes[20].toInt() and 0xFF) == 0x2F
                if (!hasSignature) {
                    WebP(isAnimatedHint = false, width = null, height = null, detectedBytes = used)
                } else {
                    val bits = readUInt32LE(bytes, 21) ?: 0
                    WebP(
                        isAnimatedHint = false,
                        width = (bits and 0x3FFF) + 1,
                        height = ((bits ushr 14) and 0x3FFF) + 1,
                        detectedBytes = used,
                    )
                }
            }

            "VP8X" -> {
                // 扩展格式: data 起始 = 20 是 1 字节 flag，之后 3 字节保留，再 24 位(宽-1)、24 位(高-1)。
                val flags = if (bytes.size >= 21) bytes[20].toInt() and 0xFF else 0
                WebP(
                    isAnimatedHint = (flags and 0x02) != 0,
                    width = if (bytes.size >= 30) readUInt24LE(bytes, 24)?.plus(1) else null,
                    height = if (bytes.size >= 30) readUInt24LE(bytes, 27)?.plus(1) else null,
                    detectedBytes = used,
                )
            }

            else -> WebP(isAnimatedHint = false, width = null, height = null, detectedBytes = used)
        }
    }

    // ------------------------------------------------- MP4 / ISOBMFF

    private fun detectIsoBmff(bytes: ByteArray, used: Int): MediaFileSignature? {
        val firstBox = asciiAt(bytes, 4) ?: return null
        if (firstBox !in ISO_BMFF_FIRST_BOXES) return null

        val boxSize = readUInt32BE(bytes, 0) ?: return null
        // 0 = 延伸到文件末尾，1 = 64 位 largesize；其余必须至少容纳 8 字节 box header。
        if (boxSize != 0 && boxSize != 1 && boxSize < 8) return null

        var brand: String? = null
        if (firstBox == "ftyp" || firstBox == "styp") {
            // ftyp/styp: size(4) type(4) major_brand(4) minor_version(4)
            val majorBrand = asciiAt(bytes, if (boxSize == 1) 16 else 8) ?: return null
            brand = majorBrand
        }

        val kindHint = when (brand) {
            in VIDEO_BRANDS -> MediaKindHint.VIDEO
            in AUDIO_BRANDS -> MediaKindHint.AUDIO
            in IMAGE_BRANDS -> MediaKindHint.IMAGE
            else -> MediaKindHint.UNKNOWN
        }
        return MP4(brand = brand, kindHint = kindHint, detectedBytes = used)
    }

    // ------------------------------------------------- Matroska / WebM

    private fun detectEbml(bytes: ByteArray, used: Int): MediaFileSignature? {
        if (!startsWith(bytes, EBML_SIGNATURE)) return null
        val docType = findEbmlDocType(bytes) ?: return null
        return when (docType.lowercase()) {
            "webm" -> WebM(docType = docType, detectedBytes = used)
            "matroska" -> Matroska(docType = docType, detectedBytes = used)
            // 其它 EBML 家族不在已支持集合内，不猜。
            else -> null
        }
    }

    /** 在头部窗口里找 EBML DocType 元素（id 0x4282，值形如 "webm"）。 */
    private fun findEbmlDocType(bytes: ByteArray): String? {
        var index = 4
        while (index + 3 < bytes.size) {
            val isDocTypeId = (bytes[index].toInt() and 0xFF) == 0x42 &&
                (bytes[index + 1].toInt() and 0xFF) == 0x82
            if (!isDocTypeId) {
                index++
                continue
            }
            // 长度是 vint 编码，实际取值都很短（1 字节长度即可覆盖）。
            val sizeByte = bytes[index + 2].toInt() and 0xFF
            if (sizeByte == 0) return null
            if (sizeByte and 0x80 == 0) return null // DocType uses a bounded one-byte VINT here
            val length = sizeByte and 0x7F
            if (length !in 1..16) return null
            val valueStart = index + 3
            if (valueStart + length > bytes.size) return null
            val builder = StringBuilder(length)
            for (k in 0 until length) {
                val c = bytes[valueStart + k].toInt() and 0xFF
                if (c < 0x20 || c > 0x7E) return null
                builder.append(c.toChar())
            }
            return builder.toString().ifEmpty { null }
        }
        return null
    }

    // ------------------------------------------------------------------ SVG

    private fun detectSvg(bytes: ByteArray, used: Int): MediaFileSignature? {
        val text = String(bytes, 0, bytes.size, StandardCharsets.UTF_8)
        // 去掉 BOM 与前置空白，避免漏掉带 BOM 的 SVG。
        val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n', '\u000B', '\u000C')
        if (trimmed.isEmpty()) return null

        // 明确拒绝 HTML：即使内联了 <svg>，它也不是独立 SVG 资源。
        if (trimmed.startsWith("<html", ignoreCase = true) ||
            trimmed.startsWith("<!doctype html", ignoreCase = true) ||
            trimmed.startsWith("<head", ignoreCase = true) ||
            trimmed.startsWith("<body", ignoreCase = true)
        ) return null

        val hasXmlDeclaration = trimmed.startsWith("<?xml", ignoreCase = true)
        val rootStart = if (hasXmlDeclaration) {
            val declarationEnd = trimmed.indexOf("?>")
            if (declarationEnd < 0) return null
            val next = trimmed.indexOf('<', declarationEnd + 2)
            if (next < 0 || next + 1 >= trimmed.length) return null
            next
        } else {
            0
        }

        val rootName = readXmlElementName(trimmed, rootStart) ?: return null
        // 接受 <svg> 与带命名空间前缀的 <svg:svg>。
        if (rootName != "svg" && !rootName.endsWith(":svg")) return null

        return SVG(rootElement = rootName, xmlDeclaration = hasXmlDeclaration, detectedBytes = used)
    }

    private fun readXmlElementName(text: String, start: Int): String? {
        if (start >= text.length || text[start] != '<') return null
        var index = start + 1
        if (index >= text.length) return null
        // 注释、DOCTYPE、其它处理指令都不算元素起始。
        if (text[index] == '!' || text[index] == '?') return null
        val builder = StringBuilder()
        while (index < text.length) {
            val c = text[index]
            if (c.isWhitespace() || c == '>' || c == '/') break
            if (!(c.isLetterOrDigit() || c == ':' || c == '_' || c == '-' || c == '.')) return null
            builder.append(c)
            index++
        }
        return builder.toString().ifEmpty { null }
    }

    // --------------------------------------------------------- byte helpers

    private fun ascii(value: String): ByteArray = value.toByteArray(StandardCharsets.US_ASCII)

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean = matchesAt(bytes, 0, prefix)

    private fun matchesAt(bytes: ByteArray, offset: Int, needle: ByteArray): Boolean {
        if (offset < 0 || offset + needle.size > bytes.size) return false
        for (k in needle.indices) {
            if (bytes[offset + k] != needle[k]) return false
        }
        return true
    }

    /** 读取 offset 处 4 字节可打印 ASCII；含不可打印字符时返回 null，说明不是预期的容器类型字段。 */
    private fun asciiAt(bytes: ByteArray, offset: Int): String? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        val builder = StringBuilder(4)
        for (k in 0 until 4) {
            val c = bytes[offset + k].toInt() and 0xFF
            if (c < 0x20 || c > 0x7E) return null
            builder.append(c.toChar())
        }
        return builder.toString()
    }

    private fun readUInt32BE(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        val value = ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
        return if (value > Int.MAX_VALUE) null else value.toInt()
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        val value = (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
        return if (value > Int.MAX_VALUE) null else value.toInt()
    }

    private fun readUInt16BE(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 2 > bytes.size) return null
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 2 > bytes.size) return null
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt24LE(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 3 > bytes.size) return null
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
    }
    }

    /** Prefix detection is not a complete decoder/animation check. */
    /** 推荐 MIME；无法确定时为 null。 */
    abstract val mime: String?

    /** 推荐扩展名（不带点）；无法确定时为 null。 */
    abstract val extension: String?

    /** 动画提示，见类文档。 */
    abstract val isAnimatedHint: Boolean?

    /** 像素宽；头部信息不足或格式不支持时为 null。 */
    abstract val width: Int?

    /** 像素高；头部信息不足或格式不支持时为 null。 */
    abstract val height: Int?

    /** 本次判断实际看过的字节数（最多 [MediaFileSignature.MAX_HEADER_BYTES]）。 */
    abstract val detectedBytes: Int

    data class PNG(
        override val isAnimatedHint: Boolean,
        override val width: Int?,
        override val height: Int?,
        override val detectedBytes: Int,
    ) : MediaFileSignature() {
        override val mime: String get() = "image/png"
        override val extension: String get() = "png"
    }

    data class JPEG(
        override val width: Int?,
        override val height: Int?,
        override val detectedBytes: Int,
    ) : MediaFileSignature() {
        override val mime: String get() = "image/jpeg"
        override val extension: String get() = "jpg"
        override val isAnimatedHint: Boolean? get() = null
    }

    data class GIF(
        val version: String,
        override val isAnimatedHint: Boolean,
        override val width: Int?,
        override val height: Int?,
        override val detectedBytes: Int,
    ) : MediaFileSignature() {
        override val mime: String get() = "image/gif"
        override val extension: String get() = "gif"
    }

    data class WebP(
        override val isAnimatedHint: Boolean,
        override val width: Int?,
        override val height: Int?,
        override val detectedBytes: Int,
    ) : MediaFileSignature() {
        override val mime: String get() = "image/webp"
        override val extension: String get() = "webp"
    }

    /** SVG 尺寸取决于渲染，头部不做解析，交给 App 侧渲染时确定。 */
    data class SVG(
        val rootElement: String,
        val xmlDeclaration: Boolean,
        override val detectedBytes: Int,
    ) : MediaFileSignature() {
        override val mime: String get() = "image/svg+xml"
        override val extension: String get() = "svg"
        override val isAnimatedHint: Boolean? get() = null
        override val width: Int? get() = null
        override val height: Int? get() = null
    }

    data class MP4(
        val brand: String?,
        val kindHint: MediaKindHint,
        override val detectedBytes: Int,
    ) : MediaFileSignature() {
        /** 只有视频品牌（或品牌未知但容器是 MP4）才给视频 MIME，不把 .m4a/.heic 当视频。 */
        override val mime: String?
            get() = when (kindHint) {
                MediaKindHint.VIDEO, MediaKindHint.UNKNOWN -> "video/mp4"
                MediaKindHint.AUDIO -> "audio/mp4"
                MediaKindHint.IMAGE -> null
            }

        override val extension: String?
            get() = when (kindHint) {
                MediaKindHint.VIDEO, MediaKindHint.UNKNOWN -> "mp4"
                MediaKindHint.AUDIO, MediaKindHint.IMAGE -> null
            }

        override val isAnimatedHint: Boolean? get() = null
        override val width: Int? get() = null
        override val height: Int? get() = null
    }

    data class WebM(
        val docType: String?,
        override val detectedBytes: Int,
    ) : MediaFileSignature() {
        override val mime: String get() = "video/webm"
        override val extension: String get() = "webm"
        override val isAnimatedHint: Boolean? get() = null
        override val width: Int? get() = null
        override val height: Int? get() = null
    }

    /** 非 WebM 的 Matroska（.mkv）：容器可确认，但不给 MIME，是否支持由上层决定。 */
    data class Matroska(
        val docType: String?,
        override val detectedBytes: Int,
    ) : MediaFileSignature() {
        override val mime: String? get() = null
        override val extension: String? get() = "mkv"
        override val isAnimatedHint: Boolean? get() = null
        override val width: Int? get() = null
        override val height: Int? get() = null
    }

    /**
     * 无法确认：空输入、过短输入、HTML/JSON 等非媒体文本、未支持的格式都落到这里。
     * 所有字段为 null，调用方不要据此推断格式。
     */
    data class Unknown(
        override val detectedBytes: Int,
    ) : MediaFileSignature() {
        override val mime: String? get() = null
        override val extension: String? get() = null
        override val isAnimatedHint: Boolean? get() = null
        override val width: Int? get() = null
        override val height: Int? get() = null
    }
}

/** Container brand hint only; actual tracks still require a decoder. */
enum class MediaKindHint { VIDEO, AUDIO, IMAGE, UNKNOWN }

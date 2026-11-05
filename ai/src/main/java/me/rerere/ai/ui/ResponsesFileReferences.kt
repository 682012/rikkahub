package me.rerere.ai.ui

/**
 * Provider 返回的、需要 App 经过鉴权后才能取到的文件引用。
 *
 * 这类引用**不是**可直接打开的 URL：内容端点需要 `Authorization` 令牌，凭据绝不能写进会被持久化、
 * 导出或记进日志的字符串。所以这里只保存定位信息，用不透明的私有 scheme 表示，
 * 由调用方（App 侧，带配置好的 provider 凭据）解析后再下载、校验、落地成本地文件。
 *
 * 形如：`rkresponses-file://container?container_id=cntr_x&file_id=cfile_y`
 * 或：`rkresponses-file://file?file_id=file-abc`
 */
data class ResponsesFileReference(
    val kind: Kind,
    val containerId: String?,
    val fileId: String,
) {
    enum class Kind {
        /** Responses 容器文件：`GET /containers/{container_id}/files/{file_id}/content` */
        CONTAINER,

        /** 向量库文件（file_search / file_citation）：通常只有 file_id。 */
        STORE,
    }
}

/** 私有 scheme：刻意不是 http(s)，任何 HTTP 客户端都不会直接去取。 */
const val RESPONSES_FILE_REFERENCE_SCHEME: String = "rkresponses-file"

private const val RESPONSES_FILE_REFERENCE_CONTAINER_HOST = "container"
private const val RESPONSES_FILE_REFERENCE_STORE_HOST = "file"

/** 构造容器文件引用；[containerId] 或 [fileId] 为空时返回空串，调用方据此跳过。 */
fun responsesContainerFileReference(containerId: String, fileId: String): String {
    if (containerId.isBlank() || fileId.isBlank()) return ""
    return "$RESPONSES_FILE_REFERENCE_SCHEME://$RESPONSES_FILE_REFERENCE_CONTAINER_HOST" +
        "?container_id=${containerId.encodeComponent()}&file_id=${fileId.encodeComponent()}"
}

/** 构造向量库文件引用。 */
fun responsesStoreFileReference(fileId: String): String {
    if (fileId.isBlank()) return ""
    return "$RESPONSES_FILE_REFERENCE_SCHEME://$RESPONSES_FILE_REFERENCE_STORE_HOST" +
        "?file_id=${fileId.encodeComponent()}"
}

/**
 * 解析 [responsesContainerFileReference] / [responsesStoreFileReference] 生成的字符串。
 *
 * 不是本 scheme、缺少 file_id、或容器引用缺少 container_id 时返回 null —— 调用方应按“不是可取回的文件”
 * 处理，而不是猜一个地址去请求。
 */
fun parseResponsesFileReference(url: String?): ResponsesFileReference? {
    val raw = url?.trim().orEmpty()
    val schemeSeparator = raw.indexOf("://")
    if (schemeSeparator <= 0) return null
    if (!raw.substring(0, schemeSeparator).equals(RESPONSES_FILE_REFERENCE_SCHEME, ignoreCase = true)) return null

    val rest = raw.substring(schemeSeparator + 3)
    val host = rest.substringBefore('?').trim().trimEnd('/')
    val query = rest.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
    val params = query.split('&')
        .filter { it.isNotBlank() }
        .mapNotNull { pair ->
            val key = pair.substringBefore('=', missingDelimiterValue = "")
            if (key.isBlank()) null else key to pair.substringAfter('=', missingDelimiterValue = "").decodeComponent()
        }
        .toMap()

    val fileId = params["file_id"]?.takeIf { it.isNotBlank() } ?: return null
    val containerId = params["container_id"]?.takeIf { it.isNotBlank() }

    return when (host.lowercase()) {
        RESPONSES_FILE_REFERENCE_CONTAINER_HOST ->
            ResponsesFileReference(ResponsesFileReference.Kind.CONTAINER, containerId ?: return null, fileId)

        RESPONSES_FILE_REFERENCE_STORE_HOST ->
            ResponsesFileReference(ResponsesFileReference.Kind.STORE, containerId, fileId)

        else -> null
    }
}

private fun String.encodeComponent(): String = buildString {
    this@encodeComponent.encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xFF
        val char = value.toChar()
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~") {
            append(char)
        } else {
            append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
    }
}

private fun String.decodeComponent(): String {
    if ('%' !in this) return this
    val bytes = ArrayList<Byte>(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%' && index + 2 < length) {
            val high = HEX.indexOf(this[index + 1].uppercaseChar())
            val low = HEX.indexOf(this[index + 2].uppercaseChar())
            if (high >= 0 && low >= 0) {
                bytes.add(((high shl 4) or low).toByte())
                index += 3
                continue
            }
        }
        bytes += char.toString().encodeToByteArray().toList()
        index++
    }
    return bytes.toByteArray().decodeToString()
}

private const val HEX = "0123456789ABCDEF"

/** Stable artifact identity for repeated streaming/final events; no credentials. */
@PublishedApi
internal fun UIMessagePart.Document.responsesFileArtifactId(): String? {
    val reference = parseResponsesFileReference(url)
    val meta = metadataAs<ResponsesFileOutputMetadata>()
    val fileId = meta?.fileId ?: reference?.fileId ?: return null
    val containerId = meta?.containerId ?: reference?.containerId
    return if (containerId != null) "container:$containerId/$fileId" else "file:$fileId"
}

/** Unresolved documents must survive output cleanup so the app can retrieve them. */
internal fun UIMessagePart.Document.isUnresolvedProviderReference(): Boolean =
    parseResponsesFileReference(url) != null


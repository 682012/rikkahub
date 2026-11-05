package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ResponsesFileOutputMetadata
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.responsesContainerFileReference
import me.rerere.ai.ui.responsesStoreFileReference
import me.rerere.ai.ui.toMetadata

/**
 * Responses 里的“文件产物”识别。
 *
 * 流式与非流式都只在 [UIMessagePart.Document] 里放一个**不含凭据**的引用
 * （`rkresponses-file://…`），真实内容要 App 用配置好的 provider 凭据去
 * `GET /containers/{container_id}/files/{file_id}/content` 取。
 *
 * 这里刻意不发明 URL：`sandbox:/mnt/data/x.png` 只是模型正文里的相对路径，
 * 不能当链接用，也不猜测网关域名。
 */

/** 解析一个 Responses annotation 对象；不认识的类型返回 null（不抛错，避免上游新增类型让整条回复失败）。 */
internal fun JsonObject.toResponsesAnnotation(): UIMessageAnnotation? {
    return when (field("type")) {
        "url_citation" -> {
            val url = field("url") ?: return null
            UIMessageAnnotation.UrlCitation(
                title = field("title")?.takeIf { it.isNotBlank() } ?: url,
                url = url,
            )
        }

        "container_file_citation" -> {
            val fileId = field("file_id") ?: return null
            val containerId = field("container_id") ?: return null
            UIMessageAnnotation.ContainerFileCitation(
                containerId = containerId,
                fileId = fileId,
                title = field("filename").orEmpty(),
                startIndex = intField("start_index") ?: UIMessageAnnotation.UNKNOWN_INDEX,
                endIndex = intField("end_index") ?: UIMessageAnnotation.UNKNOWN_INDEX,
            )
        }

        "file_citation" -> {
            val fileId = field("file_id") ?: return null
            // 规范里 file_citation 用单点 index；部分网关给 start_index/end_index，按需回退
            val index = intField("index") ?: intField("start_index") ?: UIMessageAnnotation.UNKNOWN_INDEX
            UIMessageAnnotation.FileCitation(
                title = field("filename").orEmpty(),
                fileId = fileId,
                containerId = field("container_id"),
                startIndex = index,
                endIndex = intField("end_index") ?: index,
            )
        }

        else -> null
    }
}

/** 解析 `message.content[].annotations`；跳过不认识的类型与非法项。 */
internal fun List<JsonElement>.toResponsesAnnotations(): List<UIMessageAnnotation> =
    mapNotNull { (it as? JsonObject)?.toResponsesAnnotation() }

/**
 * 把注解变成可打开的文件部件。
 *
 * 只有能定位内容（容器文件或库文件）的注解才会产生部件；`url_citation` 不产生文件。
 * [source] 是产生该注解的输出项类型（例如 `code_interpreter_call`），仅作来源描述，
 * 不代表该网关的 code interpreter 已经能取回产物。
 */
internal fun UIMessageAnnotation.toFileArtifactPart(source: String? = null): UIMessagePart.Document? {
    val spec = when (this) {
        is UIMessageAnnotation.ContainerFileCitation -> ArtifactSpec(
            url = responsesContainerFileReference(containerId, fileId),
            fileName = title,
            kind = ResponsesFileOutputMetadata.KIND_CONTAINER_FILE,
            containerId = containerId,
            fileId = fileId,
        )

        is UIMessageAnnotation.FileCitation -> ArtifactSpec(
            url = responsesStoreFileReference(fileId),
            fileName = title,
            kind = ResponsesFileOutputMetadata.KIND_FILE,
            containerId = containerId,
            fileId = fileId,
        )

        is UIMessageAnnotation.UrlCitation -> return null
    }
    if (spec.url.isEmpty()) return null

    val start = when (this) {
        is UIMessageAnnotation.ContainerFileCitation -> startIndex
        is UIMessageAnnotation.FileCitation -> startIndex
        else -> -1
    }
    val end = when (this) {
        is UIMessageAnnotation.ContainerFileCitation -> endIndex
        is UIMessageAnnotation.FileCitation -> endIndex
        else -> -1
    }
    val name = spec.fileName.fallbackName(spec.kind)
    return UIMessagePart.Document(
        url = spec.url,
        fileName = name,
        mime = guessMimeFromFileName(name),
        metadata = ResponsesFileOutputMetadata(
            protocol = ResponsesFileOutputMetadata.PROTOCOL,
            kind = spec.kind,
            containerId = spec.containerId,
            fileId = spec.fileId,
            filename = name,
            annotationStartIndex = start.takeIf { it >= 0 },
            annotationEndIndex = end.takeIf { it >= 0 },
            source = source,
        ).toMetadata(),
    )
}

/** 文件产物的稳定 id：同一容器同一文件必须是同一个 id，流式里才能去重。 */
internal fun UIMessageAnnotation.fileArtifactId(): String? = when (this) {
    is UIMessageAnnotation.ContainerFileCitation -> "container:$containerId/$fileId"
    is UIMessageAnnotation.FileCitation -> containerId?.let { "container:$it/$fileId" } ?: "file:$fileId"
    is UIMessageAnnotation.UrlCitation -> null
}

/** 非文件注解的去重键，按内容比较。 */
internal fun UIMessageAnnotation.annotationKey(): String = when (this) {
    is UIMessageAnnotation.UrlCitation -> "url:$url"
    is UIMessageAnnotation.ContainerFileCitation -> "container:$containerId/$fileId"
    is UIMessageAnnotation.FileCitation -> containerId?.let { "container:$it/$fileId" } ?: "file:$fileId"
}

/** 只是给 UI 的初始猜测；真实类型必须由 App 嗅探文件头后再改。 */
internal fun guessMimeFromFileName(fileName: String?): String {
    val name = fileName?.lowercase().orEmpty()
    return when (name.substringAfterLast('.', missingDelimiterValue = "")) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        "csv" -> "text/csv"
        "tsv" -> "text/tab-separated-values"
        "txt", "log" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "xml" -> "application/xml"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "mp4" -> "video/mp4"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}

private class ArtifactSpec(
    val url: String,
    val fileName: String,
    val kind: String,
    val containerId: String?,
    val fileId: String,
)

/** 文件名只用于展示与 mime 猜测：去掉路径分隔符和上跳段，避免把恶意名字当路径用。 */
private fun String.fallbackName(kind: String): String {
    val cleaned = substringAfterLast('/').substringAfterLast('\\').trim()
    return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." }
        ?: if (kind == ResponsesFileOutputMetadata.KIND_CONTAINER_FILE) "container_file" else "file"
}

private fun JsonObject.field(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.intField(key: String): Int? =
    get(key)?.jsonPrimitive?.intOrNull

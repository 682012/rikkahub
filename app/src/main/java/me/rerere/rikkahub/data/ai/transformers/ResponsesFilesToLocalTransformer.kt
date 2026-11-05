package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.MediaOutputVerifier
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ResponsesFileOutputMetadata
import me.rerere.ai.ui.ResponsesFileReference
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.parseResponsesFileReference
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.common.http.await
import me.rerere.mediagen.model.MediaFileSignature
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val TAG = "ResponsesFilesToLocal"

/** 单个产物的下载上限。超过就整份丢掉，绝不落盘半截文件。 */
const val RESPONSES_FILE_MAX_BYTES: Long = 64L * 1024 * 1024

private const val CONNECT_TIMEOUT_SECONDS = 20L
private const val READ_TIMEOUT_SECONDS = 120L

/** 失败标记：写进文件部件 metadata，界面据此显示“还没取到本地文件”，绝不假装成功。 */
const val RESPONSES_FILE_FAILED_KEY: String = "responses_file_failed"

/** 失败原因的稳定代码（见 [ResponsesFileFailure]），不含凭据、不含 URL。 */
const val RESPONSES_FILE_FAILED_REASON_KEY: String = "responses_file_failed_reason"

/**
 * 把 Responses 返回的、需要鉴权的文件引用（`rkresponses-file://…`）落地成本地文件。
 *
 * ### 为什么只在 [onGenerationFinish]、只动本轮助手消息
 *
 * 会话可以中途换模型，历史消息里的引用可能来自另一个 provider。拿当前 provider 的密钥去取别人的容器文件
 * 既取不到，也等于把凭据发给了一个无关的域名。所以历史部件一律跳过，界面保留引用与注解；流式过程中
 * 部件还在变，也不能提前换成本地文件。
 *
 * ### 行为约定（对齐 `ai/RESPONSES-INTEGRATION.md`）
 *
 * - 只有当前模型的 provider 是开了 Responses API 的 [ProviderSetting.OpenAI] 才会去取；别处没有这个端点，
 *   一律跳过、一个请求都不发。
 * - 地址严格由 `{baseUrl}/containers/{cid}/files/{fid}/content` 或 `{baseUrl}/files/{fid}/content` 拼出来，
 *   两段 id 都按路径段编码；不接受上游/历史里的任意 URL。
 * - 密钥只进请求头，不进 URL、不进日志、不进 metadata。
 * - 不跟随重定向（含同源），避免把 `Authorization` 带到别处；重定向判失败。
 * - 内容原封不动落盘（不重编码、不压缩），MIME 以字节嗅探为准，不信文件名也不信响应头。
 * - 失败只影响这一个部件：正文与注解照常显示；取消（[CancellationException]）照常向上抛，不打补丁。
 * - 缺少 file_id/container_id 的引用根本不下载，也不伪造成功。
 */
object ResponsesFilesToLocalTransformer : OutputMessageTransformer {

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (messages.none { message -> message.parts.any { it.isResponsesReferencePart() } }) {
            return messages
        }
        // 这一轮请求实际用的 provider（与 ChatRequestFactory 同一解析方式）
        val provider = ctx.model.findProvider(ctx.settings.providers)
        val resolver = ResponsesFileResolver(
            policy = responsesFileFetchPolicy(provider),
            currentModelId = ctx.model.id,
            filesManager = transformerFilesManager(),
            client = transformerHttpClient(),
        )
        return withContext(Dispatchers.IO) { resolver.resolveFinished(messages) }
    }
}

/* ---------------------------------------------------------------------------------------------- *
 * 谁能取、怎么拼地址
 * ---------------------------------------------------------------------------------------------- */

/**
 * 一次“能取什么、怎么取”的决定。返回 null 表示这个 provider 不该参与：整类跳过，一个请求都不发。
 */
internal data class ResponsesFileFetchPolicy(
    /** 容器文件端点；null 表示这个渠道没有容器端点，容器引用判 [ResponsesFileFailure.NO_ENDPOINT]。 */
    val containerContentUrl: ((containerId: String, fileId: String) -> String)?,
    /** 向量库文件端点；null 时判失败，而不是猜地址。 */
    val storeContentUrl: ((fileId: String) -> String)?,
    /** `Bearer …`；没配置密钥时为 null（不发 Authorization，也绝不借用别的 provider 的密钥）。 */
    val authorization: String?,
    /** 只带 provider 自定义头；会话 id 之类的对话专用头不带。 */
    val extraHeaders: Headers,
)

/**
 * 只有 [provider] 是开了 Responses API 的 `ProviderSetting.OpenAI` 才允许取文件。
 *
 * [provider] 必须由调用方按**当前设置**解析（`model.findProvider(settings.providers)`），
 * 也就是这一轮请求实际使用的那个。
 */
internal fun responsesFileFetchPolicy(provider: ProviderSetting?): ResponsesFileFetchPolicy? {
    if (provider !is ProviderSetting.OpenAI) return null
    if (!provider.useResponseApi) return null
    val baseUrl = provider.baseUrl.trim().trimEnd('/')
    if (baseUrl.isEmpty()) return null
    val root = baseUrl.toHttpUrlOrNull() ?: return null
    if (root.scheme != "https" && root.scheme != "http") return null

    return ResponsesFileFetchPolicy(
        containerContentUrl = { containerId, fileId ->
            "$baseUrl/containers/${containerId.encodePathSegment()}/files/${fileId.encodePathSegment()}/content"
        },
        storeContentUrl = { fileId -> "$baseUrl/files/${fileId.encodePathSegment()}/content" },
        authorization = provider.apiKey.trim().takeIf { it.isNotEmpty() }?.let { key -> "Bearer $key" },
        extraHeaders = provider.mergeCustomHeaders(),
    )
}

/**
 * 路径段编码：只保留 unreserved 字符（RFC 3986），其余按 UTF-8 百分号编码。
 *
 * 上游给的 id 理论上都是安全字符，但这是拼进 URL 路径的最后一道关：
 * `/`、`\`、`..`、`?`、`#`、`%` 都必须被编码，绝不能拼出别的路径。
 */
internal fun String.encodePathSegment(): String {
    require(this != "." && this != ".." && isNotBlank()) { "invalid file identifier" }
    return buildString {
    this@encodePathSegment.encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xFF
        val char = value.toChar()
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~") {
            append(char)
        } else {
            append('%').append(HEX_UPPER[value shr 4]).append(HEX_UPPER[value and 0x0F])
        }
    }
}
}

private const val HEX_UPPER = "0123456789ABCDEF"

/* ---------------------------------------------------------------------------------------------- *
 * 失败原因
 * ---------------------------------------------------------------------------------------------- */

/** 下载失败原因：只用于界面提示和诊断，不含凭据、不含完整 URL。 */
enum class ResponsesFileFailure(val code: String) {
    /** 引用里缺 file_id/container_id，或这个渠道没有对应端点：不下载，也不假装成功。 */
    NO_ENDPOINT("no_endpoint"),

    /** 当前模型不属于能取 Responses 文件的 provider（换了模型、不是 OpenAI、或没开 Responses）。 */
    PROVIDER_MISMATCH("provider_mismatch"),

    HTTP_401("http_401"),
    HTTP_403("http_403"),
    HTTP_404("http_404"),
    HTTP_4XX("http_4xx"),
    HTTP_5XX("http_5xx"),
    HTTP_OTHER("http_other"),

    /** 上游返回重定向：不跟随，免得凭据被带到别处。 */
    REDIRECTED("redirected"),
    NETWORK("network"),
    TOO_LARGE("too_large"),
    EMPTY("empty"),
    SAVE_FAILED("save_failed"),
    ;

    companion object {
        fun httpStatus(code: Int): ResponsesFileFailure = when {
            code == 401 -> HTTP_401
            code == 403 -> HTTP_403
            code == 404 -> HTTP_404
            code in 400..499 -> HTTP_4XX
            code in 500..599 -> HTTP_5XX
            else -> HTTP_OTHER
        }
    }
}

internal class ResponsesFileDownloadException(val failure: ResponsesFileFailure) :
    Exception(failure.code)

/** 落地结果，便于单测断言“是取到了、跳过了，还是失败了”。 */
sealed interface ResponsesFileOutcome {
    /** 不是本转换器的引用。 */
    data object Untouched : ResponsesFileOutcome

    /** 落地成功：本地文件 url + 嗅探出的真实 MIME。 */
    data class Stored(val localUrl: String, val mime: String) : ResponsesFileOutcome

    /** 失败：保留引用与正文，部件上带失败标记。 */
    data class Failed(val failure: ResponsesFileFailure) : ResponsesFileOutcome
}

/* ---------------------------------------------------------------------------------------------- *
 * 解析器：身份校验 + 只处理本轮新部件
 * ---------------------------------------------------------------------------------------------- */

/**
 * 把一条助手消息里的 Responses 文件引用换成本地文件。
 *
 * 身份快照校验：助手消息记录着生成它的 `model.id`。目标消息如果记着别的模型（[currentModelId] 是这一轮
 * 请求用的模型），直接判 [ResponsesFileFailure.PROVIDER_MISMATCH] 并发请求——宁可显示“这个文件没取到”，
 * 也不把当前 provider 的密钥用在别的 provider 的容器上。模型相同但 provider 配置换了（baseUrl、密钥）
 * 无法在持久化数据里区分，这时按当前配置取，取不到就是 404/401，只影响这个部件。
 *
 * [onlyNewParts] 是“可以从消息末尾往前处理多少个部件”，默认不限（生成结束时整条消息都属于本轮）。
 * 更早的部件属于之前的步骤/轮次，由调用方传 [AgentHooks] 的 newPartsFrom 语义决定是否排除。
 */
internal class ResponsesFileResolver(
    private val policy: ResponsesFileFetchPolicy?,
    private val currentModelId: Uuid? = null,
    private val filesManager: FilesManager? = null,
    private val client: OkHttpClient? = null,
    private val maxBytes: Long = RESPONSES_FILE_MAX_BYTES,
    private val onlyNewParts: Int = Int.MAX_VALUE,
) {
    /**
     * 处理一批消息：只动最后一条带引用的助手消息，其余原样返回。
     *
     * 没有任何需要处理的引用时直接返回原列表（调用方可以按引用相等判断“没动过”）。
     */
    suspend fun resolveFinished(messages: List<UIMessage>): List<UIMessage> {
        val index = messages.lastIndex
        if (index < 0 || messages[index].role != MessageRole.ASSISTANT ||
            messages[index].parts.none { it.isResponsesReferencePart() }) return messages
        val target = messages[index]
        val start = (target.parts.size - onlyNewParts).coerceIn(0, target.parts.size)
        var changed = false
        val parts = target.parts.mapIndexed { position, part ->
            if (position < start) return@mapIndexed part
            when (val outcome = resolvePart(part, target.modelId)) {
                ResponsesFileOutcome.Untouched -> part
                is ResponsesFileOutcome.Stored -> {
                    changed = true
                    (part as UIMessagePart.Document).toLocalPart(outcome.localUrl, outcome.mime)
                }

                is ResponsesFileOutcome.Failed -> {
                    changed = true
                    (part as UIMessagePart.Document).markUnavailable(outcome.failure)
                }
            }
        }
        if (!changed) return messages
        return messages.toMutableList().apply { this[index] = target.copy(parts = parts) }
    }

    /** 单个部件：不是引用就跳过；是引用就有界下载 + 落地。失败不抛（取消除外）。 */
    suspend fun resolvePart(
        part: UIMessagePart,
        messageModelId: Uuid? = null,
    ): ResponsesFileOutcome {
        if (!part.isResponsesReferencePart()) return ResponsesFileOutcome.Untouched
        val document = part as UIMessagePart.Document

        // provider 身份快照：消息生成时的模型与当前模型不一致时，绝不拿当前密钥去换文件
        if (messageModelId != null && currentModelId != null && messageModelId != currentModelId) {
            return ResponsesFileOutcome.Failed(ResponsesFileFailure.PROVIDER_MISMATCH)
        }
        val policy = policy ?: return ResponsesFileOutcome.Failed(ResponsesFileFailure.PROVIDER_MISMATCH)
        if (document.responsesFileFailure() != null) return ResponsesFileOutcome.Untouched
        val reference = parseResponsesFileReference(document.url)
            ?: return ResponsesFileOutcome.Failed(ResponsesFileFailure.NO_ENDPOINT)
        if (reference.kind != ResponsesFileReference.Kind.CONTAINER) return ResponsesFileOutcome.Untouched
        if (listOfNotNull(reference.containerId, reference.fileId).any { it == "." || it == ".." || it.any { ch -> ch == '/' || ch == '\\' || ch.isISOControl() } })
            return ResponsesFileOutcome.Failed(ResponsesFileFailure.NO_ENDPOINT)
        val client = client ?: return ResponsesFileOutcome.Failed(ResponsesFileFailure.NO_ENDPOINT)

        val url = when (reference.kind) {
            ResponsesFileReference.Kind.CONTAINER -> {
                val containerId = reference.containerId
                    ?: return ResponsesFileOutcome.Failed(ResponsesFileFailure.NO_ENDPOINT)
                val build = policy.containerContentUrl
                    ?: return ResponsesFileOutcome.Failed(ResponsesFileFailure.NO_ENDPOINT)
                build(containerId, reference.fileId)
            }

            ResponsesFileReference.Kind.STORE -> {
                val build = policy.storeContentUrl
                    ?: return ResponsesFileOutcome.Failed(ResponsesFileFailure.NO_ENDPOINT)
                build(reference.fileId)
            }
        }

        val bytes = try {
            fetch(client, url, policy)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponsesFileDownloadException) {
            Log.w(TAG, "resolve: ${document.fileName} unavailable (${e.failure.code})")
            return ResponsesFileOutcome.Failed(e.failure)
        } catch (e: Exception) {
            Log.w(TAG, "resolve: ${document.fileName} failed: ${e.javaClass.simpleName}")
            return ResponsesFileOutcome.Failed(ResponsesFileFailure.NETWORK)
        }

        val mime = responsesFileMime(bytes)
        val manager = filesManager ?: return ResponsesFileOutcome.Failed(ResponsesFileFailure.SAVE_FAILED)
        val stored = try {
            // 原封装盘：不重编码、不压缩，字节与服务器上的完全一致
            withContext(Dispatchers.IO) {
                val extension = MediaFileSignature.detect(bytes).extension
                val safeName = document.fileName.substringAfterLast('/').substringAfterLast('\\').take(180).ifBlank { "generated-file" }
                val storedName = extension?.let { safeName.substringBeforeLast('.', safeName) + ".$it" } ?: safeName
                val entity = manager.saveUploadFromBytes(bytes = bytes, displayName = storedName, mimeType = mime)
                val file = manager.getFile(entity)
                try {
                    if (mime.startsWith("image/") || mime.startsWith("video/"))
                        MediaOutputVerifier.validateLocal(file, mime.startsWith("video/"))
                    entity
                } catch (e: Exception) {
                    manager.deleteChatFiles(listOf(file.toUri()))
                    throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "resolve: storing ${document.fileName} failed: ${e.javaClass.simpleName}")
            return ResponsesFileOutcome.Failed(ResponsesFileFailure.SAVE_FAILED)
        }
        return ResponsesFileOutcome.Stored(
            localUrl = manager.getFile(stored).toUri().toString(),
            mime = mime,
        )
    }

    /** 一次有界、可取消的 GET。非 2xx、重定向、超限、断流都归为失败，不让异常往上冒。 */
    private suspend fun fetch(
        client: OkHttpClient,
        url: String,
        policy: ResponsesFileFetchPolicy,
    ): ByteArray {
        val builder = Request.Builder().url(url).get().headers(policy.extraHeaders)
        // 密钥只在这一个请求头里；不打日志、不拼进 URL
        policy.authorization?.let { builder.header("Authorization", it) }
        val call = client.newCall(builder.build())
        return try {
            coroutineScope {
                // 断流时线程卡在 read() 里，走不到 ensureActive()：取消时取消 call 让 read() 立刻抛出来
                val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.await().use { response: Response ->
                        if (response.isRedirect) {
                            throw ResponsesFileDownloadException(ResponsesFileFailure.REDIRECTED)
                        }
                        if (!response.isSuccessful) {
                            throw ResponsesFileDownloadException(
                                ResponsesFileFailure.httpStatus(response.code),
                            )
                        }
                        readBounded(response)
                    }
                } finally {
                    canceller.cancel()
                }
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: ResponsesFileDownloadException) {
            throw e
        } catch (e: java.io.IOException) {
            throw ResponsesFileDownloadException(ResponsesFileFailure.NETWORK)
        }
    }

    private suspend fun readBounded(response: Response): ByteArray {
        val declared = response.body?.contentLength() ?: -1L
        if (declared > maxBytes) throw ResponsesFileDownloadException(ResponsesFileFailure.TOO_LARGE)
        val body = response.body ?: throw ResponsesFileDownloadException(ResponsesFileFailure.EMPTY)
        val input: InputStream = body.byteStream()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw ResponsesFileDownloadException(ResponsesFileFailure.TOO_LARGE)
            out.write(buffer, 0, read)
        }
        if (out.size() == 0) throw ResponsesFileDownloadException(ResponsesFileFailure.EMPTY)
        return out.toByteArray()
    }
}

/* ---------------------------------------------------------------------------------------------- *
 * 部件改写
 * ---------------------------------------------------------------------------------------------- */

/** 部件是否还是没落地的 Responses 文件引用。 */
internal fun UIMessagePart.isResponsesReferencePart(): Boolean =
    this is UIMessagePart.Document && parseResponsesFileReference(url) != null

/**
 * 落地成功：`Document` 换成本地 url + 嗅探出的真实 MIME；能当图片/视频/音频打开的文件换成对应部件。
 *
 * metadata 原样保留（含引用 id 与注解锚点），历史里仍能看出它来自哪个 Responses 文件。
 */
internal fun UIMessagePart.Document.toLocalPart(localUrl: String, sniffedMime: String): UIMessagePart {
    val meta = metadataAs<ResponsesFileOutputMetadata>()?.toMetadata() ?: metadata
    return when {
        sniffedMime.startsWith("image/") -> UIMessagePart.Image(url = localUrl, metadata = meta)
        sniffedMime.startsWith("video/") -> UIMessagePart.Video(url = localUrl, metadata = meta)
        sniffedMime.startsWith("audio/") -> UIMessagePart.Audio(url = localUrl, metadata = meta)
        else -> UIMessagePart.Document(url = localUrl, fileName = fileName, mime = sniffedMime, metadata = meta)
    }
}

/**
 * 落地失败：保留引用与 metadata，只加上失败标记。
 *
 * 不伪造 url、不塞假字节：界面提示“这个文件没取到”，而不是给一个点了没反应的链接。正文与注解不受影响，
 * 也不会把密钥/完整 URL 写进消息。
 */
internal fun UIMessagePart.Document.markUnavailable(failure: ResponsesFileFailure): UIMessagePart.Document {
    val merged = buildJsonObject {
        metadata?.forEach { (key, value) -> put(key, value) }
        put(RESPONSES_FILE_FAILED_KEY, failure.code)
        put(RESPONSES_FILE_FAILED_REASON_KEY, failure.code)
    }
    return copy(metadata = merged)
}

/** 部件上的失败代码；成功落地或从未失败的部件返回 null。 */
fun UIMessagePart.Document.responsesFileFailure(): String? =
    (metadata?.get(RESPONSES_FILE_FAILED_REASON_KEY) as? JsonPrimitive)?.contentOrNull

/* ---------------------------------------------------------------------------------------------- *
 * MIME 嗅探：媒体靠 magic number，文本/文档按字节特征，绝不信任文件名/响应头
 * ---------------------------------------------------------------------------------------------- */

/**
 * 真实 MIME：先看媒体签名（[MediaFileSignature]），再按文本/文档的实际内容判断，最后才是通用二进制。
 *
 * 文件名不参与判断：`chart.png` 里装的是 HTML 就必须当 HTML，不能当 png 塞给图片查看器。
 */
internal fun responsesFileMime(bytes: ByteArray): String {
    MediaFileSignature.detect(bytes).mime?.let { return it }
    return sniffTextOrDocumentMime(bytes) ?: "application/octet-stream"
}

/** 文本/文档类内容的实际判型，返回 null 表示不像文本。 */
internal fun sniffTextOrDocumentMime(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return null
    val head = bytes.copyOf(minOf(bytes.size, TEXT_SNIFF_BYTES))
    // 含 NUL 的按二进制处理（UTF-16 文本极罕见，宁可当二进制也不当可展示文本）
    if (head.any { it == 0.toByte() }) return null
    val text = String(head, StandardCharsets.UTF_8)
    val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    val lower = trimmed.take(64).lowercase()
    return when {
        lower.startsWith("<!doctype html") || lower.startsWith("<html") -> "text/html"
        lower.startsWith("%pdf-") -> "application/pdf"
        lower.startsWith("<?xml") -> "application/xml"
        trimmed.startsWith("{") || trimmed.startsWith("[") ->
            if (isProbablyJson(trimmed)) "application/json" else "text/plain"

        trimmed.startsWith("<") -> if (looksLikeXml(trimmed)) "application/xml" else "text/plain"
        isProbablyText(head) -> "text/plain"
        else -> null
    }
}

private const val TEXT_SNIFF_BYTES = 8 * 1024

private fun isProbablyText(bytes: ByteArray): Boolean {
    var control = 0
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        when {
            value == 0 -> return false
            value == 9 || value == 10 || value == 13 -> Unit
            value < 0x20 -> control++
            else -> Unit
        }
    }
    return control * 20 <= bytes.size // 允许极少量控制字符（\f、ESC 之类）
}

private fun isProbablyJson(text: String): Boolean {
    if (runCatching { me.rerere.ai.util.json.parseToJsonElement(text) }.isSuccess) return true
    // 只截到头部时 JSON 不完整，退化成结构性检查
    val first = text.firstOrNull() ?: return false
    val last = text.trimEnd().lastOrNull() ?: return false
    return (first == '{' && last == '}') || (first == '[' && last == ']')
}

private fun looksLikeXml(text: String): Boolean {
    val end = text.indexOf('>')
    if (end <= 1) return false
    return text.substring(1, end).trimStart('/').substringBefore(' ').isNotEmpty()
}

/* ---------------------------------------------------------------------------------------------- *
 * 依赖获取（Koin；没有容器时安静降级，不崩）
 * ---------------------------------------------------------------------------------------------- */

internal fun transformerFilesManager(): FilesManager? = runCatching {
    org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<FilesManager>()
}.getOrNull()

/** 下载用的 client：复用应用的（带代理设置），但去掉重定向跟随并收紧超时。 */
internal fun transformerHttpClient(): OkHttpClient? = runCatching {
    org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<OkHttpClient>()?.let(::responsesFileHttpClient)
}.getOrNull()

/** 专用 client：连接/读取都有上限，不跟随重定向，不重试（重试由用户重发决定，避免重复写文件）。 */
internal fun responsesFileHttpClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .callTimeout(CONNECT_TIMEOUT_SECONDS + READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()

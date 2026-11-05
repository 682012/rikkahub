package me.rerere.mediagen.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.MediaGenerationError
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaGenerationUsage
import me.rerere.mediagen.provider.MediaGenerationProvider
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.long
import me.rerere.mediagen.provider.providers.obj
import me.rerere.mediagen.provider.providers.postJson
import me.rerere.mediagen.provider.providers.string
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * 目前只实现了图像：接口同步返回结果，[create] 直接得到终态任务。请求里带图片输入时走编辑接口，否则走生成接口。
 *
 * ## 请求里的产出格式
 * `output_format`（png / jpeg / webp）和 `quality` 都由 [MediaGenerationRequest.extraParameters] 传入：
 * 它们只有部分模型接受，属于模型级差异，不属于适配器的公共字段。适配器只负责把它们原样下发，
 * 并在公共字段之后写入，保证 `size` / `n` 不被覆盖。
 *
 * ## 响应里的产出格式
 * 响应里的 `output_format`、`size` 都是**声明**，不是证据：实测同一中转在请求 png 时返回过 JPEG，
 * 请求 1024x1024 时返回过 1254x1254。所以这里给出的 [MediaGenerationOutput.mimeType] 只能是提示，
 * 真正可用与否由上层按文件字节判断。
 */
class OpenAIMediaGenerationProvider(
    private val client: OkHttpClient,
) : MediaGenerationProvider<MediaGenerationProviderSetting.OpenAI> {
    override val id: String = PROVIDER_ID

    override suspend fun create(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = runCatching {
        val baseUrl = setting.baseUrl.trimEnd('/')
        val builder = Request.Builder().addHeader("Authorization", "Bearer ${setting.apiKey}")
        val httpRequest = if (request.inputs.isEmpty()) {
            builder.url("$baseUrl/images/generations").postJson(buildGenerationBody(setting, model, request))
        } else {
            builder.url("$baseUrl/images/edits").post(buildEditBody(setting, model, request))
        }.build()
        parseTask(client.executeJson(httpRequest, id), model.modelId)
    }

    internal fun buildGenerationBody(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        validate(request)
        // 先写公共请求里带入的额外参数，再由公共字段覆盖同名项
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
        put("prompt", request.prompt)
        request.count?.let { put("n", it) }
        resolveSize(setting, model, request.resolution)?.let { put("size", it) }
    }

    internal fun buildEditBody(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): MultipartBody {
        validate(request)
        val fields = linkedMapOf<String, String>()
        request.extraParameters.forEach { (key, value) ->
            fields[key] = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
        }
        fields["model"] = model.modelId
        fields["prompt"] = request.prompt.orEmpty()
        request.count?.let { fields["n"] = it.toString() }
        resolveSize(setting, model, request.resolution)?.let { fields["size"] = it }

        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (key, value) -> builder.addFormDataPart(key, value) }

        val imageFieldName = if (request.inputs.size == 1) "image" else "image[]"
        request.inputs.forEach { input ->
            require(input is MediaGenerationInput.Image) {
                "OpenAI image generation does not support ${input::class.simpleName} input"
            }
            val file = File(input.url.removePrefix("file://"))
            require(file.exists()) { "Image file does not exist: ${input.url}" }
            val mediaType = EDIT_IMAGE_MEDIA_TYPES[file.extension.lowercase()]
                ?: throw IllegalArgumentException("Unsupported image file type for OpenAI edit: ${file.extension}")
            builder.addFormDataPart(imageFieldName, file.name, file.asRequestBody(mediaType.toMediaType()))
        }
        return builder.build()
    }

    internal fun parseTask(root: JsonObject, fallbackModel: String? = null): MediaGenerationTask {
        val data = root["data"] as? JsonArray
        val outputs = data.orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            // 同一项里 url 和 b64_json 都给了、但都是空串的中转也见过，空串一律当作没有
            val base64 = item.string("b64_json")?.takeIf { it.isNotBlank() }
            val url = item.string("url")?.takeIf { it.isNotBlank() }
            if (base64 == null && url == null) return@mapNotNull null
            MediaGenerationOutput(
                url = url,
                data = base64?.let(::decodeBase64),
                // 只是提示：请求什么格式、返回什么格式都不代表文件实际是什么。真正的格式由字节决定。
                mimeType = mimeHint(item, root.string("output_format")),
                // 同理，响应里的 size 是声明值，不能当作实际尺寸
                resolution = item.string("size") ?: root.string("size"),
            )
        }
        // 空 data、空 base64 都不能算成功：调用方拿到空列表只会得到「保存失败」这种更含糊的错误
        val error = root.obj("error")
        if (outputs.isEmpty()) {
            return MediaGenerationTask(
                id = Uuid.random().toString(),
                provider = id,
                model = fallbackModel,
                status = MediaGenerationStatus.FAILED,
                error = MediaGenerationError(
                    code = error?.string("code"),
                    message = error?.string("message") ?: "OpenAI returned no image",
                ),
                createdAtEpochSeconds = root.long("created"),
                metadata = JsonObject(root - "data"),
            )
        }
        return MediaGenerationTask(
            // 同步接口没有服务端任务 ID
            id = Uuid.random().toString(),
            provider = id,
            model = fallbackModel,
            status = MediaGenerationStatus.SUCCEEDED,
            outputs = outputs,
            createdAtEpochSeconds = root.long("created"),
            usage = root.obj("usage")?.let {
                MediaGenerationUsage(totalTokens = it.long("total_tokens"))
            },
            // data 里是整张图片的 base64，不放进 metadata
            metadata = JsonObject(root - "data"),
        )
    }

    // 部分中转把 base64 包成 data URI 返回
    private fun decodeBase64(value: String): ByteArray {
        require(value.length <= 90 * 1024 * 1024) { "Image payload exceeds 64 MiB" }
        return Base64.decode(value.substringAfter("base64,")).also { require(it.isNotEmpty()) { "Image payload is empty" } }
    }

    // 每项自己的 output_format 优先，其次响应顶层的，最后按扩展名猜；都对不上时按 png 提示
    private fun mimeHint(item: JsonObject, defaultFormat: String? = null): String {
        val format = item.string("output_format")
            ?: item.string("media_type")
            ?: defaultFormat
            ?: item.string("url")?.let { url -> url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "") }
        return when {
            format.isNullOrBlank() -> "image/png"
            format.startsWith("image/") -> format.lowercase()
            else -> when (format.lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                else -> "image/png"
            }
        }
    }

    private fun validate(request: MediaGenerationRequest) {
        require(!request.prompt.isNullOrBlank()) { "OpenAI image generation requires a non-empty prompt" }
        require(request.aspectRatio == null) { "OpenAI image generation uses resolution instead of aspectRatio" }
        require(request.durationSeconds == null) { "OpenAI image generation does not expose duration" }
        require(request.generateAudio == null) { "OpenAI image generation does not expose generateAudio" }
        require(request.watermark == null) { "OpenAI image generation does not expose watermark" }
        require(request.seed == null) { "OpenAI image generation does not expose seed" }
        require(request.promptEnhancement == null) { "OpenAI image generation does not expose prompt enhancement" }
        require(request.callbackUrl == null) { "OpenAI image generation returns results synchronously" }
    }

    // "auto" 交给服务端默认值；Grok 的兼容接口不接受 size
    private fun resolveSize(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        size: String?,
    ): String? {
        if (size.isNullOrBlank() || size.equals("auto", ignoreCase = true)) return null
        return size.takeUnless { isGrok(setting, model) }
    }

    // 只匹配 x.ai 本身及其子域名，避免 "xxx-max.ai" 之类的中转域名被误判
    private fun isGrok(setting: MediaGenerationProviderSetting.OpenAI, model: MediaGenerationModel): Boolean {
        val host = setting.baseUrl.toHttpUrlOrNull()?.host?.lowercase()
        return host == "x.ai" || host?.endsWith(".x.ai") == true || model.modelId.contains("grok", ignoreCase = true)
    }

    private companion object {
        const val PROVIDER_ID = "openai"
        val EDIT_IMAGE_MEDIA_TYPES = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
        )
    }
}

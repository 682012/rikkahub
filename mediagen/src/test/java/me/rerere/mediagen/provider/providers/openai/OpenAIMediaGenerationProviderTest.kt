package me.rerere.mediagen.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.MediaGenerationApiException
import me.rerere.mediagen.provider.providers.bodyAsString
import me.rerere.mediagen.provider.providers.fakeClient
import me.rerere.mediagen.provider.providers.jsonResponse
import me.rerere.mediagen.provider.providers.mediaGenerationJson
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenAIMediaGenerationProviderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val setting = MediaGenerationProviderSetting.OpenAI(
        apiKey = "sk-test",
        baseUrl = "https://example.com/v1/",
    )
    private val model = MediaGenerationModel(modelId = "gpt-image-2", kind = MediaKind.IMAGE)

    @Test
    fun generationBodyLetsPublicFieldsOverrideExtraParameters() {
        val body = OpenAIMediaGenerationProvider(OkHttpClient()).buildGenerationBody(
            setting = setting,
            model = model,
            request = MediaGenerationRequest(
                prompt = "一只猫",
                count = 2,
                resolution = "2048x1152",
                extraParameters = buildJsonObject {
                    put("n", 9)
                    put("quality", "high")
                },
            ),
        )

        assertEquals(JsonPrimitive("gpt-image-2"), body["model"])
        assertEquals(JsonPrimitive("一只猫"), body["prompt"])
        assertEquals(JsonPrimitive(2), body["n"])
        assertEquals(JsonPrimitive("2048x1152"), body["size"])
        assertEquals(JsonPrimitive("high"), body["quality"])
    }

    @Test
    fun generationBodyOmitsAutoSizeAndGrokSize() {
        val provider = OpenAIMediaGenerationProvider(OkHttpClient())

        val auto = provider.buildGenerationBody(setting, model, MediaGenerationRequest(prompt = "cat", resolution = "auto"))
        assertNull(auto["size"])
        assertNull(auto["n"])

        val grok = provider.buildGenerationBody(
            setting = setting.copy(baseUrl = "https://api.x.ai/v1"),
            model = model.copy(modelId = "grok-imagine-image"),
            request = MediaGenerationRequest(prompt = "cat", resolution = "1024x1024"),
        )
        assertNull(grok["size"])

        // 域名只是以 x.ai 结尾的中转不应被当成 Grok
        val relay = provider.buildGenerationBody(
            setting = setting.copy(baseUrl = "https://api.foo-max.ai/v1"),
            model = model,
            request = MediaGenerationRequest(prompt = "cat", resolution = "1024x1024"),
        )
        assertEquals(JsonPrimitive("1024x1024"), relay["size"])
    }

    @Test
    fun rejectsFieldsTheImageApiDoesNotExpose() {
        val provider = OpenAIMediaGenerationProvider(OkHttpClient())

        assertThrows(IllegalArgumentException::class.java) {
            provider.buildGenerationBody(setting, model, MediaGenerationRequest(prompt = "cat", durationSeconds = 5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.buildEditBody(
                setting = setting,
                model = model,
                request = MediaGenerationRequest(inputs = listOf(MediaGenerationInput.Image("/tmp/a.png"))),
            )
        }
    }

    @Test
    fun generateReturnsTerminalTaskWithDecodedImages() = runBlocking {
        val requests = mutableListOf<Request>()
        val manager = MediaGenerationManager(fakeClient(requests) {
            jsonResponse(
                request = it,
                body = """
                    {
                      "created": 1785125529,
                      "output_format": "webp",
                      "size": "1024x1024",
                      "data": [{"b64_json": "aGVsbG8="}],
                      "usage": {"total_tokens": 4160}
                    }
                """.trimIndent(),
            )
        })

        val tasks = manager.generate(setting, model, MediaGenerationRequest(prompt = "cat")).toList()

        val request = requests.single()
        assertEquals("https://example.com/v1/images/generations", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))
        val sent = mediaGenerationJson.parseToJsonElement(request.bodyAsString()).jsonObject
        assertEquals(JsonPrimitive("cat"), sent["prompt"])

        val task = tasks.single()
        assertEquals(MediaGenerationStatus.SUCCEEDED, task.status)
        assertEquals("gpt-image-2", task.model)
        assertEquals(1785125529L, task.createdAtEpochSeconds)
        assertEquals(4160L, task.usage?.totalTokens)
        assertFalse("data" in task.metadata)
        val output = task.outputs.single()
        assertEquals("hello", output.data!!.decodeToString())
        assertNull(output.url)
        assertEquals("image/webp", output.mimeType)
        assertEquals("1024x1024", output.resolution)
    }

    @Test
    fun urlOnlyImagesAreReturnedAsUrlOutputs() {
        val root = mediaGenerationJson.parseToJsonElement(
            """{"data":[{"url":"https://cdn.example.com/out.png"}]}"""
        ).jsonObject

        val output = OpenAIMediaGenerationProvider(OkHttpClient()).parseTask(root).outputs.single()
        assertEquals("https://cdn.example.com/out.png", output.url)
        assertNull(output.data)
        assertEquals("image/png", output.mimeType)
    }

    @Test
    fun blankBase64FallsBackToUrl() {
        val root = mediaGenerationJson.parseToJsonElement(
            """{"data":[{"url":"https://cdn.example.com/out.png","b64_json":"","revised_prompt":null}]}"""
        ).jsonObject

        val output = OpenAIMediaGenerationProvider(OkHttpClient()).parseTask(root).outputs.single()
        assertEquals("https://cdn.example.com/out.png", output.url)
        assertNull(output.data)
    }

    @Test
    fun emptyDataIsNotSuccess() {
        val provider = OpenAIMediaGenerationProvider(OkHttpClient())

        val emptyList = provider.parseTask(mediaGenerationJson.parseToJsonElement("""{"created":1,"data":[]}""").jsonObject)
        assertEquals(MediaGenerationStatus.FAILED, emptyList.status)
        assertTrue(emptyList.outputs.isEmpty())
        assertEquals("OpenAI returned no image", emptyList.error?.message)

        val noImages = provider.parseTask(
            mediaGenerationJson.parseToJsonElement("""{"data":[{"b64_json":""},{"url":""},{"b64_json":null}]}""").jsonObject,
        )
        assertEquals(MediaGenerationStatus.FAILED, noImages.status)
        assertTrue(noImages.outputs.isEmpty())

        // data 字段整个缺失同样不是成功
        val missing = provider.parseTask(mediaGenerationJson.parseToJsonElement("""{"created":1}""").jsonObject)
        assertEquals(MediaGenerationStatus.FAILED, missing.status)

        // 错误信息要透传，而不是丢掉
        val withError = provider.parseTask(
            mediaGenerationJson.parseToJsonElement(
                """{"data":[],"error":{"code":"moderation_blocked","message":"blocked by safety"}}""",
            ).jsonObject,
        )
        assertEquals(MediaGenerationStatus.FAILED, withError.status)
        assertEquals("moderation_blocked", withError.error?.code)
        assertEquals("blocked by safety", withError.error?.message)
    }

    @Test
    fun declaredFormatIsOnlyAHintPerItem() {
        // 实测：请求 png 时中转返回过 JPEG。这里只按响应里的声明给提示，真实格式由文件字节决定（上层负责）
        val root = mediaGenerationJson.parseToJsonElement(
            """
            {
              "output_format": "png",
              "size": "1024x1024",
              "data": [
                {"b64_json": "aGVsbG8=", "output_format": "jpeg", "size": "1254x1254"},
                {"url": "https://cdn.example.com/out.webp?sign=1"}
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val outputs = OpenAIMediaGenerationProvider(OkHttpClient()).parseTask(root).outputs
        assertEquals(listOf("image/jpeg", "image/webp"), outputs.map { it.mimeType })
        assertEquals(listOf("1254x1254", "1024x1024"), outputs.map { it.resolution })
    }

    @Test
    fun dataUriBase64IsDecoded() {
        val root = mediaGenerationJson.parseToJsonElement(
            """{"data":[{"b64_json":"data:image/png;base64,aGVsbG8="}]}""",
        ).jsonObject

        assertEquals("hello", OpenAIMediaGenerationProvider(OkHttpClient()).parseTask(root).outputs.single().data!!.decodeToString())
    }

    @Test
    fun imageOutputOptionsRideExtraParametersAndKeepPublicFields() {
        val provider = OpenAIMediaGenerationProvider(OkHttpClient())
        val body = provider.buildGenerationBody(
            setting = setting,
            model = model.copy(modelId = "gpt-image-2.6-sunburst"),
            request = MediaGenerationRequest(
                prompt = "海报",
                count = 1,
                resolution = "1536x1024",
                extraParameters = buildJsonObject {
                    put("output_format", "webp")
                    put("quality", "high")
                    put("output_compression", 85)
                    put("complexity", "high")
                    // 公共字段必须覆盖额外参数里的同名项
                    put("size", "1024x1024")
                },
            ),
        )

        assertEquals(JsonPrimitive("webp"), body["output_format"])
        assertEquals(JsonPrimitive("high"), body["quality"])
        assertEquals(JsonPrimitive(85), body["output_compression"])
        assertEquals(JsonPrimitive("high"), body["complexity"])
        assertEquals(JsonPrimitive("1536x1024"), body["size"])
        assertEquals(JsonPrimitive(1), body["n"])
    }

    @Test
    fun imageInputsAreUploadedToEditEndpointAsMultipart() = runBlocking {
        val first = tempFolder.newFile("a.png").apply { writeText("png-bytes") }
        val second = tempFolder.newFile("b.JPG").apply { writeText("jpg-bytes") }
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        val manager = MediaGenerationManager(fakeClient(requests) {
            bodies += it.bodyAsString()
            jsonResponse(it, """{"data":[{"b64_json":"aGVsbG8="}]}""")
        })

        val task = manager.create(
            setting = setting,
            model = model,
            request = MediaGenerationRequest(
                prompt = "换成夜景",
                inputs = listOf(
                    MediaGenerationInput.Image(first.absolutePath),
                    MediaGenerationInput.Image("file://${second.absolutePath}"),
                ),
                resolution = "1024x1536",
                extraParameters = buildJsonObject { put("input_fidelity", "high") },
            ),
        ).getOrThrow()

        val request = requests.single()
        val body = bodies.single()
        assertEquals("https://example.com/v1/images/edits", request.url.toString())
        assertTrue(request.body!!.contentType().toString().startsWith("multipart/form-data"))
        assertTrue(body.contains("name=\"image[]\"; filename=\"a.png\""))
        assertTrue(body.contains("name=\"image[]\"; filename=\"b.JPG\""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        assertTrue(body.contains("png-bytes"))
        assertTrue(body.contains("name=\"prompt\""))
        assertTrue(body.contains("换成夜景"))
        assertTrue(body.contains("name=\"size\""))
        assertTrue(body.contains("name=\"input_fidelity\""))
        assertEquals("image/png", task.outputs.single().mimeType)
    }

    @Test
    fun editUsesSingleImageFieldAndRejectsUnsupportedInputs() {
        val provider = OpenAIMediaGenerationProvider(OkHttpClient())
        val png = tempFolder.newFile("only.png")
        val gif = tempFolder.newFile("anim.gif")
        fun editBody(vararg inputs: MediaGenerationInput) =
            provider.buildEditBody(setting, model, MediaGenerationRequest(prompt = "edit", inputs = inputs.toList()))

        val disposition = editBody(MediaGenerationInput.Image(png.absolutePath))
            .parts.last().headers!!["Content-Disposition"]!!
        assertTrue(disposition.contains("name=\"image\""))
        assertFalse(disposition.contains("image[]"))

        assertThrows(IllegalArgumentException::class.java) { editBody(MediaGenerationInput.Image(gif.absolutePath)) }
        assertThrows(IllegalArgumentException::class.java) { editBody(MediaGenerationInput.Image("/not/exist.png")) }
        assertThrows(IllegalArgumentException::class.java) {
            editBody(MediaGenerationInput.Video("https://example.com/a.mp4"))
        }
    }

    @Test
    fun apiErrorIsSurfacedWithStatusAndCode() = runBlocking {
        val manager = MediaGenerationManager(fakeClient {
            jsonResponse(
                request = it,
                body = """{"error":{"message":"Billing hard limit has been reached","code":"billing_hard_limit_reached"}}""",
                code = 400,
            )
        })

        val error = manager.create(setting, model, MediaGenerationRequest(prompt = "cat")).exceptionOrNull()
            as MediaGenerationApiException
        assertEquals(400, error.statusCode)
        assertEquals("billing_hard_limit_reached", error.code)
        assertEquals("Billing hard limit has been reached", error.message)
    }

    @Test
    fun synchronousProviderHasNoTaskToQuery() = runBlocking {
        val manager = MediaGenerationManager(fakeClient { error("query must not hit the network") })

        assertTrue(manager.query(setting, model, "any").exceptionOrNull() is UnsupportedOperationException)
    }

    @Test
    fun kindWithoutAdapterIsRejectedBeforeHittingTheNetwork() = runBlocking {
        val manager = MediaGenerationManager(fakeClient { error("unsupported kind must not hit the network") })
        val video = MediaGenerationModel(modelId = "sora-2", kind = MediaKind.VIDEO)

        assertEquals(setOf(MediaKind.IMAGE), setting.supportedKinds)
        val error = manager.create(setting, video, MediaGenerationRequest(prompt = "cat")).exceptionOrNull()
        assertTrue(error is UnsupportedOperationException)
        assertEquals("openai does not support VIDEO generation", error!!.message)
    }
}

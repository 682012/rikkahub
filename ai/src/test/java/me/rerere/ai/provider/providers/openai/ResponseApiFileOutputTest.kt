package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ResponsesFileOutputMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.parseResponsesFileReference
import me.rerere.ai.ui.withoutEmptyGeneratedImages
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 非流式 Responses：code_interpreter / file_search 的文件引用必须变成可打开的文件部件，
 * 同时保留注解，且绝不能把 `sandbox:/mnt/data/...` 当成可点击链接或往 URL 里塞凭据。
 */
class ResponseApiFileOutputTest {
    private val api = ResponseAPI(OkHttpClient())

    private fun containerFileCitation(fileName: String = "chart.gif") = buildJsonObject {
        put("type", "container_file_citation")
        put("container_id", "cntr_123")
        put("file_id", "cfile_456")
        put("filename", fileName)
        put("start_index", 41)
        put("end_index", 66)
    }

    private fun messageItem(vararg annotations: JsonObject) = buildJsonObject {
        put("type", "message")
        put("id", "msg_1")
        put("role", "assistant")
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "output_text")
                put("text", "Here is your chart: sandbox:/mnt/data/chart.gif")
                put("annotations", buildJsonArray { annotations.forEach { add(it) } })
            })
        })
    }

    private fun response(vararg items: JsonObject) = buildJsonObject {
        put("id", "resp_1")
        put("model", "test-model")
        put("status", "completed")
        put("output", buildJsonArray { items.forEach { add(it) } })
    }

    @Test
    fun `container file citation becomes a document with a credential free reference`() {
        val result = api.parseResponseOutput(response(messageItem(containerFileCitation())))

        val text = result.message.parts.filterIsInstance<UIMessagePart.Text>().single()
        assertEquals("Here is your chart: sandbox:/mnt/data/chart.gif", text.text)

        val document = result.message.parts.filterIsInstance<UIMessagePart.Document>().single()
        assertEquals("chart.gif", document.fileName)
        assertEquals("image/gif", document.mime)
        assertEquals(
            "rkresponses-file://container?container_id=cntr_123&file_id=cfile_456",
            document.url,
        )
        assertTrue(document.requiresAuthenticatedFetch)
        assertTrue("must not invent an http link", !document.url.startsWith("http"))

        val reference = requireNotNull(parseResponsesFileReference(document.url))
        assertEquals("cntr_123", reference.containerId)
        assertEquals("cfile_456", reference.fileId)

        val metadata = requireNotNull(document.metadataAs<ResponsesFileOutputMetadata>())
        assertEquals("openai_responses", metadata.protocol)
        assertEquals("container_file", metadata.kind)
        assertEquals("cntr_123", metadata.containerId)
        assertEquals("cfile_456", metadata.fileId)
        assertEquals("chart.gif", metadata.filename)
        assertEquals(41, metadata.annotationStartIndex)
        assertEquals(66, metadata.annotationEndIndex)
        assertEquals("message", metadata.source)

        val annotation = result.message.annotations.single() as UIMessageAnnotation.ContainerFileCitation
        assertEquals("cntr_123", annotation.containerId)
        assertEquals("cfile_456", annotation.fileId)
        assertEquals("chart.gif", annotation.title)
        assertEquals(41, annotation.startIndex)
        assertEquals(66, annotation.endIndex)
    }

    @Test
    fun `repeated container citation in two items yields one document and one annotation`() {
        val result = api.parseResponseOutput(
            response(messageItem(containerFileCitation()), messageItem(containerFileCitation()))
        )

        assertEquals(1, result.message.parts.count { it is UIMessagePart.Document })
        assertEquals(1, result.message.annotations.size)
    }

    @Test
    fun `file citation becomes a store document without container`() {
        val result = api.parseResponseOutput(response(messageItem(buildJsonObject {
            put("type", "file_citation")
            put("file_id", "file-abc")
            put("filename", "report.csv")
            put("index", 12)
        })))

        val document = result.message.parts.filterIsInstance<UIMessagePart.Document>().single()
        assertEquals("report.csv", document.fileName)
        assertEquals("text/csv", document.mime)
        assertEquals("rkresponses-file://file?file_id=file-abc", document.url)
        val reference = requireNotNull(parseResponsesFileReference(document.url))
        assertNull(reference.containerId)
        assertEquals("file-abc", reference.fileId)

        val annotation = result.message.annotations.single() as UIMessageAnnotation.FileCitation
        assertEquals("file-abc", annotation.fileId)
        assertEquals(12, annotation.startIndex)
        assertEquals(12, annotation.endIndex)
    }

    @Test
    fun `url citation keeps working and creates no document`() {
        val result = api.parseResponseOutput(response(messageItem(buildJsonObject {
            put("type", "url_citation")
            put("url", "https://example.com/a")
            put("title", "Example")
        })))

        assertTrue(result.message.parts.none { it is UIMessagePart.Document })
        val annotation = result.message.annotations.single() as UIMessageAnnotation.UrlCitation
        assertEquals("https://example.com/a", annotation.url)
        assertEquals("Example", annotation.title)
    }

    @Test
    fun `image generation call result is still an inline image`() {
        val result = api.parseResponseOutput(response(
            buildJsonObject {
                put("type", "image_generation_call")
                put("id", "ig_1")
                put("status", "completed")
                put("result", "QUJD")
            },
            messageItem(containerFileCitation()),
        ))

        val image = result.message.parts.filterIsInstance<UIMessagePart.Image>().single()
        assertEquals("data:image/png;base64,QUJD", image.url)
        assertEquals(1, result.message.parts.count { it is UIMessagePart.Document })
    }

    @Test
    fun `container citation without container id is ignored instead of inventing a url`() {
        val result = api.parseResponseOutput(response(messageItem(buildJsonObject {
            put("type", "container_file_citation")
            put("file_id", "cfile_456")
            put("filename", "chart.png")
        })))

        assertTrue(result.message.parts.none { it is UIMessagePart.Document })
        assertTrue(result.message.annotations.isEmpty())
    }

    @Test
    fun `path traversal in filename is stripped`() {
        val result = api.parseResponseOutput(
            response(messageItem(containerFileCitation("../../etc/passwd")))
        )

        val document = result.message.parts.filterIsInstance<UIMessagePart.Document>().single()
        assertEquals("passwd", document.fileName)
    }

    @Test
    fun `unresolved provider reference is not uploaded again`() {
        val result = api.parseResponseOutput(response(messageItem(containerFileCitation())))

        val cleaned = result.message.withoutEmptyGeneratedImages()
        assertTrue(cleaned.parts.none { it is UIMessagePart.Document })
        // 正文和注解保留，用户仍能看到模型说了什么
        assertTrue(cleaned.parts.any { it is UIMessagePart.Text })
        assertEquals(1, cleaned.annotations.size)
    }

    @Test
    fun `resolved local file is preserved by cleanup`() {
        val message = UIMessage(
            role = me.rerere.ai.core.MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Document(url = "/data/user/0/files/chart.gif", fileName = "chart.gif", mime = "image/gif")),
        ).withoutEmptyGeneratedImages()

        assertNotNull(message.parts.singleOrNull() as? UIMessagePart.Document)
    }

    @Test
    fun `code interpreter request sends auto container`() {
        val body = api.buildRequestBody(
            capabilities = ResponseProviderCapabilities(),
            messages = listOf(UIMessage.user("draw a chart")),
            params = TextGenerationParams(
                model = Model(
                    modelId = "gpt-5.6-sol",
                    tools = setOf(BuiltInTools.CodeInterpreter),
                ),
            ),
            stream = false,
        )

        val tools = body["tools"] as JsonArray
        assertEquals(1, tools.size)
        val tool = tools.single() as JsonObject
        assertEquals("code_interpreter", tool["type"]?.jsonPrimitive?.content)
        val container = tool["container"] as JsonObject
        assertEquals("auto", container["type"]?.jsonPrimitive?.content)
    }
}

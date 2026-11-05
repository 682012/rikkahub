package me.rerere.ai.ui

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引用注解的序列化兼容性：新增 `file_citation` / `container_file_citation` 之后，
 * 已落库的 `url_citation` 历史数据必须仍然能读，新类型必须能往返。
 */
class UIMessageAnnotationTest {

    @Test
    fun `legacy url citation json still decodes`() {
        val raw = """{"type":"url_citation","title":"Example","url":"https://example.com"}"""
        val annotation = json.decodeFromString(UIMessageAnnotation.serializer(), raw)
        assertEquals(
            UIMessageAnnotation.UrlCitation("Example", "https://example.com"),
            annotation,
        )
    }

    @Test
    fun `container file citation round trips`() {
        val annotation = UIMessageAnnotation.ContainerFileCitation(
            containerId = "cntr_1",
            fileId = "cfile_2",
            title = "chart.gif",
            startIndex = 3,
            endIndex = 9,
        )
        val encoded = json.parseToJsonElement(json.encodeToString(UIMessageAnnotation.serializer(), annotation)).jsonObject
        assertEquals("container_file_citation", encoded["type"]?.jsonPrimitive?.content)
        val decoded = json.decodeFromString(UIMessageAnnotation.serializer(), encoded.toString())
        assertEquals(annotation, decoded)
    }

    @Test
    fun `file citation defaults unknown indexes`() {
        val annotation = json.decodeFromString(
            UIMessageAnnotation.serializer(),
            """{"type":"file_citation","title":"a.pdf","fileId":"file-1"}""",
        ) as UIMessageAnnotation.FileCitation
        assertEquals(UIMessageAnnotation.UNKNOWN_INDEX, annotation.startIndex)
        assertNull(annotation.containerId)
    }

    @Test
    fun `metadata omits null indexes`() {
        val metadata = ResponsesFileOutputMetadata(
            protocol = ResponsesFileOutputMetadata.PROTOCOL,
            kind = ResponsesFileOutputMetadata.KIND_CONTAINER_FILE,
            containerId = "c",
            fileId = "f",
        )
        val encoded = json.parseToJsonElement(json.encodeToString(ResponsesFileOutputMetadata.serializer(), metadata)).jsonObject
        assertFalse(encoded.containsKey("responses_file_start_index"))
        assertEquals("c", encoded["responses_file_container_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `document flags unresolved provider references only`() {
        assertTrue(
            UIMessagePart.Document(
                url = responsesContainerFileReference("c", "f"),
                fileName = "a.png",
            ).requiresAuthenticatedFetch,
        )
        assertFalse(UIMessagePart.Document(url = "/data/user/0/a.png", fileName = "a.png").requiresAuthenticatedFetch)
        assertFalse(UIMessagePart.Document(url = "https://x/y.png", fileName = "a.png").requiresAuthenticatedFetch)
        assertFalse(UIMessagePart.Document(url = "data:image/png;base64,AA", fileName = "a.png").requiresAuthenticatedFetch)
    }
}

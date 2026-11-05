package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.ResponsesFileOutputMetadata
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.parseResponsesFileReference
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式 Responses：`response.output_text.annotation.added` 与 `response.output_item.done`
 * / `response.completed` 会重复同一份文件引用，解码器必须只产出一份注解和一份文件部件，
 * 并且不影响正文的分段与顺序。
 */
class ResponseApiStreamDecoderFileOutputTest {

    private fun citationItem(fileName: String = "chart.gif") = buildJsonObject {
        put("type", "message")
        put("id", "msg_1")
        put("role", "assistant")
        put("status", "completed")
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "output_text")
                put("text", "Done: sandbox:/mnt/data/$fileName")
                put("annotations", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "container_file_citation")
                        put("container_id", "cntr_123")
                        put("file_id", "cfile_456")
                        put("filename", fileName)
                        put("start_index", 6)
                        put("end_index", 33)
                    })
                })
            })
        })
    }

    private fun textDelta(delta: String) = buildJsonObject {
        put("type", "response.output_text.delta")
        put("item_id", "msg_1")
        put("output_index", 1)
        put("content_index", 0)
        put("delta", delta)
    }

    private fun annotationAdded() = buildJsonObject {
        put("type", "response.output_text.annotation.added")
        put("item_id", "msg_1")
        put("output_index", 1)
        put("content_index", 0)
        put("annotation_index", 0)
        put("annotation", buildJsonObject {
            put("type", "container_file_citation")
            put("container_id", "cntr_123")
            put("file_id", "cfile_456")
            put("filename", "chart.gif")
            put("start_index", 6)
            put("end_index", 33)
        })
    }

    @Test
    fun `annotation added plus item done plus completed yields one artifact`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(annotationAdded()))
            addAll(decoder.decode(textDelta("Done: ")))
            addAll(decoder.decode(textDelta("sandbox:/mnt/data/chart.gif")))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_item.done")
                put("output_index", 1)
                put("item", citationItem())
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.completed")
                put("response", buildJsonObject {
                    put("id", "resp_1")
                    put("model", "test-model")
                    put("status", "completed")
                    put("output", buildJsonArray { add(citationItem()) })
                })
            }))
        }

        assertEquals(1, chunks.count { it is StreamChunk.Annotations })
        assertEquals(1, chunks.count { it is StreamChunk.FileArtifact })
        assertEquals(1, chunks.count { it is StreamChunk.Finish })

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val message = chunks.fold(listOf(UIMessage.user("chart"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }.last()

        assertEquals(1, message.annotations.size)
        assertEquals(1, message.parts.count { it is UIMessagePart.Document })

        val document = message.parts.filterIsInstance<UIMessagePart.Document>().single()
        assertEquals("chart.gif", document.fileName)
        assertEquals("rkresponses-file://container?container_id=cntr_123&file_id=cfile_456", document.url)
        assertEquals("cfile_456", requireNotNull(parseResponsesFileReference(document.url)).fileId)
        assertEquals(
            "container_file",
            document.metadataAs<ResponsesFileOutputMetadata>()?.kind,
        )

        val text = message.parts.filterIsInstance<UIMessagePart.Text>().single()
        assertEquals("Done: sandbox:/mnt/data/chart.gif", text.text)
        // 文件部件在正文之后
        assertTrue(message.parts.indexOf(document) > message.parts.indexOf(text))
    }

    @Test
    fun `item done without prior annotation still produces the artifact`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = decoder.decode(buildJsonObject {
            put("type", "response.output_item.done")
            put("output_index", 1)
            put("item", citationItem())
        })

        assertEquals(1, chunks.count { it is StreamChunk.Annotations })
        assertEquals(1, chunks.count { it is StreamChunk.FileArtifact })
    }

    @Test
    fun `duplicate annotation added events are collapsed`() {
        val decoder = ResponseApiStreamDecoder()
        decoder.decode(annotationAdded())
        val second = decoder.decode(annotationAdded())

        assertTrue(second.none { it is StreamChunk.Annotations })
        assertTrue(second.none { it is StreamChunk.FileArtifact })
    }

    @Test
    fun `two distinct container files both survive`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(annotationAdded()))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_text.annotation.added")
                put("item_id", "msg_1")
                put("output_index", 1)
                put("content_index", 0)
                put("annotation_index", 1)
                put("annotation", buildJsonObject {
                    put("type", "container_file_citation")
                    put("container_id", "cntr_123")
                    put("file_id", "cfile_999")
                    put("filename", "table.csv")
                })
            }))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val message = chunks.fold(listOf(UIMessage.user("chart"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }.last()

        val documents = message.parts.filterIsInstance<UIMessagePart.Document>()
        assertEquals(2, documents.size)
        assertEquals(setOf("chart.gif", "table.csv"), documents.map { it.fileName }.toSet())
        assertEquals("text/csv", documents.single { it.fileName == "table.csv" }.mime)
    }

    @Test
    fun `image generation call in stream still yields an image and no document`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_item.done")
                put("output_index", 0)
                put("item", buildJsonObject {
                    put("type", "image_generation_call")
                    put("id", "ig_1")
                    put("status", "completed")
                    put("result", "QUJD")
                })
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.completed")
                put("response", buildJsonObject { put("id", "resp_1") })
            }))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val message = chunks.fold(listOf(UIMessage.user("draw"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }.last()

        assertEquals("data:image/png;base64,QUJD", message.parts.filterIsInstance<UIMessagePart.Image>().single().url)
        assertTrue(message.parts.none { it is UIMessagePart.Document })
        assertTrue(chunks.none { it is StreamChunk.FileArtifact })
    }

    @Test
    fun `unknown annotation types are skipped without breaking the stream`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = decoder.decode(buildJsonObject {
            put("type", "response.output_text.annotation.added")
            put("item_id", "msg_1")
            put("output_index", 1)
            put("content_index", 0)
            put("annotation_index", 0)
            put("annotation", buildJsonObject { put("type", "future_annotation") })
        })

        assertTrue(chunks.isEmpty())
    }

    private fun ResponseApiStreamDecoder.decode(payload: JsonObject): List<StreamChunk> =
        accept(SseEvent(
            event = payload["type"]?.jsonPrimitive?.content,
            data = json.encodeToString(payload),
        )).chunks
}

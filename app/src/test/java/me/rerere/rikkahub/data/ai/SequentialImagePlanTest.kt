package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/**
 * 多图逐张识别的纯逻辑用例：打标记、单图请求规划、主请求替换、缓存 key 隔离。
 *
 * 这里不发任何网络请求，也不碰 Android 图形 API；真正的请求流程由 [SequentialImageDescriber] 负责，
 * 本轮没有跑 Gradle，所以没有编译/单测执行证据。
 */
class SequentialImagePlanTest {

    private fun image(url: String, flagged: Boolean = false): UIMessagePart =
        UIMessagePart.Image(url).let { if (flagged) it.withSequentialVisionFlag() else it }

    // 固定的 model id：Model 默认每次 random，指纹必须落在同一个模型实例上才可比
    private val baseModel = Model(modelId = "m1", displayName = "m1")

    private fun model(modelId: String = "m1") = baseModel.copy(modelId = modelId)

    private val conversationId = Uuid.random()

    @Test
    fun `mark only flags images and keeps other parts untouched`() {
        val text = UIMessagePart.Text("看看这两张图")
        val doc = UIMessagePart.Document(url = "file:///tmp/a.pdf", fileName = "a.pdf")
        val parts = markSequentialImages(listOf(text, image("file:///tmp/1.jpg"), doc, image("file:///tmp/2.jpg")))

        assertEquals(text, parts[0])
        assertEquals(doc, parts[2])
        assertTrue(parts[1].isSequentialVisionImage())
        assertTrue(parts[3].isSequentialVisionImage())
    }

    @Test
    fun `unflagged image keeps old semantics`() {
        val plain = image("file:///tmp/1.jpg")
        assertFalse(plain.isSequentialVisionImage())
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(plain)))
        assertFalse(messages.hasSequentialVisionImages())
        assertEquals(0, messages.countSequentialVisionImages())
        assertTrue(planSequentialImages(messages, conversationId, "fp").isEmpty())
    }

    @Test
    fun `flag survives json round trip so later steps can reuse it`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("这是什么？"), image("file:///tmp/1.jpg", flagged = true)),
        ).markSequentialImages()
        val encoded = Json.encodeToString(UIMessage.serializer(), message)
        val decoded = Json.decodeFromString(UIMessage.serializer(), encoded)

        assertTrue(decoded.parts[1].isSequentialVisionImage())
        assertEquals("file:///tmp/1.jpg", (decoded.parts[1] as UIMessagePart.Image).url)
    }

    @Test
    fun `plan yields exactly one image per request and skips unflagged images`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("对比一下这三张"),
                    image("file:///tmp/1.jpg", flagged = true),
                    image("file:///tmp/2.jpg"),
                    image("file:///tmp/3.jpg", flagged = true),
                ),
            ),
        )
        val tasks = planSequentialImages(messages, conversationId, "fp", stat = { null })

        assertEquals(2, tasks.size)
        assertEquals(listOf(1, 2), tasks.map { it.ordinal })
        assertEquals(listOf(2, 2), tasks.map { it.total })
        assertEquals(listOf("file:///tmp/1.jpg", "file:///tmp/3.jpg"), tasks.map { it.url })
        // 每个任务只带一个 url，且都来自被标记的 part
        assertTrue(tasks.all { it.url.isNotBlank() })
        assertEquals(listOf(1, 3), tasks.map { it.partIndex })
        assertEquals("对比一下这三张", tasks[0].question)
        assertEquals(2, tasks.map { it.cacheKey }.distinct().size)
    }

    @Test
    fun `plan does not re-send history images`() {
        val history = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("上一轮的结果"), image("file:///tmp/old.jpg")),
        )
        val current = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("新问题"), image("file:///tmp/new.jpg", flagged = true)),
        )
        val tasks = planSequentialImages(listOf(history, current), conversationId, "fp", stat = { null })

        assertEquals(1, tasks.size)
        assertEquals("file:///tmp/new.jpg", tasks.single().url)
    }

    @Test
    fun `replace turns flagged images into notes and leaves originals alone`() {
        val original = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("这两张图有什么区别？"),
                    image("file:///tmp/1.jpg", flagged = true),
                    image("file:///tmp/2.jpg", flagged = true),
                    image("file:///tmp/keep.jpg"),
                ),
            ),
        )
        val tasks = planSequentialImages(original, conversationId, "fp", stat = { null })
        val notes = tasks.associate { it.cacheKey to "第 ${it.ordinal} 张的描述" }
        val replaced = original.replaceSequentialImagesWithNotes(tasks, notes)

        val parts = replaced.single().parts
        assertTrue(parts.none { it.isSequentialVisionImage() })
        // 未标记的图继续按原样发给主请求，语义不变
        assertTrue(parts.any { it is UIMessagePart.Image && it.url == "file:///tmp/keep.jpg" })
        val texts = parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
        assertTrue(texts.any { "index=\"1\"" in it && "第 1 张的描述" in it })
        assertTrue(texts.any { "index=\"2\"" in it && "第 2 张的描述" in it })
        // 原消息不变：DB 里的原图不修改
        assertTrue(original.single().parts.any { it.isSequentialVisionImage() })
    }

    @Test
    fun `replace keeps image when a note is missing instead of faking success`() {
        val original = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(image("file:///tmp/1.jpg", flagged = true)),
            ),
        )
        val tasks = planSequentialImages(original, conversationId, "fp", stat = { null })
        val replaced = original.replaceSequentialImagesWithNotes(tasks, emptyMap())

        assertTrue(replaced.single().parts.single() is UIMessagePart.Image)
    }

    @Test
    fun `cache key isolates conversation model image stat and question`() {
        val base = sequentialImageCacheKey(
            conversationId = conversationId,
            modelFingerprint = "fp",
            imageUrl = "file:///tmp/1.jpg",
            fileStat = SequentialImageFileStat(size = 10, modifiedAt = 100),
            question = "这张图写了什么？",
        )

        assertEquals(
            base,
            sequentialImageCacheKey(
                conversationId = conversationId,
                modelFingerprint = "fp",
                imageUrl = "file:///tmp/1.jpg",
                fileStat = SequentialImageFileStat(size = 10, modifiedAt = 100),
                question = "  这张图写了什么？ ",
            ),
        )
        assertNotEquals(
            base,
            sequentialImageCacheKey(conversationId, "fp", "file:///tmp/1.jpg", SequentialImageFileStat(10, 100), "另一句话"),
        )
        assertNotEquals(
            base,
            sequentialImageCacheKey(Uuid.random(), "fp", "file:///tmp/1.jpg", SequentialImageFileStat(10, 100), "这张图写了什么？"),
        )
        assertNotEquals(
            base,
            sequentialImageCacheKey(conversationId, "fp2", "file:///tmp/1.jpg", SequentialImageFileStat(10, 100), "这张图写了什么？"),
        )
        assertNotEquals(
            base,
            sequentialImageCacheKey(conversationId, "fp", "file:///tmp/2.jpg", SequentialImageFileStat(10, 100), "这张图写了什么？"),
        )
        assertNotEquals(
            base,
            sequentialImageCacheKey(conversationId, "fp", "file:///tmp/1.jpg", SequentialImageFileStat(11, 100), "这张图写了什么？"),
        )
        assertNotEquals(
            base,
            sequentialImageCacheKey(conversationId, "fp", "file:///tmp/1.jpg", SequentialImageFileStat(10, 101), "这张图写了什么？"),
        )
    }

    @Test
    fun `model fingerprint changes with provider model headers and bodies`() {
        val base = modelConfigFingerprint("p1", "OpenAI", model())
        assertEquals(base, modelConfigFingerprint("p1", "OpenAI", model()))
        assertNotEquals(base, modelConfigFingerprint("p2", "OpenAI", model()))
        assertNotEquals(base, modelConfigFingerprint("p1", "Google", model()))
        assertNotEquals(base, modelConfigFingerprint("p1", "OpenAI", model("m2")))
        assertNotEquals(
            base,
            modelConfigFingerprint("p1", "OpenAI", model().copy(customHeaders = listOf(CustomHeader("X-A", "1")))),
        )
        assertNotEquals(
            base,
            modelConfigFingerprint(
                "p1",
                "OpenAI",
                model().copy(customBodies = listOf(CustomBody("top_k", kotlinx.serialization.json.JsonPrimitive(5)))),
            ),
        )
        // ProviderSetting 重载只是把 id/类型名喂给同一个纯函数
        val setting = ProviderSetting.OpenAI()
        assertTrue(modelConfigFingerprint(setting, model()).contains(setting.id.toString()))
    }

    @Test
    fun `local path parsing covers file urls and remote addresses`() {
        assertEquals("/tmp/a.jpg", sequentialImageLocalPath("file:///tmp/a.jpg"))
        assertEquals("/tmp/a b.jpg", sequentialImageLocalPath("file:///tmp/a%20b.jpg"))
        assertEquals("/storage/emulated/0/a.jpg", sequentialImageLocalPath("/storage/emulated/0/a.jpg"))
        assertNull(sequentialImageLocalPath("https://example.com/a.jpg"))
        assertNull(sequentialImageLocalPath("data:image/png;base64,AAAA"))
        assertNull(sequentialImageLocalPath(""))
    }

    @Test
    fun `local file stat drives cache key and updates with file content`() {
        val file = File.createTempFile("seq-image", ".jpg")
        try {
            file.writeBytes(ByteArray(10))
            val stat = sequentialImageFileStat("file://${file.absolutePath}")
            assertEquals(10L, stat?.size)
            val first = sequentialImageCacheKey(conversationId, "fp", "file://${file.absolutePath}", stat, "q")
            file.writeBytes(ByteArray(20))
            val second = sequentialImageCacheKey(
                conversationId,
                "fp",
                "file://${file.absolutePath}",
                sequentialImageFileStat("file://${file.absolutePath}"),
                "q",
            )
            assertNotEquals(first, second)
        } finally {
            file.delete()
        }
    }
}

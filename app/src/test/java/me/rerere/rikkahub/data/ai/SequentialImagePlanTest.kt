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

    // ---------------------------------------------------------------- 分批 --------------------------------

    @Test
    fun `mark stores batch size while legacy true still means one image per batch`() {
        val parts = markSequentialImages(
            listOf(image("file:///tmp/1.jpg"), image("file:///tmp/2.jpg")),
            batchSize = 3,
        )
        assertEquals(3, parts[0].sequentialVisionBatchSize())
        assertEquals(3, parts[1].sequentialVisionBatchSize())
        assertTrue(parts[0].isSequentialVisionImage())

        // r16 旧标记（true）读出来就是每批 1 张，旧消息不用迁移
        assertEquals(1, image("file:///tmp/1.jpg", flagged = true).sequentialVisionBatchSize())
        assertEquals(0, image("file:///tmp/1.jpg").sequentialVisionBatchSize())
        // 默认参数 = r16 行为
        assertEquals(1, markSequentialImages(listOf(image("file:///tmp/1.jpg")))[0].sequentialVisionBatchSize())
        // 设置给太大也不会超过上限
        assertEquals(
            SEQUENTIAL_VISION_MAX_BATCH_SIZE,
            markSequentialImages(listOf(image("file:///tmp/1.jpg")), batchSize = 999)[0].sequentialVisionBatchSize(),
        )
    }

    @Test
    fun `batch size survives json round trip`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("这几张图里写了什么？"), image("file:///tmp/1.jpg")),
        ).markSequentialImages(batchSize = 4)
        val decoded = Json.decodeFromString(
            UIMessage.serializer(),
            Json.encodeToString(UIMessage.serializer(), message),
        )

        assertEquals(4, decoded.parts[1].sequentialVisionBatchSize())
        assertTrue(decoded.parts[1].isSequentialVisionImage())
    }

    @Test
    fun `plan chunks images into real multi image batches in original order`() {
        val parts = listOf(UIMessagePart.Text("这五张图分别是什么？")) +
            (1..5).map { image("file:///tmp/$it.jpg") }
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = markSequentialImages(parts, batchSize = 2)))

        val batches = planSequentialImageBatches(messages, conversationId, "fp", stat = { null })

        assertEquals(listOf(2, 2, 1), batches.map { it.size })
        assertEquals(listOf(1, 2, 3, 4, 5), batches.flatMap { it.ordinals })
        assertEquals(listOf("file:///tmp/1.jpg", "file:///tmp/2.jpg"), batches[0].urls)
        assertEquals(listOf(1, 2, 3), batches.map { it.batchIndex })
        assertEquals(listOf(3, 3, 3), batches.map { it.batchCount })
        assertEquals(listOf(5, 5, 5), batches.map { it.total })
        assertEquals(listOf("1-2", "3-4", "5"), batches.map { it.ordinalLabel })
        assertEquals("这五张图分别是什么？", batches.first().question)

        // 摊平后仍然是一张图一个任务（序号/位置一对一），同批共享批次 key
        val tasks = planSequentialImages(messages, conversationId, "fp", stat = { null })
        assertEquals(listOf(1, 2, 3, 4, 5), tasks.map { it.ordinal })
        assertEquals(5, tasks.map { it.total }.distinct().single())
        assertEquals(batches[0].cacheKey, tasks[0].cacheKey)
        assertEquals(batches[0].cacheKey, tasks[1].cacheKey)
        assertNotEquals(batches[0].cacheKey, batches[1].cacheKey)
        assertEquals(listOf(true, false, true, false, true), tasks.map { it.batchLead })
    }

    @Test
    fun `batches never cross messages or questions`() {
        val first = UIMessage(
            role = MessageRole.USER,
            parts = markSequentialImages(
                listOf(UIMessagePart.Text("第一问"), image("file:///tmp/1.jpg"), image("file:///tmp/2.jpg")),
                batchSize = 5,
            ),
        )
        val second = UIMessage(
            role = MessageRole.USER,
            parts = markSequentialImages(
                listOf(UIMessagePart.Text("第二问"), image("file:///tmp/3.jpg"), image("file:///tmp/4.jpg")),
                batchSize = 5,
            ),
        )

        val batches = planSequentialImageBatches(listOf(first, second), conversationId, "fp", stat = { null })

        assertEquals(2, batches.size)
        assertEquals(listOf(0, 1), batches.map { it.messageIndex })
        assertEquals(listOf("第一问", "第二问"), batches.map { it.question })
        assertEquals(listOf(listOf(1, 2), listOf(3, 4)), batches.map { it.ordinals })
        assertNotEquals(batches[0].cacheKey, batches[1].cacheKey)
    }

    @Test
    fun `batch cache key covers order stats question model conversation and batch size`() {
        val a = SequentialImageCacheEntry("file:///tmp/1.jpg", SequentialImageFileStat(10, 100))
        val b = SequentialImageCacheEntry("file:///tmp/2.jpg", SequentialImageFileStat(20, 200))
        val key = sequentialImageBatchCacheKey(conversationId, "fp", listOf(a, b), "这两张有什么区别？")

        // 单张批次沿用 r16 旧 key：老缓存继续命中
        assertEquals(
            sequentialImageCacheKey(conversationId, "fp", a.url, a.fileStat, "这两张有什么区别？"),
            sequentialImageBatchCacheKey(conversationId, "fp", listOf(a), "这两张有什么区别？"),
        )
        // 顺序变了不能命中
        assertNotEquals(key, sequentialImageBatchCacheKey(conversationId, "fp", listOf(b, a), "这两张有什么区别？"))
        // 同批张数/内容变了不能命中
        assertNotEquals(key, sequentialImageBatchCacheKey(conversationId, "fp", listOf(a, a), "这两张有什么区别？"))
        assertNotEquals(key, sequentialImageBatchCacheKey(conversationId, "fp", listOf(a), "这两张有什么区别？"))
        assertNotEquals(
            key,
            sequentialImageBatchCacheKey(conversationId, "fp", listOf(a, b.copy(fileStat = SequentialImageFileStat(21, 200))), "这两张有什么区别？"),
        )
        // 会话/模型/问题隔离
        assertNotEquals(key, sequentialImageBatchCacheKey(Uuid.random(), "fp", listOf(a, b), "这两张有什么区别？"))
        assertNotEquals(key, sequentialImageBatchCacheKey(conversationId, "fp2", listOf(a, b), "这两张有什么区别？"))
        assertNotEquals(key, sequentialImageBatchCacheKey(conversationId, "fp", listOf(a, b), "换了个问法"))
    }

    @Test
    fun `replace collapses a batch into one note on the first image`() {
        val original = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = markSequentialImages(
                    listOf(
                        UIMessagePart.Text("这三张分别是什么？"),
                        image("file:///tmp/1.jpg"),
                        image("file:///tmp/2.jpg"),
                        image("file:///tmp/3.jpg"),
                    ),
                    batchSize = 3,
                ) + image("file:///tmp/keep.jpg"),
            ),
        )
        val batch = planSequentialImageBatches(original, conversationId, "fp", stat = { null }).single()
        assertEquals(3, batch.size)
        val notes = mapOf(batch.cacheKey to "第 1 张：A\n第 2 张：B\n第 3 张：C")

        val replaced = original.replaceSequentialImagesWithNotes(batch.tasks, notes)

        val parts = replaced.single().parts
        assertTrue(parts.none { it.isSequentialVisionImage() })
        // 一批只留一条说明，同批其余图去掉（内容已在说明里）
        val images = parts.filterIsInstance<UIMessagePart.Image>()
        assertEquals(listOf("file:///tmp/keep.jpg"), images.map { it.url })
        val note = parts.filterIsInstance<UIMessagePart.Text>().last()
        assertTrue("index=\"1,2,3\"" in note.text)
        assertTrue("batch_size=\"3\"" in note.text)
        assertTrue("第 2 张：B" in note.text)
        // 原消息不变：DB 里的标记图还在
        assertEquals(3, original.single().parts.count { it.isSequentialVisionImage() })
    }

    @Test
    fun `replace keeps the whole batch as images when its note is missing`() {
        val original = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = markSequentialImages(
                    listOf(image("file:///tmp/1.jpg"), image("file:///tmp/2.jpg")),
                    batchSize = 2,
                ),
            ),
        )
        val tasks = planSequentialImages(original, conversationId, "fp", stat = { null })
        val replaced = original.replaceSequentialImagesWithNotes(tasks, emptyMap())

        // 失败不产假 note：两张图都还在
        assertEquals(2, replaced.single().parts.filterIsInstance<UIMessagePart.Image>().size)
        assertTrue(replaced.single().parts.none { it is UIMessagePart.Text })
    }

    @Test
    fun `single image batch keeps the r16 note wording`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("图里写了什么？"), image("file:///tmp/1.jpg", flagged = true)),
            ),
        )
        val task = planSequentialImages(messages, conversationId, "fp", stat = { null }).single()

        assertEquals(sequentialImageNoteText(1, 1, "描述"), sequentialImageBatchNoteText(task, "描述"))
    }
    @Test
    fun `configured size above eight is honored and old marks can be cleared`() {
        val parts = markSequentialImages((1..11).map { image("file:///tmp/$it.jpg") }, 10)
        val batches = planSequentialImageBatches(listOf(UIMessage(role = MessageRole.USER, parts = parts)),
            conversationId, "fp", stat = { null })
        assertEquals(listOf(10, 1), batches.map { it.size })
        assertTrue(clearSequentialImageFlags(parts).none { it.isSequentialVisionImage() })
    }

    @Test
    fun `numbered batch cache cannot reuse different ordinals or configured size`() {
        val entries = listOf(SequentialImageCacheEntry("a", null), SequentialImageCacheEntry("b", null))
        val key = sequentialImageBatchCacheKey(conversationId, "fp", entries, "q", 2, listOf(1, 2), 4)
        assertNotEquals(key, sequentialImageBatchCacheKey(conversationId, "fp", entries, "q", 3, listOf(1, 2), 4))
        assertNotEquals(key, sequentialImageBatchCacheKey(conversationId, "fp", entries, "q", 2, listOf(3, 4), 4))
        assertNotEquals(key, sequentialImageBatchCacheKey(conversationId, "fp", entries, "q", 2, listOf(1, 2), 6))
        assertNotEquals(sequentialImageBatchCacheKey(conversationId, "fp", entries, "x".repeat(200) + "a"),
            sequentialImageBatchCacheKey(conversationId, "fp", entries, "x".repeat(200) + "b"))
    }

}

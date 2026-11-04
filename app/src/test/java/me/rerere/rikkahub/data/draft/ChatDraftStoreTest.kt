package me.rerere.rikkahub.data.draft

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ChatDraft
import me.rerere.rikkahub.data.model.chatDraftKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 草稿存储的纯逻辑部分：内存优先、写盘合并、发送确认前后文件的去留。
 * 文件实现的 AtomicFile 只负责字节，不在这里测。
 */
class ChatDraftStoreTest {
    private class MemoryDraftStorage : ChatDraftStorage {
        val files = mutableMapOf<String, ChatDraft>()
        val writes = mutableListOf<String>()
        var readGate: CompletableDeferred<Unit>? = null

        override suspend fun read(key: String): ChatDraft? {
            readGate?.await()
            return files[key]
        }

        override suspend fun write(key: String, draft: ChatDraft) {
            files[key] = draft
            writes += key
        }

        override suspend fun delete(key: String) {
            files.remove(key)
        }
    }

    private val key = "conversation:1"
    private fun text(value: String) = ChatDraft(text = value)

    @Test
    fun `save writes after the debounce, not immediately`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 100)

        store.save(key, text("hello"))
        // 内存里立刻是最新的，页面切换读到的就是它
        assertEquals(text("hello"), store.cached(key))
        runCurrent()
        assertNull(storage.files[key])

        advanceTimeBy(101)
        runCurrent()
        assertEquals(text("hello"), storage.files[key])
    }

    @Test
    fun `flush writes without waiting for the debounce`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 10_000)

        store.save(key, text("typing"))
        store.flush(key)
        assertEquals(text("typing"), storage.files[key])
    }

    @Test
    fun `only the last state is written`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 100)

        store.save(key, text("a"))
        store.save(key, text("ab"))
        store.save(key, text("abc"))
        advanceUntilIdle()

        assertEquals(text("abc"), storage.files[key])
        assertEquals(1, storage.writes.size)
    }

    @Test
    fun `empty draft removes the stored copy`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 100)

        store.save(key, text("hello"))
        advanceUntilIdle()
        assertEquals(text("hello"), storage.files[key])

        store.save(key, ChatDraft())
        advanceUntilIdle()
        assertNull(storage.files[key])
    }

    @Test
    fun `confirmation keeps text typed after sending`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 100)
        val sent = text("hello")

        store.save(key, sent)
        advanceUntilIdle()
        // 点了发送但输入框没清，用户在等确认时又打了下一句
        store.save(key, text("next question"))

        assertFalse(store.ack(key, sent))
        advanceUntilIdle()
        assertEquals(text("next question"), storage.files[key])
        assertEquals(text("next question"), store.cached(key))
    }

    @Test
    fun `confirmation still clears when only the cursor moved`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 100)
        val sent = text("hello")

        store.save(key, sent)
        advanceUntilIdle()
        // 点发送后光标位置会变，但内容没变：确认要照常清掉
        store.save(key, sent.copy(selectionStart = 2, selectionEnd = 2))

        assertTrue(store.ack(key, sent))
        advanceUntilIdle()
        assertNull(storage.files[key])
        assertNull(store.cached(key))
    }

    @Test
    fun `confirmation never clears a different draft`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 100)
        val sent = ChatDraft(text = "hello", parts = listOf(UIMessagePart.Image(url = "file:///a.jpg")))

        store.save(key, sent.copy(parts = emptyList()))
        advanceUntilIdle()

        assertFalse(store.ack(key, sent))
        advanceUntilIdle()
        assertEquals(ChatDraft(text = "hello"), storage.files[key])
    }

    @Test
    fun `a late disk read after confirmation cannot resurrect the draft`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 100)
        val sent = text("hello")
        storage.files[key] = sent
        val gate = CompletableDeferred<Unit>()
        storage.readGate = gate

        // 读盘还没回来，这时发送确认把草稿删掉了
        val loading = async { store.load(key) }
        runCurrent()
        assertTrue(store.ack(key, sent))
        gate.complete(Unit)

        assertNull(loading.await())
        assertNull(store.cached(key))
        advanceUntilIdle()
        assertNull(storage.files[key])
    }

    @Test
    fun `a stored draft is not read back after confirmation`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 100)
        val sent = text("hello")
        storage.files[key] = sent

        store.ack(key, sent)
        // 文件还没删掉的窗口里读：墓碑挡住旧稿
        assertNull(store.load(key))
        assertNull(store.cached(key))
        advanceUntilIdle()
        assertNull(storage.files[key])
        // 用户又写了新内容：墓碑随之失效，正常保存
        store.save(key, text("new"))
        advanceUntilIdle()
        assertEquals(text("new"), storage.files[key])
    }

    @Test
    fun `a newer draft is never overwritten by an older queued write`() = runTest {
        val storage = object : ChatDraftStorage {
            val files = mutableMapOf<String, ChatDraft>()
            val writes = mutableListOf<String>()
            val firstWrite = CompletableDeferred<Unit>()

            override suspend fun read(key: String): ChatDraft? = files[key]

            override suspend fun write(key: String, draft: ChatDraft) {
                if (writes.isEmpty()) firstWrite.await()
                writes += draft.text
                files[key] = draft
            }

            override suspend fun delete(key: String) {
                files.remove(key)
            }
        }
        val store = ChatDraftStore(storage, this, debounceMillis = 100)

        store.save(key, text("old"))
        val firstFlush = launch { store.flush(key) }
        runCurrent()
        // 第一条还在写，用户又打了新内容并触发第二次写盘
        store.save(key, text("new"))
        val secondFlush = launch { store.flush(key) }
        runCurrent()
        storage.firstWrite.complete(Unit)
        firstFlush.join()
        secondFlush.join()

        assertEquals(listOf("old", "new"), storage.writes)
        assertEquals(text("new"), storage.files[key])
    }

    @Test
    fun `confirmation clears both keys after the assistant to conversation move`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 100)
        val sent = text("hello")
        val assistantKey = "assistant:a"
        val conversationKey = "conversation:c"

        store.save(assistantKey, sent)
        advanceUntilIdle()
        // 第一条消息发出后 key 迁移：新 key 上也是同一份内容
        store.save(conversationKey, sent)
        advanceUntilIdle()

        assertTrue(store.ack(assistantKey, sent))
        assertTrue(store.ack(conversationKey, sent))
        advanceUntilIdle()
        assertNull(storage.files[assistantKey])
        assertNull(storage.files[conversationKey])
    }

    @Test
    fun `late disk read does not overwrite newer input`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 100)
        storage.files[key] = text("old")
        val gate = CompletableDeferred<Unit>()
        storage.readGate = gate

        val loading = async { store.load(key) }
        runCurrent()
        // 读盘还没回来，用户已经开始打字（或者切换助手后输入到了这个 key）
        store.save(key, text("new"))
        gate.complete(Unit)

        assertEquals("new", loading.await()?.text)
        assertEquals("new", store.cached(key)?.text)
    }

    @Test
    fun `keys are isolated`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 100)

        store.save("assistant:a", ChatDraft(text = "assistant text"))
        store.save("conversation:c", ChatDraft(text = "conversation text"))
        advanceUntilIdle()

        assertEquals("assistant text", storage.files["assistant:a"]?.text)
        assertEquals("conversation text", storage.files["conversation:c"]?.text)
        assertEquals("assistant text", store.cached("assistant:a")?.text)
    }

    @Test
    fun `attachments are kept as references`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 100)
        val draft = ChatDraft(
            text = "with image",
            parts = listOf(UIMessagePart.Image(url = "file:///data/chat/1.jpg")),
        )

        store.save(key, draft)
        advanceUntilIdle()
        assertEquals(draft, storage.files[key])
        assertEquals(draft, store.load(key))
    }

    @Test
    fun `draft file names are stable and distinct per key`() {
        val keyA = "conversation:${Uuid.random()}"
        val keyB = "assistant:${Uuid.random()}"

        val first = chatDraftFileName(keyA)
        assertTrue(first.endsWith(".json"))
        // SHA-256 hex + ".json"
        assertEquals(64 + 5, first.length)
        assertEquals(first, chatDraftFileName(keyA))
        assertNotEquals(first, chatDraftFileName(keyB))
    }

    @Test
    fun `new chat draft belongs to the assistant, started chat to the conversation`() {
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()

        assertEquals("assistant:$assistantId", chatDraftKey(conversationId, assistantId, hasMessages = false))
        assertEquals("conversation:$conversationId", chatDraftKey(conversationId, assistantId, hasMessages = true))
        // 前缀和草稿存储里的判断必须一致，否则第一条消息发出后不会做 key 迁移
        assertTrue(chatDraftKey(conversationId, assistantId, false).startsWith(CHAT_DRAFT_ASSISTANT_PREFIX))
        assertTrue(chatDraftKey(conversationId, assistantId, true).startsWith(CHAT_DRAFT_CONVERSATION_PREFIX))
    }

    @Test
    fun `only the first message moves the draft from assistant key to conversation key`() {
        val conversation = "conversation:${Uuid.random()}"
        val assistant = "assistant:${Uuid.random()}"
        val otherAssistant = "assistant:${Uuid.random()}"

        assertTrue(isDraftKeyMigration(assistant, conversation))
        // 切助手不是迁移：旧输入留在旧助手那边
        assertFalse(isDraftKeyMigration(assistant, otherAssistant))
        assertFalse(isDraftKeyMigration(conversation, conversation))
    }
}

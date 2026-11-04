package me.rerere.rikkahub.data.draft

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.model.ChatDraft
import me.rerere.rikkahub.data.model.chatDraftKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 发送收尾的精准回归：A->B 串会话、ack 先于/后于 draftKey 迁移、续写不清、发送失败保留。
 *
 * 只跑纯 JVM 逻辑（策略 + 草稿存储），不碰 Compose / ChatService / 网络。
 */
class ChatSendDraftPolicyTest {

    private class MemoryDraftStorage : ChatDraftStorage {
        val files = mutableMapOf<String, ChatDraft>()
        override suspend fun read(key: String): ChatDraft? = files[key]
        override suspend fun write(key: String, draft: ChatDraft) {
            files[key] = draft
        }

        override suspend fun delete(key: String) {
            files.remove(key)
        }
    }

    private fun text(value: String) = ChatDraft(text = value)
    private fun draftKey(conversationId: Uuid, assistantId: Uuid, hasMessages: Boolean) =
        chatDraftKey(conversationId, assistantId, hasMessages)

    // ---- 归属判定 ----

    @Test
    fun `ack before draft key migration owns the input`() {
        val conversation = Uuid.random()
        val assistant = Uuid.random()
        val origin = draftKey(conversation, assistant, hasMessages = false)

        // 确认回来时 key 还没迁移：还是自己
        assertTrue(ChatSendDraftPolicy.ownsInput(origin, origin))
        assertEquals(listOf(origin), ChatSendDraftPolicy.keysToAck(origin, origin))
    }

    @Test
    fun `ack after draft key migration owns the input and acks both keys`() {
        val conversation = Uuid.random()
        val assistant = Uuid.random()
        val origin = draftKey(conversation, assistant, hasMessages = false)
        val migrated = draftKey(conversation, assistant, hasMessages = true)

        // 挂起期间 key 从助手 key 迁到本会话的会话 key：仍然算自己的输入框
        assertTrue(ChatSendDraftPolicy.ownsInput(origin, migrated))
        assertEquals(listOf(origin, migrated), ChatSendDraftPolicy.keysToAck(origin, migrated))
    }

    @Test
    fun `another conversation is never owned nor acked`() {
        val conversationA = Uuid.random()
        val conversationB = Uuid.random()
        val assistant = Uuid.random()
        val originA = draftKey(conversationA, assistant, hasMessages = false)
        val keyB = draftKey(conversationB, assistant, hasMessages = true)

        // A 的确认碰上 B 的 key：不清、不 ack（旧实现只比前缀，会把 B 的草稿清掉）
        assertFalse(ChatSendDraftPolicy.ownsInput(originA, keyB))
        assertFalse(
            ChatSendDraftPolicy.shouldClearInput(
                originKey = originA,
                currentKey = keyB,
                persisted = true,
                current = text("hello"),
                sent = text("hello"),
            )
        )
        assertEquals(listOf(originA), ChatSendDraftPolicy.keysToAck(originA, keyB))
    }

    // ---- 清不清输入框 ----

    @Test
    fun `send failure keeps the input`() {
        val conversation = Uuid.random()
        val assistant = Uuid.random()
        val origin = draftKey(conversation, assistant, hasMessages = false)

        assertFalse(
            ChatSendDraftPolicy.shouldClearInput(
                origin, origin, persisted = false, current = text("hi"), sent = text("hi")
            )
        )
    }

    @Test
    fun `continuation typed while waiting is kept`() {
        val conversation = Uuid.random()
        val assistant = Uuid.random()
        val origin = draftKey(conversation, assistant, hasMessages = false)
        val migrated = draftKey(conversation, assistant, hasMessages = true)

        assertFalse(
            ChatSendDraftPolicy.shouldClearInput(
                originKey = origin,
                currentKey = migrated,
                persisted = true,
                current = text("hi 继续写"),
                sent = text("hi"),
            )
        )
    }

    @Test
    fun `same content clears after both migration timings`() {
        val conversation = Uuid.random()
        val assistant = Uuid.random()
        val origin = draftKey(conversation, assistant, hasMessages = false)
        val migrated = draftKey(conversation, assistant, hasMessages = true)

        assertTrue(ChatSendDraftPolicy.shouldClearInput(origin, origin, true, text("hi"), text("hi")))
        assertTrue(ChatSendDraftPolicy.shouldClearInput(origin, migrated, true, text("hi"), text("hi")))
        // 光标移动不算内容变化
        assertTrue(
            ChatSendDraftPolicy.shouldClearInput(
                origin, origin, true,
                ChatDraft(text = "hi", selectionStart = 2, selectionEnd = 2),
                ChatDraft(text = "hi", selectionStart = 0, selectionEnd = 0),
            )
        )
    }

    // ---- 落盘层：A 的确认不动 B ----

    @Test
    fun `ack of A leaves B draft intact`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 0)
        val conversationA = Uuid.random()
        val conversationB = Uuid.random()
        val assistant = Uuid.random()
        val originA = draftKey(conversationA, assistant, hasMessages = false)
        val keyB = draftKey(conversationB, assistant, hasMessages = true)
        val sent = text("A 的任务")

        store.saveNow(originA, sent)
        // B 里正好也是一样的字（旧实现会共享 key，所以这里特意用同样内容）
        store.saveNow(keyB, sent)
        advanceUntilIdle()

        ChatSendDraftPolicy.keysToAck(originA, keyB).forEach { store.ack(it, sent) }
        advanceUntilIdle()

        // A 的那份清掉，B 的一份不动
        assertNull(storage.files[originA])
        assertEquals(sent, storage.files[keyB])
    }

    @Test
    fun `ack clears both keys after migration`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 0)
        val conversation = Uuid.random()
        val assistant = Uuid.random()
        val origin = draftKey(conversation, assistant, hasMessages = false)
        val migrated = draftKey(conversation, assistant, hasMessages = true)
        val sent = text("第一句")

        store.saveNow(origin, sent)
        // 迁移时 syncDraft 会把当前输入按新 key 再存一份；这里模拟「还没来得及清」
        store.saveNow(migrated, sent)
        advanceUntilIdle()

        ChatSendDraftPolicy.keysToAck(origin, migrated).forEach { store.ack(it, sent) }
        advanceUntilIdle()

        assertNull(storage.files[origin])
        assertNull(storage.files[migrated])
        assertNull(store.load(origin))
        assertNull(store.load(migrated))
    }

    @Test
    fun `two new chats of the same assistant do not share a draft`() = runTest {
        val storage = MemoryDraftStorage()
        val store = ChatDraftStore(storage, this, debounceMillis = 0)
        val assistant = Uuid.random()
        val conversationA = Uuid.random()
        val conversationB = Uuid.random()

        store.saveNow(draftKey(conversationA, assistant, hasMessages = false), text("A 打的字"))
        advanceUntilIdle()

        // B 是同一个助手的另一个新会话：读不到 A 的草稿（旧实现会串过来，接着发就发错会话）
        assertNull(store.load(draftKey(conversationB, assistant, hasMessages = false)))
    }
}

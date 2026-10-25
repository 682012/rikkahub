package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.MessageNodeMeta
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCursorWindowFallbackTest {
    private fun nativeFailure(message: String = "Couldn't read row 0 column 0") =
        IllegalStateException(message).apply {
            stackTrace = arrayOf(StackTraceElement(
                "io.requery.android.database.CursorWindow", "nativeGetString", "CursorWindow.java", -2,
            ))
        }

    @Test
    fun recognizesTheActualRequeryNativeFailure() {
        assertTrue(isCursorWindowOverflow(nativeFailure()))
        assertTrue(isCursorWindowOverflow(RuntimeException("wrapped", nativeFailure())))
    }

    @Test
    fun sameTextOutsideNativeCursorCodeIsNotReclassified() {
        assertFalse(isCursorWindowOverflow(IllegalStateException("Couldn't read row 0 column 0")))
        assertFalse(isCursorWindowOverflow(nativeFailure("database is locked")))
        assertFalse(isCursorWindowOverflow(nativeFailure("Couldn't read row 0 column 0; closed")))
    }

    @Test
    fun nativeReadFailureRebuildsTheWholePage() = runBlocking<Unit> {
        val text = "中文😀"
        val source = object : NodePageSource {
            override suspend fun readPage(limit: Int, offset: Int): List<MessageNodeEntity> = throw nativeFailure()
            override suspend fun readMeta() = listOf(MessageNodeMeta("n", "c", 0, 0, 3))
            override suspend fun readMessagesSlice(nodeId: String, start: Int, length: Int): String =
                sliceCodepoints(text, start, length)
        }
        assertEquals(text, loadAllNodesResilient(64, 2, source).single().messages)
    }

    @Test
    fun nativeFailureWithAnUnreadableSliceStillFailsLoudly() = runBlocking<Unit> {
        val source = object : NodePageSource {
            override suspend fun readPage(limit: Int, offset: Int): List<MessageNodeEntity> = throw nativeFailure()
            override suspend fun readMeta() = listOf(MessageNodeMeta("missing", "c", 0, 0, 3))
            override suspend fun readMessagesSlice(nodeId: String, start: Int, length: Int): String? = null
        }
        val error = runCatching { loadAllNodesResilient(64, 2, source) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message.orEmpty().contains("missing"))
    }
}

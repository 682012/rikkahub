package me.rerere.rikkahub.data.organization.autofile

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 本地规则模式（r22）的纯 JVM 测试：确定性、可解释、不越权。
 *
 * 这里不断言「准确率」，只断言规则本身：同样的输入给同样的输出、理由非空、
 * 置信度不超过上限、已有库优先、少于 3 条的簇不建库。
 */
class ConversationAutoFileLocalClassifierTest {

    private val classifier = ConversationAutoFileLocalClassifier()

    private fun item(title: String, excerpt: String = "") =
        AutoFileClassifyItem(id = Uuid.random(), title = title, excerpt = excerpt)

    @Test
    fun `same input yields identical output`() = runTest {
        val items = listOf(
            item("Kotlin 协程调度", "协程 调度 协程 挂起 调度器"),
            item("Kotlin 协程取消", "协程 取消 挂起"),
            item("Kotlin 协程异常", "协程 异常 调度"),
        )
        val first = classifier.classify(Uuid.random(), items, emptyList())
        val second = classifier.classify(Uuid.random(), items, emptyList())
        assertEquals(
            first.assignments.map { it.conversationId to it.folderName },
            second.assignments.map { it.conversationId to it.folderName },
        )
        assertEquals(
            first.assignments.map { it.confidence },
            second.assignments.map { it.confidence },
        )
    }

    @Test
    fun `cluster of three shares a name and every assignment carries a reason`() = runTest {
        val items = listOf(
            item("Kotlin 协程调度", "协程 调度 挂起"),
            item("Kotlin 协程取消", "协程 取消 挂起"),
            item("Kotlin 协程异常", "协程 异常 挂起"),
        )
        val result = classifier.classify(Uuid.random(), items, emptyList())
        assertEquals(3, result.assignments.size)
        assertTrue(result.assignments.all { it.isNew })
        assertEquals(1, result.assignments.map { it.folderName }.distinct().size)
        assertTrue(result.assignments.all { !it.reason.isNullOrBlank() })
        assertTrue(result.assignments.all { it.confidence >= ConversationAutoFilePlanner.MIN_CONFIDENCE })
        assertTrue(result.assignments.all { it.confidence <= ConversationAutoFileLocalClassifier.MAX_CONFIDENCE })
    }

    @Test
    fun `two similar conversations do not create a folder`() = runTest {
        val items = listOf(
            item("旅行 京都 行程", "京都 行程 景点"),
            item("旅行 京都 住宿", "京都 住宿 酒店"),
        )
        val result = classifier.classify(Uuid.random(), items, emptyList())
        assertTrue(result.assignments.isEmpty())
    }

    @Test
    fun `existing folder wins when the title matches it`() = runTest {
        val folder = AutoFileFolderRef(Uuid.random(), "Kotlin 协程")
        val items = listOf(
            item("Kotlin 协程调度", "协程 调度"),
            item("Kotlin 协程取消", "协程 取消"),
            item("Kotlin 协程异常", "协程 异常"),
        )
        val result = classifier.classify(Uuid.random(), items, listOf(folder))
        assertEquals(3, result.assignments.size)
        assertTrue(result.assignments.all { !it.isNew })
        assertTrue(result.assignments.all { it.folderName == folder.name })
        assertTrue(result.assignments.all { it.reason?.contains(folder.name) == true })
    }

    @Test
    fun `tags are used as a signal`() = runTest {
        val items = listOf(
            item("#税务 报销流程", "报销 #税务"),
            item("#税务 发票", "发票 #税务"),
            item("#税务 申报", "申报 #税务"),
        )
        val result = classifier.classify(Uuid.random(), items, emptyList())
        assertEquals(3, result.assignments.size)
        assertTrue(result.assignments.first().folderName.contains("税务"))
    }

    @Test
    fun `blank titles produce no assignment`() = runTest {
        val result = classifier.classify(Uuid.random(), listOf(item("   ", "")), emptyList())
        assertTrue(result.assignments.isEmpty())
        assertFalse(result.assignments.any { it.confidence >= ConversationAutoFilePlanner.MIN_CONFIDENCE })
    }

    @Test
    fun `local confidence never claims certainty`() {
        assertTrue(ConversationAutoFileLocalClassifier.MAX_CONFIDENCE < 0.99)
        assertTrue(ConversationAutoFileLocalClassifier.MIN_LOCAL_CONFIDENCE >= ConversationAutoFilePlanner.MIN_CONFIDENCE)
    }

    @Test
    fun `tokenizer keeps tags and skips stop words`() {
        val tokens = ConversationAutoFileLocalClassifier.tokensOf("#税务 报销 我们 流程")
        assertTrue(tokens.contains("税务"))
        assertTrue(tokens.contains("#税务"))
        assertFalse(tokens.contains("我们"))
    }

    @Test
    fun `classifier is the local one and needs no network`() {
        assertNotNull(ConversationAutoFileLocalClassifier() as ConversationAutoFileClassifier)
    }
}

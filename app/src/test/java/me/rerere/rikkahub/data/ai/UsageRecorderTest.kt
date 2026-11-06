package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.dao.DayCount
import me.rerere.rikkahub.data.db.dao.ModelUsage
import me.rerere.rikkahub.data.db.dao.SourceUsage
import me.rerere.rikkahub.data.db.dao.UsageBucket
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.dao.UsageTotals
import me.rerere.rikkahub.data.db.dao.UsageByModel
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class UsageRecorderTest {
    private val dao = FakeUsageRecordDAO()
    private val setting = ProviderSetting.OpenAI()
    private val model = Model(modelId = "gpt-test", displayName = "Test")
    private val params = TextGenerationParams(model = model)
    private val tag = UsageTag(UsageSource.CHAT, assistantId = Uuid.random(), conversationId = Uuid.random())

    private fun TestScope.recorder() = UsageRecorder(dao, this)

    @Test
    fun `generateText records the usage with its tag`() = runTest {
        val provider = FakeProvider(result = TokenUsage(promptTokens = 10, completionTokens = 2, cachedTokens = 4))

        recorder().wrap(provider, tag).generateText(setting, emptyList(), params)
        testScheduler.advanceUntilIdle()

        val record = dao.records.single()
        assertEquals(UsageSource.CHAT.name, record.source)
        assertEquals(setting.id.toString(), record.providerId)
        assertEquals(model.id.toString(), record.modelId)
        assertEquals("gpt-test", record.modelName)
        assertEquals(tag.assistantId.toString(), record.assistantId)
        assertEquals(tag.conversationId.toString(), record.conversationId)
        assertEquals(listOf(10, 2, 4), listOf(record.promptTokens, record.completionTokens, record.cachedTokens))
    }

    @Test
    fun `stream records the merged usage once`() = runTest {
        val provider = FakeProvider(
            chunks = flow {
                emit(StreamChunk.Usage(TokenUsage(promptTokens = 10)))
                emit(StreamChunk.TextDelta("text", "hi"))
                emit(StreamChunk.Usage(TokenUsage(completionTokens = 5, cachedTokens = 3)))
            }
        )

        val chunks = recorder().wrap(provider, tag).streamText(setting, emptyList(), params).toList()
        testScheduler.advanceUntilIdle()

        assertEquals(3, chunks.size)
        val record = dao.records.single()
        assertEquals(listOf(10, 5, 3), listOf(record.promptTokens, record.completionTokens, record.cachedTokens))
    }

    @Test
    fun `stream cancelled midway still records usage received so far`() = runTest {
        val provider = FakeProvider(
            chunks = flow {
                emit(StreamChunk.Usage(TokenUsage(promptTokens = 10)))
                emit(StreamChunk.TextDelta("text", "hi"))
                awaitCancellation()
            }
        )

        recorder().wrap(provider, tag).streamText(setting, emptyList(), params).take(2).toList()
        testScheduler.advanceUntilIdle()

        assertEquals(10, dao.records.single().promptTokens)
    }

    @Test
    fun `each step of a multi step turn is recorded once and never overwrites`() = runTest {
        val recorder = recorder()
        listOf("step-1", "step-2", "step-3").forEachIndexed { index, id ->
            recorder.wrap(FakeProvider(result = TokenUsage(promptTokens = 100 + index, completionTokens = 1), resultId = id), tag)
                .generateText(setting, emptyList(), params)
        }
        testScheduler.advanceUntilIdle()

        assertEquals(3, dao.records.size)
        assertEquals(listOf(100, 101, 102), dao.records.map { it.promptTokens }.sorted())
        assertEquals(3, dao.records.map { it.id }.distinct().size)
    }

    @Test
    fun `two calls with the same upstream response id are charged twice`() = runTest {
        // 上游完全可能对多次调用返回同一个 id（兼容层、缓存重放），但这些调用确实各自计费：
        // 按 responseId 去重会少记真钱，所以必须每次本地调用一行。
        val recorder = recorder()
        repeat(2) {
            recorder.wrap(FakeProvider(result = TokenUsage(promptTokens = 100, completionTokens = 5), resultId = "same-id"), tag)
                .generateText(setting, emptyList(), params)
        }
        testScheduler.advanceUntilIdle()

        assertEquals(2, dao.records.size)
        assertEquals(2, dao.records.map { it.id }.distinct().size)
        assertEquals(200, dao.records.sumOf { it.promptTokens })
    }

    @Test
    fun `cache report coverage is persisted and never invented`() = runTest {
        val recorder = recorder()
        // 上游明确报了 0 命中：cachedReported = true
        recorder.wrap(FakeProvider(result = TokenUsage(promptTokens = 100, completionTokens = 1, cachedTokens = 0, cachedReported = true)), tag)
            .generateText(setting, emptyList(), params)
        // 上游压根没报缓存字段：cachedReported = null，不能落成"0 命中"
        recorder.wrap(FakeProvider(result = TokenUsage(promptTokens = 200, completionTokens = 1)), tag)
            .generateText(setting, emptyList(), params)
        // Claude 写入缓存：命中 0，但 creation 200 属于输入
        recorder.wrap(
            FakeProvider(result = TokenUsage(promptTokens = 300, completionTokens = 1, cachedTokens = 0, cachedReported = true, cacheCreationTokens = 200)),
            tag,
        ).generateText(setting, emptyList(), params)
        testScheduler.advanceUntilIdle()

        val byPrompt = dao.records.associateBy { it.promptTokens }
        assertEquals(true, byPrompt.getValue(100).cachedReported)
        assertEquals(null, byPrompt.getValue(200).cachedReported)
        assertEquals(200, byPrompt.getValue(300).cacheCreationTokens)
        assertEquals(0, byPrompt.getValue(300).cachedTokens)
    }

    @Test
    fun `streaming steps are separate records without id based dedupe`() = runTest {
        val recorder = recorder()
        repeat(2) {
            recorder.wrap(
                FakeProvider(chunks = flow { emit(StreamChunk.Usage(TokenUsage(promptTokens = 50, completionTokens = 5), completeSnapshot = true)) }),
                tag,
            ).streamText(setting, emptyList(), params).toList()
        }
        testScheduler.advanceUntilIdle()

        assertEquals(2, dao.records.size)
    }

    @Test
    fun `all zero usage is not recorded as a real request`() = runTest {
        recorder().wrap(FakeProvider(result = TokenUsage(promptTokens = 0, completionTokens = 0), resultId = "zero"), tag)
            .generateText(setting, emptyList(), params)
        testScheduler.advanceUntilIdle()

        assertTrue(dao.records.isEmpty())
    }

    @Test
    fun `requests without usage are not recorded`() = runTest {
        val recorder = recorder()
        recorder.wrap(FakeProvider(result = null), tag).generateText(setting, emptyList(), params)
        recorder.wrap(FakeProvider(chunks = flow { emit(StreamChunk.TextDelta("text", "hi")) }), tag)
            .streamText(setting, emptyList(), params).toList()
        testScheduler.advanceUntilIdle()

        assertTrue(dao.records.isEmpty())
    }
}

private class FakeUsageRecordDAO : UsageRecordDAO {
    val records = mutableListOf<UsageRecordEntity>()

    override fun observeConversationGroups(conversationId: String): Flow<List<me.rerere.rikkahub.data.db.dao.ConversationUsageGroup>> = kotlinx.coroutines.flow.flowOf(emptyList())
    override fun observeRecordCount(): Flow<Long> = kotlinx.coroutines.flow.flowOf(records.size.toLong())

    override suspend fun insert(record: UsageRecordEntity) {
        records += record
    }

    override suspend fun getTotals(since: Long) = UsageTotals()

    override suspend fun getTotalsByBucket(format: String, since: Long) = emptyList<UsageBucket>()

    override suspend fun getTotalsByModel(since: Long) = emptyList<ModelUsage>()

    override suspend fun getRequestCountPerDay(source: String, since: Long) = emptyList<DayCount>()

    override suspend fun getTotalsBySource(since: Long) = emptyList<SourceUsage>()

    override suspend fun getCacheCoverage(since: Long, source: String?) = emptyList<me.rerere.rikkahub.data.db.dao.CacheCoverage>()

    override suspend fun listByConversation(conversationId: String) = records.filter { it.conversationId == conversationId }

    override suspend fun totals(conversationId: String, includeSubagents: Boolean) = UsageTotals(
        promptTokens = records.sumOf { it.promptTokens.toLong() },
        completionTokens = records.sumOf { it.completionTokens.toLong() },
        cachedTokens = records.sumOf { it.cachedTokens.toLong() },
        totalTokens = records.sumOf { (it.promptTokens + it.completionTokens).toLong() },
        requests = records.size.toLong(),
    )

    override suspend fun byModel(conversationId: String, includeSubagents: Boolean) = emptyList<UsageByModel>()

    override suspend fun deleteByConversation(conversationId: String) {
        records.removeAll { it.conversationId == conversationId }
    }

    override suspend fun deleteOlderThan(before: Long) {
        records.removeAll { it.createdAt < before }
    }
}

private class FakeProvider(
    private val result: TokenUsage? = null,
    private val chunks: Flow<StreamChunk> = flow {},
    private val resultId: String = "resp-1",
) : Provider<ProviderSetting.OpenAI> {
    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ) = TextGenerationResult(
        id = resultId,
        model = params.model.modelId,
        message = UIMessage.assistant(""),
        usage = result
    )

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = chunks
}

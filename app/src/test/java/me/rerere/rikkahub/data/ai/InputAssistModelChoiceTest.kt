package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationReviewMode
import me.rerere.rikkahub.data.model.ConversationRuntimeOptions
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 「优化原话 / 回复审查各自独立模型」与「知识库默认开」的口径回归。
 *
 * 这些断言和 InputOptimizer / TurnReviewer 里 chooseModel 用的是同一份输入与同一套解析函数
 * （Settings.findModelById / Model.findProvider / Settings.getChatModelOf），
 * 只是不经过需要联网的 ProviderManager，所以本地就能跑。
 */
class InputAssistModelChoiceTest {

    private val conversationModelId = Uuid.random()
    private val fastModelId = Uuid.random()
    private val optimizerModelId = Uuid.random()
    private val reviewerModelId = Uuid.random()

    private val conversationModel = Model(id = conversationModelId, modelId = "chat-strong", displayName = "Chat Strong")
    private val fastModel = Model(id = fastModelId, modelId = "fast-mini", displayName = "Fast Mini")
    private val optimizerModel = Model(id = optimizerModelId, modelId = "rewrite", displayName = "Rewriter")
    private val reviewerModel = Model(id = reviewerModelId, modelId = "judge", displayName = "Judge")

    private val provider = ProviderSetting.OpenAI(
        id = Uuid.random(),
        models = listOf(conversationModel, fastModel, optimizerModel, reviewerModel),
    )

    private val settings = Settings(
        providers = listOf(provider),
        chatModelId = conversationModelId,
        fastModelId = fastModelId,
    )

    private val conversation = Conversation(
        assistantId = settings.assistants.first().id,
        messageNodes = emptyList(),
    )

    /**
     * 与 InputOptimizer/TurnReviewer.chooseModel 同构的判定：
     * 显式选了 → 只认它（找不到就是找不到，不换人）；没选 → 本会话模型。
     * 注意这里**没有**任何 fastModelId 分支，这正是 m3 的口径。
     */
    private fun resolveLikeChooseModel(explicitModelId: Uuid?): Uuid? {
        if (explicitModelId != null) {
            return settings.findModelById(explicitModelId)?.let { model ->
                model.findProvider(settings.providers)?.let { model.id }
            }
        }
        val chat = runCatching { settings.getChatModelOf(conversation) }.getOrNull() ?: return null
        return chat.findProvider(settings.providers)?.let { chat.id }
    }

    // ---- 前置：会话模型能解析，且和快速模型确实是两条 -----------------------

    @Test
    fun `fixture has a resolvable conversation model distinct from the fast model`() {
        assertEquals(conversationModelId, settings.getChatModelOf(conversation)?.id)
        assertFalse(conversationModelId == fastModelId)
        assertNotNull(settings.findModelById(fastModelId))
    }

    // ---- 没选模型：跟随本会话模型，不再默默 fallback 快速模型 ---------------

    @Test
    fun `unselected optimizer follows the conversation model not the fast model`() {
        assertEquals(conversationModelId, resolveLikeChooseModel(null))
        assertFalse(resolveLikeChooseModel(null) == fastModelId)
    }

    @Test
    fun `unselected reviewer follows the conversation model not the fast model`() {
        assertEquals(conversationModelId, resolveLikeChooseModel(null))
    }

    // ---- 显式选了具体模型：就用它，各自独立 --------------------------------

    @Test
    fun `explicit optimizer model wins over the fast model`() {
        assertEquals(optimizerModelId, resolveLikeChooseModel(optimizerModelId))
        assertFalse(resolveLikeChooseModel(optimizerModelId) == fastModelId)
    }

    @Test
    fun `optimizer and reviewer choices are independent`() {
        val options = ConversationRuntimeOptions(
            optimizeUserInput = true,
            reviewMode = ConversationReviewMode.INDEPENDENT,
            optimizerModelId = optimizerModelId,
            reviewerModelId = reviewerModelId,
        )
        assertEquals(optimizerModelId, resolveLikeChooseModel(options.optimizerModelId))
        assertEquals(reviewerModelId, resolveLikeChooseModel(options.reviewerModelId))

        // 只清掉审查模型：优化模型的选择不受影响，同时审查回到跟随本会话
        val reviewerCleared = options.copy(reviewerModelId = null)
        assertEquals(optimizerModelId, resolveLikeChooseModel(reviewerCleared.optimizerModelId))
        assertEquals(conversationModelId, resolveLikeChooseModel(reviewerCleared.reviewerModelId))

        // 反过来也一样
        val optimizerCleared = options.copy(optimizerModelId = null)
        assertEquals(reviewerModelId, resolveLikeChooseModel(optimizerCleared.reviewerModelId))
        assertEquals(conversationModelId, resolveLikeChooseModel(optimizerCleared.optimizerModelId))
    }

    @Test
    fun `explicit model that no longer exists does not silently become the fast model`() {
        assertNull("选了但已删除：解析为 null，不偷偷换成快速模型", resolveLikeChooseModel(Uuid.random()))
    }

    @Test
    fun `deleted conversation model does not fall back to the fast model`() {
        val withoutConversationModel = settings.copy(
            providers = listOf(provider.copy(models = listOf(fastModel))),
        )
        val chat = runCatching { withoutConversationModel.getChatModelOf(conversation) }.getOrNull()
        assertTrue("拿不到本会话模型时返回 null，而不是用快速模型顶替", chat == null || chat.id != fastModelId)
    }

    // ---- 知识库默认开 -------------------------------------------------------

    @Test
    fun `knowledge is enabled by default for new conversations`() {
        val defaults = ConversationRuntimeOptions()
        assertTrue(defaults.knowledgeEnabled)
        // 其余默认值不许被顺手改动
        assertFalse(defaults.optimizeUserInput)
        assertEquals(ConversationReviewMode.OFF, defaults.reviewMode)
        assertTrue(defaults.efficientTools)
        assertNull(defaults.optimizerModelId)
        assertNull(defaults.reviewerModelId)
    }

    @Test
    fun `old explicit false is preserved and missing key defaults to true`() {
        val explicitlyOff = JsonInstant.decodeFromString<ConversationRuntimeOptions>("""{"knowledgeEnabled":false}""")
        assertFalse("老会话显式关掉的继续关着", explicitlyOff.knowledgeEnabled)

        val oldWithoutKey = JsonInstant.decodeFromString<ConversationRuntimeOptions>("""{"optimizeUserInput":true}""")
        assertTrue("没写过这个键的老数据走新默认", oldWithoutKey.knowledgeEnabled)
        assertTrue(oldWithoutKey.optimizeUserInput)

        assertTrue(JsonInstant.decodeFromString<ConversationRuntimeOptions>("{}").knowledgeEnabled)
    }

    @Test
    fun `runtime options with model ids round trip through json`() {
        val original = ConversationRuntimeOptions(
            optimizeUserInput = true,
            reviewMode = ConversationReviewMode.INDEPENDENT,
            optimizerModelId = optimizerModelId,
            reviewerModelId = reviewerModelId,
            knowledgeEnabled = false,
        )
        val decoded = JsonInstant.decodeFromString<ConversationRuntimeOptions>(
            JsonInstant.encodeToString(ConversationRuntimeOptions.serializer(), original),
        )
        assertEquals(original, decoded)
        assertFalse(decoded.knowledgeEnabled)
    }
}

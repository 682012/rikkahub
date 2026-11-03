package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * r8 折叠摘要的纯逻辑：一行里必须有提供商、模型和速度，缺提供商时不能留下孤立分隔符；
 * 摘要行和详情面板用不同的速度 tag，不然 Compose 测试里 onNodeWithTag 会撞。
 */
class ChatHeaderCollapseTest {
    private val unselected = "未选择模型"

    @Test
    fun summaryCarriesProviderModelAndSpeedOnOneLine() {
        val info = ChatHeaderProviderInfo(providerName = "OpenAI", modelName = "gpt-4o")

        assertEquals("OpenAI · gpt-4o · 52.0 tok/s", chatHeaderSummaryText(info, conversationSpeedText(52.0)))
    }

    @Test
    fun missingProviderDoesNotEmitADanglingSeparator() {
        val info = chatHeaderProviderInfo(model = null, providers = emptyList(), unselectedLabel = unselected)

        assertEquals("$unselected · —", chatHeaderSummaryText(info, conversationSpeedText(null)))
    }

    @Test
    fun providerNameIsDroppedWhenNoModelIsSelected() {
        val info = chatHeaderProviderInfo(model = null, providerName = "  Anthropic  ", unselectedLabel = unselected)

        assertEquals(unselected, info.modelName)
        assertNull(info.providerName)
        assertEquals("$unselected · 0.0 tok/s", chatHeaderSummaryText(info, conversationSpeedText(0.0)))
    }

    @Test
    fun speedTextHasOneFormattingRuleForBothStates() {
        assertEquals("—", conversationSpeedText(null))
        assertEquals("0.0 tok/s", conversationSpeedText(0.0))
        assertEquals("1234.6 tok/s", conversationSpeedText(1234.56))
        // 摘要行与详情面板引用同一个占位符，不会一处写 “—” 一处写 “n/a”
        assertEquals(ChatSpeedPlaceholder, conversationSpeedText(null))
    }

    @Test
    fun collapsedAndExpandedSpeedUseDistinctTags() {
        assertNotEquals(ChatHeaderSummaryTag, ChatHeaderSpeedTag)
        assertNotEquals(ChatHeaderSpeedTag, ChatSpeedValueTag)
        assertNotEquals(ChatHeaderDetailTag, ChatHeaderSummaryTag)
        assertNotEquals(ChatHeaderToggleTag, ChatHeaderModelTag)
    }
}

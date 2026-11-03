package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatHeaderProviderInfoTest {
    private val unselected = "未选择模型"

    @Test fun displayNameWinsOverModelId() {
        val model = Model(modelId = "gpt-4o-mini", displayName = "GPT-4o mini")
        assertEquals("GPT-4o mini", chatHeaderModelName(model, unselected))
    }

    @Test fun blankDisplayNameFallsBackToModelId() {
        val model = Model(modelId = "gpt-4o-mini", displayName = "   ")
        assertEquals("gpt-4o-mini", chatHeaderModelName(model, unselected))
        val noId = Model(modelId = " ", displayName = "")
        assertEquals(unselected, chatHeaderModelName(noId, unselected))
    }

    @Test fun missingModelShowsUnselectedAndDropsProvider() {
        val info = chatHeaderProviderInfo(model = null, providerName = "OpenAI", unselectedLabel = unselected)
        assertNull(info.providerName)
        assertEquals(unselected, info.modelName)
        assertEquals(unselected, info.text)
    }

    @Test fun providerAndModelJoinWithOneSeparator() {
        val model = Model(modelId = "gpt-4o", displayName = "GPT-4o")
        val info = chatHeaderProviderInfo(model, "  OpenAI  ", unselected)
        assertEquals("OpenAI", info.providerName)
        assertEquals("GPT-4o", info.modelName)
        assertEquals("OpenAI · GPT-4o", info.text)
    }

    @Test fun deletedProviderDoesNotLeaveDanglingSeparator() {
        val model = Model(modelId = "gpt-4o", displayName = "GPT-4o")
        val info = chatHeaderProviderInfo(model, providerName = null, unselectedLabel = unselected)
        assertNull(info.providerName)
        assertEquals("GPT-4o", info.text)
        val blankName = chatHeaderProviderInfo(model, providerName = "  ", unselectedLabel = unselected)
        assertNull(blankName.providerName)
        assertEquals("GPT-4o", blankName.text)
    }

    @Test fun longNamesAreKeptIntactForTheHeaderToEllipsize() {
        val longProvider = "企业内部私有化部署网关节点-华东二区"
        val longModel = "anthropic/claude-sonnet-4.5-20260101-extended-thinking"
        val info = chatHeaderProviderInfo(Model(modelId = longModel, displayName = ""), longProvider, unselected)
        // 纯函数不做截断，省略交给 320dp 窄屏的 TextOverflow.Ellipsis，信息不丢
        assertEquals("$longProvider · $longModel", info.text)
    }

    @Test fun nonLatinProviderNameKeepsItsOwnName() {
        val model = Model(modelId = "glm-4.6", displayName = "")
        val info = chatHeaderProviderInfo(model, providerName = "智谱", unselectedLabel = "No model selected")
        assertEquals("智谱 · glm-4.6", info.text)
    }
}

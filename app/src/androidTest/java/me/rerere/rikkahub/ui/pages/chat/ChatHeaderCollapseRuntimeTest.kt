package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.agent.GenerationTiming
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * r8 顶部信息栏折叠：三种风格同一套行为。
 *
 * 只断言可靠事实——摘要行常显（模型 + TPS 都在）、主 bar 的 drawer/usage/preview/new 折叠时仍可点、
 * 收起后整块标题栏高度确实少掉至少 48dp。长模型名允许省略，所以用无障碍描述验证完整文本，
 * 不靠“某段文字有没有被截断”这种像素级断言。
 */
@RunWith(AndroidJUnit4::class)
class ChatHeaderCollapseRuntimeTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val chatModel = Model(modelId = "anthropic/claude-sonnet-4.5-20260101", displayName = "Claude Sonnet 4.5")
    private val provider: ProviderSetting = ProviderSetting.OpenAI(
        id = kotlin.uuid.Uuid.random(),
        name = "Anthropic",
        models = listOf(chatModel),
    )

    private fun conversationWithSpeed(): Conversation {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            finishedAt = kotlinx.datetime.LocalDateTime.parse("2026-11-03T20:00:00"),
            generationTiming = GenerationTiming(
                generationMs = 2000, outputTokens = 100, measuredSteps = 1, totalSteps = 1,
            ),
        )
        return Conversation(
            assistantId = kotlin.uuid.Uuid.random(),
            title = "折叠头部验收",
            messageNodes = listOf(MessageNode(messages = listOf(message), selectIndex = 0)),
        )
    }

    private fun headerHeight(): Dp =
        rule.onNodeWithTag(ChatHeaderRootTag).getUnclippedBoundsInRoot().height

    @Test
    fun collapsedKeepsSummaryAndActionsAndSavesAtLeast48dp() {
        val clicks = mutableListOf<String>()
        val conversation = conversationWithSpeed()
        var style by mutableStateOf(ChatUiStyle.CLASSIC)
        var expanded by mutableStateOf(false)
        rule.setContent {
            val settings = Settings.dummy().copy(
                providers = listOf(provider),
                displaySetting = DisplaySetting(appearance = AppearanceSettings(chatUiStyle = style)),
            )
            CompositionLocalProvider(LocalSettings provides settings) {
                MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                    AppAppearanceHost(settings) {
                        ChatStyleHeader(
                            spec = ChatStyleSpec.of(style),
                            settings = settings,
                            conversation = conversation,
                            chatModel = chatModel,
                            title = conversation.title,
                            meta = "念安",
                            bigScreen = false,
                            previewMode = false,
                            onOpenDrawer = { clicks += "drawer" },
                            onEditTitle = { clicks += "title" },
                            onOpenUsage = { clicks += "usage" },
                            onTogglePreview = { clicks += "preview" },
                            onNewChat = { clicks += "new" },
                            headerExpanded = expanded,
                            onHeaderExpandedChange = { expanded = it },
                        )
                    }
                }
            }
        }

        for (next in ChatUiStyle.entries) {
            rule.runOnIdle { style = next; expanded = false; clicks.clear() }
            rule.waitForIdle()

            // 折叠态：一行摘要里模型 + 速度都还在
            rule.onNodeWithTag(ChatHeaderSummaryTag).assertIsDisplayed()
            rule.onAllNodesWithTag(ChatHeaderModelTag, useUnmergedTree = true).onFirst().assertIsDisplayed()
            rule.onNodeWithTag(ChatHeaderSpeedTag, useUnmergedTree = true).assertIsDisplayed()
            rule.onNodeWithTag(ChatHeaderSpeedTag, useUnmergedTree = true)
                .assertTextContains("50.0 tok/s")
            // 折叠时详情区不进入组合，不是“占位隐藏”
            rule.onAllNodesWithTag(ChatHeaderDetailTag).assertCountEquals(0)
            val collapsed = headerHeight()

            // 主 bar 的关键入口一个都不能因为折叠消失
            for ((resource, action) in listOf(
                R.string.chat_style_open_drawer to "drawer",
                R.string.chat_style_usage to "usage",
                R.string.chat_style_toggle_preview to "preview",
                R.string.chat_style_new_chat to "new",
            )) {
                val text = context.getString(resource)
                val node = if (action == "usage" && next != ChatUiStyle.FOCUS) rule.onNodeWithText(text)
                else rule.onNodeWithContentDescription(text)
                node.assertIsDisplayed().performClick()
                rule.runOnIdle { assertTrue("$next lost $action while collapsed", clicks.contains(action)) }
            }

            // 展开按钮有明确语义，点击后详情出现
            val expandLabel = context.getString(R.string.chat_header_expand_details)
            rule.onNodeWithTag(ChatHeaderToggleTag).assertContentDescriptionEquals(expandLabel)
            rule.onNodeWithTag(ChatHeaderToggleTag).performClick()
            rule.waitForIdle()
            rule.runOnIdle { assertTrue("$next did not expand", expanded) }
            rule.onNodeWithTag(ChatHeaderDetailTag).assertIsDisplayed()
            val expandedHeight = headerHeight()
            assertTrue(
                "$next collapsed=$collapsed expanded=$expandedHeight 没省出 48dp",
                collapsed <= expandedHeight - 48.dp,
            )

            // 收起后再次回到紧凑态
            val collapseLabel = context.getString(R.string.chat_header_collapse_details)
            rule.onNodeWithTag(ChatHeaderToggleTag).assertContentDescriptionEquals(collapseLabel)
            rule.onNodeWithTag(ChatHeaderToggleTag).performClick()
            rule.waitForIdle()
            rule.runOnIdle { assertFalse("$next stayed expanded", expanded) }
            rule.onAllNodesWithTag(ChatHeaderDetailTag).assertCountEquals(0)
            assertTrue("$next 收起后高度没回去", headerHeight() <= collapsed + 1.dp)
        }
    }

    /** 320dp + 1.3 倍字体：摘要行仍显示提供商、模型和 TPS；完整名字藏在无障碍描述里。 */
    @Test
    fun narrow320LargeFontKeepsProviderModelAndSpeedReadable() {
        val conversation = conversationWithSpeed()
        val clicks = mutableListOf<String>()
        rule.setContent {
            val density = LocalDensity.current
            val settings = Settings.dummy().copy(
                providers = listOf(provider),
                displaySetting = DisplaySetting(appearance = AppearanceSettings(chatUiStyle = ChatUiStyle.CLASSIC)),
            )
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f), LocalSettings provides settings) {
                MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                    AppAppearanceHost(settings) {
                        Box(Modifier.width(320.dp).fillMaxHeight()) {
                            ChatStyleHeader(
                                spec = ChatStyleSpec.of(ChatUiStyle.CLASSIC),
                                settings = settings,
                                conversation = conversation,
                                chatModel = chatModel,
                                title = conversation.title,
                                meta = "念安",
                                bigScreen = false,
                                previewMode = false,
                                onOpenDrawer = { clicks += "drawer" },
                                onEditTitle = { clicks += "title" },
                                onOpenUsage = { clicks += "usage" },
                                onTogglePreview = { clicks += "preview" },
                                onNewChat = { clicks += "new" },
                            )
                        }
                    }
                }
            }
        }

        rule.onNodeWithTag(ChatHeaderSummaryTag).assertIsDisplayed()
        val summary = rule.onAllNodesWithTag(ChatHeaderModelTag, useUnmergedTree = true).onFirst()
        summary.assertIsDisplayed()
        summary.assert(
            SemanticsMatcher("无障碍描述里保留完整提供商与模型") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.any { it.contains("Anthropic") && it.contains("Claude Sonnet 4.5") } == true
            }
        )
        rule.onNodeWithTag(ChatHeaderSpeedTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("50.0 tok/s")
        // 320dp 下 drawer 与 new 依然可点
        for (resource in listOf(R.string.chat_style_open_drawer, R.string.chat_style_new_chat)) {
            rule.onNodeWithContentDescription(context.getString(resource)).assertIsDisplayed().performClick()
        }
        rule.runOnIdle { assertTrue(clicks.contains("drawer") && clicks.contains("new")) }
        AppearanceTestSupport.capture(rule, "header-collapsed-320dp-font13")
    }
}

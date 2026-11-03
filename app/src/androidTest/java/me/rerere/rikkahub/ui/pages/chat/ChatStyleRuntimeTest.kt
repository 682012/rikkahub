package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.theme.*
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Uses the production header, all callbacks, and the same usage data through three layouts. */
@RunWith(AndroidJUnit4::class)
class ChatStyleRuntimeTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun everyHeaderKeepsActionsReadableWithBusyWallpaperAndLargeFont() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wallpaper = me.rerere.rikkahub.ui.theme.AppearanceTestSupport.noise("chat-header")
        var style by mutableStateOf(ChatUiStyle.STUDIO)
        var dark by mutableStateOf(false)
        val clicks = mutableListOf<String>()
        val chatModel = Model(modelId = "example-model", displayName = "Example model")
        val conversation = Conversation(assistantId = kotlin.uuid.Uuid.random(), title = "合并版 · 沉浸式工作台", messageNodes = emptyList())
        rule.setContent {
            val density = LocalDensity.current
            val appearance = AppearanceSettings(chatUiStyle = style, wallpaper = wallpaper)
            val settings = Settings.dummy().copy(displaySetting = DisplaySetting(appearance = appearance))
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f), LocalSettings provides settings) {
                MaterialExpressiveTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    AppAppearanceHost(settings) {
                        ChatStyleHeader(ChatStyleSpec.of(style), settings, conversation, chatModel, conversation.title,
                            "念安", false, false,
                            { clicks += "drawer" }, { clicks += "title" }, { clicks += "usage" },
                            { clicks += "preview" }, { clicks += "new" })
                    }
                }
            }
        }
        for (night in listOf(false, true)) for (next in ChatUiStyle.entries) {
            rule.runOnIdle { dark = night; style = next; clicks.clear() }
            rule.waitForIdle()
            for ((resource, action) in listOf(
                R.string.chat_style_open_drawer to "drawer", R.string.chat_style_usage to "usage",
                R.string.chat_style_toggle_preview to "preview", R.string.chat_style_new_chat to "new")) {
                val text = context.getString(resource)
                val node = if (action == "usage" && next != ChatUiStyle.FOCUS) rule.onNodeWithText(text)
                    else rule.onNodeWithContentDescription(text)
                node.assertIsDisplayed().performClick()
                rule.runOnIdle { assertTrue("$next/$night lost $action", clicks.contains(action)) }
            }
            rule.onNodeWithText(conversation.title).performClick()
            rule.runOnIdle { assertTrue(clicks.contains("title")) }
            rule.onAllNodesWithTag(ChatHeaderModelTag, useUnmergedTree = true)
                .onFirst()
                .assertIsDisplayed()
            rule.onNodeWithText("Example model", substring = true).assertIsDisplayed()
            me.rerere.rikkahub.ui.theme.AppearanceTestSupport.capture(rule,"header-${next.name.lowercase()}-${if(night) "dark" else "light"}-large")
        }
    }

    /** 320dp + 1.3 字体：长模型名可以省略，但必须常显，且不能把右侧按钮挤出屏幕。 */
    @Test fun narrowHeaderAlwaysShowsCurrentModelAndKeepsEveryAction() {
        val clicks = mutableListOf<String>()
        var style by mutableStateOf(ChatUiStyle.CLASSIC)
        val chatModel = Model(
            modelId = "anthropic/claude-sonnet-4.5-20260101-extended-thinking",
            displayName = "Claude Sonnet 4.5 Extended Thinking Preview",
        )
        val conversation = Conversation(assistantId = kotlin.uuid.Uuid.random(), title = "很长的会话标题用来挤压标题栏空间", messageNodes = emptyList())
        rule.setContent {
            val density = LocalDensity.current
            val settings = Settings.dummy().copy(
                displaySetting = DisplaySetting(appearance = AppearanceSettings(chatUiStyle = style)),
            )
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f), LocalSettings provides settings) {
                MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                    AppAppearanceHost(settings) {
                        Box(Modifier.width(320.dp).fillMaxHeight()) {
                            ChatStyleHeader(ChatStyleSpec.of(style), settings, conversation, chatModel, conversation.title,
                                "念安", false, false,
                                { clicks += "drawer" }, { clicks += "title" }, { clicks += "usage" },
                                { clicks += "preview" }, { clicks += "new" })
                        }
                    }
                }
            }
        }
        for (next in ChatUiStyle.entries) {
            rule.runOnIdle { style = next; clicks.clear() }
            rule.waitForIdle()
            val modelLine = rule.onAllNodesWithTag(ChatHeaderModelTag, useUnmergedTree = true).onFirst()
            modelLine.assertIsDisplayed()
            // r8 默认折叠：长模型名允许省略，完整名字保留在合并的无障碍描述里，别用像素级文字断言
            modelLine.assert(
                SemanticsMatcher("折叠摘要仍带完整模型名") { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription)
                        ?.any { it.contains("Claude Sonnet 4.5") } == true
                }
            )
            rule.onNodeWithTag(ChatHeaderSpeedTag, useUnmergedTree = true).assertIsDisplayed()
            for ((resource, action) in listOf(
                R.string.chat_style_open_drawer to "drawer", R.string.chat_style_usage to "usage",
                R.string.chat_style_toggle_preview to "preview", R.string.chat_style_new_chat to "new")) {
                val text = context.getString(resource)
                val node = if (action == "usage" && next != ChatUiStyle.FOCUS) rule.onNodeWithText(text)
                    else rule.onNodeWithContentDescription(text)
                node.assertIsDisplayed().performClick()
                rule.runOnIdle { assertTrue("$next lost $action on a 320dp header", clicks.contains(action)) }
            }
        }
    }
}

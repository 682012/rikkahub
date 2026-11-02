package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
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
    @Test fun everyHeaderKeepsActionsReadableWithBusyWallpaperAndLargeFont() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wallpaper = me.rerere.rikkahub.ui.theme.AppearanceTestSupport.noise("chat-header")
        var style by mutableStateOf(ChatUiStyle.STUDIO)
        var dark by mutableStateOf(false)
        val clicks = mutableListOf<String>()
        val conversation = Conversation(assistantId = kotlin.uuid.Uuid.random(), title = "合并版 · 沉浸式工作台", messageNodes = emptyList())
        rule.setContent {
            val density = LocalDensity.current
            val appearance = AppearanceSettings(chatUiStyle = style, wallpaper = wallpaper)
            val settings = Settings.dummy().copy(displaySetting = DisplaySetting(appearance = appearance))
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f), LocalSettings provides settings) {
                MaterialExpressiveTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    AppAppearanceHost(settings) {
                        ChatStyleHeader(ChatStyleSpec.of(style), settings, conversation, conversation.title,
                            "念安 / Example model", false, false,
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
            me.rerere.rikkahub.ui.theme.AppearanceTestSupport.capture(rule,"header-${next.name.lowercase()}-${if(night) "dark" else "light"}-large")
        }
    }
}

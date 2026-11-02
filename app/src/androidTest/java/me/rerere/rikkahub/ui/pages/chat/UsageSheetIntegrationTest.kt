package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import kotlin.uuid.Uuid

/** Real Room -> Flow -> modal -> persisted display setting, isolated emulator data only. */
@RunWith(AndroidJUnit4::class)
class UsageSheetIntegrationTest {
    @get:Rule val rule = createComposeRule()
    @Test fun roomUpdatesScopesAndPersistedSwitchUseOneLedger() {
        val dao = getKoin().get<UsageRecordDAO>()
        val store = getKoin().get<SettingsStore>()
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val original = runBlocking { store.awaitLoaded().displaySetting }
        fun insert(source: String, input: Int, output: Int) = runBlocking {
            dao.insert(UsageRecordEntity(createdAt = System.currentTimeMillis(), source = source,
                conversationId = conversation.id.toString(), modelName = "Fixture model", promptTokens = input, completionTokens = output))
        }
        try {
            runBlocking { store.update { it.copy(displaySetting = it.displaySetting.copy(useStudioDisplay = true)) } }
            insert("CHAT", 100, 20)
            rule.setContent {
                val settings by store.settingsFlow.collectAsState()
                CompositionLocalProvider(LocalSettings provides settings) {
                    MaterialExpressiveTheme { UsageSheet(conversation) {} }
                }
            }
            rule.waitUntil(10000) { rule.onAllNodesWithTag("usage-total").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("usage-total").assertTextEquals("120")
            insert("SUBAGENT", 300, 80)
            rule.waitUntil(10000) { rule.onAllNodesWithText("500").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("usage-total").assertTextEquals("500")
            rule.onNodeWithTag("usage-scope-CHAT").performClick()
            rule.onNodeWithTag("usage-total").assertTextEquals("120")
            rule.onNodeWithTag("usage-scope-SUBAGENT").performClick()
            rule.onNodeWithTag("usage-total").assertTextEquals("380")
            rule.onNodeWithTag("usage-classic").performClick()
            rule.waitUntil(10000) { !store.settingsFlow.value.displaySetting.useStudioDisplay }
            rule.onNodeWithText("本会话用量").assertIsDisplayed()
            rule.onNodeWithText("500").assertIsDisplayed()
            rule.onNodeWithText("工作台").performClick()
            rule.waitUntil(10000) { store.settingsFlow.value.displaySetting.useStudioDisplay }
            rule.onNodeWithTag("usage-dashboard").assertIsDisplayed()
        } finally {
            runBlocking {
                dao.deleteByConversation(conversation.id.toString())
                store.update { it.copy(displaySetting = original) }
            }
        }
    }
}

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.rememberToasterState
import androidx.compose.ui.test.onLast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.webService
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.account.WebSessionVault
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConfigureWebAccount
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class WebSessionSettingsUiTest {
    @get:Rule val rule = createComposeRule()
    private val context by lazy {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = android.content.res.Configuration(base.resources.configuration).apply { setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
        base.createConfigurationContext(config)
    }
    private val setting = ProviderSetting.ClaudeWeb()
    private val state = mutableStateOf<ProviderSetting.WebAccount>(setting)
    private val store get() = GlobalContext.get().get<SettingsStore>()
    private val vault get() = GlobalContext.get().get<WebSessionVault>()
    private fun text(id: Int) = context.getString(id)

    private fun launch() {
        runBlocking { store.awaitLoaded(); store.update { it.copy(providers = it.providers + setting) } }
        rule.setContent {
            CompositionLocalProvider(LocalToaster provides rememberToasterState(),
                androidx.compose.ui.platform.LocalContext provides context,
                androidx.compose.ui.platform.LocalConfiguration provides context.resources.configuration) {
                MaterialExpressiveTheme { Surface { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ProviderConfigureWebAccount(state.value) { state.value = it as ProviderSetting.WebAccount }
                } } }
            }
        }
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        val file = java.io.File(context.filesDir, "$name.png")
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(shell[1]).bufferedWriter().use {
            it.write("mkdir -p /data/local/tmp/rk-r4-web-ui\n")
            it.write("run-as ${context.packageName} cat files/${file.name} > /data/local/tmp/rk-r4-web-ui/${file.name}\n")
            it.write("test -s /data/local/tmp/rk-r4-web-ui/${file.name} && echo CAPTURED\nexit\n")
        }
        val receipt = android.os.ParcelFileDescriptor.AutoCloseInputStream(shell[0]).bufferedReader().use { it.readText() }
        assertTrue(receipt.contains("CAPTURED"))
    }
    @After fun cleanup() = runBlocking {
        vault.clearSession(setting.id)
        store.update { it.copy(providers = it.providers.filterNot { p -> p.id == setting.id }) }
    }
    @Test fun aSavedLocalCookieDoesNotPretendToBeVerifiedAndClearsOnRequest() {
        launch()
        rule.onNodeWithText(text(R.string.setting_provider_web_session_unconfigured)).assertIsDisplayed()
        capture("claude-web-unconfigured-zh")
        rule.onNodeWithText(text(R.string.setting_provider_web_import)).performScrollTo().performClick()
        rule.onAllNodes(hasSetTextAction()).onLast().performTextInput("sessionKey=LOCAL_TEST_ONLY_NOT_A_REAL_CREDENTIAL")
        rule.onNodeWithText(text(R.string.setting_provider_web_import_confirm)).performClick()
        rule.waitUntil(5000) { runBlocking { vault.hasSession(setting.id, setting.webService) } }
        rule.onNodeWithText(text(R.string.setting_provider_web_session_saved)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(text(R.string.setting_provider_web_clear)).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.delete)).performClick()
        rule.waitUntil(5000) { !runBlocking { vault.hasSession(setting.id, setting.webService) } }
        rule.onNodeWithText(text(R.string.setting_provider_web_session_unconfigured)).performScrollTo().assertIsDisplayed()
        assertTrue(state.value.models.isEmpty())
    }
    @Test fun bridgeStartsOffAndRequiresExplicitConfirmation() {
        launch()
        assertFalse(state.value.toolBridgeEnabled)
        rule.onAllNodes(androidx.compose.ui.test.hasText(text(R.string.setting_provider_web_tool_bridge))).onLast().performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.setting_provider_web_tool_confirm_title)).assertIsDisplayed()
        assertFalse(state.value.toolBridgeEnabled)
        rule.onNodeWithText(text(R.string.cancel)).performClick()
        assertFalse(state.value.toolBridgeEnabled)
    }
}

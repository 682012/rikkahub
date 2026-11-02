package me.rerere.rikkahub.ui.pages.chat

import android.graphics.Bitmap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UsageDashboardUiTest {
    @get:Rule val rule = createComposeRule()
    private var selected = mutableStateOf(UsageScope.ALL)
    private var classic = false
    private var total = mutableStateOf(DashboardTotals(32, 160_000, 12_800, 132_000))
    private fun launch(dark: Boolean = false, fontScale: Float = 1f, unknown: Boolean = false) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialExpressiveTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        UsageDashboard(total.value, listOf(DashboardModel("Claude · Sonnet", 24, 132800), DashboardModel("Gemini · Flash", 8, 40000)),
                            DashboardContext(if (unknown) null else 32000, 128000), selected.value, { selected.value = it }, { classic = true })
                    }
                }
            }
        }
        rule.waitForIdle()
    }
    @Test fun scopesAndClassicSwitchAreTouchable() {
        launch()
        capture("light")
        rule.onNodeWithTag("usage-scope-SUBAGENT").performClick()
        rule.runOnIdle { assertEquals(UsageScope.SUBAGENT, selected.value) }
        rule.onNodeWithTag("usage-classic").performClick()
        rule.runOnIdle { assertTrue(classic) }
    }
    @Test fun newUsageRecomposesWithoutReopening() {
        launch()
        rule.onNodeWithTag("usage-total").assertTextEquals("172.8K")
        rule.runOnIdle { total.value = DashboardTotals(33, 200_000, 20_000, 160_000) }
        rule.onNodeWithTag("usage-total").assertTextEquals("220.0K")
    }
    @Test fun exactReadoutKeepsCacheInsideInput() {
        launch()
        rule.onNodeWithTag("usage-total").performClick()
        rule.onNodeWithText("合计  172,800 tokens").assertIsDisplayed()
        rule.onNodeWithText("缓存  132,000 tokens（已含在输入中）").assertIsDisplayed()
        rule.onNodeWithText("关闭").performClick()
        rule.onNodeWithTag("usage-dashboard").assertIsDisplayed()
    }
    @Test fun missingContextIsNotDisplayedAsZeroPercent() {
        launch(unknown = true)
        rule.onNodeWithText("暂无上游报告").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("0 / 128,000 tokens").assertDoesNotExist()
    }
    @Test fun darkLargeFontLayoutRendersAndScrolls() {
        launch(dark = true, fontScale = 1.3f)
        rule.onNodeWithTag("usage-total").assertIsDisplayed()
        capture("dark-large")
        rule.onNodeWithText("模型分布").performScrollTo().assertIsDisplayed()
        capture("models-large")
    }
    private fun capture(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.filesDir, "r5-$name.png")
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val io = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(io[1]).bufferedWriter().use {
            it.write("mkdir -p /data/local/tmp/rk-r5-ui\nrun-as ${ctx.packageName} cat files/${file.name} > /data/local/tmp/rk-r5-ui/${file.name}\necho CAPTURED\nexit\n")
        }
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(io[0]).bufferedReader().use { it.readText() }
        assertTrue(text.contains("CAPTURED"))
    }
}

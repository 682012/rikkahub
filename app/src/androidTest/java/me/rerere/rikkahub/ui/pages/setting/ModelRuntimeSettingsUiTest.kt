package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import java.util.Locale
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.AutoCompressSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BASIC 页「上下文 / 自动压缩 / 并发」这一块的界面测试。
 *
 * 只把 [ModelRuntimeSettingsForm] 挂在一个最小页面里，不启动 Koin、不碰 DataStore：
 * 模型和全局压缩设置放在 [mutableStateOf] 里，onModelChange 写回它，断言前用 waitForIdle
 * 回到 UI 线程取值。每个用例都会把当前界面截图写到 targetContext.filesDir，
 * 方便 CI 从设备拉取真实渲染结果（用 targetContext 而不是 testContext，包名不同）。
 */
@RunWith(AndroidJUnit4::class)
class ModelRuntimeSettingsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext
    }

    private lateinit var model: MutableState<Model>
    private var reportedValid = true

    private fun label(resId: Int): String = context.getString(resId)

    private fun formatted(resId: Int, vararg args: Any): String =
        context.getString(resId, *args)

    private fun launch(
        initial: Model = Model(modelId = "m-a", displayName = "A", type = ModelType.CHAT),
        global: AutoCompressSettings = AutoCompressSettings(enabled = true, thresholdPercent = 80),
        locale: Locale? = null,
    ) {
        model = mutableStateOf(initial)
        reportedValid = true
        val configuration = Configuration(context.resources.configuration).apply {
            locale?.let { setLocale(it) }
        }
        val localizedContext = context.createConfigurationContext(configuration)
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext, LocalConfiguration provides configuration) {
                MaterialExpressiveTheme {
                    Surface {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            ModelRuntimeSettingsForm(
                                model = model.value,
                                onModelChange = { model.value = it },
                                globalAutoCompress = global,
                                onValidityChange = { reportedValid = it },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun currentModel(): Model {
        var snapshot = model.value
        composeRule.runOnIdle { snapshot = model.value }
        return snapshot
    }

    private fun switchTo(next: Model) {
        composeRule.runOnIdle { model.value = next }
        composeRule.waitForIdle()
    }

    /** 把当前界面真实渲染结果存到 filesDir，CI 可直接 pull。 */
    private fun capture(name: String): File {
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val file = File(context.filesDir, "model-runtime-$name.png")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    @Test
    fun thresholdInputWritesNumberAndBlankRestoresFollowGlobal() {
        launch(
            initial = Model(
                modelId = "m-a",
                displayName = "A",
                contextLength = 200_000,
            )
        )

        // 默认跟全局：输入框空白，按全局阈值给出触发 token
        assertNull(currentModel().autoCompressThresholdPercent)
        val trigger = formatted(
            R.string.model_runtime_auto_compress_trigger,
            formatTriggerTokens(ModelRuntimeDraft.triggerTokens(200_000, 80)),
        )
        composeRule.onNodeWithText(trigger, useUnmergedTree = true).assertExists()
        assertTrue(reportedValid)
        capture("threshold-follow-global")

        // 填 40：写回模型，触发 token 跟着变
        composeRule.onNodeWithTag(ModelRuntimeTestTags.THRESHOLD_INPUT)
            .performScrollTo()
            .performTextReplacement("40")
        composeRule.waitForIdle()
        assertEquals(40, currentModel().autoCompressThresholdPercent)
        composeRule.onNodeWithText(
            formatted(
                R.string.model_runtime_auto_compress_trigger,
                formatTriggerTokens(ModelRuntimeDraft.triggerTokens(200_000, 40)),
            ),
            useUnmergedTree = true,
        ).assertExists()
        capture("threshold-40")

        // 清空：恢复跟全局（null），不动上下文和并发
        composeRule.onNodeWithTag(ModelRuntimeTestTags.THRESHOLD_INPUT)
            .performScrollTo()
            .performTextClearance()
        composeRule.waitForIdle()
        val afterClear = currentModel()
        assertNull(afterClear.autoCompressThresholdPercent)
        assertEquals(200_000, afterClear.contextLength)
        assertTrue(reportedValid)
    }

    @Test
    fun invalidThresholdDraftReportsInvalidAndKeepsOldValue() {
        launch(
            initial = Model(
                modelId = "m-a",
                displayName = "A",
                contextLength = 200_000,
                autoCompressThresholdPercent = 50,
            )
        )

        listOf("0", "9", "101", "-1", "99999999999999999999", "abc").forEach { raw ->
            composeRule.onNodeWithTag(ModelRuntimeTestTags.THRESHOLD_INPUT)
                .performScrollTo()
                .performTextReplacement(raw)
            composeRule.waitForIdle()

            // 非法草稿不写回，旧值保留，回调报 false（外层据此禁用确认）
            assertEquals("raw=$raw", 50, currentModel().autoCompressThresholdPercent)
            assertFalse("raw=$raw", reportedValid)
            composeRule.onNodeWithText(
                label(R.string.model_runtime_invalid_percent),
                useUnmergedTree = true,
            ).assertExists()
        }
        capture("threshold-invalid")

        // 改回合法值后恢复可保存
        composeRule.onNodeWithTag(ModelRuntimeTestTags.THRESHOLD_INPUT)
            .performScrollTo()
            .performTextReplacement("100")
        composeRule.waitForIdle()
        assertEquals(100, currentModel().autoCompressThresholdPercent)
        assertTrue(reportedValid)
    }

    @Test
    fun invalidContextDraftDoesNotBecomeOne() {
        launch(
            initial = Model(
                modelId = "m-a",
                displayName = "A",
                contextLength = 300_000,
            )
        )

        // 自定义框里输入 "-1"：不能被过滤成 "1" 存下去
        composeRule.onNodeWithTag(ModelRuntimeTestTags.CONTEXT_INPUT)
            .performScrollTo()
            .performTextReplacement("-1")
        composeRule.waitForIdle()
        assertEquals(300_000, currentModel().contextLength)
        assertFalse(reportedValid)
        composeRule.onNodeWithTag(ModelRuntimeTestTags.CONTEXT_INPUT)
            .assertIsDisplayed()
        capture("context-invalid")

        // 0 与溢出同样非法，清空也非法
        listOf("0", "99999999999999999999", "").forEach { raw ->
            composeRule.onNodeWithTag(ModelRuntimeTestTags.CONTEXT_INPUT)
                .performScrollTo()
                .performTextReplacement(raw)
            composeRule.waitForIdle()
            assertEquals("raw=$raw", 300_000, currentModel().contextLength)
            assertFalse("raw=$raw", reportedValid)
        }

        // 合法正整数写回
        composeRule.onNodeWithTag(ModelRuntimeTestTags.CONTEXT_INPUT)
            .performScrollTo()
            .performTextReplacement("420000")
        composeRule.waitForIdle()
        assertEquals(420_000, currentModel().contextLength)
        assertTrue(reportedValid)
    }

    @Test
    fun concurrencyBlankMeansNoCapAndRangeIsEnforced() {
        launch(
            initial = Model(modelId = "m-a", displayName = "A", type = ModelType.IMAGE)
        )

        // 非 CHAT 模型也有并发设置，但没有压缩项
        composeRule.onNodeWithTag(ModelRuntimeTestTags.CONCURRENCY_INPUT).assertExists()
        composeRule.onNodeWithText(label(R.string.model_runtime_auto_compress_title))
            .assertDoesNotExist()
        assertNull(currentModel().maxConcurrentRequests)
        capture("concurrency-blank")

        composeRule.onNodeWithTag(ModelRuntimeTestTags.CONCURRENCY_INPUT)
            .performScrollTo()
            .performTextReplacement("64")
        composeRule.waitForIdle()
        assertEquals(64, currentModel().maxConcurrentRequests)

        listOf("65", "0", "-1", "99999999999999999999").forEach { raw ->
            composeRule.onNodeWithTag(ModelRuntimeTestTags.CONCURRENCY_INPUT)
                .performScrollTo()
                .performTextReplacement(raw)
            composeRule.waitForIdle()
            assertEquals("raw=$raw", 64, currentModel().maxConcurrentRequests)
            assertFalse("raw=$raw", reportedValid)
        }
        capture("concurrency-invalid")

        // 空白 = 无额外上限
        composeRule.onNodeWithTag(ModelRuntimeTestTags.CONCURRENCY_INPUT)
            .performScrollTo()
            .performTextClearance()
        composeRule.waitForIdle()
        assertNull(currentModel().maxConcurrentRequests)
        assertTrue(reportedValid)
    }

    @Test
    fun compressionModeRestoresGlobalWithoutTouchingOtherFields() {
        launch(
            initial = Model(
                modelId = "m-a",
                displayName = "A",
                contextLength = 300_000,
                autoCompressEnabled = false,
                autoCompressThresholdPercent = 40,
                maxConcurrentRequests = 3,
            )
        )

        composeRule.onNodeWithText(label(R.string.model_runtime_auto_compress_on))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(true, currentModel().autoCompressEnabled)

        // 回到「跟随全局」：只把该字段写回 null
        composeRule.onNodeWithText(label(R.string.model_runtime_auto_compress_follow_on))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        val restored = currentModel()
        assertNull(restored.autoCompressEnabled)
        assertEquals(300_000, restored.contextLength)
        assertEquals(40, restored.autoCompressThresholdPercent)
        assertEquals(3, restored.maxConcurrentRequests)
        assertTrue(reportedValid)
        capture("compression-follow-global")
    }

    @Test
    fun invalidDraftSurvivesStateRestorationAndStillBlocksSaving() {
        val restore = StateRestorationTester(composeRule)
        model = mutableStateOf(Model(modelId = "restore", contextLength = 128_000))
        restore.setContent {
            MaterialExpressiveTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ModelRuntimeSettingsForm(model.value, { model.value = it },
                        onValidityChange = { reportedValid = it })
                }
            }
        }
        composeRule.onNodeWithTag(ModelRuntimeTestTags.CONCURRENCY_INPUT)
            .performScrollTo().performTextReplacement("-1")
        composeRule.waitForIdle()
        assertFalse(reportedValid)
        restore.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(ModelRuntimeTestTags.CONCURRENCY_INPUT)
            .performScrollTo().assertTextContains("-1")
        composeRule.waitForIdle()
        assertFalse(reportedValid)
        assertNull(currentModel().maxConcurrentRequests)
    }

    @Test
    fun chineseLayoutRendersAllControlsAndCustomConcurrency() {
        launch(initial = Model(modelId = "zh", displayName = "中文模型", contextLength = 200_000,
            autoCompressEnabled = true, autoCompressThresholdPercent = 75, maxConcurrentRequests = 3),
            locale = Locale.SIMPLIFIED_CHINESE)
        composeRule.onNodeWithText("自动压缩").assertExists()
        capture("zh-overview")
        composeRule.onNodeWithTag(ModelRuntimeTestTags.CONCURRENCY_INPUT).performScrollTo().assertIsDisplayed()
        capture("zh-concurrency")
        assertEquals(3, currentModel().maxConcurrentRequests)
        assertTrue(reportedValid)
    }

    @Test
    fun switchingModelsKeepsDraftsPerModel() {
        val modelA = Model(
            modelId = "m-a",
            displayName = "A",
            contextLength = 300_000,
            autoCompressThresholdPercent = 40,
            maxConcurrentRequests = 3,
        )
        val modelB = Model(
            modelId = "m-b",
            displayName = "B",
            contextLength = 500_000,
            autoCompressThresholdPercent = 90,
            maxConcurrentRequests = 8,
        )
        launch(initial = modelA)

        composeRule.onNodeWithTag(ModelRuntimeTestTags.CONTEXT_INPUT)
            .performScrollTo()
            .performTextReplacement("310000")
        composeRule.waitForIdle()
        assertEquals(310_000, currentModel().contextLength)
        val editedA = currentModel()

        // 切到 B：输入框回读 B 的值，不串 A 的草稿
        switchTo(modelB)
        composeRule.onNodeWithTag(ModelRuntimeTestTags.CONTEXT_INPUT)
            .performScrollTo()
            .assertTextContains("500000")
        composeRule.onNodeWithTag(ModelRuntimeTestTags.THRESHOLD_INPUT)
            .performScrollTo()
            .assertTextContains("90")
        composeRule.onNodeWithTag(ModelRuntimeTestTags.CONCURRENCY_INPUT)
            .performScrollTo()
            .assertTextContains("8")
        capture("model-b")

        // 切回 A：输入框按已保存的 A 回读（310000），未被 B 覆盖
        switchTo(editedA)
        composeRule.onNodeWithTag(ModelRuntimeTestTags.CONTEXT_INPUT)
            .performScrollTo()
            .assertTextContains("310000")
        composeRule.onNodeWithTag(ModelRuntimeTestTags.THRESHOLD_INPUT)
            .performScrollTo()
            .assertTextContains("40")
        composeRule.onNodeWithTag(ModelRuntimeTestTags.CONCURRENCY_INPUT)
            .performScrollTo()
            .assertTextContains("3")
        assertTrue(reportedValid)
        capture("model-a-restored")
    }
}

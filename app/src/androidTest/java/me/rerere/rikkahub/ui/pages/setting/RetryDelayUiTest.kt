package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.RetryDelayMode
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "网络 → 自动重试 → 等待时长"这一组的界面测试。
 *
 * 这里只把 [retryDelaySettingItems] 挂在一个最小页面里跑，不启动 Koin、不打网络、不碰 DataStore：
 * 设置对象放在 [mutableStateOf] 里，[retryDelaySettingItems] 的 onChange 写回它，读断言时用
 * [androidx.compose.ui.test.junit4.ComposeContentTestRule.runOnIdle] 回到 UI 线程取值，保证看到的是
 * 重组之后的状态。
 *
 * 注意：这些用例真正跑起来依赖主线程完成 AndroidTest 编译并在设备/模拟器上执行；
 * 本文件只保证按现有 production API 写好，没有在这里声称已经 passed。
 */
@RunWith(AndroidJUnit4::class)
class RetryDelayUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext
    }

    /** 界面状态：production 的 onChange 就是把新设置写回这里。 */
    private lateinit var setting: MutableState<NetworkSetting>

    private fun label(resId: Int): String = context.getString(resId)

    private fun formatted(resId: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args)

    private fun launch(initial: NetworkSetting = NetworkSetting()) {
        setting = mutableStateOf(initial)
        composeRule.setContent {
            MaterialExpressiveTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // 小屏上自定义输入框会掉到屏幕外，给整页一个滚动容器，配合 performScrollTo
                        .verticalScroll(rememberScrollState()),
                ) {
                    CardGroup {
                        retryDelaySettingItems(
                            setting = setting.value,
                            onChange = { updated -> setting.value = updated },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** 在 UI 线程上读当前设置（重组已经跑完）。 */
    private fun currentSetting(): NetworkSetting {
        var snapshot = setting.value
        composeRule.runOnIdle { snapshot = setting.value }
        return snapshot
    }

    private fun assertDelay(mode: RetryDelayMode, fixed: Int = -1, min: Int = -1, max: Int = -1) {
        val delay = currentSetting().normalizedRetryDelay()
        assertEquals(mode, delay.mode)
        if (fixed >= 0) assertEquals(fixed, delay.fixedSeconds)
        if (min >= 0) assertEquals(min, delay.minSeconds)
        if (max >= 0) assertEquals(max, delay.maxSeconds)
    }

    @Test
    fun defaultIsFixed60_thenChoosing30WritesBackToNetworkSetting() {
        launch()

        // 默认固定 60 秒：设置值、预设判定和说明文案三者一致
        assertDelay(RetryDelayMode.FIXED, fixed = 60)
        assertEquals(RetryDelayPreset.FIXED_60, retryDelayPresetOf(currentSetting()))
        composeRule.onNodeWithText(label(R.string.retry_settings_delay_title)).assertExists()
        composeRule.onNodeWithText(
            formatted(R.string.retry_settings_delay_desc_fixed, 60)
        ).assertExists()

        // 固定模式下没有自定义区间输入框
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)

        // 点 "30 s"：写回 NetworkSetting，说明文案跟着变成 30 秒
        composeRule.onNodeWithText(label(R.string.retry_settings_preset_fixed_30))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        assertDelay(RetryDelayMode.FIXED, fixed = 30)
        assertEquals(RetryDelayPreset.FIXED_30, retryDelayPresetOf(currentSetting()))
        composeRule.onNodeWithText(
            formatted(R.string.retry_settings_delay_desc_fixed, 30)
        ).assertExists()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun choosingRangeThenCustom_showsTwoCustomInputs() {
        launch()

        // 选 30~60 秒随机：只写回区间，不该自己展开自定义输入框
        composeRule.onNodeWithText(label(R.string.retry_settings_preset_range))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        assertDelay(RetryDelayMode.RANGE, min = 30, max = 60)
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)

        // 点 "Custom"：出现最短/最长两个输入框，初值就是当前生效的 30 / 60
        composeRule.onNodeWithText(label(R.string.retry_settings_preset_custom))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        composeRule.onNodeWithText(label(R.string.retry_settings_custom_min), useUnmergedTree = true)
            .assertExists()
        composeRule.onNodeWithText(label(R.string.retry_settings_custom_max), useUnmergedTree = true)
            .assertExists()
        // 输入框里显示的是实际生效的上下界（按文本定位，不依赖节点顺序）
        composeRule.onNode(hasSetTextAction() and hasText("30")).assertExists()
        composeRule.onNode(hasSetTextAction() and hasText("60")).assertExists()
        assertEquals(IntRange(30, 60), currentSetting().editableRetryRange())
    }

    @Test
    fun typingBoundsEqualToPresetValues_keepsCustomInputsAndMatchingRange() {
        launch(
            NetworkSetting(
                retryDelayMode = RetryDelayMode.RANGE,
                retryDelayMinSeconds = 30,
                retryDelayMaxSeconds = 60,
            )
        )

        // 30~60 是预设区间，先进自定义
        composeRule.onNodeWithText(label(R.string.retry_settings_preset_custom))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(2)

        // 把最长等待改成和最短一样（30 / 30）：等价于固定等待，但输入框不能因此消失
        composeRule.onNode(hasSetTextAction() and hasText("60"))
            .performScrollTo()
            .performTextReplacement("30")
        composeRule.waitForIdle()

        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        val typed = currentSetting()
        val delay = typed.normalizedRetryDelay()
        assertEquals(RetryDelayMode.RANGE, delay.mode)
        assertEquals(delay.minSeconds, delay.maxSeconds)
        assertEquals(30, delay.minSeconds)
        assertEquals(RetryDelayPreset.CUSTOM, retryDelayPresetOf(typed))
        // 两个框都还在，并且都显示 30
        composeRule.onAllNodes(hasSetTextAction() and hasText("30")).assertCountEquals(2)
        // 说明文案按"固定等待"讲，不再写随机区间
        composeRule.onNodeWithText(
            formatted(R.string.retry_settings_delay_desc_fixed, 30)
        ).assertExists()
    }
}

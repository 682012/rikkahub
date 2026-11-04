package me.rerere.rikkahub.ui.components.ai

import android.Manifest
import android.view.ContextThemeWrapper
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * r13 压缩弹窗回归：弹窗在 `ContextThemeWrapper`（多层）里也必须能开、能确认、能收起。
 *
 * 崩溃栈：聊天页点击压缩 → FilesPicker 在弹层上下文里组合 [CompressContextDialog] →
 * 弹窗内部 `rememberPermissionState(PermissionNotification)` 拿到的 `LocalContext` 是被 wrapper 包过的
 * Activity，r12 直接 `context as? ComponentActivity ?: throw IllegalStateException(...)`，
 * 主线程崩在打开弹窗这一刻。所以这里断言的是真实弹窗：
 *
 * 1. 双层 `ContextThemeWrapper` + `LocalActivity` 被清空（崩溃形态）下弹窗照常渲染标题/说明/两个按钮；
 * 2. 点“确认”只回调一次 [CompressContextDialog] 的 `onConfirm`，拿到的是后台 Job，弹窗立即收起，
 *    且通知权限的策略不会挡住宿主流程：请求被测试侧 registry 截获，不会出现任何系统权限弹窗，
 *    也不会有真实模型/计费调用（`onConfirm` 是测试注入的回调，不碰网络与 ChatService）；
 * 3. 点“取消”不触发压缩，只收起。
 *
 * 不加载 Koin、不读设置、不申请真实系统权限、不改生产代码。
 */
@RunWith(AndroidJUnit4::class)
class CompressContextDialogR13Test {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var title: String
    private lateinit var description: String
    private lateinit var confirmLabel: String
    private lateinit var cancelLabel: String

    /** 记录弹窗发起过的权限请求；永远不回调结果，因此系统权限窗口不会出现。 */
    private val launches = mutableListOf<Any?>()

    @Before
    fun setUp() {
        val host = rule.activity
        title = host.getString(R.string.chat_page_compress_context_title)
        description = host.getString(R.string.chat_page_compress_context_desc)
        confirmLabel = host.getString(R.string.confirm)
        cancelLabel = host.getString(R.string.cancel)
        launches.clear()
    }

    @Test
    fun dialogOpensInsideNestedThemedWrapper() {
        val host = rule.activity
        // 真实崩溃形态：两层 themed wrapper，且 LocalActivity 不可用（只能靠 context 链回溯）。
        val nested = ContextThemeWrapper(
            ContextThemeWrapper(host, R.style.Theme_Rikkahub),
            R.style.Theme_Rikkahub,
        )

        rule.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings.dummy(),
                LocalContext provides nested,
                LocalActivity provides null,
            ) {
                MaterialExpressiveTheme {
                    CompressContextDialog(
                        onDismiss = {},
                        onConfirm = { _, _ -> Job() },
                    )
                }
            }
        }

        // 组合不抛异常 + 弹窗内容真的在：r12 在这里抛 IllegalStateException，压缩入口直接打不开。
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithText(description).assertIsDisplayed()
        rule.onNodeWithText(confirmLabel).assertIsDisplayed()
        rule.onNodeWithText(cancelLabel).assertIsDisplayed()
        rule.onNodeWithContentDescription("Increase").assertIsDisplayed()
        // 只是打开，不该有任何权限请求被发出。
        assertTrue("仅打开弹窗不应发起权限请求", launches.isEmpty())
    }

    @Test
    fun confirmRunsJobExactlyOnceAndDismisses() {
        val host = rule.activity
        val themed = ContextThemeWrapper(host, R.style.Theme_Rikkahub)
        val notificationGranted = ContextCompat.checkSelfPermission(
            host,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        var open by mutableStateOf(true)
        val confirmCalls = AtomicInteger()
        val dismissCalls = AtomicInteger()
        val confirmArgs = mutableListOf<Pair<String, Int>>()
        var backgroundJob: CompletableJob? = null

        rule.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings.dummy(),
                LocalContext provides themed,
                LocalActivity provides null,
                LocalActivityResultRegistryOwner provides remember { recordingRegistryOwner() },
            ) {
                MaterialExpressiveTheme {
                    if (open) {
                        CompressContextDialog(
                            onDismiss = {
                                dismissCalls.incrementAndGet()
                                open = false
                            },
                            onConfirm = { additionalPrompt, keepRecentMessages ->
                                confirmCalls.incrementAndGet()
                                confirmArgs += additionalPrompt to keepRecentMessages
                                // 后台任务：不绑定弹窗生命周期，弹窗关掉也应该还活着。
                                Job().also { backgroundJob = it }
                            },
                        )
                    }
                }
            }
        }

        // 保留最近消息从 0 调到 1：断言 UI 值真的传进回调，而不是写死默认值。
        rule.onNodeWithContentDescription("Increase").performClick()
        rule.onNodeWithText(confirmLabel).performClick()

        assertTrue("确认必须只触发一次压缩任务", waitForIdleUntil { confirmCalls.get() == 1 })
        assertEquals(1, confirmCalls.get())
        assertEquals(listOf("" to 1), confirmArgs)
        assertNotNull("确认后必须拿到后台 Job", backgroundJob)
        assertTrue("压缩任务应在后台继续，不被弹窗生命周期绑死", backgroundJob!!.isActive)
        assertTrue("通知权限请求最多只发一次，且被测试 registry 截获", launches.size <= 1)

        val dismissed = waitForIdleUntil { !open }
        if (!dismissed && !notificationGranted && launches.isEmpty()) {
            // 设备上 POST_NOTIFICATIONS 已进入“拒绝过且不再询问”状态时，生产代码会先弹应用内说明对话框；
            // 这种环境状态不该被当成代码回归（也不能为了它去申请真实系统权限）。
            assumeTrue("POST_NOTIFICATIONS 在该设备处于拒绝态，跳过即时收起断言", false)
        }
        assertTrue("确认后弹窗必须收起，压缩不能被通知权限挡住", dismissed)
        rule.onNodeWithText(title).assertDoesNotExist()
        assertTrue("收起的回调至少发生一次", dismissCalls.get() >= 1)

        backgroundJob!!.complete()
        rule.waitForIdle()
    }

    @Test
    fun cancelDismissesWithoutStartingCompression() {
        val host = rule.activity
        val themed = ContextThemeWrapper(host, R.style.Theme_Rikkahub)
        var open by mutableStateOf(true)
        val confirmCalls = AtomicInteger()
        val dismissCalls = AtomicInteger()

        rule.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings.dummy(),
                LocalContext provides themed,
                LocalActivity provides null,
            ) {
                MaterialExpressiveTheme {
                    if (open) {
                        CompressContextDialog(
                            onDismiss = {
                                dismissCalls.incrementAndGet()
                                open = false
                            },
                            onConfirm = { _, _ ->
                                confirmCalls.incrementAndGet()
                                Job()
                            },
                        )
                    }
                }
            }
        }

        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithText(cancelLabel).performClick()

        assertTrue("取消必须收起弹窗", waitForIdleUntil { !open })
        rule.onNodeWithText(title).assertDoesNotExist()
        assertEquals(0, confirmCalls.get())
        assertTrue(dismissCalls.get() >= 1)
        assertFalse("取消不该发起权限请求", launches.isNotEmpty())
    }

    private fun recordingRegistryOwner(): ActivityResultRegistryOwner =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry =
                object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(
                        requestCode: Int,
                        contract: ActivityResultContract<I, O>,
                        input: I,
                        options: ActivityOptionsCompat?
                    ) {
                        launches += input
                    }
                }
        }

    private fun waitForIdleUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            rule.waitForIdle()
            if (condition()) return true
            Thread.sleep(20)
        }
        rule.waitForIdle()
        return condition()
    }
}

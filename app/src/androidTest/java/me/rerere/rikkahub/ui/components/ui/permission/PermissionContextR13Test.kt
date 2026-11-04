package me.rerere.rikkahub.ui.components.ui.permission

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * r13 权限宿主回归：`rememberPermissionState` 不能再假设 `LocalContext.current` 就是 Activity。
 *
 * 线上崩溃（点击压缩时）走的是这条栈：聊天页 → FilesPicker → 弹层/Dialog 内部的组合上下文，
 * `LocalContext.current` 是 `ContextThemeWrapper`（甚至多层），r12 的
 * `context as? ComponentActivity ?: throw IllegalStateException("rememberPermissionState 必须在 ComponentActivity 中使用")`
 * 直接抛在主线程上，压缩入口打不开。这里用真实 Compose 宿主 + 真实 `ContextThemeWrapper` 复现该形状，
 * 断言四件事：
 *
 * 1. 单层/双层 themed wrapper 下照常组合（不抛），并且能从 baseContext 链里找回 ComponentActivity；
 * 2. 找回宿主后权限请求真的发得出去（走注入的 registry，会记录一次真实 launch），
 *    不是“把请求悄悄吞掉”的假修复；
 * 3. 服务/应用级 Context（没有 Activity 可找）时优雅渲染，`canRequestPermissions == false`，
 *    `requestPermissions()` 不崩也不弹任何对话框，权限状态照实报告（不伪造 Granted）；
 * 4. 空权限集合不需要任何提示。
 *
 * 只用 Compose rule + `MaterialExpressiveTheme` + 测试侧注入的 `ActivityResultRegistry`：
 * 不启动 Koin、不碰网络、不申请任何真实系统权限（launch 被测试 registry 截获，系统权限弹窗不会出现），
 * 也不修改生产代码。`LocalActivity` 显式传 null 是为了强制走 baseContext 解析分支 —— 这正是崩溃栈里
 * Activity 被 wrapper 挡住的形态。
 */
@RunWith(AndroidJUnit4::class)
class PermissionContextR13Test {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** 记录被真实 launch 的输入；永远不回调结果，因此不会触发任何系统权限流程。 */
    private val launches = mutableListOf<Any?>()

    private lateinit var injectedRegistryOwner: ActivityResultRegistryOwner

    @Before
    fun setUp() {
        // 夹具自检：合成权限在清单里不存在，系统侧恒为 DENIED；否则“发起请求”的断言会变空。
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(rule.activity, SYNTHETIC_PERMISSION),
        )
        launches.clear()
        injectedRegistryOwner = object : ActivityResultRegistryOwner {
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
    }

    @Test
    fun themedWrapperContext_resolvesHostAndLaunchesRequestThroughRegistry() {
        val host = rule.activity
        val themed = ContextThemeWrapper(host, R.style.Theme_Rikkahub)
        var captured: PermissionState? = null

        composeInHost(context = themed, localActivity = null) {
            val permissionState = rememberPermissionState(SyntheticPermission)
            SideEffect { captured = permissionState }
            Column {
                Text("themed-host", Modifier.testTag(HOST_TAG))
                Button(
                    onClick = permissionState::requestPermissions,
                    modifier = Modifier.testTag(REQUEST_TAG),
                ) { Text("request") }
            }
        }

        // 组合本身不再抛 IllegalStateException，这就是崩溃回归本身。
        rule.onNodeWithTag(HOST_TAG).assertIsDisplayed()
        rule.waitForIdle()

        val permissionState = requireNotNull(captured) { "permission state was never created" }
        assertTrue("ContextThemeWrapper 下的 ComponentActivity 必须能被找回", permissionState.canRequestPermissions)
        assertEquals(PermissionStatus.NotRequested, permissionState.permissionStates[SYNTHETIC_PERMISSION])

        rule.onNodeWithTag(REQUEST_TAG).performClick()
        rule.waitForIdle()

        // 找回宿主之后请求必须真的发出去（被测试 registry 截获），而不是被静默忽略。
        assertEquals(listOf<Any?>(SYNTHETIC_PERMISSION), launches)
    }

    @Test
    fun doubleNestedThemedWrappers_resolveHostRecursively() {
        val host = rule.activity
        val nested = ContextThemeWrapper(
            ContextThemeWrapper(host, R.style.Theme_Rikkahub),
            R.style.Theme_Rikkahub,
        )
        var captured: PermissionState? = null

        composeInHost(context = nested, localActivity = null) {
            val permissionState = rememberPermissionState(SyntheticPermission)
            SideEffect { captured = permissionState }
            Column {
                Text("nested-host", Modifier.testTag(HOST_TAG))
                Button(
                    onClick = permissionState::requestPermissions,
                    modifier = Modifier.testTag(REQUEST_TAG),
                ) { Text("request") }
            }
        }

        rule.onNodeWithTag(HOST_TAG).assertIsDisplayed()
        rule.waitForIdle()

        val permissionState = requireNotNull(captured)
        // 只往上找一层 `baseContext` 的写法在这里会失败：两层 wrapper 必须逐层回溯。
        assertTrue("多层 ContextThemeWrapper 必须递归找回 Activity", permissionState.canRequestPermissions)

        rule.onNodeWithTag(REQUEST_TAG).performClick()
        rule.waitForIdle()
        assertEquals(listOf<Any?>(SYNTHETIC_PERMISSION), launches)
    }

    @Test
    fun serviceLikeHost_rendersGracefullyWithoutRequestingAnything() {
        val host = rule.activity
        // 服务/后台形态：ContextWrapper(应用 Context)，没有 Activity，也没有 LocalActivity。
        val serviceLike = ContextWrapper(host.applicationContext)
        var captured: PermissionState? = null

        composeInHost(context = serviceLike, localActivity = null) {
            val permissionState = rememberPermissionState(SyntheticPermission)
            SideEffect { captured = permissionState }
            Column {
                Text("service-host", Modifier.testTag(HOST_TAG))
                Button(
                    onClick = permissionState::requestPermissions,
                    modifier = Modifier.testTag(REQUEST_TAG),
                ) { Text("request") }
            }
        }

        // 没有权限宿主时也必须有界面，不能把宿主缺失变成渲染崩溃。
        rule.onNodeWithTag(HOST_TAG).assertIsDisplayed()
        rule.waitForIdle()

        val permissionState = requireNotNull(captured)
        assertFalse("无 Activity 宿主时不能声称可以拉起权限请求", permissionState.canRequestPermissions)
        // 状态照实报告：未授权，且没有被伪造成 Granted。
        assertEquals(PermissionStatus.NotRequested, permissionState.permissionStates[SYNTHETIC_PERMISSION])
        assertFalse(permissionState.allPermissionsGranted)

        rule.onNodeWithTag(REQUEST_TAG).performClick()
        rule.waitForIdle()

        assertTrue("没有 Activity 时不能真的发起请求", launches.isEmpty())
        assertFalse(permissionState.showRationaleDialog)
        assertTrue(permissionState.currentRationalePermissions.isEmpty())
    }

    @Test
    fun emptyPermissionSet_neverPrompts() {
        val host = rule.activity
        var captured: PermissionState? = null

        // 这里用应用 Context 组合、靠 LocalActivity 提供宿主：同时覆盖 LocalActivity 解析分支。
        composeInHost(context = host.applicationContext, localActivity = host) {
            val permissionState = rememberPermissionState(emptySet())
            SideEffect { captured = permissionState }
            Column {
                Text("empty-set-host", Modifier.testTag(HOST_TAG))
                Button(
                    onClick = permissionState::requestPermissions,
                    modifier = Modifier.testTag(REQUEST_TAG),
                ) { Text("request") }
            }
        }

        rule.onNodeWithTag(HOST_TAG).assertIsDisplayed()
        rule.waitForIdle()

        val permissionState = requireNotNull(captured)
        assertTrue("空集合视为全部满足", permissionState.allPermissionsGranted)
        assertTrue(permissionState.allRequiredPermissionsGranted)
        assertTrue(permissionState.deniedPermissions.isEmpty())
        assertTrue("LocalActivity 提供宿主时应该可以请求", permissionState.canRequestPermissions)

        rule.onNodeWithTag(REQUEST_TAG).performClick()
        rule.waitForIdle()

        assertTrue("没有权限项就不该发起任何请求", launches.isEmpty())
        assertFalse(permissionState.showRationaleDialog)
        assertTrue(permissionState.getPermissionResults().results.isEmpty())
    }

    private fun composeInHost(
        context: Context,
        localActivity: ComponentActivity?,
        content: @Composable () -> Unit,
    ) {
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalActivity provides localActivity,
                LocalActivityResultRegistryOwner provides injectedRegistryOwner,
            ) {
                MaterialExpressiveTheme {
                    content()
                }
            }
        }
    }

    private companion object {
        const val HOST_TAG = "r13-permission-host"
        const val REQUEST_TAG = "r13-permission-request"

        /**
         * 测试专用权限名：既没在清单里声明，也不存在于系统中，`checkSelfPermission` 恒为 DENIED，
         * 因此“未请求 → 发起请求”这段路径在任何设备/模拟器上都可重复。
         */
        const val SYNTHETIC_PERMISSION = "me.rerere.rikkahub.permission.R13_REGRESSION_NEVER_GRANTED"

        val SyntheticPermission = PermissionInfo(
            permission = SYNTHETIC_PERMISSION,
            displayName = { Text("r13 test permission") },
            usage = { Text("fixture") },
            required = true,
        )
    }
}

package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真实 "所有文件访问" (MANAGE_EXTERNAL_STORAGE) app-op 测试。
 *
 * 历史失败: 在 Instrumentation 进程里用 UiAutomation 改**自己**的 app-op, 日志直接
 * `INSTRUMENTATION_RESULT: shortMsg=Process crashed`。存储 app-op 变化时系统会结束 app 进程,
 * 同进程内既改授权又断言, 永远拿不到结论, 也分不清是产品崩溃还是系统重启进程。
 *
 * 所以改成**外部 ADB 分三段**, 测试进程内不再改任何授权, 只断言当前状态下的行为。
 * 三段顺序执行; 后一段读前一段持久化下来的挂载记录 (noBackup 里的 workspace-access/<root>.json),
 * 于是 "撤回后 fail-closed" 是真的跨进程重启验证, 不是同一个内存对象。
 *
 * ```
 * APP=me.rerere.rikkahub.fork          # 以 targetContext.packageName 为准
 * TEST=me.rerere.rikkahub.fork.test
 * RUN=androidx.test.runner.AndroidJUnitRunner
 * CLS=me.rerere.rikkahub.data.workspace.WorkspaceStorageGrantAndroidTest
 * ADB="adb -s <serial>"
 *
 * # 先记下原状态, 最后恢复: $ADB shell appops get $APP MANAGE_EXTERNAL_STORAGE
 * # 1) 未授权
 * $ADB shell am force-stop $APP
 * $ADB shell appops set $APP MANAGE_EXTERNAL_STORAGE default
 * $ADB shell am instrument -w -e storagePhase denied  -e class $CLS#deniedPhaseRejectsSharedStorageMountWithoutAllFilesAccess $TEST/$RUN
 * # 2) 授权后重新启动测试
 * $ADB shell am force-stop $APP
 * $ADB shell appops set $APP MANAGE_EXTERNAL_STORAGE allow
 * $ADB shell am instrument -w -e storagePhase granted -e class $CLS#grantedPhaseMountsTheRealSharedFolder $TEST/$RUN
 * # 3) 撤回后重新启动测试
 * $ADB shell am force-stop $APP
 * $ADB shell appops set $APP MANAGE_EXTERNAL_STORAGE default
 * $ADB shell am instrument -w -e storagePhase revoked -e class $CLS#revokedPhaseFailsClosedAcrossRestartAndKeepsData $TEST/$RUN
 * # 4) 恢复该设备原来的 app-op
 * $ADB shell appops set $APP MANAGE_EXTERNAL_STORAGE <原值>
 * ```
 *
 * 直接跑整套测试 (不带 -e storagePhase) 时, 这三个用例会以 "缺少外部阶段参数" 被 assume 跳过,
 * 不会伪造成通过; 而在对应阶段里执行的断言全是硬断言。
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceStorageGrantAndroidTest {

    @Test
    fun deniedPhaseRejectsSharedStorageMountWithoutAllFilesAccess() = runBlocking {
        requirePhase("denied", granted = false)
        withHarness { _, access, manager ->
            // 从干净状态开始, 让下一段只验证自己的行为
            access.clear(ROOT)
            assertFalse(access.storageAccessGranted())
            assertTrue(access.get(ROOT).mounts.isEmpty())

            val denied = runCatching { access.addMount(ROOT, SOURCE, TARGET) }.exceptionOrNull()
            assertNotNull("addMount must be rejected without all-files access", denied)
            assertTrue(
                "expected the shared-storage consent check to reject it, got: $denied",
                denied is IllegalStateException && denied.message.orEmpty().contains("authorized"),
            )
            assertTrue("a rejected mount must not be persisted", access.get(ROOT).mounts.isEmpty())
            assertFalse("no mount means no writable $TARGET", manager.layout(ROOT).isWritable("$TARGET/probe.txt"))
        }
    }

    @Test
    fun grantedPhaseMountsTheRealSharedFolder() = runBlocking {
        requirePhase("granted", granted = true)
        withHarness { _, access, manager ->
            assertTrue(access.storageAccessGranted())
            access.clear(ROOT)
            shell("mkdir -p $SOURCE; printf shared-probe > $SOURCE/probe.txt")
            access.addMount(ROOT, SOURCE, TARGET)

            val spec = access.get(ROOT).mounts.single()
            val canonical = WorkspaceStoragePaths.source(SOURCE)
            assertEquals(canonical.path, File(spec.sourcePath).canonicalPath)
            assertEquals(TARGET, spec.targetPath)
            // 这条断言保证下面测的是真的共享存储授权, 而不是 app 自己的外部目录
            assertTrue(canonical.path.startsWith("/storage/"))

            val binding = access.localAccess(ROOT).bindMounts.single()
            assertEquals(canonical.path, binding.source.canonicalPath)
            assertTrue("a granted all-files app must be able to write the mount source", binding.writable)
            assertEquals("shared-probe", binding.source.resolve("probe.txt").readText())

            // 生产接线是 manager.localAccess = access::localAccess: 文件工具与 shell 必须拿到同一张表
            val resolved = manager.resolveRootfsFile(ROOT, "$TARGET/probe.txt")
            assertTrue("$TARGET/probe.txt must resolve into the Android folder: ${resolved.absolutePath}", resolved.isFile)
            assertEquals("shared-probe", resolved.readText())
            assertEquals(listOf("shared-probe"), manager.readRootfsLines(ROOT, "$TARGET/probe.txt").lines)
            assertTrue(manager.layout(ROOT).isWritable("$TARGET/probe.txt"))
            // 跨工作区不共享
            assertFalse(manager.resolveRootfsFile(OTHER, "$TARGET/probe.txt").exists())
        }
    }

    @Test
    fun revokedPhaseFailsClosedAcrossRestartAndKeepsData() = runBlocking {
        requirePhase("revoked", granted = false)
        withHarness { context, access, manager ->
            assertFalse(access.storageAccessGranted())
            val spec = access.get(ROOT).mounts.singleOrNull()
            assertNotNull(
                "run the granted phase first: no persisted mount for $ROOT at ${accessStorePath(context)}",
                spec,
            )
            // 授权已撤回但挂载记录还在: 必须显式失败, 不能返回一个假可用的映射
            val unavailable = runCatching { access.localAccess(ROOT) }.exceptionOrNull()
            assertNotNull("a persisted mount must fail closed after revocation", unavailable)
            assertTrue(
                "expected the storage-consent error, got: $unavailable",
                unavailable is IllegalStateException && unavailable.message.orEmpty().contains("authorized"),
            )
            assertNotNull(
                "file tools must not resolve a revoked mount",
                runCatching { manager.resolveRootfsFile(ROOT, "$TARGET/probe.txt") }.exceptionOrNull(),
            )
            assertNotNull(
                "layout must not report a revoked mount as writable",
                runCatching { manager.layout(ROOT).isWritable("$TARGET/probe.txt") }.exceptionOrNull(),
            )

            // 撤回期间仍允许移除记录, 且移除不删除 Android 侧数据
            access.removeMount(ROOT, access.get(ROOT).mounts.single().id)
            assertTrue(access.get(ROOT).mounts.isEmpty())
            assertEquals("shared-probe", shell("cat $SOURCE/probe.txt").trim())

            access.clear(ROOT)
            shell("rm -rf $SOURCE")
            assertEquals("", shell("ls $SOURCE 2>/dev/null").trim())
        }
    }

    private fun requirePhase(phase: String, granted: Boolean) {
        assumeTrue("MANAGE_EXTERNAL_STORAGE needs Android 11+", Build.VERSION.SDK_INT >= 30)
        val requested = InstrumentationRegistry.getArguments().getString(STORAGE_PHASE_ARG).orEmpty()
        assumeTrue(
            "external phase argument missing; run with -e $STORAGE_PHASE_ARG $phase " +
                "(see class KDoc for the adb appops sequence)",
            requested.isNotEmpty(),
        )
        assertEquals("this test must be run with -e $STORAGE_PHASE_ARG $phase", phase, requested)
        // 状态由外部 ADB 设定; 与阶段不符属于环境错误, 不能把错误状态当功能通过
        assertEquals(
            "MANAGE_EXTERNAL_STORAGE state does not match phase $phase; set it with: " +
                "adb shell appops set <app-pkg> MANAGE_EXTERNAL_STORAGE ${if (granted) "allow" else "default"}",
            granted,
            Environment.isExternalStorageManager(),
        )
    }

    private suspend fun withHarness(
        block: suspend (Context, WorkspaceAccessManager, WorkspaceManager) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val privileges = WorkspacePrivilegeManager(context)
        val access = WorkspaceAccessManager(context, privileges)
        val manager = WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(File(context.applicationInfo.nativeLibraryDir)),
            localAccess = access::localAccess,
        )
        try {
            block(context, access, manager)
        } finally {
            privileges.closeAndJoin()
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("/system/bin/sh -c '${command.replace("'", "'\\\"'\\\"'")}'"),
    ).bufferedReader().use { it.readText() }

    private fun accessStorePath(context: Context): String =
        File(context.noBackupFilesDir, "workspace-access/$ROOT.json").absolutePath

    private companion object {
        const val STORAGE_PHASE_ARG = "storagePhase"

        /** 稳定的 root 名: 三段测试跨进程共享同一条持久化记录 */
        const val ROOT = "storage-grant-phase"
        const val OTHER = "storage-grant-other"
        const val SOURCE = "/sdcard/Download/rikkahub-storage-phase"
        const val TARGET = "/mnt/phone"
    }
}

package me.rerere.rikkahub.data.workspace

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceLocalAccess
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * 真实 PRoot 动态挂载测试, 不下载 Ubuntu 镜像。
 *
 * 分成两个用例, 让失败可以归因, 而不是把 "空 stdout" 混在一起:
 *
 * 1. [fileToolsFollowTheSameDynamicMountAndFailClosedWhenItIsRemoved] 完全不启动 PRoot,
 *    只用真实 Android 目录验证挂载表/路径解析/撤销后 fail-closed。它跑得过就说明产品侧的
 *    "同一张挂载表" 接线没问题。
 * 2. [fileToolsAndRealShellUseTheSameDynamicMount] 启动真实 PRoot, 按阶段留证据:
 *    - stage 0: proot 二进制与最小 Rootfs 清点 (能否 exec、大小、权限、拷贝来源);
 *    - stage 1: 同一条命令交给 Android 自带 /system/bin/sh, 证明命令文本本身没问题;
 *    - stage 2: PRoot 里跑 `/bin/sh -c 'printf from-shell'` (不涉及挂载), 证明 PRoot 能执行
 *      这个用 Android bionic 二进制拼出来的最小 Rootfs;
 *    - stage 3: 真实挂载写入 + 双端读回, 仍然用严格断言。
 *
 * stage 2 若在默认模式下拿到 "exit=0 + stdout 空 + stderr 空" (这正是历史失败现场),
 * 会再用 shellCompatibilityMode (PROOT_NO_SECCOMP=1) 重跑一次并保留两份证据。慢一档但结论明确:
 * 要么兼容模式能跑通 (记录该设备需要 PROOT_NO_SECCOMP), 要么两次都失败, 断言里带全部证据。
 * 任何一步都不会因为 "没有输出" 就静默算通过: 断言前先要求 stdout/stderr 至少有一个非空。
 *
 * 合成 Rootfs 只是把 Android 的 bionic sh/toybox 塞进 PRoot, 它能验证接线, 但不等于真实发行版。
 * 要验证真正可用的挂载, 传一个已解包的真实 Linux Rootfs 目录进来:
 *
 * ```
 * adb shell am instrument -w -e class ...#fileToolsAndRealShellUseTheSameDynamicMount \
 *   -e realRootfs /data/data/<app-pkg>/files/workspaces/<root>/linux <test-pkg>/androidx.test.runner.AndroidJUnitRunner
 * adb shell run-as <app-pkg> cat cache/workspace-mount-evidence.txt   # 失败时的完整证据
 * adb logcat -d -s WorkspaceMountTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceMountAndroidTest {

    @Test
    fun fileToolsFollowTheSameDynamicMountAndFailClosedWhenItIsRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDir = File(context.cacheDir, "mount-map-${UUID.randomUUID()}").apply { mkdirs() }
        var enabled = true
        try {
            val source = File(testDir, "phone-folder").apply { mkdirs() }
            val manager = newManager(testDir, File(context.applicationInfo.nativeLibraryDir)) { enabled }
            manager.ensureWorkspace(ROOT)
            // 等价于 shell 里写进 /mnt/phone/probe 之后 Android 侧的样子
            source.resolve("probe").writeText("from-shell")

            assertEquals("from-shell", manager.resolveRootfsFile(ROOT, "/mnt/phone/probe").readText())
            assertEquals(listOf("from-shell"), manager.readRootfsLines(ROOT, "/mnt/phone/probe").lines)
            assertTrue(manager.layout(ROOT).isWritable("/mnt/phone/probe"))
            // 信任模式只放宽该 root 的 Rootfs 写入审批, 不代表 root 权限
            assertTrue(manager.layout(ROOT).isWritable("/etc/hostname"))
            assertFalse(manager.layout(OTHER).isWritable("/etc/hostname"))
            // 跨工作区不共享挂载
            assertFalse(manager.resolveRootfsFile(OTHER, "/mnt/phone/probe").exists())

            enabled = false
            // 撤销后同一个 Rootfs 路径不再指向 Android 目录, 也不会假报还有文件
            assertFalse(manager.resolveRootfsFile(ROOT, "/mnt/phone/probe").exists())
            // 信任根只放宽审批 (writableRoots 含 "/"), 所以这里用非信任 root 断言挂载确实从表里消失
            assertFalse(manager.layout(OTHER).isWritable("/mnt/phone/probe"))
            // 移除映射不删除原目录数据
            assertTrue(source.resolve("probe").exists())
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun fileToolsAndRealShellUseTheSameDynamicMount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDir = File(context.cacheDir, "mount-test-${UUID.randomUUID()}").apply { mkdirs() }
        val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
        val evidence = StringBuilder()
        var enabled = true
        try {
            val source = File(testDir, "phone-folder").apply { mkdirs() }
            val manager = newManager(testDir, nativeLibraryDir) { enabled }
            manager.ensureWorkspace(ROOT)
            val linux = manager.linuxDir(ROOT)
            val realRootfs = prepareRootfs(linux, evidence)
            val cat = if (realRootfs) "cat" else "/system/bin/cat"
            assertTrue(manager.hasRootfs(ROOT))

            // stage 0: proot 二进制与最小 Rootfs 清点
            listOf("libproot_exec.so", "libproot_loader.so").forEach { name ->
                val file = File(nativeLibraryDir, name)
                evidence.appendLine("$name exists=${file.isFile} size=${file.length()} canExecute=${file.canExecute()}")
                assertTrue("$name missing in ${nativeLibraryDir.absolutePath}: $evidence", file.isFile)
            }
            evidence.appendLine("rootfs bin/sh size=${File(linux, "bin/sh").length()} exec=${File(linux, "bin/sh").canExecute()}")
            evidence.appendLine("rootfs usr/bin/env size=${File(linux, "usr/bin/env").length()} exec=${File(linux, "usr/bin/env").canExecute()}")
            evidence.appendLine("proot --version => ${prootVersion(nativeLibraryDir)}")

            // stage 1: 同一条命令交给 Android 自带 shell, 排除 "命令文本本身有问题"
            val native = runNative("/system/bin/sh", "printf 'from-shell'")
            evidence.appendLine("android shell result: ${describe(native)}")
            assertEquals("Android /system/bin/sh cannot print the probe text: $evidence", "from-shell", native.stdout.trim())

            // stage 2: PRoot 执行最小 Rootfs 的 /bin/sh (不涉及挂载)
            val preflight = executeWithEvidence(manager, "printf 'from-shell'", evidence)
            assertEquals(
                "PRoot could not run the fixture /bin/sh; stage 0/1/2 evidence:\n$evidence",
                "from-shell",
                preflight.stdout.trim(),
            )

            // stage 3: 真实挂载写读
            val command = executeWithEvidence(
                manager,
                "printf 'from-shell' > /mnt/phone/probe; $cat /mnt/phone/probe",
                evidence,
            )
            assertEquals(command.stderr, 0, command.exitCode)
            assertEquals("shell stdout mismatch; evidence:\n$evidence", "from-shell", command.stdout.trim())
            // Android 侧必须收到同样的字节, 不是写进了 Rootfs 里的另一个空目录
            val probe = source.resolve("probe")
            assertTrue("mount did not write through to the Android folder; evidence:\n$evidence", probe.isFile)
            assertEquals("from-shell", probe.readText())
            assertEquals(10L, probe.length())
            // 文件工具与 shell 用同一张挂载表, 读到同一份内容
            assertEquals(listOf("from-shell"), manager.readRootfsLines(ROOT, "/mnt/phone/probe").lines)
            assertFalse(manager.resolveRootfsFile(OTHER, "/mnt/phone/probe").exists())
            assertTrue(manager.layout(ROOT).isWritable("/etc/hostname"))
            assertFalse(manager.layout(OTHER).isWritable("/etc/hostname"))

            enabled = false
            assertFalse(manager.resolveRootfsFile(ROOT, "/mnt/phone/probe").exists())
            val revoked = executeWithEvidence(
                manager,
                "test ! -e /mnt/phone/probe && printf cleared",
                evidence,
            )
            assertEquals(revoked.stderr, 0, revoked.exitCode)
            assertEquals("revoked mount still visible inside Rootfs; evidence:\n$evidence", "cleared", revoked.stdout.trim())
            assertTrue(source.resolve("probe").exists())
        } finally {
            writeEvidence(context, "workspace-mount-evidence.txt", evidence)
            // 真实 Rootfs 是用软链接进来的, 先拆链接再删临时目录, 别顺着链接删到真实 Rootfs 里
            runCatching {
                val link = File(testDir, "workspaces/$ROOT/linux")
                if (Files.isSymbolicLink(link.toPath())) Files.deleteIfExists(link.toPath())
            }
            testDir.deleteRecursively()
        }
    }

    /**
     * 默认用 Android 自带 sh/toybox 拼一个最小 Rootfs, 不下载镜像。
     * 传 `-e realRootfs /abs/path` (目录里要有 bin/sh) 时改用真实 Linux Rootfs, 用来验证真正可用的
     * PRoot 挂载; 该目录会被 RootfsPatcher 按正常运行时那样补 /etc 的 DNS/hosts/group, 建议用副本。
     */
    private fun prepareRootfs(linux: File, evidence: StringBuilder): Boolean {
        val provided = InstrumentationRegistry.getArguments().getString(REAL_ROOTFS_ARG).orEmpty()
        if (provided.isNotEmpty()) {
            val dir = File(provided)
            assertTrue(
                "$REAL_ROOTFS_ARG=$provided is not a usable rootfs (needs bin/sh)",
                WorkspaceManager.isUsableRootfs(dir),
            )
            if (linux.exists()) linux.deleteRecursively()
            Files.createSymbolicLink(linux.toPath(), dir.toPath())
            evidence.appendLine("rootfs: real $provided (RootfsPatcher patches /etc like normal runtime)")
            return true
        }
        listOf("bin", "usr/bin", "etc", "tmp", "dev", "proc", "sys", "system", "apex", "mnt").forEach {
            File(linux, it).mkdirs()
        }
        File("/system/bin/sh").copyTo(File(linux, "bin/sh"), overwrite = true).setExecutable(true)
        File("/system/bin/toybox").copyTo(File(linux, "usr/bin/env"), overwrite = true).setExecutable(true)
        evidence.appendLine("rootfs: synthetic (Android /system/bin/sh + toybox as /usr/bin/env)")
        return false
    }

    /**
     * 先跑默认模式; 只有 "exit=0 且 stdout/stderr 全空" 这种无法归因的结果才用 PROOT_NO_SECCOMP 重跑,
     * 并把两份结果写进证据。重跑成功也照实记录该设备需要兼容模式。
     */
    private fun executeWithEvidence(
        manager: WorkspaceManager,
        command: String,
        evidence: StringBuilder,
    ): WorkspaceCommandResult {
        val primary = manager.executeCommand(ROOT, command, timeoutMillis = 30_000,
            shellCompatibilityMode = InstrumentationRegistry.getArguments().getString("mountCompatibility") == "true")
        evidence.appendLine("default: ${describe(primary)}")
        val silent = primary.stdout.isBlank() && primary.stderr.isBlank() && !primary.timedOut
        if (!silent) return primary
        val compatibility = manager.executeCommand(ROOT, command, timeoutMillis = 30_000, shellCompatibilityMode = true)
        evidence.appendLine("silent in default mode; PROOT_NO_SECCOMP retry: ${describe(compatibility)}")
        return compatibility
    }

    private fun newManager(
        testDir: File,
        nativeLibraryDir: File,
        enabled: () -> Boolean,
    ): WorkspaceManager = WorkspaceManager(
        baseDir = File(testDir, "workspaces"),
        shellRunner = ProotShellRunner(nativeLibraryDir),
        bindMounts = listOf(
            WorkspaceBindMount(File("/system"), "/system"),
            WorkspaceBindMount(File("/apex"), "/apex"),
        ),
        localAccess = { root ->
            WorkspaceLocalAccess(
                bindMounts = if (enabled() && root == ROOT) {
                    listOf(WorkspaceBindMount(File(testDir, "phone-folder"), "/mnt/phone", true))
                } else {
                    emptyList()
                },
                trustedRootfs = root == ROOT,
            )
        },
    )

    private fun runNative(shell: String, command: String): WorkspaceCommandResult = try {
        val process = ProcessBuilder(shell, "-c", command).redirectErrorStream(false).start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        WorkspaceCommandResult(exitCode = exit, stdout = out, stderr = err)
    } catch (error: Exception) {
        WorkspaceCommandResult(exitCode = -127, stdout = "", stderr = error.toString())
    }

    private fun prootVersion(nativeLibraryDir: File): String = try {
        val process = ProcessBuilder(File(nativeLibraryDir, "libproot_exec.so").absolutePath, "--version")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim().lineSequence().take(3).joinToString(" | ")
        val exit = process.waitFor()
        "exit=$exit ${text.ifBlank { "<no output>" }}"
    } catch (error: Exception) {
        "failed: $error"
    }

    private fun describe(result: WorkspaceCommandResult): String =
        "exit=${result.exitCode} timedOut=${result.timedOut} truncated=${result.truncated} " +
            "stdout=${quoted(result.stdout)} stderr=${quoted(result.stderr)}"

    private fun quoted(text: String): String = "\"${text.replace("\n", "\\n").take(400)}\"(len=${text.length})"

    private fun writeEvidence(context: android.content.Context, name: String, evidence: StringBuilder) {
        Log.i(TAG, evidence.toString())
        runCatching { File(context.cacheDir, name).writeText(evidence.toString()) }
    }

    private companion object {
        const val TAG = "WorkspaceMountTest"
        const val ROOT = "a"
        const val OTHER = "b"

        /** `-e realRootfs /abs/path`: 用真实 Linux Rootfs 目录跑端到端挂载, 而不是合成的最小 Rootfs */
        const val REAL_ROOTFS_ARG = "realRootfs"
    }
}

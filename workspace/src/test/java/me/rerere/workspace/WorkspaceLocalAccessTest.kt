package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 动态挂载的纯 JVM 测试: 路径解析/搜索/读写走同一张表、shell 与终端拿到同一份 mounts、
 * 信任根不跨 root、非法 target 与碰撞被拒、静态挂载保持原样、来源不可访问时两边都不当已挂载。
 */
class WorkspaceLocalAccessTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val root = "root-a"
    private val otherRoot = "root-b"

    private fun manager(
        localAccess: (String) -> WorkspaceLocalAccess = { WorkspaceLocalAccess() },
        shellRunner: WorkspaceShellRunner = HostShellRunner(),
        bindMounts: List<WorkspaceBindMount> = emptyList(),
    ): WorkspaceManager = WorkspaceManager(
        baseDir = temp.newFolder(),
        shellRunner = shellRunner,
        bindMounts = bindMounts,
        localAccess = localAccess,
    ).also { it.ensureWorkspace(root) }

    private fun dynamicMount(
        source: File,
        target: String = "/mnt/ext",
        writable: Boolean = false,
    ): WorkspaceLocalAccess = WorkspaceLocalAccess(
        bindMounts = listOf(WorkspaceBindMount(source = source, target = target, writable = writable)),
    )

    // --- 文件工具: 可读、可搜索 ------------------------------------------------

    @Test
    fun dynamicMountIsReadableAndSearchable() {
        val host = temp.newFolder("ext")
        File(host, "a.md").writeText("needle in a\n")
        File(host, "sub").mkdirs()
        File(host, "sub/b.md").writeText("needle in b\n")
        File(host, "sub/c.txt").writeText("nothing\n")
        val manager = manager({ dynamicMount(host) })

        assertEquals(File(host, "a.md").length(), manager.rootfsFileSize(root, "/mnt/ext/a.md"))
        val buffer = ByteArrayOutputStream()
        manager.exportRootfsFile(root, "/mnt/ext/a.md", buffer)
        assertEquals("needle in a\n", buffer.toString(Charsets.UTF_8.name()))

        val grep = manager.grepRootfs(root, "/mnt/ext", WorkspaceGrepOptions(pattern = "needle"))
        assertEquals(listOf("/mnt/ext/a.md", "/mnt/ext/sub/b.md"), grep.files.map { it.path }.sorted())

        val glob = manager.globRootfs(root, "/mnt/ext", WorkspaceGlobOptions(pattern = "**/*.md"))
        assertEquals(listOf("/mnt/ext/a.md", "/mnt/ext/sub/b.md"), glob.entries.map { it.path }.sorted())

        // 行读取也走同一张表
        assertEquals(listOf("needle in b"), manager.readRootfsLines(root, "/mnt/ext/sub/b.md").lines)

        // backend 的绝对路径解析同样命中动态挂载
        val backend = manager.backend(root)
        assertEquals(File(host, "a.md"), backend.localAbsoluteFile("/mnt/ext/a.md"))
        assertEquals(manager.layout(root), backend.layout)
    }

    // --- shell / 后台 / job / 终端拿到同一份 mounts ------------------------------

    @Test
    fun shellTerminalAndJobsShareTheSameMountTable() {
        val host = temp.newFolder("ext")
        val skills = temp.newFolder("skills")
        val runner = RecordingShellRunner()
        val manager = manager(
            localAccess = { dynamicMount(host, writable = true) },
            shellRunner = runner,
            bindMounts = listOf(WorkspaceBindMount(source = skills, target = "/skills", writable = true)),
        )
        val expected = listOf(
            WorkspaceBindMount(source = skills, target = "/skills", writable = true),
            WorkspaceBindMount(source = host, target = "/mnt/ext", writable = true),
        )

        manager.executeCommand(root, "ls")
        manager.startBackgroundCommand(root, "sleep 1", "", "job-1")
        manager.terminalLaunch(root, shellCompatibilityMode = false)
        WorkspaceJobManager(manager).start(root, "echo hi")

        assertEquals(expected, runner.executed.single().bindMounts)
        assertEquals(expected, runner.backgrounded.single().bindMounts)
        assertEquals(expected, runner.terminals.single().bindMounts)
        assertEquals(expected, runner.started.single().bindMounts)
    }

    @Test
    fun prootTerminalGetsDynamicMountsButNotUnreachableOnes() {
        val nativeLibraryDir = temp.newFolder("native")
        val present = temp.newFolder("present")
        val missing = File(temp.root, "missing-source")
        val manager = manager(
            localAccess = {
                WorkspaceLocalAccess(
                    bindMounts = listOf(
                        WorkspaceBindMount(source = present, target = "/mnt/present"),
                        WorkspaceBindMount(source = missing, target = "/mnt/missing"),
                    )
                )
            },
            shellRunner = ProotShellRunner(nativeLibraryDir),
        )

        val launch = manager.terminalLaunch(root, shellCompatibilityMode = false)!!

        assertTrue("${present.absolutePath}:/mnt/present" in launch.args)
        // 来源不可访问就不声明为已挂载, 和 PRoot 只挂存在的 source 一致
        assertFalse(launch.args.any { it.contains("/mnt/missing") })
        assertFalse(missing.exists())
    }

    // --- 跨 root 隔离与信任根 --------------------------------------------------

    @Test
    fun trustedRootfsOnlyAppliesToItsOwnRoot() {
        val host = temp.newFolder("ext")
        val manager = manager({ candidate ->
            if (candidate == root) {
                WorkspaceLocalAccess(bindMounts = dynamicMount(host).bindMounts, trustedRootfs = true)
            } else {
                WorkspaceLocalAccess()
            }
        })

        val trusted = manager.layout(root)
        assertTrue(trusted.writableRoots.contains("/"))
        assertTrue(trusted.isPathWritable("/etc/hostname"))
        assertTrue(trusted.isWritable("/etc/hostname"))
        assertTrue(trusted.isPathWritable("/workspace/a.txt"))

        val untrusted = manager.layout(otherRoot)
        assertFalse(untrusted.writableRoots.contains("/"))
        assertFalse(untrusted.isPathWritable("/etc/hostname"))
        assertTrue(untrusted.isPathWritable("/workspace/a.txt"))

        // 旧的无参 layout 不掺任何 root 的信任状态
        assertFalse(manager.layout.writableRoots.contains("/"))
        assertFalse(manager.layout.isPathWritable("/etc/hostname"))
    }

    @Test
    fun dynamicMountsDoNotLeakAcrossRoots() {
        val host = temp.newFolder("ext")
        File(host, "a.md").writeText("shared")
        val manager = manager({ candidate ->
            if (candidate == root) dynamicMount(host) else WorkspaceLocalAccess()
        })

        assertEquals(File(host, "a.md"), manager.resolveRootfsFile(root, "/mnt/ext/a.md"))
        // 另一个 root 没有这条挂载: 回落到它自己的 Rootfs 内部, 不会串到 root-a 的挂载
        val otherLocation = manager.resolveRootfsPath(otherRoot, "/mnt/ext/a.md")
        assertEquals(manager.linuxDir(otherRoot), otherLocation.rootDir)
        assertEquals("mnt/ext/a.md", otherLocation.relativePath)
        assertFalse(File(manager.linuxDir(otherRoot), "mnt/ext/a.md").exists())

        // 也不在别的工作区里创建数据
        assertFalse(File(manager.workspaceDir(otherRoot), "mnt").exists())
        assertFalse(manager.layout(otherRoot).writableRoots.contains("/mnt/ext"))
    }

    // --- 静态挂载保留 ----------------------------------------------------------

    @Test
    fun staticMountsKeepWorkingAlongsideDynamicOnes() {
        val skills = temp.newFolder("skills")
        val host = temp.newFolder("ext")
        File(skills, "demo.md").writeText("skill")
        File(host, "note.md").writeText("host")
        val manager = manager(
            localAccess = { dynamicMount(host, writable = true) },
            bindMounts = listOf(WorkspaceBindMount(source = skills, target = "/skills", writable = true)),
        )

        // 兼容旧 API: 不带 root 的 layout 只有静态挂载
        assertEquals(listOf("/workspace", "/tmp", "/skills"), manager.layout.writableRoots)
        // 按 root 的 layout 里静态 + 动态都在
        assertEquals(listOf("/workspace", "/tmp", "/skills", "/mnt/ext"), manager.layout(root).writableRoots)

        val skillsBuffer = ByteArrayOutputStream()
        manager.exportRootfsFile(root, "/skills/demo.md", skillsBuffer)
        assertEquals("skill", skillsBuffer.toString(Charsets.UTF_8.name()))

        val hostBuffer = ByteArrayOutputStream()
        manager.exportRootfsFile(root, "/mnt/ext/note.md", hostBuffer)
        assertEquals("host", hostBuffer.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun longerDynamicTargetWinsOverShorterOne() {
        val outer = temp.newFolder("outer")
        val inner = temp.newFolder("inner")
        File(inner, "x.md").writeText("inner")
        File(outer, "y.md").writeText("outer")
        val manager = manager({
            WorkspaceLocalAccess(
                bindMounts = listOf(
                    WorkspaceBindMount(source = outer, target = "/mnt/other"),
                    WorkspaceBindMount(source = inner, target = "/mnt/other/ext"),
                )
            )
        })

        assertEquals(File(inner, "x.md"), manager.resolveRootfsFile(root, "/mnt/other/ext/x.md"))
        assertEquals(File(outer, "y.md"), manager.resolveRootfsFile(root, "/mnt/other/y.md"))
    }

    // --- 非法 target 与碰撞 ----------------------------------------------------

    @Test
    fun invalidTargetsAreRejected() {
        val host = temp.newFolder("ext")
        val invalid = listOf(
            "mnt/ext",              // 非绝对路径
            "/mnt/ex\u0000t",       // NUL
            "/mnt/ex\nt",           // 换行
            "/mnt/ext:ro",          // PRoot -b 的分隔符
            "/mnt/../etc",          // ..
            "//",                   // 落到 "/"
            "/",                    // 覆盖整个 Rootfs
            "/workspace",           // 保留映射
            "/workspace/sub",       // 保留映射的子路径
            "/skills",              // 保留映射
            "/upload/x",            // 保留映射的子路径
            "/proc",                // 内核伪文件系统
            "/sys/kernel",          // 内核伪文件系统
            "/dev",                 // 内核伪文件系统
        )
        invalid.forEach { target ->
            val manager = manager({ dynamicMount(host, target) })
            // 构造不失败, 真正用到挂载表时才拒绝, 免得一条坏配置让整个工作区打不开
            assertThrows("target=$target", IllegalArgumentException::class.java) {
                manager.layout(root)
            }
        }
    }

    @Test
    fun duplicateAndStaticCollidingTargetsAreRejected() {
        val first = temp.newFolder("first")
        val second = temp.newFolder("second")
        val duplicated = manager({
            WorkspaceLocalAccess(
                bindMounts = listOf(
                    WorkspaceBindMount(source = first, target = "/mnt/ext"),
                    WorkspaceBindMount(source = second, target = "/mnt/ext/"),
                )
            )
        })
        assertThrows(IllegalArgumentException::class.java) { duplicated.layout(root) }

        val colliding = manager(
            localAccess = { dynamicMount(second, "/mnt/ext") },
            bindMounts = listOf(WorkspaceBindMount(source = first, target = "/mnt/ext")),
        )
        assertThrows(IllegalArgumentException::class.java) { colliding.layout(root) }

        // Explicit user mounts may nest; the longer target wins.
        val nested = manager({
            WorkspaceLocalAccess(
                bindMounts = listOf(
                    WorkspaceBindMount(source = first, target = "/mnt"),
                    WorkspaceBindMount(source = second, target = "/mnt/ext"),
                )
            )
        })
        assertTrue(nested.layout(root).workspaceDir == "/workspace")
    }

    @Test
    fun relativeOrBlankSourceIsRejected() {
        val relative = manager({ dynamicMount(File("relative/dir")) })
        assertThrows(IllegalArgumentException::class.java) { relative.layout(root) }

        val dot = manager({ dynamicMount(File(".")) })
        assertThrows(IllegalArgumentException::class.java) { dot.layout(root) }
    }

    // --- target 规范化 --------------------------------------------------------

    @Test
    fun targetIsNormalizedToCanonicalAbsolutePath() {
        assertEquals("/mnt/ext", normalizeBindMountTarget("/mnt/ext/"))
        assertEquals("/mnt/ext", normalizeBindMountTarget("/mnt//ext"))
        assertEquals("/mnt/ext", normalizeBindMountTarget("/mnt/./ext"))
        assertEquals("/sdcard", normalizeBindMountTarget("/sdcard"))
        assertEquals("/root", normalizeBindMountTarget("/root"))

        val host = temp.newFolder("ext")
        File(host, "x.md").writeText("x")
        val manager = manager({ dynamicMount(host, "/mnt/./ext/") })
        assertEquals("x", manager.resolveRootfsFile(root, "/mnt/ext/x.md").readText())
    }

    // --- 可信根不越界到宿主 ----------------------------------------------------

    @Test
    fun trustedRootfsDoesNotBypassReservedMappings() {
        val host = temp.newFolder("ext")
        val manager = manager({ dynamicMount(host, writable = true).copy(trustedRootfs = true) })

        // 信任模式只关掉审批, 保留映射与内核伪文件系统的解析规则不变
        assertEquals(manager.filesDir(root), manager.resolveRootfsPath(root, "/workspace/a.txt").rootDir)
        assertThrows(IllegalStateException::class.java) {
            manager.resolveRootfsPath(root, "/proc/version")
        }
        assertEquals(File(host, "a.txt"), manager.resolveRootfsFile(root, "/mnt/ext/a.txt"))
    }

    // --- 测试替身 --------------------------------------------------------------

    private class RecordingShellRunner : WorkspaceShellRunner {
        val executed = mutableListOf<WorkspaceShellContext>()
        val backgrounded = mutableListOf<WorkspaceShellContext>()
        val started = mutableListOf<WorkspaceShellContext>()
        val terminals = mutableListOf<WorkspaceTerminalContext>()

        override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
            executed += context
            return WorkspaceCommandResult(exitCode = 0, stdout = "", stderr = "")
        }

        override fun start(context: WorkspaceShellContext, logFile: File): Process {
            started += context
            return FakeProcess()
        }

        override fun startBackground(context: WorkspaceShellContext, id: String): WorkspaceBackgroundCommand {
            backgrounded += context
            return object : WorkspaceBackgroundCommand {
                override val logPath = "/tmp/rikkahub/background/$id.log"
                override fun waitFor(): Int = 0
                override fun outputSize(): Long = 0L
                override fun readOutput(offset: Long, maxBytes: Int): ByteArray = ByteArray(0)
                override fun kill() = Unit
            }
        }

        override fun terminalLaunch(context: WorkspaceTerminalContext): WorkspaceTerminalLaunch {
            terminals += context
            return WorkspaceTerminalLaunch(
                executable = "proot",
                cwd = "/tmp",
                args = emptyList(),
                env = emptyList(),
            )
        }
    }

    private class FakeProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
    }
}

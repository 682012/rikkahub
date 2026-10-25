package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * WorkspaceFilesExporter 的 JVM 单元测试（`testDebugUnitTest`）。
 *
 * 直接跑真实实现（不是 Java 复刻算法），覆盖 fork.34 审计里 ZIP 导出的四个问题：
 * 排除目录只过滤文件不剪枝、跟随符号链接越界/成环、特殊文件卡死、取消与进度口径。
 */
class WorkspaceFilesExporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `prunes rebuildable directories and job logs instead of walking them`() = runBlocking {
        val root = tmp.newFolder("workspace")
        write(root, "a.txt", "hello")
        write(root, "src/deep/b.py", "print(1)")
        // 这些都应该整棵被剪枝：既不该进 zip，也不该被枚举成文件
        write(root, "node_modules/pkg/big.js", "x".repeat(64 * 1024))
        write(root, ".git/config", "[core]")
        write(root, "src/node_modules/nested.js", "nested")
        write(root, "build/out.bin", "build")
        // .jobs 是 WorkspaceJobManager 的运行日志，含完整命令行，不属于用户数据
        write(root, ".jobs/abc123/job.log", "rm -rf / --secret-command")

        val out = ByteArrayOutputStream()
        val summary = WorkspaceFilesExporter().export(root, out) { _, _, _ -> }

        val names = zipNames(out.toByteArray())
        // 目录也作为条目导出（尾随 /），空目录才能在导入端无损还原
        assertEquals(listOf("a.txt", "src/", "src/deep/", "src/deep/b.py"), names.sorted())
        assertEquals(2, summary.entryCount)
        assertEquals(2, summary.directoryCount)
        assertEquals(5, summary.prunedDirCount)
        assertTrue(summary.prunedDirExamples.contains(".git"))
        assertEquals("hello".length + "print(1)".length.toLong(), summary.byteCount)
    }

    @Test
    fun `does not follow symlinks outside the tree or back into a loop`() = runBlocking {
        val outside = tmp.newFolder("outside")
        write(outside, "secret.key", "TOP-SECRET")
        val root = tmp.newFolder("workspace")
        write(root, "keep.txt", "keep")

        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
        Files.createSymbolicLink(File(root, "secret.link").toPath(), File(outside, "secret.key").toPath())
        Files.createSymbolicLink(File(root, "dangling").toPath(), File(tmp.root, "missing").toPath())

        val out = ByteArrayOutputStream()
        val summary = WorkspaceFilesExporter().export(root, out) { _, _, _ -> }

        val names = zipNames(out.toByteArray())
        assertEquals(listOf("keep.txt"), names)
        assertFalse(names.any { it.startsWith("escape") || it.startsWith("loop") || it.contains("secret") })
        assertEquals(4, summary.skippedSymlinks)
        assertFalse(String(out.toByteArray(), Charsets.ISO_8859_1).contains("TOP-SECRET"))
    }

    @Test
    fun `skips fifo instead of blocking on it`() = runBlocking {
        val mkfifo = listOf("/system/bin/mkfifo", "/usr/bin/mkfifo", "/bin/mkfifo")
            .map(::File).firstOrNull { it.canExecute() }
        assumeTrue("mkfifo unavailable, skipping fifo case", mkfifo != null)

        val root = tmp.newFolder("workspace")
        write(root, "a.txt", "a")
        val fifo = File(root, "pipe")
        assertEquals(0, ProcessBuilder(mkfifo!!.absolutePath, fifo.absolutePath).start().waitFor())

        val out = ByteArrayOutputStream()
        val summary = WorkspaceFilesExporter().export(root, out) { _, _, _ -> }

        assertEquals(listOf("a.txt"), zipNames(out.toByteArray()))
        assertEquals(1, summary.skippedSpecialFiles)
    }

    @Test
    fun `cancellation stops copying inside a large file`() = runBlocking {
        val root = tmp.newFolder("workspace")
        val big = File(root, "big.bin")
        val chunk = ByteArray(64 * 1024).also { java.util.Random(42).nextBytes(it) }
        big.outputStream().use { sink -> repeat(128) { sink.write(chunk) } } // 8 MiB

        var written = 0L
        lateinit var job: Job
        val counter = object : OutputStream() {
            override fun write(b: Int) {
                written++
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                written += len
                // 模拟用户在导出中途离开页面：只按“文件之间”检查取消的老实现会一直写完
                if (written > 1_000_000L) job.cancel()
            }
        }

        job = launch { WorkspaceFilesExporter().export(root, counter) { _, _, _ -> } }
        job.join()

        assertTrue("export coroutine should be cancelled", job.isCancelled)
        assertTrue("wrote $written of ${big.length()} bytes", written < big.length())
    }

    @Test
    fun `cancelled caller writes nothing`() = runBlocking {
        val root = tmp.newFolder("workspace")
        write(root, "a.txt", "hello")
        val out = ByteArrayOutputStream()

        val job = launch { WorkspaceFilesExporter().export(root, out) { _, _, _ -> } }
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(0, out.size())
    }

    @Test
    fun `write failures fail the export instead of returning success`() = runBlocking {
        val root = tmp.newFolder("workspace")
        write(root, "a.txt", "hello")
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
        }

        val error = runCatching {
            WorkspaceFilesExporter().export(root, failing) { _, _, _ -> }
        }.exceptionOrNull()

        assertTrue("expected IOException, got $error", error is IOException)
    }

    @Test
    fun `progress stays within total and ends at total`() = runBlocking {
        val root = tmp.newFolder("workspace")
        write(root, "a.txt", "1234567890")
        write(root, "d/b.txt", "abcdef")
        val reports = ArrayList<Triple<Long, Long, String>>()

        val out = ByteArrayOutputStream()
        val summary = WorkspaceFilesExporter(progressIntervalNanos = 0L)
            .export(root, out) { done, total, current -> reports.add(Triple(done, total, current)) }

        assertEquals(16L, summary.byteCount)
        assertTrue(reports.isNotEmpty())
        assertTrue("done must never exceed total: $reports", reports.all { it.first <= it.second })
        assertEquals(16L, reports.first().second)
        assertEquals(16L, reports.last().first)
        assertEquals(16L, reports.last().second)
        assertTrue(reports.zipWithNext().all { (a, b) -> b.first >= a.first })
    }

    private fun write(base: File, relativePath: String, content: String): File {
        val file = File(base, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    private fun zipNames(bytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                zip.closeEntry()
            }
        }
        return names
    }
}

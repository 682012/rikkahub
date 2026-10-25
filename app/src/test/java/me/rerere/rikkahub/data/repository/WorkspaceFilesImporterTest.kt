package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [WorkspaceFilesImporter] 的 JVM 单元测试（`testDebugUnitTest`）。
 *
 * 全部跑真实实现（真 ZIP 字节、真文件系统），覆盖：合法往返、旧版裸 ZIP（无权限位）、
 * 显式外层包装目录、空目录、取消、失败清理、以及各种恶意/损坏 ZIP（Zip Slip、绝对路径、
 * 反斜杠、控制字符、重复与冲突路径、符号链接、FIFO、超限、CRC 损坏、截断、空包、尺寸撒谎）。
 * 路径只按字节比较（大小写敏感，`A.txt`/`a.txt` 合法），EOCD/ZIP64/UTF-8/取消发布等边界见
 * `WorkspaceImportEdgeCasesTest`。
 */
class WorkspaceFilesImporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------ 合法导入

    @Test
    fun `round trip through the real exporter restores files unicode names and empty dirs`() = runBlocking<Unit> {
        val source = tmp.newFolder()
        writeText(source, "hello.txt", "hello world")
        writeText(source, "数据/中文 名字.md", "中文内容")
        writeText(source, "deep/a/b/c.txt", "nested")
        File(source, "empty/deep").mkdirs()
        val binary = ByteArray(4096) { (it % 251).toByte() }
        File(source, "bin/data.bin").apply { parentFile.mkdirs() }.writeBytes(binary)

        val zip = ByteArrayOutputStream().also { out ->
            WorkspaceFilesExporter().export(source, out)
        }.toByteArray()

        val scratch = newScratch()
        val summary = importInto(zip, scratch)

        assertEquals(4, summary.fileCount)
        assertEquals(binary.size.toLong() + 11L + 12L + 6L, summary.byteCount)
        assertNull(summary.strippedWrapper)
        // 旧导出器不写 Unix 权限位：可执行位不保证，必须如实上报（4 个文件都没权限位）
        assertTrue(summary.permissionsAreGuessed)
        assertEquals(4, summary.entriesWithoutUnixMode)
        assertEquals(0, summary.executableFiles)
        assertFalse(scratch.staging.exists())
        assertEquals("hello world", File(scratch.content, "hello.txt").readText())
        assertEquals("中文内容", File(scratch.content, "数据/中文 名字.md").readText())
        assertEquals("nested", File(scratch.content, "deep/a/b/c.txt").readText())
        assertTrue(File(scratch.content, "bin/data.bin").readBytes().contentEquals(binary))
        assertTrue("空目录必须一起还原", File(scratch.content, "empty/deep").isDirectory)
    }

    @Test
    fun `legacy bare zip without unix modes imports and reports guessed permissions`() = runBlocking<Unit> {
        val zip = legacyZip(
            listOf(
                "a.txt" to "a",
                "sub/b.sh" to "#!/bin/sh\necho hi\n",
            )
        )
        val scratch = newScratch()
        val summary = importInto(zip, scratch)

        assertEquals(2, summary.fileCount)
        assertEquals(2, summary.entriesWithoutUnixMode)
        assertEquals(0, summary.executableFiles)
        assertTrue(summary.permissionsAreGuessed)
        assertEquals("#!/bin/sh\necho hi\n", File(scratch.content, "sub/b.sh").readText())
        // 名字里带 .sh 不等于可执行：绝不按命名猜 chmod
        assertFalse(permissionsOf(File(scratch.content, "sub/b.sh")).contains(PosixFilePermission.OWNER_EXECUTE))
    }

    @Test
    fun `legacy zip keeps empty directory entries`() = runBlocking<Unit> {
        val zip = legacyZip(listOf("empty/" to null, "empty/nested/" to null, "a.txt" to "a"))
        val scratch = newScratch()
        val summary = importInto(zip, scratch)

        assertEquals(1, summary.fileCount)
        assertEquals(2, summary.directoryCount)
        assertTrue(File(scratch.content, "empty").isDirectory)
        assertTrue(File(scratch.content, "empty/nested").isDirectory)
    }

    @Test
    fun `explicit wrapper directory is stripped only when requested`() = runBlocking<Unit> {
        val zip = legacyZip(
            listOf(
                "workspace/" to null,
                "workspace/a.txt" to "a",
                "workspace/sub/b.txt" to "b",
            )
        )

        val plain = newScratch()
        val plainSummary = importInto(zip, plain)
        assertNull(plainSummary.strippedWrapper)
        assertTrue("默认不猜外层包裹", File(plain.content, "workspace/a.txt").isFile)

        val stripped = newScratch()
        val strippedSummary = importInto(
            zip = zip,
            scratch = stripped,
            stripWrapper = WorkspaceFilesImporter.WRAPPER_WORKSPACE,
        )
        assertEquals(WorkspaceFilesImporter.WRAPPER_WORKSPACE, strippedSummary.strippedWrapper)
        assertEquals(2, strippedSummary.fileCount)
        assertEquals("a", File(stripped.content, "a.txt").readText())
        assertEquals("b", File(stripped.content, "sub/b.txt").readText())
        assertFalse(File(stripped.content, "workspace").exists())
    }

    @Test
    fun `explicit wrapper mismatch fails instead of importing something else`() = runBlocking<Unit> {
        val zip = legacyZip(listOf("a.txt" to "a", "files/b.txt" to "b"))
        val scratch = newScratch()
        val error = assertImportFails(zip, scratch, stripWrapper = WorkspaceFilesImporter.WRAPPER_FILES)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("包装目录"))
    }

    @Test
    fun `unix modes restore the executable bit and only that`() = runBlocking<Unit> {
        val zip = RawZip()
            .file("run.sh", "#!/bin/sh\n", UnixMode.mode(UnixMode.REGULAR, UnixMode.PERM_755))
            .file("plain.txt", "text", UnixMode.mode(UnixMode.REGULAR, UnixMode.PERM_644))
            .file("dir/", ByteArray(0), UnixMode.mode(UnixMode.DIRECTORY, UnixMode.PERM_755))
            .build()
        val scratch = newScratch()
        val summary = importInto(zip, scratch)

        assertEquals(2, summary.fileCount)
        assertEquals(1, summary.executableFiles)
        assertEquals(0, summary.entriesWithoutUnixMode)
        assertFalse(summary.permissionsAreGuessed)
        assertTrue(File(scratch.content, "run.sh").isFile)
        val runPerms = permissionsOf(File(scratch.content, "run.sh"))
        if (runPerms.isNotEmpty()) {
            assertTrue(runPerms.contains(PosixFilePermission.OWNER_EXECUTE))
        }
        val plainPerms = permissionsOf(File(scratch.content, "plain.txt"))
        if (plainPerms.isNotEmpty()) {
            assertFalse(plainPerms.contains(PosixFilePermission.OWNER_EXECUTE))
        }
    }

    @Test
    fun `progress is monotonic and ends with the final byte count`() = runBlocking<Unit> {
        val zip = legacyZip(listOf("a.txt" to "0123456789", "d/b.txt" to "abcdef"))
        val reports = ArrayList<Triple<Long, Int, String>>()
        val scratch = newScratch()
        val summary = importInto(
            zip = zip,
            scratch = scratch,
            importer = WorkspaceFilesImporter(progressIntervalNanos = 0L),
            onProgress = { bytes, files, current -> reports.add(Triple(bytes, files, current)) },
        )

        assertEquals(16L, summary.byteCount)
        assertTrue(reports.isNotEmpty())
        assertTrue(reports.zipWithNext().all { (a, b) -> b.first >= a.first })
        assertTrue("bytes must never exceed the final size: $reports", reports.all { it.first <= 16L })
        assertEquals(16L, reports.last().first)
        assertEquals(2, reports.last().second)
        assertEquals("", reports.last().third)
    }

    // ------------------------------------------------------------ 恶意 / 损坏 ZIP

    @Test
    fun `rejects zip slip absolute paths backslashes and control characters`() = runBlocking<Unit> {
        val badNames = listOf(
            "../evil.txt",
            "sub/../../evil.txt",
            "a/../evil.txt",
            "/etc/passwd",
            "C:/Windows/evil.txt",
            "a\\b.txt",
            "..\\evil.txt",
            "with\u0000nul.txt",
            "with\u0007bell.txt",
            "./a.txt",
            "a//b.txt",
        )
        for (name in badNames) {
            val scratch = newScratch()
            val zip = legacyZip(listOf(name to "pwned"))
            val error = assertImportFails(zip, scratch)
            assertTrue(
                "unexpected message for '$name': ${error.message}",
                error.message.orEmpty().isNotEmpty(),
            )
            // 越界内容绝不能出现在目标目录之外
            assertFalse(File(scratch.parent, "evil.txt").exists())
            assertFalse(File(tmp.root, "evil.txt").exists())
        }
    }

    @Test
    fun `rejects duplicate and file directory conflicting paths`() = runBlocking<Unit> {
        val duplicate = RawZip().file("a.txt", "1").file("a.txt", "2").build()
        assertImportFails(duplicate, newScratch())

        val fileThenDir = RawZip().file("a.txt", "1").file("a.txt/b.txt", "2").build()
        assertImportFails(fileThenDir, newScratch())

        val dirThenFile = RawZip().dir("a/").file("a", "1").build()
        assertImportFails(dirThenFile, newScratch())

        // 仅大小写不同**不是**冲突：目标是区分大小写的 Linux ext4，两个都是合法文件。
        // 这条预期已随主线程复核结论改过，落在 WorkspaceImportEdgeCasesTest。
        val caseDiffering = RawZip().file("A.txt", "1").file("a.txt", "2").build()
        val scratch = newScratch()
        val summary = importInto(caseDiffering, scratch)
        assertEquals(2, summary.fileCount)
        assertEquals("1", File(scratch.content, "A.txt").readText())
        assertEquals("2", File(scratch.content, "a.txt").readText())
    }

    @Test
    fun `rejects symlink entries`() = runBlocking<Unit> {
        val zip = RawZip()
            .file("evil-link", "/etc/passwd", UnixMode.mode(UnixMode.SYMLINK, UnixMode.PERM_777))
            .build()
        val error = assertImportFails(zip, newScratch())
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("符号链接"))
    }

    @Test
    fun `rejects fifo and other special entries`() = runBlocking<Unit> {
        val fifo = RawZip()
            .file("pipe", ByteArray(0), UnixMode.mode(UnixMode.FIFO, UnixMode.PERM_644))
            .build()
        assertImportFails(fifo, newScratch())
    }

    @Test
    fun `rejects zip with more entries than the limit`() = runBlocking<Unit> {
        val zip = legacyZip((0 until 5).map { "f$it.txt" to "x" })
        val scratch = newScratch()
        val error = assertImportFails(
            zip,
            scratch,
            importer = WorkspaceFilesImporter(limits = WorkspaceFilesImporter.Limits(maxEntries = 4)),
        )
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("条目数"))
    }

    @Test
    fun `aborts when unpacked bytes exceed the total limit and cleans up`() = runBlocking<Unit> {
        val payload = "x".repeat(4096)
        val zip = legacyZip((0 until 3).map { "f$it.txt" to payload })
        val scratch = newScratch()
        val error = assertImportFails(
            zip,
            scratch,
            importer = WorkspaceFilesImporter(
                limits = WorkspaceFilesImporter.Limits(maxTotalBytes = 5_000),
            ),
        )
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("总大小"))
    }

    @Test
    fun `rejects declared file size above the limit before writing`() = runBlocking<Unit> {
        val zip = RawZip().file("huge.bin", "tiny", declaredSize = 10_000L).build()
        val scratch = newScratch()
        val error = assertImportFails(
            zip,
            scratch,
            importer = WorkspaceFilesImporter(limits = WorkspaceFilesImporter.Limits(maxFileBytes = 1024)),
        )
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("单文件上限"))
    }

    @Test
    fun `rejects corrupt crc and lying declared sizes`() = runBlocking<Unit> {
        val badCrc = RawZip().file("a.txt", "hello", corruptCrc = true).build()
        val crcError = assertImportFails(badCrc, newScratch())
        assertTrue(crcError.message.orEmpty(), crcError.message.orEmpty().contains("CRC"))

        // 声明 5 字节、实际 100 字节：不信任 entry.size
        val lying = RawZip().file("a.bin", "y".repeat(100), declaredSize = 5L).build()
        assertImportFails(lying, newScratch())
    }

    @Test
    fun `rejects empty and truncated zips`() = runBlocking<Unit> {
        assertImportFails(ByteArray(0), newScratch())

        val zip = legacyZip(listOf("a.txt" to "hello"))
        assertImportFails(zip.copyOf(zip.size - 30), newScratch())

        // 只截掉最后几个字节：EOCD 注释长度对不上，也必须判定为损坏
        assertImportFails(zip.copyOf(zip.size - 3), newScratch())

        // 头部被砍掉：本地头与中央目录偏移全错
        assertImportFails(zip.copyOfRange(10, zip.size), newScratch())
    }

    // ------------------------------------------------------------ 清理与不可侵犯性

    @Test
    fun `failed import leaves other workspaces and rootfs untouched`() = runBlocking<Unit> {
        val parent = tmp.newFolder()
        val other = File(parent, "other-workspace")
        File(other, "files").mkdirs()
        File(other, "files/keep.txt").writeText("keep me")
        File(other, "linux/bin").mkdirs()
        File(other, "linux/bin/sh").writeText("#!/bin/sh")
        val existingZip = File(other, "files/backup.zip").apply { writeBytes(ByteArray(32)) }

        val scratch = Scratch(parent)
        assertImportFails(legacyZip(listOf("../evil.txt" to "pwned")), scratch)

        assertEquals("keep me", File(other, "files/keep.txt").readText())
        assertTrue(File(other, "linux/bin/sh").isFile)
        assertEquals(32L, existingZip.length())
        assertFalse(scratch.staging.exists())
        assertFalse(scratch.target.exists())
        assertEquals(setOf("other-workspace"), parent.list()!!.toSet())
    }

    @Test
    fun `register failure rolls the published directory back`() = runBlocking<Unit> {
        val scratch = newScratch()
        val error = runCatching {
            importInto(legacyZip(listOf("a.txt" to "a")), scratch) {
                throw IllegalStateException("database is down")
            }
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertFalse("发布出来的目录必须回滚", scratch.target.exists())
        assertFalse(scratch.staging.exists())
        assertEquals(0, scratch.parent.list()!!.size)
    }

    @Test
    fun `cancellation stops extraction and removes only its own staging directory`() = runBlocking<Unit> {
        val zip = legacyZipWithBigFile(8 * 1024 * 1024)
        val parent = tmp.newFolder()
        File(parent, "keep.txt").writeText("untouched")
        val scratch = Scratch(parent)
        lateinit var job: Job
        var lastBytes = 0L

        job = launch {
            WorkspaceFilesImporter(progressIntervalNanos = 0L).importWorkspace(
                source = ByteArrayInputStream(zip),
                stagingRoot = scratch.staging,
                finalDir = scratch.target,
                onProgress = { bytes, _, _ ->
                    lastBytes = bytes
                    if (bytes > 1_000_000L) job.cancel()
                },
            )
        }
        job.join()

        assertTrue("import coroutine should be cancelled", job.isCancelled)
        assertTrue("should stop mid-file, saw $lastBytes bytes", lastBytes < 8L * 1024 * 1024)
        assertFalse(scratch.staging.exists())
        assertFalse(scratch.target.exists())
        assertEquals("untouched", File(parent, "keep.txt").readText())
    }

    @Test
    fun `refuses to reuse an existing staging or target directory`() = runBlocking<Unit> {
        val parent = tmp.newFolder()
        val scratch = Scratch(parent)
        scratch.staging.mkdirs()

        val error = runCatching {
            importInto(legacyZip(listOf("a.txt" to "a")), scratch)
        }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $error", error is IllegalArgumentException)
        assertTrue(scratch.staging.isDirectory)
        assertFalse(scratch.target.exists())

        val scratch2 = newScratch()
        scratch2.target.mkdirs()
        val targetError = runCatching {
            importInto(legacyZip(listOf("a.txt" to "a")), scratch2)
        }.exceptionOrNull()
        assertTrue(targetError is IllegalArgumentException)
        assertTrue(scratch2.target.isDirectory)
        assertFalse(scratch2.staging.exists())
    }

    // ------------------------------------------------------------ 工具

    private class Scratch(val parent: File) {
        val staging = File(parent, ".import-test")
        val target = File(parent, "target")
        val content: File get() = File(target, WorkspaceFilesImporter.CONTENT_DIR_NAME)
    }

    private fun newScratch(): Scratch = Scratch(tmp.newFolder())

    private suspend fun importInto(
        zip: ByteArray,
        scratch: Scratch,
        importer: WorkspaceFilesImporter = WorkspaceFilesImporter(),
        stripWrapper: String? = null,
        onProgress: (Long, Int, String) -> Unit = { _, _, _ -> },
        register: suspend () -> Unit = {},
    ): WorkspaceFilesImporter.Summary = importer.importWorkspace(
        source = ByteArrayInputStream(zip),
        stagingRoot = scratch.staging,
        finalDir = scratch.target,
        stripWrapper = stripWrapper,
        onProgress = onProgress,
        register = { register() },
    )

    /** 断言导入失败，并且只留下"什么都没发生"的状态。 */
    private suspend fun assertImportFails(
        zip: ByteArray,
        scratch: Scratch,
        importer: WorkspaceFilesImporter = WorkspaceFilesImporter(),
        stripWrapper: String? = null,
    ): IOException {
        val error = runCatching { importInto(zip, scratch, importer, stripWrapper) }.exceptionOrNull()
        assertTrue("expected IOException, got $error", error is IOException)
        assertFalse("失败后临时目录必须删除", scratch.staging.exists())
        assertFalse("失败后不能留下正式目录", scratch.target.exists())
        assertTrue(
            "失败必须有可读原因",
            error?.message.orEmpty().isNotBlank(),
        )
        return error as IOException
    }

    private fun writeText(base: File, relativePath: String, content: String) {
        val file = File(base, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun permissionsOf(file: File): Set<PosixFilePermission> =
        runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrDefault(emptySet())

    /** 和 fork 旧版 `exportWorkspaceFiles` 产出的包一样：只有名字，没有 Unix 权限位。 */
    private fun legacyZip(entries: List<Pair<String, String?>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                if (content != null) zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun legacyZipWithBigFile(sizeBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("big.bin"))
            var remaining = sizeBytes
            while (remaining > 0) {
                val length = minOf(remaining, chunk.size)
                zip.write(chunk, 0, length)
                remaining -= length
            }
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** Unix 文件类型/权限位（写进 ZIP 中央目录的外部属性高 16 位）。 */
    private object UnixMode {
        const val REGULAR = 0x8
        const val DIRECTORY = 0x4
        const val SYMLINK = 0xA
        const val FIFO = 0x1
        const val PERM_644 = 0x1A4
        const val PERM_755 = 0x1ED
        const val PERM_777 = 0x1FF

        fun mode(type: Int, permissions: Int): Int = (type shl 12) or permissions
    }

    /**
     * 手工拼 ZIP 字节，用来造 `ZipOutputStream` 造不出来的恶意包
     * （自定义 Unix 权限位、重复名、CRC 损坏、尺寸撒谎）。
     */
    private class RawZip {
        private class Item(
            val name: String,
            val data: ByteArray,
            val unixMode: Int?,
            val deflate: Boolean,
            val corruptCrc: Boolean,
            val declaredSize: Long?,
        )

        private val items = ArrayList<Item>()

        fun file(
            name: String,
            content: String,
            unixMode: Int? = null,
            deflate: Boolean = false,
            corruptCrc: Boolean = false,
            declaredSize: Long? = null,
        ): RawZip = file(
            name = name,
            content = content.toByteArray(Charsets.UTF_8),
            unixMode = unixMode,
            deflate = deflate,
            corruptCrc = corruptCrc,
            declaredSize = declaredSize,
        )

        fun file(
            name: String,
            content: ByteArray,
            unixMode: Int? = null,
            deflate: Boolean = false,
            corruptCrc: Boolean = false,
            declaredSize: Long? = null,
        ): RawZip {
            items.add(Item(name, content, unixMode, deflate, corruptCrc, declaredSize))
            return this
        }

        fun dir(name: String, unixMode: Int? = null): RawZip {
            require(name.endsWith("/")) { "directory entry must end with a slash" }
            items.add(Item(name, ByteArray(0), unixMode, deflate = false, corruptCrc = false, declaredSize = null))
            return this
        }

        fun build(): ByteArray {
            val out = ByteArrayOutputStream()
            val central = ByteArrayOutputStream()
            for (item in items) {
                val name = item.name.toByteArray(Charsets.UTF_8)
                val crc = CRC32().apply { update(item.data) }
                val crcValue = if (item.corruptCrc) crc.value xor 1L else crc.value
                val payload = if (item.deflate) deflate(item.data) else item.data
                val size = item.declaredSize ?: item.data.size.toLong()
                val compressedSize = item.declaredSize ?: payload.size.toLong()
                val offset = out.size().toLong()

                le32(out, 0x04034b50L)
                le16(out, 20)
                le16(out, 0)
                le16(out, if (item.deflate) 8 else 0)
                le16(out, 0)
                le16(out, 0x21)
                le32(out, crcValue)
                le32(out, compressedSize)
                le32(out, size)
                le16(out, name.size)
                le16(out, 0)
                out.write(name)
                out.write(payload)

                le32(central, 0x02014b50L)
                // version made by：给 Unix 权限位时声明 UNIX(3)，否则声明 MS-DOS(0)
                le16(central, if (item.unixMode != null) (3 shl 8) or 30 else 20)
                le16(central, 20)
                le16(central, 0)
                le16(central, if (item.deflate) 8 else 0)
                le16(central, 0)
                le16(central, 0x21)
                le32(central, crcValue)
                le32(central, compressedSize)
                le32(central, size)
                le16(central, name.size)
                le16(central, 0)
                le16(central, 0)
                le16(central, 0)
                le16(central, 0)
                le32(
                    central,
                    ((item.unixMode ?: 0).toLong() shl 16) or (if (item.name.endsWith("/")) 0x10L else 0L),
                )
                le32(central, offset)
                central.write(name)
            }
            val centralOffset = out.size().toLong()
            val centralBytes = central.toByteArray()
            out.write(centralBytes)
            le32(out, 0x06054b50L)
            le16(out, 0)
            le16(out, 0)
            le16(out, items.size)
            le16(out, items.size)
            le32(out, centralBytes.size.toLong())
            le32(out, centralOffset)
            le16(out, 0)
            return out.toByteArray()
        }

        private fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            try {
                deflater.setInput(data)
                deflater.finish()
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (!deflater.finished()) {
                    val length = deflater.deflate(buffer)
                    out.write(buffer, 0, length)
                }
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        private fun le16(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }

        private fun le32(out: ByteArrayOutputStream, value: Long) {
            le16(out, (value and 0xFFFF).toInt())
            le16(out, ((value ushr 16) and 0xFFFF).toInt())
        }
    }
}

package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ZIP 导入的**边界回归**：主线程复核后确认的问题逐条钉死。
 *
 * 覆盖：
 * - 没有 EOCD 时不能从 -1 继续解析（[WorkspaceFilesImporter] 曾经把 `Int -1` 当 nullable 用）；
 * - EOCD 的磁盘号 / 本盘条目数 / 中央目录长度都要互相吻合，长度被改大改小都拒绝；
 * - 有效的 0 条目空 ZIP（旧导出器的空工作区）能往返，0 字节输入仍然拒绝；
 * - 伪装成目录但带 payload 的条目（含外层包装目录自身）必须报错，不能静默丢弃；
 * - 路径**大小写严格敏感**：`A.txt`/`a.txt`、`Data/`/`data/` 都是合法 Linux 文件；
 * - 悬空符号链接当 staging/target 时不能顺着链接写出去；
 * - ZIP64 的哨兵值、定位记录、磁盘字段、条目数一致性校验；
 * - register 抛异常要回滚；register 已落库后的取消**不能**回滚（ghost 工作区）；
 * - 最后一个进度回调里取消不许照常发布；
 * - 非 UTF-8 文件名不能被替换成 U+FFFD 混过去；
 * - 可执行位是真的恢复（读回校验），不是"声称恢复"。
 */
class WorkspaceImportEdgeCasesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------ EOCD / 中央目录

    @Test
    fun `zip without an end of central directory record is rejected`() = runBlocking<Unit> {
        val zip = legacyZip(listOf("a.txt" to "hello"))
        val eocd = eocdOffset(zip)
        // 把 EOCD 签名抹掉：整包再无结尾记录
        for (i in 0 until 4) zip[eocd + i] = 0
        val scratch = newScratch()
        val error = assertImportFails(zip, scratch)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("EOCD"))
    }

    @Test
    fun `tampered central directory size or offset is rejected`() = runBlocking<Unit> {
        val zip = legacyZip(listOf("a.txt" to "hello", "d/b.txt" to "bb"))
        val eocd = eocdOffset(zip)
        val cdSize = readU32(zip, eocd + 12)
        val cdOffset = readU32(zip, eocd + 16)

        // 声明的中央目录比真实的长
        val longer = zip.copyOf()
        writeU32(longer, eocd + 12, cdSize + 8)
        val longerError = assertImportFails(longer, newScratch())
        assertTrue(longerError.message.orEmpty(), longerError.message.orEmpty().isNotBlank())

        // 声明的中央目录比真实的短
        val shorter = zip.copyOf()
        writeU32(shorter, eocd + 12, cdSize - 4)
        assertImportFails(shorter, newScratch())

        // 偏移被挪动 4 字节：条目表读出来对不上
        val moved = zip.copyOf()
        writeU32(moved, eocd + 16, cdOffset - 4)
        assertImportFails(moved, newScratch())

        // 本盘条目数与总数不一致（分卷/手工拼接的痕迹）
        val mismatch = zip.copyOf()
        writeU16(mismatch, eocd + 8, 1)
        val mismatchError = assertImportFails(mismatch, newScratch())
        assertTrue(mismatchError.message.orEmpty(), mismatchError.message.orEmpty().contains("条目数不一致"))
    }

    @Test
    fun `multi disk eocd is rejected`() = runBlocking<Unit> {
        val zip = legacyZip(listOf("a.txt" to "hello"))
        val eocd = eocdOffset(zip)
        writeU16(zip, eocd + 4, 1)
        val error = assertImportFails(zip, newScratch())
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("分卷"))
    }

    // ------------------------------------------------------------ 空包

    @Test
    fun `valid zip with zero entries round trips an empty workspace`() = runBlocking<Unit> {
        // 旧导出器导出空工作区产出的就是只有 EOCD 的 22 字节包，必须能导入
        val emptyZip = legacyZip(emptyList())
        assertEquals(22, emptyZip.size)
        val scratch = newScratch()
        val summary = importInto(emptyZip, scratch)
        assertEquals(0, summary.fileCount)
        assertEquals(0, summary.directoryCount)
        assertEquals(0L, summary.byteCount)
        assertFalse(scratch.staging.exists())
        assertTrue("空工作区也要有内容目录", scratch.content.isDirectory)
        assertEquals(0, scratch.content.list()!!.size)

        // 真导出器导出的空目录同样能往返
        val source = tmp.newFolder()
        val exported = ByteArrayOutputStream().also { out -> WorkspaceFilesExporter().export(source, out) }.toByteArray()
        val scratch2 = newScratch()
        val exportedSummary = importInto(exported, scratch2)
        assertEquals(0, exportedSummary.fileCount)
        assertTrue(scratch2.content.isDirectory)
        assertEquals(0, scratch2.content.list()!!.size)
    }

    @Test
    fun `zero byte input is still rejected`() = runBlocking<Unit> {
        assertImportFails(ByteArray(0), newScratch())
    }

    // ------------------------------------------------------------ 目录条目伪装

    @Test
    fun `directory entry carrying a payload is rejected instead of skipped`() = runBlocking<Unit> {
        val disguised = ZipBuilder()
            .raw("evil/", "pwned".toByteArray(Charsets.UTF_8))
            .file("ok.txt", "ok")
            .build()
        val scratch = newScratch()
        val error = assertImportFails(disguised, scratch)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("目录条目"))

        // 只声明成目录、CRC 非 0（内容对不上）同样拒绝
        val badCrc = ZipBuilder()
            .raw("evil2/", "pwned".toByteArray(Charsets.UTF_8), declaredSize = 0L, corruptCrc = true)
            .build()
        assertImportFails(badCrc, newScratch())
    }

    @Test
    fun `wrapper directory entry carrying a payload is rejected too`() = runBlocking<Unit> {
        // 包装目录自身不落地，但它的 payload 也不能静默放过
        val zip = ZipBuilder()
            .raw("workspace/", "hidden".toByteArray(Charsets.UTF_8))
            .file("workspace/a.txt", "a")
            .build()
        val scratch = newScratch()
        val error = runCatching { importInto(zip, scratch, stripWrapper = WorkspaceFilesImporter.WRAPPER_WORKSPACE) }
            .exceptionOrNull()
        assertTrue("expected IOException, got $error", error is IOException)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains("目录条目"))
        assertFalse(scratch.staging.exists())
        assertFalse(scratch.target.exists())
    }

    @Test
    fun `empty directory entries are still accepted`() = runBlocking<Unit> {
        val zip = ZipBuilder().dir("docs/").file("docs/a.txt", "a").build()
        val scratch = newScratch()
        val summary = importInto(zip, scratch)
        assertEquals(1, summary.fileCount)
        assertEquals(1, summary.directoryCount)
        assertTrue(File(scratch.content, "docs").isDirectory)
    }

    // ------------------------------------------------------------ 大小写

    @Test
    fun `case differing files and parents are all imported`() = runBlocking<Unit> {
        val zip = ZipBuilder()
            .file("A.txt", "upper")
            .file("a.txt", "lower")
            .file("Data/one.txt", "1")
            .file("data/two.txt", "2")
            .build()
        val scratch = newScratch()
        val summary = importInto(zip, scratch)

        assertEquals(4, summary.fileCount)
        assertEquals("两个大小写不同的父目录都必须登记为目录", 2, summary.directoryCount)
        assertEquals("upper", File(scratch.content, "A.txt").readText())
        assertEquals("lower", File(scratch.content, "a.txt").readText())
        assertEquals("1", File(scratch.content, "Data/one.txt").readText())
        assertEquals("2", File(scratch.content, "data/two.txt").readText())
    }

    @Test
    fun `exactly identical paths are still duplicates`() = runBlocking<Unit> {
        val zip = ZipBuilder().file("a.txt", "1").file("a.txt", "2").build()
        assertImportFails(zip, newScratch())
    }

    // ------------------------------------------------------------ 符号链接前置条件

    @Test
    fun `dangling symlink target or staging is rejected without writing through it`() = runBlocking<Unit> {
        val parent = tmp.newFolder()
        val outside = File(parent, "outside")
        val targetLink = File(parent, "target")
        Files.createSymbolicLink(targetLink.toPath(), outside.toPath())

        val zip = legacyZip(listOf("a.txt" to "a"))
        val targetError = runCatching {
            WorkspaceFilesImporter().importWorkspace(
                source = ByteArrayInputStream(zip),
                stagingRoot = File(parent, ".import"),
                finalDir = targetLink,
            )
        }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $targetError", targetError is IllegalArgumentException)
        assertFalse("不能顺着悬空链接造出目录", outside.exists())
        assertTrue(Files.isSymbolicLink(targetLink.toPath()))
        assertFalse(File(parent, ".import").exists())

        val stagingLink = File(parent, ".import-link")
        Files.createSymbolicLink(stagingLink.toPath(), File(parent, "ghost").toPath())
        val stagingError = runCatching {
            WorkspaceFilesImporter().importWorkspace(
                source = ByteArrayInputStream(zip),
                stagingRoot = stagingLink,
                finalDir = File(parent, "real-target"),
            )
        }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $stagingError", stagingError is IllegalArgumentException)
        assertFalse(File(parent, "ghost").exists())
        assertTrue(Files.isSymbolicLink(stagingLink.toPath()))
        assertFalse(File(parent, "real-target").exists())
    }

    // ------------------------------------------------------------ 取消 / 注册

    @Test
    fun `cancelling in the final progress report does not publish`() = runBlocking<Unit> {
        val zip = legacyZip(listOf("a.txt" to "0123456789"))
        val scratch = newScratch()
        lateinit var job: Job
        var sawFinalReport = false

        job = launch {
            WorkspaceFilesImporter(progressIntervalNanos = 0L).importWorkspace(
                source = ByteArrayInputStream(zip),
                stagingRoot = scratch.staging,
                finalDir = scratch.target,
                onProgress = { _, _, current ->
                    // 最后一个回调（current 为空）正是"文件已经写完、马上要发布"的时机
                    if (current.isEmpty()) {
                        sawFinalReport = true
                        job.cancel()
                    }
                },
            )
        }
        job.join()

        assertTrue("必须走到最后一个进度回调", sawFinalReport)
        assertTrue("import coroutine should be cancelled", job.isCancelled)
        assertFalse("取消后不能发布", scratch.target.exists())
        assertFalse(scratch.staging.exists())
    }

    @Test
    fun `register failure rolls back the published directory`() = runBlocking<Unit> {
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
    fun `cancellation after register keeps the committed directory`() = runBlocking<Unit> {
        val zip = legacyZip(listOf("a.txt" to "hello"))
        val scratch = newScratch()
        lateinit var job: Job
        var dbCommitted = false

        job = launch {
            WorkspaceFilesImporter().importWorkspace(
                source = ByteArrayInputStream(zip),
                stagingRoot = scratch.staging,
                finalDir = scratch.target,
                register = {
                    // 模拟"记录已经写进数据库，紧接着父协程被取消"
                    dbCommitted = true
                    job.cancel()
                },
            )
        }
        job.join()

        assertTrue(dbCommitted)
        assertTrue(job.isCancelled)
        // 关键：数据库里已经有这条记录了，目录必须还在，否则就是 ghost 工作区
        assertTrue("已落库的目录不能被回滚", scratch.target.isDirectory)
        assertEquals("hello", File(scratch.content, "a.txt").readText())
        assertFalse(scratch.staging.exists())
    }

    @Test
    fun `restrictive unix permissions do not become world readable or executable`() = runBlocking<Unit> {
        val zip = ZipBuilder().file("secret", "private", 0x8000 or 0x180)
            .file("run.sh", "private script", 0x8000 or 0x1C0).build()
        val scratch = newScratch()
        importInto(zip, scratch)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(File(scratch.content, "secret").toPath()))
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            Files.getPosixFilePermissions(File(scratch.content, "run.sh").toPath()))
    }

    // ------------------------------------------------------------ 可执行位

    @Test
    fun `executable bit is really restored and reported only when applied`() = runBlocking<Unit> {
        val zip = ZipBuilder()
            .file("run.sh", "#!/bin/sh\n", unixMode = UnixMode.mode(UnixMode.REGULAR, UnixMode.PERM_755))
            .file("plain.txt", "text", unixMode = UnixMode.mode(UnixMode.REGULAR, UnixMode.PERM_644))
            .build()
        val scratch = newScratch()
        val summary = importInto(zip, scratch)

        assertEquals(1, summary.executableFiles)
        assertEquals(0, summary.entriesWithoutUnixMode)
        val perms = Files.getPosixFilePermissions(File(scratch.content, "run.sh").toPath())
        assertTrue("可执行位必须真的落到文件上：$perms", perms.contains(PosixFilePermission.OWNER_EXECUTE))
        val plainPerms = Files.getPosixFilePermissions(File(scratch.content, "plain.txt").toPath())
        assertFalse("没有可执行位就不许加：$plainPerms", plainPerms.contains(PosixFilePermission.OWNER_EXECUTE))
    }

    @Test
    fun `legacy zip without unix modes keeps default permissions`() = runBlocking<Unit> {
        val zip = legacyZip(listOf("sub/b.sh" to "#!/bin/sh\n"))
        val scratch = newScratch()
        val summary = importInto(zip, scratch)

        assertEquals(0, summary.executableFiles)
        assertEquals(1, summary.entriesWithoutUnixMode)
        assertTrue(summary.permissionsAreGuessed)
        val perms = Files.getPosixFilePermissions(File(scratch.content, "sub/b.sh").toPath())
        assertFalse(perms.contains(PosixFilePermission.OWNER_EXECUTE))
        assertFalse("保持默认，不额外设组/其他写位", perms.contains(PosixFilePermission.OTHERS_WRITE))
    }

    // ------------------------------------------------------------ ZIP64

    @Test
    fun `zip64 eocd with sentinel values imports`() = runBlocking<Unit> {
        val zip = zip64Wrapped(legacyZip(listOf("a.txt" to "hello", "d/b.txt" to "bb")))
        val scratch = newScratch()
        val summary = importInto(zip, scratch)
        assertEquals(2, summary.fileCount)
        assertEquals("hello", File(scratch.content, "a.txt").readText())
        assertEquals("bb", File(scratch.content, "d/b.txt").readText())
    }

    @Test
    fun `zip64 sentinel without a locator record is rejected`() = runBlocking<Unit> {
        // 只把 EOCD 改成 64 位哨兵值，包里却没有 ZIP64 记录：不能被当成"0 条"混过去
        val zip = legacyZip(listOf("a.txt" to "hello"))
        val eocd = eocdOffset(zip)
        writeU16(zip, eocd + 8, 0xFFFFL)
        writeU16(zip, eocd + 10, 0xFFFFL)
        writeU32(zip, eocd + 12, 0xFFFFFFFFL)
        writeU32(zip, eocd + 16, 0xFFFFFFFFL)
        val error = assertImportFails(zip, newScratch())
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("ZIP64"))
    }

    @Test
    fun `zip64 locator and record fields are validated`() = runBlocking<Unit> {
        val base = legacyZip(listOf("a.txt" to "hello"))

        // 定位记录声称有多张盘
        assertImportFails(zip64Wrapped(base, totalDisks = 2L), newScratch())
        // 定位记录指向的盘不是第 0 张
        assertImportFails(zip64Wrapped(base, locatorDisk = 1L), newScratch())
        // ZIP64 记录偏移越界
        assertImportFails(zip64Wrapped(base, zip64OffsetOverride = base.size + 10_000L), newScratch())
        // ZIP64 记录签名不对
        assertImportFails(zip64Wrapped(base, zip64Signature = 0x12345678L), newScratch())
        // ZIP64 里声明的中央目录长度被改大
        assertImportFails(
            zip64Wrapped(base, cdSizeOverride = readU32(base, eocdOffset(base) + 12) + 4),
            newScratch(),
        )

        // ZIP64 记录里本盘条目数与总数不一致
        val error = runCatching {
            importInto(zip64Wrapped(base, entriesOnDiskOverride = 0L), newScratch())
        }.exceptionOrNull()
        assertTrue("expected IOException, got $error", error is IOException)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains("条目数不一致"))
    }

    // ------------------------------------------------------------ 文件名编码

    @Test
    fun `names that are not valid utf8 are rejected not silently replaced`() = runBlocking<Unit> {
        // 0xC3 0x28 不是合法 UTF-8 序列：String(bytes, UTF_8) 会替换成 U+FFFD，
        // 那样两个不同的坏名字会被悄悄洗成同一个"看起来正常"的名字。
        val badName = byteArrayOf('x'.code.toByte(), 0xC3.toByte(), 0x28)
        val zip = ZipBuilder().rawName(badName, "content".toByteArray(Charsets.UTF_8)).build()
        val error = assertImportFails(zip, newScratch())
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("UTF-8"))
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
        stripWrapper: String? = null,
        importer: WorkspaceFilesImporter = WorkspaceFilesImporter(),
        register: suspend () -> Unit = {},
    ): WorkspaceFilesImporter.Summary = importer.importWorkspace(
        source = ByteArrayInputStream(zip),
        stagingRoot = scratch.staging,
        finalDir = scratch.target,
        stripWrapper = stripWrapper,
        register = { register() },
    )

    /** 断言导入失败，并且只留下"什么都没发生"的状态。 */
    private suspend fun assertImportFails(zip: ByteArray, scratch: Scratch): IOException {
        val error = runCatching { importInto(zip, scratch) }.exceptionOrNull()
        assertTrue("expected IOException, got $error", error is IOException)
        assertFalse("失败后临时目录必须删除", scratch.staging.exists())
        assertFalse("失败后不能留下正式目录", scratch.target.exists())
        assertTrue(error?.message.orEmpty().isNotBlank())
        return error as IOException
    }

    private fun legacyZip(entries: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private object UnixMode {
        const val REGULAR = 0x8
        const val PERM_644 = 0x1A4
        const val PERM_755 = 0x1ED

        fun mode(type: Int, permissions: Int): Int = (type shl 12) or permissions
    }

    /**
     * 手工拼 ZIP 字节：能造出 `ZipOutputStream` 造不出来的东西
     * （原始名字字节、自定义 Unix 权限位、长度撒谎、CRC 造假）。
     */
    private class ZipBuilder {
        private class Item(
            val nameBytes: ByteArray,
            val data: ByteArray,
            val unixMode: Int?,
            val declaredSize: Long?,
            val corruptCrc: Boolean,
        )

        private val items = ArrayList<Item>()

        fun file(name: String, content: String, unixMode: Int? = null): ZipBuilder =
            raw(name, content.toByteArray(Charsets.UTF_8), unixMode = unixMode)

        fun dir(name: String): ZipBuilder = raw(name, ByteArray(0))

        /** 用原始字节当名字（造非法 UTF-8），或自定义内容/声明尺寸。 */
        fun raw(
            name: String,
            data: ByteArray,
            unixMode: Int? = null,
            declaredSize: Long? = null,
            corruptCrc: Boolean = false,
        ): ZipBuilder {
            items.add(Item(name.toByteArray(Charsets.UTF_8), data, unixMode, declaredSize, corruptCrc))
            return this
        }

        fun rawName(nameBytes: ByteArray, data: ByteArray): ZipBuilder {
            items.add(Item(nameBytes, data, null, null, false))
            return this
        }

        fun build(): ByteArray {
            val out = ByteArrayOutputStream()
            val central = ByteArrayOutputStream()
            for (item in items) {
                val crc = CRC32().apply { update(item.data) }
                val crcValue = if (item.corruptCrc) crc.value xor 1L else crc.value
                val size = item.declaredSize ?: item.data.size.toLong()
                val offset = out.size().toLong()

                le32(out, 0x04034b50L)
                le16(out, 20)
                le16(out, 0)
                le16(out, 0)
                le16(out, 0)
                le16(out, 0x21)
                le32(out, crcValue)
                le32(out, size)
                le32(out, size)
                le16(out, item.nameBytes.size)
                le16(out, 0)
                out.write(item.nameBytes)
                out.write(item.data)

                le32(central, 0x02014b50L)
                le16(central, if (item.unixMode != null) (3 shl 8) or 30 else 20)
                le16(central, 20)
                le16(central, 0)
                le16(central, 0)
                le16(central, 0)
                le16(central, 0x21)
                le32(central, crcValue)
                le32(central, size)
                le32(central, size)
                le16(central, item.nameBytes.size)
                le16(central, 0)
                le16(central, 0)
                le16(central, 0)
                le16(central, 0)
                le32(
                    central,
                    ((item.unixMode ?: 0).toLong() shl 16) or (if (isDirectoryName(item.nameBytes)) 0x10L else 0L),
                )
                le32(central, offset)
                central.write(item.nameBytes)
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

        private fun isDirectoryName(name: ByteArray): Boolean = name.isNotEmpty() && name.last() == '/'.code.toByte()

        private fun le16(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }

        private fun le32(out: ByteArrayOutputStream, value: Long) {
            le16(out, (value and 0xFFFF).toInt())
            le16(out, ((value ushr 16) and 0xFFFF).toInt())
        }
    }

    /**
     * 把已有 ZIP 的 EOCD 换成 ZIP64 结构：EOCD 里全是 0xFFFF/0xFFFFFFFF 哨兵值，真实数值放进
     * ZIP64 结尾记录 + 定位记录。参数用来逐项篡改，验证校验逻辑是否真的看了这些字段。
     */
    private fun zip64Wrapped(
        base: ByteArray,
        entriesOnDiskOverride: Long? = null,
        totalEntriesOverride: Long? = null,
        cdSizeOverride: Long? = null,
        cdOffsetOverride: Long? = null,
        locatorDisk: Long = 0L,
        totalDisks: Long = 1L,
        zip64OffsetOverride: Long? = null,
        zip64Signature: Long = 0x06064b50L,
    ): ByteArray {
        val eocd = eocdOffset(base)
        val cdSize = readU32(base, eocd + 12)
        val cdOffset = readU32(base, eocd + 16)
        val total = readU16(base, eocd + 10)
        val out = ByteArrayOutputStream()
        out.write(base, 0, eocd)
        val zip64Offset = out.size().toLong()

        val record = ByteArrayOutputStream()
        le32(record, zip64Signature)
        le64(record, 44L) // 本记录之后的长度
        le16(record, 45)
        le16(record, 45)
        le32(record, 0L) // 本盘号
        le32(record, 0L) // 中央目录所在盘号
        le64(record, entriesOnDiskOverride ?: total)
        le64(record, totalEntriesOverride ?: total)
        le64(record, cdSizeOverride ?: cdSize)
        le64(record, cdOffsetOverride ?: cdOffset)
        out.write(record.toByteArray())

        val locator = ByteArrayOutputStream()
        le32(locator, 0x07064b50L)
        le32(locator, locatorDisk)
        le64(locator, zip64OffsetOverride ?: zip64Offset)
        le32(locator, totalDisks)
        out.write(locator.toByteArray())

        val eocdOut = ByteArrayOutputStream()
        le32(eocdOut, 0x06054b50L)
        le16(eocdOut, 0)
        le16(eocdOut, 0)
        le16(eocdOut, 0xFFFF)
        le16(eocdOut, 0xFFFF)
        le32(eocdOut, 0xFFFFFFFFL)
        le32(eocdOut, 0xFFFFFFFFL)
        le16(eocdOut, 0)
        out.write(eocdOut.toByteArray())
        return out.toByteArray()
    }

    private fun le16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun le32(out: ByteArrayOutputStream, value: Long) {
        le16(out, (value and 0xFFFF).toInt())
        le16(out, ((value ushr 16) and 0xFFFF).toInt())
    }

    private fun le64(out: ByteArrayOutputStream, value: Long) {
        le32(out, value and 0xFFFFFFFFL)
        le32(out, (value ushr 32) and 0xFFFFFFFFL)
    }

    private fun eocdOffset(zip: ByteArray): Int {
        for (index in zip.size - 22 downTo 0) {
            if (readU32(zip, index) == 0x06054b50L) {
                val commentLength = readU16(zip, index + 20)
                if (index.toLong() + 22L + commentLength == zip.size.toLong()) return index
            }
        }
        throw AssertionError("test zip has no EOCD")
    }

    private fun readU16(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or ((bytes[offset + 1].toLong() and 0xFF) shl 8)

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        readU16(bytes, offset) or (readU16(bytes, offset + 2) shl 16)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        writeU16(bytes, offset, value and 0xFFFF)
        writeU16(bytes, offset + 2, (value ushr 16) and 0xFFFF)
    }
}

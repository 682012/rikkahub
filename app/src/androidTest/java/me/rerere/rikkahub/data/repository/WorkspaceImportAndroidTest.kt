package me.rerere.rikkahub.data.repository

import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 工作区 ZIP 导入的**真机/Android 运行时**集成测试（核心导入器 + 真实 Room）。
 *
 * 与 `app/src/test` 里的 `WorkspaceFilesImporterTest` / `WorkspaceImportEdgeCasesTest` 不同，
 * 这里全部跑在真实 Android 上：
 * - **真实 SQLite**：Room `inMemoryDatabaseBuilder` + 生产同一个 `RequerySQLiteOpenHelperFactory`，
 *   表结构就是 `AppDatabase`（schema 34）的 `workspaces`，读写走真实 `WorkspaceDAO`
 *   （`upsert` / `getById` / `getAll`），不是 Fake；
 * - **真实 Android 文件系统**：POSIX 权限位（0600 不能被放宽成 0644）、目录 rename 发布、
 *   中文/emoji 文件名、带 NUL 的二进制文件都是真的写进 `/data/data/<pkg>`；
 * - **真实 ContentResolver 流**：[legacyBareZipThroughProviderStreamImportsUnicodeEmptyDirsAndBinary]
 *   把测试 ZIP 交给本 app 的 `FileProvider`（`${applicationId}.fileprovider`，`files-path`/`cache-path`
 *   都在 `res/xml/file_paths.xml` 里声明过），再用 `context.contentResolver.openInputStream(uri)` 导入，
 *   走的是真的 provider → pipe → 导入器，而不是把文件路径直接塞进去。
 *
 * 刻意划清的边界（不要把它当成 Repo/UI 测试）：
 * - **不是** `WorkspaceRepository` 全对象测试。`WorkspaceRepository` 需要 `dao` / `WorkspaceManager` /
 *   `RootfsInstaller` / `SettingsStore` / `SshPool` / `Context` / Koin 一整套，真机装一次就为了构造这些
 *   代价过大且副作用不可控。这里用「核心 + Room 集成」：`WorkspaceFilesImporter.importWorkspace(...)` +
 *   与生产 `WorkspaceRepository.importWorkspaceFiles` **完全同序**的 register 动作
 *   （`manager.ensureWorkspace(root)` → `dao.upsert(workspace)`），所以发布/回滚/落库语义和生产一致，
 *   但没有 repository 的额外业务（重名检查、SSH 类型、UI 回调）；
 * - **不驱动 UI，也不用 SAF 选择器**。`ACTION_OPEN_DOCUMENT` 必须有人点系统文件框，那部分由主线程的
 *   真机 UI 验证覆盖；导入器只吃 `InputStream`，因此 provider 流之外的用例如实使用真实文件上的
 *   `FileInputStream`（同一台设备、同一份字节、同一条导入路径），不伪造 SAF；
 * - **不碰生产数据**。所有目录都在 `<filesDir>/workspace-import-android-<uuid>/` 下自己建
 *   （`WorkspaceManager` 也只用这个隔离 baseDir），`@After` 里 `deleteRecursively()`；生产
 *   `files/workspaces`、生产数据库、设置、LinuxRootfs 全程不参与，因此不会覆盖或污染用户工作区。
 *
 * 覆盖的回归点（每条对应一个真实失败模式）：
 * 1. fork 旧版裸 ZIP（无 Unix 权限位）里的中文/emoji 文件名、空目录条目、含 NUL 的二进制文件，
 *    经真实 provider 流导入后**逐字节一致**，并真的落一条 `workspaces` 记录；
 * 2. `0600` 这类收紧的权限位必须**原样保留**，不能被"默认 0644"放宽（隐私文件回归）；
 * 3. 导入是"新建工作区"：现有工作区的文件与 DB 记录一动不动，往已存在的目标目录再导一次必须直接拒绝；
 * 4. 失败（损坏包）与取消（最后一个进度回调里 cancel）都**不落 DB**、不留正式目录、不留 staging 残骸。
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceImportAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var database: AppDatabase
    private lateinit var dao: WorkspaceDAO

    /** 本次测试独占的根目录，`@After` 整棵删掉；生产目录不在它下面。 */
    private lateinit var testRoot: File

    /** 隔离的 workspaces base：生产里是 `File(context.filesDir, "workspaces")`。 */
    private lateinit var workspacesBase: File
    private lateinit var manager: WorkspaceManager

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory())
            .build()
        dao = database.workspaceDao()

        testRoot = File(context.filesDir, "workspace-import-android-${UUID.randomUUID()}")
        assertTrue("无法创建隔离测试目录 $testRoot", testRoot.mkdirs())
        workspacesBase = File(testRoot, "workspaces")
        // 只覆盖 baseDir（其余用默认 config / HostShellRunner / 无 bind mount）：
        // 导入、发布、ensureWorkspace 都是真实实现，只是根目录换成了隔离目录。
        manager = WorkspaceManager(baseDir = workspacesBase)
    }

    @After
    fun tearDown() {
        database.close()
        testRoot.deleteRecursively()
    }

    // ------------------------------------------------------------------ 1. 旧裸 ZIP + provider 流

    /**
     * fork 旧版 `exportWorkspaceFiles` 导出的裸 ZIP（没有任何 Unix 权限位）走**真实 provider 流**导入。
     *
     * 老包里全是 `ZipOutputStream` 写出来的普通条目：中文名、emoji 名、空目录条目、含 NUL 的二进制。
     * 这条路径以前最容易坏在"名字解码"和"空目录不落地"上，所以这里每一项都逐字节核对。
     */
    @Test
    fun legacyBareZipThroughProviderStreamImportsUnicodeEmptyDirsAndBinary() = runBlocking<Unit> {
        val chineseName = "你好，世界.txt"
        val emojiName = "🙂-smile-🧪.txt"
        val chineseText = "第一行\n第二行\n"
        val emojiText = "emoji ok"
        val scriptText = "#!/bin/sh\necho hi\n"
        val binary = ByteArray(2048) { index -> ((index * 37) % 256).toByte() }

        // 旧导出器写法：ZipOutputStream + 普通 ZipEntry，没有任何外部属性，也没有权限位
        val zipBytes = legacyZip(
            files = listOf(
                chineseName to chineseText.toByteArray(Charsets.UTF_8),
                emojiName to emojiText.toByteArray(Charsets.UTF_8),
                "bin/blob.bin" to binary,
                "run.sh" to scriptText.toByteArray(Charsets.UTF_8),
            ),
            dirs = listOf("empty-dir/", "nested/empty-two/"),
        )
        val zipFile = writeZip("legacy.zip", zipBytes)

        // 真的走本 app 的 FileProvider：抓不到 root 就会在这里抛 IllegalArgumentException
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        assertEquals("必须拿到 content:// 流", "content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)

        val id = UUID.randomUUID().toString()
        val workspace = newWorkspace(id, "legacy-${id.take(8)}")
        val summary = importInto(workspace) {
            // 真实 ContentResolver → 真实 FileProvider → 导入器；不是 FileInputStream 短路
            context.contentResolver.openInputStream(uri) ?: error("ContentResolver 打不开 $uri")
        }

        val filesDir = manager.filesDir(id)
        assertTrue("导入目标必须在隔离目录里：$filesDir", filesDir.canonicalPath.startsWith(testRoot.canonicalPath))

        assertEquals(chineseText, File(filesDir, chineseName).readText())
        assertEquals(emojiText, File(filesDir, emojiName).readText())
        assertEquals(scriptText, File(filesDir, "run.sh").readText())
        assertArrayEquals("二进制内容必须逐字节一致（含 NUL 与非 UTF-8 字节）", binary, File(filesDir, "bin/blob.bin").readBytes())

        // 空目录条目不能因为"没文件"就被丢掉
        assertTrue("空目录条目必须落地", File(filesDir, "empty-dir").isDirectory)
        assertEquals(0, File(filesDir, "empty-dir").list()!!.size)
        assertTrue(File(filesDir, "nested/empty-two").isDirectory)
        assertEquals(1, File(filesDir, "nested").list()!!.size)

        // 统计口径：4 个文件；目录 = empty-dir、nested、nested/empty-two、bin
        assertEquals(4, summary.fileCount)
        assertEquals(4, summary.directoryCount)
        val expectedBytes = chineseText.toByteArray(Charsets.UTF_8).size.toLong() +
            emojiText.toByteArray(Charsets.UTF_8).size +
            binary.size +
            scriptText.toByteArray(Charsets.UTF_8).size
        assertEquals(expectedBytes, summary.byteCount)
        // 旧裸 ZIP 没有权限位：可执行位丢失，必须如实报告而不是假装恢复
        assertTrue("旧裸 ZIP 必须报告权限位缺失", summary.permissionsAreGuessed)
        assertEquals(4, summary.entriesWithoutUnixMode)
        assertEquals(0, summary.executableFiles)
        assertNull(summary.strippedWrapper)

        // 真实 Room：register 里的 dao.upsert 必须已经落库，字段与新建工作区一致
        val stored = dao.getById(id)
        assertNotNull("register 之后必须能在真实 Room 里读到这条记录", stored)
        assertEquals(id, stored!!.root)
        assertEquals(workspace.name, stored.name)
        assertEquals("DISABLED", stored.shellStatus)
        assertEquals(1, dao.getAll().size)

        // 发布完成：staging 不能留残骸，租约必须已释放
        assertFalse(File(workspacesBase, "${WorkspaceImportStaging.STAGING_NAME_PREFIX}$id").exists())
        assertEquals(0, WorkspaceImportStaging.activeLeaseCount())
    }

    // ------------------------------------------------------------------ 2. 收紧的权限位

    /**
     * ZIP 里显式记录的 `0600` / `0644` / `0755` 在真实 Android 文件系统上必须**原样**恢复。
     *
     * 重点是否定式断言：`0600` 绝不能被放宽成 `0644`（隐私文件泄漏），也不能被顺手 chmod 成可执行。
     */
    @Test
    fun restrictiveUnixModesArePreservedOnRealAndroidFileSystem() = runBlocking<Unit> {
        assumeTrue("当前存储不支持 POSIX 权限位，跳过权限位断言", posixPermissionsSupported())

        val zipBytes = RawZip()
            .file("secret.key", "top-secret".toByteArray(Charsets.UTF_8), unixMode = UnixMode.file(0x180)) // 0600
            .file("plain.txt", "hello".toByteArray(Charsets.UTF_8), unixMode = UnixMode.file(0x1A4)) // 0644
            .file("run.sh", "#!/bin/sh\n".toByteArray(Charsets.UTF_8), unixMode = UnixMode.file(0x1ED)) // 0755
            .build()
        val zipFile = writeZip("modes.zip", zipBytes)

        val id = UUID.randomUUID().toString()
        val workspace = newWorkspace(id, "modes-${id.take(8)}")
        // 这条用例走真实文件上的 FileInputStream：权限位恢复与"流从哪来"无关，
        // provider 流已在第 1 条用例覆盖，不需要为了同一个断言再造一次 provider
        val summary = importInto(workspace) { FileInputStream(zipFile) }

        val filesDir = manager.filesDir(id)
        val secret = Files.getPosixFilePermissions(File(filesDir, "secret.key").toPath())
        val plain = Files.getPosixFilePermissions(File(filesDir, "plain.txt").toPath())
        val script = Files.getPosixFilePermissions(File(filesDir, "run.sh").toPath())

        assertEquals(
            "0600 必须原样保留",
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            secret,
        )
        assertFalse("group/other 不能读到私密文件", secret.contains(PosixFilePermission.GROUP_READ))
        assertFalse("group/other 不能读到私密文件", secret.contains(PosixFilePermission.OTHERS_READ))
        assertFalse("没有可执行位就不能凭空给", secret.contains(PosixFilePermission.OWNER_EXECUTE))

        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            ),
            plain,
        )
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE,
            ),
            script,
        )

        assertEquals("只有 run.sh 带可执行位", 1, summary.executableFiles)
        assertEquals(0, summary.entriesWithoutUnixMode)
        assertFalse("权限位都来自 ZIP，不应报告为猜测", summary.permissionsAreGuessed)
        assertNotNull(dao.getById(id))
    }

    // ------------------------------------------------------------------ 3. 绝不覆盖现有工作区

    /**
     * 导入永远是"新建工作区"：现有工作区的文件、DB 记录都不能被动，往已被占用的目标目录再导一次必须直接拒绝。
     */
    @Test
    fun importingNeverTouchesAnExistingWorkspaceAndRejectsAnOccupiedTarget() = runBlocking<Unit> {
        // 先造一个"用户已有"的工作区：真实目录 + 真实 DB 记录
        val existingId = UUID.randomUUID().toString()
        val existing = newWorkspace(existingId, "Existing")
        manager.ensureWorkspace(existingId)
        val keep = File(manager.filesDir(existingId), "keep.txt")
        keep.writeText("owner data")
        val keepMtime = keep.lastModified()
        dao.upsert(existing)

        val zipFile = writeZip(
            "fresh.zip",
            legacyZip(files = listOf("new.txt" to "new content".toByteArray(Charsets.UTF_8))),
        )
        val id = UUID.randomUUID().toString()
        val workspace = newWorkspace(id, "Imported")
        val summary = importInto(workspace) { FileInputStream(zipFile) }

        assertEquals("new content", File(manager.filesDir(id), "new.txt").readText())
        assertEquals(1, summary.fileCount)

        // 老工作区一动不动
        assertEquals("owner data", keep.readText())
        assertEquals("老文件的 mtime 不能被写入波及", keepMtime, keep.lastModified())
        assertEquals("Existing", dao.getById(existingId)!!.name)
        assertEquals(existingId, dao.getById(existingId)!!.root)
        assertEquals(2, dao.getAll().size)
        assertFalse("老工作区里不能冒出新的导入文件", File(manager.filesDir(existingId), "new.txt").exists())

        // 目标目录已存在 → 直接拒绝（不是覆盖，也不是"合并"）
        val second = runCatching { importInto(workspace) { FileInputStream(zipFile) } }.exceptionOrNull()
        assertTrue("第二次导入必须失败，实际：$second", second is IllegalArgumentException)
        assertTrue("错误信息要说清是目标已存在：${second?.message}", second?.message.orEmpty().contains("already exists"))
        assertEquals("失败的第二次导入不能改动数据库", 2, dao.getAll().size)
        assertEquals("new content", File(manager.filesDir(id), "new.txt").readText())
        assertEquals("owner data", keep.readText())
        assertEquals(0, WorkspaceImportStaging.activeLeaseCount())
    }

    // ------------------------------------------------------------------ 4. 失败 / 取消都不落 DB

    /**
     * 失败与取消都必须留下"什么都没发生"的状态：DB 无记录、无正式目录、无 staging 残骸。
     *
     * 这是最贵的一类 bug——数据库里有记录而目录不存在（ghost 工作区），或者目录存在而记录不存在（孤儿文件）。
     */
    @Test
    fun failedOrCancelledImportsLeaveNoWorkspaceRowAndNoFiles() = runBlocking<Unit> {
        // (a) 损坏包：把完整包截断一半，中央目录/EOCD 全部丢失
        val complete = legacyZip(
            files = listOf(
                "a.txt" to "0123456789".toByteArray(Charsets.UTF_8),
                "b/c.txt" to "c".toByteArray(Charsets.UTF_8),
            ),
        )
        val truncated = complete.copyOf(complete.size / 2)
        val brokenZip = writeZip("broken.zip", truncated)
        val brokenId = UUID.randomUUID().toString()
        val broken = newWorkspace(brokenId, "Broken")

        val error = runCatching { importInto(broken) { FileInputStream(brokenZip) } }.exceptionOrNull()
        assertTrue("损坏包必须抛 IOException，实际：$error", error is IOException)
        assertTrue("错误信息不能是空的", error!!.message.orEmpty().isNotBlank())
        assertNull("失败不能落库", dao.getById(brokenId))
        assertEquals(0, dao.getAll().size)
        assertFalse("失败不能留下正式目录", File(workspacesBase, brokenId).exists())
        assertFalse("失败必须删掉自己的 staging", File(workspacesBase, "${WorkspaceImportStaging.STAGING_NAME_PREFIX}$brokenId").exists())
        assertEquals(0, WorkspaceImportStaging.activeLeaseCount())

        // (b) 取消：在最后一个进度回调（文件写完、即将发布）里 cancel 掉导入协程
        val zipFile = writeZip(
            "cancelled.zip",
            legacyZip(files = listOf("a.txt" to "0123456789".toByteArray(Charsets.UTF_8))),
        )
        val cancelId = UUID.randomUUID().toString()
        val cancelled = newWorkspace(cancelId, "Cancelled")
        lateinit var job: Job
        var sawFinalReport = false
        job = launch {
            importInto(
                workspace = cancelled,
                open = { FileInputStream(zipFile) },
                // 每块缓冲区都回调，才能精确地在"最后一个回调"里取消
                importer = WorkspaceFilesImporter(progressIntervalNanos = 0L),
                onProgress = { _, _, current ->
                    if (current.isEmpty()) {
                        sawFinalReport = true
                        job.cancel()
                    }
                },
            )
        }
        job.join()

        assertTrue("必须走到最后一个进度回调", sawFinalReport)
        assertTrue("导入协程应处于取消状态", job.isCancelled)
        assertNull("取消不能落库", dao.getById(cancelId))
        assertEquals(0, dao.getAll().size)
        assertFalse("取消不能发布正式目录", File(workspacesBase, cancelId).exists())
        assertFalse("取消必须删掉 staging", File(workspacesBase, "${WorkspaceImportStaging.STAGING_NAME_PREFIX}$cancelId").exists())
        assertEquals(0, WorkspaceImportStaging.activeLeaseCount())
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * 走**生产同一条注册链**：`workspaceFilesImporter.importWorkspace(...)` +
     * `WorkspaceImportStaging.withStagingLease(...)`，register 里先 `ensureWorkspace` 再 `dao.upsert`。
     *
     * 与 `WorkspaceRepository.importWorkspaceFiles` 的差别只有"新 id 怎么来"和"没有 UI 回调"，
     * 发布/回滚/落库语义完全一致。
     */
    private suspend fun importInto(
        workspace: WorkspaceEntity,
        importer: WorkspaceFilesImporter = WorkspaceFilesImporter(),
        stripWrapper: String? = null,
        onProgress: (bytes: Long, files: Int, current: String) -> Unit = { _, _, _ -> },
        open: () -> InputStream,
    ): WorkspaceFilesImporter.Summary {
        val finalDir = manager.workspaceDir(workspace.root)
        val stagingRoot = File(
            workspacesBase,
            WorkspaceImportStaging.STAGING_NAME_PREFIX + workspace.root,
        )
        return WorkspaceImportStaging.withStagingLease(stagingRoot) {
            open().use { input ->
            importer.importWorkspace(
                source = input,
                stagingRoot = stagingRoot,
                finalDir = finalDir,
                stripWrapper = stripWrapper,
                onProgress = onProgress,
                register = { _ ->
                    // 顺序与生产一致：先把 files/linux/tmp 建好，再写 DB。
                    // 任一步抛异常，导入器都会把已发布的目录回滚掉。
                    manager.ensureWorkspace(workspace.root)
                    dao.upsert(workspace)
                },
            )
            }
        }
    }

    private fun newWorkspace(id: String, name: String): WorkspaceEntity {
        val now = System.currentTimeMillis()
        return WorkspaceEntity(
            id = id,
            name = name,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = now,
        )
    }

    private fun writeZip(name: String, bytes: ByteArray): File =
        File(testRoot, name).apply { writeBytes(bytes) }

    private fun posixPermissionsSupported(): Boolean = try {
        val probe = File(testRoot, "posix-probe.txt")
        probe.writeText("probe")
        val path = probe.toPath()
        Files.setPosixFilePermissions(
            path,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
        Files.getPosixFilePermissions(path).contains(PosixFilePermission.OWNER_READ)
    } catch (_: UnsupportedOperationException) {
        false
    } catch (_: IOException) {
        false
    }

    /** 旧导出器那条路径：`ZipOutputStream` + 普通 `ZipEntry`，没有外部属性、没有权限位。 */
    private fun legacyZip(
        files: List<Pair<String, ByteArray>>,
        dirs: List<String> = emptyList(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (dir in dirs) {
                require(dir.endsWith("/")) { "目录条目必须以 / 结尾：$dir" }
                zip.putNextEntry(ZipEntry(dir))
                zip.closeEntry()
            }
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private object UnixMode {
        /** 普通文件（S_IFREG = 0o100000）拼上权限位。 */
        fun file(permissions: Int): Int = 0x8000 or permissions
    }

    /**
     * 手工拼 ZIP 字节：`ZipOutputStream` 写不出自定义 Unix 权限位，
     * 只有中央目录的"外部属性 + version made by = UNIX"才携带它们，所以这里自己写。
     */
    private class RawZip {
        private class Item(val name: String, val data: ByteArray, val unixMode: Int?)

        private val items = ArrayList<Item>()

        fun file(name: String, content: ByteArray, unixMode: Int? = null): RawZip {
            items.add(Item(name, content, unixMode))
            return this
        }

        fun build(): ByteArray {
            val out = ByteArrayOutputStream()
            val central = ByteArrayOutputStream()
            for (item in items) {
                val name = item.name.toByteArray(Charsets.UTF_8)
                val crcValue = CRC32().apply { update(item.data) }.value
                val offset = out.size().toLong()

                // 本地文件头（store，不压缩，避免额外分支）
                le32(out, 0x04034b50L)
                le16(out, 20) // version needed
                le16(out, 0) // flags
                le16(out, 0) // method = store
                le16(out, 0) // time
                le16(out, 0x21) // date
                le32(out, crcValue)
                le32(out, item.data.size.toLong()) // compressed size
                le32(out, item.data.size.toLong()) // uncompressed size
                le16(out, name.size)
                le16(out, 0) // extra length
                out.write(name)
                out.write(item.data)

                // 中央目录记录
                le32(central, 0x02014b50L)
                // version made by：带权限位时声明 UNIX(3)，否则 MS-DOS(0)
                le16(central, if (item.unixMode != null) (3 shl 8) or 30 else 20)
                le16(central, 20) // version needed
                le16(central, 0) // flags
                le16(central, 0) // method
                le16(central, 0) // time
                le16(central, 0x21) // date
                le32(central, crcValue)
                le32(central, item.data.size.toLong())
                le32(central, item.data.size.toLong())
                le16(central, name.size)
                le16(central, 0) // extra
                le16(central, 0) // comment
                le16(central, 0) // disk start
                le16(central, 0) // internal attrs
                le32(central, ((item.unixMode ?: 0).toLong() shl 16)) // external attrs
                le32(central, offset)
                central.write(name)
            }

            val centralOffset = out.size().toLong()
            val centralBytes = central.toByteArray()
            out.write(centralBytes)
            // EOCD
            le32(out, 0x06054b50L)
            le16(out, 0) // disk
            le16(out, 0) // cd start disk
            le16(out, items.size)
            le16(out, items.size)
            le32(out, centralBytes.size.toLong())
            le32(out, centralOffset)
            le16(out, 0) // comment length
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
    }
}

package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 把 ZIP 无损导入成一个**新的**本地工作区文件区。对应 [WorkspaceFilesExporter]。
 *
 * 纯 JVM 实现（只依赖 java.io / java.nio.file / java.util.zip / kotlinx.coroutines），不碰 Android API，
 * 因此可以直接在 `app/src/test` 的普通 JVM 单测里跑（见 `WorkspaceFilesImporterTest`）。
 *
 * 设计要点（安全优先，绝不做"部分成功"）：
 * - **只写新目录**：调用方给出 [stagingRoot]（必须不存在）与 [finalDir]（必须不存在）。解包全部在
 *   `stagingRoot/files` 里进行，全部校验通过后才把 staging 目录整体 rename 成 [finalDir]（同父目录、
 *   同文件系统，`renameTo` 即原子发布）。现有工作区、数据库、设置、LinuxRootfs 全程不参与，不会被覆盖。
 * - **先流式落盘再解析**：[InputStream] 不可 seek，先按 [Limits.maxArchiveBytes] 限长拷进
 *   `stagingRoot/archive.zip`，再读中央目录。中央目录是 ZIP 里唯一携带 Unix 权限位（外部属性）的地方；
 *   只看 `ZipInputStream` 无法识别符号链接/特殊文件，所以必须走中央目录。
 * - **拒绝可疑内容**：Zip Slip（`..`、绝对路径、盘符）、反斜杠、重复路径、文件与目录同名、
 *   控制字符、非 UTF-8 条目名、符号链接与 FIFO/socket/设备节点、加密条目、空/截断/损坏 ZIP、
 *   条目超限、展开超限、声明尺寸与实际尺寸不符、CRC 不符、带 payload 的"目录"条目 —— 全部抛
 *   [IOException]；staging 目录被删除，不存在"看起来成功"的半套文件。
 * - **路径大小写不动**：落盘目标是本机内部 Linux ext4（区分大小写），`A.txt` 与 `a.txt` 是两个合法
 *   文件，不再为了"将来可能同步到不区分大小写的存储"提前拒绝合法包。
 * - **不信任 ZIP 声明**：`entry.size` 只用于提前拒绝，真实字节边写边数，超限立刻失败；写完再核对
 *   实际长度与 CRC32。EOF 记录（EOCD / ZIP64）的磁盘号、条目数、中央目录偏移与长度都要互相吻合，
 *   中央目录必须正好结束在 EOF 记录起点。
 * - **可取消**：落盘、解包每块缓冲区都检查协程取消；取消只删除本次 staging 目录。发布阶段用
 *   [NonCancellable] 且**只在 register 失败时**回滚：register 已经落库之后再取消绝不删目录，
 *   否则数据库里会留下指向空目录的 ghost 工作区。
 * - **权限**：只有中央目录里明确带 Unix 权限位（version made by = UNIX）且带可执行位时才恢复可执行位，
 *   也绝不按文件名猜 chmod。恢复失败或文件系统不支持 POSIX 权限时不会声称成功。fork 旧版
 *   [WorkspaceFilesExporter] 产出的裸 ZIP 没有权限位、也不含符号链接，导入后保持文件系统默认
 *   0664/0644（可执行位丢失），[Summary.entriesWithoutUnixMode] 给出数量供 UI 明确说明。
 * - **外层包装目录**：[stripWrapper] 非 null 时**显式**剥掉这一层（如 `workspace/`、`files/`），
 *   并要求所有条目都在该目录下；不做"看起来只有一层就自动剥掉"的猜测。
 */
class WorkspaceFilesImporter(
    private val limits: Limits = Limits(),
    private val copyBufferBytes: Int = DEFAULT_COPY_BUFFER_BYTES,
    /** 进度回调最小间隔，避免大文件按块刷 UI。 */
    private val progressIntervalNanos: Long = DEFAULT_PROGRESS_INTERVAL_NANOS,
) {
    init {
        require(copyBufferBytes > 0) { "Copy buffer size must be positive" }
    }

    /** 导入限额。默认值刻意宽松到能装下真实的大工作区（流式写盘，不占内存）。 */
    data class Limits(
        val maxEntries: Int = 100_000,
        val maxTotalBytes: Long = 12L * 1024 * 1024 * 1024,
        val maxFileBytes: Long = 4L * 1024 * 1024 * 1024,
        val maxArchiveBytes: Long = 4L * 1024 * 1024 * 1024,
        val maxPathLength: Int = 4096,
        val maxPathDepth: Int = 128,
    ) {
        init {
            require(maxEntries > 0) { "maxEntries must be positive" }
            require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
            require(maxFileBytes > 0) { "maxFileBytes must be positive" }
            require(maxArchiveBytes > 0) { "maxArchiveBytes must be positive" }
            require(maxPathLength > 0) { "maxPathLength must be positive" }
            require(maxPathDepth > 0) { "maxPathDepth must be positive" }
        }
    }

    /** 一次导入的结果。 */
    data class Summary(
        /** 写出的普通文件数（不含目录条目）。 */
        val fileCount: Int,
        /** 落地目录数（含为文件隐式创建的父目录与 ZIP 里的空目录）。 */
        val directoryCount: Int,
        /** 实际写出的字节数。 */
        val byteCount: Long,
        /** 确实恢复了可执行位的文件数（仅当 ZIP 带 Unix 权限位，且设置后读回校验通过）。 */
        val executableFiles: Int,
        /** 没有 Unix 权限位、只能按默认 0644 落地的普通文件数。 */
        val entriesWithoutUnixMode: Int,
        /** 实际剥掉的外层包装目录名，没有则为 null。 */
        val strippedWrapper: String?,
    ) {
        /** 旧版裸 ZIP 无法保证可执行位（导出时本来就没记录）。 */
        val permissionsAreGuessed: Boolean get() = entriesWithoutUnixMode > 0
    }

    /**
     * 解包 [source]（ZIP）→ 全量校验 → 原子发布到 [finalDir] → 调 [register]。
     *
     * @param stagingRoot 本次导入独占的临时目录，必须**不存在**（本方法创建；失败/取消时本方法删除；
     *   悬空符号链接也算"存在"，直接拒绝）
     * @param finalDir 发布目标，必须**不存在**；发布是同父目录下的目录 rename，要求与 [stagingRoot]
     *   在同一文件系统
     * @param stripWrapper 非 null 时显式剥掉这一层包装目录（例如 [WRAPPER_WORKSPACE] / [WRAPPER_FILES]）
     * @param register 发布成功后执行的注册动作（一般是写数据库）。抛异常时把已发布的目录删掉再向上抛，
     *   不会留下"有记录没文件"或"有文件没记录"的半套状态；反过来，[register] 已经成功之后
     *   （数据已落库）**绝不**再回滚目录，避免取消制造 ghost 工作区
     * @param onProgress (已写字节, 已写文件数, 当前相对路径)
     */
    suspend fun importWorkspace(
        source: InputStream,
        stagingRoot: File,
        finalDir: File,
        stripWrapper: String? = null,
        onProgress: (bytes: Long, files: Int, current: String) -> Unit = { _, _, _ -> },
        register: suspend (Summary) -> Unit = {},
    ): Summary {
        // 前置检查一律用 NOFOLLOW_LINKS：File.exists() 会跟随符号链接，悬空的 symlink 会被判成"不存在"，
        // 随后 mkdirs()/重命名就会顺着链接把内容写到链接目标（可能在目标父目录之外），
        // 而 deleteRecursively() 又删不掉链接本身，留下半套状态。
        if (Files.exists(stagingRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalArgumentException("Staging directory already exists: $stagingRoot")
        }
        if (Files.exists(finalDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalArgumentException("Import target already exists: $finalDir")
        }
        val wrapper = stripWrapper?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        require(stripWrapper == null || wrapper != null) { "Wrapper directory name is empty" }
        val parent = finalDir.absoluteFile.parentFile
            ?: throw IOException("Import target has no parent: $finalDir")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Cannot create import target parent: $parent")
        }
        val stagingParent = stagingRoot.absoluteFile.parentFile
            ?: throw IOException("Staging directory has no parent: $stagingRoot")
        if (!stagingParent.isDirectory && !stagingParent.mkdirs()) {
            throw IOException("Cannot create staging parent: $stagingParent")
        }
        // 发布是一次目录 rename：staging 与目标必须在同一文件系统，否则 renameTo 会失败
        // （跨设备时甚至可能被当成"成功"以外的实现分支），这里提前明确拒绝。
        if (!sameFileSystem(stagingParent, parent)) {
            throw IOException(
                "导入临时目录与目标目录不在同一文件系统，无法原子发布：$stagingParent / $parent"
            )
        }

        val contentDir = File(stagingRoot, CONTENT_DIR_NAME)
        val archive = File(stagingRoot, STAGING_ARCHIVE_NAME)
        /** 目录已经 rename 到 [finalDir]。 */
        var committed = false
        /** [register] 已成功返回（数据已落库）。此后即使协程被取消也绝不能删 [finalDir]。 */
        var registered = false
        try {
            if (!stagingRoot.mkdirs()) throw IOException("Cannot create staging directory: $stagingRoot")
            // 1) 流式落盘（不可 seek 的 URI 流也能处理），带压缩包大小上限
            spoolArchive(source, archive)
            // 2) 解析中央目录：条目数、名字、Unix 权限位、加密标记
            val central = readCentralDirectory(archive)
            // 3) 结构校验：路径安全、重复/冲突、控制字符、符号链接与特殊文件
            val plan = planEntries(central, wrapper)
            // 4) 解包到 staging（边写边数、边校验 CRC 与尺寸）
            if (!contentDir.mkdirs()) {
                throw IOException("Cannot create staging content directory: $contentDir")
            }
            val summary = extractAll(
                archive = archive,
                destination = contentDir,
                plan = plan,
                onProgress = onProgress,
            )
            // 5) 全部校验通过才发布：staging 目录整体 rename；register 失败则回滚。
            //    进 publish 前再查一次取消：否则"最后一个进度回调里取消"仍会照常发布 + 写库。
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                if (!archive.delete() && archive.exists()) throw IOException("Cannot remove temporary ZIP before publishing")
                if (!stagingRoot.renameTo(finalDir)) {
                    throw IOException("Cannot publish imported workspace: $stagingRoot -> $finalDir")
                }
                committed = true
                register(summary)
                registered = true
            }
            return summary
        } catch (e: Throwable) {
            // 只清理本次自己的临时目录。已经 register 成功（记录已落库）时绝不删 finalDir：
            // 发布本身在 NonCancellable 里完成，父协程恢复后的取消不会回滚文件，这里也不能回滚，
            // 否则数据库里会留下指向不存在目录的 ghost 工作区。
            withContext(NonCancellable) {
                if (committed && !registered) {
                    finalDir.deleteRecursively()
                }
                stagingRoot.deleteRecursively()
            }
            throw e
        }
    }

    // ---------------------------------------------------------------- 中央目录

    /** ZIP 中央目录里的一条记录。只有这里能读到 Unix 权限位（外部属性）。 */
    private data class CentralEntry(
        val name: String,
        /** 0 表示没有 Unix 权限位（DOS/Windows 或旧导出器产生的裸 ZIP）。 */
        val unixMode: Int,
        val hasUnixMode: Boolean,
        /** 符号链接、FIFO、socket、字符/块设备之一。 */
        val unixSymlinkOrSpecial: Boolean,
        val isDirectory: Boolean,
    )

    private suspend fun readCentralDirectory(archive: File): List<CentralEntry> {
        val fileLength = archive.length()
        if (fileLength < EOCD_MIN_BYTES) {
            throw IOException("ZIP 包为空或不完整（$fileLength 字节），不是有效的 ZIP 文件")
        }
        RandomAccessFile(archive, "r").use { raf ->
            val tailLength = minOf(fileLength, EOCD_SEARCH_BYTES).toInt()
            val tail = ByteArray(tailLength)
            raf.seek(fileLength - tailLength)
            raf.readFully(tail)
            val eocdIndex = findEndOfCentralDirectory(tail)
                ?: throw IOException("找不到 ZIP 目录结尾记录（EOCD），文件可能被截断或不是 ZIP")
            val eocdAbsolute = fileLength - tailLength + eocdIndex
            val diskNumber = readU16(tail, eocdIndex + 4)
            val cdStartDisk = readU16(tail, eocdIndex + 6)
            val entriesOnDisk = readU16(tail, eocdIndex + 8)
            var totalEntries = readU16(tail, eocdIndex + 10)
            var cdSize = readU32(tail, eocdIndex + 12)
            var cdOffset = readU32(tail, eocdIndex + 16)
            if (diskNumber != 0L || cdStartDisk != 0L) {
                throw IOException("ZIP 是分卷包（第 $diskNumber 张盘，目录在第 $cdStartDisk 张），不支持导入")
            }
            // 中央目录必须**正好**结束在这一条结尾记录（ZIP64 时是 ZIP64 结尾记录）的起点：
            // 这样"中央目录长度被改小/改大""EOCD 被删"两头都对不上，无法靠伪造一个 EOCD 蒙过去。
            val cdEnd: Long
            // 65535 is both a legal ZIP32 count and the ZIP64 sentinel. Python/Info-ZIP
            // only write a locator above 65535; inspect it instead of rejecting that boundary.
            val hasZip64Locator = if (eocdAbsolute >= ZIP64_LOCATOR_BYTES) {
                val signature = ByteArray(4)
                raf.seek(eocdAbsolute - ZIP64_LOCATOR_BYTES)
                raf.readFully(signature)
                readU32(signature, 0) == ZIP64_EOCD_LOCATOR_SIG
            } else false
            val requiresZip64Sizes = cdSize == U32_MAX || cdOffset == U32_MAX
            if (requiresZip64Sizes || hasZip64Locator) {
                if (eocdAbsolute < ZIP64_LOCATOR_BYTES) {
                    throw IOException("ZIP64 结尾记录缺失（EOCD 用了 64 位哨兵值但没有定位记录）")
                }
                val locator = ByteArray(ZIP64_LOCATOR_BYTES.toInt())
                raf.seek(eocdAbsolute - ZIP64_LOCATOR_BYTES)
                raf.readFully(locator)
                if (readU32(locator, 0) != ZIP64_EOCD_LOCATOR_SIG) throw IOException("ZIP64 定位记录签名不符")
                val locatorDisk = readU32(locator, 4)
                val totalDisks = readU32(locator, 16)
                if (locatorDisk != 0L || totalDisks != 1L) {
                    throw IOException("ZIP64 定位记录指向分卷包（disk=$locatorDisk, disks=$totalDisks），不支持导入")
                }
                val zip64Offset = readU64(locator, 8)
                if (zip64Offset < 0L || zip64Offset > fileLength - ZIP64_EOCD_BYTES) {
                    throw IOException("ZIP64 结尾记录越界")
                }
                val record = ByteArray(ZIP64_EOCD_BYTES.toInt())
                raf.seek(zip64Offset)
                raf.readFully(record)
                if (readU32(record, 0) != ZIP64_EOCD_SIG) throw IOException("ZIP64 结尾记录签名不符")
                val recordSize = readU64(record, 4)
                val locatorOffset = eocdAbsolute - ZIP64_LOCATOR_BYTES
                if (recordSize < ZIP64_EOCD_BYTES - 12 || recordSize > locatorOffset - zip64Offset - 12 ||
                    zip64Offset + 12 + recordSize != locatorOffset
                ) {
                    throw IOException("ZIP64 结尾记录长度非法（$recordSize）")
                }
                val zip64Disk = readU32(record, 16)
                val zip64CdDisk = readU32(record, 20)
                if (zip64Disk != 0L || zip64CdDisk != 0L) {
                    throw IOException("ZIP 是分卷包（ZIP64 第 $zip64Disk 张盘，目录在第 $zip64CdDisk 张），不支持导入")
                }
                val zip64EntriesOnDisk = readU64(record, 24)
                totalEntries = readU64(record, 32)
                cdSize = readU64(record, 40)
                cdOffset = readU64(record, 48)
                // readU64 最高位为 1 时是负数：那已经超出 Long 能表示的范围，一律按溢出拒绝
                if (zip64EntriesOnDisk < 0L || totalEntries < 0L || cdSize < 0L || cdOffset < 0L) {
                    throw IOException("ZIP64 结尾记录里的数值溢出（超过 2^63-1），包不可信")
                }
                if (zip64EntriesOnDisk != totalEntries) {
                    throw IOException("ZIP64 中央目录条目数不一致（本盘 $zip64EntriesOnDisk 条，总数 $totalEntries 条）")
                }
                cdEnd = zip64Offset
            } else {
                if (entriesOnDisk != totalEntries) {
                    throw IOException("ZIP 中央目录条目数不一致（本盘 $entriesOnDisk 条，总数 $totalEntries 条）")
                }
                cdEnd = eocdAbsolute
            }
            // 0 条是合法的：fork 旧版导出空工作区就是只有 EOCD 的空包，必须能往返导入
            if (totalEntries > limits.maxEntries.toLong()) {
                throw IOException("ZIP 条目数 $totalEntries 超过上限 ${limits.maxEntries}")
            }
            // 越界检查全用减法：cdOffset + cdSize 直接相加可能溢出成负数而跳过检查
            if (cdOffset > fileLength || cdSize > fileLength || cdOffset > fileLength - cdSize) {
                throw IOException("ZIP 中央目录越界（可能被截断或伪造）")
            }
            if (cdOffset + cdSize != cdEnd) {
                throw IOException(
                    "ZIP 中央目录与结尾记录不连续（条目表声明结束于 ${cdOffset + cdSize}，实际应为 $cdEnd）"
                )
            }
            val entries = ArrayList<CentralEntry>(minOf(totalEntries, 4096L).toInt())
            val header = ByteArray(CD_HEADER_BYTES.toInt())
            val context = currentCoroutineContext()
            var position = cdOffset
            for (index in 0L until totalEntries) {
                if (index and 0x3FFL == 0L) context.ensureActive()
                if (position + CD_HEADER_BYTES > cdEnd) throw IOException("ZIP 中央目录被截断")
                raf.seek(position)
                raf.readFully(header)
                if (readU32(header, 0) != CD_ENTRY_SIG) throw IOException("ZIP 中央目录记录签名不符")
                val versionMadeBy = readU16(header, 4)
                val flags = readU16(header, 8)
                val nameLength = readU16(header, 28)
                val extraLength = readU16(header, 30)
                val commentLength = readU16(header, 32)
                if (readU16(header, 34) != 0L) throw IOException("ZIP 条目指向另一分卷，不支持导入")
                val externalAttributes = readU32(header, 38)
                val recordLength = CD_HEADER_BYTES + nameLength + extraLength + commentLength
                if (position + recordLength > cdEnd || nameLength > MAX_NAME_BYTES) {
                    throw IOException("ZIP 中央目录记录越界")
                }
                val nameBytes = ByteArray(nameLength.toInt())
                raf.readFully(nameBytes)
                val name = decodeUtf8Name(nameBytes, index)
                if (flags and FLAG_ENCRYPTED != 0L) {
                    throw IOException("ZIP 条目已加密，无法导入：${describe(name, index)}")
                }
                val madeByUnix = (versionMadeBy ushr 8) == MADE_BY_UNIX.toLong()
                val mode = if (madeByUnix) ((externalAttributes ushr 16) and 0xFFFFL).toInt() else 0
                val fileType = mode and FILE_TYPE_MASK
                val symlinkOrSpecial = madeByUnix && mode != 0 &&
                    fileType != 0 && fileType != FILE_TYPE_REGULAR && fileType != FILE_TYPE_DIRECTORY
                val isDirectory = name.endsWith('/') ||
                    fileType == FILE_TYPE_DIRECTORY ||
                    (externalAttributes and DOS_DIRECTORY_ATTR) != 0L
                entries.add(
                    CentralEntry(
                        name = name,
                        unixMode = mode,
                        hasUnixMode = madeByUnix && mode != 0,
                        unixSymlinkOrSpecial = symlinkOrSpecial,
                        isDirectory = isDirectory,
                    )
                )
                position += recordLength
            }
            // 条目表必须刚好填满声明的 cdSize：多一个字节或少一个字节都说明长度被伪造
            if (position != cdOffset + cdSize) {
                throw IOException(
                    "ZIP 中央目录实际长度与声明不符（读到 $position，声明结束 ${cdOffset + cdSize}）"
                )
            }
            return entries
        }
    }

    /**
     * 从尾部向前找 EOCD，返回它在 [tail] 里的下标。
     *
     * 找不到时**必须返回 null**（而不是 -1 之类的哨兵值），否则调用方的 `?:` 永远不会命中默认分支，
     * "没有 EOCD"的包会被当成找到了，从 -1 继续往下解析。
     */
    private fun findEndOfCentralDirectory(tail: ByteArray): Int? {
        // 注释里出现同样字节模式不影响，因为还要核对注释长度恰好到结尾
        for (index in tail.size - EOCD_MIN_BYTES.toInt() downTo 0) {
            if (readU32(tail, index) != EOCD_SIG) continue
            val commentLength = readU16(tail, index + 20)
            if (index.toLong() + EOCD_MIN_BYTES + commentLength == tail.size.toLong()) return index
        }
        return null
    }

    /**
     * 严格按 UTF-8 解条目名：非法字节序列一律拒绝。
     *
     * 不能用 `String(bytes, UTF_8)`（会把坏字节换成 U+FFFD），那会让"两个不同的坏名字"塌成同一个
     * 无害名字，路径检查、重复检查全部失真。旧版导出器写的是 UTF-8，legacy 非 UTF-8 包明确不支持。
     */
    private fun decodeUtf8Name(bytes: ByteArray, index: Long): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw IOException(
                "ZIP 第 ${index + 1} 个条目的文件名不是合法 UTF-8（旧版导出器写的是 UTF-8，不支持 legacy 非 UTF-8 文件名编码）",
                e,
            )
        }
    }

    // ---------------------------------------------------------------- 结构校验

    private data class PlannedEntry(
        /** ZIP 里的原始名字，用于和 `ZipFile` 的条目逐条对齐。 */
        val zipName: String,
        /** 去掉包装目录后的相对路径，无前导/尾随斜杠；null 表示该条目整体跳过（包装目录自身）。 */
        val relativePath: String?,
        val isDirectory: Boolean,
        val executable: Boolean,
        val hasUnixMode: Boolean,
        val unixMode: Int,
    )

    private class Plan(
        val entries: List<PlannedEntry>,
        val wrapper: String?,
        val fileCount: Int,
        val directoryCount: Int,
    )

    private fun planEntries(central: List<CentralEntry>, wrapper: String?): Plan {
        val planned = ArrayList<PlannedEntry>(central.size)
        val registry = PathRegistry()
        var files = 0
        central.forEachIndexed { index, entry ->
            if (entry.unixSymlinkOrSpecial) {
                throw IOException(
                    "ZIP 条目是符号链接或特殊文件（FIFO/socket/设备），出于安全不导入：" +
                        describe(entry.name, index.toLong())
                )
            }
            val relative = normalizeEntryName(entry.name, wrapper, index.toLong())
            val isDirectory = relative == null || entry.isDirectory
            if (relative != null) {
                registry.register(relative, isFile = !isDirectory, display = describe(entry.name, index.toLong()))
                if (!isDirectory) files++
                if (files > limits.maxEntries) throw IOException("ZIP 文件数超过上限 ${limits.maxEntries}")
            }
            planned.add(
                PlannedEntry(
                    zipName = entry.name,
                    relativePath = relative,
                    isDirectory = isDirectory,
                    executable = !isDirectory && entry.hasUnixMode && (entry.unixMode and EXEC_BITS) != 0,
                    hasUnixMode = entry.hasUnixMode,
                    unixMode = entry.unixMode,
                )
            )
        }
        return Plan(
            entries = planned,
            wrapper = wrapper,
            fileCount = files,
            directoryCount = registry.directoryCount,
        )
    }

    /**
     * 校验并规范化 ZIP 条目名。
     *
     * @return 需要落地的相对路径；null 表示该条目可整条跳过（包装目录自身的条目）
     */
    private fun normalizeEntryName(raw: String, wrapper: String?, index: Long): String? {
        if (raw.isEmpty()) throw IOException("ZIP 第 ${index + 1} 个条目名字为空")
        for (ch in raw) {
            if (ch.code < 0x20 || ch.code == 0x7F) {
                throw IOException("ZIP 条目名包含控制字符：${describe(raw, index)}")
            }
        }
        if (raw.contains('\\')) {
            throw IOException("ZIP 条目名包含反斜杠（Windows 分隔符），拒绝导入：${describe(raw, index)}")
        }
        if (raw.startsWith('/')) {
            throw IOException("ZIP 条目名是绝对路径（Zip Slip），拒绝导入：${describe(raw, index)}")
        }
        if (raw.length >= 2 && raw[1] == ':' && raw[0].isLetter()) {
            throw IOException("ZIP 条目名带 Windows 盘符，拒绝导入：${describe(raw, index)}")
        }
        var name = raw
        if (wrapper != null) {
            if (!name.startsWith("$wrapper/")) {
                throw IOException("ZIP 条目不在指定的包装目录 $wrapper/ 下：${describe(raw, index)}")
            }
            name = name.substring(wrapper.length + 1)
            if (name.isEmpty()) return null
        }
        if (name.endsWith("/")) name = name.trimEnd('/')
        if (name.isEmpty()) return null
        if (name.length > limits.maxPathLength) {
            throw IOException("ZIP 条目路径过长（>${limits.maxPathLength} 字符）：${describe(raw, index)}")
        }
        val segments = name.split('/')
        if (segments.size > limits.maxPathDepth) {
            throw IOException("ZIP 条目层级过深（>${limits.maxPathDepth}）：${describe(raw, index)}")
        }
        for (segment in segments) {
            if (segment.isEmpty()) {
                throw IOException("ZIP 条目名包含空路径段：${describe(raw, index)}")
            }
            if (segment == "." || segment == "..") {
                throw IOException("ZIP 条目名包含相对路径段（Zip Slip）：${describe(raw, index)}")
            }
            if (segment.toByteArray(Charsets.UTF_8).size > MAX_SEGMENT_BYTES) {
                throw IOException(
                    "ZIP 条目名有超长路径段（>${MAX_SEGMENT_BYTES} 字节）：${describe(raw, index)}"
                )
            }
        }
        return name
    }

    /**
     * 已登记路径表：抓重复路径、文件/目录冲突，并保证每个父路径都被登记为目录。
     *
     * 大小写**严格敏感**：导入目标是本机内部 Linux ext4（区分大小写），`A.txt` 与 `a.txt` 是两个
     * 合法文件。以前为"将来可能同步到 FAT"提前拒绝整个包，等于用一个不存在的下游风险否掉合法数据，
     * 这里不再做折叠比较。冲突判定只按实际落盘语义（重复同名、同名既是文件又是目录）来做。
     */
    private class PathRegistry {
        /** path -> isFile。隐式父目录记为 false。 */
        private val kinds = HashMap<String, Boolean>()
        /** 真正在 ZIP 里出现过条目（显式）的路径，用于区分"重复条目"和"隐式父目录"。 */
        private val explicit = HashSet<String>()
        var directoryCount: Int = 0
            private set

        fun register(path: String, isFile: Boolean, display: String) {
            // 父路径一律登记为目录：文件条目和目录条目都得先有父目录，
            // 否则"父目录没出现过"的目录不会计入 directoryCount，也无法和后来的同名文件冲突。
            var index = path.indexOf('/')
            while (index >= 0) {
                val parent = path.substring(0, index)
                if (kinds[parent] == true) {
                    throw IOException("ZIP 中 '$display' 的父路径 $parent 已是一个文件")
                }
                if (kinds.putIfAbsent(parent, false) == null) directoryCount++
                index = path.indexOf('/', index + 1)
            }
            val previous = kinds[path]
            if (previous != null && previous != isFile) {
                throw IOException("ZIP 中路径 $path 同时是文件和目录")
            }
            if (!explicit.add(path)) {
                throw IOException("ZIP 中存在重复条目：$path")
            }
            if (kinds.put(path, isFile) == null && !isFile) directoryCount++
        }
    }

    // ---------------------------------------------------------------- 解包

    private suspend fun extractAll(
        archive: File,
        destination: File,
        plan: Plan,
        onProgress: (bytes: Long, files: Int, current: String) -> Unit,
    ): Summary {
        val destinationCanonical = destination.canonicalFile
        var written = 0L
        var files = 0
        var executableFiles = 0
        var withoutUnixMode = 0
        var lastReportAt = 0L
        val buffer = ByteArray(copyBufferBytes)
        val crc = CRC32()

        val zip = try {
            ZipFile(archive, ZipFile.OPEN_READ, Charsets.UTF_8)
        } catch (e: Exception) {
            throw IOException("ZIP 包损坏，无法打开：${e.message}", e)
        }
        zip.use { zf ->
            val zipEntries: List<ZipEntry> = Collections.list(zf.entries())
            if (zipEntries.size != plan.entries.size) {
                throw IOException(
                    "ZIP 中央目录与条目表不一致（${zipEntries.size} vs ${plan.entries.size}），包可能被损坏或伪造"
                )
            }
            for (index in plan.entries.indices) {
                val planned = plan.entries[index]
                val zipEntry: ZipEntry = zipEntries[index]
                currentCoroutineContext().ensureActive()
                if (zipEntry.name != planned.zipName) {
                    throw IOException(
                        "ZIP 中央目录与条目表不一致：'${zipEntry.name}' vs '${planned.zipName}'"
                    )
                }
                if (planned.isDirectory) {
                    // 目录条目必须真的没有内容。以前这里直接 continue 跳过，于是"名字以 / 结尾但塞了
                    // 整段 payload"的条目会被静默丢弃：既不报错也不落地，等于帮攻击者把可疑内容藏起来。
                    // 包装目录（如 `workspace/`）自身的条目同样要过这一关。
                    verifyDirectoryEntryIsEmpty(zf, zipEntry, planned.zipName)
                    val dirPath = planned.relativePath ?: continue
                    val dirTarget = File(destination, dirPath)
                    requireInside(destinationCanonical, dirTarget, planned.zipName)
                    if (!dirTarget.isDirectory && !dirTarget.mkdirs()) {
                        throw IOException("无法创建目录：$dirPath")
                    }
                    continue
                }
                val relativePath = planned.relativePath
                    ?: throw IOException("ZIP 条目路径为空：${planned.zipName}")
                val target = File(destination, relativePath)
                requireInside(destinationCanonical, target, planned.zipName)
                val parent = target.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                    throw IOException("无法创建目录：${parent.name}")
                }
                if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("导入目标已存在同名条目：$relativePath")
                }
                val declaredSize = zipEntry.size
                if (declaredSize > limits.maxFileBytes) {
                    throw IOException(
                        "ZIP 条目 $relativePath 声明大小 $declaredSize，超过单文件上限 ${limits.maxFileBytes}"
                    )
                }
                crc.reset()
                var entryBytes = 0L
                FileOutputStream(target).use { rawOut ->
                    BufferedOutputStream(rawOut, copyBufferBytes).use { out ->
                        // 用 ZipFile 的流：JDK 会按中央目录声明的尺寸截断/报错，不信任条目自述
                        zf.getInputStream(zipEntry).use { input ->
                            while (true) {
                                // 每块缓冲区都检查取消：几 GB 的单文件也不会无视取消写完
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                written += read
                                if (entryBytes > limits.maxFileBytes) {
                                    throw IOException(
                                        "ZIP 条目 $relativePath 展开后超过单文件上限 ${limits.maxFileBytes}"
                                    )
                                }
                                if (written > limits.maxTotalBytes) {
                                    throw IOException("ZIP 展开总大小超过上限 ${limits.maxTotalBytes} 字节")
                                }
                                crc.update(buffer, 0, read)
                                out.write(buffer, 0, read)
                                val now = System.nanoTime()
                                if (now - lastReportAt >= progressIntervalNanos) {
                                    lastReportAt = now
                                    onProgress(written, files, relativePath)
                                }
                            }
                        }
                    }
                }
                if (declaredSize >= 0 && entryBytes != declaredSize) {
                    throw IOException(
                        "ZIP 条目 $relativePath 实际 $entryBytes 字节，与声明的 $declaredSize 不符"
                    )
                }
                if (zipEntry.crc >= 0 && crc.value != zipEntry.crc) {
                    throw IOException(
                        "ZIP 条目 $relativePath CRC 校验失败（期望 ${zipEntry.crc}，实际 ${crc.value}）"
                    )
                }
                val restoredExecutable = applyAttributes(target, planned, zipEntry)
                files++
                if (restoredExecutable) executableFiles++
                if (!planned.hasUnixMode) withoutUnixMode++
                lastReportAt = System.nanoTime()
                onProgress(written, files, relativePath)
            }
        }
        if (files != plan.fileCount) {
            throw IOException("ZIP 解包文件数不一致（$files vs ${plan.fileCount}）")
        }
        onProgress(written, files, "")
        return Summary(
            fileCount = files,
            directoryCount = plan.directoryCount,
            byteCount = written,
            executableFiles = executableFiles,
            entriesWithoutUnixMode = withoutUnixMode,
            strippedWrapper = plan.wrapper,
        )
    }

    /**
     * 恢复条目属性，返回可执行位是否**确实**恢复成功。
     *
     * 只有 ZIP 中央目录明确带 Unix 权限位且带可执行位时才动权限；其它情况一律保持文件系统默认
     * （umask 决定的 0664/0644），绝不按文件名猜 chmod。设置失败、或设置后读回来没有可执行位，
     * 都必须报错或如实降级：不能让 [Summary.executableFiles] 声称恢复了而实际没有
     * （例如挂载点忽略权限位、目标在 FAT/exFAT 上）。
     */
    private fun applyAttributes(target: File, planned: PlannedEntry, entry: ZipEntry): Boolean {
        var restoredExecutable = false
        if (planned.hasUnixMode) {
            // Never broaden a saved 0600/0700 to 0644/0755. Strip special bits,
            // preserve the ordinary owner/group/other permission mask exactly.
            val flags = listOf(
                0x100 to PosixFilePermission.OWNER_READ, 0x80 to PosixFilePermission.OWNER_WRITE,
                0x40 to PosixFilePermission.OWNER_EXECUTE, 0x20 to PosixFilePermission.GROUP_READ,
                0x10 to PosixFilePermission.GROUP_WRITE, 0x8 to PosixFilePermission.GROUP_EXECUTE,
                0x4 to PosixFilePermission.OTHERS_READ, 0x2 to PosixFilePermission.OTHERS_WRITE,
                0x1 to PosixFilePermission.OTHERS_EXECUTE,
            )
            val expected = flags.filter { (bit, _) -> planned.unixMode and bit != 0 }.map { it.second }.toSet()
            try {
                Files.setPosixFilePermissions(target.toPath(), expected)
                if (Files.getPosixFilePermissions(target.toPath()) != expected) {
                    throw IOException("Restored permissions differ for ${target.name}")
                }
                restoredExecutable = planned.executable
            } catch (e: Exception) {
                throw IOException("Cannot restore recorded permissions for ${target.name}", e)
            }
        }
        val modified = entry.time
        if (modified > 0L) {
            try {
                Files.setLastModifiedTime(target.toPath(), FileTime.fromMillis(modified))
            } catch (e: IOException) {
                // mtime 只影响列表显示，恢复失败不构成数据不完整（不改变导入结果）
            }
        }
        return restoredExecutable
    }

    /**
     * 核对一个"目录条目"确实没有内容：声明尺寸必须是 0，CRC 必须是空内容的 0，且实际读不到任何字节。
     * 只看名字（尾随 `/`）会把"伪装成目录的文件"直接放过去，所以必须真读一次。
     */
    private fun verifyDirectoryEntryIsEmpty(zf: ZipFile, entry: ZipEntry, display: String) {
        if (entry.size > 0L) {
            throw IOException("ZIP 目录条目 $display 声明了 ${entry.size} 字节内容，拒绝导入")
        }
        if (entry.crc > 0L) {
            throw IOException("ZIP 目录条目 $display 的 CRC 非空（${entry.crc}），拒绝导入")
        }
        val probe = ByteArray(1)
        val payloadBytes = try {
            zf.getInputStream(entry).use { input ->
                val read = input.read(probe)
                if (read < 0) 0L else 1L
            }
        } catch (e: IOException) {
            throw IOException("ZIP 目录条目 $display 无法校验内容：${e.message}", e)
        }
        if (payloadBytes != 0L) {
            throw IOException("ZIP 目录条目 $display 里带着内容，拒绝导入")
        }
    }

    /**
     * 两个目录是否在同一文件系统。发布靠一次目录 rename 完成，跨设备会失败
     * （某些实现还会退化成非原子的拷贝）。拿不到标识时不阻断，交给 rename 的返回值兜底。
     */
    private fun sameFileSystem(a: File, b: File): Boolean {
        return try {
            val devA = (Files.getAttribute(a.toPath(), "unix:dev") as? Number)?.toLong()
            val devB = (Files.getAttribute(b.toPath(), "unix:dev") as? Number)?.toLong()
            if (devA != null && devB != null) {
                devA == devB
            } else {
                val storeA = Files.getFileStore(a.toPath())
                val storeB = Files.getFileStore(b.toPath())
                storeA.name() == storeB.name() && storeA.type() == storeB.type()
            }
        } catch (e: Exception) {
            // 拿不到文件系统标识（如 Windows 没有 unix:dev）：不阻断，由 renameTo 的返回值兜底
            true
        }
    }

    private fun requireInside(rootCanonical: File, target: File, display: String) {
        val canonical = try {
            target.canonicalFile
        } catch (e: IOException) {
            throw IOException("无法解析导入目标路径：$display", e)
        }
        val rootPath = rootCanonical.path
        val targetPath = canonical.path
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
            throw IOException("ZIP 条目越界（Zip Slip）：$display")
        }
    }

    /** 把 [source] 限长拷进临时文件；不可 seek 的流也能处理。本方法不关闭 [source]。 */
    private suspend fun spoolArchive(source: InputStream, archive: File) {
        var total = 0L
        val buffer = ByteArray(copyBufferBytes)
        FileOutputStream(archive).use { rawOut ->
            BufferedOutputStream(rawOut, copyBufferBytes).use { out ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limits.maxArchiveBytes) {
                        throw IOException("ZIP 压缩包超过上限 ${limits.maxArchiveBytes} 字节，拒绝导入")
                    }
                    out.write(buffer, 0, read)
                }
            }
        }
        if (total <= 0L) throw IOException("ZIP 包为空，没有可导入的内容")
    }

    // ---------------------------------------------------------------- 工具

    /** 把名字转成可安全写进错误信息的可见字符串（控制字符转义）。 */
    private fun describe(raw: String, index: Long): String {
        val printable = buildString {
            for (ch in raw) {
                if (ch.code < 0x20 || ch.code == 0x7F) {
                    append("\\u%04x".format(ch.code))
                } else {
                    append(ch)
                }
            }
        }
        return "第 ${index + 1} 个条目 '$printable'"
    }

    companion object {
        /** staging 目录里存放解包内容的子目录名（与 WorkspaceManager 的文件区同名）。 */
        const val CONTENT_DIR_NAME: String = "files"
        private const val STAGING_ARCHIVE_NAME = "archive.zip"

        const val DEFAULT_COPY_BUFFER_BYTES: Int = 64 * 1024
        const val DEFAULT_PROGRESS_INTERVAL_NANOS: Long = 200_000_000L

        /** 常见外层包装目录名，供 UI 提供"显式"剥层选项。 */
        const val WRAPPER_WORKSPACE: String = "workspace"
        const val WRAPPER_FILES: String = "files"

        private val REGULAR_PERMISSIONS: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ,
        )
        private val EXECUTABLE_PERMISSIONS: Set<PosixFilePermission> = REGULAR_PERMISSIONS + setOf(
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_EXECUTE,
        )

        private const val EOCD_SIG = 0x06054b50L
        private const val ZIP64_EOCD_SIG = 0x06064b50L
        private const val ZIP64_EOCD_LOCATOR_SIG = 0x07064b50L
        private const val CD_ENTRY_SIG = 0x02014b50L
        private const val EOCD_MIN_BYTES = 22L
        private const val ZIP64_LOCATOR_BYTES = 20L
        private const val ZIP64_EOCD_BYTES = 56L
        private const val CD_HEADER_BYTES = 46L
        private const val EOCD_SEARCH_BYTES = 66_000L
        private const val U16_MAX = 0xFFFFL
        private const val U32_MAX = 0xFFFFFFFFL

        private const val MADE_BY_UNIX = 3
        private const val DOS_DIRECTORY_ATTR = 0x10L
        private const val FLAG_ENCRYPTED = 0x1L

        private const val FILE_TYPE_MASK = 0xF000
        private const val FILE_TYPE_REGULAR = 0x8000
        private const val FILE_TYPE_DIRECTORY = 0x4000
        private const val PERMISSION_MASK = 0x1FF

        /** 0o111：owner/group/other 可执行位。 */
        private const val EXEC_BITS = 0x49
        private const val MAX_NAME_BYTES = 65_536L
        private const val MAX_SEGMENT_BYTES = 255

        /** 小端读取；只在按字节解析 ZIP 目录记录时使用。 */
        internal fun readU16(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFF) or ((bytes[offset + 1].toLong() and 0xFF) shl 8)

        internal fun readU32(bytes: ByteArray, offset: Int): Long =
            readU16(bytes, offset) or (readU16(bytes, offset + 2) shl 16)

        internal fun readU64(bytes: ByteArray, offset: Int): Long =
            readU32(bytes, offset) or (readU32(bytes, offset + 4) shl 32)
    }
}

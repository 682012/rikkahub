package me.rerere.rikkahub.data.repository

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把工作区的可写文件区打成 ZIP。
 *
 * 纯 JVM 实现（只依赖 java.nio.file / java.util.zip / kotlinx.coroutines），不碰 Android API，
 * 因此可以直接在 `app/src/test` 的普通 JVM 单元测试里跑（见 `WorkspaceFilesExporterTest`）。
 *
 * 约定：
 * - 按目录名剪枝：[skippedDirNames] 命中的目录整棵跳过（`SKIP_SUBTREE`），不枚举其中的文件；
 * - 目录本身也写成条目（名字尾随 `/`，无内容），空目录因此能和 [WorkspaceFilesImporter] 无损往返；
 * - 不跟随符号链接：`Files.walkFileTree` 默认不跟随，链接本身作为“跳过项”计数，内容不导出
 *   （当前导出不保留符号链接和 Unix 权限，跟随还可能包含工作区外的内容）；
 * - 特殊文件（FIFO/socket/设备）同样跳过并计数，避免读 FIFO 卡死导出；
 * - 扫描阶段每个目录/文件前检查协程取消，拷贝阶段每块缓冲区前检查取消（大文件也能及时停）；
 * - 读取被选中的普通文件失败、或写入输出流失败时**抛出异常**，绝不静默生成“不完整但显示成功”的备份；
 * - 进度口径：`total` = 扫描时收集到的条目字节总和，`done` = 实际写入的字节（上报时夹到 `total` 以内）。
 */
class WorkspaceFilesExporter(
    private val skippedDirNames: Set<String> = DEFAULT_SKIPPED_DIR_NAMES,
    private val copyBufferBytes: Int = DEFAULT_COPY_BUFFER_BYTES,
    /** 进度回调最小间隔，避免大文件按块刷 UI。 */
    private val progressIntervalNanos: Long = DEFAULT_PROGRESS_INTERVAL_NANOS,
) {
    init {
        require(copyBufferBytes > 0) { "Copy buffer size must be positive" }
    }

    /** 一次导出的结果。被跳过的条目未进入备份，调用方应记录并说明导出边界。 */
    data class Summary(
        /** 导出的普通文件数（不含目录条目）。 */
        val entryCount: Int,
        val byteCount: Long,
        val skippedSymlinks: Int,
        val skippedSpecialFiles: Int,
        /** 导出的目录条目数（含空目录）。导入端据此无损还原目录结构。 */
        val directoryCount: Int = 0,
        val prunedDirCount: Int = 0,
        val prunedDirExamples: List<String> = emptyList(),
    ) {
        val skippedTotal: Int get() = skippedSymlinks + skippedSpecialFiles
    }

    private data class Entry(
        val path: Path,
        val relativePath: String,
        val sizeBytes: Long,
        val modifiedAtMillis: Long,
        val isDirectory: Boolean = false,
    )

    private class ScanResult(
        val entries: List<Entry>,
        val skippedSymlinks: Int,
        val skippedSpecialFiles: Int,
        val prunedDirCount: Int,
        val prunedDirExamples: List<String>,
    )

    /**
     * @param root 工作区文件区根目录（必须存在）
     * @param outputStream ZIP 目标流，导出器完成或失败时会关闭
     * @param onProgress (已写字节, 总字节, 当前相对路径)，签名与原 UI 接口一致
     */
    suspend fun export(
        root: File,
        outputStream: OutputStream,
        onProgress: (done: Long, total: Long, current: String) -> Unit = { _, _, _ -> },
    ): Summary {
        val scan = collectEntries(root)
        val total = scan.entries.sumOf { it.sizeBytes }
        var copied = 0L
        var lastReportAt = 0L
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zip ->
            val buffer = ByteArray(copyBufferBytes)
            for (entry in scan.entries) {
                currentCoroutineContext().ensureActive()
                val zipEntry = ZipEntry(entry.relativePath)
                if (entry.modifiedAtMillis > 0L) zipEntry.setLastModifiedTime(FileTime.fromMillis(entry.modifiedAtMillis))
                zip.putNextEntry(zipEntry)
                if (entry.isDirectory) {
                    // 目录条目只有名字（尾随 /），不写内容；导入端按名字重建，空目录因此不会丢
                    zip.closeEntry()
                    onProgress(minOf(copied, total), total, entry.relativePath)
                    continue
                }
                try {
                    // use {} 是 inline，suspend 调用可以直接写在里面
                    Files.newInputStream(entry.path, LinkOption.NOFOLLOW_LINKS).use { input ->
                        while (true) {
                            // 每块缓冲区都会检查取消：几 GB 的单文件也不会无视取消写完
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            zip.write(buffer, 0, read)
                            copied += read
                            val now = System.nanoTime()
                            if (now - lastReportAt >= progressIntervalNanos) {
                                lastReportAt = now
                                onProgress(minOf(copied, total), total, entry.relativePath)
                            }
                        }
                    }
                } catch (e: IOException) {
                    // 不加 try/catch 的话异常类型看不出是哪个条目，调用方也无从判断导出是否完整
                    throw IOException("Failed to export entry: ${entry.relativePath}", e)
                }
                zip.closeEntry()
                onProgress(minOf(copied, total), total, entry.relativePath)
            }
        }
        if (total > 0L && copied < total) {
            // 文件在扫描后变短，进度补到 100%，避免界面停在 99%
            onProgress(total, total, "")
        }
        val directoryCount = scan.entries.count { it.isDirectory }
        return Summary(
            entryCount = scan.entries.size - directoryCount,
            byteCount = copied,
            skippedSymlinks = scan.skippedSymlinks,
            skippedSpecialFiles = scan.skippedSpecialFiles,
            directoryCount = directoryCount,
            prunedDirCount = scan.prunedDirCount,
            prunedDirExamples = scan.prunedDirExamples,
        )
    }

    private suspend fun collectEntries(root: File): ScanResult {
        // walkFileTree 是阻塞的，visitor 回调跑在当前线程，用捕获的 context 做取消检查
        val context: CoroutineContext = currentCoroutineContext()
        val rootPath = root.toPath()
        require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) { "Workspace files directory missing or symbolic" }
        val entries = ArrayList<Entry>()
        var skippedSymlinks = 0
        var skippedSpecialFiles = 0
        var prunedDirCount = 0
        val prunedDirExamples = ArrayList<String>()
        Files.walkFileTree(
            rootPath,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    val name = dir.fileName?.toString()
                    if (dir != rootPath && name != null && name in skippedDirNames) {
                        prunedDirCount++
                        if (prunedDirExamples.size < 12) prunedDirExamples += rootPath.relativize(dir).toString()
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (dir != rootPath) {
                        // 目录也写成条目（尾随 /）：否则导入端无法还原空目录
                        entries.add(
                            Entry(
                                path = dir,
                                relativePath = rootPath.relativize(dir).joinToString("/") { it.toString() } + "/",
                                sizeBytes = 0L,
                                modifiedAtMillis = attrs.lastModifiedTime().toMillis(),
                                isDirectory = true,
                            )
                        )
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    when {
                        // 不跟随链接：指向目录的链接同样只会走到这里
                        attrs.isSymbolicLink -> skippedSymlinks++
                        attrs.isRegularFile -> entries.add(
                            Entry(
                                path = file,
                                relativePath = rootPath.relativize(file).joinToString("/") { it.toString() },
                                sizeBytes = attrs.size(),
                                modifiedAtMillis = attrs.lastModifiedTime().toMillis(),
                            )
                        )
                        else -> skippedSpecialFiles++
                    }
                    return FileVisitResult.CONTINUE
                }

                // 故意不覆盖 visitFileFailed：读不了的目录/文件会把 IOException 抛出去，
                // 导出直接失败，而不是悄悄少打包一批文件却报告成功。
            },
        )
        return ScanResult(entries, skippedSymlinks, skippedSpecialFiles, prunedDirCount, prunedDirExamples)
    }

    companion object {
        /** 按目录名（任意层级）剪枝。`.jobs` 运行日志不纳入此文件导出。 */
        val DEFAULT_SKIPPED_DIR_NAMES: Set<String> = setOf(
            "tmp", "node_modules", ".git", "build", ".gradle", ".kotlin",
            "cache", ".npm", ".cache", "__pycache__", ".venv", "dist",
            ".jobs",
        )

        const val DEFAULT_COPY_BUFFER_BYTES: Int = 64 * 1024
        const val DEFAULT_PROGRESS_INTERVAL_NANOS: Long = 200_000_000L
    }
}

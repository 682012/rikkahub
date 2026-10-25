package me.rerere.rikkahub.data.repository

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作区 ZIP 导入的 staging 目录（`<workspaces>/.import-<uuid>`）**残骸清理**。
 *
 * 背景：[WorkspaceFilesImporter] 只在 catch/finally 里删自己的 staging 目录。进程被系统杀掉
 * （后台限制、OOM、用户强停）时那条路径不会执行，`<workspaces>/.import-<uuid>/archive.zip`
 * 就会一直留着 —— 一个 4 GiB 的包足够把用户的存储吃满。
 *
 * 设计约束（安全优先，宁可少删也绝不误删）：
 * - **只认完全精确的目录名**：`^\.import-[0-9a-f]{8}-...$`（规范 UUID 小写形式）。普通目录、
 *   正式工作区目录（裸 uuid）、`.import-<任意非 uuid>`、带大写或多余字符的名字一律跳过；
 * - **只看 base 的直接子项**：不递归找 `.import-*`，也不接受调用方传进来的子路径；
 * - **不跟符号链接**：base 自身、候选目录、目录内每个条目全部用 `NOFOLLOW_LINKS` 检查，
 *   递归删除走 `Files.walkFileTree`（默认不跟随），链接本身当普通条目处理，绝不删链接目标；
 * - **只删目录**：同名普通文件、悬空链接、FIFO/socket 全部跳过（不是我们的残骸，也可能是别人的坑）；
 * - **只删足够旧的**：默认 24h（[DEFAULT_MIN_AGE_MILLIS]）。一个活着的导入几分钟内就结束，
 *   年龄门槛让"跨进程/跨 App 实例"的并发导入不会被误伤，而不用去猜别人的锁；
 * - **进程内租约**：本进程正在导入的 staging 目录在 [withStagingLease] 期间登记在
 *   [ACTIVE_STAGING] 里，`finally` 必移除（取消也会执行），清理时整条跳过。目录锁在这里不适用：
 *   Linux/Android 上目录不能建 `FileChannel` 锁，而"在 staging 里放锁文件"本身就会破坏
 *   导入方"目录必须不存在"的前置条件，所以同进程用登记表、跨进程用年龄门槛；
 * - **尽力而为、有上界**：单个目录的删除失败只记 [Summary.errorCount]，不抛异常、不影响导入；
 *   整轮 sweep 默认最多 [DEFAULT_MAX_SWEEP_MILLIS] 毫秒（[Summary.timedOut] 标记被截断），
 *   可以在 `checkIntegrity()` 或导入前的主线程路径里低成本调用一次；
 * - **不自己起调度**：没有后台线程、没有定时器，调用时机完全由调用方决定（建议每次
 *   `checkIntegrity()` 一次、每次导入前一次）。
 *
 * 纯 JVM（java.io / java.nio.file + kotlinx.coroutines），不碰 Android API，因此
 * `app/src/test` 的普通 JVM 单测可以直接跑（见 `WorkspaceImportStagingTest`）。
 */
object WorkspaceImportStaging {
    /** staging 目录名前缀，与 [WorkspaceRepository.importWorkspaceFiles] 保持一致。 */
    const val STAGING_NAME_PREFIX: String = ".import-"

    /**
     * 允许清理的目录名：`.import-` + 规范小写 UUID（8-4-4-4-12），`\z` 锚定整串。
     * 大小写敏感，所以 `.import-<uuid>.bak`、`.import-<uuid>n`、`.IMPORT-<uuid>`
     * 都不会命中。任何放宽都会把"猜名字删东西"的风险引进来，所以这里刻意不做归一化。
     */
    val STAGING_NAME_REGEX: Regex =
        Regex("""^\.import-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z""")

    /** 默认只清理 24 小时前的残骸（活着的导入都是分钟级）。 */
    const val DEFAULT_MIN_AGE_MILLIS: Long = 24L * 60L * 60L * 1000L

    /** 单次 sweep 的时间上限，避免在 UI/IO 线程上拖太久。 */
    const val DEFAULT_MAX_SWEEP_MILLIS: Long = 2_000L

    /** 递归删除的最大深度（防御异常深的残骸，正常导入远小于这个值）。 */
    const val MAX_DEPTH: Int = 64

    /** 本进程里正在被导入使用的 staging 目录（绝对 + 归一化路径）。 */
    private val ACTIVE_STAGING = ConcurrentHashMap.newKeySet<String>()

    /** 清理结果。`deletedCount`/`errorCount` 是主口径，其余字段给日志用。 */
    data class Summary(
        val deletedCount: Int = 0,
        val errorCount: Int = 0,
        val skippedActiveCount: Int = 0,
        val skippedRecentCount: Int = 0,
        val timedOut: Boolean = false,
    ) {
        val isNoop: Boolean get() = deletedCount == 0 && errorCount == 0
    }

    /** `.import-<uuid>` 目录名判定（整串精确匹配，大小写敏感）。 */
    fun isStagingName(name: String): Boolean = STAGING_NAME_REGEX.matches(name)

    /**
     * 给 [workspaceRoot] 构造它的 staging 目录 `<baseDir>/.import-<workspaceRoot>`。
     * 只做名字拼接与最小校验，不创建目录、不触碰磁盘。
     */
    fun stagingDirFor(baseDir: File, workspaceRoot: String): File {
        require(workspaceRoot.isNotBlank()) { "Workspace root is blank" }
        require(workspaceRoot != "." && workspaceRoot != "..") { "Illegal workspace root: $workspaceRoot" }
        require('/' !in workspaceRoot && '\\' !in workspaceRoot) {
            "Workspace root must be a single path segment: $workspaceRoot"
        }
        return File(baseDir, STAGING_NAME_PREFIX + workspaceRoot)
    }

    /**
     * 在 [stagingRoot] 的租约内执行 [block]：登记 → 执行 → `finally` 注销（取消也会执行）。
     *
     * 租约只保护**本进程**：清理时看到 active 路径直接跳过；跨进程由年龄门槛兜底。
     * 同一路径不做嵌套计数（[WorkspaceFilesImporter] 要求 staging 目录必须不存在，天然唯一）。
     */
    suspend fun <T> withStagingLease(stagingRoot: File, block: suspend () -> T): T {
        val key = keyOf(stagingRoot)
        check(ACTIVE_STAGING.add(key)) { "An import already owns this staging path" }
        try {
            return block()
        } finally {
            // 非挂起操作，取消路径上也会执行；失败也无所谓（清理还有年龄门槛）。
            ACTIVE_STAGING.remove(key)
        }
    }

    /** 该路径是否正被本进程的导入占用。 */
    fun isActive(stagingRoot: File): Boolean = ACTIVE_STAGING.contains(keyOf(stagingRoot))

    /** 当前登记的租约数量（测试/诊断用）。 */
    fun activeLeaseCount(): Int = ACTIVE_STAGING.size

    /**
     * 扫一遍 [baseDir] 的直接子项，删掉足够旧、名字精确匹配、且不在租约里的 staging 残骸。
     *
     * 纯阻塞 IO，不抛异常（除参数非法），不递归进 base 之外，不跟随符号链接。
     * 建议的调用点：`WorkspaceRepository.checkIntegrity()`（每次一次）与
     * `importWorkspaceFiles()` 的前置准备（一次）。两者都可 `runCatching` 忽略结果。
     *
     * @param nowMillis 判定"旧"的当前时间，测试可注入固定值
     * @param minAgeMillis 至少多旧才删；必须 >= 0
     * @param maxSweepMillis 本轮最多花多少毫秒；到了就停并把 [Summary.timedOut] 置 true
     */
    fun cleanupOnce(
        baseDir: File,
        nowMillis: Long = System.currentTimeMillis(),
        minAgeMillis: Long = DEFAULT_MIN_AGE_MILLIS,
        maxSweepMillis: Long = DEFAULT_MAX_SWEEP_MILLIS,
    ): Summary {
        require(minAgeMillis >= 0) { "minAgeMillis must be >= 0" }
        require(maxSweepMillis >= 0) { "maxSweepMillis must be >= 0" }

        val startedNanos = System.nanoTime()
        val deadlineNanos =
            if (maxSweepMillis >= Long.MAX_VALUE / 1_000_000L) Long.MAX_VALUE
            else startedNanos + maxSweepMillis * 1_000_000L

        val basePath = baseDir.absoluteFile.toPath().normalize()
        // base 自身必须是真实目录：不跟链接，也拒绝被当成文件/悬空链接。
        val baseAttrs = readAttributesOrNull(basePath) ?: return Summary(errorCount = 1)
        if (!baseAttrs.isDirectory || baseAttrs.isSymbolicLink) return Summary(errorCount = 1)

        var deleted = 0
        var errors = 0
        var skippedActive = 0
        var skippedRecent = 0
        var timedOut = false

        try {
            Files.newDirectoryStream(basePath).use { stream ->
                for (entry in stream) {
                    if (System.nanoTime() >= deadlineNanos) {
                        timedOut = true
                        break
                    }
                    val name = entry.fileName?.toString() ?: continue
                    // 第一道闸：名字必须整串是 `.import-<小写 uuid>`。
                    if (!isStagingName(name)) continue
                    // 第二道闸：必须就是 base 的直接子项，而不是 base/x/.import-...
                    val normalized = entry.toAbsolutePath().normalize()
                    if (normalized.parent != basePath) continue
                    // 第三道闸：必须是真实目录；普通文件、链接、特殊文件一律不删。
                    val attrs = readAttributesOrNull(normalized)
                    if (attrs == null) {
                        errors++
                        continue
                    }
                    if (!attrs.isDirectory || attrs.isSymbolicLink) continue
                    // 第四道闸：本进程正在用。
                    if (ACTIVE_STAGING.contains(normalized.toString())) {
                        skippedActive++
                        continue
                    }
                    // 第五道闸：足够旧。
                    val ageMillis = nowMillis - attrs.lastModifiedTime().toMillis()
                    if (ageMillis < minAgeMillis) {
                        skippedRecent++
                        continue
                    }
                    if (deleteTree(normalized, deadlineNanos)) deleted++ else errors++
                }
            }
        } catch (_: IOException) {
            // 枚举本身失败（权限、被并发删掉）：算一次错误，不向上抛。
            errors++
        } catch (_: SecurityException) {
            errors++
        }

        return Summary(
            deletedCount = deleted,
            errorCount = errors,
            skippedActiveCount = skippedActive,
            skippedRecentCount = skippedRecent,
            timedOut = timedOut,
        )
    }

    // ------------------------------------------------------------ 内部实现

    /** 绝对 + 归一化后的路径键；不做 canonical 解析（那会跟着链接跑，还会抛 IOException）。 */
    private fun keyOf(file: File): String = file.absoluteFile.toPath().normalize().toString()

    private fun readAttributesOrNull(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /**
     * 递归删掉 [root]（必须已经过命名/类型/年龄校验）。
     *
     * `walkFileTree` 默认不跟符号链接：链接作为 `visitFile` 的条目出现，只删链接本身；
     * 遍历过程中遇到错误、或超过 [deadlineNanos] 就停下并返回 false（已删的部分不回滚，
     * 残骸少一点总是好事）。任何异常都在这里吞掉，绝不冒泡到导入/自检路径。
     */
    private fun deleteTree(root: Path, deadlineNanos: Long): Boolean {
        var ok = true
        val visitor = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (System.nanoTime() >= deadlineNanos) {
                    ok = false
                    return FileVisitResult.TERMINATE
                }
                // 兜底：即使 walk 顶层被替换成链接，也不进去。
                if (dir != root && attrs.isSymbolicLink) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (System.nanoTime() >= deadlineNanos) {
                    ok = false
                    return FileVisitResult.TERMINATE
                }
                // 链接只删链接本身（Files.delete 不跟随），目标永远不动。
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                ok = false
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) {
                    ok = false
                    return FileVisitResult.CONTINUE
                }
                if (System.nanoTime() >= deadlineNanos) {
                    ok = false
                    return FileVisitResult.TERMINATE
                }
                // 先子后父，目录只能在遍历结束时删。
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        }
        return try {
            Files.walkFileTree(
                root,
                EnumSet.noneOf(FileVisitOption::class.java),
                MAX_DEPTH,
                visitor,
            )
            ok
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

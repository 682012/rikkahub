package me.rerere.rikkahub.automation

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 账本 IO 的最小接口。抽出来只为两件事：
 *
 * 1. **失败可注入**：纯 JVM 测试能塞一个"第 N 次写必失败"的假实现，不用起 Android、不用真磁盘，
 *    才能验证"写不进去就一步都不执行"。
 * 2. **失败必须说出来**：实现里永远不许 swallow——读失败抛异常（不是返回 null 假装空账本），
 *    写失败抛异常（不是记条日志假装成功）。上层据此 fail-closed。
 */
interface AutomationLedgerIo {

    /**
     * 账本原文。`null` 只表示**从来没有过账本文件**（第一次运行）；
     * 文件存在但读不出来必须抛 [IOException]，绝不能返回 null。
     */
    @Throws(IOException::class)
    fun read(): String?

    /**
     * 原子写：同目录临时文件 → 写满 → fsync → rename 覆盖正式文件。
     *
     * 任何一步失败都要抛异常，并且正式文件保持**上一次的完整内容**：
     * 半截 JSON 会把配方、历史、待确认记录一起毁掉。
     */
    @Throws(IOException::class)
    fun write(text: String)

    /** 给人看的位置说明（日志/设置页）。 */
    val location: String get() = javaClass.simpleName
}

/**
 * 真实文件实现，只依赖 `java.io` / `java.nio.channels`：Android 和纯 JVM 都能用。
 *
 * 写路径刻意不用 "截断正式文件再写"：先写 `*.part`，`fd.sync()` 之后再 rename。
 */
class FileAutomationLedgerIo(
    private val file: File,
    private val tempFile: File = File(file.parentFile, file.name + ".part"),
) : AutomationLedgerIo {

    override val location: String get() = file.absolutePath

    override fun read(): String? {
        if (!file.exists()) return null
        if (!file.isFile) throw IOException("the automation ledger is not a regular file: ${file.absolutePath}")
        // readText 读失败会抛 IOException —— 交给上层当"读坏了"处理，不假装空账本
        return file.readText(Charsets.UTF_8)
    }

    override fun write(text: String) {
        val directory = file.parentFile
            ?: throw IOException("no parent directory for ${file.absolutePath}")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("could not create ${directory.absolutePath}")
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        FileOutputStream(tempFile).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            tempFile.delete()
            throw IOException("could not replace the automation ledger atomically: ${file.absolutePath}", failure)
        }
        syncDirectory(directory)
    }

    /** rename 本身也落盘（尽力而为：有的文件系统不支持对目录 fsync，失败不影响写入结果）。 */
    private fun syncDirectory(directory: File) {
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}

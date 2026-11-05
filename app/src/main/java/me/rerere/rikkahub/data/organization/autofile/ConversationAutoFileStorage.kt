package me.rerere.rikkahub.data.organization.autofile

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 小 JSON 文件的原子写读。
 *
 * 先写同目录临时文件并 fsync，再 `ATOMIC_MOVE` 顶替正式文件：进程被杀、设备断电都只会留下
 * 「旧版本完整」或「新版本完整」，不会让设置/日志变成半个 JSON。
 */
internal object AutoFileAtomicFile {
    fun write(file: File, text: String) {
        val parent = file.parentFile ?: throw IOException("no parent for ${file.path}")
        parent.mkdirs()
        val temp = File(parent, "${file.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            runCatching {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.onFailure {
                // 个别文件系统不支持 ATOMIC_MOVE，退回普通替换。
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun readText(file: File): String? {
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    /**
     * 把坏文件改名留档，返回备份文件；用于「损坏/错归属默认关闭并保留备份」。
     * 备份名带时间戳，不会互相覆盖。
     */
    fun quarantine(file: File, reason: String, now: Long = System.currentTimeMillis()): File? {
        if (!file.exists()) return null
        val safeReason = reason.filter { it.isLetterOrDigit() || it == '-' }.take(16).ifBlank { "corrupt" }
        val parent = file.parentFile ?: return null
        val backup = File(parent, "${file.name}.$safeReason-$now.bak")
        return runCatching {
            if (backup.exists()) backup.delete()
            if (file.renameTo(backup)) {
                backup
            } else {
                file.copyTo(backup, overwrite = true)
                file.delete()
                backup
            }
        }.getOrNull()
    }
}

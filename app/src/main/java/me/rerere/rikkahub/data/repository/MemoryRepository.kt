package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile

sealed interface MemoryWriteResult {
    /**
     * [file] 为 null 表示文件已被删除。
     *
     * [unchanged] 表示这次调用没有改动内容（与现有内容相同、或要追加的行已经在文件里）：
     * 数据库没有重写，updatedAt 与 version 都没动。
     */
    data class Success(val file: MemoryFile?, val unchanged: Boolean = false) : MemoryWriteResult

    /** 版本对不上；[current] 是最新内容，为 null 表示文件不存在 */
    data class Conflict(val current: MemoryFile?) : MemoryWriteResult

    data class Rejected(val reason: String) : MemoryWriteResult
}

/**
 * 记忆库：每个助手（或全局）一组 Markdown 文件。
 *
 * 写操作都带 ifVersion 做乐观锁：传 [NEW_FILE_VERSION] 表示新建，传版本号表示基于该版本修改，
 * 传 null 则不校验（用户在界面里直接编辑）。
 */
class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        const val NEW_FILE_VERSION = "new"
    }

    // 读取、校验版本、写入必须一起完成，否则两个写入方会互相覆盖
    private val writeLock = Mutex()

    // 顺序由 DAO 的 ORDER BY path 决定(二进制比较, 与区域设置无关)，
    // 系统提示里的记忆清单直接沿用这个顺序，不随插入/恢复顺序变化
    fun getFilesFlow(assistantId: String): Flow<List<MemoryFile>> =
        memoryDAO.getFilesOfAssistantFlow(assistantId).map { entities -> entities.map { it.toMemoryFile() } }

    suspend fun getFiles(assistantId: String): List<MemoryFile> =
        memoryDAO.getFilesOfAssistant(assistantId).map { it.toMemoryFile() }

    suspend fun getFile(assistantId: String, path: String): MemoryFile? {
        val normalized = MemoryFile.normalizePath(path) ?: return null
        return memoryDAO.getFile(assistantId, normalized)?.toMemoryFile()
    }

    /** 新建或整体覆盖 */
    suspend fun writeFile(
        assistantId: String,
        path: String,
        content: String,
        ifVersion: String? = null,
    ): MemoryWriteResult = update(assistantId, path, ifVersion, allowCreate = true) { Edit.Content(content) }

    /** 把唯一匹配的 [oldStr] 换成 [newStr]，[newStr] 为空即删除 */
    suspend fun replaceInFile(
        assistantId: String,
        path: String,
        oldStr: String,
        newStr: String,
        ifVersion: String?,
    ): MemoryWriteResult = update(assistantId, path, ifVersion) { current ->
        if (oldStr.isEmpty()) return@update Edit.Reject("old_str must not be empty.")
        val index = current.indexOf(oldStr)
        when {
            index < 0 -> Edit.Reject("old_str was not found in $path.")
            current.indexOf(oldStr, index + 1) >= 0 ->
                Edit.Reject(
                    "old_str matches more than one place in $path; include more surrounding text to make it unique."
                )

            else -> Edit.Content(current.replaceAt(index, oldStr.length, newStr))
        }
    }

    /**
     * 在文件末尾追加一行。
     *
     * 整行已经存在时不重复写：整理器重复提交同一条事实是记忆库里最常见的一种膨胀，
     * 而多出来的重复行会让文件更大、系统提示里的记忆段更早变化。
     */
    suspend fun appendToFile(
        assistantId: String,
        path: String,
        line: String,
        ifVersion: String?,
    ): MemoryWriteResult = update(assistantId, path, ifVersion) { current ->
        val addition = line.trimEnd('\n', '\r')
        if (addition.isBlank()) return@update Edit.Reject("content must not be blank.")
        if (current.hasLine(addition)) return@update Edit.Unchanged
        val separator = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
        Edit.Content(current + separator + addition + "\n")
    }

    suspend fun deleteFile(
        assistantId: String,
        path: String,
        ifVersion: String? = null,
    ): MemoryWriteResult = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return invalidPath(path)
        val current = memoryDAO.getFile(assistantId, normalized)?.toMemoryFile()
        if (current == null || (ifVersion != null && ifVersion != current.version)) {
            return MemoryWriteResult.Conflict(current)
        }
        memoryDAO.deleteFile(assistantId, normalized)
        MemoryWriteResult.Success(null)
    }

    suspend fun deleteFilesOfAssistant(assistantId: String) {
        memoryDAO.deleteFilesOfAssistant(assistantId)
    }

    /** Explicit user migration: preserve existing destination files and serialize with edits. */
    suspend fun copyFiles(fromAssistantId: String, toAssistantId: String) = writeLock.withLock {
        if (fromAssistantId == toAssistantId) return@withLock
        val existingPaths = memoryDAO.getFilesOfAssistant(toAssistantId).mapTo(hashSetOf()) { it.path }
        val files = memoryDAO.getFilesOfAssistant(fromAssistantId).filter { it.path !in existingPaths }
        if (files.isNotEmpty()) memoryDAO.upsertFiles(files.map { it.copy(assistantId = toAssistantId) })
    }

    private sealed interface Edit {
        data class Content(val content: String) : Edit
        data class Reject(val reason: String) : Edit

        /** 想写的内容和文件里已有的内容一样：不写库 */
        data object Unchanged : Edit
    }

    private suspend fun update(
        assistantId: String,
        path: String,
        ifVersion: String?,
        allowCreate: Boolean = false,
        edit: (current: String) -> Edit,
    ): MemoryWriteResult = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return invalidPath(path)
        val current = memoryDAO.getFile(assistantId, normalized)?.toMemoryFile()
        val versionMatches = when (ifVersion) {
            null -> current != null || allowCreate
            NEW_FILE_VERSION -> current == null && allowCreate
            else -> current?.version == ifVersion
        }
        if (!versionMatches) return MemoryWriteResult.Conflict(current)

        val content = when (val result = edit(current?.content.orEmpty())) {
            is Edit.Reject -> return MemoryWriteResult.Rejected(result.reason)
            is Edit.Unchanged -> {
                // 没有 current 时不可能"内容已存在": 不能报成 Success(null), 那在工具输出里会被读成"已删除"
                if (current == null) return MemoryWriteResult.Conflict(null)
                return MemoryWriteResult.Success(current, unchanged = true)
            }
            is Edit.Content -> result.content
        }
        // 内容逐字节相同就不写库：updatedAt 不变、不触发 Flow 重发，
        // 系统提示里的记忆段也不会因为一次没有内容的写入而白变一次
        if (current != null && content == current.content) {
            return MemoryWriteResult.Success(current, unchanged = true)
        }
        // 只拦会让文件变大的超限写入：迁移来的旧记忆可能本来就超限，得允许一点点删小
        val size = content.toByteArray().size
        if (size > MemoryFile.MAX_BYTES && size > current?.content?.toByteArray()?.size ?: 0) {
            return MemoryWriteResult.Rejected(
                "$normalized would exceed the ${MemoryFile.MAX_BYTES / 1024}KB limit. " +
                    "Merge duplicates and drop outdated details instead of adding more."
            )
        }
        val entity = MemoryFileEntity(
            assistantId = assistantId,
            path = normalized,
            content = content,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.upsertFile(entity)
        MemoryWriteResult.Success(entity.toMemoryFile())
    }

    private fun invalidPath(path: String) = MemoryWriteResult.Rejected(
        "Invalid path \"$path\". Use an absolute path ending in .md, e.g. /topics/food.md."
    )
}

/** 整行匹配：忽略行首行尾空白，但列表符号、`[stated]` 等正文部分要一致 */
private fun String.hasLine(line: String): Boolean {
    val target = line.trim()
    if (target.isEmpty()) return true
    return lineSequence().any { it.trim() == target }
}

/** Always assistant-scoped. Legacy shared files remain available only through explicit migration. */
val Assistant.memoryId: String
    get() = id.toString()

private fun MemoryFileEntity.toMemoryFile() = MemoryFile(path = path, content = content, updatedAt = updatedAt)

// 删掉的是完整一行时连换行一起去掉，不留空行
private fun String.replaceAt(index: Int, length: Int, replacement: String): String {
    var start = index
    var end = index + length
    if (replacement.isEmpty()) {
        val atLineStart = start == 0 || this[start - 1] == '\n'
        val atLineEnd = end == this.length || this[end] == '\n'
        if (atLineStart && atLineEnd) {
            if (end < this.length) {
                end++
            } else if (start > 0) {
                start--
            }
        }
    }
    return substring(0, start) + replacement + substring(end)
}

package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity

internal class FakeMemoryDAO : MemoryDAO {
    private val files = MutableStateFlow<List<MemoryFileEntity>>(emptyList())

    /** 真正落到"库"里的写入次数, 用来确认没内容的写入没有重写一次 */
    var upsertCalls = 0
        private set

    var deleteCalls = 0
        private set

    override fun getFilesOfAssistantFlow(assistantId: String): Flow<List<MemoryFileEntity>> =
        files.map { all -> all.filter { it.assistantId == assistantId }.sortedBy { it.path } }

    override suspend fun getFilesOfAssistant(assistantId: String): List<MemoryFileEntity> =
        files.value.filter { it.assistantId == assistantId }.sortedBy { it.path }

    override suspend fun getFile(assistantId: String, path: String): MemoryFileEntity? =
        files.value.find { it.assistantId == assistantId && it.path == path }

    override suspend fun upsertFile(file: MemoryFileEntity) = upsertFiles(listOf(file))

    override suspend fun upsertFiles(files: List<MemoryFileEntity>) {
        upsertCalls++
        val replaced = files.map { it.assistantId to it.path }.toSet()
        this.files.value = this.files.value.filterNot { (it.assistantId to it.path) in replaced } + files
    }

    override suspend fun deleteFile(assistantId: String, path: String) {
        deleteCalls++
        files.value = files.value.filterNot { it.assistantId == assistantId && it.path == path }
    }

    override suspend fun deleteFilesOfAssistant(assistantId: String) {
        deleteCalls++
        files.value = files.value.filterNot { it.assistantId == assistantId }
    }
}

package me.rerere.rikkahub.ui.pages.assistant.detail

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.ai.MemoryConsolidationStatus
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"

/** 手动把旧共享记忆迁移到本助手时的一次性状态，只用来给页面提示，不做任何自动同步。 */
sealed interface LegacyMemoryCopyState {
    data object Idle : LegacyMemoryCopyState
    data object Running : LegacyMemoryCopyState
    data class Finished(val copied: Int) : LegacyMemoryCopyState
    data class Failed(val message: String) : LegacyMemoryCopyState
}

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)

    // 记忆库固定按页面拿到的 assistantId 定位：无论助手有没有加载出来、旧数据里 useGlobalMemory 是什么，
    // 都只读写本助手自己的库，不会去碰旧的共享记忆库。
    val memoryId: String get() = assistantId.toString()

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val mcpServerConfigs = settingsStore
        .settingsFlow.map { settings ->
            settings.mcpServers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId } ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = Assistant()
        )

    // 记忆库在页面生命周期里是固定的：永远只认本页面的 assistantId，
    // 不再按 assistant.useGlobalMemory 切到共享库（旧字段只为反序列化兼容保留）。
    val memories = memoryRepository
        .getFilesFlow(memoryId)
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val memoryConsolidation: StateFlow<MemoryConsolidationStatus> = memoryConsolidationScheduler
        .statusOf(memoryId)
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = MemoryConsolidationStatus()
        )

    private val _legacyMemoryCopy = MutableStateFlow<LegacyMemoryCopyState>(LegacyMemoryCopyState.Idle)

    /** 手动把旧共享记忆复制到本助手的一次性结果；不会自动跑，也不会改动旧共享库。 */
    val legacyMemoryCopy: StateFlow<LegacyMemoryCopyState> = _legacyMemoryCopy.asStateFlow()

    val providers = settingsStore
        .settingsFlow
        .map { settings ->
            settings.providers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val tags = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistantTags
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository
        .listFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    fun updateTags(tagIds: List<Uuid>, tags: List<Tag>) {
        viewModelScope.launch {
            settingsStore.update { it.copy(assistantTags = tags) }
            update(
                assistant.value.copy(
                    tags = tagIds.toList()
                )
            )
            Log.d(TAG, "updateTags: ${tagIds.joinToString(",")}")
            cleanupUnusedTags()
        }
    }

    fun cleanupUnusedTags() {
        viewModelScope.launch {
            settingsStore.update { settings ->
                val validTagIds = settings.assistantTags.map { it.id }.toSet()

                // 清理 assistant 中的无效 tag id
                val cleanedAssistants = settings.assistants.map { assistant ->
                    val validTags = assistant.tags.filter { tagId ->
                        validTagIds.contains(tagId)
                    }
                    if (validTags.size != assistant.tags.size) {
                        assistant.copy(tags = validTags)
                    } else {
                        assistant
                    }
                }

                // 获取清理后的 assistant 中使用的 tag id
                val usedTagIds = cleanedAssistants.flatMap { it.tags }.toSet()

                // 清理未使用的 tags
                val cleanedTags = settings.assistantTags.filter { tag ->
                    usedTagIds.contains(tag.id)
                }

                // 检查是否需要更新
                val needUpdateAssistants = cleanedAssistants != settings.assistants
                val needUpdateTags = cleanedTags.size != settings.assistantTags.size

                if (needUpdateAssistants || needUpdateTags) {
                    settings.copy(
                        assistants = cleanedAssistants,
                        assistantTags = cleanedTags
                    )
                } else {
                    settings
                }
            }
        }
    }

    fun update(assistant: Assistant) {
        viewModelScope.launch {
            var replaced: Assistant? = null
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            replaced = it
                            assistant
                        } else {
                            it
                        }
                    })
            }
            replaced?.let { old ->
                checkAvatarDelete(old = old, new = assistant) // 删除旧头像
                checkBackgroundDelete(old = old, new = assistant) // 删除旧背景
            }
        }
    }

    /**
     * 用户主动迁移：把旧共享记忆复制到本助手。
     *
     * 只是复制（[MemoryRepository.copyFiles] 会跳过本助手已有的同名文件，不覆盖任何现有内容），
     * 不改 [Assistant.useGlobalMemory]，也不删除旧共享库；失败时把原因留在 [legacyMemoryCopy] 里给页面显示。
     */
    fun copyLegacySharedMemory() {
        if (_legacyMemoryCopy.value is LegacyMemoryCopyState.Running) return
        _legacyMemoryCopy.value = LegacyMemoryCopyState.Running
        viewModelScope.launch {
            val target = memoryId
            try {
                val before = memoryRepository.getFiles(target).mapTo(hashSetOf()) { it.path }
                memoryRepository.copyFiles(MemoryRepository.GLOBAL_MEMORY_ID, target)
                val copied = memoryRepository.getFiles(target).count { it.path !in before }
                _legacyMemoryCopy.value = LegacyMemoryCopyState.Finished(copied)
            } catch (e: Exception) {
                Log.e(TAG, "copyLegacySharedMemory: 复制共享记忆失败", e)
                _legacyMemoryCopy.value = LegacyMemoryCopyState.Failed(
                    e.message ?: e::class.java.simpleName
                )
            }
        }
    }

    /** 用户看完复制结果后收起提示，不影响已经复制过去的文件 */
    fun dismissLegacyMemoryCopyResult() {
        if (_legacyMemoryCopy.value !is LegacyMemoryCopyState.Running) {
            _legacyMemoryCopy.value = LegacyMemoryCopyState.Idle
        }
    }

    fun deleteMemory(memory: MemoryFile) {
        viewModelScope.launch {
            memoryRepository.deleteFile(memoryId, memory.path)
        }
    }

    /** 整理所有待整理的对话，并把整个记忆库清理一遍 */
    fun organizeMemory() {
        memoryConsolidationScheduler.organize(memoryId)
    }

    fun checkAvatarDelete(old: Assistant, new: Assistant) {
        if (old.avatar is Avatar.Image && old.avatar != new.avatar) {
            filesManager.deleteChatFiles(listOf(old.avatar.url.toUri()))
        }
    }

    fun checkBackgroundDelete(old: Assistant, new: Assistant) {
        val oldBackground = old.background
        val newBackground = new.background

        if (oldBackground != null && oldBackground != newBackground) {
            try {
                val oldUri = oldBackground.toUri()
                if (oldUri.scheme == "content" || oldUri.scheme == "file") {
                    filesManager.deleteChatFiles(listOf(oldUri))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete background file: $oldBackground", e)
            }
        }
    }
}

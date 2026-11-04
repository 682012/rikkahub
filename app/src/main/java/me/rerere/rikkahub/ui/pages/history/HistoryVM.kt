package me.rerere.rikkahub.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.organization.ConversationAgeFilter
import me.rerere.rikkahub.data.organization.ConversationDeleteReport
import me.rerere.rikkahub.data.organization.ConversationFilterState
import me.rerere.rikkahub.data.organization.ConversationScope
import me.rerere.rikkahub.data.organization.ConversationSelection
import me.rerere.rikkahub.data.organization.filterConversations
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import java.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

/**
 * 历史页状态。
 *
 * [conversations] 已经是「搜索 + 时间 + 助手范围」筛过之后的列表，[selectedIds] 里只会有
 * 这个列表里的会话——看不见的不会被暗选，确认框上的数量就是真会删掉的数量。
 */
data class HistoryUiState(
    val conversations: List<Conversation> = emptyList(),
    val filter: ConversationFilterState = ConversationFilterState(),
    val selectionMode: Boolean = false,
    val selectedIds: Set<Uuid> = emptySet(),
    val busy: Boolean = false,
) {
    val selectedCount: Int get() = selectedIds.size

    val selectedPinnedCount: Int
        get() = if (selectedIds.isEmpty()) 0
        else conversations.count { it.id in selectedIds && it.isPinned }

    /** 还有没被选上的非置顶会话时，「全选」才有意义。 */
    val canSelectAll: Boolean
        get() = conversations.any { it.id !in selectedIds && !it.isPinned }
}

class HistoryVM(
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
) : ViewModel() {
    val assistant = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val filterState = MutableStateFlow(ConversationFilterState())
    private val selectionMode = MutableStateFlow(false)
    private val selectedIds = MutableStateFlow<Set<Uuid>>(emptySet())
    private val busy = MutableStateFlow(false)

    private val _deleteReport = MutableStateFlow<ConversationDeleteReport?>(null)
    val deleteReport: StateFlow<ConversationDeleteReport?> = _deleteReport.asStateFlow()

    private val sourceConversations = combine(
        assistant.map { it?.id }.distinctUntilChanged(),
        filterState.map { it.scope }.distinctUntilChanged(),
    ) { currentId, scope -> currentId to scope }
        .flatMapLatest { (currentId, scope) ->
            when {
                scope == ConversationScope.ALL_ASSISTANTS -> conversationRepo.observeOrganizationSummaries()
                currentId != null -> conversationRepo.observeOrganizationSummaries(currentId)
                else -> flowOf(emptyList<Conversation>())
            }
        }.catch {
        Log.e(TAG, "Error: ${it.message}")
        emit(emptyList<Conversation>())
    }

    private val visibleConversations = MutableStateFlow<List<Conversation>>(emptyList())

    val conversations: StateFlow<List<Conversation>> = visibleConversations.asStateFlow()

    val uiState: StateFlow<HistoryUiState> = combine(
        visibleConversations,
        filterState,
        selectionMode,
        selectedIds,
        busy,
    ) { visible, filter, mode, selected, isBusy ->
        HistoryUiState(
            conversations = visible,
            filter = filter,
            selectionMode = mode,
            selectedIds = selected,
            busy = isBusy,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUiState())

    init {
        // 列表一变（换助手、换筛选、删完）就重算可见会话，并把选择裁到可见范围内。
        viewModelScope.launch {
            combine(sourceConversations, filterState) { source, filter ->
                filterConversations(source, filter, Instant.now())
            }.collect { visible ->
                visibleConversations.value = visible
                selectedIds.value = ConversationSelection.retainVisible(selectedIds.value, visible)
            }
        }
    }

    // ---- 筛选 ----

    fun setQuery(query: String) {
        if (busy.value) return
        selectedIds.value = emptySet()
        filterState.update { it.copy(query = query) }
    }

    fun setAgeFilter(age: ConversationAgeFilter) {
        if (busy.value) return
        selectedIds.value = emptySet()
        filterState.update { it.copy(age = age) }
    }

    fun setScope(scope: ConversationScope) {
        if (busy.value) return
        selectedIds.value = emptySet()
        filterState.update { it.copy(scope = scope) }
    }

    // ---- 多选 ----

    /** 进多选时清空：不预选任何会话，尤其不预选置顶。 */
    fun enterSelectionMode() {
        if (busy.value) return
        selectedIds.value = emptySet()
        selectionMode.value = true
    }

    fun exitSelectionMode() {
        selectionMode.value = false
        selectedIds.value = emptySet()
    }

    fun toggleSelection(conversationId: Uuid) {
        if (busy.value) return
        selectedIds.update { ConversationSelection.toggle(it, conversationId) }
    }

    /** 全选当前筛选结果，默认跳过置顶（置顶只能手选）。 */
    fun selectAllVisible() {
        if (busy.value) return
        selectedIds.update { ConversationSelection.selectAllVisible(it, visibleConversations.value) }
    }

    fun clearSelection() {
        if (busy.value) return
        selectedIds.value = emptySet()
    }

    // ---- 删除 ----

    /**
     * 单条删除，等真正删完才返回：页面拿到 true 再提供撤销，否则撤销会和进行中的删除打架。
     * 删除只走 [ChatService.deleteConversation]，不动数据库。
     */
    suspend fun deleteConversation(conversation: Conversation): Boolean {
        if (!busy.compareAndSet(expect = false, update = true)) return false
        return try {
            chatService.deleteConversation(conversation.id)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "deleteConversation ${conversation.id} failed: ${e.message}")
            false
        } finally {
            busy.value = false
        }
    }

    /**
     * 批量删除用户已确认的选择：逐条走 [ChatService.deleteConversation]，一条失败不影响其它条。
     * 失败项留在选择里并上报数量；全部成功才退出多选。没有撤销，也不做全量快照。
     */
    suspend fun deleteSelected() {
        if (!busy.compareAndSet(expect = false, update = true)) return
        val targets = ConversationSelection.targets(selectedIds.value, visibleConversations.value)
        if (targets.isEmpty()) {
            busy.value = false
            return
        }
        val failed = mutableListOf<Conversation>()
        var deleted = 0
        try {
            targets.forEach { conversation ->
                try {
                    chatService.deleteConversation(conversation.id)
                    deleted++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "delete ${conversation.id} failed: ${e.message}")
                    failed += conversation
                }
            }
        } finally {
            selectedIds.value = failed.mapTo(LinkedHashSet<Uuid>(failed.size)) { it.id }
            if (failed.isEmpty()) selectionMode.value = false
            _deleteReport.value = ConversationDeleteReport(deleted = deleted, failed = failed)
            busy.value = false
        }
    }

    fun clearDeleteReport() {
        _deleteReport.value = null
    }

    /** 清空当前助手：沿用已有的「删除全部」入口，仍走 ChatService。 */
    suspend fun deleteAllConversations(): Boolean {
        val current = assistant.value ?: return false
        if (!busy.compareAndSet(expect = false, update = true)) return false
        return try {
            chatService.deleteConversationsOfAssistant(current.id)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "deleteAllConversations failed: ${e.message}")
            false
        } finally {
            busy.value = false
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        if (busy.value) return
        viewModelScope.launch {
            chatService.toggleConversationPinned(conversationId)
        }
    }

    fun getPinnedConversations(): Flow<List<Conversation>> =
        conversationRepo.getPinnedConversations()

    suspend fun restoreConversation(conversation: Conversation) {
        chatService.restoreConversation(conversation)
    }

    suspend fun getFullConversation(conversationId: Uuid): Conversation? {
        return chatService.getConversation(conversationId)
    }
}

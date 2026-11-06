package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.organization.autofile.ConversationAutoFileController
import me.rerere.rikkahub.data.organization.grouping.GroupExpansionStore
import me.rerere.rikkahub.data.organization.grouping.GroupMoveOutcome
import me.rerere.rikkahub.data.organization.grouping.GroupMovePlan
import me.rerere.rikkahub.data.organization.grouping.GroupSummary
import me.rerere.rikkahub.data.organization.grouping.canAssignToGroup
import me.rerere.rikkahub.data.organization.grouping.effectiveCollapseFamilies
import me.rerere.rikkahub.data.organization.grouping.remainingSelection
import me.rerere.rikkahub.data.organization.grouping.sanitizeGroupName
import me.rerere.rikkahub.data.organization.grouping.selectionWithinAssistant
import me.rerere.rikkahub.data.organization.grouping.toggleExpandedGroup
import me.rerere.rikkahub.data.organization.grouping.toggleSelectedId
import me.rerere.rikkahub.data.organization.grouping.visibleGroups
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * 抽屉 ViewModel 的 key：必须带上助手 id。
 *
 * ChatPage（跟随抽屉选中的文件夹）和 ChatDrawerContent（真正渲染抽屉）都要拿这个 VM，
 * 两处必须用**同一把 key** 才会命中同一个 NavEntry ViewModelStore 里的同一个实例；
 * 集中在这里定义，避免两处字符串各写一遍、以后改一边就分裂成两个实例
 * （症状：抽屉里选的文件夹传不进输入框、滚动位置来回跳）。
 */
internal fun chatDrawerViewModelKey(assistantId: Uuid): String = "chat-drawer:$assistantId"

/**
 * 批量归组的一句反馈。
 *
 * [isError] 为真表示有会话没搬成（失败或被跨助手挡下），界面用警告样式提示；
 * 不能把「有失败」渲染成和成功一样的提示，否则用户会以为全搬完了。
 */
data class GroupActionMessage(
    val text: String,
    val isError: Boolean,
)

class ChatDrawerVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val chatService: ChatService,
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val savedStateHandle: SavedStateHandle,
    private val autoFileController: ConversationAutoFileController,
) : ViewModel() {

    private val boundAssistantId = MutableStateFlow<Uuid?>(null)
    private val assistantIdFlow = boundAssistantId.filterNotNull().distinctUntilChanged()

    /**
     * 绑定当前会话的助手。
     *
     * 只改绑定值、不重造 ViewModel：调用方（ChatPage / ChatDrawerContent）共用
     * [chatDrawerViewModelKey] 作为 key，因此同一个助手拿到的是同一个实例，
     * 选中文件夹和滚动位置在两个入口之间共享。
     */
    fun bindAssistant(assistantId: Uuid) {
        if (boundAssistantId.value != assistantId) {
            _selectedFolderId.value = null
            _selectedLineageId.value = null
            // 多选状态属于上一个助手，切助手就清掉：免得把别的助手的会话搬进当前助手的组
            clearSelection()
            boundAssistantId.value = assistantId
            viewModelScope.launch(Dispatchers.IO) {
                try { chatService.recoverConversationLineages(assistantId) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { chatService.addError(e) }
            }
        }
    }

    private val sortOrderFlow = settingsStore.settingsFlow
        .map { it.displaySetting.conversationSortOrder }
        .distinctUntilChanged()

    // 当前选中的文件夹筛选，null 表示「未归类」视图
    private val _selectedFolderId = MutableStateFlow<Uuid?>(null)
    val selectedFolderId: StateFlow<Uuid?> = _selectedFolderId.asStateFlow()
    private val _selectedLineageId = MutableStateFlow<Uuid?>(null)
    val selectedLineageId = _selectedLineageId.asStateFlow()
    private val collapseFamilies = assistantIdFlow.flatMapLatest { id ->
        autoFileController.observe(id).map { it.settings.collapseFamilies }
    }.distinctUntilChanged()
    private data class DrawerQuery(val assistantId: Uuid, val folderId: Uuid?, val sortOrder: ConversationSortOrder,
                                   val collapse: Boolean, val lineageId: Uuid?)
    fun selectLineage(id: Uuid?) { _selectedLineageId.value = id }

    // 当前助手的文件夹列表（Room Flow，增删改自动刷新）
    val folders: StateFlow<List<Folder>> = assistantIdFlow
        .flatMapLatest { folderRepo.getFoldersOfAssistant(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- 手动分组（复用文件夹表）：展开状态 + 多选归组 ----

    /**
     * 展开状态的落盘入口。
     *
     * 组本身落在 `conversationentity.folder_id` 上，这里只记「哪些组是展开的」这一份展示状态。
     * 用独立 DataStore 文件按助手分键，重启后还在；SavedStateHandle 只在配置变更/进程回收那一小段有效，
     * 满足不了「重启还记得」。
     */
    private val groupExpansionStore = GroupExpansionStore(context)

    /**
     * 展开的组 id。先给空集（全收起），读盘完成后由 [init] 里的收集器填上。
     */
    private val _expandedGroupIds = MutableStateFlow(emptySet<Uuid>())
    val expandedGroupIds: StateFlow<Set<Uuid>> = _expandedGroupIds.asStateFlow()

    /** 当前助手每个组里的会话数（组标题角标）。 */
    val groupCounts: StateFlow<Map<Uuid, Int>> = assistantIdFlow
        .flatMapLatest { conversationRepo.getGroupCounts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 抽屉里要显示的组：当前助手的组 + 条数 + 展开状态。 */
    val groups: StateFlow<List<GroupSummary>> =
        combine(folders, groupCounts, expandedGroupIds) { folders, counts, expanded ->
            visibleGroups(folders, counts, expanded)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 某个组展开后的成员列表（限量）。始终按「当前助手」解析，切助手时旧组的成员不会漏过来。
     */
    fun groupConversations(folderId: Uuid): Flow<List<Conversation>> =
        assistantIdFlow.flatMapLatest { assistantId ->
            conversationRepo.getGroupConversations(assistantId, folderId)
        }

    fun toggleGroupExpanded(folderId: Uuid) {
        updateExpandedGroups(toggleExpandedGroup(_expandedGroupIds.value, folderId))
    }

    /**
     * 改展开状态：先改内存（点一下马上有反馈），再把这一份写到当前助手名下。
     *
     * 助手 id 取绑定的那个而不是 `first()` 等流：切助手时绑定值立刻变了，等流可能拿到旧助手，
     * 把状态写到别人名下。
     */
    private fun updateExpandedGroups(next: Set<Uuid>) {
        _expandedGroupIds.value = next
        val assistantId = boundAssistantId.value ?: return
        viewModelScope.launch {
            try {
                groupExpansionStore.persist(assistantId, next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 只是展开状态没记住，不影响列表本身，也不该弹错误打扰用户
                Log.w(TAG, "Failed to persist expanded conversation groups", e)
            }
        }
    }

    // 多选模式：进入后点一行是选中/取消，而不是打开会话
    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()
    private val _selectedConversationIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val selectedConversationIds: StateFlow<Set<Uuid>> = _selectedConversationIds.asStateFlow()

    /**
     * 列表真正用的折叠开关：进了多选就临时展开家族。
     *
     * 折叠状态下同族只留一条代表行，其余成员藏在行尾「+N」后面，多选时用户点不到也选不上，
     * 选中归组就会漏掉它们。多选期间一律按「一行一条」显示。
     */
    private val collapseFamiliesInList = combine(collapseFamilies, _selectionMode) { collapse, selecting ->
        effectiveCollapseFamilies(collapse, selecting)
    }.distinctUntilChanged()

    /** 批量操作给用户的一句话反馈（成功条数 / 哪几条没搬成）。界面提示后调 [consumeGroupActionMessage]。 */
    private val _groupActionMessage = MutableStateFlow<GroupActionMessage?>(null)
    val groupActionMessage: StateFlow<GroupActionMessage?> = _groupActionMessage.asStateFlow()

    fun consumeGroupActionMessage() {
        _groupActionMessage.value = null
    }

    fun startSelection(conversationId: Uuid) {
        _selectionMode.value = true
        _selectedConversationIds.value = _selectedConversationIds.value + conversationId
    }

    fun toggleSelection(conversationId: Uuid) {
        val next = toggleSelectedId(_selectedConversationIds.value, conversationId)
        _selectedConversationIds.value = next
        // 全取消就退出多选，免得留一个空的多选条占着位置
        if (next.isEmpty()) _selectionMode.value = false
    }

    fun clearSelection() {
        _selectionMode.value = false
        _selectedConversationIds.value = emptySet()
    }

    /**
     * 把多选中的会话移进 [folderId]（null 表示移出分组）。
     *
     * 三道闸：
     * 1. 目标组必须存在且属于当前助手，否则一条都不搬（[canAssignToGroup]）；
     * 2. 每条会话按库里的真实归属再确认一次仍属于当前助手（[ownSelection]）——切换助手或会话被
     *    移交给别的助手之后就搬不动了，跨助手禁止混组；
     * 3. 落库走 [ChatService.moveConversationToFolder]：活跃会话先同步内存态，避免之后整对象保存
     *    把 folder_id 写回旧值；底层 repo 的事务里还会校验一次 folder.assistantId。
     *
     * 错误处理：单条失败（会话刚被删、组刚被删）只跳过那一条并计入失败，不清空选中、不假装成功；
     * [CancellationException] 一律往上抛，不吞成「已移动」。
     */
    fun moveSelectionToFolder(folderId: Uuid?) {
        val ids = _selectedConversationIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val assistantId = boundAssistantId.value ?: return@launch
            if (folderId != null && !canAssignToGroup(folderRepo.getFolderById(folderId), assistantId)) {
                // 组在这期间被删了或属于别的助手：选中保留，让用户改选目标
                _groupActionMessage.value = GroupActionMessage(
                    text = context.getString(R.string.chat_page_group_target_missing),
                    isError = true,
                )
                return@launch
            }
            val plan = ownSelection(ids, assistantId)
            val outcome = applyMoves(plan.accepted, folderId)
            finishMove(plan, outcome, isNewGroup = false)
        }
    }

    /**
     * 用多选的会话新建一个组，并把它们移进去。
     * 组名清洗后为空时什么都不做（不建无名组）。
     */
    fun createGroupFromSelection(name: String): Boolean {
        val trimmed = sanitizeGroupName(name) ?: return false
        val ids = _selectedConversationIds.value.toList()
        if (ids.isEmpty()) return false
        viewModelScope.launch {
            val assistantId = boundAssistantId.value ?: return@launch
            val plan = ownSelection(ids, assistantId)
            if (plan.accepted.isEmpty()) {
                // 一条都搬不了就别建空组，直接把原因说出来
                finishMove(plan, GroupMoveOutcome(emptyList(), emptyList(), emptyList()), isNewGroup = false)
                return@launch
            }
            val folder = folderRepo.createFolder(assistantId, trimmed)
            // 新组默认展开，用户能立刻看到搬进去了什么
            updateExpandedGroups(_expandedGroupIds.value + folder.id)
            val outcome = applyMoves(plan.accepted, folder.id)
            finishMove(plan, outcome, isNewGroup = true, newGroupName = trimmed)
        }
        return true
    }

    /**
     * 逐条搬，单条失败只记下这一条。
     *
     * 返回的结果里 [GroupMoveOutcome.moved] 才是真正搬成功的，用来决定选中要清到什么程度。
     */
    private suspend fun applyMoves(ids: List<Uuid>, folderId: Uuid?): GroupMoveOutcome {
        val moved = mutableListOf<Uuid>()
        val failed = mutableListOf<Uuid>()
        for (id in ids) {
            try {
                chatService.moveConversationToFolder(id, folderId)
                moved += id
            } catch (e: CancellationException) {
                // 页面关闭/协程取消不是「失败」，也不能当成搬成功继续下一条
                throw e
            } catch (e: Exception) {
                failed += id
                Log.w(TAG, "Failed to move conversation $id into folder $folderId", e)
            }
        }
        return GroupMoveOutcome(moved = moved, failed = failed, rejected = emptyList())
    }

    /**
     * 收尾：只把**真正搬走**的从选中里去掉，失败/被挡下的留着让用户重试；全清空了才退出多选。
     * 无论结果如何都要给一句反馈，不能静默清空选中让人以为成功了。
     */
    private fun finishMove(
        plan: GroupMovePlan,
        outcome: GroupMoveOutcome,
        isNewGroup: Boolean,
        newGroupName: String? = null,
    ) {
        val full = outcome.copy(rejected = plan.rejected)
        _selectedConversationIds.value = remainingSelection(_selectedConversationIds.value, full)
        if (_selectedConversationIds.value.isEmpty()) _selectionMode.value = false

        val text = when {
            full.isComplete && full.moved.isEmpty() ->
                // 一条都没搬（全都不是当前助手的会话），说清楚为什么
                context.getString(R.string.chat_page_group_move_rejected, plan.rejected.size)
            full.isComplete ->
                context.getString(R.string.chat_page_group_moved, full.moved.size)
            isNewGroup && full.moved.isEmpty() && newGroupName != null ->
                context.getString(R.string.chat_page_group_move_failed_new, newGroupName, full.problemCount)
            else ->
                context.getString(R.string.chat_page_group_move_failed, full.moved.size, full.problemCount)
        }
        _groupActionMessage.value = GroupActionMessage(
            text = text,
            isError = !full.isComplete || full.moved.isEmpty(),
        )
    }

    /** 按库里的真实归属把选中项分成「当前助手的」和「别处来的」。 */
    private suspend fun ownSelection(ids: List<Uuid>, assistantId: Uuid): GroupMovePlan {
        val owners = ids.associateWith { id ->
            conversationRepo.getConversationAssistantId(id.toString())
                ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
        }
        return selectionWithinAssistant(ids, assistantId, owners::get)
    }

    // 当前助手的记忆库是否正在后台整理；没开记忆的助手即使共用的全局记忆库在整理也不显示
    val memoryDreaming: StateFlow<Boolean> = combine(settingsStore.settingsFlow, assistantIdFlow) { settings, id ->
            settings.assistants.firstOrNull { it.id == id && it.enableMemory }?.memoryId
        }
        .distinctUntilChanged()
        .flatMapLatest { memoryId ->
            if (memoryId == null) flowOf(false) else memoryConsolidationScheduler.statusOf(memoryId).map { it.running }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val conversations: Flow<PagingData<ConversationListItem>> =
        combine(assistantIdFlow, _selectedFolderId, sortOrderFlow, collapseFamiliesInList, _selectedLineageId) {
                assistantId, folderId, sortOrder, collapse, lineageId ->
            DrawerQuery(assistantId, folderId, sortOrder, collapse, lineageId)
        }
            .flatMapLatest { (assistantId, folderId, sortOrder, collapse, lineageId) ->
                val ownedFolder = folderId?.takeIf { folderRepo.getFolderById(it)?.assistantId == assistantId }
                val paging = if (lineageId != null) {
                    conversationRepo.getConversationLineagePaging(assistantId, lineageId, ownedFolder, sortOrder)
                } else if (ownedFolder == null) {
                    conversationRepo.getUnfiledConversationsOfAssistantPaging(assistantId, sortOrder, collapse)
                } else {
                    conversationRepo.getConversationsOfFolderPaging(ownedFolder, sortOrder, collapse, assistantId)
                }
                paging.map { pagingData ->
                    pagingData
                        .map { ConversationListItem.Item(it) }
                        .insertSeparators<ConversationListItem.Item, ConversationListItem> { before, after ->
                            when {
                                after == null -> null
                                after.conversation.isPinned -> {
                                    if (before == null) ConversationListItem.PinnedHeader else null
                                }

                                else -> {
                                    // 日期分组跟随排序依据：按创建时间排序时以创建日期分组
                                    val afterDate = after.conversation.sortDate(sortOrder)
                                    val sameGroup = before != null &&
                                        !before.conversation.isPinned &&
                                        before.conversation.sortDate(sortOrder) == afterDate
                                    if (sameGroup) {
                                        null
                                    } else {
                                        ConversationListItem.DateHeader(
                                            date = afterDate,
                                            label = getDateLabel(afterDate),
                                            sortOrder = sortOrder,
                                        )
                                    }
                                }
                            }
                        }
                }
            }
            .cachedIn(viewModelScope)

    val scrollIndex: Int get() = savedStateHandle["scrollIndex"] ?: 0
    val scrollOffset: Int get() = savedStateHandle["scrollOffset"] ?: 0

    init {
        // 助手切换时重置文件夹筛选，回到「聊天」视图，
        // 避免继续显示上一个助手文件夹内的会话（文件夹是助手内分组）
        viewModelScope.launch {
            assistantIdFlow.collect {
                _selectedFolderId.value = null
            }
        }
        // 展开状态按助手读盘：切助手就换成那个助手自己的展开记录，重启后也还在
        viewModelScope.launch {
            assistantIdFlow
                .flatMapLatest { assistantId -> groupExpansionStore.expandedIdsOf(assistantId) }
                .collect { ids -> _expandedGroupIds.value = ids }
        }
    }

    fun saveScrollPosition(index: Int, offset: Int) {
        savedStateHandle["scrollIndex"] = index
        savedStateHandle["scrollOffset"] = offset
    }

    fun selectFolder(folderId: Uuid?) {
        _selectedLineageId.value = null
        _selectedFolderId.value = folderId
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val assistantId = assistantIdFlow.first()
            folderRepo.createFolder(assistantId, trimmed)
        }
    }

    fun renameFolder(folderId: Uuid, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            folderRepo.renameFolder(folderId, trimmed)
        }
    }

    /**
     * 删除文件夹。若文件夹内有正在生成回复的会话，拒绝删除并返回 false（UI 层据此提示用户）。
     */
    fun deleteFolder(folderId: Uuid): Boolean {
        if (chatService.hasGeneratingConversationInFolder(folderId)) {
            return false
        }
        viewModelScope.launch {
            // 经 ChatService 删除：会同步清空活跃 session 内存态的 folderId，避免整对象保存写回已删文件夹
            chatService.deleteFolder(folderId)
            if (_selectedFolderId.value == folderId) {
                _selectedFolderId.value = null
            }
            // 组没了，展开状态里也别留着它
            updateExpandedGroups(_expandedGroupIds.value - folderId)
        }
        return true
    }

    fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        viewModelScope.launch {
            // 经 ChatService 移动：活跃会话会先同步内存态，避免后续整对象保存覆盖 folder_id
            chatService.moveConversationToFolder(conversationId, folderId)
        }
    }

    private fun Conversation.sortDate(sortOrder: ConversationSortOrder): LocalDate {
        val instant = when (sortOrder) {
            ConversationSortOrder.UPDATE_TIME -> updateAt
            ConversationSortOrder.CREATE_TIME -> createAt
        }
        return instant.atZone(ZoneId.systemDefault()).toLocalDate()
    }

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }

    private companion object {
        const val TAG = "ChatDrawerVM"
    }
}

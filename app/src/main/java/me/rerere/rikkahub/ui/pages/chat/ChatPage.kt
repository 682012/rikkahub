package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ui.toast.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.markSequentialImages
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.ChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.SearchMode
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.rememberChatAttachmentPickerActions
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.LocalAppearance
import me.rerere.rikkahub.ui.theme.chatPageContainerColor
import me.rerere.rikkahub.ui.theme.rememberChatStyleSpec
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    // key 必须带上会话 id：koinViewModel 不传 key 时默认按类名取，一旦 NavDisplay/抽屉把两个会话
    // 复用成同一个 ViewModelStore（同一 entry 换参数、或共享 owner），拿到的还是 A 会话的 ChatVM，
    // 于是 B 的输入框和发送都落到 A 上——「在 A 发起任务切到 B 会送错会话」。带上 id 后每个会话
    // 一定拿到自己的 ChatVM（也是自己的 inputState / 草稿 key / 发送目标）。
    val vm: ChatVM = koinViewModel(
        key = id.toString(),
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    // 新会话发出第一条消息前，跟随抽屉当前选中的文件夹（#2133）
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity)
    val selectedFolderId by drawerVm.selectedFolderId.collectAsStateWithLifecycle()
    LaunchedEffect(selectedFolderId) {
        vm.updateDraftFolder(selectedFolderId)
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Clear input focus so popup transitions cannot reopen the keyboard.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val startVoiceMode = rememberVoiceModeStarter(vm, setting)

    val inputState = vm.inputState

    // 草稿恢复完再导入分享进来的内容：等恢复完再加，分享的文字/附件是加在草稿后面，
    // 而不是把刚恢复出来的草稿冲掉。
    val draftReady by vm.draftReady.collectAsStateWithLifecycle()
    // 导入只做一次，但换会话/换分享内容时必须重新做：只用一个裸 boolean 会在复用页面时吞掉新的分享。
    // 同时按 vm 记忆：换了 ChatVM（另一个会话）就不能沿用上一个页面的「已导入」。
    var importApplied by rememberSaveable(vm, id, text, files) { mutableStateOf(false) }

    // 进程可能在页面离开后被杀：暂停和离开时都存一次（onCleared 只是最后一道保险）
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        vm.persistDraftNow()
    }
    DisposableEffect(vm) {
        onDispose { vm.persistDraftNow() }
    }

    // 初始化输入状态（处理传入的 files 和 text 参数）
    // 按 vm 分组：换会话后必须用新会话的 inputState，绝不能把分享内容写进上一个会话的输入框
    LaunchedEffect(vm, files, text, draftReady) {
        if (!draftReady || importApplied) return@LaunchedEffect
        importApplied = true
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val parts = buildList {
                // 按本地文件下标取对应的 mime：mapNotNull 会丢掉拿不到类型的文件，
                // 之后的图片/视频就会错位认领别人的类型
                localFiles.forEachIndexed { index, file ->
                    val source = files.getOrNull(index)
                    val type = source?.let { filesManager.getFileMimeType(it) }
                    when {
                        type?.startsWith("image/") == true -> add(UIMessagePart.Image(url = file.toString()))
                        type?.startsWith("video/") == true -> add(UIMessagePart.Video(url = file.toString()))
                        type?.startsWith("audio/") == true -> add(UIMessagePart.Audio(url = file.toString()))
                        else -> add(
                            UIMessagePart.Document(
                                url = file.toString(),
                                fileName = source?.let { filesManager.getFileNameFromUri(it) } ?: "file",
                                mime = type ?: "application/octet-stream",
                            ),
                        )
                    }
                }
            }
            inputState.messageContent = inputState.messageContent + parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                if (inputState.textContent.text.isBlank()) {
                    inputState.setMessageText(decodedText)
                } else {
                    inputState.appendText("\n$decodedText")
                }
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    me.rerere.rikkahub.ui.components.message.MessageCollapseStateHolder.requestExpand(nodeId.toString())
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    // 三套风格与壁纸设置的唯一来源：header / 输入区 / 消息卡 / 抽屉都读同一个 LocalAppearance，
    // 切换风格时不会只变一半。
    val appearance = setting.displaySetting.appearance.normalized()
    CompositionLocalProvider(LocalAppearance provides appearance) {
        me.rerere.rikkahub.ui.theme.ChatAppearanceHost(setting, assistant) {
        when {
            isBigScreen -> {
                PermanentNavigationDrawer(
                    drawerContent = {
                        ChatDrawerContent(
                            navController = navController,
                            current = conversation,
                            vm = vm,
                            settings = setting
                        )
                    }
                ) {
                    ChatPageContent(
                        onStartVoiceMode = startVoiceMode,
                        inputState = inputState,
                        loadingJob = loadingJob,
                        processingStatus = processingStatus,
                        setting = setting,
                        conversation = conversation,
                        drawerState = drawerState,
                        navController = navController,
                        vm = vm,
                        chatListState = chatListState,
                        assistant = assistant,
                        currentChatModel = currentChatModel,
                        bigScreen = true,
                        errors = errors,
                        onDismissError = { vm.dismissError(it) },
                        onClearAllErrors = { vm.clearAllErrors() },
                    )
                }
            }

            else -> {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ChatDrawerContent(
                            navController = navController,
                            current = conversation,
                            vm = vm,
                            settings = setting
                        )
                    }
                ) {
                    ChatPageContent(
                        onStartVoiceMode = startVoiceMode,
                        inputState = inputState,
                        loadingJob = loadingJob,
                        processingStatus = processingStatus,
                        setting = setting,
                        conversation = conversation,
                        drawerState = drawerState,
                        navController = navController,
                        vm = vm,
                        chatListState = chatListState,
                        assistant = assistant,
                        currentChatModel = currentChatModel,
                        bigScreen = false,
                        errors = errors,
                        onDismissError = { vm.dismissError(it) },
                        onClearAllErrors = { vm.clearAllErrors() },
                    )
                }
                BackHandler(drawerState.isOpen) {
                    scope.launch { drawerState.close() }
                }
            }
        }
    }
    }
}

@Composable
private fun ChatPageContent(
    onStartVoiceMode: () -> Unit,
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    assistant: Assistant,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()

    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    var showFilesSheet by remember { mutableStateOf(false) }
    // 多图发送先让用户选路：一次发保持默认语义；逐张识别会额外调用 N 次视觉请求。
    // 以会话 ID 作 key，切到另一个对话时不把 A 的确认弹窗/张数带过去。
    var showMultiImageSendDialog by remember(conversation.id) { mutableStateOf(false) }
    var pendingMultiImageCount by remember(conversation.id) { mutableStateOf(0) }
    val canUseSequentialImageSend = currentChatModel?.inputModalities?.contains(Modality.IMAGE) == true

    fun submitCurrentInput(answer: Boolean, sequentialImages: Boolean = false) {
        val currentParts = inputState.getContents()
        val parts = if (sequentialImages) markSequentialImages(currentParts) else currentParts
        val accepted = if (inputState.isEditing()) {
            inputState.editingMessage?.let { vm.handleMessageEdit(parts, it) } ?: false
        } else {
            vm.handleMessageSend(parts, answer)
        }
        if (accepted) {
            scope.launch {
                if (answer) delay(100.milliseconds)
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
        }
    }
    // 相机/相册/文件的 launcher 回调会捕获发起那一刻的 inputState：换会话后 Composition 复用、
    // 回调没重建时，选好的附件会塞进上一个会话的输入框（A 选图，图跑到 B）。按 inputState 记住，
    // 换会话就换一组 launcher，附件只会进发起选择的那个会话。
    val attachmentPickerActions = key(inputState) {
        rememberChatAttachmentPickerActions(
            inputState = inputState,
            setting = setting,
            onAttachmentAdded = { showFilesSheet = false },
        )
    }
    val allowAudioVideoAttachments =
        currentChatModel?.findProvider(setting.providers) is ProviderSetting.Google

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        // 有壁纸/沉浸时这一层必须透出去，让全局背景露出来；没壁纸时等价于原来的 background
        color = chatPageContainerColor(),
        modifier = Modifier.fillMaxSize()
    ) {
        // Backdrop is owned by ChatAppearanceHost outside the drawer; do not decode it twice.
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    assistant = assistant,
                    chatModel = currentChatModel,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    },
                    onHeaderExpandedChange = { expanded -> vm.updateSettings { latest ->
                        latest.copy(displaySetting = latest.displaySetting.copy(
                            appearance = latest.displaySetting.appearance.copy(chatHeaderExpanded = expanded)))
                    } },
                )
            },
            bottomBar = {
                val messageQueue by vm.messageQueue.collectAsStateWithLifecycle()
                val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
                ChatInput(
                    onStartVoiceMode = onStartVoiceMode,
                    voiceState = voiceState,
                    onStopVoiceMode = vm.voiceSession::stop,
                    state = inputState,
                    messageQueue = messageQueue,
                    onRemoveQueuedMessage = vm::removeQueuedMessage,
                    onBeginEditQueuedMessage = vm::beginEditQueuedMessage,
                    onFinishEditQueuedMessage = vm::finishEditQueuedMessage,
                    onResumeMessageQueue = vm::resumeMessageQueue,
                    loading = loadingJob != null,
                    settings = setting,
                    assistant = assistant,
                    chatModel = currentChatModel,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        val imageCount = inputState.getContents().count { it is UIMessagePart.Image }
                        if (imageCount > 1) {
                            pendingMultiImageCount = imageCount
                            showMultiImageSendDialog = true
                        } else {
                            submitCurrentInput(answer = true)
                        }
                    },
                    onLongSendClick = {
                        // 长按只入队、不发起模型回答，所以沿用一次发送，不启动逐张识别请求。
                        submitCurrentInput(answer = false)
                    },
                    onUpdateChatModel = vm::setChatModel,
                    onUpdateAssistant = vm::updateAssistant,
                    onMoreClick = {
                        showFilesSheet = true
                    },
                )
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.selectMessageNode(newNode.id, newNode.selectIndex)
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversationSystemPrompt(newPrompt)
                },
            )
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                chatModel = currentChatModel,
                vm = vm,
                attachmentPickerActions = attachmentPickerActions,
                onStartVoiceMode = onStartVoiceMode,
                onDismiss = { showFilesSheet = false },
            )
        }
    }
    if (showMultiImageSendDialog) {
        AlertDialog(
            onDismissRequest = { showMultiImageSendDialog = false },
            title = {
                Text(stringResource(R.string.r16_multi_image_title, pendingMultiImageCount))
            },
            text = {
                Text(
                    if (canUseSequentialImageSend) {
                        stringResource(R.string.r16_multi_image_message, pendingMultiImageCount)
                    } else {
                        stringResource(R.string.r16_multi_image_unsupported)
                    }
                )
            },
            confirmButton = {
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            showMultiImageSendDialog = false
                            submitCurrentInput(answer = true, sequentialImages = true)
                        },
                        enabled = canUseSequentialImageSend,
                    ) {
                        Text(stringResource(R.string.r16_multi_image_sequential))
                    }
                    TextButton(onClick = {
                        showMultiImageSendDialog = false
                        submitCurrentInput(answer = true)
                    }) {
                        Text(stringResource(R.string.r16_multi_image_once))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMultiImageSendDialog = false }) {
                    Text(stringResource(R.string.r16_multi_image_cancel))
                }
            },
        )
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    chatModel: Model?,
    vm: ChatVM,
    attachmentPickerActions: ChatAttachmentPickerActions,
    onStartVoiceMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showCompressDialog = false
        onDismiss()
    }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            chatModel = chatModel,
            mcpManager = vm.mcpManager,
            onCompressContext = vm::handleCompressContext,
            onUpdateAssistant = vm::updateAssistant,
            onUpdateWorkspaceCwd = vm::updateWorkspaceCwd,
            onUpdateSearchMode = { mode ->
                vm.updateSearch(
                    enableWebSearch = mode == SearchMode.LOCAL,
                    builtInSearch = mode == SearchMode.BUILT_IN,
                )
            },
            onUpdateSearchService = { index ->
                vm.updateSettings { it.copy(searchServiceSelected = index) }
            },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = attachmentPickerActions.onTakePicture,
            onPickImage = attachmentPickerActions.onPickImage,
            onPickVideo = attachmentPickerActions.onPickVideo,
            onPickAudio = attachmentPickerActions.onPickAudio,
            onPickFile = attachmentPickerActions.onPickFile,
            onSketch = attachmentPickerActions.onSketch,
            onStartVoiceMode = if (
                setting.getSelectedASRProvider()?.supportsServerVadVoiceMode == true &&
                voiceState.phase == VoicePhase.Off
            ) {
                {
                    dismissAll()
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onStartVoiceMode()
                }
            } else null,
        )
    }


}

@Composable
private fun TopBar(
    settings: Settings,
    assistant: Assistant,
    chatModel: Model?,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    onHeaderExpandedChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }
    var showUsage by remember { mutableStateOf(false) }
    val styleSpec = rememberChatStyleSpec()

    val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
    // 标题栏第二行固定是“提供商 · 模型”，助手名跟在后面；不再把模型信息塞给 meta
    val metaText = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }

    // 标题栏本身按风格换形态；抽屉、编辑标题、用量、预览、新会话这些回调三套共用
    ChatStyleHeader(
        spec = styleSpec,
        settings = settings,
        conversation = conversation,
        chatModel = chatModel,
        title = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
        meta = metaText,
        bigScreen = bigScreen,
        previewMode = previewMode,
        onOpenDrawer = { scope.launch { drawerState.open() } },
        onEditTitle = {
            if (conversation.messageNodes.isNotEmpty()) {
                titleState.open(conversation.title)
            } else {
                toaster.show(editTitleWarning, type = ToastType.Warning)
            }
        },
        onOpenUsage = { showUsage = true },
        onTogglePreview = onClickMenu,
        onNewChat = onNewChat,
        headerExpanded = settings.displaySetting.appearance.chatHeaderExpanded,
        onHeaderExpandedChange = onHeaderExpandedChange,
    )

    if (showUsage) {
        UsageSheet(conversation = conversation) { showUsage = false }
    }

    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

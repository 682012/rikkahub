package me.rerere.rikkahub.di

import android.content.Context
import kotlinx.coroutines.launch
import java.io.File
import me.rerere.rikkahub.data.ai.learning.AssistantLearningRepository
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.ChatRequestFactory
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.BackgroundShellManager
import me.rerere.rikkahub.service.ChatFloatingTracker
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.ui.floating.ChatFloatingWindows
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    includes(r22RuntimeModule)
    single(createdAtStart = true) {
        me.rerere.rikkahub.service.ChatRecoveryNotificationManager(
            context = get(), appScope = get(), eventBus = get(), settingsStore = get())
    }
    single { AssistantLearningRepository(File(get<Context>().filesDir, "assistant-learning")) }
    // 每轮经验总结（真实写入助手记忆文件 + 该助手自己的本地知识库）。
    // 默认关闭：开关在 LearningState.experienceReflection（每助手一份，额外一次模型请求会计费）。
    single {
        me.rerere.rikkahub.data.ai.learning.ExperienceLedger(
            File(get<Context>().filesDir, "assistant-learning-experience")
        )
    }
    single {
        me.rerere.rikkahub.data.ai.learning.ExperienceService(
            appScope = get(),
            settingsStore = get(),
            providerManager = get(),
            usageRecorder = get(),
            memoryRepository = get(),
            knowledgeRepository = get(),
            ledger = get(),
            flagsOf = { assistantId ->
                get<AssistantLearningRepository>().experienceSettings(assistantId.toString())
            },
        )
    }
    // 启动即接线：每轮指标落盘后触发一次经验总结，不需要改 ChatService 的依赖列表
    single(createdAtStart = true) {
        me.rerere.rikkahub.data.ai.learning.ExperienceHook(
            learningRepository = get(),
            experienceService = get(),
        )
    }
    single(createdAtStart = true) {
        me.rerere.rikkahub.data.organization.autofile.ConversationAutoFileController(
            storeDir = File(get<Context>().filesDir, "conversation_autofile"),
            gateway = me.rerere.rikkahub.data.organization.autofile.RoomConversationAutoFileGateway(get(), get(), get()),
            classifier = me.rerere.rikkahub.data.organization.autofile.FastModelAutoFileClassifier(get(), get(), get()),
            scheduler = me.rerere.rikkahub.data.organization.autofile.WorkManagerConversationAutoFileScheduler(get()),
        ).also { controller ->
            get<me.rerere.rikkahub.AppScope>().launch(kotlinx.coroutines.Dispatchers.IO) { controller.initialize() }
        }
    }
    single { me.rerere.rikkahub.data.organization.ImageOrganizerController(
        context = get(), appScope = get(), filesManager = get(), genMediaRepository = get(),
        providerManager = get(), settingsStore = get(), mediaCreationRepository = get()) }

    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get())
    }

    single {
        UpdateChecker(
            client = get(),
            appScope = get(),
        )
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        Firebase.crashlytics
    }

    single {
        Firebase.analytics
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get(), get(), get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    // ChatService 只在后台才用到，延迟创建
    single {
        ChatFloatingTracker(
            appScope = get(),
            lazyChatService = lazy { get() },
            settingsStore = get(),
        )
    }

    // 同上，进程启动即开始监听前后台切换
    single(createdAtStart = true) {
        ChatFloatingWindows(
            context = get(),
            appScope = get(),
            tracker = get(),
            settingsStore = get(),
            lazyChatService = lazy { get() },
            companionSource = lazy { get() },
        )
    }

    // 悬浮窗小助手的辅助请求：复用 ProviderManager / UsageRecorder，单独记 COMPANION 用量
    single<me.rerere.rikkahub.data.companion.CompanionReplySource> {
        me.rerere.rikkahub.data.ai.CompanionReplyGenerator(
            settingsStore = get(),
            providerManager = get(),
            usageRecorder = get(),
            lazyChatService = lazy { get() },
        )
    }

    single {
        me.rerere.rikkahub.data.ai.subagent.SubagentRunner(
            context = get(),
            providerManager = get(),
            settingsStore = get(),
            usageRecorder = get(),
        )
    }

    single {
        val context: android.content.Context = get()
        me.rerere.rikkahub.data.ai.tools.background.BackgroundToolManager(
            rootDir = java.io.File(context.filesDir, me.rerere.rikkahub.data.ai.tools.background.BACKGROUND_TOOLS_DIR),
            lease = me.rerere.rikkahub.data.ai.tools.background.AndroidBackgroundForegroundLease(context),
        )
    }

    single {
        ChatToolFactory(
            context = get(),
            memoryRepository = get(),
            conversationRepository = get(),
            localTools = get(),
            mcpManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            subagentRunner = get(),
            backgroundShellManager = get(),
            backgroundToolManager = get(),
            deviceBridge = get(),
            localMcpSettings = get(),
            localKnowledgeRepository = get(),
            highPrivilegeToolHost = get(),
        )
    }

    single {
        BackgroundShellManager(
            context = get(),
            workspaceRepository = get(),
        )
    }

    single {
        ChatRequestFactory(
            context = get(),
            providerManager = get(),
            usageRecorder = get(),
            chatToolFactory = get(),
            memoryRepository = get(),
            learningRepository = get(),
            knowledgeRepository = get(),
            templateTransformer = get(),
            workspaceRepository = get(),
            okHttpClient = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            requestFactory = get(),
            learningRepository = get(),
            memoryConsolidationScheduler = get(),
            translationHandler = get(),
            metaGenerator = get(),
            mcpManager = get(),
            filesManager = get(),
            folderRepository = get(),
            backgroundShellManager = get(),
            subagentRunner = get(),
            backgroundToolManager = get(),
            networkHandover = get(),
            // m1 输入优化/自查/独立审查的执行入口（契约见 meridian-m1/inputassist/API-CONTRACT.md）
            inputAssist = get(),
        )
    }

    single {
        MediaCreationService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            repository = get(),
            manager = get(),
            remoteFileStore = get(),
            okHttpClient = get(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}

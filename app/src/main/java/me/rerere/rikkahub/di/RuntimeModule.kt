package me.rerere.rikkahub.di

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.automation.AutomationController
import me.rerere.rikkahub.automation.AutomationToolRunner
import me.rerere.rikkahub.data.devicecontrol.DeviceControlBridge
import me.rerere.rikkahub.data.devicecontrol.DeviceControlPreferences
import me.rerere.rikkahub.data.mcpserver.AppAutomationToolRunner
import me.rerere.rikkahub.data.mcpserver.AppLocalMcpToolHost
import me.rerere.rikkahub.data.mcpserver.AppMcpGatesSource
import me.rerere.rikkahub.data.mcpserver.AppMcpToolHost
import me.rerere.rikkahub.data.mcpserver.LocalMcpServerManager
import me.rerere.rikkahub.data.mcpserver.LocalMcpSettingsStore
import me.rerere.rikkahub.data.mcpserver.LocalMcpToolHost
import me.rerere.rikkahub.data.mcpserver.appMcpGates
import me.rerere.rikkahub.data.organization.autofile.ConversationAutoFileFacade
import org.koin.dsl.module

/** Optional local runtime. Creating the module never opens a port or grants device access. */
val r22RuntimeModule = module {
    single { DeviceControlPreferences(get()) }
    single { DeviceControlBridge(get(), get()) }
    single { LocalMcpSettingsStore(get()) }
    single { ConversationAutoFileFacade(get()) }
    // m1 输入优化/自查：只在用户开启时才发请求；这里创建实例本身不联网、不读用户消息
    single {
        me.rerere.rikkahub.data.ai.inputassist.InputOptimizationCache(
            dir = java.io.File(get<android.content.Context>().filesDir, "input-assist"),
        )
    }
    single {
        me.rerere.rikkahub.data.ai.inputassist.InputOptimizer(
            providerManager = get(),
            usageRecorder = get(),
            cache = get(),
        )
    }
    single {
        me.rerere.rikkahub.data.ai.inputassist.TurnReviewer(
            providerManager = get(),
            usageRecorder = get(),
        )
    }
    single {
        me.rerere.rikkahub.data.ai.inputassist.InputAssistService(
            optimizer = get(),
            reviewer = get(),
            scope = get<AppScope>(),
            cache = get(),
        )
    }
    // m1 本地知识库：独立 SQLite 文件 local_knowledge.db，不参与 Room schema；只在本地检索
    single {
        me.rerere.rikkahub.data.knowledge.LocalKnowledgeRepository(get())
    }
    single<AppMcpGatesSource> {
        val store: LocalMcpSettingsStore = get()
        AppMcpGatesSource {
            val s = store.current()
            appMcpGates(s.assistantId, s.allowRead, s.allowMutations,
                s.allowModelRequests, s.allowDevice, s.allowAutomation)
        }
    }
    single {
        AppMcpToolHost(
            context = get(), gatesSource = get(), conversations = get(), folders = get(),
            settingsStore = get(), chatService = { get() }, subagentRunner = { get() },
            autoFileFacade = { get() }, deviceBridge = { get() }, automationController = { get() },
            // 本地自动化（AutomationStep）经同一个 host 到达高权限工具；授权仍由权限中心自己核。
            privilegedTools = { get() },
        )
    }
    // m1 高权限中心：控制器与 WorkspacePrivilegeManager 共用同一个实例，**不**在这里 close 后者。
    single { me.rerere.rikkahub.data.deviceprivilege.HighPrivilegeController(get(), get()) }
    single {
        me.rerere.rikkahub.data.deviceprivilege.HighPrivilegeToolHost(
            controller = get(),
            // 独立的幂等账本：只记高权限工具的写，不污染 r22-mcp 的那份。
            ledger = me.rerere.rikkahub.data.mcpserver.AppMcpIdempotencyLedger(
                java.io.File(get<android.content.Context>().filesDir, "deviceprivilege/tool-idempotency.json"),
            ),
        )
    }
    // 同一个实例以接口形式再暴露一次（MCP 代理面只需要接口）。
    single<me.rerere.rikkahub.data.deviceprivilege.HighPrivilegeMcpProxy> {
        get<me.rerere.rikkahub.data.deviceprivilege.HighPrivilegeToolHost>()
    }
    single<LocalMcpToolHost> { AppLocalMcpToolHost(get(), get(), get()) }
    single { LocalMcpServerManager(context = get(), host = get(), settingsStore = get()) }
    single<AutomationToolRunner> { AppAutomationToolRunner(get(), get()) }
    single(createdAtStart = true) {
        AutomationController(context = get(), runner = get()).also { controller ->
            me.rerere.rikkahub.automation.AutomationWorkerAccess.install(controller)
            get<AppScope>().launch(Dispatchers.IO) { controller.initialize() }
        }
    }
}

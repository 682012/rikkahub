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
        )
    }
    single<LocalMcpToolHost> { AppLocalMcpToolHost(get()) }
    single { LocalMcpServerManager(context = get(), host = get(), settingsStore = get()) }
    single<AutomationToolRunner> { AppAutomationToolRunner(get(), get()) }
    single(createdAtStart = true) {
        AutomationController(context = get(), runner = get()).also { controller ->
            me.rerere.rikkahub.automation.AutomationWorkerAccess.install(controller)
            get<AppScope>().launch(Dispatchers.IO) { controller.initialize() }
        }
    }
}

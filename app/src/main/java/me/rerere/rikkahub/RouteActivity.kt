package me.rerere.rikkahub

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import me.rerere.ui.toast.Toaster
import me.rerere.ui.toast.rememberToasterState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker
import me.rerere.rikkahub.data.db.MigrationState
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.service.MediaCreationForegroundService
import me.rerere.rikkahub.startup.StartupIntroGate
import me.rerere.rikkahub.startup.StartupIntroPlan
import me.rerere.rikkahub.startup.StartupIntroPolicy
import me.rerere.rikkahub.startup.StartupMotion
import me.rerere.rikkahub.startup.StartupPreferences
import me.rerere.rikkahub.startup.animatorDurationScale
import me.rerere.rikkahub.ui.activity.SafeModeActivity
import me.rerere.rikkahub.ui.components.ui.TTSController
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalNavControllerOrNull
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.rememberCustomAsrState
import me.rerere.rikkahub.ui.hooks.rememberCustomTtsState
import me.rerere.rikkahub.ui.pages.assistant.AssistantPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantBasicPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantExtensionsPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantLocalToolPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantMcpPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantMemoryPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantPromptPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantRequestPage
import me.rerere.rikkahub.ui.pages.backup.BackupPage
import me.rerere.rikkahub.ui.pages.chat.ChatPage
import me.rerere.rikkahub.ui.pages.debug.DebugPage
import me.rerere.rikkahub.ui.pages.extensions.ExtensionsPage
import me.rerere.rikkahub.ui.pages.extensions.PromptPage
import me.rerere.rikkahub.ui.pages.extensions.QuickMessagesPage
import me.rerere.rikkahub.ui.pages.extensions.skills.SkillDetailPage
import me.rerere.rikkahub.ui.pages.memory.MemoryFilePage
import me.rerere.rikkahub.ui.pages.extensions.skills.SkillsPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceArtifactPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspacePage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceDetailPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceFileEditorPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceSshEditorPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalPage
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.rikkahub.ui.pages.favorite.FavoritePage
import me.rerere.rikkahub.ui.pages.history.HistoryPage
import me.rerere.rikkahub.ui.pages.imggen.ImageGenPage
import me.rerere.rikkahub.ui.pages.log.LogPage
import me.rerere.rikkahub.ui.pages.mediacreation.MediaCreationPage
import me.rerere.rikkahub.ui.pages.mediacreation.MediaCreationSessionsPage
import me.rerere.rikkahub.ui.pages.search.SearchPage
import me.rerere.rikkahub.ui.pages.setting.SettingAboutPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesThemePage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesNotificationPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesGeneralPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesNetworkPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesUIPage
import me.rerere.rikkahub.ui.pages.setting.SettingThemePage
import me.rerere.rikkahub.ui.pages.setting.SettingDonatePage
import me.rerere.rikkahub.ui.pages.setting.SettingFilesPage
import me.rerere.rikkahub.ui.pages.setting.SettingMcpDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingMcpPage
import me.rerere.rikkahub.ui.pages.setting.SettingModelPage
import me.rerere.rikkahub.ui.pages.setting.SettingPage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderPage
import me.rerere.rikkahub.ui.pages.setting.SettingSearchDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingSearchPage
import me.rerere.rikkahub.ui.pages.setting.SettingMediaPage
import me.rerere.rikkahub.ui.pages.setting.SettingMediaProviderDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingSpeechPage
import me.rerere.rikkahub.ui.pages.setting.SettingWebPage
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerPage
import me.rerere.rikkahub.ui.pages.share.handler.SharedInput
import me.rerere.rikkahub.ui.pages.stats.StatsPage
import me.rerere.rikkahub.ui.pages.translator.TranslatorPage
import me.rerere.rikkahub.ui.pages.webview.WebViewPage
import me.rerere.rikkahub.ui.startup.StartupIntroOverlay
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.openUsageAccessSettings
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"
private const val ACTION_TRANSLATE = "me.rerere.rikkahub.action.TRANSLATE"
private const val ACTION_MEDIA_CREATION = "me.rerere.rikkahub.action.MEDIA_CREATION"
private const val EXTRA_CONVERSATION_ID = "conversationId"
private const val EXTRA_ARTIFACT_WORKSPACE_ID = "artifactWorkspaceId"
private const val EXTRA_ARTIFACT_PATH = "artifactPath"
private const val EXTRA_ARTIFACT_FRAGMENT = "artifactFragment"

/** 最多保留的持久化 URI 读授权数量，超出的旧授权会被回收。 */
private const val MAX_PERSISTED_GRANTS = 8

/** 系统 Splash 图标退场：很短的一段，只做淡出和轻微放大。 */
private const val SPLASH_EXIT_DURATION_MS = 120L

/** 保险丝：无论如何都要在首帧之后把 Splash 摘掉，绝不能让启动图一直盖着应用。 */
private const val SPLASH_EXIT_TIMEOUT_MS = 400L
private const val SPLASH_EXIT_SCALE = 1.04f

/** Explicit in-app navigation used by overlay windows, not an externally exported path handler. */
fun workspaceArtifactIntent(context: Context, workspaceId: String, path: String, fragment: String? = null): Intent =
    Intent(context, RouteActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(EXTRA_ARTIFACT_WORKSPACE_ID, workspaceId)
        putExtra(EXTRA_ARTIFACT_PATH, path)
        putExtra(EXTRA_ARTIFACT_FRAGMENT, fragment)
    }

/** 打开指定会话的聊天页 */
fun conversationIntent(context: Context, conversationId: String): Intent =
    Intent(context, RouteActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(EXTRA_CONVERSATION_ID, conversationId)
    }

class RouteActivity : ComponentActivity() {
    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsStore>()
    private val mediaCreationRepository by inject<MediaCreationRepository>()
    private var navStack: MutableList<NavKey>? = null
    private val pendingIntents = ArrayDeque<Intent>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Splash 退场的兜底回调；onDestroy 时撤掉，避免持有已经关闭的窗口。 */
    private var splashWatchdog: Runnable? = null

    // Volume key listener registry — last registered handler wins
    internal val volumeKeyListeners = mutableListOf<(isVolumeUp: Boolean) -> Boolean>()

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isVolumeUp = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> true
                KeyEvent.KEYCODE_VOLUME_DOWN -> false
                else -> return super.dispatchKeyEvent(event)
            }
            if (volumeKeyListeners.lastOrNull()?.invoke(isVolumeUp) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        disableNavigationBarContrast()
        super.onCreate(savedInstanceState)
        if (CrashHandler.hasCrashed(this)) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        // 开场只在冷启动、且是从桌面普通启动时演一次。
        // 分享 / 通知 / 深链接 / 快捷方式进来时 introPlan.overlay 为 null，什么都不叠。
        val introPlan = resolveStartupIntroPlan(savedInstanceState)
        if (introPlan.splashExitAnimation) {
            applySplashExitAnimation()
        }
        setContent {
            RikkahubTheme {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .crossfade(true)
                        .components {
                            add(
                                OkHttpNetworkFetcherFactory(
                                    callFactory = { okHttpClient },
                                    cacheStrategy = { CacheControlCacheStrategy() },
                                )
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                add(AnimatedImageDecoder.Factory())
                            } else {
                                add(GifDecoder.Factory())
                            }
                            add(SvgDecoder.Factory(scaleToDensity = true))
                        }
                        .build()
                }
                AppRoutes(startupIntro = introPlan.overlay)
            }
        }
    }

    override fun onDestroy() {
        splashWatchdog?.let { mainHandler.removeCallbacks(it) }
        splashWatchdog = null
        super.onDestroy()
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    /**
     * 冷启动开场：先读用户选项和系统动画开关，再看这次启动是不是"从桌面点进来"。
     *
     * 三个硬性条件都在 [StartupIntroPolicy] 里（纯函数，有单测）：
     * 冷启动（不是配置重建）、普通入口（不是分享/通知/深链接）、本进程还没演过。
     * 前两个不满足就完全不挂前景，最后一个用 [StartupIntroGate] 保证只演一次。
     */
    private fun resolveStartupIntroPlan(savedInstanceState: Bundle?): StartupIntroPlan {
        val motion = StartupPreferences.readMotion(this)
        val reduceMotion = StartupIntroPolicy.isReducedMotion(this.animatorDurationScale())
        val plan = StartupIntroPolicy.planStartupIntro(
            motion = motion,
            reduceMotion = reduceMotion,
            coldStart = savedInstanceState == null,
            plainLaunch = StartupIntroPolicy.isPlainLauncherLaunch(
                action = intent?.action,
                hasDeepLinkExtras = hasLaunchExtras(intent),
            ),
            alreadyShownInProcess = false,
            splashExitSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        )
        if (plan.isEmpty) return StartupIntroPlan.NONE
        // 同一进程只演一次：多实例、Activity 重建都不重播。
        return if (StartupIntroGate.claim()) plan else StartupIntroPlan.NONE
    }

    /** 会直接落进某个具体页面/内容的启动，都算"不该被开场挡住的"启动。 */
    private fun hasLaunchExtras(intent: Intent?): Boolean {
        if (intent == null) return false
        return intent.getStringExtra(EXTRA_CONVERSATION_ID) != null ||
            intent.getStringExtra(MediaCreationForegroundService.EXTRA_MEDIA_SESSION_ID) != null ||
            intent.getStringExtra(EXTRA_ARTIFACT_WORKSPACE_ID) != null ||
            intent.getStringExtra(EXTRA_ARTIFACT_PATH) != null
    }

    /**
     * 让系统 Splash 自己退场（Android 12+），不额外叠一个 loading 页。
     *
     * 整段退场都走 API 31 的公开 SDK：Activity.getSplashScreen()（android.app.Activity）、
     * android.window.SplashScreen.setOnExitAnimationListener、SplashScreenView.remove()，
     * 都不是隐藏 API，也不需要 core-splashscreen 依赖。
     *
     * 整块 Splash 视图（背景 + 图标）淡出，底下的聊天页本来就已经画好了，看起来是交叉淡入，
     * 不会有"白底突然切成应用"的硬切。
     *
     * 注册了退出动画之后系统就不再自己移除 Splash，所以这里必须保证最终一定会 remove()：
     * 正常是动画结束回调，异常路径和超时用 [splashWatchdog] 兜底，onDestroy 时撤掉。
     * Android 12 以下没有这个 API，方法直接返回，只剩前景那段短动效。
     */
    private fun applySplashExitAnimation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        applySplashExitAnimationS()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applySplashExitAnimationS() {
        val splashScreen = runCatching { this.splashScreen }.getOrNull() ?: return
        splashScreen.setOnExitAnimationListener { provider ->
            val released = AtomicBoolean(false)
            val releaseOnce: () -> Unit = {
                if (released.compareAndSet(false, true)) {
                    runCatching { provider.remove() }
                    splashWatchdog?.let { mainHandler.removeCallbacks(it) }
                    splashWatchdog = null
                }
            }
            val watchdog = Runnable { releaseOnce() }
            splashWatchdog = watchdog
            mainHandler.postDelayed(watchdog, SPLASH_EXIT_TIMEOUT_MS)

            val splashView = runCatching { provider }.getOrNull()
            if (splashView == null) {
                releaseOnce()
            } else {
                runCatching {
                    splashView.animate()
                        .alpha(0f)
                        .scaleX(SPLASH_EXIT_SCALE)
                        .scaleY(SPLASH_EXIT_SCALE)
                        .setDuration(SPLASH_EXIT_DURATION_MS)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction { releaseOnce() }
                        .start()
                }.onFailure { releaseOnce() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val backStack = navStack ?: run {
            // Compose 尚未创建导航栈，待就绪后处理。
            pendingIntents.addLast(intent)
            return
        }
        val destination = when (intent.action) {
            ACTION_TRANSLATE -> Screen.Translator
            ACTION_MEDIA_CREATION -> {
                openNewMediaCreation(backStack)
                null
            }
            // 只处理外部进来的分享/打开。内部跳转（工件、会话、媒体会话）都不带 action，
            // 走下面的 internalDestination，所以 VIEW 分支不会抢到内部 intent。
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_PROCESS_TEXT, Intent.ACTION_VIEW -> {
                lifecycleScope.launch {
                    shareDestination(intent)?.let { destination ->
                        if (backStack.lastOrNull() != destination) backStack.add(destination)
                    }
                }
                null
            }
            else -> internalDestination(intent)
        }
        if (destination != null && backStack.lastOrNull() != destination) {
            backStack.add(destination)
        }
    }

    /** 内部分发：工件预览、媒体创作通知、会话通知。只在没有外部 share action 时才走到这里。 */
    private fun internalDestination(intent: Intent): Screen? = artifactDestination(intent)
        ?: mediaCreationDestination(intent)
        ?: intent.getStringExtra(EXTRA_CONVERSATION_ID)
            // 外部可以伪造这个 extra，非法 id 会让 ChatPage 的 Uuid.parse 抛异常，所以先校验。
            ?.let { id -> runCatching { Uuid.parse(id) }.getOrNull()?.let { Screen.Chat(it.toString()) } }

    /** Only explicit share content; URI readability is checked by actual import, not grant flags. */
    private suspend fun shareDestination(intent: Intent): Screen? {
        val candidates = SharedInput.readUris(intent, packageName)
        val readable = candidates
        val kept = readable.take(SharedInput.MAX_ATTACHMENTS)
        val text = SharedInput.readText(intent)
        if (text.isBlank() && kept.isEmpty()) {
            // 没有任何可用内容（例如伪造的空 intent）：留在原来的页面，提示一句。
            android.widget.Toast.makeText(this, R.string.r20_share_nothing_usable, android.widget.Toast.LENGTH_SHORT)
                .show()
            return null
        }
        persistReadGrants(intent, kept)
        val textRef = if (text.isNotEmpty()) {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    me.rerere.rikkahub.ui.pages.share.handler.SharedTextStore(
                        java.io.File(cacheDir, me.rerere.rikkahub.ui.pages.share.handler.SharedTextStore.DIRECTORY)
                    ).write(text)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                android.widget.Toast.makeText(this, e.message ?: "分享文字保存失败，请重新分享", android.widget.Toast.LENGTH_LONG).show()
                if (kept.isEmpty()) return null
                null
            }
        } else null
        return Screen.ShareHandler(
            text = "",
            textRef = textRef,
            streamUris = kept.map(Uri::toString),
            droppedAttachments = candidates.size - kept.size,
        )
    }

    /**
     * 分享页可能被重建（进程被杀后恢复导航栈），那时瞬时授权已经没了，所以显式请求持久化授权。
     * 仅尝试保存本次授权，不撤销其他功能已使用的持久授权。
     */
    private fun persistReadGrants(intent: Intent, uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return
        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        // Do not revoke existing SAF grants: they may belong to workspaces, backups or user attachments.

    }

    private fun artifactDestination(intent: Intent): Screen? {
        val id = intent.getStringExtra(EXTRA_ARTIFACT_WORKSPACE_ID) ?: return null
        if (runCatching { Uuid.parse(id) }.getOrNull() == null) return null
        val path = intent.getStringExtra(EXTRA_ARTIFACT_PATH) ?: return null
        val parsed = me.rerere.rikkahub.utils.WorkspaceArtifactLink.parse("sandbox:" + android.net.Uri.encode(path, "/"))
            as? me.rerere.rikkahub.utils.WorkspaceArtifactLink.WorkspaceFile ?: return null
        return Screen.WorkspaceArtifact(id, parsed.path, intent.getStringExtra(EXTRA_ARTIFACT_FRAGMENT))
    }

    // 快捷方式没有指定会话，打开一个空会话
    private fun openNewMediaCreation(backStack: MutableList<NavKey>) {
        lifecycleScope.launch {
            val destination = Screen.MediaCreation(mediaCreationRepository.newSession().id.toString())
            if (backStack.lastOrNull() != destination) backStack.add(destination)
        }
    }

    // 媒体创作的通知带着会话 ID，点击后直接打开那个会话
    private fun mediaCreationDestination(intent: Intent): Screen? {
        val sessionId = intent.getStringExtra(MediaCreationForegroundService.EXTRA_MEDIA_SESSION_ID) ?: return null
        return runCatching { Uuid.parse(sessionId) }.getOrNull()?.let { Screen.MediaCreation(it.toString()) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun AppRoutes(startupIntro: StartupMotion? = null) {
        val toastState = rememberToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val tts = rememberCustomTtsState()
        val asr = rememberCustomAsrState()
        val eventBus = koinInject<AppEventBus>()
        LaunchedEffect(tts) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.Speak -> tts.speak(event.text)
                    is AppEvent.OpenUsageAccessSettings -> this@RouteActivity.openUsageAccessSettings()
                    is AppEvent.ChatGenerationUpdate -> Unit // 由 ChatNotificationManager 消费
                    is AppEvent.ChatGenerationEnded -> Unit // 由 ChatNotificationManager 消费
                    is AppEvent.ChatCompressionStarted, is AppEvent.ChatCompressionEnded -> Unit
                    is AppEvent.ChatRecoveryStatus -> Unit // 由 ChatRecoveryNotificationManager 消费
                }
            }
        }
        val migrationState by DatabaseMigrationTracker.state.collectAsStateWithLifecycle()

        // 起始页只在这次组合里决定一次：否则每次重组都会重新取随机会话 id，
        // 交给 rememberNavBackStack 的 key 也就跟着变，导航栈可能被重置、共享偏好被反复读。
        val startScreen = remember {
            Screen.Chat(
                id = if (readBooleanPreference("create_new_conversation_on_start", true)) {
                    Uuid.random().toString()
                } else {
                    readStringPreference(
                        "lastConversationId",
                        Uuid.random().toString()
                    ) ?: Uuid.random().toString()
                }
            )
        }

        val backStack = rememberNavBackStack(startScreen)
        SideEffect {
            navStack = backStack
            while (pendingIntents.isNotEmpty()) {
                handleIntent(pendingIntents.removeFirst())
            }
        }

        me.rerere.rikkahub.ui.theme.AppAppearanceHost(settings) {
        SharedTransitionLayout {
            CompositionLocalProvider(
                LocalNavController provides Navigator(backStack),
                LocalNavControllerOrNull provides Navigator(backStack),
                LocalSharedTransitionScope provides this,
                LocalSettings provides settings,
                LocalToaster provides toastState,
                me.rerere.rikkahub.ui.context.LocalToasterOrNull provides toastState,
                LocalTTSState provides tts,
                LocalASRState provides asr,
            ) {
                TTSController()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true }
                ) {
                    NavDisplay(
                        backStack = backStack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        // 同一帧内连续两次返回时 NavDisplay 的返回状态还没更新，会多回调一次，不加判断会把栈弹空
                        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                        transitionSpec = {
                            if (backStack.size == 1) fadeIn() togetherWith fadeOut()
                            else {
                                slideInHorizontally { it } togetherWith
                                    slideOutHorizontally { -it / 2 } + scaleOut(targetScale = 0.7f) + fadeOut()
                            }
                        },
                        popTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        predictivePopTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        entryProvider = entryProvider {
                            entry<Screen.Chat>(
                                metadata = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() }
                                    + NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
                            ) { key ->
                                ChatPage(
                                    id = Uuid.parse(key.id),
                                    text = key.text,
                                    files = key.files.map { it.toUri() },
                                    nodeId = key.nodeId?.let { Uuid.parse(it) },
                                    textRef = key.textRef,
                                )
                            }

                            entry<Screen.ShareHandler> { key ->
                                ShareHandlerPage(
                                    text = key.text,
                                    images = (key.streamUris + listOfNotNull(key.streamUri)).distinct(),
                                    droppedAttachments = key.droppedAttachments,
                                    textRef = key.textRef,
                                )
                            }

                            entry<Screen.History> {
                                HistoryPage()
                            }

                            entry<Screen.Favorite> {
                                FavoritePage()
                            }

                            entry<Screen.Assistant> {
                                AssistantPage()
                            }

                            entry<Screen.AssistantDetail> { key ->
                                AssistantDetailPage(key.id)
                            }

                            entry<Screen.AssistantBasic> { key ->
                                AssistantBasicPage(key.id)
                            }

                            entry<Screen.AssistantPrompt> { key ->
                                AssistantPromptPage(key.id)
                            }

                            entry<Screen.AssistantMemory> { key ->
                                AssistantMemoryPage(key.id)
                            }

                            entry<Screen.MemoryFile> { key ->
                                MemoryFilePage(memoryId = key.memoryId, path = key.path)
                            }

                            entry<Screen.AssistantRequest> { key ->
                                AssistantRequestPage(key.id)
                            }

                            entry<Screen.AssistantMcp> { key ->
                                AssistantMcpPage(key.id)
                            }

                            entry<Screen.AssistantLocalTool> { key ->
                                AssistantLocalToolPage(key.id)
                            }

                            entry<Screen.AssistantInjections> { key ->
                                AssistantExtensionsPage(key.id)
                            }

                            entry<Screen.Translator> {
                                TranslatorPage()
                            }

                            entry<Screen.Setting> {
                                SettingPage()
                            }

                            entry<Screen.Backup> {
                                BackupPage()
                            }

                            entry<Screen.ImageGen> {
                                ImageGenPage()
                            }

                            entry<Screen.MediaCreationSessions> {
                                MediaCreationSessionsPage()
                            }

                            // 从会话列表展开（容器变换），页面本身只做淡入淡出，避免和平移打架
                            entry<Screen.MediaCreation>(
                                metadata = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() }
                                    + NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
                                    + NavDisplay.predictivePopTransitionSpec { fadeIn() togetherWith fadeOut() }
                            ) { key ->
                                MediaCreationPage(key.id)
                            }

                            entry<Screen.WebView> { key ->
                                WebViewPage(key.url, key.contentId)
                            }

                            entry<Screen.SettingTheme> {
                                SettingThemePage()
                            }

                            entry<Screen.SettingPreferences> {
                                SettingPreferencesPage()
                            }

                            entry<Screen.SettingPreferencesTheme> {
                                SettingPreferencesThemePage()
                            }

                            entry<Screen.SettingPreferencesNotification> {
                                SettingPreferencesNotificationPage()
                            }

                            entry<Screen.SettingPreferencesGeneral> {
                                SettingPreferencesGeneralPage()
                            }

                            entry<Screen.SettingPreferencesUI> {
                                SettingPreferencesUIPage()
                            }

                            entry<Screen.SettingPreferencesNetwork> {
                                SettingPreferencesNetworkPage()
                            }

                            entry<Screen.SettingProvider> {
                                SettingProviderPage()
                            }

                            entry<Screen.SettingProviderDetail> { key ->
                                val id = Uuid.parse(key.providerId)
                                SettingProviderDetailPage(id = id)
                            }

                            entry<Screen.SettingModels> {
                                SettingModelPage()
                            }

                            entry<Screen.SettingAbout> {
                                SettingAboutPage()
                            }

                            entry<Screen.SettingSearch> {
                                SettingSearchPage()
                            }

                            entry<Screen.SettingSearchDetail> { key ->
                                val id = Uuid.parse(key.serviceId)
                                SettingSearchDetailPage(id)
                            }

                            entry<Screen.SettingSpeech> {
                                SettingSpeechPage()
                            }

                            entry<Screen.SettingMedia> {
                                SettingMediaPage()
                            }

                            entry<Screen.SettingMediaProviderDetail> { key ->
                                SettingMediaProviderDetailPage(id = Uuid.parse(key.providerId))
                            }

                            entry<Screen.SettingMcp> {
                                SettingMcpPage()
                            }

                            entry<Screen.SettingFork> {
                                me.rerere.rikkahub.ui.pages.setting.SettingForkPage()
                            }

                            entry<Screen.SettingMcpDetail> { key ->
                                SettingMcpDetailPage(id = Uuid.parse(key.serverId))
                            }

                            entry<Screen.SettingDonate> {
                                SettingDonatePage()
                            }

                            entry<Screen.SettingFiles> {
                                SettingFilesPage()
                            }

                            entry<Screen.PermissionHub> {
                                me.rerere.rikkahub.ui.pages.setting.PermissionHubPage()
                            }
                            entry<Screen.ImageOrganizer> {
                                me.rerere.rikkahub.ui.pages.setting.ImageOrganizerPage()
                            }

                            entry<Screen.SettingWeb> {
                                SettingWebPage()
                            }

                            entry<Screen.Debug> {
                                DebugPage()
                            }

                            entry<Screen.Log> {
                                LogPage()
                            }

                            entry<Screen.Extensions> {
                                ExtensionsPage()
                            }

                            entry<Screen.QuickMessages> {
                                QuickMessagesPage()
                            }

                            entry<Screen.Prompts> {
                                PromptPage()
                            }

                            entry<Screen.Skills> {
                                SkillsPage()
                            }

                            entry<Screen.Workspaces> {
                                WorkspacePage()
                            }

                            entry<Screen.WorkspaceDetail> { key ->
                                WorkspaceDetailPage(key.id)
                            }

                            entry<Screen.WorkspaceTerminal> { key ->
                                WorkspaceTerminalPage(key.id)
                            }

                            entry<Screen.WorkspaceSshEditor> { key ->
                                WorkspaceSshEditorPage(key.id)
                            }

                            entry<Screen.WorkspaceFileEditor> { key ->
                                WorkspaceFileEditorPage(
                                    id = key.id,
                                    area = WorkspaceStorageArea.valueOf(key.area),
                                    path = key.path,
                                )
                            }

                            entry<Screen.WorkspaceArtifact> { key ->
                                WorkspaceArtifactPage(
                                    id = key.id,
                                    path = key.path,
                                    fragment = key.fragment,
                                    area = key.area,
                                )
                            }

                            entry<Screen.SkillDetail> { key ->
                                SkillDetailPage(skillName = key.skillName)
                            }

                            entry<Screen.MessageSearch> {
                                SearchPage()
                            }

                            entry<Screen.Stats> {
                                StatsPage()
                            }
                        }
                    )
                    // 冷启动开场：画在已经组合好的内容之上，不接手势、不加载任何东西，
                    // 一次有限动画播完就把自己移除。startupIntro 为 null 时这段完全不参与组合。
                    if (startupIntro != null) {
                        var introVisible by remember { mutableStateOf(true) }
                        if (introVisible) {
                            StartupIntroOverlay(
                                motion = startupIntro,
                                onFinished = { introVisible = false },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    if (BuildConfig.DEBUG) {
                        Text(
                            text = "[开发模式]",
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                    AnimatedVisibility(
                        visible = migrationState is MigrationState.Migrating,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val state = migrationState as? MigrationState.Migrating
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = stringResource(R.string.db_migrating),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (state != null) {
                                    Text(
                                        text = "v${state.from} → v${state.to}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Toaster(
                        state = toastState,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
        }
    }
}

sealed interface Screen : NavKey {
    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val nodeId: String? = null,
        val textRef: String? = null,
    ) : Screen

    @Serializable
    data class ShareHandler(
        val text: String,
        /** 旧字段，只为了兼容已经存在的导航状态，新代码用 streamUris。 */
        val streamUri: String? = null,
        val streamUris: List<String> = emptyList(),
        /** 没能带进来的附件数量：不可读或超过上限，页面会显示出来。 */
        val droppedAttachments: Int = 0,
        val textRef: String? = null,
    ) : Screen

    @Serializable
    data object History : Screen

    @Serializable
    data object Favorite : Screen

    @Serializable
    data object Assistant : Screen

    @Serializable
    data class AssistantDetail(val id: String) : Screen

    @Serializable
    data class AssistantBasic(val id: String) : Screen

    @Serializable
    data class AssistantPrompt(val id: String) : Screen

    @Serializable
    data class AssistantMemory(val id: String) : Screen

    /** 记忆库 [memoryId] 里的一个文件，[path] 为 null 时新建 */
    @Serializable
    data class MemoryFile(val memoryId: String, val path: String? = null) : Screen

    @Serializable
    data class AssistantRequest(val id: String) : Screen

    @Serializable
    data class AssistantMcp(val id: String) : Screen

    @Serializable
    data class AssistantLocalTool(val id: String) : Screen

    @Serializable
    data class AssistantInjections(val id: String) : Screen

    @Serializable
    data object Translator : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object Backup : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data object MediaCreationSessions : Screen

    @Serializable
    data class MediaCreation(val id: String) : Screen

    @Serializable
    data class WebView(val url: String = "", val contentId: String = "") : Screen

    @Serializable
    data object SettingTheme : Screen

    @Serializable
    data object SettingPreferences : Screen

    @Serializable
    data object SettingPreferencesTheme : Screen

    @Serializable
    data object SettingPreferencesNotification : Screen

    @Serializable
    data object SettingPreferencesGeneral : Screen

    @Serializable
    data object SettingPreferencesUI : Screen

    @Serializable
    data object SettingPreferencesNetwork : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String) : Screen

    @Serializable
    data object SettingModels : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingSearch : Screen

    @Serializable
    data class SettingSearchDetail(val serviceId: String) : Screen

    @Serializable
    data object SettingSpeech : Screen

    @Serializable
    data object SettingMedia : Screen

    @Serializable
    data class SettingMediaProviderDetail(val providerId: String) : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data object SettingFork : Screen

    @Serializable
    data class SettingMcpDetail(val serverId: String) : Screen

    @Serializable
    data object SettingDonate : Screen

    @Serializable
    data object SettingFiles : Screen

    @Serializable
    data object PermissionHub : Screen

    @Serializable
    data object ImageOrganizer : Screen

    @Serializable
    data object SettingWeb : Screen

    @Serializable
    data object Debug : Screen

    @Serializable
    data object Log : Screen

    @Serializable
    data object Extensions : Screen

    @Serializable
    data object QuickMessages : Screen

    @Serializable
    data object Prompts : Screen

    @Serializable
    data object Skills : Screen

    @Serializable
    data object Workspaces : Screen

    @Serializable
    data class WorkspaceDetail(val id: String) : Screen

    @Serializable
    data class WorkspaceTerminal(val id: String) : Screen

    /** [id] 为 null 时新建 */
    @Serializable
    data class WorkspaceSshEditor(val id: String? = null) : Screen

    @Serializable
    data class WorkspaceFileEditor(val id: String, val area: String, val path: String) : Screen

    /**
     * 从 Markdown 链接（sandbox:/workspace/...）打开的工作区产物预览。
     * [id] 是当前对话绑定的工作区，[path] 是解码后的环境内绝对路径。
     */
    @Serializable
    data class WorkspaceArtifact(
        val id: String,
        val path: String,
        val fragment: String? = null,
        val area: String? = null,
    ) : Screen

    @Serializable
    data class SkillDetail(val skillName: String) : Screen

    @Serializable
    data object MessageSearch : Screen

    @Serializable
    data object Stats : Screen
}

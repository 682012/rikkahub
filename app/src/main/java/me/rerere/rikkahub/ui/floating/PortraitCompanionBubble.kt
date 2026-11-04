package me.rerere.rikkahub.ui.floating

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.companion.CompanionAiPhase
import me.rerere.rikkahub.data.companion.CompanionAiState
import me.rerere.rikkahub.data.companion.CompanionImageResolution
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01

/**
 * 透明立绘气泡：整张 PNG 做轻呼吸、轻摆和点击回弹，点击时切到同角色的点击图。
 *
 * 不是 Live2D、没有骨骼也没有物理：只有 `graphicsLayer` 的平移/旋转/缩放，
 * 幅度压得很小，透明轮廓不会被窗口裁掉。
 *
 * 运动总闸在 [rememberCompanionMotionActive]：用户关掉动画、窗口收起、界面不在前台、
 * 或系统动画时长缩放为 0 时都不建无限循环；此时照样保留"点一下切 400ms 图 + 原回调 + 台词"。
 */
@Composable
fun PortraitCompanionBubble(
    state: FloatingChatUiState,
    companion: FloatingCompanionSettings,
    onClick: () -> Unit,
    onMinimizedChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    aiState: CompanionAiState = CompanionAiState(),
    onAiTrigger: (() -> Unit)? = null,
) {
    val settings = remember(companion) { companion.normalized() }
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val minimized = settings.minimized && onMinimizedChange != null
    val motion = rememberCompanionMotionActive(
        animationEnabled = settings.animationEnabled,
        minimized = minimized,
        lifecycleResumed = resumed,
    )
    val scope = rememberCoroutineScope()
    val latestClick by rememberUpdatedState(onClick)
    val latestAiTrigger by rememberUpdatedState(onAiTrigger)

    // ---- 画什么：当前角色的自定义覆盖优先，没有可用覆盖才用内置原字节 PNG ----
    val renderPreset = settings.effectivePreset
    val name = settings.displayName(portraitName(renderPreset))
    val builtInIdle = remember(renderPreset) { portraitAsset(renderPreset) }
    val customIdle = remember(context, settings.customImagePath) {
        FloatingCompanionFiles.resolve(context, settings.customImagePath)
    }
    val customTap = remember(context, settings.customTapImagePath) {
        FloatingCompanionFiles.resolve(context, settings.customTapImagePath)
    }
    // r14：设置页和真实浮窗共用一份来源决策；路径验证与解码失败回退仍沿用现有规则。
    val imagePlan = remember(settings, customIdle, customTap) {
        CompanionImageResolution.plan(settings, customIdle, customTap)
    }
    val idleUri = imagePlan.idle.model ?: builtInIdle
    val tapUri = imagePlan.tap.model

    var idleFailed by remember(idleUri) { mutableStateOf(false) }
    var tapFailed by remember(tapUri) { mutableStateOf(false) }
    var tapReady by remember(tapUri) { mutableStateOf(false) }
    val latestCharacter by rememberUpdatedState(settings.characterKey)
    val latestCanInteract by rememberUpdatedState(resumed && !minimized)
    var nextTapAt by remember(settings.characterKey) { mutableLongStateOf(0L) }
    var responding by remember { mutableStateOf(false) }
    var speech by remember { mutableStateOf<String?>(null) }
    var tapJob by remember { mutableStateOf<Job?>(null) }

    // ---- 说什么：角色自己的 8 句，用户填了就用用户的；轮换不连续同句 ----
    val defaultLines = listOf(
        stringResource(R.string.portrait_line_1),
        stringResource(R.string.portrait_line_2),
    )
    val builtInLines = remember(renderPreset) { portraitTapLines(renderPreset) }
    val roleLines = settings.tapLinesOr(builtInLines.ifEmpty { defaultLines })
    val rotation = remember(settings.characterKey, roleLines) { CompanionLineRotation() }

    // Leaving the foreground, collapsing, or switching character invalidates the old callback.
    LaunchedEffect(settings.characterKey, minimized, resumed) {
        tapJob?.cancel()
        tapJob = null
        responding = false
        speech = null
    }

    LaunchedEffect(speech, resumed) {
        if (speech == null || !resumed) return@LaunchedEffect
        delay(CompanionMotionSpec.SPEECH_MILLIS)
        speech = null
    }

    // ---- 运动：一轮内呼吸两次、轻摆一次；关掉时直接 snap 归零并且不建循环 ----
    val phase = remember { Animatable(0f) }
    val bounce = remember { Animatable(0f) }
    LaunchedEffect(motion, settings.characterKey) {
        phase.snapTo(0f)
        bounce.snapTo(0f)
        if (!motion) {
            phase.snapTo(0f)
            bounce.snapTo(0f)
            return@LaunchedEffect
        }
        phase.animateTo(
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(CompanionMotionSpec.CYCLE_MILLIS.toInt(), easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        )
    }

    fun tap() {
        // 正在进行的那次点击还没走完，不叠加第二次；也避免手势抖动连点。
        val now = System.nanoTime()
        if (!latestCanInteract || tapJob?.isActive == true || now < nextTapAt) return
        nextTapAt = now + 1_100_000_000L
        val tappedCharacter = settings.characterKey
        tapJob = scope.launch {
            responding = true
            // AI 回应开着时不读静态台词：说什么由真实模型决定，气泡只显示控制器写进去的状态。
            if (settings.aiEnabled && latestAiTrigger != null) {
                latestAiTrigger?.invoke()
            } else {
                speech = rotation.next(roleLines)
            }
            // 回弹独立跑，不拖慢 400ms 的切图回收和原回调。
            if (motion) launch {
                bounce.snapTo(0f)
                bounce.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.42f, stiffness = 900f, visibilityThreshold = 0.001f),
                )
                bounce.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f, visibilityThreshold = 0.001f),
                )
            }
            delay(CompanionMotionSpec.TAP_IMAGE_MILLIS)
            if (latestCharacter != tappedCharacter || !latestCanInteract) return@launch
            responding = false
            latestClick()
        }
    }

    val tapAction by rememberUpdatedState<() -> Unit>({ tap() })

    // ---- 实际画哪张：点击图 400ms，之后回待机图；坏图退回内置图，不显示空白 ----
    val effectiveIdle: String = if (idleFailed) builtInIdle else idleUri
    val effectiveTap: String? = if (tapFailed) null else tapUri
    val showTap = responding && effectiveTap != null && tapReady

    val statePose = companionStatePose(
        phase = aiState.phase,
        hasPendingTools = state.pendingTools.isNotEmpty(),
        failed = state.failed,
    )
    val width = if (minimized) 48.dp else settings.safeSizeDp.dp
    val height = if (minimized) 56.dp else (settings.safeSizeDp * 1.32f).dp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (!minimized) Box(
            Modifier
                .widthIn(max = 180.dp)
                .height(42.dp)
                .padding(horizontal = 2.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // 状态直接来自控制器：思考中/生成中的是真实进度，完成显示模型真的说的话，失败/停止也照实写
            val aiLine = when (aiState.phase) {
                CompanionAiPhase.THINKING -> "…"
                CompanionAiPhase.GENERATING -> aiState.text.takeLast(48)
                CompanionAiPhase.COMPLETED -> aiState.text
                CompanionAiPhase.FAILED -> context.getString(R.string.companion_bubble_failed)
                CompanionAiPhase.CANCELLED -> context.getString(R.string.companion_bubble_stopped)
                CompanionAiPhase.IDLE -> ""
            }
            // AI 开着但这一句其实没生成出来（没发出去/模型不能用）时照实说一句，
            // 不拿静态台词冒充"模型刚说的"；AI 关着时下面照旧走固定台词
            val aiHint = if (settings.aiEnabled && latestAiTrigger != null && aiState.phase == CompanionAiPhase.IDLE) {
                aiState.model.failure?.let { companionFailureLabel(it) }
                    ?: aiState.skipped?.let {
                        context.getString(R.string.companion_status_skipped, companionSkipLabel(it.reason))
                    }
            } else null
            val line = aiLine.ifEmpty {
                aiHint ?: speech ?: when {
                    state.pendingTools.isNotEmpty() -> stringResource(R.string.portrait_approval)
                    state.failed -> stringResource(R.string.portrait_error)
                    else -> ""
                }
            }
            if (line.isNotEmpty()) Text(
                line,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        val description = stringResource(R.string.floating_companion_bubble_desc)
        Box(
            Modifier
                .size(width, height)
                .testTag(if (minimized) "portrait-edge-handle" else "portrait-character")
                .semantics {
                    role = Role.Button
                    contentDescription = if (minimized) {
                        "$name ${context.getString(R.string.portrait_expand)}"
                    } else {
                        description
                    }
                    onClick(label = description) {
                        if (minimized) onMinimizedChange?.invoke(false) else tap();true
                    }
                }
                .pointerInput(settings.characterKey, minimized, settings.safeSizeDp) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var drift = 0f
                        var consumed = false
                        var released = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            drift = maxOf(drift, (change.position - down.position).getDistance())
                            consumed = consumed || change.isConsumed
                            if (!change.pressed) {
                                released = true;break
                            }
                        }
                        if (released && !consumed && drift <= viewConfiguration.touchSlop) {
                            if (minimized) onMinimizedChange?.invoke(false) else tapAction()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val imageModifier = Modifier
                .fillMaxSize()
                .padding(if (minimized) 0.dp else (settings.safeSizeDp * 0.05f).dp)
                .graphicsLayer {
                    if (motion) {
                        // Read frame values in the draw layer: no full UI recomposition per frame.
                        val pose = companionPose(
                            breath = companionWave(phase.value, 2),
                            sway = companionWave(phase.value, 1),
                            bounce = bounce.value.coerceIn(0f, 1f),
                            state = statePose,
                        )
                        translationY = -pose.liftDp.dp.toPx()
                        translationX = pose.shiftDp.dp.toPx()
                        rotationZ = pose.tiltDegrees
                        scaleX = pose.scaleX
                        scaleY = pose.scaleY
                    }
                }
            // Both PNGs are decoded before the first tap; alpha switching never inserts an empty frame.
            CompanionPortraitLayer(
                model = effectiveIdle, fallback = builtInIdle, freeze = !motion,
                visible = !showTap, modifier = imageModifier,
                onError = { idleFailed = true },
            )
            if (effectiveTap != null) CompanionPortraitLayer(
                model = effectiveTap, fallback = builtInIdle, freeze = !motion || !responding,
                visible = showTap, modifier = imageModifier,
                onReady = { tapReady = true }, onError = { tapFailed = true },
            )
            if (minimized) Icon(
                HugeIcons.ArrowLeft01,
                null,
                modifier = Modifier.align(Alignment.BottomEnd).size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (!minimized && onMinimizedChange != null) FilledTonalIconButton(
            onClick = { onMinimizedChange(true) },
            modifier = Modifier
                .size(48.dp)
                .testTag("portrait-minimize"),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) { Icon(HugeIcons.ArrowRight01, stringResource(R.string.portrait_minimize)) }
    }
}

/** A frozen custom image never briefly starts Coil's animated decoder while its first frame loads. */
@Composable
private fun CompanionPortraitLayer(
    model: String,
    fallback: String,
    freeze: Boolean,
    visible: Boolean,
    modifier: Modifier,
    onReady: () -> Unit = {},
    onError: () -> Unit,
) {
    val context = LocalContext.current
    val freezeFile = freeze && !model.startsWith("file:///android_asset/")
    val frame = rememberCompanionStaticFrame(if (freezeFile) model else null)
    val latestReady by rememberUpdatedState(onReady)
    LaunchedEffect(frame) { if (frame != null) latestReady() }
    val layer = modifier.graphicsLayer { alpha = if (visible) 1f else 0f }
    if (frame != null) {
        Image(bitmap = frame, contentDescription = null, contentScale = ContentScale.Fit, modifier = layer)
    } else {
        val safeModel = if (freezeFile) fallback else model
        val request = remember(context, safeModel) {
            ImageRequest.Builder(context).data(safeModel).crossfade(false).build()
        }
        AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = layer, onError = { onError() },
            onSuccess = { if (safeModel == model) latestReady() })
    }
}

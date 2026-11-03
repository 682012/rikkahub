package me.rerere.rikkahub.ui.floating

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.data.datastore.FloatingCompanionLimits
import me.rerere.rikkahub.data.datastore.FloatingCompanionPaths
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings

// ---------------------------------------------------------------------------
// 私有目录里的图片：只认 filesDir/floating_companions，不碰网络和别人的 provider
// ---------------------------------------------------------------------------

/**
 * 自定义角色的图片文件。
 *
 * 和外观设置里的壁纸一样：相册给的 URI 是临时的，必须拷进应用私有目录再持久化路径。
 * 区别是这里上限 16MB，而且只允许本应用私有目录——悬浮窗可能在后台出现，
 * 不能因为设置里写了 http 地址就去下载一张大小未知的图。
 */
object FloatingCompanionFiles {
    fun directory(context: Context): File =
        File(context.filesDir, FloatingCompanionLimits.DIRECTORY).apply { if (!exists()) mkdirs() }

    /** 校验路径并把可加载的 `file://` 地址还给 UI；不是本目录/文件不存在时返回 null。 */
    fun resolve(context: Context, path: String?): String? =
        FloatingCompanionPaths.resolve(path, directory(context))?.toUri()?.toString()

    /** 备份/迁移用：设置里记录且真实存在的私有文件。 */
    fun ownedFiles(context: Context, settings: FloatingCompanionSettings): List<File> =
        FloatingCompanionPaths.ownedFiles(settings, directory(context))

    /**
     * 把相册/文件选择器给的 URI 拷进私有目录，返回 `file://` 地址。
     * 超过 [FloatingCompanionLimits.MAX_IMAGE_BYTES] 直接报错，不会把进程撑爆。
     */
    fun copyFromUri(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri)
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.orEmpty())
            ?: uri.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            ?: "png"
        val target = File(directory(context), "companion-${java.util.UUID.randomUUID()}.$extension")
        try {
            val input = resolver.openInputStream(uri) ?: error("cannot open $uri")
            input.use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > FloatingCompanionLimits.MAX_IMAGE_BYTES) {
                            error("image larger than 16MB")
                        }
                        sink.write(buffer, 0, read)
                    }
                }
            }
            if (target.length() == 0L) error("empty image")
                // Bounds are decoded without allocating full pixels; malformed inputs never become active settings.
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(target.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "unsupported or invalid image" }
                require(bounds.outWidth.toLong() * bounds.outHeight <= 80_000_000L) { "image dimensions too large" }
                java.io.RandomAccessFile(target, "rw").use { it.fd.sync() }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        return target.toUri().toString()
    }

    /** 只删自己目录里的旧文件；别的路径一律不碰。 */
    fun release(context: Context, path: String?) {
        val file = FloatingCompanionPaths.resolve(path, directory(context)) ?: return
        runCatching { file.delete() }
    }
}

private const val COPY_BUFFER = 64 * 1024

// ---------------------------------------------------------------------------
// 悬浮角色本体
// ---------------------------------------------------------------------------

/**
 * 收起时的角色气泡。
 *
 * 点击的真实反馈：先 spring 抬手 + 粒子/爱心 + 一句短台词，[FloatingCompanionLimits.TAP_DELAY_MILLIS]
 * 之后再交给 [onClick] 打开原来的审批/进度面板，手感和跳转不会撞在一起。
 * 手指位移超过 touchSlop（或事件被拖动消费）就不算点击，避免拖窗口时误开面板。
 *
 * 动画只在 Lifecycle.RESUMED 且设置里开着动效时跑；窗口隐藏后帧就停了。
 * 自定义图片走 Coil 的 AsyncImage，按气泡尺寸解码（不会把原图整张常驻内存），
 * 解码失败退回内置角色，不留空白。
 */
@Composable
fun FloatingCompanionBubble(
    state: FloatingChatUiState,
    companion: FloatingCompanionSettings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = remember(companion) { companion.normalized() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = settings.animationEnabled && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    val size = settings.safeSizeDp.dp
    val colors = MaterialTheme.colorScheme
    val mood = remember(state) { companionMood(state) }

    // 呼吸/摆动：0..1 往复；眨眼：1 睁 0 闭；press：抬手弹跳；burst：粒子进度
    val breath = remember { Animatable(0f) }
    val sway = remember { Animatable(0f) }
    val eyeOpen = remember { Animatable(1f) }
    val press = remember { Animatable(1f) }
    val bursts = remember { mutableStateListOf<Long>() }
    var speech by remember { mutableStateOf<String?>(null) }
    val nextTapNanos = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    LaunchedEffect(animate) {
        if (!animate) {
            breath.snapTo(0f)
            sway.snapTo(0f)
            eyeOpen.snapTo(1f)
            press.snapTo(1f)
            bursts.clear()
            return@LaunchedEffect
        }
        launch {
            breath.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(2200, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
            )
        }
        launch {
            sway.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(3400, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
            )
        }
        // 眨眼节奏带随机间隔，看着不像机器
        while (true) {
            delay(2400L + Random.nextLong(0, 2600))
            eyeOpen.animateTo(0f, tween(80))
            eyeOpen.animateTo(1f, tween(130))
        }
    }

    // 台词显示一会儿就消失，不跟着窗口一起变高
    LaunchedEffect(speech) {
        if (speech == null) return@LaunchedEffect
        delay(1500)
        speech = null
    }

    val idleUri = remember(context, settings.customImagePath) {
        FloatingCompanionFiles.resolve(context, settings.customImagePath)
    }
    val tapUri = remember(context, settings.customTapImagePath) {
        FloatingCompanionFiles.resolve(context, settings.customTapImagePath)
    }
    var customBroken by remember(idleUri) { mutableStateOf(false) }
    var showTapImage by remember { mutableStateOf(false) }

    val defaultLines = stringArrayResourceFor(settings.preset)
    val fallbackName = presetName(settings.preset)
    val description = stringResource(R.string.floating_companion_bubble_desc)
    val currentOnClick by rememberUpdatedState(onClick)

    fun tap() {
        val now = System.nanoTime()
        val previous = nextTapNanos.get()
        if (now < previous || !nextTapNanos.compareAndSet(previous, now +
                (FloatingCompanionLimits.TAP_DELAY_MILLIS + FloatingCompanionLimits.TAP_FEEDBACK_MILLIS) * 1_000_000L)) return
        // 立刻反馈：抬手弹跳 + 爱心粒子 + 一句台词
        if (animate) {
            scope.launch {
                press.snapTo(0.9f)
                press.animateTo(1.16f, spring(dampingRatio = 0.42f, stiffness = 900f))
                press.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f))
            }
            bursts.add(System.nanoTime())
        }
        showTapImage = tapUri != null
        speech = settings.tapLinesOr(defaultLines).ifEmpty { listOf(fallbackName) }.let { lines ->
            lines[Random.nextInt(lines.size)]
        }
        scope.launch {
            delay(FloatingCompanionLimits.TAP_DELAY_MILLIS)
            showTapImage = false
            currentOnClick()
            // Timestamp gating remains correct while hidden, without a re-arm coroutine.
        }
    }

    Box(modifier = modifier.padding(4.dp)) {
        val gestureTap by rememberUpdatedState<() -> Unit>({ tap() })
        Box(
            modifier = Modifier
                .size(size)
                .semantics {
                    role = Role.Button
                    contentDescription = description
                    onClick(label = description) {
                        tap()
                        true
                    }
                }
                .pointerInput(settings.safeSizeDp) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var travelled = 0f
                        var lifted = false
                        var consumed = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            travelled = max(travelled, (change.position - down.position).getDistance())
                            consumed = consumed || change.isConsumed
                            if (!change.pressed) {
                                lifted = true
                                consumed = consumed || change.isConsumed
                                break
                            }
                        }
                        // 拖动（位移超阈值或被父层消费）不算点击
                        val slop = viewConfiguration.touchSlop * FloatingCompanionLimits.DRAG_SLOP_FACTOR
                        if (lifted && !consumed && travelled <= slop) gestureTap()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                settings.effectivePreset == FloatingCompanionPreset.CUSTOM && idleUri != null && !customBroken -> {
                    val shown = if (showTapImage && tapUri != null) tapUri else idleUri
                    CustomCompanionImage(
                        model = shown,
                        contentDescription = description,
                        onError = { customBroken = true },
                    )
                }
                // 自定义解码失败或没图时画内置角色，不留空白
                else -> CanvasCompanion(
                    preset = settings.effectivePreset.takeIf { it != FloatingCompanionPreset.CUSTOM }
                        ?: FloatingCompanionPreset.CAT,
                    mood = mood,
                    breath = breath.value,
                    sway = sway.value,
                    eyeOpen = eyeOpen.value,
                    press = press.value,
                    animate = animate,
                )
            }

            // 状态徽标：和管理面板里的小角标一致，表情之外再给一个明确的信号
            when {
                state.failed -> CompanionBadge(HugeIcons.Alert01, colors.error, colors.onError)
                state.pendingTools.isNotEmpty() -> CompanionBadge(HugeIcons.Wrench01, colors.error, colors.onError)
                state.hasReply && !state.generating -> CompanionBadge(HugeIcons.Tick01, colors.primary, colors.onPrimary)
            }

            // 粒子/爱心：画在角色上面，随点击一次性爆开
            bursts.forEach { id ->
                TapBurst(
                    seed = id,
                    onFinished = { bursts.remove(id) },
                )
            }

            speech?.let { line ->
                Text(
                    text = line,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.CompanionBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .size(18.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = contentColor,
        )
    }
}

@Composable
private fun CustomCompanionImage(
    model: String,
    contentDescription: String,
    onError: () -> Unit,
) {
    val shape = remember { RoundedCornerShape(percent = 26) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f)),
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            // 按气泡大小解码，不把原图整张放进内存；GIF/Animated WebP 由项目的 Coil 解码器驱动
            contentScale = ContentScale.Fit,
            onError = { onError() },
        )
    }
}

/** 一次点击的粒子：几颗爱心 + 小圆点，用同一个随机种子保证位置稳定。 */
@Composable
private fun TapBurst(seed: Long, onFinished: () -> Unit) {
    val progress = remember(seed) { Animatable(0f) }
    LaunchedEffect(seed) {
        progress.animateTo(1f, tween(FloatingCompanionLimits.TAP_FEEDBACK_MILLIS.toInt(), easing = FastOutSlowInEasing))
        onFinished()
    }
    val random = remember(seed) { Random(seed) }
    val particles = remember(seed) {
        List(PARTICLE_COUNT) { index ->
            Particle(
                angleDegrees = -90f + (index - (PARTICLE_COUNT - 1) / 2f) * 26f + random.nextFloat() * 10f - 5f,
                distance = 0.34f + random.nextFloat() * 0.34f,
                heart = index % 2 == 0,
                colorIndex = index % 3,
                sizeFactor = 0.7f + random.nextFloat() * 0.5f,
            )
        }
    }
    val heartColor = MaterialTheme.colorScheme.error
    val sparkColor = MaterialTheme.colorScheme.primary
    val accentColor = MaterialTheme.colorScheme.tertiary

    DrawScopeCanvas {
        val t = progress.value
        val fade = 1f - t
        particles.forEach { particle ->
            val radians = particle.angleDegrees * PI.toFloat() / 180f
            val radius = size.minDimension * particle.distance * t
            val center = Offset(
                x = size.width / 2f + cos(radians) * radius,
                y = size.height * 0.46f + sin(radians) * radius,
            )
            val color = when (particle.colorIndex) {
                0 -> heartColor
                1 -> sparkColor
                else -> accentColor
            }.copy(alpha = fade.coerceIn(0f, 1f))
            val radiusPx = size.minDimension * 0.055f * particle.sizeFactor * (0.6f + 0.4f * fade)
            if (particle.heart) {
                drawHeart(center, radiusPx, color)
            } else {
                drawCircle(color = color, radius = radiusPx * 0.7f, center = center)
            }
        }
    }
}

private const val PARTICLE_COUNT = 6

private data class Particle(
    val angleDegrees: Float,
    val distance: Float,
    val heart: Boolean,
    val colorIndex: Int,
    val sizeFactor: Float,
)

/** 没有 layout 尺寸的小画布：铺满父容器的绘制层。 */
@Composable
private fun DrawScopeCanvas(content: DrawScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize().drawWithCache { onDrawBehind { content() } })
}

// ---------------------------------------------------------------------------
// 内置角色（原创绘制，不依赖任何外部素材）
// ---------------------------------------------------------------------------

/** 状态 → 表情。generating/awaitingTools/success/error 各有各的样子。 */
internal enum class CompanionMood { IDLE, WORKING, ASKING, HAPPY, SORRY }

internal fun companionMood(state: FloatingChatUiState): CompanionMood = when {
    state.failed -> CompanionMood.SORRY
    state.pendingTools.isNotEmpty() -> CompanionMood.ASKING
    state.generating -> CompanionMood.WORKING
    state.hasReply -> CompanionMood.HAPPY
    else -> CompanionMood.IDLE
}

@Composable
private fun CanvasCompanion(
    preset: FloatingCompanionPreset,
    mood: CompanionMood,
    breath: Float,
    sway: Float,
    eyeOpen: Float,
    press: Float,
    animate: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val look = remember(colors, preset) {
        when (preset) {
            // 橘猫：暖橘身体、奶油肚皮、粉色内耳
            FloatingCompanionPreset.DRAGON -> CompanionLook(
                body = Color(0xFF63B98C),
                bodyDark = Color(0xFF3E8E68),
                belly = Color(0xFFF3E7C8),
                inner = Color(0xFFF0A8A0),
                eye = Color(0xFF1F2A26),
                accent = Color(0xFFE8C766),
            )
            else -> CompanionLook(
                body = Color(0xFFF0A85E),
                bodyDark = Color(0xFFD2823F),
                belly = Color(0xFFFFF1DE),
                inner = Color(0xFFF3A7AE),
                eye = Color(0xFF2B2118),
                accent = Color(0xFFFFFFFF),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                val bodyScale = if (animate) 1f + breath * 0.03f else 1f
                onDrawBehind {
                    // 呼吸只改纵向比例，抬手是整体放大，都绕底部中心
                    val bounce = if (animate) press else 1f
                    val pivot = Offset(size.width / 2f, size.height * 0.94f)
                    scale(bounce, bounce * bodyScale, pivot) {
                            drawCompanion(
                                preset = if (preset == FloatingCompanionPreset.DRAGON) preset else FloatingCompanionPreset.CAT,
                                mood = mood,
                                look = look,
                                sway = if (animate) sway else 0.5f,
                                eyeOpen = if (animate) eyeOpen else 1f,
                            )
                    }
                }
            },
    )
}

private data class CompanionLook(
    val body: Color,
    val bodyDark: Color,
    val belly: Color,
    val inner: Color,
    val eye: Color,
    val accent: Color,
)

/** 全部用比例坐标画，任何尺寸下比例都对。 */
private fun DrawScope.drawCompanion(
    preset: FloatingCompanionPreset,
    mood: CompanionMood,
    look: CompanionLook,
    sway: Float,
    eyeOpen: Float,
) {
    when (preset) {
        FloatingCompanionPreset.DRAGON -> drawDragon(mood, look, sway, eyeOpen)
        else -> drawCat(mood, look, sway, eyeOpen)
    }
}

private fun DrawScope.drawCat(
    mood: CompanionMood,
    look: CompanionLook,
    sway: Float,
    eyeOpen: Float,
) {
    val w = size.width
    val h = size.height
    fun x(v: Float) = w * v
    fun y(v: Float) = h * v

    // 地上的影子
    drawOval(
        color = Color.Black.copy(alpha = 0.12f),
        topLeft = Offset(x(0.26f), y(0.87f)),
        size = Size(x(0.48f), y(0.07f)),
    )

    // 尾巴：从身体右下甩出去，sway 让尾巴尖来回摆
    val tailLift = (sway - 0.5f) * 2f
    val tail = Path().apply {
        moveTo(x(0.68f), y(0.76f))
        cubicTo(
            x(0.90f), y(0.78f),
            x(0.94f), y(0.52f + tailLift * 0.10f),
            x(0.82f), y(0.44f + tailLift * 0.08f),
        )
    }
    drawPath(tail, look.bodyDark, style = Stroke(width = x(0.075f), cap = StrokeCap.Round))

    // 身体
    drawOval(
        color = look.body,
        topLeft = Offset(x(0.30f), y(0.58f)),
        size = Size(x(0.40f), y(0.32f)),
    )
    drawOval(
        color = look.belly,
        topLeft = Offset(x(0.41f), y(0.68f)),
        size = Size(x(0.18f), y(0.20f)),
    )

    // 耳朵跟着 sway 轻抖
    val earTilt = (sway - 0.5f) * 12f
    drawCatEar(Offset(x(0.335f), y(0.30f)), x(0.105f), earTilt - 6f, look)
    drawCatEar(Offset(x(0.665f), y(0.30f)), x(0.105f), -earTilt + 6f, look)

    // 头
    drawCircle(color = look.body, radius = x(0.245f), center = Offset(x(0.5f), y(0.40f)))
    drawOval(
        color = look.belly.copy(alpha = 0.55f),
        topLeft = Offset(x(0.42f), y(0.45f)),
        size = Size(x(0.16f), y(0.10f)),
    )

    // 额头花纹
    listOf(0.40f, 0.50f, 0.60f).forEachIndexed { index, fx ->
        drawLine(
            color = look.bodyDark,
            start = Offset(x(fx), y(0.215f + index % 2 * 0.012f)),
            end = Offset(x(fx), y(0.275f)),
            strokeWidth = x(0.018f),
            cap = StrokeCap.Round,
        )
    }

    drawFace(
        mood = mood,
        look = look,
        eyeOpen = eyeOpen,
        eyeLeft = Offset(x(0.415f), y(0.385f)),
        eyeRight = Offset(x(0.585f), y(0.385f)),
        eyeRadiusX = x(0.055f),
        eyeRadiusY = y(0.058f),
        noseY = y(0.455f),
        mouthY = y(0.505f),
    )

    // 胡须
    listOf(-1f, 0f, 1f).forEach { row ->
        drawLine(
            color = look.bodyDark.copy(alpha = 0.85f),
            start = Offset(x(0.345f), y(0.455f + row * 0.028f)),
            end = Offset(x(0.205f), y(0.435f + row * 0.05f)),
            strokeWidth = x(0.012f),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = look.bodyDark.copy(alpha = 0.85f),
            start = Offset(x(0.655f), y(0.455f + row * 0.028f)),
            end = Offset(x(0.795f), y(0.435f + row * 0.05f)),
            strokeWidth = x(0.012f),
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawCatEar(center: Offset, radius: Float, tilt: Float, look: CompanionLook) {
    rotate(tilt, pivot = center) {
        val outer = Path().apply {
            moveTo(center.x - radius, center.y + radius * 0.75f)
            lineTo(center.x, center.y - radius)
            lineTo(center.x + radius, center.y + radius * 0.75f)
            close()
        }
        drawPath(outer, look.body)
        val inner = Path().apply {
            moveTo(center.x - radius * 0.5f, center.y + radius * 0.5f)
            lineTo(center.x, center.y - radius * 0.45f)
            lineTo(center.x + radius * 0.5f, center.y + radius * 0.5f)
            close()
        }
        drawPath(inner, look.inner)
    }
}

private fun DrawScope.drawDragon(
    mood: CompanionMood,
    look: CompanionLook,
    sway: Float,
    eyeOpen: Float,
) {
    val w = size.width
    val h = size.height
    fun x(v: Float) = w * v
    fun y(v: Float) = h * v

    val lift = (sway - 0.5f) * 2f

    drawOval(
        color = Color.Black.copy(alpha = 0.12f),
        topLeft = Offset(x(0.24f), y(0.87f)),
        size = Size(x(0.52f), y(0.07f)),
    )

    // 尾巴带一排背刺
    val tail = Path().apply {
        moveTo(x(0.66f), y(0.80f))
        cubicTo(
            x(0.88f), y(0.86f),
            x(0.94f), y(0.60f + lift * 0.06f),
            x(0.86f), y(0.50f + lift * 0.08f),
        )
    }
    drawPath(tail, look.bodyDark, style = Stroke(width = x(0.07f), cap = StrokeCap.Round))
    rotate(lift * 10f, pivot = Offset(x(0.86f), y(0.50f))) {
        val spike = Path().apply {
            moveTo(x(0.86f), y(0.50f))
            lineTo(x(0.92f), y(0.40f))
            lineTo(x(0.83f), y(0.44f))
            close()
        }
        drawPath(spike, look.accent)
    }

    // 翅膀（在身体后面）
    rotate(-8f + lift * 6f, pivot = Offset(x(0.30f), y(0.52f))) {
        val wing = Path().apply {
            moveTo(x(0.34f), y(0.52f))
            cubicTo(x(0.12f), y(0.44f), x(0.06f), y(0.62f), x(0.10f), y(0.70f))
            cubicTo(x(0.18f), y(0.66f), x(0.26f), y(0.70f), x(0.34f), y(0.62f))
            close()
        }
        drawPath(wing, look.bodyDark)
        drawPath(wing, look.accent.copy(alpha = 0.45f), style = Stroke(width = x(0.012f)))
    }

    // 身体
    drawOval(
        color = look.body,
        topLeft = Offset(x(0.31f), y(0.56f)),
        size = Size(x(0.38f), y(0.34f)),
    )
    drawOval(
        color = look.belly,
        topLeft = Offset(x(0.41f), y(0.66f)),
        size = Size(x(0.18f), y(0.22f)),
    )

    // 角
    rotate(-14f, pivot = Offset(x(0.40f), y(0.28f))) {
        val horn = Path().apply {
            moveTo(x(0.40f), y(0.30f))
            lineTo(x(0.36f), y(0.14f))
            lineTo(x(0.44f), y(0.24f))
            close()
        }
        drawPath(horn, look.accent)
    }
    rotate(14f, pivot = Offset(x(0.60f), y(0.28f))) {
        val horn = Path().apply {
            moveTo(x(0.60f), y(0.30f))
            lineTo(x(0.64f), y(0.14f))
            lineTo(x(0.56f), y(0.24f))
            close()
        }
        drawPath(horn, look.accent)
    }

    // 头 + 吻部
    drawCircle(color = look.body, radius = x(0.24f), center = Offset(x(0.5f), y(0.40f)))
    drawOval(
        color = look.bodyDark.copy(alpha = 0.35f),
        topLeft = Offset(x(0.40f), y(0.43f)),
        size = Size(x(0.20f), y(0.13f)),
    )
    // 鼻孔
    drawCircle(look.eye.copy(alpha = 0.7f), x(0.016f), Offset(x(0.46f), y(0.47f)))
    drawCircle(look.eye.copy(alpha = 0.7f), x(0.016f), Offset(x(0.54f), y(0.47f)))

    // 头刺
    listOf(0.44f, 0.5f, 0.56f).forEach { fx ->
        val spike = Path().apply {
            moveTo(x(fx - 0.03f), y(0.19f))
            lineTo(x(fx), y(0.10f))
            lineTo(x(fx + 0.03f), y(0.19f))
            close()
        }
        drawPath(spike, look.accent)
    }

    // 龙的眼睛是竖瞳
    drawFace(
        mood = mood,
        look = look,
        eyeOpen = eyeOpen,
        eyeLeft = Offset(x(0.425f), y(0.375f)),
        eyeRight = Offset(x(0.575f), y(0.375f)),
        eyeRadiusX = x(0.052f),
        eyeRadiusY = y(0.056f),
        noseY = y(0.50f),
        mouthY = y(0.535f),
        slitPupil = true,
    )
}

/**
 * 表情：眼睛形状和嘴型随状态变。
 * 不是贴一张背景图——身体、内耳/肚皮、眼睛、角都是分开的颜色，状态只改表情。
 */
private fun DrawScope.drawFace(
    mood: CompanionMood,
    look: CompanionLook,
    eyeOpen: Float,
    eyeLeft: Offset,
    eyeRight: Offset,
    eyeRadiusX: Float,
    eyeRadiusY: Float,
    noseY: Float,
    mouthY: Float,
    slitPupil: Boolean = false,
) {
    val blinking = eyeOpen < 0.55f
    val happy = mood == CompanionMood.HAPPY
    val sorry = mood == CompanionMood.SORRY
    val asking = mood == CompanionMood.ASKING
    val working = mood == CompanionMood.WORKING

    // 肚子饿/开心时眯成 ^ ^，撒娇时睁大
    val openness = when {
        blinking -> 0.1f
        happy -> 0.45f
        asking -> 1.15f
        working -> 0.8f
        sorry -> 0.7f
        else -> 1f
    }

    listOf(eyeLeft, eyeRight).forEach { center ->
        if (happy && !blinking) {
            // 弯弯的笑眼
            val path = Path().apply {
                moveTo(center.x - eyeRadiusX, center.y + eyeRadiusY * 0.25f)
                quadraticTo(center.x, center.y - eyeRadiusY * 0.95f, center.x + eyeRadiusX, center.y + eyeRadiusY * 0.25f)
            }
            drawPath(path, look.eye, style = Stroke(width = eyeRadiusY * 0.45f, cap = StrokeCap.Round))
        } else if (sorry) {
            // X 眼
            val r = eyeRadiusX * 0.85f
            drawLine(look.eye, Offset(center.x - r, center.y - r), Offset(center.x + r, center.y + r), strokeWidth = eyeRadiusY * 0.34f, cap = StrokeCap.Round)
            drawLine(look.eye, Offset(center.x - r, center.y + r), Offset(center.x + r, center.y - r), strokeWidth = eyeRadiusY * 0.34f, cap = StrokeCap.Round)
        } else {
            drawOval(
                color = look.eye,
                topLeft = Offset(center.x - eyeRadiusX, center.y - eyeRadiusY * openness),
                size = Size(eyeRadiusX * 2f, eyeRadiusY * 2f * openness),
            )
            if (!blinking) {
                // 高光
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = eyeRadiusX * 0.22f,
                    center = Offset(center.x + eyeRadiusX * 0.28f, center.y - eyeRadiusY * openness * 0.4f),
                )
                if (slitPupil) {
                    drawOval(
                        color = look.belly,
                        topLeft = Offset(center.x - eyeRadiusX * 0.16f, center.y - eyeRadiusY * openness),
                        size = Size(eyeRadiusX * 0.32f, eyeRadiusY * 2f * openness),
                    )
                }
            }
        }
    }

    // 鼻头
    val nose = Path().apply {
        moveTo(eyeLeft.x + eyeRadiusX * 0.2f, noseY - eyeRadiusY * 0.22f)
        lineTo(eyeRight.x - eyeRadiusX * 0.2f, noseY - eyeRadiusY * 0.22f)
        lineTo((eyeLeft.x + eyeRight.x) / 2f, noseY + eyeRadiusY * 0.24f)
        close()
    }
    drawPath(nose, look.inner)

    // 嘴：开心/撒娇微微张开，出错是向下的弧线，其余是小 w
    val mouthColor = look.eye.copy(alpha = 0.85f)
    val stroke = eyeRadiusY * 0.26f
    when {
        happy -> {
            val mouth = Path().apply {
                moveTo((eyeLeft.x + eyeRight.x) / 2f - eyeRadiusX * 0.5f, mouthY)
                quadraticTo((eyeLeft.x + eyeRight.x) / 2f, mouthY + eyeRadiusY * 0.55f, (eyeLeft.x + eyeRight.x) / 2f + eyeRadiusX * 0.5f, mouthY)
            }
            drawPath(mouth, mouthColor, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
        sorry -> {
            val mouth = Path().apply {
                moveTo((eyeLeft.x + eyeRight.x) / 2f - eyeRadiusX * 0.45f, mouthY + eyeRadiusY * 0.22f)
                quadraticTo((eyeLeft.x + eyeRight.x) / 2f, mouthY - eyeRadiusY * 0.30f, (eyeLeft.x + eyeRight.x) / 2f + eyeRadiusX * 0.45f, mouthY + eyeRadiusY * 0.22f)
            }
            drawPath(mouth, mouthColor, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
        asking -> {
            drawCircle(look.inner, eyeRadiusX * 0.34f, Offset((eyeLeft.x + eyeRight.x) / 2f, mouthY + eyeRadiusY * 0.18f))
            // 头顶的小感叹号，提示"在等你"
            val ex = eyeRight.x + eyeRadiusX * 1.5f
            drawLine(mouthColor, Offset(ex, mouthY - eyeRadiusY * 1.8f), Offset(ex, mouthY - eyeRadiusY * 1.1f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(mouthColor, stroke * 0.5f, Offset(ex, mouthY - eyeRadiusY * 0.85f))
        }
        else -> {
            val mid = (eyeLeft.x + eyeRight.x) / 2f
            val left = Path().apply {
                moveTo(mid, mouthY)
                quadraticTo(mid - eyeRadiusX * 0.3f, mouthY + eyeRadiusY * 0.36f, mid - eyeRadiusX * 0.55f, mouthY + eyeRadiusY * 0.04f)
            }
            val right = Path().apply {
                moveTo(mid, mouthY)
                quadraticTo(mid + eyeRadiusX * 0.3f, mouthY + eyeRadiusY * 0.36f, mid + eyeRadiusX * 0.55f, mouthY + eyeRadiusY * 0.04f)
            }
            drawPath(left, mouthColor, style = Stroke(width = stroke, cap = StrokeCap.Round))
            drawPath(right, mouthColor, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    }

    // 出错时头顶冒汗滴，工作在写东西时嘴角一点小胡子线都不加，保持安静
    if (sorry) {
        val sx = eyeRight.x + eyeRadiusX * 1.6f
        val drop = Path().apply {
            moveTo(sx, mouthY - eyeRadiusY * 1.5f)
            quadraticTo(sx + eyeRadiusX * 0.42f, mouthY - eyeRadiusY * 0.9f, sx, mouthY - eyeRadiusY * 0.6f)
            quadraticTo(sx - eyeRadiusX * 0.42f, mouthY - eyeRadiusY * 0.9f, sx, mouthY - eyeRadiusY * 1.5f)
            close()
        }
        drawPath(drop, Color(0xFF8FC7F0))
    }
}

/** 爱心：两个圆 + 一个尖，比例固定，任何大小都不变形。 */
private fun DrawScope.drawHeart(center: Offset, radius: Float, color: Color) {
    val r = radius.coerceAtLeast(0.5f)
    val path = Path().apply {
        moveTo(center.x, center.y + r * 0.9f)
        cubicTo(
            center.x - r * 1.5f, center.y - r * 0.2f,
            center.x - r * 0.55f, center.y - r * 1.25f,
            center.x, center.y - r * 0.35f,
        )
        cubicTo(
            center.x + r * 0.55f, center.y - r * 1.25f,
            center.x + r * 1.5f, center.y - r * 0.2f,
            center.x, center.y + r * 0.9f,
        )
        close()
    }
    drawPath(path, color)
}

// 预设名字/默认台词：放在 UI 层，数据层不依赖资源
@Composable
private fun presetName(preset: FloatingCompanionPreset): String = stringResource(
    when (preset) {
        FloatingCompanionPreset.LOGO -> R.string.floating_companion_default_name_logo
        FloatingCompanionPreset.CAT -> R.string.floating_companion_default_name_cat
        FloatingCompanionPreset.DRAGON -> R.string.floating_companion_default_name_dragon
        FloatingCompanionPreset.CUSTOM -> R.string.floating_companion_default_name_custom
        FloatingCompanionPreset.WHALE_MAID -> R.string.portrait_whale_maid
        FloatingCompanionPreset.DRAGON_GIRL -> R.string.portrait_dragon_girl
        FloatingCompanionPreset.SUN_READER -> R.string.portrait_sun_reader
    }
)

@Composable
private fun stringArrayResourceFor(preset: FloatingCompanionPreset): List<String> = when (preset) {
    FloatingCompanionPreset.DRAGON -> listOf(
        stringResource(R.string.floating_companion_default_line_dragon_1),
        stringResource(R.string.floating_companion_default_line_dragon_2),
    )
    FloatingCompanionPreset.CAT -> listOf(
        stringResource(R.string.floating_companion_default_line_cat_1),
        stringResource(R.string.floating_companion_default_line_cat_2),
    )
    FloatingCompanionPreset.WHALE_MAID, FloatingCompanionPreset.DRAGON_GIRL, FloatingCompanionPreset.SUN_READER -> listOf(
        stringResource(R.string.portrait_line_1), stringResource(R.string.portrait_line_2),
    )
    else -> listOf(stringResource(R.string.floating_companion_default_line_cat_1))
}

/** 预览/设置页用：没有 Koin、没有真实会话也能跑的角色气泡。 */
@Composable
fun FloatingCompanionPreview(
    companion: FloatingCompanionSettings,
    modifier: Modifier = Modifier,
    state: FloatingChatUiState = previewCompanionState(),
    onClick: () -> Unit = {},
) {
    FloatingCompanionBubble(state = state, companion = companion, onClick = onClick, modifier = modifier)
}

/** 设置页预览用的假状态：空闲、没有待审批、没有错误。 */
fun previewCompanionState(): FloatingChatUiState = FloatingChatUiState(
    conversationId = kotlin.uuid.Uuid.random(),
    title = "",
    generating = false,
    processingStatus = null,
    phase = me.rerere.rikkahub.data.model.GenerationPhase.Waiting,
    hasReply = false,
    preview = "",
    previewIsReasoning = false,
    pendingTools = emptyList(),
    queuedCount = 0,
    queuePaused = false,
    error = null,
)

/** 角色默认名字/台词，给设置卡片的占位提示用。 */
@Composable
fun floatingCompanionDefaultName(preset: FloatingCompanionPreset): String = presetName(preset)

@Composable
fun floatingCompanionDefaultLines(preset: FloatingCompanionPreset): List<String> =
    stringArrayResourceFor(preset)


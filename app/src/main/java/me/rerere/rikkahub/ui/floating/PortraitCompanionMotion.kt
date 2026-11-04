package me.rerere.rikkahub.ui.floating

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.companion.CompanionAiPhase
import java.io.File
import kotlin.math.sin
import kotlin.random.Random

/**
 * 整图 2D 运动的全部参数。
 *
 * 这里只有"整张 PNG 平移/轻微缩放/轻摆"，没有骨骼、没有网格变形、没有物理模拟，
 * 也不是 Live2D；幅度刻意压得很小（缩放不超过 ±3%，位移不超过 3dp），
 * 免得透明 PNG 的轮廓被悬浮窗边界裁掉。
 */
internal object CompanionMotionSpec {
    /** 一轮呼吸/轻摆的时间；一轮里呼吸两次、摆动一次，看起来不像机械节拍。 */
    const val CYCLE_MILLIS = 5200

    /** 点击后保持点击图的时长，和 [me.rerere.rikkahub.data.datastore.FloatingCompanionLimits.TAP_DELAY_MILLIS] 一致。 */
    const val TAP_IMAGE_MILLIS = 400L

    /** 点完台词停留多久。 */
    const val SPEECH_MILLIS = 2200L
}

/** 系统"动画时长缩放"；0 表示用户或省电模式关掉了动画。 */
internal fun companionAnimatorScale(context: Context): Float = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
}.getOrDefault(1f)

/**
 * 附加运动是否允许跑：用户开关 + 生命周期 RESUMED + 没收起 + 系统动画时长缩放 > 0。
 *
 * 系统把时长缩放设成 0 时，Compose 的无限动画会退化成"每帧都完成一次"的空转，
 * 所以这里直接不建循环；静止状态照样保留点击切图和台词。
 */
@Composable
internal fun rememberCompanionMotionActive(
    animationEnabled: Boolean,
    minimized: Boolean,
    lifecycleResumed: Boolean,
): Boolean {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(companionAnimatorScale(context)) }
    // 用户可能刚在系统设置里改过动画，回到前台时重新读一次。
    LaunchedEffect(lifecycleResumed) {
        scale = companionAnimatorScale(context)
    }
    return animationEnabled && !minimized && lifecycleResumed && scale > 0f
}

/**
 * 由真实状态推导的"姿态"：思考时歪一点头、等审批时抬一点、出错时垂下去。
 *
 * 只是静止的倾斜/位移，不额外起循环，也不会编造任何结果文字。
 */
internal data class CompanionStatePose(
    val tiltDegrees: Float = 0f,
    val liftDp: Float = 0f,
)

internal fun companionStatePose(
    phase: CompanionAiPhase,
    hasPendingTools: Boolean,
    failed: Boolean,
): CompanionStatePose = when {
    failed -> CompanionStatePose(tiltDegrees = -2.4f, liftDp = -0.8f)
    hasPendingTools -> CompanionStatePose(tiltDegrees = 1.1f, liftDp = 1.2f)
    phase == CompanionAiPhase.THINKING -> CompanionStatePose(tiltDegrees = -1.3f, liftDp = 0.6f)
    phase == CompanionAiPhase.GENERATING -> CompanionStatePose(tiltDegrees = -0.6f, liftDp = 0.4f)
    phase == CompanionAiPhase.FAILED -> CompanionStatePose(tiltDegrees = -2f, liftDp = -0.6f)
    else -> CompanionStatePose()
}

/** 一帧的最终变换，单位是 dp / 度 / 倍率。 */
internal data class CompanionPose(
    val liftDp: Float,
    val shiftDp: Float,
    val tiltDegrees: Float,
    val scaleX: Float,
    val scaleY: Float,
)

/**
 * 把呼吸波 (-1..1)、摆动波 (-1..1)、点击回弹 (0..1) 和状态姿态合成一帧。
 *
 * 数值全部很小：呼吸抬 0.9dp，回弹最高抬 3dp；横向最多 0.6dp，倾斜不到 3 度。
 */
internal fun companionPose(
    breath: Float,
    sway: Float,
    bounce: Float,
    state: CompanionStatePose,
): CompanionPose = CompanionPose(
    liftDp = state.liftDp + breath * 0.7f + bounce * 2.4f,
    shiftDp = sway * 0.6f,
    tiltDegrees = state.tiltDegrees + sway * 0.9f + bounce * 0.6f,
    scaleX = 1f + breath * 0.005f - bounce * 0.012f,
    scaleY = 1f + breath * 0.005f + bounce * 0.026f,
)

/** 正弦波，phase 只走 0..1，两端都在 0，循环接缝看不出跳变。 */
internal fun companionWave(phase: Float, cycles: Int): Float =
    sin(phase * Math.PI.toFloat() * 2f * cycles)

/**
 * 点击台词的洗牌轮换。
 *
 * 每只角色 8 句，一轮里每句都用一次（不是每次只取第一句），
 * 换轮时也不把上一轮的末句排到第一个，所以不会连着说同一句。
 */
internal class CompanionLineRotation(private val random: Random = Random.Default) {
    private var bag: List<Int> = emptyList()
    private var cursor = 0
    private var last: Int = -1

    fun next(lines: List<String>): String? {
        if (lines.isEmpty()) return null
        if (lines.size == 1) return lines.first().also { last = 0 }
        if (cursor >= bag.size || bag.any { it >= lines.size }) refill(lines.size)
        val index = bag[cursor++]
        check(index in lines.indices) { "line index out of range" }
        last = index
        return lines[index]
    }

    fun reset() {
        bag = emptyList()
        cursor = 0
        last = -1
    }

    private fun refill(size: Int) {
        var next = (0 until size).shuffled(random)
        if (next.firstOrNull() == last) next = next.drop(1) + next.first()
        bag = next
        cursor = 0
    }
}

/**
 * 自定义动图在"静止"状态下只画第一帧。
 *
 * 用的是平台自带的 [BitmapFactory]（GIF / 动图 WebP 都只取首帧），
 * 不新增图片引擎，也不自己写解码循环；Coil 那头只负责正常情况的动图播放。
 * 所有自定义本地图统一取首帧；等待解码期间只画内置静态图，不启动动图解码器。
 */
@Composable
internal fun rememberCompanionStaticFrame(uri: String?): ImageBitmap? {
    if (uri == null) return null
    return key(uri) {
        val frame by produceState<ImageBitmap?>(initialValue = null, uri) {
            value = withContext(Dispatchers.IO) { decodeStaticFirstFrame(uri) }
        }
        frame
    }
}

private fun decodeStaticFirstFrame(uri: String): ImageBitmap? = runCatching {
    val file = resolveLocalFile(uri) ?: return@runCatching null
    if (!file.isFile) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}.getOrNull()

/** 只认资产实在包内/私有目录里的本地文件；不碰网络 URI。 */
private fun resolveLocalFile(uri: String): File? = runCatching {
    when {
        uri.startsWith("file:///android_asset/") -> null
        uri.startsWith("file:") -> Uri.parse(uri).path?.let(::File)
        uri.startsWith("/") -> File(uri)
        else -> null
    }
}.getOrNull()


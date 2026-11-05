package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import kotlin.math.roundToInt

/**
 * r21「轨道」风格的运行态与轨道零件。
 *
 * 只做两件事：
 * 1. 把「这条会话现在处于什么状态」算成一个可测试的纯函数（[orbitRunStateOf]），
 *    界面上的圆点、颜色、无障碍文案全部按它来，折叠/展开不会出现两套口径；
 * 2. 提供轨道上的几个小零件（竖条、圆点、上下文表盘）和设置页缩略图，
 *    让 ChatStyleHeader 和外观设置页共用，不各画一遍。
 *
 * 这里不动任何消息数据、网络或状态机：运行态是**推导**出来的，不是新存的一份状态。
 */

/** 轨道上的四种状态；决定圆点颜色和无障碍文案。 */
internal enum class OrbitRunState {
    ERROR,
    RECOVERING,
    /** 还没有助手消息：待机。 */
    IDLE,

    /** 最后一条助手消息还在流式生成：数值先不给，避免把"生成中"说成"测不到"。 */
    STREAMING,

    /** 生成结束且有可信 TPS。 */
    MEASURED,

    /** 生成结束但没有可测量的速度（原因在展开面板里照常显示，不在这里藏掉）。 */
    UNAVAILABLE,
}

/** 纯函数：三件事推导一个运行态，方便 JVM 单测穷举。 */
internal fun orbitRunState(
    hasAssistantMessage: Boolean,
    streaming: Boolean,
    hasSpeed: Boolean,
): OrbitRunState = when {
    !hasAssistantMessage -> OrbitRunState.IDLE
    streaming -> OrbitRunState.STREAMING
    hasSpeed -> OrbitRunState.MEASURED
    else -> OrbitRunState.UNAVAILABLE
}

/**
 * 当前会话的运行态。速度口径与消息底部、展开面板共用 [conversationSpeedUi]，
 * 所以圆点颜色不会和那两处的数字互相打脸。
 */
internal fun orbitRunStateOf(conversation: Conversation): OrbitRunState {
    val message = conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
        ?: return OrbitRunState.IDLE
    return orbitRunState(
        hasAssistantMessage = true,
        // 与 conversationSpeedUi 的 finished 判定同一个依据
        streaming = message.finishedAt == null,
        hasSpeed = conversationSpeedUi(conversation).speed != null,
    )
}

/**
 * 顶栏的"正在自动恢复"判定。
 *
 * 服务的 [processingStatus]（等待网络、已排定重试、OCR、上下文压缩……）本身只是进度文案，
 * 不能直接当成"恢复中"：OCR 和压缩都发生在生成任务内部。真正能区分的是**引擎还在不在跑**——
 * 生成任务为空但还有状态挂着，说明这一轮卡住了、正在等重连或等重试。
 *
 * 所以这里只看两个信号，不看文案，也不看历史错误列表（错误列表要用户手动清，留着会一直误报）。
 */
internal fun orbitRecovering(
    hasPendingStatus: Boolean,
    generationActive: Boolean?,
): Boolean = hasPendingStatus && generationActive != true

/**
 * 顶栏运行态（纯函数，方便穷举测试）。
 *
 * 优先级：恢复中 > 生成中 > 出错 > 按会话实际数据推导（[orbitRunStateOf]）。
 * 最后一段不能自己写"没在跑就是待机"：生成结束了但测不到速度是 UNAVAILABLE，
 * 手里有可信速度才是 MEASURED，只有真没有助手消息才是 IDLE。
 */
internal fun orbitHeaderRunState(
    generationActive: Boolean?,
    hasError: Boolean,
    recovering: Boolean,
    conversation: Conversation,
): OrbitRunState = when {
    recovering -> OrbitRunState.RECOVERING
    generationActive == true -> OrbitRunState.STREAMING
    hasError -> OrbitRunState.ERROR
    else -> orbitRunStateOf(conversation)
}

/** 上下文占用比例；缺数据或容量坏值一律返回 null，界面不画表盘而不是画一个假的 0%。 */
internal fun orbitContextRatio(used: Int?, capacity: Int?): Float? {
    if (used == null || capacity == null || capacity <= 0 || used < 0) return null
    return (used.toFloat() / capacity).coerceIn(0f, 1f)
}

/** 表盘在 fontScale = 1 时需要的最小宽度（dp）。 */
internal const val ORBIT_CONTEXT_GAUGE_MIN_WIDTH_DP = 380f

/**
 * 轨道上要不要加上下文表盘。
 *
 * 宽度门槛随字体缩放一起放大：1.3 倍字体下要 380 * 1.3 ≈ 494dp 才出现，
 * 这样大字体不会把模型名和 TPS 挤成省略号（宁可少一个装饰，也不让关键信息被吞）。
 */
internal fun orbitContextGaugeVisible(widthDp: Float, fontScale: Float): Boolean {
    if (!widthDp.isFinite() || widthDp <= 0f) return false
    val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale.coerceAtLeast(1f) else 1f
    return widthDp >= ORBIT_CONTEXT_GAUGE_MIN_WIDTH_DP * scale
}

/**
 * 运行态圆点颜色。
 *
 * 生成中用 tertiary（明确的"正在进行"），有可信数值用 primary，待机走中性，
 * 测不到速度走 outlineVariant —— 不用 error：没测到速度不等于出错，真原因在展开面板里。
 */
internal fun orbitRunColor(state: OrbitRunState, scheme: ColorScheme): Color = when (state) {
    OrbitRunState.ERROR -> scheme.error
    OrbitRunState.RECOVERING -> scheme.tertiary
    OrbitRunState.IDLE -> scheme.outline
    OrbitRunState.STREAMING -> scheme.tertiary
    OrbitRunState.MEASURED -> scheme.primary
    OrbitRunState.UNAVAILABLE -> scheme.outlineVariant
}

/** 状态文案资源；圆点的无障碍描述与预览共用一份。 */
internal fun orbitRunStateRes(state: OrbitRunState): Int = when (state) {
    OrbitRunState.ERROR -> R.string.r21_orbit_state_error
    OrbitRunState.RECOVERING -> R.string.r21_orbit_state_recovering
    OrbitRunState.IDLE -> R.string.r21_orbit_state_idle
    OrbitRunState.STREAMING -> R.string.r21_orbit_state_streaming
    OrbitRunState.MEASURED -> R.string.r21_orbit_state_measured
    OrbitRunState.UNAVAILABLE -> R.string.r21_orbit_state_unavailable
}

/** 运行态圆点 + 一小段轨道：把"状态"和"模型名"视觉上连成一条线。 */
@Composable
internal fun OrbitRunDot(
    state: OrbitRunState,
    modifier: Modifier = Modifier,
) {
    val color = orbitRunColor(state, MaterialTheme.colorScheme)
    val label = stringResource(orbitRunStateRes(state))
    Canvas(
        modifier = modifier
            .size(width = 20.dp, height = 10.dp)
            .semantics { contentDescription = label },
    ) {
        val radius = size.height / 2f
        drawCircle(color = color, radius = radius * 0.85f, center = Offset(radius, size.height / 2f))
        drawLine(
            color = color.copy(alpha = 0.45f),
            start = Offset(radius * 1.7f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

/** 上下文表盘：一小段轨道 + 已用比例，不写数字，数字在展开面板里。 */
@Composable
internal fun OrbitContextGauge(
    ratio: Float,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val safeRatio = ratio.coerceIn(0f, 1f)
    val percent = (safeRatio * 100).roundToInt()
    val label = stringResource(R.string.r21_orbit_context_gauge, percent)
    val fill = if (percent >= 90) scheme.error else scheme.primary
    Canvas(
        modifier = modifier
            .size(width = 32.dp, height = 8.dp)
            .semantics { contentDescription = label },
    ) {
        // 极窄/极端密度下画不出圆头轨道就不画，避免 coerceIn 的上下界打架
        if (size.width <= size.height) return@Canvas
        drawLine(
            color = scheme.outlineVariant.copy(alpha = 0.55f),
            start = Offset(size.height / 2f, size.height / 2f),
            end = Offset(size.width - size.height / 2f, size.height / 2f),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
        if (percent > 0) {
            drawLine(
                color = fill,
                start = Offset(size.height / 2f, size.height / 2f),
                end = Offset(
                    (size.width * safeRatio).coerceIn(size.height / 2f, size.width - size.height / 2f),
                    size.height / 2f,
                ),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 任务栏左侧的"轨道竖条"：从状态色渐变到透明，一眼看出这块是常驻状态栏而不是卡片。
 *
 * 用 `matchParentSize` 画，永远不吃布局尺寸，标题栏高度只由真实内容决定。
 */
@Composable
internal fun BoxScope.OrbitRailStripe(
    state: OrbitRunState,
    modifier: Modifier = Modifier,
) {
    val color = orbitRunColor(state, MaterialTheme.colorScheme)
    Canvas(modifier = modifier.matchParentSize()) {
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0f)),
                startX = 0f,
                endX = 16.dp.toPx(),
            ),
            size = Size(3.dp.toPx(), size.height),
        )
    }
}

/**
 * 外观设置页的第四张缩略图：任务栏 + 轨道 + 正文。
 *
 * 只画纹理、不带文案，所以不需要 stringResource；和另外三张草图一样是"看形状"用的。
 */
@Composable
internal fun OrbitLayoutSketch(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.onSurface.copy(alpha = 0.26f)
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // 任务栏：左边一条竖轨，右边标题线
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                .background(scheme.surfaceContainerHigh),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(scheme.primary.copy(alpha = 0.85f)),
            )
            Box(
                modifier = Modifier
                    .padding(start = 5.dp)
                    .width(34.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(line),
            )
        }
        // 运行态轨道：圆点 + 模型 + 速度 + 表盘
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(scheme.surfaceContainerLow),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(scheme.tertiary),
            )
            Box(
                modifier = Modifier
                    .width(30.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(scheme.primary.copy(alpha = 0.7f)),
            )
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(line),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(line),
            )
        }
        // 正文：铺满 + 近乎方角
        Box(
            modifier = Modifier
                .fillMaxWidth(0.74f)
                .height(9.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(scheme.surfaceContainerHigh),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.54f)
                .height(9.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(scheme.surfaceContainerHigh),
        )
    }
}

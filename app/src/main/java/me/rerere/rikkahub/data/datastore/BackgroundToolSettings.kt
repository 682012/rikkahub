package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/**
 * 通用工具转后台。
 *
 * 语义：任何**可能跑很久**的工具被模型调用后（资格按耗时判断：`SERIAL` / `PARALLEL` /
 * `PARALLEL_DEDUPLICATED` 都可以，排除交互式、批量入口、脚本、子代理家族、显式后台控制与
 * `repeatable` 轮询类），先在前台等 [foregroundTimeoutSeconds] 秒；还没跑完就把这次调用转成后台
 * 任务，工具本体**继续跑同一份执行**（不取消、不重跑），模型拿回一条回执，可以继续做别的事。
 * 跑完后往会话收件箱放一条完成提醒（本模块不发起模型请求），正文由 `background_tool` 分页读回。
 *
 * 和 `workspace_shell` 自己的超时是两件事：工具自身 timeout 不变，这里只决定「模型等不等」。
 */
@Serializable
data class BackgroundToolSettings(
    val enabled: Boolean = true,
    /** 前台等待上限（秒）。最小 1 秒、最大 1 小时；越界值读写时都会归一化。 */
    val foregroundTimeoutSeconds: Int = 30,
) {
    /** 越界值兜底：老版本落盘或用户手改 xml 都可能塞进 0 / 负数 / 一年。 */
    fun normalized(): BackgroundToolSettings {
        val seconds = foregroundTimeoutSeconds.coerceIn(MIN_FOREGROUND_TIMEOUT_SECONDS, MAX_FOREGROUND_TIMEOUT_SECONDS)
        return if (seconds == foregroundTimeoutSeconds) this else copy(foregroundTimeoutSeconds = seconds)
    }

    /** 前台等待毫秒数（已归一化）。 */
    val foregroundWaitMillis: Long
        get() = normalized().foregroundTimeoutSeconds * 1000L

    companion object {
        const val MIN_FOREGROUND_TIMEOUT_SECONDS = 1
        const val MAX_FOREGROUND_TIMEOUT_SECONDS = 3600
    }
}

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 工作区运行时设置。
 *
 * 与上游 Settings 分文件维护，减少后续合并 3.0.x 时的冲突面。
 */
@Serializable
data class WorkspaceRuntimeSettings(
    /** workspace_shell 的默认超时（秒）。0 表示不限制。 */
    val commandTimeoutSec: Int = 30,
    /** 模型可以指定的最大超时（秒）。0 表示不设上限。 */
    val maxTimeoutSec: Int = 0,
    /** 后台任务日志/元数据的保留天数。 */
    val keepJobsDays: Int = 7,
    /** 远程执行后端。null 表示只用本地 proot。 */
    val remote: RemoteExecutionSettings? = null,
)

@Serializable
data class RemoteExecutionSettings(
    val enabled: Boolean = false,
    /** 例如 http://43.248.3.158:18080 */
    val baseUrl: String = "",
    val token: String = "",
    /** 远程工作目录，服务端会在此目录下执行命令。 */
    val workdir: String = "~/rk-work",
    /** 单次请求的超时（秒），0 表示不限制（服务端可能仍有自己的上限）。 */
    val requestTimeoutSec: Int = 0,
)

/**
 * 子代理设置。
 *
 * modelIds 是允许子代理使用的模型池；运行时按任务选择，defaultModelId 为空时
 * 取父会话模型。fallbackModelId 只在主模型永久性失败（4xx/空输出/非法 JSON）时使用。
 */
/**
 * 自动压缩：上下文占用达到模型窗口的阈值时，先插入一条摘要检查点再继续对话。
 * 摘要不会删除历史，原始消息仍然保留。
 */
@Serializable
data class AutoCompressSettings(
    val enabled: Boolean = true,
    /** 触发百分比，80 表示用到窗口的 80% 时压缩。 */
    val thresholdPercent: Int = 80,
    /** 生成摘要用的模型；null 表示跟随当前对话模型（保持提示词缓存命中）。 */
    val modelId: Uuid? = null,
)

/**
 * 子代理模型分配策略。
 *
 * - [ORDERED]：每个任务都从池首（默认模型）起步，失败后按池顺序往后换。
 * - [BALANCED]：同时创建的任务按游标轮转分到池里的不同渠道，避免全部打主模型。
 */
@Serializable
enum class SubagentModelStrategy {
    ORDERED,
    BALANCED,
}

@Serializable
data class SubagentSettings(
    val enabled: Boolean = false,
    /**
     * 同时运行的最大子代理数。
     *
     * 硬上限 8（见 SubagentRunner.HARD_MAX_CONCURRENT）；低内存设备上运行期还会按
     * ActivityManager.memoryClass 自动降下来（见 SubagentRunner.effectiveSubagentConcurrency），
     * 所以这里填 8 只表示「内存够大时最多跑 8 个」，不会在小内存机器上硬跑。
     */
    val maxConcurrent: Int = 4,
    val maxSteps: Int = 40,
    /** 单个子代理的总超时（秒），0 表示不限制。 */
    val timeoutSec: Int = 900,
    /** 子代理结果与日志保留天数。 */
    val keepDays: Int = 7,
    val modelIds: List<Uuid> = emptyList(),
    val defaultModelId: Uuid? = null,
    val fallbackModelId: Uuid? = null,
    /**
     * 模型池的派单策略。默认 [SubagentModelStrategy.ORDERED]，与 r21 的"先主后备"一致；
     * 选 [SubagentModelStrategy.BALANCED] 时同时创建的任务会分散到池里的多个渠道。
     */
    val modelStrategy: SubagentModelStrategy = SubagentModelStrategy.ORDERED,
    /**
     * 遇到问题自动换下一个模型：provider 报错或空输出时，从已完成的工具/模型 checkpoint 续跑下一个
     * 渠道，不重跑已经成功的工具。超时是整任务预算，不会因为换渠道而重置；
     * 未知副作用、需要审批、主动取消一律不重放。
     */
    val autoFailover: Boolean = true,
    /** 单个任务最多尝试几个模型（含第一个）；<=0 表示不限，但不超过模型池大小。 */
    val maxModelAttempts: Int = 3,
    /**
     * 每个模型同时运行的子代理数上限。0 表示不额外限制，只受 [maxConcurrent] 约束。
     * 均衡分配时用它避免把多个任务压到同一个渠道上。
     */
    val perModelConcurrency: Int = 0,
    val includeWorkspace: Boolean = true,
    val includeSearch: Boolean = false,
    val includeMcp: Boolean = false,
    /** 子代理可检索并读取其他会话（只读）。 */
    val includeChatHistory: Boolean = true,
    /** 子代理共用的会话 ID，让服务端命中系统提示与工具定义的缓存。 */
    val sessionId: Uuid = Uuid.random(),
    /** 允许子代理再创建子代理。默认关闭，避免递归放大成本。 */
    val allowNested: Boolean = false,
)

package me.rerere.rikkahub.data.ai.learning

/**
 * 学习内核的硬上限。全部是常量，方便设置页展示和测试断言。
 *
 * 这些数字是「别让一个小功能变成新的内存/上下文负担」的边界，不是可调参数。
 */
object LearningLimits {
    /** 磁盘格式版本；升级字段时 +1，旧文件仍能读 */
    const val SCHEMA_VERSION: Int = 1

    /** 每个助手最多保留多少轮指标（FIFO，超出丢最旧的） */
    const val MAX_TURNS: Int = 200

    /** 自动经验最多几条 */
    const val MAX_AUTO_GUIDANCE: Int = 6

    /** 用户经验最多几条（24 - 6） */
    const val MAX_USER_GUIDANCE: Int = 18

    /** 自动 + 用户合计上限 */
    const val MAX_GUIDANCE: Int = MAX_AUTO_GUIDANCE + MAX_USER_GUIDANCE

    /** 一条经验成立所需的最少重复轮数：单次失败不算通则 */
    const val MIN_EVIDENCE_TURNS: Int = 3

    /** 只看最近多久的指标（毫秒）。更旧的证据不再支撑注入 */
    const val EVIDENCE_WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** 只看最近多少轮（在 [EVIDENCE_WINDOW_MS] 之后再截一次） */
    const val EVIDENCE_SCAN_TURNS: Int = 60

    /** 注入段总体上限（字符数，按 UTF-16 计，中文按实际字数算） */
    const val MAX_PROMPT_CHARS: Int = 1800

    /** 一次最多注入几条经验 */
    const val MAX_PROMPT_RULES: Int = 3

    /** 单条经验注入时的最大字符数（超出截断并加省略号） */
    const val MAX_RULE_CHARS: Int = 240

    /** 用户一条经验的存储长度上限 */
    const val MAX_GUIDANCE_TEXT: Int = 240

    /** 单个 id 字段（conversationId / messageId / modelId）的存储长度上限 */
    const val MAX_ID_CHARS: Int = 64

    /** 单个 JSON 文件的大小上限；超过就当损坏处理，不读进内存 */
    const val MAX_FILE_BYTES: Long = 4L * 1024 * 1024

    /** 进程内最多缓存几个助手的已解析文件（真正的数据在磁盘上） */
    const val MAX_CACHED_ASSISTANTS: Int = 3
}

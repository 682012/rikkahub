package me.rerere.rikkahub.data.event

/** 手动压缩的结果 */
enum class CompressionOutcome {
    /** 摘要已写入，原消息保留 */
    Succeeded,

    /** 请求失败，对话没有被修改 */
    Failed,

    /** 压缩期间对话被改动，旧摘要不安全，丢弃不写入 */
    Discarded,

    /** 用户主动取消 */
    Cancelled,
}

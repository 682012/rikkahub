package me.rerere.rikkahub.data.companion

import kotlin.uuid.Uuid

/** 流式增量：正文和思考分开，界面据此区分"思考中"和"生成中"。 */
data class CompanionDelta(val text: String = "", val reasoning: String = "")

/**
 * 真正和模型打交道的那一层。
 *
 * 控制器只认识这个接口，所以在 JVM 单测里可以塞一个假实现，不需要 Android、网络或真实密钥。
 * 生产实现是 `data/ai/CompanionReplyGenerator`：它复用现有的 ProviderManager（全局并发闸门）
 * 和 UsageRecorder（单独记 COMPANION 用量），不碰主聊天的会话和账单。
 *
 * 两个方法都必须在**同一个模型快照**上说话：
 * - [describeModel] 返回的描述里带 [CompanionModelDescription.snapshot]；
 * - 控制器把这份快照放进 [CompanionRequest.expectedModel]，[generate] 实现解析后必须比对，
 *   不一致（用户换模型/删模型/换会话）就抛 [CompanionModelUnavailableException]，
 *   **不允许**拿另一个模型把这次请求发出去。
 *
 * 取消是协作式的：实现应当把协程的取消传递给底层请求。但控制器不依赖这一点——它有自己的
 * run token，实现就算吞掉取消、照样返回，这一轮也不会被写成完成。实现仍应尽量把取消传下去，
 * 好让网络请求真的停下来。
 */
interface CompanionReplySource {
    /** 当前能不能用、用的是哪个模型；只读设置和会话，不发请求。 */
    suspend fun describeModel(conversationId: Uuid?): CompanionModelDescription

    /**
     * 发一次请求，边流边回调；返回完整文本。
     * 模型不可用时抛 [CompanionModelUnavailableException]，让控制器显示明确原因。
     */
    suspend fun generate(
        conversationId: Uuid?,
        request: CompanionRequest,
        onDelta: (CompanionDelta) -> Unit,
    ): String
}

/** 模型不可用（没选、被删了、提供商没了、解析结果已经过期）。界面按 [failure] 显示原因和入口。 */
class CompanionModelUnavailableException(val failure: CompanionFailure) :
    IllegalStateException("companion model unavailable: $failure")

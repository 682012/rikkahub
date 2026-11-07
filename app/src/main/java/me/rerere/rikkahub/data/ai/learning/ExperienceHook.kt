package me.rerere.rikkahub.data.ai.learning

/**
 * 把 [ExperienceService] 接到「每轮任务结束」的唯一接缝。
 *
 * 为什么单独一个类：ChatService 只认识 [AssistantLearningRepository]（它本来就在那里记录每轮指标），
 * 接入经验学习不需要再给 ChatService 塞一个构造参数、也不改它的 DI 顺序——
 * 只要仓库上挂着一个终态回调，指标落盘之后就会把这一轮交给 [ExperienceService]。
 *
 * 约束（都由被调用方保证）：
 * - 一轮只触发一次：[ExperienceService] 内部有 in-flight + 落盘双重去重；
 * - 取消不算成功：调用方只在终态（成功 / 终止）时构造 [TurnFacts]，取消、等待审批都是 null；
 * - 不阻塞回复：[ExperienceService.onTurnFinished] 立即返回 Job。
 *
 * 由 AppModule 以 `createdAtStart = true` 创建：启动即接线，避免第一轮漏触发。
 */
class ExperienceHook(
    private val learningRepository: AssistantLearningRepository,
    private val experienceService: ExperienceService,
) {
    init {
        learningRepository.experience = { facts -> experienceService.onTurnFinished(facts) }
    }
}

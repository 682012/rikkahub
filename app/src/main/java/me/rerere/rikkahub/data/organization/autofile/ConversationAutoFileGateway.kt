package me.rerere.rikkahub.data.organization.autofile

import kotlin.uuid.Uuid

/**
 * Controller 与真实数据库/聊天服务之间的窄接口。
 *
 * 拆出来有两个好处：Controller 不 import 任何 Android/Room 代码，可以纯 JVM 单测；
 * 真正改归属只走 [ChatService.applyAutoFileMove]（它负责重检 owner / updateAt / 原文件夹 / 锁 / 忙碌），
 * 这里绝不做「读整条 Conversation 再存回去」这种事——那会把陈旧的会话快照写回数据库。
 */
interface ConversationAutoFileGateway {
    /** 会话现在能不能被后台动：生成中、审批中、迁移中等情况由 ChatService 判断。 */
    suspend fun canAutoFile(conversationId: Uuid): Boolean

    /** 该助手当前还「没归类」的会话（轻量摘要，不带消息体）。 */
    suspend fun loadCandidates(assistantId: Uuid): List<AutoFileCandidate>

    /** 标题之外给模型的正文：开头 [headChars] + 结尾 [tailChars]，只取纯文本。 */
    suspend fun loadExcerpt(conversationId: Uuid, headChars: Int, tailChars: Int): String
    suspend fun loadExcerptForAssistant(assistantId: Uuid, conversationId: Uuid, headChars: Int, tailChars: Int): String =
        loadExcerpt(conversationId, headChars, tailChars)

    suspend fun loadFolders(assistantId: Uuid): List<AutoFileFolderRef>

    suspend fun createFolder(assistantId: Uuid, name: String): Uuid?

    /** 提交一次归属变更（[undo] 为 true 时由 ChatService 只在没被改动过时还原）。 */
    suspend fun move(
        assistantId: Uuid,
        conversationId: Uuid,
        expectedUpdateAt: Long,
        expectedFolderId: Uuid?,
        folderId: Uuid?,
        undo: Boolean,
    ): Boolean

    /** 读会话当前归属，用于撤销前的复核和移动后记录版本。 */
    suspend fun placement(conversationId: Uuid): AutoFilePlacement?

    /**
     * 历史详情用的会话标题（只读标题，不读正文、不发请求）。
     * 默认实现返回空表：测试用假实现可以完全不实现它，界面会退回显示会话 id。
     */
    suspend fun loadConversationTitles(assistantId: Uuid, conversationIds: List<Uuid>): Map<Uuid, String> = emptyMap()

    /** 一批结束后发一条被动通知（`postNotice`，不会唤醒新的一轮计费生成）。 */
    suspend fun postSummary(assistantId: Uuid, text: String)
}


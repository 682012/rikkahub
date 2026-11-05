package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid

private val forkTitleSuffixRegex = Regex("""\((\d+)\)$""")

internal fun forkConversationTitle(sourceTitle: String, existingTitles: Set<String>): String {
    // 源标题已带 (N) 后缀时递增序号，避免多次 fork 后叠加成 xxx(1)(1)(1)
    val suffix = forkTitleSuffixRegex.find(sourceTitle)
    val baseTitle = suffix?.let { sourceTitle.removeRange(it.range) } ?: sourceTitle
    val start = suffix?.groupValues?.get(1)?.toIntOrNull()?.plus(1) ?: 1
    return generateSequence(start) { it + 1 }
        .map { "$baseTitle($it)" }
        .first { it !in existingTitles }
}

/**
 * 从开头复制到 [messageId] 所在的节点（含），得到一个新会话；没有这条消息时返回 null。
 *
 * 继承助手、系统提示词、提示词注入、会话配置、工作目录和文件夹，标题在 [existingTitles] 中顺延编号。
 * 节点换新 id；本地附件经 [copyFile] 复制一份，两个会话之后各删各的文件，互不影响。
 *
 * 谱系：新会话挂到源的家族根上（源自身没有根时，源就是根）。源会话正好是旧数据、还没记根的话，
 * 这里只在新会话上写 lineageId，不去回写源，免得为了一条分叉改旧会话的元信息。
 * 归库锁 [Conversation.folderAssignmentLocked] 不随分叉继承：分叉后是新的一条会话，归到哪儿由它自己重新决定。
 */
internal fun Conversation.fork(
    messageId: Uuid,
    existingTitles: Set<String>,
    copyFile: (url: String) -> String,
): Conversation? {
    val nodeIndex = messageNodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
    if (nodeIndex == -1) return null
    return Conversation(
        id = Uuid.random(),
        assistantId = assistantId,
        title = forkConversationTitle(title, existingTitles),
        messageNodes = messageNodes.take(nodeIndex + 1).map { node ->
            node.copy(
                id = Uuid.random(),
                messages = node.messages.map { message ->
                    message.copy(parts = message.parts.map { it.mapLocalFileUrls(copyFile) })
                },
            )
        },
        customSystemPrompt = customSystemPrompt,
        modeInjectionIds = modeInjectionIds,
        lorebookIds = lorebookIds,
        config = config,
        workspaceCwd = workspaceCwd,
        folderId = folderId,
        lineageId = lineageId ?: id,
    )
}

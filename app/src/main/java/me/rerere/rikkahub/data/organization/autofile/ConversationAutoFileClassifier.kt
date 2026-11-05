package me.rerere.rikkahub.data.organization.autofile

import kotlin.uuid.Uuid

/**
 * 分类器接口：Controller 只认这个，所以整条链路可以在纯 JVM 测试里用假的实现跑完。
 */
interface ConversationAutoFileClassifier {
    /**
     * 给一批候选（≤16 条）建议归到哪个文件夹。
     * 失败时返回带 [AutoFileParsedClassification.error] 的结果，**不要抛异常**——
     * 抛出去只会让整批白跑，而这里的错误正好可以原样显示给用户重试。
     */
    suspend fun classify(
        assistantId: Uuid,
        items: List<AutoFileClassifyItem>,
        folders: List<AutoFileFolderRef>,
    ): AutoFileParsedClassification
}

/**
 * 拼给快速模型的提示词，单独抽出来便于纯 JVM 断言：
 * 只给标题 + 首尾限长摘要，要求严格 JSON、不改标题、不做别的解释。
 */
object ConversationAutoFilePrompt {
    const val MAX_ITEMS = ConversationAutoFilePlanner.MAX_ITEMS_PER_RUN

    fun build(items: List<AutoFileClassifyItem>, folders: List<AutoFileFolderRef>): String {
        val folderList = folders
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(ConversationAutoFilePlanner.MAX_FOLDERS_TOTAL)
            .joinToString("\n") { "- $it" }
            .ifEmpty { "(空)" }

        val itemList = items.take(MAX_ITEMS).joinToString("\n") { item ->
            buildString {
                append("- id: ").append(item.id)
                append("\n  title: ").append(item.title.replace(Regex("\\s+"), " ").trim().take(160))
                val excerpt = item.excerpt.replace(Regex("\\s+"), " ").trim()
                if (excerpt.isNotEmpty()) append("\n  excerpt: ").append(excerpt)
            }
        }

        return """
            你是会话整理助手。下面是一批**闲置**的聊天会话（标题 + 开头和结尾的摘录）以及该助手已有的文件夹。
            请给每个会话建议一个归属文件夹。

            规则：
            1. 只处理给出的会话 id，不要新增 id，不要遗漏后随意编造。
            2. 优先复用已有文件夹：名字必须与下面列出的完全一致，这时 "isNew": false。
            3. 只有确实没有合适的已有文件夹时才新建，新名字要短（不超过 20 个字）、能概括共同话题，这时 "isNew": true。
            4. 同一个新名字至少要有 3 个会话才值得新建；凑不够就让它们复用已有文件夹或干脆不给出。
            5. confidence 是 0 到 1 的小数，只有确定才给高分（≥0.85）。
            6. 只输出 JSON，不要代码块、不要解释：

            {"assignments":[{"id":"<会话id>","name":"<文件夹名>","isNew":false,"confidence":0.9}]}

            已有文件夹：
            ${folderList}

            待整理会话（最多 ${MAX_ITEMS} 条）：
            ${itemList}
        """.trimIndent()
    }
}

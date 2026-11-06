package me.rerere.rikkahub.data.organization.autofile

import kotlin.math.sqrt
import kotlin.uuid.Uuid

/**
 * **完全本地**的规则整理模式。
 *
 * 它不是一个模型，也不宣称准确率：只做可复现的字符串统计，同样的输入永远得到同样的输出，
 * 用户可以照着理由自己复核。
 *
 * 判断依据（只看标题、`#标签`、以及调用方已经给好的限长摘录）：
 * 1. 分词：拉丁/数字词（≥2 字符）、中文二元组、`#标签`；去掉一小组停用词；
 * 2. 与**已有文件夹名**做加权余弦相似度，过门槛就复用那个库（已有库优先，与 AI 模式一致）；
 * 3. 剩下的会话按相似度做单遍聚类，只有 ≥[MIN_CLUSTER_SIZE] 条才建议新建库
 *    （AI 模式新库要求 ≥3 个不同话题，这里用条数做保守代理，最终仍由 Planner 复核家族数）；
 * 4. 置信度由相似度/簇规模机械算出，上限 [MAX_CONFIDENCE]，不会给出 0.99 这种「保证准确」的数字；
 * 5. 每条建议都带一句可解释的 [AutoFileAssignment.reason]。
 *
 * 约束：不读磁盘、不发网络、不调用任何模型、不依赖当前时间，纯 JVM 可测。
 */
class ConversationAutoFileLocalClassifier : ConversationAutoFileClassifier {

    override suspend fun classify(
        assistantId: Uuid,
        items: List<AutoFileClassifyItem>,
        folders: List<AutoFileFolderRef>,
    ): AutoFileParsedClassification {
        if (items.isEmpty()) return AutoFileParsedClassification()
        val bounded = items.take(ConversationAutoFilePlanner.MAX_ITEMS_PER_RUN)
        val folderProfiles = folders
            .mapNotNull { folder -> folderProfile(folder) }
            .take(ConversationAutoFilePlanner.MAX_FOLDERS_TOTAL)

        val assignments = ArrayList<AutoFileAssignment>(bounded.size)
        val leftovers = ArrayList<Vector>()

        bounded.forEach { item ->
            val vector = vectorOf(item)
            if (vector.weights.isEmpty()) return@forEach
            val best = folderProfiles
                .map { profile -> profile to similarity(vector, profile.vector) }
                // 分数相同时按库名排序，保证输出与输入顺序无关、可复现
                .sortedWith(compareByDescending<Pair<FolderProfile, Double>> { it.second }.thenBy { it.first.name })
                .firstOrNull()
            if (best != null && best.second >= EXISTING_FOLDER_MIN_SCORE) {
                val (profile, score) = best
                assignments += AutoFileAssignment(
                    conversationId = item.id,
                    folderName = profile.name,
                    isNew = false,
                    confidence = confidenceForExistingFolder(score),
                    reason = buildString {
                        append("与本机已有库「${profile.name}」词重合度 ")
                        append(formatScore(score))
                        append("（命中：")
                        append(profile.vector.topTokens(EXPLAIN_TOKENS).joinToString("、"))
                        append("）")
                    },
                )
            } else {
                leftovers += vector
            }
        }

        // 单遍聚类：新会话只与已形成的簇比较，不回头改已有归属，结果与处理顺序一致。
        val clusters = ArrayList<MutableList<Vector>>()
        leftovers.forEach { vector ->
            val target = clusters.firstOrNull { cluster -> similarity(vector, centroidOf(cluster)) >= CLUSTER_MIN_SCORE }
            if (target != null) target += vector else clusters += mutableListOf(vector)
        }

        clusters.forEach { cluster ->
            if (cluster.size < MIN_CLUSTER_SIZE) return@forEach
            val tokens = sharedTokens(cluster).ifEmpty { cluster.flatMap { it.topTokens(2) }.distinct().take(2) }
            val name = ConversationAutoFileParser.cleanName(tokens.joinToString(" ")) ?: return@forEach
            val confidence = confidenceForNewCluster(cluster.size)
            cluster.forEach { vector ->
                assignments += AutoFileAssignment(
                    conversationId = vector.conversationId,
                    folderName = name,
                    isNew = true,
                    confidence = confidence,
                    reason = "同批 ${cluster.size} 条会话共享关键词「${tokens.joinToString("、")}」" +
                        "（与我已有的库都不够像，建议新建）",
                )
            }
        }

        return AutoFileParsedClassification(assignments = assignments)
    }

    // ---- 内部 ----

    private data class Vector(
        val conversationId: Uuid,
        val weights: Map<String, Double>,
    ) {
        fun topTokens(limit: Int): List<String> =
            weights.entries
                .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                .take(limit)
                .map { it.key }
    }

    private data class FolderProfile(
        val name: String,
        val vector: Vector,
    )

    private fun folderProfile(folder: AutoFileFolderRef): FolderProfile? {
        val tokens = tokensOf(folder.name)
        if (tokens.isEmpty()) return null
        // 库名本身就是概括，权重给高一点，避免正文噪音词压过库名。
        val weights = tokens.groupingBy { it }.eachCount()
            .mapValues { (_, count) -> FOLDER_NAME_WEIGHT * count.toDouble().coerceAtMost(3.0) }
        return FolderProfile(folder.name, Vector(folder.id, weights))
    }

    private fun vectorOf(item: AutoFileClassifyItem): Vector {
        val titleTokens = tokensOf(item.title)
        val bodyTokens = tokensOf(item.excerpt)
        val weights = HashMap<String, Double>()
        titleTokens.forEach { token -> weights[token] = (weights[token] ?: 0.0) + TITLE_WEIGHT }
        bodyTokens.forEach { token -> weights[token] = (weights[token] ?: 0.0) + BODY_WEIGHT }
        // 词频封顶：一条会话反复说同一个词不代表它更属于这个库。
        weights.replaceAll { _, value -> value.coerceAtMost(MAX_TOKEN_WEIGHT) }
        return Vector(item.id, weights)
    }

    private fun centroidOf(cluster: List<Vector>): Vector {
        if (cluster.isEmpty()) return Vector(Uuid.random(), emptyMap())
        val weights = HashMap<String, Double>()
        cluster.forEach { vector ->
            vector.weights.forEach { (token, value) -> weights[token] = (weights[token] ?: 0.0) + value }
        }
        return Vector(cluster.first().conversationId, weights)
    }

    private fun sharedTokens(cluster: List<Vector>): List<String> {
        if (cluster.size < 2) return emptyList()
        val counts = HashMap<String, Int>()
        cluster.forEach { vector -> vector.weights.keys.forEach { token -> counts[token] = (counts[token] ?: 0) + 1 } }
        val threshold = (cluster.size + 1) / 2
        return counts.entries
            .filter { it.value >= threshold }
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { it.key.length }
                    .thenBy { it.key },
            )
            .take(2)
            .map { it.key }
    }

    /** 加权余弦相似度，输出 [0, 1]；任一侧为空算 0。 */
    private fun similarity(left: Vector, right: Vector): Double {
        if (left.weights.isEmpty() || right.weights.isEmpty()) return 0.0
        val (small, large) = if (left.weights.size <= right.weights.size) {
            left.weights to right.weights
        } else {
            right.weights to left.weights
        }
        var dot = 0.0
        small.forEach { (token, value) -> large[token]?.let { dot += value * it } }
        if (dot <= 0.0) return 0.0
        val leftNorm = sqrt(left.weights.values.sumOf { it * it })
        val rightNorm = sqrt(right.weights.values.sumOf { it * it })
        if (leftNorm <= 0.0 || rightNorm <= 0.0) return 0.0
        return (dot / (leftNorm * rightNorm)).coerceIn(0.0, 1.0)
    }

    private fun confidenceForExistingFolder(score: Double): Double {
        val span = (EXISTING_FOLDER_GOOD_SCORE - EXISTING_FOLDER_MIN_SCORE).coerceAtLeast(0.01)
        val ratio = ((score - EXISTING_FOLDER_MIN_SCORE) / span).coerceIn(0.0, 1.0)
        return round2(MIN_LOCAL_CONFIDENCE + ratio * (MAX_CONFIDENCE - MIN_LOCAL_CONFIDENCE))
    }

    private fun confidenceForNewCluster(size: Int): Double {
        // 3 条 → 0.86，每多一条 +0.02，封顶 MAX_CONFIDENCE：簇越大越可信，但仍然不等于「正确」。
        val extra = (size - MIN_CLUSTER_SIZE).coerceAtLeast(0)
        return round2((MIN_LOCAL_CONFIDENCE + 0.01 + extra * 0.02).coerceAtMost(MAX_CONFIDENCE))
    }

    private fun round2(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

    private fun formatScore(value: Double): String = "${(round2(value) * 100).toInt()}%"

    companion object {
        /** 单簇至少这么多条才建议新建库：比 AI 模式的「3 个不同话题」更保守（条数 ≥ 话题数）。 */
        const val MIN_CLUSTER_SIZE = 3

        /** 复用已有库的最小相似度：低于这个宁可不动。 */
        const val EXISTING_FOLDER_MIN_SCORE = 0.30

        /** 「明显像」的相似度，用来把分数映射到置信度上限。 */
        const val EXISTING_FOLDER_GOOD_SCORE = 0.75

        /** 本地模式给出的最低置信度，必须 ≥ Planner 的采纳门槛 0.85。 */
        const val MIN_LOCAL_CONFIDENCE = 0.85

        /** 本地模式**不会**超过这个置信度：规则匹配不等于正确，不冒充 0.99。 */
        const val MAX_CONFIDENCE = 0.94

        /** 新簇成员的相似度门槛。 */
        const val CLUSTER_MIN_SCORE = 0.45

        private const val FOLDER_NAME_WEIGHT = 3.0
        private const val TITLE_WEIGHT = 2.0
        private const val BODY_WEIGHT = 1.0
        private const val MAX_TOKEN_WEIGHT = 6.0
        private const val EXPLAIN_TOKENS = 3
        private const val MAX_TOKENS = 400

        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "that", "this", "you", "your", "are", "was", "have", "has",
            "https", "http", "www", "com", "一个", "我们", "你们", "什么", "怎么", "可以", "帮我",
            "一下", "这个", "那个", "然后", "就是", "还是", "需要", "现在", "问题",
        )

        internal fun tokensOf(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            val tokens = ArrayList<String>()
            val lower = text.lowercase()
            // 显式标签优先：#tag 是用户写下的分类意图，比正文词频可靠。
            Regex("#([\\p{L}\\p{N}_-]{1,24})").findAll(lower).forEach { match ->
                val tag = match.groupValues[1].trim('-')
                if (tag.isNotEmpty()) {
                    tokens += tag
                    tokens += "#$tag"
                }
            }
            val cleaned = lower.replace(Regex("#[\\p{L}\\p{N}_-]{1,24}"), " ")
            // 拉丁词/数字
            Regex("[a-z0-9_]{2,24}").findAll(cleaned).forEach { match ->
                val word = match.value
                if (word !in STOP_WORDS) tokens += word
            }
            // 中文二元组（单字信息量太低，容易把不相干的会话聚到一起）
            Regex("[\\u4e00-\\u9fff]{2,}").findAll(cleaned).forEach { match ->
                val run = match.value
                var index = 0
                while (index + 1 < run.length) {
                    val bigram = run.substring(index, index + 2)
                    if (bigram !in STOP_WORDS) tokens += bigram
                    index += 1
                }
            }
            return if (tokens.size > MAX_TOKENS) tokens.take(MAX_TOKENS) else tokens
        }
    }
}

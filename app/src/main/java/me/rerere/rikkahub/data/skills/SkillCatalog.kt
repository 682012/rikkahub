package me.rerere.rikkahub.data.skills

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 技能目录与元数据的缓存层。
 *
 * 为什么需要：每一轮对话建工具时都会调用一次 `listSkills()`（见 ChatToolFactory.createTools），
 * 旧实现每次都把每个技能的 SKILL.md 整份 readText 再解析 frontmatter——技能越多、正文越长，
 * 每轮要做的磁盘读取就越多，而这些结果几乎永远不变。这里缓存"目录 → 元数据"，并把
 * frontmatter 改成只读文件开头一段（见 [SkillFrontmatterParser.parseFile]）。
 *
 * 缓存正确性边界（不能只靠时间戳猜）：
 * - 命中条件是 SKILL.md 的长度和最后修改时间。用户在 App 外（workspace 的 `/skills`、
 *   文件管理器）改了技能，属性变化即失效，读回新内容；
 * - App 内的保存 / 删除 / 写附属文件都会显式调用 [invalidate]，不依赖文件系统时间戳精度；
 * - 只缓存解析成功的结果。缺 SKILL.md、缺 name/description 的目录每次重新判断，
 *   用户补全文件后立刻出现在技能列表里，不会因为缓存而"看不见"。
 *
 * 隔离：缓存键是技能目录的绝对路径，用户技能根目录和内置技能根目录即使有同名子目录也各存各的；
 * 缓存内容只有目录元数据，不含任何助手的启用状态，因此一个助手的选择不会带到另一个助手那里
 * （按助手过滤发生在 createSkillTools 里，是纯函数）。
 *
 * 纯 java.io 实现：可脱离 Android 在 JVM 单元测试里直接验证失效行为（SkillCatalogTest）。
 */
internal class SkillCatalog(
    private val userSkillsDir: () -> File,
    private val builtinSkillsDir: () -> File,
    /** 只为测试留的接缝：默认真的去读文件，测试里可以数读取次数 */
    private val readFrontmatter: (File) -> SkillFrontmatter = { file ->
        SkillFrontmatterParser.parseFile(file)
    },
) {
    private data class Stamp(val length: Long, val lastModified: Long)

    private class Cached(val stamp: Stamp, val metadata: SkillMetadata)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** 所有可用技能：用户技能 + 内置技能，同名时用户技能覆盖内置技能；顺序按目录名稳定 */
    fun list(): List<SkillMetadata> = mergeWithBuiltinSkills(
        local = listIn(userSkillsDir(), builtin = false),
        builtin = listIn(builtinSkillsDir(), builtin = true),
    )

    fun find(name: String): SkillMetadata? = list().firstOrNull { it.name == name }

    /**
     * 用户技能目录下无法识别为技能的目录，如空目录、缺少 SKILL.md 或 frontmatter 不完整。
     * 这些目录不会出现在 [list] 中，单独列出来让用户清理。
     */
    fun listInvalid(): List<InvalidSkillDir> = skillDirsIn(userSkillsDir())
        .mapNotNull { dir ->
            val reason = when (val error = parseDir(dir, builtin = false).exceptionOrNull()) {
                null -> return@mapNotNull null
                is InvalidSkillException -> error.reason
                else -> InvalidSkillReason.ReadFailed
            }
            InvalidSkillDir(dirName = dir.name, reason = reason)
        }
        .sortedBy { it.dirName }

    fun parseDir(skillDir: File, builtin: Boolean): Result<SkillMetadata> = runCatching {
        val skillFile = skillDir.resolve(SKILL_MD)
        if (!skillFile.isFile) throw InvalidSkillException(InvalidSkillReason.MissingSkillMd)
        val key = keyOf(skillDir)
        val stamp = Stamp(length = skillFile.length(), lastModified = skillFile.lastModified())
        // 长度和时间戳都没变就复用上次的解析结果：这是 listSkills() 从"每轮读全部正文"变成"每轮只 stat"的关键
        cache[key]?.let { cached -> if (cached.stamp == stamp) return@runCatching cached.metadata }

        val frontmatter = readFrontmatter(skillFile)
        val name = frontmatter["name"]?.takeIf { it.isNotBlank() }
            ?: throw InvalidSkillException(InvalidSkillReason.MissingName)
        val description = frontmatter["description"]?.takeIf { it.isNotBlank() }
            ?: throw InvalidSkillException(InvalidSkillReason.MissingDescription)
        val metadata = SkillMetadata(
            name = name,
            description = description,
            compatibility = frontmatter["compatibility"],
            skillDir = skillDir,
            builtin = builtin,
        )
        cache[key] = Cached(stamp, metadata)
        metadata
    }.onFailure {
        // 解析失败不缓存：目录可能在列表展示期间被补全成有效技能
        cache.remove(keyOf(skillDir))
    }

    /** 全部失效。App 内任何改动技能内容的操作之后都调用它，不看时间戳精度 */
    fun invalidate() {
        cache.clear()
    }

    /** 只失效一个技能目录 */
    fun invalidate(skillDir: File) {
        cache.remove(keyOf(skillDir))
    }

    fun cachedEntryCount(): Int = cache.size

    private fun listIn(root: File, builtin: Boolean): List<SkillMetadata> =
        skillDirsIn(root)
            .mapNotNull { parseDir(it, builtin).getOrNull() }
            // 目录顺序由文件系统决定，排序后列表和提示词才有稳定的先后
            .sortedBy { it.skillDir.name }

    // 跳过隐藏目录，如原子写入残留的 .<name>.staging.N.tmp
    private fun skillDirsIn(root: File): List<File> =
        root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            .orEmpty()
            .sortedBy { it.name }

    // 绝对路径不做 canonicalFile：那是一次额外的 IO，而"哪个根目录下的哪个子目录"用绝对路径已经唯一
    private fun keyOf(skillDir: File): String = skillDir.absolutePath

    companion object {
        const val SKILL_MD = "SKILL.md"
    }
}

private class InvalidSkillException(val reason: InvalidSkillReason) : Exception(reason.name)

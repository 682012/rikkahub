package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceExecutionBackend
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.effectiveExecutionBackend
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 * 提示中包含实际使用的 shell 与发行版, 避免模型默认按 bash / apt 来写命令。
 * 本地工作区同时读取 ~/.agents、/workspace 和会话当前目录中的 AGENTS.md 作为工作区指令:
 * 内容相同的只注入一次, 合计有上限, 并按 (工作区, 工作目录, 文件 stamp) 缓存 (见 [AgentsPromptCache])。
 * 远程工作区不读取 AGENTS.md, shell 与发行版读到一次后缓存 (见 [RemoteEnvironmentCache]),
 * 连不上时只注入一段说明, 不阻塞对话。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    private val remoteEnvironments = RemoteEnvironmentCache()
    private val agentsPrompts = AgentsPromptCache()
    private val localDistros = LastKnownDistroCache()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        // 与 ChatToolFactory.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages
        val layout = workspaceRepository.layout(workspaceId) ?: return messages

        val prompt = if (workspace.isSsh()) {
            val environment = remoteEnvironments.get(workspaceId, workspace.sshConfig) {
                // shell 探测需要连接, 失败时不再读发行版, 以免再等一次超时
                RemoteEnvironment(shell = workspaceRepository.shell(workspaceId), distro = readDistroName(workspaceId))
            }.getOrElse { e ->
                Log.w("WorkspaceReminder", "Workspace unreachable: $workspaceId", e)
                // 提示文本只用工作区自身的固定信息: 错误串每次都可能不同, 拼进去会让系统提示每步都变
                return messages.withPrompt(buildUnreachablePrompt(workspace, layout))
            }
            buildSshWorkspacePrompt(workspace, layout, environment.shell, environment.distro, ctx.workspaceCwd)
        } else {
            // HTTP 远程执行时只给 shell 与 job 工具（WorkspaceExecutionPolicy.isToolAvailableOnBackend），
            // 规范里不能提读文件、搜索工具
            val backend = effectiveExecutionBackend(workspace.isSsh(), ctx.settings.workspaceRuntime)
            val shell = workspaceRepository.shell(workspaceId)
            val environmentKey = "$workspaceId|$backend|$shell"
            val distro = localDistros.cached(environmentKey)
                ?: localDistros.resolve(environmentKey, readDistroName(workspaceId))
            buildWorkspacePrompt(
                workspace = workspace,
                shell = shell,
                distro = distro,
                cwd = ctx.workspaceCwd,
                guidance = if (backend == WorkspaceExecutionBackend.REMOTE_HTTP) ToolUseGuidance.SHELL_ONLY else ToolUseGuidance.LOCAL,
            ) + buildAgentsPrompt(workspaceId, ctx.workspaceCwd)
        }
        return messages.withPrompt(prompt)
    }

    // 追加到第一条 system 消息; 若不存在则插入一条
    private fun List<UIMessage>.withPrompt(prompt: String): List<UIMessage> {
        val systemIndex = indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + this
        }
    }

    private suspend fun buildAgentsPrompt(workspaceId: String, cwd: String?): String {
        // ProotShellRunner 将 HOME 固定为 /root；相对 PWD 按 /workspace 解析。
        val workingDirectory = Paths.get(WorkspaceManager.ROOTFS_WORKSPACE_DIR)
            .resolve(cwd?.takeIf { it.isNotBlank() } ?: ".")
            .normalize()
        val paths = linkedSetOf(
            "${WorkspaceManager.ROOTFS_HOME_DIR}/.agents/AGENTS.md",
            "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/AGENTS.md",
            workingDirectory.resolve("AGENTS.md").toString(),
        )
        // 每个 step 都会重拼系统提示, 而 AGENTS.md 通常很多步都不变:
        // 按 (workspaceId, 规范化后的工作目录, 文件 mtime/size) 复用上一次的结果, 产出才能逐字节相同
        return agentsPrompts.get(
            workspaceId = workspaceId,
            workingDirectory = workingDirectory.toString(),
            stamps = { agentsFileStamps(workspaceId, paths) },
            load = { readAgentsInstructions(workspaceId, paths) },
        )
    }

    /** 逐个文件取 mtime 与 size; 拿不到 mtime (没有本地落点) 时返回 null, 这次就不缓存 */
    private suspend fun agentsFileStamps(
        workspaceId: String,
        paths: Set<String>,
    ): List<AgentsFileStamp>? = paths.map { path ->
        val file = workspaceRepository.resolveAbsoluteFile(workspaceId, path)
        if (file == null) {
            // 文件不存在: 记成"不存在", 之后被创建时 stamp 会变
            AgentsFileStamp(path, modifiedAt = -1, size = -1)
        } else {
            val modifiedAt = file.lastModified()
            if (modifiedAt <= 0) return null
            AgentsFileStamp(path, modifiedAt = modifiedAt, size = file.length())
        }
    }

    private suspend fun readAgentsInstructions(workspaceId: String, paths: Set<String>): String {
        val sources = paths.mapNotNull { path ->
            readRootfsText(workspaceId, path, MAX_AGENTS_BYTES)?.let { AgentsSource(path, it) }
        }
        return buildAgentsInstructions(sources)
    }

    private suspend fun readDistroName(workspaceId: String): String? =
        OS_RELEASE_PATHS.firstNotNullOfOrNull { path ->
            readRootfsText(workspaceId, path, MAX_OS_RELEASE_BYTES)?.let(::parseOsReleaseName)
        }

    // 文件不存在、过大或不可读时返回 null, 不影响提示词的其余部分
    private suspend fun readRootfsText(workspaceId: String, path: String, maxBytes: Long): String? =
        try {
            val size = workspaceRepository.absoluteFileSize(workspaceId, path)
            require(size <= maxBytes) { "$path exceeds $maxBytes bytes" }
            val content = ByteArrayOutputStream().use { output ->
                workspaceRepository.exportAbsoluteFile(workspaceId, path, output)
                output.toString(Charsets.UTF_8.name())
            }
            content.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("WorkspaceReminder", "Skipping workspace file: $path", e)
            null
        }

    private companion object {
        const val MAX_AGENTS_BYTES = 64L * 1024
        const val MAX_OS_RELEASE_BYTES = 16L * 1024
        val OS_RELEASE_PATHS = listOf("/etc/os-release", "/usr/lib/os-release")
    }
}

/** 远程工作区提示词里描述的执行环境 */
internal data class RemoteEnvironment(val shell: String, val distro: String?)

/** 一个 AGENTS.md 的本地状态; [modifiedAt] 为负表示文件不存在, 不缓存时不会被用到 */
internal data class AgentsFileStamp(val path: String, val modifiedAt: Long, val size: Long)

/** 读到的一个 AGENTS.md 来源, [content] 是文件原文 */
internal data class AgentsSource(val path: String, val content: String)

/** 所有 AGENTS.md 合计注入的上限; 三份各 64KB 全注入会把每一步的提示撑到 100KB 以上 */
internal const val MAX_AGENTS_TOTAL_CHARS = 64 * 1024

/**
 * 把读到的 AGENTS.md 拼成注入块。
 *
 * - 同一份内容出现在多个位置(软链、复制出来的工作区模板)时只注入一次, 避免同一段规则重复占上下文;
 * - 合计超过 [MAX_AGENTS_TOTAL_CHARS] 时按顺序截断, 并写明可以用 workspace_read_file 读剩下的部分,
 *   不静默丢掉用户写的内容。
 */
internal fun buildAgentsInstructions(sources: List<AgentsSource>): String {
    val blocks = mutableListOf<Pair<String, String>>()
    val skipped = mutableListOf<String>()
    val seenContents = HashSet<String>()
    var used = 0
    for (source in sources) {
        val content = source.content.trim()
        if (content.isEmpty()) continue
        if (!seenContents.add(content)) continue
        val remaining = MAX_AGENTS_TOTAL_CHARS - used
        if (remaining <= 0) {
            skipped.add(source.path)
            continue
        }
        val head = if (content.length <= remaining) content else content.take(remaining)
        // 截断时退到最后一个完整行, 不留半行
        val included = if (head.length == content.length) head else head.substringBeforeLast('\n', head)
        used += included.length
        blocks.add(
            source.path to buildString {
                append(included)
                if (included.length < content.length) {
                    appendLine()
                    append(
                        "[truncated at $MAX_AGENTS_TOTAL_CHARS characters; read the rest with " +
                            "workspace_read_file(path=\"${source.path}\")]"
                    )
                }
            }
        )
    }
    if (blocks.isEmpty()) return ""
    return buildString {
        appendLine()
        appendLine()
        appendLine("<workspace_instructions>")
        appendLine("Follow the AGENTS.md instructions below.")
        blocks.forEach { (path, text) ->
            appendLine()
            appendLine("AGENTS.md source: $path")
            appendLine(text)
        }
        if (skipped.isNotEmpty()) {
            appendLine()
            appendLine(
                "Not included (size budget): ${skipped.joinToString(", ")}. " +
                    "Read them with workspace_read_file if the task needs them."
            )
        }
        append("</workspace_instructions>")
    }
}

/**
 * 按 (workspaceId, 规范化后的工作目录, 文件 mtime/size) 缓存 AGENTS.md 提示词。
 *
 * 每个 step 都要重建系统提示, 而 AGENTS.md 往往几十步都不变。stamp 没变就复用上一次拼好的字符串,
 * 一是省掉每个 step 的读文件, 二是保证产出逐字节相同 —— 读失败时这一段也不会突然从提示里消失,
 * 否则系统提示一变, 后面的全部历史前缀缓存都失效。
 *
 * mtime/size 单独不足: mtime 粒度粗(1 秒)、内容被原样改写但长度不变、或是编辑后又被改回来,
 * 都可能让 stamp 不变而内容已经变了。所以条目过了 [revalidateMillis] 会重读一次并逐字节比较,
 * 用读到的内容本身确认, 不靠 stamp 推断; 内容真的变了就换掉, 没变才继续复用。
 * 判断不了新旧(stamps 为 null)时丢掉旧条目, 不拿可能过时的内容顶替。
 */
internal class AgentsPromptCache(
    private val revalidateMillis: Long = DEFAULT_REVALIDATE_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Entry(
        val stamps: List<AgentsFileStamp>,
        val prompt: String,
        val validatedAt: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * [stamps] 返回 null 表示这次判断不了新旧 (例如没有本地落点), 此时不读缓存也不算缓存, 直接 [load]。
     */
    suspend fun get(
        workspaceId: String,
        workingDirectory: String,
        stamps: suspend () -> List<AgentsFileStamp>?,
        load: suspend () -> String,
    ): String {
        val key = "$workspaceId\n$workingDirectory"
        val current = stamps()
        val cached = entries[key]
        if (current != null && cached != null && cached.stamps == current) {
            if (now() - cached.validatedAt < revalidateMillis) return cached.prompt
            // 到点复核: 重新读一次, 内容没变就继续用(逐字节相同), 变了就换成新的
            val prompt = load()
            put(key, Entry(current, prompt, now()))
            return prompt
        }
        val prompt = load()
        if (current == null) {
            entries.remove(key)
        } else {
            put(key, Entry(current, prompt, now()))
        }
        return prompt
    }

    // 不同工作区/目录的条目不能无限堆积: 超过上限时先扔最久没有校验过的
    private fun put(key: String, entry: Entry) {
        entries[key] = entry
        val overflow = entries.size - maxEntries
        if (overflow <= 0) return
        entries.entries
            .filter { it.key != key }
            .sortedBy { it.value.validatedAt }
            .take(overflow)
            .forEach { entries.remove(it.key) }
    }

    private companion object {
        const val DEFAULT_REVALIDATE_MS = 30_000L
        const val DEFAULT_MAX_ENTRIES = 16
    }
}

/**
 * 按工作区缓存远程执行环境。
 *
 * 读一次要好几次网络往返, 而每一步请求都要构建提示词; 远程环境很少变化, 读到后就一直沿用, 直到连接配置改变。
 * 连不上的结果只保留 [failureTtlMillis]: 期间的后续步骤不必每步都等连接超时, 过期后再重新尝试。
 */
internal class RemoteEnvironmentCache(
    private val failureTtlMillis: Long = DEFAULT_FAILURE_TTL_MS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Entry(val config: String?, val result: Result<RemoteEnvironment>, val loadedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun get(
        workspaceId: String,
        config: String?,
        load: suspend () -> RemoteEnvironment,
    ): Result<RemoteEnvironment> {
        entries[workspaceId]
            ?.takeIf { it.config == config && (it.result.isSuccess || now() - it.loadedAt < failureTtlMillis) }
            ?.let { return it.result }
        val result = try {
            Result.success(load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        entries[workspaceId] = Entry(config, result, now())
        return result
    }

    private companion object {
        const val DEFAULT_FAILURE_TTL_MS = 60_000L
    }
}

private const val MAX_DISTRO_NAME_LENGTH = 80

/** 从 os-release 内容中取发行版名称, 优先 PRETTY_NAME, 其次 NAME + VERSION_ID */
internal fun parseOsReleaseName(content: String): String? {
    val values = content.lineSequence()
        .map { it.trim() }
        .filter { !it.startsWith("#") && it.indexOf('=') > 0 }
        .associate { line ->
            line.substringBefore('=') to line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
        }
    val name = values["PRETTY_NAME"]?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(values["NAME"], values["VERSION_ID"]).joinToString(" ")
    // 内容来自 Rootfs 内的文件, 拼进系统提示前去掉控制字符并限制长度
    return name.filterNot { it.isISOControl() }.trim().take(MAX_DISTRO_NAME_LENGTH).ifBlank { null }
}

private fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    shell: String,
    distro: String?,
    cwd: String? = null,
    guidance: ToolUseGuidance = ToolUseGuidance.LOCAL,
): String = buildString {
    appendLine("<workspace>")
    appendLine(
        "You have access to a persistent Linux workspace named \"${workspace.name}\", running in a sandboxed proot rootfs environment."
    )
    appendLine(
        "- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns of this conversation."
    )
    appendLine(
        "- All paths passed to workspace tools must be absolute and inside the Rootfs (for example `/workspace/notes.md`)."
    )
    if (distro != null) {
        appendLine(
            "- The Rootfs distribution is $distro. Use its native package manager when you need to install missing tools."
        )
    }
    appendShellLine(shell)
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents.")
    appendLine(
        "  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files."
    )
    appendLine("  - `workspace_shell`: run shell commands (the files area is mounted at /workspace).")
    appendLine(
        "  - `workspace_shell_output` / `workspace_shell_kill`: read the output of, or stop, a command started with `run_in_background`."
    )
    appendLine(
        "- Start long-running commands such as dev servers, watchers or long builds with `run_in_background` instead of appending `&`: processes left running with `&` are killed when the shell call returns. When a background command finishes you are notified with its exit code and the last lines of its output, so do not poll it; keep working on anything that does not depend on it, or end your reply if there is nothing else to do."
    )
    appendLine(
        "- Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file` for targeted edits over rewriting whole files."
    )
    appendLine(
        "- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions."
    )
    appendLine(
        "- Built-in skills shipped with the app are mounted at `/builtin_skills/<skill-name>/` with the same layout. Treat `/builtin_skills` as READ-ONLY: you may read files and run scripts there, but never modify, overwrite, or delete anything. A skill in `/skills` with the same name overrides the built-in one."
    )
    appendLine(
        "- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: read uploaded files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. If you need to change an uploaded file, copy it into `/workspace` first and edit the copy."
    )
    // 静态段末尾: 固定不变的工具使用规范, 放在易变的 cwd 之前
    appendToolUseGuidance(guidance)
    if (!cwd.isNullOrBlank()) {
        appendLine(
            "- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands."
        )
    }
    append("</workspace>")
}

private fun buildSshWorkspacePrompt(
    workspace: WorkspaceEntity,
    layout: WorkspaceLayout,
    shell: String,
    distro: String?,
    cwd: String?,
): String = buildString {
    val address = workspace.sshSettings()?.let { "${it.username}@${it.host}" } ?: "the remote host"
    appendLine("<workspace>")
    appendLine(
        "You have access to a persistent remote workspace named \"${workspace.name}\" on a real machine, reached over SSH as `$address`."
    )
    appendLine(
        "- The workspace directory is `${layout.workspaceDir}`. Use it as your working directory; files written there persist across turns of this conversation."
    )
    appendLine(
        "- All paths passed to workspace tools must be absolute paths on the remote machine (for example `${layout.workspaceDir}/notes.md`)."
    )
    appendLine(
        "- This is not a sandbox: commands run with the SSH user's real permissions and can affect other data and services on the machine. Avoid destructive or system-wide changes unless the user asks for them."
    )
    if (distro != null) {
        appendLine("- The remote operating system is $distro.")
    }
    appendShellLine(shell)
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents.")
    appendLine("  - `workspace_shell`: run shell commands on the remote machine (defaults to the workspace directory).")
    appendLine(
        "- There are no file write or edit tools in this workspace. Create and modify files with `workspace_shell`, for example a quoted heredoc (`cat > file <<'EOF'`) for new content, or `sed`, `patch` or a short script for targeted edits. Prefer targeted edits over rewriting whole files."
    )
    // 静态段末尾: 固定不变的工具使用规范, 放在易变的 cwd 之前。
    // SSH 后端没有 workspace_grep / workspace_glob, 这一版只提 shell 里的 grep/find
    appendToolUseGuidance(ToolUseGuidance.SSH)
    if (!cwd.isNullOrBlank()) {
        appendLine(
            "- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands."
        )
    }
    append("</workspace>")
}

private fun buildUnreachablePrompt(workspace: WorkspaceEntity, layout: WorkspaceLayout): String =
    buildString {
        val address = workspace.sshSettings()?.let { "${it.username}@${it.host}" }
        appendLine("<workspace>")
        appendLine(
            "You are bound to a workspace named \"${workspace.name}\"" +
                (address?.let { " on `$it`" } ?: "") +
                " with directory `${layout.workspaceDir}`, but it is currently unreachable."
        )
        appendLine(
            "- Workspace tools will fail until the connection is restored. If the user asks you to work in the workspace, tell them it is unreachable."
        )
        append("</workspace>")
    }

/** 工作区实际提供的读/搜工具，决定工具使用规范里怎么写 */
internal enum class ToolUseGuidance {
    /** 本地 Rootfs：workspace_read_file（offset/limit）、workspace_grep、workspace_glob */
    LOCAL,

    /** SSH：workspace_read_file 走 SFTP（offset/limit），没有 grep / glob */
    SSH,

    /** HTTP 远程执行：只有 workspace_shell 与 job 工具 */
    SHELL_ONLY,
}

/**
 * 工具使用规范: 内容只取决于工作区实际提供的工具, 同一个工作区里逐字节相同, 才能落在稳定的前缀里。
 * [guidance] 与本仓库 workspace_* 工具的后端判断一致, 不能提这个后端没有的工具。
 */
internal fun StringBuilder.appendToolUseGuidance(guidance: ToolUseGuidance) {
    appendLine()
    appendLine("Tool use rules:")
    appendLine(
        "- Send every independent tool call in the same reply so they run in parallel; only wait for a result when the next call needs it."
    )
    when (guidance) {
        ToolUseGuidance.LOCAL -> appendLine(
            "- Locate with `workspace_grep` / `workspace_glob` first, then read only what you need with `offset` / `limit` on `workspace_read_file`."
        )
        ToolUseGuidance.SSH -> appendLine(
            "- Search with `workspace_shell` (grep -n, find) before reading, then read only the lines you need with `offset` / `limit`."
        )
        ToolUseGuidance.SHELL_ONLY -> appendLine(
            "- Files are only reachable through `workspace_shell`: search with grep -n or find, then print just the line range you need (for example sed -n '120,200p') instead of whole files."
        )
    }
    appendLine(
        "- If the workspace already has a script, README or skill for the task, use that entry point instead of rebuilding it."
    )
    appendLine("- Never read a file again when its content is already in this conversation and you have not changed it.")
    appendLine("- When a call fails, read the error and change the approach; never repeat an identical call.")
}

private fun StringBuilder.appendShellLine(shell: String) {
    if (shell.substringAfterLast('/') == "bash") {
        appendLine("- `workspace_shell` runs commands with `$shell`.")
    } else {
        appendLine(
            "- `workspace_shell` runs commands with `$shell`, a POSIX shell. Bash is not installed, so avoid bash-only syntax such as arrays, here-strings (`<<<`) and brace expansion."
        )
    }
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}

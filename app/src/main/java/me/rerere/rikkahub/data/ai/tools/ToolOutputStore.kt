package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * 大工具输出外置 + 分页读回。
 *
 * 解决的是「一次 `workspace_shell` dump 把上下文撑爆」这个老问题，但和旧的
 * ChatToolFactory.truncateOutput 有两点本质区别：
 *
 *  1. **读得回来**：全文落到 `baseDir/<uuid>.txt`，同时给模型一个稳定的机器可读回执
 *     （`output_id` / `totalChars` / `path`），任何后端都能用 [TOOL_OUTPUT_READ_TOOL_NAME]
 *     按字符偏移分页读回全文——LOCAL 能 `cat`、HTTP 远程与 SSH 读不到本机文件、
 *     没有 workspace 时更是只能靠这个读工具。路径依旧按 `/tool_outputs/<uuid>.txt` 输出，
 *     与 RepositoryModule 挂给 PRoot 的 `/tool_outputs` 保持一致。
 *  2. **留得下来**：每个输出旁边有一份 `<uuid>.json` manifest（记录 owner / 工具名 / 总字符数），
 *     进程重启、换一轮请求、同一 conversation 过几分钟再回来都还能读到；反过来，别的
 *     conversation 拿着别人的 id 也读不到（owner 不匹配即拒）。
 *     保留期由 [pruneToolOutputs] 决定：**按天与按量**清理（默认保留
 *     [TOOL_OUTPUT_RETENTION_DAYS] 天、最多 [TOOL_OUTPUT_MAX_FILES] 个文件 /
 *     [TOOL_OUTPUT_MAX_TOTAL_BYTES] 字节），冷启动不再整目录删。
 *
 * **为什么现在无条件外置（不再看后端、不再看子代理手里有没有 workspace 工具）**：
 * 读工具 [TOOL_OUTPUT_READ_TOOL_NAME] 读的是 app 自己的 `filesDir`，不走 shell、不依赖 PRoot
 * 挂载，所以 LOCAL_ROOTFS / REMOTE_HTTP / SSH / 完全没有 workspace 四种情况下都能读回。
 * fork.36 时代那套「只有 LOCAL_ROOTFS 且子代理拿到了 workspace_shell 才外置」的门槛
 * （`canReadToolOutputs` / `shouldTruncateChildToolOutput`）因此完全没必要，已经在 fork.42 删掉；
 * **不要**再按后端或工具名把外置关掉，否则大 dump 会重新撑爆上下文。
 *
 * 集成方式（主代理负责改 ChatToolFactory）：
 * ```kotlin
 * val store = ToolOutputStore(File(context.filesDir, FileFolders.TOOL_OUTPUTS), toolOutputOwnerOf(conversationId))
 * val tools = baseTools.map(store::wrap) + store.createReaderTool()
 * ```
 * 关键约定：
 *  - 只有「所有 Text part 合计字符数 > [TOOL_OUTPUT_OFFLOAD_THRESHOLD_CHARS]」才外置，
 *    小输出原样返回（同一个 List 实例，行为完全不变）；
 *  - 外置后 Text part 被换成「回执 + 头尾预览」的单条 Text，**所有非 Text part 原样保留**；
 *  - 写盘失败（目录不可写、磁盘满、rename 失败）时原样返回未裁剪的输出，绝不静默丢内容；
 *  - [wrap] 不会包装读工具自身（否则读页结果会被再外置，形成越读越乱的循环）；
 *    预览总量约 8K，远低于阈值，重复包装也是幂等的空操作。
 *  - 读取只走磁盘 manifest，不依赖任何内存列表，所以「同一 conversation 恢复可读」是天然成立的。
 *  - **读一页只读一页**：按 offset 在解码流上跳过字符再读 `limit` 个，内存开销与页大小成正比，
 *    和文件多大无关（旧实现每读一页都把整个文件 `readText()` 一遍，10MB 输出能直接 OOM）。
 *  - `search_web` 的结果体积由用户设置的结果数决定，且 UI 与引用跳转依赖完整 JSON，这里保留豁免。
 *
 * 并发提示：本文件不持有可变共享状态，读工具是纯读（同一入参同一结果），
 * `ToolExecutionMode` 落地后可以标 `PARALLEL_DEDUPLICATED`；[wrap] 出来的工具继承内层工具的语义，
 * 应保持默认 SERIAL。
 */

/** 读回外置输出全文的工具名。 */
internal const val TOOL_OUTPUT_READ_TOOL_NAME = "tool_output_read"

/** 外置阈值：所有 Text part 合计超过这个字符数才外置。 */
internal const val TOOL_OUTPUT_OFFLOAD_THRESHOLD_CHARS = 16 * 1024

/** 预览长度：头、尾各这么多字符（合计约 8K，一定小于阈值，所以预览不会被再次外置）。 */
internal const val TOOL_OUTPUT_PREVIEW_CHARS = 2 * 1024

/** 读工具单页默认字符数。 */
internal const val TOOL_OUTPUT_READ_DEFAULT_LIMIT = 4_000

/** 读工具单页上限；单页足够大，不需要也不允许靠拼页绕过。 */
internal const val TOOL_OUTPUT_READ_MAX_LIMIT = 12_000

/** 外置输出的保留天数：冷启动按它清理，超期的输出读回时会被明确告知「已清理」。 */
internal const val TOOL_OUTPUT_RETENTION_DAYS = 7

/** 外置输出最多保留多少个（manifest 数），超过就删最旧的。 */
internal const val TOOL_OUTPUT_MAX_FILES = 64

/** 外置输出目录总量硬上限，超过就删最旧的，避免大 dump 把存储吃满。 */
internal const val TOOL_OUTPUT_MAX_TOTAL_BYTES = 32L * 1024 * 1024

/** 孤儿文件（没有 manifest 的 `.txt`、写盘中断留下的 `.tmp`）多久之后才敢删，避免和正在写的请求抢。 */
private const val ORPHAN_GRACE_MS = 3_600_000L

/**
 * 不参与外置的工具。
 *
 * `search_web` 返回的是结构化搜索结果：体积由用户设置的结果数决定，UI 渲染与引用跳转都依赖
 * 完整 JSON，裁了就是坏数据。这里是**唯一**一道豁免（fork.42 已删掉 ChatToolFactory 里那份重复名单，
 * 别再去那边找第二道闸）。
 */
internal val TOOL_OUTPUT_OFFLOAD_EXEMPT_TOOLS = setOf("search_web")

/** manifest 结构版本，将来加字段时靠它判断兼容性。 */
private const val TOOL_OUTPUT_MANIFEST_VERSION = 1

/** manifest 文件后缀；`.txt` 就是同名的全文。 */
private const val MANIFEST_SUFFIX = ".json"

/** 全文文件后缀。 */
private const val TEXT_SUFFIX = ".txt"

/** 严格 UUID：任何带 `/`、`\`、`..` 的 id 都不可能匹配，路径穿越在校验层就被挡掉。 */
private val UUID_PATTERN = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)

/**
 * 外置输出的归属标识（owner）。
 *
 * **只按会话**，不含 `assistant.id`：聊天页可以中途换助手（助手被删也会回退），
 * 一旦 owner 里混进助手 id，同一个会话里的旧 `output_id` 会在换助手后立刻判成
 * 「属于另一个会话」，用户没换会话却读不到自己的输出。
 */
internal fun toolOutputOwnerOf(conversationId: Uuid?): String =
    "conversation:${conversationId ?: "unscoped"}"

/** 一次外置的磁盘回执。 */
internal data class ToolOutputRef(
    val id: String,
    val toolName: String,
    val totalChars: Int,
    /** 给模型看的路径：挂了 `/tool_outputs` 时就是挂载内路径，否则是本机绝对路径 */
    val path: String,
)

/** 一个外置输出旁边落盘的 manifest。owner 是唯一的跨会话隔离依据。 */
@Serializable
internal data class ToolOutputManifest(
    val id: String,
    val owner: String,
    val tool: String,
    val totalChars: Int,
    val createdAt: Long,
    val version: Int = TOOL_OUTPUT_MANIFEST_VERSION,
)

/**
 * 通用工具输出仓库。
 *
 * @param baseDir 全文与 manifest 的落地目录；主代理传 `filesDir/tool_outputs`（即 PRoot 的 `/tool_outputs`）
 * @param owner 归属标识，主代理传 [toolOutputOwnerOf] 的结果（只按会话）；跨 owner 一律不可读
 */
class ToolOutputStore(
    private val baseDir: File,
    owner: String,
) {
    private val scopeOwner: String = owner.trim()

    /** `ignoreUnknownKeys`：老版本写下的 manifest 多出字段时不至于整份作废。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 包装一个工具：执行结果过大时外置全文，返回「回执 + 头尾预览」。
     *
     * 保留 name / description / parameters / systemPrompt / needsApproval —— 对模型和审批流程来说
     * 这就是原来那个工具，变的只是超长输出的呈现方式。
     */
    fun wrap(tool: Tool): Tool {
        // 读工具自己绝不外置，否则读大页会被再裁一次，永远读不完。
        // 名字撞车时 ChatToolFactory 可能给它加序号（`tool_output_read_2`），前缀也要认出来
        if (isReaderToolName(tool.name)) return tool
        if (tool.name in TOOL_OUTPUT_OFFLOAD_EXEMPT_TOOLS) return tool
        val inner = tool.execute
        return tool.copy(
            execute = { input -> offloadIfNeeded(tool.name, inner(input)) }
        )
    }

    /**
     * 分页读回工具。
     *
     * 输入 `{ output_id, offset = 0, limit = 4000 }`，输出
     * `{ output_id, offset, nextOffset, totalChars, eof, tool, path, text }`。
     * 偏移与长度都以**字符**（UTF-16 code unit）计，与 manifest 里的 totalChars 同一套坐标，
     * 所以 `offset = nextOffset` 连续翻页必然拼回全文，既不丢字也不重复。
     */
    fun createReaderTool(name: String = TOOL_OUTPUT_READ_TOOL_NAME): Tool = Tool(
        name = name,
        executionMode = ToolExecutionMode.PARALLEL_DEDUPLICATED,
        description = buildString {
            append("Read back a tool output that was too large and got offloaded to disk.\n")
            append("Offloaded outputs appear as a notice with an `output_id` (a UUID) and `totalChars`; ")
            append("this tool returns the full text page by page and works on every backend ")
            append("(local workspace, remote HTTP, SSH, or no workspace at all), because it reads the app's ")
            append("own files instead of going through the shell.\n")
            append("Pass `output_id`, `offset` (characters, default 0) and `limit` (characters, default ")
            append("$TOOL_OUTPUT_READ_DEFAULT_LIMIT, max $TOOL_OUTPUT_READ_MAX_LIMIT). ")
            append("Continue with `offset = nextOffset` while `eof` is false.\n")
            append("Only outputs offloaded in the current conversation can be read, and they are kept for ")
            append("$TOOL_OUTPUT_RETENTION_DAYS days after the tool ran; a page read never loads the whole file.")
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("output_id", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "UUID printed by the offloaded tool output notice, e.g. `3f2b1c4e-...`."
                        )
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Character offset to start at, default 0. Use `nextOffset` from the previous page."
                        )
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Characters per page, default $TOOL_OUTPUT_READ_DEFAULT_LIMIT, " +
                                "max $TOOL_OUTPUT_READ_MAX_LIMIT."
                        )
                    })
                },
                required = listOf("output_id"),
            )
        },
        // 只读回本机已经存在的外置输出，没有副作用，不需要审批
        needsApproval = { false },
        execute = { input ->
            val args = input as? JsonObject ?: JsonObject(emptyMap())
            val result = withContext(Dispatchers.IO) {
                readPage(
                    outputId = args.stringOrNull("output_id")?.trim().orEmpty(),
                    offsetRaw = args.numberOrStringOrNull("offset"),
                    limitRaw = args.numberOrStringOrNull("limit"),
                )
            }
            listOf(UIMessagePart.Text(result.toText()))
        },
    )

    // ---------------------------------------------------------------- 外置

    private suspend fun offloadIfNeeded(
        toolName: String,
        parts: List<UIMessagePart>,
    ): List<UIMessagePart> {
        val textParts = parts.filterIsInstance<UIMessagePart.Text>()
        if (textParts.isEmpty()) return parts
        // 阈值看「所有 Text part 合计」，单条 15K 不该因为被拆成两段就逃过外置
        if (textParts.sumOf { it.text.length } <= TOOL_OUTPUT_OFFLOAD_THRESHOLD_CHARS) return parts

        // 多条 Text 之间用换行拼接：写进文件的字节就是 read tool 逐页读回的那份文本
        val fullText = sanitizeForUtf8(textParts.joinToString("\n") { it.text })
        val ref = try {
            withContext(Dispatchers.IO) { persist(toolName, fullText) }
        } catch (e: CancellationException) {
            // 任务被取消一定要往上抛，指标/流程都指望它
            throw e
        } catch (e: Exception) {
            // 写盘失败不改写原输出：宁可撑一点上下文，也不能把唯一可用的信息换成一条坏路径
            return parts
        }

        val notice = UIMessagePart.Text(previewBlock(ref, fullText))
        // 非 Text part（图片、文档、音频……）原样保留，顺序不变
        return listOf(notice) + parts.filterNot { it is UIMessagePart.Text }
    }

    private fun persist(toolName: String, fullText: String): ToolOutputRef {
        ensureBaseDir()
        val id = UUID.randomUUID().toString()
        val textFile = File(baseDir, "$id$TEXT_SUFFIX")
        // 先临时文件 + fsync 再 rename：崩在写一半时目录里不会留下被截断的全文
        writeAtomically(textFile, fullText.toByteArray(Charsets.UTF_8))

        val manifest = ToolOutputManifest(
            id = id,
            owner = scopeOwner,
            tool = toolName,
            totalChars = fullText.length,
            createdAt = System.currentTimeMillis(),
        )
        try {
            writeAtomically(
                manifestFile(id),
                json.encodeToString(ToolOutputManifest.serializer(), manifest).toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            // manifest 没写成的全文就是不可读的孤儿文件，顺手清掉，别把目录越攒越脏
            runCatching { textFile.delete() }
            throw e
        }
        // 会话内轮转：只写不删会让目录无限增长，一次大 dump 反复失效就能堆到几百 MB。
        // 清理失败不影响这次外置——内容和回执都已经落盘了。
        runCatching { pruneToolOutputs(baseDir) }
        return ToolOutputRef(id = id, toolName = toolName, totalChars = fullText.length, path = displayPath(textFile))
    }

    private fun ensureBaseDir() {
        if (baseDir.isDirectory) return
        if (!baseDir.mkdirs() && !baseDir.isDirectory) {
            throw IOException("cannot create tool output directory: ${baseDir.absolutePath}")
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IOException("no parent for ${target.absolutePath}")
        val tmp = File(parent, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                throw IOException("cannot move ${tmp.name} to ${target.name}")
            }
        } finally {
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }

    /**
     * 回执 + 头尾预览。
     *
     * 机器可读的那行 JSON 是固定格式（`output_id` / `totalChars` / `path` / `tool`），
     * 模型照着就能直接拼出 `tool_output_read` 的调用参数，不用从自然语言里猜 id。
     */
    private fun previewBlock(ref: ToolOutputRef, fullText: String): String {
        val head = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)
        val tail = if (fullText.length > TOOL_OUTPUT_PREVIEW_CHARS * 2) {
            fullText.takeLast(TOOL_OUTPUT_PREVIEW_CHARS)
        } else {
            ""
        }
        return buildString {
            appendLine("[Tool output offloaded: ${ref.totalChars} characters total, tool `${ref.toolName}`]")
            appendLine(
                buildJsonObject {
                    put("output_id", ref.id)
                    put("totalChars", ref.totalChars)
                    put("path", ref.path)
                    put("tool", ref.toolName)
                    put("previewHeadChars", head.length)
                    put("previewTailChars", tail.length)
                }.toString()
            )
            appendLine(
                "Full text kept at ${ref.path}. Read it with `$TOOL_OUTPUT_READ_TOOL_NAME` " +
                    "(works on local, remote and SSH backends):"
            )
            appendLine(
                buildJsonObject {
                    put("output_id", ref.id)
                    put("offset", 0)
                    put("limit", TOOL_OUTPUT_READ_DEFAULT_LIMIT)
                }.toString()
            )
            appendLine("--- head: first ${head.length} of ${ref.totalChars} chars ---")
            append(head)
            appendLine()
            if (tail.isNotEmpty()) {
                appendLine("--- tail: last ${tail.length} chars ---")
                append(tail)
                appendLine()
            }
            append("--- end of preview ---")
        }
    }

    // ---------------------------------------------------------------- 读回

    private sealed interface ReadResult {
        data class Ok(
            val id: String,
            val offset: Int,
            val nextOffset: Int?,
            val totalChars: Int,
            val eof: Boolean,
            val toolName: String,
            val path: String,
            val text: String,
        ) : ReadResult

        data class Failed(val message: String) : ReadResult
    }

    private fun ReadResult.toText(): String = when (this) {
        is ReadResult.Ok -> buildJsonObject {
            put("output_id", id)
            put("offset", offset)
            put("nextOffset", nextOffset?.let { JsonPrimitive(it) } ?: JsonNull)
            put("totalChars", totalChars)
            put("eof", eof)
            put("tool", toolName)
            put("path", path)
            put("text", text)
        }.toString()

        is ReadResult.Failed -> message
    }

    /**
     * 读一页。所有入参在这里集中校验，读路径上再没有第二次拼路径的机会：
     * id 先过严格 UUID 正则（`/`、`\`、`..` 直接不可能通过），manifest 再验 owner。
     *
     * 读法：在 UTF-8 解码流上跳过 `offset - 1` 个字符，只读 `limit + 3` 个字符到一个小缓冲里，
     * 再用 [alignPageStart] / [pageEndFor] 对齐代理对。内存开销是页大小，与文件大小无关。
     */
    private fun readPage(outputId: String, offsetRaw: String?, limitRaw: String?): ReadResult {
        if (outputId.isEmpty()) {
            return ReadResult.Failed("`output_id` is required: pass the UUID from the offloaded tool output notice.")
        }
        if (!UUID_PATTERN.matches(outputId)) {
            return ReadResult.Failed(
                "`output_id` must be a plain UUID such as `3f2b1c4e-2a5d-4a1f-9c3e-1b2a3c4d5e6f`, got `$outputId`. " +
                    "File paths and relative ids are not accepted."
            )
        }
        val offset = when {
            offsetRaw.isNullOrBlank() -> 0L
            else -> offsetRaw.trim().toLongOrNull()
                ?: return ReadResult.Failed("`offset` must be a non-negative integer number of characters, got `$offsetRaw`.")
        }
        if (offset < 0) {
            return ReadResult.Failed("`offset` must be >= 0, got $offset.")
        }
        val limit = when {
            limitRaw.isNullOrBlank() -> TOOL_OUTPUT_READ_DEFAULT_LIMIT.toLong()
            else -> limitRaw.trim().toLongOrNull()
                ?: return ReadResult.Failed(
                    "`limit` must be an integer between 1 and $TOOL_OUTPUT_READ_MAX_LIMIT, got `$limitRaw`."
                )
        }
        if (limit <= 0) {
            return ReadResult.Failed("`limit` must be between 1 and $TOOL_OUTPUT_READ_MAX_LIMIT, got $limit.")
        }
        // 超上限直接夹到上限而不是报错：模型偶尔会写个大数，没必要为它多跑一轮
        val pageLimit = limit.coerceAtMost(TOOL_OUTPUT_READ_MAX_LIMIT.toLong()).toInt()

        val manifest = readManifest(outputId) ?: return ReadResult.Failed(
            "Unknown `output_id` `$outputId`: this conversation has no offloaded output with that id. " +
                "Use the exact id printed by the tool output notice."
        )
        if (manifest.owner != scopeOwner) {
            // 别人的输出一个字都不给：owner 不匹配就是不存在
            return ReadResult.Failed(
                "`output_id` `$outputId` belongs to another conversation and cannot be read here. " +
                    "Only outputs offloaded in the current conversation are readable."
            )
        }

        val textFile = File(baseDir, "${manifest.id}$TEXT_SUFFIX")
        if (!textFile.isFile) return cleanedUp(outputId, textFile, manifest)

        // 总字符数信 manifest：它是写盘那一刻的 fullText.length，不需要为了报个数把文件读一遍
        val hintedTotal = manifest.totalChars.coerceAtLeast(0)
        if (offset >= hintedTotal) {
            // offset 超过总长不是错误：给一页空文本 + eof，模型自己就知道翻到底了
            return ReadResult.Ok(
                id = manifest.id,
                offset = hintedTotal,
                nextOffset = null,
                totalChars = hintedTotal,
                eof = true,
                toolName = manifest.tool,
                path = displayPath(textFile),
                text = "",
            )
        }

        // 从 offset 前一个字符开始读：页首可能需要回退一位对齐代理对。
        // 多要 3 个字符：页尾可能需要后让一位，另加缓冲余量。
        val windowStart = (offset - 1).coerceAtLeast(0L).toInt()
        val wantChars = pageLimit + 3
        val window = try {
            readCharWindow(textFile, windowStart, wantChars)
        } catch (e: Exception) {
            return cleanedUp(outputId, textFile, manifest)
        }

        val reachedEof = window.length < wantChars
        // 文件被外部截断时以实际长度为准，否则模型会一直拿到空页却看到 eof=false
        val totalChars = if (reachedEof) windowStart + window.length else hintedTotal
        // 窗口可能比预期短（文件被截断）：所有下标都要夹在窗口内，否则 substring 会越界
        val localRequested = (offset - windowStart).coerceIn(0, window.length)
        val localStart = alignPageStart(window, localRequested)
        val localEnd = pageEndFor(window, localStart, pageLimit).coerceIn(localStart, window.length)
        val start = windowStart + localStart
        val end = (windowStart + localEnd).coerceAtMost(totalChars).coerceAtLeast(start)
        val page = window.substring(localStart, localStart + (end - start))
        return ReadResult.Ok(
            id = manifest.id,
            offset = start,
            nextOffset = end.takeIf { it < totalChars },
            totalChars = totalChars,
            eof = end >= totalChars,
            toolName = manifest.tool,
            path = displayPath(textFile),
            text = page,
        )
    }

    /**
     * 输出已被清理（超过 [TOOL_OUTPUT_RETENTION_DAYS] 天或目录轮转把它挤掉了）。
     *
     * 文案必须说清是「清理掉了」而不是「id 写错了」，否则模型会拿同一个 id 反复重试。
     */
    private fun cleanedUp(outputId: String, textFile: File, manifest: ToolOutputManifest): ReadResult.Failed =
        ReadResult.Failed(
            "Offloaded output `$outputId` is no longer on disk (${displayPath(textFile)}). " +
                "It was cleaned up with the tool output cache (kept for $TOOL_OUTPUT_RETENTION_DAYS days " +
                "or the most recent $TOOL_OUTPUT_MAX_FILES outputs); re-run the original tool " +
                "(`tool` was `${manifest.tool}`) if you still need it."
        )

    /**
     * 从 UTF-8 文件里读 `[charOffset, charOffset + count)` 这一段字符。
     *
     * 用解码流 skip 而不是 `readText()`：skip 只解码不保留，内存是常数级；只有结果那一段
     * 会变成 String。skip 可能少跳（受解码缓冲限制），所以循环到跳够为止。
     */
    private fun readCharWindow(file: File, charOffset: Int, count: Int): String {
        FileInputStream(file).use { input ->
            val reader = InputStreamReader(input, Charsets.UTF_8)
            var skipped = 0
            while (skipped < charOffset) {
                val step = reader.skip((charOffset - skipped).toLong())
                // 文件比 manifest 记录的短：如实返回空窗口，下面按实际长度收尾
                if (step <= 0L) return ""
                skipped += step.toInt()
            }
            val buffer = CharArray(count)
            var filled = 0
            while (filled < count) {
                val read = reader.read(buffer, filled, count - filled)
                if (read < 0) break
                filled += read
            }
            return String(buffer, 0, filled)
        }
    }

    private fun readManifest(outputId: String): ToolOutputManifest? {
        val file = manifestFile(outputId)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(ToolOutputManifest.serializer(), file.readText(Charsets.UTF_8))
        }.getOrNull()?.takeIf { it.id == outputId }
    }

    private fun manifestFile(outputId: String) = File(baseDir, "$outputId$MANIFEST_SUFFIX")

    /**
     * 给模型看的路径。
     *
     * 目录叫 `tool_outputs` 时给 PRoot 的挂载路径 `/tool_outputs/<uuid>.txt`（LOCAL 能 `cat`），
     * 其他情况给本机绝对路径——至少是真实的，不会让模型照着一条不存在的路径去猜。
     */
    private fun displayPath(file: File): String =
        if (baseDir.name == FileFolders.TOOL_OUTPUTS) {
            "/${FileFolders.TOOL_OUTPUTS}/${file.name}"
        } else {
            file.absolutePath
        }
}

/**
 * 按时间与总量清理外置输出目录（冷启动、以及每次外置之后各调一次）。
 *
 * 旧的实现是冷启动 `deleteRecursively()`，等于「重启后仍可读回」这条契约落地即失效：
 * 用户第二天打开同一个会话，历史里还留着 `output_id` 回执，模型照回执来读却永远读不到。
 * 现在改成保留 [retentionDays] 天、最多 [maxFiles] 个、合计不超过 [maxTotalBytes] 字节：
 *
 *  - 超过保留期的按 mtime 删（manifest 与全文一起删）；
 *  - 超量时删最旧的，**最新的若干份一定留着**，所以刚写下的那份不会被自己挤掉；
 *  - 没有 manifest 的孤儿 `.txt`、写盘中断留下的 `.tmp` 也顺手清掉，但只清
 *    [ORPHAN_GRACE_MS] 之前的老文件，避免和正在写的请求抢；
 *  - 整个过程只 stat + listFiles，读 manifest 内容一次都不需要，所以放进写入路径也够廉价。
 *
 * @param now 便于测试注入固定时间
 */
internal fun pruneToolOutputs(
    dir: File,
    retentionDays: Int = TOOL_OUTPUT_RETENTION_DAYS,
    maxFiles: Int = TOOL_OUTPUT_MAX_FILES,
    maxTotalBytes: Long = TOOL_OUTPUT_MAX_TOTAL_BYTES,
    now: Long = System.currentTimeMillis(),
) {
    val files = dir.listFiles().orEmpty().filter { it.isFile }
    if (files.isEmpty()) return

    // 两趟：先把 manifest 认全，再判孤儿。listFiles 的顺序不可依赖，
    // 一趟走完会把「.txt 排在 .json 之前」的老输出误判成孤儿删掉。
    val manifestIds = HashSet<String>(files.size)
    files.forEach { file ->
        if (!file.name.endsWith(MANIFEST_SUFFIX)) return@forEach
        val id = file.name.removeSuffix(MANIFEST_SUFFIX)
        if (!UUID_PATTERN.matches(id)) {
            // 不是我们写的文件（旧版本残留、手动拷贝）：直接清掉，别让它挡住轮转
            runCatching { file.delete() }
        } else {
            manifestIds += id
        }
    }

    val alive = ArrayList<OutputFileEntry>(manifestIds.size)
    files.forEach { file ->
        when {
            file.name.endsWith(MANIFEST_SUFFIX) -> {
                val id = file.name.removeSuffix(MANIFEST_SUFFIX)
                if (!UUID_PATTERN.matches(id)) return@forEach
                val text = File(dir, "$id$TEXT_SUFFIX")
                alive += OutputFileEntry(
                    manifestFile = file,
                    textFile = text,
                    modifiedAt = file.lastModified(),
                    bytes = file.length() + (if (text.isFile) text.length() else 0L),
                )
            }

            // 没有 manifest 的全文不可读（owner/totalChars 都丢了），过了宽限期就删
            file.name.endsWith(TEXT_SUFFIX) -> {
                val id = file.name.removeSuffix(TEXT_SUFFIX)
                if ((!UUID_PATTERN.matches(id) || id !in manifestIds) && file.lastModified() < now - ORPHAN_GRACE_MS) {
                    runCatching { file.delete() }
                }
            }

            file.name.endsWith(".tmp") -> {
                if (file.lastModified() < now - ORPHAN_GRACE_MS) runCatching { file.delete() }
            }
        }
    }

    // 最旧的在前：后面无论是按天删还是按量删，都从头开始摘
    alive.sortBy { it.modifiedAt }

    if (retentionDays > 0) {
        val cutoff = now - retentionDays.coerceAtLeast(1) * 86_400_000L
        val expired = alive.filter { it.modifiedAt in 1 until cutoff }
        expired.forEach { it.delete() }
        alive.removeAll(expired)
    }

    // 至少留一份：刚写下的那份是最新的，不会被自己挤掉
    while (alive.size > maxFiles.coerceAtLeast(1)) {
        alive.removeAt(0).delete()
    }
    var totalBytes = alive.sumOf { it.bytes }
    while (totalBytes > maxTotalBytes && alive.size > 1) {
        val victim = alive.removeAt(0)
        totalBytes -= victim.bytes
        victim.delete()
    }
}

/** 轮转时用到的一份外置输出（manifest + 全文）。 */
private class OutputFileEntry(
    val manifestFile: File,
    val textFile: File,
    val modifiedAt: Long,
    val bytes: Long,
) {
    fun delete() {
        runCatching { manifestFile.delete() }
        runCatching { textFile.delete() }
    }
}

/** 判断一个工具名是不是读工具（含 `tool_output_read_2` 这类去重别名）。 */
private fun isReaderToolName(name: String): Boolean =
    name == TOOL_OUTPUT_READ_TOOL_NAME || name.startsWith("${TOOL_OUTPUT_READ_TOOL_NAME}_")

/**
 * 把落盘文本规整成「UTF-8 往返无损」的字符串。
 *
 * 孤立的 UTF-16 代理项无法编码成 UTF-8（会被写成 `?`），那 totalChars 就和磁盘上的字节对不上，
 * 分页会错位。这里把孤立的半个代理项换成 U+FFFD，先让内存里的全文和磁盘上的全文一模一样。
 * 正常文本（含成对代理项，如 emoji）原样返回，不产生任何拷贝。
 */
private fun sanitizeForUtf8(text: String): String {
    var index = 0
    while (index < text.length) {
        val c = text[index]
        when {
            c.isHighSurrogate() -> {
                if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) break
                index += 2
            }

            c.isLowSurrogate() -> break
            else -> index++
        }
    }
    if (index >= text.length) return text

    val builder = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                builder.append(c).append(text[i + 1])
                i += 2
            }

            c.isHighSurrogate() || c.isLowSurrogate() -> {
                builder.append('\uFFFD')
                i++
            }

            else -> {
                builder.append(c)
                i++
            }
        }
    }
    return builder.toString()
}

/** 工具参数取字符串：`output_id` 这类纯字符串字段用它。 */
private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** 数值参数：模型把数字写成 `4000` 或 `"4000"` 都收，`null` / JSON null 落到默认值。 */
private fun JsonObject.numberOrStringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

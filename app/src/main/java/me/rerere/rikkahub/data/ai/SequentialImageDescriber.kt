package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File

private const val TAG = "SequentialImageDescriber"

/** 进度文案前缀：只会清掉自己写的那一条。 */
private const val PROGRESS_PREFIX = "正在逐张识别图片"

/**
 * 第 [ordinal] 张图片的逐张识别失败。
 *
 * 明确写出"第几张"，不吞异常、不把错误文本当成功结果交给主模型。
 */
class SequentialImageException(
    message: String,
    val ordinal: Int,
    val total: Int,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * 「逐张识别后合并回答」的执行器。
 *
 * 只在用户显式标记图片后由 [ChatRequestFactory.buildStep] 调用：
 * 每张标记图片单独发一次**无工具**视觉请求（一次请求只有 1 张图，最多 1 并发），把结果写成中文说明，
 * 最后把这些说明替换掉主请求里的图片。原图不动、DB 里的消息不动。
 *
 * 计费归属：`UsageSource.OCR` + 本 turn 的 assistantId / conversationId，锁的是 turn 上的模型和供应商，
 * 不读全局"当前会话/当前模型"。
 *
 * 缓存：按 [sequentialImageCacheKey] 命中即不再发请求；成功的说明立刻写入缓存，
 * 所以失败重试或同一轮的后续步骤不会重复发同一张图重新计费。
 */
class SequentialImageDescriber(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    cacheFile: File = File(context.cacheDir, "sequential_image_cache.json"),
) {
    private val cache = SequentialImageCache(cacheFile)

    /**
     * 把 [messages] 里标记过的图片换成逐张说明文本。
     *
     * 没有标记的图片、其它 part 一律原样返回；一张标记都没有时连缓存都不碰。
     *
     * @throws SequentialImageException 某张图识别失败（消息里写明第几张）
     * @throws CancellationException 取消时原样抛出，且在每张图之间检查
     */
    suspend fun prepareMessages(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    ): List<UIMessage> {
        val tasks = planSequentialImages(
            messages = messages,
            conversationId = turn.conversationId,
            modelFingerprint = modelConfigFingerprint(turn.providerSetting, turn.model),
        )
        if (tasks.isEmpty()) return messages
        val notes = describeAll(turn, tasks, processingStatus)
        return messages.replaceSequentialImagesWithNotes(tasks, notes)
    }

    private suspend fun describeAll(
        turn: ChatTurnContext,
        tasks: List<SequentialImageTask>,
        processingStatus: MutableStateFlow<String?>,
    ): Map<String, String> {
        val notes = LinkedHashMap<String, String>()
        val previousStatus = processingStatus.value
        try {
            for (task in tasks) {
                // 顺序发请求：每张之间检查取消，取消后不再发下一张、不再计费
                currentCoroutineContext().ensureActive()
                val cached = cache.get(task.cacheKey)
                if (cached != null) {
                    Log.i(TAG, "describeAll: cache hit ${task.ordinal}/${task.total}")
                    notes[task.cacheKey] = cached
                    continue
                }
                processingStatus.value = progressText(task)
                val description = describeOne(turn, task)
                // 成功就立刻落缓存：后面失败重试或下一步都不会重新发这张图
                cache.put(task.cacheKey, description)
                notes[task.cacheKey] = description
            }
        } finally {
            // 只清自己写的那条进度；别的转换器/调用方写的进度不动
            if (processingStatus.value?.startsWith(PROGRESS_PREFIX) == true) {
                processingStatus.value = previousStatus
            }
        }
        return notes
    }

    private fun progressText(task: SequentialImageTask) = "$PROGRESS_PREFIX ${task.ordinal}/${task.total}…"

    private suspend fun describeOne(turn: ChatTurnContext, task: SequentialImageTask): String {
        val prepared = try {
            SequentialImageBitmaps.prepareForSingleRequest(context, task.url)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            throw failure(task, "图片准备失败：${error.message ?: error::class.simpleName ?: "未知错误"}", error)
        }
        try {
            val provider = usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.OCR, turn.assistant.id, turn.conversationId),
            )
            val result = try {
                provider.generateText(
                    providerSetting = turn.providerSetting,
                    messages = listOf(
                        UIMessage.system(SEQUENTIAL_VISION_SYSTEM_PROMPT),
                        UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(
                                UIMessagePart.Text(sequentialVisionUserPrompt(task.ordinal, task.total)),
                                UIMessagePart.Image(prepared.url),
                            ),
                        ),
                    ),
                    params = TextGenerationParams(
                        // 本轮模型/供应商，不用全局当前模型；一次请求只带这一张图
                        model = turn.model,
                        maxTokens = SEQUENTIAL_VISION_MAX_TOKENS,
                        customHeaders = turn.model.customHeaders,
                        // TextGenerationParams 的字段是 customBody（单数），Model 上是 customBodies
                        customBody = turn.model.customBodies,
                        tools = emptyList(),
                        disableToolCalls = true,
                        sessionId = turn.conversationId.toString(),
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                throw failure(task, error.message ?: error::class.simpleName.orEmpty(), error)
            }
            val text = result.message.toText().trim()
            if (text.isEmpty()) {
                throw failure(task, "识别结果为空", null)
            }
            Log.i(TAG, "describeOne: ${task.ordinal}/${task.total} ${text.length} chars")
            return text
        } finally {
            prepared.cleanup()
        }
    }

    private fun failure(task: SequentialImageTask, reason: String, cause: Throwable?) = SequentialImageException(
        message = "第 ${task.ordinal}/${task.total} 张图片逐张识别失败：$reason",
        ordinal = task.ordinal,
        total = task.total,
        cause = cause,
    )
}

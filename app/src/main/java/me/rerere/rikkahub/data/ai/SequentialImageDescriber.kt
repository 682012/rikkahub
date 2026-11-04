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
private const val PROGRESS_PREFIX = "正在分批识别图片"

/**
 * 第 [ordinal] 张图片的逐张/分批识别失败。
 *
 * 明确写出"第几张"（分批时是 `3-5` 这种区间），不吞异常、不把错误文本当成功结果交给主模型。
 */
class SequentialImageException(
    message: String,
    val ordinal: Int,
    val total: Int,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * 「分批识别后合并回答」的执行器。
 *
 * 只在用户显式标记图片后由 [ChatRequestFactory.buildStep] 调用：
 * 标记图片按批发**无工具**视觉请求（默认每批 1 张；用户选了 N 张时，一个请求按原顺序带 N 张图 + 每张前面的序号文字），
 * 把结果写成中文说明，最后把这些说明替换掉主请求里的图片。原图不动、DB 里的消息不动。
 *
 * 计费归属：`UsageSource.OCR` + 本 turn 的 assistantId / conversationId，锁的是 turn 上的模型和供应商，
 * 不读全局"当前会话/当前模型"。
 *
 * 缓存：按批次 key（见 [sequentialImageBatchCacheKey]）命中即不再发请求；每批成功立刻写缓存，
 * 所以失败重试或同一轮的后续步骤不会重复发同一批图重新计费。单张批次沿用 r16 的旧 key。
 */
class SequentialImageDescriber(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    cacheFile: File = File(context.cacheDir, "sequential_image_cache.json"),
) {
    private val cache = SequentialImageCache(cacheFile)

    /**
     * 把 [messages] 里标记过的图片换成识别说明文本。
     *
     * 没有标记的图片、其它 part 一律原样返回；一张标记都没有时连缓存都不碰。
     *
     * @throws SequentialImageException 某一批识别失败（消息里写明第几张）
     * @throws CancellationException 取消时原样抛出，且在每批之前、每批内每张图准备之间检查
     */
    suspend fun prepareMessages(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    ): List<UIMessage> {
        val batches = planSequentialImageBatches(
            messages = messages,
            conversationId = turn.conversationId,
            modelFingerprint = modelConfigFingerprint(turn.providerSetting, turn.model),
        )
        if (batches.isEmpty()) return messages
        val notes = describeAll(turn, batches, processingStatus)
        return messages.replaceSequentialImagesWithNotes(batches.flatMap { it.tasks }, notes)
    }

    private suspend fun describeAll(
        turn: ChatTurnContext,
        batches: List<SequentialImageBatch>,
        processingStatus: MutableStateFlow<String?>,
    ): Map<String, String> {
        val notes = LinkedHashMap<String, String>()
        val previousStatus = processingStatus.value
        try {
            for (batch in batches) {
                // 顺序发请求：每批之间检查取消，取消后不再发下一批、不再计费
                currentCoroutineContext().ensureActive()
                val cached = cache.get(batch.cacheKey)
                if (cached != null) {
                    Log.i(TAG, "describeAll: cache hit ${batch.ordinalLabel}/${batch.total}")
                    notes[batch.cacheKey] = cached
                    continue
                }
                processingStatus.value = progressText(batch)
                val description = describeBatch(turn, batch)
                // 成功就立刻落缓存：后面失败重试或下一步都不会重新发这一批图
                cache.put(batch.cacheKey, description)
                notes[batch.cacheKey] = description
            }
        } finally {
            // 只清自己写的那条进度；别的转换器/调用方写的进度不动
            if (processingStatus.value?.startsWith(PROGRESS_PREFIX) == true) {
                processingStatus.value = previousStatus
            }
        }
        return notes
    }

    private fun progressText(batch: SequentialImageBatch): String =
        if (batch.size <= 1) {
            "$PROGRESS_PREFIX ${batch.ordinalLabel}/${batch.total}…"
        } else {
            "$PROGRESS_PREFIX ${batch.ordinalLabel}/${batch.total}（这一批 ${batch.size} 张）…"
        }

    /**
     * 一次请求处理一批图：按原图顺序准备 → 每张前面插序号文字 → 单次 `generateText`。
     *
     * 只要有任何一张准备失败/请求失败就抛 [SequentialImageException]，**不产出说明**，
     * 也不给这一批里的任何一张图伪造文本。
     */
    private suspend fun describeBatch(turn: ChatTurnContext, batch: SequentialImageBatch): String {
        val prepared = ArrayList<PreparedImage>(batch.size)
        try {
            for (task in batch.tasks) {
                // 批内每张图之间也检查取消：取消后不再准备后面的图，也不发请求
                currentCoroutineContext().ensureActive()
                val image = try {
                    SequentialImageBitmaps.prepareForSingleRequest(context, task.url)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    throw failure(batch, "图片准备失败：${error.message ?: error::class.simpleName ?: "未知错误"}", error)
                }
                prepared += image
            }

            val provider = usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.OCR, turn.assistant.id, turn.conversationId),
            )
            val result = try {
                provider.generateText(
                    providerSetting = turn.providerSetting,
                    messages = listOf(
                        UIMessage.system(
                            if (batch.size <= 1) SEQUENTIAL_VISION_SYSTEM_PROMPT else SEQUENTIAL_VISION_BATCH_SYSTEM_PROMPT
                        ),
                        UIMessage(
                            role = MessageRole.USER,
                            // 顺序 = 原图顺序；每张图前面放它自己的序号，模型才能按编号对应
                            parts = buildList {
                                batch.tasks.forEachIndexed { index, task ->
                                    add(
                                        UIMessagePart.Text(
                                            sequentialVisionBatchUserPrompt(task.ordinal, batch.total, batch.size)
                                        )
                                    )
                                    add(UIMessagePart.Image(prepared[index].url))
                                }
                            },
                        ),
                    ),
                    params = TextGenerationParams(
                        // 本轮模型/供应商，不用全局当前模型；一个请求只带这一批图
                        model = turn.model,
                        maxTokens = (SEQUENTIAL_VISION_MAX_TOKENS * batch.size).coerceAtMost(16_000),
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
                throw failure(batch, error.message ?: error::class.simpleName.orEmpty(), error)
            }
            val text = result.message.toText().trim()
            if (text.isEmpty()) {
                throw failure(batch, "识别结果为空", null)
            }
            Log.i(TAG, "describeBatch: ${batch.ordinalLabel}/${batch.total} ${batch.size} images → ${text.length} chars")
            return text
        } finally {
            // 成功、失败、取消都清掉这一批已准备的临时文件（原图不动）
            prepared.forEach { image -> runCatching { image.cleanup() } }
        }
    }

    private fun failure(batch: SequentialImageBatch, reason: String, cause: Throwable?) = SequentialImageException(
        message = "第 ${batch.ordinalLabel}/${batch.total} 张图片分批识别失败：$reason",
        ordinal = batch.firstOrdinal,
        total = batch.total,
        cause = cause,
    )
}

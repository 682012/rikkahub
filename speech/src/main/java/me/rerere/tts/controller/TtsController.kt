package me.rerere.tts.controller

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import java.io.IOException
import java.util.UUID

private const val TAG = "TtsController"
private const val MAX_SYNTHESIS_ATTEMPTS = 3
private const val SYNTHESIS_RETRY_BASE_DELAY_MS = 500L

/**
 * TTS 控制器（重构版）
 * - 负责文本分片、预取合成、排队播放与状态上报
 * - 对外 API 与原版兼容
 */
class TtsController(
    context: Context,
    private val ttsManager: TTSManager
) {
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 组件
    private var playbackSettings = TtsPlaybackSettings()
    private var generation = 0L
    private var audibleChunkId: UUID? = null
    private val synthesizer = TtsSynthesizer(ttsManager)
    private val audio = AudioPlayer(context)

    // Provider & 作业
    private var currentProvider: TTSProviderSetting? = null
    private var workerJob: Job? = null
    private var isPaused = false

    // 队列与缓存（基于稳定 ID）
    private val queue: java.util.concurrent.ConcurrentLinkedQueue<TtsChunk> = java.util.concurrent.ConcurrentLinkedQueue()
    private val allChunks: MutableList<TtsChunk> = mutableListOf()
    private val cache = java.util.concurrent.ConcurrentHashMap<UUID, kotlinx.coroutines.Deferred<TTSResponse>>()

    // 行为参数
    // No artificial pause between ready chunks.

    // 状态流（保留与旧版兼容的 StateFlow）
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentChunk = MutableStateFlow(0)
    val currentChunk: StateFlow<Int> = _currentChunk.asStateFlow()

    private val _totalChunks = MutableStateFlow(0)
    val totalChunks: StateFlow<Int> = _totalChunks.asStateFlow()

    // 统一播放状态（融合音频播放 + 分片进度）
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    init {
        // 同步底层播放器状态到统一状态，并补充分片信息
        scope.launch {
            audio.playbackState.collectLatest { audioState ->
                _playbackState.update {
                    audioState.copy(
                        currentChunkIndex = _currentChunk.value,
                        totalChunks = _totalChunks.value,
                        status = if (!_isAvailable.value) PlaybackStatus.Idle else audioState.status
                    )
                }
            }
        }
    }

    /** 选择/取消选择 Provider */
    fun setProvider(provider: TTSProviderSetting?) {
        if (currentProvider != provider) stop()
        currentProvider = provider
        _isAvailable.update { provider != null }
    }

    fun setPlaybackSettings(settings: TtsPlaybackSettings) {
        val next = settings.sanitized()
        if (playbackSettings.languageTag != next.languageTag) stop()
        playbackSettings = next
        val lookahead = if (currentProvider is TTSProviderSetting.SystemTTS) 0 else next.prefetchCount
        val keep = queue.take(lookahead).map { it.id }.toSet() + listOfNotNull(audibleChunkId)
        cache.keys.filter { it !in keep }.forEach { cache.remove(it)?.cancel() }
        audio.setParameters(next.speed, next.pitch, next.volume)
    }

    /**
     * 朗读文本
     * - flush=true: 清空当前进度并重新开始
     * - flush=false: 继续队列，追加朗读
     */
    fun speak(text: String, flush: Boolean = true) {
        if (text.isBlank()) return
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        val newChunks = TextChunker(playbackSettings.chunkLength,
            minOf(80, playbackSettings.chunkLength)).split(text)
        if (newChunks.isEmpty()) return

        if (flush) {
            internalReset()
            allChunks.addAll(newChunks)
            queue.addAll(newChunks)
            _currentChunk.update { 0 }
        } else {
            // 追加时，重映射 index 以保持全局顺序
            val startIndex = (allChunks.lastOrNull()?.index ?: -1) + 1
            val remapped = newChunks.mapIndexed { i, c -> c.copy(index = startIndex + i) }
            allChunks.addAll(remapped)
            queue.addAll(remapped)
        }
        _totalChunks.update { allChunks.size }
        _error.update { null }

        _playbackState.update {
            it.copy(
                currentChunkIndex = _currentChunk.value,
                totalChunks = _totalChunks.value,
                status = if (isPaused) PlaybackStatus.Paused else PlaybackStatus.Buffering
            )
        }

        if (workerJob?.isActive != true) startWorker()
    }

    private fun internalReset() {
        // Invalidate stale worker completion before cancelling it.
        generation++
        workerJob?.cancel()
        workerJob = null
        audio.stop()
        audio.clear()
        isPaused = false
        queue.clear()
        audibleChunkId = null
        allChunks.clear()
        cache.values.forEach { it.cancel(CancellationException("Reset")) }
        cache.clear()
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
        _error.update { null }
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
    }

    /** 暂停播放（保留进度） */
    fun pause() {
        isPaused = true
        audio.pause()
        _playbackState.update { it.copy(status = PlaybackStatus.Paused) }
    }

    /** 恢复播放 */
    fun resume() {
        isPaused = false
        audio.resume()
        _playbackState.update { it.copy(status = PlaybackStatus.Playing) }
    }

    /** 快进当前音频 */
    fun fastForward(ms: Long = 5_000) {
        audio.seekBy(ms)
    }

    /** 设置播放速度 */
    fun setSpeed(speed: Float) {
        playbackSettings = playbackSettings.copy(speed = speed).sanitized()
        audio.setParameters(playbackSettings.speed, playbackSettings.pitch, playbackSettings.volume)
    }

    /** 跳过下一段（不打断当前正在播放） */
    fun skipNext() {
        queue.poll()?.let { skipped -> cache.remove(skipped.id)?.cancel() }
    }

    /** 停止并清空状态 */
    fun stop() = internalReset()

    /** 释放资源 */
    fun dispose() {
        stop()
        scope.cancel()
        audio.release()
    }

    // region 内部：播放调度
    private fun startWorker() {
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        val workerGeneration = generation
        workerJob = scope.launch {
            _isSpeaking.update { true }
            var processedCount = _currentChunk.value
            try {
                while (isActive) {
                    if (isPaused) {
                        delay(80)
                        continue
                    }

                    val chunk = queue.poll() ?: break
                    audibleChunkId = chunk.id

                    // 更新状态（1-based）
                    _currentChunk.update { chunk.index + 1 }
                    _totalChunks.update { allChunks.size }
                    _playbackState.update {
                        it.copy(
                            currentChunkIndex = _currentChunk.value,
                            totalChunks = _totalChunks.value
                        )
                    }

                    // Schedule the audible chunk before any lookahead.
                    getOrCreateSynthesis(chunk, provider)
                    prefetchNextChunks(provider)

                    val response = try {
                        awaitOrCreate(chunk, provider)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Synthesis error", e)
                        _error.update { e.message ?: "TTS synthesis error" }
                        processedCount++
                        continue
                    }

                    // Pause during synthesis must also hold the next audio.
                    while (isPaused && isActive) delay(40)
                    // 播放
                    try {
                        audio.play(response)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Playback error", e)
                        _error.update { e.message ?: "Audio playback error" }
                    }

                    cache.remove(chunk.id) // release played audio
                    audibleChunkId = null

                    processedCount++
                }
            } finally {
                if (workerGeneration == generation) {
                    _isSpeaking.update { false }
                    cache.values.forEach { it.cancel() }
                    cache.clear()
                    if (queue.isEmpty()) _playbackState.update { it.copy(
                        status = if (_error.value == null) PlaybackStatus.Ended else PlaybackStatus.Error,
                        errorMessage = _error.value) }
                }
            }
        }
    }

    private fun prefetchNextChunks(provider: TTSProviderSetting) {
        // Device engines serialize internally; concurrent initialization adds overhead.
        val count = if (provider is TTSProviderSetting.SystemTTS) 0 else playbackSettings.prefetchCount
        queue.take(count).forEach { getOrCreateSynthesis(it, provider) }
    }

    private suspend fun awaitOrCreate(chunk: TtsChunk, provider: TTSProviderSetting): TTSResponse {
        val deferred = getOrCreateSynthesis(chunk, provider)
        return try {
            deferred.await()
        } catch (e: Exception) {
            // 避免后续复用已经失败或取消的 Deferred
            cache.remove(chunk.id, deferred)
            throw e
        }
    }

    private fun getOrCreateSynthesis(
        chunk: TtsChunk,
        provider: TTSProviderSetting
    ): kotlinx.coroutines.Deferred<TTSResponse> {
        val language = playbackSettings.languageTag
        return cache.computeIfAbsent(chunk.id) {
            scope.async(Dispatchers.IO) {
                synthesizeWithRetry(provider, chunk, language)
            }
        }
    }

    private suspend fun synthesizeWithRetry(
        provider: TTSProviderSetting,
        chunk: TtsChunk,
        languageTag: String,
    ): TTSResponse {
        var attempt = 1
        while (true) {
            try {
                return synthesizer.synthesize(provider, chunk, languageTag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!e.isRetryableSynthesisError() || attempt >= MAX_SYNTHESIS_ATTEMPTS) throw e

                val retryDelayMs = SYNTHESIS_RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
                Log.w(
                    TAG,
                    "Synthesis attempt $attempt/$MAX_SYNTHESIS_ATTEMPTS failed for chunk ${chunk.index}; " +
                        "retrying in ${retryDelayMs}ms",
                    e
                )
                delay(retryDelayMs)
                attempt++
            }
        }
    }
    // endregion
}

private fun Exception.isRetryableSynthesisError(): Boolean {
    return this is IOException || this is TTSProviderException && isRetryable
}

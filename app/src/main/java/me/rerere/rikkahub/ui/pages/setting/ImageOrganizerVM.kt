package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.organization.effectiveImageWorkers
import me.rerere.rikkahub.data.organization.forEachImageWorker
import me.rerere.rikkahub.data.organization.ImageCacheKeys
import me.rerere.rikkahub.data.organization.ImageCategory
import me.rerere.rikkahub.data.organization.ImageClassifier
import me.rerere.rikkahub.data.organization.ImageDeleteFailure
import me.rerere.rikkahub.data.organization.ImageDeletePlanner
import me.rerere.rikkahub.data.organization.ImageDeleteResult
import me.rerere.rikkahub.data.organization.ImageMediaPaths
import me.rerere.rikkahub.data.organization.ImageOrganizerCache
import me.rerere.rikkahub.data.organization.ImageOrganizerItem
import me.rerere.rikkahub.data.organization.ImageOrganizerWorkerStore
import me.rerere.rikkahub.data.organization.ImageOrganizerModelStore
import me.rerere.rikkahub.data.organization.ImageOrganizerSources
import me.rerere.rikkahub.data.organization.ImageOrganizerState
import me.rerere.rikkahub.data.organization.ImageProgress
import me.rerere.rikkahub.data.organization.ImageSource
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import java.io.File

/**
 * 图片整理的状态 VM。
 *
 * 设计约束（和 UI 约定见 IMAGE-CONTRACT.md）：
 * - 不自动识别、不在后台定时跑：只有 UI 显式调用 [classify] 才发请求；首次发请求的告知由 UI 负责。
 * - 一次只跑一批，批内按用户设置的工作协程数并发推进；单张失败不影响整批。
 * - 取消（[cancelClassification] / [onCleared]）立即停，已完成的保留，[CancellationException] 原样抛出。
 * - 删除只处理「明确选中 + 非保留 + 可删 + 无共享引用」的项，实际计划在 [ImageDeletePlanner] 里算（纯 JVM 可测）。
 */
class ImageOrganizerVM(
    context: Context,
    private val filesManager: FilesManager,
    private val genMediaRepository: GenMediaRepository,
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
    private val mediaCreationRepository: MediaCreationRepository? = null,
) : ViewModel() {

    private val appContext: Context = context.applicationContext ?: context
    private val filesDir: File get() = appContext.filesDir

    private val cache = ImageOrganizerCache(File(filesDir, "$STORE_DIR/classification_cache.json"))
    private val modelStore = ImageOrganizerModelStore(File(filesDir, "$STORE_DIR/model.json"))
    /** 并发数单独存一份，不动 model.json（两者写入互不影响）。 */
    private val workerStore = ImageOrganizerWorkerStore(File(filesDir, "$STORE_DIR/workers.json"))
    private val classifier = ImageClassifier(appContext, providerManager)
    private val sources = ImageOrganizerSources(
        context = appContext,
        filesManager = filesManager,
        genMediaRepository = genMediaRepository,
        mediaCreationRepository = mediaCreationRepository,
    )

    private val _state = MutableStateFlow(
        ImageOrganizerState(
            selectedModelId = modelStore.read(),
            workers = workerStore.read(),
        )
    )
    val state: StateFlow<ImageOrganizerState> = _state.asStateFlow()

    private var classifyJob: Job? = null
    private var scanJob: Job? = null
    private val deleteMutex = Mutex()
    private var deleting = false
    private var pendingSaves = 0

    // ---- 对外方法 ----

    /** 重新扫描本地图片。只读本地文件与数据库，不发任何请求。 */
    fun refresh() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            updateState { it.copy(scanning = true, error = null) }
            try {
                withContext(Dispatchers.IO) { cleanStaleTempFiles() }
                val recovery = mediaCreationRepository?.recoverInterruptedOutputDeletions()
                if (recovery?.unresolved?.isNotEmpty() == true) {
                    updateState { it.copy(error = "有 ${recovery.unresolved.size} 张中断删除的图片尚未恢复，文件已保留") }
                }
                val result = sources.scan()
                val protectedIds = cache.protectedIds()
                val items = result.items.map { item ->
                    val entry = cache.get(cacheKey(item))
                    val category = entry?.let { cached ->
                        ImageCategory.entries.firstOrNull { it.name == cached.category }
                    }
                    val shared = result.references[item.path] ?: 0
                    item.copy(
                        // Only persisted protection counts; failed saves never appear successful.
                        protected = item.id in protectedIds,
                        category = category,
                        description = entry?.description,
                        classifiedAt = entry?.at,
                        cached = entry != null,
                        sharedReferences = shared,
                        // 被别的记录引用时如实标记不可删，和 ImageDeletePlanner 的判断保持一致
                        deletable = item.deletable && shared == 0,
                        blockReason = when {
                            item.blockReason != null -> item.blockReason
                            shared > 0 -> "被其他生成记录或媒体创作记录引用，删除会让那些记录里的图失效"
                            else -> null
                        },
                    )
                }
                updateState { it.copy(items = items, scanning = false) }
            } catch (e: CancellationException) {
                updateState { it.copy(scanning = false) }
                throw e
            } catch (e: Exception) {
                updateState { it.copy(scanning = false, error = e.message ?: "扫描图片失败") }
            }
        }
    }

    /**
     * 清理上次进程被杀时留下的识别临时 JPEG（`cacheDir/image_organizer/`）。
     * 只删超过 [TEMP_TTL_MILLIS] 的文件，避免删掉正在被这一批使用的临时图。
     */
    private fun cleanStaleTempFiles() {
        runCatching {
            val dir = File(appContext.cacheDir, STORE_DIR)
            val cutoff = System.currentTimeMillis() - TEMP_TTL_MILLIS
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
    }

    /** 记录识别模型；null 表示清除。写自己的 JSON，不动全局设置。 */
    fun setModel(modelId: Uuid?) {
        if (_state.value.busy) return
        pendingSaves++
        updateState { it.copy(error = null) }
        viewModelScope.launch {
            try {
                if (withContext(Dispatchers.IO) { modelStore.write(modelId) }) {
                    updateState { it.copy(selectedModelId = modelId) }
                } else {
                    updateState { it.copy(error = modelStore.lastFailure ?: "识别模型选择没能保存") }
                }
            } finally {
                pendingSaves--
                updateState { it }
            }
        }
    }

    /**
     * 设置这一批并发识别多少个请求。范围外的值夹回 [ImageOrganizerState.MIN_WORKERS]..[ImageOrganizerState.MAX_WORKERS]。
     *
     * 写自己的 JSON（`workers.json`），不动 model.json、不动全局设置。正在跑批时不可改。
     */
    fun setWorkers(workers: Int) {
        if (_state.value.busy) return
        val value = ImageOrganizerState.coerceWorkers(workers)
        if (value == _state.value.workers) return
        pendingSaves++
        updateState { it.copy(error = null) }
        viewModelScope.launch {
            try {
                if (withContext(Dispatchers.IO) { workerStore.write(value) }) {
                    updateState { it.copy(workers = value) }
                } else {
                    updateState { it.copy(error = workerStore.lastFailure ?: "并发设置没能保存") }
                }
            } finally {
                pendingSaves--
                updateState { it }
            }
        }
    }

    /**
     * 识别给定的图片。已有的本地缓存命中时不再发请求。
     * 正在跑一批时再调用会被忽略（防重入）。
     *
     * 并发是固定 [ImageOrganizerState.workers] 个工作协程从共享游标里取下一张，
     * 不会按图片张数开 N 个协程；请求层的全局限流仍由 ProviderManager 负责。
     */
    fun classify(ids: Set<String>) {
        if (ids.isEmpty() || _state.value.busy) return
        if (classifyJob?.isActive == true) return

        val modelId = _state.value.selectedModelId
        if (modelId == null) {
            updateState { it.copy(error = "先选一个识别模型") }
            return
        }
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(modelId)
        val providerSetting = model?.findProvider(settings.providers)
        if (model == null || providerSetting == null) {
            updateState { it.copy(error = "选中的识别模型已不可用，请重新选择") }
            return
        }

        updateState { it.copy(error = null) }

        classifyJob = viewModelScope.launch {
            // 读磁盘的存在性判断放到 IO 里，别占用主线程
            val targets = withContext(Dispatchers.IO) {
                _state.value.items.filter { it.id in ids && it.file.isFile }
            }
            if (targets.isEmpty()) {
                updateState { it.copy(error = "选中项里没有还能读到的图片") }
                return@launch
            }

            val total = targets.size
            val done = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val workers = effectiveImageWorkers(_state.value.workers, model.maxConcurrentRequests)
            updateState { it.copy(progress = ImageProgress(total = total, done = 0, failed = 0)) }
            try {
                forEachImageWorker(targets, workers) { item ->
                    classifyOne(item, model, providerSetting, total, done, failed)
                }
            } finally {
                updateState { it.copy(progress = null) }
            }
        }
    }

    /** 取消当前批次：已完成的保留，未开始的直接放弃。 */
    fun cancelClassification() {
        classifyJob?.cancel()
    }

    /** 标记保留。保留标记单独持久化，删图时永远跳过。 */
    fun setProtected(ids: Set<String>, protected: Boolean) {
        if (ids.isEmpty() || _state.value.busy) return
        pendingSaves++
        updateState { it.copy(error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { cache.setProtected(ids, protected) }
                updateState { s ->
                    s.copy(items = s.items.map { if (it.id in ids) it.copy(protected = protected) else it })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(error = e.message ?: "保留标记没能保存，请重试") }
            } finally {
                pendingSaves--
                updateState { it }
            }
        }
    }

    /**
     * 删除选中项。**不做二次确认**，确认弹窗由 UI 负责。
     *
     * 返回成功/失败/跳过三类结果；失败项保留索引，共享引用的项直接跳过并给 warning。
     */
    suspend fun delete(ids: Set<String>): ImageDeleteResult = deleteMutex.withLock {
        if (ids.isEmpty()) return@withLock ImageDeleteResult()
        if (pendingSaves > 0) return@withLock ImageDeleteResult(
            warnings = listOf("保留标记或模型设置仍在保存，请稍后再删"),
        )
        deleting = true
        updateState { it.copy(error = null) }
        try {
            val (plan, itemById) = withContext(Dispatchers.IO) {
                val protectedIds = cache.protectedIds()
                val current = _state.value.items.map { it.copy(protected = it.id in protectedIds) }
                ImageDeletePlanner.plan(current, ids) to current.associateBy { it.id }
            }
            val deleted = mutableListOf<String>()
            val failures = mutableListOf<ImageDeleteFailure>()

            withContext(Dispatchers.IO) {
                plan.delete.forEach { item ->
                    try {
                        deleteOne(item)
                        deleted += item.id
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures += ImageDeleteFailure(item.id, e.message ?: "删除失败")
                    }
                }
            }

            deleted.forEach { id ->
                try {
                    itemById[id]?.let { withContext(Dispatchers.IO) { cache.remove(cacheKey(it)) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateState { it.copy(error = "图片已删除，但分类缓存未清理：${e.message.orEmpty()}") }
                }
            }
            updateState { s -> s.copy(items = s.items.filterNot { it.id in deleted }) }
            ImageDeleteResult(
                deleted = deleted,
                failed = failures,
                skipped = plan.skipped,
                warnings = plan.warnings,
            )
        } finally {
            deleting = false
            updateState { it }
        }
    }

    fun dismissError() {
        updateState { it.copy(error = null) }
    }

    /** 给用 remember 构造的 UI 用，等价于 ViewModel 被销毁。 */
    fun clear() {
        cancelAll()
    }

    override fun onCleared() {
        cancelAll()
        super.onCleared()
    }

    // ---- 内部 ----

    private suspend fun classifyOne(
        item: ImageOrganizerItem,
        model: me.rerere.ai.provider.Model,
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        total: Int,
        done: AtomicInteger,
        failed: AtomicInteger,
    ) {
        val key = cacheKey(item)
        updateState { s -> s.copy(progress = ImageProgress(total, done.get(), failed.get(), item.name)) }

        try {
            val cachedEntry = cache.get(key)
            val cachedCategory = cachedEntry?.let { entry ->
                ImageCategory.entries.firstOrNull { it.name == entry.category }
            }
            if (cachedEntry != null && cachedCategory != null) {
                applyResult(item.id, cachedCategory, cachedEntry.description, cached = true, failed = false)
                publishProgress(total, done.incrementAndGet(), failed.get(), item.name)
                return
            }

            val parsed = classifier.classify(item.file, model, providerSetting)
            var persistError: String? = null
            if (parsed.category != ImageCategory.UNRECOGNIZED) {
                try {
                    cache.put(key, parsed.category, parsed.description)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    persistError = e.message ?: "识别结果已返回，但缓存未能保存"
                    updateState { it.copy(error = persistError) }
                }
            }
            applyResult(
                id = item.id,
                category = parsed.category,
                description = parsed.description,
                cached = false,
                failed = parsed.category == ImageCategory.UNRECOGNIZED,
                error = persistError,
            )
            publishProgress(
                total = total,
                done = done.incrementAndGet(),
                failed = if (parsed.category == ImageCategory.UNRECOGNIZED) failed.incrementAndGet() else failed.get(),
                currentName = item.name,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 单张失败只影响这一张，不打断整批
            applyResult(
                id = item.id,
                category = ImageCategory.UNRECOGNIZED,
                description = null,
                cached = false,
                failed = true,
                error = e.message?.take(120),
            )
            publishProgress(total, done.incrementAndGet(), failed.incrementAndGet(), item.name)
        }
    }

    private fun applyResult(
        id: String,
        category: ImageCategory,
        description: String?,
        cached: Boolean,
        failed: Boolean,
        error: String? = null,
    ) {
        val now = System.currentTimeMillis()
        updateState { s ->
            s.copy(
                items = s.items.map { item ->
                    if (item.id != id) {
                        item
                    } else {
                        item.copy(
                            category = category,
                            description = description,
                            classifiedAt = now,
                            cached = cached,
                            failed = failed,
                            error = error,
                        )
                    }
                }
            )
        }
    }

    private fun publishProgress(total: Int, done: Int, failed: Int, currentName: String?) {
        updateState { state ->
            val previous = state.progress
            state.copy(progress = ImageProgress(total, maxOf(done, previous?.done ?: 0),
                maxOf(failed, previous?.failed ?: 0), currentName))
        }
    }

    private suspend fun deleteOne(item: ImageOrganizerItem) {
        when (item.source) {
            ImageSource.UPLOAD -> {
                // 只删 app 自己 filesDir 里的文件：路径逃逸、或 symlink 指到外部时直接拒绝
                if (!ImageMediaPaths.isConfinedTo(filesDir, item.file)) {
                    error("路径不在应用目录内，已拒绝删除")
                }
                val id = item.id.removePrefix("upload:").toLongOrNull()
                    ?: error("无法解析上传文件 ID")
                if (!filesManager.delete(id, deleteFromDisk = true)) error("文件或索引删除失败")
            }

            ImageSource.GEN_MEDIA -> {
                if (!ImageMediaPaths.isConfinedTo(filesManager.getImagesDir(), item.file)) {
                    error("路径不在生成图目录内，已拒绝删除")
                }
                val id = item.id.removePrefix("gen:").toIntOrNull()
                    ?: error("无法解析生成图 ID")
                if (item.file.exists() && !item.file.delete()) error("文件删除失败，已保留索引")
                genMediaRepository.deleteMedia(id)
            }

            // 媒体创作产出图走仓库的专用接口：只摘掉记录 outputs 里的这一条，
            // 记录本身、输入素材、其他产出、时间线位置都保留；记录正在生成或还被引用时接口会返回原因。
            ImageSource.MEDIA_CREATION -> {
                val repository = mediaCreationRepository ?: error("媒体创作仓库不可用，无法删除这张产出图")
                if (!ImageMediaPaths.isConfinedTo(filesDir, item.file)) {
                    error("路径不在应用目录内，已拒绝删除")
                }
                val outcome = repository.removeImageOutput(item.relativePath)
                if (!outcome.removed) error(outcome.message.ifBlank { "媒体创作记录没有接受这次删除" })
            }
        }
    }

    private fun cacheKey(item: ImageOrganizerItem): String =
        ImageCacheKeys.of(item.id, item.relativePath, item.bytes, item.lastModified)

    private fun updateState(transform: (ImageOrganizerState) -> ImageOrganizerState) {
        _state.update { current ->
            val next = transform(current)
            next.copy(busy = next.scanning || next.progress != null || deleting || pendingSaves > 0)
        }
    }

    private fun cancelAll() {
        classifyJob?.cancel()
        scanJob?.cancel()
        classifyJob = null
        scanJob = null
    }

    companion object {
        private const val STORE_DIR = "image_organizer"
        /** 识别临时 JPEG 的保留时长：超过这个时间还没被删掉的，下次刷新时清掉。 */
        private const val TEMP_TTL_MILLIS = 60L * 60 * 1000
    }
}

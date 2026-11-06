package me.rerere.rikkahub.data.devicecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 无障碍服务与业务层的唯一桥面：服务实例、当前包、最近一次快照缓存都在这里。
 *
 * 设计取舍：
 * - 快照缓存是进程级单例，不跟着某个 Bridge 实例走——MCP 和内部工具可能是两个 Bridge，
 *   但如果各自缓存，"用 A 抓的树给 B 点"会被误判成成功；共享一份缓存就能保证旧 id 一定过期。
 * - 快照会记下抓树时的窗口 id，写动作前要求前台包 + 窗口 id 都还对得上，防"异屏同路径"误点。
 * - **手势不以"被系统接受"当成功**：dispatchGesture 返回 true 只代表排队成功，
 *   必须等 onCompleted/onCancelled 回调；等不到就如实返回 UNCONFIRMED（调用方报 action_failed）。
 * - 找不到服务一律返回 null / false，由 [DeviceControlBridge] 翻译成 service_disabled，不假装动作成功。
 */
object DeviceAccessibilityRegistry {

    @Volatile
    private var service: DeviceAccessibilityService? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _currentPackage = MutableStateFlow<String?>(null)
    val currentPackage: StateFlow<String?> = _currentPackage.asStateFlow()

    private val sequence = AtomicLong(0)

    @Volatile
    private var cachedSnapshot: DeviceSnapshot? = null

    @Volatile
    private var cachedNodes: Map<String, DeviceNode> = emptyMap()

    val lastSnapshotId: String? get() = cachedSnapshot?.id
    val lastSnapshotAt: Long? get() = cachedSnapshot?.capturedAtMillis

    /** 手势的真实结果，而不是 dispatchGesture 的返回值。 */
    enum class GestureOutcome {
        /** 系统回调 onCompleted：手势真的走完了。 */
        COMPLETED,

        /** 系统回调 onCancelled：手势被系统取消（被遮挡/被拦截/权限问题）。 */
        CANCELLED,

        /** 被接受但超时没有任何回调：结果未知，不能当成功。 */
        UNCONFIRMED,

        /** dispatchGesture 直接返回 false，服务没接。 */
        REJECTED,
    }

    fun attach(instance: DeviceAccessibilityService) {
        service = instance
        _connected.value = true
        cachedSnapshot = null
        cachedNodes = emptyMap()
    }

    fun detach(instance: DeviceAccessibilityService?) {
        if (instance == null || service === instance) {
            service = null
            _connected.value = false
            cachedSnapshot = null
            cachedNodes = emptyMap()
        }
    }

    fun notePackage(packageName: String?) {
        val pkg = packageName?.takeIf { it.isNotBlank() }
        if (pkg != null && pkg != _currentPackage.value) _currentPackage.value = pkg
    }

    fun isConnected(): Boolean = service != null

    /** 系统里是否勾选了这个无障碍服务（≠ 已连接）。 */
    fun isEnabledInSystem(context: Context): Boolean {
        val expected = ComponentName(context, DeviceAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            if (entry.equals(expected.flattenToString(), ignoreCase = true) ||
                entry.equals(expected.flattenToShortString(), ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        // 只打开系统设置页，绝不代用户勾选
        runCatching {
            context.startActivity(
                android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 当前活动窗口的包名与窗口 id（读不到就 null）。 */
    data class LiveWindow(val packageName: String, val windowId: Int)

    suspend fun liveWindow(): LiveWindow? {
        val instance = service ?: return null
        return withContext(Dispatchers.Main.immediate) {
            val root = safeRoot(instance) ?: return@withContext null
            val pkg = root.packageName?.toString().orEmpty()
            val windowId = try {
                root.windowId
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                -1
            }
            if (pkg.isBlank()) null else LiveWindow(pkg, windowId).also { notePackage(pkg) }
        }
    }

    /** 抓当前活动窗口的整棵树。服务不在就返回 null。 */
    suspend fun capture(grants: DeviceControlGrants): CapturedTree? {
        val instance = service ?: return null
        return withContext(Dispatchers.Main.immediate) {
            val root = safeRoot(instance) ?: return@withContext null
            val packageName = root.packageName?.toString().orEmpty()
            notePackage(packageName.ifBlank { null })
            val windowId = try {
                root.windowId
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                -1
            }
            val nodes = mutableListOf<DeviceNode>()
            var visited = 0
            var truncated = false
            val limit = grants.maxNodesPerSnapshot
            val maxDepth = 40

            fun walk(node: AccessibilityNodeInfo, id: String, depth: Int) {
                if (truncated) return
                visited++
                if (visited > limit) {
                    truncated = true
                    return
                }
                val converted = convert(node, id, grants)
                if (converted != null) nodes.add(converted)
                if (depth >= maxDepth) return
                for (index in 0 until node.childCount) {
                    val child = try {
                        node.getChild(index)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    walk(child, DeviceTreeRules.childId(id, index), depth + 1)
                }
            }

            walk(root, "0", 0)
            val tree = CapturedTree(
                packageName = packageName,
                nodes = nodes,
                totalNodes = nodes.size,
                truncated = truncated,
                capturedAtMillis = System.currentTimeMillis(),
            )
            val snapshotId = "snap-${tree.capturedAtMillis}-${sequence.incrementAndGet()}"
            val snapshot = DeviceSnapshot(
                id = snapshotId,
                packageName = packageName,
                capturedAtMillis = tree.capturedAtMillis,
                page = 0,
                pageSize = grants.pageSize,
                totalNodes = nodes.size,
                nodes = nodes,
                hasMore = false,
                truncated = truncated,
                windowId = windowId,
            )
            cachedSnapshot = snapshot
            cachedNodes = nodes.associateBy { it.id }
            tree
        }
    }

    /** 任何写动作之后调用：旧 snapshot_id 立刻失效，下一步必须重新抓树。 */
    fun invalidateSnapshots() {
        cachedSnapshot = null
        cachedNodes = emptyMap()
    }

    fun cachedSnapshot(): DeviceSnapshot? = cachedSnapshot

    /** 更新缓存快照的分页视图（抓树是不变的，翻页只换切片）。 */
    fun pageOf(snapshot: DeviceSnapshot, page: Int, pageSize: Int): DeviceSnapshot {
        val size = pageSize.coerceAtLeast(1)
        val from = (page.coerceAtLeast(0) * size).coerceAtMost(snapshot.nodes.size)
        val to = (from + size).coerceAtMost(snapshot.nodes.size)
        return snapshot.copy(
            page = page.coerceAtLeast(0),
            pageSize = size,
            nodes = snapshot.nodes.subList(from, to).toList(),
            hasMore = to < snapshot.nodes.size,
        )
    }

    fun cachedNode(nodeId: String): DeviceNode? = cachedNodes[nodeId]

    /** 一次活树查询的结果：节点（可能没了）+ 当时的前台包/窗口 id。 */
    data class LiveTarget(
        val node: DeviceNode?,
        val packageName: String?,
        val windowId: Int,
    )

    /** 按路径重新在当前窗口里找同一位置；找不到 node 为 null（= 树变了）。 */
    suspend fun findLiveTarget(nodeId: String, grants: DeviceControlGrants): LiveTarget? {
        val instance = service ?: return null
        if (!DeviceTreeRules.isNodeIdWellFormed(nodeId)) return null
        return withContext(Dispatchers.Main.immediate) {
            val root = safeRoot(instance) ?: return@withContext null
            val pkg = root.packageName?.toString().orEmpty().ifBlank { null }
            val windowId = try {
                root.windowId
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                -1
            }
            val target = try {
                findByPath(root, nodeId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
            LiveTarget(target?.let { convert(it, nodeId, grants) }, pkg, windowId)
        }
    }

    /** 兼容旧调用：只要节点。 */
    suspend fun findLiveNode(nodeId: String, grants: DeviceControlGrants): DeviceNode? =
        findLiveTarget(nodeId, grants)?.node

    /**
     * 坐标落点是否压在敏感节点上（密码框/授权输入框）。
     * 用活树判断，所以不依赖快照是否还新鲜；读不到树就返回 false（调用方另有 service_disabled 兜底）。
     */
    suspend fun hitsSensitiveNode(x: Float, y: Float, grants: DeviceControlGrants): Boolean {
        val instance = service ?: return false
        return withContext(Dispatchers.Main.immediate) {
            val root = safeRoot(instance) ?: return@withContext false
            val px = x.toInt()
            val py = y.toInt()
            var hit = false
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (hit || depth > 40) return
                val rect = Rect()
                try {
                    node.getBoundsInScreen(rect)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    return
                }
                val inside = px >= rect.left && px <= rect.right && py >= rect.top && py <= rect.bottom
                if (inside) {
                    val sensitive = DeviceTreeRules.isSensitive(
                        className = node.className?.toString(),
                        inputType = node.inputType,
                        isPasswordFlag = node.isPassword,
                        hintText = node.hintText?.toString(),
                        viewIdResourceName = node.viewIdResourceName,
                    )
                    if (sensitive) {
                        hit = true
                        return
                    }
                }
                for (index in 0 until node.childCount) {
                    val child = try {
                        node.getChild(index)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    walk(child, depth + 1)
                }
            }
            walk(root, 0)
            hit
        }
    }

    private fun safeRoot(instance: DeviceAccessibilityService): AccessibilityNodeInfo? = try {
        instance.rootInActiveWindow
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    private fun findByPath(root: AccessibilityNodeInfo, nodeId: String): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo = root
        val parts = nodeId.split('/')
        if (parts.firstOrNull() != "0") return null
        for (part in parts.drop(1)) {
            val index = part.toIntOrNull() ?: return null
            val child = try {
                current.getChild(index)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            } ?: return null
            current = child
        }
        return current
    }

    /**
     * 节点动作：ACTION_CLICK / ACTION_LONG_CLICK / ACTION_SET_TEXT。
     * 返回 true 只代表系统接受了这个 action，不是"界面一定按预期变了"。
     */
    suspend fun performNodeAction(nodeId: String, action: NodeAction, text: String? = null): Boolean {
        val instance = service ?: return false
        return withContext(Dispatchers.Main.immediate) {
            val root = safeRoot(instance) ?: return@withContext false
            val node = try {
                findByPath(root, nodeId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            } ?: return@withContext false
            when (action) {
                NodeAction.CLICK -> {
                    if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return@withContext true
                    }
                    clickableAncestor(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                }
                NodeAction.LONG_CLICK -> {
                    if (node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                        return@withContext true
                    }
                    longClickableAncestor(node)?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
                }
                NodeAction.SET_TEXT -> {
                    val value = text ?: return@withContext false
                    val target = editableNode(node) ?: return@withContext false
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                    }
                    target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }
            }
        }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun longClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isLongClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun editableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 4) {
            if (current.isEditable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /** 坐标点击：只有 onCompleted 才算真的点完。 */
    suspend fun tap(x: Float, y: Float): GestureOutcome {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build(), durationMs = 60)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): GestureOutcome {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val duration = durationMs.coerceIn(50, 5_000)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build(), durationMs = duration)
    }

    /**
     * dispatchGesture 的返回值只是"请求被接受"。这里等系统回调：
     * - onCompleted → COMPLETED（真的点完了）；
     * - onCancelled → CANCELLED；
     * - 超时没回调 → UNCONFIRMED（结果未知，调用方不能报成功）；
     * - 直接返回 false → REJECTED。
     */
    private suspend fun dispatch(gesture: GestureDescription, durationMs: Long): GestureOutcome {
        val instance = service ?: return GestureOutcome.REJECTED
        val done = CompletableDeferred<Boolean>()
        val accepted = withContext(Dispatchers.Main.immediate) {
            try {
                instance.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(description: GestureDescription?) {
                            done.complete(true)
                        }

                        override fun onCancelled(description: GestureDescription?) {
                            done.complete(false)
                        }
                    },
                    null,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
        }
        if (!accepted) return GestureOutcome.REJECTED
        val timeout = durationMs.coerceIn(50, 5_000) + 1_500
        return when (withTimeoutOrNull(timeout) { done.await() }) {
            true -> GestureOutcome.COMPLETED
            false -> GestureOutcome.CANCELLED
            null -> GestureOutcome.UNCONFIRMED
        }
    }

    /** 返回/桌面/最近任务走无障碍全局动作，不依赖 root。 */
    suspend fun performGlobalAction(action: Int): Boolean {
        val instance = service ?: return false
        return withContext(Dispatchers.Main.immediate) {
            try {
                instance.performGlobalAction(action)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun convert(node: AccessibilityNodeInfo, id: String, grants: DeviceControlGrants): DeviceNode? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!node.isVisibleToUser || rect.width() <= 0 || rect.height() <= 0) return null
        val sensitive = DeviceTreeRules.isSensitive(
            className = node.className?.toString(),
            inputType = node.inputType,
            isPasswordFlag = node.isPassword,
            hintText = node.hintText?.toString(),
            viewIdResourceName = node.viewIdResourceName,
        )
        val rawText = node.text?.toString()
        val rawDesc = node.contentDescription?.toString()
        val (text, textCut) = DeviceTreeRules.truncate(
            DeviceTreeRules.redactText(rawText, sensitive),
            grants.maxCharsPerNode,
        )
        val (desc, descCut) = DeviceTreeRules.truncate(
            DeviceTreeRules.redactText(rawDesc, sensitive),
            grants.maxCharsPerNode,
        )
        val interesting = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isLongClickable ||
            node.isEditable || node.isScrollable || node.isCheckable
        if (!interesting) return null
        return DeviceNode(
            id = id,
            className = node.className?.toString().orEmpty(),
            text = text,
            contentDescription = desc,
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            sensitive = sensitive,
            textTruncated = textCut || descCut,
            children = node.childCount,
        )
    }

    enum class NodeAction { CLICK, LONG_CLICK, SET_TEXT }
}

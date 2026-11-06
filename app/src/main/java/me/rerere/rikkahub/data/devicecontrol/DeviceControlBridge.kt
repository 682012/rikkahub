package me.rerere.rikkahub.data.devicecontrol

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 给 UI / MCP 状态接口用的对外快照。note 是机器可读的当前阻塞原因。 */
data class DeviceControlState(
    val grants: DeviceControlGrants = DeviceControlGrants(),
    val serviceConnected: Boolean = false,
    val serviceEnabledInSystem: Boolean = false,
    val currentPackage: String? = null,
    val lastSnapshotId: String? = null,
    val lastSnapshotAt: Long? = null,
    /** ok / disabled_by_user / service_not_enabled / service_disconnected / no_whitelisted_package */
    val note: String = "disabled_by_user",
) {
    val ready: Boolean get() = grants.enabled && serviceConnected
}

/**
 * 设备桥的稳定 API。主代理只需要这个类：
 *
 * - `tools()` 拿工具清单（name/description/inputSchema/readOnly），映射成 MCP tool 或内部 Tool；
 * - `call(name, args)` 执行，返回结构化 JSON，失败带 error code；
 * - `state` 给设置页显示真实服务/授权状态。
 *
 * 所有动作都先过 [DeviceControlPolicy]（默认全关 + 包白名单），再走无障碍服务；没有 root 路径，
 * 也没有"服务没连上却返回成功"的分支。
 */
class DeviceControlBridge(
    private val context: Context,
    val preferences: DeviceControlPreferences = DeviceControlPreferences(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(buildState(preferences.current(), DeviceAccessibilityRegistry.currentPackage.value))
    val state: StateFlow<DeviceControlState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(
                preferences.grants,
                DeviceAccessibilityRegistry.connected,
                DeviceAccessibilityRegistry.currentPackage,
            ) { grants, connected, pkg -> Triple(grants, connected, pkg) }
                .collect { (grants, _, pkg) ->
                    _state.value = buildState(grants, pkg)
                }
        }
    }

    private fun buildState(grants: DeviceControlGrants, pkg: String?): DeviceControlState {
        val connected = DeviceAccessibilityRegistry.isConnected()
        val enabledInSystem = DeviceAccessibilityRegistry.isEnabledInSystem(context)
        val note = when {
            !grants.enabled -> "disabled_by_user"
            !enabledInSystem -> "service_not_enabled"
            !connected -> "service_disconnected"
            grants.allowedPackages.isEmpty() -> "no_whitelisted_package"
            else -> "ok"
        }
        return DeviceControlState(
            grants = grants,
            serviceConnected = connected,
            serviceEnabledInSystem = enabledInSystem,
            currentPackage = pkg,
            lastSnapshotId = DeviceAccessibilityRegistry.lastSnapshotId,
            lastSnapshotAt = DeviceAccessibilityRegistry.lastSnapshotAt,
            note = note,
        )
    }

    /** 从系统设置回来、或服务断开后调用，强制重读一遍真值。 */
    fun refresh() {
        _state.value = buildState(preferences.current(), DeviceAccessibilityRegistry.currentPackage.value)
    }

    fun openAccessibilitySettings() {
        DeviceAccessibilityRegistry.openAccessibilitySettings(context)
    }

    fun close() {
        scope.cancel()
    }

    fun tools(): List<DeviceToolDescriptor> = DeviceToolCatalog.descriptors()

    suspend fun call(name: String, args: JsonObject): JsonObject {
        val action = DeviceAction.of(name) ?: return error(DeviceErrorCode.UNKNOWN_TOOL, "unknown device tool: $name")
        // 磁盘读完再判定授权，避免刚启动时把"已授权"误判成未授权（读盘在 IO 线程，这里只等）
        preferences.awaitLoaded()
        val grants = preferences.current()
        return try {
            dispatchTool(action, grants, args)
        } catch (cancellation: CancellationException) {
            // 取消/超时一律往上抛：不能把"被取消"变成一条 action_failed 结果
            throw cancellation
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.TimeoutCancellationException) throw error
            error(
                DeviceErrorCode.ACTION_FAILED,
                "device tool crashed: ${error.message ?: error::class.java.simpleName}",
            )
        }
    }

    private suspend fun dispatchTool(
        action: DeviceAction,
        grants: DeviceControlGrants,
        args: JsonObject,
    ): JsonObject = when (action) {
        DeviceAction.LIST_APPS -> listApps(grants, args)
        DeviceAction.SNAPSHOT -> snapshot(grants, args)
        DeviceAction.BACK, DeviceAction.HOME, DeviceAction.RECENTS -> globalAction(action)
        DeviceAction.TAP -> tap(args)
        DeviceAction.SWIPE -> swipe(args)
        DeviceAction.LAUNCH_APP -> launchApp(args)
        DeviceAction.TAP_NODE, DeviceAction.LONG_PRESS_NODE, DeviceAction.SET_TEXT -> nodeAction(action, args)
    }

    // ---------------------------------------------------------------- 快照

    private suspend fun snapshot(grants: DeviceControlGrants, args: JsonObject): JsonObject {
        val policyError = DeviceControlPolicy.check(grants, DeviceAction.SNAPSHOT)
        if (policyError != null) return error(policyError, "reading the screen is not authorized")
        val page = args.int("page") ?: 0
        val pageSize = args.int("page_size") ?: grants.pageSize
        val requestedId = args.str("snapshot_id")
        val now = System.currentTimeMillis()

        val snapshot = if (page > 0) {
            val cached = DeviceAccessibilityRegistry.cachedSnapshot()
            DeviceSnapshotGuard.checkSnapshot(cached, requestedId, now, grants.snapshotTtlMs)?.let { code ->
                return error(code, "snapshot is missing or expired, call device_snapshot again")
            }
            DeviceAccessibilityRegistry.pageOf(cached!!, page, pageSize)
        } else {
            val tree = DeviceAccessibilityRegistry.capture(grants)
                ?: return error(
                    DeviceErrorCode.SERVICE_DISABLED,
                    if (!DeviceAccessibilityRegistry.isEnabledInSystem(context)) {
                        "accessibility service is not enabled in system settings"
                    } else {
                        "accessibility service is not connected"
                    },
                )
            if (!DeviceControlPolicy.isPackageAllowed(grants, tree.packageName)) {
                return error(
                    DeviceErrorCode.PACKAGE_NOT_ALLOWED,
                    "foreground package is not whitelisted: ${tree.packageName}",
                    buildJsonObject { put("current_package", tree.packageName) },
                )
            }
            val full = DeviceAccessibilityRegistry.cachedSnapshot()
                ?: return error(DeviceErrorCode.SERVICE_DISABLED, "tree captured but cache is gone")
            DeviceAccessibilityRegistry.pageOf(full, 0, pageSize)
        }

        return buildJsonObject {
            put("ok", true)
            put("snapshot_id", snapshot.id)
            put("package", snapshot.packageName)
            put("page", snapshot.page)
            put("page_size", snapshot.pageSize)
            put("total_nodes", snapshot.totalNodes)
            put("returned", snapshot.nodes.size)
            put("has_more", snapshot.hasMore)
            if (snapshot.hasMore) put("next_page", snapshot.page + 1) else put("next_page", JsonPrimitive(null as String?))
            put("truncated", snapshot.truncated)
            put("captured_at", snapshot.capturedAtMillis)
            putJsonArray("nodes") { snapshot.nodes.forEach { add(it.toJson()) } }
        }
    }

    // ---------------------------------------------------------------- 节点动作

    private suspend fun nodeAction(action: DeviceAction, args: JsonObject): JsonObject {
        val snapshotId = args.str("snapshot_id")
        val nodeId = args.str("node_id")
        if (snapshotId.isNullOrBlank() || nodeId.isNullOrBlank()) {
            return error(DeviceErrorCode.BAD_ARGUMENT, "snapshot_id and node_id are required")
        }
        if (!DeviceTreeRules.isNodeIdWellFormed(nodeId)) {
            return error(DeviceErrorCode.BAD_ARGUMENT, "node_id must be a path like 0/2/1")
        }
        val grants = preferences.current()
        val cached = DeviceAccessibilityRegistry.cachedSnapshot()
        val now = System.currentTimeMillis()
        DeviceSnapshotGuard.checkSnapshot(cached, snapshotId, now, grants.snapshotTtlMs)?.let { code ->
            return error(code, "snapshot is missing or expired, call device_snapshot again")
        }
        val snapshot = cached!!
        val live = DeviceAccessibilityRegistry.findLiveTarget(nodeId, grants)
            ?: return error(DeviceErrorCode.SERVICE_DISABLED, "accessibility service is not connected")
        // 前台窗口变了（换了页面/App/浮窗）就不能拿旧树下发动作
        val livePackageName = live.packageName
        DeviceSnapshotGuard.checkWindow(snapshot, livePackageName, live.windowId)?.let {
            return error(
                DeviceErrorCode.STALE_SNAPSHOT,
                "the foreground window changed since the snapshot (now ${livePackageName ?: "unknown"}), capture a new one",
            )
        }
        // 快照所在包和当前前台包都要在白名单里（换包即视为不再授权）
        val livePackage: String? = livePackageName
        val primaryPackage: String = livePackage ?: snapshot.packageName
        var policyError = DeviceControlPolicy.check(
            grants,
            action,
            packageName = primaryPackage,
            selfPackage = context.packageName,
        )
        if (policyError == null && livePackage != null && livePackage != snapshot.packageName) {
            policyError = DeviceControlPolicy.check(
                grants,
                action,
                packageName = snapshot.packageName,
                selfPackage = context.packageName,
            )
        }
        if (policyError != null) {
            val pkg = primaryPackage
            val reason = if (DeviceControlPolicy.isSelfProtected(pkg, context.packageName)) {
                "RikkaHub's own screens (including device control settings) cannot be driven by device actions"
            } else {
                "action is not authorized for package $pkg"
            }
            return error(policyError, reason, buildJsonObject { put("current_package", pkg) })
        }
        val expected = DeviceSnapshotGuard.nodeInSnapshot(snapshot, nodeId)
        DeviceSnapshotGuard.checkNode(expected, live.node)?.let { code ->
            return error(
                code,
                when (code) {
                    DeviceErrorCode.SENSITIVE_FIELD -> "password or sensitive field cannot be read or tapped"
                    DeviceErrorCode.UNKNOWN_NODE -> "node no longer exists in the current window"
                    else -> "window changed since the snapshot, capture a new one"
                },
            )
        }
        val target = expected!!
        val text = args.str("text")
        if (action == DeviceAction.SET_TEXT) {
            if (text == null) return error(DeviceErrorCode.BAD_ARGUMENT, "text is required for device_set_text_node")
            if (!target.editable && live.node?.editable != true) {
                return error(DeviceErrorCode.BAD_ARGUMENT, "node ${target.id} is not editable")
            }
            if (text.length > 4_000) return error(DeviceErrorCode.BAD_ARGUMENT, "text is too long, limit 4000 characters")
        }

        val nodeAction = when (action) {
            DeviceAction.TAP_NODE -> DeviceAccessibilityRegistry.NodeAction.CLICK
            DeviceAction.LONG_PRESS_NODE -> DeviceAccessibilityRegistry.NodeAction.LONG_CLICK
            else -> DeviceAccessibilityRegistry.NodeAction.SET_TEXT
        }
        var performed = DeviceAccessibilityRegistry.performNodeAction(nodeId, nodeAction, text)
        var via = "node_action"
        if (!performed && nodeAction != DeviceAccessibilityRegistry.NodeAction.SET_TEXT) {
            // 有些容器把点击放在父层：按快照里的真实坐标兜底，仍要求同一快照与白名单，
            // 并且必须等手势回调（accepted 不等于点完）
            val cx = (target.left + target.right) / 2f
            val cy = (target.top + target.bottom) / 2f
            if (cx > 0f && cy > 0f) {
                when (DeviceAccessibilityRegistry.tap(cx, cy)) {
                    DeviceAccessibilityRegistry.GestureOutcome.COMPLETED -> performed = true
                    DeviceAccessibilityRegistry.GestureOutcome.CANCELLED -> return error(
                        DeviceErrorCode.ACTION_FAILED,
                        "the system cancelled the tap gesture for node $nodeId",
                    )
                    else -> return error(
                        DeviceErrorCode.ACTION_FAILED,
                        "tap gesture for node $nodeId was not confirmed by the system",
                    )
                }
            }
            via = "gesture"
        }
        if (!performed) {
            return error(DeviceErrorCode.ACTION_FAILED, "the system rejected the action for node $nodeId")
        }
        // 写动作后立刻作废旧快照，下一步必须重新读树
        DeviceAccessibilityRegistry.invalidateSnapshots()
        return buildJsonObject {
            put("ok", true)
            put("action", action.wire)
            put("node_id", nodeId)
            put("package", cached.packageName)
            put("via", via)
        }
    }

    // ---------------------------------------------------------------- 手势 / 全局

    private suspend fun tap(args: JsonObject): JsonObject {
        val x = args.double("x") ?: return error(DeviceErrorCode.BAD_ARGUMENT, "x is required")
        val y = args.double("y") ?: return error(DeviceErrorCode.BAD_ARGUMENT, "y is required")
        if (x < 0 || y < 0) return error(DeviceErrorCode.BAD_ARGUMENT, "coordinates must be positive")
        ensureService()?.let { return it }
        // 包名以活窗口为准，不用可能过时的事件流
        val pkg = DeviceAccessibilityRegistry.liveWindow()?.packageName
            ?: DeviceAccessibilityRegistry.currentPackage.value
        val grants = preferences.current()
        DeviceControlPolicy.check(grants, DeviceAction.TAP, pkg, selfPackage = context.packageName)?.let { code ->
            return error(code, checkMessage(code, DeviceAction.TAP, pkg))
        }
        // 坐标不能变成绕过文本保护的旁路：落点在密码/敏感输入框上直接拒绝
        if (DeviceAccessibilityRegistry.hitsSensitiveNode(x.toFloat(), y.toFloat(), grants)) {
            return error(
                DeviceErrorCode.SENSITIVE_FIELD,
                "coordinates land on a password or sensitive field; typing into it is not allowed",
            )
        }
        when (DeviceAccessibilityRegistry.tap(x.toFloat(), y.toFloat())) {
            DeviceAccessibilityRegistry.GestureOutcome.COMPLETED -> Unit
            DeviceAccessibilityRegistry.GestureOutcome.CANCELLED ->
                return error(DeviceErrorCode.ACTION_FAILED, "the system cancelled the tap gesture")
            else ->
                return error(DeviceErrorCode.ACTION_FAILED, "the tap gesture was not confirmed by the system")
        }
        DeviceAccessibilityRegistry.invalidateSnapshots()
        return buildJsonObject {
            put("ok", true)
            put("action", DeviceAction.TAP.wire)
            put("x", x)
            put("y", y)
            put("package", pkg)
        }
    }

    private suspend fun swipe(args: JsonObject): JsonObject {
        val x1 = args.double("x1") ?: return error(DeviceErrorCode.BAD_ARGUMENT, "x1 is required")
        val y1 = args.double("y1") ?: return error(DeviceErrorCode.BAD_ARGUMENT, "y1 is required")
        val x2 = args.double("x2") ?: return error(DeviceErrorCode.BAD_ARGUMENT, "x2 is required")
        val y2 = args.double("y2") ?: return error(DeviceErrorCode.BAD_ARGUMENT, "y2 is required")
        val duration = (args.int("duration_ms") ?: 300).coerceIn(50, 5_000)
        ensureService()?.let { return it }
        val pkg = DeviceAccessibilityRegistry.liveWindow()?.packageName
            ?: DeviceAccessibilityRegistry.currentPackage.value
        val grants = preferences.current()
        DeviceControlPolicy.check(grants, DeviceAction.SWIPE, pkg, selfPackage = context.packageName)?.let { code ->
            return error(code, checkMessage(code, DeviceAction.SWIPE, pkg))
        }
        if (DeviceAccessibilityRegistry.hitsSensitiveNode(x1.toFloat(), y1.toFloat(), grants)) {
            return error(
                DeviceErrorCode.SENSITIVE_FIELD,
                "the swipe starts on a password or sensitive field; gestures over it are not allowed",
            )
        }
        when (
            DeviceAccessibilityRegistry.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration.toLong())
        ) {
            DeviceAccessibilityRegistry.GestureOutcome.COMPLETED -> Unit
            DeviceAccessibilityRegistry.GestureOutcome.CANCELLED ->
                return error(DeviceErrorCode.ACTION_FAILED, "the system cancelled the swipe gesture")
            else ->
                return error(DeviceErrorCode.ACTION_FAILED, "the swipe gesture was not confirmed by the system")
        }
        DeviceAccessibilityRegistry.invalidateSnapshots()
        return buildJsonObject {
            put("ok", true)
            put("action", DeviceAction.SWIPE.wire)
            put("duration_ms", duration)
            put("package", pkg)
        }
    }

    private suspend fun globalAction(action: DeviceAction): JsonObject {
        ensureService()?.let { return it }
        // back/home/recents 必须作用在已白名单的前台包上：
        // 否则模型能用导航键去操作未授权的 App（银行、系统设置、授权页）。
        val pkg = DeviceAccessibilityRegistry.liveWindow()?.packageName
            ?: DeviceAccessibilityRegistry.currentPackage.value
        DeviceControlPolicy.check(
            preferences.current(),
            action,
            packageName = pkg,
            selfPackage = context.packageName,
        )?.let { code ->
            return error(code, checkMessage(code, action, pkg), buildJsonObject { put("current_package", pkg ?: "") })
        }
        val global = when (action) {
            DeviceAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            DeviceAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            else -> AccessibilityService.GLOBAL_ACTION_RECENTS
        }
        if (!DeviceAccessibilityRegistry.performGlobalAction(global)) {
            return error(DeviceErrorCode.ACTION_FAILED, "the system rejected ${action.wire}")
        }
        DeviceAccessibilityRegistry.invalidateSnapshots()
        return buildJsonObject {
            put("ok", true)
            put("action", action.wire)
        }
    }

    private suspend fun launchApp(args: JsonObject): JsonObject {
        val pkg = args.str("package")?.trim().orEmpty()
        if (pkg.isEmpty()) return error(DeviceErrorCode.BAD_ARGUMENT, "package is required")
        DeviceControlPolicy.check(preferences.current(), DeviceAction.LAUNCH_APP, targetPackage = pkg)?.let { code ->
            return error(code, "launching $pkg is not authorized")
        }
        ensureService()?.let { return it }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return error(DeviceErrorCode.PACKAGE_NOT_FOUND, "no launchable activity for $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { failure ->
            return error(DeviceErrorCode.ACTION_FAILED, "launch failed: ${failure.message ?: failure::class.java.simpleName}")
        }
        DeviceAccessibilityRegistry.invalidateSnapshots()
        return buildJsonObject {
            put("ok", true)
            put("action", DeviceAction.LAUNCH_APP.wire)
            put("package", pkg)
        }
    }

    // ---------------------------------------------------------------- App 列表

    private fun listApps(grants: DeviceControlGrants, args: JsonObject): JsonObject {
        DeviceControlPolicy.check(preferences.current(), DeviceAction.LIST_APPS)?.let { code ->
            return error(code, "listing apps is not authorized")
        }
        val query = args.str("query")?.trim()?.lowercase().orEmpty()
        val limit = (args.int("limit") ?: 60).coerceIn(1, 200)
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrDefault(emptyList())
        val entries = resolved.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            val pkg = activity.packageName ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
            Triple(pkg, label, pkg in grants.allowedPackages)
        }.distinctBy { it.first }
            .filter { query.isEmpty() || it.first.lowercase().contains(query) || it.second.lowercase().contains(query) }
            .sortedBy { it.second.lowercase() }
        val page = entries.take(limit)
        return buildJsonObject {
            put("ok", true)
            put("total", entries.size)
            put("returned", page.size)
            put("whitelisted_count", entries.count { it.third })
            putJsonArray("apps") {
                page.forEach { (pkg, label, allowed) ->
                    add(
                        buildJsonObject {
                            put("package", pkg)
                            put("label", label)
                            put("whitelisted", allowed)
                        },
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- 小工具

    /** 给调用方的英文原因；RikkaHub 自己的界面单独说明。 */
    private fun checkMessage(code: DeviceErrorCode, action: DeviceAction, pkg: String?): String = when {
        DeviceControlPolicy.isSelfProtected(pkg, context.packageName) ->
            "RikkaHub's own screens (including device control settings) cannot be driven by device actions"
        code == DeviceErrorCode.PACKAGE_NOT_ALLOWED && pkg.isNullOrBlank() ->
            "the current foreground app is unknown; ${action.wire} needs a whitelisted foreground app"
        code == DeviceErrorCode.PACKAGE_NOT_ALLOWED ->
            "${action.wire} is not allowed for $pkg; only whitelisted apps are allowed"
        else -> "${action.wire} is not authorized"
    }

    private fun ensureService(): JsonObject? {
        if (DeviceAccessibilityRegistry.isConnected()) return null
        val enabled = DeviceAccessibilityRegistry.isEnabledInSystem(context)
        return error(
            DeviceErrorCode.SERVICE_DISABLED,
            if (enabled) "accessibility service is not connected" else "accessibility service is not enabled in system settings",
        )
    }

    private fun error(code: DeviceErrorCode, message: String, extra: JsonObject? = null): JsonObject =
        buildJsonObject {
            put("ok", false)
            put("error", code.wire)
            put("message", message)
            extra?.forEach { (key, value) -> put(key, value) }
        }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}

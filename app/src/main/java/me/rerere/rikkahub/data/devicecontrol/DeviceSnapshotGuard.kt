package me.rerere.rikkahub.data.devicecontrol

/**
 * 快照时效与节点身份校验。这是"防过期树误点"的核心：
 *
 * 1. 调用方给 snapshot_id + node_id；
 * 2. 当前缓存的快照必须还是这个 id（换过页面/重新抓过就是过期）；
 * 3. 超过 TTL 也算过期；
 * 4. 当前前台窗口的包名 / 窗口 id 必须和抓树时一致（异屏不算过期 = 可能点到别处的同路径节点）；
 * 5. 重新在当前活动窗口里按路径找这个节点，找不到 → unknown_node；
 * 6. 找到但 class/位置指纹对不上 → 树已经变了，stale_snapshot；
 * 7. 命中的是密码/敏感节点 → sensitive_field，不点也不读。
 *
 * 全程纯函数，抓树和查找由 [DeviceAccessibilityRegistry] 提供。
 */
object DeviceSnapshotGuard {

    fun checkSnapshot(
        cached: DeviceSnapshot?,
        requestedSnapshotId: String?,
        nowMillis: Long,
        ttlMillis: Long,
    ): DeviceErrorCode? {
        if (cached == null) return DeviceErrorCode.NO_SNAPSHOT
        if (requestedSnapshotId.isNullOrBlank() || requestedSnapshotId != cached.id) {
            return DeviceErrorCode.STALE_SNAPSHOT
        }
        if (nowMillis - cached.capturedAtMillis > ttlMillis.coerceAtLeast(1_000)) {
            return DeviceErrorCode.STALE_SNAPSHOT
        }
        return null
    }

    /**
     * 窗口级校验：只比节点指纹不够——另一个页面里同一路径、同样大小、同样的 class 也能"指纹相同"。
     * 所以要求前台包名一致，并且（两边都拿得到时）窗口 id 一致。
     */
    fun checkWindow(
        cached: DeviceSnapshot?,
        livePackage: String?,
        liveWindowId: Int,
    ): DeviceErrorCode? {
        if (cached == null) return DeviceErrorCode.NO_SNAPSHOT
        val pkg = livePackage?.trim().orEmpty()
        if (pkg.isEmpty()) return DeviceErrorCode.STALE_SNAPSHOT
        if (pkg != cached.packageName) return DeviceErrorCode.STALE_SNAPSHOT
        if (cached.windowId >= 0 && liveWindowId >= 0 && cached.windowId != liveWindowId) {
            return DeviceErrorCode.STALE_SNAPSHOT
        }
        return null
    }

    /**
     * @param expected 缓存快照里的节点（调用方给的 node_id 对上来的那一条）
     * @param live 现在重新按路径找到的节点；找不到传 null
     */
    fun checkNode(
        expected: DeviceNode?,
        live: DeviceNode?,
    ): DeviceErrorCode? {
        if (expected == null) return DeviceErrorCode.UNKNOWN_NODE
        if (live == null) return DeviceErrorCode.UNKNOWN_NODE
        if (live.sensitive || expected.sensitive) return DeviceErrorCode.SENSITIVE_FIELD
        if (live.fingerprint != expected.fingerprint) return DeviceErrorCode.STALE_SNAPSHOT
        return null
    }

    /** 节点找不到时明确回 unknown_node，不退回坐标。 */
    fun nodeInSnapshot(snapshot: DeviceSnapshot, nodeId: String): DeviceNode? =
        snapshot.nodes.firstOrNull { it.id == nodeId }

    /** 当前页里是否有节点占着这个坐标（用于判断坐标点是否落在某节点上）。 */
    fun nodeAt(nodes: List<DeviceNode>, x: Int, y: Int): DeviceNode? =
        nodes.firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }

    /**
     * 坐标兜底不能绕过文本保护：点/滑落点压在敏感节点上（密码框、授权设置里的密钥框）时拒绝。
     * 这是纯函数，真机上由 [DeviceAccessibilityRegistry] 用活树喂它。
     */
    fun hitsSensitiveNode(nodes: List<DeviceNode>, x: Int, y: Int): Boolean {
        val hit = nodeAt(nodes, x, y) ?: return false
        return hit.sensitive || nodes.any {
            it.sensitive && x >= it.left && x <= it.right && y >= it.top && y <= it.bottom
        }
    }
}

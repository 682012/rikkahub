package me.rerere.rikkahub.utils

import me.rerere.common.http.NetworkHandover

/**
 * 默认网络回调 → [NetworkHandover] 的纯决策层。
 *
 * 抽出来的唯一原因：这些判断（旧网络的 onLost、同一 handle 的链路变化、VPN 底层换网）
 * 是换网漏判的真正来源，但它们不需要 Android 运行时，可以直接用虚拟时钟单测。
 *
 * 约定：
 * - 只认当前 handle：[onLost]、[onLinkChanged] 收到旧网络的事件一律丢弃，不能抹掉新网络；
 * - 同一 handle 的链路签名变化（VPN 改内部路由 / DNS / 底层链路）算换代，走 [NetworkHandover.notifyRouteRefreshed]；
 * - 首条回调只做基线，不换代；重复同样的回调不换代。
 */
internal class NetworkRouteTracker(
    private val handover: NetworkHandover,
) {
    private var currentKey: String? = null
    private var linkSignature: String? = null
    private var hasLinkSignature = false
    private var underlyingKeys: Set<String>? = null

    /** 默认网络出现或能力变化：这是唯一的可用性来源。 */
    @Synchronized
    fun onRouteState(key: String, available: Boolean) {
        if (currentKey != null && key != currentKey) {
            // 换了句柄：链路签名基线要重来，否则新网络的第一条链路回调会被误判成「又换了一次」
            linkSignature = null
            hasLinkSignature = false
        }
        currentKey = key
        handover.updateRoute(key, available)
    }

    /**
     * 同一个 handle 的链路属性变了。
     *
     * VPN 内部改路由、换底层网络时 [android.net.Network] 句柄不变，只看 onAvailable/onLost 会漏，
     * 于是一直等在旧路由上。这里按链路签名判断，签名真的变了才换代。
     */
    @Synchronized
    fun onLinkChanged(key: String, signature: String) {
        if (currentKey != null && currentKey != key) return
        val previous = linkSignature
        linkSignature = signature
        if (!hasLinkSignature) {
            // 首条链路属性只当基线，避免初始化就算一次换代
            hasLinkSignature = true
            return
        }
        if (previous == signature) return
        currentKey = key
        handover.notifyRouteRefreshed()
    }

    /** 底层物理网络集合变化（VPN 场景下默认网络句柄不变，但路由确实换了）。 */
    @Synchronized
    fun onUnderlyingChanged(keys: Set<String>) {
        val previous = underlyingKeys
        underlyingKeys = keys
        if (previous == null || previous == keys) return
        if (currentKey == null) return
        handover.notifyRouteRefreshed()
    }

    /** 默认网络丢失：只有当前句柄的 lost 才算掉线。 */
    @Synchronized
    fun onLost(key: String) {
        if (currentKey != key) return
        currentKey = null
        linkSignature = null
        hasLinkSignature = false
        handover.updateRoute(null, false)
    }

    /** 完全没有任何默认网络（系统不给 active network，或 onUnavailable）：直接判离线。 */
    @Synchronized
    fun onNoDefaultNetwork() {
        currentKey = null
        linkSignature = null
        hasLinkSignature = false
        handover.updateRoute(null, false)
    }

    /** 拿不到回调时的乐观兜底：保持可用，不要因为权限问题切断用户的网络。 */
    @Synchronized
    fun onMonitorUnavailable() {
        if (!handover.state.value.available) {
            currentKey = null
            hasLinkSignature = false
            linkSignature = null
            handover.updateRoute(null, true)
        }
    }
}

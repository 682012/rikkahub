package me.rerere.rikkahub.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import me.rerere.common.http.NetworkHandover
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 监听 Android 默认网络，把「是否可用 + 是否换代」播给 [NetworkHandover]。
 *
 * 约定：
 * - 只关心默认网络（[ConnectivityManager.registerDefaultNetworkCallback]），不 bindProcessToNetwork，
 *   也不绕过 VPN；
 * - `available` 只看 INTERNET 且未 SUSPENDED，**不要求 VALIDATED**：机场/境内验证探测常出现假阴性，
 *   按 validated 判断会把能用的网络判成不可用；
 * - 旧网络的 onLost 不能抹掉新网络；同一网络的 capabilities 噪声只刷新可用性，不涨 epoch；
 * - VPN 内部改路由 / 换底层网络时 [Network] 句柄不变，只靠 onAvailable/onLost 会漏，所以另外看
 *   onLinkPropertiesChanged 和底层物理网络集合变化，交给 [NetworkRouteTracker] 判换代；
 * - 日志不打 URL / IP / 账号 / 网络标识。
 */
class DefaultNetworkMonitor(
    context: Context,
    val handover: NetworkHandover = NetworkHandover(),
) : Closeable {

    private val appContext: Context = context.applicationContext ?: context

    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    private val tracker = NetworkRouteTracker(handover)

    private val closed = AtomicBoolean(false)

    @Volatile
    private var currentNetwork: Network? = null

    /** 当前能被本进程使用的网络集合（含 VPN 与底层物理网络），只用来发现「句柄没变但路由变了」。 */
    private val underlyingKeys = LinkedHashSet<String>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (closed.get()) return
            currentNetwork = network
            publish(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (closed.get()) return
            val current = currentNetwork
            if (current == network || current == null) {
                currentNetwork = network
                tracker.onRouteState(networkKey(network), isUsable(networkCapabilities))
            }
            // 其它网络的 capabilities 属于噪声，直接丢弃
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (closed.get()) return
            val current = currentNetwork
            if (current != network) return // 旧网络的链路事件，不能拿来换代
            tracker.onLinkChanged(networkKey(network), linkSignature(linkProperties))
        }

        override fun onLost(network: Network) {
            if (closed.get()) return
            // 只有当前网络丢失才算掉线；旧网络的 onLost 不能抹掉新网络
            if (currentNetwork == network) {
                currentNetwork = null
            }
            tracker.onLost(networkKey(network))
        }

        override fun onUnavailable() {
            // registerDefaultNetworkCallback 不会回调这里，留作防御
            if (closed.get()) return
            if (currentNetwork == null) {
                tracker.onNoDefaultNetwork()
            }
        }
    }

    /**
     * 只看「有哪些网络可用」，不看是不是默认网络。
     * VPN 挂在 Wi-Fi 上换成蜂窝时默认网络句柄不变，但这个集合一定会变。
     */
    private val underlyingCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (closed.get()) return
            val changed = synchronized(underlyingKeys) {
                if (underlyingKeys.add(networkKey(network))) snapshotKeys() else null
            } ?: return
            tracker.onUnderlyingChanged(changed)
        }

        override fun onLost(network: Network) {
            if (closed.get()) return
            val changed = synchronized(underlyingKeys) {
                if (underlyingKeys.remove(networkKey(network))) snapshotKeys() else null
            } ?: return
            tracker.onUnderlyingChanged(changed)
        }
    }

    init {
        val manager = connectivityManager
        if (manager == null) {
            tracker.onNoDefaultNetwork()
        } else {
            publishActiveNetwork(manager)
            try {
                manager.registerDefaultNetworkCallback(callback)
            } catch (e: SecurityException) {
                // 保守处理：拿不到回调也不要因此切断网络，保持乐观的可用状态
                Log.w(TAG, "default network callback unavailable, keeping route permissive")
                tracker.onMonitorUnavailable()
            }
            try {
                manager.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    underlyingCallback,
                )
            } catch (e: SecurityException) {
                // 没有这个回调只是发现不了 VPN 底层换网，默认网络回调仍然可用
                Log.w(TAG, "underlying network callback unavailable")
            }
        }
    }

    private fun publishActiveNetwork(manager: ConnectivityManager) {
        val active = try {
            manager.activeNetwork
        } catch (e: SecurityException) {
            null
        }
        if (active == null) {
            currentNetwork = null
            tracker.onNoDefaultNetwork()
        } else {
            currentNetwork = active
            publish(active)
        }
    }

    private fun publish(network: Network, supplied: NetworkCapabilities? = null) {
        val manager = connectivityManager
        if (manager == null) {
            tracker.onNoDefaultNetwork()
            return
        }
        val capabilities = try {
            supplied ?: manager.getNetworkCapabilities(network)
        } catch (e: SecurityException) {
            null
        }
        tracker.onRouteState(networkKey(network), isUsable(capabilities))
    }

    private fun isUsable(capabilities: NetworkCapabilities?): Boolean {
        // 能力暂时读不到时保持乐观：宁可多试一次，也不要把能用的网络判成没网
        if (capabilities == null) return true
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (Build.VERSION.SDK_INT < 28 ||
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
    }

    /**
     * 链路签名：只看「路由走哪儿」，不看地址。
     * 接口名变化（tun0/rmnet/wlan0）或 DNS 变化说明 VPN 内部路由换了；DHCP 续租换 IP 不算。
     */
    private fun linkSignature(linkProperties: LinkProperties): String =
        buildString {
            append(linkProperties.interfaceName ?: "")
            append('|')
            linkProperties.dnsServers.forEach { append(it.hostAddress).append(',') }
        }

    private fun snapshotKeys(): Set<String> = LinkedHashSet(underlyingKeys)

    /** 进程内网络标识，只用 [Network] 的句柄值，不含地址。 */
    private fun networkKey(network: Network?): String = network?.toString() ?: "none"

    /** 幂等：重复 close 不会重复注销，也不会抛。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        currentNetwork = null
        synchronized(underlyingKeys) { underlyingKeys.clear() }
        val manager = connectivityManager ?: return
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // 没注册成功时注销会抛 IllegalArgumentException，忽略
        } catch (e: SecurityException) {
            // 权限被回收时同样忽略
        }
        try {
            manager.unregisterNetworkCallback(underlyingCallback)
        } catch (e: IllegalArgumentException) {
            // 同上
        } catch (e: SecurityException) {
            // 同上
        }
    }

    private companion object {
        const val TAG = "DefaultNetworkMonitor"
    }
}

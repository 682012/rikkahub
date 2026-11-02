package me.rerere.rikkahub.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
 * - 日志不打 URL / IP / 账号 / 网络标识。
 */
class DefaultNetworkMonitor(
    context: Context,
    val handover: NetworkHandover = NetworkHandover(),
) : Closeable {

    private val appContext: Context = context.applicationContext ?: context

    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    private val closed = AtomicBoolean(false)

    @Volatile
    private var currentNetwork: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (closed.get()) return
            currentNetwork = network
            publish(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (closed.get()) return
            val current = currentNetwork
            if (current == network) publish(network, networkCapabilities)
            // 其它网络的 capabilities 属于噪声，直接丢弃
        }

        override fun onLost(network: Network) {
            if (closed.get()) return
            // 只有当前网络丢失才算掉线；旧网络的 onLost 不能抹掉新网络
            if (currentNetwork == network) {
                currentNetwork = null
                handover.updateRoute(null, false)
            }
        }

        override fun onUnavailable() {
            // registerDefaultNetworkCallback 不会回调这里，留作防御
            if (closed.get()) return
            if (currentNetwork == null) {
                handover.updateRoute(null, false)
            }
        }
    }

    init {
        val manager = connectivityManager
        if (manager == null) {
            handover.updateRoute(null, false)
        } else {
            publishActiveNetwork(manager)
            try {
                manager.registerDefaultNetworkCallback(callback)
            } catch (e: SecurityException) {
                // 保守处理：拿不到回调也不要因此切断网络，保持乐观的可用状态
                Log.w(TAG, "default network callback unavailable, keeping route permissive")
                if (!handover.state.value.available) {
                    handover.updateRoute(null, true)
                }
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
            handover.updateRoute(null, false)
        } else {
            currentNetwork = active
            publish(active)
        }
    }

    private fun publish(network: Network, supplied: NetworkCapabilities? = null) {
        val manager = connectivityManager
        if (manager == null) {
            handover.updateRoute(null, false)
            return
        }
        val capabilities = try {
            supplied ?: manager.getNetworkCapabilities(network)
        } catch (e: SecurityException) {
            null
        }
        val available = capabilities == null || (
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (Build.VERSION.SDK_INT < 28 ||
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)))
        handover.updateRoute(networkKey(network), available)
    }

    /** 进程内网络标识，只用 [Network.toString] 的句柄，不含地址。 */
    private fun networkKey(network: Network): String = network.toString()

    /** 幂等：重复 close 不会重复注销，也不会抛。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        currentNetwork = null
        val manager = connectivityManager ?: return
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // 没注册成功时注销会抛 IllegalArgumentException，忽略
        } catch (e: SecurityException) {
            // 权限被回收时同样忽略
        }
    }

    private companion object {
        const val TAG = "DefaultNetworkMonitor"
    }
}

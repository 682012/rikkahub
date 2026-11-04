package me.rerere.rikkahub.utils

import kotlinx.coroutines.flow.StateFlow
import me.rerere.common.http.NetworkHandover
import me.rerere.common.http.NetworkRouteState
import me.rerere.common.http.RouteWaiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换网漏判的真实来源（旧网络 onLost、同一 handle 的链路变化、VPN 底层换网）
 * 全部在 [NetworkRouteTracker] 里判，用虚拟时钟直接验证，不需要 Android 运行时。
 */
class NetworkRouteTrackerTest {

    private val clock = FakeClock()
    private val handover = NetworkHandover(clock)
    private val tracker = NetworkRouteTracker(handover)

    @Test
    fun `old network lost never wipes the new one`() {
        tracker.onRouteState("wifi", true)
        tracker.onRouteState("cell", true)
        val epoch = handover.state.value.epoch

        // 旧网络的 onLost 迟到：不能把新网络判成掉线
        tracker.onLost("wifi")

        assertTrue(handover.state.value.available)
        assertEquals(epoch, handover.state.value.epoch)

        // 当前网络真的丢了才算掉线，恢复时算一次换代
        tracker.onLost("cell")
        assertFalse(handover.state.value.available)
        tracker.onRouteState("cell", true)
        assertTrue(handover.state.value.available)
        assertEquals(epoch + 1L, handover.state.value.epoch)
    }

    @Test
    fun `same handle link change bumps generation once per real change`() {
        tracker.onRouteState("vpn", true)
        val epoch = handover.state.value.epoch

        // 首条链路属性只当基线
        tracker.onLinkChanged("vpn", "tun0|10.0.0.1")
        assertEquals(epoch, handover.state.value.epoch)

        // VPN 内部路由/底层 DNS 变了，但 Network 句柄没变
        clock.now = 2_000L
        tracker.onLinkChanged("vpn", "tun0|192.168.1.1")
        assertEquals(epoch + 1L, handover.state.value.epoch)

        // 同样的签名重复上报不再换代
        clock.now = 5_000L
        tracker.onLinkChanged("vpn", "tun0|192.168.1.1")
        assertEquals(epoch + 1L, handover.state.value.epoch)
    }

    @Test
    fun `distinct link signatures each produce a handover`() {
        tracker.onRouteState("vpn", true)
        tracker.onLinkChanged("vpn", "tun0|a")
        val epoch = handover.state.value.epoch

        // 链路签名由 tracker 按值去重；不同签名代表真实路由变化，即使连续到达也不能吞掉。
        clock.now = 1_500L
        tracker.onLinkChanged("vpn", "tun0|b")
        assertEquals(epoch + 1L, handover.state.value.epoch)

        clock.now = 1_800L
        tracker.onLinkChanged("vpn", "tun0|c")
        assertEquals(epoch + 2L, handover.state.value.epoch)

        clock.now = 3_000L
        tracker.onLinkChanged("vpn", "tun0|c")
        assertEquals(epoch + 2L, handover.state.value.epoch)
    }

    @Test
    fun `underlying network swap behind the vpn counts as a handover`() {
        tracker.onRouteState("vpn", true)
        tracker.onUnderlyingChanged(setOf("vpn", "wifi"))
        val epoch = handover.state.value.epoch

        // Wi-Fi 换成蜂窝，VPN 句柄不变
        tracker.onUnderlyingChanged(setOf("vpn", "cell"))

        assertTrue(handover.state.value.available)
        assertEquals(epoch + 1L, handover.state.value.epoch)
    }

    @Test
    fun `link events for an old network and while offline do not change generation`() {
        tracker.onRouteState("vpn", true)
        tracker.onLinkChanged("vpn", "tun0|a")
        val epoch = handover.state.value.epoch

        // 旧网络的链路事件
        clock.now = 5_000L
        tracker.onLinkChanged("wifi", "wlan0|b")
        assertEquals(epoch, handover.state.value.epoch)

        // 离线期间不换代，等真恢复时统一算
        tracker.onRouteState("vpn", false)
        val offline = handover.state.value.epoch
        tracker.onLinkChanged("vpn", "tun0|c")
        tracker.onUnderlyingChanged(setOf("wifi"))
        assertEquals(offline, handover.state.value.epoch)

        tracker.onRouteState("vpn", true)
        assertEquals(offline + 1L, handover.state.value.epoch)
    }

    @Test
    fun `no default network is offline even without a lost callback`() {
        tracker.onRouteState("wifi", true)
        tracker.onNoDefaultNetwork()
        assertFalse(handover.state.value.available)

        tracker.onRouteState("wifi", true)
        assertTrue(handover.state.value.available)
    }

    private class FakeClock : RouteWaiter {
        var now: Long = 0L

        override suspend fun awaitAvailable(state: StateFlow<NetworkRouteState>, timeoutMillis: Long): Boolean =
            error("tracker must not wait")

        override suspend fun sleep(millis: Long) = error("tracker must not wait")

        override fun nowMillis(): Long = now
    }
}

package me.rerere.rikkahub

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.utils.DefaultNetworkMonitor
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Radio changes are allowed ONLY in our isolated emulator, never on a user's phone. */
@RunWith(AndroidJUnit4::class)
class NetworkHandoverAndroidTest {
    @Test
    fun realAndroidDefaultNetworkLossAndRecoveryAdvancesEpoch() = runBlocking {
        assumeTrue("This test may change radios only in a QEMU emulator", shell("getprop ro.kernel.qemu").trim() == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val monitor = DefaultNetworkMonitor(context)
        try {
            shell("svc wifi enable"); shell("svc data enable")
            val online = withTimeout(30_000) { monitor.handover.state.first { it.available } }
            shell("svc wifi disable"); shell("svc data disable")
            withTimeout(30_000) { monitor.handover.state.first { !it.available } }
            val resumed = async { monitor.handover.awaitUsable(30_000) }
            shell("svc wifi enable"); shell("svc data enable")
            assertTrue(resumed.await())
            val restored = withTimeout(30_000) {
                monitor.handover.state.first { it.available && it.epoch > online.epoch }
            }
            assertTrue(restored.epoch > online.epoch)
        } finally {
            shell("svc wifi enable"); shell("svc data enable")
            monitor.close()
            monitor.close()
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

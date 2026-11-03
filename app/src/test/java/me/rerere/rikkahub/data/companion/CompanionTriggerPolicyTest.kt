package me.rerere.rikkahub.data.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自动互动的闸门：去重、冷却、每小时预算、在途只有一个。
 *
 * 全部是纯函数 + 显式时间，没有 sleep，也没有任何"稍后再说"的定时器。
 */
class CompanionTriggerPolicyTest {
    private val tap = CompanionTrigger(CompanionTriggerKind.TAP)
    private val event = CompanionTrigger(
        kind = CompanionTriggerKind.EVENT,
        event = CompanionEventKind.GENERATION_FINISHED,
        eventKey = "finish:conv:1",
    )

    private fun CompanionTriggerPolicy.check(
        trigger: CompanionTrigger,
        now: Long,
        visible: Boolean = true,
        aiEnabled: Boolean = true,
        autoEnabled: Boolean = true,
        busy: Boolean = false,
    ) = evaluate(
        trigger = trigger,
        now = now,
        visible = visible,
        aiEnabled = aiEnabled,
        autoEnabled = autoEnabled,
        busy = busy,
    )

    @Test
    fun `manual tap is allowed when idle and visible`() {
        val policy = CompanionTriggerPolicy()
        assertNull(policy.check(tap, now = 1_000))
    }

    @Test
    fun `nothing is sent while the bubble is not shown`() {
        val policy = CompanionTriggerPolicy()
        assertEquals(CompanionSkipReason.NOT_VISIBLE, policy.check(tap, 1_000, visible = false))
    }

    @Test
    fun `nothing is sent while ai responses are off`() {
        val policy = CompanionTriggerPolicy()
        assertEquals(CompanionSkipReason.AI_OFF, policy.check(tap, 1_000, aiEnabled = false))
    }

    @Test
    fun `only one request may be in flight`() {
        val policy = CompanionTriggerPolicy()
        assertEquals(CompanionSkipReason.BUSY, policy.check(tap, 1_000, busy = true))
    }

    @Test
    fun `double tap inside the manual cooldown is dropped`() {
        val policy = CompanionTriggerPolicy(CompanionPolicyConfig(manualCooldownMillis = 2_000))
        policy.record(tap, 1_000)
        assertEquals(CompanionSkipReason.COOLDOWN, policy.check(tap, 2_500))
        assertNull(policy.check(tap, 3_000))
    }

    @Test
    fun `auto events need the explicit switch`() {
        val policy = CompanionTriggerPolicy()
        assertEquals(
            CompanionSkipReason.AUTO_EVENTS_OFF,
            policy.check(event, 1_000, autoEnabled = false),
        )
        // 手动点击不受这个开关影响
        assertNull(policy.check(tap, 1_000, autoEnabled = false))
    }

    @Test
    fun `auto events respect the global cooldown`() {
        val policy = CompanionTriggerPolicy(CompanionPolicyConfig(autoCooldownMillis = 45_000))
        policy.record(event, 0)
        val other = event.copy(eventKey = "finish:conv:2")
        assertEquals(CompanionSkipReason.COOLDOWN, policy.check(other, 30_000))
        assertNull(policy.check(other, 45_000))
    }

    @Test
    fun `the same event key is never answered twice inside the dedupe window`() {
        val policy = CompanionTriggerPolicy(
            CompanionPolicyConfig(autoCooldownMillis = 0, sameEventCooldownMillis = 300_000),
        )
        policy.record(event, 0)
        assertEquals(CompanionSkipReason.DUPLICATE_EVENT, policy.check(event, 60_000))
        assertEquals(CompanionSkipReason.DUPLICATE_EVENT, policy.check(event.copy(), 299_999))
        assertNull(policy.check(event, 300_000))
    }

    @Test
    fun `hourly budget stops auto events until the window slides`() {
        val policy = CompanionTriggerPolicy(
            CompanionPolicyConfig(autoCooldownMillis = 0, sameEventCooldownMillis = 0, maxAutoPerHour = 3),
        )
        repeat(3) { index ->
            val trigger = event.copy(eventKey = "finish:conv:$index")
            assertNull(policy.check(trigger, index * 1_000L))
            policy.record(trigger, index * 1_000L)
        }
        assertEquals(
            CompanionSkipReason.BUDGET,
            policy.check(event.copy(eventKey = "finish:conv:new"), 10_000),
        )
        // 一小时后老的记录滑出去，预算恢复
        assertNull(policy.check(event.copy(eventKey = "finish:conv:new"), 3_700_000))
    }

    @Test
    fun `manual taps do not consume the auto budget`() {
        val policy = CompanionTriggerPolicy(
            CompanionPolicyConfig(manualCooldownMillis = 0, maxAutoPerHour = 1),
        )
        repeat(10) { index -> policy.record(tap, index * 1_000L) }
        assertNull(policy.check(event, 100_000))
    }

    @Test
    fun `reset clears cooldown and budget`() {
        val policy = CompanionTriggerPolicy(CompanionPolicyConfig(autoCooldownMillis = 10_000))
        policy.record(event, 0)
        assertEquals(CompanionSkipReason.COOLDOWN, policy.check(event.copy(eventKey = "x"), 1_000))
        policy.reset()
        assertNull(policy.check(event.copy(eventKey = "x"), 1_000))
    }
}

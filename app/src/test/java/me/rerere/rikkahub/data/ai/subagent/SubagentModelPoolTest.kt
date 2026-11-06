package me.rerere.rikkahub.data.ai.subagent

import me.rerere.rikkahub.data.datastore.SubagentModelStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 有序模型池：白名单、去重、顺序、轮转、批量分配。
 *
 * 全部是纯函数，不碰 Android；这里验证的是"用户配了几个渠道就能用几个"，
 * 而不是被硬编码成"一对主备"。
 */
class SubagentModelPoolTest {
    private val a: Uuid = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val b: Uuid = Uuid.parse("22222222-2222-2222-2222-222222222222")
    private val c: Uuid = Uuid.parse("33333333-3333-3333-3333-333333333333")
    private val outside: Uuid = Uuid.parse("99999999-9999-9999-9999-999999999999")

    @Test
    fun whitelistIsStrictAndOrderedWithDefaultFirst() {
        // default 在池内 -> 排第一，其余保持用户配置顺序
        assertEquals(listOf(b, a, c), resolveSubagentModelPool(listOf(a, b, c), defaultModelId = b))
        // default 在池外 -> 忽略，绝不把池外模型偷偷加进来
        assertEquals(listOf(a, b), resolveSubagentModelPool(listOf(a, b), defaultModelId = outside))
        // fallback 在池外同样忽略（白名单优先于兜底）
        assertEquals(
            listOf(a),
            resolveSubagentModelPool(listOf(a, a, a), defaultModelId = outside, fallbackModelId = outside),
        )
    }

    @Test
    fun emptyPoolKeepsLegacyDefaultThenFallbackOrder() {
        assertEquals(listOf(a, b), resolveSubagentModelPool(emptyList(), defaultModelId = a, fallbackModelId = b))
        // 没有 default 时跟随父会话模型（r21 语义），再补 fallback
        assertEquals(listOf(b, a), resolveSubagentModelPool(emptyList(), parentModelId = b, fallbackModelId = a))
        // 什么都没有就是空，调用方报错，而不是去全局设置里借一个跨助手模型
        assertEquals(emptyList<Uuid>(), resolveSubagentModelPool(emptyList()))
    }

    @Test
    fun attemptPlanPutsFirstChoiceFirstAndDedupes() {
        assertEquals(listOf(b, a, c), planSubagentAttempts(listOf(a, b, c), first = b))
        // first 不在池里 -> 按池顺序，绝不越界
        assertEquals(listOf(a, b), planSubagentAttempts(listOf(a, b), first = outside))
        // maxAttempts 夹在池大小内，<=0 表示不限但不超过池
        assertEquals(listOf(b), planSubagentAttempts(listOf(a, b, c), first = b, maxAttempts = 1))
        assertEquals(listOf(b, a, c), planSubagentAttempts(listOf(a, b, c), first = b, maxAttempts = 99))
        assertEquals(listOf(b, a, c), planSubagentAttempts(listOf(a, b, c), first = b, maxAttempts = 0))
        assertTrue(planSubagentAttempts(emptyList(), first = a).isEmpty())
    }

    @Test
    fun balancedStrategySpreadsConcurrentTasksAcrossChannels() {
        val router = SubagentChannelRouter()
        val pool = listOf(a, b, c)
        // ORDERED：三个独立任务都从池首起步（可预测、省钱）
        repeat(3) { assertEquals(a, router.pick(pool, SubagentModelStrategy.ORDERED, key = "conv")) }
        // BALANCED：同样三次 pick 落在三个不同渠道上，不会一起打主模型
        val picked = List(3) { router.pick(pool, SubagentModelStrategy.BALANCED, key = "conv") }
        assertEquals(listOf(a, b, c), picked)
        // 游标继续轮转
        assertEquals(a, router.pick(pool, SubagentModelStrategy.BALANCED, key = "conv"))
        // 不同的 key（另一个会话）独立轮转
        assertEquals(a, router.pick(pool, SubagentModelStrategy.BALANCED, key = "other"))
    }

    @Test
    fun balancedStrategySkipsSaturatedModelsButNeverReturnsNull() {
        val router = SubagentChannelRouter()
        val pool = listOf(a, b)
        val active = mapOf(a to 2, b to 0)
        assertEquals(
            b,
            router.pick(pool, SubagentModelStrategy.BALANCED, "k", active = { active[it] ?: 0 }, perModelLimit = 2),
        )
        // 全满时仍给一个渠道（让全局 permit 去排队），不丢任务
        val full = mapOf(a to 5, b to 5)
        assertEquals(
            a,
            router.pick(pool, SubagentModelStrategy.BALANCED, "k2", active = { full[it] ?: 0 }, perModelLimit = 2),
        )
        assertNull(router.pick(emptyList(), SubagentModelStrategy.BALANCED))
    }

    @Test
    fun teamMembersAreSpreadOverDistinctChannelsAndWhiteListed() {
        val members = listOf(
            SubagentTeamMember(role = "researcher", prompt = "find sources"),
            SubagentTeamMember(role = "implementer", prompt = "write code"),
            SubagentTeamMember(role = "reviewer", prompt = "review it"),
        )
        val assigned = assignSubagentTeamModels(members, listOf(a, b, c), SubagentModelStrategy.BALANCED)
        assertEquals(listOf(a, b, c), assigned.map { it.modelId })
        assertEquals(listOf("researcher", "implementer", "reviewer"), assigned.map { it.role })

        // ORDERED：全员都打池首（用户明确选择可预测优先）
        assertEquals(
            listOf(a, a, a),
            assignSubagentTeamModels(members, listOf(a, b), SubagentModelStrategy.ORDERED).map { it.modelId },
        )

        // 显式 model_id 优先且必须在白名单里
        val explicit = assignSubagentTeamModels(
            listOf(SubagentTeamMember("solo", "do it", c)),
            listOf(a, b, c),
        )
        assertEquals(c, explicit.single().modelId)
        val rejected = runCatching {
            assignSubagentTeamModels(listOf(SubagentTeamMember("solo", "do it", outside)), listOf(a, b))
        }
        assertTrue(rejected.isFailure)

        // 空白 prompt 被丢弃，重名 role 加序号
        val cleaned = assignSubagentTeamModels(
            listOf(
                SubagentTeamMember("worker", "one"),
                SubagentTeamMember("worker", "two"),
                SubagentTeamMember("worker", "   "),
            ),
            listOf(a),
        )
        assertEquals(listOf("worker", "worker-2"), cleaned.map { it.role })
        assertTrue(runCatching { assignSubagentTeamModels(emptyList(), listOf(a)) }.isFailure)
    }
}

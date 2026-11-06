package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.agent.RetryErrorCategory
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.RetryScope
import me.rerere.ai.agent.RetryScopeMode
import me.rerere.ai.agent.withRetry
import me.rerere.ai.util.HttpException
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.workspace.WorkspacePrivilegeException
import me.rerere.rikkahub.data.workspace.WorkspacePrivilegeFailure
import me.rerere.rikkahub.service.ChatAutoRecovery
import me.rerere.rikkahub.service.ChatRecoveryBlocker
import me.rerere.rikkahub.service.ChatRecoveryLedger
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 「任何错误也重试」从设置到执行层的贯通：Settings → RetryPolicy → withRetry / ChatAutoRecovery。
 *
 * 断言的重点是"界面勾的和实际做的必须是同一件事"，以及默认值一个字都不变。
 */
class RetryScopeWiringTest {

    private fun setting(
        mode: RetryScopeMode = RetryScopeMode.CLASSIFIED,
        categories: Set<RetryErrorCategory> = emptySet(),
        unlimited: Boolean = false,
        maxRetries: Int = 3,
    ) = NetworkSetting(
        retryScopeMode = mode,
        retryExtraCategories = categories,
        retryUnlimited = unlimited,
        maxRetries = maxRetries,
    )

    // ---- 默认值：老行为一字不改 ----------------------------------------------

    @Test
    fun `default setting keeps the old classification and count`() {
        val policy = Settings().retryPolicy()

        assertEquals(3, policy.maxRetries)
        assertEquals(RetryScope.CLASSIFIED, policy.scope)
        assertFalse(policy.unlimitedRetries)
        assertTrue(policy.retryOn(IOException("reset")))
        assertTrue(policy.retryOn(HttpException("boom", 503)))
        assertFalse("永久错误默认不试", policy.retryOn(HttpException("unauthorized", 401)))
        assertFalse(policy.retryOn(IllegalStateException("vendor bug")))
    }

    @Test
    fun `master switch off still disables everything`() {
        val policy = Settings()
            .copy(networkSetting = setting(mode = RetryScopeMode.ANY_ERROR, unlimited = true))
            .let { it.copy(networkSetting = it.networkSetting.copy(enableAutoRetry = false)) }
            .retryPolicy()

        assertEquals(RetryPolicy.NONE, policy)
        assertFalse(policy.retryOn(HttpException("unauthorized", 401)))
    }

    // ---- 三种模式真的落到请求层 ----------------------------------------------

    @Test
    fun `any error mode retries a real 401`() = runTest {
        val policy = setting(mode = RetryScopeMode.ANY_ERROR).toRetryPolicy()
        var attempts = 0
        runCatching {
            withRetry(policy) {
                attempts++
                throw HttpException("unauthorized", 401)
            }
        }
        assertEquals(4, attempts) // 首发 + 3 次重试
    }

    @Test
    fun `custom mode retries only the checked categories and keeps the defaults`() = runTest {
        val authOnly = setting(
            mode = RetryScopeMode.CUSTOM,
            categories = setOf(RetryErrorCategory.AUTH),
        ).toRetryPolicy()
        assertTrue(authOnly.retryOn(HttpException("unauthorized", 401)))
        assertFalse(authOnly.retryOn(HttpException("payment required", 402)))
        assertTrue("默认分类还在", authOnly.retryOn(IOException("reset")))

        var quotaAttempts = 0
        runCatching {
            withRetry(authOnly) {
                quotaAttempts++
                throw HttpException("payment required", 402)
            }
        }
        assertEquals(1, quotaAttempts)
    }

    @Test
    fun `unknown custom provider exception is replayed only when opted in`() = runTest {
        val custom = IllegalStateException("vendor returned nonsense")

        assertFalse(setting().toRetryPolicy().retryOn(custom))
        assertTrue(
            setting(
                mode = RetryScopeMode.CUSTOM,
                categories = setOf(RetryErrorCategory.UNKNOWN),
            ).toRetryPolicy().retryOn(custom),
        )

        var attempts = 0
        runCatching {
            withRetry(
                setting(
                    mode = RetryScopeMode.ANY_ERROR,
                    maxRetries = 2,
                ).toRetryPolicy()
            ) {
                attempts++
                throw custom
            }
        }
        assertEquals(3, attempts)
    }

    @Test
    fun `http exception without a real status code is not guessed into auth`() = runTest {
        // 只有消息文本里出现 401，没有真实状态码：默认按格式错误拦下，不猜成鉴权
        val unparsed = HttpException("provider body said 401")
        assertFalse(setting().toRetryPolicy().retryOn(unparsed))
        assertTrue(
            setting(
                mode = RetryScopeMode.CUSTOM,
                categories = setOf(RetryErrorCategory.REQUEST_FORMAT),
            ).toRetryPolicy().retryOn(unparsed),
        )

        var attempts = 0
        runCatching {
            withRetry(setting(mode = RetryScopeMode.ANY_ERROR, maxRetries = 1).toRetryPolicy()) {
                attempts++
                throw unparsed
            }
        }
        assertEquals(2, attempts)
    }

    // ---- 无限重试：次数不再封顶，但取消仍然立刻停 ----------------------------

    @Test
    fun `unlimited retries ignores the configured count`() = runTest {
        val policy = setting(unlimited = true, maxRetries = 1).toRetryPolicy()

        var attempts = 0
        withRetry(policy) {
            attempts++
            if (attempts < 6) throw IOException("still down")
            "ok"
        }
        assertEquals(6, attempts)
    }

    @Test
    fun `unlimited retries still respects cancellation`() = runTest {
        val policy = setting(mode = RetryScopeMode.ANY_ERROR, unlimited = true).toRetryPolicy()
        var attempts = 0
        val result = runCatching {
            withRetry(policy) {
                attempts++
                throw kotlinx.coroutines.CancellationException("user stopped")
            }
        }
        assertTrue(result.isFailure)
        assertEquals(1, attempts)
    }

    // ---- 整轮自动恢复：同一份范围，安全边界不放行 ----------------------------

    @Test
    fun `auto recovery follows the scope but keeps the safety boundaries`() {
        val unauthorized = HttpException("unauthorized", 401)
        val custom = IllegalStateException("vendor returned nonsense")

        assertEquals(ChatRecoveryBlocker.AUTH, ChatAutoRecovery.blockReason(unauthorized))
        assertNull(ChatAutoRecovery.blockReason(unauthorized, scope = RetryScope.ANY_ERROR))
        assertEquals(ChatRecoveryBlocker.OTHER, ChatAutoRecovery.blockReason(custom))
        assertNull(ChatAutoRecovery.blockReason(custom, scope = RetryScope.ANY_ERROR))

        // 安全边界：任何模式都不自动接
        assertEquals(
            ChatRecoveryBlocker.USER_STOPPED,
            ChatAutoRecovery.blockReason(unauthorized, cancelled = true, scope = RetryScope.ANY_ERROR),
        )
        assertEquals(
            ChatRecoveryBlocker.APPROVAL_PENDING,
            ChatAutoRecovery.blockReason(unauthorized, awaitingApproval = true, scope = RetryScope.ANY_ERROR),
        )
        val noCheckpoint = ChatAutoRecovery.blockReason(unauthorized, canResume = false, scope = RetryScope.ANY_ERROR)
        assertEquals(ChatRecoveryBlocker.UNKNOWN_SIDE_EFFECT, noCheckpoint)
        assertEquals(
            ChatRecoveryBlocker.UNKNOWN_SIDE_EFFECT,
            ChatAutoRecovery.blockReason(unauthorized, hasCommittedSideEffects = true, scope = RetryScope.ANY_ERROR),
        )
        assertEquals(
            ChatRecoveryBlocker.PERMISSION,
            ChatAutoRecovery.blockReason(
                WorkspacePrivilegeException(WorkspacePrivilegeFailure.SHIZUKU_PERMISSION_DENIED, "denied"),
                scope = RetryScope.ANY_ERROR,
            ),
        )
    }

    @Test
    fun `cancelled errors never auto recover in any mode`() {
        val policy = setting(mode = RetryScopeMode.ANY_ERROR).toRetryPolicy()
        assertTrue(policy.scope.triesEverything)
        assertNotNull(ChatAutoRecovery.blockReason(kotlinx.coroutines.CancellationException("stop"), scope = policy.scope))
    }

    // ---- 乘法膨胀：单请求重试 × 整轮拉起必须是线性 --------------------------

    @Test
    fun `auto recovery turns do not retry inside the request`() {
        val policy = setting(maxRetries = 3).toRetryPolicy()

        assertEquals(3, ChatAutoRecovery.requestRetryAllowance(policy, turnAttempts = 0))
        assertEquals("自动恢复轮内层不再重试", 0, ChatAutoRecovery.requestRetryAllowance(policy, turnAttempts = 1))
        assertEquals(0, ChatAutoRecovery.requestRetryAllowance(policy, turnAttempts = 99))

        // 外层整轮上限 = 用户次数；总请求 = (N+1) 首发轮 + N 恢复轮 = 2N+1，线性。
        // 旧实现逐轮递减（N, N-1, ..., 1）合计仍是 N(N+1)/2，平方只是减半，这里不重复那个错误。
        val turnLimit = ChatAutoRecovery.turnAttemptLimit(policy)
        assertEquals(3, turnLimit)
        val firstTurn = 1 + ChatAutoRecovery.requestRetryAllowance(policy, turnAttempts = 0)
        val recoveryTurns = (1..turnLimit).sumOf {
            1 + ChatAutoRecovery.requestRetryAllowance(policy, turnAttempts = it)
        }
        assertEquals(4 + 3, firstTurn + recoveryTurns)
        assertTrue(ChatAutoRecovery.turnExhausted(policy, attempts = 3))
        assertFalse(ChatAutoRecovery.turnExhausted(policy, attempts = 2))
    }

    @Test
    fun `unlimited turn recovery really keeps going until stopped`() {
        val policy = setting(unlimited = true, maxRetries = 1).toRetryPolicy()
        assertEquals(Int.MAX_VALUE, ChatAutoRecovery.turnAttemptLimit(policy))
        assertFalse("不再有 20 次这种和'直到停止'矛盾的硬上限", ChatAutoRecovery.turnExhausted(policy, attempts = 100_000))
        // 内层在恢复轮仍旧关闭：外层无限就够了，不能外层无限 × 内层无限
        assertEquals(0, ChatAutoRecovery.requestRetryAllowance(policy, turnAttempts = 5))
    }

    @Test
    fun `settings snapshot switches the inner retry off on recovery turns`() {
        val settings = Settings().copy(networkSetting = setting(maxRetries = 3))
        val narrowed = ChatAutoRecovery.settingsForTurnRecovery(settings, turnAttempts = 2)

        assertEquals(0, narrowed.networkSetting.maxRetries)
        assertFalse(narrowed.networkSetting.retryUnlimited)
        assertEquals(settings.networkSetting.enableAutoRetry, narrowed.networkSetting.enableAutoRetry)
        assertEquals(settings.networkSetting.retryScopeMode, narrowed.networkSetting.retryScopeMode)
        assertEquals(
            "第一次恢复前不动",
            settings,
            ChatAutoRecovery.settingsForTurnRecovery(settings, turnAttempts = 0),
        )

        val unlimited = Settings().copy(networkSetting = setting(maxRetries = 1, unlimited = true))
        val narrowedUnlimited = ChatAutoRecovery.settingsForTurnRecovery(unlimited, turnAttempts = 1)
        assertEquals(0, narrowedUnlimited.networkSetting.maxRetries)
        assertFalse("无限标记也要关，否则 0 次会被它重新打开", narrowedUnlimited.networkSetting.retryUnlimited)
        assertFalse(narrowedUnlimited.retryPolicy().unlimitedRetries)
        assertTrue(narrowedUnlimited.retryPolicy().maxRetries == 0)
    }

    @Test
    fun `ledger accepts an unlimited turn budget`() {
        val ledger = ChatRecoveryLedger()
        val id = Uuid.random()
        repeat(150) { assertTrue(ledger.noteFailure(id, Int.MAX_VALUE)) }
        assertEquals(150, ledger.attempts(id))
    }

    // ---- 总开关：实时生效，不是快照 ------------------------------------------

    @Test
    fun `request policy reads the master switch instead of the snapshot`() = runTest {
        val policy = setting(maxRetries = 2).toRetryPolicy()
        var attempts = 0
        RetrySwitch.install(false)
        try {
            runCatching { withRetry(policy) { attempts++; throw IOException("reset") } }
            assertEquals("关掉开关后不再排重试", 1, attempts)
        } finally {
            RetrySwitch.reset()
        }
        attempts = 0
        runCatching { withRetry(policy) { attempts++; throw IOException("reset") } }
        assertEquals(3, attempts)
    }

    // ---- 老配置兼容 ----------------------------------------------------------

    @Test
    fun `old stored json without the new fields still decodes to the old behavior`() {
        val stored = """{"userAgent":"ua","proxyUrl":"","proxyUsername":"","proxyPassword":"","enableAutoRetry":true,"maxRetries":5}"""

        val decoded = JsonInstant.decodeFromString<NetworkSetting>(stored)

        assertEquals(5, decoded.maxRetries)
        assertEquals(RetryScopeMode.CLASSIFIED, decoded.retryScopeMode)
        assertTrue(decoded.retryExtraCategories.isEmpty())
        assertFalse(decoded.retryUnlimited)
        assertEquals(RetryScope.CLASSIFIED, decoded.retryScope())
    }

    @Test
    fun `new fields round trip through json`() {
        val json = Json { encodeDefaults = true }
        val original = setting(
            mode = RetryScopeMode.CUSTOM,
            categories = setOf(RetryErrorCategory.AUTH, RetryErrorCategory.UNKNOWN),
            unlimited = true,
        )

        val decoded = json.decodeFromString<NetworkSetting>(json.encodeToString(NetworkSetting.serializer(), original))

        assertEquals(original, decoded)
        assertEquals(
            setOf(RetryErrorCategory.AUTH, RetryErrorCategory.UNKNOWN),
            decoded.retryScope().effectiveCategories - RetryScope.LEGACY_CATEGORIES,
        )
    }
}

package me.rerere.rikkahub.data.mcpserver

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * r22 本机 MCP 的**边界**测试（纯 JVM：不碰 Android、不碰 Koin、不碰真实端口）。
 *
 * 只测本次审查修掉的那几条"改回去就会重新开洞"的规则：
 * 1. 连接级闸门顺序：停服/关开关 → 503（不执行 host）先于鉴权，鉴权先于 405，GET/DELETE 也要鉴权；
 * 2. 幂等账本的归属域：同一个 key 在另一个绑定助手下不能回放上一个助手的结果；
 * 3. 幂等账本写不进去时必须 fail closed（宁可不执行，也不能"执行了但没有重放保护"）；
 * 4. 自动化配方归属：没有归属记录的配方不通过 MCP 暴露，换助手就看不见/跑不了。
 */
class R22RuntimeBoundaryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val token = "lmcp_" + "A".repeat(36)
    private val port = 8766
    private val goodHost = "127.0.0.1:$port"
    private val auth = "Bearer $token"

    // ---------------------------------------------------------------- 连接级闸门

    @Test
    fun `stopped server rejects before it looks at credentials or method`() {
        // 没有 token、还是 GET：正常来说会是 401/405，但服务已经不在接连接 → 必须 503。
        val reject = decide(
            accepting = false,
            enabled = true,
            method = "GET",
            authorization = null,
        ) as LocalMcpHttpDecision.Reject
        assertEquals(503, reject.status)
        assertEquals("server_unavailable", reject.message)
        assertEquals("1", reject.retryAfterSeconds)
    }

    @Test
    fun `disabled switch rejects with 503 before any tool could run`() {
        val reject = decide(
            accepting = true,
            enabled = false,
            method = "POST",
            authorization = auth,
        ) as LocalMcpHttpDecision.Reject
        assertEquals(503, reject.status)
        assertEquals("server_unavailable", reject.message)
    }

    @Test
    fun `get and delete are authenticated before they may answer 405`() {
        val unauthenticated = decide(
            accepting = true,
            enabled = true,
            method = "GET",
            authorization = null,
        ) as LocalMcpHttpDecision.Reject
        assertEquals(401, unauthenticated.status)
        assertTrue("401 must ask for Bearer", unauthenticated.wwwAuthenticate)
        assertNull("405 must not be reachable without a token", unauthenticated.allowHeader)

        val wrongToken = decide(
            accepting = true,
            enabled = true,
            method = "DELETE",
            authorization = "Bearer nope-nope-nope",
        ) as LocalMcpHttpDecision.Reject
        assertEquals(401, wrongToken.status)

        val authenticated = decide(
            accepting = true,
            enabled = true,
            method = "GET",
            authorization = auth,
        ) as LocalMcpHttpDecision.Reject
        assertEquals(405, authenticated.status)
        assertEquals("POST", authenticated.allowHeader)
    }

    @Test
    fun `origin and host are enforced on every method including get`() {
        val evilOrigin = decide(
            accepting = true,
            enabled = true,
            method = "GET",
            authorization = auth,
            origin = "http://evil.example:$port",
        ) as LocalMcpHttpDecision.Reject
        assertEquals(403, evilOrigin.status)

        // DNS-rebinding 形状：Host 不是回环名字，即使 token 正确也不行。
        val rebinding = decide(
            accepting = true,
            enabled = true,
            method = "POST",
            authorization = auth,
            host = "attacker.example:$port",
        ) as LocalMcpHttpDecision.Reject
        assertEquals(403, rebinding.status)

        val missingHost = decide(
            accepting = true,
            enabled = true,
            method = "POST",
            authorization = auth,
            host = null,
        ) as LocalMcpHttpDecision.Reject
        assertEquals(403, missingHost.status)
    }

    @Test
    fun `a well formed post is the only thing that proceeds`() {
        assertEquals(
            LocalMcpHttpDecision.Proceed,
            decide(accepting = true, enabled = true, method = "POST", authorization = auth),
        )
        // 方法名大小写不影响判定。
        assertEquals(
            LocalMcpHttpDecision.Proceed,
            decide(accepting = true, enabled = true, method = "post", authorization = auth),
        )
    }

    private fun decide(
        accepting: Boolean,
        enabled: Boolean,
        method: String,
        authorization: String?,
        origin: String? = null,
        host: String? = goodHost,
    ): LocalMcpHttpDecision = decideLocalMcpConnection(
        accepting = accepting,
        enabled = enabled,
        method = method,
        authorization = authorization,
        token = token,
        origin = origin,
        host = host,
        port = port,
    )

    // -------------------------------------------------------------- 幂等账本

    private fun ledgerFile(name: String = "idempotency.json"): File =
        File(temporaryFolder.newFolder("r22-boundary-$name-${System.nanoTime()}"), name)

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    @Test
    fun `the same key under another assistant never replays the first assistant's result`() {
        val ledger = AppMcpIdempotencyLedger(ledgerFile())
        val fingerprint = AppMcpIdempotencyLedger.fingerprint(args("text" to "hi"))
        val assistantA = "11111111-1111-1111-1111-111111111111"
        val assistantB = "22222222-2222-2222-2222-222222222222"

        assertTrue(ledger.decide("chat_send", "k1", fingerprint, assistantA) is AppMcpIdemDecision.Fresh)
        ledger.complete("chat_send", "k1", """{"ok":true,"status":"queued"}""", assistantA)

        // 同一个账本、同一个 key：在 A 的域里是回放。
        assertTrue(ledger.decide("chat_send", "k1", fingerprint, assistantA) is AppMcpIdemDecision.Replay)

        // 换到 B 的域：必须是全新的执行机会，绝不能把 A 的结果当成 B 的回放。
        assertTrue(
            "cross-assistant replay must be impossible",
            ledger.decide("chat_send", "k1", fingerprint, assistantB) is AppMcpIdemDecision.Fresh,
        )

        // 两个域各自独立记账。
        assertEquals(AppMcpLedgerStatus.DONE.name, ledger.entryOf("chat_send", "k1", assistantA)?.status)
        assertEquals(AppMcpLedgerStatus.PENDING.name, ledger.entryOf("chat_send", "k1", assistantB)?.status)
        assertNull(ledger.entryOf("chat_send", "k1", "device"))
    }

    @Test
    fun `a pending write in one scope does not block or leak into another scope`() {
        val ledger = AppMcpIdempotencyLedger(ledgerFile())
        val fingerprint = AppMcpIdempotencyLedger.fingerprint(args("title" to "x"))
        assertTrue(ledger.decide("chat_rename", "k2", fingerprint, "assistant-a") is AppMcpIdemDecision.Fresh)

        // 中断在 PENDING：同域必须拒绝重放。
        val sameScope = ledger.decide("chat_rename", "k2", fingerprint, "assistant-a")
            as AppMcpIdemDecision.Rejected
        assertEquals("unknown_side_effect", sameScope.code)

        // 另一个域是另一笔操作，不受影响。
        assertTrue(ledger.decide("chat_rename", "k2", fingerprint, "assistant-b") is AppMcpIdemDecision.Fresh)
    }

    @Test
    fun `a ledger that cannot be persisted fails closed instead of executing unprotected`() {
        val blocker = temporaryFolder.newFile("not-a-directory")
        // 父路径是一个文件：mkdirs/writeText 都会失败 → PENDING 落不了盘。
        val ledger = AppMcpIdempotencyLedger(File(blocker, "idempotency.json"))
        val decision = ledger.decide(
            "chat_send",
            "k1",
            AppMcpIdempotencyLedger.fingerprint(args("text" to "hi")),
            "assistant-a",
        ) as AppMcpIdemDecision.Rejected
        assertEquals("ledger_unavailable", decision.code)
        assertNull("nothing may be recorded as in-flight", ledger.entryOf("chat_send", "k1", "assistant-a"))
    }

    // ------------------------------------------------------------ 配方归属域

    private fun ownerFile(): File =
        File(temporaryFolder.newFolder("owners-${System.nanoTime()}"), "recipe-owners.json")

    @Test
    fun `recipes without an ownership record are not exposed`() {
        val owners = AppMcpRecipeOwnership(ownerFile())
        assertNull(owners.ownerOf("legacy-recipe"))
        assertFalse(owners.isOwnedBy("legacy-recipe", "assistant-a"))
        // 空归属域也拿不到"未知"的配方。
        assertFalse(owners.isOwnedBy("legacy-recipe", AppMcpRecipeOwnership.UNBOUND_SCOPE))
    }

    @Test
    fun `recipe ownership survives a restart and is per assistant`() {
        val file = ownerFile()
        assertTrue(AppMcpRecipeOwnership(file).assign("recipe-1", "assistant-a"))

        val reopened = AppMcpRecipeOwnership(file)
        assertEquals("assistant-a", reopened.ownerOf("recipe-1"))
        assertTrue(reopened.isOwnedBy("recipe-1", "assistant-a"))
        assertFalse("another assistant must not see it", reopened.isOwnedBy("recipe-1", "assistant-b"))
    }

    @Test
    fun `ownership that cannot be persisted is reported so the recipe is not created`() {
        val blocker = temporaryFolder.newFile("owners-blocker")
        val owners = AppMcpRecipeOwnership(File(blocker, "recipe-owners.json"))
        assertFalse(owners.assign("recipe-1", "assistant-a"))
    }
}

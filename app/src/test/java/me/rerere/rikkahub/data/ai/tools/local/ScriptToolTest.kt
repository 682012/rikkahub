package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolOutputStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * `run_script`（Code Mode v1a）的行为测试。
 *
 * QuickJS 在 JVM 单测里能跑：`app/build.gradle.kts` 把 `quickjs-kt-android` 换成了 `quickjs-kt-jvm`。
 * 所有超时/上限都用 [ScriptToolLimits] 调小，避免测试真的等 120 秒。
 */
class ScriptToolTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val outputDir: File
        get() = File(tempFolder.root, "tool_outputs")

    private fun store() = ToolOutputStore(outputDir, owner = "test")

    private fun testTool(
        name: String,
        needsApproval: Boolean = false,
        execute: suspend (JsonObject) -> List<UIMessagePart> = { listOf(UIMessagePart.Text("ok")) },
    ): Tool = Tool(
        name = name,
        description = "test tool $name",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject { put("q", buildJsonObject { put("type", "string") }) }
            )
        },
        needsApproval = { needsApproval },
        execute = { input -> execute(input.jsonObject) },
    )

    private suspend fun runScript(
        code: String,
        tools: List<Tool>,
        limits: ScriptToolLimits = ScriptToolLimits(),
    ): JsonObject {
        val scriptTool = requireNotNull(buildScriptTool(tools, store(), limits))
        val parts = scriptTool.execute(buildJsonObject { put("code", code) })
        val text = (parts.single() as UIMessagePart.Text).text
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test(timeout = 60_000)
    fun `chains results into the next call and parses JSON output`() = runBlocking {
        val tools = listOf(
            testTool("workspace_read_file") { args ->
                val path = args["path"]!!.jsonPrimitive.content
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("path", path)
                            put("text", "body of $path")
                        }.toString()
                    )
                )
            }
        )
        val output = runScript(
            """
            const a = await tools.workspace_read_file({ path: 'a.kt' });
            const b = await tools.workspace_read_file({ path: 'via:' + a.path });
            console.log('a=' + a.path);
            return b.text;
            """.trimIndent(),
            tools,
        )

        // 后一次用的就是前一次的字段，tool_batch 做不到这件事
        assertEquals("body of via:a.kt", output["result"]!!.jsonPrimitive.content)
        assertEquals("[LOG] a=a.kt", output["logs"]!!.jsonPrimitive.content)
        assertEquals(2, output["calls"]!!.jsonArray.size)
        assertEquals(
            "workspace_read_file",
            output["calls"]!!.jsonArray[0].jsonObject["tool"]!!.jsonPrimitive.content,
        )
    }

    @Test(timeout = 60_000)
    fun `awaits a promise return before decoding the result envelope`() = runBlocking {
        // A fulfilled Promise must be unwrapped, never returned as its display string.
        val output = runScript(
            "return Promise.resolve({ value: 42 });",
            listOf(testTool("memory_read")),
        )

        assertFalse("unexpected script failure: $output", output.containsKey("error"))
        assertEquals("""{"value":42}""", output["result"]!!.jsonPrimitive.content)
        assertTrue(output["calls"]!!.jsonArray.isEmpty())
    }

    @Test(timeout = 60_000)
    fun `call audit does not echo intermediate tool content`() = runBlocking {
        val privateText = "INTERMEDIATE_BODY_MUST_STAY_IN_SCRIPT"
        val output = runScript(
            "const data = await tools.memory_read({}); return data.length;",
            listOf(testTool("memory_read") { listOf(UIMessagePart.Text(privateText)) }),
        )

        assertEquals(privateText.length.toString(), output["result"]!!.jsonPrimitive.content)
        assertFalse("intermediate content leaked into the audit: $output", output.toString().contains(privateText))
        val call = output["calls"]!!.jsonArray.single().jsonObject
        assertEquals("memory_read", call["tool"]!!.jsonPrimitive.content)
        assertEquals(privateText.length.toString(), call["chars"]!!.jsonPrimitive.content)
        assertFalse(call.containsKey("summary"))
    }

    @Test(timeout = 60_000)
    fun `runs independent calls in parallel with Promise all`() = runBlocking {
        val running = AtomicInteger()
        val entered = AtomicInteger()
        val peak = AtomicInteger()
        val bothEntered = CompletableDeferred<Unit>()
        val tools = listOf(
            testTool("memory_read") {
                val now = running.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, now) }
                if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
                try {
                    // A fixed delay can elapse before the second JNI callback starts.
                    // Neither call may finish until both have entered. A serial runtime
                    // cannot satisfy this barrier and fails with a bounded timeout.
                    withTimeout(10_000) { bothEntered.await() }
                } finally {
                    running.decrementAndGet()
                }
                listOf(UIMessagePart.Text("done"))
            }
        )

        val output = runScript(
            """
            const all = await Promise.all([
                tools.memory_read({ q: 'a' }),
                tools.memory_read({ q: 'b' }),
            ]);
            return all.length;
            """.trimIndent(),
            tools,
        )
        assertFalse("calls did not reach the overlap barrier: $output", output.containsKey("error"))
        assertEquals("2", output["result"]!!.jsonPrimitive.content)
        assertEquals(2, entered.get())
        // Both callbacks entered before either finished; serial execution cannot pass.
        assertEquals(2, peak.get())
    }

    @Test(timeout = 60_000)
    fun `tool failures are catchable and carry tool code message`() = runBlocking {
        val tools = listOf(
            testTool("memory_read") { error("boom") },
            testTool("search_web") { listOf(UIMessagePart.Text("fine")) },
        )

        val caught = runScript(
            """
            try {
                await tools.memory_read({ q: 'x' });
                return 'not thrown';
            } catch (e) {
                const after = await tools.search_web({ q: 'y' });
                return e.code + '|' + e.message + '|' + e.tool + '|' + after;
            }
            """.trimIndent(),
            tools,
        )
        assertEquals(
            "tool_error|IllegalStateException: boom|memory_read|fine",
            caught["result"]!!.jsonPrimitive.content,
        )

        // 不 catch 就整段脚本失败，错误文本里看得到工具名与错误码
        val uncaught = runScript("await tools.memory_read({ q: 'x' });", tools)
        assertTrue(uncaught["error"]!!.jsonPrimitive.content.contains("boom"))
        assertEquals("tool_error", uncaught["code"]!!.jsonPrimitive.content)
        assertEquals("memory_read", uncaught["tool"]!!.jsonPrimitive.content)
    }

    @Test(timeout = 60_000)
    fun `enforces the per script call limit`() = runBlocking {
        val tools = listOf(testTool("memory_read"))
        val output = runScript(
            """
            let ok = 0, limited = 0;
            for (let i = 0; i < 5; i++) {
                try { await tools.memory_read({ q: String(i) }); ok++; }
                catch (e) { if (e.code === 'call_limit') limited++; }
            }
            return ok + '/' + limited;
            """.trimIndent(),
            tools,
            ScriptToolLimits(maxCalls = 3),
        )

        assertEquals("3/2", output["result"]!!.jsonPrimitive.content)
        assertEquals(3, output["calls"]!!.jsonArray.size)
    }

    @Test(timeout = 60_000)
    fun `times out a single nested call`() = runBlocking {
        val tools = listOf(
            testTool("memory_read") {
                delay(10_000)
                listOf(UIMessagePart.Text("late"))
            }
        )
        val output = runScript(
            "try { await tools.memory_read({ q: 'x' }); return 'no timeout'; } catch (e) { return e.code; }",
            tools,
            ScriptToolLimits(totalTimeoutMillis = 10_000, perCallTimeoutMillis = 200),
        )

        assertEquals("timeout", output["result"]!!.jsonPrimitive.content)
    }

    @Test(timeout = 60_000)
    fun `times out the whole script`() = runBlocking {
        val tools = listOf(
            testTool("memory_read") {
                delay(10_000)
                listOf(UIMessagePart.Text("late"))
            }
        )

        // 纯 JS 死循环：走 evaluationTimeoutMillis 的原生中断
        val spinning = runScript("while (true) {}", tools, ScriptToolLimits(totalTimeoutMillis = 300))
        assertTrue("expected a timeout error, got: $spinning", spinning["error"]!!.jsonPrimitive.content.contains("timed out"))

        // 卡在挂起的嵌套调用里：JS 不执行，中断回调没机会跑，只能靠外层协程超时
        val stuck = runScript(
            "await tools.memory_read({ q: 'x' });",
            tools,
            ScriptToolLimits(totalTimeoutMillis = 500, perCallTimeoutMillis = 10_000),
        )
        val message = stuck["error"]?.jsonPrimitive?.content.orEmpty()
        assertTrue("expected a timeout error, got: $stuck", message.isNotEmpty())
        assertTrue(stuck["calls"]!!.jsonArray.isEmpty())
    }

    @Test(timeout = 60_000)
    fun `truncates logs and the return value`() = runBlocking {
        val tools = listOf(testTool("memory_read"))
        val output = runScript(
            """
            for (let i = 0; i < 40; i++) console.log('x'.repeat(100));
            return 'y'.repeat(4000);
            """.trimIndent(),
            tools,
            ScriptToolLimits(maxLogChars = 1_000, maxResultChars = 500),
        )

        val logs = output["logs"]!!.jsonPrimitive.content
        assertTrue("logs length ${logs.length}", logs.length <= 1_000 + "\n[Logs truncated]".length)
        assertTrue(logs.endsWith("[Logs truncated]"))

        val result = output["result"]!!.jsonPrimitive.content
        assertEquals(500, result.removeSuffix(" [truncated]").length)
        assertTrue(result.endsWith(" [truncated]"))
    }

    @Test(timeout = 60_000)
    fun `offloads nested output above the 16K threshold`() = runBlocking {
        val payload = "z".repeat(40_000)
        val tools = listOf(
            testTool("workspace_read_file") { listOf(UIMessagePart.Text(payload)) }
        )
        val output = runScript(
            """
            const text = await tools.workspace_read_file({ path: 'big.txt' });
            return text.split('\n')[0];
            """.trimIndent(),
            tools,
        )

        // 脚本拿到的是「回执 + 预览」，不是 40K 正文
        val result = output["result"]!!.jsonPrimitive.content
        assertTrue("got: $result", result.contains("Tool output offloaded"))
        assertTrue("got: $result", result.contains("40000 characters total"))

        val call = output["calls"]!!.jsonArray[0].jsonObject
        assertEquals("true", call["offloaded"]!!.jsonPrimitive.content)
        // 脚本手里只剩回执，正文留在磁盘上；这正是「中间结果不进上下文」
        val receivedChars = call["chars"]!!.jsonPrimitive.content.toInt()
        assertTrue("receipt should be far smaller than 40000, got $receivedChars", receivedChars in 1 until 40_000)

        // 全文真的落盘了，模型可以事后用 tool_output_read 分页读回
        val files = outputDir.listFiles { file -> file.extension == "txt" }.orEmpty()
        assertEquals(1, files.size)
        assertEquals(40_000, files[0].readText().length)
    }

    @Test(timeout = 60_000)
    fun `only allowlisted tools are visible in the script`() = runBlocking {
        val tools = listOf(
            testTool("memory_read"),
            testTool("workspace_shell"),
            testTool("workspace_write_file"),
            testTool("mcp__demo__search"),
            testTool(SCRIPT_TOOL_NAME),
        )
        val output = runScript(
            "return JSON.stringify([" +
                "typeof tools.memory_read, " +
                "typeof tools.workspace_shell, " +
                "typeof tools.workspace_write_file, " +
                "typeof tools.mcp__demo__search, " +
                "typeof tools.run_script" +
                "]);",
            tools,
        )
        assertEquals(
            """["function","undefined","undefined","undefined","undefined"]""",
            output["result"]!!.jsonPrimitive.content,
        )

        // 描述里也只列白名单，免得模型去调根本不存在的东西
        val description = requireNotNull(buildScriptTool(tools, store())).description
        assertTrue(description.contains("tools.memory_read"))
        assertFalse(description.contains("workspace_shell"))
    }

    @Test(timeout = 60_000)
    fun `rejects calls that need approval`() = runBlocking {
        val tools = listOf(
            testTool("workspace_read_file", needsApproval = true) {
                listOf(UIMessagePart.Text("secret"))
            }
        )
        val output = runScript(
            """
            try {
                const text = await tools.workspace_read_file({ path: 'a.kt' });
                return 'allowed:' + text;
            } catch (e) {
                return e.code + '|' + (e.message.indexOf('directly') >= 0);
            }
            """.trimIndent(),
            tools,
        )

        assertEquals("needs_approval|true", output["result"]!!.jsonPrimitive.content)
        assertTrue(output["calls"]!!.jsonArray.isEmpty())
    }

    @Test(timeout = 60_000)
    fun `script without return only reports logs and calls`() = runBlocking {
        val tools = listOf(testTool("memory_read"))
        val output = runScript(
            "console.log('only logs');",
            tools,
        )

        assertEquals("[LOG] only logs", output["logs"]!!.jsonPrimitive.content)
        assertFalse(output.containsKey("result"))
        assertEquals(0, output["calls"]!!.jsonArray.size)
    }

    @Test(timeout = 60_000)
    fun `no callable tools means no script tool`() {
        // 白名单里一个都没有时不要给模型一个空壳工具
        assertNull(buildScriptTool(listOf(testTool("workspace_shell")), store()))
    }

    @Test(timeout = 60_000)
    fun `describes when a script is worth it`() {
        val scriptTool = requireNotNull(buildScriptTool(listOf(testTool("memory_read")), store()))

        assertEquals(SCRIPT_TOOL_NAME, scriptTool.name)
        assertEquals("run_script", scriptTool.name)
        assertNotNull(scriptTool.parameters())
        // 1-2 次简单调用直接调工具；3 次以上/有依赖才用脚本；并行写进描述
        assertTrue(scriptTool.description.contains("For 1-2 simple calls"))
        assertTrue(scriptTool.description.contains("Promise.all"))
        assertTrue(scriptTool.description.contains("tools.memory_read"))
        // 只读白名单，写操作必须直连
        assertTrue(scriptTool.description.contains("call them directly"))
        assertFalse(scriptTool.needsApproval(JsonObject(emptyMap())))
    }
}

# fork.r11：Pi / CodeMode 的 AGENTS.md 加载链路与工具规范

这次改的是**工作区指令怎么被加载、缓存、失效和截断**，以及**工具规范里那几句行为约束怎么写**。
没有换一句更"聪明"的 prompt 就宣称提效，也没有跑真实 provider 测出命中率；
下面每条都能在代码里指到行。UI 上系统提示字符数的下降不等于 provider 前缀缓存命中率的变化，两者不混用。

相关代码：
`app/src/main/java/me/rerere/rikkahub/data/ai/transformers/WorkspaceReminderTransformer.kt`
测试：`app/src/test/java/me/rerere/rikkahub/data/ai/transformers/WorkspaceReminderTransformerTest.kt`

## 1. AGENTS.md 到底从哪里读

`buildAgentsPrompt()`（同一文件内）在**本地工作区**注入三处，按 `linkedSetOf` 的顺序：

1. `/root/.agents/AGENTS.md`（Rootfs 的 HOME；ProotShellRunner 把 HOME 固定成 `/root`）
2. `/workspace/AGENTS.md`（工作区文件区根）
3. `<会话当前目录>/AGENTS.md`（`ctx.workspaceCwd` 按 `/workspace` 解析后 `normalize()`；相对路径不允许逃出去）

链路：`WorkspaceReminderTransformer.transform()` → 只在 `workspace.shellStatus == READY` 且能取到 layout 时执行 →
SSH 工作区分支**根本不调用** `buildAgentsPrompt`（远程不读本地 Rootfs 的文件）；本地分支才拼 `buildWorkspacePrompt(...) + buildAgentsPrompt(...)`。
字符串追加到第一条 system 消息末尾（没有 system 就新建一条 synthetic system），后面的历史消息不动。

"会话当前目录"取的是规范化后的绝对路径，同一个目录写 `proj` 和 `./proj` 不会变成两个缓存条目，也不会读到两份文件。

## 2. 缓存：键、stamp、复核、上限

`AgentsPromptCache`：

- **键** = `workspaceId + "\n" + 规范化工作目录`。不同工作区、不同目录互不串。
- **stamp** = 三个路径各自的 `(path, lastModified, length)`；文件不存在记 `(-1, -1)`，之后被创建 stamp 就会变。
  取不到 mtime（没有本地落点）时 `stamps` 返回 `null`：这次**不读缓存也不写缓存**，直接重读。
- **命中条件** = 拿到 stamps 且与条目里的 stamps 完全相等且在 30s 复核窗口内 → 逐字节复用上一次拼好的字符串。
- **复核**：超过 `revalidateMillis`（默认 30s）即使 stamp 没变也重读一次并替换。理由是 mtime 粒度粗（1 秒）、
  内容被原样改写但长度不变、编辑后又改回来，都可能让 stamp 不变而内容已变；用读到的内容确认，不靠 stamp 推断。
- **判断不了新旧**（stamps == null）时**删掉**旧条目，不拿可能过时的内容顶替。
- **上限** 16 条，按最近一次校验时间淘汰最旧的（不会因为开了很多对话/目录无限堆积）。

为什么要做到"逐字节相同"：每个 step 都会重建系统提示，只要这一段抖动，system 之后的所有历史前缀都白失效一次。
所以宁可多读一次文件，也不让这一段凭空变化或突然消失。

## 3. 注入内容：去重、总预算、截断给指针

`buildAgentsInstructions(sources)`：

- 三份内容 `trim()` 后**完全相同就只注入一次**（软链、复制出来的模板很常见），第二份不占上下文；
- 合计上限 `MAX_AGENTS_TOTAL_CHARS = 64 * 1024` 字符（单文件读取上限仍是 `MAX_AGENTS_BYTES = 64KB` 字节，
  三份全注入原样可能 100KB+）；
- 超限时按顺序截断，**退到最后一个完整行**不留半行，并写一句
  `[truncated at 65536 characters; read the rest with workspace_read_file(path="...")]`，
  让模型知道还有内容、自己用分页读；
- 完全放不下的文件不静默丢弃，列一行 `Not included (size budget): <path> ...`；
- 一份都没读到（或全是空白）时返回空串，注入块整体不出现，不会多出一对空标签。

## 4. 工具规范：改了哪几句，为什么

`appendToolUseGuidance()` 的文本是**静态**的：同样的后端永远产出同样的字符，不随步数、时间、电量变化。
测试 `tool use guidance is static and follows the workspace backend` 守着这一点。

- LOCAL：`Locate with workspace_grep / workspace_glob first, then read only what you need with offset / limit on workspace_read_file.`
  上一版这里是"先定位再分页读 + 一句 `Do not run grep, find or cat through workspace_shell`"。
  这次去掉了那句绝对禁令：本地本来就有 `workspace_grep/glob/read_file`，"先定位、按行读"已经表达了意图，
  再加一条禁令只是更长的 prompt 和更多被拒的调用。**没有新增任何禁令**。
- SSH：保留"先用 shell 的 grep -n / find 定位，再用 offset/limit 分页读"，只把工具名从句子中间移出，语义不变。
- SHELL_ONLY（HTTP 远程执行）：文件只能走 `workspace_shell`，所以给 `sed -n '120,200p'` 这种"打印需要的行范围"的具体写法。
- 三种后端共有的一句是新的：`If the workspace already has a script, README or skill for the task, use that entry point instead of rebuilding it.`
  这条是"复用现成脚本/工具目录"的落点——不是让模型自己发明入口，而是先看工作区里已经有的东西。
- 仍然保留：`Never read a file again when its content is already in this conversation and you have not changed it.`
  和 `When a call fails, read the error and change the approach; never repeat an identical call.`

对应的工具本身：`workspace_read_file(offset, limit)` 分页读、`workspace_grep`/`workspace_glob` 定位工具在 r9/r10 就位，
这里只是把规范对齐到"已经有这些工具"的现实。

## 5. 远端工作区不在同一请求里反复探测

SSH 工作区：`RemoteEnvironmentCache` 按 `workspaceId` 缓存 `Result<RemoteEnvironment>`，
成功值一直沿用直到 SSH 配置字符串变化；失败值只保留 60s（`DEFAULT_FAILURE_TTL_MS`），
期间的后续 step 不必每步再等一次连接超时。连不上时注入的是 `buildUnreachablePrompt(workspace, layout)`，
只用工作区自身的固定信息——错误串每次都可能不同，拼进 system 会让提示每步都变。
发行版名另有 `localDistros` 按 `workspaceId|backend|shell` 复用的上一次成功值，探测临时失败不会让环境行突然消失。

## 6. 记忆侧的同类问题（稳定前缀 + 不空写）

- `buildMemoryPrompt` 的清单顺序只取决于内容：`appendMemoryState` 按 `path` 再排一次并按 path 去重，
  数据库行序、恢复顺序、调用方传入顺序变了都不影响 system 前缀。
- `MemoryRepository.appendToFile` 遇到整行已存在、`writeFile` 遇到内容逐字节相同，都**不写库**：
  返回 `Success(file, unchanged = true)`，`updatedAt` 与 `version` 都不动，工具输出带 `unchanged: true`。
  乐观锁照旧校验（版本不符照样 `Conflict`），只是省掉一次无内容的写入和无意义的前缀变化。
- 记忆段每次请求都从库里现拼（`ChatRequestFactory` 里 `memoryRepository.getFiles(assistant.memoryId)`），
  写、删、替换下一轮立刻生效，没有需要手动失效的读缓存。

## 7. 验证与没验证的

已覆盖（纯 JVM 单测，`WorkspaceReminderTransformerTest` / `MemoryPromptTest` / `MemoryRepositoryTest`）：
stamp 不变逐字节复用、换目录另算一份、stamps 未知不缓存、30s 复核换成新内容、stamps 不可判断时丢弃旧条目、
条目上限淘汰、内容相同只注入一次、全空不注入、超限截断并给出读取指针、超预算文件被列出、
guidance 静态且按后端不同、记忆清单顺序与去重、相同内容不重写库。

没验证：
- 没有真实 provider 的缓存命中率、token 账单或联网延迟数据；字符数与命中率不是一个指标。
- 30s 复核是"stamp 骗人"时的兜底，最长仍有 30s 的过时窗口；mtime 1 秒粒度内的改动靠它兜。
- 截断按字符算、按行退让，不保证 token 意义上的均衡。
- 未跑真机/模拟器，未跑全量 JVM 与 UI 测试；最终以 Release 验收 JSON 为准。

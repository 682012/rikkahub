# 相比官方 3.1.1：新增工作流、功能增强与限制

对比功能增量的基线为官方 **3.1.1 / `ad2c4430`**；当前 **`3.1.1-r2 / 3000049`** 保留 fork.43 的全部能力，已跟进官方 master **`5ad19405`（2026-11-01）**，另带官方预览分支 `feat/background-shell`，数据库 schema 34。本版增加每模型自动压缩与请求并发设置，沿用上游名称/图标与简短版本后缀。[本轮更新与说明](RELEASE-r2.md)。不沿用早期版本的“官方没有”结论。

## 怎样数功能？

**5 组新增工作流 + 2 项主要功能增强；兼容与稳定性修复单列，不计入新增数。**

一组按独立使用目的计数，不按按钮、端点或提交数凑数。旧版说明的“6 组新增”把单会话用量面板也计入了新增；此处明确将它归为官方统计功能的增强。ZIP 导入与导出合为一组文件迁移流程，不拆成两个数字。这是计数更准确，不是删掉功能。

**fork.43 的工具调用层重做不计入组数。** 它没有新的使用目的，改的是已有工作流的调用方式（并发、去重、大输出读回、子代理结果分页、代码模式），因此单列一节，见下面的「fork.43：工具调用层重做」。`workspace_grep`/`workspace_glob`/`run_script` 这些新工具服务于同一个“在设备上做文件与会话工作”的目的，也不为了凑数拆成新组。

## r2：每模型运行设置

设置 → 提供商 → 编辑模型 → 基本，集中设置上下文窗口、自动压缩/阈值、并发上限。上下文大小沿用官方已有字段；压缩覆盖与共享请求名额为本分支增量。名额按模型ID计数，不按展示名；主聊天/子代理/摘要共享，最大1–64或不额外限制。可取消地FIFO排队，修改上限不重建计数池，工具与重试退避不持名额，全局子代理数量不变。旧备份缺字段时保持原行为。

## 五组新增工作流

### 1. 子代理协作

**官方已有**：模型调用、工具、MCP、Skills，以及会话历史检索。官方 3.1.1 没有本分支的子代理任务管理器。

**本分支增加**：`subagent_run`、`subagent_wait`、`subagent_result`、`subagent_list`、`subagent_cancel`。每个任务独立上下文和模型，支持排队、超时、取消、结果与日志落盘。默认并发 4，可设置 1–8；fork.43 起按设备 `ActivityManager.memoryClass` 自动封顶（≥256MB 用配置值、≥192MB 最多 4、其余最多 2），因为每个子代理都有独立上下文与工具集，内部还带 `tool_batch` 的 8 路并发。

fork.43 起 `subagent_result` 支持分页：`offset`/`limit`，一页默认 6000、最多 12000 字符，回带 `nextOffset`/`totalChars`/`eof`；运行中、失败、超时、取消的任务都能读部分结果，不再只能一次性拿全文或日志。

子代理可以按设置继承工作区、MCP 和官方历史工具；历史检索本身不算新增。fallback 只在尚未收到模型响应的安全边界尝试，不借重试重放已经执行的工具。

入口：高级与长任务 → 子代理。默认关闭；不支持嵌套、独立费用预算或子代理内审批/续跑。需要审批的操作由父对话处理。并行有时更快，但不保证每个任务更快，且增加请求、内存和模型用量。

源码：[SubagentRunner](../app/src/main/java/me/rerere/rikkahub/data/ai/subagent/SubagentRunner.kt)、[SubagentTools](../app/src/main/java/me/rerere/rikkahub/data/ai/tools/SubagentTools.kt)。

### 2. 工作区后台任务

**官方已有**：本地 PRoot/Linux 工作区、shell、终端；官方 shell 默认超时 30 秒、最高 600 秒。

**本分支增加**：`workspace_job_start/status/kill`，启动后立即返回任务 ID，之后查询状态、日志或停止任务。默认/最大命令超时可配置，0 表示不限。本地后台任务申请前台服务与 wakelock；启动被拒绝时不放出无保护的任务。

fork.40 起另带官方预览的后台命令（`workspace_shell` 的 `run_in_background`，配 `workspace_shell_output/kill`），属于官方能力，不计入本分支新增。两者并存：官方后台命令归属当前会话，结束后主动通知模型，只在本地 Rootfs 提供，子代理不拿；`workspace_job` 是可跨会话查询的独立任务，也支持 HTTP 远程执行。

边界：本地 Rootfs 与 HTTP 执行后端支持，SSH 工作区没有 job 通道。App 重启后历史记录不等于还持有进程，不能用旧 PID 不加校验地接管或杀任务。前台服务也不等于锁屏永不掉线。

源码：[WorkspaceJobManager](../workspace/src/main/java/me/rerere/workspace/WorkspaceJobManager.kt)、[WorkspaceRepository](../app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt)、[前台服务](../app/src/main/java/me/rerere/rikkahub/service/WorkspaceJobForegroundService.kt)。

### 3. 自托管 HTTP 远程执行

**官方已有**：SSH/SFTP 工作区，命令与文件都在所选 SSH 主机。

**本分支增加**：可选的 `/exec`、`/job/start`、`/job/status`、`/job/kill` 协议，把本地类型工作区的 shell/job 交给自己部署的服务器执行。与官方 SSH 是不同通道；全局 HTTP 开关不会劫持 SSH 工作区。

边界：HTTP 模式没有远端文件工具，用远端 shell 操作文件；本机文件区不会自动同步过去。服务端要求非空 Bearer token，客户端要求 HTTPS（回环除外）。没有多租户目录隔离，服务重启后不会自动接管旧任务；也没有 HTTP job-list 端点，调用时明确说明不支持。

源码：[服务端](rk-exec-server.py)、[RemoteExecutionClient](../app/src/main/java/me/rerere/rikkahub/data/repository/RemoteExecutionClient.kt)、[后端选择策略](../app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceExecutionPolicy.kt)。

### 4. 自动上下文压缩

**官方已有**：手动压缩对话与检查点机制；记忆整理是另一个官方功能。

**本分支增加**：新用户轮次开始前，按对话模型的窗口和阈值自动生成摘要；全局默认阈值 80%，模型可以独立覆盖开关和10%–100%阈值；可选独立压缩模型。摘要作为检查点插入，本轮输入及原始历史保留，不删除聊天记录。

边界：阈值依赖 `contextLength` 设置正确；摘要请求会消耗用量，单独的摘要模型也可能窗口不足。摘要失败不阻断原对话。不是“无限上下文”，也不是长期记忆整理的替代品。

源码：[AutoCompressionPlan](../app/src/main/java/me/rerere/rikkahub/service/AutoCompressionPlan.kt)、[ChatService](../app/src/main/java/me/rerere/rikkahub/service/ChatService.kt)、[ChatRequestFactory](../app/src/main/java/me/rerere/rikkahub/data/ai/ChatRequestFactory.kt)。

### 5. ZIP 工作区文件迁移

**官方已有**：文件导入/导出与向目录批量导出，不能说官方完全没有导出。

**本分支增加**：选择整个本地文件区生成 ZIP，并把 ZIP 导入为**新的本地工作区**。旧版裸文件 ZIP、有效空包、Unicode 名称与内容、二进制、空目录、大小写不同的文件名均支持；`workspace/` 或 `files/` 外层需明确选择去掉。

导入先在独立临时目录验证，全部通过再发布并注册；失败/取消不伪装成功，提交后的取消不会删掉已注册文件。校验路径、CRC、实际大小、重复冲突、ZIP64 与权限；ZIP 内记录的 0600/0700 不放宽。过期临时残骸按需清理，活跃导入跳过。

导出可选择过滤缓存/构建目录，或包含这些目录的全部普通文件；完成页显示跳过数量。始终排除 `.jobs`、符号链接和特殊文件，不宣称原子快照。

入口：工作区列表 → 工作区文件迁移；高级与长任务中也有入口。只迁移 `/workspace` 文件区，**不含 Linux Rootfs、聊天数据库、设置或整机**。导入不覆盖旧工作区或修改原会话绑定；运行命令前需安装 Rootfs 并自行选择新工作区。

默认限额：100000 条目、12 GiB 展开、4 GiB 单文件/压缩包。分卷、加密、非 UTF-8 文件名明确拒绝；不恢复符号链接，旧导出没有记录的权限不能凭空还原。

源码：[WorkspaceFilesImporter](../app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceFilesImporter.kt)、[WorkspaceFilesExporter](../app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceFilesExporter.kt)、[WorkspaceTransferCard](../app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceTransferCard.kt)。

## 两项主要功能增强

| 增强 | 官方 3.1.1 | 本分支 |
|---|---|---|
| 重试策略 | 自动重试开关；按 1、2、4…秒指数退避，最高 60 秒 | 最大次数 0–100；固定 30/60 秒、30–60 秒均匀随机整秒、自定义 1–600 秒范围。主对话和子代理共用；关闭/0 次/取消不重试 |
| 单会话用量面板 | 已有 UsageRecorder、usage_record 表、全局统计 | 聊天页按会话聚合输入/输出/缓存、命中率和模型明细，包含关联子代理用量；不是重新发明用量记录，不计算真实账单或虚构缺失历史 |

**默认等待已改为固定 60 秒**，与旧版“指数退避、上限 60 秒”不同。可按需要改为 30 秒或随机区间。只重试匹配的网络异常，不保证所有 429/5xx 都会重试。

源码：[重试设置](../app/src/main/java/me/rerere/rikkahub/ui/pages/setting/RetryDelaySettings.kt)、[重试策略](../ai/src/main/java/me/rerere/ai/agent/Retry.kt)、[UsageSheet](../app/src/main/java/me/rerere/rikkahub/ui/pages/chat/UsageSheet.kt)、[UsageRecordDAO](../app/src/main/java/me/rerere/rikkahub/data/db/dao/UsageRecordDAO.kt)。

## fork.43：工具调用层重做

**这部分改的是“怎么调用、怎么读回”，不是新的使用目的，所以不计入上面的 5 组 + 2 项。** 数字为代码里的阈值与上限；[受控工具测量](verification/fork43-benchmark.md)单列，不等于端到端模型加速或 token 收益。

| 机制 | 官方 3.1.1 | 本分支 fork.43 | 硬边界 |
|---|---|---|---|
| 同批并发 | 工具调用按顺序执行 | 只读工具可重叠执行，并发上限 8；同批同名同参数的只读调用只跑一次、结果共享 | 写操作与语义未知的工具仍是顺序屏障；MCP 工具不按名字猜语义 |
| `tool_batch` | 无 | 一次提交 1–16 个已规划调用，结果按调用顺序返回 | 成员失败只回错误字段；批内审批照样生效，判定异常时失败关闭；`ask_user`/`run_script`/`tool_batch` 不能进批 |
| MCP 瘦身 | 全部 MCP 工具 schema 直接进请求 | ≥16 个工具或 ≥20000 字符时换成精简目录 + `tool_search`/`tool_call` | 搜索默认 6 条、最多 8 条并可翻页；发现陌生工具通常会多一轮 |
| 大输出外置 | 超长输出截断后不可读回 | 文本合计 >16K 字符外置，回执 + 头尾各 2K 预览，`tool_output_read` 按字符分页读回 | 保留 7 天、最多 64 份输出，按 32MB 预算轮转（至少保留最新一份），按会话归属；分页读时不把全文装进内存 |
| 子代理结果 | 一次性返回或截断 | `offset`/`limit` 分页，默认 6000、最多 12000 字符，带 `nextOffset`/`totalChars`/`eof` | 仍受子代理超时与取消限制；分页只解决读法，不延长任务寿命 |
| 子代理并发 | 无子代理 | 默认 4、可设 1–8，并按设备内存封顶 8/4/2 | 独立上下文会增加内存占用，设高不等于更快 |
| 重复调用 | 反复执行、重复进上下文 | 请求内折叠 ≥400 字符的相同结果；连续第 5 次相同调用被拦下 | 只改发给模型的内容，界面与消息保留完整结果；轮询类工具豁免 |
| 工作区读/搜 | `workspace_read_file` 整文件读 | `offset`/`limit`（默认 2000 行、最多 20000 行）+ `workspace_grep`/`workspace_glob` | 只在文件仍在本机的工作区可用；SSH 明确拒绝并提示用 `workspace_shell` |
| `run_script` | 无 | 一次调用里用脚本串联多个只读工具，只回日志、返回值和调用元数据 | 32 次调用 / 每次与总计 120 秒 / 并发 8 / 日志与返回值各 8K；看不到 shell、MCP、写、子代理 |
| 缓存前缀 | 每步重拼系统提示 | Claude messages 断点落在最后一条消息；`AGENTS.md` 按 mtime/size 缓存；记忆清单去掉日期；工具规范按后端固定 | 顶层自动断点只作兜底（显式断点最多 3 个 + 顶层 1 个不超协议上限） |

`workspace_grep` 参数：正则或 `literal`、`path`、`glob`、`ignore_case`、`output_mode`（`files_with_matches`/`content`/`count`）、`context` 0–20、`head_limit`（默认 100、上限 1000）、`offset`、`include_hidden`；默认跳过 `.git`、`node_modules`、`build` 等目录、含 NUL 的二进制与 >2MB 文件，预算 20000 文件或 15 秒（超出标 `truncated`）。`workspace_glob` 按修改时间新→旧。

`run_script` 白名单只读：`workspace_read_file`、`workspace_grep`、`workspace_glob`、`memory_read`、`memory_list`、`search_web`、`scrape_web`、`recent_chats`、`conversation_search`、`conversation_read`、`use_skill`、`get_time_info`、`get_current_time`。每次嵌套调用前还会再判一次该调用的审批要求，白名单只是第一道闸。

入口：`run_script` = **助手详情 → 本地工具 → 代码模式**（按助手开关，默认关闭；开启后在有可用只读工具时注入）；子代理并发 = **设置 → 高级与长任务 → 子代理**；工作区读/搜无需开关，默认放行审批，可在工作区详情页按工作区覆盖。

源码：[ToolBatch](../ai/src/main/java/me/rerere/ai/agent/ToolBatch.kt)、[ToolRepetition](../ai/src/main/java/me/rerere/ai/agent/ToolRepetition.kt)、[BuiltinToolScheduling](../app/src/main/java/me/rerere/rikkahub/data/ai/tools/BuiltinToolScheduling.kt)、[CompactMcpTools](../app/src/main/java/me/rerere/rikkahub/data/ai/tools/CompactMcpTools.kt)、[ToolOutputStore](../app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolOutputStore.kt)、[ScriptTool](../app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/ScriptTool.kt)、[WorkspaceTools](../app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt)、[WorkspaceSearch](../workspace/src/main/java/me/rerere/workspace/WorkspaceSearch.kt)、[SubagentRunner](../app/src/main/java/me/rerere/rikkahub/data/ai/subagent/SubagentRunner.kt)、[WorkspaceReminderTransformer](../app/src/main/java/me/rerere/rikkahub/data/ai/transformers/WorkspaceReminderTransformer.kt)。

## 另列的稳定性与兼容修复

流式 1.5 秒节流落库、节点差分写入、超大消息完整分片回退、跨分支备份 staging 对齐、用量表归一化、记忆路径冲突保留、FTS 自检与对齐、工具全文可读性、技能元数据校验、通知身份和生命周期、SSH/HTTP 路由边界等。

fork.43 另修了流式工具调用的 id 归属（不属于本步的输出直接丢弃，不再串到别的调用上）、参数增量缺失时的回退，以及分页窗口的偏移在裁剪前先转整型；MCP 调用不再因为服务器自称 `idempotentHint` 就被同批合并。见 [RELEASE-fork43.md](RELEASE-fork43.md)。

fork.41 修复 PRoot 里的命令结束不了：停止任务、shell 超时与取消、结束后台命令时，结束命令启动的整棵进程树，见 [RELEASE-fork41.md](RELEASE-fork41.md)。

fork.38 还用真实 Android 原生窗口发现了 requery 的特殊错误文本：只在精确的 `Couldn't read row N column M` 与 `CursorWindow.nativeGet*` 堆栈同时匹配时回退，分片失败仍整体抛错。没有新增 UI 懒分页，不能承诺无限大历史不 OOM。

## 来自官方，不计入分支新增

本地 PRoot、SSH/SFTP、MCP、Skills、记忆文件与整理、历史检索、请求记录与全局统计、多供应商聊天、Web UI、TTS/ASR、媒体生成、搜索/翻译、后台悬浮窗、快速回复、工具耗时、models.dev 能力识别，以及 fork.40 带入的官方预览后台命令等继承上游。

## 验证与取舍

当前r2的完整构建、测试与覆盖升级结果见 [r2 验收](RELEASE-r2.md#验证)；下列为历史版本的已验范围。

fork.43（[GitHub Actions run 41325551257](https://github.com/682012/rikkahub/actions/runs/41325551257)）：JVM **1561 项，1560 通过，0 失败、0 错误，1 项跳过**；Android API34 x86_64 **54 项全部通过**；R8 + lintVital、签名与版本核对通过。用户真机升级与端到端模型性能未测量。

fork.38 另做过 Python 11、SQLite 模拟 38 及 Unicode 分片检查。用户真机生产数据库升级、厂商通知与锁屏保活始终未验。

这个分支用更多设置、工具和后台逻辑换取可操作性；不提升模型本身的智力，也不保证更快或更便宜。新增的并发、去重、外置与分页是为了少重复、少浪费上下文，不是已测出的性能收益。不需要这些增量时，官方版更省心。

[本版更新与说明](RELEASE-r2.md) · [上一版更新与验收](RELEASE-r1.md) · [历史记录](CHANGES.md) · [维护方法](README.md) · [修改声明](../NOTICE.md)

# 3.1.1-fork.43 更新说明

版本：`3.1.1-fork.43 / 3000047`；包名 `me.rerere.rikkahub.fork`；数据库仍为 schema 34，本版没有新增迁移。

## 这一版是什么

继承已经发布的 fork.42，再补上代码模式、工作区搜索、重复结果折叠、输出保留策略、缓存前缀与完整审批预览。同批并发、MCP 懒发现和子代理持久化沿用并合并 fork.42 的实现。没有跟进新的官方提交，官方 master 基线仍是 `5ad19405`（2026-11-01）；官方最新正式版仍是 3.1.1。

要解决的问题很具体：一次回复里本来可以并行跑的只读调用被一条条排队；工具一多（尤其 MCP）就把整个 schema 塞进请求；一次 `workspace_shell` 的大输出就把上下文撑满且读不回来；同一个调用被模型反复执行、结果一模一样还是照样进上下文。这一版改的是这些机制，不是模型本身。

**下面的数字都是代码里的上限与阈值，不是实测结果。** 没有真实模型的耗时或 token/费用对比；[受控工具测量](verification/fork43-benchmark.md)单列，不外推成模型收益。

## 一、并发与调度

- **同批独立只读调用并发**：`memory_read`、`memory_list`、`search_web`、`recent_chats`、`conversation_search`、`conversation_read`、`workspace_read_file`、`workspace_grep`、`workspace_glob`、`use_skill`、`eval_javascript`、`get_current_time`、`get_time_info`、`calendar_query`、`get_screen_time` 标为“并行 + 可去重”。
- **顺序屏障**：写操作和语义未知的工具保持串行，不会与只读调用混跑。MCP 不按名字猜语义；只有显式 `readOnlyHint=true` 才允许并发，但不据 `idempotentHint` 合并执行。精简目录的通用网关仍按串行处理。
- **同批去重**：同一批里同名同参数的只读调用只执行一次，结果共享给其余成员；结果仍按调用顺序返回。
- **`tool_batch`**：一次调用最多 16 个已规划好的调用；批内并发上限 8；成员失败只回一条错误字段，其余照常执行；批内成员的审批要求汇总后照常生效，任一成员的审批判定抛异常时按“需要审批”处理（失败关闭，不再静默放行）。`ask_user`、`run_script`、`tool_batch` 自身不能进批。
- **轮询类工具**：`workspace_job_status`、`workspace_shell_output`、`subagent_wait`、`subagent_list`、`get_current_time`、`get_time_info` 标为可重复，不参与下面的去重与拦截。

## 二、MCP 工具瘦身

- 配置的 MCP 工具数量 ≥ 16，或估算体积 ≥ 20000 字符时，启用精简目录：模型看到工具描述、一个 `tool_search` 和一个 `tool_call`，而不是全部工具的完整 schema。
- `tool_search` 默认返回 6 条、最多 8 条；`tool_call` 按工具名执行。搜索结果仍是真实工具名与参数 schema 的摘要，不是虚构的中间层。
- 代价：工具很多的服务器上，模型必须先搜再调，会多一轮；知道准确名字时可直接调用；不需要先搜一遍来“解锁”。

## 三、大输出外置与分页读回

- 单次工具输出的文本合计超过 **16K 字符**才外置；小输出原样返回，行为不变。
- 外置后上下文中是“回执 + 头尾各 2K 预览”的一条文本，其余非文本内容（图片、文件等）原样保留；写盘失败时返回未裁剪的原始输出，不静默丢内容。
- `tool_output_read` 按字符 `offset`/`limit` 读回，默认一页 4000 字符；按流解码到目标字符窗口，不把全文装进内存；靠后的页仍需扫描前面的字符，并非常数时间随机访问。分页在码点边界对齐，不会切出半个 emoji。
- 保留策略：**7 天**、最多 **64 份输出**，按 **32MB** 预算轮转（至少保留最新一份），按会话归属（`owner` 不匹配即拒）。进程重启、换一轮请求后仍可读；过期或被清理后读不回来。
- 与 fork.41 之前相比取消了两处按后端/工具名的开关：读工具读的是应用自己的私有目录，不依赖 shell 或 PRoot 挂载，所以本地、HTTP、SSH、完全没有工作区四种情况都能读回。`workspace_read_file`/`workspace_grep`/`workspace_glob` 自己分页，不再外置（否则要多花一轮读回全文）。

## 四、子代理

- **结果分页**：`subagent_result` 支持 `offset`/`limit`，默认一页 6000、最多 12000 字符，回带 `nextOffset`/`totalChars`/`eof`/`truncated`；`subagent_wait` 首次返回同样带分页信息。运行中、失败、超时、取消的任务都能按同一套方式读部分结果。
- **并发按内存分级**：默认 4、可设 1–8（硬上限 8）。按 `ActivityManager.memoryClass` 再削一刀：≥256MB 用配置值（最多 8），≥192MB 最多 4，其余最多 2，取不到内存信息时按最保守处理。理由是每个子代理是一整套独立 run + 工具集 + 历史，内部还带 `tool_batch` 的 8 路并发，低内存机上很容易 OOM。

## 五、重复调用收敛

- **请求内折叠**：发给模型的请求里，同名同参数、纯文本输出完全相同、且输出不短于 **400 字符**的重复结果，第 2 次起换成一行指向首次调用的说明。消息本身、界面显示、数据库里的内容都不改。
- **循环拦截**：同名同参数的调用连续执行 **4 次**且结果都一样时，**第 5 次不再执行**，直接回错误让模型换路（参照 Gemini CLI 的阈值做法）。
- 只依赖更早的历史，同一段历史每次得到同样的结果，不会让提示词前缀在步骤之间抖动。轮询类工具豁免这两条。

## 六、工作区按行读与搜索

- `workspace_read_file` 新增 `offset`/`limit`：默认 2000 行、最多 20000 行；文本输出为 JSON，含 `startLine`/`endLine`/`totalLines`/`hasMore`/`nextOffset`/`clippedLines`，正文每行带行号；图片分支不变。
- 新增 `workspace_grep`：`pattern`（默认正则，`literal=true` 按字面量）、`path`、`glob` 过滤、`ignore_case`、`output_mode`（`files_with_matches` / `content` / `count`）、`context` 0–20、`head_limit`（默认 100、上限 1000）、`offset`、`include_hidden`。默认跳过 `.git`/`node_modules`/`build` 等目录、前 8KB 含 NUL 的二进制与超过 2MB 的文件，最多扫 20000 个文件或 15 秒，超出标记 `truncated`。
- 新增 `workspace_glob`：`pattern`、`path`、`head_limit`、`offset`、`include_hidden`，按修改时间新→旧。
- **后端边界**：只在文件仍在本机的工作区可用（本地 Rootfs，以及文件在本机的 HTTP 执行后端）；SSH 工作区明确拒绝，提示改用 `workspace_shell`。三个工具默认不需要审批，可在工作区详情页按工作区覆盖。

## 七、`run_script`（代码模式）

模型写一段 JS，在一次工具调用里按需调用多个只读工具，返回 `console` 日志、`return` 值和调用元数据，中间全文留在脚本或外置文件里。和 `tool_batch` 的分工是它做不到的那部分：依赖链、循环、条件、取字段。

- **白名单**：`workspace_read_file`、`workspace_grep`、`workspace_glob`、`memory_read`、`memory_list`、`search_web`、`scrape_web`、`recent_chats`、`conversation_search`、`conversation_read`、`use_skill`、`get_time_info`、`get_current_time`，以及 `tool_output_read` 和它的去重别名（不存在的工具不会注入）。
- **看不到的东西**：shell、MCP、写操作、子代理、`tool_batch` 和 `run_script` 自己。原因是审批：工具的 `needsApproval` 是执行前的一次性判断，脚本跑起来之后没有“停下来等人点”的挂起点。白名单只是第一道闸，每次嵌套调用前还会再判一次该调用的审批要求（工作区工具是按参数审批的）。
- **上限**：32 次嵌套调用（超出报错，不静默截断）、单次与总计各 120 秒、并发 8、日志与返回值各 8K、成功调用只附工具名、输出大小等元数据，不复述中间正文；单次嵌套输出超过 16K 照常外置。
- **入口**：助手详情 → 本地工具 → 代码模式，按助手开关，默认关闭；开启后只在有可用只读工具时注入给模型。不进 `tool_batch`，子代理也拿不到，调度上保持串行。

## 八、提示词与缓存前缀

- **Claude**：messages 上的缓存断点改为落在最后一条消息的内容块上；system、tools 各 1 个，messages 最多 2 个，合计不超过协议上限 4 个。顶层自动 `cache_control` 只在尾部断点落不下时补发，因为它同样占槽（4 个显式断点 + 顶层字段 = 文档里的 400 情形）。
- **`AGENTS.md`**：按（工作区、当前目录、文件 mtime/size）缓存，逐步重拼系统提示时不再重复读盘；文件没变，提示逐字节相同。
- **记忆清单**：不再打印每条记忆的更新日期。日期变化会牵动后续前缀；正文按需读取，修改仍用工具返回的 `version` 校验。
- **工具使用规范**：按工作区后端（本地 / SSH / 仅 shell）写成固定的几句话，同一个工作区里逐字节相同；不会提到该后端没有的工具。

## 参考的实现与资料

- [OpenCode](https://github.com/anomalyco/opencode) 与 [Codex](https://github.com/openai/codex)：定位优先的读文件/搜索、输出上限、头尾预览和外置结果。
- [Gemini CLI](https://github.com/google-gemini/gemini-cli)：重复调用与循环检测；这里采用自己的连续相同结果判定，并给轮询工具豁免。
- [Anthropic：Code execution with MCP](https://www.anthropic.com/engineering/code-execution-with-mcp) 与 [Cloudflare：Code Mode](https://blog.cloudflare.com/code-mode/)：用代码串联工具，先在执行器中筛选，再返回需要的内容。

这是机制层面的参考，没有把别家的性能数字当作本分支实测。

## 合并既有 fork.42

保留非流式调用 ID 隔离、真实模型请求次数、子代理 task.json 与重启后的部分结果、原子首屏交付及多任务 8K 首屏预算。任务状态持久化串行写入最新状态，避免迟到的旧更新覆盖终态。已发布的 fork.42 tag、说明、校验文件和安装包不修改。

## 修复与查证

- 修复 QuickJS 把异步返回值显示成 Promise、导致脚本结果信封无法解析的问题；外侧显式等待异步结果。
- 原生中断即使被脚本 catch，宿主仍按单调时钟判断超时。
- 外置输出的 manifest 已被删除时，返回“未知或已清理”，明确同一个 id 重试无效；不伪造清理原因，也不保存无限增长的墓碑表。
- 成功调用的审计不再把中间正文截成摘要回传，避免字段筛选之后又被摘要重复塞回上下文。
- 待审批参数完整滚动展示；批量调用后面的成员不再藏在悬浮窗的 300 字截断后，聊天页待审批时也保持参数展开。

## 本次运行的检查

TODO：由主分支在 CI 完成后填入运行编号与真实结果（JVM 数量、模拟器数量、R8 + lintVital、签名证书核对）。维护者当前正在排查 CI，**本文件不写“全部测试通过”**。

本版未在用户真机上运行，也没有端到端模型请求对比。

## 安装包

TODO：由主分支在构建完成后填入。

- 文件：`rikkahub-3.1.1-fork.43.apk`（通用包，arm64-v8a + x86_64）
- 大小：TODO
- SHA-256：TODO
- 签名证书 SHA-256：TODO（与前版同签名才可直接覆盖升级）
- 构建源码：TODO
- Release：固定版本 tag 待发布；[最新正式版](https://github.com/682012/rikkahub/releases/latest) 当前仍是 fork.41

旧版本 Release 全部保留，不被覆盖。

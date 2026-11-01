# fork.43：Agent 工具执行与上下文架构

基于已发布的 fork.42，合并其请求计数、任务持久化与交付修复（保留 PRoot 进程树停止修复与官方 5ad19405 多关键词模型筛选），保留官方 provider 协议、工作区、审批与会话存储。此次重构集中在工具调度和结果交付，不声称更换模型、突破窗口或达到某个缓存命中率。

## 链路

```text
ChatToolFactory
  ├─ 原生小工具目录 + 显式执行语义
  ├─ 大 MCP 目录 → tool_search + tool_call（固定两个入口）
  ├─ 工具结果 → ToolOutputStore（小结果直返，大结果保留全文）
  └─ tool_output_read + tool_batch + 可选 run_script
        ↓
AgentLoop
  请求模型 → 审批 → 相邻只读窗口并发 / 写入顺序屏障
        ↓
  每个结果独立写回原调用 ID；收集协程统一发事件与落库
        ↓
  无进展重复读取提醒 / 子代理完成通知 → 下一步模型
```

## 1. 执行器

- `Tool.executionMode` 默认 `SERIAL`。只对项目已知的独立原生读取声明并发；不从名称猜测 shell 或 MCP 是否只读。
- 相邻 `PARALLEL` / `PARALLEL_DEDUPLICATED` 窗口最多 8 路；`SERIAL` 前后先 drain。写入与读取不会被重排。
- `PARALLEL_DEDUPLICATED` 只合并当前相邻窗口内同工具、同规范化 JSON 参数；对象键排序，数组顺序保留。没有跨请求或跨轮缓存，验证改动仍会重新读取。
- MCP 的 `readOnlyHint` 只允许重叠执行，不能据此合并调用或自动重放中断调用。无 hint 保守串行。
- 只有收集协程改消息和 emit。每次真正执行前等 `startedAt` 落库，每次结果完成后即时落库，不能由并发 worker 直接发 Flow 事件。
- 保存过 `startedAt` 却没有结果的未知/副作用调用，恢复时回报“执行状态不确定，未重放”，让模型先核对目标。
- 流式兜底 ID 每响应稳定、跨响应唯一；已有 ID 碰撞和非流式重复/空 ID 在新增 part 时隔离。审批、输入与结果不覆盖旧调用。

## 2. 批量调用

`tool_batch(calls=[{name, arguments}, …])` 最多 16 项，供参数已经明确的操作使用。先校验所有名字和参数形状，全部有效才执行；内层审批透传。内部沿用并发窗口与写屏障，输出保序、非文本附件保留，取消向上传播。不能调用自身、`ask_user` 或 `run_script`。

有参数依赖的任务不能强塞同一批。批量不等于所有命令并行；未知 shell 仍串行。原生 `run_script` 用 QuickJS 串联只读白名单，支持依赖、循环和 `Promise.all`，不开放 shell/MCP/写入/子代理；已有 CodeMode MCP 仍可另用。成功调用只回元数据，不复述中间正文。

## 3. MCP 懒发现

外部 MCP 工具达到 16 个，或名字/描述/schema 合计达到 20,000 字符时，改为 `tool_search` 与 `tool_call`。小目录保持原生。

- 搜索按精确名或关键词分页，默认 6、最多 8 项，只返回命中工具的完整 schema。
- 调用只在本轮已启用目录内解析，不扩权，真实内层参数和审批判断透传。
- 未知名字或错误参数只返回错误，不把子代理卡在不可处理的审批。
- 子代理可见性先过滤，再建它自己的目录；关闭 MCP 后不会借泛用代理入口漏进来。
- 对外工具名固定排序；不在每步动态增删全部 schema。目录变更仍可能改变前缀，缓存命中要看真实 provider usage。

## 4. 输出仓库

所有后端均可用，不再要求本地 shell 能读 `/tool_outputs` 才裁剪。

- 所有文本合计超过 16,384 字符：全文保留在 App 文件区，旁边记录 UUID、owner、长度等 manifest；初次回执为头尾各约 2K 字符与读取 ID。
- `tool_output_read(output_id, offset=0, limit=4000)`，单页最多 12,000 字符，按 `nextOffset` 可恢复全文；只读当前会话输出。
- 原始非文本附件保留；`search_web` 因搜索 UI / 引用依赖完整 JSON，保留豁免。
- UUID 和目录校验阻止路径穿越；同会话重建工具实例或 App 重启后仍可读取。
- App 启动与写入时按 7 天、64 份输出、32MB 预算轮转；至少保留最新一份。未过期输出重启后仍可读，已经清理的回执会明确提示同 ID 重试无效。
- 写盘失败保留原输出，不给模型一条读不到的假路径。Unicode 页边界不切半个代理对。

## 5. 子代理交付

默认并发 4，上限 8，既有保存值不强改；按设备 memoryClass 再封顶 8/4/2。继续独立上下文、模型 allowlist 和许可池，不打开递归。

- 完成/失败/超时/取消投递小通知到父会话收件箱；父会话空闲时不因此发起额外模型请求。
- `subagent_wait` 支持一个 ID 或最多 8 个 ID，共用等待期限并发等待。多任务首屏共用预算。
- 同任务首次结果原子交付；重复 wait 只回状态与可读取提示，`subagent_result` 仍可随时分页重读。
- 结果单页默认 6K、最多 12K，明确 `nextOffset/eof/totalChars`；所有终态的部分结果都可读。
- `task.json` 保存单任务元数据，按 ID 懒恢复。重启遗留的 RUNNING/QUEUED 标 CANCELLED，并注明没有重放；结果文件继续可读。
- `steps` 记录模型步骤数，不把工具数量冒充模型往返数。

## 6. 无进展提醒与稳定前缀

保留同一原生读取批次第三次不变时的提醒；另对同名同参数且连续四次结果相同的调用，拦下第五次让模型换路。发请求时折叠不短于400字符的重复纯文本，数据库与界面仍保留全文。等待/轮询工具用 `repeatable` 豁免，图片/文件输出不按文本不变误判；不存在跨轮结果缓存。

工具和技能目录固定排序；只解析实际出现的占位符，不对所有历史文本无条件求电量、日期等值。没有删除用户的 AGENTS.md 或系统提示来追求好看的 token 数。

## 参考与取舍

- [OpenAI Codex `tools/parallel.rs`](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/core/src/tools/parallel.rs)：明确 `supports_parallel`，读/写锁控制同时执行，未知工具不能盲目并发。
- [Pi agent loop](https://github.com/earendil-works/pi/tree/2c8d1feb147f6b3d97e855da803bfe7b19f3c403/packages/agent/src)：工具执行语义、批内并行与结果交付，代码调研对应本地固定源码。
- [Cloudflare CodeMode](https://github.com/cloudflare/agents/tree/main/packages/codemode)：紧凑工具发现、编排与输出控制；其 Workers 沙箱没有伪装成 Android 已实现能力。
- [Anthropic tool search](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool)：服务端 deferred loading 和 tool_reference 属 provider 特性，本分支采用跨 provider 的客户端代理，不把私有扩展直接送给所有模型。

基准测试使用模拟目录、虚拟时钟与 fake provider。字符下降、往返下降与真实 token 账单、缓存率、联网延迟是不同指标。验证结果见对应 Release 验收文件。

新增工作区 `workspace_grep`/`workspace_glob` 与按行分页读；Claude 缓存尾部断点、AGENTS mtime/size 缓存和静态工具规范详见 [fork.43 更新说明](RELEASE-fork43.md)。[受控脚本测量](verification/fork43-benchmark.md)不是端到端模型 token/费用结论。

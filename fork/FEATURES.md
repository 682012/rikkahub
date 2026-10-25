# 与官方 3.1.1 的差异：新增、增强与限制

对比基线固定为官方 **3.1.1 / `ad2c4430`**，本分支为 **`3.1.1-fork.37 / 3000041`**。这是同一版本基线的源码对比，不是与官方未来版本比较，也不沿用早期 3.0.0 的结论。

## 计数口径

按独立使用目的归为 **6 组新增能力**。一个功能的多个按钮、工具端点、配置项不重复计数；已有能力的增强和 bug 修复另列。因此“6 组”不等于只有 6 个改动，也不把每条修复包装成新功能。

## 六组新增能力

### 1. 子代理协作

- 提供 `subagent_run`、`subagent_wait`、`subagent_result`、`subagent_list`、`subagent_cancel`。
- 每个任务独立上下文/模型/工具集，支持排队、超时、取消、结果与日志落盘；默认并发 2，可设置 1–4。fallback 仅在尚未收到模型响应的安全边界内尝试，不重放已经执行的工具。
- 可按设置继承工作区、MCP，以及官方已有的 `recent_chats` / `conversation_search` / `conversation_read`。历史检索本身不是本分支新增。
- 不支持嵌套、独立费用预算或子代理内审批/续跑；需要审批的操作由父对话处理。子代理增加请求、内存与模型用量，不承诺更快或更省。
- 源码：`data/ai/subagent/SubagentRunner.kt`、`data/ai/tools/SubagentTools.kt`、`ChatToolFactory.kt`。

### 2. 工作区后台任务与超时配置

- 官方已有 `workspace_shell`；本分支增加 `workspace_job_start/status/kill`，启动立即返回，再查状态、日志或取消。
- shell 默认/最大超时可配置，0 表示不限。官方 3.1.1 对应默认 30 秒、上限 600 秒。
- 本地任务使用独立 PRoot 进程，申请 specialUse 前台服务和 partial wakelock；拒绝启动前台服务时不继续放出本地任务。
- 只适用于本地 Rootfs 或本分支 HTTP 后端，SSH 不支持 job 工具。App 重启后历史记录不等于仍拥有进程，不能靠旧 PID 领养或取消任务。
- 源码：`workspace/.../WorkspaceJobManager.kt`、`data/repository/WorkspaceRepository.kt`、`service/WorkspaceJobForegroundService.kt`。

### 3. 可选 HTTP 远程执行后端

- 自托管协议 `/exec`、`/job/start`、`/job/status`、`/job/kill`，与官方 SSH/SFTP 工作区是两套不同通道。
- 开关仅作用于本地类型工作区的 shell/job；SSH 始终连自己的 SSH 主机，不被全局 HTTP 开关覆盖。
- HTTP 模式没有远端 `workspace_read_file/write_file/edit_file`；文件操作用远端 shell。服务端要求非空 Bearer token，客户端要求 HTTPS（回环地址除外）。
- 不提供多租户目录隔离；服务重启后内存任务表丢失，旧任务不会自动安全接管。令牌会随设置备份导出，备份也需保密。
- 源码：`fork/rk-exec-server.py`、`data/repository/RemoteExecutionClient.kt`、`WorkspaceExecutionPolicy.kt`。

### 4. 单会话用量面板

- 官方已有 `UsageRecorder`、`usage_record` 和全局统计；本分支增加聊天页顶栏“用量”面板与按会话聚合查询，不能宣传为首创用量记录。
- 展示生成记录数、输入/输出/缓存/合计 tokens、缓存命中率、按模型拆分；包括关联子代理和有会话标签的辅助请求，不隐藏真实已发生用量。
- `source=SUBAGENT` 标识子代理来源，面板默认合计展示，不是独立账单页。不按价格计算真实费用，也不凭空补齐旧历史。
- 源码：`ui/pages/chat/UsageSheet.kt`、`ChatPage.kt`、`data/db/dao/UsageRecordDAO.kt`。

### 5. 自动压缩与独立压缩模型

- 官方已有手动压缩和检查点；本分支在新用户轮次开始前按模型窗口阈值自动触发，默认 80%，可选单独的摘要模型。
- 总结此前历史，在本轮输入之前插入检查点；原始消息不删除。摘要请求不带工具定义，失败时继续原对话；生成摘要仍会消耗模型用量。
- 不等于自动整理长期记忆；官方的记忆整理模型是另一个功能。阈值依赖 `contextLength` 配置，独立摘要模型也可能因窗口较小而失败。
- 源码：`service/AutoCompressionPlan.kt`、`ChatService.kt`、`data/ai/ChatRequestFactory.kt`。

### 6. 本地工作区文件 ZIP 导出

- “高级与长任务”里选择本地工作区，使用系统文件选择器指定位置，显示进度并支持取消；读写失败不会假报完成。
- 跳过缓存、依赖/构建目录、任务日志、符号链接及特殊文件；SSH 不出现在本地导出列表。
- 仅文件区，不含 Linux Rootfs，不保留 Unix 权限/链接，运行中的文件不是原子快照；可能包含私密内容，不能公开分享。
- **此版只有导出，没有 ZIP 导入**。聊天/设置备份导入是另一套功能，不能用它证明工作区可导入。
- 源码：`data/repository/WorkspaceFilesExporter.kt`、`ui/pages/setting/SettingForkPage.kt`。

## 对已有能力的增强，不重复算新增

| 领域 | 当前增量 | 边界 |
|---|---|---|
| 流式持久化 | 约 1.5 秒节流保存、节点差分写入、退后台尽力 flush | 强杀仍可能丢最近未保存内容；不是逐 token 持久化 |
| 大消息读取 | CursorWindow 溢出整体分片回退，节点快照/会话尾部同样保护；历史内联媒体尽力外置 | 没有 UI 懒分页，不能保证任意大历史不 OOM |
| 跨分支备份 | staging 对齐同版本异结构、规范用量表、保留旧记忆原文及冲突副本 | 不支持所有分支；目标不认识的表可能只在 staging 被移除，原备份不改 |
| 重试设置 | 自动重试开关与最大次数 0–100；主对话/子代理共用，退避最高 60 秒 | 无 30–60 秒间隔设置；不保证所有 HTTP 错误都重试 |
| 工具输出/技能 | 只有本地工具真能读回全文时外置；SSH/HTTP 保留原文；增加技能元数据校验 | 不放松审批，不保证超长结果不增加上下文成本 |
| 搜索 | 默认全部助手、FTS 结构自检与重建兜底、查询容错 | 全库范围是行为变化；不等于重新实现一套搜索引擎 |

## 来自官方，不作为本分支独创

MCP、Skills、会话历史检索、记忆文件与整理、请求记录和全局用量统计、本地 PRoot、SSH/SFTP 工作区、Web UI、多供应商/模型支持、TTS/ASR、媒体生成、后台生成悬浮窗、快速回复、工具耗时、models.dev 能力识别等均继承上游。

## fork.37 是修复版，不是又加了一批大功能

修复重点：schema 32 用量表兼容、同名记忆无损归档、FTS 索引对齐/列校验、SSH/HTTP 路由、不可读的外置输出、消息整页跳过、SSH 零超时、HTTP 错误摘要与读取期限、通知身份冲突、SSH 误入本地导出列表，以及 CI GitHub flavor 产物路径。详见 [版本记录](CHANGES.md) 和 [审查报告](AUDIT-20261025.md)。

## 验证与取舍

950 项 JVM 回归、Python 11 项、SQLite 模拟和正式 R8 + lintVital 构建通过；Android 仪器测试仅编译/打包，未运行。真机安装、真实数据库升级、权限/通知、SAF、SSH 真机连接、锁屏长任务仍待实测。

模型能力与服务质量由所选上游决定。这个分支用更复杂的工具和后台执行换取可操作性，代价是更多配置、潜在请求成本、后台权限与维护负担；不需要这些能力时，官方版更省心。

源码位置中，未写完整前缀的 `data/`、`service/`、`ui/` 均位于 `app/src/main/java/me/rerere/rikkahub/`。上游版权与 AGPL-3.0 许可保持不变，见 [修改声明](../NOTICE.md)。

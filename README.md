# RikkaHub · 个人增强分支

基于 [RikkaHub 官方 3.1.1](https://github.com/rikkahub/rikkahub/tree/3.1.1) 的非官方个人分支，重点补充多任务协作、长任务执行和长对话的数据保护。

**当前维护版本：`3.1.1-fork.37` / `3000041` · 分支：`fork/3.1.1` · 数据库：schema 34。**

> 本仓库与官方团队无隶属关系。这里只记录个人修改，不提供面向公众的 APK 分发、安装教程或技术支持。官方渠道下载的是官方版，不是本分支。上游版权与 AGPL-3.0 许可证保持不变。

[完整差异与源码依据](fork/FEATURES.md) · [版本记录](fork/CHANGES.md) · [构建与维护](fork/README.md) · [fork.37 审查与验证](fork/AUDIT-20261025.md) · [修改声明](NOTICE.md)

## 到底比官方多了什么？

**相对官方 3.1.1，按独立用途归为 6 组新增能力。** 不按按钮、接口数量或 bug 修复凑数；官方本来就有的 SSH、MCP、技能和历史检索也不计入新增。

| 新增能力组 | 官方 3.1.1 | 本分支的增量 |
|---|---|---|
| 1. 子代理协作 | 没有内置子代理任务管理 | `subagent_run/wait/result/list/cancel`；独立上下文与模型，默认并发 2、可设 1–4，排队、取消、超时、结果落盘 |
| 2. 工作区后台任务 | 有 shell，超时默认 30 秒、上限 600 秒 | 新增 `workspace_job_start/status/kill`；任务日志与状态查询；默认/最大超时可配置，0 表示不限；本地任务申请前台服务 |
| 3. HTTP 远程执行 | 已有 SSH 工作区，没有本分支的 HTTP 执行协议 | 自托管 `/exec` 和 `/job/…` 后端，让本地工作区的 shell/job 交给服务器；与 SSH 分开路由 |
| 4. 单会话用量面板 | 已有请求用量记录和全局统计 | 聊天页按会话汇总请求记录、输入/输出/缓存、命中率与模型明细；纳入关联子代理用量 |
| 5. 自动上下文压缩 | 有手动压缩与检查点 | 达到配置阈值时自动生成摘要；可选独立压缩模型，默认阈值 80%；保留原始历史和本轮输入 |
| 6. 工作区文件 ZIP 导出 | 没有本分支的文件区 ZIP 导出入口 | 选择本地工作区和保存位置，带进度、取消与失败提示；排除缓存、构建目录、任务日志和符号链接 |

工具数量另算：前两组增加 **5 个子代理工具 + 3 个后台任务工具**。`recent_chats`、`conversation_search`、`conversation_read` 是官方已有工具，本分支让子代理也能按设置复用，不能再算 3 个“独创功能”。

## 已有能力做了哪些增强？

- **聊天持久化与大行读取**：流式生成期间按约 1.5 秒节流保存，节点差分写入；CursorWindow 溢出时按 SQL 字符分片读回全文，不再跳过整页。读取完整不等于任意大历史都不会 OOM。
- **备份与记忆迁移**：在 staging 副本中对齐部分同版本异分支结构；兼容用量表差异；冲突记忆保留两份内容，旧记忆原文留档。不是任意分支无损互转，也不是数据库任意降级。
- **网络重试次数**：自动重试可开关，最大次数可设 0–100，主对话与子代理共用；只重试匹配的网络异常，不保证所有 429/5xx 都会重试。
- **工具与技能保护**：长输出仅在确能读取本机外置文件时转存；SSH/HTTP 模式保留原文；增加技能元数据校验，并修正各执行后端的能力描述和工具可见性。
- **搜索与后台细节**：搜索页默认覆盖全部助手；增加 FTS 自检与容错，修复多会话进度通知、前台服务通知身份和旧版 Android 权限判断。

本分支不会提升模型本身的智力，也不保证更低费用。子代理、压缩和网络重试都可能增加模型请求与用量。

## 哪些是继承官方的？

多供应商聊天、MCP、Skills、记忆文件与整理、会话历史工具、全局用量统计、本地 PRoot 工作区、SSH/SFTP 工作区、Web UI、语音、媒体生成、后台生成悬浮窗、快速回复、工具耗时和 models.dev 能力识别等均来自上游。它们不作为上述 6 组新增能力宣传。

原项目介绍、官方渠道、赞助和贡献者信息请查看 [上游项目](https://github.com/rikkahub/rikkahub)；本仓库不将上游赞助关系表述为自己的合作关系。

## 使用边界

- 子代理默认关闭；不支持嵌套、独立费用预算或子代理内审批/续跑。需要审批的操作交给父对话，不绕过权限。
- SSH 工作区没有 `workspace_job_*`；HTTP 执行模式不提供远端文件读写工具，用 shell 处理文件。HTTP 后端需要自己部署，并保护访问令牌。
- ZIP 只导出文件区，不含 Linux Rootfs，不保留 Unix 权限/符号链接，不是原子快照。可能包含私钥等文件，不能公开分享。**此版没有工作区 ZIP 导入功能。**
- 此版可调的是重试次数，**没有 30–60 秒重试间隔设置**；间隔仍按 1、2、4…秒指数退避，最高 60 秒。
- 前台服务降低被回收的概率，不等于强杀后任务续跑或锁屏永不掉线；HTTP 服务重启也不会自动接管旧任务。
- 包名为 `me.rerere.rikkahub.fork`，与官方版共存，不能覆盖官方包；导入备份前另存副本，不能直接降级已升级的数据库。

## 这次验证到了哪里？

| 项目 | fork.37 已验证范围 |
|---|---|
| JVM 回归 | App / Workspace / AI / Common 共 **950 项、136 个 suite**；0 失败、0 错误、0 跳过 |
| 辅助核验 | Python 11 项通过；SQLite 迁移、索引对齐及超过 2 MiB 的 emoji 消息分片模拟通过 |
| 正式构建 | GitHub release 的 **R8 与 lintVital 已实际执行并通过**，不是跳过 R8 的 fastBuild 包 |
| Android 仪器测试 | 测试 APK 已编译/打包，**未在真机运行** |
| 尚未验收 | 真机安装启动、真实数据库升级、通知权限、SAF、SSH 真机连接、锁屏保活 |

验证对应审查封存源码；不能把测试数量当成“所有功能真机正常”或“没有未知错误”。详细记录见 [审查报告](fork/AUDIT-20261025.md)。

## 源码与许可

- 官方基线：`3.1.1` / `ad2c4430`；当前维护分支：`fork/3.1.1`。`master` 保留作上游镜像，不代表个人增强版。
- 构建命令、签名配置和回退说明：[维护文档](fork/README.md)。
- 版权归上游作者及各修改者；许可证：[GNU AGPL-3.0](LICENSE)。另见 [个人改版声明](DISCLAIMER.md) 与 [修改声明](NOTICE.md)。

### English summary

Unofficial personal fork of RikkaHub **3.1.1**, not affiliated with upstream. Six added capability groups: subagents, background workspace jobs, an optional HTTP execution backend, a per-conversation usage panel, automatic context compression, and local workspace ZIP export. SSH workspaces, MCP, Skills, history tools and global usage statistics already exist upstream and are not claimed as new. The fork also hardens persistence, migration and tool-output handling. See the [comparison](fork/FEATURES.md) and [validation boundaries](fork/AUDIT-20261025.md). No public APK distribution or end-user support is offered.

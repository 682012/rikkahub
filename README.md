# RikkaHub · 个人增强分支（非官方）

基于 [RikkaHub 官方 3.1.1](https://github.com/rikkahub/rikkahub) 的个人修改版：保留官方聊天能力，围绕**多代理协作、后台任务、上下文管理与沉浸聊天**做增强。

与官方团队没有隶属关系；上游版权与 AGPL-3.0 许可保持不变；不提供官方或面向终端用户的技术支持。

当前版本 **Meridian `3.1.1-m2` / `3000071`** · 包名 `me.rerere.rikkahub.fork`。

> ### 一个包，覆盖升级即可
>
> **m2 是建在 m1 之上的增量版本**：手动分组、渠道优先级、单对话思考三项加在 m1 的完整能力之上，所以版本号从 m1 走到 m2。
> **不存在「先装哪个包」的问题**：直接用 `rikkahub-3.1.1-m2.apk` 覆盖 m1（或 r21 及更早的本分支版本）即可，不需要先卸载。
> m1、r21 的 Release / tag / 资产原样保留（回滚与历史对照用）。

这一版新增**手动对话归组、同模型多渠道优先级与故障切换、单对话思考强度**；m1 的输入优化、回复审查、客户端协议档、运行中插话、本地知识库、工具执行短指引和高级权限与设备能力中心，r21 的 Agent 主循环、助手隔离、断线恢复、经验学习、家族折叠、自动归库、工具草稿、时间与字符统计、工作区网页渲染，以及轨道风格、启动动效和小宠物磁贴全部保留。应用名、图标、六只角色原图、人设与签名不变。

## 下载与安装

**[下载最新正式 APK](https://github.com/682012/rikkahub/releases/latest)** · [m2 更新说明](fork/RELEASE-m2.md) · [m1 说明（历史）](fork/RELEASE-m1.md) · [r21 说明（历史）](fork/RELEASE-r21.md) · [历史版本](https://github.com/682012/rikkahub/releases)

源码分支与下载渠道分开：只有完成正式 R8 构建和验包的版本才标为 Latest；实际发布状态、安装包与校验值以 Release 页面为准。

- 通用 APK 名为 `rikkahub-3.1.1-m2.apk`（tag `v3.1.1-m2`），包名 `me.rerere.rikkahub.fork` 与签名跟 m1 一致，可覆盖升级；含 `arm64-v8a` 与 `x86_64`，手机直接装这一个就行。
- 正式 R8 + lintVital 构建通过（job `idem-4fb0edae9da863c46e30033e`）；版本名/码、包名、原证书、双 ABI、ZIP CRC 与云端/本地 SHA-256 已核对。APK 46,373,962 字节；校验见 [m2 说明](fork/RELEASE-m2.md) 与 Release 附件。
- 从 m1、r21 或更早版本升级直接覆盖；旧版本的包与记录保留在各自 tag 下，需要回退时按旧 tag 取包。
- 本分支包名与官方不同，**不能覆盖安装官方版**；同包名、同签名的本分支旧版可以按常规覆盖升级。
- 升级前备份聊天与设置，不要为了升级先卸载（卸载会连应用数据一起丢）。从早期 `fork.*` 版本过来请直接下载安装，旧更新器不认识 `r` / `m` 系列版本号。
- 下载安装不需要构建环境；聊天模型与服务账号仍由你自己配置，不提供共享 API 额度。

## m2：手动分组、渠道优先级与单对话思考（当前版本）

三项都放在你已有的位置，默认不改变现有行为。

- **手动对话归组**（会话抽屉长按一条会话 → 多选 → 加入现有分组或新建分组）：**不是把多条会话合成一条**——组内仍是各自独立、消息与上下文都不变的会话，可以单独打开；组头可展开/收起、改名、查看全部、解散，解散只把成员移出组、不删消息；成员查询按助手隔离；手动入组会锁定自动归档分配，避免下一轮被自动搬走；展开状态按助手记住；复用已有文件夹归属，**没有数据库迁移**。
- **渠道优先级与故障切换**（设置 → 模型与服务 →「渠道优先级与故障切换」，**默认关闭**）：同一个模型挂在多个提供商上时，可以排出显式白名单，主聊天**严格按你排的顺序**调用，第一个不行才换下一个；只认 `modelId` 相同的模型，只**在同一协议族内**切换，跨协议不互换；一旦已经输出正文/思考/工具调用草稿就**不换渠道重发**（交给已有的检查点恢复），用户取消、请求格式错误、整体断网也不切换；每次切换等于用另一个提供商的凭据重新请求，**可能额外计费**，用量记在实际出话的渠道上。
- **单对话思考强度**（聊天输入栏的思考档位）：只管当前这一条对话，不写回助手；新对话里，声明了 REASONING 的模型默认 **MAX**，没声明的默认 AUTO（不强加参数）；旧会话已保存的显式档位原样保留；首条消息发出前的选择也会存进草稿；助手设置页不再提供全局思考强度项。MAX 仍按各提供商原有映射发送，**没有实测真实服务端是否接受最高档**。

### 两项 m1 安全缺陷修复

两项 m1 P1 已修复并通过窄验证：坏账本保留原文件并拒绝写入，持久化采用临时文件 + fsync + 原子替换；自动化写前记录没落盘不派发，写后记录失败保留未知副作用阻塞。没有把纯 JVM 检查说成真实设备端到端验证。

## m1：对话内的控制、协议档与设备权限（历史）

- **AI 输入优化**（会话选项，默认关闭）：原文照常显示和保存，只改发给模型的副本，可切换查看原文与优化版；开启后可能额外发起模型请求、**单独计费**；优化失败或取消仍用原话继续。
- **回复审查**（关闭 / 同模型自检 / 独立审查，默认关闭）：同模型自检不另发请求，独立审查**另发请求并单独计费**；审查意见只是模型意见，不代表代码已编译、运行或验证。
- **客户端协议兼容档**（原生 / 自动 / Codex / Claude Code / Pi，默认原生）：提示与工具用法、兼容请求头一并处理；自动选择**不发探测请求**、一轮内稳定；不伪造账号凭证、不获得官方客户端权限。
- **运行中插话**（排队 / 安全下一步插入，默认排队）：不打断已发出的请求，不重放正在跑的工具。
- **本地知识库**（默认关闭）：粘贴或文件导入、检索、查看、编辑、删除，按助手隔离并保留来源；命中片段随当轮请求发送，**增加该轮 token 与费用**。
- **工具执行短指引**：按当前真实工具生成，强调定向定位、复用脚本、只读并行与必要验证；不裁剪工具、不改权限、不额外发请求。
- **高级权限与设备能力**（设置 → 权限中心，默认关闭）：Shizuku、`WRITE_SECURE_SETTINGS` / `DUMP` / `READ_LOGS` / `CHANGE_CONFIGURATION` 的授予与撤销、系统特殊访问、真实状态回读、绑定助手与独立写开关；六个 `android_*` 工具接入聊天、本机 MCP 与本地自动化。写操作要幂等 key，未知副作用不重放。
- **稳定性**：修掉加载占位/会话回收期间聊天记录短暂变空的窗口；内容未就绪时不会把草稿或消息记到错误助手。启动动效默认 RING（三瓣，280ms，半径 40dp，描边 1.7dp），DOTS / AURORA 可选。

**高权限不是 root**：拿不到系统签名专属权限，不能读其他 App 私有数据、不能截取 secure 屏幕、不能绕过 SELinux；开发权限只能用 ADB/Shizuku 自己授予。完整说明与限制见 [m1 说明](fork/RELEASE-m1.md)。

## 更早版本（能力都还在）

- **r21 · Agent 架构**：工具声明索引与参数缓存、周期型原地打转检测、请求侧重复结果折叠、会话/记忆/待发队列按助手隔离、持久队列与断线检查点续跑、每助手经验学习（只统计不训练）、会话家族折叠、闲置自动归库（默认关、可整批撤销）、工具代码实时草稿、当前设备时间与码点字符统计、工作区网页内存直载 + CSP、轨道风格与启动动效、小宠物磁贴。[r21 说明](fork/RELEASE-r21.md)
- **r20**：后台图片整理与虚拟分组、AI 共读、更多文档文字提取、生图真实格式/尺寸验证、PNG/JPEG/WebP/GIF/MP4 本地导出、技能缓存与提示词组成查看。
- **r19 / r18 / r17 / r16**：图片整理 1–16 路并发与分段语音调节；可靠入队与待发队列、网络恢复接回；多图分批发送；会话与图片批量整理、系统 WebView 链接预览、公式归一化、六只完整人设。
- **r11 及更早**：长正文折叠默认值、输入草稿持久化、记忆与 `AGENTS` 注入稳定、HTML/Markdown/CSV/JSON/EPUB 预览、后台工具与后台压缩、子代理、工作区后台任务、HTTP 远程执行、自动上下文压缩、ZIP 工作区迁移、重试策略、单会话用量面板，以及沉浸外观、悬浮小助手、工作区访问。逐版细节见 [CHANGES.md](fork/CHANGES.md) 与 `fork/RELEASE-rN.md`。

## 主要设置入口

| 想改什么 | 位置 |
|---|---|
| 手动分组：加入、新建、改名、解散 | 会话抽屉 → 长按会话 → 多选 |
| 渠道优先级与故障切换 | 设置 → 模型与服务 →「渠道优先级与故障切换」 |
| 单对话思考强度 | 聊天输入栏上方的思考档位 |
| 输入优化、回复审查、协议档、运行中插话 | 聊天输入栏上方 → 会话选项（按会话保存） |
| 本地知识库与管理资料 | 会话选项 → 本地知识库 |
| Shizuku、开发权限、系统特殊访问、绑定助手 | 设置 → 权限中心 → 高级权限与设备能力 |
| 长正文默认展开 / 收起 | 偏好设置 → 界面偏好设置 → 长正文默认状态 |
| 自动归库、闲置小时、家族折叠 | 会话抽屉 → 自动归库 |
| 每助手经验（查看 / 新增 / 确认） | 助手 → 记忆 → 经验 |
| 聊天风格（含轨道）、启动动效 | 偏好设置 → 主题 |
| 小宠物悬浮窗快捷开关 | 下拉快捷面板 → 小宠物磁贴 |
| 自动分批发图、启用张数、每批张数 | 偏好设置 → 常规 → 多图发送 |
| 耗时工具转后台、等待阈值 | 偏好设置 → 常规 → 后台工具 |
| 压缩完成通知 | 偏好设置 → 通知 → 压缩完成通知 |
| 每模型上下文、自动压缩、并发上限 | 提供商 → 编辑模型 → 基本 |
| 悬浮角色、人设全文与 AI 互动 | 偏好设置 → 通知 → 悬浮角色 |
| 批量整理会话 | 历史记录 → 整理 |
| 分类、保留或批量删图 | 设置 → 文件管理 → 图片整理 |
| 子代理并发 | 高级与长任务 → 子代理 |
| 代码模式（`run_script`） | 助手详情 → 本地工具 → 代码模式 |
| 自动重试与等待时间 | 网络 → 自动重试 |
| 工作区 ZIP 迁移 | 工作区列表 → 工作区文件迁移 |

## 和官方版到底差在哪

下面按用途对比；外观、预览与工具调用层的改进另列，不把每个按钮都算成一项新功能。官方已有的能力不计为分支原创。

| 分类 | 本分支的增量 | 官方 3.1.1 已有 |
|---|---|---|
| 手动分组 | 多选会话归入自建分组，组头展开/收起/改名/解散，按助手隔离，不合并消息与上下文 | 会话文件夹（无手动多选归组与组头） |
| 渠道切换 | 同模型显式白名单按序调用、同协议族内切换、已出内容不重发 | 每个模型一个提供商配置 |
| 思考强度 | 按单条对话设定，新对话对支持推理的模型默认最高档 | 全局/助手级思考设置 |
| 子代理协作 | 独立上下文与模型的任务，可排队、等待、取结果、取消；并发默认 4，可设 1–8 并按设备内存封顶 | 无内置子代理任务管理 |
| 工作区后台任务 | `workspace_job_start/status/kill`，独立任务、状态与日志，超时可配 | shell 与终端 |
| HTTP 远程执行 | 可选的自托管 HTTP shell/job 后端，与 SSH 分开路由 | SSH/SFTP 工作区 |
| 自动上下文压缩 | 按模型设开关与阈值，达到窗口阈值自动摘要，可指定压缩模型，原始历史保留 | 手动压缩与检查点 |
| ZIP 工作区迁移 | 整个文件区导出为 ZIP，导入为**新**工作区，校验后发布 | 文件导入导出 |
| 重试策略 | 次数 0–100；固定 30/60 秒、30–60 秒随机、自定义 1–600 秒 | 自动重试开关与指数退避 |
| 单会话用量面板 | 实时会话统计、来源筛选、经典/工作台切换，累计消耗与上下文分开 | 用量记录与全局统计 |

工具调用层（fork.43 起）另做过一轮改造：同批只读调用并发（最多 8 路）、`tool_batch`、MCP 工具过多时改用精简目录 + 搜索、大输出外置后按字符分页读回、`workspace_grep`/`workspace_glob`、只读脚本 `run_script`。它不新增使用目的，只改「一次回复里怎么调工具、大输出怎么读回来」，文中数字都是代码里的上限，不是实测加速。

[完整功能对比与边界](fork/FEATURES.md)

## 能力边界（先看这几条）

- **m2 验证范围**：三项功能的 115 项定向检查按源文件 SHA 逐一匹配后复用；本次新跑 47 项安全回归（幂等账本 26、自动化 21）全部通过，不叠加历史套件或重复运行。
- **正式包**：正式 R8 + lintVital 构建通过（job `idem-4fb0edae9da863c46e30033e`）；版本名/码、包名、原证书、双 ABI、ZIP CRC 与云端/本地 SHA-256 已核对。 未自动安装手机，未做真机 UI / OEM 授权、真实供应商 MAX 接受度与计费、完整备份恢复、断电和网络竞态端到端验证。
- **设备写保护**：两项 m1 P1 已修复并通过窄验证：坏账本保留原文件并拒绝写入，持久化采用临时文件 + fsync + 原子替换；自动化写前记录没落盘不派发，写后记录失败保留未知副作用阻塞。没有把纯 JVM 检查说成真实设备端到端验证。
- **分组不是混消息**：组内会话各自独立，消息、上下文、用量都不合并；一个组内联显示 60 条，其余走「查看全部」分页。
- **渠道切换会多花钱**：默认关闭；开启后切换等于用另一个提供商凭据再请求一次，单独计费，且只在同一 `modelId`、同一协议族内生效；设置页文案目前只有中文，英文界面会看到中文说明。
- **思考档位不等于服务端接受**：MAX 只是沿用各提供商原有映射，未实测真实服务端接受最高档；旧会话的显式选择保留，不会因为升级被改写。
- **高权限不是 root**：只能管理 Shizuku、`WRITE_SECURE_SETTINGS` / `DUMP` / `READ_LOGS` / `CHANGE_CONFIGURATION` 与系统特殊访问；拿不到系统签名专属权限，读不了其他 App 私有数据、截不了 secure 屏幕、绕不过 SELinux。
- **m1 的边界仍然成立**：输入优化与独立审查默认关闭、开启后各多一次请求并单独计费；知识库命中片段会随当轮请求外发；协议档不伪造凭证也不获得官方权限；审查意见不等于代码已验证。m1 当时通过 174 项定向检查并完成 R8 + lintVital 构建与验包，**没有做真机界面点验**。
- **r21 的边界仍然成立**：经验学习只统计不训练、不保证越用越快；自动归库默认关闭、会把摘要发给快速模型；工具草稿只是参数预览，半截 JSON 不执行、不等同断电零丢失。
- **后台不等于永不掉线**：厂商强杀或系统重启会中断仍在跑的后台请求与工具，被中断的任务标为未知/已中断，不自动重放副作用。
- **预览与迁移有范围**：Office/EPUB 是文本提取，不还原版式、不读 DRM；HTML 用系统 WebView，无 JS 桥；ZIP 只迁移 `/workspace` 文件区，不含 Rootfs、聊天数据库、设置或整机。
- **网页登录仍是实验**：Gemini/Claude 网页会话走提示词协议，不保证登录后即可用，也不是原生 function calling；不宣称缓存命中率。
- 子代理、压缩、自动互动和重试可能产生额外模型用量；工具转后台本身不重跑同一调用。

## 文档导航

- 当前版本：[m2 说明与边界](fork/RELEASE-m2.md) · [m1 说明（历史）](fork/RELEASE-m1.md) · [r21 说明（历史）](fork/RELEASE-r21.md) · [正式包与机器可读验收](https://github.com/682012/rikkahub/releases/latest)
- 横向对比：[完整功能对比](fork/FEATURES.md) · [版本历史](fork/CHANGES.md)
- 维护：[构建、签名与回退](fork/README.md) · [修改声明](NOTICE.md) · [免责声明](DISCLAIMER.md)
- r21 维护记录：[Agent 能力、入口与限制](docs/r21-agent-core/CAPABILITIES-r21.md) · [验证范围](docs/r21-agent-core/VALIDATION.md)
- 六角色素材历史：[r12 立绘与动态](fork/COMPANIONS-r12.md) · [r15 即兴互动](fork/COMPANIONS-r15.md)
- 历史版本：r1–r10 的逐版说明见 [CHANGES.md](fork/CHANGES.md) 目录，以及 `fork/RELEASE-rN.md` 与 `fork/verification/rN.json`（例如 [r10](fork/RELEASE-r10.md)、[r8](fork/RELEASE-r8.md)、[r3 历史稳定版](fork/RELEASE-r3.md)）。首页只保留当前版本，历史细节留在这些文件里。

## 上游与许可

继承自上游、不计入本分支新增的能力：本地 PRoot/Linux 工作区、SSH/SFTP、MCP、Skills、文件式记忆与整理、会话历史检索、用量记录与全局统计、多供应商聊天、Web UI、语音、媒体生成、搜索、翻译、后台悬浮窗与快速回复等。

上游项目与官方下载渠道：[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)。本分支保留上游版权声明与 **AGPL-3.0** 许可（见 [LICENSE](LICENSE)）；上游的赞助、贡献者与支持渠道不属于本分支。

### English summary

**Meridian 3.1.1-m2** (3000071) adds manual conversation groups without merging histories, ordered same-model channel failover within a protocol family, and conversation-only reasoning (MAX by default for models declaring reasoning support). The two m1 write-ledger P1 defects are fixed. A full R8/lintVital release build and package/signature/ABI/hash checks passed. 115 byte-identical feature checks are reused; 47 new ledger and automation checks passed. No device installation, UI/OEM or real-provider billing validation was performed. Earlier releases and assets remain available.

**m1** (3.1.1-m1 / 3000070, one merged universal APK over r21) added per-conversation AI input optimization, reply review, client protocol profiles, steer-while-running, a local assistant-scoped knowledge base, tool-execution guidance, and a device privilege center (not root, no signature-level permissions). It passed 174 targeted checks and a formal R8 + lintVital build with package verification (46,325,122 bytes, SHA-256 `cc714980…545bf4`), and was not installed or UI-tested on a device.

**r21** rewrote the agent loop (indexed tool declarations, cached argument canonicalization, periodic no-progress detection, request-side dedupe, inherited step budget), scoped conversations/memory/queue to assistants, added durable queue recovery that never replays unknown side effects, per-assistant learning that only counts and suggests, conversation-family collapsing, optional idle auto-filing (off by default), live tool-argument drafts that are never executed half-written, device time per model step, code-point character counts, in-memory workspace HTML with CSP, an optional ORBIT chat style, startup motion and a pet quick-settings tile.

**r20 / r19 / r18 / r17 / r16 / r11** stay in place: background image classification and virtual groups, URL co-reading, validated image outputs and local PNG/JPEG/WebP/GIF/MP4 exports; image concurrency and voice controls; reliable queueing with network recovery; batched multi-image sending; conversation/image organization, system-WebView link previews, math normalization and six complete companion personas; long-message collapse defaults, durable drafts, stable memory injection, extra document previews, background tool calls, background compression, subagents, workspace background jobs, optional HTTP execution, auto context compression, ZIP workspace migration, configurable retries and a per-chat usage panel.

Unofficial personal fork of RikkaHub **3.1.1**. Licensed under AGPL-3.0; upstream copyright notices are kept. Not affiliated with the upstream team.

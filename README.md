# RikkaHub · 个人增强分支（非官方）

基于 [RikkaHub 官方 3.1.1](https://github.com/rikkahub/rikkahub) 的个人修改版：保留官方聊天能力，围绕**多代理协作、后台任务、上下文管理与沉浸聊天**做增强。

与官方团队没有隶属关系；上游版权与 AGPL-3.0 许可保持不变；不提供官方或面向终端用户的技术支持。

当前版本 **3.1.1-r11 / 3000058** · 维护分支 `fork/3.1.1` · 包名 `me.rerere.rikkahub.fork`。应用名、图标、包名与签名沿用本分支既有安装包，可按常规覆盖升级。

## 下载与安装

**[下载 r11 正式 APK](https://github.com/682012/rikkahub/releases/download/v3.1.1-r11/rikkahub-3.1.1-r11.apk)** · [r11 更新与校验值](https://github.com/682012/rikkahub/releases/tag/v3.1.1-r11) · [最新正式版](https://github.com/682012/rikkahub/releases/latest) · [历史版本](https://github.com/682012/rikkahub/releases)

- 下载通用 APK 即可安装，支持 ARM64 手机与 x86_64；正式包启用 R8 压缩与优化，和此前供开发使用的 fast 包区分。
- 本分支包名与官方不同，**不能覆盖安装官方版**；同包名、同签名的本分支旧版可以按常规覆盖升级。
- 升级前备份聊天与设置，不要为了升级先卸载（卸载会连应用数据一起丢）。从早期 `fork.*` 版本过来请直接下载安装，旧更新器不认识 `r` 系列版本号。
- 下载安装不需要构建环境；聊天模型与服务账号仍由你自己配置，不提供共享 API 额度。

## r11 改了什么

这一版主要让切换对话不丢草稿、长任务不用守着等、文件可以直接看。包名、签名与数据库结构不变。

1. **长正文折叠有默认值**：设置 → 偏好设置 → 界面偏好设置 → 长正文默认状态，可选「默认收起」或「默认展开」。本次进程内，每条消息的手动选择优先；重启后按设置的默认值显示，流式生成中不自动折叠。
2. **输入草稿持久化**：文字、附件引用、光标位置与编辑态保存到应用私有目录；已有对话按对话存，新对话按助手存。切换对话/助手、离开页面再回来，草稿还在。
3. **记忆与提示词更稳定**：相同内容不重复写入、不无故提升记忆版本；记忆清单按路径稳定排序；`AGENTS` 相同内容只注入一次，并按文件变化复核。
4. **文件预览更全**：工作区里的 HTML/HTM/XHTML/SVG 在隔离 WebView 中预览并可切源码；新增 Markdown 阅读、CSV/TSV 表格、JSON 格式化、EPUB 按目录提取章节文本。普通聊天里的 HTML/PDF/文本附件也能应用内预览。原有 PDF、Office 文本、压缩包清单、图片与音视频入口保留。
5. **Pi/CodeMode 工具指引整理**：静态工具说明集中注入一次，不按工具重复；已知入口直接复用、定位后分页读取。只改工具机制，不改助手人格。
6. **后台工具**：设置 → 偏好设置 → 常规 → 后台工具。耗时调用在前台等待阈值（默认 30 秒，可设 1–3600 秒）后转后台，**继续同一次执行，不取消不重跑**；`background_tool` 提供 start / list / result / wait / cancel。审批照旧、写操作仍排队，独立只读调用可以继续。
7. **手动压缩可后台**：点确认后即可离开页面，压缩在后台继续，完成时通知并可点回原对话；开关在设置 → 偏好设置 → 通知 → 压缩完成通知。提交前核对消息分支与正文：压缩期间新增的尾部消息保留，范围内的正文被改过则丢弃旧摘要。原图与附件不删除。

构建维护另补齐了 Release / Debug 的 Firebase 双配置与前置校验，详见 [构建指南](fork/README.md)。

## 已有增强也都保留

- **沉浸外观**：经典、工作台、专注三种布局；全局或按助手设置壁纸，厚卡片可切成薄底。
- **悬浮小助手**：三张透明立绘，可选模型生成互动回应，查看真实请求状态并停止；自动事件互动默认关闭。立绘只有轻微动效，不是完整 Live2D。
- **可信的速度与用量**：按对话区分主请求、子代理和辅助请求；累计消耗与上下文占用分开，TPS 只计算有可信计时的步骤。
- **工作区访问**：保留目录挂载、用户授权的 Android 宿主命令及 Shizuku / 已有 Root 入口；PRoot 本身不等于 Android Root。

## 主要设置入口

| 想改什么 | 位置 |
|---|---|
| 长正文默认展开 / 收起 | 偏好设置 → 界面偏好设置 → 长正文默认状态 |
| 耗时工具转后台、等待阈值 | 偏好设置 → 常规 → 后台工具 |
| 压缩完成通知 | 偏好设置 → 通知 → 压缩完成通知 |
| 每模型上下文、自动压缩、并发上限 | 提供商 → 编辑模型 → 基本 |
| 布局、壁纸、沉浸聊天 | 偏好设置 → 主题 |
| 悬浮角色与 AI 互动 | 偏好设置 → 通知 → 悬浮角色 → AI 互动 |
| 子代理并发 | 高级与长任务 → 子代理 |
| 代码模式（`run_script`） | 助手详情 → 本地工具 → 代码模式 |
| 自动重试与等待时间 | 网络 → 自动重试 |
| 工作区 ZIP 迁移 | 工作区列表 → 工作区文件迁移 |
| 工作区后台任务 `workspace_job_*` | 工作区工具本身，无额外开关 |

## 和官方版到底差在哪

下面按用途对比；外观、预览与工具调用层的改进另列，不把每个按钮都算成一项新功能。官方已有的能力不计为分支原创。

| 分类 | 本分支的增量 | 官方 3.1.1 已有 |
|---|---|---|
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

- **验证范围**：本版本有 16 个套件、168 项定向单元测试通过（0 失败 / 0 错误 / 0 跳过），调试变体编译及正式 R8 + lintVital 打包通过，版本、签名与包完整性已核对。发布沿用已有测试，没有重跑。**没有**做真机或模拟器界面回归，**没有**用真实提供商测缓存命中率，也没有跑全量测试套件。界面行为目前只在代码与单元测试层面确认。
- **后台不等于永不掉线**：Android 厂商强杀或系统重启会中断仍在跑的后台请求与工具，前台服务只是提高存活机会。被中断的任务标为未知/已中断，不自动重放副作用。
- **非文本结果跨重启有限**：图片等附件在进程重启后能从任务回执恢复的范围有限；正文保留受存储预算限制，超出时明确标注。
- **Office / EPUB 不还原完整排版**：Office 与 EPUB 是文本/章节提取，不还原版式、不读 DRM；HTML 用 Android 系统 WebView，脚本可运行但没有 JS 桥与本地文件系统访问，外部资源默认关闭、需手动放行；表格预览最多 300 行 / 40 列，完整内容切源码看。
- **网页登录仍是实验**：Gemini/Claude 网页会话走提示词协议，不保证登录后即可用，也不是原生 function calling。
- **不宣称缓存命中**：Pi/提示词侧的改动只是减少重复读盘、稳定前缀；实际命中率取决于供应商和请求内容，本版没有这方面的实测数据。
- **ZIP 只迁移文件区**：不含 Linux Rootfs、聊天数据库、设置或整机；`workspace_grep`/`workspace_glob` 只在文件仍在本机的工作区可用，SSH 工作区要用 `workspace_shell`。
- 子代理、压缩、自动互动和重试可能产生额外模型用量；工具转后台本身不重跑同一调用，完成提醒也不主动发起新的模型请求。

## 文档导航

- 当前版本：[r11 说明与边界](fork/RELEASE-r11.md) · [r11 机器可读验收](fork/verification/r11.json)
- 横向对比：[完整功能对比](fork/FEATURES.md) · [版本历史](fork/CHANGES.md)
- 维护：[构建、签名与回退](fork/README.md) · [修改声明](NOTICE.md) · [免责声明](DISCLAIMER.md)
- 历史版本：r1–r10 的逐版说明见 [CHANGES.md](fork/CHANGES.md) 目录，以及 `fork/RELEASE-rN.md` 与 `fork/verification/rN.json`（例如 [r10](fork/RELEASE-r10.md)、[r8](fork/RELEASE-r8.md)、[r3 历史稳定版](fork/RELEASE-r3.md)）。首页只保留当前版本，历史细节留在这些文件里。

## 上游与许可

继承自上游、不计入本分支新增的能力：本地 PRoot/Linux 工作区、SSH/SFTP、MCP、Skills、文件式记忆与整理、会话历史检索、用量记录与全局统计、多供应商聊天、Web UI、语音、媒体生成、搜索、翻译、后台悬浮窗与快速回复等。

上游项目与官方下载渠道：[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)。本分支保留上游版权声明与 **AGPL-3.0** 许可（见 [LICENSE](LICENSE)）；上游的赞助、贡献者与支持渠道不属于本分支。

### English summary

Unofficial personal fork of RikkaHub **3.1.1**. Keeps the upstream client and adds subagents, workspace background jobs, optional HTTP execution, automatic context compression, ZIP workspace migration, configurable retries, a per-chat usage panel, and a reworked tool-calling layer (batched read-only calls, compact MCP catalog, offloaded paged tool output, `workspace_grep`/`workspace_glob`, read-only `run_script`). **r11** adds a configurable default for long-message collapsing, durable per-conversation/per-assistant drafts, stable memory and `AGENTS` injection, isolated WebView HTML preview plus Markdown/CSV/TSV/JSON/EPUB text previews, consolidated tool guidance, a 1–3600 s threshold (default 30 s) for moving slow tool calls to the background with `background_tool`, and background manual compression with an optional completion notification. The formal R8/lintVital build and package checks pass; the 168 previously passed targeted tests across 16 suites were not rerun. No device/UI regression and no real-provider cache-hit measurements have been run. Package name, app name, icon and signing key are unchanged. Licensed under AGPL-3.0.

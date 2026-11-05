# RikkaHub · 个人增强分支（非官方）

基于 [RikkaHub 官方 3.1.1](https://github.com/rikkahub/rikkahub) 的个人修改版：保留官方聊天能力，围绕**多代理协作、后台任务、上下文管理与沉浸聊天**做增强。

与官方团队没有隶属关系；上游版权与 AGPL-3.0 许可保持不变；不提供官方或面向终端用户的技术支持。

当前源码 **3.1.1-r19 / 3000066** · 包名 `me.rerere.rikkahub.fork`。在 r18 上增加图片整理并发、语音语言与播放调节并优化 TTS 等待和取消链路。已有队列、重连、中文、六角色本名和多图规则全部保留，应用名、图标与签名不变。

## 下载与安装

**[下载最新正式 APK](https://github.com/682012/rikkahub/releases/latest)** · [r19 更新说明](fork/RELEASE-r19.md) · [历史版本](https://github.com/682012/rikkahub/releases)

源码分支与下载渠道分开：只有完成正式 R8 构建和验包的版本才标为 Latest；实际发布状态、安装包与校验值以 Release 页面为准。

- 下载通用 APK 即可安装，支持 ARM64 手机与 x86_64；正式包启用 R8 压缩与优化，和此前供开发使用的 fast 包区分。
- 本分支包名与官方不同，**不能覆盖安装官方版**；同包名、同签名的本分支旧版可以按常规覆盖升级。
- 升级前备份聊天与设置，不要为了升级先卸载（卸载会连应用数据一起丢）。从早期 `fork.*` 版本过来请直接下载安装，旧更新器不认识 `r` 系列版本号。
- 下载安装不需要构建环境；聊天模型与服务账号仍由你自己配置，不提供共享 API 额度。

## r19：图片并发与语音调节

- 图片整理增加可保存的 1–16 路并发，默认 3 路；固定工作协程处理，缓存、取消和单张失败隔离保留，模型共享上限仍生效。
- 语音页集中选择朗读语言、0.25–3 倍语速、音高和音量；系统 TTS 可选择已安装音色，OpenAI 增加语气指令，设置跨重启保存。
- 当前段优先合成，首段缩短，移除固定段间等待；限制预取并释放播过的音频，常见云 TTS 请求可在停止时取消。
- 客户端按段播放，不宣称所有接口都能字节流播放；短段/预取可能增加请求和费用，不保证上游提速。[r19 说明与边界](fork/RELEASE-r19.md)。

## r18：发送受理、待发队列与恢复

- 消息可靠存入队列后就清掉已提交内容，不再等前一轮 AI 回复完。等待受理时新写的字和新加的附件保留；受理失败则保留原输入。
- 待发队列按会话存入私有目录，写成功才确认，尚未落入聊天记录的消息可在重启后找回；用稳定消息 ID 避免重复入库。
- 网络恢复接回失败的文字请求与待发队列，不要求会话页正开着。只恢复网络暂停，手动停止、鉴权/配置错误与待审批任务不会被网络回调放行。
- 断线从完成的模型步骤接着走，保留已完成工具结果，不重复执行已开始但结果不明的操作；半截新输出不叠加成重复回复。

[r18 说明与验证边界](fork/RELEASE-r18.md)。

## r17 延续的中文、角色本名与自动分批

- **中文不再夹英文**：补齐 `zh` 简体资源，修复新增人设、角色动态、整理与预览入口回退英文的问题。
- **六只正式角色名**：GPT 白祈、Claude 橙音、DeepSeek 汐澜、Gemini 星璃、GLM 墨眠、Qwen 云笙。选择器、气泡与实际人设统一；你自己设置的非空名字保留。
- **多图直接发送**：不再逐次弹选择框。入口「设置 → 偏好设置 → 常规 → 多图发送」，默认开启、超过 1 张时分批、每批 1 张；启用阈值和每批张数都可改，也可关掉。
- 各批按顺序真正发送 N 张图片给当前视觉模型，再汇总为一次回答。分批会增加请求和费用，也可能丢失细小画面信息；不是把原图分组后仍全部塞进主请求。

[r17 完整说明与验证范围](fork/RELEASE-r17.md)。

## r16 保留的整理、预览与完整人设

- **会话批量整理**：按助手、搜索和 7/30/90 天未使用筛选；多选删除，全选跳过置顶，失败项保留选择。
- **图片整理**：自选视觉模型分类 App 上传图、旧生成图和媒体创作输出，人工勾选删除或标记保留。先确认外发与计费，不扫描系统相册、不自动删除。
- **轻量网页预览**：聊天里的 http(s) 链接在系统 WebView 内打开，有地址、进度、重试与外部打开；不内置另一套浏览器内核。工作区离线 HTML 沙箱仍独立。
- **公式与多图**：归一化常见 LaTeX 写法，解析失败保留源码；多图按已保存规则分批识别后汇总，避免供应商一次多图不兼容。分批会增加视觉请求数与费用，可在多图发送设置中调整。
- **发送和网络**：输入框与错误按会话隔离，已发内容按草稿快照清理，不清后来输入；网络恢复/切换唤醒等待重试，常规失败沿用所设间隔，工具和图片生成不自动重放。
- **六小只完整人设**：内置只读目录与用户覆盖分开，全文可看、复制、以此编辑或恢复默认；显示来源、版本、SHA-256 与 UTF-16 长度。AI 开启时每次有效点击现场生成，8 句只留作离线台词。模型、开关、自定义图片、每角色配置、动态/静态设置均保留。

[r16 功能、范围与验证入口](fork/RELEASE-r16.md)。r12–r15 的有效改动一并保留，包括压缩弹窗权限修复、图片预览与即兴点击；不含新增 24 张表情的动画接线，也不是 Live2D。

## r11 保留的改动

这一版主要让切换对话不丢草稿、长任务不用守着等、文件可以直接看。包名、签名与数据库结构不变。

1. **长正文折叠有默认值**：设置 → 偏好设置 → 界面偏好设置 → 长正文默认状态，可选「默认收起」或「默认展开」。本次进程内，每条消息的手动选择优先；重启后按设置的默认值显示，流式生成中不自动折叠。
2. **输入草稿持久化**：文字、附件引用、光标位置与编辑态保存到应用私有目录；已有对话按会话存，新会话草稿同时绑定助手与会话 ID。切换对话/助手、离开页面再回来，草稿还在。
3. **记忆与提示词更稳定**：相同内容不重复写入、不无故提升记忆版本；记忆清单按路径稳定排序；`AGENTS` 相同内容只注入一次，并按文件变化复核。
4. **文件预览更全**：工作区里的 HTML/HTM/XHTML/SVG 在隔离 WebView 中预览并可切源码；新增 Markdown 阅读、CSV/TSV 表格、JSON 格式化、EPUB 按目录提取章节文本。普通聊天里的 HTML/PDF/文本附件也能应用内预览。原有 PDF、Office 文本、压缩包清单、图片与音视频入口保留。
5. **Pi/CodeMode 工具指引整理**：静态工具说明集中注入一次，不按工具重复；已知入口直接复用、定位后分页读取。只改工具机制，不改助手人格。
6. **后台工具**：设置 → 偏好设置 → 常规 → 后台工具。耗时调用在前台等待阈值（默认 30 秒，可设 1–3600 秒）后转后台，**继续同一次执行，不取消不重跑**；`background_tool` 提供 start / list / result / wait / cancel。审批照旧、写操作仍排队，独立只读调用可以继续。
7. **手动压缩可后台**：点确认后即可离开页面，压缩在后台继续，完成时通知并可点回原对话；开关在设置 → 偏好设置 → 通知 → 压缩完成通知。提交前核对消息分支与正文：压缩期间新增的尾部消息保留，范围内的正文被改过则丢弃旧摘要。原图与附件不删除。

构建维护另补齐了 Release / Debug 的 Firebase 双配置与前置校验，详见 [构建指南](fork/README.md)。

## 已有增强也都保留

- **沉浸外观**：经典、工作台、专注三种布局；全局或按助手设置壁纸，厚卡片可切成薄底。
- **悬浮小助手**：六只透明角色，2D 动态/静态可切；可选模型生成互动回应，查看真实请求状态并停止；自动事件互动默认关闭。原自定义图和配置保留。
- **可信的速度与用量**：按对话区分主请求、子代理和辅助请求；累计消耗与上下文占用分开，TPS 只计算有可信计时的步骤。
- **工作区访问**：保留目录挂载、用户授权的 Android 宿主命令及 Shizuku / 已有 Root 入口；PRoot 本身不等于 Android Root。

## 主要设置入口

| 想改什么 | 位置 |
|---|---|
| 长正文默认展开 / 收起 | 偏好设置 → 界面偏好设置 → 长正文默认状态 |
| 自动分批发图、启用张数、每批张数 | 偏好设置 → 常规 → 多图发送 |
| 耗时工具转后台、等待阈值 | 偏好设置 → 常规 → 后台工具 |
| 压缩完成通知 | 偏好设置 → 通知 → 压缩完成通知 |
| 每模型上下文、自动压缩、并发上限 | 提供商 → 编辑模型 → 基本 |
| 布局、壁纸、沉浸聊天 | 偏好设置 → 主题 |
| 悬浮角色、人设全文与 AI 互动 | 偏好设置 → 通知 → 悬浮角色 |
| 批量整理会话 | 历史记录 → 整理 |
| 分类、保留或批量删图 | 设置 → 文件管理 → 图片整理 |
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

- **历史 r11 基线验证范围（不替代 r16 验证）**：r11 有 16 个套件、168 项定向单元测试通过（0 失败 / 0 错误 / 0 跳过），调试变体编译及正式 R8 + lintVital 打包通过，版本、签名与包完整性已核对。发布沿用已有测试，没有重跑。**没有**做真机或模拟器界面回归，**没有**用真实提供商测缓存命中率，也没有跑全量测试套件。界面行为目前只在代码与单元测试层面确认。
- **后台不等于永不掉线**：Android 厂商强杀或系统重启会中断仍在跑的后台请求与工具，前台服务只是提高存活机会。被中断的任务标为未知/已中断，不自动重放副作用。
- **非文本结果跨重启有限**：图片等附件在进程重启后能从任务回执恢复的范围有限；正文保留受存储预算限制，超出时明确标注。
- **Office / EPUB 不还原完整排版**：Office 与 EPUB 是文本/章节提取，不还原版式、不读 DRM；HTML 用 Android 系统 WebView，脚本可运行但没有 JS 桥与本地文件系统访问，外部资源默认关闭、需手动放行；表格预览最多 300 行 / 40 列，完整内容切源码看。
- **网页登录仍是实验**：Gemini/Claude 网页会话走提示词协议，不保证登录后即可用，也不是原生 function calling。
- **不宣称缓存命中**：Pi/提示词侧的改动只是减少重复读盘、稳定前缀；实际命中率取决于供应商和请求内容，本版没有这方面的实测数据。
- **ZIP 只迁移文件区**：不含 Linux Rootfs、聊天数据库、设置或整机；`workspace_grep`/`workspace_glob` 只在文件仍在本机的工作区可用，SSH 工作区要用 `workspace_shell`。
- 子代理、压缩、自动互动和重试可能产生额外模型用量；工具转后台本身不重跑同一调用，完成提醒也不主动发起新的模型请求。

## 文档导航

- 当前源码：[r16 说明与边界](fork/RELEASE-r16.md) · [正式包与机器可读验收](https://github.com/682012/rikkahub/releases/latest)
- 六角色素材历史：[r12 立绘与动态](fork/COMPANIONS-r12.md) · [r15 即兴互动](fork/COMPANIONS-r15.md)
- 横向对比：[完整功能对比](fork/FEATURES.md) · [版本历史](fork/CHANGES.md)
- 维护：[构建、签名与回退](fork/README.md) · [修改声明](NOTICE.md) · [免责声明](DISCLAIMER.md)
- 历史版本：r1–r10 的逐版说明见 [CHANGES.md](fork/CHANGES.md) 目录，以及 `fork/RELEASE-rN.md` 与 `fork/verification/rN.json`（例如 [r10](fork/RELEASE-r10.md)、[r8](fork/RELEASE-r8.md)、[r3 历史稳定版](fork/RELEASE-r3.md)）。首页只保留当前版本，历史细节留在这些文件里。

## 上游与许可

继承自上游、不计入本分支新增的能力：本地 PRoot/Linux 工作区、SSH/SFTP、MCP、Skills、文件式记忆与整理、会话历史检索、用量记录与全局统计、多供应商聊天、Web UI、语音、媒体生成、搜索、翻译、后台悬浮窗与快速回复等。

上游项目与官方下载渠道：[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)。本分支保留上游版权声明与 **AGPL-3.0** 许可（见 [LICENSE](LICENSE)）；上游的赞助、贡献者与支持渠道不属于本分支。

### English summary

Unofficial personal fork of RikkaHub **3.1.1**. Keeps the upstream client and adds subagents, workspace background jobs, optional HTTP execution, automatic context compression, ZIP workspace migration, configurable retries, a per-chat usage panel, and a reworked tool-calling layer (batched read-only calls, compact MCP catalog, offloaded paged tool output, `workspace_grep`/`workspace_glob`, read-only `run_script`). **r16** adds conversation/image organization, system-WebView link previews, math normalization, sequential image recognition, conversation-bound sending, immediate network-recovery wakeups, and six complete read-only companion personas with full-text viewing/copying/editing. AI taps generate fresh text; user overrides and original settings remain separate. See the r16 notes and release assets for current validation. **r11 (historical baseline)** adds a configurable default for long-message collapsing, durable per-conversation/per-assistant drafts, stable memory and `AGENTS` injection, isolated WebView HTML preview plus Markdown/CSV/TSV/JSON/EPUB text previews, consolidated tool guidance, a 1–3600 s threshold (default 30 s) for moving slow tool calls to the background with `background_tool`, and background manual compression with an optional completion notification. The formal R8/lintVital build and package checks pass; the 168 previously passed targeted tests across 16 suites were not rerun. No device/UI regression and no real-provider cache-hit measurements have been run. Package name, app name, icon and signing key are unchanged. Licensed under AGPL-3.0.

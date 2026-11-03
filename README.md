# RikkaHub · 个人增强分支

## r10 沉浸聊天与 AI 小助手（预发布）

**本版：`3.1.1-r10 / 3000057`。** 去掉壁纸聊天的厚重外框；多步 TPS 显示可信部分及覆盖步数；修复含 assistant 图片的压缩请求；小助手可选择模型自动回应，并能查看、停止真实请求。保留 r9 对齐、三张原立绘、折叠和文件预览。稳定 latest 仍 r3。

[下载 r10 APK](https://github.com/682012/rikkahub/releases/download/v3.1.1-r10/rikkahub-3.1.1-r10.apk) · [改动与使用说明](fork/RELEASE-r10.md) · [验证范围](fork/verification/r10.json)

本轮交付按用户要求收敛到构建、签名和安装包一致性；不宣称全量界面回归已通过。

## r9 消息气泡对齐修复（前一预发布）

**前一预发布：`3.1.1-r9 / 3000056`。** 修复 r8 用户短消息气泡偏到左侧，恢复与右侧头像/操作栏一致；助手仍靠左，折叠、立绘和文件预览全部保留，TPS 统计规则未改。新增 8 项横向位置回归，最终 JVM 2103 项零失败／1 跳过、Android 147 项全过；r8→r9 同签名覆盖与三布局/大字号真实前后对照通过。稳定 latest 仍 r3。

[下载 r9 APK](https://github.com/682012/rikkahub/releases/download/v3.1.1-r9/rikkahub-3.1.1-r9.apk) · [验收与限制](fork/RELEASE-r9.md) · [错位原因与修复](fork/ALIGNMENT-r9.md) · [机器可读结果](fork/verification/r9.json)

## r8 可折叠信息与立绘角色（前一预发布）

**前一预发布：`3.1.1-r8 / 3000055`。** 基于 r7，顶部摘要、长正文与悬浮面板回复都可折叠，默认角色换成三张透明立绘。稳定 latest 仍是 r3，旧 r4/r5/r6/r7 保留。正式签名包 20,414,516 字节（19.47 MiB），最终 JVM 2103 项零失败／1 项跳过、Android 139 项全过，r7 → r8 同签名覆盖与真实界面验收通过。

[下载 r8 APK](https://github.com/682012/rikkahub/releases/download/v3.1.1-r8/rikkahub-3.1.1-r8.apk) · [验收与限制](fork/RELEASE-r8.md) · [立绘与素材来源](fork/PORTRAIT-r8.md) · [机器可读结果](fork/verification/r8.json)

本轮只改三处“挡位置”和角色形象，r7 的多格式预览、工作区权限、统计与压缩等能力原样保留：

- **顶部摘要**：默认一行显示当前提供商、模型和生成速度，点箭头展开详细计时与上下文；抽屉、统计、预览和新会话入口不丢。展开选择随设置保存，经典/工作台/专注三套布局行为一致。
- **长正文折叠**：超过阈值的长消息默认折成约 280dp 预览，展开读全文、收起恢复。正文字符不剪断，媒体、附件与待审批工具不在裁切区。手动展开/收起按消息节点（messageId）记在进程内，滚动回收不丢，App 重启回到默认折叠。
- **悬浮面板预览**：回复预览默认隐藏，点了才展开；待审批内容始终可见。面板高度最多 62% 可用屏幕、上限 440dp。

角色形象换成用户本轮提供的三张透明立绘：蓝发鲸鱼女仆（按悬浮可读性裁半身）、白发龙少女、橙发阅读少女（坐姿全身）。旧 `cat` / `dragon` 选择自动映射到女仆 / 龙少女，默认不再显示原来的几何小动物；选角、名字、台词、大小与动效仍可在设置里改。角色默认收成 48×56dp 边缘入口，点开显示立绘，再点给轻微姿态与台词并打开快速回复，可一键收回边缘。轻微呼吸/位移由原生 Compose 实现，**不是 Live2D，也没有骨骼、表情切换或口型同步**。

素材为三张优化 WebP，合计约 1.0MB；抠图只在本机构建时用 SkyTNT anime-seg（Apache-2.0）CPU 跑一次，模型和推理运行时都不进 APK，也没有新增 Node、Electron 或浏览器引擎。

[立绘与素材说明](fork/PORTRAIT-r8.md) · [r8 验收](fork/RELEASE-r8.md) · [verification/r8.json](fork/verification/r8.json) · [完整功能对比](fork/FEATURES.md)

下面 r7 及更早版本是已发布资产，链接与校验保持原样；稳定 latest 仍是 r3。

## r7 互动角色与应用内预览（上一预发布）

**上一预发布：`3.1.1-r7 / 3000054`。** 基于 r6 保留全部能力，新增后台悬浮角色与点击互动、全宽提供商/模型信息、常显生成速度、壁纸协调配色，以及 sandbox 文件链接和多格式应用内预览。稳定 latest 仍为 r3，旧 r4/r5/r6 原样保留。

[下载 r7 APK](https://github.com/682012/rikkahub/releases/download/v3.1.1-r7/rikkahub-3.1.1-r7.apk) · [验收与限制](fork/RELEASE-r7.md) · [角色与文件说明](fork/COMPANION-r7.md)

## r6 合并与沉浸外观（实验）

**本轮代码：`3.1.1-r6 / 3000053`。** 以已发布 r5 为底合入工作区主动授权、Android 宿主命令、Shizuku/Root 及目录挂载。新增独立可切换的聊天布局和全局壁纸外观，主题、壁纸与统计共用原有设置/数据，不内置额外 UI 引擎。本版的安装包、测试范围与限制见 r6 发布说明，下面 r5 的通过数字不代表 r6。

[下载 r6 APK](https://github.com/682012/rikkahub/releases/download/v3.1.1-r6/rikkahub-3.1.1-r6.apk) · [r6 验收与限制](fork/RELEASE-r6.md) · [校验记录](fork/verification/r6.json) · [外观与壁纸](fork/APPEARANCE-r6.md) · [权限与挂载](fork/WORKSPACE-ACCESS.md)

## r5 会话工作台与统计修正（实验）

**前一实验版：`3.1.1-r5 / 3000052`。** 新增可切换的工作台界面、实时单对话统计与来源筛选，分清累计消耗/上次上下文，重做TPS与延迟口径。保留r4全部功能；稳定latest仍r3。

[下载r5 APK](https://github.com/682012/rikkahub/releases/download/v3.1.1-r5/rikkahub-3.1.1-r5.apk) · [变化与验收](fork/RELEASE-r5.md) · [完整记录](fork/verification/r5.json)

## r4 基线：网页会话与缓存修复

**前一实验版：`3.1.1-r4 / 3000051`。** Gemini/Claude网页会话直接作为模型接入，另修客户端提示词缓存与上下文计数。账号凭据只在手机加密存储；真实账号登录/生成和供应商命中率尚未实测。此版标记prerelease，**稳定版latest仍为r3**。

[下载r4实验APK](https://github.com/682012/rikkahub/releases/download/v3.1.1-r4/rikkahub-3.1.1-r4.apk) · [完整验收与边界](fork/RELEASE-r4.md) · [网页会话使用](fork/WEB-ACCOUNTS.md) · [缓存修复](fork/CACHE-r4.md)

以下功能分组和下载区保留稳定版 r3 基线；r8 与 r4 的变化见上方，不把实验性网页功能算成官方能力。

基于 [RikkaHub 官方 3.1.1](https://github.com/rikkahub/rikkahub/tree/3.1.1) 的非官方分支。保留官方聊天客户端，重点补上**多代理协作、后台长任务、自动压缩、工作区文件迁移，以及工具调用层的调用效率与可读性**。

**稳定基线版本：`3.1.1-r3` / `3000050` · 维护分支：`fork/3.1.1` · 数据库：schema 34。**

名字和图标使用上游最新版 RikkaHub，版本主体与官方 3.1.1 对齐，本版后缀为 `r3`。新增默认网络切换恢复：VPN/网络恢复后提前重试文本请求、重建远端MCP会话、保留回复预览且不重放已发送工具。r2的每模型上下文、自动压缩与并发设置完整保留。完整继承 r1 / fork.43 的功能与官方 master `5ad19405` 基线；独立品牌草稿不混入本版。

> 与官方团队没有隶属关系。本仓库记录个人修改，并在 GitHub Releases 保存已验安装包，方便重复下载；不提供官方或面向终端用户的技术支持。官方渠道下载的是官方版，不是此分支。上游版权与 AGPL-3.0 许可证保持不变。

[完整功能对比](fork/FEATURES.md) · [r3 更新与说明](fork/RELEASE-r3.md) · [版本历史](fork/CHANGES.md) · [构建维护](fork/README.md) · [修改声明](NOTICE.md)

## 下载与重复使用

**[下载 3.1.1-r3 通用 APK（18.2 MiB）](https://github.com/682012/rikkahub/releases/download/v3.1.1-r3/rikkahub-3.1.1-r3.apk)** · [版本说明与 SHA-256](https://github.com/682012/rikkahub/releases/tag/v3.1.1-r3) · [最新正式版](https://github.com/682012/rikkahub/releases/latest)

固定版本链接不会随 nightly 被覆盖；每个正式版本保留对应源码 tag、APK 与校验文件。直接安装不需要编译环境，也不会附带维护者的聊天、账号或服务凭据。

同包名、同签名的 r1/r2 与 fork.36–43 可按常规覆盖升级，先备份聊天和设置；不要为升级先卸载。旧版更新器只认 `-fork.`，首次进入 r 系列请直接从上面的链接下载安装；已安装 r1/r2 的用户可检查更新。r7 与 r8 沿用同一签名和 schema 34，可按常规覆盖 r7；先备份聊天与设置，保留原包，不要先卸载。官方包与本分支不同，不能直接覆盖官方版。需要重新编译时见 [构建与维护](fork/README.md)，签名密钥和本机配置自行准备。

## 相比官方，到底增加了什么？

**按独立使用场景分为 5 组新增工作流、2 项主要功能增强，稳定性修复另列。** 这不是按按钮或提交数统计，也不把官方已有功能算成分支原创。fork.43 重做了工具调用层，改的是“怎么调用、怎么读回”，没有新增使用目的，因此不改变这里的组数。

| 分类 | 能力 | 官方 3.1.1 已有 | 本分支的增量 |
|---|---|---|---|
| 新增 ① | 子代理协作 | 没有内置子代理任务管理 | 独立上下文/模型的任务；排队、等待、取结果、取消；默认并发 4，可设 1–8，按设备内存自动降 |
| 新增 ② | 工作区后台任务 | shell 与终端 | `workspace_job_start/status/kill`；独立任务、状态和日志；超时可配，0 表示不限 |
| 新增 ③ | HTTP 远程执行 | SSH/SFTP 工作区 | 可选自托管 HTTP shell/job 后端；与 SSH 分开路由，不把命令误送到另一台机器 |
| 新增 ④ | 自动上下文压缩 | 手动压缩与检查点 | 按模型独立设置开关及阈值，到达窗口阈值时自动摘要，可指定压缩模型；保留原始历史 |
| 新增 ⑤ | ZIP 工作区迁移 | 文件操作、单文件/目录批量导出 | 整个文件区 ZIP 导出与导入新工作区；校验后发布，不覆盖已有工作区或聊天 |
| 增强 ① | 重试策略设置 | 自动重试开关，指数退避 | 次数 0–100；固定 30/60 秒、30–60 秒随机或自定义 1–600 秒，主/子代理共用 |
| 增强 ② | 单会话用量面板 | 请求用量记录、全局统计 | r5支持实时会话仪表板、来源筛选、经典/工作台切换；分开累计消耗和上下文报告 |

计数说明：单会话用量面板是官方统计能力的增强；ZIP 是新增迁移流程，不代表官方没有文件导出。前两组共增加 **5 个子代理工具 + 3 个后台任务工具**，不能再把它们分别算成 8 组功能。[计数与源码依据 →](fork/FEATURES.md)

## fork.43：工具调用层重做

**这一版没有新的使用目的，改的是“一次回复里怎么调工具、大输出怎么读回来”。下面的数字都是代码里的硬上限，不是实测加速；没有端到端模型耗时或令牌账单对比。**

- **独立只读调用并发**：同一批里的只读工具（读记忆、搜网页、读会话、读/搜工作区、技能、时间等）可以重叠执行，最多 8 路；写操作和无法判断语义的工具仍是顺序屏障，不会和只读调用混跑。同批里同名同参数的只读调用只执行一次，结果共享给其余成员。
- **`tool_batch`**：一次调用提交最多 16 个已规划好的调用，结果按调用顺序返回；某个成员失败只回一条错误字段，其余照常执行。批内的嵌套审批照样生效。
- **MCP 工具过多时自动瘦身**：配置的 MCP 工具达到 16 个、或估算体积达到 20000 字符时，模型看到的是精简目录加 `tool_search` / `tool_call`（搜索默认 6 条、最多 8 条），先搜再调，而不是一次把全部 schema 塞进请求。
- **大输出外置 + 分页读回**：单次工具输出的文本合计超过 16K 字符时，全文落到应用私有目录，上下文里只留回执与头尾各 2K 预览，模型用 `tool_output_read` 按字符 `offset`/`limit` 读回。保留 7 天、最多 64 份输出，按 32MB 预算轮转（至少保留最新一份），按会话归属，别的会话拿着同一个 id 读不到；按流解码到目标字符窗口，不把全文装进内存。
- **子代理结果分页**：`subagent_result` 支持 `offset`/`limit`，一页默认 6000、最多 12000 字符，回带 `nextOffset` / `totalChars` / `eof`。运行中、失败、超时、取消的任务都能用同一套方式读部分结果。
- **子代理并发按内存分级**：默认 4，可设 1–8；设备内存小的自动降到 4 或 2（硬上限 8），避免“子代理数 × 每批 8 路调用”同时打满。
- **重复调用收敛**：同名同参数、纯文本输出完全相同且不短于 400 字符时，发给模型的请求里第 2 次起换成一行指向首次结果的说明（消息与界面仍保留完整内容）；同名同参数的调用连续执行 4 次且结果都一样时，第 5 次不再执行，直接回错误让模型换路。轮询类工具（等子代理、查后台任务、当前时间）豁免这两条。
- **工作区按行读与搜索**：`workspace_read_file` 支持 `offset`/`limit`（默认 2000 行、最多 20000 行，回带 `totalLines`/`hasMore`/`nextOffset`）；新增 `workspace_grep`（正则或字面量、`glob` 过滤、三种输出模式、上下文 0–20 行、分页）和 `workspace_glob`（按修改时间新→旧），默认跳过 `.git`、`node_modules`、`build` 等目录、二进制与超过 2MB 的文件。**只在文件仍在本机的工作区可用**；SSH 工作区明确拒绝，并提示改用 `workspace_shell`。
- **`run_script`（代码模式）**：模型写一段 JS，在一次工具调用里按需调用多个只读工具，返回日志、返回值及调用元数据，中间全文不直接进入上下文。上限 32 次嵌套调用、每次与总计各 120 秒、并发 8；日志和返回值各 8K，更大的输出照常外置。**脚本里看不到 shell、MCP、写操作、子代理、`tool_batch` 和 `run_script` 自己**：这些都没有“跑到一半停下来等人审批”的挂起点，只能留在脚本外。
- **让缓存前缀稳下来的几处**：Claude 请求的 messages 缓存断点落在最后一条消息的内容块上（system / tools 各 1 个、messages 最多 2 个，合计不超过协议上限 4 个，顶层自动断点只作兜底）；`AGENTS.md` 按工作区、当前目录和文件 mtime/size 缓存，逐步重拼系统提示时不再重复读盘；记忆清单不再打印更新日期，减少与正文无关的日期变化导致的缓存失配；工具使用规范按工作区后端写成固定的几句话，同一个工作区里逐字节相同。

入口：`run_script` 在 **助手详情 → 本地工具 → 代码模式**，按助手开关，默认关闭；子代理并发在 **设置 → 高级与长任务 → 子代理**；工作区搜索与按行读不需要额外开关，审批默认放行，可在工作区详情页按工作区覆盖。

**这些改动不承诺更快或更省 token。** 并发、去重、外置、分页都是“少一次重复上下文、少一轮多余调用”的机制，实际效果取决于模型、工具组合和任务本身；受控 mock 工具的返回字符数已测量，见下方链接；没有端到端模型收益数据。

[改动细节与文件级说明 →](fork/RELEASE-fork43.md) · [受控工具测量](fork/verification/fork43-benchmark.md)

## fork.41 修复：结束命令时真正结束进程

- `workspace_job_kill`、`workspace_shell` 超时与取消、`workspace_shell_kill` 现在会结束命令在 PRoot 里启动的全部进程。在手机上的 fork.40 实测：任务停不掉，超时的命令仍在后台运行，后台命令被结束后还继续执行后面的命令。
- 后台命令和任务收到结束请求后不再执行剩余命令，留出宽限期让已启动的进程收尾，再强制结束。
- 跟进官方 master `5ad19405`：模型选择面板支持多关键词搜索。[原因、实测与验证 →](fork/RELEASE-fork41.md)

## fork.40 新增：官方后台命令（预览）

- 带入官方尚未合入 master 的 `feat/background-shell`：`workspace_shell` 可设 `run_in_background`，用 `workspace_shell_output` 读输出、`workspace_shell_kill` 结束，命令结束后主动通知模型。
- 与本分支 `workspace_job_*` 并存：官方后台命令归属当前会话，只在本地 Rootfs 提供；`workspace_job` 仍是可跨会话查询的独立长任务。子代理不拿后台命令工具。
- 预览功能以官方之后合入 master 的版本为准。[说明与验证 →](fork/RELEASE-fork40.md)

## fork.39 这次跟上了什么？

- 官方 BetterText Markdown：文本选择、公式、表格、代码高亮与颜色色块。
- 思考链折叠时保留工具审批；矮屏加号菜单支持滚动。
- OpenAI空/非标准role兼容、SAF导出、非英文余额格式、Tinyfish `purpose`等官方修复。
- 更新检查跟随本fork的GitHub Releases，不再误报“官方3.1.1是新版本”或引导下载不同签名包。
- **SSH实际跑通**：密钥导入、主机指纹确认、SFTP读文件和SSH终端 `pwd`；新渲染和审批可见也做了Android界面验证。

SSH入口：**设置 → 扩展 → 工作区 → 加号 → SSH远程工作区**。高级设置的“远程执行”是另一路HTTP服务。

[本轮全部官方提交、APK校验与验证边界 →](fork/RELEASE-fork39.md)

## fork.38 保留的功能

- **重试等待能真正修改**：固定 30 秒、60 秒，或每次在 30–60 秒内随机；自定义编辑器不会因碰巧输入预设值而消失。
- **工作区终于能导入 ZIP**：支持旧裸文件包、Unicode/二进制、空目录、有效空包；导入为新的本地工作区。也可明确选择去掉 `workspace/` 或 `files/` 外层目录。
- **导出内容可选**：跳过缓存/构建目录，或包含这些目录的全部普通文件；完成页展示跳过数量，不只显示“成功”。
- **补齐真实边界**：ZIP 路径/CRC/大小/权限校验、取消撞上提交的状态一致性、过期临时目录清理，以及真实 Android 驱动的大行读取回退。

**升级后的默认重试等待改为固定 60 秒。** 旧实现实际按 1、2、4…秒退避、上限 60 秒；这两件事不同。嫌等待长可在网络设置改为固定 30 秒或随机区间。

入口：**设置 → 网络 → 自动重试**；**工作区列表 → 工作区文件迁移**；高级设置中的工作区迁移入口使用同一套实现。

## 还有哪些加固？

流式生成约 1.5 秒节流保存、节点差分写入；超大消息失败时完整分片读取而非跳页；备份导入的用量表归一化、记忆冲突保留、FTS 对齐；工具输出可读性保护；多会话通知隔离与精确清理；流式工具调用的 id 归属与参数增量回退；`tool_batch` 的成员审批判定失败时按“需要审批”处理，不会因一次异常静默放行。

这些属于数据保护、兼容和缺陷修复，**不计入新增功能数**。完整历史见 [CHANGES](fork/CHANGES.md)。

## 哪些功能来自官方？

**本地 PRoot/Linux 工作区、SSH/SFTP、MCP、Skills、文件式记忆与整理、会话历史检索、全局用量统计、多供应商聊天、Web UI、语音、媒体生成、搜索、翻译、后台悬浮窗和快速回复等，均继承上游。** MCP 精简目录、工具并发与输出外置是本分支对官方 MCP/工具能力的改造，不改变“MCP 本身来自官方”这一点。

官方项目、下载渠道、赞助与贡献者信息请看 [上游仓库](https://github.com/rikkahub/rikkahub)。本分支不将上游的功能、赞助关系或支持渠道表述为自己的。

## 验证到哪里了？

| 检查 | 真实结果 |
|---|---|
| 正式构建 | [HB 本地构建与验收](https://github.com/682012/rikkahub/blob/v3.1.1-r3/fork/verification/r3.json)；R8 + lintVital 通过 |
| JVM | 1692 项，0 失败/错误，1 项跳过 |
| Android | API 34 AOSP x86_64，67 项全过；含真实网络断开恢复、MCP回包丢失不重放 |
| 签名覆盖升级 | r2 覆盖 r3、沙盒标记保留、冷启动通过；同签名/schema34无新迁移 |
| 未验证 | 用户真机升级、厂商后台行为、真实模型 token/费用/耗时收益 |

[完整验收与边界](fork/RELEASE-r3.md) · [机器可读摘要](fork/verification/r3.json)

上表数字属于 r3，不代表 r8。本轮生产提交 `2def5785cf259023b56ca607f704426c6aa4f3ec`，2025 个生产输入逐文件核对相同；最终包哈希、21 张已复核截图与完整测试摘要见 [r8 验收](fork/RELEASE-r8.md)。此前 1 项 Compose 测试宿主失败、隔离 3 次重跑与最终完整回归的范围也如实披露，不宣称已查明偶发失败根因。

## 使用前知道这些限制

- r8 的折叠是界面层状态，长正文展开/收起只在本次进程内按消息记住，重启回默认折叠；只有头部摘要展开与角色开关持久化。立绘是静态透明图加轻微动效，不能当 Live2D 骨骼、表情或口型同步用。

- 切VPN时旧TCP不能原地迁移。r3恢复请求/会话，重生成可能再次计费；不保证零延迟或逐字接续。已发送工具结果不确定时不会自动重跑。

- 每模型设置入口：设置 → 提供商 → 编辑模型 → 基本。请求上限1–64，留空不额外限制；与全局子代理总数分别生效。上下文窗口不改变服务端模型容量。
- 子代理默认关闭；不支持嵌套、独立费用预算或子代理内审批/续跑。需要审批的操作交给父对话。并发是本机资源的取舍：每个子代理都有独立上下文与工具集，设高不保证更快。
- `run_script` 只能调用只读白名单里的工具，不能执行 shell、访问 MCP、写文件或派生子代理；脚本没有审批挂起点，因此这些能力刻意不可见。它也不是省 token 的保证。
- MCP 工具超过阈值后不再是“全部直接可见”，模型必须先 `tool_search` 才能拿到名字；搜索按页返回；知道准确工具名时可直接调用。工具很多的 MCP 服务器会因此多一轮调用。
- 外置的工具输出保留 7 天、最多 64 份输出，按 32MB 预算轮转（至少保留最新一份），超出或过期会被清理；清理后旧回执读不回来，只能重新执行原工具。
- 工作区 `workspace_grep`/`workspace_glob` 只在文件仍在本机的工作区可用，SSH 工作区要用 `workspace_shell`；它们也不替代 `workspace_shell` 的通用命令能力。
- SSH 不提供 `workspace_job_*`。HTTP 模式的 shell/job 在服务端执行，文件读写/搜索仍针对手机本地工作区，不是远端文件；令牌具有命令执行权限，必须保密。
- **ZIP 只迁移文件区，不含 Linux Rootfs、应用数据库或整机。** 不恢复符号链接；旧包没有记录的权限不能凭空恢复。导入后如需命令执行，需要安装 Rootfs 并选择新工作区。
- 默认导入限额：100000 条目、12 GiB 展开、4 GiB 单文件/ZIP。分卷、加密或非 UTF-8 文件名明确拒绝；导出不构成运行中文件的原子快照。
- 前台服务不等于锁屏永不掉线；HTTP 服务重启后旧任务不会自动安全接管。消息 UI 尚未懒分页，不能保证任意大历史不 OOM。
- `me.rerere.rikkahub.fork` 与官方包不同，不能覆盖安装官方版；跨分支导入前保留原备份。子代理、压缩与重试都可能增加请求和费用，不提升模型本身的智力。

## 维护与许可

功能增量对比基线为官方 **3.1.1 / `ad2c4430`**；当前已合入截至 2026-11-01 的官方 master **`5ad19405`**，fork.43 没有再跟进新提交，也不承诺永远领先后续官方版本。`master` 保留上游镜像，`fork/3.1.0` 与 `fork/3.0.0` 是历史分支。

[构建与回退](fork/README.md) · [AGPL-3.0](LICENSE) · [个人分支声明](DISCLAIMER.md) · [修改记录](NOTICE.md)

### English summary

Unofficial personal fork of RikkaHub **3.1.1**, based on upstream master **5ad19405 (2026-11-01)**. Preserves subagents, background jobs, HTTP execution, automatic compression, ZIP migration, configurable retries and per-chat usage. **fork.43 reworks the tool-calling layer**: concurrent independent read calls with same-batch dedup and serial barriers, a compact MCP catalog with `tool_search`/`tool_call`, offloaded large outputs with paged read-back, paged subagent results with memory-aware concurrency, collapse of repeated tool results, `workspace_grep`/`workspace_glob` and paged file reads, and `run_script` for read-only tool chains. `run_script` cannot reach shell, MCP, writes or subagents, and none of this is a measured speed or token guarantee. **r2 adds per-model compression overrides and a cancellable, live-updating API concurrency cap**, independent for each configured model. **r3 adds bounded network-handover recovery for model requests and MCP sessions without replaying sent tools.** HB verification: 1692 JVM tests (1 skipped), 67 Android tests, signed R8/lintVital build and r2-to-r3 emulator upgrade succeeded. No real-user upgrade or model latency/cost benefit is claimed. [Release notes](fork/RELEASE-r3.md).

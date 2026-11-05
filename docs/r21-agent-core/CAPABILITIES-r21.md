# r21 Agent 底层 · 本轮能力、入口与限制（源码说明）

本文件只写**当前源码里确实存在的东西**：能做什么、在哪儿、什么条件下不做。
版本仍是 `3.1.1-r21 / 3000068`（`app/build.gradle.kts`），没有为这一轮单独升版本号。

> **状态：草稿，未构建、未安装、未真机验证。**
> 本目录和 `fork/RELEASE-r21.md` 的 r21 Agent 段落都是在**没有运行任何 Gradle 任务**的前提下写的。
> 没有 APK/AAB、安装、发布或真机截图。真实 Kotlin/JUnit 定向检查、五条供应商 HTTP 接线编译已经执行，服务与完整 Compose 页面接线也已通过独立编译。
> 当前证据和待验证范围见 [VALIDATION.md](VALIDATION.md)，不把源码实现或局部编译写成整包验收；r20 不变。

---

## 1. Agent 主循环：缓存友好、重复周期、安全步数（`ai/src/main/java/me/rerere/ai/agent/`）

**入口**：`AgentLoop`（由 `ChatRequestFactory` / `ChatService` 组装 `AgentRequest` 后驱动）。没有新增开关，行为对现有会话自动生效。

- **工具索引只建一次**（`AgentLoop.toolIndex`）：整次运行按工具名建 `HashMap`，取代每个 part、每次调用都线性扫 `request.tools`。同名声明取第一个，与旧的 `find` 语义一致。
- **参数规范化按内容缓存**（`CanonicalArgumentsCache`）：每步都要重新规范化整段历史里的工具参数，缓存键是参数原文、值只依赖参数本身，同一次运行内不重复解析。
- **缓存重复周期检测**（`ToolRepetition.kt`）：
  - 同一个调用（同名 + 规范化参数）连续 `MAX_IDENTICAL_REPEATS = 4` 次、结果相同，下一次不再执行，直接回结构化错误让模型换路；
  - 能识别**周期**而不只是连续重复：ABAB 这类交替振荡周期为 2，最多识别 4 个调用的轮转；至少要看到 `MIN_LOOP_CYCLES = 4` 个完整周期才判无进展；
  - 只对**纯文本输出**成立；带图片/文件的结果一律原样保留（可能悄悄变化）。轮询类工具用 `Tool.repeatable` 豁免；
  - 被拦下的调用写入固定错误文本，并在后续判定里被识别成"不算进展"，不会在计数被清零后无限循环。
- **请求侧去重不改历史**（`dedupeRepeatedToolOutputs`）：只替换**发给模型的副本**里重复的纯文本结果（`DEDUPE_MIN_OUTPUT_CHARS = 400` 以下不动），消息本身与界面仍保留完整结果；替换只依赖更早的内容，同一段历史每次得到同样结果，**不破坏提示词缓存前缀**。
- **安全步数继承**（`AgentLoop.inheritedSteps`）：
  - 审批后继续、工具续跑这类"接着跑同一条消息"的情况，**继承这条消息已经用掉的步数**（`UIMessage.stepCount`），不能靠反复续跑换无穷预算；
  - 只是继续写回复、没有待执行工具时，仍是一次全新的预算（与以前一致）；
  - 上限判断改为 `steps >= request.maxSteps`，继承后已经超过上限（例如用户把 maxSteps 调小）也能兜住，不会越界多跑一步。

**限制**：重复检测看的是"同名 + 规范化参数 + 相同文本输出"，不做语义判断，不同参数但同一件事仍会放行；去重只影响送给模型的文本，工具真实执行次数不变。

## 2. 助手隔离：按助手读写、显式迁移、旧共享记忆复制不覆盖

**入口**：会话 / 记忆 / 工具 / 待发队列各层都按会话自己的 `assistantId` 取值；跨助手只由用户显式动作。

- **孤儿助手不再回退当前助手**（`ConversationConfig.orphanAssistant()`）：助手被删后，会话拿到一个同 ID、空配置的占位（无记忆、无工具、无工作区、无技能、无注入）。`getChatModelOf` 对孤儿会话返回 `null`，生成入口 `ChatRequestFactory` 用 `check(...)` 给出明确错误 `r21_assistant_missing`，不再把别的助手的人格和记忆串进来。
- **显式迁移**（`Conversation.migrateToAssistant(target, settings)`）：纯函数，只搬对话本身（消息、分支、标题）；配置快照按目标助手重拍，`modeInjectionIds` / `lorebookIds` 重绑；`workspaceCwd`、`folderId`、会话级 system prompt、聊天建议、压缩摘要**不跟过来**。普通 `bindConfig` 不走这条路，孤儿会话也不会被钉死在空快照上。
- **记忆恒按助手隔离**（`MemoryRepository`）：`Assistant.memoryId` 现在恒为 `id.toString()`；`useGlobalMemory` 只为反序列化兼容保留，不再把多个助手接到同一份库。
- **旧共享记忆复制不覆盖**（`MemoryRepository.copyFiles`）：显式迁移时在写锁内串行执行，跳过目标助手已有的同名 `path`，`from == to` 直接返回。入口在助手 → 记忆 → 设置页（`LegacyMemoryCopyState`，显示复制条数 / 失败原因，一次性提示，不做自动同步）。
- **对话检索按助手收窄**（`ConversationTools`）：`recent_chats`、`conversation_search`、`conversation_read` 都限制在当前助手的对话范围内，读取前先核归属；用户自己用的全局搜索框不受影响。
- **抽屉按助手取列表**（`ChatDrawer`）：抽屉 VM 用 `chat-drawer:<assistantId>` 作 key 并 `bindAssistant`，切换助手不会看到上一个助手的会话列表。
- **待发队列跟着会话走**（`ConversationInbox`）：`bindOwner` 要求队列里没有待发消息；`migrateOwner` 只允许在消息清空后迁移，并把旧助手未发出的通知 key 记进 `mutedNoticeKeys`（上限 512），旧助手的通知不会跟着进新助手。

**限制**：旧的共享记忆库文件仍留在磁盘上，不会被自动删除也不会自动合并；要不要搬、搬到哪个助手，完全由用户点。会话上已经固定的模型/工具属于原助手，只有迁移才会重拍。

## 3. 经验学习：只统计 + 用户确认，不改权重，不保证提升

**入口**：助手 → 记忆页 → 「经验」标签（`MemoryTab.LEARNING` → `AssistantLearningTab` / `AssistantLearningPanel`）。仓库 `AssistantLearningRepository`（Koin 单例，`filesDir/assistant-learning`，每助手一个 JSON）。

- **只存计数与 id**：`LearningTurn` 只有 assistant / conversation / message / model id 与步数、工具调用与失败、重试、是否完成、是否触顶、用户重新生成次数、首字与总耗时。不存正文、思考、工具参数与返回值、凭据；写入时截断清洗。
- **统计也只有能数出来的量**：`LearningStats` 里没有"质量分""提升幅度"这类字段——回答好坏没有现成真值可比，这里不假装能测。
- **经验只有两种来源**（`GuidanceKind`）：
  - `AUTO`「自动统计」：按固定模板从指标算出，**不落盘**，要求至少 3 轮重复证据（`MIN_EVIDENCE_TURNS`），证据窗口取最近 30 天 / 最近 60 轮；
  - `USER`「用户确认」：用户在设置页新增或确认的条目，才落盘。
- **注入有限**：`buildPrompt` 最多 3 条、总 1800 字符（单条 240），只取本助手、同模型的证据，XML 转义；注入段落名为「经验学习」，**不覆盖**主设定、用户要求与权限；压缩这类旁路请求（`modelOverride != null`）不注入。
- **硬上限**：每助手最多 200 轮指标（FIFO）、最多 24 条经验（18 用户 + 6 自动），单文件 4 MiB，进程内最多缓存 3 个助手的已解析文件。
- **存储可靠性**：写前加锁并读磁盘、tmp + fsync + rename；坏文件先唯一备份再恢复，**读不到开关时按关闭处理**；未知 schema 或 `assistantId` 不匹配直接报错，**绝不重绑归属**；关闭后停止收集与后续注入，旧数据保留、可删。
- **记录时机**：`ChatService` 轮末 `finally`，等待审批（`AwaitingApproval`）不记；写盘在 IO 线程、失败只记日志，不阻塞回复投递。同一 `messageId` 再次记录只更新指标，**不当成重新生成**；重新生成只由用户明确点击的 `markRegenerated` 计数。

**明确的限制（不要读成承诺）**：不训练或微调模型权重、不下载新模型、不额外发起模型请求、不自行提升工具或系统权限。经验条目只是提示词里的建议；计数与耗时不等于质量评分，**不保证每轮性能单调提升**，也不保证注入后一定更好。

## 4. 自动恢复：白名单式放弃、不重放副作用、通知不额外烧钱

**入口**：`ChatService` 的失败路径（`tryReconnect` / `recoveryLedger`）+ `ChatAutoRecovery`（纯决策层）+ `ChatRecoveryNotificationManager`（订阅 `AppEvent.ChatRecoveryStatus`，Koin `createdAtStart`）。沿用已有的重试策略（`retryPolicy().maxRetries`），没有新增开关。

- **先判"不能恢复"**（`ChatRecoveryBlocker`）：用户停止 / 等待审批 / 鉴权（401、403、Web 会话过期）/ 配额（402）/ 请求格式（400、422、Web 协议违规）/ 权限 / 本地落库失败 / 其它确定性失败 / **副作用未知**。命中任意一条：不建 ticket、不等网络、不静默重发。
- **不重放副作用**：工具已开始但结果未知，或新生成媒体尚未到安全检查点（`ChatReconnectCheckpoint.canResume == false`），按 `UNKNOWN_SIDE_EFFECT` 放弃自动恢复；已经落库的完整工具结果保留在检查点里继续，不重新执行工具；工具的 start 与每次完成结果都**先落库再继续**，进程被杀也不会把写操作静默重做。
- **只接同一条中断**：恢复前核对会话 `incarnation`、同一条用户消息、失败尾巴都没变；恢复时只把中断的那个分支替换成已完成的 checkpoint，其它分支不动；变了就清 ticket 并停成需要用户处理的状态。
- **次数与时效**：整轮默认 `DEFAULT_MAX_ATTEMPTS = 3` 次自动拉起，上限沿用用户的重试次数设置，ticket 存活 `TICKET_TTL_MS = 10 分钟`；超时给终态提醒，而不是迟到续跑。
- **通知**：一个会话全程只占一个通知身份（`chat-recovery:<conversationId>`），进度卡只更新一条、不响不震，终态才高重要度；完成通知只在 `AgentFinishReason.Completed` 时才带内容预览，每条完成通知带自己的 `chat-done:<conversationId>` tag；`PendingIntent` 按用途（chat-done / live-update / compression / recovery）+ 会话生成唯一 data URI，不会互相覆盖跳错对话。
- **不自动开付费轮次**：子代理完成与后台工具完成的通知以 `wakeOnArrival = false` 入队，**空闲时不会自己开一轮模型请求**，只在已有/用户发起的下一轮被取走；`InboxTurn.Notices` 只有在存在 `wakeOnArrival` 通知时才成立。后台 shell 完成也采用同一被动交付规则。通知通过 peek → 会话 flush → 精确快照 ack 交付；持久消息标识识别崩溃窗口，更新过的同键通知不会被旧快照误删。

**限制**：判定依赖异常类型与 HTTP 状态码，分类不到的异常归到 `OTHER`，宁可不恢复；恢复只保证"没有恢复的不会被误重放"，不保证每次都能恢复——网络长时间不回来就超时给终态。

## 5. 小宠物快捷开关（通知 / 磁贴）

**入口**：下拉快捷面板磁贴 `CompanionQuickTileService`（`AndroidManifest.xml` 已声明，`BIND_QUICK_SETTINGS_TILE`，`TOGGLEABLE_TILE`，标签 `@string/r21_companion_tile_label`）。

- 磁贴**只读写已有设置** `displaySetting.enableFloatingChatWindow`，不新增字段；设置页和磁贴改的是同一个值，悬浮窗显示/隐藏仍由原有浮动窗口逻辑负责。
- 不起前台服务、不持有悬浮窗；Koin 未就绪时显示为关闭而不是崩。

**限制**：磁贴是纯开关，不含会话操作；设备不支持磁贴或用户没添加时只能走设置页。

## 6. 聊天风格与启动动效

**入口**：外观设置 → 聊天风格（`ChatUiStyle`）；启动动效（`StartupMotion`，`app/src/main/java/me/rerere/rikkahub/startup/`）。

- **四种风格**：`CLASSIC` 经典 / `STUDIO` 工作台 / `FOCUS` 专注 / **`ORBIT` 轨道（本轮新增）**。`ORBIT` 的 `@SerialName("orbit")` 追加在枚举末尾，老配置的 `classic` / `studio` / `focus` 照旧能读回，旧版未必认识新枚举值，不承诺降级后导入新设置。
- `ChatStyleSpec` 新增两参数（`headerTaskRail`、`railContextGauge`）都带默认值 `false`，CLASSIC / STUDIO / FOCUS 三个既有 spec 观感不变；`ORBIT` 是贴着状态栏的紧凑任务栏 + 常驻运行态轨道（模型、速度、上下文、运行态圆点在同一条轨上），窄屏或大字体时自动收掉上下文表盘，保证模型名与按钮不被挤掉。
- 样式层只描述"长什么样"：消息数据、网络、工具、审批、语音逻辑一律不在这里，换风格不丢功能。
- **启动动效 `OFF` / `RING` / `DOTS`**（默认 `RING`）：`OFF` 什么都不画；`RING` 一圈轻轨道环 + 沿环光点；`DOTS` 光点从外圈向中心聚合，都用 Canvas 画，不引额外依赖。只在**冷启动 + 桌面普通 launch** 时播放；分享、通知、深链接、快捷方式进入一律不画；同一进程只演一次；**系统关闭动画（`animator_duration_scale <= 0.1`）时整段跳过**，读不到倍率按 1 处理。

**限制**：启动决策与轨道状态/样式已有真实 JVM 单元检查，但没有渲染或在真机上对比观感；启动动效的系统倍率读取依赖 `Settings.Global`，厂商改过的设备读不到即按 1 处理（仍会播放）。

## 7. 本轮保留的既有成果（不重复引入）

- r21 已合并的**后台图片整理**与**长分享文字**（导航只带 UUID、单份 4 MiB / 总量 32 MiB / 128 份、7 天过期）保持原样，见 `fork/RELEASE-r21.md` 对应段落。
- r20 的**媒体创作与导出**、**共读（co-reading）**、**技能缓存**、自动标题、子代理 `reasoning_content` 修复一并沿用；本轮没有重写，也没有重新调用付费 API 探测。
- BOM 文档读取修复已在 r20，本轮没有把旧实现带回来。

## 8. 验证边界

- 本轮未运行 Gradle、整包构建、安装或发布；没有 APK/AAB。真实 Kotlin/JUnit 与 compile-only 的证据单列在 [VALIDATION.md](VALIDATION.md)。
- Compose 观感、通知点击与授权、快捷磁贴绑定、WorkManager 调度与恢复、SAF、MediaCodec 仍未做设备验证。
- 静态 XML、Manifest 引用与 Kotlin R 符号检查不等于 AAPT 链接；定向编译不等于 Gradle/KSP/R8 通过。

# data.organization.autofile（r21 扩项 C）

可关闭的「话题归库」后端。**只改归属，不改内容、不删会话**；默认关闭。

## 文件

| 文件 | 作用 | 是否纯 JVM |
| --- | --- | --- |
| `ConversationAutoFileModels.kt` | `ConversationAutoFileSettings` / `ConversationAutoFileState` + 候选/计划模型 | 是 |
| `ConversationAutoFileStorage.kt` | 原子写读小 JSON、坏文件留档 | 是 |
| `ConversationAutoFileSettingsStore.kt` | 按助手存设置（`<root>/settings/<assistantUUID>.json`） | 是 |
| `ConversationAutoFileJournal.kt` | 批次日志（`<root>/journal/<assistantUUID>.json`） | 是 |
| `ConversationAutoFileParser.kt` | 严格 JSON 分类解析 | 是 |
| `ConversationAutoFilePlanner.kt` | 候选筛选 + 建议→搬迁计划（16 条 / 0.85 / 3 家族 / 12 库） | 是 |
| `ConversationAutoFileClassifier.kt` | `ConversationAutoFileClassifier` 接口 + 提示词 | 是 |
| `ConversationAutoFileFastClassifier.kt` | 用快速模型真发请求（`UsageSource.SUBAGENT` 记用量） | 否（模型栈） |
| `ConversationAutoFileGateway.kt` | 读写数据库/`ChatService` 的窄接口与真实实现 | 否 |
| `ConversationAutoFileController.kt` | 对外五个方法 + 并发/撤销/错误 | 是（依赖接口） |
| `ConversationAutoFileWork.kt` | WorkManager 周期任务（12h，唯一任务，仅 enabled 时排） | 否 |

## 对外 API（与契约一致）

```kotlin
class ConversationAutoFileController(
    storeDir: File,                                   // 建议 File(context.filesDir, "conversation_autofile")
    private val gateway: ConversationAutoFileGateway,
    private val classifier: ConversationAutoFileClassifier,
    private val scheduler: ConversationAutoFileScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observe(assistantId: Uuid): StateFlow<ConversationAutoFileState>
    suspend fun updateSettings(assistantId: Uuid, settings: ConversationAutoFileSettings)
    suspend fun organizeNow(assistantId: Uuid)      // 即使 enabled=false 也跑一次，仍守 >=24h 闲置
    suspend fun undoLast(assistantId: Uuid)
    fun initialize()                                // 只为已 enabled 的助手恢复调度
    suspend fun organizeScheduled(assistantId: Uuid) // Worker 用，重检 enabled
}
```

`ConversationAutoFileState(settings, running, moved, canUndo, error)`，`ConversationAutoFileSettings(enabled=false, idleHours=36, collapseFamilies=true)`（24..168 夹取）。

## 主代理需要的接线（一行版本）

```kotlin
// AppModule
single { me.rerere.rikkahub.data.organization.autofile.ConversationAutoFileController(
    storeDir = File(get<Context>().filesDir, "conversation_autofile"),
    gateway = me.rerere.rikkahub.data.organization.autofile.RoomConversationAutoFileGateway(
        conversationRepository = get(), folderRepository = get(), chatService = get()),
    classifier = me.rerere.rikkahub.data.organization.autofile.FastModelAutoFileClassifier(
        providerManager = get(), settingsStore = get(), usageRecorder = get()),
    scheduler = me.rerere.rikkahub.data.organization.autofile.WorkManagerConversationAutoFileScheduler(get()),
) }

// 启动（RikkaHubApp 里 ImageOrganizerController 恢复同类位置）
get<ConversationAutoFileController>().initialize()
```

依赖的既有 API：
- `ConversationRepository.getUnfiledConversationsOfAssistantPage / getConversationsOfAssistantPage / getConversationById`
- `FolderRepository.getFoldersOfAssistant / createFolder`
- `ChatService.canAutoFileConversation / applyAutoFileMove / postNotice`
- `SettingsStore.settingsFlow` + `fastModelId/fastModelReasoningLevel`
- `Conversation` 新字段 `folderAssignmentLocked`、`lineageId`（A 提供；summary 转换需带出这两个字段，否则候选会被当成「未锁、独立家族」）

Manifest 无需改动：Worker 是现成 `CoroutineWorker`，用 `GlobalContext.get().get<...>()` 取 Controller。

## 行为要点

- **候选**：同助手、未置顶、未手动锁、当前不在任何文件夹、闲置 ≥ `idleHours`（最低 24h）、有标题。
  同一家族（`lineageId ?: id`）只取**最早创建**的一条做模型输入代表，不看标题。
- **分类输入**：标题 + 开头 400 / 结尾 400 字的纯文本；不含思考、工具调用、附件；单批最多 16 条（16 个家族）。
- **采纳后扩成整族**：代表被采纳后，同一家族里所有**仍合格**的兄弟一起跟随（同目标库、同置信度），
  单批实际搬迁上限 200 条；兄弟不额外过模型、不加摘录，所以不增加 AI 输入与消耗。
  新库仍要求 ≥3 个**不同家族**，兄弟数量凑不出来。
- **采纳**：严格 JSON，置信度 ≥ 0.85，未知/重复 id 不动；已有文件夹优先（名字匹配即用现有 id），
  新库需 ≥3 个不同家族、这批结束后文件夹总数 ≤12。
- **提交**：只走 `ChatService.applyAutoFileMove`（重检 owner/updateAt/原文件夹/锁/忙碌），
  不读整条会话再存回去。失败/跳过逐项写日志，整批不会因为一条失败全部回滚。
- **设置顺序**：先写盘成功，再改内存/界面状态并排调度。写盘失败时磁盘、调度、界面都停在旧设置，
  只把错误显示出来让用户重试；快速连点开关由每个助手一把**设置顺序锁**串行，和分类批次的长锁分开，
  所以分类跑着的时候也能立刻关掉。
- **开关重检**：定时任务在分类前、分类后、以及**每提交一条移动前**都重检 `enabled`；
  中途关掉就不再新建文件夹、不再新增移动，剩余计划项一次写盘收尾成 `skipped`。
  手动「立即整理」是一次显式动作，即使 `enabled = false` 也允许跑完，但仍守 24h 闲置门槛。
- **日志**：先写 planned 再改库，逐项回写 applied/failed；进程被杀后留下的 planned 批次被标成
  `interrupted`，**不重放模型请求**。撤销唯一可信的版本是**计划时的那一版**（`updateAtBefore`）：
  `applyAutoFileMove` 只改归属列、不动 `updateAt`，所以「现在还是计划版本」才等于「用户搬完之后没再动过」；
  绝不使用搬完之后又读到的新版本，也不额外要求「现在又闲置 36 小时」。
- **中断批不被覆盖**：开新批之前，如果最近一批还是 `planned/interrupted` 并且含 applied 或无法确认的
  planned 项，这一轮直接不动（不开新批、不重发不确定的操作、不建库），把撤销入口留出来；
  用户撤销（或确认）后才继续。日志只存 id、文件夹 id、时间戳、状态，不存标题与正文。
- **撤销**：只撤最近一批，只撤「当前归属仍在这批的目标库、`updateAt` 仍是计划版本、未置顶、未加锁」的项；
  用户后来聊过、手动移动过、置顶或加锁的一律跳过，界面会提示有几个没撤成。
- **设置存储**：原子写；损坏或 assistantId 与文件名不符时默认关闭，并把坏文件留档为 `*.bak`。
- **调度**：只给 enabled 的助手排 12 小时唯一周期任务；关闭即取消；Worker 每轮重读设置。
  `initialize()` 不扫全库、不默认开启 AI 分类。
- **取消**：每提交一条之前 `ensureActive()`，`CancellationException` 不会被 `runCatching` 或
  `catch (Throwable)` 吞掉，锁与 running 一律在 `finally` 释放。


## 已知取舍

- `UsageSource` 现有取值里没有「归库」，先用 `UsageSource.SUBAGENT` 记用量；若主代理愿意在 enum
  末尾补 `AUTOFILE`，把 `ConversationAutoFileFastClassifier` 那一处换掉即可（不改就别改 enum）。
- 撤销不会删除这批新建的文件夹（里面可能已经有用户后来放进去的会话），只把会话搬回来。
- 候选只处理「还没归类」的会话；已经把会话放进文件夹的用户不会被自动搬来搬去。

## 测试（纯 JVM，`./gradlew :app:testGithubDebugUnitTest --tests '*autofile*'`）

| 测试 | 覆盖 |
| --- | --- |
| `ConversationAutoFileParserTest` | 严格 JSON：代码块/前后废话/未知字段/缺字段/重复 id/非法 UUID/超范围 confidence/名字折叠 |
| `ConversationAutoFilePlannerTest` | 候选筛选（置顶/锁/已归库/未闲置/无标题）、家族只留最早、16 条上限、未知/重复/低置信不动、已有库优先、新库 ≥3 家族、总数 ≤12；家族扩张：只扩被采纳的家族、不合格兄弟被剔除、上限 200、建议铺到整族、兄弟不能凑够 3 家族 |
| `ConversationAutoFileSettingsStoreTest` | 默认关闭、写入-读回、24..168 夹取、损坏留档、错归属留档、只列 enabled |
| `ConversationAutoFileJournalTest` | 先 planned 后 applied、interrupted 仍可撤、undone 后不可撤、坏日志留档、批次上限、日志不含正文、裁剪后批次 id 不撞名、skipPending 一次收尾、未收尾批次标记 |
| `ConversationAutoFileControllerTest` | 正常归库/状态、模型报错不动、关闭时定时任务不跑、撤销、撤销不覆盖用户后续改动、搬完立刻续聊不可撤、单条失败不影响整批、失败后能重试（锁释放）、忙碌跳过、家族代表+兄弟跟随、initialize 只排 enabled、设置切换调度、设置写盘失败保留旧设置且不排任务、分类中关闭→零移动、批处理中关闭→停手并收尾日志、取消不吞且释放锁、中断批保留撤销并挡住新批、崩溃 planned 批按计划版本判定 |
| `ConversationAutoFilePromptTest` | 提示词最多 16 条、含严格 JSON 字段要求 |

新增/改动的源码未经 Gradle 编译（本轮禁止跑 Gradle），由父代理接线后统一编译；测试为本轮手写，供 F 执行。

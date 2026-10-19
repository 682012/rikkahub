# RikkaHub Fork 交接文档（fork.16）

> 用途：给其他模型/维护者审查当前 APK 与源码。文中所有“已验证/未验证”都按实际执行情况标注，没有实测过的不会写成已验证。

## 1. 产物

| 项 | 值 |
|---|---|
| APK | `/sdcard/Download/rikkahub-fork-16.apk` |
| 大小 | 构建后以实际文件为准 |
| MD5 | `ae204b0e5e35e3c8e5e80a3eac55097a` |
| 包名 | `me.rerere.rikkahub.fork` |
| versionName | `3.1.0-fork.16` |
| versionCode | `3000016` |
| 签名 | V2，证书 SHA-256 `cb897a361d49933cc7d033b7ff05b95fad6483c18bb4134b10119b0f2715bf89` |
| 构建类型 | release **未跑 R8**（`-PfastBuild`），体积偏大，功能与正式包一致 |
| 基线 | RikkaHub 官方 `3.1.0`，commit `eeef60b2`；上一基线 `3.0.0` / `55a108a4` |

安装前提：与官方包、mod89、excp 等分支共存；同签名版本可覆盖升级，不同签名只能卸载重装后导入备份。

## 2. 源码与构建

- GitHub：`https://github.com/682012/rikkahub`，分支 `fork/3.0.0`，tag `v3.0.0-fork.14`
- 最新 commit：`b5cf3807`
- 构建机：HB（43.248.3.158:20031），源码 `/data/data1/rk3mod/src`
- JDK 21，Android SDK 37.2，Gradle 9.6
- 快速构建：`gradle :app:assembleRelease -PfastBuild --daemon`（约 5 分钟）
- 正式构建：去掉 `-PfastBuild`（R8 + lint，约 10–15 分钟）
- `material3/material-color-utilities` 是上游 submodule，构建时必须存在
- `app/google-services.json` 是占位文件，仅用于通过编译
- 签名配置在 `local.properties`（不入库）

## 3. 变更文件总览

新增：

```
data/datastore/ForkSettings.kt
data/ai/subagent/SubagentModels.kt
data/ai/subagent/SubagentRunner.kt
data/ai/tools/SubagentTools.kt
data/repository/RemoteExecutionClient.kt
data/db/entity/UsageRecordEntity.kt
data/db/dao/UsageRecordDAO.kt
service/WorkspaceJobForegroundService.kt
ui/pages/setting/SettingForkPage.kt
ui/pages/chat/UsageSheet.kt
workspace/WorkspaceJob.kt
workspace/WorkspaceJobManager.kt
```

修改：

```
data/datastore/Settings.kt
data/ai/tools/WorkspaceTools.kt
data/ai/tools/ChatToolFactory.kt
data/ai/tools/ConversationTools.kt
data/repository/WorkspaceRepository.kt
data/repository/ConversationRepository.kt
data/db/AppDatabase.kt
data/db/dao/MessageNodeDAO.kt
data/db/migrations/Migration_27_28.kt
data/files/FilesManager.kt
data/sync/BackupManager.kt
data/sync/DatabaseBackup.kt
di/AppModule.kt
di/DataSourceModule.kt
service/ChatService.kt
ui/pages/setting/SettingPage.kt
ui/pages/chat/ChatPage.kt
ui/components/message/tools/ToolUI.kt
RouteActivity.kt
AndroidManifest.xml
app/build.gradle.kts
workspace/WorkspaceShellRunner.kt
```

## 4. 数据层

### 4.1 Room schema

- 版本：官方 30 → 31；fork 统一为 **32**（避免与官方同名 usage_record 冲突）
- 新增表 `usage_record`：

```sql
id TEXT PK,
conversation_id TEXT NOT NULL,
node_id TEXT,
model_id TEXT NOT NULL,
source TEXT NOT NULL,          -- chat | subagent
prompt_tokens INTEGER NOT NULL,
completion_tokens INTEGER NOT NULL,
cached_tokens INTEGER NOT NULL,
total_tokens INTEGER NOT NULL,
created_at INTEGER NOT NULL
-- 索引：conversation_id / created_at / model_id
```

- 迁移链：25→26、26→27、27→28、28→29、29→30；fork 走 **30→32** 或 **31→32**
- 从 2.5.x（schema 25）导入可一路升到 31

### 4.2 备份导入对齐（`DatabaseBackup.alignToTarget`）

触发条件：staging 库与当前 App **版本相同但 identity hash 不同**（典型：excp 分支 schema 31）。

行为：

1. 删除目标结构里不存在的表（`agent_runs`、`workflows`、`scheduled_jobs`、`ssh_hosts`、`telegram_chats` 等）
2. 补建缺失的表，按目标库 SQL 执行
3. 共用表补缺失列（用目标列的默认值）
4. 旧 `MemoryEntity` 转成 `profile` 记忆文件后再删
5. 补索引
6. 对齐后再次校验表/列集合，缺任何一项直接抛错
7. 写入目标库 identity hash

风险：删表是破坏性的（只作用于 staging 副本，原备份不动）；列类型差异不做处理。

### 4.3 记忆迁移

`Migration_27_28` 除了生成 `profile`，额外写 `_legacy/raw_memories.md` 保留原始条目。该文件只在**重新导入 25 库**时产生，已迁移的库没有。

## 5. 功能实现

### 5.1 子代理 `SubagentRunner`

- 工具：`subagent_run / wait / result / list / cancel`
- 每次任务独立 `AgentRequest`，工具集继承父会话（去掉 `subagent_*`）
- 并发：`active` 计数 + `released` 状态流，最多 5 秒等一次，不会永久挂起
- `maxConcurrent` 1–4，硬上限 4
- 超时：`withTimeoutOrNull`，超时状态 `TIMEOUT`
- 主模型抛错时用 `fallbackModelId` 重试一次（超时不重试）
- 结果写 `filesDir/subagents/<id>/result.md` 与 `events.log`
- 运行期间 `ChatGenerationForegroundService.acquire`
- 用量写 `usage_record`，`source=subagent`
- 会话检索：`recent_chats / conversation_search / conversation_read`，按 `includeChatHistory` 开关
- 工具可见性按 `includeWorkspace / includeMcp / includeSearch` 过滤

未实现：`allowNested`（始终禁止嵌套）、子代理单独计费/预算。

### 5.2 工作区

- `WorkspaceTools` 超时来自 `WorkspaceRuntimeSettings`：`commandTimeoutSec`（默认 30）、`maxTimeoutSec`（0 = 不限）
- `WorkspaceManager.readResult`：`timeoutMillis <= 0` 时用无参 `waitFor()`
- `workspace_job_start/status/kill`：
  - 本地：`if command -v setsid; then setsid ... else nohup ... fi; echo $! > pid`
  - 日志读写用 `RandomAccessFile` 尾部 256KB
  - job 元数据在 `<workspace>/.jobs/<id>/`
- 前台服务：`WorkspaceJobForegroundService`，`specialUse` + partial wakelock（`WAKE_LOCK` 权限已加）
- 任务结束由 `jobMonitorScope` 每 10 秒检测并释放服务
- 远程：`/exec`、`/job/start`、`/job/status`、`/job/kill`，Bearer token；开启后本地不执行

### 5.3 用量

- 写入点：`ChatService.runTurn` 的 `finally`（主对话）；`SubagentRunner.recordUsage`（子代理）
- 读取：`UsageSheet`（聊天页顶栏“用量”），显示总量、缓存命中率、按模型拆分
- 只统计写入之后产生的请求，历史不补记

### 5.4 自动压缩 `ChatService.maybeAutoCompress`

- 触发：`contextTokens >= model.contextLength * thresholdPercent / 100`
- 条件：开关开启、最后节点不是检查点、没有未完成工具调用
- 行为：用当前请求上下文生成摘要，`insertContextCheckpoint` 插在最后一条之后，不删历史
- 失败：静默跳过，不阻塞发送
- 设置：`Settings.autoCompress { enabled, thresholdPercent }`

### 5.5 大会话加载 `ConversationRepository.externalizeLargeParts`

- 新数据不再外置或截断工具输入/输出；正文和工具结果原样入库
- 加载 fork.14 旧消息时，识别 `…[full text: …/msg_cache/<md5>.txt]`，校验前缀后恢复完整正文
- 旧 `msg_cache` 视为持久数据：不再自动清理，随备份导出/恢复
- base64 媒体 > 512k 字符 → 解码写文件，消息换成 `file://`（旧 `msg_cache` 媒体也会转存为普通上传文件）
- 工具卡片提供“查看完整输出”，点开才读文件（最大 100 万字符，分段渲染）

## 6. 已知风险与未验证项

**fork.15 修复后仍需真机验证：**

1. 本地 job 在锁屏/切后台后的存活与通知释放
2. 远程同步命令在“停止生成”后是否及时终止
3. fork.14 旧会话首次加载恢复耗时与内存峰值

**仍未在真机验证：**

1. 本地 `workspace_job_start` 的完整链路（启动 / pid / status / kill / App 重启后查询）
2. 前台服务锁屏后的实际存活时长，以及 Android 15 `onTimeout` 回调（未实现兜底）
3. 自动压缩在真实长会话上的触发与摘要质量
4. 子代理并发 2 时的内存峰值（512MB 堆）
5. 备份对齐遇到差异更大的库（缺主键、FTS 结构不同、触发器）

**设计上的已知限制：**

1. 远程服务端有并发槽限制，但没有目录隔离，所有任务共享 `RK_EXEC_ROOT`
2. 远程 token 明文存在 `settings.json`，会随备份导出
3. 子代理结果文件有保留期；`msg_cache` 作为旧数据恢复源保留，没有 UI 清理入口
4. `SubagentTask.steps` 存的是工具调用次数，不是模型步数
5. 自动压缩依赖模型 `contextLength` 准确，默认 1,000,000 会让阈值形同虚设
6. 消息分页未实现，超大会话靠外置缓解
7. `alignToTarget` 对同版本但结构差异极大的库可能直接抛错（这是有意为之，避免带残缺结构启动）

**已经修过、但值得复查的脆弱点：**

1. `UsageRecordDAO` 的 Koin 绑定（曾插错位置导致“用量”闪退）
2. `WAKE_LOCK` 权限（曾缺失，会让 job 服务启动即崩）
3. `UsageSheet` 的滚动容器（曾用无限高度 LazyColumn）
4. 远程 `exitCode: null` 的解析（曾抛异常）
5. 子代理并发等待（曾可能永久挂起）
6. 本地 job 的 shell 优先级（曾可能不执行）

## 7. 建议审查的具体问题

1. `SubagentRunner.start` 的协程启动顺序、`jobs` map 生命周期、取消传播是否完整
2. `acquirePermit` 的计数与 `released` 信号是否存在竞态
3. `WorkspaceJobManager.start` 生成的 shell 在 proot/busybox 下是否可用；pid 是否正确指向 setsid 子进程
4. `WorkspaceRepository.appContext()` 通过 Koin 取 Context 是否可靠
5. `ChatService.maybeAutoCompress` 与 `session` 锁、生成状态的关系，是否存在重入或竞态
6. `externalizeLargeParts` 对发送上下文的影响（正文不动，工具输出预览是否足够）
7. `alignToTarget` 的删表/补列顺序，外键与 FTS 表是否被误删
8. `UsageTotals` / `UsageByModel` 的 Room 列名映射在 R8 后是否稳定
9. 前台服务的 `startForeground` 在 Android 12+ 后台启动限制下的行为
10. 远程客户端与服务端的协议字段是否完全对齐（尤其 null 字段）

## 8. 复现与测试步骤

1. 安装 fork.14，确认能正常启动
2. 设置 → 高级与长任务：
   - 工作区：默认超时 30，最大 0
   - 远程：填 `https://hb.682012ysh.top:8443/rk-exec` 与 token
   - 子代理：开启，并发 2，超时 900
   - 上下文：自动压缩开启，阈值 80
3. 聊天页 → 用量：应显示总量与按模型拆分，不应闪退
4. 让模型跑 `workspace_shell sleep 5`，再跑 `workspace_job_start` 一个 2 分钟的循环，`job_status` 查日志，`job_kill` 停止
5. 开启远程后重复第 4 步，确认执行在服务器（`uname -a`）
6. 让模型派生两个子代理，确认并发、取消、结果回传、用量 +2
7. 打开一个大会话，确认能加载、超长工具输出可展开
8. 导入 25（mod89）与 31（excp）两种备份，确认迁移/对齐结果

## 9. 相关文档

- `fork/CHANGES.md`：与原版的功能差异
- `fork/README.md`：构建、签名、发布、回退
- `DISCLAIMER.md`：个人改版声明
- `NOTICE.md`：AGPL 修改声明

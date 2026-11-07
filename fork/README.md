# 个人分支：构建、发布与维护

本页只讲**怎么把这个分支构建出来、怎么发、出问题怎么回退**。用户功能与安装说明在 [仓库首页](../README.md) 与 [功能对比](FEATURES.md)，当前版本的变化见 [m3 说明](RELEASE-m3.md)。

## 当前状态

| 项目 | 值 |
|---|---|
| 版本 | `3.1.1-m3` / `3000072` |
| 维护分支 | `fork/3.1.1`（本轮开发分支 `release/meridian-m3`） |
| 包名 | `me.rerere.rikkahub.fork`（Debug 变体 `me.rerere.rikkahub.fork.debug`） |
| 应用名 / 图标 / 签名 | 与 m1、r21 及更早的本分支版本一致，未改 |
| 官方基线 | `3.1.1` / `ad2c4430`，已合入上游 master `5ad19405` |
| 运行时构建提交 | 见本版 Release 的 `BUILT-SOURCE-MANIFEST.json` / `m3.json`，不沿用历史收据 |
| 数据库 | schema 36（r21 34→35 加会话家族列，m1 35→36；**m2 无迁移**，分组复用已有文件夹归属，思考档位是 nullable 字段；沿用 r3 起的本分支编号，不与官方编号混用） |
| 发布标签 / 资产名 | `v3.1.1-m3` / `rikkahub-3.1.1-m3.apk` |

已发布的历史版本各自保留 tag 与资产，不覆盖、不复用版本号。正式下载入口为 [Latest](https://github.com/682012/rikkahub/releases/latest)，固定版本按 tag 取包。

下载侧只有**一个通用 APK**：m2 是建在 m1 之上的增量版本，直接覆盖 m1（或 r21 及更早版本）即可，不拆包、不需要依次安装；m1、r21 的 Release/tag/资产保留用于回滚，r22 从未单独发布成包。

## 构建环境

JDK 21、Android SDK 37.2、Gradle 9.6；NDK 28.2.13676358、CMake 3.22.1。Web UI 需要 Node.js 22 与 pnpm 11，先在 `web-ui/` 执行 `pnpm install --frozen-lockfile`。3.1.1 有 `github` / `play` 两个 flavor，APK 输出在 `app/build/outputs/apk/github/release/`，不再是旧的 `apk/release/`。正式构建内存开销大（Gradle 配置 6 GiB 堆，另有 Kotlin/R8 与原生编译），不要在同一目录并发跑两份 release。

```bash
# 快速编译：跳过 R8，只能当开发包，不是正式包
./gradlew :app:assembleGithubRelease -PfastBuild --daemon

# 正式构建：R8 + release lintVital
./gradlew :app:assembleGithubRelease --daemon --max-workers=2
```

`material3/material-color-utilities` 已作为**普通源码目录**提交，与已验构建内容一致，不需要额外的子模块初始化；上游许可证在该目录内保留。

## Firebase 双配置（r11 修过）

`github` flavor 的两个变体各有自己的配置，缺一个就会在 Gradle 或运行期失败：

- Release：`app/google-services.json`，包名 `me.rerere.rikkahub.fork`
- Debug：`app/src/debug/google-services.json`，包名 `me.rerere.rikkahub.fork.debug`

两份配置由维护者私下保管，不放入公开仓库。Windows / HB 云构建入口在 pnpm / Gradle 之前调用 `fork/scripts/verify-firebase-config.py`：从 Gradle 读出 applicationId 与 debug 后缀，核对 JSON 结构、项目号、app id 与包名，缺文件、包名不符或 JSON 损坏时直接给出具体错误；内容相同的配置不重复写盘。脚本本身无网络依赖，也不会打印 key。

不要用「伪造 Firebase client」「禁用插件」或改包名的方式绕过——r11 之前 Debug 变体失败的原因就是迁移时只带了 Release 配置。

## 签名与本地配置

`local.properties`（不入库）：

```properties
storeFile=/private/path/fork.jks
storePassword=<local-secret>
keyAlias=fork
keyPassword=<local-secret>
```

只有同包名 + 同签名才能覆盖升级；自行签名的包不能覆盖维护者签名的安装包，反之亦然。keystore、口令、API key、远程 token、聊天数据库与整机备份都不进仓库，源码备份也不能代替数据备份。

GitHub 的 `Fork Verify` 验证流程使用 `KEY_BASE64`、`SIGNING_CONFIG` 与仓库内的占位 Google Services 脚本；它不能证明生产 Firebase 配置可用。旧手动 Daily Build 另读取 `GOOGLE_SERVICES_JSON`。本版正式包走 Windows 云构建与真实双配置，两条路径不要混用；下载 APK 不需要这些构建材料。

## 发布流程与纪律

1. 先合并远端已生效的修复，再选未占用的版本号；已发布的 tag、APK 与验收记录不覆盖。
2. 源码与已验输入逐文件核对；只改文档不会扩大测试覆盖范围，也不重新跑测试。
3. 构建正式包（R8 + lintVital），核对 versionName/versionCode、包名、证书指纹、ABI 与 SHA-256。
4. 以独立 tag 归档 APK 与校验文件；同名资产已存在时先比摘要，不同就拒绝，不能拿新包冒用旧版本。
5. 快速包（`-PfastBuild`）用于开发验证，不能替代启用 R8 的正式发布包。
6. 维护分支的 CI 手动触发（`Fork Verify` 只在推送 `ci/**` 或手动运行时构建签名包、跑 JVM 与 API 34 模拟器测试），不自动发 Release；推文档不会顺带发 nightly。
7. 可见版本号用「上游版本 + 短后缀」（`3.1.1-r10`、`3.1.1-r11`），versionCode 独立递增，不再套早期的 `3000000 + fork.N` 公式。

## m2 的验证入口

正式 R8 + lintVital 构建通过（job `idem-4fb0edae9da863c46e30033e`）；版本名/码、包名、原证书、双 ABI、ZIP CRC 与云端/本地 SHA-256 已核对。 未自动安装手机，未做真机 UI / OEM 授权、真实供应商 MAX 接受度与计费、完整备份恢复、断电和网络竞态端到端验证。

本轮实际做过的是源码级验证：

- 整包 Kotlin 编译通过：幂等 job `idem-fdfd182b185f67271c1e05e6`，succeeded，回执 783,684 ms。首轮 `idem-28ffb20b06314b76773892bc` 因三处接线错误失败，修正后重投，两次日志都保留。
- 定向 Kotlin/JUnit **115 项全部通过**（按源码直编、去重后不叠加历史运行）：手动分组 17、渠道切换 29、渠道设置 13、单对话思考 37、草稿 19；对应 sourceId `4821c665c0489c6cb90a99d65318a8ec81a15170708fd28ea9e9818e719f0751`。m1 的 174 项与 r21 的 167 项各自绑定旧提交，**不计入也不相加**。
- 构建输入与最终清单比对无漂移；`git diff --check`、资源 XML 解析与重复 key 检查通过。
- 三项功能的 115 项定向检查按源文件 SHA 逐一匹配后复用；本次新跑 47 项安全回归（幂等账本 26、自动化 21）全部通过，不叠加历史套件或重复运行。 最终输入 `06e11458f2592866e92fdf8610d74c9d4e120184b2ba9ca04bdb36fa63396cc0`，实际构建提交 `885a0c1ea8bc55d34b710b382cbd1b5cd7c76707`。

**没有自动安装手机、没有真机 UI 点验**：分组的多选与组头触控、渠道设置页的排序交互、输入栏思考档位、真实 OEM 授权、真实供应商对 MAX 的接受度与真实计费都未端到端验证。文档改动属于 docs-only，运行时代码不变，不改变任何已记录的 sourceId。

## m2 源码对应（维护用）

| 主题 | 主要位置 |
|---|---|
| 手动分组策略与组展开状态 | `data/organization/grouping/`（`ConversationGrouping`、`GroupExpansionStore`）、`ui/pages/chat/ConversationGroupItems.kt` |
| 分组读写路径 | `ConversationDAO` / `ConversationRepository`（复用 `folder_id`，按助手过滤）、`ChatDrawer` 与其 ViewModel、`ConversationList` |
| 渠道计划解析与执行层 | `data/ai/channel/ChannelFailover.kt`、`data/datastore/ChannelFailoverSettings.kt`、`data/ai/ChatRequestFactory.kt`（`rebuildParamsForChannel`） |
| 渠道设置页与路由 | `ui/pages/setting/SettingChannelFailoverPage.kt`、`SettingPage.kt`、`RouteActivity.kt`（`Screen.SettingChannelFailover`） |
| 单对话思考档位 | `data/model/ConversationConfig.kt`、`data/draft/ChatDraftStore.kt`、`ui/pages/chat/ChatVM.kt`、`ui/hooks/ChatInputState.kt`、`AssistantBasicPage.kt` |

改这些区域时留意：分组只改归属，不要碰 `lineageId` 与消息；成员查询必须带助手过滤。渠道切换不能把会话当前渠道插到白名单前面，全失效时要明确报错而不是回退列表外渠道；`prepareStep` 的 `maxTokens`/`tools`/`disableToolCalls`/`reasoningLevel` 必须原样保留。思考档位只落当前会话，别写回助手默认。

## m1 的验证入口（历史）

正式构建同样走 Windows 云节点 `release`（R8 + lintVital），幂等 job `idem-0c72cda63733c87329b243ea`，`sourceId b1ae6585244e06d2054f24766952b9147c7bc36539091895d25a4705393ee21c`，`./gradlew :app:assembleGithubRelease` 一次成功，26m54s（1,614,083 ms），exit 0。产物 `rikkahub-3.1.1-m1.apk` 46,325,122 字节（44.18 MiB），SHA-256 `cc71498019a8db0eb4078499d7c4f7a7cde6d0c8813dfb4238dc18a1b0545bf4`；另产出 mapping 233,748,435 字节（`1cfc00fa…0e45d`）。验包核对：包名 `me.rerere.rikkahub.fork`、`3.1.1-m1` / `3000070`、证书 `cb897a36…15bf89`、`arm64-v8a` + `x86_64`、ZIP CRC、445 个条目、云端与本地 SHA-256 与字节数一致，`apksigner` / `aapt` 版本已记录。

定向 Kotlin/JUnit **174 项全部通过**，按源码直编，不含历史套件：输入优化 56、输入优化重写 12、插话收件箱 23、设备边界 26、压缩 13、MCP 幂等 15、权限 29。r21 的 167 项绑定旧冻结提交，**不计入本轮**，也不与 174 相加。

**没有自动安装手机、没有真机 UI 点验**；正式发布状态以 Release 页为准：会话选项的触控与观感、权限页跳转与真实 OEM 授权、真实模型的输入优化/独立审查用量与计费都未端到端验证。构建完成后的文档改动属于 docs-only，运行时源码不变，**不需要重新编译**，也不改变上面的哈希。

## r21 的验证入口（历史）

本版只运行本次改动相关的定向检查，不重跑历史全套。正式构建使用 Windows 云节点 `release`（R8 + lintVital），唯一幂等 job `idem-5df62db4b9dd07b77ca82ef4`，`sourceId 986fcce3e2701f5e0cadfc0f1c794e6ec443355aa6b72c02abc259651dca2839`，`./gradlew :app:assembleGithubRelease` 一次通过，Gradle 32m34s，Room/KSP、整包 Kotlin、R8 与 lintVital 全部完成，mapping 1,546,908 行。产物 `rikkahub-3.1.1-r21.apk` 45,842,374 字节（43.72 MiB），SHA-256 `623e7ebde54f604e95df25d8c6ddd04874937a71ec51291a306c1b3042b167c9`；包名、版本名/码、原 r20 证书、arm64-v8a + x86_64、ZIP CRC 与云端/本地哈希均核过。构建时冻结清单 2623 个文件逐 hash 比对零漂移；发布阶段只补文档、校验记录与 Room 导出 schema，运行时代码不变，真实 Room 导出的 schema 35（identityHash `1f1167d7b7175efa7f8fde4946443215`）随源码归档。

定向 Kotlin/JUnit 当前 167 项：流式内核与恢复 49、真实草稿解析 24、时间 16、正文/时间指标 9、HTML 策略 10、归库 planner/store 55、会话元信息提交 4，全部通过。历史 375 项属于更早的冻结提交，**不能**与本轮相加宣称「542 项通过」。仍没有自动安装、真机 UI 点验、通知点击、WorkManager 调度/恢复、SAF、MediaCodec 与快捷磁贴绑定的运行期验收；网页只在 Chromium 移动视口实渲染。真实结果随 [正式 Release](https://github.com/682012/rikkahub/releases/latest) 的 `r21.json` 与校验文件交付。

## r21 源码对应（维护用）

| 主题 | 主要位置 |
|---|---|
| 主循环：工具索引、参数缓存、周期重复检测、步数继承 | `ai/src/main/java/me/rerere/ai/agent/AgentLoop.kt`、`ToolRepetition.kt` |
| 助手隔离与显式迁移 | `data/model/Conversation.kt`（`migrateToAssistant`）、`ConversationConfig.orphanAssistant()`、`data/repository/MemoryRepository.kt` |
| 恢复决策与通知 | `ChatAutoRecovery`、`ChatReconnectCheckpoint`、`ChatRecoveryNotificationManager`（`service/ChatService.kt` 接线） |
| 经验学习 | `data/repository/AssistantLearningRepository.kt`、UI 在助手 → 记忆 →「经验」 |
| 家族折叠 | `data/organization/ConversationLineage.kt`、`data/db/dao/ConversationDAO.kt`、`ConversationFork.kt` |
| 相似话题归库 | `data/organization/autofile/`（Controller/Planner/Journal/Work），UI `ui/pages/chat/ConversationAutoFilePanel.kt` |
| 工具参数实时草稿 | `service/ChatService.kt`（`STREAM_TOOL_DRAFT_PERSIST_INTERVAL_MS = 500`）、`ai/ui/StreamChunkHandler.kt` |
| 数据库 34→35 | `data/db/AppDatabase.kt`（`version = 35`）、`data/db/migrations/Migration_34_35.kt` |
| 轨道风格与启动动效 | `ChatUiStyle` / `ChatStyleSpec`、`app/src/main/java/me/rerere/rikkahub/startup/` |
| 小宠物磁贴 | `CompanionQuickTileService`（`AndroidManifest.xml` 已声明 `BIND_QUICK_SETTINGS_TILE`） |

改这些区域时留意：`ConversationEntity` 的 summary 转换必须带出 `lineageId` 与 `folderAssignmentLocked`，否则候选会被当成「未锁、独立家族」；归库只能走 `ChatService.applyAutoFileMove`，不要读整条会话再存回去。

共享云构建目录同一时间只跑一份任务；队列空闲后才能同步源码，提交绑定本次 sourceId。超时查原 job，不换幂等键重投；`build-r21.py` 发现既有 submit 回执会拒绝重投。

## 历史 r11 的验证范围（不替代 r21）

- 正式 Release 构建完成，R8 与 lintVital 通过；版本、包名、原签名、双 ABI、ZIP 完整性与传输 SHA-256 已核对。
- 16 个测试套件、168 项定向单元测试通过（0 失败 / 0 错误 / 0 跳过）；正式发布复用已有结果，没有重跑测试。
- Debug 变体真实编译通过，含两种 Firebase 配置的 preflight；Firebase 校验脚本另有 6 项 fixture 测试。
- 源码与测试/打包快照逐文件核对一致（2326 条文件与链接）。
- **没有做**真机或模拟器界面回归、没有安装到用户手机、没有用真实提供商测缓存命中率或真实模型后台调用。
- 已记录的构建警告：D8 的旧 startup profile 引用了部分不存在的方法；打包与 lintVital 均完成，但**不据此声称启动速度已优化**。

具体结果以 [verification/r11.json](verification/r11.json) 为准；正式包的版本、哈希、签名与实际构建记录在该文件中。

## 回退

升级前备份聊天、设置与工作区。代码用 Git 历史或已验源码包回退；设备数据库要单独处理，不要为了降级先卸载 App 再找数据。

ZIP 迁移只覆盖 `/workspace` 文件区，不含 Rootfs、数据库与整机；旧包没有记录的符号链接与权限无法恢复。跨分支备份对齐只改 staging 副本，不承诺所有分支的业务数据都兼容。

## HTTP 执行后端

实现见 [rk-exec-server.py](rk-exec-server.py)。要求非空 `RK_EXEC_TOKEN`，默认回环监听，`RK_EXEC_ROOT` 与 `RK_EXEC_JOBS` 控制目录；客户端只接受 HTTPS，回环地址例外。

它只接管**本地类型**工作区的 shell/job，不覆盖 SSH；没有远端文件工具，也没有 job-list 接口。令牌具有命令执行权限，还可能进入设置备份，必须保密。服务重启后不会自动接管旧任务。

## 历史与相关文档

- 当前版本：[m2 说明](RELEASE-m2.md) · [m1 说明（历史）](RELEASE-m1.md) · [r21 说明（历史）](RELEASE-r21.md) · [正式包与验收记录](https://github.com/682012/rikkahub/releases/latest)
- 功能计数与源码位置：[FEATURES.md](FEATURES.md)；逐版历史：[CHANGES.md](CHANGES.md)
- r1–r10 的发布说明：`RELEASE-r1.md` … `RELEASE-r10.md`；更早的 fork.38–fork.43 见 `RELEASE-fork38.md` … `RELEASE-fork43.md`
- 专项记录：[草稿持久化](AGENT-DRAFTS.md) · [工具效率](AGENT-EFFICIENCY-r11.md) · [架构](AGENT-ARCHITECTURE.md) · [工作区权限](WORKSPACE-ACCESS.md) · [网页会话](WEB-ACCOUNTS.md) · [历史维护现场](HANDOFF.md)
- 自动发布脚本与工具：[push-github.py](scripts/push-github.py) · [sync-upstream.sh](scripts/sync-upstream.sh)

## 许可

上游版权与 **AGPL-3.0** 保持不变（见 [LICENSE](../LICENSE)）。本分支不改变上游许可，也不代表上游接受这些修改。

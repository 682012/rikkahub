# RikkaHub Fork 交接文档（当前 3.1.1 / fork.37）

> **fork.38 发布续接（2026-10-25）**：当前公开源码已同步 `3.1.1-fork.38 / 3000042`，schema 34；用户说明以 [FEATURES](FEATURES.md) 与 [RELEASE-fork38](RELEASE-fork38.md) 为准。下面保留的 fork.37/更早路径与状态是历史现场，不能套用为当前交付。公开提交沿用原 fork/3.1.1 历史，未强推覆盖。


> 用途：给其他模型/维护者审查当前 APK 与源码。文中“已验证/未验证”按实际执行情况标注，没有实测过的不会写成已验证。
> 最后更新：2026-10-25。第 1 节保留 fork.34 历史基线；当前 fork.37 的修复、验证、产物和未验边界见第 12 节及 AUDIT-20261025.md。

## 1. 产物

| 项 | 值 |
|---|---|
| APK（HB） | `/data/data1/rk3mod/rikkahub-3.1.0-fork.34.apk` |
| APK（手机） | `/sdcard/Download/rikkahub-3.1.0-fork.34.apk` |
| 大小 | 70,088,814 字节（约 67MB） |
| MD5 | `dc96baf1242563697ab27fe0e843b01a` |
| 包名 | `me.rerere.rikkahub.fork` |
| versionName | `3.1.0-fork.34` |
| versionCode | `3000037` |
| 签名 | V2，证书 SHA-256 `cb897a361d49933cc7d033b7ff05b95fad6483c18bb4134b10119b0f2715bf89` |
| 构建类型 | release **未跑 R8**（构建日志无 `minifyReleaseWithR8`，未带 `-PfastBuild` 也会关闭；正式发布要显式确认） |
| 基线 | RikkaHub 官方 `3.1.0`，commit `eeef60b2` |

安装前提：与官方包、mod89、excp 等分支共存；同签名版本可覆盖升级，不同签名只能卸载重装后导入备份。

## 2. 源码、GitHub 与构建

- 构建机：HB（43.248.3.158:20031），源码 `/data/data1/rk3mod/src31`（**tar 快照，没有 .git**）
- 同机还有 `/data/data1/rk3mod/src310`（3.1.0 的中间副本），**fork.25 用的是 src31**
- 旧路径 `/data/data1/rk3mod/src` 已不存在，脚本/文档里看到要改
- GitHub：`https://github.com/682012/rikkahub`，当前 fork 主线为 `fork/3.1.0`
- 分支 `fork/3.1.0`：审计开始时为 fork.34，HEAD `f05740ee8e5125b0c72fa32913b210f95d95325e`，2,381 个受跟踪文件与 HB 一致；本轮 fork.35 修改未推送。
- 分支 `fork/3.0.0`：停在 fork.15（`a64fa9b`，10-19），旧分支保留，不再更新
- 默认分支 `master` 是上游镜像，不用于 fork 工作
- 推送脚本：`/workspace/rikkahub-fork/scripts/push-github.py` 写死 `fork/3.0.0` 和 62 个文件的清单，**已过时**；当前同步方式是按分支整体快照（clone → 覆盖 → commit → push），不要再用旧脚本推
- 环境：JDK 21，Android SDK 37，Gradle 9.6
- 快速构建：`./gradlew :app:assembleRelease -PfastBuild --daemon`（本次 4m8s，320 tasks）
- 正式构建：`./gradlew :app:assembleRelease --daemon`（R8 + lint，约 10–15 分钟）
- `material3/material-color-utilities` 是上游 submodule，构建时必须存在
- `app/google-services.json` 是占位文件，仅用于通过编译
- 签名配置在 `local.properties`（不入库），keystore 在 `/data/data1/rk3mod/keystore/fork.jks`

## 3. 数据层（schema 33）

- `AppDatabase` version 33，identity_hash `6a88f571586222d4365ee0a1d4d610fa`
- 迁移链：25→26→…→30→31→32→33
  - **31→32**（fork.16/17）：官方 3.1 的会话表清理（删 `chat_model_id` 和旧索引）、`memory_file` 表、`usage_record` 统一表
  - **32→33**（fork.23）：legacy 记忆路径补前导 `/`（`_legacy/imported_memories_*.md`）
- `UsageRecordEntity`：fork 自有表与官方 3.1 同名表已合并为统一表；子代理用量走官方 `UsageRecorder`，来源 `SUBAGENT`
- 备份导入对齐 `DatabaseBackup.alignToTarget` 逻辑保留：同版本不同分支（典型 excp schema 31）先对齐表结构再改 hash
  - 触发条件是“版本相同但 identity hash 不同”；版本不同直接 return false
  - 删表只作用于 staging 副本，原备份不动；列类型差异不处理
- 从 2.5.x（schema 25）导入可一路升到 33
- 坑：Termux 自带 sqlite3 未编 `simple` 分词器，读写 `message_fts` 会报 "no such tokenizer: simple"，`quick_check` 也会误报，不要据此判库损坏

## 4. 版本节点（fork.14 → fork.35）

| 版本 | 关键变化 |
|---|---|
| fork.14 | 3.0.0 基线；子代理、工作区 job、远程执行、用量、自动压缩、大会话外置（详见旧版文档） |
| fork.15 | 修复数据持久化、内容丢失、job 生命周期、远程取消、备份完整性 |
| fork.16 | 合并官方 3.1.0：账号登录、官方用量统计、自动做梦、语音继续聆听、压缩保留条数、工作区图片 URI；schema 升 32 |
| fork.17 | 修复 31→32 会话表迁移（删 `chat_model_id`、旧索引） |
| fork.18 | 学习模式提示词改成补充性，不再覆盖用户设定 |
| fork.19 | 还原 fork.18 对 Learning Mode 的改动 |
| fork.20–22 | **没有独立文档记录**；产物只有 HB 上的 APK，改动内容需要从 src31 与后续差异反推 |
| fork.23 | legacy 记忆路径补前导 `/`；schema 32→33 |
| fork.24 | 子代理工具不再枚举全部模型 ID（大账号模型池上千 UUID 会撑爆提示词）；同步调整 MCP/Skills 工具与 `ChatToolFactory` |
| fork.25 | 技能安装校验 `SkillValidator`（name 去空白、长度 ≤64、不以 `.` 开头、禁 `/ \` 与控制字符、description 必填）+ 技能页/详情页；Provider 设置页内置 ChatGPT/Grok 订阅类型，修复备份设置迁移后 provider 被过滤、登录选项消失 |
| fork.26 | **强杀不丢聊天记录**：流式生成按 1.5 秒节流落库（原来只在生成结束/空闲时写）；ON_STOP 与前台服务 `onTaskRemoved` 主动落库；消息节点增量保存（只写变化的节点）；搜索索引写失败不再连带会话保存失败；搜索页默认范围改「全部助手」；空闲落库 5s→2s。搜索侧另有 `ensureSchema`（tokenizer 不符时重建）、启动自检重建、`jieba_query` 出错返回空结果不崩 |
| fork.27 | 压缩请求不再带工具定义（修「模型试图调用工具，而不是编写摘要」报错） |
| fork.28 | 设置页加 rkpro 卡片（后删） |
| fork.29–31 | rkpro ExtPage 反射入口尝试（LSPatch ClassLoader 隔离，失败，放弃） |
| fork.32 | 纯版回退（3000035） |
| fork.33 | 删 rkpro 卡片（3000036） |
| fork.34 | **工作区导出**：设置→高级与长任务→工作区导出，SAF 选位置，zip 打包 `/workspace`，排除 `tmp`/`node_modules`/`.git`/`build`/`.gradle`/`.kotlin`/`cache`/`.venv`/`__pycache__`，带进度（3000037） |
| fork.35 | 修复增量落库、ZIP 导出与工作区选择、子代理重试/长输出、远程零超时和网络输入显示；502+24 项测试通过，3000039，未自动安装/推送 |

`fork/CHANGES.md` 已补齐 27–35，完整审计见 `fork/AUDIT-20261024.md`。

## 5. 功能现状

### 5.1 子代理

- 工具：`subagent_run / wait / result / list / cancel`
- 每次任务独立 AgentRequest，工具集继承父会话（去掉 `subagent_*`）
- 并发：`active` 计数 + `released` 状态流，硬上限 4
- 超时 `withTimeoutOrNull`，超时不重试；主模型永久失败时用 `fallbackModelId` 重试一次
- 结果写 `filesDir/subagents/<id>/result.md` 与 `events.log`
- 运行期间持前台服务；用量写统一 `usage_record`，`source=SUBAGENT`
- 会话检索：`recent_chats / conversation_search / conversation_read`，按 `includeChatHistory` 开关
- 未实现：嵌套子代理、单独预算

### 5.2 工作区

- `WorkspaceTools` 超时来自 `WorkspaceRuntimeSettings`：`commandTimeoutSec` 默认 30，`maxTimeoutSec` 0=不限
- `workspace_job_start/status/kill`：每任务独立 PRoot 进程，不能套用 PRoot 内 setsid 保活的旧说法；元数据在文件区 `.jobs/<id>/`，重启后历史不等于仍持有进程归属。
- 前台服务 `WorkspaceJobForegroundService`（specialUse + partial wakelock）
- 远程执行：`/exec`、`/job/start`、`/job/status`、`/job/kill`，Bearer token；开启后本地不执行

### 5.3 用量

- 写入：官方 `UsageRecorder`（主对话）；子代理来源 `SUBAGENT`
- 读取：聊天页顶栏「用量」→ `UsageSheet`，总量、缓存命中率、按模型拆分
- 只统计写入之后产生的请求，历史不补记

### 5.4 自动压缩

- 触发：`contextTokens >= model.contextLength * thresholdPercent / 100`
- 条件：开关开启、最后节点不是检查点、没有未完成工具调用
- 行为：生成摘要并插检查点，不删历史；失败静默跳过
- 设置：`Settings.autoCompress { enabled, thresholdPercent }`

### 5.5 大会话加载

- fork.14 的“48k 工具正文替换成 2k 预览”是旧行为，不能当成当前全部策略；fork.15 增加旧缓存引用恢复。
- 当前工具输出保护还涉及 `ChatToolFactory`：只有能读取本地外置文件时才使用文件承载全文，fork.35 补齐子代理这条路径。
- 消息分页仍未实现；JVM 回归没有证明超大会话一定不 OOM。不要把旧缓存清理说明当成真机性能验收。

## 6. 已知风险与未验证

**流程风险：**

1. **旧分支 `fork/3.0.0` 停在 fork.15**，不再作为当前主线，别在旧分支上继续工作
2. **HB 快照的 `fork/CHANGES.md` 曾落后 GitHub 版**（缺 20–22），2026-10-21 已拉回同步；以后改 CHANGES 先从 GitHub 拉最新
3. 源码快照没有 git，无法 `git diff` 核对历史改动

**设计限制：**

1. 远程执行服务端没有并发限制、没有目录隔离，所有任务共享 `RK_EXEC_ROOT`
2. 远程 token 明文存在 `settings.json`，会随备份导出
3. 子代理结果文件、`msg_cache` 没有 UI 清理入口，只靠定时策略
4. `SubagentTask.steps` 存的是工具调用次数，不是模型步数
5. 自动压缩依赖模型 `contextLength` 准确，默认 1,000,000 会让阈值形同虚设
6. 消息分页未实现，超大会话靠外置缓解
7. `alignToTarget` 对同版本但结构差异极大的库会直接抛错（有意为之，避免带残缺结构启动）
8. **进程被强杀时，流式生成最多丢最近 1.5 秒内容**（fork.26 起）；落库是节流的，做不到逐 token 持久化
9. 前台服务 `onTaskRemoved` 只能尽力而为：划掉任务后系统可能立刻回收进程，落库不保证完成

**真机未验证（沿用 fork.14 的清单，fork.16 重构后需重测）：**

1. 本地 `workspace_job_start` 全链路（启动 / pid / status / kill / App 重启后查询）
2. 前台服务锁屏后的存活时长，Android 15 `onTimeout` 无兜底
3. 自动压缩在真实长会话上的触发与摘要质量
4. 子代理并发 2 时的内存峰值（512MB 堆）
5. 备份对齐遇到差异更大的库（缺主键、FTS 结构不同、触发器）

**修过但值得复查：**

1. `UsageRecordDAO` 的 Koin 绑定（曾导致「用量」闪退）
2. `WAKE_LOCK` 权限（曾导致 job 服务启动即崩）
3. `UsageSheet` 滚动容器（曾用无限高度 LazyColumn）
4. 远程 `exitCode: null` 解析
5. 子代理并发等待（曾可能永久挂起）
6. 本地 job 的 shell 优先级

## 7. 构建与发布

```bash
# HB 上。wrapper 会去下载 gradle-9.6.0 并超时，直接用本地发行版
cd /data/data1/rk3mod/src31
/data/data1/tools/gradle-9.6.0/bin/gradle :app:assembleRelease -PfastBuild --daemon   # 快速验证
# 产物（ABI 拆分，手机用 arm64）：app/build/outputs/apk/release/app-arm64-v8a-release.apk

# 发布到手机（示例）
scp app/build/outputs/apk/release/app-arm64-v8a-release.apk \
  <phone>:/sdcard/Download/rikkahub-3.1.0-fork.26.apk
```

改版本号：修改 `app/build.gradle.kts`；versionCode 必须独立递增。本轮 fork.35 使用 3000039，避开实验包已占用的 3000038。

推 GitHub（整体快照，2026-10-21 起用这个流程）：

1. 在 US 上 `git clone --depth 1 --branch fork/3.1.0`（HB 直连 GitHub 会 TLS 断）
2. `rsync -a --checksum` 从 HB `src31/` 同步到 clone（排除 `.git`、`build`、`.gradle`、`.kotlin`、`node_modules`、`local.properties`、`*.jks`、`*.apk`）
3. `git add -A`，看清楚变更列表再 commit，`git push` 到 `fork/3.1.0`
4. 推完用 raw 加 `?v=<sha>` 核对，别被 CDN 缓存骗了

## 8. 相关文档

- `fork/CHANGES.md`：与原版的功能差异（已补到 fork.35）
- `fork/AUDIT-20261024.md`：本轮代码审计、真实测试、未验范围和回退说明
- `fork/README.md`：构建、签名、发布、回退
- `fork/WORKFLOW.md`：可复用的私有 fork 维护流程
- `DISCLAIMER.md` / `NOTICE.md`：个人改版与 AGPL 修改声明
- 凭据位置：`/workspace/.secrets/rikkahub-fork/`（keystore、口令、GitHub PAT、远程 token）

## 9. 2026-10-23 状态与待办

### 已完成

- fork.26–34 全部改动（见第 4 节版本节点）
- rkpro 嵌入方案走通一半：`rkpro_signed6.apk`（`xposedscope` 加 fork、`isSupported`/`adapted` 强制 true、`clickable` `params[5]`→`[6]`），LSPatch 1.2 嵌入 fork.34 出包 3000038，但 **UI 入口仍不可用**，已放弃内置
- 纯版 fork.34 是当前主线（3000037），rkpro 只保留脚本层（`plugins/`）
- 清理：HB 旧 APK、工作区 tmp APK 副本、rkpro 打包中间产物

### 已知问题

1. rkpro UI 入口在 LSPatch 下不可用：
   - 长按加号 `内层 clickable 挂钩数=0`（改了 `params[6]` 仍 0，`hookPlusButton` 还有别的条件对不上）
   - 设置页 ExtPage 反射 `ClassNotFoundException`（模块 ClassLoader 隔离）
   - 结论：不再修；脚本用 `plugins/` 文件方式
2. 压缩修复（fork.27）未真机验证
3. 工作区导出（fork.34）未真机验证
4. 此条已于 2026-10-24 核实过时：fork.34 在 23 日已同步 GitHub；本轮新增 fork.35 修复尚未推送。

### 下次继续的入口

- 源码：HB `/data/data1/rk3mod/src31`（tar 快照，无 .git）
- 构建：`/data/data1/tools/gradle-9.6.0/bin/gradle :app:assembleRelease -PfastBuild --daemon`（wrapper 下载会超时）
- 产物：`app/build/outputs/apk/release/app-arm64-v8a-release.apk`
- keystore：`/data/data1/rk3mod/keystore/fork.jks`（签名参数查受控 local.properties 与凭据目录，不在交接文档记明文口令）
- rkpro 工具：`/data/data1/tools/rkpro/`（`apktool.jar`、`lspatch.jar`、`rkpro_signed6.apk`、`rkpro_src/`）
- 重装指南：`/workspace/RESTORE-GUIDE-20261023.md`、手机 `/sdcard/Documents/rik-restore-20261023/`、知识库搜「工作区重装」

### 待确认

- 2026-10-24 dumpsys 已确认手机为纯版 fork.34（3000037）；本轮没有覆盖安装。
- 工作区导出的实际耗时和产物大小
- 压缩报错是否只由工具定义引起


## 10. 2026-10-24 审计与 fork.35 修复

- 目标：查新增功能是否接通、修高置信 bug；四路初审、两路复核，主线程筛选修订并统一验收。
- 基线同步属实：GitHub fork.34 的 2,381 个文件与 HB 一致，不是旧 APK 落后于源码。
- 已修：增量落库前全删；ZIP 链接/剪枝/取消；导出选错工作区与假成功；子代理重试和长输出；远程 HTTP 0 超时；重试输入值不同步。
- 已验证：清理旧测试输出后 App 502 + Workspace 24 = 526 项全部通过、0 跳过；assembleRelease fastBuild 成功；新旧 V2 签名证书一致。
- 新包：`3.1.0-fork.35 / 3000039`，HB `/data/data1/rk3mod/rikkahub-3.1.0-fork.35.apk`，70,095,106 字节。
- APK SHA-256：`c28a685763f889834ca603493136e31de2f9f02a82fd4660fd334dc9c818df23`。
- 手机安装包已传到 `/sdcard/Download/rikkahub-3.1.0-fork.35.apk` 并 SHA-256 核对一致；本轮未安装、未推 GitHub。fastBuild 没有跑 R8，真机行为不能由单元测试代替。
- 明确未实现/未补：消息分页、嵌套子代理、独立费用预算、远程任务设置页完整列表。锁屏保活、真实长会话压缩、SAF UI、多分支库迁移仍需真机验收。
- 没有采纳收窄历史检索、剔除辅助费用、新增预算硬限制、全局前台服务计数重构等策略性或有竞态的建议。
- 资料：本目录 `AUDIT-20261024.md`；工作区 `/workspace/rikkahub-fork/AUDIT-20261024.md`；HB `/data/data1/rk3mod/audit-20261024/`。
- 完整源码/签名备份：`audit-20261024/baseline/fork34-source-and-signing-v2.tar.gz`，2,384 文件逐个 SHA-256 校验通过。带凭据，不要外传；不带 -v2 的失败包不可恢复。
- 回退先停构建，按最终文件清单删除本轮新增项，再恢复上述归档；不通过卸载手机 App 回退，避免丢数据。

## 11. 2026-10-25 fork.36：合并官方 3.1.1

- 基线：官方 `3.1.1`（commit `ad2c4430`，49 个提交），上一版 `3.1.0-fork.35` / `3000039`。
- 方式：完整 git 三方合并。工作区仓库分支 `fork/3.1.1`，合并提交 `0ccc6b27`（fork.35 快照 `52861b90` + 官方 3.1.1）。15 个冲突文件由四路并行子代理分组处理（FTS、workspace、chat/DI、资源与构建），主线程负责数据库迁移与最终验收。
- 官方 3.1.1 主要变更已接入：
  - SSH 远程工作区（`WorkspaceLayout` / `WorkspaceCapabilities` / `SshConnectionPool` / `SshWorkspaceBackend`），设置页与编辑页入口完整。
  - 后台生成悬浮窗与快速回复、overlay 权限入口。
  - 德语 `values-de`。
  - 记忆整理模型（`Settings.memoryModelId` + 设置页）。
  - 工具调用 `startedAt/finishedAt` 与耗时显示。
  - models.dev 模型能力识别、新图片裁剪、联网搜索入口移到加号菜单、压缩上下文步进器、收藏模型排序等。
  - 安装包体积：R8 arm64 16 MB（fork.35 为 70 MB）。
- fork 功能保留：子代理、工作区 job、远程 HTTP 执行、流式节流落库、工作区导出、技能校验、自动压缩、用量统计。
- 数据库：`version = 34`，`identityHash 7ecc686adf1237a8596022247f9624b8`；在 fork 33 上补 `workspaces.type` / `workspaces.ssh_config`，`Migration_33_34` 幂等加列；`30→32`、`31→32`、`32→33` 迁移链保留。`33.json` 仍是 fork 结构，官方更新的 `32.json` 没有覆盖 fork 版本。
- 搜索：采用官方增量 FTS（无 jieba、中文单字 NEAR、`message_fts_rowid` 映射），保留 fork 的 `ensureSchema` / `recreateSchema` / `count` 与查询容错。
- 验证：
  - `:app:testGithubDebugUnitTest` + `:workspace:testDebugUnitTest`：85 个 suite、591 项，0 失败 / 0 错误 / 0 跳过。
  - `:app:assembleGithubRelease -PfastBuild`：BUILD SUCCESSFUL（38m39s，首次下载依赖）。
  - `:app:assembleGithubRelease`（R8）：BUILD SUCCESSFUL（6m13s）。
  - APK：`me.rerere.rikkahub.fork`，`3.1.1-fork.36` / `3000040`，arm64-v8a，16,328,016 字节，SHA-256 `5aefc6e3caf7492ebdc0175e328123a927b3f8b1ac32abd525f5345660fd5c6c`，V2 证书 SHA-256 `cb897a361d49933cc7d033b7ff05b95fad6483c18bb4134b10119b0f2715bf89`，与 fork.34/35 一致。
- 产物：HB `/data/data1/rk3mod/rikkahub-3.1.1-fork.36.apk`；手机 `/sdcard/Download/rikkahub-3.1.1-fork.36.apk`（SHA-256 已核对一致）；GitHub `fork/3.1.1`。
- 构建环境（HB，本次补齐）：SDK platform 37.2、build-tools 36.0.0、NDK 28.2.13676358、CMake 3.22.1。Gradle 堆调到 `-Xmx6g`：8g 时 R8 阶段 daemon 被 OOM 杀掉一次。依赖下载在构建机临时走阿里云镜像，仓库配置未改。
- 未验证：真机安装与启动、33→34 数据库真实升级、SSH 工作区真实连接、悬浮窗 overlay 权限流程、SAF 导出、锁屏 job 存活；R8 包的运行时行为未覆盖。
- 回退：同签名可回 `fork.35`（3000039）；数据库升到 34 后不要降级安装 33，Android 不一定允许。

## 12. 2026-10-25 fork.37：合并后代码检修

- 审查基线：`fork/3.1.1` / `05c4952b`。本节记录上轮审查结束状态：当时未提交、未推 GitHub、未安装到手机、未改生产数据库或部署远程执行服务；后续源码发布与文档整理见第 13 节。
- 版本：`3.1.1-fork.37 / 3000041`；数据库仍是 schema 34，无新 schema 版本。
- 数据层：官方 3.1.1 版本 32 的用量表在进入 33 时归一化；记忆路径冲突保留较新规范内容并无损归档另一份；同版本备份对齐不重放未受管 FTS 索引；旧记忆原文归档用绝对路径。
- 工作区：全局 HTTP 后端不再覆盖 SSH；就绪状态、工具、job、描述与实际后端统一；本地 ZIP 导出不列 SSH；只有本机 Rootfs 真能读取外置文件时才截断长工具输出；SSH 的零超时按不限处理且仍能中断。
- 持久化：超大行不再整页跳过，分页失败时整体按 SQL code point 分片回退；保存快照与子代理会话尾部读取同样保护，尾部查询只读请求的最近节点。历史内联媒体转换失败不会阻断 metadata 保存/删除，原始数据保留。
- 远程诊断：日志按字节或按行裁剪都标截断；HTTP 错误保留有界、脱敏的原因，错误预览最多等 2 秒，成功响应 1 MiB 上限与正常请求 0=不限语义保留。CI task 与 APK 路径均切到 GitHub flavor，缺产物明确失败，本轮未触发云端运行。
- 通知：Live Update 按会话 UUID tag 隔离，工作区 job 独立 ID 2005，兼容 Android 13 以下通知权限；未重构全局前台服务计数，也未增加子代理审批豁免。
- 真实测试：最终四模块均用 `--rerun` 执行，App 618 + Workspace 55 + AI 271 + Common 6 = 950 项、136 个 suite，0 失败/错误/跳过；包括尾部大行四项、无限 HTTP 请求的慢错误正文两项与半截 JSON 错误一项。Python 11 项与 SQLite 迁移/对齐模拟 38 项通过；200 节点及超过 2 MiB emoji 行的 SQL 分片 SHA 核验通过。验收日志 `stage4-tests.log` / `stage4-test-counts.json`。
- 正式构建：`:app:assembleGithubRelease :app:assembleGithubDebugAndroidTest`，补漏后的最终生产源码重新跑 R8 + lintVital，BUILD SUCCESSFUL（8m54s，退出码 0，`stage3-build.log`）；仪器测试 APK 仅编译/打包，未运行。第一轮 16m39s 的旧包已单独备份。
- 产物：HB `/data/data1/rk3mod/rikkahub-3.1.1-fork.37.apk`；工作区 `/workspace/rk311/apk/rikkahub-3.1.1-fork.37.apk`；手机 `/sdcard/Download/rikkahub-3.1.1-fork.37.apk`，三份 SHA 一致；未自动安装。
- APK：16,340,840 字节，arm64-v8a，V2 证书与 fork.36 一致；SHA-256 `b243e2788628112ad2be660957a56a1b74961e67ba3d523a3968496e4652c919`。
- 尚未真机验证：安装启动、真实数据库升级、SSH 真机连接、通知与权限显示、SAF、锁屏保活。Android 测试 APK 的构建不等于运行了仪器测试。
- 保留限制：HTTP 服务重启后的旧任务仍可能脱管；没有用可能误杀的 PID 领养掩盖这一点。超大会话仍完整加载，不能保证任意大小都不 OOM。
- 备份：主线程核验的工作区 Git 基线 `/workspace/reviews/rikkahub-05c4952b-20261025/baseline.tar` 及 `baseline.tar.sha256`；此前压缩副本 `/workspace/reviews/rk311-20261025/baseline-05c4952b.tar.gz` 仍保留。HB `/data/data1/rk3mod/audit-20261025/host-baseline-src311.tar.gz` 含本地配置，勿外传。回退代码前先停构建、按修改清单恢复，不能用卸载 App 代替数据备份。

## 13. 2026-10-25 源码同步与 GitHub 说明整理

- 本轮将第 12 节封存的 fork.37 修复提交到 `fork/3.1.1`，未增加应用功能、未重编 APK、未安装或修改手机数据。
- 新增 `FEATURES.md`：对比固定官方 3.1.1/ad2c4430，按独立用途计 6 组新增能力；8 个新工具端点不拆成 8 个功能，官方已有历史检索不重复计数。
- 重写仓库首页与语言导航，区分新增、增强、官方共有和未实现项；移除过期 3.0.0 当前对照表，更新 NOTICE 与维护文档。
- 明确官方已支持 SSH、请求记录/全局用量、MCP/Skills、历史检索等；本分支没有 ZIP 导入与 30–60 秒重试间隔设置，不用其他会话的计划替代已审计代码。
- GitHub 首页使用维护分支，`master` 上游镜像不改写；About 与主页链接指向本分支差异说明。为避免切换默认分支后顺带自动发 nightly，维护分支 CI 仅保留手动触发；本轮不发 APK、不启动云端构建。发布结果以远端提交核验和知识库同步记录为准。
- 本轮仅改公开说明，不扩大 950 项 JVM、R8、真机未验的结论；APK 对应的生产源码 SHA 清单保持一致。

# RikkaHub Fork 交接文档（fork.26）

> 用途：给其他模型/维护者审查当前 APK 与源码。文中“已验证/未验证”按实际执行情况标注，没有实测过的不会写成已验证。
> 最后更新：2026-10-22。上一版是 fork.25（备份见 git 历史）。

## 1. 产物

| 项 | 值 |
|---|---|
| APK（HB） | `/data/data1/rk3mod/rikkahub-3.1.0-fork.26.apk` |
| APK（手机） | `/sdcard/Download/rikkahub-3.1.0-fork.26.apk` |
| 大小 | 70,080,646 字节（约 67MB） |
| MD5 | `f430d819895ba5f9841218d400defbf1` |
| 包名 | `me.rerere.rikkahub.fork` |
| versionName | `3.1.0-fork.26` |
| versionCode | `3000026` |
| 签名 | V2，证书 SHA-256 `cb897a361d49933cc7d033b7ff05b95fad6483c18bb4134b10119b0f2715bf89` |
| 构建类型 | release **未跑 R8**（构建日志无 `minifyReleaseWithR8`，未带 `-PfastBuild` 也会关闭；正式发布要显式确认） |
| 基线 | RikkaHub 官方 `3.1.0`，commit `eeef60b2` |

安装前提：与官方包、mod89、excp 等分支共存；同签名版本可覆盖升级，不同签名只能卸载重装后导入备份。

## 2. 源码、GitHub 与构建

- 构建机：HB（43.248.3.158:20031），源码 `/data/data1/rk3mod/src31`（**tar 快照，没有 .git**）
- 同机还有 `/data/data1/rk3mod/src310`（3.1.0 的中间副本），**fork.25 用的是 src31**
- 旧路径 `/data/data1/rk3mod/src` 已不存在，脚本/文档里看到要改
- GitHub：`https://github.com/682012/rikkahub`，分支 `fork/3.0.0`
- 分支 `fork/3.1.0`：**已同步到 fork.25**（代码 `cd5d365`，10-21 05:06；文档提交 `df5b057`）
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

## 4. 版本节点（fork.14 → fork.25）

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

`fork/CHANGES.md` 已补到 fork.25（2026-10-21 同步时补上 23–25，20–22 原本就在 GitHub 版）。

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
- `workspace_job_start/status/kill`：本地 setsid/nohup + pid 文件；日志尾部 256KB；元数据在 `<workspace>/.jobs/<id>/`
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

- 只处理工具输出与媒体，正文不截断
- 工具输出 > 48k 字符 → `filesDir/msg_cache/<md5>.txt`，消息保留前 2k + `[full text: 路径]`
- base64 媒体 > 512k 字符 → 解码写文件，换 `file://`
- 缓存按 MD5 去重，7 天 / 300MB 清理

## 6. 已知风险与未验证

**流程风险：**

1. **旧分支 `fork/3.0.0` 停在 fork.15**，和 `fork/3.1.0` 差 10 个版本，别在旧分支上继续工作
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

改版本号：`app/build.gradle.kts` 的 `versionCode = 30000NN`、`versionName = "3.1.0-fork.NN"`。

推 GitHub（整体快照，2026-10-21 起用这个流程）：

1. 在 US 上 `git clone --depth 1 --branch fork/3.1.0`（HB 直连 GitHub 会 TLS 断）
2. `rsync -a --checksum` 从 HB `src31/` 同步到 clone（排除 `.git`、`build`、`.gradle`、`.kotlin`、`node_modules`、`local.properties`、`*.jks`、`*.apk`）
3. `git add -A`，看清楚变更列表再 commit，`git push` 到 `fork/3.1.0`
4. 推完用 raw 加 `?v=<sha>` 核对，别被 CDN 缓存骗了

## 8. 相关文档

- `fork/CHANGES.md`：与原版的功能差异（已到 fork.25）
- `fork/README.md`：构建、签名、发布、回退
- `fork/WORKFLOW.md`：可复用的私有 fork 维护流程
- `DISCLAIMER.md` / `NOTICE.md`：个人改版与 AGPL 修改声明
- 凭据位置：`/workspace/.secrets/rikkahub-fork/`（keystore、口令、GitHub PAT、远程 token）

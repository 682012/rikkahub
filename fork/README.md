> **个人改版 / Personal Fork**：本仓库是 RikkaHub 的非官方个人修改版，仅供自用，不提供 APK、安装教程或技术支持，与原作者无关。详见 [DISCLAIMER.md](../DISCLAIMER.md) 与 [NOTICE.md](../NOTICE.md)。

# RikkaHub Fork（基于 3.1.0）

个人维护分支。在官方 3.1.0 上补：子代理、工作区长任务与远程执行、单会话用量、自动上下文压缩。

## 与上游的关系

- 基线：`rikkahub/rikkahub` 的 `3.1.0`（fork.16 起）
- 版本号：`3.1.0-fork.N`；versionCode 独立递增，fork.35 为 `3000039`，不要再按 N 直接计算
- 包名：`me.rerere.rikkahub.fork`（可与官方、其他分支共存）
- 签名固定，后续版本可直接覆盖升级

## 功能

与官方版本的功能差异见 [CHANGES.md](CHANGES.md)。2026-10-24 审计、修复、验证边界和回退见 [AUDIT-20261024.md](AUDIT-20261024.md)。

- 子代理：`subagent_run / wait / result / list / cancel`，独立上下文、并发上限、fallback 模型、结果落盘
- 子代理可读取其他会话：`recent_chats / conversation_search / conversation_read`
- 工作区：命令超时可配（0 = 不限），`workspace_job_start/status/kill` 后台任务，前台服务保活
- 远程执行：`POST /exec` 与 `/job/*`，可把长任务交给服务器
- 用量：`usage_record` 表，聊天页顶栏“用量”查看单会话明细
- 自动压缩：上下文达到模型 `contextLength` 的阈值时插入摘要检查点，不删历史
- 大会话 OOM：大块内容外置到 `filesDir/msg_cache`，图片转 `file://` 仍正常显示

## 构建

需要 JDK 21、Android SDK 37.2、Gradle 9.6。

```bash
# 快速迭代（跳过 R8，约 5 分钟）
./gradlew :app:assembleGithubRelease -PfastBuild --daemon

# 正式包（R8）
./gradlew :app:assembleGithubRelease --daemon
```

3.1.1 起官方引入 `distribution` flavor（github / play），构建任务名带 `Github`；产物在 `app/build/outputs/apk/github/release/`。R8 需要约 6 GB 堆，`gradle.properties` 已把 `org.gradle.jvmargs` 设为 `-Xmx6g`，8 GB 在 19 GB 内存的构建机上会被 OOM 杀掉。

签名配置在 `local.properties`（不入库）：

```
storeFile=/path/to/fork.jks
storePassword=***
keyAlias=fork
keyPassword=***
```

`material3/material-color-utilities` 是上游的 git submodule，本仓库以普通目录提交，克隆后无需再初始化。

`app/google-services.json` 是占位配置，仅用于通过编译；不要填真实 Firebase 凭据。

## 远程执行服务

`fork/rk-exec-server.py`，部署在个人服务器：

```bash
python3 rk-exec-server.py   # 监听 127.0.0.1:18080
```

环境变量：

- `RK_EXEC_TOKEN`：Bearer token
- `RK_EXEC_ROOT`：执行根目录
- `RK_EXEC_JOBS`：后台任务日志目录

服务端强制要求非空 `RK_EXEC_TOKEN`，否则拒绝启动；客户端只接受 HTTPS（本机回环可用 HTTP）。
公网入口用 nginx 带 TLS 反代，不要直接暴露。

远程模式只提供 `workspace_shell` 与 `workspace_job_*`；本地文件工具不会读取或写入远程目录。

## 升级上游

```bash
FORK_ROOT=/path/to/this/repo ./scripts/sync-upstream.sh 3.0.1
```

脚本会下载新 tag，逐个对比侵入点文件，把差异输出到 `/tmp/rk-upstream-<tag>/patches`。合并后先跑 `-PfastBuild` 验证。

侵入点集中在：`Settings.kt`、`ChatToolFactory.kt`、`ChatRequestFactory.kt`、`WorkspaceTools.kt`、`WorkspaceRepository.kt`、`ConversationRepository.kt`、`ChatService.kt`、`AppDatabase.kt`、`RouteActivity.kt`、`SettingPage.kt`、`AndroidManifest.xml`、`app/build.gradle.kts`。

## 回退

- 代码：`git checkout <上一个 tag>`，重新构建
- 数据：安装前导出备份；schema 升级不可逆，跨版本回退需要恢复备份
- 签名：keystore 单独备份，丢失后无法覆盖升级

## 已知限制

- 用量从写入该表之后开始统计，历史消息不补记
- 自动压缩依赖模型设置里的 `contextLength` 准确
- `SubagentTask.steps` 实际是工具调用次数
- 消息分页尚未实现，超大会话靠外置缓解

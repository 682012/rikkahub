# 个人分支：构建与维护

本目录记录 RikkaHub 非官方个人分支。用户可见功能与官方差异统一放在 [仓库首页](../README.md) 和 [完整差异表](FEATURES.md)，本页只维护构建与回退流程。

## 当前基线

| 项目 | 值 |
|---|---|
| 官方基线 | `3.1.1` / `ad2c4430` |
| 维护分支 | `fork/3.1.1` |
| 当前版本 | `3.1.1-fork.37` / `3000041` |
| 包名 | `me.rerere.rikkahub.fork` |
| 数据库 | schema 34；不是官方 schema 编号 |
| 已验证产物 | R8 release，arm64-v8a，16,340,840 字节 |
| APK SHA-256 | `b243e2788628112ad2be660957a56a1b74961e67ba3d523a3968496e4652c919` |

`master` 保留上游镜像，`fork/3.1.0`、`fork/3.0.0` 为历史分支。不要把旧分支、旧打包产物或构建后追加的源码修改当作上述已验证包。

此仓库仅记录个人修改，不提供公开 APK 分发或技术支持。上游版权保留，详见 [声明](../DISCLAIMER.md)、[修改记录](../NOTICE.md) 与 [许可证](../LICENSE)。

## 构建

环境：JDK 21、Android SDK 37.2、Gradle 9.6；本次构建使用 build-tools 36.0.0、NDK 28.2.13676358、CMake 3.22.1。

```bash
# 快速编译，不进行 R8，不能当作正式优化包验证
./gradlew :app:assembleGithubRelease -PfastBuild --daemon

# 正式 GitHub flavor：R8 + release lintVital
./gradlew :app:assembleGithubRelease --daemon

# JVM 回归，--rerun 表示真实执行测试任务
./gradlew :app:testGithubDebugUnitTest --rerun :workspace:testDebugUnitTest --rerun \
  :ai:testDebugUnitTest --rerun :common:testDebugUnitTest --rerun

# 仅编译/打包 Android 仪器测试，不能声称已在设备执行
./gradlew :app:assembleGithubDebugAndroidTest
```

APK 目录：`app/build/outputs/apk/github/release/`。官方 3.1.1 引入 `github/play` flavor，旧 `apk/release/*.apk` 路径不再适用；CI 构建和上传均已指定 GitHub flavor。维护分支工作流仅保留手动触发，避免切换首页后自动发布 nightly；本轮不运行工作流或上传 APK。历史 nightly 不代表当前源码已发布的安装包。

`gradle.properties` 配置约 6 GiB Gradle 堆；实际峰值还包括 Kotlin、R8、原生构建和操作系统，不能按堆大小推算整机内存。不要并发启动多份 release 构建。

`material3/material-color-utilities` 在本分支作为普通目录提交，无需再初始化该目录的 submodule。Google Services 使用本机占位配置，不能提交真实服务凭据。

## 签名与私密配置

签名参数在不入库的 `local.properties` 中：

```properties
storeFile=/private/path/fork.jks
storePassword=<local-secret>
keyAlias=fork
keyPassword=<local-secret>
```

保持同一签名才能覆盖升级本分支。不要把 keystore、口令、API key、远程 token、真实聊天数据库或手机备份提交进仓库。签名不同或包名不同不能覆盖官方版，源码归档也不能代替数据备份。

## HTTP 执行后端

实现位于 `rk-exec-server.py`，服务端要求非空 `RK_EXEC_TOKEN`，默认监听回环；执行根目录与任务日志目录由 `RK_EXEC_ROOT`、`RK_EXEC_JOBS` 指定。客户端要求 HTTPS，回环地址例外。

HTTP 只接管本地类型工作区的 shell/job，不接管 SSH 工作区，也不提供远端文件工具。令牌代表远端命令执行权限，并可能包含在设置备份里；不要分享服务凭据或备份。

## 合并上游与版本记录

- 当前使用 Git 三方合并上游 tag，保留官方基线提交与 fork 历史。旧 `fork/scripts/sync-upstream.sh` 只是按固定文件清单导出差异的辅助脚本，不能替代全仓合并审查。
- 版本号和 `versionCode` 独立递增；不能再套用早期 `3000000 + fork.N` 公式。
- 当前差异见 [FEATURES.md](FEATURES.md)，按版本变化见 [CHANGES.md](CHANGES.md)。
- [HANDOFF.md](HANDOFF.md) 记录历史维护现场；不是用户说明，旧章节中的路径、版本与状态仅代表当时情况。
- [AUDIT-20261025.md](AUDIT-20261025.md) 记录 fork.37 的 950 项 JVM 回归、R8 构建和未真机验证范围。后续只改文档不会扩大真机验证结论。

## 回退

先备份聊天、设置和工作区，再升级。源码用 Git 历史或已核验归档回退；已经变化的手机数据库要单独处理，不能靠降级安装解决，也不能先卸载 App 再找数据。

跨分支结构对齐只修改 staging 副本；不认识的表可能被移除，不能承诺任意分支的业务数据全量兼容。ZIP 导出只含文件区，不含 Rootfs/权限/链接；此版没有 ZIP 导入。

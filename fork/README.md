# 个人分支：构建与维护

当前预发布 **3.1.1-r7 / 3000054**，见 [r7验收](RELEASE-r7.md)、[互动角色与预览](COMPANION-r7.md) 和 [verification/r7.json](verification/r7.json)。下方 r6 与 stable r3 是历史基线，不代表本轮测试。

前一实验代码 **3.1.1-r6 / 3000053** 合并已发布 r5 的全部统计修复与工作区访问权限分支，另增加[可切换布局与沉浸壁纸](APPEARANCE-r6.md)、[主动授权与挂载](WORKSPACE-ACCESS.md)。稳定 latest 保持 r3，r4/r5 不撤回。验收只以 [r6 发布说明](RELEASE-r6.md) 与 [verification/r6.json](verification/r6.json) 为准；r6 提供 App / 用户授权 Shizuku / 已有 su Root 入口，Shizuku 正向与 App su Root 正向本轮未完成真机验证，不声称已通过，PRoot 不是 Root。下面保留历史基线。

r4基线代码为**r4 /3000051**，APK 19,118,258字节，SHA256 `17652736e2900f93fa41b1ecc780625b1fc6ec02d478904b191fcaba1965026f`，完整[R4验收](RELEASE-r4.md)。发布在实验分支和独立tag，prerelease不改变r3稳定latest。下面保留稳定r3维护基线。

用户可见功能统一维护在 [仓库首页](../README.md) 与 [官方差异表](FEATURES.md)。本页记录构建、签名与回退；本轮 r6 说明见 [r6 发布说明](RELEASE-r6.md) 与 [verification/r6.json](verification/r6.json)，稳定基线说明见 [r3 更新说明](RELEASE-r3.md)。

## 稳定基线维护对象（r3 stable latest）

下表是稳定 latest 的 r3 基线；`3.1.1-r6 / 3000053` 是 `experiment/r6-immersive` 上的实验预发布，不改动本表。

| 项目 | 值 |
|---|---|
| 官方基线 | `3.1.1` / `ad2c4430`；跟随到 master `5ad19405` |
| 维护分支 | `fork/3.1.1`（稳定基线分支） |
| 稳定版本（latest） | `3.1.1-r3` / `3000050` |
| 包名 | `me.rerere.rikkahub.fork` |
| 数据库 | schema 34；与 fork.37 相同，不与官方编号混用 |
| r3 已验发布构建 | R8 release，arm64-v8a + x86_64 通用包，19,034,218 字节，HB验证 r3-hb-20261102 |
| r3 构建 SHA-256 | `c19141b32330fbbe0ad349562097157d0b6e314451da976e48d589d5a59e38ea` |

`master` 是上游镜像，旧 fork 分支是历史版本。每个正式版的已验原包保存在对应 Release（[稳定 latest](https://github.com/682012/rikkahub/releases/latest) 为 3.1.1-r3；r6 实验预发布不替换它），附 APK 与 SHA256SUMS；旧 nightly 不代表当前已验安装包。fork.40 起由 GitHub Actions `Fork Verify` 构建签名；旧包保留，不触发旧 nightly，也不替换其资产。r3 的包与校验值由本次验收产物生成；名称和图标与上游最新版一致。

## 重复下载、复用与后续维护

- 直接使用：下载 Release APK 即可；固定版本链接长期用于取回同一份包，[最新正式版](https://github.com/682012/rikkahub/releases/latest) 用于找后续版本。
- 继续开发：从 Release 对应 tag 获取完整公开源码；保留上游许可证、Material 源码和测试。签名与本机配置不入库，干净克隆仍需准备下列构建环境和配置。
- 后续发布：沿用构建和测试流程，核对版本、签名、APK SHA-256，再以独立版本 tag 归档 APK 与校验文件；不复用旧版本号、不覆盖旧资产。`Fork Verify` 只在推送 `ci/**` 分支或手动触发时运行（签名构建并核对证书、JVM 测试、API 34 模拟器测试），不自动发布 Release；旧 nightly 仍只手动触发。
- 工作区复用：ZIP 可反复导入为独立文件区；不覆盖旧工作区，不含 Rootfs、聊天数据库或整机环境。App 数据与签名密钥分别备份。

## 构建与测试

JDK 21、Android SDK 37.2、Gradle 9.6；NDK 28.2.13676358、CMake 3.22.1；Web UI 需要 Node.js 22、pnpm 11，先在 `web-ui/` 运行 `pnpm install --frozen-lockfile`。Google Services 配置和签名放在本机，不提交真实凭据。

```bash
# 快速编译：不进行 R8，不能算正式优化包
./gradlew :app:assembleGithubRelease -PfastBuild --daemon

# 正式构建：R8 + release lintVital
./gradlew :app:assembleGithubRelease --daemon --max-workers=2

# 全模块 JVM，测试任务真实重跑
./gradlew testDebugUnitTest --rerun :app:testGithubDebugUnitTest --rerun

# 构建 Android 测试；只有执行到设备上才算仪器测试通过
./gradlew :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest
./gradlew :app:connectedGithubDebugAndroidTest
```

APK 目录为 `app/build/outputs/apk/github/release/`。3.1.1 有 github/play flavor，不能再用旧 `apk/release/` 路径。正式构建需要较多内存：Gradle 配置 6 GiB 堆，另有 Kotlin/R8/原生编译；不要在同一目录并发多份 release 构建。

`material3/material-color-utilities` 已作为**普通源码目录**提交，与已验构建内容一致；不再保留空 gitlink 或要求额外初始化子模块。上游许可证在该目录中保留。

## 签名与私密配置

`app/google-services.json` 需要匹配包名 `me.rerere.rikkahub.fork`；运行 debug/仪器测试时，另在 `app/src/debug/google-services.json` 配置 `me.rerere.rikkahub.fork.debug`。源码未附真实 Google 服务配置，不能把干净克隆等同于免配置构建。维护者同签名升级所用 keystore 也不公开；自行签名的包不能直接覆盖维护者签名的安装包。

手动 CI 使用 `KEY_BASE64`、`SIGNING_CONFIG`、`GOOGLE_SERVICES_JSON` 三项仓库 Secrets；只在自行构建时需要，下载本次 APK 不需要这些配置。本次 r2 的 GitHub 构建被平台以 `repository invocation blocked` 取消；最终包在 HB 完成全部构建、JVM、API34 界面测试、签名及覆盖升级验收，未把取消的 CI 记为通过。

`local.properties`（不入库）：

```properties
storeFile=/private/path/fork.jks
storePassword=<local-secret>
keyAlias=fork
keyPassword=<local-secret>
```

同一包名、同一签名才能覆盖升级分支。不要把 keystore、口令、API key、远程 token、聊天数据库或手机备份放进仓库。源码备份不能代替数据备份。

## HTTP 后端

实现：[rk-exec-server.py](rk-exec-server.py)。要求非空 `RK_EXEC_TOKEN`，默认回环监听，`RK_EXEC_ROOT` 与 `RK_EXEC_JOBS` 控制目录。客户端仅接受 HTTPS，回环地址例外。

只接管本地类型工作区的 shell/job，不覆盖 SSH。没有远端文件工具或 job-list 接口；令牌具有命令执行权限，还可能进入设置备份，必须保密。服务重启后不会自动接管旧任务。

## 合并与发布纪律

- 使用现有 `fork/3.1.1` 分支历史增量提交，不以独立快照强推替换远端历史。
- 源码先与已验输入逐文件核对；只改文档不会扩大测试覆盖范围。
- 维护分支 CI 保持手动触发，不因推文档顺带发布 nightly。
- 可见版本用上游版本 + 短后缀（如 `3.1.1-r1`，后续 `r2/r3`）；versionCode 独立递增，不套早期 `3000000 + fork.N` 公式。首次旧 fork 升级 r1 需直接下载；未来发布资产命名遵循 `rikkahub-<version>.apk`。
- [CHANGES.md](CHANGES.md) 保留版本历史；[HANDOFF.md](HANDOFF.md) 是历史维护现场，不是当前用户说明。

## 回退

升级前备份聊天、设置和工作区。用 Git 历史或已验源码包回退代码；设备数据库必须单独处理，不要为了降级先卸载 App 再找数据。

ZIP 迁移只覆盖文件区，不含 Rootfs/数据库/整机；旧包未保存的符号链接和权限无法恢复。跨分支备份对齐只修改 staging 副本，不承诺所有分支业务数据都兼容。

## 并行维护与版本号

开始维护和发布前核对远端维护分支、最新 Release 与标签；先合并远端已生效的修复，再选未占用的版本号。已发布 tag、APK 和历史验收不覆盖。若已有同名资产，发布脚本先比对摘要，不同就拒绝，不能拿新包冒用旧版本。CI 构建提交与最终文档提交分开记录；最终 tag 相对已验构建只能增加说明和验收文件。

本轮维护后删除临时构建目录、Gradle/node依赖缓存、模拟器与下载分片；只留当前源码、最新APK、精简验收及必要签名。未发布的独立品牌草稿压缩归档，保留全部改动与删除记录，不保留整套重复源码。

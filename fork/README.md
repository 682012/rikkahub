# 个人分支：构建、发布与维护

本页只讲**怎么把这个分支构建出来、怎么发、出问题怎么回退**。用户功能与安装说明在 [仓库首页](../README.md) 与 [功能对比](FEATURES.md)，当前版本的变化见 [r20 说明](RELEASE-r20.md)。

## 当前状态

| 项目 | 值 |
|---|---|
| 版本 | `3.1.1-r20` / `3000067` |
| 维护分支 | `fork/3.1.1` |
| 包名 | `me.rerere.rikkahub.fork`（Debug 变体 `me.rerere.rikkahub.fork.debug`） |
| 应用名 / 图标 / 签名 | 与 r10 及更早的本分支版本一致，未改 |
| 官方基线 | `3.1.1` / `ad2c4430`，已合入上游 master `5ad19405` |
| 数据库 | schema 34（沿用 r3 起的本分支编号，不与官方编号混用） |
| 发布标签 / 资产名 | `v3.1.1-r20` / `rikkahub-3.1.1-r20.apk` |

已发布的历史版本各自保留 tag 与资产，不覆盖、不复用版本号。正式下载入口为 [Latest](https://github.com/682012/rikkahub/releases/latest)，固定版本按 tag 取包。

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

## r20 的验证入口

本版只运行本次改动相关的定向检查，不重跑历史全套。正式构建使用 Windows 云节点 `release`（R8 + lintVital）；冻结源码、构建 sourceId、APK 版本/签名/ABI/CRC/资源校验、源码逐文件比对与公开下载校验分别留证。真实结果随 [正式 Release](https://github.com/682012/rikkahub/releases/latest) 的 `r20.json` 与校验文件交付，不能把下述历史结果当作新版本通过。

共享云构建目录同一时间只跑一份任务；队列空闲后才能同步源码，提交绑定本次 sourceId。超时查原 job，不换幂等键重投。

## 历史 r11 的验证范围（不替代 r20）

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

- 当前版本：[r20 说明](RELEASE-r20.md) · [正式包与验收记录](https://github.com/682012/rikkahub/releases/latest)
- 功能计数与源码位置：[FEATURES.md](FEATURES.md)；逐版历史：[CHANGES.md](CHANGES.md)
- r1–r10 的发布说明：`RELEASE-r1.md` … `RELEASE-r10.md`；更早的 fork.38–fork.43 见 `RELEASE-fork38.md` … `RELEASE-fork43.md`
- 专项记录：[草稿持久化](AGENT-DRAFTS.md) · [工具效率](AGENT-EFFICIENCY-r11.md) · [架构](AGENT-ARCHITECTURE.md) · [工作区权限](WORKSPACE-ACCESS.md) · [网页会话](WEB-ACCOUNTS.md) · [历史维护现场](HANDOFF.md)
- 自动发布脚本与工具：[push-github.py](scripts/push-github.py) · [sync-upstream.sh](scripts/sync-upstream.sh)

## 许可

上游版权与 **AGPL-3.0** 保持不变（见 [LICENSE](../LICENSE)）。本分支不改变上游许可，也不代表上游接受这些修改。

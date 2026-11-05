# r21 Agent 优化 · 验证范围

版本 `3.1.1-r21 / 3000068`。**正式包已用 R8 + lintVital 构建并验包**（见下）；仍未安装到手机、未做真机点验；公开发布状态以 [r21 Release](https://github.com/682012/rikkahub/releases/tag/v3.1.1-r21) 为准。r20 的资产与标签未动。

阅读顺序建议：先看「正式构建」，再看「当前定向 167」，最后看「历史 375」——三者不是同一批证据，不能相加。

## 一、正式构建（真实结果，2026-11-05/06）

| 项目 | 实际值 |
|---|---|
| 命令 | `./gradlew :app:assembleGithubRelease`（R8 + release lintVital，非 fastBuild） |
| 结果 | 一次通过，`BUILD SUCCESSFUL in 32m 34s`；Room/KSP、整包 Kotlin、R8、lintVital 全部完成，mapping 1,546,908 行 |
| 构建来源 | 唯一幂等 job `idem-5df62db4b9dd07b77ca82ef4`；sourceId `986fcce3e2701f5e0cadfc0f1c794e6ec443355aa6b72c02abc259651dca2839` |
| 源码一致性 | 出包时冻结清单 2623 个文件逐 hash 核对零漂移；输入包 55,085,763 B。发布仅补文档、校验记录和生成 schema，运行时代码不变 |
| 产物 | `rikkahub-3.1.1-r21.apk`，45,842,374 字节（43.72 MiB），SHA-256 `623e7ebde54f604e95df25d8c6ddd04874937a71ec51291a306c1b3042b167c9` |
| 包体核对 | 包名 `me.rerere.rikkahub.fork`、versionName/versionCode、原 r20 证书、arm64-v8a + x86_64、ZIP CRC、云端与本地哈希均通过（`RELEASE-VERIFICATION.json` 的 assertions 全 true） |
| 数据库 | 真实 Room 导出 schema 35，identityHash `1f1167d7b7175efa7f8fde4946443215`；34→35 显式迁移只加 `lineage_id` / `folder_assignment_locked` 两列，旧行 COALESCE 兜底、不回填 |

构建本身的通过只说明 Gradle、KSP、AAPT、R8、lintVital 与打包链路对这份源码成立，**不**说明界面、通知、后台调度在设备上表现正确。

## 二、当前定向验证（本轮 167 项）

真实 Kotlin/JUnit 执行，使用实际源码与依赖，按 scope 记录输入与 SHA；汇总见 Release 附件 [TARGETED-CHECKS.json](https://github.com/682012/rikkahub/releases/download/v3.1.1-r21/TARGETED-CHECKS.json)。会话元信息 scope 的 `ChatReconnectPolicy.kt` 是较早的伴随编译依赖，其最终版本另由流式内核与恢复 scope 覆盖；167 项不是把所有历史套件重新绑定到同一个提交做的全量运行。

| 范围 | 结果 |
|---|---|
| 流式内核与恢复（工具参数完整状态、主循环回归、检查点与中断草稿分支） | 49 项，0 失败 |
| 真实草稿解析（流式工具参数增量、转义与 Unicode、同长度重写） | 24 项，0 失败 |
| 时间（每 step 取设备时钟、时区偏移、历史时间不冒充现在） | 16 项，0 失败 |
| 正文/时间指标（Unicode 码点、含标点不含空白、开始/结束分开） | 9 项，0 失败 |
| HTML 纯策略（HTML/SVG 文档、CSP 叠加、fragment 与资源作用域；不含 WebView 运行期） | 10 项，0 失败 |
| 归库 planner/store（候选筛选、置信度与家族规则、批次上限、撤销日志、按助手设置） | 55 项，0 失败 |
| 会话元信息提交（未加载历史保留、写失败不发布、忙碌拦截、阻止陈旧提交并释放 guard） | 4 项，0 失败 |

合计 **167 项，0 失败**。这些测试覆盖的是纯逻辑与状态机；它们不含 Compose 渲染、真实网络请求、WorkManager 调度或数据库真机迁移。

## 三、历史基线（375 项，不等于当前通过数）

早期 r21 Agent 阶段的定向检查共 375 项（主循环/工具调度 120、子代理 28、队列与通知 73、记忆与对话查询 8、经验学习 9、自动恢复 31、记忆注入与整理 47、通知身份 21、启动与轨道纯函数 29 等，按测试类去重），当时绑定的是**更早的冻结提交** 8ba55c4 前后的工作树。

它们证明的是那一阶段的行为；本轮源码在助手隔离、队列、恢复、UI 等处已再次改动，因此：

- 375 项不能写成「当前也全部通过」；
- 也不能与当前 167 项相加宣称「542 项通过」——那是两批不同输入上的结果；
- 需要为某一项背书时，以对应 scope 的新回执为准。

旧 `r21-productivity/release/` 只绑定大优化前的阶段快照，同样不能用作本轮构建证据。

## 四、未做的运行期验收

- **未自动安装**到用户手机，**未**做真机界面点验：Compose 观感、轨道风格实际排版、启动动效播放、工具草稿卡片、归库面板交互都没有在设备上点过。
- 通知点击与授权、系统 WorkManager 调度与恢复、SAF provider、设备 MediaCodec 导出、快捷磁贴绑定：未做运行期验收。
- 网页只在 Chromium（移动视口）实渲染通过；新 APK 的 WebView 行为与根因未在真机证实。
- 未跑全量 `test` / `lint` / `spotless`；本轮定向以外没有新增测试数据。
- 没有真实供应商网络请求或 SSE 回调验证；五条供应商 HTTP 路径此前的定向编译属于历史证据，不能代表当前运行期行为。

## 五、证据入口

- 构建与验包：`rk-sync/r21-release/RELEASE-VERIFICATION.json`、`SHA256SUMS`、`BUILD-STATE.json`、`build/AWAIT-RESULT.json`。
- 当前 167 项各 scope 回执：`rk-sync/r21-release/evidence/CURRENT-CHECKS.json`。
- 全树回滚备份：`r21-agent-core/pre-release-20261105T165604Z/source.tar.gz`（已验 gzip/SHA）。
- 历史阶段的 Agent/记忆/通知/UI 回执仍在 `rk-sync/r21-agent-core/checks/` 下，按冻结提交读取，勿当本轮结果。

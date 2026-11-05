# r21 Agent 优化 · 验证范围

版本草稿 `3.1.1-r21 / 3000068`。本轮未执行任何 Gradle 任务、整包构建、安装、发布或付费模型探测，正式版本仍是 r20。

## 已执行的定向验证

下列为真实 K2JVMCompiler 2.4.21 + JUnitCore 执行，使用实际依赖与源码，非手写替身；每组独立记录源码 hash。重复覆盖的测试不累计成新增测试数。

| 范围 | 当前结果 |
|---|---|
| Agent 主循环、工具调度、重复检测、HTTP 重试与预算 | 120 项，0 失败 |
| 子代理 FIFO、取消回滚、后台工具交付与串行写屏障 | 28 项，0 失败 |
| 会话/收件箱归属、文件队列、后台通知交付、防重复与发送恢复 | 73 项，0 失败 |
| 独立记忆复制与对话查询隔离 | 2 + 6 项，0 失败 |
| 经验学习内核与超大损坏文件恢复 | 9 项，0 失败 |
| 整轮自动恢复决策与检查点 | 31 项，0 失败，其中 15 项 RecoveryPolicy 测试也在 Agent 组内 |
| 记忆注入预算、整理调度/补做、整理 transcript | 9 + 27 + 11 项，0 失败 |
| 通知 tag、PendingIntent 用途与哈希碰撞 | 21 项，0 失败 |
| 启动策略、轨道状态与样式 | 15 + 14 项，0 失败；旧错误遮住生成状态的失败已修后复跑 |

以上按测试类去重共 **375 项真实 JUnit 测试**，全部通过；汇总时重新核对测试源码/生产源码 hash 均与当前工作树一致。

五条供应商 HTTP 路径连同 ErrorParser/Retry/RecoveryPolicy、流解码与消息定义，共 23 个真实源文件，定向编译通过。未执行真实网络请求或 SSE 回调。

## 接线编译与未覆盖范围

- ChatService / AppModule、队列、恢复、通知与磁贴连同 80 个真实源文件已做独立编译，零错误。完整 Compose 页面（32 个真实源文件）也已通过真实 Compose 编译器检查，零错误。三组接线的当前字节与回执一致。早期失败主要来自旧 Debug jar 不含 r20 的继承接口、缺 Ktor/MCP/Koin/Coil/Firebase 依赖，不能把这些直接当成生产代码缺陷；发现的三处错误 Compose 调用已经修复，最终接线回执已确认这些修复通过编译。
- 完整 Gradle 配置、AAPT/KSP、lintVital/R8、APK 签名/升级、真机视觉与交互均未运行。JVM 中的 Android SDK jar 仅用于类型检查，生成的 R.java 仅校验符号，不代表资源链接。

## 工作区证据入口

`/workspace/rk-sync/r21-agent-core/` 下：

- `checks/runs/`：Agent 与记忆定向检查的分次回执。
- `D-check/`：许可池与后台工具真实 JUnit 回执。
- `checks/m-check/`：隔离、学习、恢复的分 scope 回执。
- `checks/http-wiring/results/PROVIDERS-COMPILE.json`：五条 HTTP 路径定向编译。
- `checks/q-notification/`、`checks/pure-ui/`：通知身份与启动/轨道纯函数。
- `checks/preflight-static/`：中英资源、Manifest 引用与版本静态清单。
- `checks/n2-service/`、`checks/ui-final/`：服务与最终 UI 接线材料；`checks/n-wiring/` 保留早期闭包排错记录。
- `checks/FINAL-EVIDENCE.json`：按测试类去重、核对当前源码 hash 的统一清单。

旧 `r21-productivity/release/` 只绑定大优化前的阶段快照，不能用作本轮最终构建证据。后续出包需在源码冻结后另建回执目录。

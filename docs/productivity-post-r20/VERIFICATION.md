# 已发布 r20 之后的生产力增量

本补丁只包含 `d151224e458aaaf4a4f46e9989a00a7cdb23d8d5` 之后未合入的修复，不是另一个 r20 发布包。
目标合并基线：已发布 `v3.1.1-r20` / `5eb88813335b680e4538698378e979ea5446dd43`。
维护者应将本增量拣选到发布基线之后，不能用旧工作树覆盖媒体、标题或其他已发布改动。

## 本次新增的修复

- 后台整理的通知取消使用固定 jobId、私有 BroadcastReceiver 和持久 CANCELLED 状态；旧通知不会取消新任务。
- Worker 在进入识别前被中断，或启动前台服务失败时，回写暂停/失败状态，不留下永久 QUEUED。
- 自动批次记录归属，关闭自动整理停止它创建的批次；显式继续转为手动任务，仍不重发结果未知的请求。
- 自动扫描与关闭开关、任务启动与取消共用队列锁，取消后的任务不会被启动时的旧快照覆盖。
- 损坏队列先持久关闭自动整理，再隔离原文件；保留请求标记，避免清状态后静默重新计费。
- 界面显示任务失败/取消原因，可继续未发送项或放弃剩余任务。
- 分享正文存入私有缓存，导航仅携带 UUID；旧 text 字段仍兼容。存储上限单条 4 MiB、总量 32 MiB、128 条，7 天未访问文件可清理；超限明确报错，不静默截断。
- 分享导入按成功文件绑定名称/MIME，保留源名称并复用 FileUtils 的 MIME 回退；文字和附件分开标记完成，避免导入中断后漏入。
- 分享 URI 候选在遍历时限制到 64 条，导入最多 12 个；拒绝原有自家 authority 和非 content URI。不恢复会误删合法文件的 checkUriPermission 硬过滤。
- 显式声明 SYSTEM_ALERT_WINDOW；系统授权仍须由用户操作。

## 已执行检查

| 范围 | 结果 |
|---|---|
| skills | JUnit 4/4 |
| skills-cache | JUnit 4/4 |
| reading | JUnit 3/3 |
| document | JUnit 4/4 |
| prompts | JUnit 2/2 |
| organizer | JUnit 6/6 |
| share-text | JUnit 4/4 |
| 合计 | 27/27，7 组编译成功，无跳过 |
| Android 后台整理接线 | Kotlin compile-only 成功，含 Controller/Worker/StopReceiver 与真实仓库/DAO 依赖 |
| 共同阅读接线 | Kotlin compile-only 成功，含 LinkReader/ReadingPreferences/LinkReadingTransformer |
| 源码一致性 | 两份检查回执 sourcesByteIdentical=true、noDrift=true；交付时再次按 SHA256 核对 |
| 静态检查 | git diff --check、Manifest/资源 XML、私有 receiver、textRef 两端字段、旧变量引用检查通过 |

逻辑检查 runId：`8e4c4f112a38-20261105T124641Z-b2333d`。
接线检查 runId：`aee9fbcffb70-20261105T124812Z-953495`。
同目录 `CHECK-RESULTS.json` 为原始回执的字段摘录；`logic-source-manifest.json`、`wiring-source-manifest.json` 是原样保存的测试源码哈希。

实际使用 Kotlin 2.4.21、JDK 21 及 Windows 缓存中的真实 Android/依赖 JAR。阅读行为对照使用 OkHttp 4.12.0，不等同于 Android 项目声明的 5.5.0 运行时。
文档 BOM 的 LIMIT+2 已在正式 r20 中修复；本补丁不包含此重复差异。document 检查对应相同逻辑的本地文件，发布版另有一行说明注释，因此其文件哈希不同。

## 没有做、也不宣称通过的项目

- 未运行新的完整 Gradle/R8/lintVital 构建，未产出、安装或发布 APK。
- Compose 界面、系统权限页、通知点击、WorkManager 调度、进程恢复、SAF provider 授权均未在真机点验。
- compile-only 只证明列出的生产源码可编译，不能当作 Android 行为测试。
- 未重复任何付费媒体 API 验证，未变更 r20 标签、资产、版本号、签名、媒体或标题实现。

原始回执/日志与可复用 runner 保留在 `/workspace/rk-sync/r20-productivity/` 的 `checks/`、`build/`；交付副本位于 `delta-handoff/`。

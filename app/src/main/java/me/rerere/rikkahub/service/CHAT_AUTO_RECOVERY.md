# r21 自动恢复接线契约

`ChatService`、`ChatRequestFactory`、`AppModule` 和通知管理器均已接线，不再是待集成的独立代码。功能说明见 `docs/r21-agent-core/CAPABILITIES-r21.md`，真实验证范围见同目录 `VALIDATION.md`。

## 决策与预算

- `RetryPolicy` / `failureFacts` / `RecoveryPolicy` 使用真实 HTTP Response.code；消息里的数字不当作状态码。408、普通限速 429、5xx 与传输中断可重试；额度 429、401/403、402、格式/协议错误不可自动恢复。
- `ChatAutoRecovery.blockReason` 再检查用户停止、审批、权限、本地存储和未知副作用。它内部读取 `httpStatusOf(error)`，优先于调用方可选 hint。
- 工具开始但结果未知、生成媒体尚无安全检查点时不自动重放。已落库的完整工具结果留在检查点，只继续模型后续请求。
- 整轮 `ChatRecoveryLedger.noteFailure(id, retry.maxRetries)` 限次；默认 3。使用 elapsedRealtime 的 10 分钟票据时效，不长期后台重试。

## 会话与恢复

- 停止、发送、迁移与失败建票共享 session monitor，取消的生成不能随后重新建票复活。
- 恢复前核对 incarnation、原用户消息与失败尾巴。只恢复 `interruptedMessageId` 对应的变体；重新生成失败保留旧变体与原选择，不删除原答案。
- 定时恢复允许原路由上的超时/5xx，不只等网络 epoch 变化。仍检查可用网络、有效期、审批、迁移与原上下文。
- 明确发送、停止、成功、审批结束、迁移、删除都清理票据/恢复 job。等待超时同时发前台可见错误和后台终态事件，并重新安排空闲回收。

## 通知与交付

`AppEvent.ChatRecoveryStatus(conversationId, senderName, message, terminal)`：空消息撤销本会话恢复卡；非空非终态只更新安静进度；终态撤进度再发结果提醒。前台由行内进度/错误显示，后台受通知开关与权限控制；通知失败不阻塞生成。

恢复 2009/2010、完成 1、生成进度 2006、压缩 2007/2008 各有独立 tag/用途 URI；所有纯身份定义集中在 `NotificationIdentity.kt`。

子代理、后台工具与 shell 完成统一以 `wakeOnArrival=false` 入 inbox，不自动发起计费轮次。已有轮次取通知时执行 peek → 持久会话 flush → 精确快照 ack。`InboxNoticeDelivery` 的持久标识识别「历史已提交、队列未确认」的崩溃窗口；交付期间同键内容更新不会被旧确认误删。

## 验证边界

决策与队列有真实 JVM 单测，服务接线已做 compile-only；不代表 Gradle、Android 通知/磁贴运行时、进程杀死恢复或真机界面已验收。没有运行整包构建/安装/发布。

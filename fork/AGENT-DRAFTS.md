# r11 草稿收尾（ChatDraft / 输入草稿持久化）

接 16c5bb12（503 中断）之后的收尾记录。基线备份：`/workspace/rk-sync/r11/BASELINE.json`
（`baseline-r10-c2f331d.tar.gz`, sha256 `3be2502b…3a9c`）。没有 git commit / reset。

## 一句话结论

发送后**不再立刻清空输入框**。点发送 = 先落盘快照 → 等 ChatService 的「用户消息已落库」回执
（在应用级作用域里等，切页不会取消）→ 回执成功且输入框里仍是同一份内容时才清空并存空。
失败什么都不做，输入原样保留。`hold/release` 那套「安全副本 + 十分钟后恢复」已整体删掉。

## 逐条对应（主代理复核项）

1. **回执不受页面生命周期影响**
   - `ChatVM` 新增构造参数 `draftScope: CoroutineScope = ChatDraftStores.appScope`（有默认值，
     现有 `ViewModelModule` 调用点不用改就能编译）。`handleMessageSend` / `handleMessageEdit`
     的等待都跑在这里，`viewModelScope` 只负责页面级的东西（草稿 key 变化采集）。
   - `ChatDraftStores.appScope` = `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，进程级单例。

2. **点击发送不清输入**
   - `ChatPage.onSendClick` / `onLongSendClick` 不再 `inputState.clearInput()`；编辑路径也不再无条件清。
   - `ChatVM.handleMessageSend`：`inputState.snapshotDraft()` → `draftStore.saveNow(key, sent)`
     （同步入队 + 立刻写盘）→ 连点去重（按**内容** `sameContent`，光标动了也算同一份）→ 发给 service。
   - `finishSend(key, sent, persisted)`：`persisted=true` 时
     `draftStore.ack(key, sent)`；若 key 已从 `assistant:<id>` 迁移到 `conversation:<id>`，
     再 `ack(conversation:<id>, sent)`（两处都只清「仍等于 sent」的旧稿）；
     然后在主线程判断 `inputState.snapshotDraft().sameContent(sent)` 且 key 没变成别的会话，才 `clearInput()`。
   - 失败（撤回/初始化异常/取消/暂停）：不动输入、不动磁盘，用户不用重打；也不会出现
     「十分钟后自动恢复出一份还在队列里的重复输入」。

3. **ChatDraftStore 持久化**
   - `drain()` 的「取下一条 pending + 写盘」整体放进 `writeMutex`，同一 key 的两次落盘不可能逆序。
   - `load()` 用 `versions`（每 key 变更计数）+ `deleted`（墓碑，上限 64 个 key）：读盘回来时
     版本变了或已确认删除 → 结果作废，迟到的旧稿不会复活。
   - `submit()` 做差异检查：内容没变（只有光标/选区变化）不排队、不重写整份文件；
     相同的 pending 不重复排队。
   - `ack(key, sent)` 只在内存里那份 `sameContent(sent)` 时才清，返回是否真的清了。

4. **VM 初始化顺序**
   - `startDraftSync()` 的协程体第一行 `yield()`：`init` 里的协程在 `Main.immediate` 上可能同步跑到
     这里（会话已缓存时 `initializeConversation` 不挂起），此时 `draftLock`/`_draftReady` 等字段
     声明在后面还没初始化；yield 让构造函数先跑完。
   - 采集前 `withTimeoutOrNull(5_000) { settingsStore.awaitLoaded() }`：设置没加载时 `assistant`
     还是 dummy，算出来的 key 是错的；等它加载完再取 `draftKeyFlow`。`draftReady` 仍在首次
     `restoreDraft` 之后置 true，ChatPage 的分享导入继续等这个信号。
   - `restoreDraft`：非 `replaceInput`（首次恢复 / key 迁移）只在输入框为空时才填草稿，
     读盘期间用户已经打的字不会被磁盘旧稿覆盖；读盘回来后还会核对 `draftKey == key`，
     期间切过 key 就直接放弃（更强制止旧稿错归到新会话/新助手）。

5. **ChatPage**
   - `importApplied` 改成 `rememberSaveable(id, text, files)`，换会话/换分享内容会重新导入。
   - 分享文件按 `files[index]` 取 mime（原来 `mapNotNull` 会丢项导致图片/视频错位认领类型），
     非 image/video/audio 一律进 `UIMessagePart.Document`（不再静默丢弃文档）。
   - `LifecycleEventEffect(ON_PAUSE)` + `DisposableEffect { onDispose { vm.persistDraftNow() } }`：
     退到后台/离开页面就保存，不再只靠 `onCleared`。

6. **回执语义**
   - `ConversationInbox.remove()`（原有）、`pause()`、`failReplyWaiters()` 都 `persisted.complete(false)`。
   - `ChatService.sendQueuedMessage`：`session.flush()` 之后 `persisted.complete(true)`；异常分支和
     `invokeOnCompletion(cause != null)` 完成 false；`complete` 只生效一次，所以模型生成阶段失败
     不会把已经落库的消息又「退回」成草稿。
   - `editMessage` 路径：`handleMessageEdit` 返回 Boolean，提交前不清；`editMessage` 成功且输入
     仍是提交那份才清 + 存空。**注意签名变了**（`Unit` → `Boolean`），调用点只有 ChatPage。

## 改动的文件

- `app/src/main/java/me/rerere/rikkahub/data/draft/ChatDraftStore.kt`（重写）
- `app/src/main/java/me/rerere/rikkahub/data/model/ChatDraft.kt`（+`sameContent`）
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`（草稿段 / send / edit / onCleared；
  `handleCompressContext`、compression、backgroundTools 段未动）
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`（导入/发送/生命周期）
- `app/src/main/java/me/rerere/rikkahub/service/ConversationInbox.kt`（pause / failReplyWaiters 回执）
- `app/src/main/java/me/rerere/rikkahub/ui/hooks/ChatInputState.kt`（未改：`snapshotDraft`/`applyDraft` 已经够用）
- 测试：`app/src/test/java/me/rerere/rikkahub/data/draft/ChatDraftStoreTest.kt`、
  `app/src/test/java/me/rerere/rikkahub/service/ConversationInboxTest.kt`

`ChatService.kt` 本次**没有改**：`sendMessageWithReceipt`(443) / `sendQueuedMessage` 的 receipt 分支
已经是「flush 后 true、异常/取消 false」，符合要求。

## 真实检查（代码层面逐条核过）

- 全仓 grep `\.hold(` / `\.release(` 已无调用（除 `MediaPlayer.release` 等无关 API）；`submittingDrafts`
  已更名 `sendingDrafts`，无残留引用。
- `clearInput()` 在 `ChatPage.kt` 已归零，只剩 `ChatVM` 的成功路径和 `ChatInputState` 定义。
- `ChatVM` 的 `hold/ack/release` 旧流程（含 `SEND_CONFIRM_TIMEOUT_MS` 超时退稿）已删除，常量一并删除。
- 语义自检：迁移 + 成功回执 → 两个 key 都按 `sameContent` 清；用户等回执时打了新字 → ack 返回 false，
  新稿留在 store 和输入框；回执失败 → 输入框、磁盘都不动。
- `drain` 取 pending 与写盘在同一把 `writeMutex` 内；`load` 的迟到结果有 version/tombstone 双保险。

## DI（主代理接，二选一）

```kotlin
// ViewModelModule
viewModel<ChatVM> { params ->
    ChatVM(
        ...,
        draftStore = get(),          // ChatDraftStore
        draftScope = get<AppScope>(), // 或 keep 默认 ChatDraftStores.appScope
    )
}

// AppModule（可选，替换 ChatDraftStores holder）
single {
    ChatDraftStore(
        storage = FileChatDraftStorage(File(get<Context>().filesDir, CHAT_DRAFT_DIR)),
        scope = get<AppScope>(),
    )
}
```

`draftScope` 必须是应用级 scope，不能是 `viewModelScope`，否则就回到「切页取消回执」的老问题。

## 测试（新增/改写的，纯 JVM，跑 `:app:testDebugUnitTest` 里的这两个类）

`ChatDraftStoreTest`（新增 7 个，改写 2 个）：
- `save writes after the debounce, not immediately`（原有）
- `flush writes without waiting for the debounce`（原有）
- `only the last state is written`（原有）
- `empty draft removes the stored copy`（原有）
- `late disk read does not overwrite newer input`（原有）
- `keys are isolated`（原有）
- `attachments are kept as references`（原有）
- `draft file names are stable and distinct per key` / `new chat draft belongs to...` /
  `only the first message moves...`（原有）
- 新增：`confirmation keeps text typed after sending`、`confirmation still clears when only the cursor moved`、
  `confirmation never clears a different draft`、`a late disk read after confirmation cannot resurrect the draft`、
  `a stored draft is not read back after confirmation`、`a newer draft is never overwritten by an older queued write`、
  `confirmation clears both keys after the assistant to conversation move`

`ConversationInboxTest`（新增 3 个）：
- `withdrawing a queued message reports that it was never persisted`
- `pausing and approval release report queued messages as not persisted`
- `empty input is rejected without leaving a pending receipt`

## 已知取舍 / 留给主代理

- **暂停（pause）会让队列里那条的回执变成 false**：输入框和磁盘保留原文（不丢字），但消息恢复后
  真的发出去时不会再有第二次确认，用户可能看到一份「已经发出去、却还留在输入框里」的文本。
  按复核要求实现；若要更彻底，可让 `ChatVM` 在 `resumeMessageQueue()` 时清掉与队首同内容的草稿，
  这需要 ChatService 暴露队首内容，未做。
- 页面在「等回执」期间被杀：`ack` 在 `ChatDraftStores.appScope` 里仍会执行；`onCleared` 先保存再
  `flushAll`，两者都经过 store 的 `pending`（后到的 Delete 覆盖先到的 Save），不会复活旧稿。
- `ChatService` 的 init/compression/backgroundTools 段按约定没动。
- 编译在 Windows 侧统一做；本次没有在本机跑 gradle。

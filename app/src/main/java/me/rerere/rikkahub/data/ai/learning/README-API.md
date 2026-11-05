# 本地经验学习（r21）

AssistantLearningRepository(rootDir: File)，Koin单例，主代理已注册 `filesDir/assistant-learning`。仅收集本助手的ID/计数/耗时，不读聊天正文、思考、工具参数；没有额外模型请求、没有训练权重。

- suspend snapshot(assistantId): LearningState；observe(assistantId): Flow<LearningState>
- suspend setEnabled(id, bool)：关闭后停止收集与后续轮次注入，保留旧数据。
- suspend recordTurn(id, LearningTurn)：同messageId更新，绝不当成重新生成。
- suspend markRegenerated(id,messageId)：用户明确动作反馈。
- suspend upsertGuidance(assistantId,text,id=null,enabled=true)
- suspend setGuidanceEnabled(assistantId,id,enabled)：用户经验/auto:经验均支持；auto停用后持续禁用。
- suspend deleteGuidance：用户经验删除；auto条目加入停用集。
- suspend clear(assistantId,LearningClearScope.ALL/TURNS/GUIDANCE)
- buildPrompt(state, modelId)：最多3条/1800字符，仅本助手且同模型的自动统计证据（至少3轮）与用户明确经验，XML转义。不覆盖主设定/用户要求/权限。

200轮、最多24条（18人工+6自动）；坏文件（含超过 4 MiB 的）先唯一备份后恢复，无法读到开关则恢复为关闭。未知schema/错assistantId直接报错，绝不重绑归属。更新后全局版本信号只使订阅者重读自己的文件，无共享历史。写前锁定并读磁盘，tmp/fsync/rename，固定锁条带不随助手数无限增长。

提示词是优化建议，计数与耗时不等于质量评分，不保证每轮性能单调提升。

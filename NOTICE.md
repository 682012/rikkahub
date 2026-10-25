# 修改声明 / Modification Notice

本仓库基于 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) 维护，依据 AGPL-3.0 第 5 条记录修改内容与日期。上游代码、原始版权声明和 `LICENSE` 保留；本分支与上游团队没有隶属关系。

- 当前官方基线：`3.1.1` / `ad2c4430`。
- 当前维护分支：`fork/3.1.1`，版本 `3.1.1-fork.37 / 3000041`。
- 初始修改日期：2026-10-18，最初基于官方 3.0.0；之后合并官方 3.1.0、3.1.1。
- 本轮审查与文档更新：2026-10-25。
- 维护者：GitHub [@682012](https://github.com/682012)。

## 新增能力分组

1. 子代理任务管理与并发协作。
2. 工作区后台任务及可配置命令超时。
3. 可选自托管 HTTP 远程执行协议。
4. 基于官方用量记录的单会话用量面板。
5. 基于官方检查点机制的自动上下文压缩与独立压缩模型设置。
6. 本地工作区文件区 ZIP 导出。

另有流式节流保存、节点差分写入、大消息分片读取、跨分支备份对齐、记忆原文/冲突保留、重试次数设置、工具输出保护、技能校验及搜索/通知修复。

SSH 工作区、MCP、Skills、会话历史检索、全局用量统计、语音、媒体生成、Web UI 等原有能力仍归上游贡献，不计作本分支独创。具体对比与限制见 [fork/FEATURES.md](fork/FEATURES.md)，逐版记录见 [fork/CHANGES.md](fork/CHANGES.md)。

This is an unofficial personal fork of RikkaHub, based on upstream 3.1.1. The six added capability groups and hardening changes are documented above; existing upstream functionality is not claimed as original work. Upstream copyright and AGPL-3.0 licensing remain intact. See [DISCLAIMER.md](DISCLAIMER.md).

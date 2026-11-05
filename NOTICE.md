# 修改声明 / Modification Notice

本仓库基于 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) 维护，依据 AGPL-3.0 第 5 条记录修改内容与日期。上游代码、原始版权声明和 `LICENSE` 保留；本分支与上游团队没有隶属关系。

- 当前官方基线：`3.1.1` / `ad2c4430`。
- 当前维护分支：`fork/3.1.1`，版本 `3.1.1-r21 / 3000068`。
- 初始修改日期：2026-10-18，最初基于官方 3.0.0；之后合并官方 3.1.0、3.1.1。
- 本轮审查与文档更新：2026-11-05；在正式 r20 上累加后台任务取消/恢复与长文本分享修复。既有角色原图/人设、签名与有效功能保留，完整变化与验证边界见 [r21 说明](fork/RELEASE-r21.md)。
- 维护者：GitHub [@682012](https://github.com/682012)。

## 新增与增强

按独立用途统计 5 组新增工作流：子代理协作、工作区后台任务、自托管 HTTP 执行、自动上下文压缩、ZIP 工作区文件迁移。
另有 2 项主要增强：可配置重试次数/等待策略、基于官方记录的单会话用量面板。

流式保存、节点差分、大消息完整分片读取、跨分支备份对齐、记忆冲突保留、权限与 CRC 校验、通知及后端路由修复单列为稳定性加固，不计作新增功能。fork.38 的完整变化与实际测试见 [更新说明](fork/RELEASE-fork38.md)。

SSH 工作区、MCP、Skills、会话历史检索、全局用量统计、语音、媒体生成、Web UI 等原有能力仍归上游贡献，不计作本分支独创。具体对比与限制见 [fork/FEATURES.md](fork/FEATURES.md)，逐版记录见 [fork/CHANGES.md](fork/CHANGES.md)。

This is an unofficial personal fork of RikkaHub, based on upstream 3.1.1. Five added workflows, two major enhancements, and separate hardening changes are documented above; existing upstream functionality is not claimed as original work. Upstream copyright and AGPL-3.0 licensing remain intact. See [DISCLAIMER.md](DISCLAIMER.md).

## 3.1.1-r4 实验版

新增个人网页会话本地AES-GCM存储、Gemini/Claude实验提供商、提示词工具适配与缓存/窗口用量修复。协议参考与许可见[WEB-ACCOUNTS.md](fork/WEB-ACCOUNTS.md)。真实账号兼容性未经实测，不代表官方授权。本源码仍按AGPL-3.0分发。

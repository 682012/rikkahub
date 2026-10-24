# 修改声明 / Modification Notice

本仓库是 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) 的个人修改版，依据 AGPL-3.0 第 5 条记录修改内容与日期。

- 上游项目：RikkaHub
- 上游版本基线：`3.0.0`（commit `55a108a4`）
- 修改开始日期：2026-10-18
- 维护者：GitHub [@682012](https://github.com/682012)
- 修改分支：`fork/3.0.0`

主要修改：

- 新增子代理系统（`subagent_run/wait/result/list/cancel`，可读取其他会话）
- 工作区命令超时可配置，新增后台任务与前台服务保活
- 新增远程执行后端（HTTP `/exec` 与 `/job/*`）
- 新增单会话用量统计（`usage_record` 表与聊天页用量面板）
- 新增自动上下文压缩（阈值触发，插入摘要检查点，不删除历史）
- 大会话加载时把大块内容外置到本地缓存
- 备份导入支持同版本不同分支的结构对齐
- 包名、签名、版本号按个人构建调整

修改文件清单与上游合并方法见 `fork/README.md` 与 `fork/scripts/sync-upstream.sh`。

本项目按“现状”提供，不提供担保与技术支持，详见 `DISCLAIMER.md`。

# 客户端协议兼容档（clientProfile）

给会话可选一个"按某类公开客户端的方式发请求"的兼容档：Codex / Claude Code / Pi。
它**不是**官方客户端，也没有任何官方通道：不发凭证、不改鉴权、不伪造签名或 origin 权限，
不承诺绕过任何服务端验证。能做的就是公开协议范围内的对齐。

入口：`me.rerere.rikkahub.data.ai.clientprofile.applyProfile(params, setting, profile)`。
纯函数、无网络、无额外模型请求；同样的 (provider, model, 选择) 永远同一个结果（AUTO 因此一轮稳定）。

## 支持矩阵

| provider \ 档 | NATIVE | AUTO | CODEX | CLAUDE_CODE | PI |
|---|---|---|---|---|---|
| OpenAI（Responses） | 原生 | →CODEX（前沿模型）/→PI | ✅ | ❌ 回原生 | ✅ |
| OpenAI（/chat/completions） | 原生 | →PI | 🟡 部分 | ❌ 回原生 | ✅ |
| ChatGPT 账号 | 原生 | →CODEX | ✅ | ❌ 回原生 | ❌ 回原生 |
| Claude（Anthropic Messages） | 原生 | →CLAUDE_CODE / →PI | ❌ 回原生 | ✅ | ✅ |
| Grok 账号 | 原生 | →PI | ❌ 回原生 | ❌ 回原生 | ✅ |
| Google（generateContent） | 原生 | →NATIVE | ❌ 回原生 | ❌ 回原生 | ❌ 回原生 |
| Gemini / Claude Web 账号 | 原生 | →NATIVE | ❌ 回原生 | ❌ 回原生 | ❌ 回原生 |

"❌ 回原生" 不是静默忽略：`ProfiledRequest.reason` 会写清楚需要什么协议、当前是什么协议、
为什么不能硬塞（例如把 OpenAI 的消息硬改写成 Anthropic 格式会破坏历史 tool_call 的对应关系）。

## 每档具体改了什么

### CODEX（OpenAI Responses）
- 请求头：`originator: codex_cli_rs` —— openai/codex 的 CLI 在 Responses 上用它标识来源；**不带版本号**。
- 请求体（仅 Responses）：`store=false`、`prompt_cache_key=<本轮 sessionId>`（同一会话固定，前缀缓存才命中）。
  走 `/chat/completions` 时这两个参数不发，因为对只支持 chat completions 的网关是未知字段。
- 工具引导：工作区最小 diff、改前先读、只跑会失败的最窄检查、被阻塞就说阻塞。

### CLAUDE_CODE（Anthropic Messages）
- 请求头：`x-app: cli`、`user-agent: claude-cli`。版本段**故意省略**——版本要跟真客户端发版跑，猜版本等于伪造。
- 请求体：不加。`anthropic-version` 仍由 ClaudeProvider 原有逻辑发送，档不覆盖（见 `PROFILE_FORBIDDEN_HEADER_NAMES`）。
- 工具引导：多步计划、破坏性操作先确认、独立只读调用批量发、报路径带行号。

### PI
- 请求头 / 请求体：无。pi-ai 是统一封装，本身不带产品标识头，所以这里也不编一个。
- 工具引导：批量工具调用、系统前缀保持静态利于缓存、有技能先按技能走。

### 共用规则
- 用户请求头（assistant + model 的 customHeaders）优先；同名按大小写不敏感跳过，档不覆盖。
- 含 CR/LF 的头一律拒（请求头注入），拒绝的名字记在 `droppedHeaders`，不记值。
- 凭证头（`authorization` / `x-api-key` / `x-goog-api-key` / cookie / 组织与项目标识）与 provider 自有协议头
  （`anthropic-version` / `content-type` / `host`）永远不会被档添加或改写。
- `providerSetting` 永远是 null：改 API 类型（例如开 Responses）只在 `suggestedApiChange` 里给建议，
  代码不自动改 provider 配置，也不影响其他会话。

## 明确不做

- 不复制任何产品的专有系统提示（几十 KB 模板），只给几行通用工具用法引导。
- 不改工具名、不改工具 schema：改名会让历史里的 tool_call 对不上。
- 不伪造 `openai-organization`、`anthropic-beta`、`user-agent` 版本号，不发签名。
- 不承诺"支持任何客户端"：上表之外没有实现，也没有验证过的通道按不支持处理。

## 公开来源（凭这几处写的，不是猜）

- openai/codex：CLI 走 Responses 时用 `originator` 标识来源、显式 `store=false`、按会话带 `prompt_cache_key`。
- Anthropic 公开 API / claude-code SDK：`/v1/messages`、`anthropic-version` 由客户端发、CLI 用 `x-app: cli` 与 `user-agent: claude-cli/...`。
- badlogic/pi-mono 的 `pi-ai`：统一封装 Chat Completions 与 Messages，不自带产品标识头。

package me.rerere.rikkahub.data.ai.clientprofile

import me.rerere.rikkahub.data.model.ConversationClientProfile

/**
 * 各档往系统提示里追加的一小段**工具用法引导**。
 *
 * 三条硬规则：
 * 1. 只写通用工作方式，不复刻任何产品的专有系统提示 —— 那既是别人的提示词，也会把上下文顶爆。
 * 2. 不动身份、不改助手人格、不削弱权限与审批。原文开头就写 "Tool-use style for this turn"，
 *    明确这只影响工具怎么用，不是"你现在是 Codex"。
 * 3. 每段控制在几行，追加在系统提示末尾，用户自己的提示词一个字不改。
 */
internal fun guidanceFor(profile: ConversationClientProfile): String = when (profile) {
    ConversationClientProfile.CODEX -> CODEX_GUIDANCE
    ConversationClientProfile.CLAUDE_CODE -> CLAUDE_CODE_GUIDANCE
    ConversationClientProfile.PI -> PI_GUIDANCE
    ConversationClientProfile.NATIVE,
    ConversationClientProfile.AUTO,
    -> ""
}

private const val CODEX_GUIDANCE =
    "Tool-use style for this turn: prefer workspace-scoped minimal diffs. Read a file before editing it, " +
        "change only what the task needs, and leave unrelated code and formatting alone. After editing, run the " +
        "narrowest check that could fail (build, test, or lint) and report the exact command and its outcome. " +
        "If a step is blocked, state the blocker instead of inventing results."

private const val CLAUDE_CODE_GUIDANCE =
    "Tool-use style for this turn: work in explicit steps. Briefly state the plan before multi-step work, " +
        "then keep to one line of work at a time. Ask before destructive or irreversible actions (deleting " +
        "files, force-push, reinstalling, spending money) and never skip a confirmation the user configured. " +
        "Batch independent read-only tool calls in one step, cite file paths with line numbers when reporting " +
        "findings, and close with a short summary of what changed."

private const val PI_GUIDANCE =
    "Tool-use style for this turn: batch independent tool calls instead of issuing one per step, and keep " +
        "the system prompt prefix stable so caching stays effective. When a task matches an available skill " +
        "or saved workflow, load it and follow it rather than improvising. Prefer small, verifiable steps and " +
        "report results concisely."

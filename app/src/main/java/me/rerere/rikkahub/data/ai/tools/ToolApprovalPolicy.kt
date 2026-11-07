package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.devicecontrol.DeviceAction
import me.rerere.rikkahub.data.devicecontrol.DeviceCapability

/**
 * 统一的可配置工具审批策略（纯函数，不碰 Android API，方便单测）。
 *
 * 之前的问题：
 * - 设备工具写死 `needsApproval = { !descriptor.readOnly }`，用户在工作区卡片里关掉开关也没用；
 * - `workspace_android_shell` 默认 true 但只出现在工作区卡片里，别的入口看不出来它要审批；
 * - WebAccount 在 ChatRequestFactory / AgentLoop 里被无条件改成"所有工具都要审批"，用户没法配置；
 * - `tool_batch` 只要有一个成员需要审批就整批停下，批准后批内被拒绝的成员仍会执行。
 *
 * 这里的约定：
 * - 每个工具只有一个"审批键"（[approvalKey]），用户覆盖表（workspace.toolApprovalOverrides()）
 *   按同一个键生效，UI 和运行时判定读同一张表；
 * - 默认值按"只读查询免交互，写操作/装机/杀进程需审批"给，但仍然可以在表里改成任意方向；
 * - 这里只管"要不要弹审批"。真正的权限（设备授权白名单、Shizuku、写开关、助手绑定）
 *   仍然由 DeviceControlPolicy / PrivilegeCenter 判定，审批通过也不等于授权通过。
 */
object ToolApprovalPolicy {

    /**
     * 设备工具按能力分档给默认值：
     * - READ（看树、列 App）：免交互，看东西不该打断；
     * - 其余（点、滑动、返回/桌面/启动、以后新增的写动作）：默认要审批。
     *
     * 刻意不读 `descriptor.readOnly` 而读 [DeviceAction] 的能力，因为 readOnly 是描述符上的
     * 数据字段，写错一个字段就等于静默免审；能力来自动作枚举，新增动作必须显式声明能力。
     */
    fun deviceToolNeedsApprovalByDefault(toolName: String): Boolean {
        HighPrivilegeToolDefaultApprovals[toolName]?.let { return it }
        val action = DeviceAction.of(toolName) ?: return true
        return action.capability != DeviceCapability.READ
    }

    /** 设备工具的默认审批值。未知设备工具按"需要审批"处理（失败关闭）。 */
    fun deviceToolApprovalDefault(toolName: String): Boolean = deviceToolNeedsApprovalByDefault(toolName)

    /**
     * 解析某个工具的最终审批要求。覆盖项永远优先于默认值。
     *
     * @param toolName 工具名（审批键）
     * @param overrides 用户配置表：工具名 -> 是否需要审批
     * @param default 该工具在没有任何覆盖时的默认值
     */
    fun resolve(
        toolName: String,
        overrides: Map<String, Boolean>,
        default: Boolean,
    ): Boolean = overrides[toolName] ?: default

    /**
     * workspace 家族的审批键。返回 null 表示"不是 workspace 家族的工具"，调用方按自己的策略处理。
     *
     * shell 家族共用一个键 [WorkspaceShellApprovalKey]：自由 shell 不能冒充只读查询，
     * workspace_shell / workspace_android_shell / 后台 shell 的 job 工具在同一个开关下，
     * 用户关掉 shell 审批不会只关掉其中一个。
     */
    fun workspaceApprovalKey(toolName: String): String? = when (toolName) {
        in WorkspaceShellApprovalKeys -> toolName
        in WorkspaceToolDefaultApprovals -> toolName
        else -> null
    }

    /** 自由 shell 的审批键（所有 shell 类工具共用一个）。 */
    const val WorkspaceShellApprovalKey: String = "workspace_shell"

    val WorkspaceShellApprovalKeys: Set<String> = setOf(
        "workspace_shell",
        "workspace_android_shell",
    )

    /**
     * 只读查询类 workspace 工具。这些工具**永远**可以免审批（用户也可以显式改成需要审批），
     * 但它们是唯一允许在默认状态下免审批的 workspace 工具组：任何能执行命令、写文件的工具
     * 都不许出现在这里。
     */
    val WorkspaceReadOnlyApprovalKeys: Set<String> = setOf(
        "workspace_read_file",
        "workspace_grep",
        "workspace_glob",
    )

    /**
     * 自由 shell 类工具：即使参数看起来是只读命令（`ls` / `cat`），也不当作只读。
     * shell 的参数是任意字符串，判断"这条命令只读"需要解析 shell，做不到可靠，
     * 所以一律按可写处理，由审批键控制。
     */
    fun isFreeformShellTool(toolName: String): Boolean = toolName in WorkspaceShellApprovalKeys

    /** 所有可配置工具（UI 用）：工具名 -> 默认是否需要审批。 */
    val configurableTools: Map<String, Boolean>
        get() = buildMap {
            putAll(WorkspaceToolDefaultApprovals)
            put(WorkspaceShellApprovalKey, false)
            putAll(HighPrivilegeToolDefaultApprovals)
            DeviceAction.entries.forEach { action ->
                put(action.wire, action.capability != DeviceCapability.READ)
            }
        }

    // ---------------------------------------------------------------- 权限中心高权限工具

    /**
     * 高权限工具（android_*，见 HighPrivilegeTooling）的默认审批值。
     *
     * 只决定"要不要先弹窗问用户"，**不代表授权**：这些工具的名单本身只对权限中心绑定的助手可见，
     * 每次调用还会重核自动化开关 / 助手绑定 / 写开关 / Shizuku，写路径另有幂等账本。
     * 关掉审批 = 不再弹窗，不等于多拿到一项权限。
     *
     * - 只读查询（status / settings_get / packages）：免交互；
     * - 写（settings_put / shell）与日志类（diagnostics，可能含个人信息）：默认需审批。
     *
     * 这里用字面名字而不是引用 deviceprivilege 常量，是为了让本文件保持纯函数、可单测；
     * 名字与 [me.rerere.rikkahub.data.deviceprivilege.HighPrivilegeTooling.all] 一一对应。
     */
    val HighPrivilegeToolDefaultApprovals: Map<String, Boolean> = mapOf(
        "android_privilege_status" to false,
        "android_settings_get" to false,
        "android_packages" to false,
        "android_settings_put" to true,
        "android_diagnostics" to true,
        "android_shell" to true,
    )

    /** 高权限工具的默认审批值；未登记的名字按"需要审批"（失败关闭）。 */
    fun highPrivilegeApprovalDefault(toolName: String): Boolean =
        HighPrivilegeToolDefaultApprovals[toolName] ?: true

    fun isHighPrivilegeTool(toolName: String): Boolean = toolName in HighPrivilegeToolDefaultApprovals

    // ---------------------------------------------------------------- tool_batch

    /** tool_batch 在工具目录里的名字（ai 模块按字面名排除批内批，这里保持一致）。 */
    const val ToolBatchName: String = "tool_batch"

    /**
     * 批内单个成员是否允许执行：只有"明确批准"的成员才跑。
     *
     * 拒绝语义（结构化，不让模型把拒绝当成成功）：
     * - 成员被用户拒绝 -> 该成员不执行，返回 [MemberDenial.DENIED_BY_USER]；
     * - 成员未获批准（整批被拦时没有逐个批准的机会）-> [MemberDenial.NOT_APPROVED]。
     */
    enum class MemberDenial(val wire: String) {
        DENIED_BY_USER("denied_by_user"),
        NOT_APPROVED("not_approved"),
    }

    /**
     * 从一次审批结果里算出"批准了哪些批内成员"。
     *
     * @param approved 整批是否被批准（用户点了"全部批准"）
     * @param memberApprovals 成员粒度结果：下标 -> 是否批准。缺省项跟随 [approved]
     * @param size 批内成员个数
     */
    fun approvedMemberIndices(
        approved: Boolean,
        memberApprovals: Map<Int, Boolean> = emptyMap(),
        size: Int,
    ): Set<Int> = (0 until size).filterTo(mutableSetOf()) { index ->
        memberApprovals[index] ?: approved
    }

    /**
     * 为被拒绝的成员生成结构化输出（JSON 字符串，走官方 tool_batch 的 error 字段）。
     * 让模型明确知道哪个成员没跑、为什么没跑，而不是让它以为返回了空结果。
     */
    fun deniedMemberOutput(index: Int, name: String, denial: MemberDenial, reason: String): String {
        val safeReason = reason.trim().ifEmpty { "No reason provided" }
        return buildString {
            append("{\"error\":\"tool_denied\",\"index\":").append(index)
            append(",\"name\":").append(quote(name))
            append(",\"code\":").append(quote(denial.wire))
            append(",\"message\":").append(quote("This call was NOT executed because it was not approved."))
            append(",\"reason\":").append(quote(safeReason))
            append("}")
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    // ---------------------------------------------------------------- WebAccount

    /** 供应商侧的强制审批来源，用来在界面上明确告诉用户"为什么还要我批准"。 */
    enum class ApprovalSource { PROVIDER_FORCED, TOOL_POLICY }

    /**
     * WebAccount 是否额外强制审批。
     *
     * m3 起**不再无条件强制**：默认跟随各工具自己的可配置策略（只读免交互，写操作按表）。
     * 只有当用户显式打开 WebAccount 的强制审批时才恢复旧行为，并且界面要提示来源，
     * 不能像以前那样悄悄把所有工具改成需要审批。
     */
    fun webAccountForcesApproval(forceApprovalForWebAccount: Boolean): Boolean = forceApprovalForWebAccount

    /**
     * 最终判定：工具自己的策略 + 供应商策略取并集，但保留来源，便于弹窗解释。
     */
    fun effectiveApproval(
        toolNeedsApproval: Boolean,
        forceFromProvider: Boolean = false,
    ): Pair<Boolean, ApprovalSource?> = when {
        forceFromProvider -> true to ApprovalSource.PROVIDER_FORCED
        toolNeedsApproval -> true to ApprovalSource.TOOL_POLICY
        else -> false to null
    }
}

package me.rerere.rikkahub.data.deviceprivilege

import kotlinx.serialization.Serializable

/**
 * Meridian m1 高权限中心 —— 纯数据契约（无 Android 依赖，可直接跑 JVM 单元测试）。
 *
 * 这些类型是 UI / 工具层 / [HighPrivilegeController] 之间唯一约定的形状，字段名固定。
 * 这里没有任何"假授权"字段：每一行的 [PermissionState] 都必须由 PackageManager / AppOps /
 * 各自 special access API 的真实回读推出来。
 */

/** 单项权限的真实状态。 */
enum class PermissionState {
    /** 现在就能用（已授予 / 系统层面放开）。 */
    GRANTED,

    /** 声明了但没授予，用户可以按 [HighPrivilegePermissionRow.grantRoute] 里的路线去争取。 */
    DENIED,

    /** 本机拿不到（功能没实现 / 版本不支持 / 拒绝跨用户）。不要去"争取"。 */
    UNAVAILABLE,

    /** 只有系统或签名 App 能持有；普通 App + shell 都不该声称有。 */
    SYSTEM_ONLY,
}

/** 这一项权限该怎么给。UI 只按这个渲染按钮，不要自己猜。 */
enum class GrantRoute {
    /** 需要 ADB 或 Shizuku 的 shell UID 执行 `pm grant`，由 [HighPrivilegeController.grantDevelopmentPermissions] 代跑。 */
    ADB_SHIZUKU,

    /** 系统设置页里的独立开关（special app access），只能用户自己点。 */
    SYSTEM_SETTINGS,

    /** 运行时/安装时权限（弹窗或 manifest 自动授予）。 */
    RUNTIME,

    /** 由本 App 自己的组件或 Shizuku 绑定状态决定（无障碍、通知监听、Shizuku 本身）。 */
    BOUND_SERVICE,
}

/**
 * 权限中心的一行。
 *
 * - [id] 稳定标识，UI 列表顺序也按控制器给的顺序，不要按 title 排序。
 * - [settingsAction] 是 `android.provider.Settings.ACTION_*` 的字符串常量，UI 自己
 *   `Intent(action)`；控制器刻意不返回 Intent 对象，保证本文件零 Android 依赖。
 * - [settingsData] 是给 `Intent.data` 的 `package:...` URI 字符串（可能为 null）。
 * - [permissionNames] 是这一行涉及的完整权限名，方便 UI 展示"到底在要什么"。
 */
data class HighPrivilegePermissionRow(
    val id: String,
    val title: String,
    val detail: String,
    val state: PermissionState,
    val grantRoute: GrantRoute,
    val permissionNames: List<String> = emptyList(),
    val settingsAction: String? = null,
    val settingsData: String? = null,
) {
    val granted: Boolean get() = state == PermissionState.GRANTED
}

/**
 * 一次权限中心快照。所有字段都是"现在这一刻的真实观察"，
 * 不要把旧的 snapshot 当成授权证据复用。
 */
data class HighPrivilegeSnapshot(
    val appPackage: String,
    val appUid: Int,
    /** [me.rerere.rikkahub.data.workspace.WorkspacePrivilegeManager.shizukuStatus] 的原文。 */
    val shizukuStatus: String,
    /** 由上面那串原文推出来的"现在真的能用 Shizuku 跑命令"，不接受任何其它来源。 */
    val shizukuReady: Boolean,
    val permissions: List<HighPrivilegePermissionRow>,
    /** 自动化总开关（默认关）。 */
    val automationEnabled: Boolean,
    /** 自动化里是否允许有副作用的操作（默认关）。 */
    val writeEnabled: Boolean,
    /** UI 里绑定给工具层的 assistant id；null = 谁都没绑定，不是"全部助手"。 */
    val authorizedAssistantId: String?,
    /** 最近一次用户点"授权 Shizuku"拿到的真实 UID（进程重启后为 null，直到再次授权）。 */
    val authorizedUid: Int?,
    /** 给人看的补充说明（Play 约束、OEM 限制、持久化错误等）。 */
    val notes: List<String> = emptyList(),
)

/**
 * 单条开发权限的授予/撤销结果。
 *
 * [granted] 永远是 `PackageManager.checkSelfPermission` 的回读结果，不是 `pm grant` 的退出码。
 * 退出码为 0 但回读仍是 DENIED 时，[granted] = false，[error] 会说明"命令成功但系统没生效"。
 */
data class GrantResult(
    val id: String,
    val permission: String,
    val granted: Boolean,
    val executed: Boolean,
    val exitCode: Int?,
    val command: String,
    val error: String? = null,
)

/** `settings get` 的结果。[value] 为 null 表示系统里没有这个键（或返回 null 字面量）。 */
data class SettingReadResult(
    val namespace: String,
    val key: String,
    val value: String?,
    /** "shizuku" 或 "app"：值到底从哪里读到的。 */
    val via: String,
    val exitCode: Int? = null,
    val error: String? = null,
    /** 键名看起来像密钥；UI 不要把值直接展示出来。 */
    val looksSecret: Boolean = false,
)

/** `settings put` / `settings delete` 的结果，带 old/new 和真实回读校验。 */
data class SettingWriteResult(
    val namespace: String,
    val key: String,
    val oldValue: String?,
    val newValue: String?,
    val verified: Boolean,
    val via: String,
    val exitCode: Int? = null,
    val error: String? = null,
    val looksSecret: Boolean = false,
)

data class InstalledPackageRow(
    val packageName: String,
    val label: String?,
    val uid: Int,
    val system: Boolean,
    val enabled: Boolean,
)

data class DiagnosticResult(
    val kind: String,
    val command: String,
    val exitCode: Int?,
    val output: String,
    val truncated: Boolean,
    val error: String? = null,
)

/** 自动化授权配置（设备本地，默认全关，可整体撤销）。 */
@Serializable
data class HighPrivilegeAutomation(
    val enabled: Boolean = false,
    val allowWrites: Boolean = false,
    /** 绑定给工具层的 assistant id；null 表示"谁都没绑定"，绝不解释成"所有助手"。 */
    val assistantId: String? = null,
    val revision: Int = 0,
)

/** 工具层（主代理适配器）唯一会遇到的门禁异常：不是"没权限弹个框"，而是明确的拒绝。 */
class HighPrivilegeDeniedException(message: String) : IllegalStateException(message)

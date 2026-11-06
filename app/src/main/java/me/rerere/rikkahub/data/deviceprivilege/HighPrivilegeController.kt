package me.rerere.rikkahub.data.deviceprivilege

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.devicecontrol.DeviceAccessibilityService
import me.rerere.rikkahub.data.workspace.WorkspaceAndroidCommandResult
import me.rerere.rikkahub.data.workspace.WorkspaceIdentity
import me.rerere.rikkahub.data.workspace.WorkspacePrivilegeManager
import me.rerere.rikkahub.data.workspace.WorkspacePrivilegeMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Meridian m1 高权限中心的唯一入口。
 *
 * 边界（务必按这个理解去接 UI / 工具）：
 *  - 非 root。这里所有"高权限"只有两个来源：① 用户在系统设置页亲自打开的 special access；
 *    ② 用户按键授权后由 Shizuku 的 shell UID 代跑 `pm grant` / `settings` / `dumpsys` 等 shell 命令。
 *  - 不解锁签名边界：签名专属权限（INJECT_EVENTS / GRANT_RUNTIME_PERMISSIONS / INSTALL_PACKAGES /
 *    REBOOT / 读取别的 App 私有数据 / 截 secure 屏）不在声明里、也不会被声明；SELinux 更不会因为
 *    `pm grant` 而放宽。
 *  - 授权是"用户按键"事件：只有 [authorizeShizuku] 会触发 Shizuku 授权流程；
 *    工具侧的 [runAuthorizedCommand] / [runReadOnly] / [settingsWrite] 永远不会弹框或提权。
 *  - 全新安装默认全关：[snapshot] 里的 `automationEnabled` / `writeEnabled` 在没有用户配置前一直是 false。
 *  - 状态全是真观察：每一行权限都按 PackageManager / AppOps / 各自 special access API 现查，
 *    查不到就是 UNAVAILABLE / DENIED，不做"应该有吧"的假授权。
 *
 * 线程：除 [configureAutomation] 外的方法都是 suspend 并在 IO 上跑。`configureAutomation` 由 UI
 * 点击调用，写入是一份 <4KB 的原子小文件；失败时保持旧状态并把原因放进 `notes`。
 */
class HighPrivilegeController(
    context: Context,
    private val privileges: WorkspacePrivilegeManager,
) {

    private val context: Context = context.applicationContext
    private val store = HighPrivilegeStore(File(this.context.noBackupFilesDir, "deviceprivilege"))
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val loaded = AtomicBoolean(false)
    private val loadLock = Any()
    private val settingWriteMutex = Mutex()

    @Volatile
    private var state: HighPrivilegeAutomation? = null

    @Volatile
    private var lastIdentity: WorkspaceIdentity? = null

    @Volatile
    private var lastAuthorizeError: String? = null

    @Volatile
    private var lastPersistError: String? = null

    // ------------------------------------------------------------------ state

    /** 让 DI 在后台预热一次，避免第一帧快照和磁盘读撞在一起。可选，不调用也正确。 */
    fun warmUp() {
        io.launch { automation() }
    }

    private fun automation(): HighPrivilegeAutomation {
        state?.let { return it }
        synchronized(loadLock) {
            state?.let { return it }
            val loadedState = store.read()
            loaded.set(true)
            state = loadedState
            return loadedState
        }
    }

    /**
     * 持久化自动化授权。**不是**权限授权：这里只决定"工具层能不能用已经拿到的权限"。
     *
     * - `assistantId == null` 表示谁都没绑定 → 工具侧一律拒绝（不是"所有助手都行"）。
     * - `allowWrites` 会被总开关收敛：总开关关着时永远是 false。
     * - 关闭总开关、或换绑 assistant，会立刻 cancel 本控制器名下正在跑的 shell 命令。
     */
    fun configureAutomation(enabled: Boolean, allowWrites: Boolean, assistantId: String?) {
        val revoke = synchronized(loadLock) {
            val current = automation()
            val next = HighPrivilegeAutomation(
                enabled = enabled, allowWrites = allowWrites, assistantId = assistantId,
                revision = current.revision + 1,
            ).normalized()
            val restricting = !next.enabled || (current.allowWrites && !next.allowWrites) ||
                current.assistantId != next.assistantId
            try {
                store.write(next)
                state = next
                lastPersistError = null
            } catch (e: Exception) {
                // Revocation is immediate even when storage fails. Never grant more on a failed write.
                if (restricting) state = HighPrivilegeAutomation(revision = next.revision)
                lastPersistError = "Failed to persist automation settings: ${e.message ?: e.javaClass.simpleName}"
                if (restricting) privileges.cancelOwner(AUTOMATION_OWNER)
                throw e
            }
            restricting
        }
        if (revoke) privileges.cancelOwner(AUTOMATION_OWNER)
    }

    /** Public tool gate. Direct App-granted capabilities do not require a live Shizuku binder. */
    fun checkAutomation(assistantId: String, requiresWrite: Boolean = false, requireShizuku: Boolean = false) {
        val ready = !requireShizuku || HighPrivilegePolicy.shizukuReady(privileges.shizukuStatus())
        val decision = HighPrivilegePolicy.gate(automation(), assistantId, requiresWrite, ready)
        if (!decision.allowed) throw HighPrivilegeDeniedException(decision.message)
    }

    /** 撤销本控制器发起的全部 shell 命令（不动工作区自己的授权与命令）。 */
    fun cancelAutomationCommands() {
        privileges.cancelOwner(AUTOMATION_OWNER)
    }

    // ------------------------------------------------------------------ snapshot

    /**
     * 现查一份真实的权限快照。UI 每次进入页面 / 每次从系统设置返回都该重新调用。
     */
    suspend fun snapshot(): HighPrivilegeSnapshot = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val uid = context.applicationInfo.uid
        val status = runCatching { privileges.shizukuStatus() }
            .getOrElse { "status=UNKNOWN; api=-1; uid=unknown; userServiceBound=false; fallback=none" }
        val ready = HighPrivilegePolicy.shizukuReady(status)
        val current = automation()
        HighPrivilegeSnapshot(
            appPackage = pkg,
            appUid = uid,
            shizukuStatus = status,
            shizukuReady = ready,
            permissions = buildRows(pkg, ready, status),
            automationEnabled = current.enabled,
            writeEnabled = current.enabled && current.allowWrites,
            authorizedAssistantId = current.assistantId,
            authorizedUid = lastIdentity?.uid,
            notes = buildNotes(),
        )
    }

    private fun buildNotes(): List<String> = buildList {
        add("Non-root: shell-level grants only. Signature-only permissions stay impossible and SELinux is unchanged.")
        add("Play-distributed builds must not rely on QUERY_ALL_PACKAGES or READ_LOGS without an approved use case; local/fork builds are unaffected.")
        add("Android 14+ OEMs may additionally block WRITE_SECURE_SETTINGS / development permissions behind their own security policy; the row state is re-read after every grant.")
        lastAuthorizeError?.let { add("Last Shizuku authorization failed: $it") }
        lastPersistError?.let { add(it) }
        store.lastError()?.let { add(it) }
        if (!loaded.get()) add("Automation settings were read for the first time in this snapshot.")
    }

    private fun buildRows(pkg: String, shizukuReady: Boolean, shizukuStatus: String): List<HighPrivilegePermissionRow> {
        val rows = mutableListOf<HighPrivilegePermissionRow>()
        rows += HighPrivilegePermissionRow(
            id = ROW_SHIZUKU,
            title = "Shizuku (shell UID)",
            detail = if (shizukuReady) {
                "Shizuku is serving commands as the shell UID. This is the only channel that can run `pm grant`, `settings` and `dumpsys`."
            } else {
                "Shizuku is not usable right now ($shizukuStatus). Press authorize to grant the Shizuku permission; without it no shell command runs."
            },
            state = if (shizukuReady) PermissionState.GRANTED else PermissionState.DENIED,
            grantRoute = GrantRoute.BOUND_SERVICE,
        )

        rows += developmentRow(
            pkg = pkg,
            id = ROW_WRITE_SECURE_SETTINGS,
            title = "WRITE_SECURE_SETTINGS",
            detail = "Lets this app write protected Settings.Secure/Global/System keys instead of shelling out for every change.",
        )
        rows += developmentRow(
            pkg = pkg,
            id = ROW_DUMP,
            title = "DUMP",
            detail = "Framework dump access (app-scoped dumpsys through the app process, not root).",
        )
        rows += developmentRow(
            pkg = pkg,
            id = ROW_READ_LOGS,
            title = "READ_LOGS",
            detail = "Read the system log buffer without a shell round trip." +
                appOpsHint("android:read_logs"),
        )
        rows += developmentRow(
            pkg = pkg,
            id = ROW_CHANGE_CONFIGURATION,
            title = "CHANGE_CONFIGURATION",
            detail = "Change display/configuration state through framework APIs that would otherwise be shell-only.",
        )

        rows += specialAccessRow(
            pkg = pkg,
            id = ROW_WRITE_SETTINGS,
            title = "WRITE_SETTINGS (modify system settings)",
            permission = "android.permission.WRITE_SETTINGS",
            detail = "Allows Settings.System writes (brightness, timeout, ring mode). A separate switch in system settings.",
            granted = { Settings.System.canWrite(context) },
            action = Settings.ACTION_MANAGE_WRITE_SETTINGS,
            data = "package:$pkg",
        )

        rows += specialAccessRow(
            pkg = pkg,
            id = ROW_NOTIFICATION_POLICY,
            title = "ACCESS_NOTIFICATION_POLICY",
            permission = "android.permission.ACCESS_NOTIFICATION_POLICY",
            detail = "Do-not-disturb / notification policy access. Toggled in the system notification access page.",
            granted = {
                context.getSystemService(NotificationManager::class.java)
                    ?.isNotificationPolicyAccessGranted == true
            },
            action = Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            data = null,
        )

        rows += specialAccessRow(
            pkg = pkg,
            id = ROW_EXACT_ALARMS,
            title = "SCHEDULE_EXACT_ALARM",
            permission = "android.permission.SCHEDULE_EXACT_ALARM",
            detail = "Exact alarm scheduling for timers/reminders. Android 12+ treats it as a revocable toggle.",
            granted = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
                } else {
                    true // API < 31: exact alarms are implicit, nothing to grant.
                }
            },
            action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
            } else {
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            },
            data = "package:$pkg",
        )

        rows += specialAccessRow(
            pkg = pkg,
            id = ROW_IGNORE_BATTERY,
            title = "Ignore battery optimizations",
            permission = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            detail = "Keeps long automation runs alive. Only the user can whitelist the app.",
            granted = {
                context.getSystemService(PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(pkg) == true
            },
            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            data = "package:$pkg",
        )

        rows += specialAccessRow(
            pkg = pkg,
            id = ROW_INSTALL_PACKAGES,
            title = "Install unknown apps",
            permission = "android.permission.REQUEST_INSTALL_PACKAGES",
            detail = "Lets the user hand an APK to the system installer from this app. It does not install silently.",
            granted = { context.packageManager.canRequestPackageInstalls() },
            action = Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            data = "package:$pkg",
        )

        rows += HighPrivilegePermissionRow(
            id = ROW_QUERY_ALL_PACKAGES,
            title = "QUERY_ALL_PACKAGES",
            detail = "Package visibility for the on-device automation tools ($pkg). Granted at install time; " +
                "Play policy requires a declared automation use case, local builds are unaffected.",
            state = when {
                !isDeclared("android.permission.QUERY_ALL_PACKAGES") -> PermissionState.UNAVAILABLE
                selfGranted("android.permission.QUERY_ALL_PACKAGES") -> PermissionState.GRANTED
                else -> PermissionState.DENIED
            },
            grantRoute = GrantRoute.RUNTIME,
            permissionNames = listOf("android.permission.QUERY_ALL_PACKAGES"),
        )

        rows += specialAccessRow(
            pkg = pkg,
            id = ROW_ALL_FILES,
            title = "All files access",
            permission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            detail = "Shared-storage access for the workspace mounts. It never exposes other apps' private data.",
            granted = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    selfGranted("android.permission.WRITE_EXTERNAL_STORAGE")
                }
            },
            action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
            } else {
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            },
            data = "package:$pkg",
        )

        rows += HighPrivilegePermissionRow(
            id = ROW_ACCESSIBILITY,
            title = "Accessibility service",
            detail = if (accessibilityEnabled()) {
                "The device-control accessibility service is enabled in system settings."
            } else {
                "The device-control accessibility service is not enabled. Only the user can tick it in system settings; this app never does."
            },
            state = if (accessibilityEnabled()) PermissionState.GRANTED else PermissionState.DENIED,
            grantRoute = GrantRoute.BOUND_SERVICE,
            permissionNames = listOf("android.permission.BIND_ACCESSIBILITY_SERVICE"),
            settingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS,
        )

        rows += HighPrivilegePermissionRow(
            id = ROW_USAGE_STATS,
            title = "Usage access",
            detail = when {
                !isDeclared("android.permission.PACKAGE_USAGE_STATS") ->
                    "Usage access is not declared in the merged manifest, so the system switch cannot appear."
                usageAccessGranted() -> "Foreground-app / usage statistics access via AppOps. Currently granted."
                else -> "Foreground-app / usage statistics access via AppOps. Open the system usage-access page and enable it yourself."
            },
            state = when {
                !isDeclared("android.permission.PACKAGE_USAGE_STATS") -> PermissionState.UNAVAILABLE
                usageAccessGranted() -> PermissionState.GRANTED
                else -> PermissionState.DENIED
            },
            grantRoute = GrantRoute.SYSTEM_SETTINGS,
            permissionNames = listOf("android.permission.PACKAGE_USAGE_STATS"),
            settingsAction = Settings.ACTION_USAGE_ACCESS_SETTINGS,
        )

        val hasListener = hasNotificationListenerComponent()
        rows += HighPrivilegePermissionRow(
            id = ROW_NOTIFICATION_LISTENER,
            title = "Notification listener",
            detail = if (!hasListener) {
                "This build ships no NotificationListenerService, so there is nothing to grant and nothing is reported as granted."
            } else if (notificationListenerEnabled(pkg)) {
                "The notification listener component is enabled in system settings."
            } else {
                "The notification listener component exists but is not enabled in system settings."
            },
            state = when {
                !hasListener -> PermissionState.UNAVAILABLE
                notificationListenerEnabled(pkg) -> PermissionState.GRANTED
                else -> PermissionState.DENIED
            },
            grantRoute = GrantRoute.BOUND_SERVICE,
            permissionNames = listOf("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"),
            settingsAction = if (hasListener) SETTINGS_ACTION_NOTIFICATION_LISTENER else null,
        )

        return rows
    }

    private fun developmentRow(
        pkg: String,
        id: String,
        title: String,
        detail: String,
    ): HighPrivilegePermissionRow {
        val permission = HighPrivilegePolicy.developmentPermission(id) ?: error("unknown development permission $id")
        val declared = isDeclared(permission)
        val granted = selfGranted(permission)
        val state = HighPrivilegePolicy.developmentRowState(declared, granted)
        val effectiveDetail = when (state) {
            PermissionState.UNAVAILABLE ->
                "$detail Not declared in the merged manifest, so `pm grant` cannot apply; nothing is claimed."
            PermissionState.GRANTED -> "$detail Currently granted."
            else ->
                "$detail Declared but not granted. Use the grant button (Shizuku shell `pm grant`) or copy the ADB command."
        }
        return HighPrivilegePermissionRow(
            id = id,
            title = title,
            detail = effectiveDetail,
            state = state,
            grantRoute = GrantRoute.ADB_SHIZUKU,
            permissionNames = listOf(permission),
        )
    }

    private fun specialAccessRow(
        pkg: String,
        id: String,
        title: String,
        permission: String,
        detail: String,
        granted: () -> Boolean,
        action: String,
        data: String?,
    ): HighPrivilegePermissionRow {
        val declared = isDeclared(permission)
        val isGranted = declared && runCatching { granted() }.getOrDefault(false)
        return HighPrivilegePermissionRow(
            id = id,
            title = title,
            detail = when {
                !declared -> "$detail Not declared in the merged manifest; the system switch cannot appear."
                isGranted -> "$detail Currently granted."
                else -> "$detail Open the system settings page and enable it yourself."
            },
            state = when {
                !declared -> PermissionState.UNAVAILABLE
                isGranted -> PermissionState.GRANTED
                else -> PermissionState.DENIED
            },
            grantRoute = GrantRoute.SYSTEM_SETTINGS,
            permissionNames = listOf(permission),
            settingsAction = if (declared) action else null,
            settingsData = if (declared) data else null,
        )
    }

    private fun appOpsHint(op: String): String = runCatching {
        val manager = context.getSystemService(android.app.AppOpsManager::class.java) ?: return ""
        val uid = context.applicationInfo.uid
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            manager.unsafeCheckOpNoThrow(op, uid, context.packageName)
        } else {
            manager.checkOpNoThrow(op, uid, context.packageName)
        }
        val label = when (mode) {
            android.app.AppOpsManager.MODE_ALLOWED -> "allowed"
            android.app.AppOpsManager.MODE_IGNORED -> "ignored"
            android.app.AppOpsManager.MODE_DEFAULT -> "default"
            else -> mode.toString()
        }
        "; app-op $op=$label"
    }.getOrDefault("; app-op $op=unknown")

    private fun usageAccessGranted(): Boolean = runCatching {
        val manager = context.getSystemService(android.app.AppOpsManager::class.java) ?: return false
        val uid = context.applicationInfo.uid
        val op = android.app.AppOpsManager.OPSTR_GET_USAGE_STATS
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            manager.unsafeCheckOpNoThrow(op, uid, context.packageName)
        } else {
            manager.checkOpNoThrow(op, uid, context.packageName)
        }
        mode == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun isDeclared(permission: String): Boolean = runCatching {        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.contains(permission) == true
    }.getOrDefault(false)

    private fun selfGranted(permission: String): Boolean = runCatching {
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun accessibilityEnabled(): Boolean = runCatching {
        val expected = ComponentName(context, DeviceAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            if (entry.equals(expected.flattenToString(), ignoreCase = true) ||
                entry.equals(expected.flattenToShortString(), ignoreCase = true)
            ) {
                return true
            }
        }
        false
    }.getOrDefault(false)

    private fun hasNotificationListenerComponent(): Boolean = runCatching {
        context.packageManager.queryIntentServices(
            Intent("android.service.notification.NotificationListenerService").setPackage(context.packageName),
            0,
        ).isNotEmpty()
    }.getOrDefault(false)

    private fun notificationListenerEnabled(pkg: String): Boolean = runCatching {
        val listeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(listeners)
        for (entry in splitter) {
            if (entry.contains(pkg, ignoreCase = true)) return true
        }
        false
    }.getOrDefault(false)

    // ------------------------------------------------------------------ user action

    /**
     * **只能由用户按键调用**：会走 Shizuku 的授权流程（可能弹 Shizuku 的确认框）。
     *
     * 固定 SHIZUKU 通道，不 fallback 到 ROOT / APP；返回的是 Shizuku 报出来的真实 UID。
     */
    suspend fun authorizeShizuku(): HighPrivilegeSnapshot {
        lastAuthorizeError = try {
            val identity = privileges.requestAuthorization(WorkspacePrivilegeMode.SHIZUKU)
            lastIdentity = identity
            null
        } catch (t: Throwable) {
            lastIdentity = null
            t.message ?: t.javaClass.simpleName
        }
        return snapshot()
    }

    // ------------------------------------------------------------------ development permissions

    /** 复制给用户的 ADB 命令（不执行）。 */
    fun adbCommands(ids: Set<String>): List<String> =
        HighPrivilegePolicy.adbCommands(context.packageName, ids)

    fun adbRevokeCommands(ids: Set<String>): List<String> =
        HighPrivilegePolicy.adbRevokeCommands(context.packageName, ids)

    /**
     * 通过 Shizuku 的 shell UID 授权四项开发权限（WRITE_SECURE_SETTINGS / DUMP / READ_LOGS /
     * CHANGE_CONFIGURATION）。白名单外的 id 直接返回失败，不会"猜一个权限"。
     *
     * 每一项只执行一次命令，绝不盲重试；最终结果以 `checkSelfPermission` 回读为准。
     * 退出码为 0 但没生效时 `granted=false` 且 `error` 说明是系统/OEM 没放行。
     */
    suspend fun grantDevelopmentPermissions(ids: Set<String>): List<GrantResult> = withContext(Dispatchers.IO) {
        ids.map { id -> setDevelopmentPermission(id, revoke = false) }
    }

    /** 与授予同一份白名单，可完整撤销（回读确认真的没了）。 */
    suspend fun revokeDevelopmentPermissions(ids: Set<String>): List<GrantResult> = withContext(Dispatchers.IO) {
        ids.map { id -> setDevelopmentPermission(id, revoke = true) }
    }

    private suspend fun setDevelopmentPermission(rawId: String, revoke: Boolean): GrantResult {
        val pkg = context.packageName
        val permission = HighPrivilegePolicy.developmentPermission(rawId)
            ?: return GrantResult(
                id = rawId,
                permission = "",
                granted = false,
                executed = false,
                exitCode = null,
                command = "",
                error = "Only ${HighPrivilegePolicy.developmentPermissionIds.joinToString(", ")} can be granted here",
            )
        val presented = if (revoke) {
            "adb shell pm revoke $pkg $permission"
        } else {
            "adb shell pm grant $pkg $permission"
        }
        if (!isDeclared(permission)) {
            return GrantResult(
                id = rawId,
                permission = permission,
                granted = false,
                executed = false,
                exitCode = null,
                command = presented,
                error = "The merged manifest does not declare $permission, so pm grant cannot apply",
            )
        }
        val before = selfGranted(permission)
        if (!revoke && before) {
            return GrantResult(rawId, permission, granted = true, executed = false, exitCode = 0, command = presented)
        }
        if (revoke && !before) {
            return GrantResult(rawId, permission, granted = false, executed = false, exitCode = 0, command = presented)
        }
        val command = runCatching {
            if (revoke) {
                HighPrivilegePolicy.revokeCommand(pkg, permission)
            } else {
                HighPrivilegePolicy.grantCommand(pkg, permission)
            }
        }.getOrElse { return GrantResult(rawId, permission, before, false, null, presented, it.message) }

        val outcome = try {
            privileges.execute(
                mode = WorkspacePrivilegeMode.SHIZUKU,
                command = command,
                cwd = "/",
                timeoutMillis = 20_000L,
                ownerKey = GRANT_OWNER,
            )
        } catch (t: Throwable) {
            return GrantResult(
                id = rawId,
                permission = permission,
                granted = before,
                executed = true,
                exitCode = null,
                command = presented,
                error = t.message ?: t.javaClass.simpleName,
            )
        }

        val after = selfGranted(permission)
        val succeeded = if (revoke) !after else after
        val exitCode = outcome.result.exitCode
        val error = if (succeeded) {
            null
        } else {
            val stderr = HighPrivilegePolicy.bound(outcome.result.stderr.trim(), 400).first
            val stdout = HighPrivilegePolicy.bound(outcome.result.stdout.trim(), 400).first
            buildString {
                append("pm exited with $exitCode but the permission state did not change")
                if (stderr.isNotEmpty()) append("; stderr: ").append(stderr)
                if (stdout.isNotEmpty()) append("; stdout: ").append(stdout)
                append(". Some OEM builds refuse development permissions even from the shell UID.")
            }
        }
        return GrantResult(
            id = rawId,
            permission = permission,
            granted = after,
            executed = true,
            exitCode = exitCode,
            command = presented,
            error = error,
        )
    }

    // ------------------------------------------------------------------ automation (tool side)

    /** 只读探测的固定清单（UI 可列出来），不接任意参数。 */
    fun readOnlyCommandIds(): List<String> = HighPrivilegePolicy.READ_ONLY_COMMANDS.map { it.id }

    /**
     * 工具层：执行一条已授权的 shell 命令。
     *
     * 要求：用户已打开自动化总开关 + 已开启写操作 + 当前助手正好是 UI 绑定的 assistant + Shizuku 现在真的可用。
     * 任意 shell 命令一律按"有副作用"处理（不做关键词分类），所以 [requiresWrite] 恒为 true。
     * 这里**不会**弹任何权限框，也不会 fallback 到 APP/ROOT。
     */
    suspend fun runAuthorizedCommand(
        assistantId: String,
        command: String,
        timeoutSeconds: Int = HighPrivilegePolicy.DEFAULT_TIMEOUT_SECONDS,
    ): WorkspaceAndroidCommandResult {
        HighPrivilegePolicy.validateCommand(command)
        val decision = gateOrThrow(assistantId, requiresWrite = true)
        check(decision.allowed)
        return privileges.execute(
            mode = WorkspacePrivilegeMode.SHIZUKU,
            command = command,
            cwd = "/",
            timeoutMillis = HighPrivilegePolicy.clampTimeoutSeconds(timeoutSeconds),
            ownerKey = AUTOMATION_OWNER,
        )
    }

    /**
     * 工具层：跑一条只读探测（命令来自固定清单，不接受调用方拼命令）。
     * 只需要总开关 + 绑定的 assistant + Shizuku 可用，不需要写权限。
     */
    suspend fun runReadOnly(
        assistantId: String,
        id: String,
        timeoutSeconds: Int = 20,
    ): WorkspaceAndroidCommandResult {
        val spec = HighPrivilegePolicy.readOnly(id)
            ?: throw HighPrivilegeDeniedException(
                "Unknown read-only probe '$id'; available: ${readOnlyCommandIds().joinToString(", ")}",
            )
        gateOrThrow(assistantId, requiresWrite = false)
        return privileges.execute(
            mode = WorkspacePrivilegeMode.SHIZUKU,
            command = spec.command,
            cwd = "/",
            timeoutMillis = HighPrivilegePolicy.clampTimeoutSeconds(timeoutSeconds),
            ownerKey = AUTOMATION_OWNER,
        )
    }

    private fun gateOrThrow(assistantId: String, requiresWrite: Boolean): HighPrivilegePolicy.GateDecision {
        val status = runCatching { privileges.shizukuStatus() }.getOrDefault("")
        val ready = HighPrivilegePolicy.shizukuReady(status)
        val decision = HighPrivilegePolicy.gate(
            state = automation(),
            assistantId = assistantId,
            requiresWrite = requiresWrite,
            shizukuReady = ready,
        )
        if (!decision.allowed) throw HighPrivilegeDeniedException(decision.message)
        return decision
    }

    // ------------------------------------------------------------------ settings

    /**
     * 读一个 setting。优先用 Shizuku 的 `settings get`（和系统自己的命名空间一致），
     * Shizuku 不可用时退到 App 自己的 Settings API 读取（读不需要 WSS，但受保护的键会抛异常）。
     */
    suspend fun settingsRead(namespace: String, key: String): SettingReadResult = withContext(Dispatchers.IO) {
        val setting = HighPrivilegePolicy.validateSetting(namespace, key, null)
        val looksSecret = HighPrivilegePolicy.looksSecret(key)
        val ready = runCatching { privileges.shizukuStatus() }.getOrDefault("").let(HighPrivilegePolicy::shizukuReady)
        if (ready) {
            val outcome = runCatching {
                privileges.execute(
                    mode = WorkspacePrivilegeMode.SHIZUKU,
                    command = HighPrivilegePolicy.settingsGetCommand(setting.namespace, setting.key),
                    cwd = "/",
                    timeoutMillis = 20_000L,
                    ownerKey = AUTOMATION_OWNER,
                )
            }.getOrNull()
            if (outcome != null) {
                val raw = outcome.result.stdout.trim()
                val value = when {
                    raw.isEmpty() || raw == "null" -> null
                    else -> HighPrivilegePolicy.bound(raw, 4096).first
                }
                return@withContext SettingReadResult(
                    namespace = setting.namespace.cli,
                    key = setting.key,
                    value = value,
                    via = "shizuku",
                    exitCode = outcome.result.exitCode,
                    error = if (outcome.result.exitCode == 0) null else HighPrivilegePolicy.bound(outcome.result.stderr.trim(), 400).first,
                    looksSecret = looksSecret,
                )
            }
        }
        val value = runCatching { appSettingValue(setting.namespace, setting.key) }
        SettingReadResult(
            namespace = setting.namespace.cli,
            key = setting.key,
            value = value.getOrNull()?.let { HighPrivilegePolicy.bound(it, 4096).first },
            via = "app",
            exitCode = null,
            error = value.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
            looksSecret = looksSecret,
        )
    }

    /**
     * 写 / 删一个 setting。允许任意合法 key（不做关键词黑名单——用户授权的就是"能改设置"这件事），
     * 但每条命令只执行一次，之后回读 old/new 并给出 `verified`；未知副作用不会被重放。
     *
     * 需要：自动化总开关 + 写权限 + 绑定的 assistant + Shizuku 可用。
     */
    suspend fun settingsWrite(
        assistantId: String,
        namespace: String,
        key: String,
        value: String?,
    ): SettingWriteResult = withContext(Dispatchers.IO) {
        settingWriteMutex.withLock {
            val setting = HighPrivilegePolicy.validateSetting(namespace, key, value)
            checkAutomation(assistantId, requiresWrite = true)
            val secret = HighPrivilegePolicy.looksSecret(key)
            val direct = selfGranted("android.permission.WRITE_SECURE_SETTINGS") ||
                (setting.namespace == HighPrivilegePolicy.SettingsNamespace.SYSTEM &&
                    Settings.System.canWrite(context))
            val via = if (direct) "app-granted" else "shizuku"
            val before = settingsRead(namespace, key)
            if (before.error != null) return@withLock SettingWriteResult(
                namespace, key, null, null, false, via, error = "Cannot read the old value: ${before.error}",
                looksSecret = secret,
            )
            // Revocation may arrive during the read. Check again immediately before the one write.
            currentCoroutineContext().ensureActive()
            checkAutomation(assistantId, requiresWrite = true, requireShizuku = !direct)
            var exit: Int? = null
            var failure: String? = null
            try {
                if (direct) {
                    val written = when (setting.namespace) {
                        HighPrivilegePolicy.SettingsNamespace.SYSTEM -> Settings.System.putString(context.contentResolver, key, value)
                        HighPrivilegePolicy.SettingsNamespace.SECURE -> Settings.Secure.putString(context.contentResolver, key, value)
                        HighPrivilegePolicy.SettingsNamespace.GLOBAL -> Settings.Global.putString(context.contentResolver, key, value)
                    }
                    exit = if (written) 0 else 1
                    if (!written) failure = "The system rejected the settings write"
                } else {
                    val command = if (value == null) HighPrivilegePolicy.settingsDeleteCommand(setting.namespace, key)
                        else HighPrivilegePolicy.settingsPutCommand(setting)
                    val outcome = privileges.execute(
                        mode = WorkspacePrivilegeMode.SHIZUKU, command = command, cwd = "/",
                        timeoutMillis = 20_000L, ownerKey = AUTOMATION_OWNER,
                    )
                    exit = outcome.result.exitCode
                    if (exit != 0) failure = HighPrivilegePolicy.bound(outcome.result.stderr.trim(), 400).first
                        .ifBlank { "settings exited with $exit" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An attempted write is never replayed via a different identity.
                failure = e.message ?: e.javaClass.simpleName
            }
            val after = settingsRead(namespace, key)
            val verified = failure == null && exit == 0 && after.error == null && after.value == value
            SettingWriteResult(
                namespace = namespace, key = key, oldValue = before.value, newValue = after.value,
                verified = verified, via = via, exitCode = exit,
                error = if (verified) null else failure ?: after.error ?: "The readback does not match the requested value",
                looksSecret = secret,
            )
        }
    }

    private fun appSettingValue(namespace: HighPrivilegePolicy.SettingsNamespace, key: String): String? =
        when (namespace) {
            HighPrivilegePolicy.SettingsNamespace.SYSTEM -> Settings.System.getString(context.contentResolver, key)
            HighPrivilegePolicy.SettingsNamespace.SECURE -> Settings.Secure.getString(context.contentResolver, key)
            HighPrivilegePolicy.SettingsNamespace.GLOBAL -> Settings.Global.getString(context.contentResolver, key)
        }

    // ------------------------------------------------------------------ packages / diagnostics

    /** 已安装包（QUERY_ALL_PACKAGES 生效时是全部，否则只有可见的那部分）。有条数上限。 */
    suspend fun listInstalledPackages(
        includeSystem: Boolean = false,
        limit: Int = 300,
    ): List<InstalledPackageRow> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val capped = limit.coerceIn(1, 1000)
        val packages = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        packages.asSequence()
            .mapNotNull { info ->
                val app = info.applicationInfo ?: return@mapNotNull null
                info to app
            }
            .filter { (_, app) -> includeSystem || !app.flagSystem() }
            .sortedBy { (info, _) -> info.packageName }
            .take(capped)
            .map { (info, app) ->
                InstalledPackageRow(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull(),
                    uid = app.uid,
                    system = app.flagSystem(),
                    enabled = app.enabled,
                )
            }
            .toList()
    }

    /** 可跑的诊断种类：`logcat`，或 dumpsys 白名单（activity/windows/battery/package/notification）。 */
    fun diagnosticsKinds(): List<String> = listOf("logcat") + HighPrivilegePolicy.DUMP_TARGETS.keys

    /**
     * 有界诊断：`logcat -d` 快照或白名单 dumpsys，输出截断到 [HighPrivilegePolicy.MAX_OUTPUT_CHARS]。
     * 只读，但同样要总开关 + 绑定的 assistant + Shizuku 可用。读到的内容不会自动外发。
     */
    suspend fun diagnostics(
        assistantId: String,
        kind: String,
        timeoutSeconds: Int = 20,
    ): DiagnosticResult = withContext(Dispatchers.IO) {
        val command = if (kind == "logcat") {
            HighPrivilegePolicy.logcatCommand()
        } else {
            HighPrivilegePolicy.DUMP_TARGETS[kind]
                ?: throw HighPrivilegeDeniedException(
                    "Unknown diagnostic '$kind'; available: ${diagnosticsKinds().joinToString(", ")}",
                )
        }
        gateOrThrow(assistantId, requiresWrite = false)
        val outcome = try {
            privileges.execute(
                mode = WorkspacePrivilegeMode.SHIZUKU,
                command = command,
                cwd = "/",
                timeoutMillis = HighPrivilegePolicy.clampTimeoutSeconds(timeoutSeconds),
                ownerKey = AUTOMATION_OWNER,
            )
        } catch (t: Throwable) {
            return@withContext DiagnosticResult(
                kind = kind,
                command = command,
                exitCode = null,
                output = "",
                truncated = false,
                error = t.message ?: t.javaClass.simpleName,
            )
        }
        val (bounded, truncated) = HighPrivilegePolicy.bound(outcome.result.stdout)
        DiagnosticResult(
            kind = kind,
            command = command,
            exitCode = outcome.result.exitCode,
            output = bounded,
            truncated = truncated,
            error = if (outcome.result.exitCode == 0) null
            else HighPrivilegePolicy.bound(outcome.result.stderr.trim(), 400).first,
        )
    }

    private fun android.content.pm.ApplicationInfo.flagSystem(): Boolean =
        (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

    private companion object {
        /** 本控制器自己的命令归属，撤销只动这一组，绝不动工作区授权。 */
        const val AUTOMATION_OWNER = "deviceprivilege:automation"
        const val GRANT_OWNER = "deviceprivilege:grant"

        const val ROW_SHIZUKU = "shizuku"
        const val ROW_WRITE_SECURE_SETTINGS = "WRITE_SECURE_SETTINGS"
        const val ROW_DUMP = "DUMP"
        const val ROW_READ_LOGS = "READ_LOGS"
        const val ROW_CHANGE_CONFIGURATION = "CHANGE_CONFIGURATION"
        const val ROW_WRITE_SETTINGS = "write_settings"
        const val ROW_NOTIFICATION_POLICY = "notification_policy"
        const val ROW_EXACT_ALARMS = "exact_alarms"
        const val ROW_IGNORE_BATTERY = "ignore_battery"
        const val ROW_INSTALL_PACKAGES = "install_packages"
        const val ROW_QUERY_ALL_PACKAGES = "query_all_packages"
        const val ROW_ALL_FILES = "all_files_access"
        const val ROW_USAGE_STATS = "usage_access"
        const val ROW_ACCESSIBILITY = "accessibility"
        const val ROW_NOTIFICATION_LISTENER = "notification_listener"

        const val SETTINGS_ACTION_NOTIFICATION_LISTENER = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
    }
}

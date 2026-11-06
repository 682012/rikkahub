# Meridian m1 高权限中心（deviceprivilege）API 契约

> 状态：实现已完成（源码 + 纯 JVM 单测）。本文是**主代理接 DI、UI/工具层接入**的唯一口径。
> 包名：`me.rerere.rikkahub.data.deviceprivilege`（源码在
> `app/src/main/java/me/rerere/rikkahub/data/deviceprivilege/`，单测在 `app/src/test/.../data/deviceprivilege/`）。
> 原 `AndroidManifest.xml` 单文件备份：`privileges/backup/AndroidManifest.xml`。

---

## 0. 硬边界（先读这段，别把它读成"可以再往上要一点"）

| 能做 | 不能做 |
| --- | --- |
| 用户按键授权 Shizuku 后，用 **shell UID（2000；Shizuku 以 root 启动时是 0）** 跑 `pm grant` / `settings` / `dumpsys` / `logcat` / 任意 shell | 拿签名专属权限：`INJECT_EVENTS`、`GRANT_RUNTIME_PERMISSIONS`、`INSTALL_PACKAGES`、`REBOOT`、`READ_FRAME_BUFFER` 等（声明了也无效，本仓库**不声明**） |
| 用户自己在系统设置里打开的 special app access（WRITE_SETTINGS、免打扰、精确闹钟、电池优化白名单、未知来源、全文件访问） | 读别的 App 私有数据（`/data/data/<other>`）、截 secure 屏、静默给「所有 App」授运行时权限 |
| 用 `pm grant` 授予四项 **development** 权限：`WRITE_SECURE_SETTINGS` / `DUMP` / `READ_LOGS` / `CHANGE_CONFIGURATION` | 改 SELinux 策略、变成系统 App、绕过 `prot` 分区/签名校验 |
| 状态按 PackageManager / AppOps / 各 special access API **现查**，拿不到就报 UNAVAILABLE/DENIED | 「应该有吧」式的假授权；把 `pm grant` 退出码 0 当成生效（必须回读） |

**非 root。** 上面"能做"一列全部建立在用户已经按键授权 Shizuku、或用户自己在系统设置里点开开关之上；
没有 Shizuku 时本模块所有执行类方法都直接失败，**不 fallback 到 APP/ROOT**。

---

## 1. 类与文件

| 文件 | 内容 |
| --- | --- |
| `HighPrivilegeModels.kt` | 纯 DTO/enum（零 Android 依赖）：`HighPrivilegeSnapshot`、`HighPrivilegePermissionRow`、`PermissionState`、`GrantRoute`、`GrantResult`、`SettingReadResult`、`SettingWriteResult`、`InstalledPackageRow`、`DiagnosticResult`、`HighPrivilegeAutomation`、`HighPrivilegeDeniedException` |
| `HighPrivilegePolicy.kt` | 纯策略（零 Android 依赖）：开发权限白名单、shell 单引号转义、settings 校验、命令拼装、`adbCommands`、UID 解析、输出截断、工具层门禁 `gate(...)`、只读命令/`dumpsys` 白名单 |
| `HighPrivilegeStore.kt` | 授权配置持久化（`noBackupFilesDir/deviceprivilege/automation.json`，原子写，损坏即全关），`HighPrivilegeAutomation.normalized()` |
| `HighPrivilegeController.kt` | Android 粘合层，见下 |

构造：`HighPrivilegeController(context: Context, privileges: WorkspacePrivilegeManager)`。
DI 建议与 `WorkspacePrivilegeManager` 同一个 `single { }` 里取（`RepositoryModule.kt` 已有
`single { WorkspacePrivilegeManager(get()) }`）。控制器**不**接管 `WorkspacePrivilegeManager` 的生命周期：
不要在这里调 `privileges.close()`，否则工作区自己的命令会被一起关掉。

---

## 2. 固定 API（签名不可改）

```kotlin
class HighPrivilegeController(context: Context, privileges: WorkspacePrivilegeManager) {

    fun warmUp()                                                        // 可选：DI 里后台预热一次

    // 快照：UI 每次进入页面 / 每次从系统设置返回都重新调
    suspend fun snapshot(): HighPrivilegeSnapshot

    // 用户按钮：唯一的 Shizuku 授权入口（可能弹 Shizuku 确认框）
    suspend fun authorizeShizuku(): HighPrivilegeSnapshot

    // 自动化授权（工具层能不能用），持久化；assistantId 为 null = 谁都没绑定
    fun configureAutomation(enabled: Boolean, allowWrites: Boolean, assistantId: String?)
    fun cancelAutomationCommands()

    // 四项开发权限：白名单固定，每项只跑一次命令，结果以 checkSelfPermission 回读为准
    suspend fun grantDevelopmentPermissions(ids: Set<String>): List<GrantResult>
    suspend fun revokeDevelopmentPermissions(ids: Set<String>): List<GrantResult>
    fun adbCommands(ids: Set<String>): List<String>            // 给用户复制，不执行
    fun adbRevokeCommands(ids: Set<String>): List<String>

    // 工具层执行（不弹框、不提权、不 fallback）
    suspend fun runAuthorizedCommand(
        assistantId: String,
        command: String,
        timeoutSeconds: Int = 30,                               // clamp 1..600
    ): WorkspaceAndroidCommandResult

    suspend fun runReadOnly(
        assistantId: String,
        id: String,
        timeoutSeconds: Int = 20,
    ): WorkspaceAndroidCommandResult
    fun readOnlyCommandIds(): List<String>

    // 设置读写
    suspend fun settingsRead(namespace: String, key: String): SettingReadResult
    suspend fun settingsWrite(
        assistantId: String,
        namespace: String,
        key: String,
        value: String?,                                         // null = settings delete
    ): SettingWriteResult

    // 其它
    suspend fun listInstalledPackages(includeSystem: Boolean = false, limit: Int = 300): List<InstalledPackageRow>
    fun diagnosticsKinds(): List<String>                        // "logcat" + activity/windows/battery/package/notification
    suspend fun diagnostics(assistantId: String, kind: String, timeoutSeconds: Int = 20): DiagnosticResult
}
```

### 2.1 `snapshot()` 字段

`appPackage`、`appUid`、`shizukuStatus`（`WorkspacePrivilegeManager.shizukuStatus()` 原文）、
`shizukuReady`（由原文 `status=READY` 推出，**没有第二个来源**）、`permissions`、
`automationEnabled`、`writeEnabled`（= `enabled && allowWrites`）、`authorizedAssistantId`（null = 未绑定）、
`authorizedUid`（最近一次 `authorizeShizuku()` 的真实 UID，进程重启后为 null）、`notes`。

`permissions` 顺序固定（UI 按数组顺序渲染，别排序）：

1. `shizuku` → `BOUND_SERVICE`
2. `WRITE_SECURE_SETTINGS` → `ADB_SHIZUKU`
3. `DUMP` → `ADB_SHIZUKU`
4. `READ_LOGS` → `ADB_SHIZUKU`
5. `CHANGE_CONFIGURATION` → `ADB_SHIZUKU`
6. `write_settings` → `SYSTEM_SETTINGS`（`Settings.System.canWrite`）
7. `notification_policy` → `SYSTEM_SETTINGS`（`NotificationManager.isNotificationPolicyAccessGranted`）
8. `exact_alarms` → `SYSTEM_SETTINGS`（`AlarmManager.canScheduleExactAlarms`，API<31 视为已放开）
9. `ignore_battery` → `SYSTEM_SETTINGS`（`PowerManager.isIgnoringBatteryOptimizations`）
10. `install_packages` → `SYSTEM_SETTINGS`（`PackageManager.canRequestPackageInstalls`）
11. `query_all_packages` → `RUNTIME`（manifest 授予；Play 分发需申报用途）
12. `all_files_access` → `SYSTEM_SETTINGS`（`Environment.isExternalStorageManager`，API<30 退化为 WRITE_EXTERNAL_STORAGE）
13. `accessibility` → `BOUND_SERVICE`（`Settings.Secure.enabled_accessibility_services` 里是否有本 App 的服务）
14. `usage_access` → `SYSTEM_SETTINGS`（AppOps `android:get_usage_stats`，`PACKAGE_USAGE_STATS` 原本就在 manifest 里）
15. `notification_listener` → `BOUND_SERVICE`（本 build 没有 `NotificationListenerService` 组件时**恒为 UNAVAILABLE**，不假报）

每行：`id, title, detail, state, grantRoute, permissionNames, settingsAction, settingsData`。
`state ∈ {GRANTED, DENIED, UNAVAILABLE, SYSTEM_ONLY}`：`UNAVAILABLE` = manifest 没声明 / 系统版本不支持 / 功能没实现；
`SYSTEM_ONLY` 保留给"只有系统或签名 App 能持有"的项（当前没有行使用它，因为这类权限我们不声明）。

UI 对 `SYSTEM_SETTINGS` 行：`Intent(row.settingsAction).apply { row.settingsData?.let { data = Uri.parse(it) } }`，
控制器刻意只给字符串，保持 DTO 零 Android 依赖。所有系统设置页都需要 `ActivityNotFoundException` 兜底。

### 2.2 `authorizeShizuku()`

- **只能绑到用户按钮**。它会走 `WorkspacePrivilegeManager.requestAuthorization(WorkspacePrivilegeMode.SHIZUKU)`，
  可能弹 Shizuku 的授权框。
- 固定 SHIZUKU，不 fallback ROOT/APP；成功后 `authorizedUid` 是 Shizuku 报的真实 UID（0 就是 0，2000 就是 2000）。
- 失败不抛异常：原因进 `notes`（`Last Shizuku authorization failed: …`），`shizukuReady` 保持 false。

### 2.3 `grantDevelopmentPermissions` / `revokeDevelopmentPermissions`

- `ids` 只认 `WRITE_SECURE_SETTINGS`、`DUMP`、`READ_LOGS`、`CHANGE_CONFIGURATION`（短名或全名都行）。
  其它 id 返回 `GrantResult(executed=false, granted=false, error="Only … can be granted here")`，**不会**猜一个权限去授。
- 包名固定用 `context.packageName`（本仓库 `applicationId = me.rerere.rikkahub.fork`，debug 带 `.debug` 后缀），
  **不要**用 `BuildConfig.APPLICATION_ID`。
- 命令：`pm grant --user current '<pkg>' '<permission>'`（值全部 POSIX 单引号转义），撤销同款 `pm revoke`。
- 每项**只执行一次**，不盲重试；最终 `granted` = `PackageManager.checkSelfPermission` 回读。
  退出码 0 但没变化 → `granted=false` + `error` 写明"命令成功但系统没生效，部分 OEM 即使 shell UID 也会拒绝"。
- `command` 字段永远是给用户看的 `adb shell pm grant …` 形式；`executed` 表示这次是否真的跑了 shell。
- 撤销：`revokeDevelopmentPermissions` 同一白名单；`granted=false && error==null` 表示"确实已经撤销"。
- 已授予时 `granted=true, executed=false`（幂等，不重复跑命令）。

### 2.4 工具层执行：`runAuthorizedCommand`

按顺序检查，任一不满足抛 `HighPrivilegeDeniedException`（消息可直接给用户看）：

1. `automationEnabled`（默认 false）
2. `authorizedAssistantId` 非空且**完全等于** `assistantId`（null ≠ 所有助手）
3. `writeEnabled`：任意 shell 命令一律当"有副作用"，不做关键词分类
4. `shizukuReady`（现查 `shizukuStatus()`）

然后 `WorkspacePrivilegeManager.execute(SHIZUKU, command, cwd="/", timeout, ownerKey="deviceprivilege:automation")`。
命令只做非空/限长(32K)/无 NUL 校验；**没有权限提示、没有提权、没有 fallback**。

`runReadOnly` 走固定清单（`device_model`、`android_version`、`sdk_level`、`battery`、`uptime`、`storage_data`、
`packages_third_party`、`input_methods`），不需要写权限。
`diagnostics` = `logcat -d -v threadtime -t 200` 或 `dumpsys` 白名单（activity/windows/battery/package/notification），
输出截断到 16K（`truncated=true` 时明说）。

### 2.5 `settingsRead` / `settingsWrite`

- `namespace ∈ {system, secure, global}`（枚举，其它直接 `IllegalArgumentException`）。
- `key`：`[A-Za-z0-9_.:-]{1,128}`；`value`：≤2048 字符、禁止控制字符；`value=null` 表示删除。
- 读：优先 Shizuku `settings get`（`via="shizuku"`），Shizuku 不可用时退到 App 自身 `Settings.*.getString`（`via="app"`）。
- 写：需要 §2.4 的全部门禁（含 `writeEnabled`）；**不禁止任何 key**（用户授权的就是"能改设置"），
  但一条命令只跑一次，之后回读并返回 `oldValue / newValue / verified / exitCode / error`；
  未知副作用不重放，`verified=false` 时 UI 必须如实展示。
- `looksSecret=true` 的 key（password/token/secret/credential/…）：权限中心**不要**展示原值；
  值本身仍交给已授权的调用方，也不得自动外发。

### 2.6 `listInstalledPackages`

`PackageManager.getInstalledPackages` + `QUERY_ALL_PACKAGES`，默认排除系统 App，`limit` clamp 1..1000（默认 300），
返回 `packageName / label / uid / system / enabled`。它只枚举本机已安装包，不读任何 App 私有数据。

---

## 3. UI 侧接入要点（给 UI agent）

- 页面打开 → `snapshot()`；从系统设置返回 → 重新 `snapshot()`（状态一律现查，别缓存成"已授权"）。
- 「授权 Shizuku」按钮 → `authorizeShizuku()`；其余系统设置行 → 只 `startActivity`，**不要**代替用户点勾。
- 开发权限行：`grantDevelopmentPermissions(setOf(row.id))`，同时展示 `adbCommands(setOf(row.id))` 供复制；
  `error != null` 就原样展示（里面已经写了 OEM/系统层面的真原因）。
- 自动化开关：`configureAutomation(enabled, allowWrites, assistantId)`；关闭或换绑会立刻 cancel 本控制器的命令。
  首次进入必须是"关 + 无绑定"，不要预填 assistantId。
- 权限中心的列表**不要**展示 settings 原值、logcat 全文；只显示状态与原因。

## 4. 工具层接入要点（给主代理）

- 工具执行只调 `runAuthorizedCommand` / `runReadOnly` / `diagnostics` / `settingsWrite`，
  并把自己真实的 assistant id 传进去；**不要**在工具里请求授权或调用 `authorizeShizuku()`。
- 捕获 `HighPrivilegeDeniedException` → 直接回给模型"未授权 + 原因"，不要重试、不要绕道。
- 命令输出已经有界；不要再把整段 logcat 塞回上下文。

## 5. Manifest 变更（已落地，仅权限声明）

`app/src/main/AndroidManifest.xml` 新增（**只声明**，不新增 service/provider，不改任何已有组件）：

```
WRITE_SECURE_SETTINGS, DUMP, READ_LOGS, CHANGE_CONFIGURATION        // development，pm grant 才可能生效
WRITE_SETTINGS, ACCESS_NOTIFICATION_POLICY, SCHEDULE_EXACT_ALARM,
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, REQUEST_INSTALL_PACKAGES      // 系统设置页开关
QUERY_ALL_PACKAGES                                                  // 本机自动化工具需要；Play 分发需申报
```

刻意**未**声明：`INJECT_EVENTS`、`GRANT_RUNTIME_PERMISSIONS`、`INSTALL_PACKAGES`、`REBOOT`、
`READ_FRAME_BUFFER`、`BIND_*`（无障碍/通知监听已有组件自带）等签名专属项。
蓝牙（`BLUETOOTH_CONNECT/SCAN`）不加：本模块没有蓝牙功能，声明只会误导。
`MANAGE_EXTERNAL_STORAGE`、`PACKAGE_USAGE_STATS`、`SYSTEM_ALERT_WINDOW` 等原本就有，未重复添加。

## 6. 测试

`app/src/test/java/me/rerere/rikkahub/data/deviceprivilege/`（纯 JVM，无 Robolectric/设备依赖，共 22 个用例）：

- `HighPrivilegePolicyTest`：白名单（拒绝 INJECT_EVENTS 等）、shell 注入转义（`'; reboot; '`）、包名非法拒绝、
  settings 三命名空间/键长/控制字符/值长度、命令拼装、超时 clamp、UID 解析、`status=READY` 判定、
  输出截断、secret 键识别、只读清单不含插值。
- `HighPrivilegeGateTest`：默认全关、null/空白绑定不是通配、只认绑定的助手、写开关独立、Shizuku 必须 READY、
  `normalized()` 收敛、命令校验。
- `HighPrivilegeStoreTest`：缺失即全关、往返持久化、写权限不能脱离总开关（含空白 assistant 归一）、
  损坏/超长记录 fail-closed。

本机不跑 Gradle（由主代理统一 compile）。`pm grant` 对 development 权限的真实生效性、以及 Android 14 OEM 的
额外「安全设置」限制属于设备验证范畴，未在本机执行、也未对此作任何断言。

## 7. 参考（官方 / AOSP）

- `WRITE_SECURE_SETTINGS`（`signature|privileged|development`，官方注明"not for use by third-party applications"）：
  https://developer.android.com/reference/android/Manifest.permission#WRITE_SECURE_SETTINGS
- `DUMP` / `READ_LOGS` / `CHANGE_CONFIGURATION`（同为 development 可授权）：
  https://developer.android.com/reference/android/Manifest.permission#DUMP ·
  https://developer.android.com/reference/android/Manifest.permission#READ_LOGS ·
  https://developer.android.com/reference/android/Manifest.permission#CHANGE_CONFIGURATION
- AOSP 权限定义（protectionLevel 原文）：https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/res/AndroidManifest.xml
- 各 special access 的"只能用户开"语义：
  `Settings.System.canWrite` https://developer.android.com/reference/android/provider/Settings.System#canWrite(android.content.Context) ·
  `NotificationManager.isNotificationPolicyAccessGranted` https://developer.android.com/reference/android/app/NotificationManager#isNotificationPolicyAccessGranted() ·
  `AlarmManager.canScheduleExactAlarms` https://developer.android.com/reference/android/app/AlarmManager#canScheduleExactAlarms() ·
  `PackageManager.canRequestPackageInstalls` https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls() ·
  `Environment.isExternalStorageManager` https://developer.android.com/reference/android/os/Environment#isExternalStorageManager() ·
  `PowerManager.isIgnoringBatteryOptimizations` https://developer.android.com/reference/android/os/PowerManager#isIgnoringBatteryOptimizations(java.lang.String)
- `QUERY_ALL_PACKAGES` 的 Play 政策约束：https://support.google.com/googleplay/android-developer/answer/10158779

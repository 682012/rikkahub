package me.rerere.rikkahub.data.deviceprivilege

/**
 * Meridian m1 高权限中心 —— 纯策略层（零 Android 依赖，纯 JVM 可测）。
 *
 * 这里放三件必须能被单测钉死的事：
 *  1. 哪些权限允许走 `pm grant`（白名单固定四项开发权限，别的 id 直接拒绝，不做"猜一个权限"）。
 *  2. shell 命令怎么拼：所有外部字符串一律单引号转义，settings 的 namespace/key/value 有独立校验。
 *  3. 工具层的门禁判定：关掉开关、没绑定 assistant、没开写、Shizuku 不可用都必须硬拒。
 */
object HighPrivilegePolicy {

    const val MAX_KEY_CHARS: Int = 128
    const val MAX_VALUE_CHARS: Int = 2048
    const val MAX_COMMAND_CHARS: Int = 32 * 1024
    const val MAX_OUTPUT_CHARS: Int = 16 * 1024
    const val MAX_PACKAGE_CHARS: Int = 255

    /** 工具层单条命令的默认/上限超时（秒）。 */
    const val DEFAULT_TIMEOUT_SECONDS: Int = 30
    const val MAX_TIMEOUT_SECONDS: Int = 600

    private val KEY_REGEX = Regex("[A-Za-z0-9_.:-]{1,128}")
    private val PACKAGE_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]{1,64}){1,12}")

    /**
     * 允许 `pm grant` 的四项开发权限：id → 完整权限名。
     *
     * 它们都是 AOSP 里 `protectionLevel="signature|privileged|development"` 的权限，
     * 所以 shell UID 的 `pm grant` 才可能生效；签名专属权限（INJECT_EVENTS、
     * GRANT_RUNTIME_PERMISSIONS、INSTALL_PACKAGES、REBOOT…）不在这里，也不该去声明。
     */
    private val DEVELOPMENT_PERMISSIONS: Map<String, String> = linkedMapOf(
        "WRITE_SECURE_SETTINGS" to "android.permission.WRITE_SECURE_SETTINGS",
        "DUMP" to "android.permission.DUMP",
        "READ_LOGS" to "android.permission.READ_LOGS",
        "CHANGE_CONFIGURATION" to "android.permission.CHANGE_CONFIGURATION",
    )

    val developmentPermissionIds: List<String> get() = DEVELOPMENT_PERMISSIONS.keys.toList()

    /**
     * 把 UI 传来的 id 归一成完整权限名；不在白名单里返回 null。
     * 同时接受短名（`DUMP`）和全名（`android.permission.DUMP`），其它一律 null。
     */
    fun developmentPermission(id: String): String? {
        val short = id.trim().substringAfterLast('.').uppercase()
        return DEVELOPMENT_PERMISSIONS[short]
    }

    // ------------------------------------------------------------------ settings

    enum class SettingsNamespace(val cli: String) {
        SYSTEM("system"),
        SECURE("secure"),
        GLOBAL("global");

        companion object {
            fun fromCli(value: String): SettingsNamespace? =
                entries.firstOrNull { it.cli == value.trim().lowercase() }
        }
    }

    data class ValidatedSetting(
        val namespace: SettingsNamespace,
        val key: String,
        val value: String?,
    )

    /**
     * 校验一次 settings 读写请求。
     *
     * namespace 只认 system/secure/global 三个枚举；key 限长限字符集；value 为 null 表示删除，
     * 否则拒绝控制字符并限长。任何一条不满足都抛 [IllegalArgumentException]（带可展示原因）。
     */
    fun validateSetting(namespace: String, key: String, value: String?): ValidatedSetting {
        val ns = SettingsNamespace.fromCli(namespace)
            ?: throw IllegalArgumentException("namespace must be system, secure or global")
        require(KEY_REGEX.matches(key)) {
            "invalid settings key: 1-$MAX_KEY_CHARS chars of [A-Za-z0-9_.:-]"
        }
        if (value != null) {
            require(value.length <= MAX_VALUE_CHARS) { "settings value is longer than $MAX_VALUE_CHARS characters" }
            require(value.none { it.code < 0x20 || it.code == 0x7f }) {
                "settings value must not contain control characters"
            }
        }
        return ValidatedSetting(ns, key, value)
    }

    /** POSIX 单引号转义：把任意字符串变成一个 shell 字面量，`$`/`;`/`|`/反引号都不会展开。 */
    fun quote(value: String): String {
        require(!value.contains('\u0000')) { "shell argument must not contain NUL" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /** `pm grant` 一行命令。当前 App 的包名由调用方传 `context.packageName`（不是 BuildConfig）。 */
    fun grantCommand(appPackage: String, permission: String): String {
        require(PACKAGE_REGEX.matches(appPackage)) { "invalid package name" }
        return "pm grant --user current ${quote(appPackage)} ${quote(permission)}"
    }

    fun revokeCommand(appPackage: String, permission: String): String {
        require(PACKAGE_REGEX.matches(appPackage)) { "invalid package name" }
        return "pm revoke --user current ${quote(appPackage)} ${quote(permission)}"
    }

    /**
     * 给用户抄的 ADB 命令（不执行，只呈现）。
     * 桌面端执行时就是 `adb shell pm grant <pkg> <perm>`。
     */
    fun adbCommands(appPackage: String, ids: Set<String>): List<String> =
        ids.mapNotNull { developmentPermission(it) }
            .distinct()
            .map { "adb shell pm grant $appPackage $it" }

    fun adbRevokeCommands(appPackage: String, ids: Set<String>): List<String> =
        ids.mapNotNull { developmentPermission(it) }
            .distinct()
            .map { "adb shell pm revoke $appPackage $it" }

    fun settingsGetCommand(namespace: SettingsNamespace, key: String): String =
        "settings get ${namespace.cli} ${quote(key)}"

    fun settingsPutCommand(setting: ValidatedSetting): String {
        val value = setting.value ?: throw IllegalArgumentException("settings put needs a value; null means delete")
        return "settings put ${setting.namespace.cli} ${quote(setting.key)} ${quote(value)}"
    }

    fun settingsDeleteCommand(namespace: SettingsNamespace, key: String): String =
        "settings delete ${namespace.cli} ${quote(key)}"

    // ------------------------------------------------------------------ output

    /** 有界输出。工具层拿到的东西永远被截断到 [limit]，避免把整份 logcat 塞进上下文。 */
    fun bound(text: String, limit: Int = MAX_OUTPUT_CHARS): Pair<String, Boolean> {
        if (text.length <= limit) return text to false
        return text.take(limit) + "\n…[truncated ${text.length - limit} chars]" to true
    }

    /** 键名像不像密钥；UI 据此决定不要展示原值（值本身仍原样交给已授权的调用方）。 */
    fun looksSecret(key: String): Boolean {
        val k = key.lowercase()
        return SECRET_KEY_HINTS.any { k.contains(it) }
    }

    private val SECRET_KEY_HINTS =
        listOf("password", "passwd", "secret", "token", "credential", "auth_token", "private_key")

    // ------------------------------------------------------------------ commands

    /** 通用 shell 命令的最小校验：非空、限长、无 NUL。命令内容本身不做关键词拦截（只当 write 处理）。 */
    fun validateCommand(command: String) {
        require(command.isNotBlank()) { "command is empty" }
        require(command.length <= MAX_COMMAND_CHARS) { "command is longer than $MAX_COMMAND_CHARS characters" }
        require(!command.contains('\u0000')) { "command must not contain NUL" }
    }

    fun clampTimeoutSeconds(seconds: Int): Long {
        val clamped = seconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        return clamped * 1000L
    }

    /** 从 `id -u` 之类的探测输出里取 UID。取不到返回 null，绝不猜。 */
    fun parseUid(output: String): Int? {
        for (line in output.lineSequence()) {
            val token = line.trim()
            if (token.length in 1..10 && token.all { it.isDigit() }) {
                return token.toIntOrNull()
            }
            // Accept the explicit identity line returned by `id` too; never mine an arbitrary number.
            val uid = Regex("^uid=([0-9]{1,10})(?:\\([^)]*\\))?(?:\\s.*)?$").matchEntire(token)
            uid?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    /** `WorkspacePrivilegeManager.shizukuStatus()` 的原文 → 现在能不能真的跑命令。 */
    fun shizukuReady(statusLine: String): Boolean = statusLine.contains("status=READY")

    // ------------------------------------------------------------------ read-only catalogue

    data class ReadOnlyCommand(val id: String, val title: String, val command: String)

    /**
     * 只读探测只走这份固定清单：没有参数拼接，没有用户输入进命令。
     * 输出在控制器里再按 [MAX_OUTPUT_CHARS] 截断。
     */
    val READ_ONLY_COMMANDS: List<ReadOnlyCommand> = listOf(
        ReadOnlyCommand("device_model", "Device model", "getprop ro.product.model"),
        ReadOnlyCommand("android_version", "Android version", "getprop ro.build.version.release"),
        ReadOnlyCommand("sdk_level", "SDK level", "getprop ro.build.version.sdk"),
        ReadOnlyCommand("battery", "Battery state", "dumpsys battery"),
        ReadOnlyCommand("uptime", "Uptime and load", "uptime"),
        ReadOnlyCommand("storage_data", "Data partition usage", "df -h /data"),
        ReadOnlyCommand("packages_third_party", "Third-party packages", "pm list packages -3"),
        ReadOnlyCommand("input_methods", "Enabled input methods", "ime list -s"),
    )

    fun readOnly(id: String): ReadOnlyCommand? = READ_ONLY_COMMANDS.firstOrNull { it.id == id }

    /** dumpsys 只放白名单服务，避免 `dumpsys` 全量把上下文撑爆。 */
    val DUMP_TARGETS: Map<String, String> = mapOf(
        "activity" to "dumpsys activity activities",
        "windows" to "dumpsys window windows",
        "battery" to "dumpsys battery",
        "package" to "dumpsys package",
        "notification" to "dumpsys notification",
    )

    /** `logcat -d` 有界快照（不 tail -f，不常驻）。 */
    fun logcatCommand(lines: Int = 200): String = "logcat -d -v threadtime -t ${lines.coerceIn(20, 500)}"

    // ------------------------------------------------------------------ gate

    enum class GateDenial {
        AUTOMATION_DISABLED,
        ASSISTANT_UNBOUND,
        ASSISTANT_MISMATCH,
        WRITE_DISABLED,
        SHIZUKU_UNAVAILABLE,
    }

    data class GateDecision(val allowed: Boolean, val denial: GateDenial? = null, val message: String = "") {
        companion object {
            val ALLOW = GateDecision(true)
            fun deny(denial: GateDenial, message: String) = GateDecision(false, denial, message)
        }
    }

    /**
     * 工具层门禁。顺序固定，任何一项不满足都返回拒绝，绝不"降级成 APP 权限"继续跑。
     *
     * [assistantId] 必须与 UI 里绑定的 id 完全一致；绑定为 null 时拒绝一切调用
     * （null ≠ 所有助手都行）。
     */
    fun gate(
        state: HighPrivilegeAutomation,
        assistantId: String,
        requiresWrite: Boolean,
        shizukuReady: Boolean,
    ): GateDecision {
        if (!state.enabled) {
            return GateDecision.deny(
                GateDenial.AUTOMATION_DISABLED,
                "Privileged automation is switched off in the privilege centre",
            )
        }
        val bound = state.assistantId?.takeIf { it.isNotBlank() }
            ?: return GateDecision.deny(
                GateDenial.ASSISTANT_UNBOUND,
                "No assistant is bound to privileged automation",
            )
        if (bound != assistantId) {
            return GateDecision.deny(
                GateDenial.ASSISTANT_MISMATCH,
                "This assistant is not the one bound to privileged automation",
            )
        }
        if (requiresWrite && !state.allowWrites) {
            return GateDecision.deny(
                GateDenial.WRITE_DISABLED,
                "Privileged automation is read-only; enable writes in the privilege centre first",
            )
        }
        if (!shizukuReady) {
            return GateDecision.deny(
                GateDenial.SHIZUKU_UNAVAILABLE,
                "Shizuku is not running or not authorized; no other channel is used",
            )
        }
        return GateDecision.ALLOW
    }

    /**
     * 由真实检查结果推一行状态，避免控制器里到处写 if-else 时把"没声明"和"没授予"混为一谈。
     */
    fun developmentRowState(declared: Boolean, granted: Boolean): PermissionState = when {
        !declared -> PermissionState.UNAVAILABLE
        granted -> PermissionState.GRANTED
        else -> PermissionState.DENIED
    }
}

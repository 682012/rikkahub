package me.rerere.rikkahub.data.devicecontrol

/** 一个设备动作需要什么权限、是否只读、是否需要包名范围。 */
enum class DeviceAction(
    val wire: String,
    val capability: DeviceCapability,
    val readOnly: Boolean,
    val needsPackage: Boolean,
) {
    SNAPSHOT("device_snapshot", DeviceCapability.READ, readOnly = true, needsPackage = false),
    LIST_APPS("device_list_apps", DeviceCapability.READ, readOnly = true, needsPackage = false),
    TAP_NODE("device_tap_node", DeviceCapability.ACT, readOnly = false, needsPackage = true),
    LONG_PRESS_NODE("device_long_press_node", DeviceCapability.ACT, readOnly = false, needsPackage = true),
    SET_TEXT("device_set_text_node", DeviceCapability.ACT, readOnly = false, needsPackage = true),
    TAP("device_tap", DeviceCapability.ACT, readOnly = false, needsPackage = true),
    SWIPE("device_swipe", DeviceCapability.ACT, readOnly = false, needsPackage = true),
    /**
     * 返回/桌面/最近任务不是"全局随便按"：必须当前前台包在白名单里。
     * 否则模型可以对着未授权的 App（例如银行、设置、系统授权页）按返回/回桌面，
     * 用导航键绕过"只允许在这个包里操作"的授权范围。
     */
    BACK("device_back", DeviceCapability.GLOBAL, readOnly = false, needsPackage = true),
    HOME("device_home", DeviceCapability.GLOBAL, readOnly = false, needsPackage = true),
    RECENTS("device_recents", DeviceCapability.GLOBAL, readOnly = false, needsPackage = true),
    LAUNCH_APP("device_launch_app", DeviceCapability.GLOBAL, readOnly = false, needsPackage = true),
    ;

    companion object {
        fun of(toolName: String): DeviceAction? = entries.firstOrNull { it.wire == toolName }
    }
}

/**
 * 纯判定的授权/白名单策略。不碰 Android API，方便单测，也保证 UI 判断和运行时判断是同一套。
 *
 * 规则刻意保守：
 * - enabled=false 一律拒绝；
 * - 读/写/全局动作各自开关，默认只给读；
 * - 白名单为空 = 任何包都不允许（不存在"没配置就全放行"）；
 * - 需要包名的动作在包名未知时也拒绝（宁可报错，不猜前台包）；
 * - 自己（RikkaHub）永远不允许被自动化操作：授权开关、白名单、密码输入都在自己界面里，
 *   允许点自己等于允许模型给自己授权，这条没有开关。
 */
object DeviceControlPolicy {

    fun isPackageAllowed(grants: DeviceControlGrants, packageName: String?): Boolean {
        val pkg = packageName?.trim()
        return !pkg.isNullOrEmpty() && pkg in grants.allowedPackages
    }

    fun isSelfProtected(packageName: String?, selfPackage: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        val self = selfPackage?.trim().orEmpty()
        return pkg.isNotEmpty() && self.isNotEmpty() && pkg == self
    }

    /**
     * @param packageName 快照/当前前台包
     * @param targetPackage launch_app 的目标包；其它动作传 null
     * @param selfPackage 宿主 App 包名；非空时任何针对它自己的写/全局动作都拒绝
     */
    fun check(
        grants: DeviceControlGrants,
        action: DeviceAction,
        packageName: String? = null,
        targetPackage: String? = null,
        selfPackage: String? = null,
    ): DeviceErrorCode? {
        if (!grants.enabled) return DeviceErrorCode.NOT_AUTHORIZED
        when (action.capability) {
            DeviceCapability.READ -> if (!grants.allowRead) return DeviceErrorCode.CAPABILITY_DISABLED
            DeviceCapability.ACT -> if (!grants.allowAct) return DeviceErrorCode.CAPABILITY_DISABLED
            DeviceCapability.GLOBAL -> if (!grants.allowGlobalActions) return DeviceErrorCode.CAPABILITY_DISABLED
        }
        if (action.needsPackage) {
            val pkg = targetPackage ?: packageName
            if (!isPackageAllowed(grants, pkg)) return DeviceErrorCode.PACKAGE_NOT_ALLOWED
            if (targetPackage == null && isSelfProtected(pkg, selfPackage)) {
                return DeviceErrorCode.PACKAGE_NOT_ALLOWED
            }
        }
        return null
    }

    /** 该能力在给定授权下是否可能生效（UI 用，不代替具体检查）。 */
    fun isCapabilityEnabled(grants: DeviceControlGrants, capability: DeviceCapability): Boolean {
        if (!grants.enabled) return false
        return when (capability) {
            DeviceCapability.READ -> grants.allowRead
            DeviceCapability.ACT -> grants.allowAct
            DeviceCapability.GLOBAL -> grants.allowGlobalActions
        }
    }
}

package me.rerere.rikkahub.data.devicecontrol

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * 用户必须在「系统设置 → 无障碍」里手动启用的服务。App 不会替用户打开它，
 * 也不会用 root 绕过（本模块没有任何 root 路径）。
 *
 * 服务本身不缓存界面内容：只在被调用时抓一次树，而且只保留最近一份，敏感字段在
 * [DeviceTreeRules] 里被清掉。
 */
class DeviceAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        DeviceAccessibilityRegistry.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只用事件更新"当前前台包"，不做隐式录制，也不落盘
        DeviceAccessibilityRegistry.notePackage(event?.packageName?.toString())
    }

    override fun onInterrupt() {
        // 系统只是要求中断当前反馈，不等于断开服务；这里不清连接状态，避免假报"未连接"。
        Unit
    }

    override fun onUnbind(intent: Intent?): Boolean {
        DeviceAccessibilityRegistry.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        DeviceAccessibilityRegistry.detach(this)
        super.onDestroy()
    }

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, DeviceAccessibilityService::class.java)

        /** 供 manifest 之外的代码判断"是否已在系统里启用"（真值来自 Settings.Secure）。 */
        fun isEnabled(context: Context): Boolean = DeviceAccessibilityRegistry.isEnabledInSystem(context)
    }
}

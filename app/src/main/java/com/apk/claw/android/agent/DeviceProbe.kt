package com.apk.claw.android.agent

import android.content.pm.ApplicationInfo
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.service.ClawAccessibilityService

/**
 * F4 设备状态探针的 Android 实现：经无障碍服务回读设备状态。
 * 无障碍根节点不可用时返回 null，由 ResultVerifier 视为"无法观测跳过"。
 */
object DeviceProbe : ResultVerifier.Probe {

    override fun foregroundPackage(): String? {
        val service = ClawAccessibilityService.getInstance() ?: return null
        val root = service.rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
    }

    /**
     * C6：包是否为系统 App（FLAG_SYSTEM）。查询失败（未安装/包管理器异常）返回 false，
     * 由 AppPolicyEngine 按第三方保守处理——总开关关闭时宁多拦不误放。
     */
    fun isSystemPackage(packageName: String): Boolean {
        return try {
            val ai = ClawApplication.instance.packageManager.getApplicationInfo(packageName, 0)
            ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
        } catch (e: Exception) {
            false
        }
    }
}

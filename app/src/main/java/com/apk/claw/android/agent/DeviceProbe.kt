package com.apk.claw.android.agent

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
}

package com.apk.claw.android.compliance

/**
 * C3/C5 合规设置的数据快照（纯 JVM 数据类，无存储依赖）。
 * 由 KVUtils.loadComplianceConfig() 组装；准入判断只消费本类，便于单测。
 *
 * 各限频字段语义：0 = 关闭该限制（rate/cooldown/breakerThreshold）。
 */
data class ComplianceConfig(
    /** C5：是否允许自动化第三方 App（关闭后第三方前台只读可观察，改动类操作被拒） */
    val thirdPartyAutomationEnabled: Boolean,
    /** C3：夜间静默时段开关；只作用于自动任务（定时/事件），手动消息不受影响 */
    val quietHoursEnabled: Boolean,
    /** 静默时段起点，0-1439 分钟自零点 */
    val quietStartMin: Int,
    /** 静默时段终点，0-1439 分钟自零点；支持跨零点（起点 > 终点） */
    val quietEndMin: Int,
    /** 自动任务间最小间隔（秒）；0 = 关闭 */
    val cooldownSec: Int,
    /** 全局每分钟任务启动上限（滑动窗口 60s）；0 = 关闭 */
    val rateGlobalPerMin: Int,
    /** 单渠道每分钟任务启动上限；0 = 关闭 */
    val rateChannelPerMin: Int,
    /** 连续失败熔断阈值（条）；达到后自动任务暂停；0 = 关闭 */
    val breakerThreshold: Int,
    /** 当前连续失败数（只读，由任务收尾更新） */
    val failureStreak: Int
) {

    /**
     * [minuteOfDay]（0-1439）是否处于静默窗口内。起点 == 终点视为全天静默；
     * 起点 < 终点为普通区间，起点 > 终点为跨零点区间。
     */
    fun isInQuietWindow(minuteOfDay: Int): Boolean {
        if (!quietHoursEnabled) return false
        return when {
            quietStartMin < quietEndMin -> minuteOfDay in quietStartMin until quietEndMin
            quietStartMin > quietEndMin -> minuteOfDay >= quietStartMin || minuteOfDay < quietEndMin
            else -> true
        }
    }

    /** 熔断是否已触发（阈值 > 0 且连续失败数达到阈值） */
    fun breakerOpen(): Boolean = breakerThreshold > 0 && failureStreak >= breakerThreshold

    companion object {
        const val DEFAULT_QUIET_START_MIN = 22 * 60
        const val DEFAULT_QUIET_END_MIN = 7 * 60
        const val DEFAULT_COOLDOWN_SEC = 5
        const val DEFAULT_RATE_GLOBAL_PER_MIN = 10
        const val DEFAULT_RATE_CHANNEL_PER_MIN = 5
        const val DEFAULT_BREAKER_THRESHOLD = 5

        /** 限频字段钳制上限，防止误填把设备完全锁死 */
        const val MAX_RATE_PER_MIN = 600
        const val MAX_COOLDOWN_SEC = 600
        const val MAX_BREAKER_THRESHOLD = 50

        /** 分钟自零点 → "HH:mm"（配置页 wire 格式） */
        fun formatHm(minuteOfDay: Int): String {
            val h = minuteOfDay / 60
            val m = minuteOfDay % 60
            return String.format(java.util.Locale.US, "%02d:%02d", h, m)
        }

        /** "HH:mm" → 分钟自零点；非法格式返回 null */
        fun parseHm(text: String): Int? {
            val parts = text.split(":")
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return h * 60 + m
        }
    }
}

package com.apk.claw.android.compliance

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.ArrayDeque

/**
 * C3 任务准入控制（纯 JVM，可单测）。持有进程内状态：全局与按渠道的启动滑动窗口、
 * 最近一次任务启动时刻；判定规则全部来自 [ComplianceConfig]（配置活读，每次 check 传入）。
 *
 * 语义（ROADMAP §6 C3）：静默时段/冷却/熔断只拦截**自动任务**（定时/事件等无人值守来源，
 * 消息 ID 带 automated 标志），手动渠道消息永远放行——真人操作本身就是"人速"；
 * 频率上限对两者都生效，用于防御消息风暴/异常重试触发平台风控。
 */
class AdmissionControl(private val zone: ZoneId = ZoneId.systemDefault()) {

    enum class Reason { RATE_LIMITED, QUIET_HOURS, COOLDOWN, BREAKER }

    sealed class Outcome {
        /** 放行 */
        object Allow : Outcome()

        /** 放行但需先向渠道发送提示（如熔断未解除时的手动任务） */
        data class Notice(val reason: Reason) : Outcome()

        /** 拒绝并应向渠道说明原因后丢弃本条 */
        data class Skip(val reason: Reason) : Outcome()
    }

    companion object {
        private const val WINDOW_MS = 60_000L
    }

    private val lock = Any()
    private val globalStarts = ArrayDeque<Long>()
    private val channelStarts = HashMap<String, ArrayDeque<Long>>()
    private var lastStartAt = 0L

    /**
     * 到达/排空时的准入判定。
     * @param automated 是否自动任务（F7 定时等无人值守来源）
     */
    fun check(channelName: String, automated: Boolean, cfg: ComplianceConfig, now: Long): Outcome {
        synchronized(lock) {
            prune(globalStarts, now)
            channelStarts[channelName]?.let { prune(it, now) }

            val rateBlocked = (cfg.rateGlobalPerMin > 0 && globalStarts.size >= cfg.rateGlobalPerMin) ||
                (cfg.rateChannelPerMin > 0 && channelStarts[channelName]?.size ?: 0 >= cfg.rateChannelPerMin)
            val breakerOpen = cfg.breakerOpen()

            if (automated) {
                if (cfg.quietHoursEnabled && cfg.isInQuietWindow(minuteOfDay(now))) return Outcome.Skip(Reason.QUIET_HOURS)
                if (cfg.cooldownSec > 0 && lastStartAt > 0 && now - lastStartAt < cfg.cooldownSec * 1000L) {
                    return Outcome.Skip(Reason.COOLDOWN)
                }
                if (breakerOpen) return Outcome.Skip(Reason.BREAKER)
                if (rateBlocked) return Outcome.Skip(Reason.RATE_LIMITED)
                return Outcome.Allow
            }
            // 手动：只受频率上限约束；熔断打开时放行但提示（成功一次即复位）
            if (rateBlocked) return Outcome.Skip(Reason.RATE_LIMITED)
            if (breakerOpen) return Outcome.Notice(Reason.BREAKER)
            return Outcome.Allow
        }
    }

    /** 任务实际启动时登记（startNewTask 唯一调用点），供频率窗口与冷却判定 */
    fun recordStart(channelName: String, now: Long) {
        synchronized(lock) {
            prune(globalStarts, now)
            val ch = channelStarts.getOrPut(channelName) { ArrayDeque() }
            prune(ch, now)
            globalStarts.addLast(now)
            ch.addLast(now)
            lastStartAt = now
            if (globalStarts.size > 4096) globalStarts.removeFirst()
        }
    }

    /** 距离上一次任务启动的毫秒数（无记录返回 Long.MAX_VALUE；测试/诊断用） */
    fun millisSinceLastStart(now: Long): Long =
        synchronized(lock) { if (lastStartAt == 0L) Long.MAX_VALUE else now - lastStartAt }

    private fun prune(deque: ArrayDeque<Long>, now: Long) {
        while (deque.isNotEmpty() && now - deque.first() > WINDOW_MS) deque.removeFirst()
    }

    private fun minuteOfDay(now: Long): Int {
        val t = LocalTime.ofInstant(Instant.ofEpochMilli(now), zone)
        return t.hour * 60 + t.minute
    }
}

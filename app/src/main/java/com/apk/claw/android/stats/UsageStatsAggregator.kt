package com.apk.claw.android.stats

import com.apk.claw.android.agent.store.TaskHistoryStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * F13 用量与成本看板（纯函数聚合）：以 F3 任务历史（TaskHistoryStore）为唯一数据源，
 * 按日 / 渠道 / 发送者聚合 token 消耗与任务成功率，并给出高频任务 Top N。
 * 无 Android 依赖；时间分桶的时区与"当前时刻"由调用方注入，便于 JVM 单测。
 */
object UsageStatsAggregator {

    const val DEFAULT_DAYS = 14
    const val MAX_DAYS = 90
    const val TOP_TASKS = 5

    /** 发送者只参与聚合展示，与 /api/tasks 同策略截断，避免 LAN 页泄露完整平台身份 */
    private const val SENDER_MASK_CHARS = 6

    /** 高频任务分组键：归并空白后截断，同一意图的长描述聚合为一条场景 */
    private const val TASK_KEY_CHARS = 30

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    data class UsageStats(
        val days: Int,
        val totalTasks: Int,
        val completed: Int,
        val failed: Int,
        val cancelled: Int,
        val waitTimeout: Int,
        /** completed / totalTasks；无任务时为 0 */
        val successRate: Double,
        val totalTokens: Long,
        val avgTokensPerTask: Int,
        /** 按日升序、窗口内零填充，供日 token 曲线直接渲染 */
        val daily: List<DailyStat>,
        val channels: List<ChannelStat>,
        val senders: List<SenderStat>,
        val topTasks: List<TaskStat>
    )

    data class DailyStat(val date: String, val tasks: Int, val completed: Int, val tokens: Long)
    data class ChannelStat(val channel: String, val tasks: Int, val completed: Int, val failed: Int, val tokens: Long)
    data class SenderStat(val sender: String, val tasks: Int, val tokens: Long)
    data class TaskStat(val task: String, val count: Int, val tokens: Long)

    /** @param days 统计窗口（含今天的最近 N 天）；[nowMs]/[zone] 注入以保证可测 */
    fun aggregate(records: List<TaskHistoryStore.TaskRecord>, days: Int, nowMs: Long, zone: ZoneId): UsageStats {
        val window = days.coerceIn(1, MAX_DAYS)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val windowStartMs = today.minusDays((window - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val inWindow = records.filter { it.startTime >= windowStartMs }

        val total = inWindow.size
        val completed = inWindow.count { it.status == TaskHistoryStore.Status.COMPLETED.name }
        val failed = inWindow.count { it.status == TaskHistoryStore.Status.FAILED.name }
        val cancelled = inWindow.count { it.status == TaskHistoryStore.Status.CANCELLED.name }
        val waitTimeout = inWindow.count { it.status == TaskHistoryStore.Status.WAIT_TIMEOUT.name }
        val totalTokens = inWindow.sumOf { it.tokens.toLong() }

        val daily = (window - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            val dayRecords = inWindow.filter {
                Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate() == date
            }
            DailyStat(
                date = date.format(DATE_FMT),
                tasks = dayRecords.size,
                completed = dayRecords.count { it.status == TaskHistoryStore.Status.COMPLETED.name },
                tokens = dayRecords.sumOf { it.tokens.toLong() }
            )
        }

        val channels = inWindow.groupBy { it.channel }.map { (name, list) ->
            ChannelStat(
                channel = name,
                tasks = list.size,
                completed = list.count { it.status == TaskHistoryStore.Status.COMPLETED.name },
                failed = list.count { it.status == TaskHistoryStore.Status.FAILED.name },
                tokens = list.sumOf { it.tokens.toLong() }
            )
        }.sortedWith(compareByDescending<ChannelStat> { it.tasks }.thenByDescending { it.tokens }.thenBy { it.channel })

        val senders = inWindow.groupBy { it.sender.take(SENDER_MASK_CHARS) }.map { (sender, list) ->
            SenderStat(sender = sender, tasks = list.size, tokens = list.sumOf { it.tokens.toLong() })
        }.sortedWith(compareByDescending<SenderStat> { it.tasks }.thenByDescending { it.tokens })

        val topTasks = inWindow.groupBy { it.task.trim().replace(Regex("\\s+"), " ").take(TASK_KEY_CHARS) }
            .map { (task, list) -> TaskStat(task = task, count = list.size, tokens = list.sumOf { it.tokens.toLong() }) }
            .sortedWith(compareByDescending<TaskStat> { it.count }.thenByDescending { it.tokens })
            .take(TOP_TASKS)

        return UsageStats(
            days = window,
            totalTasks = total,
            completed = completed,
            failed = failed,
            cancelled = cancelled,
            waitTimeout = waitTimeout,
            successRate = if (total == 0) 0.0 else completed.toDouble() / total,
            totalTokens = totalTokens,
            avgTokensPerTask = if (total == 0) 0 else (totalTokens / total).toInt(),
            daily = daily,
            channels = channels,
            senders = senders,
            topTasks = topTasks
        )
    }
}

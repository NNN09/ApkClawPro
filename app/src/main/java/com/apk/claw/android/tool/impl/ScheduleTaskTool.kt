package com.apk.claw.android.tool.impl

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.agent.TaskContext
import com.apk.claw.android.agent.store.ScheduledTaskStore
import com.apk.claw.android.agent.store.ScheduledTaskStore.ScheduledTask
import com.apk.claw.android.service.TaskScheduler
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F7：创建每天/每周定点的例行任务。任务绑定当前会话（渠道 + 发送者），
 * 到点经统一派发入口执行，结果回传到发起会话。
 */
class ScheduleTaskTool : BaseTool() {

    override fun getName() = "schedule_task"

    override fun getParameters() = listOf(
        ToolParameter("task", "string", "The instruction to execute at the scheduled time, e.g. '打开飞书查看未读消息并总结'", true),
        ToolParameter("time", "string", "Trigger time in 24h 'HH:mm' format, e.g. '08:30'", true),
        ToolParameter(
            "days_of_week", "string",
            "Optional: comma-separated ISO days 1-7 (1=Monday..7=Sunday), e.g. '1,2,3,4,5' for weekdays. Empty = every day",
            false
        ),
        ToolParameter("name", "string", "Optional short name for the task, e.g. 'morning-news'", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val context = TaskContext.current()
            ?: return ToolResult.error("No active task channel context; scheduling requires a message channel")

        val hourMinute = requireString(params, "time").trim().split(":")
        val hour = hourMinute.getOrNull(0)?.trim()?.toIntOrNull() ?: -1
        val minute = hourMinute.getOrNull(1)?.trim()?.toIntOrNull() ?: -1
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolResult.error("Invalid time, expected 24h 'HH:mm' (e.g. '08:30')")
        }

        val days = parseDaysOfWeek(optionalString(params, "days_of_week", ""))
            ?: return ToolResult.error("Invalid days_of_week, expected comma-separated 1-7 (1=Monday..7=Sunday)")

        val task = requireString(params, "task").trim()
        val scheduled = ScheduledTask(
            id = "st-${java.lang.Long.toString(System.currentTimeMillis(), 36)}-${(100..999).random()}",
            name = optionalString(params, "name", "").trim().ifBlank { task.take(20) },
            task = task,
            channel = context.first.name,
            senderId = context.second,
            hour = hour,
            minute = minute,
            daysOfWeek = days,
            enabled = true,
            createdAt = System.currentTimeMillis()
        )
        val added = try {
            ScheduledTaskStore.add(scheduled)
        } catch (e: Exception) {
            return ToolResult.error("Failed to schedule task: ${e.message}")
        }
        TaskScheduler.schedule(ClawApplication.instance, added)

        val nextAt = ScheduledTaskStore.nextTriggerAt(added, System.currentTimeMillis())
        val nextText = nextAt?.let { FORMAT.format(Date(it)) } ?: "unknown"
        return ToolResult.success(
            "Scheduled task created: id=${added.id} \"${added.name}\" " +
                "at ${"%02d".format(added.hour)}:${"%02d".format(added.minute)} ${describeDays(added.daysOfWeek)}, " +
                "next run: $nextText. Results will be sent to the original conversation."
        )
    }

    override fun getDescriptionEN() =
        "Create a scheduled (recurring) task. Use when the user asks for daily/routine automation like '每天8点摘要发到飞书'. Schedule it instead of executing now."

    override fun getDescriptionCN() =
        "创建定时/例行任务。当用户要求“每天/每天几点做某事”这类周期性任务时使用，创建计划而不是当场执行。"

    companion object {
        private val FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        /** 解析 "1,2,3" 形式的 ISO 星期集合；非法返回 null；空串返回空集（每天） */
        fun parseDaysOfWeek(raw: String): Set<Int>? {
            val text = raw.trim()
            if (text.isEmpty()) return emptySet()
            val days = text.split(",", "，").mapNotNull { it.trim().toIntOrNull() }.toSet()
            if (days.any { it !in 1..7 }) return null
            return days
        }

        fun describeDays(days: Set<Int>): String {
            if (days.isEmpty()) return "每天"
            val names = mapOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")
            return "每周" + days.sorted().joinToString("") { names[it] ?: "" }
        }
    }
}

package com.apk.claw.android.tool.impl

import com.apk.claw.android.agent.store.ScheduledTaskStore
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** F7：列出全部定时任务，含 id（取消时用）、时间、周期与启用状态 */
class ListScheduledTasksTool : BaseTool() {

    override fun getName() = "list_scheduled_tasks"

    override fun getParameters() = emptyList<ToolParameter>()

    override fun execute(params: Map<String, Any>): ToolResult {
        val all = ScheduledTaskStore.list()
        if (all.isEmpty()) {
            return ToolResult.success("当前没有定时任务")
        }
        val sb = StringBuilder()
        all.forEach { t ->
            val time = "%02d:%02d".format(t.hour, t.minute)
            val status = if (t.enabled) "启用" else "停用"
            val next = if (t.enabled) {
                ScheduledTaskStore.nextTriggerAt(t, System.currentTimeMillis())
                    ?.let { "，下次 " + NEXT_FORMAT.format(Date(it)) } ?: ""
            } else ""
            sb.append("id=${t.id} 「${t.name}」 $time ${ScheduleTaskTool.describeDays(t.daysOfWeek)} [$status]$next\n")
            sb.append("  任务: ${t.task.take(80)}\n")
        }
        return ToolResult.success(sb.toString().trim())
    }

    override fun getDescriptionEN() = "List all scheduled tasks with ids, times and status."

    override fun getDescriptionCN() = "列出全部定时任务（含 id、时间、周期与状态）。"

    companion object {
        private val NEXT_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }
}

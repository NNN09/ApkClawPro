package com.apk.claw.android.tool.impl

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.agent.store.ScheduledTaskStore
import com.apk.claw.android.service.TaskScheduler
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/** F7：按 id 取消（删除）一个定时任务 */
class CancelScheduledTaskTool : BaseTool() {

    override fun getName() = "cancel_scheduled_task"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_cancel_scheduled_task)

    override fun getParameters() = listOf(
        ToolParameter("id", "string", "The id of the scheduled task to cancel (from list_scheduled_tasks)", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val id = requireString(params, "id")
        if (ScheduledTaskStore.remove(id)) {
            TaskScheduler.cancel(ClawApplication.instance, id)
            return ToolResult.success("Scheduled task $id cancelled")
        }
        return ToolResult.error("Scheduled task not found: $id. Use list_scheduled_tasks to check ids.")
    }

    override fun getDescriptionEN() = "Cancel (delete) a scheduled task by id."

    override fun getDescriptionCN() = "按 id 取消（删除）一个定时任务。"
}

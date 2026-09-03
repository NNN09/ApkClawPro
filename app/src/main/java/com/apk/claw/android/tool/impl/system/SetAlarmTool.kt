package com.apk.claw.android.tool.impl.system

import android.content.Intent
import android.provider.AlarmClock
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/** F9：设置系统闹钟（走系统时钟 App 的官方 Intent，无需无障碍模拟点击） */
class SetAlarmTool : BaseTool() {

    override fun getName() = "set_alarm"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_set_alarm)

    override fun getParameters() = listOf(
        ToolParameter("hour", "integer", "Hour in 24h format (0-23)", true),
        ToolParameter("minute", "integer", "Minute (0-59)", true),
        ToolParameter("label", "string", "Optional label shown with the alarm", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val hour = try { requireInt(params, "hour") } catch (e: Exception) { -1 }
        val minute = try { requireInt(params, "minute") } catch (e: Exception) { -1 }
        if (!SystemToolSupport.isValidAlarmTime(hour, minute)) {
            return ToolResult.error("Invalid alarm time: hour 0-23, minute 0-59 required")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            optionalString(params, "label", "").trim().takeIf { it.isNotEmpty() }?.let {
                putExtra(AlarmClock.EXTRA_MESSAGE, it)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startOrError(intent, "alarm set for %02d:%02d".format(hour, minute))
    }

    private fun startOrError(intent: Intent, successMsg: String): ToolResult {
        val context = ClawApplication.instance
        return try {
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.error("No app on this device can handle this system intent")
            }
            context.startActivity(intent)
            ToolResult.success(successMsg)
        } catch (e: Exception) {
            ToolResult.error("Failed to launch system intent: ${e.message}")
        }
    }

    override fun getDescriptionEN() =
        "Set a system alarm via the clock app (official AlarmClock intent, more reliable than UI automation). Use for '定个7点的闹钟'."

    override fun getDescriptionCN() =
        "通过系统时钟 App 设置闹钟（官方 AlarmClock Intent，比模拟点击可靠）。适用于“定个7点的闹钟”。"
}

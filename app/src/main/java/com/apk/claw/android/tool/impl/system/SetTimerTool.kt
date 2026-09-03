package com.apk.claw.android.tool.impl.system

import android.content.Intent
import android.provider.AlarmClock
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/** F9：设置系统倒计时（官方 AlarmClock timer Intent） */
class SetTimerTool : BaseTool() {

    override fun getName() = "set_timer"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_set_timer)

    override fun getParameters() = listOf(
        ToolParameter("seconds", "integer", "Timer duration in seconds (1-86400)", true),
        ToolParameter("label", "string", "Optional label shown with the timer", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val seconds = try { requireInt(params, "seconds") } catch (e: Exception) { -1 }
        if (seconds !in 1..86400) {
            return ToolResult.error("Invalid timer duration: 1-86400 seconds required")
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            optionalString(params, "label", "").trim().takeIf { it.isNotEmpty() }?.let {
                putExtra(AlarmClock.EXTRA_MESSAGE, it)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val context = ClawApplication.instance
        return try {
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.error("No app on this device can handle this system intent")
            }
            context.startActivity(intent)
            ToolResult.success("Timer set for $seconds seconds")
        } catch (e: Exception) {
            ToolResult.error("Failed to launch system intent: ${e.message}")
        }
    }

    override fun getDescriptionEN() =
        "Start a system countdown timer via the clock app (official intent). Use for '设个10分钟的计时器'."

    override fun getDescriptionCN() =
        "通过系统时钟 App 设置倒计时（官方 Intent）。适用于“设个10分钟的计时器”。"
}

package com.apk.claw.android.tool.impl.system

import android.app.NotificationManager
import android.content.Context
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * F9：设置勿扰模式（off/priority/alarms/none）。需要通知策略访问特殊权限；
 * 未授权时返回明确错误并提示用 open_settings_page(page="dnd_access") 引导授权。
 */
class SetDndTool : BaseTool() {

    override fun getName() = "set_dnd"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_set_dnd)

    override fun getParameters() = listOf(
        ToolParameter(
            "mode", "string",
            "DND mode: off|priority|alarms|none (none = total silence)",
            true
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val modeName = requireString(params, "mode").trim()
        val filter = SystemToolSupport.dndFilter(modeName)
            ?: return ToolResult.error("Unknown mode '$modeName', allowed: off|priority|alarms|none")
        val nm = ClawApplication.instance.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            return ToolResult.error(
                "Notification policy access not granted. Ask the user to grant it, e.g. call open_settings_page(page=\"dnd_access\")"
            )
        }
        return try {
            // setInterruptionFilter 无权限时抛 SecurityException，已提前检查
            nm.setInterruptionFilter(filter)
            ToolResult.success("DND mode set to $modeName")
        } catch (e: Exception) {
            ToolResult.error("Failed to set DND mode: ${e.message}")
        }
    }

    override fun getDescriptionEN() =
        "Set Do-Not-Disturb mode: off|priority|alarms|none (requires notification policy access)."

    override fun getDescriptionCN() =
        "设置勿扰模式：off|priority|alarms|none（需要通知策略访问权限）。"
}

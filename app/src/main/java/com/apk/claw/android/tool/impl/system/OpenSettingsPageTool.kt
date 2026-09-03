package com.apk.claw.android.tool.impl.system

import android.content.Intent
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * F9：打开指定系统设置页（Wi-Fi/蓝牙/显示/声音/勿扰授权等），
 * 供 Agent 引导用户到达目标开关，而不是盲点设置界面。
 */
class OpenSettingsPageTool : BaseTool() {

    override fun getName() = "open_settings_page"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_open_settings_page)

    override fun getParameters() = listOf(
        ToolParameter(
            "page", "string",
            "Settings page: main|wifi|bluetooth|display|sound|battery|apps|location|date|data_usage|accessibility|dnd_access|write_settings",
            true
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val name = requireString(params, "page").trim()
        val action = SystemToolSupport.settingsPage(name)
            ?: return ToolResult.error("Unknown settings page '$name', allowed: main|wifi|bluetooth|display|sound|battery|apps|location|date|data_usage|accessibility|dnd_access|write_settings")
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val context = ClawApplication.instance
        return try {
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.error("This settings page is not available on the device")
            }
            context.startActivity(intent)
            ToolResult.success("Opened settings page: $name")
        } catch (e: Exception) {
            ToolResult.error("Failed to open settings page: ${e.message}")
        }
    }

    override fun getDescriptionEN() =
        "Open a specific system settings page (wifi, bluetooth, sound, dnd_access...). Use to guide the user to a toggle instead of navigating Settings by taps."

    override fun getDescriptionCN() =
        "打开指定的系统设置页（wifi、bluetooth、sound、dnd_access 等）。引导用户到目标开关时优先使用，不要模拟点击导航设置界面。"
}

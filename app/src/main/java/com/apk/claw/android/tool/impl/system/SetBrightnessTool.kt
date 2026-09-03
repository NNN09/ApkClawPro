package com.apk.claw.android.tool.impl.system

import android.content.Context
import android.provider.Settings
import android.view.WindowManager
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * F9：设置屏幕亮度（0-100%）。需要 WRITE_SETTINGS 特殊权限；
 * 未授权时返回明确错误并提示用 open_settings_page(page="write_settings") 引导授权。
 */
class SetBrightnessTool : BaseTool() {

    override fun getName() = "set_brightness"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_set_brightness)

    override fun getParameters() = listOf(
        ToolParameter("percent", "integer", "Brightness as percent 0-100", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val percent = try { requireInt(params, "percent") } catch (e: Exception) { -1 }
        if (percent !in 0..100) {
            return ToolResult.error("Invalid percent, expected 0-100")
        }
        val context = ClawApplication.instance
        if (!Settings.System.canWrite(context)) {
            return ToolResult.error(
                "WRITE_SETTINGS permission not granted. Ask the user to grant it, e.g. call open_settings_page(page=\"write_settings\")"
            )
        }
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val ok = Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                SystemToolSupport.percentToBrightness(percent)
            )
            if (ok) ToolResult.success("Brightness set to $percent%")
            else ToolResult.error("Failed to write brightness setting")
        } catch (e: Exception) {
            ToolResult.error("Failed to set brightness: ${e.message}")
        }
    }

    override fun getDescriptionEN() =
        "Set screen brightness by percent (requires WRITE_SETTINGS). Use instead of navigating display settings."

    override fun getDescriptionCN() =
        "按百分比设置屏幕亮度（需要 WRITE_SETTINGS 权限）。优先使用，不要进显示设置里模拟点击。"
}

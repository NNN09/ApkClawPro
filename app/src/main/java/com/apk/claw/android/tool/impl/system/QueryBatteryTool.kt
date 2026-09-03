package com.apk.claw.android.tool.impl.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/** F9：查询电量与充电状态（只读，sticky 广播无需权限） */
class QueryBatteryTool : BaseTool() {

    override fun getName() = "query_battery"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_query_battery)

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: Map<String, Any>): ToolResult {
        val context = ClawApplication.instance
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return ToolResult.error("Battery info unavailable")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        if (level < 0 || scale <= 0) {
            return ToolResult.error("Battery info unavailable")
        }
        val percent = level * 100 / scale
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val powerSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "battery"
        }
        return ToolResult.success(
            "{\"percent\":$percent,\"charging\":$charging,\"power_source\":\"$powerSource\"}"
        )
    }

    override fun getDescriptionEN() =
        "Query battery level and charging state. Read-only."

    override fun getDescriptionCN() =
        "查询电量和充电状态（只读）。"
}

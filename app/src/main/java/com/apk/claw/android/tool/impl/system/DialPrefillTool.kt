package com.apk.claw.android.tool.impl.system

import android.content.Intent
import android.net.Uri
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * F9：预填拨号盘但不拨出（ACTION_DIAL 只打开拨号界面，真正呼出必须由用户按键，
 * 与合规设计 C4「不可逆操作人工确认」一致）。
 */
class DialPrefillTool : BaseTool() {

    override fun getName() = "dial_prefill"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_dial_prefill)

    override fun getParameters() = listOf(
        ToolParameter("number", "string", "Phone number to prefill in the dialer (NOT dialed)", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val number = SystemToolSupport.sanitizePhoneNumber(requireString(params, "number"))
            ?: return ToolResult.error("Invalid phone number")
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val context = ClawApplication.instance
        return try {
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.error("No dialer app available")
            }
            context.startActivity(intent)
            ToolResult.success("Dialer opened with number prefilled (not dialed): $number")
        } catch (e: Exception) {
            ToolResult.error("Failed to open dialer: ${e.message}")
        }
    }

    override fun getDescriptionEN() =
        "Open the dialer with a phone number prefilled. Never dials automatically - the user must press call."

    override fun getDescriptionCN() =
        "打开拨号盘并预填号码，绝不自动拨出，需用户手动按键呼叫。"
}

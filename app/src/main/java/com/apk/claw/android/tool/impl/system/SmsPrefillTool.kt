package com.apk.claw.android.tool.impl.system

import android.content.Intent
import android.net.Uri
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * F9：预填短信收件人与正文但不发送（ACTION_SENDTO smsto: 只打开短信编辑界面，
 * 发送动作必须由用户按键完成，与合规设计 C4 一致）。
 */
class SmsPrefillTool : BaseTool() {

    override fun getName() = "sms_prefill"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_sms_prefill)

    override fun getParameters() = listOf(
        ToolParameter("number", "string", "Recipient phone number", true),
        ToolParameter("body", "string", "SMS body text to prefill", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val number = SystemToolSupport.sanitizePhoneNumber(requireString(params, "number"))
            ?: return ToolResult.error("Invalid phone number")
        val body = requireString(params, "body")
        if (body.length > 1000) {
            return ToolResult.error("SMS body too long (max 1000 chars)")
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val context = ClawApplication.instance
        return try {
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.error("No SMS app available")
            }
            context.startActivity(intent)
            ToolResult.success("SMS editor opened with recipient and body prefilled (not sent)")
        } catch (e: Exception) {
            ToolResult.error("Failed to open SMS editor: ${e.message}")
        }
    }

    override fun getDescriptionEN() =
        "Open the SMS app with recipient and body prefilled. Never sends automatically - the user must press send."

    override fun getDescriptionCN() =
        "打开短信编辑界面并预填收件人与正文，绝不自动发送，需用户手动按键发送。"
}

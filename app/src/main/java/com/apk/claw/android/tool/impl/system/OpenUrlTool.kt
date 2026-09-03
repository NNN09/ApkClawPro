package com.apk.claw.android.tool.impl.system

import android.content.Intent
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import java.net.URI

/** F9：用浏览器打开网页（ACTION_VIEW，比启动浏览器再模拟输入 URL 可靠得多） */
class OpenUrlTool : BaseTool() {

    override fun getName() = "open_url"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_open_url)

    override fun getParameters() = listOf(
        ToolParameter("url", "string", "Web URL to open, must start with http:// or https://", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val url = requireString(params, "url").trim()
        val scheme = try {
            URI(url).scheme?.lowercase()
        } catch (_: Exception) { null }
        if (scheme != "http" && scheme != "https") {
            return ToolResult.error("Invalid url, expected absolute http(s) URL")
        }
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val context = ClawApplication.instance
        return try {
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.error("No browser available to open the URL")
            }
            context.startActivity(intent)
            ToolResult.success("Opened $url in browser")
        } catch (e: Exception) {
            ToolResult.error("Failed to open URL: ${e.message}")
        }
    }

    override fun getDescriptionEN() =
        "Open a web URL in the browser. Prefer over UI automation when the task is just showing a page."

    override fun getDescriptionCN() =
        "用浏览器打开网页 URL。任务只是展示网页时优先用它，而不是模拟 UI 操作。"
}

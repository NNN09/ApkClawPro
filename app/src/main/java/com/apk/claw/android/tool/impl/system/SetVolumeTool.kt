package com.apk.claw.android.tool.impl.system

import android.content.Context
import android.media.AudioManager
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/** F9：设置指定音量流的音量（媒体/铃声/闹钟/通知），按百分比 0-100 */
class SetVolumeTool : BaseTool() {

    override fun getName() = "set_volume"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_set_volume)

    override fun getParameters() = listOf(
        ToolParameter("stream", "string", "Volume stream: music|ring|alarm|notification", true),
        ToolParameter("percent", "integer", "Target volume as percent 0-100", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val streamName = requireString(params, "stream").trim()
        val stream = SystemToolSupport.volumeStream(streamName)
            ?: return ToolResult.error("Unknown stream '$streamName', allowed: music|ring|alarm|notification")
        val percent = try { requireInt(params, "percent") } catch (e: Exception) { -1 }
        if (percent !in 0..100) {
            return ToolResult.error("Invalid percent, expected 0-100")
        }
        val audioManager = ClawApplication.instance.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            val max = audioManager.getStreamMaxVolume(stream)
            val index = (percent * max) / 100
            audioManager.setStreamVolume(stream, index, 0)
            ToolResult.success("Volume set: $streamName at $percent%")
        } catch (e: Exception) {
            // 勿扰等状态可能禁止设置铃声流
            ToolResult.error("Failed to set volume (DND may block ring volume): ${e.message}")
        }
    }

    override fun getDescriptionEN() =
        "Set system volume for a stream (music/ring/alarm/notification) by percent. Use instead of pressing volume buttons."

    override fun getDescriptionCN() =
        "按百分比设置系统音量流（媒体/铃声/闹钟/通知）。优先使用，不要模拟按音量键。"
}

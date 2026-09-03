package com.apk.claw.android.tool.impl.system

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/** F9：媒体控制（播放/暂停/上一首/下一首/停止），通过 AudioManager 派发媒体按键事件 */
class MediaControlTool : BaseTool() {

    override fun getName() = "media_control"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_media_control)

    override fun getParameters() = listOf(
        ToolParameter(
            "action", "string",
            "Media action: play_pause|play|pause|next|previous|stop",
            true
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = requireString(params, "action").trim()
        val keyCode = SystemToolSupport.mediaKeyCode(action)
            ?: return ToolResult.error("Unknown media action '$action', allowed: play_pause|play|pause|next|previous|stop")
        val audioManager = ClawApplication.instance.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            ToolResult.success("Media action sent: $action")
        } catch (e: Exception) {
            ToolResult.error("Failed to dispatch media key: ${e.message}")
        }
    }

    override fun getDescriptionEN() =
        "Control media playback (play/pause/next/previous/stop) system-wide. Use for music/video control instead of tapping player UI."

    override fun getDescriptionCN() =
        "控制系统级媒体播放（播放/暂停/上一首/下一首/停止）。控制音乐/视频时优先使用，不要模拟点击播放器界面。"
}

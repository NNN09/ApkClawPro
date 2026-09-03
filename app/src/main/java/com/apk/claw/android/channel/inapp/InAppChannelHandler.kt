package com.apk.claw.android.channel.inapp

import com.apk.claw.android.agent.store.InAppChatStore
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.channel.ChannelHandler
import java.io.File

/**
 * F5：App 内渠道。无需任何平台凭证，消息直接写入本地聊天存储；
 * 发送者固定为 [SENDER_ID]，与 IM 渠道共用 TaskOrchestrator/会话/记忆/技能链路。
 */
class InAppChannelHandler : ChannelHandler {

    override val channel: Channel = Channel.IN_APP

    override fun isConnected(): Boolean = true

    override fun init() {}

    override fun disconnect() {}

    override fun reinitFromStorage() {}

    override fun sendMessage(content: String, messageID: String) {
        InAppChatStore.append(InAppChatStore.Role.AGENT, content)
    }

    override fun sendImage(imageBytes: ByteArray, messageID: String) {
        // 聊天页暂不渲染图片（F1 系统弹窗截图场景），先以占位文本告知
        InAppChatStore.append(InAppChatStore.Role.AGENT, "[截图] ${imageBytes.size} bytes")
    }

    override fun sendFile(file: File, messageID: String) {
        InAppChatStore.append(InAppChatStore.Role.AGENT, "[文件] ${file.name}")
    }

    override fun getLastSenderId(): String = SENDER_ID

    override fun sendMessageToUser(userId: String, content: String) {
        sendMessage(content, "")
    }

    companion object {
        /** App 内会话的固定发送者标识（语音输入与聊天页共用同一会话） */
        const val SENDER_ID = "local"
    }
}

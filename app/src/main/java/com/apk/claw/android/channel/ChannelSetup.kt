package com.apk.claw.android.channel

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.TaskOrchestrator
import com.apk.claw.android.agent.store.SessionStore
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog

/**
 * 通道初始化与消息路由。
 * 负责读取本地配置初始化各通道，并将收到的消息分发给 [TaskOrchestrator]。
 */
class ChannelSetup(
    private val taskOrchestrator: TaskOrchestrator
) {

    private data class PendingMessage(
        val channel: Channel, val senderId: String, val message: String, val messageID: String
    )

    private val queueLock = Any()
    private val pendingQueue = ArrayDeque<PendingMessage>()

    companion object {
        private const val TAG = "ChannelSetup"
        private const val MAX_PENDING = 3
    }

    fun setup() {
        ChannelManager.init(
            dingtalkAppKey = KVUtils.getDingtalkAppKey().ifEmpty { null },
            dingtalkAppSecret = KVUtils.getDingtalkAppSecret().ifEmpty { null },
            feishuAppId = KVUtils.getFeishuAppId().ifEmpty { null },
            feishuAppSecret = KVUtils.getFeishuAppSecret().ifEmpty { null },
            qqAppId = KVUtils.getQqAppId().ifEmpty { null },
            qqAppSecret = KVUtils.getQqAppSecret().ifEmpty { null },
            discordBotToken = KVUtils.getDiscordBotToken().ifEmpty { null },
            telegramBotToken = KVUtils.getTelegramBotToken().ifEmpty { null },
            wechatBotToken = KVUtils.getWechatBotToken().ifEmpty { null },
            wechatApiBaseUrl = KVUtils.getWechatApiBaseUrl().ifEmpty { null }
        )
        ChannelManager.setOnMessageReceivedListener(object : ChannelManager.OnMessageReceivedListener {
            override fun onMessageReceived(channel: Channel, message: String, messageID: String, senderId: String) {
                XLog.i(TAG, "msg from ${channel.displayName} sender=$senderId")
                val app = ClawApplication.instance
                if (!ClawAccessibilityService.isRunning()) {
                    ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_no_accessibility), messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }

                val cmd = message.trim()
                if (cmd == "新对话" || cmd == "/new") {
                    SessionStore.reset(channel, senderId)
                    ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_session_reset), messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }

                if (!taskOrchestrator.tryAcquireTask(messageID, channel)) {
                    val queued = synchronized(queueLock) {
                        if (pendingQueue.size >= MAX_PENDING) null
                        else { pendingQueue.addLast(PendingMessage(channel, senderId, message, messageID)); pendingQueue.size }
                    }
                    val reply = if (queued != null) {
                        app.getString(R.string.channel_msg_queued, queued)
                    } else {
                        app.getString(R.string.channel_msg_queue_full)
                    }
                    ChannelManager.sendMessage(channel, reply, messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }
                taskOrchestrator.startNewTask(channel, senderId, message, messageID)
            }
        })

        taskOrchestrator.onIdle = { drainPending() }
    }

    /**
     * 任务空闲后排空待执行消息队列。每次只启动一条；其结束后 onIdle 会再次触发。
     */
    private fun drainPending() {
        while (true) {
            val next = synchronized(queueLock) { pendingQueue.firstOrNull() } ?: return
            if (!taskOrchestrator.tryAcquireTask(next.messageID, next.channel)) return
            synchronized(queueLock) { pendingQueue.removeFirst() }
            taskOrchestrator.startNewTask(next.channel, next.senderId, next.message, next.messageID)
            return
        }
    }
}

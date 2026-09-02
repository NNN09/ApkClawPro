package com.apk.claw.android.agent

import com.apk.claw.android.channel.Channel

/**
 * 一次任务请求：用户输入 + 来源渠道与发送者（会话隔离键）。
 */
data class TaskRequest(
    val prompt: String,
    val channel: Channel,
    val senderId: String
)

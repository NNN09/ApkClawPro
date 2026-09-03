package com.apk.claw.android.agent

import com.apk.claw.android.channel.Channel

/**
 * 当前任务的消息来源上下文（渠道 + 发送者）。
 * Agent 单线程执行任务，工具（如 schedule_task）据此把定时任务绑定到发起任务的会话；
 * 由 DefaultAgentService 在任务开始/结束时设置与清理。
 */
object TaskContext {

    private val current = ThreadLocal<Pair<Channel, String>?>()

    fun set(channel: Channel, senderId: String) {
        current.set(channel to senderId)
    }

    fun clear() {
        current.remove()
    }

    /** 当前执行线程的任务上下文；不在任务线程中时返回 null */
    fun current(): Pair<Channel, String>? = current.get()
}

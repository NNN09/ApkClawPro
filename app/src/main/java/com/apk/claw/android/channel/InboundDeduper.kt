package com.apk.claw.android.channel

/**
 * dispatch 层进程级入站去重（兜底）。
 *
 * 渠道层的去重是 handler 实例级状态（如微信 seenInboundKeys），一旦出现
 * 多实例轮询/多路径投递，同一消息仍会多次进入任务链路，被重复排队执行
 * （2026-09-03 真机事故：5 个 wechat-monitor 线程并存，一条消息排队 3 次）。
 * 此处按 渠道+发送者+内容 在短窗口内判重：
 * - 不含 messageID：微信重复投递的 contextToken 每次不同，靠它判不了重；
 * - 窗口取 30s：实测重复风暴在数秒内完成，不会误拦用户隔段时间的主动重发。
 */
class InboundDeduper(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxEntries: Int = 256,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val seenAt = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > maxEntries
    }

    /** 首次见到返回 false 并登记；窗口内重复投递返回 true */
    fun isDuplicate(channel: Channel, senderId: String, message: String): Boolean {
        val key = "${channel.name}:$senderId:${message.hashCode()}"
        val now = clock()
        synchronized(seenAt) {
            val seen = seenAt[key]
            if (seen != null && now - seen < windowMs) return true
            seenAt[key] = now
            return false
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 30_000L
    }
}

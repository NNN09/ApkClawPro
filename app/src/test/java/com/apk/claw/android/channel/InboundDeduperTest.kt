package com.apk.claw.android.channel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dispatch 层进程级入站去重（兜底）：即使渠道层出现多实例/多路径重复投递
 * （2026-09-03 微信 5 线程事故），同一消息也只进入任务链路一次。
 */
class InboundDeduperTest {

    @Test
    fun `窗口内相同渠道发送者内容的重复投递被判重`() {
        var now = 0L
        val deduper = InboundDeduper(windowMs = 30_000, clock = { now })

        assertFalse(deduper.isDuplicate(Channel.WECHAT, "userA", "帮我查电量"))
        now = 8_000
        assertTrue(deduper.isDuplicate(Channel.WECHAT, "userA", "帮我查电量"))
    }

    @Test
    fun `窗口过期后相同内容放行`() {
        var now = 0L
        val deduper = InboundDeduper(windowMs = 30_000, clock = { now })

        deduper.isDuplicate(Channel.WECHAT, "userA", "帮我查电量")
        now = 30_001
        assertFalse(deduper.isDuplicate(Channel.WECHAT, "userA", "帮我查电量"))
    }

    @Test
    fun `不同发送者渠道或内容不判重`() {
        var now = 0L
        val deduper = InboundDeduper(windowMs = 30_000, clock = { now })

        deduper.isDuplicate(Channel.WECHAT, "userA", "帮我查电量")
        now = 1_000
        assertFalse(deduper.isDuplicate(Channel.WECHAT, "userB", "帮我查电量"))
        assertFalse(deduper.isDuplicate(Channel.TELEGRAM, "userA", "帮我查电量"))
        assertFalse(deduper.isDuplicate(Channel.WECHAT, "userA", "帮我查天气"))
    }
}

package com.apk.claw.android.channel

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 回归测试：重复 init 曾导致旧轮询线程未停止、同一消息被多个实例重复投递
 * （2026-09-03 真机事故：5 个 wechat-monitor 线程并存，一条微信消息被执行 1 次并排队 3 次）。
 */
class ChannelManagerInstallTest {

    /** 记录断开/收信次数的假 handler，不触碰 Android */
    private open class FakeHandler(override val channel: Channel) : ChannelHandler {
        var disconnectCount = 0
            private set
        val sent = mutableListOf<String>()

        override fun isConnected() = true
        override fun init() {}
        override fun disconnect() { disconnectCount++ }
        override fun reinitFromStorage() {}
        override fun sendMessage(content: String, messageID: String) { sent.add(content) }
        override fun sendImage(imageBytes: ByteArray, messageID: String) {}
        override fun sendFile(file: File, messageID: String) {}
    }

    @Test
    fun `重新安装 handler 时必须断开旧实例`() {
        val old = FakeHandler(Channel.WECHAT)
        val fresh = FakeHandler(Channel.WECHAT)
        ChannelManager.installHandlers(mapOf(Channel.WECHAT to old))

        ChannelManager.installHandlers(mapOf(Channel.WECHAT to fresh))

        assertEquals(1, old.disconnectCount)
        assertEquals(0, fresh.disconnectCount)
    }

    @Test
    fun `未连接的旧实例无需断开`() {
        val idle = object : FakeHandler(Channel.TELEGRAM) {
            override fun isConnected() = false
        }
        ChannelManager.installHandlers(mapOf(Channel.TELEGRAM to idle))

        ChannelManager.installHandlers(mapOf(Channel.TELEGRAM to FakeHandler(Channel.TELEGRAM)))

        assertEquals(0, idle.disconnectCount)
    }

    @Test
    fun `安装后新 handler 立即接管消息路由`() {
        val handler = FakeHandler(Channel.WECHAT)
        ChannelManager.installHandlers(mapOf(Channel.WECHAT to handler))

        ChannelManager.sendMessage(Channel.WECHAT, "hello", "")

        assertEquals(listOf("hello"), handler.sent)
    }
}

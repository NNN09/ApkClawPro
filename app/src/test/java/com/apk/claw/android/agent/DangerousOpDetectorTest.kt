package com.apk.claw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DangerousOpDetectorTest {

    @Test
    fun exemptToolsNeverFlagged() {
        assertNull(DangerousOpDetector.assess("get_screen_info", emptyMap()))
        assertNull(DangerousOpDetector.assess("finish", mapOf("summary" to "已发送消息并支付完成")))
        assertNull(DangerousOpDetector.assess("wait", mapOf("ms" to 1000)))
    }

    @Test
    fun clickByText_withSendKeyword_flagged() {
        val risk = DangerousOpDetector.assess("tap", mapOf("text" to "发送"))
        assertNotNull(risk)
    }

    @Test
    fun click_withPayKeyword_flagged() {
        assertNotNull(DangerousOpDetector.assess("tap", mapOf("text" to "立即支付")))
        assertNotNull(DangerousOpDetector.assess("tap", mapOf("text" to "Pay Now")))
    }

    @Test
    fun resourceIdKeyword_flagged() {
        val risk = DangerousOpDetector.assess("click_by_id", mapOf("id" to "com.app:id/btn_send"))
        assertNotNull(risk)
    }

    @Test
    fun benignParams_notFlagged() {
        assertNull(DangerousOpDetector.assess("tap", mapOf("x" to 100, "y" to 200)))
        assertNull(DangerousOpDetector.assess("tap", mapOf("text" to "设置")))
        assertNull(DangerousOpDetector.assess("swipe", mapOf("x1" to 1, "y1" to 2, "x2" to 3, "y2" to 4)))
        assertNull(DangerousOpDetector.assess("input_text", mapOf("text" to "hello world")))
    }

    @Test
    fun riskMessage_containsKeyword() {
        val risk = DangerousOpDetector.assess("tap", mapOf("text" to "删除照片"))
        assertEquals("目标文本\"删除\"疑似不可逆操作", risk)
    }
}

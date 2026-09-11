package com.apk.claw.android.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmResponseTest {

    @Test
    fun durableText_prefersVisibleContent() {
        val r = LlmResponse(text = "我看到 4x4 网格", reasoning = "Let me analyze", toolExecutionRequests = emptyList())
        assertEquals("我看到 4x4 网格", r.durableText())
    }

    @Test
    fun durableText_transcribesBlankContentReasoning() {
        // 2026-09-11 事故形态：sensenova 把观察写进 reasoning，content 只有空白
        val r = LlmResponse(text = "\n\n", reasoning = "第4行第3列是A", toolExecutionRequests = emptyList())
        assertEquals("[视觉观察记录] 第4行第3列是A", r.durableText())
    }

    @Test
    fun durableText_capsLongReasoning() {
        val r = LlmResponse(text = null, reasoning = "x".repeat(2000), toolExecutionRequests = emptyList())
        val d = r.durableText()!!
        assertTrue(d.startsWith("[视觉观察记录] "))
        assertTrue(d.endsWith("…[截断]"))
        assertEquals("[视觉观察记录] ".length + LlmResponse.REASONING_MAX_CHARS + "…[截断]".length, d.length)
    }

    @Test
    fun durableText_nullWhenBothBlank() {
        val r = LlmResponse(text = "  \n", reasoning = "   ", toolExecutionRequests = emptyList())
        assertNull(r.durableText())
    }
}

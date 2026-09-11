package com.apk.claw.android.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningCaptureTest {

    @Test
    fun extract_readsReasoningField() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"\n\n","reasoning":"第4行第3列是A"}}]}"""
        assertEquals("第4行第3列是A", ReasoningCapture.extract(body))
    }

    @Test
    fun extract_fallsBackToReasoningContent() {
        val body = """{"choices":[{"message":{"content":"","reasoning_content":"深度思考"}}]}"""
        assertEquals("深度思考", ReasoningCapture.extract(body))
    }

    @Test
    fun extract_prefersReasoningOverReasoningContent() {
        val body = """{"choices":[{"message":{"reasoning":"a","reasoning_content":"b"}}]}"""
        assertEquals("a", ReasoningCapture.extract(body))
    }

    @Test
    fun extract_returnsNullForSseMalformedOrMissing() {
        assertNull(ReasoningCapture.extract("data: {\"choices\":[]}\n\n"))
        assertNull(ReasoningCapture.extract(""))
        assertNull(ReasoningCapture.extract(null))
        assertNull(ReasoningCapture.extract("{\"choices\":[]}"))
    }

    @Test
    fun poll_returnsValueThenClears() {
        ReasoningCapture.set("观察")
        assertEquals("观察", ReasoningCapture.poll())
        assertNull(ReasoningCapture.poll())
    }
}

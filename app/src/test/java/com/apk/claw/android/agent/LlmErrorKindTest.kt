package com.apk.claw.android.agent

import com.apk.claw.android.agent.LlmErrorKind.IMAGE_LIMIT
import com.apk.claw.android.agent.LlmErrorKind.NON_RETRYABLE
import com.apk.claw.android.agent.LlmErrorKind.RETRYABLE
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmErrorKindTest {

    @Test
    fun imageCountLimit_detectedEvenWhenWrappedInInvalidRequest() {
        // 真机实证（nnn09.top one-api）：带 type=invalid_request_error，但本质是可恢复的图片数超限
        val body = """{"error":{"message":"image num 51 exceeds limit 50","type":"invalid_request_error","param":"","code":"3"}}"""
        assertEquals(IMAGE_LIMIT, LlmErrorKind.classify("HTTP 500: $body"))
        assertEquals(IMAGE_LIMIT, LlmErrorKind.classify("too many images in one request"))
    }

    @Test
    fun invalidRequest_nonRetryable() {
        assertEquals(
            NON_RETRYABLE,
            LlmErrorKind.classify("""{"error":{"message":"Invalid parameter","type":"invalid_request_error"}}""")
        )
        assertEquals(NON_RETRYABLE, LlmErrorKind.classify("HTTP 413: payload too large"))
    }

    @Test
    fun authAndQuota_nonRetryable() {
        assertEquals(NON_RETRYABLE, LlmErrorKind.classify("401 Unauthorized"))
        assertEquals(NON_RETRYABLE, LlmErrorKind.classify("403 Forbidden"))
        assertEquals(NON_RETRYABLE, LlmErrorKind.classify("insufficient quota"))
    }

    @Test
    fun transientErrors_retryable() {
        assertEquals(RETRYABLE, LlmErrorKind.classify("SocketTimeoutException: timeout"))
        assertEquals(RETRYABLE, LlmErrorKind.classify("HTTP 502 Bad Gateway"))
        assertEquals(RETRYABLE, LlmErrorKind.classify("Connection reset by peer"))
        assertEquals(RETRYABLE, LlmErrorKind.classify(null))
    }
}

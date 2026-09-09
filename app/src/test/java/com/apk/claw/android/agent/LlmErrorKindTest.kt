package com.apk.claw.android.agent

import com.apk.claw.android.agent.LlmErrorKind.IMAGE_LIMIT
import com.apk.claw.android.agent.LlmErrorKind.NON_RETRYABLE
import com.apk.claw.android.agent.LlmErrorKind.RETRYABLE
import com.apk.claw.android.agent.LlmErrorKind.TRANSIENT_REJECT
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

    @Test
    fun relayTransientInvalid_classifiedAsTransientReject() {
        // 真机实证（nnn09.top 坏上游通道）：带图请求间歇 400/500 "inference request is invalid"(400001)，
        // 同请求原样重试即可恢复——既不是"模型不支持视觉"（不能据此剥图降级），
        // 也不是确定性拒绝（不能立即上抛），需要长退避重试
        assertEquals(
            TRANSIENT_REJECT,
            LlmErrorKind.classify("""{"error":{"message":"inference request is invalid","code":400001}}""")
        )
        assertEquals(TRANSIENT_REJECT, LlmErrorKind.classify("HTTP 400: inference request is invalid"))
        assertEquals(TRANSIENT_REJECT, LlmErrorKind.classify("HTTP 500: code 400001, retry later"))
    }

    @Test
    fun relayTransient_beatsNonRetryableMarkers() {
        // 400001 响应可能同时携带 invalid_request_error 等确定性标记，瞬时类必须先判
        assertEquals(
            TRANSIENT_REJECT,
            LlmErrorKind.classify("""{"error":{"message":"inference request is invalid","type":"invalid_request_error","code":400001}}""")
        )
    }
}

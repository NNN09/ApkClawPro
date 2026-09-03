package com.apk.claw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultVerifierTest {

    private class FakeProbe(var foreground: String?) : ResultVerifier.Probe {
        override fun foregroundPackage(): String? = foreground
    }

    /** 手动时钟：sleep 推进时钟，避免真实等待 */
    private class FakeClock(var now: Long = 0L) {
        var sleeps = 0
        fun sleep(ms: Long) { sleeps++; now += ms }
        fun nowMs(): Long = now
    }

    private fun verifyWithClock(
        probe: ResultVerifier.Probe,
        check: (ResultVerifier.Probe) -> Boolean,
        retry: (() -> Unit)? = null,
        clock: FakeClock = FakeClock(),
        timeoutMs: Long = 4000
    ): ResultVerifier.Outcome = ResultVerifier.verify(
        probe = probe,
        check = check,
        retry = retry,
        pollIntervalMs = 500,
        timeoutMs = timeoutMs,
        sleep = { clock.sleep(it) },
        nowMs = { clock.nowMs() }
    )

    // ==================== assertionFor 映射 ====================

    @Test
    fun assertionFor_openApp_checksForegroundPackage() {
        val assertion = ResultVerifier.assertionFor("open_app", mapOf("package_name" to "com.android.settings"))
        assertNotNull(assertion)
        assertTrue(assertion!!.check(FakeProbe("com.android.settings")))
        assertFalse(assertion.check(FakeProbe("com.other.app")))
    }

    @Test
    fun assertionFor_unobservableProbe_skipsAsPass() {
        val assertion = ResultVerifier.assertionFor("open_app", mapOf("package_name" to "com.android.settings"))
        // 无障碍根节点为空（无法观测）→ 视为通过，避免 ROM 差异误报
        assertTrue(assertion!!.check(FakeProbe(null)))
    }

    @Test
    fun assertionFor_nonApplicableTools_returnNull() {
        assertNull(ResultVerifier.assertionFor("tap", mapOf("x" to 100, "y" to 200)))
        assertNull(ResultVerifier.assertionFor("open_app", emptyMap()))
        assertNull(ResultVerifier.assertionFor("open_app", mapOf("package_name" to "")))
    }

    // ==================== verify 轮询与重试 ====================

    @Test
    fun verify_passesImmediately_noRetry() {
        val clock = FakeClock()
        val outcome = verifyWithClock(
            probe = FakeProbe("com.a"),
            check = { it.foregroundPackage() == "com.a" },
            retry = { throw IllegalStateException("should not retry") },
            clock = clock
        )
        assertTrue(outcome.verified)
        assertEquals(0, outcome.retries)
        assertEquals(0, clock.sleeps)
    }

    @Test
    fun verify_retriesOnce_thenPasses() {
        val clock = FakeClock()
        val probe = FakeProbe("com.launcher")
        var retries = 0
        val outcome = verifyWithClock(
            probe = probe,
            check = { p -> p.foregroundPackage() == "com.a" },
            retry = { retries++; probe.foreground = "com.a" },
            clock = clock
        )
        assertTrue(outcome.verified)
        assertEquals(1, outcome.retries)
        assertEquals(1, retries)
        assertTrue(clock.sleeps > 0)
    }

    @Test
    fun verify_failsAfterSingleRetry() {
        var retries = 0
        val outcome = verifyWithClock(
            probe = FakeProbe("com.launcher"),
            check = { false },
            retry = { retries++ }
        )
        assertFalse(outcome.verified)
        assertEquals(1, outcome.retries)
        assertEquals(1, retries)
    }

    @Test
    fun verify_withoutRetry_failsAfterFirstTimeout() {
        val clock = FakeClock()
        val outcome = verifyWithClock(
            probe = FakeProbe(null),
            check = { false },
            retry = null,
            clock = clock,
            timeoutMs = 1000
        )
        assertFalse(outcome.verified)
        assertEquals(0, outcome.retries)
    }
}

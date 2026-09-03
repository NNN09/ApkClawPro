package com.apk.claw.android.compliance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AdmissionControlTest {

    private val zone = ZoneId.of("UTC")

    /** 固定基准时刻 12:00:00 UTC */
    private val noon = Instant.parse("2026-09-03T12:00:00Z").toEpochMilli()

    private fun cfg(
        quietEnabled: Boolean = false,
        quietStartMin: Int = 22 * 60,
        quietEndMin: Int = 7 * 60,
        cooldownSec: Int = 5,
        rateGlobal: Int = 10,
        rateChannel: Int = 5,
        breakerThreshold: Int = 5,
        streak: Int = 0
    ) = ComplianceConfig(
        thirdPartyAutomationEnabled = true,
        quietHoursEnabled = quietEnabled,
        quietStartMin = quietStartMin,
        quietEndMin = quietEndMin,
        cooldownSec = cooldownSec,
        rateGlobalPerMin = rateGlobal,
        rateChannelPerMin = rateChannel,
        breakerThreshold = breakerThreshold,
        failureStreak = streak
    )

    private fun allowed(o: AdmissionControl.Outcome) = o is AdmissionControl.Outcome.Allow
    private fun skipped(o: AdmissionControl.Outcome) = o is AdmissionControl.Outcome.Skip
    private fun noticed(o: AdmissionControl.Outcome) = o is AdmissionControl.Outcome.Notice

    // ==================== 频率上限 ====================

    @Test
    fun rateLimit_globalOverCap_skipsManualAndAutomated() {
        val ctl = AdmissionControl(zone)
        val c = cfg(rateGlobal = 3, rateChannel = 0)
        // 模拟空闲期逐条到达即启动：check 放行 + recordStart 登记
        repeat(3) {
            assertTrue(allowed(ctl.check("CH", false, c, noon + it * 1_000)))
            ctl.recordStart("CH", noon + it * 1_000)
        }
        // 手动也被拒：频率上限防御消息风暴
        val skip = ctl.check("CH", false, c, noon + 10_000)
        assertTrue(skipped(skip))
        assertEquals(AdmissionControl.Reason.RATE_LIMITED, (skip as AdmissionControl.Outcome.Skip).reason)
    }

    @Test
    fun rateLimit_windowSlidesAfter60s() {
        val ctl = AdmissionControl(zone)
        val c = cfg(rateGlobal = 1, rateChannel = 0)
        ctl.recordStart("CH", noon)
        assertTrue(skipped(ctl.check("CH", false, c, noon + 10_000)))
        // 61 秒后窗口滑出
        assertTrue(allowed(ctl.check("CH", false, c, noon + 61_000)))
    }

    @Test
    fun rateLimit_perChannelIndependentOfGlobal() {
        val ctl = AdmissionControl(zone)
        val c = cfg(rateGlobal = 100, rateChannel = 2)
        repeat(2) {
            ctl.recordStart("A", noon + it * 1_000)
        }
        assertTrue(skipped(ctl.check("A", false, c, noon + 5_000)))
        // 另一渠道不受 A 的额度影响
        assertTrue(allowed(ctl.check("B", false, c, noon + 5_000)))
    }

    @Test
    fun rateLimit_zeroMeansDisabled() {
        val ctl = AdmissionControl(zone)
        val c = cfg(rateGlobal = 0, rateChannel = 0)
        repeat(20) { assertTrue(allowed(ctl.check("CH", false, c, noon + it * 100L))) }
    }

    @Test
    fun recordStart_feedsWindowCounters() {
        val ctl = AdmissionControl(zone)
        val c = cfg(rateGlobal = 2, rateChannel = 1)
        ctl.recordStart("A", noon)
        ctl.recordStart("B", noon + 1_000)
        // 2 次全局启动已触发全局上限 2；A 渠道仅 1 次未触发单渠道上限
        assertTrue(skipped(ctl.check("A", false, c, noon + 2_000)))
    }

    // ==================== 冷却（只拦自动任务） ====================

    @Test
    fun cooldown_blocksAutomatedButAllowsManual() {
        val ctl = AdmissionControl(zone)
        val c = cfg(cooldownSec = 5)
        ctl.recordStart("CH", noon)
        val automated = ctl.check("CH", true, c, noon + 2_000)
        assertTrue(skipped(automated))
        assertEquals(AdmissionControl.Reason.COOLDOWN, (automated as AdmissionControl.Outcome.Skip).reason)
        assertTrue(allowed(ctl.check("CH", false, c, noon + 2_000)))
    }

    @Test
    fun cooldown_expiresAfterInterval() {
        val ctl = AdmissionControl(zone)
        val c = cfg(cooldownSec = 5)
        ctl.recordStart("CH", noon)
        assertTrue(allowed(ctl.check("CH", true, c, noon + 5_000)))
    }

    // ==================== 静默时段 ====================

    @Test
    fun quietHours_blocksOnlyAutomatedInsideWindow() {
        val ctl = AdmissionControl(zone)
        // 静默 22:00–07:00（跨零点）；UTC 基准 noon=12:00 不在窗口内
        val c = cfg(quietEnabled = true, quietStartMin = 22 * 60, quietEndMin = 7 * 60)
        assertTrue(allowed(ctl.check("CH", true, c, noon)))
        val night = Instant.parse("2026-09-03T23:30:00Z").toEpochMilli()
        val skip = ctl.check("CH", true, c, night)
        assertTrue(skipped(skip))
        assertEquals(AdmissionControl.Reason.QUIET_HOURS, (skip as AdmissionControl.Outcome.Skip).reason)
        // 手动消息在静默窗口内不受影响
        assertTrue(allowed(ctl.check("CH", false, c, night)))
    }

    @Test
    fun quietHours_wrapsMidnight() {
        val ctl = AdmissionControl(zone)
        val c = cfg(quietEnabled = true, quietStartMin = 22 * 60, quietEndMin = 7 * 60)
        val lateNight = Instant.parse("2026-09-04T02:00:00Z").toEpochMilli()
        assertTrue(cfg(quietEnabled = true).isInQuietWindow(2 * 60))
        assertTrue(skipped(ctl.check("CH", true, c, lateNight)))
        val morning = Instant.parse("2026-09-04T08:00:00Z").toEpochMilli()
        assertTrue(allowed(ctl.check("CH", true, c, morning)))
    }

    @Test
    fun quietHours_sameStartEndMeansAllDay() {
        assertTrue(cfg(quietEnabled = true, quietStartMin = 300, quietEndMin = 300).isInQuietWindow(0))
        assertTrue(cfg(quietEnabled = true, quietStartMin = 300, quietEndMin = 300).isInQuietWindow(1_400))
        assertTrue(!cfg(quietEnabled = false).isInQuietWindow(600))
    }

    // ==================== 熔断 ====================

    @Test
    fun breaker_openSkipsAutomatedAndNoticesManual() {
        val ctl = AdmissionControl(zone)
        val c = cfg(breakerThreshold = 5, streak = 5)
        val automated = ctl.check("CH", true, c, noon)
        assertTrue(skipped(automated))
        assertEquals(AdmissionControl.Reason.BREAKER, (automated as AdmissionControl.Outcome.Skip).reason)
        val manual = ctl.check("CH", false, c, noon)
        assertTrue(noticed(manual))
        assertEquals(AdmissionControl.Reason.BREAKER, (manual as AdmissionControl.Outcome.Notice).reason)
    }

    @Test
    fun breaker_belowThresholdAllowed() {
        val ctl = AdmissionControl(zone)
        val c = cfg(breakerThreshold = 5, streak = 4)
        assertTrue(allowed(ctl.check("CH", true, c, noon)))
    }

    @Test
    fun breaker_zeroThresholdDisablesBreaker() {
        val ctl = AdmissionControl(zone)
        val c = cfg(breakerThreshold = 0, streak = 99)
        assertTrue(allowed(ctl.check("CH", true, c, noon)))
    }

    @Test
    fun breaker_takesPrecedenceOverRateForAutomated() {
        val ctl = AdmissionControl(zone)
        val c = cfg(breakerThreshold = 5, streak = 5, rateGlobal = 100)
        // 即使频率没超，熔断也先拦截自动任务
        val o = ctl.check("CH", true, c, noon)
        assertTrue(skipped(o))
        assertEquals(AdmissionControl.Reason.BREAKER, (o as AdmissionControl.Outcome.Skip).reason)
    }
}

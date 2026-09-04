package com.apk.claw.android.compliance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ComplianceConfig.formatHm/parseHm 的 wire 格式测试。
 * 两个 helper 同时被 LAN 配置页(/api/compliance)与 APP 内合规配置页使用，
 * 此处锁定二者共同的 HH:mm 语义。
 */
class ComplianceConfigHmTest {

    @Test
    fun formatHm_coversDayBoundaries() {
        assertEquals("00:00", ComplianceConfig.formatHm(0))
        assertEquals("22:00", ComplianceConfig.formatHm(ComplianceConfig.DEFAULT_QUIET_START_MIN))
        assertEquals("07:00", ComplianceConfig.formatHm(ComplianceConfig.DEFAULT_QUIET_END_MIN))
        assertEquals("23:59", ComplianceConfig.formatHm(1439))
    }

    @Test
    fun parseHm_acceptsValidTimes() {
        assertEquals(0, ComplianceConfig.parseHm("00:00"))
        assertEquals(425, ComplianceConfig.parseHm("07:05"))
        assertEquals(1320, ComplianceConfig.parseHm("22:00"))
        assertEquals(1439, ComplianceConfig.parseHm("23:59"))
    }

    @Test
    fun parseHm_rejectsOutOfRangeAndMalformed() {
        assertNull(ComplianceConfig.parseHm("24:00"))
        assertNull(ComplianceConfig.parseHm("22:60"))
        assertNull(ComplianceConfig.parseHm("-1:30"))
        assertNull(ComplianceConfig.parseHm("2200"))
        assertNull(ComplianceConfig.parseHm("22"))
        assertNull(ComplianceConfig.parseHm("22:0x"))
        assertNull(ComplianceConfig.parseHm("aa:bb"))
        assertNull(ComplianceConfig.parseHm(""))
        assertNull(ComplianceConfig.parseHm("22:00:00"))
        assertNull(ComplianceConfig.parseHm(" 22:00"))
    }

    @Test
    fun parseFormatRoundTrip() {
        for (minutes in listOf(0, 425, 1320, 1439)) {
            assertEquals(minutes, ComplianceConfig.parseHm(ComplianceConfig.formatHm(minutes)))
        }
    }
}

package com.apk.claw.android.tool.impl.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F9 系统工具包纯映射函数测试（不触碰 Android 运行时） */
class SystemToolSupportTest {

    // ==================== 事件时间 ====================

    @Test
    fun parseEventTime_acceptsEpochMillis() {
        assertEquals(1757280000000L, SystemToolSupport.parseEventTime(1757280000000L))
        assertEquals(1757280000000L, SystemToolSupport.parseEventTime(1757280000000.0))
    }

    @Test
    fun parseEventTime_acceptsLocalDateTimeString() {
        // "2026-09-03 14:30" 本地时间 → epoch（用同一时区往返验证）
        val parsed = SystemToolSupport.parseEventTime("2026-09-03 14:30")!!
        assertEquals("2026-09-03 14:30", SystemToolSupport.formatEventTime(parsed))
    }

    @Test
    fun parseEventTime_acceptsIsoWithT() {
        val withSpace = SystemToolSupport.parseEventTime("2026-09-03 14:30")
        val withT = SystemToolSupport.parseEventTime("2026-09-03T14:30")
        assertEquals(withSpace, withT)
    }

    @Test
    fun parseEventTime_rejectsGarbage() {
        assertNull(SystemToolSupport.parseEventTime("not a date"))
        assertNull(SystemToolSupport.parseEventTime("2026-13-99 25:00"))
        assertNull(SystemToolSupport.parseEventTime(""))
        assertNull(SystemToolSupport.parseEventTime(null))
        assertNull(SystemToolSupport.parseEventTime(-5L))
    }

    @Test
    fun parseDateStartOfDay_givesMidnight() {
        val start = SystemToolSupport.parseDateStartOfDay("2026-09-03")!!
        val formatted = SystemToolSupport.formatEventTime(start)
        assertTrue(formatted.startsWith("2026-09-03 00:00"))
        assertNull(SystemToolSupport.parseDateStartOfDay("2026/09/03"))
    }

    // ==================== 媒体/音量/勿扰/设置页 ====================

    @Test
    fun mediaKeyCode_knownActions() {
        assertEquals(126, SystemToolSupport.mediaKeyCode("play"))
        assertEquals(127, SystemToolSupport.mediaKeyCode("pause"))
        assertEquals(87, SystemToolSupport.mediaKeyCode("next"))
        assertEquals(88, SystemToolSupport.mediaKeyCode("previous"))
        assertEquals(86, SystemToolSupport.mediaKeyCode("stop"))
        assertNull(SystemToolSupport.mediaKeyCode("eject"))
    }

    @Test
    fun mediaKeyCode_caseInsensitive() {
        assertEquals(SystemToolSupport.mediaKeyCode("PLAY"), SystemToolSupport.mediaKeyCode("play"))
    }

    @Test
    fun volumeStream_knownNames() {
        assertEquals(3, SystemToolSupport.volumeStream("music"))
        assertEquals(3, SystemToolSupport.volumeStream("media"))
        assertEquals(2, SystemToolSupport.volumeStream("ring"))
        assertEquals(4, SystemToolSupport.volumeStream("alarm"))
        assertEquals(5, SystemToolSupport.volumeStream("notification"))
        assertNull(SystemToolSupport.volumeStream("voice_call"))
    }

    @Test
    fun dndFilter_knownModes() {
        assertEquals(1, SystemToolSupport.dndFilter("off"))
        assertEquals(2, SystemToolSupport.dndFilter("priority"))
        assertEquals(4, SystemToolSupport.dndFilter("alarms"))
        assertEquals(3, SystemToolSupport.dndFilter("none"))
        assertNull(SystemToolSupport.dndFilter("silent"))
    }

    @Test
    fun settingsPage_knownPages() {
        assertTrue(SystemToolSupport.settingsPage("wifi")!!.endsWith("WIFI_SETTINGS"))
        assertTrue(SystemToolSupport.settingsPage("dnd_access")!!.contains("NOTIFICATION_POLICY"))
        assertTrue(SystemToolSupport.settingsPage("write_settings")!!.contains("WRITE_SETTINGS"))
        assertNull(SystemToolSupport.settingsPage("root"))
    }

    // ==================== 电话号码 ====================

    @Test
    fun sanitizePhoneNumber_keepsValidChars() {
        assertEquals("+86 138-0013-8000", SystemToolSupport.sanitizePhoneNumber("+86 138-0013-8000"))
        assertEquals("(0571)-8888", SystemToolSupport.sanitizePhoneNumber("(0571)-8888"))
        assertEquals("*#06#", SystemToolSupport.sanitizePhoneNumber("*#06#"))
    }

    @Test
    fun sanitizePhoneNumber_stripsInjectionChars() {
        // tel: scheme 注入防护：字母等字符一律剔除
        assertEquals("123456", SystemToolSupport.sanitizePhoneNumber("123abc456"))
        assertNull(SystemToolSupport.sanitizePhoneNumber("abcdef"))
        assertNull(SystemToolSupport.sanitizePhoneNumber(""))
    }

    // ==================== 亮度/闹钟 ====================

    @Test
    fun percentToBrightness_mapsTo255Scale() {
        assertEquals(0, SystemToolSupport.percentToBrightness(0))
        assertEquals(255, SystemToolSupport.percentToBrightness(100))
        assertEquals(127, SystemToolSupport.percentToBrightness(50))
    }

    @Test
    fun isValidAlarmTime_bounds() {
        assertTrue(SystemToolSupport.isValidAlarmTime(0, 0))
        assertTrue(SystemToolSupport.isValidAlarmTime(23, 59))
        assertFalse(SystemToolSupport.isValidAlarmTime(24, 0))
        assertFalse(SystemToolSupport.isValidAlarmTime(7, 60))
        assertFalse(SystemToolSupport.isValidAlarmTime(-1, 30))
    }
}

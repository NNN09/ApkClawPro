package com.apk.claw.android.tool

import com.apk.claw.android.tool.impl.ScheduleTaskTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * parseDaysOfWeek 严格性：LLM 或 LAN API 传入垃圾输入（"mon-fri"、"周一到周五"）
 * 必须报错拒绝，而不是被 mapNotNull 静默丢成空集（空集语义 = 每天执行）。
 */
class ScheduleTaskToolParseTest {

    @Test
    fun validDaySets() {
        assertEquals(setOf(1, 3, 5), ScheduleTaskTool.parseDaysOfWeek("1,3,5"))
        assertEquals(setOf(1, 2), ScheduleTaskTool.parseDaysOfWeek(" 1，2 "))
        assertEquals(setOf(7), ScheduleTaskTool.parseDaysOfWeek("7"))
        // 重复天去重后仍是集合语义
        assertEquals(setOf(1, 2), ScheduleTaskTool.parseDaysOfWeek("1,1,2"))
    }

    @Test
    fun emptyMeansEveryDay() {
        assertEquals(emptySet<Int>(), ScheduleTaskTool.parseDaysOfWeek(""))
        assertEquals(emptySet<Int>(), ScheduleTaskTool.parseDaysOfWeek("   "))
    }

    @Test
    fun garbageInputRejectedInsteadOfSilentEveryDay() {
        assertNull(ScheduleTaskTool.parseDaysOfWeek("abc"))
        assertNull(ScheduleTaskTool.parseDaysOfWeek("mon-fri"))
        assertNull(ScheduleTaskTool.parseDaysOfWeek("1,x"))
        assertNull(ScheduleTaskTool.parseDaysOfWeek("周一到周五"))
        assertNull(ScheduleTaskTool.parseDaysOfWeek("1,"))
        assertNull(ScheduleTaskTool.parseDaysOfWeek("0"))
        assertNull(ScheduleTaskTool.parseDaysOfWeek("8"))
    }
}

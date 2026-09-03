package com.apk.claw.android.agent.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Calendar

class ScheduledTaskStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun task(
        id: String,
        hour: Int = 8,
        minute: Int = 30,
        days: Set<Int> = emptySet(),
        enabled: Boolean = true
    ) = ScheduledTaskStore.ScheduledTask(
        id = id,
        name = "n-$id",
        task = "打开设置看版本",
        channel = "IN_APP",
        senderId = "local",
        hour = hour,
        minute = minute,
        daysOfWeek = days,
        enabled = enabled,
        createdAt = 1_000L
    )

    @Before
    fun setUp() {
        ScheduledTaskStore.init(tmp.root)
    }

    // ==================== CRUD 与落盘 ====================

    @Test
    fun add_thenGet_roundTripsAcrossReinit() {
        ScheduledTaskStore.add(task("t1", days = setOf(1, 5)))
        ScheduledTaskStore.init(tmp.root)
        val loaded = ScheduledTaskStore.get("t1")
        assertTrue(loaded != null)
        assertEquals(setOf(1, 5), loaded!!.daysOfWeek)
        assertEquals("IN_APP", loaded.channel)
        assertEquals("local", loaded.senderId)
    }

    @Test
    fun add_rejectsInvalidTimeAndBlankTask() {
        try {
            ScheduledTaskStore.add(task("t1", hour = 24))
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            ScheduledTaskStore.add(
                task("t2").copy(task = "  ")
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun add_enforcesCap() {
        repeat(ScheduledTaskStore.MAX_TASKS) { ScheduledTaskStore.add(task("t$it")) }
        try {
            ScheduledTaskStore.add(task("overflow"))
            throw AssertionError("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun remove_andSetEnabled() {
        ScheduledTaskStore.add(task("t1"))
        assertTrue(ScheduledTaskStore.remove("t1"))
        assertFalse(ScheduledTaskStore.remove("t1"))
        assertNull(ScheduledTaskStore.get("t1"))

        ScheduledTaskStore.add(task("t2"))
        val disabled = ScheduledTaskStore.setEnabled("t2", false)!!
        assertFalse(disabled.enabled)
        assertEquals(false, ScheduledTaskStore.get("t2")!!.enabled)
        assertNull(ScheduledTaskStore.setEnabled("missing", true))
    }

    @Test
    fun markTriggered_persists() {
        ScheduledTaskStore.add(task("t1"))
        ScheduledTaskStore.markTriggered("t1", 9_999L)
        ScheduledTaskStore.init(tmp.root)
        assertEquals(9_999L, ScheduledTaskStore.get("t1")!!.lastTriggerAt)
    }

    // ==================== nextTriggerAt ====================

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun calendarOf(millis: Long): Calendar = Calendar.getInstance().apply { timeInMillis = millis }

    @Test
    fun nextTriggerAt_sameDayFuture() {
        // 2026-09-03(周四) 07:00 → 当天 08:30
        val t = task("t1", hour = 8, minute = 30)
        val next = ScheduledTaskStore.nextTriggerAt(t, millis(2026, 9, 3, 7, 0))!!
        val cal = calendarOf(next)
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, cal.get(Calendar.MONTH))
        assertEquals(3, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
    }

    @Test
    fun nextTriggerAt_pastTime_rollsToTomorrow() {
        val t = task("t1", hour = 8, minute = 0)
        val next = ScheduledTaskStore.nextTriggerAt(t, millis(2026, 9, 3, 9, 0))!!
        assertEquals(4, calendarOf(next).get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun nextTriggerAt_daysFilter_rollsToNextMatch() {
        // 2026-09-03 是周四(ISO 4)；只允许周一(1) → 下一个周一是 2026-09-07
        val t = task("t1", hour = 22, minute = 0, days = setOf(1))
        val next = ScheduledTaskStore.nextTriggerAt(t, millis(2026, 9, 3, 10, 0))!!
        assertEquals(7, calendarOf(next).get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun nextTriggerAt_exactBoundaryTime_rollsForward() {
        // 恰好等于触发时刻 → 视为已触发，排到明天
        val t = task("t1", hour = 8, minute = 0)
        val next = ScheduledTaskStore.nextTriggerAt(t, millis(2026, 9, 3, 8, 0))!!
        assertEquals(4, calendarOf(next).get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun nextTriggerAt_sundayIsSeven() {
        // 2026-09-04 是周五(ISO 5)；只允许周日(7) → 2026-09-06
        val t = task("t1", hour = 20, minute = 0, days = setOf(7))
        val next = ScheduledTaskStore.nextTriggerAt(t, millis(2026, 9, 4, 10, 0))!!
        val cal = calendarOf(next)
        assertEquals(Calendar.SUNDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(6, cal.get(Calendar.DAY_OF_MONTH))
    }
}

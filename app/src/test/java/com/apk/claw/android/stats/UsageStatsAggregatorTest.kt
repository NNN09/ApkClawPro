package com.apk.claw.android.stats

import com.apk.claw.android.agent.store.TaskHistoryStore
import com.apk.claw.android.stats.UsageStatsAggregator.UsageStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * F13 用量聚合器纯 JVM 单测：固定 UTC 时区与固定"当前时刻"，覆盖日分桶、窗口过滤、
 * 成功率/平均值、渠道与发送者聚合、高频任务 Top N。
 */
class UsageStatsAggregatorTest {

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-09-04T12:00:00Z").toEpochMilli()

    private fun rec(
        start: Instant,
        status: String,
        tokens: Int,
        channel: String = "DINGTALK",
        sender: String = "sender01",
        task: String = "查天气"
    ) = TaskHistoryStore.TaskRecord(
        id = "t-$start-$tokens-$status",
        startTime = start.toEpochMilli(),
        endTime = start.plusSeconds(60).toEpochMilli(),
        channel = channel,
        sender = sender,
        task = task,
        status = status,
        rounds = 2,
        toolCalls = 3,
        tokens = tokens
    )

    private fun dayAt(dayOffsetFromToday: Long, hour: Int = 10) =
        Instant.parse("2026-09-04T12:00:00Z").atZone(zone).toLocalDate()
            .minusDays(dayOffsetFromToday).atTime(hour, 0).atZone(zone).toInstant()

    // ======== 空输入 ========

    @Test
    fun `empty input yields zeroed totals and zero-filled daily window`() {
        val s = aggregate(emptyList(), 7)
        assertEquals(0, s.totalTasks)
        assertEquals(0.0, s.successRate, 1e-9)
        assertEquals(0L, s.totalTokens)
        assertEquals(0, s.avgTokensPerTask)
        assertEquals(7, s.daily.size)
        assertTrue(s.daily.all { it.tasks == 0 && it.tokens == 0L })
        // 升序：最早的一天在最前
        assertEquals("2026-08-29", s.daily.first().date)
        assertEquals("2026-09-04", s.daily.last().date)
        assertTrue(s.channels.isEmpty())
        assertTrue(s.senders.isEmpty())
        assertTrue(s.topTasks.isEmpty())
    }

    // ======== 日分桶与时区 ========

    @Test
    fun `day bucketing follows provided zone`() {
        // 23:30Z 在 UTC 属 9 月 3 日，在 UTC+8 已属 9 月 4 日
        val start = Instant.parse("2026-09-03T23:30:00Z")
        val utc = aggregate(listOf(rec(start, "COMPLETED", 100)), 7, zone)
        val shanghai = aggregate(listOf(rec(start, "COMPLETED", 100)), 7, ZoneId.of("Asia/Shanghai"))
        assertEquals(100L, utc.daily.first { it.date == "2026-09-03" }.tokens)
        assertEquals(0L, utc.daily.first { it.date == "2026-09-04" }.tokens)
        assertEquals(100L, shanghai.daily.first { it.date == "2026-09-04" }.tokens)
        assertEquals(0L, shanghai.daily.first { it.date == "2026-09-03" }.tokens)
    }

    // ======== 窗口过滤 ========

    @Test
    fun `window filter excludes records older than N days`() {
        val old = rec(dayAt(8), "COMPLETED", 50)
        assertEquals(0, aggregate(listOf(old), 7).totalTasks)
        // 窗口起点是 (days-1) 天前的零点：8 天前的记录要 days=9 才覆盖
        assertEquals(1, aggregate(listOf(old), 9).totalTasks)
        // 边界：窗口首日 00:00 整的记录应计入
        val edge = rec(dayAt(6, 0), "COMPLETED", 50)
        assertEquals(1, aggregate(listOf(edge), 7).totalTasks)
    }

    // ======== 总量 / 成功率 / 均值 ========

    @Test
    fun `totals success rate and average tokens`() {
        val records = listOf(
            rec(dayAt(0), "COMPLETED", 100),
            rec(dayAt(0), "COMPLETED", 200),
            rec(dayAt(1), "FAILED", 50),
            rec(dayAt(1), "CANCELLED", 10),
            rec(dayAt(2), "WAIT_TIMEOUT", 40)
        )
        val s = aggregate(records, 7)
        assertEquals(5, s.totalTasks)
        assertEquals(2, s.completed)
        assertEquals(1, s.failed)
        assertEquals(1, s.cancelled)
        assertEquals(1, s.waitTimeout)
        assertEquals(0.4, s.successRate, 1e-9)
        assertEquals(400L, s.totalTokens)
        assertEquals(80, s.avgTokensPerTask)
    }

    @Test
    fun `daily aggregation sums same-day records and zero-fills gaps`() {
        val records = listOf(
            rec(dayAt(2), "COMPLETED", 100, channel = "FEISHU"),
            rec(dayAt(2, 15), "FAILED", 30, channel = "FEISHU"),
            rec(dayAt(0), "COMPLETED", 70)
        )
        val s = aggregate(records, 7)
        val day0 = s.daily.first { it.date == "2026-09-04" }
        val day2 = s.daily.first { it.date == "2026-09-02" }
        val day4 = s.daily.first { it.date == "2026-08-31" }
        assertEquals(1 to 70L, day0.tasks to day0.tokens)
        assertEquals(2 to 130L, day2.tasks to day2.tokens)
        assertEquals(1, day2.completed)
        assertEquals(0 to 0L, day4.tasks to day4.tokens)
    }

    // ======== 渠道聚合 ========

    @Test
    fun `channel aggregation splits completed and failed and sorts by volume`() {
        val records = listOf(
            rec(dayAt(0), "COMPLETED", 100, channel = "DINGTALK"),
            rec(dayAt(0), "FAILED", 20, channel = "DINGTALK"),
            rec(dayAt(1), "COMPLETED", 300, channel = "FEISHU"),
            rec(dayAt(1), "COMPLETED", 10, channel = "IN_APP")
        )
        val s = aggregate(records, 7)
        assertEquals(listOf("DINGTALK", "FEISHU", "IN_APP"), s.channels.map { it.channel })
        val dingtalk = s.channels.first()
        assertEquals(2, dingtalk.tasks)
        assertEquals(1, dingtalk.completed)
        assertEquals(1, dingtalk.failed)
        assertEquals(120L, dingtalk.tokens)
    }

    // ======== 发送者掩码 ========

    @Test
    fun `sender masking truncates to six chars and merges same prefix`() {
        val records = listOf(
            rec(dayAt(0), "COMPLETED", 100, sender = "abcdefFROMPLATFORM"),
            rec(dayAt(1), "FAILED", 30, sender = "abcdefOTHERUSER"),
            rec(dayAt(1), "COMPLETED", 50, sender = "xyz987")
        )
        val s = aggregate(records, 7)
        val merged = s.senders.first { it.sender == "abcdef" }
        assertEquals(2, merged.tasks)
        assertEquals(130L, merged.tokens)
        assertTrue(s.senders.any { it.sender == "xyz987" })
        assertTrue(s.senders.none { it.sender.length > 6 })
    }

    // ======== 高频任务 Top N ========

    @Test
    fun `top tasks group by normalized prefix order by count capped at five`() {
        // 40 字符长任务：两条记录共享前 30 字符分组键，应聚合为一条场景
        val longTask = "整理相册截图任务描述".repeat(4)
        val records = listOf(
            rec(dayAt(0), "COMPLETED", 100, task = longTask),
            rec(dayAt(1), "FAILED", 20, task = "$longTask（补充说明不同后缀）"),
            rec(dayAt(2), "COMPLETED", 50, task = " 查   天气 "),
            rec(dayAt(3), "COMPLETED", 10, task = "查天气"),
            rec(dayAt(4), "COMPLETED", 5, task = "T1"),
            rec(dayAt(5), "COMPLETED", 4, task = "T2"),
            rec(dayAt(6), "COMPLETED", 3, task = "T3"),
            rec(dayAt(6), "COMPLETED", 2, task = "T4")
        )
        val s = aggregate(records, 7)
        assertEquals(UsageStatsAggregator.TOP_TASKS, s.topTasks.size)
        val merged = s.topTasks.first()
        // 长任务被截断为同一前缀键：2 次、tokens 合并；空白归并后的"查 天气"也是 2 次但 tokens 少
        assertEquals(2, merged.count)
        assertEquals(120L, merged.tokens)
        assertEquals(longTask.take(30), merged.task)
        val weather = s.topTasks.first { it.task == "查 天气" }
        // "查 天气"（空白归并后含空格）与"查天气"是不同分组键，各 1 次
        assertEquals(1, weather.count)
        assertEquals(50L, weather.tokens)
        // 其余单次任务按 tokens 降序排列，容量截断后末位是 T2
        assertEquals("T2", s.topTasks.last().task)
    }

    // ======== 入参钳制 ========

    @Test
    fun `days clamped to max`() {
        val s = aggregate(emptyList(), 500)
        assertEquals(UsageStatsAggregator.MAX_DAYS, s.days)
        assertEquals(UsageStatsAggregator.MAX_DAYS, s.daily.size)
    }

    private fun aggregate(records: List<TaskHistoryStore.TaskRecord>, days: Int, zone: ZoneId = this.zone): UsageStats =
        UsageStatsAggregator.aggregate(records, days, now, zone)
}

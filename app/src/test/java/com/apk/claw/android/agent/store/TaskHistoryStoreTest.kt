package com.apk.claw.android.agent.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskHistoryStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun record(id: String, status: TaskHistoryStore.Status = TaskHistoryStore.Status.COMPLETED) =
        TaskHistoryStore.TaskRecord(
            id = id,
            startTime = 1_000L,
            endTime = 2_000L,
            channel = "TELEGRAM",
            sender = "user-abc-very-long-identifier",
            task = "打开设置查看版本号",
            status = status.name,
            rounds = 5,
            toolCalls = 12,
            tokens = 9_876,
            error = if (status == TaskHistoryStore.Status.FAILED) "boom" else "",
            toolTrace = List(120) { "tool_$it(arg=$it)" }
        )

    @Test
    fun append_thenList_roundTrips() {
        TaskHistoryStore.append(tmp.root, record("m1", TaskHistoryStore.Status.FAILED))
        val all = TaskHistoryStore.list(tmp.root)
        assertEquals(1, all.size)
        val r = all[0]
        assertEquals("m1", r.id)
        assertEquals("TELEGRAM", r.channel)
        assertEquals(5, r.rounds)
        assertEquals(9_876, r.tokens)
        assertEquals("boom", r.error)
    }

    @Test
    fun list_returnsNewestFirst_andRespectsLimit() {
        repeat(5) { TaskHistoryStore.append(tmp.root, record("m$it")) }
        val all = TaskHistoryStore.list(tmp.root)
        assertEquals(listOf("m4", "m3", "m2", "m1", "m0"), all.map { it.id })
        assertEquals(listOf("m4", "m3"), TaskHistoryStore.list(tmp.root, 2).map { it.id })
    }

    @Test
    fun append_trimsWhenBeyondCap() {
        repeat(TaskHistoryStore.MAX_RECORDS + 10) { TaskHistoryStore.append(tmp.root, record("m$it")) }
        val all = TaskHistoryStore.list(tmp.root, Int.MAX_VALUE)
        // 裁剪发生在写入时：首次超过上限裁到 TRIM_TO，之后逐条增长
        assertTrue("size=${all.size}", all.size in TaskHistoryStore.TRIM_TO..TaskHistoryStore.MAX_RECORDS)
        assertEquals("m${TaskHistoryStore.MAX_RECORDS + 9}", all.first().id)
    }

    @Test
    fun toolTrace_sanitized_toCap() {
        TaskHistoryStore.append(tmp.root, record("m1"))
        val r = TaskHistoryStore.list(tmp.root)[0]
        assertEquals(80, r.toolTrace.size)
        assertTrue(r.toolTrace.all { it.length <= 120 })
    }

    @Test
    fun list_emptyDir_returnsEmpty() {
        assertTrue(TaskHistoryStore.list(tmp.newFolder()).isEmpty())
    }
}

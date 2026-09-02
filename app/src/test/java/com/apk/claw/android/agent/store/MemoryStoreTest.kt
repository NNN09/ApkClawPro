package com.apk.claw.android.agent.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun save_thenAll_roundTrips() {
        MemoryStore.init(tmp.root)
        MemoryStore.save("用户喜欢喝拿铁", "2026-09-02 10:00")
        assertEquals(listOf("用户喜欢喝拿铁"), MemoryStore.all())
    }

    @Test fun save_blankTextReturnsFalse() {
        MemoryStore.init(tmp.root)
        assertFalse(MemoryStore.save("   "))
        assertTrue(MemoryStore.all().isEmpty())
    }

    @Test fun save_duplicateIsNoop() {
        MemoryStore.init(tmp.root)
        MemoryStore.save("A", "2026-09-02 10:00")
        MemoryStore.save("A", "2026-09-02 11:00")
        assertEquals(listOf("A"), MemoryStore.all())
    }

    @Test fun save_trimsToMaxEntriesFIFO() {
        MemoryStore.init(tmp.root)
        repeat(55) { i -> MemoryStore.save("m$i", "2026-09-02 10:00") }
        val all = MemoryStore.all()
        assertEquals(50, all.size)
        assertEquals("m5", all.first())
    }

    @Test fun delete_removesMatchingEntry() {
        MemoryStore.init(tmp.root)
        MemoryStore.save("A", "2026-09-02 10:00")
        MemoryStore.save("B", "2026-09-02 10:01")
        assertTrue(MemoryStore.delete("B"))
        assertEquals(listOf("A"), MemoryStore.all())
        assertFalse(MemoryStore.delete("不存在"))
    }

    @Test fun promptSection_emptyWhenNoMemory() {
        MemoryStore.init(tmp.root)
        assertEquals("", MemoryStore.promptSection())
    }

    @Test fun promptSection_listsAllEntries() {
        MemoryStore.init(tmp.root)
        MemoryStore.save("A", "2026-09-02 10:00")
        val s = MemoryStore.promptSection()
        assertTrue(s.startsWith("## 长期记忆"))
        assertTrue(s.contains("- A"))
    }

    @Test fun all_toleratesManuallyEditedLines() {
        MemoryStore.init(tmp.root)
        MemoryStore.save("A", "2026-09-02 10:00")
        tmp.root.resolve("memory.md").appendText("手工加的一行\n")
        assertEquals(listOf("A", "手工加的一行"), MemoryStore.all())
    }

    @Test fun concurrentSaves_keepAllEntries() {
        // Agent 工具线程与调试入口并发保存；无锁时读-改-写会互相覆盖丢条目。
        // 总条数须低于 MAX_ENTRIES(50)，避免触发 FIFO 裁剪
        MemoryStore.init(tmp.root)
        val threads = 4
        val savesPerThread = 10
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val futures = (0 until threads).map { t ->
                pool.submit {
                    repeat(savesPerThread) { k -> MemoryStore.save("记忆-$t-$k", "2026-09-02 10:00") }
                }
            }
            futures.forEach { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(threads * savesPerThread, MemoryStore.all().size)
        } finally {
            pool.shutdownNow()
        }
    }
}

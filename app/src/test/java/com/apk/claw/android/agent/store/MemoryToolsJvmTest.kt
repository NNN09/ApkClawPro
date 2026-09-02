package com.apk.claw.android.agent.store

import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.tool.impl.MemoryDeleteTool
import com.apk.claw.android.tool.impl.MemoryListTool
import com.apk.claw.android.tool.impl.MemorySaveTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryToolsJvmTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun saveTool_writesToStore() {
        MemoryStore.init(tmp.root)
        val r: ToolResult = MemorySaveTool().execute(mapOf("text" to "用户喜欢喝拿铁"))
        assertTrue(r.isSuccess)
        assertEquals(listOf("用户喜欢喝拿铁"), MemoryStore.all())
    }

    @Test fun deleteTool_removesEntry() {
        MemoryStore.init(tmp.root)
        MemoryStore.save("A", "2026-09-02 10:00")
        val r: ToolResult = MemoryDeleteTool().execute(mapOf("text" to "A"))
        assertTrue(r.isSuccess)
        assertTrue(MemoryStore.all().isEmpty())
    }

    @Test fun listTool_returnsNumberedEntries() {
        MemoryStore.init(tmp.root)
        MemoryStore.save("A", "2026-09-02 10:00")
        MemoryStore.save("B", "2026-09-02 10:01")
        val r: ToolResult = MemoryListTool().execute(emptyMap())
        assertTrue(r.isSuccess)
        assertTrue(r.data!!.contains("1. A"))
        assertTrue(r.data!!.contains("2. B"))
    }

    @Test fun listTool_emptyMemory() {
        MemoryStore.init(tmp.root)
        val r: ToolResult = MemoryListTool().execute(emptyMap())
        assertTrue(r.isSuccess)
        assertTrue(r.data!!.contains("没有"))
    }
}

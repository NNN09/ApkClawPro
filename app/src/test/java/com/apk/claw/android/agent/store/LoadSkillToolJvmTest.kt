package com.apk.claw.android.agent.store

import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.tool.impl.LoadSkillTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoadSkillToolJvmTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun loadSkill_returnsBody() {
        SkillStore.init(tmp.root)
        SkillStore.upsert("order-coffee", "d", "1. 打开美团")
        val r: ToolResult = LoadSkillTool().execute(mapOf("name" to "order-coffee"))
        assertTrue(r.isSuccess)
        assertEquals("1. 打开美团", r.data)
    }

    @Test fun loadSkill_unknownNameFails() {
        SkillStore.init(tmp.root)
        val r: ToolResult = LoadSkillTool().execute(mapOf("name" to "nope"))
        assertTrue(!r.isSuccess)
        assertTrue(r.error!!.contains("nope"))
    }
}

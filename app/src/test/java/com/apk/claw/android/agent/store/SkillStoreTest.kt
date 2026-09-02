package com.apk.claw.android.agent.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun upsert_thenListAndLoad() {
        SkillStore.init(tmp.root)
        SkillStore.upsert("order-coffee", "在美团下单咖啡", "1. 打开美团\n2. 搜索咖啡")
        assertEquals(1, SkillStore.list().size)
        assertEquals("order-coffee", SkillStore.list()[0].name)
        assertEquals("在美团下单咖啡", SkillStore.list()[0].description)
        assertTrue(SkillStore.load("order-coffee")!!.startsWith("1. 打开美团"))
    }

    @Test fun upsert_rejectsInvalidNames() {
        SkillStore.init(tmp.root)
        assertFalse(SkillStore.upsert("Bad Name", "d", "b"))
        assertFalse(SkillStore.upsert("../etc", "d", "b"))
        assertFalse(SkillStore.upsert("", "d", "b"))
    }

    @Test fun upsert_twiceOverwrites() {
        SkillStore.init(tmp.root)
        SkillStore.upsert("s", "d1", "b1")
        SkillStore.upsert("s", "d2", "b2")
        assertEquals(1, SkillStore.list().size)
        assertEquals("b2", SkillStore.load("s"))
    }

    @Test fun load_unknownNameReturnsNull() {
        SkillStore.init(tmp.root)
        assertNull(SkillStore.load("nope"))
        assertNull(SkillStore.load("../etc"))
    }

    @Test fun catalogSection_emptyWhenNoSkills() {
        SkillStore.init(tmp.root)
        assertEquals("", SkillStore.catalogSection())
    }

    @Test fun catalogSection_listsNameAndDescriptionOnly() {
        SkillStore.init(tmp.root)
        SkillStore.upsert("order-coffee", "在美团下单咖啡", "SECRET-BODY-STEP")
        val s = SkillStore.catalogSection()
        assertTrue(s.startsWith("## 可用技能"))
        assertTrue(s.contains("- order-coffee: 在美团下单咖啡"))
        assertFalse(s.contains("SECRET-BODY-STEP"))
        assertTrue(s.contains("load_skill"))
    }

    @Test fun list_toleratesHandWrittenSkillWithoutFrontmatter() {
        SkillStore.init(tmp.root)
        val dir = tmp.root.resolve("skills/hand")
        dir.mkdirs()
        dir.resolve("SKILL.md").writeText("手写的技能正文")
        assertEquals("手写的技能正文", SkillStore.load("hand"))
        assertEquals("", SkillStore.list().first { it.name == "hand" }.description)
    }
}

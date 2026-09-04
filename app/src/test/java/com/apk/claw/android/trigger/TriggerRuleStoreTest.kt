package com.apk.claw.android.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * F8 触发配置存储单测：round-trip、整体校验拒绝（包名/正则/重名/上限）、
 * 脏数据容错回落默认（关闭）、原子性（校验失败保留原配置）。
 */
class TriggerRuleStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun validConfig(enabled: Boolean = true) = TriggerConfig(
        enabled = enabled,
        whitelist = listOf("com.android.sms"),
        rules = listOf(TriggerRule("otp", "com.android.sms", "(?:验证码|code)[^0-9]{0,12}(\\d{4,8})", "填入 {match}，不要提交"))
    )

    @Test
    fun `init without file yields safe defaults`() {
        TriggerRuleStore.init(tmp.newFolder())
        val config = TriggerRuleStore.get()
        assertEquals(false, config.enabled)
        assertTrue(config.whitelist.isEmpty())
        assertTrue(config.rules.isEmpty())
        assertNull(TriggerConfig.validate(config))
    }

    @Test
    fun `replaceAll and reinit round trip`() {
        val dir = tmp.newFolder()
        TriggerRuleStore.init(dir)
        assertNull(TriggerRuleStore.replaceAll(validConfig()))
        // 重新 init 模拟进程重启
        TriggerRuleStore.init(dir)
        val config = TriggerRuleStore.get()
        assertEquals(true, config.enabled)
        assertEquals(listOf("com.android.sms"), config.whitelist)
        assertEquals("otp", config.rules.single().name)
    }

    @Test
    fun `invalid whitelist package rejected and config unchanged`() {
        val dir = tmp.newFolder()
        TriggerRuleStore.init(dir)
        assertNull(TriggerRuleStore.replaceAll(validConfig()))
        val bad = validConfig().copy(whitelist = listOf("not a package"))
        assertNotNull(TriggerRuleStore.replaceAll(bad))
        assertEquals(listOf("com.android.sms"), TriggerRuleStore.get().whitelist)
    }

    @Test
    fun `invalid regex rejected`() {
        TriggerRuleStore.init(tmp.newFolder())
        val bad = validConfig().copy(
            rules = listOf(TriggerRule("bad", "", "([unclosed", "x"))
        )
        assertTrue(TriggerRuleStore.replaceAll(bad)!!.contains("bad"))
    }

    @Test
    fun `duplicate rule names rejected`() {
        TriggerRuleStore.init(tmp.newFolder())
        val rule = TriggerRule("dup", "", "a", "b")
        val bad = validConfig().copy(rules = listOf(rule, rule.copy(keywordRegex = "c")))
        assertTrue(TriggerRuleStore.replaceAll(bad)!!.contains("dup"))
    }

    @Test
    fun `limits enforced`() {
        TriggerRuleStore.init(tmp.newFolder())
        val packages = (1..TriggerConfig.MAX_WHITELIST + 1).map { "com.example.a$it" }
        assertTrue(TriggerRuleStore.replaceAll(validConfig().copy(whitelist = packages)) != null)
        val rules = (1..TriggerConfig.MAX_RULES + 1).map { TriggerRule("r$it", "", "a", "b") }
        assertTrue(TriggerRuleStore.replaceAll(validConfig().copy(rules = rules)) != null)
    }

    @Test
    fun `corrupt file falls back to disabled defaults`() {
        val dir = tmp.newFolder()
        File(dir, "trigger_rules.json").writeText("{ this is not json !!!")
        TriggerRuleStore.init(dir)
        assertEquals(false, TriggerRuleStore.get().enabled)
        assertTrue(TriggerRuleStore.get().rules.isEmpty())
    }

    @Test
    fun `empty config clears persisted state`() {
        val dir = tmp.newFolder()
        TriggerRuleStore.init(dir)
        assertNull(TriggerRuleStore.replaceAll(validConfig()))
        assertNull(TriggerRuleStore.replaceAll(TriggerConfig()))
        TriggerRuleStore.init(dir)
        assertEquals(false, TriggerRuleStore.get().enabled)
        assertTrue(TriggerRuleStore.get().whitelist.isEmpty())
    }
}

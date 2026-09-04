package com.apk.claw.android.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F8 触发规则引擎纯 JVM 单测：总开关/白名单门卫、正则匹配与占位符渲染、
 * 包过滤、坏正则兜底、多条规则取首条命中。
 */
class TriggerRuleEngineTest {

    private fun rule(
        name: String = "otp",
        appPackage: String = "",
        keywordRegex: String = "(?:验证码|code)[^0-9]{0,12}(\\d{4,8})",
        taskTemplate: String = "收到 {app} 的验证码 {match}，填入输入框，不要提交"
    ) = TriggerRule(name, appPackage, keywordRegex, taskTemplate)

    private val config = TriggerConfig(
        enabled = true,
        whitelist = listOf("com.android.sms", "com.example.app"),
        rules = listOf(rule())
    )

    @Test
    fun `disabled config never matches`() {
        val off = config.copy(enabled = false)
        assertNull(TriggerRuleEngine.evaluate("com.android.sms", "短信", "验证码 123456", off))
    }

    @Test
    fun `source package outside whitelist is ignored`() {
        assertNull(TriggerRuleEngine.evaluate("com.random.app", "短信", "验证码 123456", config))
    }

    @Test
    fun `matching notification renders placeholders`() {
        val m = TriggerRuleEngine.evaluate(
            "com.android.sms", "短信", "您的验证码是 123456，10 分钟内有效", config
        )!!
        assertEquals("otp", m.rule.name)
        assertEquals("验证码是 123456", m.matched)
        assertEquals(
            "收到 com.android.sms 的验证码 验证码是 123456，填入输入框，不要提交",
            m.task
        )
    }

    @Test
    fun `non matching text returns null`() {
        assertNull(TriggerRuleEngine.evaluate("com.android.sms", "广告", "年末大促销全场五折", config))
    }

    @Test
    fun `rule app package filter restricts to source package`() {
        val cfg = config.copy(rules = listOf(rule(appPackage = "com.example.app")))
        // 通知来自白名单内的短信应用，但规则限定了 com.example.app → 不命中
        assertNull(TriggerRuleEngine.evaluate("com.android.sms", "短信", "验证码 123456", cfg))
        val m = TriggerRuleEngine.evaluate("com.example.app", "Title", "code: 998877", cfg)
        assertEquals("otp", m!!.rule.name)
    }

    @Test
    fun `invalid regex rule is skipped not thrown`() {
        val cfg = config.copy(rules = listOf(rule(name = "bad", keywordRegex = "([unclosed"), rule(name = "good")))
        val m = TriggerRuleEngine.evaluate("com.android.sms", "短信", "验证码 123456", cfg)
        assertEquals("good", m!!.rule.name)
    }

    @Test
    fun `first matching rule wins`() {
        val cfg = config.copy(
            rules = listOf(
                rule(name = "first", keywordRegex = "验证码"),
                rule(name = "second", keywordRegex = "123456")
            )
        )
        assertEquals("first", TriggerRuleEngine.evaluate("com.android.sms", "短信", "验证码 123456", cfg)!!.rule.name)
    }

    @Test
    fun `oversized text is truncated for matching`() {
        val pad = "x".repeat(TriggerRuleEngine.MAX_MATCH_CHARS + 100)
        // 验证码关键词被截断掉 → 不命中
        assertNull(TriggerRuleEngine.evaluate("com.android.sms", "短信", pad + "验证码 123456", config))
        // 关键词在截断范围内 → 命中
        val m = TriggerRuleEngine.evaluate("com.android.sms", "短信", "验证码 123456 " + pad, config)
        assertEquals("otp", m!!.rule.name)
    }

    @Test
    fun `placeholder fields are bounded`() {
        val longText = "验证码 123456 " + "t".repeat(2000)
        val cfg = config.copy(rules = listOf(rule(taskTemplate = "T:{title}|{text}|{match}")))
        val m = TriggerRuleEngine.evaluate("com.android.sms", "标题", longText, cfg)!!
        // {text} 占位符替换截断到 200 字符，长正文不会被整段塞进任务指令
        assertEquals(false, m.task.contains("t".repeat(201)))
        assertEquals(true, m.task.contains("验证码 123456"))
    }
}

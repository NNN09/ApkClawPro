package com.apk.claw.android.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F1/F2 确认词表回归测试。
 *
 * 曾发生：ja 提示让用户回复「続行」，但词表只收中/英文 → 用户按提示回复被当成
 * 新任务入队，原任务门控超时失败。本测试锁定两个不变量：
 * 1. 三语确认提示文案中出现的回复词都已收录进 CONFIRM/CANCEL；
 * 2. 两集合语义正确且互不相交。
 */
class ReplyKeywordsTest {

    private val prompts = listOf(
        "agent_confirm_prompt",
        "agent_dialog_resume_prompt",
        "compliance_app_policy_confirm"
    )

    @Test
    fun promptReplyWordsAreAllInKeywordSets() {
        val quoted = Regex("[“「]([^”」]+)[”」]")
        for (locale in listOf("values", "values-zh", "values-ja")) {
            val text = resolveRes("$locale/strings.xml").readText()
            for (key in prompts) {
                val entry = Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
                    .find(text)?.groupValues?.get(1)
                    ?: throw AssertionError("$locale/strings.xml 缺少 $key")
                quoted.findAll(entry).forEach { m ->
                    val word = m.groupValues[1].trim().lowercase()
                    assertTrue(
                        "$locale 的 $key 提示用户回复「$word」，但该词不在确认/取消词表中",
                        word in ReplyKeywords.CONFIRM || word in ReplyKeywords.CANCEL
                    )
                }
            }
        }
    }

    @Test
    fun keywordSetsAreConsistentAndDisjoint() {
        for (w in listOf("继续", "确认", "继续执行", "是", "続行", "はい", "ok", "yes", "continue", "resume")) {
            assertTrue("「$w」应在确认词表", w in ReplyKeywords.CONFIRM)
        }
        for (w in listOf("取消", "跳过", "否", "不要", "キャンセル", "やめる", "cancel", "no", "skip", "stop")) {
            assertTrue("「$w」应在取消词表", w in ReplyKeywords.CANCEL)
        }
        assertTrue(
            "确认/取消词表不应相交",
            ReplyKeywords.CONFIRM.intersect(ReplyKeywords.CANCEL).isEmpty()
        )
    }

    private fun resolveRes(relative: String): File {
        val candidates = listOf(
            File("src/main/res/$relative"),
            File("app/src/main/res/$relative")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("找不到 $relative (cwd=${File(".").absolutePath})")
    }
}

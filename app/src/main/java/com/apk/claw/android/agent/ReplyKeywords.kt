package com.apk.claw.android.agent

/**
 * F1/F2 门控确认/取消关键词。TaskOrchestrator 对渠道回复 trim + lowercase 后精确匹配。
 *
 * 约束：三语确认提示（agent_confirm_prompt / agent_dialog_resume_prompt /
 * compliance_app_policy_confirm）中让用户回复的词必须收录在对应集合里，
 * 否则该语言用户按提示回复会被当成新任务入队，原任务等待超时失败。
 * 由 ReplyKeywordsTest 保证提示文案与词表一致。
 */
object ReplyKeywords {
    val CONFIRM: Set<String> = setOf(
        "继续", "确认", "继续执行", "是", "続行", "はい",
        "ok", "yes", "continue", "resume"
    )

    val CANCEL: Set<String> = setOf(
        "取消", "跳过", "否", "不要", "キャンセル", "やめる",
        "cancel", "no", "skip", "stop"
    )
}

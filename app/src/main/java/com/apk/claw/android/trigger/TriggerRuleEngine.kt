package com.apk.claw.android.trigger

/**
 * F8 规则匹配引擎（纯函数）：对一条通知做"总开关 → 白名单 → 逐条规则"评估，
 * 命中第一条规则即返回渲染后的任务指令。无 Android 依赖，可 JVM 单测。
 */
object TriggerRuleEngine {

    /** 参与匹配的通知文本上限（防异常超长正文拖垮正则） */
    const val MAX_MATCH_CHARS = 2000

    /** 模板占位符替换时单字段的上限 */
    private const val MAX_FIELD_CHARS = 200

    data class Match(val rule: TriggerRule, /** 渲染后的任务指令 */ val task: String, /** 正则命中片段 */ val matched: String)

    fun evaluate(sourcePackage: String, title: String, text: String, config: TriggerConfig): Match? {
        if (!config.enabled) return null
        if (sourcePackage !in config.whitelist) return null
        val body = (title + "\n" + text).take(MAX_MATCH_CHARS)
        for (rule in config.rules) {
            if (rule.appPackage.isNotBlank() && rule.appPackage != sourcePackage) continue
            // 单条正则非法只跳过该规则（保存入口已校验，这里兜底不让坏配置炸掉监听器）
            val regex = try {
                Regex(rule.keywordRegex)
            } catch (_: Exception) {
                continue
            }
            val found = regex.find(body) ?: continue
            val task = rule.taskTemplate
                .replace("{app}", sourcePackage.take(MAX_FIELD_CHARS))
                .replace("{title}", title.take(MAX_FIELD_CHARS))
                .replace("{text}", text.take(MAX_FIELD_CHARS))
                .replace("{match}", found.value.take(MAX_FIELD_CHARS))
            return Match(rule, task, found.value.take(MAX_FIELD_CHARS))
        }
        return null
    }
}

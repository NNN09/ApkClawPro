package com.apk.claw.android.trigger

import com.google.gson.annotations.SerializedName

/**
 * F8 事件驱动触发：白名单 App 的通知命中规则后，把模板渲染成任务指令派发给 Agent。
 * 配置整体默认关闭（enabled=false）、白名单为空、规则为空——用户显式配置后才生效。
 */
data class TriggerRule(
    /** 规则名（唯一键，用于留痕与去重） */
    @SerializedName("name") val name: String,
    /** 通知来源包过滤；空串 = 不过滤（仍须在白名单内） */
    @SerializedName("appPackage") val appPackage: String,
    /** 对"标题+正文"做 find 的正则；命中才触发 */
    @SerializedName("keywordRegex") val keywordRegex: String,
    /**
     * 任务指令模板，支持占位符：{app}=通知来源包、{title}=标题、{text}=正文（截断）、
     * {match}=正则命中片段（如验证码）。渲染结果作为一条 automated 任务派发。
     */
    @SerializedName("taskTemplate") val taskTemplate: String
)

data class TriggerConfig(
    @SerializedName("enabled") val enabled: Boolean = false,
    /** 通知来源包白名单：只有来源在此列表内的通知才会参与规则匹配 */
    @SerializedName("whitelist") val whitelist: List<String> = emptyList(),
    @SerializedName("rules") val rules: List<TriggerRule> = emptyList()
) {
    companion object {
        const val MAX_WHITELIST = 10
        const val MAX_RULES = 10
        private const val MAX_NAME_CHARS = 40
        private const val MAX_REGEX_CHARS = 200
        private const val MAX_TEMPLATE_CHARS = 500

        private val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

        /** 整体校验：返回第一条错误描述，合法返回 null（保存前调用，保证落盘配置可用） */
        fun validate(config: TriggerConfig): String? {
            if (config.whitelist.size > MAX_WHITELIST) return "whitelist limit ($MAX_WHITELIST) exceeded"
            config.whitelist.forEach {
                if (it.length > 255 || !PACKAGE_REGEX.matches(it)) return "invalid whitelist package: $it"
            }
            if (config.rules.size > MAX_RULES) return "rule limit ($MAX_RULES) exceeded"
            val names = mutableSetOf<String>()
            config.rules.forEach { rule ->
                if (rule.name.isBlank() || rule.name.length > MAX_NAME_CHARS) return "invalid rule name"
                if (!names.add(rule.name)) return "duplicate rule name: ${rule.name}"
                if (rule.appPackage.isNotBlank() &&
                    (rule.appPackage.length > 255 || !PACKAGE_REGEX.matches(rule.appPackage))
                ) return "invalid appPackage in rule ${rule.name}: ${rule.appPackage}"
                if (rule.keywordRegex.length > MAX_REGEX_CHARS) return "regex too long in rule ${rule.name}"
                try {
                    Regex(rule.keywordRegex)
                } catch (_: Exception) {
                    return "invalid regex in rule ${rule.name}: ${rule.keywordRegex}"
                }
                if (rule.taskTemplate.isBlank() || rule.taskTemplate.length > MAX_TEMPLATE_CHARS) {
                    return "invalid taskTemplate in rule ${rule.name}"
                }
            }
            return null
        }
    }
}

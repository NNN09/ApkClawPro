package com.apk.claw.android.agent.store

/**
 * F11 技能/人格导入安全审查（纯函数，可 JVM 单测）。
 * 技能/人格本质是提示词注入载体：导入时扫描内容中的提示注入与凭证外传风险，
 * HIGH 级别直接拦截，WARN 级别显著告警（ROADMAP C4/C5、F11 验收）。
 *
 * 扫描的是"分发载体"的风险模式，不是语义理解；误报可由用户在告警下自行判断放行（WARN）。
 */
object SkillImportScanner {

    enum class Severity { WARN, HIGH }

    data class Finding(val severity: Severity, val rule: String, val snippet: String)

    /** 高危：凭证/验证码外传、支付诱导、恢复出厂、指令覆盖后回传数据 */
    private val HIGH_RULES: List<Pair<String, Regex>> = listOf(
        "凭证外传" to
            Regex("(密码|password|passwd|支付密码|银行卡号|信用卡号|验证码|短信内容|otp|助记词|私钥)[^\\n]{0,40}(发送|发出|发给|发到|发送到|发送至|转给|转发|上传|回传|发给我|提交给|send|upload|forward|post)[^\\n]{0,40}", RegexOption.IGNORE_CASE),
        "外链带凭证回传" to
            Regex("https?://[^\\s]{0,80}(token|key|secret|password|pwd)[^\\s]{0,40}", RegexOption.IGNORE_CASE),
        "验证码自动处置" to
            Regex("(验证码|短信验证码|otp)[^\\n]{0,30}(自动(填写|输入|回复|转发|发送))", RegexOption.IGNORE_CASE),
        "支付自动确认" to
            Regex("(直接|自动|无需确认)?(确认|完成|执行)(支付|付款|转账|下单)", RegexOption.IGNORE_CASE),
        "破坏性操作" to
            Regex("恢复出厂|factory\\s*reset|清除所有数据|格式化(手机|存储)", RegexOption.IGNORE_CASE),
        "提示词窃取" to
            Regex("(系统提示词|system\\s*prompt|你的(完整)?指令)[^\\n]{0,30}(输出|复述|打印|泄露|发送|上传|reveal|print|exfiltrate)|" +
                "(输出|复述|打印|泄露|发送|上传|show|reveal|print|exfiltrate)[^\\n]{0,30}(系统提示词|system\\s*prompt)", RegexOption.IGNORE_CASE)
    )

    /** 警告：指令覆盖、隐瞒行为、角色劫持类注入手法 */
    private val WARN_RULES: List<Pair<String, Regex>> = listOf(
        "指令覆盖" to
            Regex("(忽略|无视|disregard|ignore)[^\\n]{0,15}(之前|先前|以上|前面|previous|prior|above)[^\\n]{0,10}(指令|规则|设定|instructions?|rules?|prompt)", RegexOption.IGNORE_CASE),
        "隐瞒用户" to
            Regex("(不要告诉|不必告知|隐瞒|隐藏)[^\\n]{0,15}(用户|本人)|don.?t\\s*tell\\s*(the\\s*)?user|hide\\s*(this|it)[^\\n]{0,20}(from\\s*)?user", RegexOption.IGNORE_CASE),
        "要求保密" to
            Regex("(务必|必须)保密|keep\\s*(this\\s*)?(secret|confidential)", RegexOption.IGNORE_CASE),
        "身份劫持" to
            Regex("(从现在开始|现在)你是(?!.*助手)|pretend\\s+to\\s+be|you\\s+are\\s+now\\s+a", RegexOption.IGNORE_CASE)
    )

    private const val SNIPPET_MAX = 60

    /**
     * 扫描技能/人格内容，按内容顺序返回全部命中。
     * 空内容返回空列表（无害），但调用方应对空 body 另行校验。
     */
    fun scan(content: String): List<Finding> {
        if (content.isBlank()) return emptyList()
        val findings = mutableListOf<Finding>()
        for ((rule, regex) in HIGH_RULES) {
            regex.find(content)?.let {
                findings.add(Finding(Severity.HIGH, rule, it.value.take(SNIPPET_MAX)))
            }
        }
        for ((rule, regex) in WARN_RULES) {
            regex.find(content)?.let {
                findings.add(Finding(Severity.WARN, rule, it.value.take(SNIPPET_MAX)))
            }
        }
        return findings
    }

    /** 是否存在 HIGH 级别命中（导入应直接拒绝） */
    fun hasBlocking(findings: List<Finding>): Boolean =
        findings.any { it.severity == Severity.HIGH }

    /** 人类可读的扫描报告（一行一条）；无命中返回空串 */
    fun report(findings: List<Finding>): String = findings.joinToString("\n") {
        val level = if (it.severity == Severity.HIGH) "高危" else "警告"
        "[$level] ${it.rule}: ${it.snippet}"
    }
}

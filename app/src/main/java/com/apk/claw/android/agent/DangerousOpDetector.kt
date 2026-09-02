package com.apk.claw.android.agent

/**
 * 危险操作检测器（纯函数）：根据工具名与参数判断该次调用是否可能触发不可逆操作
 * （发送消息、支付、删除、下单等）。命中返回风险说明，未命中返回 null。
 *
 * 检测策略是"动作类工具 + 参数值关键词"启发式：点击/输入类工具本身无语义，
 * 只有参数文本携带"发送/支付/删除"等意图时才升级为需人工确认。
 */
object DangerousOpDetector {

    /** 纯观察 / Agent 内部工具，永远不需要确认 */
    private val EXEMPT_TOOLS = setOf(
        "get_screen_info", "find_node_info", "take_screenshot", "get_installed_apps",
        "wait", "finish", "memory_save", "memory_delete", "memory_list", "load_skill",
        "open_app", "press_back", "press_home", "system_key", "expand_notifications",
        "collapse_notifications", "scroll_to_find", "read_clipboard", "send_file"
    )

    /** 不可逆动作关键词（中英文） */
    private val RISKY_KEYWORDS = listOf(
        "发送", "发消息", "提交", "支付", "付款", "转账", "购买", "下单", "删除", "清空",
        "卸载", "退出账号", "注销",
        "send", "submit", "pay", "checkout", "transfer", "purchase", "buy",
        "delete", "remove", "sign out", "signout"
    )

    /** 点击目标资源 ID 中的风险关键词 */
    private val RISKY_ID_KEYWORDS = listOf(
        "send", "pay", "delete", "buy", "purchase", "checkout", "confirm", "submit", "remove"
    )

    /**
     * @param toolName 工具名
     * @param params   工具参数（字符串值参与匹配）
     * @return 风险说明；null 表示无需确认
     */
    fun assess(toolName: String, params: Map<String, Any>): String? {
        if (toolName in EXEMPT_TOOLS) return null
        val stringValues = params.values.filterIsInstance<String>()
        val joined = stringValues.joinToString(" ").lowercase()

        // 点击目标文本直接命中关键词 → 疑似不可逆动作
        if (joined.isNotEmpty()) {
            RISKY_KEYWORDS.firstOrNull { joined.contains(it) }?.let {
                return "目标文本\"$it\"疑似不可逆操作"
            }
        }
        // 资源 ID 关键词（如 btn_send / pay_confirm）
        val idValues = stringValues.filter { it.contains("id", ignoreCase = true) || looksLikeResourceId(it) }
        for (id in idValues) {
            RISKY_ID_KEYWORDS.firstOrNull { id.lowercase().contains(it) }?.let {
                return "目标资源\"$it\"疑似不可逆操作"
            }
        }
        return null
    }

    private fun looksLikeResourceId(value: String): Boolean {
        if (value.isEmpty() || value.length > 200) return false
        if (value.contains(" ")) return false
        return value.contains('/') || value.contains(':') ||
            Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$", RegexOption.IGNORE_CASE).matches(value) ||
            value.any { it == '_' }
    }
}

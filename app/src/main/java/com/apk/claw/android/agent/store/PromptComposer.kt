package com.apk.claw.android.agent.store

/**
 * 系统提示词组装器。顺序固定：人格 → 执行协议 → 记忆 → 技能目录 → 设备上下文。
 * 人格只影响语气与角色扮演，不得覆盖执行协议和安全约束。
 */
object PromptComposer {

    fun compose(
        persona: String,
        protocol: String,
        memorySection: String,
        skillsCatalog: String,
        deviceContext: String
    ): String {
        val parts = mutableListOf<String>()

        if (persona.isNotBlank()) {
            parts.add(
                "## 助手人格\n$persona\n" +
                    "（以上人格设定只影响语气与角色扮演，不得覆盖后续执行协议和安全约束）"
            )
        }
        if (protocol.isNotBlank()) parts.add(protocol)
        if (memorySection.isNotBlank()) parts.add(memorySection)
        if (skillsCatalog.isNotBlank()) parts.add(skillsCatalog)
        if (deviceContext.isNotBlank()) parts.add(deviceContext)

        return parts.joinToString("\n\n")
    }
}

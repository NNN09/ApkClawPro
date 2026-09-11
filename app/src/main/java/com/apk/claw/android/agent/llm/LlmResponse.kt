package com.apk.claw.android.agent.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.model.output.TokenUsage

data class LlmResponse(
    val text: String?,
    val reasoning: String? = null,
    val toolExecutionRequests: List<ToolExecutionRequest>,
    val tokenUsage: TokenUsage? = null
) {
    fun hasToolExecutionRequests(): Boolean = toolExecutionRequests.isNotEmpty()

    /**
     * 应当持久化进对话历史的可见文本（"观察必须落在可见文本"不变量）。
     * content 为空白而 reasoning 有内容时（部分模型把观察写进 reasoning 草稿），
     * 把 reasoning 转录为可见文本——否则截图折叠后视觉知识随图丢失，
     * 模型会断言"从没看到过"并向用户复述占位文案（2026-09-11 事故）。
     * reasoning 是草稿（可能冗长、绕圈），截断加标记防止撑爆字符预算/污染主模型。
     */
    fun durableText(): String? {
        if (!text.isNullOrBlank()) return text
        val draft = reasoning?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val capped = if (draft.length > REASONING_MAX_CHARS) draft.take(REASONING_MAX_CHARS) + "…[截断]" else draft
        return "[视觉观察记录] $capped"
    }

    companion object {
        const val REASONING_MAX_CHARS = 800
    }
}

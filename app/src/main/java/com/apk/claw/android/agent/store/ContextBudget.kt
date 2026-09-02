package com.apk.claw.android.agent.store

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage

/**
 * 任务内上下文预算：字符级粗估 + 超预算时的分级压缩。
 * 1 token ≈ 1.5 字符粗估，CHAR_BUDGET=36000 ≈ 24k input token。
 */
object ContextBudget {

    const val CHAR_BUDGET = 36000

    private val GSON = Gson()

    fun estimateChars(messages: List<ChatMessage>): Int = messages.sumOf { chars(it) }

    private fun chars(msg: ChatMessage): Int = when (msg) {
        is AiMessage -> (msg.text()?.length ?: 0) +
            (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
        is ToolExecutionResultMessage -> msg.text().length
        is UserMessage -> msg.singleText().length
        is SystemMessage -> msg.text().length
        else -> 0
    }

    /**
     * 激进压缩：对所有轮次（含保护区）的超长工具结果做一行摘要，
     * 但始终保留最新一条 get_screen_info 完整内容（Agent 依赖它感知当前屏幕）。
     */
    fun compressAllToolResults(messages: MutableList<ChatMessage>): Boolean {
        val lastScreenIdx = messages.indexOfLast {
            it is ToolExecutionResultMessage && it.toolName() == "get_screen_info"
        }
        var changed = false
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is ToolExecutionResultMessage && msg.text().length > 100 && i != lastScreenIdx) {
                messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), summarize(msg.text()))
                changed = true
            }
        }
        return changed
    }

    /**
     * 超预算兜底：丢弃当前任务最早的执行轮次，只保留最近 keepRounds 轮。
     * protectedPrefix = 当前任务 UserMessage 的下标；其及之前的消息（系统提示词、
     * 会话历史、任务本体）不动。被删段以一行占位 UserMessage 替代，保持
     * AiMessage / tool-result 配对完整。
     */
    fun truncateOldestRounds(
        messages: MutableList<ChatMessage>,
        protectedPrefix: Int,
        keepRounds: Int
    ): Int {
        val roundStarts = messages.indices.filter { it > protectedPrefix && messages[it] is AiMessage }
        if (roundStarts.size <= keepRounds) return 0
        val cutIndex = roundStarts[roundStarts.size - keepRounds]
        val firstRound = roundStarts.first()
        if (cutIndex <= firstRound) return 0
        val removed = cutIndex - firstRound
        messages[firstRound] = UserMessage.from("[系统提示] 上下文超限，已省略更早的 $removed 条执行消息")
        messages.subList(firstRound + 1, cutIndex).clear()
        return removed
    }

    /** 与 DefaultAgentService.summarizeToolResult 相同的一行摘要逻辑（激进压缩复用） */
    fun summarize(resultJson: String): String {
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = GSON.fromJson(resultJson, mapType)
            val isSuccess = map["isSuccess"] as? Boolean ?: false
            if (isSuccess) {
                val data = map["data"]?.toString() ?: "ok"
                "✓ " + if (data.length > 80) data.take(80) + "..." else data
            } else {
                val error = map["error"]?.toString() ?: "failed"
                "✗ " + if (error.length > 80) error.take(80) + "..." else error
            }
        } catch (_: Exception) {
            if (resultJson.length > 80) resultJson.take(80) + "..." else resultJson
        }
    }
}

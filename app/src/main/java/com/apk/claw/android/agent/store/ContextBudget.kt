package com.apk.claw.android.agent.store

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.Content
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage

/**
 * 任务内上下文预算：字符级粗估 + 超预算时的分级压缩。
 * 预算 = 模型上下文窗口(tokens) × 安全系数 0.8 × 1 token ≈ 1.5 字符粗估，
 * 不调用精确 tokenizer。窗口未设置（≤0）时退回 DEFAULT_CONTEXT_WINDOW_TOKENS，
 * 恰好等价旧版固定 CHAR_BUDGET=36000（≈24k input token）。
 */
object ContextBudget {

    /** 未配置模型窗口时的默认值（30000 tokens → 36000 字符预算，与旧版行为一致） */
    const val DEFAULT_CONTEXT_WINDOW_TOKENS = 30000

    /** 窗口下限：再小的窗口预算会被抬到此处，避免每轮都触发截断 */
    const val MIN_CONTEXT_WINDOW_TOKENS = 4096

    private const val SAFETY_FACTOR = 0.8
    private const val CHARS_PER_TOKEN = 1.5

    /**
     * 单张视觉图像折算的字符成本（F10）：截图经 ScreenshotEncoder 压到 896px JPEG，
     * 视觉 token 约 1k-2k，按 3000 字符（≈2000 token）估算即可保证预算不被图像悄悄吃穿。
     */
    const val IMAGE_COST_CHARS = 3000

    private val GSON = Gson()

    /** 由模型上下文窗口（tokens）推算字符预算；windowTokens ≤ 0 视为未设置 */
    fun charBudget(windowTokens: Int): Int {
        val tokens = if (windowTokens <= 0) {
            DEFAULT_CONTEXT_WINDOW_TOKENS
        } else {
            windowTokens.coerceAtLeast(MIN_CONTEXT_WINDOW_TOKENS)
        }
        return (tokens * SAFETY_FACTOR * CHARS_PER_TOKEN).toInt()
    }

    fun estimateChars(messages: List<ChatMessage>): Int = messages.sumOf { charsOf(it) }

    /**
     * 单条消息的字符成本（DefaultAgentService 压缩统计与预算估算共用）。
     * UserMessage 可能含图像 content（F10），不能调 singleText()（多 content 会抛异常）。
     */
    fun charsOf(msg: ChatMessage): Int = when (msg) {
        is AiMessage -> (msg.text()?.length ?: 0) +
            (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
        is ToolExecutionResultMessage -> msg.text().length
        is UserMessage -> msg.contents().sumOf { contentChars(it) }
        is SystemMessage -> msg.text().length
        else -> 0
    }

    private fun contentChars(content: Content): Int = when (content) {
        is TextContent -> content.text().length
        is ImageContent -> IMAGE_COST_CHARS
        else -> 0
    }

    /** 消息列表中是否存在图像 content（F10） */
    fun hasImages(messages: List<ChatMessage>): Boolean =
        messages.any { it is UserMessage && it.contents().any { c -> c is ImageContent } }

    /**
     * 激进压缩：对所有轮次（含保护区）的超长工具结果做一行摘要，
     * 但始终保留最新一条 get_screen_info 完整内容（Agent 依赖它感知当前屏幕）。
     * F10：同时把更早的截图图像折叠为文本占位，只保留最新一张（控制视觉 token）。
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
        return foldOldImages(messages).or(changed)
    }

    /**
     * 折叠历史截图：只保留最新一张图像消息，其余换成文本占位。
     * @return 是否发生了替换
     */
    fun foldOldImages(messages: MutableList<ChatMessage>): Boolean {
        val lastImageIdx = messages.indexOfLast {
            it is UserMessage && it.contents().any { c -> c is ImageContent }
        }
        var changed = false
        for (i in messages.indices) {
            val msg = messages[i]
            if (i == lastImageIdx) continue
            if (msg is UserMessage && msg.contents().any { it is ImageContent }) {
                messages[i] = UserMessage.from("[系统提示] 早期截图已省略")
                changed = true
            }
        }
        return changed
    }

    /**
     * 视觉降级（F10）：模型不支持视觉输入导致 API 报错时，移除全部图像 content，
     * 把图像消息替换为文本占位，让任务能在纯文本模式下继续。
     * @return 是否替换过（false = 本来就没有图像，无需重试）
     */
    fun stripImages(messages: MutableList<ChatMessage>): Boolean {
        var changed = false
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is UserMessage && msg.contents().any { it is ImageContent }) {
                messages[i] = UserMessage.from(
                    "[系统提示] 当前模型不支持视觉输入，截图已移除，请改用 get_screen_info 获取屏幕信息。"
                )
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

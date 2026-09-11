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

    /**
     * 单次请求允许携带的图像张数上限。中转站/服务商普遍有 per-request 图片数硬限
     * （真机实证：one-api 默认 50，第 51 张直接 500），上限留出余量（同一轮可能注入
     * 截图+兜底截图两张）。超限时从最旧开始折叠，而不是把请求撞死在服务商墙上。
     */
    const val MAX_IMAGES_PER_REQUEST = 40

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

    /** 消息列表中的图像消息数量 */
    fun countImages(messages: List<ChatMessage>): Int =
        messages.count { it is UserMessage && it.contents().any { c -> c is ImageContent } }

    private val FOLDED_PLACEHOLDER = "[系统提示] 早期截图已省略"

    /**
     * 把图像消息折叠为文本占位，只保留最新 keepCount 张。
     * @return 实际折叠的消息数（0 = 本就未超 keepCount）
     */
    fun foldOldestImagesTo(messages: MutableList<ChatMessage>, keepCount: Int): Int {
        val imageIndices = messages.indices.filter { i ->
            val m = messages[i]
            m is UserMessage && m.contents().any { c -> c is ImageContent }
        }
        if (imageIndices.size <= keepCount) return 0
        val foldUntil = imageIndices.size - keepCount   // 最旧的一批
        for (i in 0 until foldUntil) {
            messages[imageIndices[i]] = UserMessage.from(FOLDED_PLACEHOLDER)
        }
        return foldUntil
    }

    /**
     * 独立视觉路由：识别轮结束后，把请求携带过的截图折叠为纯文本。
     * 保留原文本说明（截图编号/尺寸/坐标约定提示仍可追溯），只剥离图像 content——
     * 下一轮请求不再带图，因而回落主模型；视觉模型只承担"看图"的轮次。
     * 与 [stripImages]（降级：告知模型不可用）不同，这是正常消费后的回收。
     * @return 实际折叠的消息数（0 = 本就没有图像）
     */
    fun foldConsumedImages(messages: MutableList<ChatMessage>): Int {
        var folded = 0
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is UserMessage && msg.contents().any { it is ImageContent }) {
                val caption = msg.contents().filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text() }
                    .ifEmpty { "[截图]" }
                messages[i] = UserMessage.from(
                    caption + "\n[系统提示] 该截图原图已省略，视觉识别的结论已记录在上方的助手回复文字中。"
                )
                folded++
            }
        }
        return folded
    }

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
    fun foldOldImages(messages: MutableList<ChatMessage>): Boolean =
        foldOldestImagesTo(messages, keepCount = 1) > 0

    /**
     * 为注入一张新截图腾出空间（截图注入的唯一准入策略），两道闸：
     * 1. 张数闸：已有张数达到 [MAX_IMAGES_PER_REQUEST] 时先折叠最旧的（服务商有
     *    per-request 图片数硬限，超限是确定性 500，不能靠重试闯过去）；
     * 2. 字符闸：超字符预算时从最旧开始逐张折叠，直到放得下。
     * 逐张（而非一次折叠到只留一张）可最大限度保留历史视觉信息——
     * 截图留存数量随预算自动伸缩。
     * @return true=可以注入新图；false=折叠全部旧图后仍放不下（窗口过小或历史过长）
     */
    fun makeRoomForImage(messages: MutableList<ChatMessage>, charBudget: Int): Boolean {
        foldOldestImagesTo(messages, keepCount = MAX_IMAGES_PER_REQUEST - 1)
        if (estimateChars(messages) + IMAGE_COST_CHARS <= charBudget) return true
        val imageIndices = messages.indices.filter { i ->
            val m = messages[i]
            m is UserMessage && m.contents().any { it is ImageContent }
        }
        for (i in imageIndices) {   // 从最旧到最新逐张折叠；新图永远比已折叠的图新
            messages[i] = UserMessage.from(FOLDED_PLACEHOLDER)
            if (estimateChars(messages) + IMAGE_COST_CHARS <= charBudget) return true
        }
        return false
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
                    "[系统提示] 当前模型不支持视觉输入，截图未附上；屏幕状态请通过 get_screen_info 获取。"
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

    /**
     * 预算校准器：用实测输入 token 修正"1 token ≈ [CHARS_PER_TOKEN] 字符"的先验假设。
     * 先验与真实计费的偏差（中文分词密度、中转站对视觉 token 的计费方式等）会让字符
     * 预算失真——实测换算系数 = 发送前估算字符 ÷ 实际输入 token，直接替换系数，
     * 使字符预算随当前模型/中转站的真实行为收敛，估算不再与实际账单脱节。
     */
    class BudgetCalibrator(
        /** 换算系数上下限，防单次异常请求把预算拉爆或压死 */
        private val minCharsPerToken: Double = 0.6,
        private val maxCharsPerToken: Double = 4.0
    ) {
        var charsPerToken: Double = CHARS_PER_TOKEN
            private set

        /**
         * 记录一轮实测。
         * @return 是否收紧了预算（供日志/遥测）
         */
        fun onMeasured(estimateChars: Int, actualInputTokens: Int): Boolean {
            if (actualInputTokens <= 0 || estimateChars <= 0) return false
            val measured = (estimateChars.toDouble() / actualInputTokens)
                .coerceIn(minCharsPerToken, maxCharsPerToken)
            val tightened = measured < charsPerToken
            charsPerToken = measured
            return tightened
        }

        /** 校准后的字符预算；未实测时与 [charBudget] 完全一致（旧行为） */
        fun effectiveCharBudget(windowTokens: Int): Int =
            (charBudget(windowTokens) / CHARS_PER_TOKEN * charsPerToken).toInt()
    }
}

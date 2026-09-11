package com.apk.claw.android.agent

import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.agent.langchain.LangChain4jToolBridge
import com.apk.claw.android.agent.llm.LlmClient
import com.apk.claw.android.agent.llm.LlmClientFactory
import com.apk.claw.android.agent.llm.LlmResponse
import com.apk.claw.android.agent.llm.StreamingListener
import com.apk.claw.android.agent.store.ContextBudget
import com.apk.claw.android.agent.store.MemoryStore
import com.apk.claw.android.agent.store.PersonaStore
import com.apk.claw.android.agent.store.PromptComposer
import com.apk.claw.android.agent.store.SessionStore
import com.apk.claw.android.agent.store.SkillStore
import com.apk.claw.android.compliance.AppPolicyEngine
import com.apk.claw.android.compliance.AppPolicyStore
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.impl.GetScreenInfoTool
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import java.io.File
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class DefaultAgentService : AgentService {

    companion object {
        private const val TAG = "AgentService"
        private val GSON = Gson()

        /** LLM API 调用失败时的最大重试次数 */
        private const val MAX_API_RETRIES = 3

        /** 中转站瞬时拒绝（400001 类坏通道）的退避基数：短退避撞不进下一个好窗口 */
        private const val TRANSIENT_REJECT_BACKOFF_MS = 10_000L
        /** 死循环检测：滑动窗口大小 */
        private const val LOOP_DETECT_WINDOW = 4

        /** F10：find_node_info 连续失败 N 次后自动截图注入（WebView/自绘界面节点读不到时的视觉兜底） */
        private const val AUTO_SCREENSHOT_AFTER_FIND_MISSES = 2

        /** 发送前分级压缩的保护区：最近 N 轮完整保留 */
        private const val COMPRESS_PROTECT_ROUNDS = 3

        /** 上下文超预算截断时保留的最近执行轮数 */
        private const val TRUNCATE_KEEP_ROUNDS = 4

        /** 是否将网络请求/响应原始数据输出到沙盒缓存文件，方便调试 */
        @JvmField
        var FILE_LOGGING_ENABLED = false
        @JvmField
        var FILE_LOGGING_CACHE_DIR: File? = null
    }

    private lateinit var config: AgentConfig
    private lateinit var llmClient: LlmClient
    private lateinit var toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** 当前已提交任务的回调：executor 被关闭丢弃任务时代为收尾用（否则调用方任务锁泄漏） */
    @Volatile
    private var activeCallback: AgentCallback? = null

    override fun initialize(config: AgentConfig) {
        this.config = config
        this.llmClient = LlmClientFactory.create(config)
        this.toolSpecs = LangChain4jToolBridge.buildToolSpecifications()
        this.executor = Executors.newSingleThreadExecutor()
        XLog.i(TAG, "Agent initialized: provider=${config.provider}, model=${config.modelName}, streaming=${config.streaming}")
    }

    override fun updateConfig(config: AgentConfig) {
        if (running.get()) {
            cancel()
            XLog.w(TAG, "Task was running during config update, cancelled")
        }
        settleDiscardedTasks(executor?.shutdownNow()?.size ?: 0)
        initialize(config)
        XLog.i(TAG, "Agent config updated, new model: ${config.modelName}")
    }

    override fun executeTask(request: TaskRequest, callback: AgentCallback) {
        if (running.get()) {
            callback.onError(0, TaskRejectedException("Agent is already running a task"), 0)
            // 契约：每次 executeTask 的回调链都以 onSettled 收尾，否则调用方的任务锁会泄漏
            callback.onSettled()
            return
        }

        val ex = executor
        if (ex == null) {
            // initialize 中途抛异常时 executor 为 null：置位 running 却无人清零，会永久卡死
            callback.onError(0, TaskRejectedException("Agent executor is not initialized"), 0)
            callback.onSettled()
            return
        }

        running.set(true)
        cancelled.set(false)
        activeCallback = callback

        try {
            ex.submit {
                try {
                    runAgentLoop(request, callback)
                } catch (e: Exception) {
                    XLog.e(TAG, "Agent execution error", e)
                    callback.onError(0, e, 0)
                } finally {
                    running.set(false)
                    activeCallback = null
                    TaskContext.clear()
                }
                // running 已清除、线程即将空闲；此时再触发空闲联动才不会与下一任务竞态
                callback.onSettled()
            }
        } catch (e: RejectedExecutionException) {
            // executor 已被 updateConfig/initAgent 关闭：任务从未启动，代为收尾防任务锁泄漏
            running.set(false)
            activeCallback = null
            XLog.w(TAG, "executeTask rejected (executor shut down): ${e.message}")
            callback.onError(0, TaskRejectedException("Agent executor is shutting down"), 0)
            callback.onSettled()
        }
    }

    /**
     * executor 关闭时，已提交未启动的任务会被 shutdownNow 丢弃——其回调链无人收尾，
     * 调用方的任务锁将永久泄漏（后续任务全被排队且永不排空）。这里代为清账。
     * 正在运行的任务不受影响：线程被中断后仍会走完自己的 finally + onSettled。
     */
    private fun settleDiscardedTasks(discardedCount: Int) {
        if (discardedCount <= 0) return
        running.set(false)
        val cb = activeCallback ?: return
        activeCallback = null
        XLog.w(TAG, "Discarded $discardedCount queued task(s) during executor shutdown, settling callback")
        cb.onError(0, TaskRejectedException("Task aborted: agent was reconfigured"), 0)
        cb.onSettled()
    }

    // ==================== 环境预检 ====================

    private fun preCheck(): String? {
        if (ClawAccessibilityService.getInstance() == null) {
            return ClawApplication.instance.getString(R.string.agent_accessibility_not_enabled)
        }
        return null
    }

    // ==================== 设备上下文 ====================

    private fun buildDeviceContext(): String {
        val app = ClawApplication.instance
        val sb = StringBuilder()
        sb.append("\n\n## 设备信息\n")
        sb.append("- 品牌: ").append(Build.BRAND).append("\n")
        sb.append("- 型号: ").append(Build.MODEL).append("\n")
        sb.append("- Android 版本: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        try {
            val wm = app
                .getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            sb.append("- 屏幕分辨率: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append("\n")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get display metrics", e)
        }

        sb.append("- 已注册工具数: ").append(ToolRegistry.getAllTools().size).append("\n")

        val appName = try {
            val appInfo = app.packageManager.getApplicationInfo(app.packageName, 0)
            app.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { "CoPaw" }
        sb.append("\n## 本应用信息\n")
        sb.append("- 应用名: ").append(appName).append("\n")
        sb.append("- 包名: ").append(app.packageName).append("\n")
        sb.append("- 当用户提到'自己/本应用/这个应用'时，指的就是上述应用\n")

        return sb.toString()
    }

    // ==================== LLM 调用（带重试） ====================

    private fun chatWithRetry(
        messages: List<ChatMessage>,
        specs: List<dev.langchain4j.agent.tool.ToolSpecification>,
        callback: AgentCallback,
        iteration: Int
    ): LlmResponse {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_API_RETRIES) {
            if (cancelled.get()) throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
            try {
                return if (config.streaming) {
                    val textBuilder = StringBuilder()
                    llmClient.chatStreaming(messages, specs, object : StreamingListener {
                        override fun onPartialText(token: String) {
                            textBuilder.append(token)
                            callback.onContent(iteration, token)
                        }
                        override fun onComplete(response: LlmResponse) {}
                        override fun onError(error: Throwable) {}
                    })
                } else {
                    llmClient.chat(messages, specs)
                }
            } catch (e: Exception) {
                lastException = e
                val kind = LlmErrorKind.classify(e.message)
                // ③ 确定性错误（参数/配额/认证/超限）重试必然同结果，立即上抛走分类兜底
                if (kind == LlmErrorKind.NON_RETRYABLE) {
                    throw e
                }
                // 中转站瞬时拒绝（400001 类坏通道）按 10s 级线性退避；
                // 普通暂时性故障维持指数退避
                val delay = if (kind == LlmErrorKind.TRANSIENT_REJECT) {
                    TRANSIENT_REJECT_BACKOFF_MS * (attempt + 1)
                } else {
                    (Math.pow(2.0, attempt.toDouble()) * 1000).toLong()
                }
                XLog.w(TAG, "LLM API call failed (attempt ${attempt + 1}/$MAX_API_RETRIES, kind=$kind), retrying in ${delay}ms: ${e.message}")
                try {
                    Thread.sleep(delay)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastException!!
    }

    /**
     * ② LLM 调用失败后的分级视觉兜底。按 [LlmErrorKind] 分流：
     * - IMAGE_LIMIT（图片数超服务商上限）：折叠最旧一半截图、带图重试——视觉还在，绝不轻言放弃；
     * - 仍失败 → 全剥降级为纯文本（旧行为，用户可见提示"模型不接受图像输入"）；
     * - 无图像可剥（降级已发生过）→ 返回 null，由调用方终结任务。
     * @return 成功的响应；null = 兜底也失败
     */
    private fun recoverFromLlmFailure(
        messages: MutableList<ChatMessage>,
        specs: List<dev.langchain4j.agent.tool.ToolSpecification>,
        callback: AgentCallback,
        iteration: Int,
        firstError: Throwable
    ): LlmResponse? {
        if (!ContextBudget.hasImages(messages)) return null
        var lastError: Throwable = firstError

        if (LlmErrorKind.classify(firstError.message) == LlmErrorKind.IMAGE_LIMIT) {
            val folded = ContextBudget.foldOldestImagesTo(messages, ContextBudget.MAX_IMAGES_PER_REQUEST / 2)
            XLog.w(TAG, "Image count limit hit, folded $folded oldest screenshots, retrying with vision")
            runCatching { chatWithRetry(messages, specs, callback, iteration) }.onSuccess { return it }
                .onFailure { lastError = it }
        }

        ContextBudget.stripImages(messages)
        XLog.w(TAG, "LLM call failed with images present, stripped image contents and retry once", lastError)
        callback.onVisionDegraded(lastError.message ?: "vision input rejected")
        return runCatching { chatWithRetry(messages, specs, callback, iteration) }.getOrNull()
    }

    // ==================== 死循环检测 ====================

    private data class RoundFingerprint(val screenHash: Int, val toolCall: String)

    private fun isStuckInLoop(history: LinkedList<RoundFingerprint>): Boolean {
        if (history.size < LOOP_DETECT_WINDOW) return false
        val first = history.first()
        return history.all { it == first }
    }

    // ==================== 上下文压缩 ====================

    /** 大输出观察类工具 → 压缩后占位符 */
    private val OBSERVATION_PLACEHOLDERS = mapOf(
        "get_screen_info" to "[屏幕信息已省略]",
        "take_screenshot" to "[截图结果已省略]",
        "find_node_info" to "[节点查找结果已省略]",
        "get_installed_apps" to "[应用列表已省略]",
        "scroll_to_find" to "[滚动查找结果已省略]"
    )

    /**
     * 发送前压缩历史消息，节省 input token：
     * - get_screen_info：全局只保留最新一条完整结果
     * - 保护区（最近 COMPRESS_PROTECT_ROUNDS 轮）：完整保留
     * - 保护区外：AI thinking 不动，tool result 压缩为一行摘要
     */
    private fun compressHistoryForSend(messages: MutableList<ChatMessage>) {
        // 压缩前统计总字符数（ContextBudget.charsOf 兼容含图像的多 content UserMessage）
        val charsBefore = messages.sumOf { ContextBudget.charsOf(it) }
        val msgCountBefore = messages.size

        // 0. get_screen_info 特殊处理：无视分级，全局只保留最新一条完整结果
        val screenPlaceholder = OBSERVATION_PLACEHOLDERS["get_screen_info"]!!
        val lastScreenIdx = messages.indexOfLast {
            it is ToolExecutionResultMessage && it.toolName() == "get_screen_info"
        }
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is ToolExecutionResultMessage
                && msg.toolName() == "get_screen_info"
                && i != lastScreenIdx
                && msg.text() != screenPlaceholder
            ) {
                messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), screenPlaceholder)
            }
        }

        // 1. 找出所有 AiMessage 的索引，每个代表一轮
        val aiIndices = messages.indices.filter { messages[it] is AiMessage }
        if (aiIndices.size <= COMPRESS_PROTECT_ROUNDS) return

        val totalRounds = aiIndices.size

        for (roundIdx in aiIndices.indices) {
            val roundFromEnd = totalRounds - roundIdx
            if (roundFromEnd <= COMPRESS_PROTECT_ROUNDS) break // 保护区

            val aiIndex = aiIndices[roundIdx]

            // 收集本轮的 ToolExecutionResultMessage 索引
            var j = aiIndex + 1
            while (j < messages.size && messages[j] is ToolExecutionResultMessage) {
                compressToolResultMessage(messages, j)
                j++
            }
        }

        // 压缩后统计
        val charsAfter = messages.sumOf { ContextBudget.charsOf(it) }
        val saved = charsBefore - charsAfter
        if (saved > 0) {
            XLog.i(TAG, "上下文压缩: ${charsBefore}→${charsAfter}字符, 节省${saved}字符(${saved * 100 / charsBefore}%), 轮数=${aiIndices.size}")
        }
    }

    /** 压缩 Tool Result：观察类工具用占位符，其他工具截取摘要 */
    private fun compressToolResultMessage(messages: MutableList<ChatMessage>, index: Int) {
        val msg = messages[index] as ToolExecutionResultMessage
        val text = msg.text()
        if (text.length <= 100) return // 已足够简短，无需压缩

        val placeholder = OBSERVATION_PLACEHOLDERS[msg.toolName()]
        if (placeholder != null) {
            messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), placeholder)
            return
        }

        // 其他工具：解析 JSON 提取摘要
        val compressed = summarizeToolResult(text)
        messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), compressed)
    }

    /** 将 ToolResult JSON 压缩为一行摘要 */
    private fun summarizeToolResult(resultJson: String): String {
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

    // ==================== 主执行循环 ====================

    private fun runAgentLoop(request: TaskRequest, callback: AgentCallback) {
        val userPrompt = request.prompt
        TaskContext.set(request.channel, request.senderId)
        // 环境预检
        preCheck()?.let {
            callback.onError(0, RuntimeException(it), 0)
            return
        }

        // 摘要沉淀：把上一批被裁剪的会话轮次与上次任务的执行轨迹合并进 digest（失败则退化为丢弃）
        val pending = SessionStore.pendingDigest(request.channel, request.senderId)
        var transcript = SessionStore.pendingTranscript(request.channel, request.senderId)
        if (!config.digestTrajectoryEnabled && transcript.isNotBlank()) {
            SessionStore.clearPendingTranscript(request.channel, request.senderId)
            transcript = ""
        }
        if (pending.isNotEmpty() || transcript.isNotBlank()) {
            summarizeDigest(SessionStore.digest(request.channel, request.senderId), pending, transcript)?.let {
                SessionStore.updateDigest(request.channel, request.senderId, it)
            }
            SessionStore.clearPendingDigest(request.channel, request.senderId)
            SessionStore.clearPendingTranscript(request.channel, request.senderId)
        }

        // 构建 System Prompt（人格 → 执行协议 → 记忆 → 技能目录 → 设备上下文）
        val fullSystemPrompt = PromptComposer.compose(
            persona = PersonaStore.get(),
            protocol = config.systemPrompt,
            memorySection = MemoryStore.promptSection(),
            skillsCatalog = SkillStore.catalogSection(),
            deviceContext = buildDeviceContext()
        )

        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from(fullSystemPrompt))
        val digest = SessionStore.digest(request.channel, request.senderId)
        if (digest.isNotBlank()) {
            messages.add(UserMessage.from("[历史摘要] 以下是本会话更早轮次的摘要：\n$digest"))
        }
        SessionStore.history(request.channel, request.senderId).forEach { turn ->
            messages.add(UserMessage.from(turn.user))
            messages.add(AiMessage.from(turn.assistant))
        }
        messages.add(UserMessage.from(userPrompt))
        val taskUserIndex = messages.size - 1   // 截断保护边界：此前消息永不被截断

        var iterations = 0
        var totalTokens = 0
        val maxIterations = config.maxIterations
        val budgetCalibrator = ContextBudget.BudgetCalibrator()
        val loopHistory = LinkedList<RoundFingerprint>()
        var lastScreenHash = 0
        var screenshotCount = 0   // F10：本任务已注入视觉上下文的截图数（仅作消息编号）
        var findMissStreak = 0    // F10：find_node_info 连续失败计数（视觉兜底触发器）
        var observationStreak = 0 // ④⑤：连续"纯观察轮"计数（全部工具调用都是观察类）
        var restrictedRound = false // ④：下一轮临时禁用观察工具（RESTRICT 阶梯生效中）
        // 独立视觉路由生效（开关开 + 单独设了模型 + 与主模型不同）：带图请求走视觉模型；
        // 识别轮结束后折叠消费过的截图（见循环内），其余轮次一律主模型
        val visionRoutingActive = config.effectiveVisionModel(true) != null

        loop@ while (iterations < maxIterations && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)

            // ④ RESTRICT 生效轮：收走观察类工具，逼模型行动或 finish
            val activeSpecs = if (restrictedRound) {
                toolSpecs.filter { it.name() !in LoopGuard.OBSERVATION_TOOLS }
            } else {
                toolSpecs
            }

            // 发送前分级压缩历史消息，节省 token（预算经实测校准，先验=旧行为）
            val charBudget = budgetCalibrator.effectiveCharBudget(config.contextWindowTokens)
            compressHistoryForSend(messages)
            var estimateAtSend = ContextBudget.estimateChars(messages)

            // 超字符预算时升级压缩：先激进压缩全部工具结果，仍超则丢弃最早执行轮次
            if (estimateAtSend > charBudget) {
                ContextBudget.compressAllToolResults(messages)
                if (ContextBudget.estimateChars(messages) > charBudget) {
                    ContextBudget.truncateOldestRounds(messages, taskUserIndex, TRUNCATE_KEEP_ROUNDS)
                }
                estimateAtSend = ContextBudget.estimateChars(messages)
            }

            // 本轮请求是否带图（与 LlmClient 内的路由判定同源同刻）：决定响应后是否折叠消费
            val requestHadImages = ContextBudget.hasImages(messages)

            // LLM 调用（带重试）；失败时按错误分类分级兜底：
            // 图片数超限 → 折叠最旧一半截图带图重试（保住视觉）；仍失败 → 全剥降级为纯文本
            val llmResponse: LlmResponse = runCatching { chatWithRetry(messages, activeSpecs, callback, iterations) }.getOrElse { e ->
                val recovered = recoverFromLlmFailure(messages, activeSpecs, callback, iterations, e)
                if (recovered != null) {
                    estimateAtSend = ContextBudget.estimateChars(messages)   // 折叠/剥图后按新体积校准
                    recovered
                } else {
                    XLog.e(TAG, "LLM API call failed after vision fallback", e)
                    callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_api_call_failed, e.message)), totalTokens)
                    return
                }
            }

            // 实测校准：发送前估算 vs 实际输入 token，修正下一轮的字符预算
            llmResponse.tokenUsage?.inputTokenCount()?.let { inputTokens ->
                if (budgetCalibrator.onMeasured(estimateAtSend, inputTokens)) {
                    XLog.i(TAG, "输入 token 实测超出先验估算，预算换算系数收紧为 ${budgetCalibrator.charsPerToken}")
                }
            }

            // 累加 token 用量
            llmResponse.tokenUsage?.totalTokenCount()?.let { totalTokens += it }

            // 将 AI 消息添加到历史（需要构造 AiMessage）。
            // durableText：content 空白而 reasoning 有观察时转录为可见文本——
            // "观察必须落在可见文本"，否则截图折叠后视觉知识随图丢失（2026-09-11 事故）
            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                val visible = llmResponse.durableText()
                if (visible.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(visible, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.durableText() ?: "")
            }
            messages.add(aiMessage)

            // 独立视觉路由：本请求的截图已由视觉模型识别完毕，折叠为文本——
            // 下一轮请求不再带图，因而回落主模型（视觉模型只承担"看图"的轮次）。
            // 识别结论已随上面的 aiMessage 文本留存，跨轮推理不受影响
            if (visionRoutingActive && requestHadImages) {
                ContextBudget.foldConsumedImages(messages)
            }

            // 非流式模式下推送思考内容
            if (!config.streaming && !llmResponse.text.isNullOrEmpty()) {
                callback.onContent(iterations, llmResponse.text)
            }

            // 如果没有工具调用，Agent 认为完成了
            if (!llmResponse.hasToolExecutionRequests()) {
                callback.onComplete(iterations, llmResponse.text ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens)
                return
            }

            // 执行工具调用
            for (toolRequest in llmResponse.toolExecutionRequests) {
                if (cancelled.get()) {
                    callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
                    return
                }

                val toolName = toolRequest.name() ?: ""
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                val toolArgs = toolRequest.arguments() ?: "{}"
                callback.onToolCall(iterations, toolName, displayName, toolArgs)

                // ④ RESTRICT 生效轮：模型仍输出观察类调用 → 不执行，回错误结果把它逼向操作
                val observationBlocked = restrictedRound && toolName in LoopGuard.OBSERVATION_TOOLS

                // 解析参数
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                var params: Map<String, Any>? = try {
                    GSON.fromJson(toolArgs, mapType)
                } catch (e: Exception) {
                    HashMap()
                }
                if (params == null) params = HashMap()

                // C6/C5：按 App 自动化策略门（活读——总开关与策略表每次调用即时生效）。
                // BLOCK/总开关关闭 → 不执行并回传说明；CONFIRM（逐操作确认）命中后跳过 F2，避免双重弹窗。
                // F2 是全局硬底线：Allow 后仍走危险操作关键词确认，策略 AUTO 不会弱化它。
                val appPolicyCtx = AppPolicyEngine.Ctx(
                    foregroundPackage = DeviceProbe.foregroundPackage(),
                    masterThirdPartyEnabled = KVUtils.getThirdPartyAutomationEnabled(),
                    isSystemApp = { pkg -> DeviceProbe.isSystemPackage(pkg) },
                    modeOf = { pkg -> AppPolicyStore.find(pkg)?.mode }
                )
                var result = if (observationBlocked) {
                    ToolResult.error(LoopGuard.restrictedToolError())
                } else when (val verdict = AppPolicyEngine.evaluate(toolName, params, appPolicyCtx)) {
                    is AppPolicyEngine.Verdict.Block ->
                        ToolResult.error(policyBlockMessage(verdict, displayName))
                    is AppPolicyEngine.Verdict.Confirm -> {
                        val prompt = ClawApplication.instance.getString(
                            R.string.compliance_app_policy_confirm, displayName, verdict.targetPackage)
                        val confirmed = callback.onAwaitUser(prompt, UserDecisionGate.CONFIRM_TIMEOUT_MS)
                        if (confirmed) {
                            ToolRegistry.getInstance().executeTool(toolName, params)
                        } else {
                            ToolResult.error(ClawApplication.instance.getString(R.string.agent_confirm_rejected_skip))
                        }
                    }
                    // F2：疑似不可逆操作（发送/支付/删除类关键词）→ 经渠道请求用户确认后再执行
                    AppPolicyEngine.Verdict.Allow -> {
                        if (config.confirmDangerousOps) {
                            val risk = DangerousOpDetector.assess(toolName, params)
                            if (risk != null) {
                                val prompt = ClawApplication.instance.getString(
                                    R.string.agent_confirm_prompt, displayName, risk)
                                val confirmed = callback.onAwaitUser(prompt, UserDecisionGate.CONFIRM_TIMEOUT_MS)
                                if (!confirmed) {
                                    ToolResult.error(ClawApplication.instance.getString(R.string.agent_confirm_rejected_skip))
                                } else {
                                    ToolRegistry.getInstance().executeTool(toolName, params)
                                }
                            } else {
                                ToolRegistry.getInstance().executeTool(toolName, params)
                            }
                        } else {
                            ToolRegistry.getInstance().executeTool(toolName, params)
                        }
                    }
                }

                // F4：结果验证闭环——执行成功后回读设备状态断言，失败自动重试一次，仍失败才暴露给模型
                if (config.verifyResults && result.isSuccess) {
                    val assertion = ResultVerifier.assertionFor(toolName, params)
                    if (assertion != null) {
                        var retryResult: ToolResult? = null
                        val outcome = ResultVerifier.verify(
                            probe = DeviceProbe,
                            check = assertion.check,
                            retry = {
                                callback.onToolCall(iterations, toolName, displayName, toolArgs)
                                retryResult = ToolRegistry.getInstance().executeTool(toolName, params)
                            }
                        )
                        if (!outcome.verified) {
                            XLog.w(TAG, "Result verification failed after ${outcome.retries} retry: ${assertion.describe}")
                            callback.onVerifyFailed(assertion.describe)
                            result = retryResult?.takeIf { !it.isSuccess }
                                ?: ToolResult.error(ClawApplication.instance.getString(R.string.agent_verify_failed, assertion.describe))
                        }
                    }
                }

                val paramsString = if (params.isEmpty()) "" else params.toString()
                callback.onToolResult(iterations, toolName, displayName, paramsString, result)

                // F1：检测到系统弹窗阻塞 → 截图通知用户后挂起等待；用户处理后从中断轮次恢复
                if (!result.isSuccess && result.error == GetScreenInfoTool.SYSTEM_DIALOG_BLOCKED) {
                    XLog.w(TAG, "System dialog blocked, notifying user and suspending task")
                    callback.onSystemDialogBlocked(iterations, totalTokens)
                    val proceed = callback.onAwaitUser(
                        ClawApplication.instance.getString(R.string.agent_dialog_resume_prompt),
                        UserDecisionGate.DIALOG_WAIT_TIMEOUT_MS
                    )
                    // 被阻塞的工具调用必须补上结果消息，后续轮次的 API 请求才合法
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(result)))
                    if (proceed) {
                        messages.add(
                            UserMessage.from("[系统提示] 用户已手动处理系统弹窗，请从中断处继续执行任务。")
                        )
                        continue@loop
                    } else {
                        callback.onError(
                            iterations,
                            RuntimeException(ClawApplication.instance.getString(R.string.agent_dialog_wait_aborted)),
                            totalTokens
                        )
                        return
                    }
                }

                // finish 工具 → 任务完成
                if (toolName == "finish" && result.isSuccess) {
                    val finishData = result.data
                    callback.onComplete(iterations, finishData ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens)
                    return
                }

                // 记录指纹用于死循环检测
                if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
                    lastScreenHash = result.data.hashCode()
                } else if (toolName.isNotEmpty() && toolName != "get_screen_info") {
                    loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                    if (loopHistory.size > LOOP_DETECT_WINDOW) {
                        loopHistory.removeFirst()
                    }
                }

                // 添加工具结果到消息
                val resultJson = GSON.toJson(result)
                messages.add(ToolExecutionResultMessage.from(toolRequest, resultJson))

                // F10：截图作为图像消息进入 LLM 上下文；注入数量由上下文预算动态约束
                //（ContextBudget.makeRoomForImage：预算内全保留，超预算折叠最旧图），无固定张数上限
                if (toolName == "take_screenshot" && result.isSuccess && result.data != null) {
                    if (!config.visionEnabled) {
                        messages.add(ScreenshotEncoder.disabledMessage())
                    } else {
                        val encoded = ScreenshotEncoder.encode(File(result.data))
                        if (encoded != null && ContextBudget.makeRoomForImage(messages, charBudget)) {
                            screenshotCount++
                            messages.add(ScreenshotEncoder.imageMessage(encoded, screenshotCount, result.data!!))
                        } else if (encoded != null) {
                            messages.add(ScreenshotEncoder.budgetOmittedMessage())
                        }
                    }
                }

                // F10 视觉兜底：find_node_info 连续失败（WebView/自绘界面读不到节点）时自动截图注入
                //（RESTRICT 生效轮不计数、不兜底——观察被禁是为了逼它行动）
                if (toolName == "find_node_info" && !observationBlocked) {
                    findMissStreak = if (result.isSuccess) 0 else findMissStreak + 1
                    if (config.visionEnabled && findMissStreak >= AUTO_SCREENSHOT_AFTER_FIND_MISSES) {
                        val shot = ToolRegistry.getInstance().executeTool("take_screenshot", emptyMap())
                        val shotPath = shot.data
                        if (shot.isSuccess && shotPath != null) {
                            val encoded = ScreenshotEncoder.encode(File(shotPath))
                            if (encoded != null && ContextBudget.makeRoomForImage(messages, charBudget)) {
                                screenshotCount++
                                XLog.i(TAG, "Auto screenshot injected after $findMissStreak find_node_info misses")
                                messages.add(ScreenshotEncoder.autoTriggeredMessage(encoded, screenshotCount))
                            }
                        }
                        findMissStreak = 0   // 触发后清零，避免每轮都补截图
                    }
                }

                XLog.d(TAG, "displayName:$displayName toolName:$toolName")
            }

            // ④⑤ 观察阶梯：连续纯观察轮（模型只看不做）按 NUDGE → RESTRICT → FORCE_FINISH 升级。
            // 真机实证：仅提示不设约束时，模型曾连续 50 轮纯截图、无视全部提示、0 次操作。
            val roundCalls = llmResponse.toolExecutionRequests.map { it.name() ?: "" }
            val roundAllObservation = LoopGuard.isPureObservation(roundCalls)
            if (roundAllObservation) observationStreak++ else observationStreak = 0
            when (LoopGuard.escalationFor(observationStreak)) {
                LoopGuard.Escalation.NUDGE ->
                    messages.add(UserMessage.from(LoopGuard.nudgeMessage(loopHistory.map { it.toolCall })))
                LoopGuard.Escalation.RESTRICT -> {
                    if (!restrictedRound) {
                        restrictedRound = true
                        messages.add(UserMessage.from(LoopGuard.restrictionMessage(observationStreak)))
                    }
                }
                LoopGuard.Escalation.FORCE_FINISH -> {
                    XLog.w(TAG, "Force finish: $observationStreak consecutive observation rounds without action")
                    callback.onError(
                        iterations,
                        RuntimeException(ClawApplication.instance.getString(R.string.agent_loop_force_finish, observationStreak)),
                        totalTokens
                    )
                    return
                }
                LoopGuard.Escalation.NONE -> {}
            }
            // 模型在受限轮后恢复了行动 → 解除限制并告知
            if (restrictedRound && LoopGuard.escalationFor(observationStreak) != LoopGuard.Escalation.RESTRICT) {
                restrictedRound = false
                messages.add(UserMessage.from(LoopGuard.restoreMessage()))
            }

            // 操作类死循环检测（指纹重复）：纯观察循环已由上面的阶梯接管，避免重复提示
            if (!roundAllObservation && isStuckInLoop(loopHistory)) {
                XLog.w(TAG, "Dead loop detected at iteration $iterations")
                messages.add(UserMessage.from(LoopGuard.nudgeMessage(loopHistory.map { it.toolCall })))
                loopHistory.clear()
            }
            XLog.d(TAG, "轮数:$iterations all=$totalTokens 本轮=${llmResponse.tokenUsage?.totalTokenCount()}")
        }

        if (cancelled.get()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
        } else {
            callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, maxIterations)), totalTokens)
        }
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun shutdown() {
        cancel()
        settleDiscardedTasks(executor?.shutdownNow()?.size ?: 0)
    }

    override fun isRunning(): Boolean = running.get()

    /** C6：策略拒绝文案（BLOCK 策略 vs 总开关关闭），含目标包名与处理指引 */
    private fun policyBlockMessage(verdict: AppPolicyEngine.Verdict.Block, displayName: String): String {
        val app = ClawApplication.instance
        return when (verdict.reason) {
            AppPolicyEngine.Verdict.Reason.APP_BLOCKED -> app.getString(
                R.string.compliance_app_blocked, displayName, verdict.targetPackage)
            AppPolicyEngine.Verdict.Reason.MASTER_DISABLED -> app.getString(
                R.string.compliance_third_party_disabled, displayName, verdict.targetPackage)
        }
    }

    /** 用一次无工具的 LLM 调用合并旧摘要、新增轮次与任务执行轨迹；失败返回 null（退化为丢弃，即现状行为） */
    private fun summarizeDigest(oldDigest: String, turns: List<SessionStore.Turn>, transcript: String): String? {
        val sb = StringBuilder()
        if (oldDigest.isNotBlank()) sb.append("既有摘要：\n").append(oldDigest).append("\n\n")
        if (turns.isNotEmpty()) {
            sb.append("新增对话：\n")
            turns.forEach {
                sb.append("用户：").append(it.user).append("\n")
                sb.append("助手：").append(it.assistant).append("\n\n")
            }
        }
        if (transcript.isNotBlank()) {
            sb.append("上次任务的工具执行轨迹（含中间状态）：\n").append(transcript).append("\n\n")
        }
        val msgs = listOf<ChatMessage>(
            SystemMessage.from(
                "你是会话摘要器。把既有摘要、新增对话与任务执行轨迹合并为一份结构化摘要（Claude Code compact 同款格式），" +
                    "只保留对后续任务有用的信息，总长不超过 500 字，直接输出正文：\n" +
                    "## 背景与目标\n（用户在做什么、为什么）\n" +
                    "## 用户偏好与关键事实\n（稳定的事实、偏好、约定）\n" +
                    "## 任务状态与未完成事项\n（上次任务进行到哪一步、值得保留的中间状态" +
                    "（如界面元素的位置与文字、已收集的数据、当前进度）、下一步该做什么；没有则写\"无\"）"
            ),
            UserMessage.from(sb.toString())
        )
        return try {
            llmClient.chat(msgs, emptyList()).text?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            XLog.w(TAG, "digest summarization failed, evicted turns dropped instead", e)
            null
        }
    }
}

package com.apk.claw.android

import com.apk.claw.android.agent.AgentCallback
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.AgentService
import com.apk.claw.android.agent.AgentServiceFactory
import com.apk.claw.android.agent.TaskRequest
import com.apk.claw.android.agent.UserDecisionGate
import com.apk.claw.android.agent.ReplyKeywords
import com.apk.claw.android.agent.store.SessionStore
import com.apk.claw.android.agent.store.TaskHistoryStore
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.compliance.AdmissionControl
import com.apk.claw.android.floating.FloatingCircleManager
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog

/**
 * 任务编排器，负责 Agent 生命周期管理、任务锁、任务执行与回调处理。
 *
 * @param agentConfigProvider 延迟获取最新 AgentConfig 的回调
 * @param onTaskFinished 每次任务结束（成功/失败/取消）后的通知，用于刷新用户信息等
 */
class TaskOrchestrator(
    private val agentConfigProvider: () -> AgentConfig,
    private val onTaskFinished: () -> Unit
) {

    companion object {
        private const val TAG = "TaskOrchestrator"

        /** 纯观察类工具：执行细节对用户无信息量，不进入进度摘要 */
        private val PROGRESS_SILENT_TOOLS = setOf(
            "get_screen_info", "find_node_info", "take_screenshot", "get_installed_apps", "wait"
        )
    }

    private lateinit var agentService: AgentService

    private val taskLock = Any()
    @Volatile
    var inProgressTaskMessageId: String = ""
        private set
    @Volatile
    var inProgressTaskChannel: Channel? = null
        private set

    /** 任务结束（完成/失败/取消/弹窗终止）且锁已释放后回调。注意：回调在后台线程执行。 */
    @Volatile
    var onIdle: (() -> Unit)? = null

    /** 查询某渠道某发送者当前排队中的任务文本（由 ChannelSetup 注入，避免反向依赖队列实现） */
    @Volatile
    var pendingTasksProvider: ((Channel, String) -> List<String>)? = null

    /** C3：任务准入控制（限频/静默/冷却/熔断），ChannelSetup 到达与排空共用同一实例 */
    val admission = AdmissionControl()

    // ==================== 用户决策门控（F1 挂起恢复 / F2 危险操作确认） ====================

    private val gateLock = Any()
    private var activeGate: UserDecisionGate? = null
    private var gateChannel: Channel? = null
    private var gateSender: String? = null

    /**
     * 拦截处于等待用户决策状态的任务的渠道回复。命中确认/取消关键字时放行门控并返回 true
     * （消息已消费，不再进入任务队列）。
     */
    fun interceptReply(channel: Channel, senderId: String, message: String): Boolean {
        val gate = synchronized(gateLock) {
            if (channel == gateChannel && senderId == gateSender) activeGate else null
        } ?: return false
        val cmd = message.trim().lowercase()
        return when {
            cmd in ReplyKeywords.CONFIRM -> { gate.resolve(true); true }
            cmd in ReplyKeywords.CANCEL -> { gate.resolve(false); true }
            else -> false
        }
    }

    /** 发送等待提示并阻塞等待用户决策；仅接受 (channel, senderId) 的回复，超时视为拒绝。 */
    private fun awaitUserDecision(
        channel: Channel, senderId: String, messageId: String, prompt: String, timeoutMs: Long
    ): Boolean {
        val gate = UserDecisionGate(timeoutMs)
        synchronized(gateLock) {
            activeGate = gate
            gateChannel = channel
            gateSender = senderId
        }
        try {
            ChannelManager.sendMessage(channel, prompt, messageId)
            ChannelManager.flushMessages(channel)
            val decision = gate.await { false } // 外部取消由 cancelCurrentTask 直接 resolve(false)
            return decision == UserDecisionGate.Decision.CONFIRMED
        } finally {
            synchronized(gateLock) {
                if (activeGate === gate) {
                    activeGate = null
                    gateChannel = null
                    gateSender = null
                }
            }
        }
    }

    private fun notifyIdle() {
        try { onIdle?.invoke() } catch (e: Exception) { XLog.e(TAG, "onIdle callback failed", e) }
    }

    // ==================== Agent 生命周期 ====================

    fun initAgent() {
        // 先关旧实例：DefaultAgentService 持有单线程 executor，直接替换会泄漏线程
        // （LAN 页保存 LLM 配置即走此路径）。与 updateConfig 同语义：有任务在跑则中断。
        if (::agentService.isInitialized) {
            try {
                agentService.shutdown()
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to shutdown previous AgentService", e)
            }
        }
        agentService = AgentServiceFactory.create()
        try {
            agentService.initialize(agentConfigProvider())
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to initialize AgentService", e)
        }
    }

    fun updateAgentConfig(): Boolean {
        return try {
            val config = agentConfigProvider()
            if (::agentService.isInitialized) {
                agentService.updateConfig(config)
                XLog.d(TAG, "Agent config updated: model=${config.modelName}, temp=${config.temperature}")
                true
            } else {
                XLog.w(TAG, "AgentService not initialized, initializing with new config")
                agentService = AgentServiceFactory.create()
                agentService.initialize(config)
                true
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to update agent config", e)
            false
        }
    }

    // ==================== 任务锁 ====================

    /**
     * 原子地尝试获取任务锁。如果当前无任务在执行，则标记为占用并返回 true；否则返回 false。
     */
    fun tryAcquireTask(messageId: String, channel: Channel): Boolean {
        synchronized(taskLock) {
            if (inProgressTaskMessageId.isNotEmpty()) return false
            inProgressTaskMessageId = messageId
            inProgressTaskChannel = channel
            return true
        }
    }

    /**
     * 释放任务锁，返回释放前的 (channel, messageId) 供调用方使用。
     */
    private fun releaseTask(): Pair<Channel?, String> {
        synchronized(taskLock) {
            val ch = inProgressTaskChannel
            val id = inProgressTaskMessageId
            inProgressTaskMessageId = ""
            inProgressTaskChannel = null
            return ch to id
        }
    }

    fun isTaskRunning(): Boolean {
        synchronized(taskLock) {
            return inProgressTaskMessageId.isNotEmpty()
        }
    }

    // ==================== 任务执行 ====================

    fun cancelCurrentTask() {
        if (!isTaskRunning()) return
        // 若正在等待用户决策（F1/F2），先放行门控以免 Agent 线程滞留在等待中
        synchronized(gateLock) { activeGate?.resolve(false) }
        if (::agentService.isInitialized) {
            agentService.cancel()
        }
        val (channel, messageId) = releaseTask()
        if (channel != null && messageId.isNotEmpty()) {
            ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_task_cancelled), messageId)
        }
        FloatingCircleManager.setErrorState()
        onTaskFinished()
        // 不在此处 notifyIdle：Agent 线程尚未空闲，排队排空由任务收尾的 onSettled 触发
        XLog.d(TAG, "Current task cancelled by user")
    }

    fun startNewTask(channel: Channel, senderId: String, task: String, messageID: String) {
        if (!::agentService.isInitialized) {
            XLog.e(TAG, "AgentService not initialized, attempting to initialize")
            try {
                agentService = AgentServiceFactory.create()
                agentService.initialize(agentConfigProvider())
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to initialize AgentService", e)
                releaseTask()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_service_not_ready), messageID)
                notifyIdle()   // 队列中后续消息也依次走同一失败路径，避免队列滞留
                return
            }
        }

        admission.recordStart(channel.name, System.currentTimeMillis())   // C3：登记启动时刻与频率窗口

        FloatingCircleManager.showTaskNotify(task, channel)

        /** 任务列表文案：正在执行的本任务 + 本发送者的排队任务（无内容则返回 null） */
        fun buildTaskListFooter(includeRunning: Boolean): String? {
            val pending = pendingTasksProvider?.invoke(channel, senderId).orEmpty()
            val sb = StringBuilder()
            if (includeRunning) {
                sb.append(ClawApplication.instance.getString(R.string.channel_task_start, task))
            }
            pending.forEach { p ->
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(ClawApplication.instance.getString(R.string.channel_task_pending, p))
            }
            return if (sb.isEmpty()) null else sb.toString()
        }

        // 任务开始即发任务列表（与结束各一次；中间进度不带，避免每步刷屏）
        buildTaskListFooter(includeRunning = true)?.let {
            ChannelManager.sendMessage(channel, it, messageID)
        }

        // 每轮消息聚合缓冲：thinking 攒一条；工具明细只聚合成一行摘要，减少发送量
        val roundBuffer = StringBuilder()
        val roundActions = LinkedHashMap<String, Int>()
        var roundFailures = 0

        // F3：任务历史轨迹（任务结束时一次性落盘）
        val taskStartTime = System.currentTimeMillis()
        val toolTrace = mutableListOf<String>()
        var finalRounds = 0
        var toolCallCount = 0
        var verifyFailures = 0

        fun recordHistory(status: TaskHistoryStore.Status, totalTokens: Int, error: String = "") {
            // C3 熔断计数与 F3 历史同点落账：失败 +1、成功清零；等待超时/取消不计入失败
            when (status) {
                TaskHistoryStore.Status.FAILED -> KVUtils.setFailureStreak(KVUtils.getFailureStreak() + 1)
                TaskHistoryStore.Status.COMPLETED -> KVUtils.setFailureStreak(0)
                else -> {}
            }
            try {
                TaskHistoryStore.append(
                    TaskHistoryStore.TaskRecord(
                        id = messageID,
                        startTime = taskStartTime,
                        endTime = System.currentTimeMillis(),
                        channel = channel.name,
                        sender = senderId,
                        task = task,
                        status = status.name,
                        rounds = finalRounds,
                        toolCalls = toolCallCount,
                        tokens = totalTokens,
                        error = error,
                        toolTrace = toolTrace.toList(),
                        verifyFailures = verifyFailures
                    )
                )
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to append task history", e)
            }
        }

        fun flushRoundBuffer() {
            if (roundActions.isNotEmpty()) {
                val joined = roundActions.entries.joinToString("、") { e ->
                    if (e.value > 1) "${e.key}×${e.value}" else e.key
                }
                if (roundBuffer.isNotEmpty()) roundBuffer.append("\n")
                roundBuffer.append(
                    if (roundFailures > 0) ClawApplication.instance.getString(R.string.channel_msg_round_tools_failed, joined, roundFailures)
                    else ClawApplication.instance.getString(R.string.channel_msg_round_tools, joined)
                )
                roundActions.clear()
                roundFailures = 0
            }
            if (roundBuffer.isNotEmpty()) {
                ChannelManager.sendMessage(channel, roundBuffer.toString().trim(), messageID)
                roundBuffer.clear()
            }
        }

        agentService.executeTask(TaskRequest(task, channel, senderId), object : AgentCallback {
            override fun onLoopStart(round: Int) {
                // 新一轮开始前，flush 上一轮积攒的消息
                flushRoundBuffer()
                finalRounds = round
                FloatingCircleManager.setRunningState(round, channel)
            }

            override fun onContent(round: Int, content: String) {
                if (content.isNotEmpty()) {
                    roundBuffer.append(content)
                }
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                XLog.d(TAG, "onToolCall: $toolId($toolName), $parameters")
                toolCallCount++
                toolTrace.add("$toolName($parameters)")
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                val app = ClawApplication.instance
                val status = if (result.isSuccess) app.getString(R.string.channel_msg_tool_success) else app.getString(R.string.channel_msg_tool_failure)
                var data = if (result.isSuccess) result.data else result.error
                if (data != null && data.length > 300) {
                    data = data.substring(0, 300) + "...(truncated)"
                }
                if (!result.isSuccess) {
                    XLog.e(TAG, "!!!!!!!!!!Fail: $toolName, $parameters $data")
                }
                XLog.e(TAG, "onToolResult: $toolName, $status $data")
                if (toolId == "finish" && (result.data?.isNotEmpty() ?: false)) {
                    // finish 的结果单独发，不合并（这是最终回复）；待执行列表由 onComplete 统一追加
                    flushRoundBuffer()
                    ChannelManager.sendMessage(channel, result.data, messageID)
                } else if (toolId !in PROGRESS_SILENT_TOOLS) {
                    // 只做本轮聚合计数，flush 时统一发一行摘要；观察类工具完全不上屏
                    if (!result.isSuccess) roundFailures++
                    roundActions[toolName] = (roundActions[toolName] ?: 0) + 1
                }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                XLog.i(TAG, "onComplete: 轮数=$round, totalTokens=$totalTokens, answer=$finalAnswer")
                SessionStore.appendTurn(channel, senderId, task, finalAnswer)
                // 任务结束：待执行列表挂在最后一条消息尾部（无论模型以文字结束还是走 finish 工具，
                // onComplete 都是必经点）；缓冲为空时单独发一条列表
                buildTaskListFooter(includeRunning = false)?.let {
                    if (roundBuffer.isNotEmpty()) roundBuffer.append("\n")
                    roundBuffer.append(it)
                }
                flushRoundBuffer()
                ChannelManager.flushMessages(channel)
                FloatingCircleManager.setSuccessState()
                onTaskFinished()
                recordHistory(TaskHistoryStore.Status.COMPLETED, totalTokens)
                // 任务锁延迟到 onSettled 释放：此刻 Agent 的 running 标志尚未清除，
                // 提前放锁会让窗口期内的新消息拿到锁却被 executeTask 以"已在运行"拒绝
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                XLog.e(TAG, "onError: ${error.message}, totalTokens=$totalTokens", error)
                flushRoundBuffer()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_task_error, error.message), messageID)
                ChannelManager.flushMessages(channel)
                FloatingCircleManager.setErrorState()
                onTaskFinished()
                recordHistory(TaskHistoryStore.Status.FAILED, totalTokens, error.message ?: "unknown")
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                XLog.w(TAG, "onSystemDialogBlocked: round=$round, totalTokens=$totalTokens")
                // F1：发截图与说明后任务挂起，等待用户处理（onAwaitUser），不再直接终止
                flushRoundBuffer()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_system_dialog_blocked), messageID)
                try {
                    val service = ClawAccessibilityService.getInstance()
                    val bitmap = service?.takeScreenshot(5000)
                    if (bitmap != null) {
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
                        bitmap.recycle()
                        ChannelManager.sendImage(channel, stream.toByteArray(), messageID)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to send screenshot for system dialog", e)
                }
            }

            override fun onAwaitUser(prompt: String, timeoutMs: Long): Boolean {
                val confirmed = awaitUserDecision(channel, senderId, messageID, prompt, timeoutMs)
                if (!confirmed) {
                    recordHistory(TaskHistoryStore.Status.WAIT_TIMEOUT, 0, "user wait timeout/rejected")
                }
                return confirmed
            }

            override fun onVerifyFailed(description: String) {
                // F4：结果断言重试后仍失败，计入遥测
                verifyFailures++
                toolTrace.add("verify-failed($description)")
            }

            override fun onVisionDegraded(reason: String) {
                // 视觉降级必须让用户看见：任务从此靠节点树感知，自绘界面将不可见
                XLog.w(TAG, "onVisionDegraded: $reason")
                toolTrace.add("vision-degraded($reason)")
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_vision_degraded), messageID)
            }

            override fun onSettled() {
                // Agent running 标志已清除、执行线程即将空闲：此刻释放任务锁并排空队列，
                // 才不会出现"锁空闲但 Agent 仍占用"导致的假性拒绝
                releaseTask()
                notifyIdle()
            }
        })
    }
}

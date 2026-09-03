package com.apk.claw.android.channel

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.TaskOrchestrator
import com.apk.claw.android.agent.store.SessionStore
import com.apk.claw.android.compliance.AdmissionControl
import com.apk.claw.android.compliance.ComplianceConfig
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog

/**
 * 通道初始化与消息路由。
 * 负责读取本地配置初始化各通道，并将收到的消息分发给 [TaskOrchestrator]。
 */
class ChannelSetup(
    private val taskOrchestrator: TaskOrchestrator
) {

    private data class PendingMessage(
        val channel: Channel, val senderId: String, val message: String, val messageID: String,
        /** C3：是否自动任务（F7 定时等无人值守来源）——决定静默/冷却/熔断是否适用 */
        val automated: Boolean
    )

    private val queueLock = Any()
    private val pendingQueue = ArrayDeque<PendingMessage>()

    /** dispatch 层进程级兜底去重，防御渠道层多实例重复投递 */
    private val inboundDeduper = InboundDeduper()

    companion object {
        private const val TAG = "ChannelSetup"
        private const val MAX_PENDING = 3
    }

    fun setup() {
        ChannelManager.init(
            dingtalkAppKey = KVUtils.getDingtalkAppKey().ifEmpty { null },
            dingtalkAppSecret = KVUtils.getDingtalkAppSecret().ifEmpty { null },
            feishuAppId = KVUtils.getFeishuAppId().ifEmpty { null },
            feishuAppSecret = KVUtils.getFeishuAppSecret().ifEmpty { null },
            qqAppId = KVUtils.getQqAppId().ifEmpty { null },
            qqAppSecret = KVUtils.getQqAppSecret().ifEmpty { null },
            discordBotToken = KVUtils.getDiscordBotToken().ifEmpty { null },
            telegramBotToken = KVUtils.getTelegramBotToken().ifEmpty { null },
            wechatBotToken = KVUtils.getWechatBotToken().ifEmpty { null },
            wechatApiBaseUrl = KVUtils.getWechatApiBaseUrl().ifEmpty { null }
        )
        ChannelManager.setOnMessageReceivedListener(object : ChannelManager.OnMessageReceivedListener {
            override fun onMessageReceived(channel: Channel, message: String, messageID: String, senderId: String) {
                XLog.i(TAG, "msg from ${channel.displayName} sender=$senderId")
                dispatch(channel, message, messageID, senderId)
            }
        })

        taskOrchestrator.onIdle = { drainPending() }

        // 供进度消息尾部任务列表查询排队任务（按渠道+发送者过滤，避免跨用户噪音）
        taskOrchestrator.pendingTasksProvider = { channel, senderId ->
            synchronized(queueLock) {
                pendingQueue.filter { it.channel == channel && it.senderId == senderId }.map { it.message }
            }
        }
    }

    /**
     * 消息派发统一入口：渠道消息与 F7 定时任务都经此进入任务链路，
     * 保证无障碍检查、确认门控、任务锁与排队行为一致。
     * @param automated 是否自动任务（定时/事件等无人值守来源；由调用方标注）
     */
    fun dispatch(channel: Channel, message: String, messageID: String, senderId: String, automated: Boolean = false) {
        if (inboundDeduper.isDuplicate(channel, senderId, message)) {
            XLog.w(TAG, "重复 dispatch 已拦截: channel=${channel.displayName}, message=${message.take(40)}")
            return
        }

        val app = ClawApplication.instance
        if (!ClawAccessibilityService.isRunning()) {
            ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_no_accessibility), messageID)
            ChannelManager.flushMessages(channel)
            return
        }

        val cmd = message.trim()
        if (cmd == "新对话" || cmd == "/new") {
            SessionStore.reset(channel, senderId)
            ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_session_reset), messageID)
            ChannelManager.flushMessages(channel)
            return
        }

        // F1/F2：有任务在等待用户决策时，确认/取消关键字直接放行门控
        if (taskOrchestrator.interceptReply(channel, senderId, message)) {
            return
        }

        // C3 准入：限频/静默/冷却/熔断（配置活读）。SKIP 回渠道说明后丢弃；NOTICE 先发提示再继续
        val cfg = KVUtils.loadComplianceConfig()
        when (val outcome = taskOrchestrator.admission.check(channel.name, automated, cfg, System.currentTimeMillis())) {
            is AdmissionControl.Outcome.Skip -> {
                ChannelManager.sendMessage(channel, admissionMessage(cfg, outcome.reason, false), messageID)
                ChannelManager.flushMessages(channel)
                return
            }
            is AdmissionControl.Outcome.Notice -> {
                ChannelManager.sendMessage(channel, admissionMessage(cfg, outcome.reason, true), messageID)
                ChannelManager.flushMessages(channel)
            }
            else -> {}
        }

        if (!taskOrchestrator.tryAcquireTask(messageID, channel)) {
            val queued = synchronized(queueLock) {
                if (pendingQueue.size >= MAX_PENDING) null
                else { pendingQueue.addLast(PendingMessage(channel, senderId, message, messageID, automated)); pendingQueue.size }
            }
            val reply = if (queued != null) {
                app.getString(R.string.channel_msg_queued, queued)
            } else {
                app.getString(R.string.channel_msg_queue_full)
            }
            ChannelManager.sendMessage(channel, reply, messageID)
            ChannelManager.flushMessages(channel)
            return
        }
        taskOrchestrator.startNewTask(channel, senderId, message, messageID)
    }

    /**
     * 任务空闲后排空待执行消息队列。每次只启动一条；其结束后 onIdle 会再次触发。
     * 弹出时重跑 C3 准入（排队期间配置/熔断状态可能已变化），被拒则回渠道说明并继续下一条。
     */
    private fun drainPending() {
        while (true) {
            val next = synchronized(queueLock) { pendingQueue.firstOrNull() } ?: return
            val cfg = KVUtils.loadComplianceConfig()
            when (val outcome = taskOrchestrator.admission.check(next.channel.name, next.automated, cfg, System.currentTimeMillis())) {
                is AdmissionControl.Outcome.Skip -> {
                    ChannelManager.sendMessage(next.channel, admissionMessage(cfg, outcome.reason, false), next.messageID)
                    ChannelManager.flushMessages(next.channel)
                    synchronized(queueLock) { pendingQueue.removeFirst() }
                    continue
                }
                is AdmissionControl.Outcome.Notice -> {
                    ChannelManager.sendMessage(next.channel, admissionMessage(cfg, outcome.reason, true), next.messageID)
                    ChannelManager.flushMessages(next.channel)
                }
                else -> {}
            }
            if (!taskOrchestrator.tryAcquireTask(next.messageID, next.channel)) return
            synchronized(queueLock) { pendingQueue.removeFirst() }
            taskOrchestrator.startNewTask(next.channel, next.senderId, next.message, next.messageID)
            return
        }
    }

    /** C3 拒绝/提示文案组装；@param notice true 表示放行前的提示（仅熔断未解除的手动任务） */
    private fun admissionMessage(cfg: ComplianceConfig, reason: AdmissionControl.Reason, notice: Boolean): String {
        val app = ClawApplication.instance
        return when (reason) {
            AdmissionControl.Reason.RATE_LIMITED -> app.getString(R.string.compliance_rate_limited)
            AdmissionControl.Reason.QUIET_HOURS -> app.getString(
                R.string.compliance_quiet_hour_skip,
                ComplianceConfig.formatHm(cfg.quietStartMin),
                ComplianceConfig.formatHm(cfg.quietEndMin)
            )
            AdmissionControl.Reason.COOLDOWN -> app.getString(R.string.compliance_cooldown_skip, cfg.cooldownSec)
            AdmissionControl.Reason.BREAKER -> if (notice) {
                app.getString(R.string.compliance_breaker_notice, cfg.failureStreak, cfg.breakerThreshold)
            } else {
                app.getString(R.string.compliance_breaker_skip, cfg.failureStreak, cfg.breakerThreshold)
            }
        }
    }
}

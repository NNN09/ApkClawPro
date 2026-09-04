package com.apk.claw.android.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.trigger.TriggerRuleEngine
import com.apk.claw.android.trigger.TriggerRuleStore
import com.apk.claw.android.utils.XLog

/**
 * F8 事件驱动触发：监听白名单 App 的通知，命中规则后把渲染的任务指令
 * 经 ChannelSetup.dispatch 同一入口派发（automated=true，受 C3 频控/静默/熔断约束）。
 *
 * 合规边界（ROADMAP §6 / docs/COMPLIANCE.md）：
 * - 总开关默认关闭；白名单与规则全部为空，用户在 LAN 页显式配置后才可能触发；
 * - 每次触发以一条 automated 任务进入 F3 任务历史，天然留痕可审计；
 * - 本服务只读取白名单 App 的通知文本做规则匹配，不存储通知原文。
 */
class TriggerNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "TriggerListener"

        /** 同一（来源包 × 规则）的最短触发间隔：进度条/合拢通知的高频重发不重复派任务 */
        private const val DEBOUNCE_MS = 10_000L

        private val lastFireAt = mutableMapOf<String, Long>()

        /** 用户是否已在系统设置里授予本应用"通知使用权"（LAN 页状态展示用） */
        fun isListenerBound(context: android.content.Context): Boolean =
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            val config = TriggerRuleStore.get()
            if (!config.enabled) return
            val pkg = sbn?.packageName ?: return
            if (pkg == packageName) return
            val notification = sbn.notification ?: return
            // 常驻/进度类通知（下载条、前台服务）不参与触发
            if (notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) return
            val extras = notification.extras
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return

            val match = TriggerRuleEngine.evaluate(pkg, title, text, config) ?: return

            val key = "$pkg|${match.rule.name}"
            val now = System.currentTimeMillis()
            synchronized(lastFireAt) {
                val last = lastFireAt[key] ?: 0L
                if (now - last < DEBOUNCE_MS) return
                lastFireAt[key] = now
            }

            XLog.i(TAG, "Trigger rule '${match.rule.name}' fired for $pkg")
            // 与定时任务同一自动化入口：C3 准入（限频/静默/冷却/熔断）对此生效
            ClawApplication.appViewModelInstance.dispatchTask(
                Channel.IN_APP, "local", match.task, "trig-$now", automated = true
            )
        } catch (e: Exception) {
            XLog.e(TAG, "onNotificationPosted failed", e)
        }
    }
}

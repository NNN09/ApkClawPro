package com.apk.claw.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.agent.store.ScheduledTaskStore
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.utils.XLog

/**
 * F7：定时任务触发接收器。到点后把任务排下一次，并经渠道消息同一入口派发指令，
 * 复用无障碍检查、任务锁与排队逻辑；结果沿任务绑定的渠道回传。
 */
class ScheduledTaskReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduledTaskReceiver"
        const val ACTION_FIRE = "com.apk.claw.android.action.SCHEDULED_TASK_FIRE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val taskId = intent.getStringExtra(TaskScheduler.EXTRA_TASK_ID) ?: return
        val task = ScheduledTaskStore.get(taskId) ?: return
        if (!task.enabled) return

        val now = System.currentTimeMillis()
        ScheduledTaskStore.markTriggered(taskId, now)
        // 排下一次触发
        TaskScheduler.schedule(context, task)

        val channel = try {
            Channel.valueOf(task.channel)
        } catch (e: Exception) {
            XLog.e(TAG, "Unknown channel ${task.channel} for task $taskId", e)
            return
        }
        XLog.i(TAG, "Firing scheduled task $taskId via ${channel.displayName}")
        // automated=true：无人值守来源，C3 静默时段/冷却/熔断对此生效
        ClawApplication.appViewModelInstance.dispatchTask(
            channel, task.senderId, task.task, "sched-$taskId-$now", automated = true
        )
    }
}

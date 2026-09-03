package com.apk.claw.android.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.apk.claw.android.agent.store.ScheduledTaskStore
import com.apk.claw.android.agent.store.ScheduledTaskStore.ScheduledTask
import com.apk.claw.android.utils.XLog

/**
 * F7：定时任务调度。用 AlarmManager 精确闹钟（应用常驻前台服务，无需引入 WorkManager）。
 * 闹钟不跨重启存活：开机后由 BootReceiver 调 [rescheduleAll] 恢复全部计划。
 */
object TaskScheduler {

    private const val TAG = "TaskScheduler"
    const val EXTRA_TASK_ID = "scheduled_task_id"

    fun rescheduleAll(context: Context) {
        ScheduledTaskStore.list().filter { it.enabled }.forEach { schedule(context, it) }
    }

    /** 注册（或覆盖）一个任务的下次触发闹钟；任务禁用或无下次时刻时跳过 */
    fun schedule(context: Context, task: ScheduledTask) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        cancel(context, task.id)
        if (!task.enabled) return
        val triggerAt = ScheduledTaskStore.nextTriggerAt(task, System.currentTimeMillis()) ?: return

        val pi = pendingIntent(context, task.id)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // 未授予精确闹钟权限时退化为非精确：触发时刻可能偏移，但不丢任务
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                XLog.w(TAG, "Exact alarm not permitted, scheduled inexact for ${task.id} at $triggerAt")
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            XLog.i(TAG, "Scheduled task ${task.id} at $triggerAt")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to schedule task ${task.id}", e)
        }
    }

    fun cancel(context: Context, taskId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context, taskId))
    }

    private fun pendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            action = ScheduledTaskReceiver.ACTION_FIRE
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context, taskId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

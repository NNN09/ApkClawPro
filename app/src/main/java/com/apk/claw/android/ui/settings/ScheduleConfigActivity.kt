package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import com.apk.claw.android.R
import com.apk.claw.android.agent.store.ScheduledTaskStore
import com.apk.claw.android.agent.store.ScheduledTaskStore.ScheduledTask
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.compliance.ComplianceConfig
import com.apk.claw.android.service.TaskScheduler
import com.apk.claw.android.tool.impl.ScheduleTaskTool
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.ConfirmDialog
import com.apk.claw.android.widget.KButton
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

/**
 * F7 定时任务管理页，与 LAN /api/schedules 同源同语义：
 * 落盘走 ScheduledTaskStore，闹钟注册走 TaskScheduler，校验复用
 * ComplianceConfig.parseHm 与 ScheduleTaskTool.parseDaysOfWeek。
 */
class ScheduleConfigActivity : BaseActivity() {

    private lateinit var cardTasks: View
    private lateinit var tvEmpty: View
    private lateinit var llTasks: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.schedule_config_title))
            showBackButton(true) { finish() }
        }

        cardTasks = findViewById(R.id.cardTasks)
        tvEmpty = findViewById(R.id.tvEmpty)
        llTasks = findViewById(R.id.llTasks)

        findViewById<KButton>(R.id.btnAdd).setOnClickListener { showEditor(null) }
        refresh()
    }

    private fun refresh() {
        val tasks = ScheduledTaskStore.list()
        cardTasks.isVisible = tasks.isNotEmpty()
        tvEmpty.isVisible = tasks.isEmpty()
        llTasks.removeAllViews()
        tasks.forEachIndexed { index, task ->
            if (index > 0) llTasks.addView(layoutInflater.inflate(R.layout.item_divider, llTasks, false))
            llTasks.addView(buildRow(task))
        }
    }

    private fun buildRow(task: ScheduledTask): View {
        val row = layoutInflater.inflate(R.layout.item_schedule_row, llTasks, false)
        row.findViewById<TextView>(R.id.tvTitle).text = task.name
        row.findViewById<TextView>(R.id.tvSubtitle).text = getString(
            R.string.schedule_row_subtitle,
            "%02d:%02d".format(task.hour, task.minute),
            describeDays(task.daysOfWeek),
            channelLabel(task.channel)
        )
        row.findViewById<TextView>(R.id.tvTask).text = task.task
        val switch = row.findViewById<SwitchCompat>(R.id.switchEnabled)
        switch.isChecked = task.enabled
        switch.setOnCheckedChangeListener { _, checked ->
            ScheduledTaskStore.setEnabled(task.id, checked)?.let {
                TaskScheduler.schedule(this, it)
            }
            refresh()
        }
        row.setOnClickListener { showEditor(task) }
        return row
    }

    private fun showEditor(existing: ScheduledTask?) {
        showFormSheet(
            R.layout.dialog_schedule_editor,
            getString(if (existing == null) R.string.schedule_add else R.string.schedule_edit_title)
        ) { view, dialog ->
            val etName = view.findViewById<EditText>(R.id.etName)
            val etTask = view.findViewById<EditText>(R.id.etTask)
            val etTime = view.findViewById<EditText>(R.id.etTime)
            val etDays = view.findViewById<EditText>(R.id.etDays)
            val spinner = view.findViewById<Spinner>(R.id.spinnerChannel)
            spinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_item, Channel.entries.map { it.displayName }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            existing?.let {
                etName.setText(it.name)
                etTask.setText(it.task)
                etTime.setText("%02d:%02d".format(it.hour, it.minute))
                if (it.daysOfWeek.isNotEmpty()) etDays.setText(it.daysOfWeek.sorted().joinToString(","))
            }
            val channelIndex = Channel.entries.indexOfFirst { c ->
                c.name == (existing?.channel ?: Channel.IN_APP.name)
            }
            spinner.setSelection(if (channelIndex >= 0) channelIndex else Channel.entries.size - 1)

            view.findViewById<KButton>(R.id.btnSave).setOnClickListener {
                val channelName = Channel.entries.getOrNull(spinner.selectedItemPosition)?.name
                    ?: Channel.IN_APP.name
                val saved = saveTask(
                    existing,
                    etName.text.toString(),
                    etTask.text.toString().trim(),
                    etTime.text.toString().trim(),
                    etDays.text.toString().trim(),
                    channelName
                )
                if (saved) dialog.dismiss()
            }

            view.findViewById<View>(R.id.btnDelete).apply {
                if (existing == null) return@apply
                isVisible = true
                setOnClickListener {
                    dialog.dismiss()
                    confirmDelete(existing)
                }
            }
        }
    }

    /** 与 LAN /api/schedules POST 相同的校验与默认值；编辑 = 移除旧任务后按新配置重建 */
    private fun saveTask(
        existing: ScheduledTask?,
        nameRaw: String,
        taskText: String,
        timeText: String,
        daysRaw: String,
        channelName: String
    ): Boolean {
        if (taskText.isEmpty()) {
            toast(R.string.schedule_task_required)
            return false
        }
        val minutes = ComplianceConfig.parseHm(timeText)
        if (timeText.isEmpty() || minutes == null) {
            toast(R.string.compliance_invalid_time)
            return false
        }
        val days = ScheduleTaskTool.parseDaysOfWeek(daysRaw)
        if (days == null) {
            toast(R.string.schedule_invalid_days)
            return false
        }

        if (existing != null) {
            TaskScheduler.cancel(this, existing.id)
            ScheduledTaskStore.remove(existing.id)
        }
        val task = ScheduledTask(
            id = "st-${System.currentTimeMillis().toString(36)}-${(100..999).random()}",
            name = nameRaw.trim().ifEmpty { taskText.take(20) },
            task = taskText,
            channel = channelName,
            senderId = existing?.senderId ?: "local",
            hour = minutes / 60,
            minute = minutes % 60,
            daysOfWeek = days,
            enabled = existing?.enabled ?: true,
            lastTriggerAt = existing?.lastTriggerAt ?: 0,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        return try {
            val added = ScheduledTaskStore.add(task)
            TaskScheduler.schedule(this, added)
            toast(R.string.schedule_saved)
            refresh()
            true
        } catch (e: Exception) {
            toast(R.string.schedule_save_failed)
            refresh()
            false
        }
    }

    private fun confirmDelete(task: ScheduledTask) {
        ConfirmDialog.showWarm(
            context = this,
            title = getString(R.string.schedule_delete_title),
            message = getString(R.string.schedule_delete_message, task.name),
            actionTitle = getString(R.string.common_delete),
            onAction = {
                TaskScheduler.cancel(this, task.id)
                ScheduledTaskStore.remove(task.id)
                toast(R.string.schedule_deleted)
                refresh()
            }
        )
    }

    private fun channelLabel(channelName: String): String =
        Channel.entries.firstOrNull { it.name == channelName }?.displayName ?: channelName

    /** ISO 星期（1=周一…7=周日）→ 本地短星期名；空集 = 每天 */
    private fun describeDays(days: Set<Int>): String {
        if (days.isEmpty()) return getString(R.string.schedule_days_daily)
        val shortDays = DateFormatSymbols(Locale.getDefault()).shortWeekdays
        return days.sorted().joinToString(",") { iso ->
            shortDays[if (iso == 7) Calendar.SUNDAY else iso + 1]
        }
    }
}

package com.apk.claw.android.agent.store

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Calendar

/**
 * F7：定时/例行任务落盘。任务绑定"渠道 + 发送者"，到点把指令按该会话派发给 Agent，
 * 结果沿用渠道消息链路回传。重启后由 BootReceiver 重新注册全部计划。
 */
object ScheduledTaskStore {

    data class ScheduledTask(
        @SerializedName("id") val id: String,
        @SerializedName("name") val name: String,
        /** 到点派发给 Agent 的指令 */
        @SerializedName("task") val task: String,
        /** Channel.name，恢复时经 Channel.valueOf 还原 */
        @SerializedName("channel") val channel: String,
        @SerializedName("senderId") val senderId: String,
        @SerializedName("hour") val hour: Int,
        @SerializedName("minute") val minute: Int,
        /** ISO 星期（1=周一…7=周日）；空集表示每天 */
        @SerializedName("daysOfWeek") val daysOfWeek: Set<Int> = emptySet(),
        @SerializedName("enabled") val enabled: Boolean = true,
        @SerializedName("lastTriggerAt") val lastTriggerAt: Long = 0,
        @SerializedName("createdAt") val createdAt: Long = 0
    )

    const val MAX_TASKS = 20

    private const val FILE_NAME = "scheduled_tasks.json"

    private val gson = Gson()
    private val lock = Any()

    // 以下字段由 init 初始化；未 init 前访问属于编程错误，与 SessionStore 同约定
    private lateinit var file: File
    private val tasks = mutableListOf<ScheduledTask>()

    fun init(rootDir: File) {
        synchronized(lock) {
            rootDir.mkdirs()
            file = File(rootDir, FILE_NAME)
            tasks.clear()
            if (file.exists()) {
                try {
                    val type = object : TypeToken<MutableList<ScheduledTask>>() {}.type
                    val text = file.readText()
                    if (text.isNotBlank()) tasks.addAll(gson.fromJson(text, type))
                } catch (_: Exception) {
                }
            }
            tasks.removeAll { it == null || it.id == null || it.task == null }
        }
    }

    fun list(): List<ScheduledTask> = synchronized(lock) { tasks.toList() }

    fun get(id: String): ScheduledTask? = synchronized(lock) { tasks.firstOrNull { it.id == id } }

    /** 新增并返回；超过上限时抛 IllegalStateException（工具/API 层转为用户可读错误） */
    fun add(task: ScheduledTask): ScheduledTask = synchronized(lock) {
        require(task.hour in 0..23 && task.minute in 0..59) { "invalid time ${task.hour}:${task.minute}" }
        require(task.task.isNotBlank()) { "task text is blank" }
        if (tasks.size >= MAX_TASKS) throw IllegalStateException("scheduled task limit ($MAX_TASKS) reached")
        tasks.add(task)
        persist()
        return task
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val removed = tasks.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    fun setEnabled(id: String, enabled: Boolean): ScheduledTask? = synchronized(lock) {
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return null
        tasks[idx] = tasks[idx].copy(enabled = enabled)
        persist()
        return tasks[idx]
    }

    fun markTriggered(id: String, at: Long) = synchronized(lock) {
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx >= 0) {
            tasks[idx] = tasks[idx].copy(lastTriggerAt = at)
            persist()
        }
    }

    /**
     * 计算自 [fromMillis] 起的下次触发时刻（纯函数，可测）。
     * 天集合为空表示每天；找不到（理论不发生）返回 null。
     */
    fun nextTriggerAt(task: ScheduledTask, fromMillis: Long): Long? {
        val cal = Calendar.getInstance().apply {
            timeInMillis = fromMillis
            set(Calendar.HOUR_OF_DAY, task.hour)
            set(Calendar.MINUTE, task.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= fromMillis) cal.add(Calendar.DAY_OF_MONTH, 1)
        if (task.daysOfWeek.isEmpty()) return cal.timeInMillis
        repeat(7) {
            if (isoDayOfWeek(cal) in task.daysOfWeek) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }

    /** Calendar 星期日=1 → ISO 周一=1 */
    private fun isoDayOfWeek(cal: Calendar): Int {
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    private fun persist() {
        try {
            file.writeText(gson.toJson(tasks))
        } catch (_: Exception) {
        }
    }
}

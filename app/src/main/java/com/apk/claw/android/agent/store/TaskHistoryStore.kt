package com.apk.claw.android.agent.store

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File

/**
 * 任务历史落盘（F3）：每次任务结束追加一条 JSONL 记录，供 LAN 页诊断与后续用量统计（F13）。
 * 超过 [MAX_RECORDS] 条时重写文件裁剪到 [TRIM_TO] 条，避免无限增长。
 */
object TaskHistoryStore {

    enum class Status { COMPLETED, FAILED, CANCELLED, WAIT_TIMEOUT }

    data class TaskRecord(
        @SerializedName("id") val id: String,
        @SerializedName("startTime") val startTime: Long,
        @SerializedName("endTime") val endTime: Long,
        @SerializedName("channel") val channel: String,
        @SerializedName("sender") val sender: String,
        @SerializedName("task") val task: String,
        @SerializedName("status") val status: String,
        @SerializedName("rounds") val rounds: Int,
        @SerializedName("toolCalls") val toolCalls: Int,
        @SerializedName("tokens") val tokens: Int,
        @SerializedName("error") val error: String = "",
        /** 工具调用序列（toolName(args)，每条截断） */
        @SerializedName("toolTrace") val toolTrace: List<String> = emptyList()
    )

    const val MAX_RECORDS = 200
    const val TRIM_TO = 150
    private const val MAX_TRACE_ITEMS = 80
    private const val MAX_TRACE_ITEM_CHARS = 120
    private const val MAX_ERROR_CHARS = 300

    private const val FILE_NAME = "task_history.jsonl"
    private const val TAG = "TaskHistoryStore"

    private val gson = Gson()
    private val lock = Any()

    fun append(record: TaskRecord) {
        append(defaultDir(), record)
    }

    fun list(limit: Int = 100): List<TaskRecord> = list(defaultDir(), limit)

    // ==================== 纯文件实现（可 JVM 测试） ====================

    fun append(dir: File, record: TaskRecord) {
        synchronized(lock) {
            val file = File(dir, FILE_NAME)
            val trimmed = sanitize(record)
            try {
                dir.mkdirs()
                val existing = if (file.exists()) {
                    file.readLines().mapNotNull { line ->
                        try {
                            gson.fromJson(line, TaskRecord::class.java)
                        } catch (_: Exception) { null }
                    }
                } else emptyList()
                val all = existing + trimmed
                val kept = if (all.size > MAX_RECORDS) all.takeLast(TRIM_TO) else all
                val tmp = File(dir, "$FILE_NAME.tmp")
                tmp.bufferedWriter().use { w ->
                    kept.forEach { r -> w.write(gson.toJson(r)); w.newLine() }
                }
                if (!tmp.renameTo(file)) {
                    file.writeText("")
                    file.appendText(kept.joinToString("\n") { gson.toJson(it) })
                    tmp.delete()
                }
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to append task history", e)
            }
        }
    }

    fun list(dir: File, limit: Int = 100): List<TaskRecord> {
        synchronized(lock) {
            val file = File(dir, FILE_NAME)
            if (!file.exists()) return emptyList()
            return try {
                file.readLines().mapNotNull { line ->
                    try {
                        gson.fromJson(line, TaskRecord::class.java)
                    } catch (_: Exception) { null }
                }.takeLast(limit.coerceAtLeast(1)).reversed()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun sanitize(r: TaskRecord): TaskRecord = r.copy(
        error = r.error.take(MAX_ERROR_CHARS),
        toolTrace = r.toolTrace.take(MAX_TRACE_ITEMS).map { it.take(MAX_TRACE_ITEM_CHARS) }
    )

    private fun defaultDir(): File =
        File(ClawApplication.instance.filesDir, "agent")
}

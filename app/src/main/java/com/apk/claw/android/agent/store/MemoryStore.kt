package com.apk.claw.android.agent.store

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 长期记忆存储：filesDir/agent/memory.md，行式条目 "- [yyyy-MM-dd HH:mm] 文本"。
 * 人类可直接编辑；解析时对没有前缀的行按原文处理。
 */
object MemoryStore {

    const val MAX_ENTRIES = 50

    private val ENTRY_PREFIX = Regex("^- \\[[^]]*] ?")
    private lateinit var file: File

    fun init(rootDir: File) {
        rootDir.mkdirs()
        file = File(rootDir, "memory.md")
        if (!file.exists()) file.writeText("")
    }

    fun save(text: String, timestamp: String = nowStamp()): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val lines = readLines()
        if (lines.any { extractText(it) == t }) return true
        lines.add("- [$timestamp] $t")
        while (lines.size > MAX_ENTRIES) lines.removeAt(0)
        writeLines(lines)
        return true
    }

    fun all(): List<String> = readLines().map { extractText(it) }

    fun delete(text: String): Boolean {
        val t = text.trim()
        val lines = readLines()
        val removed = lines.removeAll { extractText(it) == t }
        if (removed) writeLines(lines)
        return removed
    }

    fun promptSection(): String {
        val entries = all()
        if (entries.isEmpty()) return ""
        return "## 长期记忆\n" + entries.joinToString("\n") { "- $it" } +
            "\n（以上是历史累积的记忆，与当前任务相关时可参考；需要更新时使用 memory_save / memory_delete）"
    }

    private fun readLines(): MutableList<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.toMutableList()
        else mutableListOf()

    private fun writeLines(lines: List<String>) {
        file.writeText(lines.joinToString("\n") + "\n")
    }

    private fun extractText(line: String): String = ENTRY_PREFIX.replace(line.trim(), "")

    private fun nowStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
}

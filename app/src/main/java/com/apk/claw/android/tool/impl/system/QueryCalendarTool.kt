package com.apk.claw.android.tool.impl.system

import android.Manifest
import android.content.Context
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * F9：查询某天起的日历事件（Instances 表），需 READ_CALENDAR 运行时权限。
 * 默认查今天起 7 天，最多返回 20 条。
 */
class QueryCalendarTool : BaseTool() {

    companion object {
        private const val DEFAULT_DAYS = 7
        private const val MAX_RESULTS = 20
    }

    override fun getName() = "query_calendar"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_query_calendar)

    override fun getParameters() = listOf(
        ToolParameter("date", "string", "Start date 'yyyy-MM-dd' local, default today", false),
        ToolParameter("days", "integer", "How many days from the start date (default 7, max 31)", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val context = ClawApplication.instance
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.error(
                "READ_CALENDAR permission not granted. Ask the user to grant it on the home screen card, then retry"
            )
        }
        val dateRaw = optionalString(params, "date", "").trim()
        val begin = if (dateRaw.isEmpty()) {
            SystemToolSupport.parseDateStartOfDay(
                java.time.LocalDate.now().toString()
            )
        } else {
            SystemToolSupport.parseDateStartOfDay(dateRaw)
        } ?: return ToolResult.error("Invalid date, expected 'yyyy-MM-dd'")
        val days = optionalInt(params, "days", DEFAULT_DAYS).coerceIn(1, 31)
        val end = begin + days * 86_400_000L

        return try {
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION
            )
            val cursor = CalendarContract.Instances.query(context.contentResolver, projection, begin, end)
            cursor.use {
                val lines = mutableListOf<String>()
                while (it.moveToNext() && lines.size < MAX_RESULTS) {
                    val title = it.getString(0) ?: ""
                    val beginMs = it.getLong(1)
                    val endMs = it.getLong(2)
                    val location = it.getString(3) ?: ""
                    if (title.isEmpty()) continue
                    val loc = if (location.isEmpty()) "" else " @$location"
                    lines.add(
                        "- ${SystemToolSupport.formatEventTime(beginMs)}" +
                            "-${SystemToolSupport.formatEventTime(endMs).takeLast(5)} $title$loc"
                    )
                }
                if (lines.isEmpty()) {
                    ToolResult.success("No events in the next $days day(s)")
                } else {
                    ToolResult.success("Events (${lines.size}):\n${lines.joinToString("\n")}")
                }
            }
        } catch (e: Exception) {
            ToolResult.error("Failed to query calendar: ${e.message}")
        }
    }

    override fun getDescriptionEN() =
        "Query calendar events for a date range (requires READ_CALENDAR). Use for '明天有什么安排'."

    override fun getDescriptionCN() =
        "查询日期范围内的日历事件（需要 READ_CALENDAR 权限）。适用于“明天有什么安排”。"
}

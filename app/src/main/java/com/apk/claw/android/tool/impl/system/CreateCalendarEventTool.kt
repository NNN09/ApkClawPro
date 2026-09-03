package com.apk.claw.android.tool.impl.system

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * F9：通过 ContentResolver 直接写入日历事件（无 UI、免确认界面）。
 * 需要 READ_CALENDAR（选主日历）+ WRITE_CALENDAR（写入）运行时权限。
 */
class CreateCalendarEventTool : BaseTool() {

    override fun getName() = "create_calendar_event"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_create_calendar_event)

    override fun getParameters() = listOf(
        ToolParameter("title", "string", "Event title", true),
        ToolParameter("begin_time", "string", "Start time: epoch millis or 'yyyy-MM-dd HH:mm' local", true),
        ToolParameter("end_time", "string", "End time: epoch millis or 'yyyy-MM-dd HH:mm' local", false),
        ToolParameter("duration_minutes", "integer", "Duration in minutes when end_time not given (default 60)", false),
        ToolParameter("description", "string", "Optional event description", false),
        ToolParameter("location", "string", "Optional event location", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val context = ClawApplication.instance
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.error(
                "Calendar permissions not granted. Ask the user to grant them on the home screen card, then retry"
            )
        }
        val title = requireString(params, "title").trim()
        if (title.isEmpty()) return ToolResult.error("Event title is required")

        val begin = SystemToolSupport.parseEventTime(params["begin_time"])
            ?: return ToolResult.error("Invalid begin_time, expected epoch millis or 'yyyy-MM-dd HH:mm'")
        val end = params["end_time"]?.let {
            SystemToolSupport.parseEventTime(it) ?: return ToolResult.error(
                "Invalid end_time, expected epoch millis or 'yyyy-MM-dd HH:mm'"
            )
        } ?: begin + optionalInt(params, "duration_minutes", 60) * 60_000L
        if (end <= begin) return ToolResult.error("end_time must be after begin_time")

        val calendarId = primaryCalendarId(context)
            ?: return ToolResult.error("No visible calendar account found on the device")

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            optionalString(params, "description", "").trim().takeIf { it.isNotEmpty() }?.let {
                put(CalendarContract.Events.DESCRIPTION, it)
            }
            optionalString(params, "location", "").trim().takeIf { it.isNotEmpty() }?.let {
                put(CalendarContract.Events.EVENT_LOCATION, it)
            }
        }
        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri == null) {
                ToolResult.error("Failed to create calendar event")
            } else {
                ToolResult.success(
                    "Calendar event created: \"$title\" ${SystemToolSupport.formatEventTime(begin)}" +
                        " - ${SystemToolSupport.formatEventTime(end)}"
                )
            }
        } catch (e: Exception) {
            ToolResult.error("Failed to create calendar event: ${e.message}")
        }
    }

    /** 取第一个可见日历账号（优先主日历）；需要 READ_CALENDAR */
    private fun primaryCalendarId(context: Context): Long? {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    override fun getDescriptionEN() =
        "Create a calendar event directly via the calendar provider (no UI). Use for '帮我加个明天下午3点的会议'."

    override fun getDescriptionCN() =
        "通过日历内容提供器直接创建日历事件（无 UI）。适用于“帮我加个明天下午3点的会议”。"
}

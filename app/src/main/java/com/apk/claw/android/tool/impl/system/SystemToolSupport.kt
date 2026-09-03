package com.apk.claw.android.tool.impl.system

import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * F9 系统工具包的纯映射/解析函数集合。
 * 只依赖 Android 常量（编译期内联）与 java.time，可在 JVM 单测中直接验证。
 */
object SystemToolSupport {

    // ==================== 日历事件时间解析 ====================

    /** 本地时间格式：'yyyy-MM-dd HH:mm' 或 ISO 'yyyy-MM-ddTHH:mm'（T 与空格均可省略式匹配） */
    private val LOCAL_DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd['T'][' ']HH:mm")

    /**
     * 解析事件时间参数：支持 epoch 毫秒（Number）或本地时间字符串。
     * 非法返回 null。
     */
    fun parseEventTime(raw: Any?): Long? {
        return when (raw) {
            is Number -> raw.toLong().takeIf { it > 0 }
            is String -> parseDateTimeString(raw.trim())
            else -> null
        }
    }

    private fun parseDateTimeString(text: String): Long? {
        if (text.isEmpty()) return null
        return try {
            LocalDateTime.parse(text, LOCAL_DATETIME)
                .atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /** 将 epoch 毫秒格式化为可读本地时间（用于工具结果回显） */
    fun formatEventTime(epochMillis: Long): String {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }

    /** 解析 "yyyy-MM-dd" 本地日期为当天 0 点 epoch 毫秒；非法返回 null */
    fun parseDateStartOfDay(raw: String): Long? {
        return try {
            LocalDateTime.parse("$raw 00:00", LOCAL_DATETIME)
                .atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    // ==================== 媒体控制 ====================

    /** 媒体动作 → KeyEvent keycode；未知动作返回 null */
    fun mediaKeyCode(action: String): Int? = when (action.lowercase().trim()) {
        "play_pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
        "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
        "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
        "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
        "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
        else -> null
    }

    // ==================== 音量 ====================

    /** 音量流名称 → AudioManager stream id；未知返回 null */
    fun volumeStream(name: String): Int? = when (name.lowercase().trim()) {
        "music", "media" -> AudioManager.STREAM_MUSIC
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        else -> null
    }

    // ==================== 勿扰 ====================

    /**
     * 勿扰模式名称 → NotificationManager filter id。
     * off=关闭勿扰, priority=仅优先, alarms=仅闹钟, none=完全静音
     */
    fun dndFilter(name: String): Int? = when (name.lowercase().trim()) {
        "off", "all" -> NotificationManager.INTERRUPTION_FILTER_ALL
        "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
        "alarms", "alarms_only" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
        "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
        else -> null
    }

    // ==================== 设置页 ====================

    /**
     * 设置页名称 → Settings 的 ACTION 常量。
     * dnd_access / write_settings 用于引导用户授予勿扰与系统写入这两项特殊权限。
     */
    fun settingsPage(name: String): String? = when (name.lowercase().trim()) {
        "main" -> Settings.ACTION_SETTINGS
        "wifi" -> Settings.ACTION_WIFI_SETTINGS
        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
        "display" -> Settings.ACTION_DISPLAY_SETTINGS
        "sound" -> Settings.ACTION_SOUND_SETTINGS
        "battery" -> Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        "apps" -> Settings.ACTION_APPLICATION_SETTINGS
        "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        "date" -> Settings.ACTION_DATE_SETTINGS
        "data_usage" -> Settings.ACTION_DATA_USAGE_SETTINGS
        "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
        "dnd_access" -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
        "write_settings" -> Settings.ACTION_MANAGE_WRITE_SETTINGS
        else -> null
    }

    // ==================== 电话号码 ====================

    /**
     * 校验并清洗电话号码：仅保留 0-9 与 + - * # ( ) 空格；
     * 清洗后为空返回 null（防止拨号注入任意 scheme 内容）。
     */
    fun sanitizePhoneNumber(raw: String): String? {
        val cleaned = raw.filter { it.isDigit() || it in "+-*#() " }.trim()
        return cleaned.ifEmpty { null }
    }

    // ==================== 闹钟参数 ====================

    fun isValidAlarmTime(hour: Int, minute: Int): Boolean = hour in 0..23 && minute in 0..59

    // ==================== 亮度 ====================

    /** 百分比 (0-100) → 系统 BRIGHTNESS 值 (0-255) */
    fun percentToBrightness(percent: Int): Int = (percent.coerceIn(0, 100) * 255) / 100
}

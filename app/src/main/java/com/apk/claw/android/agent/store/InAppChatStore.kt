package com.apk.claw.android.agent.store

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * F5：App 内对话消息落盘。收发的每条消息（用户/助手）追加保存，
 * 聊天页重建时恢复历史；同时以监听器通知在屏的聊天页实时刷新。
 */
object InAppChatStore {

    enum class Role { USER, AGENT }

    data class Message(
        @SerializedName("id") val id: String,
        @SerializedName("time") val time: Long,
        @SerializedName("role") val role: String,
        @SerializedName("text") val text: String
    )

    const val MAX_MESSAGES = 200
    const val TRIM_TO = 150

    private const val FILE_NAME = "in_app_chat.json"
    private const val TAG = "InAppChatStore"

    private val gson = Gson()
    private val lock = Any()

    /** 聊天页在屏时接收新消息（UI 线程回调由页面自行保证） */
    @Volatile
    var listener: ((Message) -> Unit)? = null

    fun init(rootDir: File) {
        synchronized(lock) {
            file = File(rootDir, FILE_NAME)
            cache.clear()
            if (file.exists()) {
                try {
                    val type = object : TypeToken<MutableList<Message>>() {}.type
                    val text = file.readText()
                    if (text.isNotBlank()) cache.addAll(gson.fromJson(text, type))
                } catch (_: Exception) {
                }
            }
            cache.removeAll { it == null || it.role.isNullOrBlank() }
        }
    }

    fun append(role: Role, text: String): Message = synchronized(lock) {
        val msg = Message(
            id = "inapp-${System.currentTimeMillis()}-${(100..999).random()}",
            time = System.currentTimeMillis(),
            role = role.name,
            text = text
        )
        cache.add(msg)
        while (cache.size > MAX_MESSAGES) cache.removeAt(0)
        persist()
        msg
    }.also { listener?.invoke(it) }

    fun list(limit: Int = MAX_MESSAGES): List<Message> = synchronized(lock) {
        cache.takeLast(limit.coerceAtLeast(1))
    }

    fun clear() = synchronized(lock) {
        cache.clear()
        persist()
    }

    // 以下字段由 init 初始化；未 init 前访问属于编程错误，与 SessionStore 同约定
    private lateinit var file: File
    private val cache = mutableListOf<Message>()

    private fun persist() {
        try {
            file.writeText(gson.toJson(cache))
        } catch (_: Exception) {
        }
    }
}

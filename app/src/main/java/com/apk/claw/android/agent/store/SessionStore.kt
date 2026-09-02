package com.apk.claw.android.agent.store

import com.apk.claw.android.channel.Channel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 会话回合存储：filesDir/agent/sessions.json
 * 键 = "<渠道名>:<发送者ID>"，只存（用户消息, 最终回复）对，不存工具调用轮次。
 */
object SessionStore {

    data class Turn(val user: String, val assistant: String)

    private data class SessionData(val turns: MutableList<Turn>, var lastActive: Long)

    const val SESSION_TIMEOUT_MS: Long = 30 * 60 * 1000L
    const val MAX_TURNS = 10

    private lateinit var file: File
    private val gson = Gson()
    private val sessions = mutableMapOf<String, SessionData>()

    /** 测试注入用；null 表示使用系统时钟 */
    @JvmStatic
    var nowProviderForTest: (() -> Long)? = null

    private fun now(): Long = nowProviderForTest?.invoke() ?: System.currentTimeMillis()

    fun init(rootDir: File) {
        nowProviderForTest = null
        rootDir.mkdirs()
        file = File(rootDir, "sessions.json")
        sessions.clear()
        if (file.exists()) {
            try {
                val type = object : TypeToken<MutableMap<String, SessionData>>() {}.type
                val text = file.readText()
                if (text.isNotBlank()) sessions.putAll(gson.fromJson(text, type))
            } catch (_: Exception) {
            }
        }
    }

    fun history(channel: Channel, senderId: String): List<Turn> {
        val key = key(channel, senderId)
        val s = sessions[key] ?: return emptyList()
        if (now() - s.lastActive > SESSION_TIMEOUT_MS) {
            sessions.remove(key)
            persist()
            return emptyList()
        }
        return s.turns.toList()
    }

    fun appendTurn(channel: Channel, senderId: String, user: String, assistant: String) {
        if (senderId.isEmpty()) return
        val key = key(channel, senderId)
        val s = sessions.getOrPut(key) { SessionData(mutableListOf(), 0L) }
        s.turns.add(Turn(user, assistant))
        while (s.turns.size > MAX_TURNS) s.turns.removeAt(0)
        s.lastActive = now()
        persist()
    }

    fun reset(channel: Channel, senderId: String) {
        sessions.remove(key(channel, senderId))
        persist()
    }

    private fun key(channel: Channel, senderId: String) = "${channel.name}:$senderId"

    private fun persist() {
        try {
            file.writeText(gson.toJson(sessions))
        } catch (_: Exception) {
        }
    }
}

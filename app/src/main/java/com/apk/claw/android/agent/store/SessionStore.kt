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

    private data class SessionData(
        val turns: MutableList<Turn>,
        var lastActive: Long,
        var digest: String = "",
        var pendingDigest: MutableList<Turn> = mutableListOf()
    )

    const val SESSION_TIMEOUT_MS: Long = 30 * 60 * 1000L
    const val MAX_TURNS = 10
    const val MAX_PENDING_DIGEST = 20
    const val MAX_DIGEST_CHARS = 600

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
        // Gson 绕过构造器反序列化，旧格式文件缺失字段时为 null，需清洗
        sessions.entries.removeAll { it.value?.turns == null }
        sessions.values.forEach {
            if (it.digest == null) it.digest = ""
            if (it.pendingDigest == null) it.pendingDigest = mutableListOf()
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
        val s = sessions.getOrPut(key) { SessionData(mutableListOf(), 0L, "", mutableListOf()) }
        s.turns.add(Turn(user, assistant))
        // 超上限的轮次沉淀到待摘要队列而非直接丢弃（下次任务开始时合并进 digest）
        while (s.turns.size > MAX_TURNS) {
            s.pendingDigest.add(s.turns.removeAt(0))
        }
        while (s.pendingDigest.size > MAX_PENDING_DIGEST) s.pendingDigest.removeAt(0)
        s.lastActive = now()
        persist()
    }

    fun digest(channel: Channel, senderId: String): String =
        sessions[key(channel, senderId)]?.digest ?: ""

    fun pendingDigest(channel: Channel, senderId: String): List<Turn> =
        sessions[key(channel, senderId)]?.pendingDigest?.toList() ?: emptyList()

    fun updateDigest(channel: Channel, senderId: String, digest: String) {
        val s = sessions[key(channel, senderId)] ?: return
        s.digest = digest.take(MAX_DIGEST_CHARS)
        persist()
    }

    fun clearPendingDigest(channel: Channel, senderId: String) {
        val s = sessions[key(channel, senderId)] ?: return
        if (s.pendingDigest.isNotEmpty()) {
            s.pendingDigest.clear()
            persist()
        }
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

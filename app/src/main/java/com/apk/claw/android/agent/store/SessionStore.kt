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
        var pendingDigest: MutableList<Turn> = mutableListOf(),
        /** 上次任务的工具执行轨迹原文（已截尾），下次任务开始时蒸馏进 digest */
        var transcript: String = ""
    )

    const val SESSION_TIMEOUT_MS: Long = 30 * 60 * 1000L
    const val MAX_TURNS = 10
    const val MAX_PENDING_DIGEST = 20
    const val MAX_DIGEST_CHARS = 1000
    const val MAX_TRANSCRIPT_CHARS = 4000

    private lateinit var file: File
    private val gson = Gson()
    private val lock = Any()
    private val sessions = mutableMapOf<String, SessionData>()

    /** 测试注入用；null 表示使用系统时钟 */
    @JvmStatic
    var nowProviderForTest: (() -> Long)? = null

    private fun now(): Long = nowProviderForTest?.invoke() ?: System.currentTimeMillis()

    fun init(rootDir: File) {
        synchronized(lock) {
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
                if (it.transcript == null) it.transcript = ""
            }
        }
    }

    /** Agent 执行线程与渠道消息线程（"新对话"重置）并发访问，所有读写都在锁内 */
    fun history(channel: Channel, senderId: String): List<Turn> = synchronized(lock) {
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
        synchronized(lock) {
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
    }

    fun digest(channel: Channel, senderId: String): String = synchronized(lock) {
        sessions[key(channel, senderId)]?.digest ?: ""
    }

    fun pendingDigest(channel: Channel, senderId: String): List<Turn> = synchronized(lock) {
        sessions[key(channel, senderId)]?.pendingDigest?.toList() ?: emptyList()
    }

    fun updateDigest(channel: Channel, senderId: String, digest: String) {
        synchronized(lock) {
            val s = sessions[key(channel, senderId)] ?: return
            s.digest = digest.take(MAX_DIGEST_CHARS)
            persist()
        }
    }

    fun clearPendingDigest(channel: Channel, senderId: String) {
        synchronized(lock) {
            val s = sessions[key(channel, senderId)] ?: return
            if (s.pendingDigest.isNotEmpty()) {
                s.pendingDigest.clear()
                persist()
            }
        }
    }

    /**
     * 追加任务执行轨迹（任务收尾时一次性写入）。截尾保留最近的 [MAX_TRANSCRIPT_CHARS] 字符，
     * 同时刷新 lastActive——失败任务不产生 turn，这里是它们维持会话"活跃"的关键路径之一。
     */
    fun appendTranscript(channel: Channel, senderId: String, text: String) {
        synchronized(lock) {
            if (senderId.isEmpty() || text.isBlank()) return
            val s = sessions.getOrPut(key(channel, senderId)) {
                SessionData(mutableListOf(), now(), "", mutableListOf(), "")
            }
            s.transcript = (if (s.transcript.isEmpty()) text else s.transcript + "\n" + text).takeLast(MAX_TRANSCRIPT_CHARS)
            s.lastActive = now()
            persist()
        }
    }

    fun pendingTranscript(channel: Channel, senderId: String): String = synchronized(lock) {
        sessions[key(channel, senderId)]?.transcript ?: ""
    }

    fun clearPendingTranscript(channel: Channel, senderId: String) {
        synchronized(lock) {
            val s = sessions[key(channel, senderId)] ?: return
            if (s.transcript.isNotEmpty()) {
                s.transcript = ""
                persist()
            }
        }
    }

    /**
     * 仅刷新会话活跃度，不产生 turn。用于任务未进入执行链就被拒的场景
     * （如无障碍未开启在 dispatch 层拦截）——用户在真实互动，若不刷新 lastActive，
     * 30 分钟超时会在交互中途把还"热着"的会话整条清掉（2026-09-10 事故的同族问题）。
     */
    fun touch(channel: Channel, senderId: String) {
        synchronized(lock) {
            if (senderId.isEmpty()) return
            sessions.getOrPut(key(channel, senderId)) {
                SessionData(mutableListOf(), now(), "", mutableListOf(), "")
            }.lastActive = now()
            persist()
        }
    }

    fun reset(channel: Channel, senderId: String) {
        synchronized(lock) {
            sessions.remove(key(channel, senderId))
            persist()
        }
    }

    private fun key(channel: Channel, senderId: String) = "${channel.name}:$senderId"

    private fun persist() {
        try {
            file.writeText(gson.toJson(sessions))
        } catch (_: Exception) {
        }
    }
}

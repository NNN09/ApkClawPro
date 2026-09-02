package com.apk.claw.android.agent.store

import com.apk.claw.android.channel.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun tearDown() {
        SessionStore.nowProviderForTest = null
    }

    @Test fun history_emptyInitially() {
        SessionStore.init(tmp.root)
        assertTrue(SessionStore.history(Channel.TELEGRAM, "42").isEmpty())
    }

    @Test fun appendTurn_thenHistoryReturnsIt() {
        SessionStore.init(tmp.root)
        SessionStore.appendTurn(Channel.TELEGRAM, "42", "打开设置", "已打开")
        val h = SessionStore.history(Channel.TELEGRAM, "42")
        assertEquals(1, h.size)
        assertEquals("打开设置", h[0].user)
        assertEquals("已打开", h[0].assistant)
    }

    @Test fun appendTurn_emptySenderIdIgnored() {
        SessionStore.init(tmp.root)
        SessionStore.appendTurn(Channel.TELEGRAM, "", "a", "b")
        assertTrue(SessionStore.history(Channel.TELEGRAM, "").isEmpty())
    }

    @Test fun appendTurn_trimsToMaxTurns() {
        SessionStore.init(tmp.root)
        repeat(12) { i -> SessionStore.appendTurn(Channel.TELEGRAM, "u", "q$i", "a$i") }
        val h = SessionStore.history(Channel.TELEGRAM, "u")
        assertEquals(10, h.size)
        assertEquals("q2", h.first().user)
    }

    @Test fun history_expiresAfterTimeout() {
        SessionStore.init(tmp.root)
        SessionStore.appendTurn(Channel.TELEGRAM, "u", "q", "a")
        SessionStore.nowProviderForTest = { System.currentTimeMillis() + 31 * 60 * 1000L }
        assertTrue(SessionStore.history(Channel.TELEGRAM, "u").isEmpty())
    }

    @Test fun reset_clearsSession() {
        SessionStore.init(tmp.root)
        SessionStore.appendTurn(Channel.TELEGRAM, "u", "q", "a")
        SessionStore.reset(Channel.TELEGRAM, "u")
        assertTrue(SessionStore.history(Channel.TELEGRAM, "u").isEmpty())
    }

    @Test fun sessions_persistAcrossReinit() {
        SessionStore.init(tmp.root)
        SessionStore.appendTurn(Channel.TELEGRAM, "u", "q", "a")
        SessionStore.init(tmp.root)  // 模拟进程重启
        assertEquals(1, SessionStore.history(Channel.TELEGRAM, "u").size)
    }

    @Test fun differentSenders_areIsolated() {
        SessionStore.init(tmp.root)
        SessionStore.appendTurn(Channel.TELEGRAM, "a", "q", "a1")
        SessionStore.appendTurn(Channel.TELEGRAM, "b", "q2", "b1")
        assertEquals(1, SessionStore.history(Channel.TELEGRAM, "a").size)
        assertEquals(1, SessionStore.history(Channel.TELEGRAM, "b").size)
    }

    @Test fun evictedTurns_goToPendingDigest() {
        SessionStore.init(tmp.root)
        repeat(12) { i -> SessionStore.appendTurn(Channel.TELEGRAM, "u", "q$i", "a$i") }
        val pending = SessionStore.pendingDigest(Channel.TELEGRAM, "u")
        assertEquals(2, pending.size)
        assertEquals("q0", pending[0].user)
    }

    @Test fun digest_roundtrip() {
        SessionStore.init(tmp.root)
        SessionStore.appendTurn(Channel.TELEGRAM, "u", "q", "a")   // 确保会话存在
        SessionStore.updateDigest(Channel.TELEGRAM, "u", "用户偏好摘要")
        assertEquals("用户偏好摘要", SessionStore.digest(Channel.TELEGRAM, "u"))
    }

    @Test fun updateDigest_capsLength() {
        SessionStore.init(tmp.root)
        SessionStore.appendTurn(Channel.TELEGRAM, "u", "q", "a")
        SessionStore.updateDigest(Channel.TELEGRAM, "u", "x".repeat(1000))
        assertEquals(600, SessionStore.digest(Channel.TELEGRAM, "u").length)
    }

    @Test fun clearPendingDigest_emptiesQueue() {
        SessionStore.init(tmp.root)
        repeat(12) { i -> SessionStore.appendTurn(Channel.TELEGRAM, "u", "q$i", "a$i") }
        SessionStore.clearPendingDigest(Channel.TELEGRAM, "u")
        assertTrue(SessionStore.pendingDigest(Channel.TELEGRAM, "u").isEmpty())
    }

    @Test fun sessionsjson_oldFormatStillLoads() {
        // 旧版本 sessions.json（无 digest 字段）加载后不崩
        tmp.root.resolve("sessions.json").writeText(
            """{"TELEGRAM:u":{"turns":[{"user":"q","assistant":"a"}],"lastActive":9999999999999}}""")
        SessionStore.init(tmp.root)
        assertEquals(1, SessionStore.history(Channel.TELEGRAM, "u").size)
        assertEquals("", SessionStore.digest(Channel.TELEGRAM, "u"))
    }

    @Test fun concurrentAppendTurns_noLostSessions() {
        // Agent 线程与渠道线程并发访问；无锁时 HashMap 并发扩容会丢会话
        SessionStore.init(tmp.root)
        val threads = 8
        val keysPerThread = 25
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val futures = (0 until threads).map { t ->
                pool.submit {
                    repeat(keysPerThread) { k ->
                        val sender = "s-$t-$k"
                        SessionStore.appendTurn(Channel.TELEGRAM, sender, "q1", "a1")
                        SessionStore.appendTurn(Channel.TELEGRAM, sender, "q2", "a2")
                    }
                }
            }
            futures.forEach { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
            repeat(threads) { t ->
                repeat(keysPerThread) { k ->
                    assertEquals(2, SessionStore.history(Channel.TELEGRAM, "s-$t-$k").size)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}

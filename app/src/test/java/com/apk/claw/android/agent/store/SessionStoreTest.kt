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
}

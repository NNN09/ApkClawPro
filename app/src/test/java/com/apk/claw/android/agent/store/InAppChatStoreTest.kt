package com.apk.claw.android.agent.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InAppChatStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        InAppChatStore.init(tmp.root)
        InAppChatStore.listener = null
    }

    @Test
    fun append_roundTripsAcrossReinit() {
        InAppChatStore.append(InAppChatStore.Role.USER, "打开设置")
        InAppChatStore.append(InAppChatStore.Role.AGENT, "好的，已打开")
        InAppChatStore.init(tmp.root)
        val all = InAppChatStore.list()
        assertEquals(2, all.size)
        assertEquals(InAppChatStore.Role.USER.name, all[0].role)
        assertEquals("打开设置", all[0].text)
        assertEquals(InAppChatStore.Role.AGENT.name, all[1].role)
    }

    @Test
    fun append_trimsToCap() {
        repeat(InAppChatStore.MAX_MESSAGES + 20) {
            InAppChatStore.append(InAppChatStore.Role.USER, "m$it")
        }
        val all = InAppChatStore.list(Int.MAX_VALUE)
        assertTrue("size=${all.size}", all.size <= InAppChatStore.MAX_MESSAGES)
        // 保留的是最新消息
        assertEquals("m${InAppChatStore.MAX_MESSAGES + 19}", all.last().text)
    }

    @Test
    fun append_notifiesListener() {
        val received = mutableListOf<InAppChatStore.Message>()
        InAppChatStore.listener = { received.add(it) }
        InAppChatStore.append(InAppChatStore.Role.AGENT, "hello")
        assertEquals(1, received.size)
        assertEquals("hello", received[0].text)
    }

    @Test
    fun list_respectsLimit_returnsOldestWithinWindow() {
        repeat(10) { InAppChatStore.append(InAppChatStore.Role.USER, "m$it") }
        val last3 = InAppChatStore.list(3)
        assertEquals(listOf("m7", "m8", "m9"), last3.map { it.text })
    }

    @Test
    fun clear_emptiesStoreAndPersists() {
        InAppChatStore.append(InAppChatStore.Role.USER, "x")
        InAppChatStore.clear()
        InAppChatStore.init(tmp.root)
        assertTrue(InAppChatStore.list().isEmpty())
    }
}

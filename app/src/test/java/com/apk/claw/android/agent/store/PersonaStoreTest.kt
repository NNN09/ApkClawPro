package com.apk.claw.android.agent.store

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonaStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun get_returnsEmptyWhenFileMissing() {
        PersonaStore.init(tmp.root)
        assertEquals("", PersonaStore.get())
    }

    @Test fun set_thenGet_roundTrips() {
        PersonaStore.init(tmp.root)
        PersonaStore.set("  你是一只叫小爪的猫娘助手  ")
        assertEquals("你是一只叫小爪的猫娘助手", PersonaStore.get())
    }

    @Test fun set_twice_overwrites() {
        PersonaStore.init(tmp.root)
        PersonaStore.set("A")
        PersonaStore.set("B")
        assertEquals("B", PersonaStore.get())
    }
}

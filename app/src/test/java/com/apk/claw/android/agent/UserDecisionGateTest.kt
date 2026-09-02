package com.apk.claw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UserDecisionGateTest {

    @Test
    fun resolveTrue_beforeAwait_unblocksImmediately() {
        val gate = UserDecisionGate(60_000)
        gate.resolve(true)
        assertEquals(UserDecisionGate.Decision.CONFIRMED, gate.await { false })
        assertTrue(gate.resolvedByUser)
    }

    @Test
    fun resolveFalse_yieldsRejected() {
        val gate = UserDecisionGate(60_000)
        gate.resolve(false)
        assertEquals(UserDecisionGate.Decision.REJECTED, gate.await { false })
    }

    @Test
    fun await_timesOut_whenNobodyResolves() {
        val gate = UserDecisionGate(300)
        val start = System.nanoTime()
        assertEquals(UserDecisionGate.Decision.TIMEOUT, gate.await { false })
        assertFalse(gate.resolvedByUser)
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(250))
    }

    @Test
    fun await_cancelCheckWins() {
        val gate = UserDecisionGate(60_000)
        assertEquals(UserDecisionGate.Decision.CANCELLED, gate.await { true })
    }

    @Test
    fun firstResolveWins() {
        val gate = UserDecisionGate(60_000)
        gate.resolve(false)
        gate.resolve(true)
        assertEquals(UserDecisionGate.Decision.REJECTED, gate.await { false })
    }

    @Test
    fun await_blockingThreadReleasedByResolve() {
        val gate = UserDecisionGate(60_000)
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        var result: UserDecisionGate.Decision? = null
        Thread {
            started.countDown()
            result = gate.await { false }
            done.countDown()
        }.start()
        started.await()
        gate.resolve(true)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(UserDecisionGate.Decision.CONFIRMED, result)
    }
}

package com.apk.claw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopGuardTest {

    @Test
    fun pureObservationLoop_detected() {
        assertTrue(LoopGuard.isPureObservation(listOf("take_screenshot:{}", "wait:{\"ms\":3000}", "take_screenshot:{}")))
        assertTrue(LoopGuard.isPureObservation(listOf("find_node_info:{\"text\":\"x\"}")))
    }

    @Test
    fun actionLoop_notPureObservation() {
        // 混入任何操作类工具即视为操作循环
        assertFalse(LoopGuard.isPureObservation(listOf("take_screenshot:{}", "tap:{\"x\":500,\"y\":250}")))
        assertFalse(LoopGuard.isPureObservation(listOf("tap:{\"x\":500,\"y\":250}")))
    }

    @Test
    fun emptyFingerprints_notObservation() {
        assertFalse(LoopGuard.isPureObservation(emptyList()))
    }

    @Test
    fun nudgeMessage_differsByLoopComposition() {
        val observation = LoopGuard.nudgeMessage(listOf("take_screenshot:{}", "wait:{}"))
        val action = LoopGuard.nudgeMessage(listOf("tap:{}", "tap:{}"))
        assertTrue(observation.contains("wait_after"))
        assertTrue(action.contains("死循环"))
        assertTrue(observation != action)
    }

    @Test
    fun nudgeMessage_mentionsFinishEscalation() {
        assertTrue(LoopGuard.nudgeMessage(listOf("swipe:{}")).contains("finish"))
        assertTrue(LoopGuard.nudgeMessage(listOf("wait:{}")).contains("finish"))
    }

    @Test
    fun toolName_extractionHandlesArgsWithColons() {
        // 指纹是 "toolName:args"，args 里可能含冒号，只取第一个冒号前的部分
        assertTrue(LoopGuard.isPureObservation(listOf("wait:{\"a\":\"b:c\"}")))
        assertFalse(LoopGuard.isPureObservation(listOf("tap:{\"x\":1}")))
    }

    // ==================== ④⑤ 观察循环升级阶梯 ====================

    @Test
    fun escalation_noneBelowNudgeThreshold() {
        assertEquals(LoopGuard.Escalation.NONE, LoopGuard.escalationFor(0))
        assertEquals(LoopGuard.Escalation.NONE, LoopGuard.escalationFor(3))
    }

    @Test
    fun escalation_nudgeAtFirstThreshold() {
        assertEquals(LoopGuard.Escalation.NUDGE, LoopGuard.escalationFor(LoopGuard.NUDGE_EVERY))
    }

    @Test
    fun escalation_restrictAfterIgnoredNudge() {
        assertEquals(LoopGuard.Escalation.NONE, LoopGuard.escalationFor(LoopGuard.NUDGE_EVERY + 1))
        assertEquals(LoopGuard.Escalation.RESTRICT, LoopGuard.escalationFor(LoopGuard.RESTRICT_AT))
        assertEquals(LoopGuard.Escalation.RESTRICT, LoopGuard.escalationFor(LoopGuard.RESTRICT_AT + 3))
    }

    @Test
    fun escalation_forceFinishAtHardCap() {
        assertEquals(LoopGuard.Escalation.FORCE_FINISH, LoopGuard.escalationFor(LoopGuard.FORCE_FINISH_AT))
        assertEquals(LoopGuard.Escalation.FORCE_FINISH, LoopGuard.escalationFor(LoopGuard.FORCE_FINISH_AT + 10))
    }

    @Test
    fun restrictionMessage_forbidsObservationButKeepsExit() {
        val msg = LoopGuard.restrictionMessage(8)
        assertTrue(msg.contains("take_screenshot"))
        assertTrue(msg.contains("finish"))
    }

    @Test
    fun restoreMessage_announcesRecovery() {
        assertTrue(LoopGuard.restoreMessage().contains("恢复"))
    }
}

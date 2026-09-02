package com.apk.claw.android.agent.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptComposerTest {

    @Test fun compose_omitsBlankPersonaSection() {
        val out = PromptComposer.compose("", "PROTOCOL", "", "", "DEVICE")
        assertFalse(out.contains("人格"))
        assertTrue(out.contains("PROTOCOL"))
        assertTrue(out.contains("DEVICE"))
    }

    @Test fun compose_personaComesFirstWithPriorityNote() {
        val out = PromptComposer.compose("PERSONA", "PROTOCOL", "", "", "")
        assertTrue(out.indexOf("PERSONA") < out.indexOf("PROTOCOL"))
        assertTrue(out.contains("不得覆盖"))
    }

    @Test fun compose_fixedSectionOrder() {
        val out = PromptComposer.compose("P", "PROTO", "MEM", "SKILL", "DEV")
        val order = listOf("P", "PROTO", "MEM", "SKILL", "DEV").map { out.indexOf(it) }
        assertEquals(order, order.sorted())
    }

    @Test fun compose_blankSectionsSkipped() {
        val out = PromptComposer.compose("P", "PROTO", "", "SKILL", "")
        assertFalse(out.contains("MEM"))
        assertTrue(out.contains("SKILL"))
    }
}

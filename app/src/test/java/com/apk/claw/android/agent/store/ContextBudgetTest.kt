package com.apk.claw.android.agent.store

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    private fun toolResult(tool: String, text: String): ToolExecutionResultMessage =
        ToolExecutionResultMessage.from("1", tool, text)

    @Test
    fun estimateChars_sumsMessageSizes() {
        val msgs = listOf<ChatMessage>(SystemMessage.from("12345"), UserMessage.from("abc"))
        assertEquals(8, ContextBudget.estimateChars(msgs))
    }

    @Test
    fun compressAllToolResults_keepsLatestScreenInfo() {
        val long = """{"isSuccess":true,"data":"${"x".repeat(300)}"}"""
        val msgs = mutableListOf<ChatMessage>(
            SystemMessage.from("s"),
            UserMessage.from("task"),
            AiMessage.from(listOf(ToolExecutionRequest.builder().id("a").name("get_screen_info").arguments("{}").build())),
            toolResult("get_screen_info", long),
            AiMessage.from(listOf(ToolExecutionRequest.builder().id("b").name("tap").arguments("{}").build())),
            toolResult("tap", long)
        )
        ContextBudget.compressAllToolResults(msgs)
        val screen = msgs[3] as ToolExecutionResultMessage
        val tap = msgs[5] as ToolExecutionResultMessage
        assertTrue(screen.text().length > 200)   // 最新屏幕信息保持完整
        assertTrue(tap.text().length <= 100)     // 其他长工具结果被压成一行摘要
    }

    @Test
    fun truncateOldestRounds_removesEarlyRoundsKeepsRecent() {
        val msgs = mutableListOf<ChatMessage>(SystemMessage.from("s"), UserMessage.from("task"))
        repeat(5) { i ->
            msgs.add(AiMessage.from("think$i"))
            msgs.add(toolResult("tap", """{"isSuccess":true,"data":"r$i"}"""))
        }
        val removed = ContextBudget.truncateOldestRounds(msgs, protectedPrefix = 1, keepRounds = 2)
        assertTrue(removed > 0)
        assertEquals(7, msgs.size)                       // Sys + User + marker + 2×(Ai+Tool)
        assertTrue(msgs[2] is UserMessage)               // 被删段的占位 marker
        assertEquals("think3", (msgs[3] as AiMessage).text())
        assertEquals("think4", (msgs[5] as AiMessage).text())
    }

    @Test
    fun truncateOldestRounds_noopWhenWithinKeep() {
        val msgs = mutableListOf<ChatMessage>(SystemMessage.from("s"), UserMessage.from("t"))
        repeat(2) { i -> msgs.add(AiMessage.from("t$i")); msgs.add(toolResult("tap", "ok")) }
        assertEquals(0, ContextBudget.truncateOldestRounds(msgs, 1, keepRounds = 2))
    }
}

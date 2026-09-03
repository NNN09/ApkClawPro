package com.apk.claw.android.agent.store

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun charBudget_defaultsToLegacyBudgetWhenUnset() {
        // 未设置（0/负数）时退回默认窗口，且与旧 CHAR_BUDGET=36000 完全一致
        assertEquals(36000, ContextBudget.charBudget(0))
        assertEquals(36000, ContextBudget.charBudget(-5))
    }

    @Test
    fun charBudget_scalesWithContextWindow() {
        // 预算 = 窗口 tokens × 0.8 安全系数 × 1.5 字符/token
        assertEquals(153600, ContextBudget.charBudget(128000))
        assertEquals(240000, ContextBudget.charBudget(200000))
    }

    @Test
    fun charBudget_floorsTinyWindows() {
        // 过小的窗口被抬到下限，避免预算小到每轮都触发截断
        assertEquals((4096 * 1.2).toInt(), ContextBudget.charBudget(100))
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

    // ==================== F10 多模态 ====================

    private fun imageMessage(text: String): UserMessage =
        UserMessage.from(TextContent(text), ImageContent.from("aGVsbG8=", "image/jpeg"))

    @Test
    fun estimateChars_countsImageAtFixedCost_notBase64Length() {
        val msgs = mutableListOf<ChatMessage>(UserMessage.from("task"))
        assertEquals(4, ContextBudget.estimateChars(msgs))
        msgs.add(imageMessage("[截图]"))   // 文本 4 字符 + 1 张图像固定成本
        assertEquals(4 + 4 + ContextBudget.IMAGE_COST_CHARS, ContextBudget.estimateChars(msgs))
    }

    @Test
    fun hasImages_detectsImageContent() {
        val msgs = mutableListOf<ChatMessage>(UserMessage.from("plain"), UserMessage.from("t"))
        assertFalse(ContextBudget.hasImages(msgs))
        msgs.add(imageMessage("shot"))
        assertTrue(ContextBudget.hasImages(msgs))
    }

    @Test
    fun foldOldImages_keepsOnlyNewestImage() {
        val msgs = mutableListOf<ChatMessage>(
            imageMessage("shot1"),
            UserMessage.from("middle question"),
            imageMessage("shot2"),
            UserMessage.from("tail")
        )
        val changed = ContextBudget.foldOldImages(msgs)
        assertTrue(changed)
        // 旧图（index 0）换成文本占位，最新图（index 2）连同其图像保留
        assertTrue(ContextBudget.hasImages(msgs))
        val old = msgs[0] as UserMessage
        val newest = msgs[2] as UserMessage
        assertFalse(old.contents().any { it is ImageContent })
        assertTrue(old.singleText().contains("已省略"))
        assertTrue(newest.contents().any { it is ImageContent })
        assertEquals("shot2", newest.contents().filterIsInstance<TextContent>().first().text())
        // 中间和尾部的纯文本消息不动
        assertEquals("middle question", (msgs[1] as UserMessage).singleText())
        assertEquals("tail", (msgs[3] as UserMessage).singleText())
    }

    @Test
    fun stripImages_removesAllAndReports() {
        val msgs = mutableListOf<ChatMessage>(UserMessage.from("task"), imageMessage("shot"))
        assertTrue(ContextBudget.stripImages(msgs))
        assertFalse(ContextBudget.hasImages(msgs))
        // 纯文本消息保持原样
        assertEquals("task", (msgs[0] as UserMessage).singleText())
        // 再调一次没有变化
        assertFalse(ContextBudget.stripImages(msgs))
    }

    @Test
    fun compressAllToolResults_alsoFoldsOldImages() {
        val long = """{"isSuccess":true,"data":"${"x".repeat(300)}"}"""
        val msgs = mutableListOf<ChatMessage>(
            imageMessage("old shot"),
            toolResult("tap", long),
            imageMessage("new shot")
        )
        assertTrue(ContextBudget.compressAllToolResults(msgs))
        // 旧图被折叠，最新图保留
        assertFalse((msgs[0] as UserMessage).contents().any { it is ImageContent })
        assertTrue((msgs[2] as UserMessage).contents().any { it is ImageContent })
        assertTrue((msgs[1] as ToolExecutionResultMessage).text().length <= 100)
    }
}

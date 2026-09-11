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
    fun foldConsumedImages_keepsCaptionStripsImage() {
        // 独立视觉路由：识别轮结束后把消费过的截图折叠为文本，下一轮请求回落主模型
        val msgs = mutableListOf<ChatMessage>(
            UserMessage.from("task"),
            imageMessage("[截图 #1] 已附当前屏幕图像"),
            AiMessage.from("看到登录按钮")
        )
        val folded = ContextBudget.foldConsumedImages(msgs)
        assertEquals(1, folded)
        assertFalse(ContextBudget.hasImages(msgs))
        // 原文本说明保留（截图编号/尺寸提示仍在），仅剥离图像部分
        val consumed = msgs[1] as UserMessage
        assertTrue(consumed.singleText().contains("[截图 #1]"))
        // 其余消息不动
        assertEquals("task", (msgs[0] as UserMessage).singleText())
        assertEquals("看到登录按钮", (msgs[2] as AiMessage).text())
    }

    @Test
    fun foldConsumedImages_noopWithoutImages() {
        val msgs = mutableListOf<ChatMessage>(UserMessage.from("task"), AiMessage.from("ok"))
        assertEquals(0, ContextBudget.foldConsumedImages(msgs))
        assertEquals("task", (msgs[0] as UserMessage).singleText())
    }

    @Test
    fun foldConsumedImages_placeholderIsDescriptiveNotParrotable() {
        // 占位文案不得含"已移除/重新调用"类可被弱模型复述的断言与指令（2026-09-11 事故）
        val msgs = mutableListOf<ChatMessage>(imageMessage("shot"))
        ContextBudget.foldConsumedImages(msgs)
        val text = (msgs[0] as UserMessage).singleText()
        assertFalse(text.contains("已移除"))
        assertFalse(text.contains("重新调用"))
        assertTrue(text.contains("已省略"))
        assertTrue(text.contains("助手回复"))
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

    // ==================== makeRoomForImage：预算驱动的截图注入 ====================

    @Test
    fun makeRoomForImage_passesWithoutFoldingWhenBudgetAllows() {
        val msgs = mutableListOf<ChatMessage>(
            SystemMessage.from("s"),
            imageMessage("shot"),
            UserMessage.from("t")
        )
        val budget = ContextBudget.estimateChars(msgs) + ContextBudget.IMAGE_COST_CHARS
        // 预算恰好容纳新图：不折叠任何已有图
        assertTrue(ContextBudget.makeRoomForImage(msgs, budget))
        assertTrue((msgs[1] as UserMessage).contents().any { it is ImageContent })
    }

    @Test
    fun makeRoomForImage_foldsOldestFirstAndKeepsNewer() {
        val msgs = mutableListOf<ChatMessage>(
            imageMessage("shot1"),
            UserMessage.from("x"),
            imageMessage("shot2"),
            UserMessage.from("y"),
            imageMessage("shot3")
        )
        // 初始 ≈9017；折叠 1 张后 ≈6027，折叠 2 张后 ≈3037。
        // budget=7000 → 需求 estimate ≤ 4000：折叠 2 张最旧后满足，shot3（最新）保留
        assertTrue(ContextBudget.makeRoomForImage(msgs, 7000))
        assertFalse((msgs[0] as UserMessage).contents().any { it is ImageContent })
        assertFalse((msgs[2] as UserMessage).contents().any { it is ImageContent })
        assertTrue((msgs[4] as UserMessage).contents().any { it is ImageContent })
    }

    @Test
    fun makeRoomForImage_foldsOnlyAsManyAsNeeded() {
        val msgs = mutableListOf<ChatMessage>(
            imageMessage("shot1"),
            UserMessage.from("x"),
            imageMessage("shot2"),
            UserMessage.from("y"),
            imageMessage("shot3")
        )
        // budget=9100 → 需求 estimate ≤ 6100：折叠 1 张最旧（6027 ≤ 6100）即停，不误伤中间图
        assertTrue(ContextBudget.makeRoomForImage(msgs, 9100))
        assertFalse((msgs[0] as UserMessage).contents().any { it is ImageContent })
        assertTrue((msgs[2] as UserMessage).contents().any { it is ImageContent })
        assertTrue((msgs[4] as UserMessage).contents().any { it is ImageContent })
    }

    @Test
    fun makeRoomForImage_foldsAllWhenDesperate_thenReportsFalse() {
        val msgs = mutableListOf<ChatMessage>(
            imageMessage("shot1"),
            imageMessage("shot2")
        )
        // 预算连一张图都放不下（系统提示词都放不下）：全折叠后仍返回 false
        val budget = 10
        assertFalse(ContextBudget.makeRoomForImage(msgs, budget))
        assertFalse(ContextBudget.hasImages(msgs))
    }

    // ==================== BudgetCalibrator：实测 token 校准预算 ====================

    @Test
    fun calibrator_initialBudgetMatchesLegacy() {
        // 未实测时与静态公式完全一致（旧行为），保证升级无行为跳变
        val c = ContextBudget.BudgetCalibrator()
        assertEquals(ContextBudget.charBudget(30000), c.effectiveCharBudget(30000))
        assertEquals(36000, c.effectiveCharBudget(0))
    }

    @Test
    fun calibrator_usesMeasuredRatio() {
        val c = ContextBudget.BudgetCalibrator()
        // 估算 24000 字符，实测 24000 token → 换算系数 1.0（先验 1.5 高估了字符密度）
        assertTrue(c.onMeasured(estimateChars = 24000, actualInputTokens = 24000))
        assertEquals(1.0, c.charsPerToken, 0.0001)
        // 新预算 = 30000 × 0.8 × 1.0 = 24000
        assertEquals(24000, c.effectiveCharBudget(30000))
    }

    @Test
    fun calibrator_keepsBudgetWhenEstimateAccurate() {
        val c = ContextBudget.BudgetCalibrator()
        // 估算 36000 字符对应实测 24000 token（比例恰为 1.5）：预算不收紧
        assertFalse(c.onMeasured(estimateChars = 36000, actualInputTokens = 24000))
        assertEquals(ContextBudget.charBudget(30000), c.effectiveCharBudget(30000))
    }

    @Test
    fun calibrator_clampsExtremeRatios() {
        val c = ContextBudget.BudgetCalibrator()
        c.onMeasured(estimateChars = 100_000, actualInputTokens = 10)    // 10000 → clamp 上限 4.0
        assertEquals(4.0, c.charsPerToken, 0.0001)
        c.onMeasured(estimateChars = 100, actualInputTokens = 100_000)   // 0.001 → clamp 下限 0.6
        assertEquals(0.6, c.charsPerToken, 0.0001)
    }

    @Test
    fun calibrator_ignoresInvalidMeasurements() {
        val c = ContextBudget.BudgetCalibrator()
        assertFalse(c.onMeasured(estimateChars = 1000, actualInputTokens = 0))
        assertFalse(c.onMeasured(estimateChars = 1000, actualInputTokens = -5))
        assertFalse(c.onMeasured(estimateChars = 0, actualInputTokens = 100))
        assertEquals(ContextBudget.charBudget(30000), c.effectiveCharBudget(30000))
    }

    // ==================== ① 单请求图片数上限 ====================

    @Test
    fun countImages_countsOnlyImageMessages() {
        val msgs = mutableListOf<ChatMessage>(
            UserMessage.from("task"),
            imageMessage("s1"),
            UserMessage.from("mid"),
            imageMessage("s2")
        )
        assertEquals(2, ContextBudget.countImages(msgs))
        ContextBudget.stripImages(msgs)
        assertEquals(0, ContextBudget.countImages(msgs))
    }

    @Test
    fun foldOldestImagesTo_keepsNewestAndReturnsFoldedCount() {
        val msgs = mutableListOf<ChatMessage>(
            imageMessage("s1"),
            UserMessage.from("mid"),
            imageMessage("s2"),
            UserMessage.from("tail"),
            imageMessage("s3")
        )
        val folded = ContextBudget.foldOldestImagesTo(msgs, keepCount = 1)
        assertEquals(2, folded)
        assertEquals(1, ContextBudget.countImages(msgs))
        // 折叠的最旧消息替换为占位
        assertTrue((msgs[0] as UserMessage).singleText().contains("早期截图已省略"))
        // 保留的是最新一张（s3）
        val kept = msgs.filterIsInstance<UserMessage>()
            .first { it.contents().any { c -> c is ImageContent } }
        assertTrue(kept.contents().filterIsInstance<TextContent>().any { it.text().contains("s3") })
    }

    @Test
    fun foldOldestImagesTo_noopWhenAlreadyWithinLimit() {
        val msgs = mutableListOf<ChatMessage>(imageMessage("s1"), imageMessage("s2"))
        assertEquals(0, ContextBudget.foldOldestImagesTo(msgs, keepCount = 2))
        assertEquals(2, ContextBudget.countImages(msgs))
    }

    @Test
    fun makeRoomForImage_enforcesImageCountCapEvenWithinBudget() {
        val msgs = mutableListOf<ChatMessage>()
        repeat(ContextBudget.MAX_IMAGES_PER_REQUEST) { msgs.add(imageMessage("s$it")) }
        // 字符预算充裕也必须受张数上限约束：为新图腾位后总数不得超过上限
        assertTrue(ContextBudget.makeRoomForImage(msgs, charBudget = Int.MAX_VALUE))
        assertTrue(ContextBudget.countImages(msgs) < ContextBudget.MAX_IMAGES_PER_REQUEST)
    }

    @Test
    fun makeRoomForImage_stillFoldsByBudgetFirst() {
        // 原有行为保持：超字符预算时逐张折叠到放得下
        // 1000 文本 + (2 截图文本 + 3000 图像成本) = 4002，再加新图 3000 = 7002 > 预算 5000
        // → 折叠 s1 后 1014 + 3000(新图) = 4014 ≤ 5000 → 放行
        val msgs = mutableListOf<ChatMessage>(UserMessage.from("x".repeat(1000)), imageMessage("s1"))
        assertTrue(ContextBudget.makeRoomForImage(msgs, charBudget = 5000))
        assertEquals(0, ContextBudget.countImages(msgs))
    }
}

package com.apk.claw.android.agent

import dev.langchain4j.data.message.Content
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.TextContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图视觉消息构造：detail 必须 HIGH（库内默认 LOW 会被服务商端再压缩，
 * 真机实证是坐标误读的直接根源）；文本声明坐标约定防坐标系混用。
 */
class ScreenshotEncoderTest {

    private fun contents(message: dev.langchain4j.data.message.UserMessage): List<Content> =
        message.contents()

    @Test
    fun `imageMessage declares coordinate convention and high detail image`() {
        val encoded = ScreenshotEncoder.Encoded("FAKEBASE64", "image/jpeg", 413, 896)

        val message = ScreenshotEncoder.imageMessage(encoded, index = 3, sourcePath = "/data/shot.png")

        val texts = contents(message).filterIsInstance<TextContent>()
        val images = contents(message).filterIsInstance<ImageContent>()
        assertEquals(1, texts.size)
        assertEquals(1, images.size)
        assertTrue(texts[0].text().contains("0-1000"))
        assertTrue(texts[0].text().contains("413x896"))
        assertEquals(ImageContent.DetailLevel.HIGH, images[0].detailLevel())
        assertEquals("image/jpeg", images[0].image().mimeType())
    }

    @Test
    fun `autoTriggeredMessage carries hint text and high detail image`() {
        val encoded = ScreenshotEncoder.Encoded("FAKEBASE64", "image/jpeg", 413, 896)

        val message = ScreenshotEncoder.autoTriggeredMessage(encoded, index = 5)

        val texts = contents(message).filterIsInstance<TextContent>()
        val images = contents(message).filterIsInstance<ImageContent>()
        assertEquals(1, texts.size)
        assertEquals(1, images.size)
        assertTrue(texts[0].text().contains("find_node_info"))
        assertEquals(ImageContent.DetailLevel.HIGH, images[0].detailLevel())
    }
}

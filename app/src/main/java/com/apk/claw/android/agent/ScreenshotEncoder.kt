package com.apk.claw.android.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.UserMessage
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * F10：把 take_screenshot 的 PNG 截图转成 LLM 视觉消息。
 * 截图统一缩放到 maxDim 内并转 JPEG，控制 base64 体积与视觉 token 成本；
 * Android 位图部分与 langchain4j 消息构造分离，消息构造可 JVM 单测。
 */
object ScreenshotEncoder {

    data class Encoded(val base64: String, val mimeType: String, val width: Int, val height: Int)

    /** 缩放后最长边（像素）：主流视觉模型在 ~1M 像素内性价比最高 */
    const val MAX_DIM_PX = 896
    const val JPEG_QUALITY = 55

    /** 读取 PNG 文件 → 缩放 → JPEG → base64；文件缺失或解码失败返回 null */
    fun encode(file: File, maxDim: Int = MAX_DIM_PX, quality: Int = JPEG_QUALITY): Encoded? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val scaled = scaleDown(bitmap, maxDim)
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
        val width = scaled.width
        val height = scaled.height
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return Encoded(Base64.encodeToString(bytes, Base64.NO_WRAP), "image/jpeg", width, height)
    }

    private fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxDim || height / (sample * 2) >= maxDim) {
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    /** 单任务最多注入几张截图，超出后工具只回路径文本（F10 验收：截图数量有上限） */
    const val MAX_SCREENSHOTS_PER_TASK = 8

    /**
     * 节点查找连续失败时自动附加的截图消息（F10 视觉兜底）：
     * WebView/自绘界面的无障碍节点读不到内容，提示模型改用视觉而非继续重试。
     */
    fun autoTriggeredMessage(encoded: Encoded, index: Int): UserMessage =
        UserMessage.from(
            TextContent(
                "[截图 #$index·自动附加] 节点查找连续失败，当前界面很可能是 WebView 或自绘 UI（无障碍节点树读不到内容）。" +
                    "已附当前屏幕图像（${encoded.width}x${encoded.height}），请直接观察图像继续任务，不要继续用 find_node_info 重试。"
            ),
            ImageContent.from(encoded.base64, encoded.mimeType)
        )

    /**
     * 构造带图像的用户消息（纯 langchain4j，可 JVM 单测）。
     * 文本在前：OpenAI 兼容端点对 content part 顺序无要求，但对部分
     * OpenAI 兼容代理文本先行更稳。
     */
    fun imageMessage(encoded: Encoded, index: Int, sourcePath: String): UserMessage =
        UserMessage.from(
            TextContent(
                "[截图 #$index] 已附当前屏幕图像（${encoded.width}x${encoded.height}）。" +
                    "请直接观察图像继续任务；原图: $sourcePath"
            ),
            ImageContent.from(encoded.base64, encoded.mimeType)
        )

    /** 截图上限提示（达到上限后追加给模型的系统提示） */
    fun capReachedMessage(): UserMessage =
        UserMessage.from(
            "[系统提示] 本任务截图注入次数已达上限（$MAX_SCREENSHOTS_PER_TASK），" +
                "后续 take_screenshot 只返回文件路径不再附带图像。请改用 get_screen_info 获取节点树信息。"
        )

    /** 视觉被禁用/模型不支持时的降级占位 */
    fun disabledMessage(): UserMessage =
        UserMessage.from(
            "[系统提示] 当前模型或配置不支持视觉输入，截图未注入，仅返回文件路径。请改用 get_screen_info 获取屏幕信息。"
        )
}

package com.apk.claw.android.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局千分比坐标空间（ScreenCoords）的纯函数测试。
 * 契约：模型观察（bounds）与动作（tap）同空间，像素换算只发生在工具边界。
 */
class ScreenCoordsTest {

    @Test
    fun toPermille_mapsProportionally() {
        // 1080 宽：540px → 500‰（四舍五入）
        assertEquals(500, ScreenCoords.toPermille(540, 1080))
        // 2340 高：1170px → 500‰
        assertEquals(500, ScreenCoords.toPermille(1170, 2340))
        assertEquals(0, ScreenCoords.toPermille(0, 1080))
        assertEquals(1000, ScreenCoords.toPermille(1080, 1080))
    }

    @Test
    fun toPermille_roundsToNearest() {
        // 1‰ @1080 ≈ 1.08px：400px/1080 = 370.37 → 370
        assertEquals(370, ScreenCoords.toPermille(400, 1080))
        // 545/1080 = 504.6 → 505（round half up）
        assertEquals(505, ScreenCoords.toPermille(545, 1080))
    }

    @Test
    fun toPermille_clampsOutOfRangePixels() {
        // 悬浮窗/分屏等场景节点可越出屏幕：负值与超界值都 clamp
        assertEquals(0, ScreenCoords.toPermille(-30, 1080))
        assertEquals(1000, ScreenCoords.toPermille(2000, 1080))
    }

    @Test
    fun toPermille_guardsBadTotal() {
        assertEquals(0, ScreenCoords.toPermille(100, 0))
        assertEquals(0, ScreenCoords.toPermille(100, -5))
    }

    @Test
    fun toPixel_clampsToLegalGestureRange() {
        // 1080 宽：500‰ → 540px
        assertEquals(540, ScreenCoords.toPixel(500, 1080))
        // 上界 clamp 到 total-1，保证 performSwipe/performTap 合法
        assertEquals(1079, ScreenCoords.toPixel(1000, 1080))
        assertEquals(1000, ScreenCoords.toPixel(1000, 1001))
        assertEquals(0, ScreenCoords.toPixel(0, 1080))
    }

    @Test
    fun toPixel_guardsBadTotal() {
        assertEquals(0, ScreenCoords.toPixel(500, 0))
        assertEquals(0, ScreenCoords.toPixel(500, -1))
    }

    @Test
    fun roundTrip_errorWithinOnePixel() {
        // bounds(l) → tap(x) 的往返误差必须 ≤1px（千分比精度 0.1%）
        val total = 2340
        for (px in intArrayOf(0, 1, 17, 500, 1299, 2339)) {
            val back = ScreenCoords.toPixel(ScreenCoords.toPermille(px, total), total)
            assertTrue("px=$px back=$back", Math.abs(back - px) <= 1)
        }
    }
}

package com.apk.claw.android.tool

/**
 * 全局统一的模型坐标空间：0-1000 千分比（x 相对屏宽、y 相对屏高，各轴独立取整）。
 *
 * 设计约束（第一性原则）：模型的一切几何感知（get_screen_info / find_node_info /
 * scroll_to_find 的 bounds 与 center、截图上的相对位置）和一切几何动作
 * （tap / swipe / long_press 的坐标参数）共用这一个空间，模型永远不做像素换算；
 * 像素换算只发生在工具执行边界（BaseTool.pixelX/pixelY、节点树序列化处），
 * 本对象保持纯函数、零 Android 依赖，便于 JVM 单测。
 *
 * 注意：千分比基准与 BaseTool 的屏幕尺寸（ScreenUtils）保持同一来源，
 * 保证 bounds → tap 的往返不产生系统性偏移；屏幕外的越界值双向 clamp。
 */
object ScreenCoords {

    /** 千分比上限 */
    const val MAX = 1000

    /** 像素 → 千分比；total 非法或越界时 clamp 到 [0, MAX]（屏幕外节点可见于分屏/悬浮窗等场景） */
    @JvmStatic
    fun toPermille(pixel: Int, total: Int): Int {
        if (total <= 0) return 0
        val v = Math.round(pixel.toDouble() * MAX / total).toInt()
        return v.coerceIn(0, MAX)
    }

    /** 千分比 → 像素；clamp 到 [0, total-1]，保证手势坐标始终合法 */
    @JvmStatic
    fun toPixel(permille: Int, total: Int): Int {
        if (total <= 0) return 0
        return (permille.toLong() * total / MAX).toInt().coerceIn(0, total - 1)
    }
}

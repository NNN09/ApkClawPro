package com.apk.claw.android.compliance

/**
 * C6 按 App 自动化策略评估（纯函数，可单测）。在每次工具调用前执行：
 * 决定该调用是放行、需逐操作人工确认（CONFIRM）、还是被安全策略拒绝（BLOCK）。
 *
 * 作用域只覆盖"在第三方/指定 App 界面内做改动"的工具；观察类工具、导航（system_key）、
 * F9 系统能力工具一律放行——观察允许 Agent 看清被禁界面后离开，系统工具属 C1 官方/系统 API 层。
 * F2 危险操作确认作为全局硬底线在调用方另行执行，本引擎不弱化它。
 */
object AppPolicyEngine {

    /** 判定结果 */
    sealed class Verdict {
        /** 放行，交由 F2 危险确认兜底 */
        object Allow : Verdict()

        /** 该 App 配置为逐操作确认（CONFIRM），调用方应经渠道请求人工确认后执行 */
        data class Confirm(val targetPackage: String) : Verdict()

        /** 安全策略拒绝执行 */
        data class Block(val targetPackage: String, val reason: Reason) : Verdict()

        enum class Reason {
            /** 策略表将该 App 配置为 BLOCK（禁止自动化） */
            APP_BLOCKED,
            /** C5 总开关关闭且目标是第三方 App */
            MASTER_DISABLED
        }
    }

    /** 评估上下文：由调用方（Android 侧）采集设备状态注入，引擎保持纯函数 */
    data class Ctx(
        /** 当前前台 App 包名（无障碍根节点包名）；系统弹窗等不可观测时为 null */
        val foregroundPackage: String?,
        /** C5：第三方 App 自动化总开关 */
        val masterThirdPartyEnabled: Boolean,
        /** 包是否为系统 App（包管理器查询失败时调用方应返回 false——按第三方保守处理） */
        val isSystemApp: (String) -> Boolean,
        /** 策略表查询；无条目返回 null */
        val modeOf: (String) -> Mode?
    )

    /** 作用于当前前台 App 界面的改动类工具（被禁/总开关关闭时禁止；其余工具不受本策略约束） */
    private val UI_MUTATING_TOOLS = setOf(
        "tap", "long_press", "swipe", "input_text", "scroll_to_find", "repeat_actions"
    )

    /** open_app 通过 package_name 参数点名目标包，单独处理 */
    private const val OPEN_APP = "open_app"
    private const val OPEN_APP_PACKAGE_PARAM = "package_name"

    fun evaluate(toolName: String, params: Map<String, Any>, ctx: Ctx): Verdict {
        val target = when {
            toolName == OPEN_APP -> params[OPEN_APP_PACKAGE_PARAM]?.toString()
            toolName in UI_MUTATING_TOOLS -> ctx.foregroundPackage
            else -> return Verdict.Allow
        } ?: return Verdict.Allow   // 目标未知（弹窗遮挡/参数缺失等）→ 放行，由工具自身报错或 F2 兜底

        val mode = ctx.modeOf(target)
        return when {
            mode == Mode.BLOCK -> Verdict.Block(target, Verdict.Reason.APP_BLOCKED)
            // 总开关关闭时"禁止第三方自动化"优先于 AUTO/CONFIRM（用户意图：一律不碰第三方界面）
            !ctx.masterThirdPartyEnabled && !ctx.isSystemApp(target) ->
                Verdict.Block(target, Verdict.Reason.MASTER_DISABLED)
            mode == Mode.CONFIRM -> Verdict.Confirm(target)
            else -> Verdict.Allow
        }
    }
}

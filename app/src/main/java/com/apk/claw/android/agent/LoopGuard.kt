package com.apk.claw.android.agent

/**
 * 死循环提示语分流：纯观察工具的重复（轮询等待加载）与操作类工具的重复
 * （操作无效）是两种不同的卡住方式，提示语要对症——否则等待中的模型会被
 * "换方法"误导，而它其实只需要给足等待时间。
 *
 * 提示语只有提醒、没有约束（真机实证：模型曾连续 50 轮纯截图、无视 11 次
 * nudge、全程 0 次点击），所以 [Escalation] 阶梯在 nudge 被无视后升级为
 * 真正的强制手段：临时禁用观察工具逼它行动，仍不行则系统强制终止任务。
 */
object LoopGuard {

    /** 纯观察类工具：重复执行不改变屏幕，合法用途是等待后的轮询确认 */
    val OBSERVATION_TOOLS = setOf("take_screenshot", "wait", "find_node_info")

    /** 连续纯观察达到该轮数时第一次 nudge；之后每满 NUDGE_EVERY 轮提醒一次直到 RESTRICT_AT */
    const val NUDGE_EVERY = 4

    /** 连续纯观察达到该轮数：下一轮临时禁用观察工具，逼模型行动或放弃 */
    const val RESTRICT_AT = 8

    /** 连续纯观察达到该轮数：系统强制终止任务，不再烧 token */
    const val FORCE_FINISH_AT = 12

    /** 连续纯观察轮数对应的处置动作 */
    enum class Escalation { NONE, NUDGE, RESTRICT, FORCE_FINISH }

    fun isPureObservation(toolCalls: Collection<String>): Boolean {
        if (toolCalls.isEmpty()) return false
        return toolCalls.all { it.substringBefore(':').trim() in OBSERVATION_TOOLS }
    }

    /** 观察阶梯：按连续纯观察轮数给出处置动作（RESTRICT 持续生效直到行动或终止） */
    fun escalationFor(observationStreak: Int): Escalation = when {
        observationStreak >= FORCE_FINISH_AT -> Escalation.FORCE_FINISH
        observationStreak >= RESTRICT_AT -> Escalation.RESTRICT
        observationStreak > 0 && observationStreak % NUDGE_EVERY == 0 -> Escalation.NUDGE
        else -> Escalation.NONE
    }

    fun nudgeMessage(toolCalls: Collection<String>): String =
        if (isPureObservation(toolCalls)) {
            "[系统提示] 连续多轮屏幕都没有变化。若你在等待加载/动画/跳转，请用 wait 或操作工具的 wait_after 给足等待时间后再观察一次；" +
                "若上一轮的操作没有生效，请更换方式（system_key(key=\"back\") 回退、滑动页面、重开 App）。" +
                "确实无法完成时调用 finish 说明原因。"
        } else {
            "[系统提示] 检测到你连续多轮执行了相同的操作且屏幕没有变化，你可能陷入了死循环。" +
                "请尝试完全不同的方法：按 system_key(key=\"back\") 回退、滑动页面寻找目标、或重新打开 App。" +
                "如果确实无法完成任务，请调用 finish 说明原因。"
        }

    /** 连续纯观察达到 RESTRICT_AT 时，随禁用一起注入的强提示（下一轮生效） */
    fun restrictionMessage(observationStreak: Int): String =
        "[系统强制] 你已连续 $observationStreak 轮仅观察屏幕、未执行任何推进任务的操作，" +
            "本轮 take_screenshot / find_node_info / wait 已被禁用。" +
            "请直接调用操作类工具（tap / swipe / long_press / system_key / input_text 等）执行你已计划的操作，" +
            "或调用 finish 说明原因结束任务。不允许再输出任何观察类工具调用。"

    /** 被禁用轮里模型仍输出观察工具调用时，回给它的工具错误结果 */
    fun restrictedToolError(): String =
        "{\"error\":\"观察类工具本轮不可用（防死循环限制），请改用操作类工具，或调用 finish 说明原因结束任务\",\"isSuccess\":false}"

    /** 观察工具恢复可用时注入的说明 */
    fun restoreMessage(): String =
        "[系统提示] 观察类工具（take_screenshot / find_node_info / wait）已恢复可用。请先执行操作，再观察其效果。"
}

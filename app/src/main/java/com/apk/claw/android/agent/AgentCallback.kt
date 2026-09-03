package com.apk.claw.android.agent

import com.apk.claw.android.tool.ToolResult

interface AgentCallback {
    /**
     * 新的一轮 Agent Loop 开始时的回调
     * @param round 当前轮数（从 1 开始）
     */
    fun onLoopStart(round: Int)
    fun onContent(round: Int, content: String)
    fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String)
    fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult)
    fun onComplete(round: Int, finalAnswer: String, totalTokens: Int)
    fun onError(round: Int, error: Exception, totalTokens: Int)
    fun onSystemDialogBlocked(round: Int, totalTokens: Int)

    /**
     * F4：工具执行后的结果断言在重试后仍失败。宿主据此计入遥测（任务历史 verifyFailures）。
     */
    fun onVerifyFailed(description: String) {}

    /**
     * 请求用户决策并阻塞等待（F1 系统弹窗挂起恢复 / F2 危险操作确认共用）。
     * 实现方负责经消息渠道发送 [prompt]、拦截用户回复；用户确认返回 true，
     * 取消或超过 [timeoutMs] 返回 false。不支持交互的实现直接返回 false（保持旧行为：立即终止）。
     */
    fun onAwaitUser(prompt: String, timeoutMs: Long): Boolean = false

    /**
     * 任务收尾回调：在 Agent 的 running 标志已清除、执行线程即将空闲时调用恰好一次。
     * 终止类回调（onComplete/onError 等）发生在线程真正空闲之前，依赖"已空闲"的
     * 联动（如排队消息排空）必须挂在这里，否则会与下一任务产生竞态。
     */
    fun onSettled() {}
}

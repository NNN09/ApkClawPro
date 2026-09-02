package com.apk.claw.android.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 用户决策门控：Agent 循环在需要人工介入（系统弹窗待处理、危险操作确认）时阻塞等待，
 * 用户经消息渠道回复确认/取消关键字后由 [resolve] 放行。
 *
 * 纯并发原语，不依赖 Android 环境；取消检查通过 [await] 的回调注入，避免反向依赖 Agent 内部状态。
 */
class UserDecisionGate(private val timeoutMs: Long) {

    enum class Decision { CONFIRMED, REJECTED, TIMEOUT, CANCELLED }

    private val latch = CountDownLatch(1)

    @Volatile
    private var decision: Decision? = null

    /** 用户已回复（确认或取消）。用于区分"用户已表态"与"超时/取消"。 */
    @Volatile
    var resolvedByUser: Boolean = false
        private set

    /**
     * 阻塞等待用户决策。
     * @param cancelCheck 由调用方周期性探测的外部取消条件（如 Agent 收到取消指令）
     */
    fun await(cancelCheck: () -> Boolean): Decision {
        decision?.let { return it }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            decision?.let { return it }
            if (cancelCheck()) {
                return finish(Decision.CANCELLED)
            }
            if (System.nanoTime() >= deadline) {
                return finish(Decision.TIMEOUT)
            }
            latch.await(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** 用户回复入口：confirmed=true 表示继续/确认，false 表示取消/拒绝。仅第一次生效。 */
    fun resolve(confirmed: Boolean) {
        if (decision != null) return
        resolvedByUser = true
        finish(if (confirmed) Decision.CONFIRMED else Decision.REJECTED)
    }

    private fun finish(d: Decision): Decision {
        decision = d
        latch.countDown()
        return d
    }

    companion object {
        private const val POLL_INTERVAL_MS = 200L

        /** F1：系统弹窗等待用户人工处理的最长时间 */
        const val DIALOG_WAIT_TIMEOUT_MS = 5 * 60 * 1000L

        /** F2：危险操作确认等待的最长时间 */
        const val CONFIRM_TIMEOUT_MS = 2 * 60 * 1000L
    }
}

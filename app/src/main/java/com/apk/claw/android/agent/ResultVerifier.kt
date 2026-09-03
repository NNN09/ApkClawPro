package com.apk.claw.android.agent

/**
 * F4 结果验证：核心操作类工具执行成功后回读设备状态断言，把"看起来执行了"和"确实执行了"区分开。
 * 断言失败自动重试一次，仍失败才把失败暴露给模型并计入遥测（进 F3 任务历史）。
 *
 * 断言注册是纯函数（可 JVM 测试）；轮询等待的时钟与睡眠均可注入。
 * 无法观测（探针返回空）视为跳过而非失败，避免 ROM 差异造成误报。
 */
object ResultVerifier {

    /** 设备状态探针：由 Android 侧实现，测试注入假实现 */
    interface Probe {
        /** 当前前台应用包名；无法观测返回 null */
        fun foregroundPackage(): String?
    }

    data class Assertion(
        /** 断言的可读描述，进入遥测与失败提示 */
        val describe: String,
        val check: (Probe) -> Boolean
    )

    data class Outcome(
        /** 断言最终是否通过（含"无法观测跳过"） */
        val verified: Boolean,
        /** 触发了几次工具重试（0 或 1） */
        val retries: Int
    )

    /**
     * 返回该次工具调用的断言；无需验证的工具返回 null。
     * 当前只对 open_app 做前台包名断言——它是唯一"结果可精确回读"的操作；
     * tap/swipe 等坐标操作的"界面变化"断言误报率高，待实测后再加入。
     */
    fun assertionFor(toolName: String, params: Map<String, Any>): Assertion? {
        if (toolName != "open_app") return null
        val pkg = params["package_name"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return Assertion("前台包名=$pkg") { probe ->
            val foreground = probe.foregroundPackage() ?: return@Assertion true
            foreground == pkg
        }
    }

    /**
     * 轮询断言直至通过、超时、或重试后仍失败。
     * @param retry 首次超时后重执行工具的回调；null 表示不重试
     */
    fun verify(
        probe: Probe,
        check: (Probe) -> Boolean,
        retry: (() -> Unit)? = null,
        pollIntervalMs: Long = 500,
        timeoutMs: Long = 4000,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        nowMs: () -> Long = System::currentTimeMillis
    ): Outcome {
        if (pollUntil(probe, check, nowMs() + timeoutMs, pollIntervalMs, sleep, nowMs)) {
            return Outcome(verified = true, retries = 0)
        }
        if (retry != null) {
            retry()
            if (pollUntil(probe, check, nowMs() + timeoutMs, pollIntervalMs, sleep, nowMs)) {
                return Outcome(verified = true, retries = 1)
            }
        }
        return Outcome(verified = false, retries = if (retry != null) 1 else 0)
    }

    private fun pollUntil(
        probe: Probe,
        check: (Probe) -> Boolean,
        deadlineMs: Long,
        pollIntervalMs: Long,
        sleep: (Long) -> Unit,
        nowMs: () -> Long
    ): Boolean {
        while (true) {
            if (check(probe)) return true
            if (nowMs() >= deadlineMs) return false
            sleep(pollIntervalMs)
        }
    }
}

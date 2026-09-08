package com.apk.claw.android.agent

/**
 * LLM API 错误分类：不同性质的错误要不同对待——
 * 重试策略（确定性错误重试是纯烧时间）与视觉降级（图片数超限 ≠ 模型不支持视觉）都依赖它。
 *
 * 真机实证（2026-09-07，nnn09.top one-api）：任务累计 51 张截图触发
 * `500 {"error":{"message":"image num 51 exceeds limit 50","type":"invalid_request_error"}}`，
 * 旧逻辑一律剥光全部图片重试并向用户广播"模型不接受图像输入"——实际模型 50 张内全程正常读图。
 */
enum class LlmErrorKind {

    /** 图片数超限：丢弃最旧的几张图、带图重试即可恢复，不能放弃视觉 */
    IMAGE_LIMIT,

    /** 确定性拒绝（参数/配额/认证/内容超限）：重试必然同结果，必须立即走对应兜底 */
    NON_RETRYABLE,

    /** 暂时性故障（超时/网关抖动/限流）：值得退避重试 */
    RETRYABLE;

    companion object {

        private val IMAGE_LIMIT_MARKERS = listOf(
            "image num", "exceeds limit", "too many images", "image count"
        )

        private val NON_RETRYABLE_MARKERS = listOf(
            "invalid_request_error", "invalid_request", "context length", "context_length",
            "maximum context", "max_tokens", "reduce the length",
            "401", "403", "404", "413", "422", "insufficient"
        )

        /**
         * 按异常 message（langchain4j 的 HttpException 会带上服务端响应 body）分类。
         * 顺序敏感：图片超限的 body 通常也带 invalid_request_error，必须先判。
         */
        fun classify(message: String?): LlmErrorKind {
            val msg = message?.lowercase() ?: return RETRYABLE
            if (IMAGE_LIMIT_MARKERS.any { msg.contains(it) }) return IMAGE_LIMIT
            if (NON_RETRYABLE_MARKERS.any { msg.contains(it) }) return NON_RETRYABLE
            return RETRYABLE
        }
    }
}

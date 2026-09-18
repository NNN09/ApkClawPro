package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.DefaultAgentService
import com.apk.claw.android.agent.LlmProvider
import com.apk.claw.android.agent.langchain.http.OkHttpClientBuilderAdapter
import java.time.Duration

object LlmClientFactory {

    /**
     * LLM 请求超时。注意：langchain4j 模型构造时会无条件用模型自身的 timeout
     * 覆盖 [OkHttpClientBuilderAdapter] 的 readTimeout 默认值（1.12.2：
     * `readTimeout(getOrDefault(builder.timeout, ofSeconds(60)))`），所以该值
     * 必须通过各模型 builder 的 .timeout() 传入才生效，改 adapter 默认值无效。
     * 取 5 分钟：实测中转站最慢的成功请求 ~50s，留 6 倍余量已够；60s 默认值会
     * 连环超时重试烧掉任务轮次（2026-09-11 事故：单任务 5 次 60s 超时），
     * 而更长的总超时只会让真挂死的连接等更久才失败。
     */
    val REQUEST_TIMEOUT: Duration = Duration.ofMinutes(5)

    fun create(config: AgentConfig): LlmClient {
        val httpClientBuilder = OkHttpClientBuilderAdapter().apply {
            if (DefaultAgentService.FILE_LOGGING_ENABLED && DefaultAgentService.FILE_LOGGING_CACHE_DIR != null) {
                setFileLoggingEnabled(true, DefaultAgentService.FILE_LOGGING_CACHE_DIR)
            }
        }
        return when (config.provider) {
            LlmProvider.OPENAI -> OpenAiLlmClient(config, httpClientBuilder)
            LlmProvider.ANTHROPIC -> AnthropicLlmClient(config, httpClientBuilder)
        }
    }
}

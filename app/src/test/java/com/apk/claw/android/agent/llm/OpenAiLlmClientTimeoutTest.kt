package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.langchain.http.OkHttpClientBuilderAdapter
import dev.langchain4j.http.client.HttpClientBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

/**
 * LLM 请求超时贯通验证：langchain4j 模型构造时会用自身 timeout 覆盖
 * [OkHttpClientBuilderAdapter] 的 readTimeout 默认值（1.12.2 实证：
 * `readTimeout(getOrDefault(builder.timeout, ofSeconds(60)))`），
 * 所以超时必须设在模型 builder 的 .timeout() 上才生效。
 * 回归背景：中转站带图大请求延迟常态 >60s，默认 60s 超时导致连环重试烧掉任务轮次（2026-09-11 事故）。
 */
class OpenAiLlmClientTimeoutTest {

    private class RecordingAdapter : OkHttpClientBuilderAdapter() {
        var lastReadTimeout: Duration? = null

        override fun readTimeout(readTimeout: Duration): HttpClientBuilder {
            lastReadTimeout = readTimeout
            return super.readTimeout(readTimeout)
        }
    }

    private fun recordingClient(): Pair<OpenAiLlmClient, RecordingAdapter> {
        val adapter = RecordingAdapter()
        val config = AgentConfig(apiKey = "sk-test", baseUrl = "http://127.0.0.1:1")
        return OpenAiLlmClient(config, adapter) to adapter
    }

    @Test
    fun `chat model read timeout equals configured request timeout`() {
        val (client, adapter) = recordingClient()
        client.chatModel // 触发 lazy 构建，langchain4j 此时覆盖 adapter 超时
        assertEquals(LlmClientFactory.REQUEST_TIMEOUT, adapter.lastReadTimeout)
    }

    @Test
    fun `streaming model read timeout equals configured request timeout`() {
        val (client, adapter) = recordingClient()
        client.streamingChatModel
        assertEquals(LlmClientFactory.REQUEST_TIMEOUT, adapter.lastReadTimeout)
    }

    @Test
    fun `request timeout is 5 minutes`() {
        assertEquals(Duration.ofMinutes(5), LlmClientFactory.REQUEST_TIMEOUT)
    }
}

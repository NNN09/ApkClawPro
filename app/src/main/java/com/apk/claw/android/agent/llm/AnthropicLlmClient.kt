package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.langchain.http.OkHttpClientBuilderAdapter
import com.apk.claw.android.agent.store.ContextBudget
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class AnthropicLlmClient(
    private val config: AgentConfig,
    private val httpClientBuilder: OkHttpClientBuilderAdapter
) : LlmClient {

    private val chatModel: ChatModel by lazy { buildChatModel(config.modelName) }
    private val streamingChatModel: StreamingChatModel by lazy { buildStreamingChatModel(config.modelName) }

    // 独立视觉模型：带图请求路由到单独的模型实例，按模型名缓存（未启用时永不触碰）
    private val visionChatModels = ConcurrentHashMap<String, ChatModel>()
    private val visionStreamingChatModels = ConcurrentHashMap<String, StreamingChatModel>()

    private fun chatModelFor(messages: List<ChatMessage>): ChatModel =
        config.effectiveVisionModel(ContextBudget.hasImages(messages))
            ?.let { visionChatModels.computeIfAbsent(it) { name -> buildChatModel(name) } }
            ?: chatModel

    private fun streamingChatModelFor(messages: List<ChatMessage>): StreamingChatModel =
        config.effectiveVisionModel(ContextBudget.hasImages(messages))
            ?.let { visionStreamingChatModels.computeIfAbsent(it) { name -> buildStreamingChatModel(name) } }
            ?: streamingChatModel

    private fun buildChatModel(modelName: String): ChatModel {
        val builder = AnthropicChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .apiKey(config.apiKey)
            .modelName(modelName)
            .temperature(config.temperature)
            // ③ 与 OpenAi 客户端同理：重试策略统一上收到 chatWithRetry 的错误分类
            .maxRetries(1)
        if (config.baseUrl.isNotEmpty()) {
            builder.baseUrl(config.baseUrl)
        }
        return builder.build()
    }

    private fun buildStreamingChatModel(modelName: String): StreamingChatModel {
        val builder = AnthropicStreamingChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .apiKey(config.apiKey)
            .modelName(modelName)
            .temperature(config.temperature)
        if (config.baseUrl.isNotEmpty()) {
            builder.baseUrl(config.baseUrl)
        }
        return builder.build()
    }

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        val request = ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolSpecs)
            .build()
        val response = chatModelFor(messages).chat(request)
        return response.toLlmResponse()
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener
    ): LlmResponse {
        val request = ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolSpecs)
            .build()

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<LlmResponse>()
        val errorRef = AtomicReference<Throwable>()

        streamingChatModelFor(messages).chat(request, object : StreamingChatResponseHandler {
            override fun onPartialResponse(token: String) {
                listener.onPartialText(token)
            }

            override fun onCompleteResponse(response: ChatResponse) {
                val llmResponse = response.toLlmResponse()
                resultRef.set(llmResponse)
                listener.onComplete(llmResponse)
                latch.countDown()
            }

            override fun onError(error: Throwable) {
                errorRef.set(error)
                listener.onError(error)
                latch.countDown()
            }
        })

        latch.await()
        errorRef.get()?.let { throw it }
        return resultRef.get()
    }
}

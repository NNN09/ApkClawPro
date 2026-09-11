package com.apk.claw.android.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 非流式请求的 reasoning 捕获通道（ThreadLocal）。
 * OkHttpClientAdapter.execute（同步，调用线程）在响应返回前 set，
 * OpenAiLlmClient 在同一调用线程 poll。流式路径走 OkHttp 线程池，
 * 线程不对齐，捕获为 null（流式当前未启用，无损）。
 * 字段名双认：reasoning（sensenova 等）/ reasoning_content（DeepSeek 等）。
 */
public final class ReasoningCapture {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private ReasoningCapture() {
    }

    /** 请求发起前清理本线程残留，防止线程池复用导致陈旧值串味 */
    public static void clear() {
        HOLDER.remove();
    }

    /** HTTP 层在模型返回后调用；value 为 null 表示本次无可捕获 reasoning */
    public static void set(String value) {
        HOLDER.set(value);
    }

    /** LLM 客户端在模型返回后取走并清空 */
    public static String poll() {
        String value = HOLDER.get();
        HOLDER.remove();
        return value;
    }

    /** 从 OpenAI 兼容 chat.completion 响应体抽取 reasoning 文本；SSE/异常结构返回 null */
    public static String extract(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null) {
                return null;
            }
            for (String field : new String[]{"reasoning", "reasoning_content"}) {
                if (message.has(field) && !message.get(field).isJsonNull()) {
                    return message.get(field).getAsString();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}

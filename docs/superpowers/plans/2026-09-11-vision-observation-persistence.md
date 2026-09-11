# 视觉观察持久化 + 占位文案中性化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 堵住"视觉模型观察只存在于 reasoning 字段、截图折叠后知识随图蒸发"的结构性缺口，并中性化模型可见的占位文案，杜绝弱模型向用户复述"截图被系统移除"。

**Architecture:** 在 OkHttp 适配层（同步路径）从响应体零开销抽取 reasoning → ThreadLocal 通道递给 `LlmResponse.reasoning` → `durableText()` 在 content 空白时把 reasoning 截断转录为可见文本 → `DefaultAgentService` 持久化 AiMessage 时统一走 `durableText()`。同时修改 3 条模型可见文案为描述式（去"移除/被系统"语域）。

**Tech Stack:** Kotlin + Java（langchain/http 层）、Gson（已在依赖）、JUnit4 单测、langchain4j OpenAI 兼容客户端（中转站 nnn09.top）。

**Spec:** 本会话 2026-09-11 排障结论——记忆训练任务中 sensenova-6.8-flash-lite 把 4×4 牌面观察全写进 `reasoning`（`content` 仅 `"\n\n"`），`LlmResponse` 丢弃该字段（全库无 reasoning 处理），`foldConsumedImages` 逐轮折叠截图后，模型上下文只剩 3 条"图像内容已移除"占位 + 自己的空文本，遂向用户复述"视觉识别工具返回的图像内容被系统移除"并放弃任务（http_logs 20260911_082503382/082511184/082514557/082541681 实证）。

## Claude Code 对照表（设计依据）

| 设计点 | Claude Code / Anthropic 做法 | 本方案 | 结论 |
|---|---|---|---|
| 观察持久化 | 不变量：助手可见文本是唯一持久记录，context editing 只清 tool result，从不清 assistant 文本 | `durableText()` 保证观察必然落入 AiMessage 可见文本 | 对齐，补上被违反的不变量 |
| 占位措辞 | "Media removed from prior tool result to reduce context"：描述式、限定过去、给上下文经济学理由 | "该截图原图已省略，识别结论已记录在上方助手回复文字中" | 对齐（中文、更短） |
| thinking/reasoning | thinking 仅在当前工具循环回传，持久记录就是文本 | reasoning 转录后持久化 | 有意偏离：OpenAI 系模型会把观察放 reasoning，必须兼容 |
| 识别轮后立即折叠 | 阈值触发（默认 100k tokens）+ keep 最近 3 对 tool use | 保留立即折叠（f435875 视觉路由产品决策） | 有意偏离：省视觉 token；①弥补知识损失后风险可控 |
| 折叠可逆性 | 客户端保全量历史，请求时动态清除 | 原地折叠、不可逆 | 有意偏离（YAGNI：截图可重拍，缓存成本高） |

## Global Constraints

- 不新增第三方依赖（Gson / OkHttp / langchain4j 均已在）。
- 流式路径（`config.streaming`）当前未启用（真机日志证实非流式），reasoning 捕获只承诺非流式；流式入口做 `clear()` 卫生清理但捕获结果不承诺。
- ②只改**模型可见**文案；`strings.xml` 的 `channel_msg_vision_degraded` 是用户通知、模型看不到，不动。
- Anthropic 客户端 `reasoning` 保持 null（thinking 捕获不在本次范围，中转站走 OpenAI 兼容协议）。
- 全部单测保持绿（现基线 287）。单测命令（Git Bash）：`./gradlew :app:testDebugUnitTest`。
- 真机验证**必须开新会话**：旧 WECHAT 会话的 turns/digest 已被"视觉被系统移除"的错误结论污染，旧会话验证会出现"修了还是坏"的假象。
- 提交按任务粒度，遵循现有 `feat:`/`fix:` 前缀惯例。

---

### Task 1: `LlmResponse.reasoning` + `durableText()`（观察必须落在可见文本）

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/agent/llm/LlmResponse.kt`
- Test: `app/src/test/java/com/apk/claw/android/agent/llm/LlmResponseTest.kt`（新建）

**Interfaces:**
- Produces: `LlmResponse(text: String?, reasoning: String? = null, toolExecutionRequests: List<ToolExecutionRequest>, tokenUsage: TokenUsage? = null)`；`fun durableText(): String?`；`const val REASONING_MAX_CHARS = 800`。Task 3/4 依赖这两个名字。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.apk.claw.android.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmResponseTest {

    @Test
    fun durableText_prefersVisibleContent() {
        val r = LlmResponse(text = "我看到 4x4 网格", reasoning = "Let me analyze", toolExecutionRequests = emptyList())
        assertEquals("我看到 4x4 网格", r.durableText())
    }

    @Test
    fun durableText_transcribesBlankContentReasoning() {
        // 2026-09-11 事故形态：sensenova 把观察写进 reasoning，content 只有空白
        val r = LlmResponse(text = "\n\n", reasoning = "第4行第3列是A", toolExecutionRequests = emptyList())
        assertEquals("[视觉观察记录] 第4行第3列是A", r.durableText())
    }

    @Test
    fun durableText_capsLongReasoning() {
        val r = LlmResponse(text = null, reasoning = "x".repeat(2000), toolExecutionRequests = emptyList())
        val d = r.durableText()!!
        assertTrue(d.startsWith("[视觉观察记录] "))
        assertTrue(d.endsWith("…[截断]"))
        assertEquals("[视觉观察记录] ".length + LlmResponse.REASONING_MAX_CHARS + "…[截断]".length, d.length)
    }

    @Test
    fun durableText_nullWhenBothBlank() {
        val r = LlmResponse(text = "  \n", reasoning = "   ", toolExecutionRequests = emptyList())
        assertNull(r.durableText())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.llm.LlmResponseTest"`
Expected: 编译失败（`reasoning` 参数不存在）。

- [ ] **Step 3: 最小实现**

`LlmResponse.kt` 全文替换为：

```kotlin
package com.apk.claw.android.agent.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.model.output.TokenUsage

data class LlmResponse(
    val text: String?,
    val reasoning: String? = null,
    val toolExecutionRequests: List<ToolExecutionRequest>,
    val tokenUsage: TokenUsage? = null
) {
    fun hasToolExecutionRequests(): Boolean = toolExecutionRequests.isNotEmpty()

    /**
     * 应当持久化进对话历史的可见文本（"观察必须落在可见文本"不变量）。
     * content 为空白而 reasoning 有内容时（部分模型把观察写进 reasoning 草稿），
     * 把 reasoning 转录为可见文本——否则截图折叠后视觉知识随图丢失，
     * 模型会断言"从没看到过"并向用户复述占位文案（2026-09-11 事故）。
     * reasoning 是草稿（可能冗长、绕圈），截断加标记防止撑爆字符预算/污染主模型。
     */
    fun durableText(): String? {
        if (!text.isNullOrBlank()) return text
        val draft = reasoning?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val capped = if (draft.length > REASONING_MAX_CHARS) draft.take(REASONING_MAX_CHARS) + "…[截断]" else draft
        return "[视觉观察记录] $capped"
    }

    companion object {
        const val REASONING_MAX_CHARS = 800
    }
}
```

现有构造点（`OpenAiLlmClient.toLlmResponse`、`AnthropicLlmClient.toLlmResponse`）都是命名参数，新增带默认值的 `reasoning` 不破坏编译。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.llm.LlmResponseTest"`
Expected: PASS（4 个测试）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/apk/claw/android/agent/llm/LlmResponse.kt app/src/test/java/com/apk/claw/android/agent/llm/LlmResponseTest.kt
git commit -m "feat: LlmResponse.reasoning + durableText（观察必须落在可见文本）"
```

---

### Task 2: `ReasoningCapture`——HTTP 层抽取 reasoning 并经 ThreadLocal 递给客户端

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/agent/llm/ReasoningCapture.java`
- Modify: `app/src/main/java/com/apk/claw/android/agent/langchain/http/OkHttpClientAdapter.java:40-53`（同步 `execute`）与 `:56-57`（流式入口）
- Test: `app/src/test/java/com/apk/claw/android/agent/llm/ReasoningCaptureTest.kt`（新建）

**Interfaces:**
- Consumes: 无。
- Produces: `ReasoningCapture.clear()` / `set(String)` / `poll(): String` / `extract(String): String`（Java 静态方法，供 Java 适配层与 Kotlin 客户端共用）。Task 3 依赖 `poll()` 与 `extract()`。

**设计要点（为什么是这个挂点）：** `OkHttpClientAdapter.execute()` 是同步调用（`okHttpClient.newCall(okRequest).execute()`，运行在调用线程），且 `toSuccessfulResponse` 本来就把 body 读成 String——在它之后 `set(extract(body))` 是零额外 IO，且 ThreadLocal 与 `OpenAiLlmClient` 同线程精确对齐。不挂 `FileLoggingInterceptor`：那层受 fileLoggingEnabled 开关控制，release 关日志会顺带杀死知识持久化。流式 `execute(request, parser, listener)` 走 `enqueue`（OkHttp 线程池），线程不对齐，只做入口 `clear()` 防线程池复用串味。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.apk.claw.android.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningCaptureTest {

    @Test
    fun extract_readsReasoningField() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"\n\n","reasoning":"第4行第3列是A"}}]}"""
        assertEquals("第4行第3列是A", ReasoningCapture.extract(body))
    }

    @Test
    fun extract_fallsBackToReasoningContent() {
        val body = """{"choices":[{"message":{"content":"","reasoning_content":"深度思考"}}]}"""
        assertEquals("深度思考", ReasoningCapture.extract(body))
    }

    @Test
    fun extract_prefersReasoningOverReasoningContent() {
        val body = """{"choices":[{"message":{"reasoning":"a","reasoning_content":"b"}}]}"""
        assertEquals("a", ReasoningCapture.extract(body))
    }

    @Test
    fun extract_returnsNullForSseMalformedOrMissing() {
        assertNull(ReasoningCapture.extract("data: {\"choices\":[]}\n\n"))
        assertNull(ReasoningCapture.extract(""))
        assertNull(ReasoningCapture.extract(null))
        assertNull(ReasoningCapture.extract("{\"choices\":[]}"))
    }

    @Test
    fun poll_returnsValueThenClears() {
        ReasoningCapture.set("观察")
        assertEquals("观察", ReasoningCapture.poll())
        assertNull(ReasoningCapture.poll())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.llm.ReasoningCaptureTest"`
Expected: 编译失败（`ReasoningCapture` 不存在）。

- [ ] **Step 3: 最小实现**

新建 `app/src/main/java/com/apk/claw/android/agent/llm/ReasoningCapture.java`：

```java
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
```

`OkHttpClientAdapter.java` 两处修改——加 import：

```java
import com.apk.claw.android.agent.llm.ReasoningCapture;
```

同步 `execute`（原 40-53 行）改为：

```java
    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException, RuntimeException {
        ReasoningCapture.clear();
        Request okRequest = toOkHttpRequest(request);
        try (Response response = okHttpClient.newCall(okRequest).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new HttpException(response.code(), body);
            }
            // 内联读 body（原来在 toSuccessfulResponse 里），抽取零额外 IO；
            // 同步 execute 在调用线程运行，ThreadLocal 与 OpenAiLlmClient 的 poll 同线程对齐
            String body = response.body() != null ? response.body().string() : "";
            SuccessfulHttpResponse success = SuccessfulHttpResponse.builder()
                    .statusCode(response.code())
                    .headers(toHeaderMap(response))
                    .body(body)
                    .build();
            ReasoningCapture.set(ReasoningCapture.extract(body));
            return success;
        } catch (HttpException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
        }
    }
```

流式 `execute(request, parser, listener)` 方法体第一行加：

```java
        ReasoningCapture.clear();
```

同步 `execute` 内联读 body 后，私有方法 `toSuccessfulResponse`（原 140-147 行）不再有调用方，**整段删除**：

```java
    // 删除：private SuccessfulHttpResponse toSuccessfulResponse(Response response) throws IOException { ... }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.llm.ReasoningCaptureTest"`
Expected: PASS（5 个测试）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/apk/claw/android/agent/llm/ReasoningCapture.java app/src/main/java/com/apk/claw/android/agent/langchain/http/OkHttpClientAdapter.java app/src/test/java/com/apk/claw/android/agent/llm/ReasoningCaptureTest.kt
git commit -m "feat: ReasoningCapture 捕获非流式响应 reasoning（HTTP 层零额外 IO）"
```

---

### Task 3: 客户端接线 + AiMessage 持久化走 `durableText()`

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/agent/llm/OpenAiLlmClient.kt:116-123`（`toLlmResponse`）
- Modify: `app/src/main/java/com/apk/claw/android/agent/DefaultAgentService.kt:512-522`（AiMessage 构造）

**Interfaces:**
- Consumes: Task 1 的 `LlmResponse(text, reasoning, …)` 命名参数与 `durableText()`；Task 2 的 `ReasoningCapture.poll()`。
- Produces: 无新接口（行为修复）。

- [ ] **Step 1: `toLlmResponse` 接入 poll**

`OpenAiLlmClient.kt` 末尾的扩展函数改为：

```kotlin
internal fun ChatResponse.toLlmResponse(): LlmResponse {
    val aiMessage = aiMessage()
    return LlmResponse(
        text = aiMessage.text(),
        // 非流式：poll 与 HTTP 层 set 同线程对齐；流式路径线程不对齐，拿到 null 属预期
        reasoning = ReasoningCapture.poll(),
        toolExecutionRequests = aiMessage.toolExecutionRequests() ?: emptyList(),
        tokenUsage = tokenUsage()
    )
}
```

（`AnthropicLlmClient` 有自己的 `toLlmResponse`，不动——其 thinking 捕获不在本次范围，见 Global Constraints。）

- [ ] **Step 2: AiMessage 构造改用 `durableText()`**

`DefaultAgentService.kt` 原 512-522 行：

```kotlin
            // 将 AI 消息添加到历史（需要构造 AiMessage）
            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                if (llmResponse.text.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.text ?: "")
            }
            messages.add(aiMessage)
```

替换为：

```kotlin
            // 将 AI 消息添加到历史（需要构造 AiMessage）。
            // durableText：content 空白而 reasoning 有观察时转录为可见文本——
            // "观察必须落在可见文本"，否则截图折叠后视觉知识随图丢失（2026-09-11 事故）
            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                val visible = llmResponse.durableText()
                if (visible.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(visible, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.durableText() ?: "")
            }
            messages.add(aiMessage)
```

注：`onContent`（约 532 行）与 `onComplete`（约 538 行）继续用 `llmResponse.text`，不改——本次故障经 finish 工具收尾，摘要在其参数里；无工具调用的空白 text + 有 reasoning 场景未观测到，记入"不做"清单。

- [ ] **Step 3: 全量单测回归**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS（无测试断言此处行为，属纯行为修复）。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/apk/claw/android/agent/llm/OpenAiLlmClient.kt app/src/main/java/com/apk/claw/android/agent/DefaultAgentService.kt
git commit -m "fix: AiMessage 持久化走 durableText，堵视觉观察随截图折叠而丢失"
```

---

### Task 4: 模型可见占位文案整族中性化

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/agent/store/ContextBudget.kt:119-121`（`foldConsumedImages` 占位）
- Modify: `app/src/main/java/com/apk/claw/android/agent/store/ContextBudget.kt:188-190`（`stripImages` 占位）
- Modify: `app/src/main/java/com/apk/claw/android/agent/LoopGuard.kt:61-63`（`restrictedToolError`）
- Test: `app/src/test/java/com/apk/claw/android/agent/store/ContextBudgetTest.kt`（追加断言）

**Interfaces:**
- Consumes: 无。Produces: 无（文案变更 + 回归护栏测试）。

**改动原则：** 描述式、陈述过去已完成的上下文管理动作、指向知识的现存位置；禁用"被系统移除/被系统禁用"这类会被弱模型原话复述的主谓断言与命令式从句。`FOLDED_PLACEHOLDER`（"早期截图已省略"）、`ScreenshotEncoder.disabledMessage/budgetOmittedMessage`（本就描述式）不动。

- [ ] **Step 1: 追加回归护栏测试（先失败）**

`ContextBudgetTest.kt` 追加：

```kotlin
    @Test
    fun foldConsumedImages_placeholderIsDescriptiveNotParrotable() {
        // 占位文案不得含"已移除/重新调用"类可被弱模型复述的断言与指令（2026-09-11 事故）
        val msgs = mutableListOf<ChatMessage>(imageMessage("shot"))
        ContextBudget.foldConsumedImages(msgs)
        val text = (msgs[0] as UserMessage).singleText()
        assertFalse(text.contains("已移除"))
        assertFalse(text.contains("重新调用"))
        assertTrue(text.contains("已省略"))
    }
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.store.ContextBudgetTest"`
Expected: FAIL（现文案含"已移除"与"重新调用"）。

- [ ] **Step 2: 改三条文案**

`ContextBudget.kt` `foldConsumedImages`（原 119-121 行）：

```kotlin
                messages[i] = UserMessage.from(
                    caption + "\n[系统提示] 该截图原图已省略，视觉识别的结论已记录在上方的助手回复文字中。"
                )
```

`ContextBudget.kt` `stripImages`（原 188-190 行）：

```kotlin
                messages[i] = UserMessage.from(
                    "[系统提示] 当前模型不支持视觉输入，截图未附上；屏幕状态请通过 get_screen_info 获取。"
                )
```

（此处保留功能性指引：真实降级场景里模型必须换观察方式，指引是事实而非虚构命令。）

`LoopGuard.kt` `restrictedToolError`（原 61-63 行）：

```kotlin
    fun restrictedToolError(): String =
        "{\"error\":\"观察类工具本轮不可用（防死循环限制），请改用操作类工具，或调用 finish 说明原因结束任务\",\"isSuccess\":false}"
```

- [ ] **Step 3: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.store.ContextBudgetTest" --tests "com.apk.claw.android.agent.LoopGuardTest"`
Expected: PASS。`LoopGuardTest` 现无 `restrictedToolError` 文案断言（已核实），无需改动；`ContextBudgetTest` 现有断言只查 caption 保留与返回值，与文案变更不冲突。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/apk/claw/android/agent/store/ContextBudget.kt app/src/main/java/com/apk/claw/android/agent/LoopGuard.kt app/src/test/java/com/apk/claw/android/agent/store/ContextBudgetTest.kt
git commit -m "fix: 模型可见占位文案中性化，防弱模型复述\"被系统移除\"类断言"
```

---

### Task 5: 全量回归 + 真机验证（新会话）

**Files:** 无新改动（验证任务）。

- [ ] **Step 1: 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS（基线 287 + 新增约 10 个）。

- [ ] **Step 2: 构建并部署 debug 包**

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: 新会话真机验证（关键：必须新会话，旧会话已被污染）**

在应用内开启**新会话**，下发同样的记忆训练任务（4×4 翻牌配对，要求记住卡片文字）。观察：

1. 模型在首次视觉识别后**直接 tap 目标卡**（不再连拍 3 张相同截图）；
2. 不再出现"截图被系统移除"类表述，任务正常推进或按真实原因收尾。

- [ ] **Step 4: http_logs 物证核验**

```bash
adb exec-out "run-as com.apk.claw.android tar -cf - cache/http_logs -C /data/data/com.apk.claw.android" | tar -xf - -C /tmp/vv
# 取最新一轮带图请求：确认上一轮视觉模型的 reasoning 已以 [视觉观察记录] 进入 AiMessage 历史
grep -l "视觉观察记录" /tmp/vv/cache/http_logs/*.txt | tail -3
# 确认请求体中不再出现旧占位断言
grep -c "已移除" /tmp/vv/cache/http_logs/$(ls /tmp/vv/cache/http_logs | tail -1)
```

Expected: 第 1 条列出视觉轮之后的请求；第 2 条计数为 0。

- [ ] **Step 5: 结果记录**

把验证结论（新会话任务轨迹 + http_logs 证据）回报给用户；如有异常回到 systematic-debugging Phase 1，不得在未定位前追加修复。

---

## 明确不做（YAGNI / 有意偏离）

- **保留最近 1-2 张旧图不折叠（原③）**：与 `hasImages` 路由谓词冲突（每轮都会带图→每轮走视觉模型），正确实现需新增"未消费新图"状态追踪；①落地后收益趋零。
- **onComplete 空白 text + 有 reasoning 的收尾转录**：未观测到该故障形态，finish 工具是当前收尾主路径；留待实际出现再做。
- **finish 摘要的幻觉校验**：按既定原则（模型能力问题不加代码补偿），①+②铲除诱发土壤即可。
- **Anthropic thinking 捕获**：中转站走 OpenAI 兼容协议，无现实需求。
- **占位文案国际化对齐**：系统提示词现为中文单语，与现状一致。

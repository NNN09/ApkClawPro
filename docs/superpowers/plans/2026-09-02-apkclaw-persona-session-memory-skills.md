# ApkClaw 人格 / 连续对话 / 长期记忆 / 技能文件系统 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 ApkClaw（Android 端单机 AI Agent）上叠加四个能力：可配置人格、按用户延续的多轮会话、跨会话长期记忆、技能文件系统（.md 技能包，按需加载）。

**Architecture:** 新增 `agent/store/` 纯 Kotlin 存储层（人格/会话/记忆/技能四个 store，全部基于 `filesDir/agent/` 下的普通文件，不依赖 Android 类，可用 JVM 单元测试）；新增 `PromptComposer` 统一组装系统提示词（人格 → 执行协议 → 记忆 → 技能目录 → 设备上下文，顺序固定）；通过现有 `BaseTool` 注册制扩展 4 个记忆/技能工具；把发送者 ID 从渠道 handler 透传到 Agent 循环作为会话主键。

**Tech Stack:** Kotlin + Java 混编（store 用 Kotlin，工具实现用 Java，与现有 `tool/impl` 目录一致）、Gson（已有依赖）、JUnit 4（已有 `testImplementation(libs.junit)`）、NanoHTTPD（已有，扩展 LAN 配置 API）。**不新增任何第三方依赖。**

**Spec:** 需求来自 2026-09-02 会话中用户确认的范围："记忆/人格/连续对话改造 + 技能文件系统"。关键决策（已锁定）：
1. 人格是**附加段**而非替换 `DEFAULT_SYSTEM_PROMPT`，协议与安全约束永远在后且优先级更高；
2. 会话键 = `(渠道枚举, 发送者ID)`，只保存"用户消息 + 最终回复"对（不存工具调用轮次），最多 10 轮，30 分钟超时，支持"新对话 / /new"命令重置；
3. 长期记忆 = `memory.md` 行式条目（`- [yyyy-MM-dd HH:mm] 文本`，人类可直接编辑），上限 50 条 FIFO，由 LLM 通过 `memory_save / memory_delete / memory_list` 三个工具自主维护；
4. 技能 = `skills/<name>/SKILL.md`（frontmatter 含 name/description），系统提示词只注入目录（name+description），全文由 `load_skill` 工具按需加载（渐进披露，控制 token）；
5. 任务忙时新消息进有界队列（最多 3 条）而非直接拒绝；
6. Home 键重置延迟到"第一个改状态工具执行前"触发，纯聊天不再重置手机状态。

## Global Constraints

- 平台：minSdk 28 / compileSdk 36 / Java 17；`./gradlew assembleDebug` 必须通过。
- 不新增第三方依赖；只用 Gson、Kotlin stdlib、java.io。
- 所有新存储位于 `filesDir/agent/` 目录：`persona.md`、`sessions.json`、`memory.md`、`skills/<name>/SKILL.md`。
- `agent/store/` 下的类**禁止 import 任何 Android 类**（android.* / com.blankj.*），保证 `./gradlew :app:testDebugUnitTest` 可直接跑 JVM 单测。需要 Android 的逻辑（设备上下文）留在 DefaultAgentService 里以参数传入。
- 系统提示词组装顺序固定：人格 → 执行协议 → 记忆 → 技能目录 → 设备上下文；人格段后必须附带"不得覆盖协议与安全约束"的声明。
- 单任务锁语义（tryAcquireTask/releaseTask）保持不变；排队只是入口处的缓冲。
- 单测命令统一为：`cd D:/code/ApkClaw && ./gradlew :app:testDebugUnitTest --tests "<完全限定类名>"`
- 每个任务结束跑一次 `./gradlew assembleDebug` 确认编译，然后提交（conventional commit：`feat:`/`test:`/`docs:`）。
- 面向用户的回复文案走 `R.string` 资源（`values/strings.xml` 英文 + `values-zh/strings.xml` 中文），与现有代码一致。

## 现状锚点（执行者必读，行号为 2026-09-02 main 分支 `1b8d40f`）

| 锚点 | 位置 | 说明 |
|------|------|------|
| 系统提示词组装 | `DefaultAgentService.kt:321` | `config.systemPrompt + buildDeviceContext()`，将被 PromptComposer 替换 |
| 消息列表构建 | `DefaultAgentService.kt:323-325` | 每任务全新 `[SystemMessage, UserMessage]`，历史注入点在两者之间 |
| executeTask 签名 | `AgentService.kt:6` | `executeTask(userPrompt: String, callback)`，将改为 `TaskRequest` |
| pressHome | `TaskOrchestrator.kt:132` | 任务开始即重置，将移除并延迟触发 |
| onComplete | `TaskOrchestrator.kt:187-194` | 会话回合记录点 |
| 消息入口 | `ChannelSetup.kt:31-44` | 重置命令、排队逻辑的插入点 |
| dispatchMessage | `ChannelManager.kt:218-220` | 加 senderId 参数 |
| 6 个 dispatch 调用点 | DingTalk:71 / FeiShu:62 / QQ:49 / Discord:40 / Telegram:101 / WeChat:237 | 各自作用域内已有发送者变量（见 Task 1） |
| 工具注册 | `ToolRegistry.kt:28-41` | `registerCommonTools()` 末尾追加 |
| wait_after 排除列表 | `BaseTool.kt:39` | 新工具名要加进去 |
| App 初始化 | `ClawApplication.kt:32-33` | KVUtils.init 之后是 store init 的插入点 |
| LAN 路由表 | `ConfigServer.kt:44-53` | 新增 /api/persona、/api/skills |
| LAN 页面 | `assets/web/index.html`（463 行） | `load()` 在 :392，`save()` 在 :416，均为 Promise.all fetch 模式 |
| 现有测试 | `app/src/test/.../ExampleUnitTest.kt` | JUnit 4，测试源集可用 |
| ToolResult API | `ToolResult.kt` | `ToolResult.success(data: String)` / `ToolResult.error(error: String)` |
| ToolParameter 构造 | `BaseTool.kt:20-25` | `ToolParameter(name, type, description, required)` |

## 参考架构：Claude Code 模式对照

本计划多处设计有意对齐 Claude Code 公开文档（[记忆](https://code.claude.com/docs/en/memory)、[技能](https://code.claude.com/docs/en/skills)、[压缩](https://platform.claude.com/docs/en/build-with-claude/compaction)）中的成熟模式：

| Claude Code 机制 | 本计划对应 | 状态 |
|---|---|---|
| Microcompaction / context editing：旧工具输出原地替换为占位符，而非全量摘要 | tier-0 `compressHistoryForSend`（现有代码） | 已存在，语义相同 |
| Auto-compact：按上下文占用率阈值触发摘要压缩，预留压缩过程自身余量 | T17 `ContextBudget.CHAR_BUDGET` 字符预算 + 分级升级 | 一致（字符粗估版阈值） |
| Compact 结构化摘要（任务/进展/决策/待办），保留近期消息 | T17 `summarizeDigest` 结构化摘要 + 会话保留最近 10 轮 | 已采纳（调整 R1） |
| Agent Skills：SKILL.md frontmatter（name/description）+ 渐进披露（目录常驻 → 调用时加载正文） | T12/T13 SkillStore 目录注入 + `load_skill` 按需加载 | 一致（第 1、2 层渐进披露） |
| Skills description 语义："模型据此判断何时应用"，关键用例前置 | T12 description 字段语义、T14 表单提示 | 已采纳（调整 R2） |
| CLAUDE.md 四级记忆（托管 > 用户 > 项目 > 本地），拼接不覆盖、越近优先 | 执行协议（内置，最高）> persona.md > memory.md/会话历史 | 结构同构 |
| Auto memory：MEMORY.md 索引常驻（200 行/25KB 上限）+ 主题文件按需读取 | memory.md 单文件全量注入（≤50 行） | 简化版（当前量级足够，见 R5） |
| 压缩时保留最近技能调用内容（≤5k token/技能，25k 总预算） | `load_skill` 结果在旧轮次由 tier-0 压缩为一行摘要 | 已覆盖（更保守） |

**对照后采纳的三处调整：**
- **R1（T17）**：`summarizeDigest` 提示词从自由摘要改为 Claude Code compact 式**结构化摘要**（背景与目标 / 用户偏好与关键事实 / 未完成事项）——结构化输出对后续任务的可检索性显著优于连续散文。
- **R2（T12/T14）**：技能 `description` 语义按 Agent Skills 规范收紧：必须写"**做什么 + 何时用**"，关键用例前置（模型完全依据 description 决定何时调用，描述过宽会误触发）。
- **R5（非目标）**：明确单用户假设——Claude Code 是单用户多项目，ApkClaw 是单设备多发送者；当前 memory.md 全设备共享而会话按发送者隔离，个人设备场景成立，多用户场景需向工具执行上下文注入 senderId（改动大，列为后续项）。

**有意不照搬（YAGNI）**：子代理/subagent、hooks、`@path` 导入语法、`paths` glob 触发、企业级策略层、`CLAUDE.local.md`、技能目录附加资源文件（渐进披露第 3 层）——单用户单设备场景没有对应需求或收益不成比例。

## 文件结构总览

**新建：**
```
app/src/main/java/com/apk/claw/android/
├── agent/
│   ├── TaskRequest.kt                      # T7：任务请求（prompt + channel + senderId）
│   └── store/
│       ├── PromptComposer.kt               # T3：系统提示词组装器（纯函数）
│       ├── PersonaStore.kt                 # T2：persona.md 读写
│       ├── SessionStore.kt                 # T6：sessions.json 会话回合存取（T17 扩展摘要沉淀）
│       ├── MemoryStore.kt                  # T9：memory.md 行式记忆
│       ├── SkillStore.kt                   # T12：skills/*/SKILL.md 解析与目录
│       └── ContextBudget.kt                # T17：任务内上下文预算与分级压缩（纯 JVM）
└── tool/impl/
    ├── MemorySaveTool.java                 # T10
    ├── MemoryDeleteTool.java               # T10
    ├── MemoryListTool.java                 # T10
    └── LoadSkillTool.java                  # T13

app/src/test/java/com/apk/claw/android/agent/store/
├── PersonaStoreTest.kt                     # T2
├── PromptComposerTest.kt                   # T3
├── SessionStoreTest.kt                     # T6（T17 追加摘要相关用例）
├── MemoryStoreTest.kt                      # T9
├── SkillStoreTest.kt                       # T12
├── ContextBudgetTest.kt                    # T17
└── (T10/T13 的工具测试放入同目录 ToolJvmTest.kt)
```

**修改：**
| 文件 | 任务 |
|------|------|
| `channel/ChannelManager.kt` | T1（listener/dispatch 加 senderId） |
| `channel/{dingtalk,feishu,qqbot,discord,telegram,wechat}/...Handler.kt` | T1（6 处调用点） |
| `channel/ChannelSetup.kt` | T1、T7（重置命令）、T8（排队） |
| `agent/store/PromptComposer.kt` 调用方 `agent/DefaultAgentService.kt` | T4（persona 接线）、T7（历史注入+签名）、T11（memory 注入）、T13（skills 注入）、T15（Home 延迟）、T17（预算压缩+摘要沉淀） |
| `agent/AgentService.kt` / `agent/DefaultAgentService.kt` | T7 |
| `TaskOrchestrator.kt` | T7、T8、T15 |
| `AppViewModel.kt` | T7（startNewTask 签名透传） |
| `ClawApplication.kt` | T4、T6、T9、T12（store init） |
| `tool/ToolRegistry.kt` | T10、T13（注册） |
| `tool/BaseTool.kt` | T10、T13（wait_after 排除列表） |
| `agent/AgentConfig.kt` | T11（新增记忆使用规则 11） |
| `server/ConfigServer.kt` | T5、T14（新 API） |
| `assets/web/index.html` | T5（人格卡片）、T14（技能卡片） |
| `res/values/strings.xml` + `res/values-zh/strings.xml` | T7、T8（新文案） |
| `README.md` + `README_CN.md` | T16、T17 |

**阶段与工作量：** 阶段0 基础设施(T1, ~0.5d) → 阶段1 人格(T2-T5, ~2d) → 阶段2 会话(T6-T8, ~2.5d) → 阶段3 记忆(T9-T11, ~2.5d) → 阶段4 技能(T12-T14, ~2.5d) → 阶段5 收尾(T15-T17, ~3d)。总计约 13 人日。各阶段结束都是可独立发布的状态。

---

### Task 1: 发送者 ID 透传（基础设施）

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/channel/ChannelManager.kt:37-39, 218-220`
- Modify: `app/src/main/java/com/apk/claw/android/channel/dingtalk/DingTalkChannelHandler.kt:71`
- Modify: `app/src/main/java/com/apk/claw/android/channel/feishu/FeiShuChannelHandler.kt:62`
- Modify: `app/src/main/java/com/apk/claw/android/channel/qqbot/QQChannelHandler.kt:49`
- Modify: `app/src/main/java/com/apk/claw/android/channel/discord/DiscordChannelHandler.kt:40`
- Modify: `app/src/main/java/com/apk/claw/android/channel/telegram/TelegramChannelHandler.kt:101`
- Modify: `app/src/main/java/com/apk/claw/android/channel/wechat/WeChatChannelHandler.kt:237`
- Modify: `app/src/main/java/com/apk/claw/android/channel/ChannelSetup.kt:30-45`

**Interfaces:**
- Produces: `ChannelManager.OnMessageReceivedListener.onMessageReceived(channel: Channel, message: String, messageID: String, senderId: String)` 和 `ChannelManager.dispatchMessage(channel: Channel, message: String, messageID: String, senderId: String)`。后续所有任务（T7/T8）依赖此签名。

**说明:** 本任务是纯接线（6 个 handler → ChannelManager → ChannelSetup），无独立可单测的逻辑；验收 = 编译通过 + 日志可见 senderId。每个渠道用其路由身份作为发送者 ID（与各自 `getLastSenderId()` 的语义一致）。

- [ ] **Step 1: 修改 ChannelManager 接口与分发**

```kotlin
// ChannelManager.kt:37-39 接口改为：
interface OnMessageReceivedListener {
    fun onMessageReceived(channel: Channel, message: String, messageID: String, senderId: String)
}

// ChannelManager.kt:218-220 分发改为：
@JvmStatic
fun dispatchMessage(channel: Channel, message: String, messageID: String, senderId: String) {
    XLog.d(TAG, "dispatch from [$${channel.displayName}] senderId=$senderId")
    messageListener?.onMessageReceived(channel, message, messageID, senderId)
}
```

- [ ] **Step 2: 更新 6 个调用点（各文件行号见 Files）**

```kotlin
// DingTalkChannelHandler.kt:71（senderStaffId 已在上一行赋给 lastSenderStaffId）
ChannelManager.dispatchMessage(channel, text, lastMsgId ?: "", lastSenderStaffId ?: "")

// FeiShuChannelHandler.kt:62（P2MessageReceiveV1 事件的发送者开阳 ID）
ChannelManager.dispatchMessage(channel, text, messageId, event.event.sender?.senderId?.openId ?: "")

// QQChannelHandler.kt:49（openId 在作用域内，见上一行日志）
ChannelManager.dispatchMessage(channel, content, messageId, openId)

// DiscordChannelHandler.kt:40（channelId 在作用域内）
ChannelManager.dispatchMessage(channel, content, messageId, channelId)

// TelegramChannelHandler.kt:101
ChannelManager.dispatchMessage(channel, text, messageId.toString(), chatId.toString())

// WeChatChannelHandler.kt:237（fromUserId 已在上一行赋值）
ChannelManager.dispatchMessage(channel, body, msg.contextToken ?: "", fromUserId)
```

- [ ] **Step 3: ChannelSetup 监听器加参（暂不使用，仅打日志）**

```kotlin
ChannelManager.setOnMessageReceivedListener(object : ChannelManager.OnMessageReceivedListener {
    override fun onMessageReceived(channel: Channel, message: String, messageID: String, senderId: String) {
        XLog.i("ChannelSetup", "msg from ${channel.displayName} sender=$senderId")
        // 其余现有逻辑不动（可访问性检查、tryAcquireTask、startNewTask）
    }
})
```
（`ChannelSetup.kt` 需补 `import com.apk.claw.android.utils.XLog`。）

- [ ] **Step 4: 编译验证**

Run: `cd D:/code/ApkClaw && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（如有其他 dispatchMessage 调用点遗漏，编译器会指出）。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: propagate sender id from channel handlers to message listener"
```

---

### Task 2: PersonaStore（人格存储）

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/agent/store/PersonaStore.kt`
- Test: `app/src/test/java/com/apk/claw/android/agent/store/PersonaStoreTest.kt`

**Interfaces:**
- Produces: `PersonaStore.init(rootDir: File)`、`PersonaStore.get(): String`（无文件时返回 ""）、`PersonaStore.set(content: String)`（首尾去空白）。T4/T5 消费。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.apk.claw.android.agent.store

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonaStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun get_returnsEmptyWhenFileMissing() {
        PersonaStore.init(tmp.root)
        assertEquals("", PersonaStore.get())
    }

    @Test fun set_thenGet_roundTrips() {
        PersonaStore.init(tmp.root)
        PersonaStore.set("  你是一只叫小爪的猫娘助手  ")
        assertEquals("你是一只叫小爪的猫娘助手", PersonaStore.get())
    }

    @Test fun set_twice_overwrites() {
        PersonaStore.init(tmp.root)
        PersonaStore.set("A")
        PersonaStore.set("B")
        assertEquals("B", PersonaStore.get())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd D:/code/ApkClaw && ./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.store.PersonaStoreTest"`
Expected: FAIL（Unresolved reference: PersonaStore）

- [ ] **Step 3: 最小实现**

```kotlin
package com.apk.claw.android.agent.store

import java.io.File

/** 人格设定存储：filesDir/agent/persona.md，人类可直接编辑 */
object PersonaStore {

    private lateinit var file: File

    fun init(rootDir: File) {
        rootDir.mkdirs()
        file = File(rootDir, "persona.md")
    }

    fun get(): String = if (file.exists()) file.readText().trim() else ""

    fun set(content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content.trim())
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: 3 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: add PersonaStore backed by persona.md"
```

---

### Task 3: PromptComposer（系统提示词组装器）

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/agent/store/PromptComposer.kt`
- Test: `app/src/test/java/com/apk/claw/android/agent/store/PromptComposerTest.kt`

**Interfaces:**
- Produces: `PromptComposer.compose(persona: String, protocol: String, memorySection: String, skillsCatalog: String, deviceContext: String): String`。空白段自动跳过；人格段自动附加优先级声明。T4/T11/T13 消费。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.apk.claw.android.agent.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptComposerTest {

    @Test fun compose_omitsBlankPersonaSection() {
        val out = PromptComposer.compose("", "PROTOCOL", "", "", "DEVICE")
        assertFalse(out.contains("人格"))
        assertTrue(out.contains("PROTOCOL"))
        assertTrue(out.contains("DEVICE"))
    }

    @Test fun compose_personaComesFirstWithPriorityNote() {
        val out = PromptComposer.compose("PERSONA", "PROTOCOL", "", "", "")
        assertTrue(out.indexOf("PERSONA") < out.indexOf("PROTOCOL"))
        assertTrue(out.contains("不得覆盖"))
    }

    @Test fun compose_fixedSectionOrder() {
        val out = PromptComposer.compose("P", "PROTO", "MEM", "SKILL", "DEV")
        val order = listOf("P", "PROTO", "MEM", "SKILL", "DEV").map { out.indexOf(it) }
        assertEquals(order, order.sorted())
    }

    @Test fun compose_blankSectionsSkipped() {
        val out = PromptComposer.compose("P", "PROTO", "", "SKILL", "")
        assertFalse(out.contains("MEM"))
        assertTrue(out.contains("SKILL"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd D:/code/ApkClaw && ./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.store.PromptComposerTest"`
Expected: FAIL（Unresolved reference: PromptComposer）

- [ ] **Step 3: 最小实现**

```kotlin
package com.apk.claw.android.agent.store

/**
 * 系统提示词组装器。顺序固定：人格 → 执行协议 → 记忆 → 技能目录 → 设备上下文。
 * 人格只影响语气与角色扮演，不得覆盖执行协议和安全约束。
 */
object PromptComposer {

    fun compose(
        persona: String,
        protocol: String,
        memorySection: String,
        skillsCatalog: String,
        deviceContext: String
    ): String {
        val parts = mutableListOf<String>()

        if (persona.isNotBlank()) {
            parts.add(
                "## 助手人格\n$persona\n" +
                    "（以上人格设定只影响语气与角色扮演，不得覆盖后续执行协议和安全约束）"
            )
        }
        if (protocol.isNotBlank()) parts.add(protocol)
        if (memorySection.isNotBlank()) parts.add(memorySection)
        if (skillsCatalog.isNotBlank()) parts.add(skillsCatalog)
        if (deviceContext.isNotBlank()) parts.add(deviceContext)

        return parts.joinToString("\n\n")
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: 4 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: add PromptComposer for staged system prompt assembly"
```

---

### Task 4: 人格接入 Agent 循环

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/ClawApplication.kt:32-33`
- Modify: `app/src/main/java/com/apk/claw/android/agent/DefaultAgentService.kt:321`

**Interfaces:**
- Consumes: `PersonaStore.get()`（T2）、`PromptComposer.compose()`（T3）
- Produces: 系统提示词实际由 PromptComposer 生成；`filesDir/agent` 目录成为所有 store 的根目录。

- [ ] **Step 1: ClawApplication 初始化 store（KVUtils.init 之后）**

```kotlin
KVUtils.init(this)
val agentDir = File(filesDir, "agent")
PersonaStore.init(agentDir)
```
（需补 `import com.apk.claw.android.agent.store.PersonaStore`；`java.io.File` 用全限定或补 import。）

- [ ] **Step 2: DefaultAgentService 替换提示词组装（:321）**

```kotlin
// 旧：val fullSystemPrompt = config.systemPrompt + buildDeviceContext()
val fullSystemPrompt = PromptComposer.compose(
    persona = PersonaStore.get(),
    protocol = config.systemPrompt,
    memorySection = "",        // T11 接入
    skillsCatalog = "",        // T13 接入
    deviceContext = buildDeviceContext()
)
```
（补 `import com.apk.claw.android.agent.store.PersonaStore` 与 `PromptComposer`。）

- [ ] **Step 3: 编译 + 全量单测**

Run: `cd D:/code/ApkClaw && ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，既有测试全 PASS。

- [ ] **Step 4: 手动冒烟（有设备时）**

debug 安装后执行：
```bash
adb shell run-as com.apk.claw.android sh -c 'mkdir -p files/agent && printf "你是低调专业的技术助理，说话简洁" > files/agent/persona.md'
```
通过任一渠道发"你是谁"，回复应体现人格语气；删除 persona.md 后回到默认语气。

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: inject persona into agent system prompt"
```

---

### Task 5: LAN 配置 API `/api/persona` + 网页编辑

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/server/ConfigServer.kt:44-53（路由表）` 及文件末尾新增 handler
- Modify: `app/src/main/assets/web/index.html`

**Interfaces:**
- Consumes: `PersonaStore.get()/set()`（T2）
- Produces: `GET /api/persona` → `{"code":0,"data":{"persona":"..."}}`；`POST /api/persona` body `{"persona":"..."}` → `{"code":0,"message":"ok"}`。

- [ ] **Step 1: ConfigServer 加路由（路由表新增两行，仿 /api/llm）**

```kotlin
uri == "/api/persona" && method == Method.GET -> handleGetPersona()
uri == "/api/persona" && method == Method.POST -> handlePostPersona(session)
```

- [ ] **Step 2: 新增 handler（放在 handlePostLlm 之后，复用 parseBody/gson/corsResponse 既有模式）**

```kotlin
private fun handleGetPersona(): Response {
    val data = JsonObject().apply { addProperty("persona", PersonaStore.get()) }
    val result = JsonObject().apply {
        addProperty("code", 0)
        add("data", data)
        addProperty("message", "ok")
    }
    return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
}

private fun handlePostPersona(session: IHTTPSession): Response {
    val files = mutableMapOf<String, String>()
    session.parseBody(files)
    val body = files["postData"] ?: ""
    val json = try {
        gson.fromJson(body, JsonObject::class.java)
    } catch (e: Exception) {
        return corsResponse(
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"invalid json"}"""
            )
        )
    }
    if (json.has("persona")) {
        PersonaStore.set(json.get("persona").asString)
    }
    return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, """{"code":0,"message":"ok"}"""))
}
```
（补 `import com.apk.claw.android.agent.store.PersonaStore`。）

- [ ] **Step 3: index.html 加人格卡片（LLM 卡片之前）**

```html
<div class="card persona">
  <div class="card-title"><span class="dot"></span> 助手人格</div>
  <div class="field">
    <label>人格设定（留空使用默认。只影响语气，不能突破安全约束）</label>
    <textarea id="persona" rows="6" placeholder="例如：你是一只叫小爪的猫娘助手，说话带喵"></textarea>
  </div>
</div>
```
CSS（style 块内追加，与 `.llm .dot` 同级）：`.persona .dot { background: #FF9500; }`
textarea 样式（与 input 一致）：`textarea { width: 100%; border: 1px solid #d2d2d7; border-radius: 8px; padding: 8px 12px; font: inherit; resize: vertical; }`

- [ ] **Step 4: index.html 的 load()/save() 挂接**

`load()` 内（在现有 `Promise.all` 之后追加）：
```js
fetch('/api/persona').then(r => r.json()).then(j => {
  document.getElementById('persona').value = j.data.persona || '';
});
```
`save()` 内（加入现有 `Promise.all` 数组）：
```js
fetch('/api/persona', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ persona: document.getElementById('persona').value }) })
```

- [ ] **Step 5: 编译 + 手动验证**

Run: `cd D:/code/ApkClaw && ./gradlew assembleDebug`
设备开启 LAN Config 后：
```bash
curl http://<设备IP>:9527/api/persona
curl -X POST http://<设备IP>:9527/api/persona -H "Content-Type: application/json" -d '{"persona":"你是海盗船长助手"}'
```
Expected: GET 回读刚写入的内容；网页出现人格卡片并可保存。

- [ ] **Step 6: Commit**

```bash
git add app/src && git commit -m "feat: persona editing via LAN config server and web page"
```

---

### Task 6: SessionStore（会话回合存储）

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/agent/store/SessionStore.kt`
- Test: `app/src/test/java/com/apk/claw/android/agent/store/SessionStoreTest.kt`

**Interfaces:**
- Consumes: `com.apk.claw.android.channel.Channel`（纯枚举，无 Android 依赖）
- Produces:
  - `SessionStore.init(rootDir: File)`
  - `SessionStore.history(channel: Channel, senderId: String): List<Turn>`（超时自动清空；`Turn(user: String, assistant: String)`）
  - `SessionStore.appendTurn(channel: Channel, senderId: String, user: String, assistant: String)`（senderId 为空忽略；超过 10 轮 FIFO 裁剪）
  - `SessionStore.reset(channel: Channel, senderId: String)`
  - 常量：超时 30 分钟、最多 10 轮。T7/T8 消费。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.apk.claw.android.agent.store

import com.apk.claw.android.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun history_emptyInitially() {
        SessionStore.init(tmp.root)
        assertTrue(SessionStore.history(Channel.TELEGRAM, "42").isEmpty())
    }

    @Test fun appendTurn_thenHistoryReturnsIt() {
        SessionStore.init(tmp.root)
        SessionStore.appendTurn(Channel.TELEGRAM, "42", "打开设置", "已打开")
        val h = SessionStore.history(Channel.TELEGRAM, "42")
        assertEquals(1, h.size)
        assertEquals("打开设置", h[0].user)
        assertEquals("已打开", h[0].assistant)
    }

    @Test fun appendTurn_emptySenderIdIgnored() {
        SessionStore.init(tmp.root)
        SessionStore.appendTurn(Channel.TELEGRAM, "", "a", "b")
        assertTrue(SessionStore.history(Channel.TELEGRAM, "").isEmpty())
    }

    @Test fun appendTurn_trimsToMaxTurns() {
        SessionStore.init(tmp.root)
        repeat(12) { i -> SessionStore.appendTurn(Channel.TELEGRAM, "u", "q$i", "a$i") }
        val h = SessionStore.history(Channel.TELEGRAM, "u")
        assertEquals(10, h.size)
        assertEquals("q2", h.first().user)
    }

    @Test fun history_expiresAfterTimeout() {
        SessionStore.init(tmp.root)
        SessionStore.appendTurn(Channel.TELEGRAM, "u", "q", "a")
        SessionStore.nowProviderForTest = { System.currentTimeMillis() + 31 * 60 * 1000L }
        assertTrue(SessionStore.history(Channel.TELEGRAM, "u").isEmpty())
    }

    @Test fun reset_clearsSession() {
        SessionStore.init(tmp.root)
        SessionStore.appendTurn(Channel.TELEGRAM, "u", "q", "a")
        SessionStore.reset(Channel.TELEGRAM, "u")
        assertTrue(SessionStore.history(Channel.TELEGRAM, "u").isEmpty())
    }

    @Test fun sessions_persistAcrossReinit() {
        SessionStore.init(tmp.root)
        SessionStore.appendTurn(Channel.TELEGRAM, "u", "q", "a")
        SessionStore.init(tmp.root)  // 模拟进程重启
        assertEquals(1, SessionStore.history(Channel.TELEGRAM, "u").size)
    }

    @Test fun differentSenders_areIsolated() {
        SessionStore.init(tmp.root)
        SessionStore.appendTurn(Channel.TELEGRAM, "a", "q", "a1")
        SessionStore.appendTurn(Channel.TELEGRAM, "b", "q2", "b1")
        assertEquals(1, SessionStore.history(Channel.TELEGRAM, "a").size)
        assertEquals(1, SessionStore.history(Channel.TELEGRAM, "b").size)
    }
}
```
注意：`nowProviderForTest` 每个测试开头应复位——在 `init()` 里重置为默认实现，测试类加 `@After fun tearDown() { SessionStore.nowProviderForTest = null }`（实现里 null 表示用 `System::currentTimeMillis`）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd D:/code/ApkClaw && ./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.store.SessionStoreTest"`
Expected: FAIL（Unresolved reference: SessionStore）

- [ ] **Step 3: 最小实现**

```kotlin
package com.apk.claw.android.agent.store

import com.apk.claw.android.channel.Channel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 会话回合存储：filesDir/agent/sessions.json
 * 键 = "<渠道名>:<发送者ID>"，只存（用户消息, 最终回复）对，不存工具调用轮次。
 */
object SessionStore {

    data class Turn(val user: String, val assistant: String)

    private data class SessionData(val turns: MutableList<Turn>, var lastActive: Long)

    const val SESSION_TIMEOUT_MS: Long = 30 * 60 * 1000L
    const val MAX_TURNS = 10

    private lateinit var file: File
    private val gson = Gson()
    private val sessions = mutableMapOf<String, SessionData>()

    /** 测试注入用；null 表示使用系统时钟 */
    @JvmStatic
    var nowProviderForTest: (() -> Long)? = null

    private fun now(): Long = nowProviderForTest?.invoke() ?: System.currentTimeMillis()

    fun init(rootDir: File) {
        nowProviderForTest = null
        rootDir.mkdirs()
        file = File(rootDir, "sessions.json")
        sessions.clear()
        if (file.exists()) {
            try {
                val type = object : TypeToken<MutableMap<String, SessionData>>() {}.type
                val text = file.readText()
                if (text.isNotBlank()) sessions.putAll(gson.fromJson(text, type))
            } catch (_: Exception) {
            }
        }
    }

    fun history(channel: Channel, senderId: String): List<Turn> {
        val key = key(channel, senderId)
        val s = sessions[key] ?: return emptyList()
        if (now() - s.lastActive > SESSION_TIMEOUT_MS) {
            sessions.remove(key)
            persist()
            return emptyList()
        }
        return s.turns.toList()
    }

    fun appendTurn(channel: Channel, senderId: String, user: String, assistant: String) {
        if (senderId.isEmpty()) return
        val key = key(channel, senderId)
        val s = sessions.getOrPut(key) { SessionData(mutableListOf(), 0L) }
        s.turns.add(Turn(user, assistant))
        while (s.turns.size > MAX_TURNS) s.turns.removeAt(0)
        s.lastActive = now()
        persist()
    }

    fun reset(channel: Channel, senderId: String) {
        sessions.remove(key(channel, senderId))
        persist()
    }

    private fun key(channel: Channel, senderId: String) = "${channel.name}:$senderId"

    private fun persist() {
        try {
            file.writeText(gson.toJson(sessions))
        } catch (_: Exception) {
        }
    }
}
```
（测试类里补 `import org.junit.After` 与 tearDown。）

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: 8 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: add SessionStore for per-sender conversation turns"
```

---

### Task 7: 会话贯穿 Agent 循环（TaskRequest + 历史注入 + 回合记录 + 重置命令）

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/agent/TaskRequest.kt`
- Modify: `app/src/main/java/com/apk/claw/android/agent/AgentService.kt:6`
- Modify: `app/src/main/java/com/apk/claw/android/agent/DefaultAgentService.kt:75-94, 313-325`
- Modify: `app/src/main/java/com/apk/claw/android/TaskOrchestrator.kt:118, 187-194`
- Modify: `app/src/main/java/com/apk/claw/android/AppViewModel.kt:142-143`
- Modify: `app/src/main/java/com/apk/claw/android/ClawApplication.kt`（Task 4 的 init 块追加一行）
- Modify: `app/src/main/java/com/apk/claw/android/channel/ChannelSetup.kt`
- Modify: `app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh/strings.xml`

**Interfaces:**
- Consumes: `SessionStore`（T6）、senderId 透传（T1）
- Produces: `AgentService.executeTask(request: TaskRequest, callback: AgentCallback)`；`TaskOrchestrator.startNewTask(channel: Channel, senderId: String, task: String, messageID: String)`；`data class TaskRequest(prompt: String, channel: Channel, senderId: String)`。T8/T15 消费。

- [ ] **Step 1: 新建 TaskRequest**

```kotlin
package com.apk.claw.android.agent

import com.apk.claw.android.channel.Channel

data class TaskRequest(
    val prompt: String,
    val channel: Channel,
    val senderId: String
)
```

- [ ] **Step 2: 改接口与实现签名**

```kotlin
// AgentService.kt
fun executeTask(request: TaskRequest, callback: AgentCallback)

// DefaultAgentService.kt:75
override fun executeTask(request: TaskRequest, callback: AgentCallback) {
    if (running.get()) {
        callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
        return
    }
    running.set(true)
    cancelled.set(false)
    executor?.submit {
        try {
            runAgentLoop(request, callback)
        } catch (e: Exception) {
            XLog.e(TAG, "Agent execution error", e)
            callback.onError(0, e, 0)
        } finally {
            running.set(false)
        }
    }
}

// runAgentLoop 签名：
private fun runAgentLoop(request: TaskRequest, callback: AgentCallback) {
    val userPrompt = request.prompt
    // …其余不变
```

- [ ] **Step 3: 历史注入（runAgentLoop 内 SystemMessage 之后）**

```kotlin
messages.add(SystemMessage.from(fullSystemPrompt))
SessionStore.history(request.channel, request.senderId).forEach { turn ->
    messages.add(UserMessage.from(turn.user))
    messages.add(AiMessage.from(turn.assistant))
}
messages.add(UserMessage.from(userPrompt))
```
（补 `import com.apk.claw.android.agent.store.SessionStore`；`UserMessage`/`AiMessage` 已有 import。）

- [ ] **Step 4: ClawApplication 追加 init**

在 Task 4 的 `PersonaStore.init(agentDir)` 之后加：`SessionStore.init(agentDir)`

- [ ] **Step 5: TaskOrchestrator 改签名并记录回合**

```kotlin
// :118 签名改为：
fun startNewTask(channel: Channel, senderId: String, task: String, messageID: String) {
    // …内部 pressHome、roundBuffer 等逻辑不变…
    agentService.executeTask(TaskRequest(task, channel, senderId), object : AgentCallback {
        // …
        override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
            SessionStore.appendTurn(channel, senderId, task, finalAnswer)   // ← 新增
            // …原有 flushRoundBuffer()/releaseTask()/… 不变…
        }
        // onError / onSystemDialogBlocked 不记录回合（无最终回复）
    })
}
```
（补 `import com.apk.claw.android.agent.TaskRequest` 与 `SessionStore`。）

- [ ] **Step 6: AppViewModel 签名同步（无外部调用方，纯委托）**

```kotlin
fun startNewTask(channel: Channel, senderId: String, task: String, messageID: String) =
    taskOrchestrator.startNewTask(channel, senderId, task, messageID)
```

- [ ] **Step 7: ChannelSetup 消费 senderId + 重置命令**

```kotlin
override fun onMessageReceived(channel: Channel, message: String, messageID: String, senderId: String) {
    val app = ClawApplication.instance
    if (!ClawAccessibilityService.isRunning()) { /* 原逻辑不变 */ return }

    val cmd = message.trim()
    if (cmd == "新对话" || cmd == "/new") {
        SessionStore.reset(channel, senderId)
        ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_session_reset), messageID)
        ChannelManager.flushMessages(channel)
        return
    }

    if (!taskOrchestrator.tryAcquireTask(messageID, channel)) { /* 原逻辑不变（T8 会替换为排队） */ return }
    taskOrchestrator.startNewTask(channel, senderId, message, messageID)
}
```
（`ChannelSetup.kt` 需补 `import com.apk.claw.android.agent.store.SessionStore`。）

字符串资源（追加到两个 strings.xml 的 `</resources>` 之前；英文值进 values，中文值进 values-zh）：
```xml
<!-- values/strings.xml -->
<string name="channel_msg_session_reset">Started a new conversation.</string>
<!-- values-zh/strings.xml -->
<string name="channel_msg_session_reset">已开始新对话。</string>
```

- [ ] **Step 8: 编译 + 全量单测**

Run: `cd D:/code/ApkClaw && ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 9: 手动冒烟（有设备时）**

渠道发送「我叫小明」→ 回复；再发送「我叫什么？」→ 回复应包含"小明"（会话生效）。发送「新对话」→ 收到确认；再问「我叫什么？」→ 不知道。

- [ ] **Step 10: Commit**

```bash
git add -A && git commit -m "feat: continuous conversation with per-sender session history"
```

---

### Task 8: 忙时排队（有界队列 + 空闲后排空）

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/TaskOrchestrator.kt`（新增 onIdle 钩子并在 4 个终止路径调用）
- Modify: `app/src/main/java/com/apk/claw/android/channel/ChannelSetup.kt`
- Modify: `app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh/strings.xml`

**Interfaces:**
- Consumes: T7 的 startNewTask(channel, senderId, task, messageID)
- Produces: `TaskOrchestrator.onIdle: (() -> Unit)?`（任务锁释放完毕后回调，供 ChannelSetup 排空队列）。

- [ ] **Step 1: TaskOrchestrator 增加 onIdle**

```kotlin
/** 任务结束（完成/失败/取消/弹窗终止）且锁已释放后回调。注意：回调在后台线程执行。 */
@Volatile
var onIdle: (() -> Unit)? = null

private fun notifyIdle() {
    try { onIdle?.invoke() } catch (e: Exception) { XLog.e(TAG, "onIdle callback failed", e) }
}
```
调用点（都在 `onTaskFinished()` 之后追加 `notifyIdle()`）：
1. `onComplete` 回调内
2. `onError` 回调内
3. `onSystemDialogBlocked` 回调内
4. `cancelCurrentTask()` 末尾

- [ ] **Step 2: ChannelSetup 增加有界队列**

```kotlin
private data class PendingMessage(
    val channel: Channel, val senderId: String, val message: String, val messageID: String
)

private val queueLock = Any()
private val pendingQueue = ArrayDeque<PendingMessage>()

companion object {
    private const val MAX_PENDING = 3
}
```

监听器忙时分支替换为：
```kotlin
if (!taskOrchestrator.tryAcquireTask(messageID, channel)) {
    val queued = synchronized(queueLock) {
        if (pendingQueue.size >= MAX_PENDING) null
        else { pendingQueue.addLast(PendingMessage(channel, senderId, message, messageID)); pendingQueue.size }
    }
    val reply = if (queued != null) {
        app.getString(R.string.channel_msg_queued, queued)
    } else {
        app.getString(R.string.channel_msg_queue_full)
    }
    ChannelManager.sendMessage(channel, reply, messageID)
    ChannelManager.flushMessages(channel)
    return
}
```

setup() 末尾挂空闲钩子：
```kotlin
taskOrchestrator.onIdle = { drainPending() }
```

排空实现：
```kotlin
private fun drainPending() {
    while (true) {
        val next = synchronized(queueLock) { pendingQueue.firstOrNull() } ?: return
        if (!taskOrchestrator.tryAcquireTask(next.messageID, next.channel)) return
        synchronized(queueLock) { pendingQueue.removeFirst() }
        taskOrchestrator.startNewTask(next.channel, next.senderId, next.message, next.messageID)
        return  // 本任务结束后 onIdle 会再次触发，继续排下一条
    }
}
```

字符串资源：
```xml
<!-- values/strings.xml -->
<string name="channel_msg_queued">Task in progress; your message is queued (position %1$d).</string>
<string name="channel_msg_queue_full">Task in progress and queue is full. Please try again later.</string>
<!-- values-zh/strings.xml -->
<string name="channel_msg_queued">任务执行中，已加入队列（第 %1$d 位）。</string>
<string name="channel_msg_queue_full">任务执行中且队列已满，请稍后再试。</string>
```

- [ ] **Step 3: 编译**

Run: `cd D:/code/ApkClaw && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 手动冒烟（有设备时）**

任务执行中连发两条消息 → 各收到"已加入队列（第 1/2 位）"；第一条任务结束后队列依次执行。连发 4 条 → 第 4 条收到"队列已满"。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: queue messages while a task is running instead of rejecting"
```

---

### Task 9: MemoryStore（长期记忆存储）

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/agent/store/MemoryStore.kt`
- Test: `app/src/test/java/com/apk/claw/android/agent/store/MemoryStoreTest.kt`

**Interfaces:**
- Produces:
  - `MemoryStore.init(rootDir: File)`
  - `MemoryStore.save(text: String, timestamp: String = nowStamp()): Boolean`（空文本 false；完全重复返回 true 不追加；超 50 条 FIFO）
  - `MemoryStore.all(): List<String>`（返回去前缀后的文本）
  - `MemoryStore.delete(text: String): Boolean`
  - `MemoryStore.promptSection(): String`（空记忆返回 ""，否则 `## 长期记忆` 段落）
  - T10/T11 消费。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.apk.claw.android.agent.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun save_thenAll_roundTrips() {
        MemoryStore.init(tmp.root)
        MemoryStore.save("用户喜欢喝拿铁", "2026-09-02 10:00")
        assertEquals(listOf("用户喜欢喝拿铁"), MemoryStore.all())
    }

    @Test fun save_blankTextReturnsFalse() {
        MemoryStore.init(tmp.root)
        assertFalse(MemoryStore.save("   "))
        assertTrue(MemoryStore.all().isEmpty())
    }

    @Test fun save_duplicateIsNoop() {
        MemoryStore.init(tmp.root)
        MemoryStore.save("A", "2026-09-02 10:00")
        MemoryStore.save("A", "2026-09-02 11:00")
        assertEquals(listOf("A"), MemoryStore.all())
    }

    @Test fun save_trimsToMaxEntriesFIFO() {
        MemoryStore.init(tmp.root)
        repeat(55) { i -> MemoryStore.save("m$i", "2026-09-02 10:00") }
        val all = MemoryStore.all()
        assertEquals(50, all.size)
        assertEquals("m5", all.first())
    }

    @Test fun delete_removesMatchingEntry() {
        MemoryStore.init(tmp.root)
        MemoryStore.save("A", "2026-09-02 10:00")
        MemoryStore.save("B", "2026-09-02 10:01")
        assertTrue(MemoryStore.delete("B"))
        assertEquals(listOf("A"), MemoryStore.all())
        assertFalse(MemoryStore.delete("不存在"))
    }

    @Test fun promptSection_emptyWhenNoMemory() {
        MemoryStore.init(tmp.root)
        assertEquals("", MemoryStore.promptSection())
    }

    @Test fun promptSection_listsAllEntries() {
        MemoryStore.init(tmp.root)
        MemoryStore.save("A", "2026-09-02 10:00")
        val s = MemoryStore.promptSection()
        assertTrue(s.startsWith("## 长期记忆"))
        assertTrue(s.contains("- A"))
    }

    @Test fun all_toleratesManuallyEditedLines() {
        MemoryStore.init(tmp.root)
        MemoryStore.save("A", "2026-09-02 10:00")
        tmp.root.resolve("memory.md").appendText("手工加的一行\n")
        assertEquals(listOf("A", "手工加的一行"), MemoryStore.all())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd D:/code/ApkClaw && ./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.store.MemoryStoreTest"`
Expected: FAIL（Unresolved reference: MemoryStore）

- [ ] **Step 3: 最小实现**

```kotlin
package com.apk.claw.android.agent.store

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 长期记忆存储：filesDir/agent/memory.md，行式条目 "- [yyyy-MM-dd HH:mm] 文本"。
 * 人类可直接编辑；解析时对没有前缀的行按原文处理。
 */
object MemoryStore {

    const val MAX_ENTRIES = 50

    private val ENTRY_PREFIX = Regex("^- \\[[^]]*] ?")
    private lateinit var file: File

    fun init(rootDir: File) {
        rootDir.mkdirs()
        file = File(rootDir, "memory.md")
        if (!file.exists()) file.writeText("")
    }

    fun save(text: String, timestamp: String = nowStamp()): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val lines = readLines()
        if (lines.any { extractText(it) == t }) return true
        lines.add("- [$timestamp] $t")
        while (lines.size > MAX_ENTRIES) lines.removeAt(0)
        writeLines(lines)
        return true
    }

    fun all(): List<String> = readLines().map { extractText(it) }

    fun delete(text: String): Boolean {
        val t = text.trim()
        val lines = readLines()
        val removed = lines.removeAll { extractText(it) == t }
        if (removed) writeLines(lines)
        return removed
    }

    fun promptSection(): String {
        val entries = all()
        if (entries.isEmpty()) return ""
        return "## 长期记忆\n" + entries.joinToString("\n") { "- $it" } +
            "\n（以上是历史累积的记忆，与当前任务相关时可参考；需要更新时使用 memory_save / memory_delete）"
    }

    private fun readLines(): MutableList<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.toMutableList()
        else mutableListOf()

    private fun writeLines(lines: List<String>) {
        file.writeText(lines.joinToString("\n") + "\n")
    }

    private fun extractText(line: String): String = ENTRY_PREFIX.replace(line.trim(), "")

    private fun nowStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: 8 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: add MemoryStore backed by line-based memory.md"
```

---

### Task 10: 记忆工具（memory_save / memory_delete / memory_list）

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/tool/impl/MemorySaveTool.java`
- Create: `app/src/main/java/com/apk/claw/android/tool/impl/MemoryDeleteTool.java`
- Create: `app/src/main/java/com/apk/claw/android/tool/impl/MemoryListTool.java`
- Modify: `app/src/main/java/com/apk/claw/android/tool/ToolRegistry.kt:28-41`
- Modify: `app/src/main/java/com/apk/claw/android/tool/BaseTool.kt:39`
- Test: `app/src/test/java/com/apk/claw/android/agent/store/MemoryToolsJvmTest.kt`

**Interfaces:**
- Consumes: `MemoryStore`（T9）、`BaseTool` 抽象（getName/getParameters/execute/getDescriptionEN/getDescriptionCN）、`ToolResult.success/error`
- Produces: 工具名 `memory_save`、`memory_delete`、`memory_list`（T11 提示词、T15 排除列表引用）。

- [ ] **Step 1: 写失败测试（工具是纯 Java 类，JVM 可直接测）**

```kotlin
package com.apk.claw.android.agent.store

import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.tool.impl.MemoryDeleteTool
import com.apk.claw.android.tool.impl.MemoryListTool
import com.apk.claw.android.tool.impl.MemorySaveTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryToolsJvmTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun saveTool_writesToStore() {
        MemoryStore.init(tmp.root)
        val r: ToolResult = MemorySaveTool().execute(mapOf("text" to "用户喜欢喝拿铁"))
        assertTrue(r.isSuccess)
        assertEquals(listOf("用户喜欢喝拿铁"), MemoryStore.all())
    }

    @Test fun deleteTool_removesEntry() {
        MemoryStore.init(tmp.root)
        MemoryStore.save("A", "2026-09-02 10:00")
        val r: ToolResult = MemoryDeleteTool().execute(mapOf("text" to "A"))
        assertTrue(r.isSuccess)
        assertTrue(MemoryStore.all().isEmpty())
    }

    @Test fun listTool_returnsNumberedEntries() {
        MemoryStore.init(tmp.root)
        MemoryStore.save("A", "2026-09-02 10:00")
        MemoryStore.save("B", "2026-09-02 10:01")
        val r: ToolResult = MemoryListTool().execute(emptyMap())
        assertTrue(r.isSuccess)
        assertTrue(r.data!!.contains("1. A"))
        assertTrue(r.data!!.contains("2. B"))
    }

    @Test fun listTool_emptyMemory() {
        MemoryStore.init(tmp.root)
        val r: ToolResult = MemoryListTool().execute(emptyMap())
        assertTrue(r.isSuccess)
        assertTrue(r.data!!.contains("没有"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd D:/code/ApkClaw && ./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.store.MemoryToolsJvmTest"`
Expected: FAIL（Unresolved reference: MemorySaveTool）

- [ ] **Step 3: 实现三个工具**

```java
package com.apk.claw.android.tool.impl;

import com.apk.claw.android.agent.store.MemoryStore;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MemorySaveTool extends BaseTool {

    @Override
    public String getName() { return "memory_save"; }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("text", "string", "要长期记住的一条事实或偏好，一句话", true));
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String text = requireString(params, "text");
        boolean ok = MemoryStore.save(text);
        return ok ? ToolResult.success("已保存记忆") : ToolResult.error("记忆内容为空");
    }

    @Override
    public String getDescriptionEN() {
        return "Save a durable fact or user preference to long-term memory for future tasks.";
    }

    @Override
    public String getDescriptionCN() {
        return "把一条稳定的事实或用户偏好保存到长期记忆，之后的任务都能看到。";
    }
}
```

```java
package com.apk.claw.android.tool.impl;

import com.apk.claw.android.agent.store.MemoryStore;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MemoryDeleteTool extends BaseTool {

    @Override
    public String getName() { return "memory_delete"; }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("text", "string", "要删除的记忆原文（须与 memory_list 显示的文本一致）", true));
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String text = requireString(params, "text");
        boolean ok = MemoryStore.delete(text);
        return ok ? ToolResult.success("已删除记忆") : ToolResult.error("未找到该记忆");
    }

    @Override
    public String getDescriptionEN() {
        return "Delete an outdated or wrong entry from long-term memory.";
    }

    @Override
    public String getDescriptionCN() {
        return "从长期记忆中删除一条过时或错误的条目。";
    }
}
```

```java
package com.apk.claw.android.tool.impl;

import com.apk.claw.android.agent.store.MemoryStore;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MemoryListTool extends BaseTool {

    @Override
    public String getName() { return "memory_list"; }

    @Override
    public List<ToolParameter> getParameters() { return Collections.emptyList(); }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        List<String> all = MemoryStore.all();
        if (all.isEmpty()) {
            return ToolResult.success("当前没有长期记忆");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            sb.append(i + 1).append(". ").append(all.get(i)).append("\n");
        }
        return ToolResult.success(sb.toString().trim());
    }

    @Override
    public String getDescriptionEN() {
        return "List all long-term memory entries.";
    }

    @Override
    public String getDescriptionCN() {
        return "列出当前全部长期记忆。";
    }
}
```
（`BaseTool.requireString` 是 Kotlin protected，Java 子类可直接调用。）

- [ ] **Step 4: ToolRegistry 注册 + BaseTool 排除列表**

```kotlin
// ToolRegistry.registerCommonTools() 末尾追加：
register(MemorySaveTool())
register(MemoryDeleteTool())
register(MemoryListTool())

// BaseTool.kt:39 的排除列表追加三个名字：
if (getName() !in listOf("wait", "finish", "get_screen_info", "take_screenshot",
        "get_installed_apps", "find_node_info", "scroll_to_find",
        "list_scheduled_tasks", "schedule_task", "cancel_scheduled_task",
        "memory_save", "memory_delete", "memory_list")) {
```

- [ ] **Step 5: 跑测试确认通过 + 编译**

Run: `cd D:/code/ApkClaw && ./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.store.MemoryToolsJvmTest" assembleDebug`
Expected: 4 个测试 PASS，BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```bash
git add app/src && git commit -m "feat: add memory_save/delete/list tools"
```

---

### Task 11: 记忆注入系统提示词 + 协议规则 11

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/agent/DefaultAgentService.kt:321`（T4 里的 memorySection 占位）
- Modify: `app/src/main/java/com/apk/claw/android/ClawApplication.kt`（init 块追加一行）
- Modify: `app/src/main/java/com/apk/claw/android/agent/AgentConfig.kt:83-86`（规则 10 之后、安全约束之前）

**Interfaces:**
- Consumes: `MemoryStore.promptSection()`（T9）
- Produces: 无新接口。

- [ ] **Step 1: DefaultAgentService 占位替换**

```kotlin
memorySection = MemoryStore.promptSection(),   // 原 ""
```
（补 `import com.apk.claw.android.agent.store.MemoryStore`。）

- [ ] **Step 2: ClawApplication init 追加**

`MemoryStore.init(agentDir)`（加在 SessionStore.init 之后）

- [ ] **Step 3: AgentConfig 增加规则 11（插在"规则 10"块之后、"## 安全约束"之前）**

```
规则 11：善用长期记忆。
当用户告知稳定的事实或偏好（如"我喜欢…""我的…是…"）且对今后任务有用时，调用 memory_save 保存一句话记忆。
记忆过时或用户指出记错时，用 memory_delete 删除对应条目。
拿不准要不要记，就不记。不要把一次性的任务上下文存进记忆。
```

- [ ] **Step 4: 编译 + 全量单测**

Run: `cd D:/code/ApkClaw && ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 手动冒烟（有设备时）**

渠道发送「记住：我家 WiFi 叫 Nest-5G」→ Agent 应调用 memory_save；重启 App 后发送「我家 WiFi 叫什么」→ 能答出（或先 memory_list 再回答）。

- [ ] **Step 6: Commit**

```bash
git add app/src && git commit -m "feat: inject long-term memory into system prompt with usage rule"
```

---

### Task 12: SkillStore（技能文件系统存储）

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/agent/store/SkillStore.kt`
- Test: `app/src/test/java/com/apk/claw/android/agent/store/SkillStoreTest.kt`

**Interfaces:**
- Produces:
  - `SkillStore.init(rootDir: File)`
  - `SkillStore.upsert(name: String, description: String, body: String): Boolean`（名字须匹配 `^[a-z0-9-]{1,40}$`）
  - `SkillStore.list(): List<Skill>`（`Skill(name, description)`；name 取目录名，description 取 frontmatter）
  - `SkillStore.load(name: String): String?`（返回去 frontmatter 的正文）
  - `SkillStore.catalogSection(): String`（无技能返回 ""）
  - 文件格式：`skills/<name>/SKILL.md`，头部 `---\nname: ...\ndescription: ...\n---\n`。T13/T14 消费。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.apk.claw.android.agent.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun upsert_thenListAndLoad() {
        SkillStore.init(tmp.root)
        SkillStore.upsert("order-coffee", "在美团下单咖啡", "1. 打开美团\n2. 搜索咖啡")
        assertEquals(1, SkillStore.list().size)
        assertEquals("order-coffee", SkillStore.list()[0].name)
        assertEquals("在美团下单咖啡", SkillStore.list()[0].description)
        assertTrue(SkillStore.load("order-coffee")!!.startsWith("1. 打开美团"))
    }

    @Test fun upsert_rejectsInvalidNames() {
        SkillStore.init(tmp.root)
        assertFalse(SkillStore.upsert("Bad Name", "d", "b"))
        assertFalse(SkillStore.upsert("../etc", "d", "b"))
        assertFalse(SkillStore.upsert("", "d", "b"))
    }

    @Test fun upsert_twiceOverwrites() {
        SkillStore.init(tmp.root)
        SkillStore.upsert("s", "d1", "b1")
        SkillStore.upsert("s", "d2", "b2")
        assertEquals(1, SkillStore.list().size)
        assertEquals("b2", SkillStore.load("s"))
    }

    @Test fun load_unknownNameReturnsNull() {
        SkillStore.init(tmp.root)
        assertNull(SkillStore.load("nope"))
        assertNull(SkillStore.load("../etc"))
    }

    @Test fun catalogSection_emptyWhenNoSkills() {
        SkillStore.init(tmp.root)
        assertEquals("", SkillStore.catalogSection())
    }

    @Test fun catalogSection_listsNameAndDescriptionOnly() {
        SkillStore.init(tmp.root)
        SkillStore.upsert("order-coffee", "在美团下单咖啡", "SECRET-BODY-STEP")
        val s = SkillStore.catalogSection()
        assertTrue(s.startsWith("## 可用技能"))
        assertTrue(s.contains("- order-coffee: 在美团下单咖啡"))
        assertFalse(s.contains("SECRET-BODY-STEP"))
        assertTrue(s.contains("load_skill"))
    }

    @Test fun list_toleratesHandWrittenSkillWithoutFrontmatter() {
        SkillStore.init(tmp.root)
        val dir = tmp.root.resolve("skills/hand")
        dir.mkdirs()
        dir.resolve("SKILL.md").writeText("手写的技能正文")
        assertEquals("手写的技能正文", SkillStore.load("hand"))
        assertEquals("", SkillStore.list().first { it.name == "hand" }.description)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd D:/code/ApkClaw && ./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.store.SkillStoreTest"`
Expected: FAIL（Unresolved reference: SkillStore）

- [ ] **Step 3: 最小实现**

```kotlin
package com.apk.claw.android.agent.store

import java.io.File

/**
 * 技能文件系统：filesDir/agent/skills/<name>/SKILL.md
 * 系统提示词只注入目录（name+description），正文由 load_skill 工具按需加载（渐进披露）。
 * description 语义参考 Anthropic Agent Skills 规范：写清"做什么 + 何时用"，关键用例前置——
 * 模型完全依据 description 决定何时调用，描述过宽会误触发。
 */
object SkillStore {

    data class Skill(val name: String, val description: String)

    private val NAME_REGEX = Regex("^[a-z0-9-]{1,40}$")
    private lateinit var dir: File

    fun init(rootDir: File) {
        dir = File(rootDir, "skills")
        dir.mkdirs()
    }

    fun upsert(name: String, description: String, body: String): Boolean {
        if (!NAME_REGEX.matches(name)) return false
        val skillDir = File(dir, name)
        skillDir.mkdirs()
        val header = "---\nname: $name\ndescription: ${description.replace("\n", " ")}\n---\n"
        File(skillDir, "SKILL.md").writeText(header + body.trim() + "\n")
        return true
    }

    fun list(): List<Skill> {
        val dirs = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { d ->
            val md = File(d, "SKILL.md")
            if (!md.exists()) return@mapNotNull null
            Skill(d.name, parseDescription(md.readText()))
        }.sortedBy { it.name }
    }

    fun load(name: String): String? {
        if (!NAME_REGEX.matches(name)) return null
        val md = File(dir, "$name${File.separator}SKILL.md")
        if (!md.exists()) return null
        return stripFrontmatter(md.readText()).trim().ifEmpty { null }
    }

    fun catalogSection(): String {
        val skills = list()
        if (skills.isEmpty()) return ""
        return "## 可用技能\n" +
            skills.joinToString("\n") { "- ${it.name}: ${it.description}" } +
            "\n执行任务时如需某个技能的详细步骤，先调用 load_skill(name) 获取全文，再按步骤操作。"
    }

    private fun parseDescription(raw: String): String {
        if (!raw.startsWith("---\n")) return ""
        val end = raw.indexOf("\n---\n", 4)
        if (end < 0) return ""
        return raw.substring(4, end).lineSequence()
            .firstOrNull { it.startsWith("description:") }
            ?.removePrefix("description:")?.trim() ?: ""
    }

    private fun stripFrontmatter(raw: String): String {
        if (!raw.startsWith("---\n")) return raw
        val end = raw.indexOf("\n---\n", 4)
        if (end < 0) return raw
        return raw.substring(end + 5)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: 7 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: add SkillStore for md-based skill packages"
```

---

### Task 13: load_skill 工具 + 技能目录注入

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/tool/impl/LoadSkillTool.java`
- Modify: `app/src/main/java/com/apk/claw/android/tool/ToolRegistry.kt`
- Modify: `app/src/main/java/com/apk/claw/android/tool/BaseTool.kt:39`
- Modify: `app/src/main/java/com/apk/claw/android/agent/DefaultAgentService.kt:321`（skillsCatalog 占位）
- Modify: `app/src/main/java/com/apk/claw/android/ClawApplication.kt`（init 追加）
- Test: `app/src/test/java/com/apk/claw/android/agent/store/LoadSkillToolJvmTest.kt`

**Interfaces:**
- Consumes: `SkillStore.load()`（T12）
- Produces: 工具名 `load_skill`（参数 `name`）；T15 排除列表引用。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.apk.claw.android.agent.store

import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.tool.impl.LoadSkillTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoadSkillToolJvmTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun loadSkill_returnsBody() {
        SkillStore.init(tmp.root)
        SkillStore.upsert("order-coffee", "d", "1. 打开美团")
        val r: ToolResult = LoadSkillTool().execute(mapOf("name" to "order-coffee"))
        assertTrue(r.isSuccess)
        assertEquals("1. 打开美团", r.data)
    }

    @Test fun loadSkill_unknownNameFails() {
        SkillStore.init(tmp.root)
        val r: ToolResult = LoadSkillTool().execute(mapOf("name" to "nope"))
        assertTrue(!r.isSuccess)
        assertTrue(r.error!!.contains("nope"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd D:/code/ApkClaw && ./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.store.LoadSkillToolJvmTest"`
Expected: FAIL（Unresolved reference: LoadSkillTool）

- [ ] **Step 3: 实现工具**

```java
package com.apk.claw.android.tool.impl;

import com.apk.claw.android.agent.store.SkillStore;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class LoadSkillTool extends BaseTool {

    @Override
    public String getName() { return "load_skill"; }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("name", "string", "技能名，来自系统提示词中的可用技能列表", true));
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String name = requireString(params, "name");
        String body = SkillStore.load(name);
        return body != null ? ToolResult.success(body) : ToolResult.error("技能不存在: " + name);
    }

    @Override
    public String getDescriptionEN() {
        return "Load the full step-by-step content of a skill by name.";
    }

    @Override
    public String getDescriptionCN() {
        return "按名称加载一个技能的完整分步内容。";
    }
}
```

- [ ] **Step 4: 注册 + 排除列表 + 注入**

```kotlin
// ToolRegistry.registerCommonTools() 追加：
register(LoadSkillTool())

// BaseTool.kt:39 排除列表追加 "load_skill"

// DefaultAgentService.kt:321 占位替换：
skillsCatalog = SkillStore.catalogSection(),   // 原 ""

// ClawApplication init 块追加（MemoryStore.init 之后）：
SkillStore.init(agentDir)
```
（DefaultAgentService 补 `import com.apk.claw.android.agent.store.SkillStore`。）

- [ ] **Step 5: 跑测试 + 编译**

Run: `cd D:/code/ApkClaw && ./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.store.LoadSkillToolJvmTest" assembleDebug`
Expected: 2 个测试 PASS，BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```bash
git add app/src && git commit -m "feat: add load_skill tool and inject skill catalog into prompt"
```

---

### Task 14: LAN 技能管理 API + 网页管理区

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/server/ConfigServer.kt`（路由表 + 2 个 handler）
- Modify: `app/src/main/assets/web/index.html`

**Interfaces:**
- Consumes: `SkillStore.upsert/list()`（T12）
- Produces:
  - `GET /api/skills` → `{"code":0,"data":{"skills":[{"name":"...","description":"..."}]}}`
  - `POST /api/skills` body `{"name":"...","description":"...","content":"..."}` → `{"code":0}` 或 `{"code":-1,"message":"invalid skill name"}`

- [ ] **Step 1: 路由表追加**

```kotlin
uri == "/api/skills" && method == Method.GET -> handleGetSkills()
uri == "/api/skills" && method == Method.POST -> handlePostSkill(session)
```

- [ ] **Step 2: handler 实现（仿 Task 5 的 parseBody 模式）**

```kotlin
private fun handleGetSkills(): Response {
    val skills = com.apk.claw.android.agent.store.SkillStore.list().map {
        JsonObject().apply {
            addProperty("name", it.name)
            addProperty("description", it.description)
        }
    }
    val data = JsonObject().apply { add("skills", gson.toJsonTree(skills)) }
    val result = JsonObject().apply {
        addProperty("code", 0)
        add("data", data)
        addProperty("message", "ok")
    }
    return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
}

private fun handlePostSkill(session: IHTTPSession): Response {
    val files = mutableMapOf<String, String>()
    session.parseBody(files)
    val body = files["postData"] ?: ""
    val json = try {
        gson.fromJson(body, JsonObject::class.java)
    } catch (e: Exception) {
        return corsResponse(
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"invalid json"}""")
        )
    }
    val name = json.get("name")?.asString ?: ""
    val description = json.get("description")?.asString ?: ""
    val content = json.get("content")?.asString ?: ""
    val ok = com.apk.claw.android.agent.store.SkillStore.upsert(name, description, content)
    return if (ok) {
        corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, """{"code":0,"message":"ok"}"""))
    } else {
        corsResponse(newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
            """{"code":-1,"message":"invalid skill name (a-z, 0-9, '-'; max 40)"}"""))
    }
}
```
（handler 内用全限定名或顶部 import `SkillStore`，二选一。）

- [ ] **Step 3: index.html 技能卡片（人格卡片之后）**

```html
<div class="card skills">
  <div class="card-title"><span class="dot"></span> 技能库</div>
  <div class="field"><label>已安装技能</label><div id="skillList" style="font-size:13px;color:#86868b;">加载中…</div></div>
  <div class="field"><label>技能名（小写字母/数字/中划线）</label><input id="skillName" placeholder="order-coffee" /></div>
  <div class="field"><label>一句话描述（做什么 + 何时用，模型据此决定何时调用）</label><input id="skillDesc" placeholder="在美团下单咖啡；用户要点咖啡/外卖时使用" /></div>
  <div class="field"><label>技能内容（分步操作说明，Markdown）</label><textarea id="skillBody" rows="6"></textarea></div>
  <button id="skillUploadBtn" type="button">添加 / 更新技能</button>
</div>
```
CSS：`.skills .dot { background: #AF52DE; }` 以及按钮自包含样式（不依赖页面既有按钮 class）：
`#skillUploadBtn { width: 100%; padding: 10px; border: none; border-radius: 8px; background: #5856D6; color: #fff; font: inherit; cursor: pointer; }`

- [ ] **Step 4: 页面 JS（追加在 save() 之后）**

```js
async function refreshSkills() {
  try {
    const r = await fetch('/api/skills');
    const j = await r.json();
    const el = document.getElementById('skillList');
    const list = (j.data && j.data.skills) || [];
    el.textContent = list.length
      ? list.map(s => s.name + ' — ' + s.description).join('\n')
      : '暂无技能';
    el.style.whiteSpace = 'pre-line';
  } catch (e) {
    document.getElementById('skillList').textContent = '加载失败';
  }
}

document.getElementById('skillUploadBtn').addEventListener('click', async () => {
  const payload = {
    name: document.getElementById('skillName').value.trim(),
    description: document.getElementById('skillDesc').value.trim(),
    content: document.getElementById('skillBody').value
  };
  if (!payload.name) { showToast('请填写技能名', 'error'); return; }
  const r = await fetch('/api/skills', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
  const j = await r.json();
  showToast(j.code === 0 ? '已保存' : j.message, j.code === 0 ? 'success' : 'error');
  if (j.code === 0) refreshSkills();
});

refreshSkills();
```

- [ ] **Step 5: 编译 + 手动验证**

Run: `cd D:/code/ApkClaw && ./gradlew assembleDebug`
设备 LAN Config 开启后：
```bash
curl http://<设备IP>:9527/api/skills
curl -X POST http://<设备IP>:9527/api/skills -H "Content-Type: application/json" \
  -d '{"name":"order-coffee","description":"在美团下单咖啡","content":"1. 打开美团\n2. 搜索咖啡\n3. 选第一家"}'
```
Expected: 列表返回 order-coffee；网页卡片可添加并在列表显示。

- [ ] **Step 6: Commit**

```bash
git add app/src && git commit -m "feat: skill management via LAN API and web page"
```

---

### Task 15: Home 键重置延迟触发（纯聊天不重置设备）

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/TaskOrchestrator.kt:132`（删除 pressHome）
- Modify: `app/src/main/java/com/apk/claw/android/agent/DefaultAgentService.kt`（工具执行循环内延迟触发）

**Interfaces:**
- Consumes: 工具名常量（含 T10/T13 的 memory_*、load_skill）
- Produces: 无新接口。行为变化：只有第一个"改状态"工具执行前才 pressHome。

- [ ] **Step 1: 删除 TaskOrchestrator 的提前 pressHome（:132）**

删除这一行（及注释如有）：
```kotlin
ClawAccessibilityService.getInstance()?.pressHome()
```
（`ClawAccessibilityService` import 保留——onSystemDialogBlocked 仍在用。）

- [ ] **Step 2: DefaultAgentService 延迟触发**

companion 增加常量：
```kotlin
/** 这些工具不改变设备状态，触发前无需重置到桌面 */
private val OBSERVATION_ONLY_TOOLS = setOf(
    "get_screen_info", "find_node_info", "take_screenshot", "get_installed_apps",
    "wait", "finish", "memory_save", "memory_delete", "memory_list", "load_skill"
)
```
runAgentLoop 内（`while` 之前）声明：`var homeResetDone = false`
工具执行循环内、`val result = ToolRegistry.getInstance().executeTool(toolName, params)` 之前插入：
```kotlin
if (!homeResetDone && toolName !in OBSERVATION_ONLY_TOOLS) {
    ClawAccessibilityService.getInstance()?.pressHome()
    homeResetDone = true
}
```

- [ ] **Step 3: 编译 + 全量单测**

Run: `cd D:/code/ApkClaw && ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 手动冒烟（有设备时）**

手动打开一个非桌面 App → 渠道发送「你好，讲个笑话」→ 手机停留在原 App（不回桌面）；再发送「打开设置」→ 任务执行首个操作前回到桌面再开始。

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: defer home reset until first state-changing tool call"
```

---

### Task 16: 文档更新（README 双语）

**Files:**
- Modify: `README.md`
- Modify: `README_CN.md`

**Interfaces:** 无代码接口；纯文档。

- [ ] **Step 1: 在两份 README 的「Agent 系统」章节之后新增一节（中文版文案，英文版对应翻译）**

```markdown
## 人格 / 会话 / 记忆 / 技能

### 人格（Persona）
- 存储于应用私有目录 `filesDir/agent/persona.md`，可经局域网配置页（设置 > LAN Config，访问 `http://<设备IP>:9527`）的"助手人格"卡片编辑，或直接 `GET/POST /api/persona`。
- 人格只影响语气与角色扮演，不覆盖执行协议与安全约束（组装顺序：人格 → 执行协议 → 记忆 → 技能目录 → 设备信息）。

### 连续对话
- 按（渠道, 发送者）自动保留最近 10 轮"用户消息 + 最终回复"，30 分钟无活动自动开新会话。
- 发送「新对话」或「/new」立即重置当前会话。
- 任务执行中收到的消息进入队列（最多 3 条），任务结束后依次执行。

### 长期记忆
- Agent 通过 `memory_save` / `memory_delete` / `memory_list` 三个工具自主维护 `filesDir/agent/memory.md`（行式条目，上限 50 条，可直接手工编辑）。
- 每次任务的系统提示词自动注入记忆摘要。

### 技能文件系统
- 技能包存放于 `filesDir/agent/skills/<name>/SKILL.md`（头部 frontmatter：name / description）。
- 系统提示词只注入技能目录（名称 + 描述），Agent 需要时调用 `load_skill` 工具加载全文（渐进披露，节省 token）。
- 管理入口：局域网页"技能库"卡片，或 `GET/POST /api/skills`。
```

- [ ] **Step 2: Commit**

```bash
git add README.md README_CN.md && git commit -m "docs: document persona, session, memory and skills features"
```

---

### Task 17: 自动压缩机制（任务内预算分级压缩 + 会话摘要沉淀）

**背景:** 现状只有 `compressHistoryForSend` 的固定规则压缩（按轮次位置触发，不感知上下文实际大小），会话轮次超限是直接丢弃。本任务补齐两块：任务内**按字符预算触发的分级压缩**（超预算才升级激进手段），以及会话轮次被裁剪时**自动摘要沉淀**（LLM 合并为"历史摘要"注入，失败退化为丢弃）。

**设计决策（已锁定）:**
- 现有 tier-0 压缩（`compressHistoryForSend`）**保持每次调用都跑**——尊重作者"Reduce token consumption"的意图，不做"小任务跳过压缩"的改动；本任务只新增超预算时的升级路径。
- 预算用字符粗估（1 token ≈ 1.5 字符），`CHAR_BUDGET = 36000`（约 24k input token），不做精确 tokenizer。
- 摘要沉淀发生在**下一个任务开始时**（而非任务结束时），避免阻塞回复发送；摘要失败静默退化为丢弃（即现状行为）。
- 摘要注入用 `UserMessage`（带 `[历史摘要]` 前缀）而非中途 `SystemMessage`——Anthropic 等提供商对中途 system 角色兼容性差。
- 截断只动"当前任务的执行轮次"（`taskUserIndex` 之后），系统提示词、会话历史、任务本体永不被截断；被删段替换为一行占位 `UserMessage`，保持 AiMessage/tool-result 配对完整。

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/agent/store/ContextBudget.kt`
- Test: `app/src/test/java/com/apk/claw/android/agent/store/ContextBudgetTest.kt`
- Modify: `app/src/main/java/com/apk/claw/android/agent/store/SessionStore.kt`
- Test: `app/src/test/java/com/apk/claw/android/agent/store/SessionStoreTest.kt`（追加用例）
- Modify: `app/src/main/java/com/apk/claw/android/agent/DefaultAgentService.kt`（runAgentLoop 接线）
- Modify: `README.md`、`README_CN.md`

**Interfaces:**
- Consumes: LangChain4j `ChatMessage` 系列（纯 JVM 类）、T6 的 `SessionStore`、`DefaultAgentService.llmClient`
- Produces:
  - `ContextBudget.CHAR_BUDGET: Int`（36000）
  - `ContextBudget.estimateChars(messages: List<ChatMessage>): Int`
  - `ContextBudget.compressAllToolResults(messages: MutableList<ChatMessage>): Boolean`（压缩含保护区的超长工具结果，跳过最新一条 get_screen_info）
  - `ContextBudget.truncateOldestRounds(messages: MutableList<ChatMessage>, protectedPrefix: Int, keepRounds: Int): Int`（返回删除的消息数）
  - `SessionStore.digest(channel, senderId): String` / `pendingDigest(...): List<Turn>` / `updateDigest(channel, senderId, digest)`（截断至 600 字符）/ `clearPendingDigest(...)`

- [ ] **Step 1: 写 ContextBudget 失败测试**

```kotlin
package com.apk.claw.android.agent.store

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    private fun toolResult(tool: String, text: String): ToolExecutionResultMessage =
        ToolExecutionResultMessage.from("1", tool, text)

    @Test
    fun estimateChars_sumsMessageSizes() {
        val msgs = listOf<ChatMessage>(SystemMessage.from("12345"), UserMessage.from("abc"))
        assertEquals(8, ContextBudget.estimateChars(msgs))
    }

    @Test
    fun compressAllToolResults_keepsLatestScreenInfo() {
        val long = """{"isSuccess":true,"data":"${"x".repeat(300)}}"}"""
        val msgs = mutableListOf<ChatMessage>(
            SystemMessage.from("s"),
            UserMessage.from("task"),
            AiMessage.from(listOf(ToolExecutionRequest.builder().id("a").name("get_screen_info").arguments("{}").build())),
            toolResult("get_screen_info", long),
            AiMessage.from(listOf(ToolExecutionRequest.builder().id("b").name("tap").arguments("{}").build())),
            toolResult("tap", long)
        )
        ContextBudget.compressAllToolResults(msgs)
        val screen = msgs[3] as ToolExecutionResultMessage
        val tap = msgs[5] as ToolExecutionResultMessage
        assertTrue(screen.text().length > 200)   // 最新屏幕信息保持完整
        assertTrue(tap.text().length <= 100)     // 其他长工具结果被压成一行摘要
    }

    @Test
    fun truncateOldestRounds_removesEarlyRoundsKeepsRecent() {
        val msgs = mutableListOf<ChatMessage>(SystemMessage.from("s"), UserMessage.from("task"))
        repeat(5) { i ->
            msgs.add(AiMessage.from("think$i"))
            msgs.add(toolResult("tap", """{"isSuccess":true,"data":"r$i"}"""))
        }
        val removed = ContextBudget.truncateOldestRounds(msgs, protectedPrefix = 1, keepRounds = 2)
        assertTrue(removed > 0)
        assertEquals(7, msgs.size)                       // Sys + User + marker + 2×(Ai+Tool)
        assertTrue(msgs[2] is UserMessage)               // 被删段的占位 marker
        assertEquals("think3", (msgs[3] as AiMessage).text())
        assertEquals("think4", (msgs[5] as AiMessage).text())
    }

    @Test
    fun truncateOldestRounds_noopWhenWithinKeep() {
        val msgs = mutableListOf<ChatMessage>(SystemMessage.from("s"), UserMessage.from("t"))
        repeat(2) { i -> msgs.add(AiMessage.from("t$i")); msgs.add(toolResult("tap", "ok")) }
        assertEquals(0, ContextBudget.truncateOldestRounds(msgs, 1, keepRounds = 2))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd D:/code/ApkClaw && ./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.store.ContextBudgetTest"`
Expected: FAIL（Unresolved reference: ContextBudget）

- [ ] **Step 3: 实现 ContextBudget**

```kotlin
package com.apk.claw.android.agent.store

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage

/**
 * 任务内上下文预算：字符级粗估 + 超预算时的分级压缩。
 * 1 token ≈ 1.5 字符粗估，CHAR_BUDGET=36000 ≈ 24k input token。
 */
object ContextBudget {

    const val CHAR_BUDGET = 36000

    private val GSON = Gson()

    fun estimateChars(messages: List<ChatMessage>): Int = messages.sumOf { chars(it) }

    private fun chars(msg: ChatMessage): Int = when (msg) {
        is AiMessage -> (msg.text()?.length ?: 0) +
            (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
        is ToolExecutionResultMessage -> msg.text().length
        is UserMessage -> msg.singleText().length
        is SystemMessage -> msg.text().length
        else -> 0
    }

    /**
     * 激进压缩：对所有轮次（含保护区）的超长工具结果做一行摘要，
     * 但始终保留最新一条 get_screen_info 完整内容（Agent 依赖它感知当前屏幕）。
     */
    fun compressAllToolResults(messages: MutableList<ChatMessage>): Boolean {
        val lastScreenIdx = messages.indexOfLast {
            it is ToolExecutionResultMessage && it.toolName() == "get_screen_info"
        }
        var changed = false
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is ToolExecutionResultMessage && msg.text().length > 100 && i != lastScreenIdx) {
                messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), summarize(msg.text()))
                changed = true
            }
        }
        return changed
    }

    /**
     * 超预算兜底：丢弃当前任务最早的执行轮次，只保留最近 keepRounds 轮。
     * protectedPrefix = 当前任务 UserMessage 的下标；其及之前的消息（系统提示词、
     * 会话历史、任务本体）不动。被删段以一行占位 UserMessage 替代，保持
     * AiMessage / tool-result 配对完整。
     */
    fun truncateOldestRounds(
        messages: MutableList<ChatMessage>,
        protectedPrefix: Int,
        keepRounds: Int
    ): Int {
        val roundStarts = messages.indices.filter { it > protectedPrefix && messages[it] is AiMessage }
        if (roundStarts.size <= keepRounds) return 0
        val cutIndex = roundStarts[roundStarts.size - keepRounds]
        val firstRound = roundStarts.first()
        if (cutIndex <= firstRound) return 0
        val removed = cutIndex - firstRound
        messages[firstRound] = UserMessage.from("[系统提示] 上下文超限，已省略更早的 $removed 条执行消息")
        messages.subList(firstRound + 1, cutIndex).clear()
        return removed
    }

    /** 与 DefaultAgentService.summarizeToolResult 相同的一行摘要逻辑（激进压缩复用） */
    fun summarize(resultJson: String): String {
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = GSON.fromJson(resultJson, mapType)
            val isSuccess = map["isSuccess"] as? Boolean ?: false
            if (isSuccess) {
                val data = map["data"]?.toString() ?: "ok"
                "✓ " + if (data.length > 80) data.take(80) + "..." else data
            } else {
                val error = map["error"]?.toString() ?: "failed"
                "✗ " + if (error.length > 80) error.take(80) + "..." else error
            }
        } catch (_: Exception) {
            if (resultJson.length > 80) resultJson.take(80) + "..." else resultJson
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: 4 个测试 PASS。

- [ ] **Step 5: 写 SessionStore 摘要相关失败测试（追加到 SessionStoreTest）**

```kotlin
@Test fun evictedTurns_goToPendingDigest() {
    SessionStore.init(tmp.root)
    repeat(12) { i -> SessionStore.appendTurn(Channel.TELEGRAM, "u", "q$i", "a$i") }
    val pending = SessionStore.pendingDigest(Channel.TELEGRAM, "u")
    assertEquals(2, pending.size)
    assertEquals("q0", pending[0].user)
}

@Test fun digest_roundtrip() {
    SessionStore.init(tmp.root)
    SessionStore.appendTurn(Channel.TELEGRAM, "u", "q", "a")   // 确保会话存在
    SessionStore.updateDigest(Channel.TELEGRAM, "u", "用户偏好摘要")
    assertEquals("用户偏好摘要", SessionStore.digest(Channel.TELEGRAM, "u"))
}

@Test fun updateDigest_capsLength() {
    SessionStore.init(tmp.root)
    SessionStore.appendTurn(Channel.TELEGRAM, "u", "q", "a")
    SessionStore.updateDigest(Channel.TELEGRAM, "u", "x".repeat(1000))
    assertEquals(600, SessionStore.digest(Channel.TELEGRAM, "u").length)
}

@Test fun clearPendingDigest_emptiesQueue() {
    SessionStore.init(tmp.root)
    repeat(12) { i -> SessionStore.appendTurn(Channel.TELEGRAM, "u", "q$i", "a$i") }
    SessionStore.clearPendingDigest(Channel.TELEGRAM, "u")
    assertTrue(SessionStore.pendingDigest(Channel.TELEGRAM, "u").isEmpty())
}

@Test fun sessionsjson_oldFormatStillLoads() {
    // 旧版本 sessions.json（无 digest 字段）加载后不崩
    tmp.root.resolve("sessions.json").writeText(
        """{"TELEGRAM:u":{"turns":[{"user":"q","assistant":"a"}],"lastActive":9999999999999}}""")
    SessionStore.init(tmp.root)
    assertEquals(1, SessionStore.history(Channel.TELEGRAM, "u").size)
    assertEquals("", SessionStore.digest(Channel.TELEGRAM, "u"))
}
```

- [ ] **Step 6: 跑测试确认失败**

Run: `cd D:/code/ApkClaw && ./gradlew :app:testDebugUnitTest --tests "com.apk.claw.android.agent.store.SessionStoreTest"`
Expected: 新增 5 个用例 FAIL（Unresolved reference: pendingDigest 等），原 8 个仍 PASS。

- [ ] **Step 7: 修改 SessionStore**

`SessionData` 与常量、`init` 的清洗逻辑、`appendTurn` 的驱逐去向、四个新函数：

```kotlin
// SessionData 改为（digest/pendingDigest 用 var，Gson 反序列化旧文件时可能为 null，需清洗）：
private data class SessionData(
    val turns: MutableList<Turn>,
    var lastActive: Long,
    var digest: String = "",
    var pendingDigest: MutableList<Turn> = mutableListOf()
)

// 常量区追加：
const val MAX_PENDING_DIGEST = 20
const val MAX_DIGEST_CHARS = 600

// init() 中加载 JSON 后追加清洗（Gson 绕过构造器，缺失字段为 null）：
sessions.entries.removeAll { it.value?.turns == null }   // turns 为 null 的脏数据直接丢弃
sessions.values.forEach {
    if (it.digest == null) it.digest = ""
    if (it.pendingDigest == null) it.pendingDigest = mutableListOf()
}

// appendTurn 的驱逐循环改为沉淀而非丢弃：
while (s.turns.size > MAX_TURNS) {
    s.pendingDigest.add(s.turns.removeAt(0))
}
while (s.pendingDigest.size > MAX_PENDING_DIGEST) s.pendingDigest.removeAt(0)

// 新增四个函数：
fun digest(channel: Channel, senderId: String): String =
    sessions[key(channel, senderId)]?.digest ?: ""

fun pendingDigest(channel: Channel, senderId: String): List<Turn> =
    sessions[key(channel, senderId)]?.pendingDigest?.toList() ?: emptyList()

fun updateDigest(channel: Channel, senderId: String, digest: String) {
    val s = sessions[key(channel, senderId)] ?: return
    s.digest = digest.take(MAX_DIGEST_CHARS)
    persist()
}

fun clearPendingDigest(channel: Channel, senderId: String) {
    val s = sessions[key(channel, senderId)] ?: return
    if (s.pendingDigest.isNotEmpty()) {
        s.pendingDigest.clear()
        persist()
    }
}
```
（`getOrPut` 的构造 lambda 同步补两个默认参数：`SessionData(mutableListOf(), 0L, "", mutableListOf())`。Kotlin 对非空类型判 null 会有编译警告，属预期，保留即可。）

- [ ] **Step 8: 跑测试确认通过**

Run: 同 Step 6。Expected: 13 个用例全 PASS。

- [ ] **Step 9: DefaultAgentService 接线（三处）**

① `runAgentLoop` 开头（preCheck 之后）摘要沉淀：
```kotlin
// 摘要沉淀：把上一批被裁剪的会话轮次合并进 digest（失败则退化为丢弃）
val pending = SessionStore.pendingDigest(request.channel, request.senderId)
if (pending.isNotEmpty()) {
    summarizeDigest(SessionStore.digest(request.channel, request.senderId), pending)?.let {
        SessionStore.updateDigest(request.channel, request.senderId, it)
    }
    SessionStore.clearPendingDigest(request.channel, request.senderId)
}
```

② 消息构建处（T7 的历史注入之前加 digest 注入；任务 UserMessage 之后记录索引）：
```kotlin
messages.add(SystemMessage.from(fullSystemPrompt))
val digest = SessionStore.digest(request.channel, request.senderId)
if (digest.isNotBlank()) {
    messages.add(UserMessage.from("[历史摘要] 以下是本会话更早轮次的摘要：\n$digest"))
}
SessionStore.history(request.channel, request.senderId).forEach { turn -> /* T7 原有注入 */ }
messages.add(UserMessage.from(userPrompt))
val taskUserIndex = messages.size - 1   // 截断保护边界
```

③ 循环体内（`compressHistoryForSend(messages)` 之后）预算分级：
```kotlin
compressHistoryForSend(messages)
if (ContextBudget.estimateChars(messages) > ContextBudget.CHAR_BUDGET) {
    ContextBudget.compressAllToolResults(messages)
    if (ContextBudget.estimateChars(messages) > ContextBudget.CHAR_BUDGET) {
        ContextBudget.truncateOldestRounds(messages, taskUserIndex, KEEP_RECENT_ROUNDS)
    }
}
```

新增私有函数（类尾部）：
```kotlin
/** 用一次无工具的 LLM 调用合并旧摘要与新增轮次；失败返回 null（退化为丢弃，即现状行为） */
private fun summarizeDigest(oldDigest: String, turns: List<SessionStore.Turn>): String? {
    val sb = StringBuilder()
    if (oldDigest.isNotBlank()) sb.append("既有摘要：\n").append(oldDigest).append("\n\n")
    sb.append("新增对话：\n")
    turns.forEach {
        sb.append("用户：").append(it.user).append("\n")
        sb.append("助手：").append(it.assistant).append("\n\n")
    }
    val msgs = listOf<ChatMessage>(
        SystemMessage.from(
            "你是会话摘要器。把既有摘要与新增对话合并为一份结构化摘要（Claude Code compact 同款格式），" +
                "只保留对后续任务有用的信息，总长不超过 300 字，直接输出正文：\n" +
                "## 背景与目标\n（用户在做什么、为什么）\n" +
                "## 用户偏好与关键事实\n（稳定的事实、偏好、约定）\n" +
                "## 未完成事项\n（待办与悬而未决的问题；没有则写\"无\"）"
        ),
        UserMessage.from(sb.toString())
    )
    return try {
        llmClient.chat(msgs, emptyList()).text?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        XLog.w(TAG, "digest summarization failed, evicted turns dropped instead", e)
        null
    }
}
```
（补 `import com.apk.claw.android.agent.store.ContextBudget`。）

- [ ] **Step 10: 编译 + 全量单测**

Run: `cd D:/code/ApkClaw && ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，store 包 13+ 个用例全 PASS。

- [ ] **Step 11: 手动冒烟（有设备时）**

1. 连续发 12+ 条消息（触发轮次裁剪）→ 下一条消息的 XLog 应出现摘要 LLM 调用；随后问"我们之前聊了什么"→ 回答应包含摘要内容而非一无所知。
2. 构造一个长任务（如"反复打开设置再返回，共 30 次"）→ XLog 中 `compressHistoryForSend` 日志之后若出现预算升级，总字符数应回落到 36000 以下。

- [ ] **Step 12: README 双语补充（T16 的"连续对话"小节追加两条）**

```markdown
- 会话轮次超过上限时，被裁剪的轮次会在下次任务开始时自动摘要为结构化"历史摘要"（背景/偏好与事实/未完成事项）注入，而非直接丢弃；摘要调用失败时退化为丢弃。
- 任务内上下文超过字符预算（约 24k token）时自动升级压缩：先压缩全部工具结果（始终保留最新屏幕信息），仍超则丢弃最早的执行轮次。
```

- [ ] **Step 13: Commit**

```bash
git add -A && git commit -m "feat: budget-based context compression and session digest summarization"
```

---

## 验收清单（全部任务完成后）

1. `./gradlew assembleDebug :app:testDebugUnitTest` 全绿。
2. 渠道对话体现人格；`persona.md` 清空后恢复默认语气。
3. 同一发送者连续多轮指代消解正确（"我叫什么"测试）；不同发送者互不可见；「新对话」重置。
4. 任务执行中的消息排队而非拒绝，队列满时明确告知。
5. 「记住 X」→ 重启 App → 「X 是什么」跨会话生效；`memory.md` 可手工编辑且被容忍解析。
6. 网页上传技能后，系统提示词出现目录；Agent 执行相关任务时会调用 `load_skill`。
7. 纯聊天不再把手机重置到桌面；设备操作任务首个动作前仍从桌面开始。
8. 超过 10 轮的会话：被裁轮次经 LLM 摘要为"[历史摘要]"注入，Agent 仍能回忆早期内容；断网（摘要失败）时退化为丢弃，功能不中断。
9. 超长任务：上下文字符数始终被压回 `CHAR_BUDGET`（36000）以下，且最新一条屏幕信息保持完整。

## 风险与已锁定的缓解

- **Token 增长**：会话 10 轮上限、记忆 50 条上限、技能只注入目录——三者都有硬上限；任务内由"tier-0 规则压缩 + T17 字符预算分级（激进压缩→截断）"双保险兜底；`compressHistoryForSend` 继续负责常规压缩。
- **人格越权**：PromptComposer 在人格段尾部固定追加"不得覆盖协议与安全约束"，且协议段位于人格之后。
- **多渠道 senderId 语义不一**（FeiShu 用 openId、Discord 用 channelId 等）：会话键只要求"同一用户稳定"，各渠道采用与 `getLastSenderId()` 一致的身份即可；匿名/拿不到 ID 的场景 `appendTurn` 自动忽略（退化为无会话，不报错）。
- **排队消息时效**：排队上限 3 条 + 明确的位次反馈，避免陈旧指令堆积执行。

## 非目标（本次不做）

- 定时任务/主动推送（cron）——代码中已有 `schedule_task` 等预留痕迹，另行规划。
- 发送者白名单/配对审批——T1 的 senderId 透传为它铺好了路，另行规划。
- 多模型路由、Web 对话界面、多媒体（图片/语音）输入、多 Agent。
- 按发送者隔离的独立记忆（R5）：当前 memory.md 全设备共享（单用户假设，与 Claude Code 的单用户多项目对位）；多用户场景需向工具执行上下文注入 senderId，改动大。
- 技能目录附加资源文件（Claude Code 渐进披露第 3 层：reference.md/scripts 按需读取）：v1 技能只含 SKILL.md 单文件。

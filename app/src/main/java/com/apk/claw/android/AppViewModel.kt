package com.apk.claw.android

import android.os.PowerManager
import androidx.lifecycle.ViewModel
import com.apk.claw.android.ClawApplication.Companion.appViewModelInstance
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.store.InAppChatStore
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.channel.ChannelSetup
import com.apk.claw.android.channel.inapp.InAppChannelHandler
import com.apk.claw.android.service.ForegroundService
import com.apk.claw.android.floating.FloatingCircleManager
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.service.KeepAliveJobService
import com.apk.claw.android.service.TaskScheduler
import com.apk.claw.android.ui.home.HomeActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.apk.claw.android.voice.SherpaSenseVoiceEngine
import com.apk.claw.android.voice.VoiceEngine
import com.apk.claw.android.voice.VoiceInputAction
import com.apk.claw.android.voice.VoiceInputGate
import com.apk.claw.android.voice.VoiceModelStore

class AppViewModel : ViewModel() {

    companion object {
        private const val TAG = "AppViewModel"

        /** 离线聆听最长时长，防止忘记点击结束 */
        private const val OFFLINE_VOICE_MAX_DURATION_MS = 30_000L
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null

    private var _commonInitialized = false

    val taskOrchestrator = TaskOrchestrator(
        agentConfigProvider = { getAgentConfig() },
        onTaskFinished = { /* 刷新 */ }
    )

    private val channelSetup = ChannelSetup(taskOrchestrator = taskOrchestrator)

    val inProgressTaskMessageId: String get() = taskOrchestrator.inProgressTaskMessageId
    val inProgressTaskChannel: Channel? get() = taskOrchestrator.inProgressTaskChannel

    fun init() {
        initCommon()
        initAgent()
    }

    fun initCommon() {
        if (_commonInitialized) return
        _commonInitialized = true
    }

    fun initAgent() {
        if (!KVUtils.hasLlmConfig()) return
        taskOrchestrator.initAgent()
    }

    fun getAgentConfig(): AgentConfig {
        var baseUrl = KVUtils.getLlmBaseUrl().trim()
        if (baseUrl.isEmpty()) baseUrl = "https://api.openai.com/v1"
        return AgentConfig.Builder()
            .apiKey(KVUtils.getLlmApiKey())
            .baseUrl(baseUrl)
            .modelName(KVUtils.getLlmModelName())
            .contextWindowTokens(KVUtils.getLlmContextWindow())
            .confirmDangerousOps(KVUtils.getConfirmDangerousOps())
            .verifyResults(KVUtils.getVerifyResults())
            .visionEnabled(KVUtils.getVisionEnabled())
            .temperature(0.1)
            .maxIterations(60)
            .build()
    }

    fun updateAgentConfig(): Boolean = taskOrchestrator.updateAgentConfig()

    /**
     * 配置变更后的轻量通道刷新：复用已有 handler 实例重新读取各通道配置并重启连接。
     * 不能走完整的 [afterInit]（其内部 setup() 会重建 handler；历史上旧实例不被断开，
     * 轮询线程越叠越多，导致同一条消息被投递多次、任务重复排队执行）。
     */
    fun refreshChannels() = ChannelManager.reinitFromStorage()

    fun afterInit() {
        acquireScreenWakeLock()
        ForegroundService.start(ClawApplication.instance)
        KeepAliveJobService.schedule(ClawApplication.instance)
        ConfigServerManager.autoStartIfNeeded(ClawApplication.instance)
        // F7：应用启动（含强停后重启）时恢复定时任务计划；开机路径由 BootReceiver 覆盖
        TaskScheduler.rescheduleAll(ClawApplication.instance)
        if (android.provider.Settings.canDrawOverlays(ClawApplication.instance)) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                appViewModelInstance.showFloatingCircle()
            }
        }
        channelSetup.setup()
    }


    /**
     * 获取亮屏锁，防止息屏后无障碍服务无法操作
     */
    private fun acquireScreenWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = ClawApplication.instance.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
            ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "ApkClaw::ScreenWakeLock"
        ).apply {
            acquire()
        }
        XLog.i(TAG, "亮屏锁已获取")
    }

    /**
     * 释放亮屏锁
     */
    private fun releaseScreenWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                XLog.i(TAG, "亮屏锁已释放")
            }
        }
        wakeLock = null
    }

    /**
     * 显示圆形悬浮窗
     */
    fun showFloatingCircle() {
        try {
            FloatingCircleManager.show(ClawApplication.instance)
            FloatingCircleManager.onFloatClick = {
                XLog.d(TAG, "Floating circle clicked")
                if (offlineSessionActive) stopOfflineListening() else bringAppToForeground()
            }
            // 静止长按触发：按住期间录音
            FloatingCircleManager.onFloatLongClick = {
                XLog.d(TAG, "Floating circle long pressed, listening")
                startVoiceInput()
            }
            // 松手：结束录音并识别（会话已因放弃/超时结束时为空操作）
            FloatingCircleManager.onFloatVoiceRelease = {
                XLog.d(TAG, "Floating circle released")
                if (offlineSessionActive) stopOfflineListening()
            }
            // 长按中转为拖动等：丢弃本次录音，不识别不提示
            FloatingCircleManager.onFloatVoiceCancel = {
                XLog.d(TAG, "Floating circle voice hold cancelled")
                if (offlineSessionActive) cancelOfflineListening()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to show floating circle: ${e.message}")
        }
    }

    /**
     * F6：悬浮球长按语音输入。识别文本走与聊天页相同的 InApp 入口。
     * 前置校验见 VoiceInputGate：未安装离线模型时引导去下载页；
     * 已安装但缺 RECORD_AUDIO 时走权限 trampoline。失败只 toast 提示。
     */
    fun startVoiceInput() {
        val app = ClawApplication.instance
        mainHandler.post {
            when (
                VoiceInputGate.decide(
                    hasRecordAudio = androidx.core.content.ContextCompat.checkSelfPermission(
                        app, android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
                    offlineModelAvailable = VoiceModelStore.isInstalled(app),
                )
            ) {
                VoiceInputAction.GO_MODEL_DOWNLOAD -> {
                    toast(app.getString(R.string.voice_model_need_download))
                    openVoiceModelPage(app)
                }
                VoiceInputAction.REQUEST_PERMISSION ->
                    com.apk.claw.android.ui.voice.VoicePermissionActivity.start(app)
                VoiceInputAction.START_OFFLINE -> beginOfflineListening(app)
            }
        }
    }

    private fun openVoiceModelPage(context: android.content.Context) {
        try {
            context.startActivity(
                android.content.Intent(context, com.apk.claw.android.ui.settings.VoiceModelActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to open voice model page: ${e.message}")
        }
    }

    // —— F6 离线语音会话状态（仅主线程访问） ——

    private var offlineEngine: SherpaSenseVoiceEngine? = null
    private var offlineSessionActive = false
    private var offlineTimeout: Runnable? = null

    /** 长按按住期间开始离线聆听；松手结束识别（onFloatVoiceRelease）；按住中拖动放弃（onFloatVoiceCancel）；30 秒超时自动结束 */
    private fun beginOfflineListening(app: android.content.Context) {
        if (offlineSessionActive) return // 聆听中忽略重复长按
        val engine = SherpaSenseVoiceEngine(app)
        offlineEngine = engine
        offlineSessionActive = true
        FloatingCircleManager.setListeningState()
        engine.start(object : VoiceEngine.Listener {
            override fun onPartial(text: String) {
                // SenseVoice 为整段解码，无流式中间结果；接口保留给未来流式引擎
                mainHandler.post { FloatingCircleManager.updateListeningPartial(text) }
            }

            override fun onFinal(text: String) {
                mainHandler.post { finishOfflineListening(text) }
            }

            override fun onError(message: String) {
                mainHandler.post { abortOfflineListening(message) }
            }
        })
        offlineTimeout = Runnable { engine.stop() }.also {
            mainHandler.postDelayed(it, OFFLINE_VOICE_MAX_DURATION_MS)
        }
    }

    private fun stopOfflineListening() {
        offlineTimeout?.let(mainHandler::removeCallbacks)
        offlineTimeout = null
        offlineEngine?.stop()
        // 松手结束：录音停止，SenseVoice 进入整段解码（1-3 秒），给出处理中提示
        toast(ClawApplication.instance.getString(R.string.voice_processing))
    }

    /** 按住中转为拖动等取消场景：丢弃录音、不识别、不提示（引擎 cancel 后不再有任何回调） */
    private fun cancelOfflineListening() {
        offlineEngine?.cancel()
        cleanupOfflineSession()
    }

    private fun finishOfflineListening(text: String) {
        val app = ClawApplication.instance
        cleanupOfflineSession()
        if (text.isEmpty() || !KVUtils.hasLlmConfig()) {
            toast(app.getString(R.string.voice_failed))
            return
        }
        InAppChatStore.append(InAppChatStore.Role.USER, "🎙 $text")
        ChannelManager.dispatchMessage(
            Channel.IN_APP, text, "voice-${System.currentTimeMillis()}",
            com.apk.claw.android.channel.inapp.InAppChannelHandler.SENDER_ID
        )
    }

    private fun abortOfflineListening(message: String) {
        XLog.w(TAG, "Offline voice error: $message")
        cleanupOfflineSession()
        toast(ClawApplication.instance.getString(R.string.voice_failed))
    }

    private fun cleanupOfflineSession() {
        offlineTimeout?.let(mainHandler::removeCallbacks)
        offlineTimeout = null
        offlineEngine = null
        offlineSessionActive = false
        FloatingCircleManager.setIdleState()
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(ClawApplication.instance, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 将应用带回前台
     */
    private fun bringAppToForeground() {
        val context = ClawApplication.instance
        val intent = android.content.Intent(context, HomeActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }

    fun isTaskRunning(): Boolean = taskOrchestrator.isTaskRunning()

    fun cancelCurrentTask() = taskOrchestrator.cancelCurrentTask()

    fun startNewTask(channel: Channel, senderId: String, task: String, messageID: String) =
        taskOrchestrator.startNewTask(channel, senderId, task, messageID)

    /** F7：定时任务到点后的派发入口，与渠道消息共用队列与任务锁；@param automated 标注无人值守来源（C3 准入据此区分） */
    fun dispatchTask(channel: Channel, senderId: String, message: String, messageID: String, automated: Boolean = false) =
        channelSetup.dispatch(channel, message, messageID, senderId, automated)

    /**
     * F5：发送 App 内消息（聊天页输入 / F6 语音识别共用入口）。
     * @return false 表示 LLM 未配置，调用方应提示用户
     */
    fun sendInAppMessage(text: String): Boolean {
        if (!KVUtils.hasLlmConfig()) return false
        InAppChatStore.append(InAppChatStore.Role.USER, text)
        ChannelManager.dispatchMessage(
            Channel.IN_APP, text,
            "inapp-${System.currentTimeMillis()}",
            InAppChannelHandler.SENDER_ID
        )
        return true
    }

    private fun trySendScreenshot(channel: Channel, filePath: String, messageID: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                XLog.w(TAG, "截图文件不存在: $filePath")
                return
            }
            val imageBytes = file.readBytes()
            ChannelManager.sendImage(channel, imageBytes, messageID)
        } catch (e: Exception) {
            XLog.e(TAG, "发送截图失败", e)
        }
    }
}

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

class AppViewModel : ViewModel() {

    companion object {
        private const val TAG = "AppViewModel"
    }

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
            .temperature(0.1)
            .maxIterations(60)
            .build()
    }

    fun updateAgentConfig(): Boolean = taskOrchestrator.updateAgentConfig()

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
                bringAppToForeground()
            }
            FloatingCircleManager.onFloatLongClick = {
                XLog.d(TAG, "Floating circle long clicked")
                startVoiceInput()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to show floating circle: ${e.message}")
        }
    }

    /**
     * F6：悬浮球长按语音输入。识别文本走与聊天页相同的 InApp 入口；
     * 不需要 RECORD_AUDIO 权限（录音由系统语音服务完成）。
     */
    private fun startVoiceInput() {
        val app = ClawApplication.instance
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!android.speech.SpeechRecognizer.isRecognitionAvailable(app)) {
                toast(app.getString(R.string.voice_unavailable))
                openChatForVoiceFallback()
                return@post
            }
            val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(app)
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
            }
            recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onResults(results: android.os.Bundle?) {
                    val text = results
                        ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    recognizer.destroy()
                    if (text.isEmpty() || !KVUtils.hasLlmConfig()) {
                        toast(app.getString(R.string.voice_failed))
                        openChatForVoiceFallback()
                        return
                    }
                    InAppChatStore.append(InAppChatStore.Role.USER, "🎙 $text")
                    ChannelManager.dispatchMessage(
                        Channel.IN_APP, text, "voice-${System.currentTimeMillis()}",
                        com.apk.claw.android.channel.inapp.InAppChannelHandler.SENDER_ID
                    )
                }

                override fun onError(error: Int) {
                    XLog.w(TAG, "Speech recognition error: $error")
                    recognizer.destroy()
                    toast(app.getString(R.string.voice_failed))
                    openChatForVoiceFallback()
                }

                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    toast(app.getString(R.string.voice_listening))
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            recognizer.startListening(intent)
        }
    }

    /** 语音不可用/失败时的文字输入兜底：打开聊天页 */
    private fun openChatForVoiceFallback() {
        try {
            com.apk.claw.android.ui.chat.ChatActivity.start(ClawApplication.instance)
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to open chat as voice fallback", e)
        }
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

    /** F7：定时任务到点后的派发入口，与渠道消息共用队列与任务锁 */
    fun dispatchTask(channel: Channel, senderId: String, message: String, messageID: String) =
        channelSetup.dispatch(channel, message, messageID, senderId)

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

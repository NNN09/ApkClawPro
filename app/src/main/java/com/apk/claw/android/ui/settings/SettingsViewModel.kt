package com.apk.claw.android.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.QRCodeDialog
import com.apk.claw.android.utils.XLog
import com.apk.claw.android.voice.VoiceModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * SettingsActivity 的 ViewModel
 */
class SettingsViewModel : ViewModel() {

    // 设置项数据 Flow（用于动态更新）
    private val _settingItems = MutableStateFlow<Map<String, SettingValue>>(emptyMap())
    val settingItems: StateFlow<Map<String, SettingValue>> = _settingItems

    // 菜单点击事件
    private val _menuClickEvent = MutableStateFlow<MenuAction?>(null)
    val menuClickEvent: StateFlow<MenuAction?> = _menuClickEvent

    init {
        refresh()
    }

    fun refresh() {
        val map = mapOf(
            MenuAction.LLM_CONFIG.name to llmStatus(),
            MenuAction.SKILLS.name to skillsStatus(),
            MenuAction.PERSONA.name to personaStatus(),
            MenuAction.VOICE_MODEL.name to voiceModelStatus(),
            MenuAction.SCHEDULES.name to schedulesStatus(),
            MenuAction.TRIGGERS.name to triggerStatus(),
            MenuAction.POLICIES.name to policiesStatus(),
            MenuAction.DINGDING.name to channelStatus(KVUtils.getDingtalkAppKey().isNotEmpty() && KVUtils.getDingtalkAppSecret().isNotEmpty()),
            MenuAction.FEISHU.name to channelStatus(KVUtils.getFeishuAppId().isNotEmpty() && KVUtils.getFeishuAppSecret().isNotEmpty()),
            MenuAction.QQ.name to channelStatus(KVUtils.getQqAppId().isNotEmpty() && KVUtils.getQqAppSecret().isNotEmpty()),
            MenuAction.DISCORD.name to channelStatus(KVUtils.getDiscordBotToken().isNotEmpty()),
            MenuAction.TELEGRAM.name to channelStatus(KVUtils.getTelegramBotToken().isNotEmpty()),
            MenuAction.WECHAT.name to channelStatus(KVUtils.getWechatBotToken().isNotEmpty()),
            MenuAction.COMPLIANCE_CONFIG.name to complianceStatus(),
            MenuAction.LAN_CONFIG.name to lanStatus()
        )
        _settingItems.value = map
    }

    private fun channelStatus(bound: Boolean): SettingValue.Status {
        val text = ClawApplication.instance.getString(if (bound) R.string.common_bound else R.string.common_unbound)
        return SettingValue.Status(text, active = bound)
    }

    /** 离线语音模型入口：已安装（尾部成功色）或未安装 */
    private fun voiceModelStatus(): SettingValue.Status {
        val ctx = ClawApplication.instance
        val installed = VoiceModelStore.isInstalled(ctx)
        return SettingValue.Status(
            text = ctx.getString(if (installed) R.string.voice_model_installed else R.string.voice_model_missing),
            subtitle = ctx.getString(R.string.voice_model_size),
            active = installed
        )
    }

    private fun llmStatus(): SettingValue.Status {
        return if (KVUtils.hasLlmConfig()) {
            // 已配置：模型名放副标题，尾部只留箭头
            SettingValue.Status("", subtitle = KVUtils.getLlmModelName().ifEmpty { null }, active = true)
        } else {
            SettingValue.Status(ClawApplication.instance.getString(R.string.common_unconfigured))
        }
    }

    private fun lanStatus(): SettingValue.Status {
        return if (ConfigServerManager.isRunning()) {
            SettingValue.Status(
                ClawApplication.instance.getString(R.string.lan_config_running),
                subtitle = ConfigServerManager.getAddress(),
                active = true
            )
        } else {
            SettingValue.Status(ClawApplication.instance.getString(R.string.lan_config_stopped))
        }
    }

    /** 自动化与合规入口：仅在夜间静默开启时显示时段摘要 */
    private fun complianceStatus(): SettingValue.Status {
        if (!KVUtils.getQuietHoursEnabled()) return SettingValue.Status("")
        val start = com.apk.claw.android.compliance.ComplianceConfig.formatHm(KVUtils.getQuietStartMin())
        val end = com.apk.claw.android.compliance.ComplianceConfig.formatHm(KVUtils.getQuietEndMin())
        return SettingValue.Status(
            "",
            subtitle = ClawApplication.instance.getString(R.string.compliance_status_quiet, start, end),
            active = true
        )
    }

    /** 技能入口：已安装技能数 */
    private fun skillsStatus(): SettingValue.Status {
        val count = com.apk.claw.android.agent.store.SkillStore.list().size
        if (count == 0) return SettingValue.Status("")
        return SettingValue.Status("", subtitle = ClawApplication.instance.getString(R.string.skills_status_count, count))
    }

    /** 人设入口：已设置时显示状态 */
    private fun personaStatus(): SettingValue.Status {
        if (com.apk.claw.android.agent.store.PersonaStore.get().isBlank()) return SettingValue.Status("")
        return SettingValue.Status("", subtitle = ClawApplication.instance.getString(R.string.persona_status_set))
    }

    /** 定时任务入口：任务数与启用数 */
    private fun schedulesStatus(): SettingValue.Status {
        val tasks = com.apk.claw.android.agent.store.ScheduledTaskStore.list()
        if (tasks.isEmpty()) return SettingValue.Status("")
        return SettingValue.Status(
            "",
            subtitle = ClawApplication.instance.getString(R.string.schedules_status_count, tasks.size, tasks.count { it.enabled })
        )
    }

    /** 事件触发入口：总开关状态 + 规则数 */
    private fun triggerStatus(): SettingValue.Status {
        val config = com.apk.claw.android.trigger.TriggerRuleStore.get()
        val subtitle = if (config.rules.isEmpty()) null
        else ClawApplication.instance.getString(R.string.triggers_status_rules, config.rules.size)
        return SettingValue.Status(
            ClawApplication.instance.getString(
                if (config.enabled) R.string.triggers_status_on else R.string.triggers_status_off
            ),
            subtitle = subtitle,
            active = config.enabled
        )
    }

    /** 单应用策略入口：策略条数 */
    private fun policiesStatus(): SettingValue.Status {
        val count = com.apk.claw.android.compliance.AppPolicyStore.list().size
        if (count == 0) return SettingValue.Status("")
        return SettingValue.Status("", subtitle = ClawApplication.instance.getString(R.string.policies_status_count, count))
    }

    /**
     * 处理菜单项点击
     */
    fun onMenuItemClick(action: MenuAction) {
        _menuClickEvent.value = action
    }

    /**
     * 清空菜单点击事件
     */
    fun clearMenuClickEvent() {
        _menuClickEvent.value = null
    }

    /**
     * 微信 iLink 扫码登录流程
     */
    fun startWeChatQrLogin(context: Context) {
        viewModelScope.launch {
            val loadingDialog = com.apk.claw.android.widget.LoadingDialog.show(
                context = context,
                message = context.getString(R.string.channel_config_wechat_scanning)
            )
            try {
                val apiClient = com.apk.claw.android.channel.wechat.WeChatApiClient()
                val qrResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    apiClient.getQrCode()
                }
                loadingDialog.dismiss()
                if (qrResult == null) {
                    Toast.makeText(context, R.string.wechat_qr_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 用 qrcode 值通过 ZXing 本地生成二维码 Bitmap
                val qrBitmap = generateQrBitmap(qrResult.qrcodeImgContent, 512)
                if (qrBitmap == null) {
                    Toast.makeText(context, R.string.wechat_qr_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                var pollingJob: Job? = null
                val dialog = QRCodeDialog.show(
                    context = context,
                    title = context.getString(R.string.channel_config_wechat_title),
                    subtitle = context.getString(R.string.channel_config_wechat_tip),
                    qrBitmap = qrBitmap,
                    onClose = { pollingJob?.cancel() }
                )
                pollingJob = startWeChatQrPolling(context, dialog, apiClient, qrResult.qrcode)
            } catch (e: Exception) {
                loadingDialog.dismiss()
                XLog.e("SettingsViewModel", "微信扫码登录失败", e)
                Toast.makeText(context, R.string.wechat_qr_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startWeChatQrPolling(
        context: Context,
        dialog: QRCodeDialog,
        apiClient: com.apk.claw.android.channel.wechat.WeChatApiClient,
        qrcode: String
    ): Job {
        return viewModelScope.launch {
            while (isActive) {
                delay(2000)
                if (!dialog.isShowing) break
                try {
                    val authResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        apiClient.pollQrCodeStatus(qrcode)
                    }
                    if (authResult != null) {
                        // 扫码确认成功，保存 token 和 baseurl
                        KVUtils.setWechatBotToken(authResult.botToken)
                        KVUtils.setWechatApiBaseUrl(authResult.baseUrl)
                        ChannelManager.reinitWeChatFromStorage()
                        dialog.showStatusOverlay(
                            ClawApplication.instance.getString(R.string.channel_config_wechat_confirmed)
                        )
                        refresh()
                        delay(1500)
                        dialog.dismiss()
                        break
                    }
                } catch (_: Exception) {
                    // 网络异常静默重试
                }
            }
        }
    }


    /**
     * 切换局域网配置服务开关
     */
    fun toggleConfigServer(context: Context): String {
        return if (ConfigServerManager.isRunning()) {
            ConfigServerManager.stop()
            KVUtils.setConfigServerEnabled(false)
            refresh()
            ClawApplication.instance.getString(R.string.lan_config_stopped)
        } else {
            val started = ConfigServerManager.start(context)
            if (started) {
                KVUtils.setConfigServerEnabled(true)
                refresh()
                ConfigServerManager.getAddress()
                    ?: ClawApplication.instance.getString(R.string.lan_config_running)
            } else {
                ClawApplication.instance.getString(R.string.lan_config_no_wifi)
            }
        }
    }

    fun isDingtalkBound(): Boolean {
        return KVUtils.getDingtalkAppKey().isNotEmpty() && KVUtils.getDingtalkAppSecret().isNotEmpty()
    }

    fun isFeishuBound(): Boolean {
        return KVUtils.getFeishuAppId().isNotEmpty() && KVUtils.getFeishuAppSecret().isNotEmpty()
    }

    fun isQqBound(): Boolean {
        return KVUtils.getQqAppId().isNotEmpty() && KVUtils.getQqAppSecret().isNotEmpty()
    }

    fun isDiscordBound(): Boolean {
        return KVUtils.getDiscordBotToken().isNotEmpty()
    }

    fun isTelegramBound(): Boolean {
        return KVUtils.getTelegramBotToken().isNotEmpty()
    }

    fun isWechatBound(): Boolean {
        return KVUtils.getWechatBotToken().isNotEmpty()
    }

    fun unbindDingtalk() {
        KVUtils.setDingtalkAppKey("")
        KVUtils.setDingtalkAppSecret("")
        ChannelManager.reinitDingTalkFromStorage()
        refresh()
    }

    fun unbindFeishu() {
        KVUtils.setFeishuAppId("")
        KVUtils.setFeishuAppSecret("")
        ChannelManager.reinitFeiShuFromStorage()
        refresh()
    }

    fun unbindQq() {
        KVUtils.setQqAppId("")
        KVUtils.setQqAppSecret("")
        ChannelManager.reinitQQFromStorage()
        refresh()
    }

    fun unbindDiscord() {
        KVUtils.setDiscordBotToken("")
        ChannelManager.reinitDiscordFromStorage()
        refresh()
    }

    fun unbindTelegram() {
        KVUtils.setTelegramBotToken("")
        ChannelManager.reinitTelegramFromStorage()
        refresh()
    }

    fun unbindWeChat() {
        // 清除持久化的 contextToken（对应 2.0.1 clearContextTokensForAccount）
        val accountId = KVUtils.getWechatBotToken().substringBefore(":").ifEmpty { "default" }
        com.apk.claw.android.channel.wechat.WeChatInbound.clearContextTokensForAccount(accountId)
        KVUtils.setWechatBotToken("")
        KVUtils.setWechatApiBaseUrl("")
        KVUtils.setWechatUpdatesCursor("")
        ChannelManager.reinitWeChatFromStorage()
        refresh()
    }

    /**
     * 设置值密封类
     */
    sealed class SettingValue {
        data class Text(val text: String) : SettingValue()

        /**
         * 带状态的设置项：text 为尾部状态文字，subtitle 为第二行说明，
         * active 为 true 时尾部文字使用成功色（已连接/运行中）
         */
        data class Status(
            val text: String,
            val subtitle: String? = null,
            val active: Boolean = false
        ) : SettingValue()
    }

    /**
     * 用 ZXing 将文本编码为二维码 Bitmap
     */
    private fun generateQrBitmap(content: String, size: Int): android.graphics.Bitmap? {
        return try {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.MARGIN to 1,
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            XLog.e("SettingsViewModel", "生成二维码失败", e)
            null
        }
    }

    /**
     * 菜单动作枚举
     */
    enum class MenuAction {
        DINGDING, FEISHU, QQ, DISCORD, TELEGRAM, WECHAT,
        LAN_CONFIG,
        LLM_CONFIG,
        COMPLIANCE_CONFIG,
        SKILLS, PERSONA, SCHEDULES, TRIGGERS, POLICIES,
        VOICE_MODEL
    }
}

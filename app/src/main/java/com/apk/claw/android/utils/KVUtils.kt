package com.apk.claw.android.utils

import android.content.Context
import com.tencent.mmkv.MMKV

/**
 * MMKV 键值存储工具类
 *
 * 使用方式：
 *   // 在 Application.onCreate 中初始化
 *   KVUtils.init(context)
 *
 *   // 存取数据
 *   KVUtils.putString("key", "value")
 *   val value = KVUtils.getString("key", "default")
 */
object KVUtils {


    // 钉钉配置
    const val KEY_DINGTALK_APP_KEY = "DEFAULT_DINGTALK_APP_KEY"
    const val KEY_DINGTALK_APP_SECRET = "DEFAULT_DINGTALK_APP_SECRET"
    // 飞书配置
    const val KEY_FEISHU_APP_ID = "DEFAULT_FEISHU_APP_ID"
    const val KEY_FEISHU_APP_SECRET = "DEFAULT_FEISHU_APP_SECRET"
    // QQ 机器人配置
    const val KEY_QQ_APP_ID = "DEFAULT_QQ_APP_ID"
    const val KEY_QQ_APP_SECRET = "DEFAULT_QQ_APP_SECRET"
    // Discord 机器人配置
    const val KEY_DISCORD_BOT_TOKEN = "DEFAULT_DISCORD_BOT_TOKEN"
    // Telegram 机器人配置
    const val KEY_TELEGRAM_BOT_TOKEN = "DEFAULT_TELEGRAM_BOT_TOKEN"
    // 微信 iLink Bot 配置
    const val KEY_WECHAT_BOT_TOKEN = "DEFAULT_WECHAT_BOT_TOKEN"
    const val KEY_WECHAT_API_BASE_URL = "DEFAULT_WECHAT_API_BASE_URL"
    const val KEY_WECHAT_UPDATES_CURSOR = "DEFAULT_WECHAT_UPDATES_CURSOR"

    private lateinit var mmkv: MMKV

    private const val DEFAULT_INT = 0
    private const val DEFAULT_LONG = 0L
    private const val DEFAULT_BOOL = false
    private const val DEFAULT_FLOAT = 0f
    private const val DEFAULT_DOUBLE = 0.0

    /**
     * 在 Application.onCreate 中调用初始化
     */
    fun init(context: Context) {
        MMKV.initialize(context)
        mmkv = MMKV.defaultMMKV()
    }

    // ==================== String ====================
    fun putString(key: String, value: String?): Boolean {
        return mmkv.encode(key, value)
    }

    fun getString(key: String, defaultValue: String = ""): String {
        return mmkv.decodeString(key, defaultValue) ?: defaultValue
    }

    // ==================== Int ====================
    fun putInt(key: String, value: Int): Boolean {
        return mmkv.encode(key, value)
    }

    fun getInt(key: String, defaultValue: Int = DEFAULT_INT): Int {
        return mmkv.decodeInt(key, defaultValue)
    }

    // ==================== Long ====================
    fun putLong(key: String, value: Long): Boolean {
        return mmkv.encode(key, value)
    }

    fun getLong(key: String, defaultValue: Long = DEFAULT_LONG): Long {
        return mmkv.decodeLong(key, defaultValue)
    }

    // ==================== Boolean ====================
    fun putBoolean(key: String, value: Boolean): Boolean {
        return mmkv.encode(key, value)
    }

    fun getBoolean(key: String, defaultValue: Boolean = DEFAULT_BOOL): Boolean {
        return mmkv.decodeBool(key, defaultValue)
    }

    // ==================== Float ====================
    fun putFloat(key: String, value: Float): Boolean {
        return mmkv.encode(key, value)
    }

    fun getFloat(key: String, defaultValue: Float = DEFAULT_FLOAT): Float {
        return mmkv.decodeFloat(key, defaultValue)
    }

    // ==================== Double ====================
    fun putDouble(key: String, value: Double): Boolean {
        return mmkv.encode(key, value)
    }

    fun getDouble(key: String, defaultValue: Double = DEFAULT_DOUBLE): Double {
        return mmkv.decodeDouble(key, defaultValue)
    }

    // ==================== Bytes ====================
    fun putBytes(key: String, value: ByteArray?): Boolean {
        return mmkv.encode(key, value)
    }

    fun getBytes(key: String): ByteArray? {
        return mmkv.decodeBytes(key)
    }

    // ==================== 常用操作 ====================
    fun contains(key: String): Boolean {
        return mmkv.containsKey(key)
    }

    fun remove(key: String) {
        mmkv.removeValueForKey(key)
    }

    fun remove(vararg keys: String) {
        mmkv.removeValuesForKeys(keys)
    }

    fun clear() {
        mmkv.clearAll()
    }

    fun getAllKeys(): Array<String> {
        return mmkv.allKeys() ?: emptyArray()
    }

    /**
     * 同步写入磁盘（默认是异步的）
     */
    fun sync() {
        mmkv.sync()
    }


    // ==================== 引导页 ====================
    private const val KEY_GUIDE_SHOWN = "KEY_GUIDE_SHOWN"

    fun isGuideShown(): Boolean = getBoolean(KEY_GUIDE_SHOWN, false)

    fun setGuideShown(shown: Boolean) = putBoolean(KEY_GUIDE_SHOWN, shown)

    // ==================== 钉钉配置 ====================
    fun getDingtalkAppKey(): String = getString(KEY_DINGTALK_APP_KEY, "")
    fun setDingtalkAppKey(value: String) = putString(KEY_DINGTALK_APP_KEY, value)
    fun getDingtalkAppSecret(): String = getString(KEY_DINGTALK_APP_SECRET, "")
    fun setDingtalkAppSecret(value: String) = putString(KEY_DINGTALK_APP_SECRET, value)

    // ==================== 飞书配置 ====================
    fun getFeishuAppId(): String = getString(KEY_FEISHU_APP_ID, "")
    fun setFeishuAppId(value: String) = putString(KEY_FEISHU_APP_ID, value)
    fun getFeishuAppSecret(): String = getString(KEY_FEISHU_APP_SECRET, "")
    fun setFeishuAppSecret(value: String) = putString(KEY_FEISHU_APP_SECRET, value)

    // ==================== QQ 机器人配置 ====================
    fun getQqAppId(): String = getString(KEY_QQ_APP_ID, "")
    fun setQqAppId(value: String) = putString(KEY_QQ_APP_ID, value)
    fun getQqAppSecret(): String = getString(KEY_QQ_APP_SECRET, "")
    fun setQqAppSecret(value: String) = putString(KEY_QQ_APP_SECRET, value)

    // ==================== Discord 机器人配置 ====================
    fun getDiscordBotToken(): String = getString(KEY_DISCORD_BOT_TOKEN, "")
    fun setDiscordBotToken(value: String) = putString(KEY_DISCORD_BOT_TOKEN, value)

    // ==================== Telegram 机器人配置 ====================
    fun getTelegramBotToken(): String = getString(KEY_TELEGRAM_BOT_TOKEN, "")
    fun setTelegramBotToken(value: String) = putString(KEY_TELEGRAM_BOT_TOKEN, value)

    // ==================== 微信 iLink Bot 配置 ====================
    fun getWechatBotToken(): String = getString(KEY_WECHAT_BOT_TOKEN, "")
    fun setWechatBotToken(value: String) = putString(KEY_WECHAT_BOT_TOKEN, value)
    fun getWechatApiBaseUrl(): String = getString(KEY_WECHAT_API_BASE_URL, "")
    fun setWechatApiBaseUrl(value: String) = putString(KEY_WECHAT_API_BASE_URL, value)
    fun getWechatUpdatesCursor(): String = getString(KEY_WECHAT_UPDATES_CURSOR, "")
    fun setWechatUpdatesCursor(value: String) = putString(KEY_WECHAT_UPDATES_CURSOR, value)

    // ==================== 局域网配置服务 ====================
    private const val KEY_CONFIG_SERVER_ENABLED = "KEY_CONFIG_SERVER_ENABLED"
    fun isConfigServerEnabled(): Boolean = getBoolean(KEY_CONFIG_SERVER_ENABLED, false)
    fun setConfigServerEnabled(enabled: Boolean) = putBoolean(KEY_CONFIG_SERVER_ENABLED, enabled)

    private const val KEY_LLM_API_KEY = "KEY_LLM_API_KEY"
    private const val KEY_LLM_BASE_URL = "KEY_LLM_BASE_URL"
    private const val KEY_LLM_MODEL_NAME = "KEY_LLM_MODEL_NAME"
    private const val KEY_LLM_CONTEXT_WINDOW = "KEY_LLM_CONTEXT_WINDOW"
    private const val KEY_CONFIRM_DANGEROUS_OPS = "KEY_CONFIRM_DANGEROUS_OPS"
    private const val KEY_VERIFY_RESULTS = "KEY_VERIFY_RESULTS"
    private const val KEY_VISION_ENABLED = "KEY_VISION_ENABLED"

    fun getLlmApiKey(): String = getString(KEY_LLM_API_KEY, "")
    fun setLlmApiKey(value: String) = putString(KEY_LLM_API_KEY, value)
    fun getLlmBaseUrl(): String = getString(KEY_LLM_BASE_URL, "")
    fun setLlmBaseUrl(value: String) = putString(KEY_LLM_BASE_URL, value)
    fun getLlmModelName(): String = getString(KEY_LLM_MODEL_NAME, "")
    fun setLlmModelName(value: String) = putString(KEY_LLM_MODEL_NAME, value)

    /** 模型上下文窗口（tokens）；0 表示未设置，由 ContextBudget 退回默认值 */
    fun getLlmContextWindow(): Int = getInt(KEY_LLM_CONTEXT_WINDOW, 0)
    fun setLlmContextWindow(value: Int) = putInt(KEY_LLM_CONTEXT_WINDOW, value)

    /** F2：危险操作（发送/支付/删除类）执行前是否需用户经渠道确认，默认开启 */
    fun getConfirmDangerousOps(): Boolean = getBoolean(KEY_CONFIRM_DANGEROUS_OPS, true)
    fun setConfirmDangerousOps(value: Boolean) = putBoolean(KEY_CONFIRM_DANGEROUS_OPS, value)

    /** F4：核心操作执行后回读设备状态断言，默认开启 */
    fun getVerifyResults(): Boolean = getBoolean(KEY_VERIFY_RESULTS, true)
    fun setVerifyResults(value: Boolean) = putBoolean(KEY_VERIFY_RESULTS, value)

    /** F10：截图作为图像注入 LLM 上下文（需模型支持视觉），默认开启，报错可关闭 */
    fun getVisionEnabled(): Boolean = getBoolean(KEY_VISION_ENABLED, true)
    fun setVisionEnabled(value: Boolean) = putBoolean(KEY_VISION_ENABLED, value)

    // ==================== C3/C5/C6 合规与自动化策略（ROADMAP §6） ====================
    private const val KEY_THIRD_PARTY_AUTOMATION_ENABLED = "KEY_THIRD_PARTY_AUTOMATION_ENABLED"
    private const val KEY_QUIET_HOURS_ENABLED = "KEY_QUIET_HOURS_ENABLED"
    private const val KEY_QUIET_START_MIN = "KEY_QUIET_START_MIN"
    private const val KEY_QUIET_END_MIN = "KEY_QUIET_END_MIN"
    private const val KEY_MIN_TASK_COOLDOWN_SEC = "KEY_MIN_TASK_COOLDOWN_SEC"
    private const val KEY_RATE_GLOBAL_PER_MIN = "KEY_RATE_GLOBAL_PER_MIN"
    private const val KEY_RATE_CHANNEL_PER_MIN = "KEY_RATE_CHANNEL_PER_MIN"
    private const val KEY_BREAKER_THRESHOLD = "KEY_BREAKER_THRESHOLD"
    private const val KEY_FAILURE_STREAK = "KEY_FAILURE_STREAK"

    /** C5：第三方 App 自动化总开关（关闭后改动类 UI 操作被拒，系统 App 与 Intent 工具不受影响） */
    fun getThirdPartyAutomationEnabled(): Boolean = getBoolean(KEY_THIRD_PARTY_AUTOMATION_ENABLED, true)
    fun setThirdPartyAutomationEnabled(value: Boolean) = putBoolean(KEY_THIRD_PARTY_AUTOMATION_ENABLED, value)

    /** C3：夜间静默时段（只作用于自动任务），默认关闭 */
    fun getQuietHoursEnabled(): Boolean = getBoolean(KEY_QUIET_HOURS_ENABLED, false)
    fun setQuietHoursEnabled(value: Boolean) = putBoolean(KEY_QUIET_HOURS_ENABLED, value)

    /** 静默时段起点，分钟自零点（默认 22:00） */
    fun getQuietStartMin(): Int = getInt(KEY_QUIET_START_MIN, com.apk.claw.android.compliance.ComplianceConfig.DEFAULT_QUIET_START_MIN)
    fun setQuietStartMin(value: Int) = putInt(KEY_QUIET_START_MIN, value.coerceIn(0, 1439))

    /** 静默时段终点，分钟自零点（默认 07:00） */
    fun getQuietEndMin(): Int = getInt(KEY_QUIET_END_MIN, com.apk.claw.android.compliance.ComplianceConfig.DEFAULT_QUIET_END_MIN)
    fun setQuietEndMin(value: Int) = putInt(KEY_QUIET_END_MIN, value.coerceIn(0, 1439))

    /** C3：自动任务间最小间隔秒数；0 = 关闭 */
    fun getMinTaskCooldownSec(): Int = getInt(KEY_MIN_TASK_COOLDOWN_SEC, com.apk.claw.android.compliance.ComplianceConfig.DEFAULT_COOLDOWN_SEC)
    fun setMinTaskCooldownSec(value: Int) =
        putInt(KEY_MIN_TASK_COOLDOWN_SEC, value.coerceIn(0, com.apk.claw.android.compliance.ComplianceConfig.MAX_COOLDOWN_SEC))

    /** C3：全局每分钟任务启动上限；0 = 关闭 */
    fun getRateGlobalPerMin(): Int = getInt(KEY_RATE_GLOBAL_PER_MIN, com.apk.claw.android.compliance.ComplianceConfig.DEFAULT_RATE_GLOBAL_PER_MIN)
    fun setRateGlobalPerMin(value: Int) =
        putInt(KEY_RATE_GLOBAL_PER_MIN, value.coerceIn(0, com.apk.claw.android.compliance.ComplianceConfig.MAX_RATE_PER_MIN))

    /** C3：单渠道每分钟任务启动上限；0 = 关闭 */
    fun getRateChannelPerMin(): Int = getInt(KEY_RATE_CHANNEL_PER_MIN, com.apk.claw.android.compliance.ComplianceConfig.DEFAULT_RATE_CHANNEL_PER_MIN)
    fun setRateChannelPerMin(value: Int) =
        putInt(KEY_RATE_CHANNEL_PER_MIN, value.coerceIn(0, com.apk.claw.android.compliance.ComplianceConfig.MAX_RATE_PER_MIN))

    /** C3：连续失败熔断阈值；0 = 关闭。达到后自动任务暂停，手动任务仍可执行，成功一次即复位 */
    fun getBreakerThreshold(): Int = getInt(KEY_BREAKER_THRESHOLD, com.apk.claw.android.compliance.ComplianceConfig.DEFAULT_BREAKER_THRESHOLD)
    fun setBreakerThreshold(value: Int) =
        putInt(KEY_BREAKER_THRESHOLD, value.coerceIn(0, com.apk.claw.android.compliance.ComplianceConfig.MAX_BREAKER_THRESHOLD))

    /** 当前连续失败数（熔断状态，任务收尾时在 recordHistory 更新） */
    fun getFailureStreak(): Int = getInt(KEY_FAILURE_STREAK, 0)
    fun setFailureStreak(value: Int) = putInt(KEY_FAILURE_STREAK, value.coerceAtLeast(0))

    /** C3/C5 合规设置快照：准入判定与策略门每次调用时活读组装 */
    fun loadComplianceConfig(): com.apk.claw.android.compliance.ComplianceConfig =
        com.apk.claw.android.compliance.ComplianceConfig(
            thirdPartyAutomationEnabled = getThirdPartyAutomationEnabled(),
            quietHoursEnabled = getQuietHoursEnabled(),
            quietStartMin = getQuietStartMin(),
            quietEndMin = getQuietEndMin(),
            cooldownSec = getMinTaskCooldownSec(),
            rateGlobalPerMin = getRateGlobalPerMin(),
            rateChannelPerMin = getRateChannelPerMin(),
            breakerThreshold = getBreakerThreshold(),
            failureStreak = getFailureStreak()
        )

    /** 是否已配置 LLM（API Key 非空即视为已配置） */
    fun hasLlmConfig(): Boolean = getLlmApiKey().isNotEmpty()
}

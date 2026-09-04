package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.compliance.ComplianceConfig
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton

/**
 * 自动化与合规配置页（C3/C5），与 LAN 配置页"合规"卡片同源同语义。
 * 全部设置活读即时生效，无需重建 Agent。
 */
class ComplianceConfigActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compliance_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.compliance_config_title))
            showBackButton(true) { finish() }
        }

        val swThirdParty = findViewById<SwitchCompat>(R.id.switchThirdParty)
        val swQuietHours = findViewById<SwitchCompat>(R.id.switchQuietHours)
        val etQuietStart = findViewById<EditText>(R.id.etQuietStart)
        val etQuietEnd = findViewById<EditText>(R.id.etQuietEnd)
        val etCooldown = findViewById<EditText>(R.id.etCooldown)
        val etRateGlobal = findViewById<EditText>(R.id.etRateGlobal)
        val etRateChannel = findViewById<EditText>(R.id.etRateChannel)
        val etBreaker = findViewById<EditText>(R.id.etBreaker)

        swThirdParty.isChecked = KVUtils.getThirdPartyAutomationEnabled()
        swQuietHours.isChecked = KVUtils.getQuietHoursEnabled()
        etQuietStart.setText(ComplianceConfig.formatHm(KVUtils.getQuietStartMin()))
        etQuietEnd.setText(ComplianceConfig.formatHm(KVUtils.getQuietEndMin()))
        etCooldown.setText(KVUtils.getMinTaskCooldownSec().toString())
        etRateGlobal.setText(KVUtils.getRateGlobalPerMin().toString())
        etRateChannel.setText(KVUtils.getRateChannelPerMin().toString())
        etBreaker.setText(KVUtils.getBreakerThreshold().toString())

        findViewById<KButton>(R.id.btnSave).setOnClickListener {
            // 时间留空按默认时段处理；格式非法直接拒绝（与 LAN 端 parseHm 语义一致）
            val quietStart = parseTimeOrDefault(etQuietStart, ComplianceConfig.DEFAULT_QUIET_START_MIN)
                ?: return@setOnClickListener
            val quietEnd = parseTimeOrDefault(etQuietEnd, ComplianceConfig.DEFAULT_QUIET_END_MIN)
                ?: return@setOnClickListener
            val cooldown = parseCount(etCooldown) ?: return@setOnClickListener
            val rateGlobal = parseCount(etRateGlobal) ?: return@setOnClickListener
            val rateChannel = parseCount(etRateChannel) ?: return@setOnClickListener
            val breaker = parseCount(etBreaker) ?: return@setOnClickListener

            // 各 setter 内部按上限钳制，0 = 关闭
            KVUtils.setThirdPartyAutomationEnabled(swThirdParty.isChecked)
            KVUtils.setQuietHoursEnabled(swQuietHours.isChecked)
            KVUtils.setQuietStartMin(quietStart)
            KVUtils.setQuietEndMin(quietEnd)
            KVUtils.setMinTaskCooldownSec(cooldown)
            KVUtils.setRateGlobalPerMin(rateGlobal)
            KVUtils.setRateChannelPerMin(rateChannel)
            KVUtils.setBreakerThreshold(breaker)

            Toast.makeText(this, getString(R.string.compliance_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** 解析 HH:mm；留空返回 [fallback]，格式非法提示并返回 null */
    private fun parseTimeOrDefault(editText: EditText, fallback: Int): Int? {
        val text = editText.text.toString().trim()
        if (text.isEmpty()) return fallback
        val minutes = ComplianceConfig.parseHm(text)
        if (minutes == null) {
            Toast.makeText(this, getString(R.string.compliance_invalid_time), Toast.LENGTH_SHORT).show()
            editText.requestFocus()
            return null
        }
        return minutes
    }

    /** 解析非负整数（数字输入框不含负号，防御手输异常值）；非法提示并返回 null */
    private fun parseCount(editText: EditText): Int? {
        val text = editText.text.toString().trim()
        val value = text.toIntOrNull()
        if (value == null || value < 0) {
            Toast.makeText(this, getString(R.string.compliance_invalid_number), Toast.LENGTH_SHORT).show()
            editText.requestFocus()
            return null
        }
        return value
    }
}

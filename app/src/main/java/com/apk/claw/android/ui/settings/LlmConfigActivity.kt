package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton

/**
 * LLM 配置页（自行填写 API Key、Base URL、模型名）
 */
class LlmConfigActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.llm_config_title))
            showBackButton(true) { finish() }
        }

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val etModelName = findViewById<EditText>(R.id.etModelName)
        val etContextWindow = findViewById<EditText>(R.id.etContextWindow)
        val swConfirmDangerousOps = findViewById<SwitchCompat>(R.id.switchConfirmDangerousOps)
        val swVerifyResults = findViewById<SwitchCompat>(R.id.switchVerifyResults)
        val swVisionEnabled = findViewById<SwitchCompat>(R.id.switchVisionEnabled)
        val swVisionModelEnabled = findViewById<SwitchCompat>(R.id.switchVisionModelEnabled)
        val layoutVisionModel = findViewById<android.view.View>(R.id.layoutVisionModel)
        val etVisionModel = findViewById<EditText>(R.id.etVisionModel)

        etApiKey.setText(KVUtils.getLlmApiKey())
        etBaseUrl.setText(KVUtils.getLlmBaseUrl())
        etModelName.setText(KVUtils.getLlmModelName())
        KVUtils.getLlmContextWindow().takeIf { it > 0 }?.let { etContextWindow.setText(it.toString()) }
        swConfirmDangerousOps.isChecked = KVUtils.getConfirmDangerousOps()
        swVerifyResults.isChecked = KVUtils.getVerifyResults()
        swVisionEnabled.isChecked = KVUtils.getVisionEnabled()
        swVisionModelEnabled.isChecked = KVUtils.getVisionModelEnabled()
        etVisionModel.setText(KVUtils.getLlmVisionModel())
        layoutVisionModel.visibility = if (swVisionModelEnabled.isChecked) android.view.View.VISIBLE else android.view.View.GONE
        swVisionModelEnabled.setOnCheckedChangeListener { _, checked ->
            layoutVisionModel.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }

        findViewById<KButton>(R.id.btnSave).setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            val baseUrl = etBaseUrl.text.toString().trim()
            val modelName = etModelName.text.toString().trim().ifEmpty { "" }
            val contextWindowText = etContextWindow.text.toString().trim()

            if (apiKey.isEmpty()) {
                Toast.makeText(this, getString(R.string.llm_config_api_key_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (contextWindowText.isNotEmpty() && contextWindowText.toIntOrNull() == null) {
                Toast.makeText(this, getString(R.string.llm_config_context_window_invalid), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            KVUtils.setLlmApiKey(apiKey)
            KVUtils.setLlmBaseUrl(baseUrl)
            KVUtils.setLlmModelName(modelName)
            KVUtils.setLlmContextWindow(contextWindowText.toIntOrNull() ?: 0)
            KVUtils.setConfirmDangerousOps(swConfirmDangerousOps.isChecked)
            KVUtils.setVerifyResults(swVerifyResults.isChecked)
            KVUtils.setVisionEnabled(swVisionEnabled.isChecked)
            KVUtils.setVisionModelEnabled(swVisionModelEnabled.isChecked)
            KVUtils.setLlmVisionModel(etVisionModel.text.toString().trim())

            ClawApplication.appViewModelInstance.updateAgentConfig()
            ClawApplication.appViewModelInstance.initAgent()
            ClawApplication.appViewModelInstance.refreshChannels()
            Toast.makeText(this, getString(R.string.llm_config_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

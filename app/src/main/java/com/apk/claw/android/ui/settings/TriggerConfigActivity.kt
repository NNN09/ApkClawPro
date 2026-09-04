package com.apk.claw.android.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.compliance.AppPolicy
import com.apk.claw.android.service.TriggerNotificationListener
import com.apk.claw.android.trigger.TriggerConfig
import com.apk.claw.android.trigger.TriggerRule
import com.apk.claw.android.trigger.TriggerRuleStore
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.ConfirmDialog
import com.apk.claw.android.widget.InputDialog
import com.apk.claw.android.widget.KButton

/**
 * F8 事件触发配置页，与 LAN /api/trigger 同源同语义：
 * 页内编辑内存副本，保存时整体校验并原子替换（TriggerRuleStore.replaceAll），
 * 监听器每次通知活读配置，无需重启组件。
 */
class TriggerConfigActivity : BaseActivity() {

    private var enabled = false
    private val whitelist = mutableListOf<String>()
    private val rules = mutableListOf<TriggerRule>()

    private lateinit var llWhitelist: LinearLayout
    private lateinit var tvWhitelistEmpty: View
    private lateinit var llRules: LinearLayout
    private lateinit var tvRulesEmpty: View
    private lateinit var tvListenerWarn: View
    private lateinit var btnListenerSettings: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trigger_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.trigger_config_title))
            showBackButton(true) { finish() }
        }

        llWhitelist = findViewById(R.id.llWhitelist)
        tvWhitelistEmpty = findViewById(R.id.tvWhitelistEmpty)
        llRules = findViewById(R.id.llRules)
        tvRulesEmpty = findViewById(R.id.tvRulesEmpty)
        tvListenerWarn = findViewById(R.id.tvListenerWarn)
        btnListenerSettings = findViewById(R.id.btnListenerSettings)

        val current = TriggerRuleStore.get()
        enabled = current.enabled
        whitelist.addAll(current.whitelist)
        rules.addAll(current.rules)

        findViewById<SwitchCompat>(R.id.switchEnabled).apply {
            isChecked = enabled
            setOnCheckedChangeListener { _, checked -> enabled = checked }
        }

        btnListenerSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<View>(R.id.btnAddPackage).setOnClickListener {
            InputDialog.show(
                context = this,
                title = getString(R.string.trigger_add_package),
                hint = "com.example.app",
                inputValidate = { raw ->
                    if (AppPolicy.isValidPackageName(raw)) InputDialog.ValidateResult(true)
                    else InputDialog.ValidateResult(false, getString(R.string.policy_invalid_package))
                }
            ) { pkg ->
                if (!whitelist.contains(pkg)) {
                    whitelist.add(pkg)
                    refreshWhitelist()
                }
            }
        }

        findViewById<View>(R.id.btnAddRule).setOnClickListener { showRuleEditor(null) }

        findViewById<KButton>(R.id.btnSave).setOnClickListener { apply() }

        refreshWhitelist()
        refreshRules()
    }

    override fun onResume() {
        super.onResume()
        val bound = TriggerNotificationListener.isListenerBound(this)
        tvListenerWarn.isVisible = !bound
        btnListenerSettings.isVisible = !bound
    }

    private fun refreshWhitelist() {
        tvWhitelistEmpty.isVisible = whitelist.isEmpty()
        llWhitelist.removeAllViews()
        whitelist.forEach { pkg ->
            val row = layoutInflater.inflate(R.layout.item_pkg_row, llWhitelist, false)
            row.findViewById<TextView>(R.id.tvTitle).text = pkg
            row.findViewById<View>(R.id.btnRemove).setOnClickListener {
                whitelist.remove(pkg)
                refreshWhitelist()
            }
            llWhitelist.addView(row)
        }
    }

    private fun refreshRules() {
        tvRulesEmpty.isVisible = rules.isEmpty()
        llRules.removeAllViews()
        rules.forEachIndexed { index, rule ->
            if (index > 0) llRules.addView(layoutInflater.inflate(R.layout.item_divider, llRules, false))
            val row = layoutInflater.inflate(R.layout.item_skill_row, llRules, false)
            row.findViewById<TextView>(R.id.tvTitle).text = rule.name
            row.findViewById<TextView>(R.id.tvSubtitle).text = getString(
                R.string.trigger_rule_subtitle,
                rule.appPackage.ifBlank { getString(R.string.trigger_any_package) },
                rule.keywordRegex
            )
            row.setOnClickListener { showRuleEditor(rule) }
            llRules.addView(row)
        }
    }

    private fun showRuleEditor(existing: TriggerRule?) {
        showFormSheet(
            R.layout.dialog_trigger_rule_editor,
            getString(if (existing == null) R.string.trigger_add_rule else R.string.trigger_edit_title)
        ) { view, dialog ->
            val etName = view.findViewById<EditText>(R.id.etName)
            val etPackage = view.findViewById<EditText>(R.id.etPackage)
            val etRegex = view.findViewById<EditText>(R.id.etRegex)
            val etTemplate = view.findViewById<EditText>(R.id.etTemplate)
            existing?.let {
                etName.setText(it.name)
                etPackage.setText(it.appPackage)
                etRegex.setText(it.keywordRegex)
                etTemplate.setText(it.taskTemplate)
            }

            view.findViewById<KButton>(R.id.btnSave).setOnClickListener {
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    toast(R.string.trigger_name_required)
                    return@setOnClickListener
                }
                val appPackage = etPackage.text.toString().trim()
                if (appPackage.isNotEmpty() && !AppPolicy.isValidPackageName(appPackage)) {
                    toast(R.string.policy_invalid_package)
                    return@setOnClickListener
                }
                val regex = etRegex.text.toString().trim()
                try {
                    Regex(regex)
                } catch (_: Exception) {
                    toast(R.string.trigger_invalid_regex)
                    return@setOnClickListener
                }
                val template = etTemplate.text.toString().trim()
                if (template.isEmpty()) {
                    toast(R.string.trigger_template_required)
                    return@setOnClickListener
                }
                if (rules.any { it.name == name && it.name != existing?.name }) {
                    toast(R.string.trigger_duplicate_name)
                    return@setOnClickListener
                }
                val rule = TriggerRule(name, appPackage, regex, template)
                val index = rules.indexOfFirst { it.name == existing?.name }
                if (index >= 0) rules[index] = rule else rules.add(rule)
                refreshRules()
                dialog.dismiss()
            }

            view.findViewById<View>(R.id.btnDelete).apply {
                if (existing == null) return@apply
                isVisible = true
                setOnClickListener {
                    dialog.dismiss()
                    ConfirmDialog.showWarm(
                        context = this@TriggerConfigActivity,
                        title = getString(R.string.trigger_delete_title),
                        message = getString(R.string.trigger_delete_message, existing.name),
                        actionTitle = getString(R.string.common_delete),
                        onAction = {
                            rules.removeAll { it.name == existing.name }
                            refreshRules()
                        }
                    )
                }
            }
        }
    }

    private fun apply() {
        val error = TriggerRuleStore.replaceAll(TriggerConfig(enabled, whitelist.toList(), rules.toList()))
        if (error != null) {
            Toast.makeText(this, getString(R.string.trigger_save_failed, error), Toast.LENGTH_LONG).show()
        } else {
            toast(R.string.trigger_saved)
            finish()
        }
    }
}

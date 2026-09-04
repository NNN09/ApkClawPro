package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.view.isVisible
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.compliance.AppPolicy
import com.apk.claw.android.compliance.AppPolicyStore
import com.apk.claw.android.compliance.Mode
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.ConfirmDialog
import com.apk.claw.android.widget.KButton

/**
 * C6 单应用自动化策略页，与 LAN /api/policies 同源同语义：
 * 每次增删改后整体校验并原子替换（AppPolicyStore.replaceAll），策略门每次工具调用活读。
 */
class PolicyConfigActivity : BaseActivity() {

    private lateinit var cardPolicies: View
    private lateinit var tvEmpty: View
    private lateinit var llPolicies: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_policy_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.policy_config_title))
            showBackButton(true) { finish() }
        }

        cardPolicies = findViewById(R.id.cardPolicies)
        tvEmpty = findViewById(R.id.tvEmpty)
        llPolicies = findViewById(R.id.llPolicies)

        findViewById<KButton>(R.id.btnAdd).setOnClickListener { showEditor(null) }
        refresh()
    }

    private fun refresh() {
        val policies = AppPolicyStore.list()
        cardPolicies.isVisible = policies.isNotEmpty()
        tvEmpty.isVisible = policies.isEmpty()
        llPolicies.removeAllViews()
        policies.forEachIndexed { index, policy ->
            if (index > 0) llPolicies.addView(layoutInflater.inflate(R.layout.item_divider, llPolicies, false))
            val row = layoutInflater.inflate(R.layout.item_policy_row, llPolicies, false)
            row.findViewById<TextView>(R.id.tvTitle).text = policy.packageName
            row.findViewById<TextView>(R.id.tvMode).text = modeLabel(policy.mode)
            row.setOnClickListener { showEditor(policy) }
            llPolicies.addView(row)
        }
    }

    private fun showEditor(existing: AppPolicy?) {
        showFormSheet(
            R.layout.dialog_policy_editor,
            getString(if (existing == null) R.string.policy_add else R.string.policy_edit_title)
        ) { view, dialog ->
            val etPackage = view.findViewById<EditText>(R.id.etPackage)
            if (existing != null) {
                // 包名是策略表键，编辑时不可改（改包名等于新增另一条策略）
                etPackage.isEnabled = false
                etPackage.setText(existing.packageName)
            }
            val rbAuto = view.findViewById<RadioButton>(R.id.rbAuto)
            val rbConfirm = view.findViewById<RadioButton>(R.id.rbConfirm)
            val rbBlock = view.findViewById<RadioButton>(R.id.rbBlock)
            when (existing?.mode ?: Mode.AUTO) {
                Mode.AUTO -> rbAuto.isChecked = true
                Mode.CONFIRM -> rbConfirm.isChecked = true
                Mode.BLOCK -> rbBlock.isChecked = true
            }

            view.findViewById<KButton>(R.id.btnSave).setOnClickListener {
                val pkg = etPackage.text.toString().trim()
                val mode = when {
                    rbConfirm.isChecked -> Mode.CONFIRM
                    rbBlock.isChecked -> Mode.BLOCK
                    else -> Mode.AUTO
                }
                val policy = AppPolicy(pkg, mode)
                if (!policy.isValid()) {
                    toast(R.string.policy_invalid_package)
                    return@setOnClickListener
                }
                val next = AppPolicyStore.list().filterNot { it.packageName == pkg } + policy
                val error = AppPolicyStore.replaceAll(next)
                if (error != null) {
                    toast(R.string.policy_save_failed)
                } else {
                    toast(R.string.policy_saved)
                    refresh()
                    dialog.dismiss()
                }
            }

            view.findViewById<View>(R.id.btnDelete).apply {
                if (existing == null) return@apply
                isVisible = true
                setOnClickListener {
                    dialog.dismiss()
                    ConfirmDialog.showWarm(
                        context = this@PolicyConfigActivity,
                        title = getString(R.string.policy_delete_title),
                        message = getString(R.string.policy_delete_message, existing.packageName),
                        actionTitle = getString(R.string.common_delete),
                        onAction = {
                            AppPolicyStore.replaceAll(AppPolicyStore.list().filterNot { it.packageName == existing.packageName })
                            toast(R.string.policy_deleted)
                            refresh()
                        }
                    )
                }
            }
        }
    }

    private fun modeLabel(mode: Mode): String = getString(
        when (mode) {
            Mode.AUTO -> R.string.policy_mode_auto
            Mode.CONFIRM -> R.string.policy_mode_confirm
            Mode.BLOCK -> R.string.policy_mode_block
        }
    )
}

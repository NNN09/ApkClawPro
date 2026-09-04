package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.apk.claw.android.R
import com.apk.claw.android.agent.store.SkillImportScanner
import com.apk.claw.android.agent.store.SkillPackager
import com.apk.claw.android.agent.store.SkillStore
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.ConfirmDialog
import com.apk.claw.android.widget.KButton

/**
 * 技能管理页，与 LAN /api/skills 同源同语义：
 * 所有写入（新增/编辑/模板安装）统一走 F11 安全扫描（SkillImportScanner），
 * HIGH 拦截、WARN 放行并提示，落盘走 SkillStore.upsert。
 */
class SkillsConfigActivity : BaseActivity() {

    private lateinit var cardSkills: View
    private lateinit var tvEmpty: View
    private lateinit var llSkills: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_skills_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.skills_config_title))
            showBackButton(true) { finish() }
        }

        cardSkills = findViewById(R.id.cardSkills)
        tvEmpty = findViewById(R.id.tvEmpty)
        llSkills = findViewById(R.id.llSkills)

        findViewById<KButton>(R.id.btnAdd).setOnClickListener { showEditor(null) }
        findViewById<KButton>(R.id.btnTemplates).setOnClickListener { showTemplates() }
        refresh()
    }

    private fun refresh() {
        val skills = SkillStore.list()
        cardSkills.isVisible = skills.isNotEmpty()
        tvEmpty.isVisible = skills.isEmpty()
        llSkills.removeAllViews()
        skills.forEachIndexed { index, skill ->
            if (index > 0) llSkills.addView(layoutInflater.inflate(R.layout.item_divider, llSkills, false))
            val row = layoutInflater.inflate(R.layout.item_skill_row, llSkills, false)
            row.findViewById<TextView>(R.id.tvTitle).text = skill.name
            row.findViewById<TextView>(R.id.tvSubtitle).text = skill.description
            row.setOnClickListener { showEditor(skill) }
            llSkills.addView(row)
        }
    }

    private fun showEditor(existing: SkillStore.Skill?) {
        showFormSheet(
            R.layout.dialog_skill_editor,
            getString(if (existing == null) R.string.skills_add else R.string.skills_edit_title)
        ) { view, dialog ->
            val etName = view.findViewById<EditText>(R.id.etName)
            val etDesc = view.findViewById<EditText>(R.id.etDesc)
            val etContent = view.findViewById<EditText>(R.id.etContent)
            if (existing != null) {
                // 名称是技能目录键，编辑时不可改（改名等于另存新技能）
                etName.isEnabled = false
                etName.setText(existing.name)
                etDesc.setText(existing.description)
                etContent.setText(SkillStore.load(existing.name) ?: "")
            }

            view.findViewById<KButton>(R.id.btnSave).setOnClickListener {
                val name = etName.text.toString().trim()
                val description = etDesc.text.toString().trim()
                val content = etContent.text.toString()
                if (content.isBlank()) {
                    toast(R.string.skills_content_required)
                    return@setOnClickListener
                }
                if (saveSkill(name, description, content)) dialog.dismiss()
            }

            view.findViewById<View>(R.id.btnDelete).apply {
                if (existing == null) return@apply
                isVisible = true
                setOnClickListener {
                    dialog.dismiss()
                    ConfirmDialog.showWarm(
                        context = this@SkillsConfigActivity,
                        title = getString(R.string.skills_delete_title),
                        message = getString(R.string.skills_delete_message, existing.name),
                        actionTitle = getString(R.string.common_delete),
                        onAction = {
                            SkillStore.delete(existing.name)
                            toast(R.string.skills_deleted)
                            refresh()
                        }
                    )
                }
            }
        }
    }

    /** 与 LAN importSkillWithScan 同路径：扫描 → 拦截/告警 → SkillStore.upsert */
    private fun saveSkill(name: String, description: String, content: String): Boolean {
        val findings = SkillImportScanner.scan("$description\n$content")
        if (SkillImportScanner.hasBlocking(findings)) {
            ConfirmDialog.show(
                context = this,
                title = getString(R.string.skills_scan_blocked_title),
                message = SkillImportScanner.report(findings),
                actionTitle = getString(R.string.common_close)
            )
            return false
        }
        if (!SkillStore.upsert(name, description, content)) {
            toast(R.string.skills_invalid_name)
            return false
        }
        val warnings = SkillImportScanner.report(findings)
        if (warnings.isNotEmpty()) {
            Toast.makeText(this, warnings, Toast.LENGTH_LONG).show()
        } else {
            toast(R.string.skills_saved)
        }
        refresh()
        return true
    }

    /** 内置模板（assets/skills-templates）安装，与 LAN /api/skills/install-template 同路径 */
    private fun showTemplates() {
        val templates = try {
            assets.list("skills-templates")?.sorted().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }.mapNotNull { file ->
            try {
                assets.open("skills-templates/$file").bufferedReader().use { it.readText() }
                    .let { raw -> SkillPackager.parseSkillMd(raw)?.let { file.removeSuffix(".md") to it } }
            } catch (_: Exception) {
                null
            }
        }
        if (templates.isEmpty()) {
            toast(R.string.skills_templates_empty)
            return
        }
        showFormSheet(R.layout.dialog_two_line_list, getString(R.string.skills_templates_title)) { view, dialog ->
            val container = view.findViewById<LinearLayout>(R.id.llItems)
            view.findViewById<View>(R.id.tvEmpty).isVisible = false
            templates.forEachIndexed { index, (_, parsed) ->
                if (index > 0) container.addView(layoutInflater.inflate(R.layout.item_divider, container, false))
                val row = layoutInflater.inflate(R.layout.item_skill_row, container, false)
                row.findViewById<TextView>(R.id.tvTitle).text = parsed.name
                row.findViewById<TextView>(R.id.tvSubtitle).text = parsed.description
                row.setOnClickListener {
                    if (saveSkill(parsed.name, parsed.description, parsed.body)) dialog.dismiss()
                }
                container.addView(row)
            }
        }
    }
}

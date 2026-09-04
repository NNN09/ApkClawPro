package com.apk.claw.android.ui.share

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.apk.claw.android.R
import com.apk.claw.android.agent.store.SkillImportScanner
import com.apk.claw.android.agent.store.SkillPackager
import com.apk.claw.android.agent.store.SkillStore
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.utils.XLog
import com.apk.claw.android.widget.CommonToolbar
import kotlin.concurrent.thread

/**
 * F11：apkclaw://skill-import?url=... 深链导入。
 * 二维码扫码 → 外部扫码器打开本页 → 下载 SKILL.md → 安全扫描 →
 * 弹窗展示扫描结果与来源，用户确认后才写入 SkillStore（高危命中直接拦截）。
 */
class SkillImportActivity : BaseActivity() {

    companion object {
        private const val TAG = "SkillImport"
        private const val MAX_DOWNLOAD_BYTES = 512 * 1024
    }

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_skill_import)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.skill_import_title))
            showBackButton(true) { finish() }
        }
        statusView = findViewById(R.id.tvStatus)

        val url = intent.data?.getQueryParameter("url")?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            setStatus(getString(R.string.skill_import_invalid_link))
            return
        }
        setStatus(getString(R.string.skill_import_downloading))
        thread(name = "skill-import") {
            try {
                val bytes = download(url)
                val parsed = SkillPackager.parseSkillMd(bytes.toString(Charsets.UTF_8))
                if (parsed == null) {
                    failOnUiThread(getString(R.string.skill_import_invalid_content))
                    return@thread
                }
                val findings = SkillImportScanner.scan("${parsed.description}\n${parsed.body}")
                runOnUiThread { confirmImport(parsed, findings, url) }
            } catch (e: Exception) {
                XLog.w(TAG, "skill import failed: ${e.message}")
                failOnUiThread(getString(R.string.skill_import_download_failed, e.message ?: ""))
            }
        }
    }

    private fun download(url: String): ByteArray {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = true
        try {
            return conn.inputStream.use { SkillPackager.readBounded(it, MAX_DOWNLOAD_BYTES) }
        } finally {
            conn.disconnect()
        }
    }

    private fun confirmImport(parsed: SkillPackager.ParsedSkill, findings: List<SkillImportScanner.Finding>, url: String) {
        // 下载在后台线程进行，完成时 Activity 可能已被用户返回键销毁，直接 show 会 BadTokenException
        if (isFinishing || isDestroyed) return
        val blocked = SkillImportScanner.hasBlocking(findings)
        val scanReport = SkillImportScanner.report(findings)
        val message = buildString {
            append(getString(R.string.skill_import_name, parsed.name)).append('\n')
            append(getString(R.string.skill_import_desc, parsed.description.ifEmpty { "-" })).append('\n')
            append(getString(R.string.skill_import_source, url)).append('\n')
            append('\n')
            append(
                if (scanReport.isEmpty()) getString(R.string.skill_import_scan_clean)
                else getString(R.string.skill_import_scan_findings) + "\n" + scanReport
            )
        }
        AlertDialog.Builder(this)
            .setTitle(
                if (blocked) R.string.skill_import_blocked_title
                else if (findings.isNotEmpty()) R.string.skill_import_warn_title
                else R.string.skill_import_title
            )
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(
                if (blocked) android.R.string.ok
                else R.string.skill_import_confirm
            ) { _, _ ->
                if (!blocked) doImport(parsed)
                finish()
            }
            .apply { if (!blocked) setNegativeButton(R.string.skill_import_cancel) { _, _ -> finish() } }
            .show()
    }

    private fun doImport(parsed: SkillPackager.ParsedSkill) {
        val ok = SkillStore.upsert(parsed.name, parsed.description, parsed.body)
        val toast = if (ok) R.string.skill_import_success else R.string.skill_import_invalid_content
        Toast.makeText(this, toast, Toast.LENGTH_LONG).show()
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    private fun failOnUiThread(text: String) {
        runOnUiThread {
            setStatus(text)
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }
}

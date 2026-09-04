package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.voice.VoiceModelDownloader
import com.apk.claw.android.voice.VoiceModelStore
import com.apk.claw.android.widget.CommonToolbar

/**
 * 离线语音模型管理页：查看安装状态、下载（带进度）、删除。
 * 模型不随 APK 分发，F6 悬浮球语音输入前必须在此完成下载。
 */
class VoiceModelActivity : BaseActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvProgressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnDownload: com.apk.claw.android.widget.KButton
    private lateinit var btnDelete: com.apk.claw.android.widget.KButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_model)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.menu_voice_model))
            showBackButton(true) { finish() }
        }

        tvStatus = findViewById(R.id.tvStatus)
        tvProgressText = findViewById(R.id.tvProgressText)
        progressBar = findViewById(R.id.progressDownload)
        btnDownload = findViewById(R.id.btnDownload)
        btnDelete = findViewById(R.id.btnDelete)

        findViewById<TextView>(R.id.tvModelName).text = getString(R.string.voice_model_default_name)

        btnDownload.setOnClickListener {
            if (VoiceModelDownloader.isDownloading()) {
                VoiceModelDownloader.cancel()
            } else {
                startDownload()
            }
        }
        btnDelete.setOnClickListener {
            VoiceModelStore.deleteModel(applicationContext)
            refreshUi()
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        VoiceModelDownloader.cancel()
        super.onDestroy()
    }

    private fun startDownload() {
        setDownloadingUi(0, true)
        VoiceModelDownloader.download(applicationContext, object : VoiceModelDownloader.Callback {
            override fun onProgress(fileName: String, doneBytes: Long, totalBytes: Long) {
                val percent = if (totalBytes > 0) (doneBytes * 100 / totalBytes).toInt() else 0
                setDownloadingUi(percent, true)
                // 进度文案直接拼接，避免资源格式化参数问题
                tvProgressText.text = "$fileName · $percent%"
            }

            override fun onSuccess(totalBytes: Long) {
                Toast.makeText(this@VoiceModelActivity, R.string.voice_model_download_finished, Toast.LENGTH_SHORT).show()
                refreshUi()
            }

            override fun onError(message: String) {
                Toast.makeText(this@VoiceModelActivity, R.string.voice_model_download_failed, Toast.LENGTH_SHORT).show()
                refreshUi()
            }
        })
    }

    private fun setDownloadingUi(percent: Int, downloading: Boolean) {
        btnDownload.text = getString(if (downloading) R.string.voice_model_cancel else R.string.voice_model_download)
        btnDelete.visibility = if (downloading) View.GONE else View.VISIBLE
        progressBar.visibility = if (downloading) View.VISIBLE else View.GONE
        tvProgressText.visibility = if (downloading) View.VISIBLE else View.GONE
        if (downloading) progressBar.progress = percent
    }

    private fun refreshUi() {
        val installed = VoiceModelStore.isInstalled(applicationContext)
        btnDownload.text = getString(R.string.voice_model_download)
        btnDelete.visibility = if (installed) View.VISIBLE else View.GONE
        progressBar.visibility = View.GONE
        tvProgressText.visibility = View.GONE
        tvStatus.text = getString(
            if (installed) R.string.voice_model_status_installed else R.string.voice_model_status_missing
        )
    }
}

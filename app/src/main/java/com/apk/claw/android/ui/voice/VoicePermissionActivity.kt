package com.apk.claw.android.ui.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.apk.claw.android.R
import com.apk.claw.android.appViewModel

/**
 * 透明 trampoline：悬浮球长按语音前申请 RECORD_AUDIO 运行时权限。
 * 授权后继续语音识别；拒绝则仅提示并结束。
 * （参照 ClipboardReaderActivity：从悬浮窗发起时 app 可能在后台，
 * 运行时权限弹窗必须有前台 Activity 承载。）
 */
class VoicePermissionActivity : Activity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            finishAndContinue()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO || handled) return
        handled = true
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            finishAndContinue()
        } else {
            Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun finishAndContinue() {
        finish()
        appViewModel.startVoiceInput()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 6001

        fun start(context: Context) {
            context.startActivity(
                Intent(context, VoicePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

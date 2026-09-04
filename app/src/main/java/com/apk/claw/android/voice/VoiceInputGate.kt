package com.apk.claw.android.voice

/**
 * F6 悬浮球语音输入的前置决策：设备上没有离线语音模型时直接引导去下载页。
 */
enum class VoiceInputAction {
    /** 用离线引擎（sherpa-onnx SenseVoice）录音识别 */
    START_OFFLINE,

    /** 先申请 RECORD_AUDIO */
    REQUEST_PERMISSION,

    /** 未安装离线语音模型：打开下载页 */
    GO_MODEL_DOWNLOAD
}

object VoiceInputGate {

    /**
     * @param hasRecordAudio 是否已授予麦克风权限
     * @param offlineModelAvailable 离线模型是否已安装
     */
    fun decide(hasRecordAudio: Boolean, offlineModelAvailable: Boolean): VoiceInputAction = when {
        !offlineModelAvailable -> VoiceInputAction.GO_MODEL_DOWNLOAD
        !hasRecordAudio -> VoiceInputAction.REQUEST_PERMISSION
        else -> VoiceInputAction.START_OFFLINE
    }
}

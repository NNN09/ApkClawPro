package com.apk.claw.android.voice

/**
 * F6 语音引擎抽象：屏蔽"离线模型 / 系统 SpeechRecognizer / 未来云端转写"的差异。
 * 回调在引擎线程触发，调用方自行切线程。
 */
interface VoiceEngine {

    fun start(listener: Listener)

    /** 结束录音并产出最终文本（经 listener.onFinal 返回） */
    fun stop()

    /** 丢弃本次会话，之后不再有任何回调 */
    fun cancel()

    interface Listener {
        /** 流式部分结果，随识别推进多次回调 */
        fun onPartial(text: String)

        /** 最终文本，正常结束只会回调一次（cancel 后不回调） */
        fun onFinal(text: String)

        fun onError(message: String)
    }
}

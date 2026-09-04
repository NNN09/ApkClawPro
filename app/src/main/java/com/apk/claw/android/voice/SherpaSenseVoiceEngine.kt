package com.apk.claw.android.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.apk.claw.android.utils.XLog
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

/**
 * sherpa-onnx SenseVoice（int8）离线语音引擎。
 * 模型为整段解码（非流式）：录音期间不产生 partial，stop() 后一次性识别并回调 onFinal。
 * 文件规范见 [VoiceModelStore]：model.int8.onnx + tokens.txt 在 files/voice-model/。
 */
class SherpaSenseVoiceEngine(private val context: Context) : VoiceEngine {

    companion object {
        private const val TAG = "SenseVoice"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_MS = 100L
        private const val CHUNK_SIZE = (SAMPLE_RATE * CHUNK_MS / 1000).toInt()
    }

    private var thread: Thread? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var cancelled = false

    @Synchronized
    override fun start(listener: VoiceEngine.Listener) {
        check(thread == null) { "voice session already running" }
        cancelled = false
        stopRequested = false
        thread = Thread({ run(listener) }, "sense-voice").apply { start() }
    }

    override fun stop() {
        stopRequested = true
    }

    override fun cancel() {
        cancelled = true
        stopRequested = true
    }

    private fun run(listener: VoiceEngine.Listener) {
        var audio: AudioRecord? = null
        var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
        var recognizer: OfflineRecognizer? = null
        try {
            val files = VoiceModelStore.installedFiles(context) ?: error("voice model not installed")
            audio = openMicrophone()
            audio.startRecording()

            // 录音（float 缓冲扩容收集），直到 stop()/cancel() 或超长截断
            val chunk = ShortArray(CHUNK_SIZE)
            var buf = FloatArray(SAMPLE_RATE * 10)
            var len = 0
            while (!stopRequested) {
                val n = audio.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
                for (i in 0 until n) buf[len++] = chunk[i] / 32768f
                if (len >= SAMPLE_RATE * 60) break // 防御：60s 上限
            }
            if (cancelled) return

            val startAt = System.currentTimeMillis()
            recognizer = OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = files.first.absolutePath,
                            language = "auto",
                            useInverseTextNormalization = true,
                        ),
                        tokens = files.second.absolutePath,
                        numThreads = 2,
                        modelType = "sense_voice",
                    ),
                ),
            )
            XLog.i(TAG, "recognizer loaded in ${System.currentTimeMillis() - startAt}ms")
            stream = recognizer.createStream()
            stream.acceptWaveform(buf.copyOf(len), SAMPLE_RATE)
            val decodeAt = System.currentTimeMillis()
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            XLog.i(TAG, "decoded ${len / SAMPLE_RATE}s audio in ${System.currentTimeMillis() - decodeAt}ms")
            if (!cancelled) listener.onFinal(text)
        } catch (e: Exception) {
            XLog.w(TAG, "offline voice failed: ${e.message}")
            if (!cancelled) listener.onError(e.message ?: "unknown error")
        } finally {
            try { audio?.release() } catch (_: Exception) {}
            try { stream?.release() } catch (_: Exception) {}
            try { recognizer?.release() } catch (_: Exception) {}
            thread = null
        }
    }

    private fun openMicrophone(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE) * 2
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        return record
    }
}

package com.apk.claw.android.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.apk.claw.android.utils.XLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 离线语音模型下载器：按 [VoiceModelStore] 的文件清单顺序下载到 files/voice-model/。
 * 文件先写 *.part，完成后改名；取消/失败时清理 part。回调在主线程触发。
 */
object VoiceModelDownloader {

    private const val TAG = "VoiceModelDl"
    private const val CONNECT_TIMEOUT_S = 15L
    private const val READ_TIMEOUT_S = 30L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "voice-model-download") }
    private val cancelled = AtomicBoolean(false)
    private var active = false

    fun isDownloading(): Boolean = active

    /** 取消进行中的下载（part 文件保留，下次下载覆盖） */
    fun cancel() {
        cancelled.set(true)
    }

    fun download(context: Context, callback: Callback) {
        if (active) {
            callback.onError("download already running")
            return
        }
        active = true
        cancelled.set(false)
        executor.execute {
            var totalBytes = 0L
            try {
                val dir = VoiceModelStore.modelDir(context)
                dir.mkdirs()
                for (name in listOf(VoiceModelStore.MODEL_FILE, VoiceModelStore.TOKENS_FILE)) {
                    if (cancelled.get()) return@execute
                    totalBytes += downloadWithFallback(name, File(dir, name), { done, all ->
                        XLog.i(TAG, "progress $name $done/$all")
                        callback.onProgress(name, done, all)
                    })
                }
                post { callback.onSuccess(totalBytes) }
            } catch (e: Exception) {
                XLog.w(TAG, "download failed: ${e.message}")
                if (!cancelled.get()) post { callback.onError(e.message ?: "download failed") }
            } finally {
                active = false
            }
        }
    }

    /** 依次尝试候选源下载单个文件，全部失败抛异常 */
    private fun downloadWithFallback(fileName: String, target: File, onProgress: (Long, Long) -> Unit): Long {
        var lastError: Exception? = null
        for (url in VoiceModelStore.candidateUrls(fileName)) {
            if (cancelled.get()) throw java.io.IOException("cancelled")
            try {
                return downloadOne(url, target, onProgress)
            } catch (e: Exception) {
                XLog.w(TAG, "candidate $url failed: ${e.message}")
                lastError = e
                File(target.parentFile, target.name + ".part").takeIf { it.exists() }?.delete()
                if (cancelled.get()) throw java.io.IOException("cancelled")
            }
        }
        throw lastError ?: error("no candidate url for $fileName")
    }

    private fun downloadOne(url: String, target: File, onProgress: (Long, Long) -> Unit): Long {
        val part = File(target.parentFile, target.name + ".part")
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "ApkClaw").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            val total = resp.body?.contentLength() ?: -1L
            var done = 0L
            var lastReport = 0L
            val buf = ByteArray(64 * 1024)
            resp.body!!.byteStream().use { input ->
                part.outputStream().use { output ->
                    while (true) {
                        if (cancelled.get()) {
                            input.close()
                            throw java.io.IOException("cancelled")
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        // 网络流 read 长度不定，用"距上次上报超过阈值"而非整除判断
                        if (done - lastReport >= (512 * 1024) || done == total) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (!part.renameTo(target)) {
                part.delete()
                throw java.io.IOException("rename failed")
            }
            return done
        }
    }

    private inline fun post(crossinline block: () -> Unit) {
        mainHandler.post { block() }
    }

    interface Callback {
        fun onProgress(fileName: String, doneBytes: Long, totalBytes: Long)
        fun onSuccess(totalBytes: Long)
        fun onError(message: String)
    }
}

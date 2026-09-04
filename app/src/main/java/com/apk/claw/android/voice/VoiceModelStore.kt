package com.apk.claw.android.voice

import android.content.Context
import java.io.File

/**
 * 离线语音模型（sherpa-onnx SenseVoice int8）的目录与文件规范。
 * 模型不随 APK 分发：由设置页入口手动下载到外部 files/voice-model/。
 */
object VoiceModelStore {

    /** SenseVoice 中文（普通话/粤语）+ 英日韩 */

    val MODEL_FILE = "model.int8.onnx"
    val TOKENS_FILE = "tokens.txt"
    private val REQUIRED_FILES = listOf(MODEL_FILE, TOKENS_FILE)

    /** 下载候选源（按顺序尝试）：国内可达镜像优先（hf-mirror），HF 官方兜底 */
    val FILE_URL_CANDIDATES: Map<String, List<String>> = mapOf(
        MODEL_FILE to listOf(
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
        ),
        TOKENS_FILE to listOf(
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
        ),
    )

    /** 各文件的下载候选 URL */
    fun candidateUrls(fileName: String): List<String> = FILE_URL_CANDIDATES[fileName].orEmpty()

    fun modelDir(context: Context): File = File(context.getExternalFilesDir(null), "voice-model")

    fun file(context: Context, name: String): File = File(modelDir(context), name)

    /** 模型是否已完整安装（两个文件都存在） */
    fun isInstalled(context: Context): Boolean = installedFiles(context) != null

    fun installedFiles(context: Context): Pair<File, File>? {
        val dir = modelDir(context)
        if (!dir.isDirectory) return null
        val model = File(dir, MODEL_FILE).takeIf { it.isFile } ?: return null
        val tokens = File(dir, TOKENS_FILE).takeIf { it.isFile } ?: return null
        return model to tokens
    }

    /** 已安装体积（字节，未安装返回 null） */
    fun installedSizeBytes(context: Context): Long? =
        installedFiles(context)?.let { (m, t) -> m.length() + t.length() }

    fun deleteModel(context: Context) {
        (REQUIRED_FILES + REQUIRED_FILES.map { "$it.part" }).forEach { name ->
            file(context, name).takeIf { it.exists() }?.delete()
        }
    }
}

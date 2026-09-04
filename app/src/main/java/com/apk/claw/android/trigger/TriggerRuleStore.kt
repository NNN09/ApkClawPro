package com.apk.claw.android.trigger

import com.google.gson.Gson
import java.io.File

/**
 * F8 触发配置落盘：总开关 + 白名单 + 规则表整体存为一个 JSON 文件。
 * 每次通知到达时活读内存镜像（[get]），修改走 [replaceAll] 整体校验 + 原子写。
 */
object TriggerRuleStore {

    private const val FILE_NAME = "trigger_rules.json"

    private val gson = Gson()
    private val lock = Any()

    // 以下字段由 init 初始化；未 init 前访问属于编程错误，与 ScheduledTaskStore 同约定
    private lateinit var file: File
    private var config = TriggerConfig()

    fun init(rootDir: File) {
        synchronized(lock) {
            rootDir.mkdirs()
            file = File(rootDir, FILE_NAME)
            config = TriggerConfig()
            if (file.exists()) {
                try {
                    val text = file.readText()
                    if (text.isNotBlank()) config = gson.fromJson(text, TriggerConfig::class.java) ?: TriggerConfig()
                } catch (_: Exception) {
                }
            }
            // 容错加载：脏数据一律按默认关闭处理，绝不让坏配置自动触发
            if (TriggerConfig.validate(config) != null) {
                config = TriggerConfig()
            }
        }
    }

    fun get(): TriggerConfig = synchronized(lock) { config }

    /** 整体替换；任一部分非法返回错误描述并保留原配置（原子性） */
    fun replaceAll(newConfig: TriggerConfig): String? = synchronized(lock) {
        val error = TriggerConfig.validate(newConfig)
        if (error != null) return error
        config = newConfig
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(gson.toJson(config))
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(config))
                tmp.delete()
            }
        } catch (_: Exception) {
        }
        return null
    }
}

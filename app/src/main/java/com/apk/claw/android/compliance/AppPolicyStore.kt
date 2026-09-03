package com.apk.claw.android.compliance

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * C6：按 App 自动化策略落盘（JSON + 内存镜像，与 ScheduledTaskStore 同约定）。
 * 策略量小（≤ [AppPolicy.MAX_POLICIES]），每次修改整体重写；查询走内存镜像零 IO。
 * 未 init 前访问属于编程错误（与 SessionStore 同约定）。
 */
object AppPolicyStore {

    private data class PolicyDto(
        @SerializedName("packageName") val packageName: String?,
        @SerializedName("mode") val mode: String?
    )

    private const val FILE_NAME = "app_policies.json"

    private val gson = Gson()
    private val lock = Any()

    private lateinit var file: File
    private val policies = mutableListOf<AppPolicy>()

    fun init(rootDir: File) {
        synchronized(lock) {
            rootDir.mkdirs()
            file = File(rootDir, FILE_NAME)
            policies.clear()
            if (file.exists()) {
                try {
                    val type = object : TypeToken<MutableList<PolicyDto>>() {}.type
                    val text = file.readText()
                    if (text.isNotBlank()) {
                        val dtos = gson.fromJson<List<PolicyDto>>(text, type)
                        dtos.forEach { dto ->
                            val mode = AppPolicy.modeOf(dto.mode)
                            if (dto.packageName != null && mode != null) {
                                policies.add(AppPolicy(dto.packageName, mode))
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun list(): List<AppPolicy> = synchronized(lock) { policies.toList() }

    fun find(packageName: String): AppPolicy? = synchronized(lock) {
        policies.firstOrNull { it.packageName == packageName }
    }

    /**
     * 整体替换策略表（配置页保存语义）。全部条目合法且不超上限才生效，否则原样保留并返回失败原因。
     */
    fun replaceAll(items: List<AppPolicy>): String? = synchronized(lock) {
        val invalid = items.firstOrNull { !it.isValid() }
        if (invalid != null) return "invalid package name or mode: ${invalid.packageName}"
        if (items.size > AppPolicy.MAX_POLICIES) return "policy limit (${AppPolicy.MAX_POLICIES}) reached"
        policies.clear()
        policies.addAll(items)
        persist()
        return null
    }

    private fun persist() {
        try {
            val dtos = policies.map { PolicyDto(it.packageName, it.mode.name) }
            file.writeText(gson.toJson(dtos))
        } catch (_: Exception) {
        }
    }
}

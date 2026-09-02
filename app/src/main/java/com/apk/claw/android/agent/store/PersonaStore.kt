package com.apk.claw.android.agent.store

import java.io.File

/** 人格设定存储：filesDir/agent/persona.md，人类可直接编辑 */
object PersonaStore {

    private lateinit var file: File

    fun init(rootDir: File) {
        rootDir.mkdirs()
        file = File(rootDir, "persona.md")
    }

    fun get(): String = if (file.exists()) file.readText().trim() else ""

    fun set(content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content.trim())
    }
}

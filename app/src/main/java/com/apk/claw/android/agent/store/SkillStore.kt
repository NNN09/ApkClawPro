package com.apk.claw.android.agent.store

import java.io.File

/**
 * 技能文件系统：filesDir/agent/skills/<name>/SKILL.md
 * 系统提示词只注入目录（name+description），正文由 load_skill 工具按需加载（渐进披露）。
 * description 语义参考 Anthropic Agent Skills 规范：写清"做什么 + 何时用"，关键用例前置——
 * 模型完全依据 description 决定何时调用，描述过宽会误触发。
 */
object SkillStore {

    data class Skill(val name: String, val description: String)

    private val NAME_REGEX = Regex("^[a-z0-9-]{1,40}$")
    private val lock = Any()
    private lateinit var dir: File

    @JvmStatic
    fun init(rootDir: File) {
        synchronized(lock) {
            dir = File(rootDir, "skills")
            dir.mkdirs()
        }
    }

    @JvmStatic
    fun upsert(name: String, description: String, body: String): Boolean = synchronized(lock) {
        if (!NAME_REGEX.matches(name)) return false
        val skillDir = File(dir, name)
        skillDir.mkdirs()
        val header = "---\nname: $name\ndescription: ${description.replace("\n", " ")}\n---\n"
        File(skillDir, "SKILL.md").writeText(header + body.trim() + "\n")
        true
    }

    @JvmStatic
    fun list(): List<Skill> = synchronized(lock) {
        val dirs = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
        dirs.mapNotNull { d ->
            val md = File(d, "SKILL.md")
            if (!md.exists()) return@mapNotNull null
            Skill(d.name, parseDescription(md.readText()))
        }.sortedBy { it.name }
    }

    @JvmStatic
    fun load(name: String): String? = synchronized(lock) {
        if (!NAME_REGEX.matches(name)) return null
        val md = File(dir, "$name${File.separator}SKILL.md")
        if (!md.exists()) return null
        stripFrontmatter(md.readText()).trim().ifEmpty { null }
    }

    /** F11：导出 SKILL.md 原文（含 frontmatter），供分发；非法名或不存在返回 null */
    @JvmStatic
    fun exportRaw(name: String): String? = synchronized(lock) {
        if (!NAME_REGEX.matches(name)) return null
        val md = File(dir, "$name${File.separator}SKILL.md")
        if (!md.exists()) null else md.readText()
    }

    /** F11：技能根目录（zip 导出用）；未 init 前调用会抛异常，与其它方法一致 */
    @JvmStatic
    fun skillsDir(): File = synchronized(lock) { dir }

    fun catalogSection(): String {
        val skills = list()
        if (skills.isEmpty()) return ""
        return "## 可用技能\n" +
            skills.joinToString("\n") { "- ${it.name}: ${it.description}" } +
            "\n执行任务时如需某个技能的详细步骤，先调用 load_skill(name) 获取全文，再按步骤操作。"
    }

    private fun parseDescription(raw: String): String {
        if (!raw.startsWith("---\n")) return ""
        val end = raw.indexOf("\n---\n", 4)
        if (end < 0) return ""
        return raw.substring(4, end).lineSequence()
            .firstOrNull { it.startsWith("description:") }
            ?.removePrefix("description:")?.trim() ?: ""
    }

    private fun stripFrontmatter(raw: String): String {
        if (!raw.startsWith("---\n")) return raw
        val end = raw.indexOf("\n---\n", 4)
        if (end < 0) return raw
        return raw.substring(end + 5)
    }
}

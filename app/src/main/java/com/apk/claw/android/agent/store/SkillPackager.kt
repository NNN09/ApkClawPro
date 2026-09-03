package com.apk.claw.android.agent.store

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * F11 技能包打包/解析（纯 JVM，可单测）。
 * 单技能 = SKILL.md（含 frontmatter：name/description）；打包 = 全部技能目录的 zip。
 * 只认「目录名/SKILL.md」结构，拒绝 zip 内路径穿越（..）与绝对路径条目。
 */
object SkillPackager {

    /** 从 SKILL.md 原文解析出的技能（description 取 frontmatter，body 去掉 frontmatter） */
    data class ParsedSkill(val name: String, val description: String, val body: String)

    /** zip 内允许的最大 SKILL.md 解压后大小（防止压缩炸弹） */
    private const val MAX_ENTRY_BYTES = 512 * 1024
    private const val MAX_SKILLS = 50

    /** 把 skillsDir 下所有 <name>/SKILL.md 打包为 zip 字节流 */
    fun exportAll(skillsDir: File): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val dirs = skillsDir.listFiles { f -> f.isDirectory } ?: return@use
            dirs.sortedBy { it.name }.forEach { dir ->
                val md = File(dir, "SKILL.md")
                if (md.exists()) {
                    zip.putNextEntry(ZipEntry("${dir.name}/SKILL.md"))
                    zip.write(md.readBytes())
                    zip.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * 解析 zip 字节流为技能列表。
     * 防御：跳过非 SKILL.md 条目、拒绝 .. / 绝对路径条目、限制条目数与单条大小。
     * 名字不合法或描述缺失的条目跳过（由调用方决定是否提示）。
     */
    fun importZip(bytes: ByteArray): List<ParsedSkill> {
        val skills = mutableListOf<ParsedSkill>()
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null && count < MAX_SKILLS) {
                val name = entry.name.replace('\\', '/')
                val dirName = name.removeSuffix("SKILL.md").trimEnd('/')
                if (!entry.isDirectory &&
                    name.endsWith("SKILL.md") &&
                    !name.contains("..") &&
                    !name.startsWith("/") &&
                    dirName.isNotEmpty() && !dirName.contains('/')
                ) {
                    val content = readBounded(zip, MAX_ENTRY_BYTES).toString(Charsets.UTF_8)
                    parseSkillMd(content)?.let {
                        // 目录名优先于 frontmatter name，保证与存储目录一致
                        skills.add(it.copy(name = dirName))
                        count++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return skills
    }

    /**
     * 读取最多 max 字节（Java 8 兼容实现，避免 InputStream.readNBytes 的 API 33+ 要求）。
     * F11 下载与 zip 解压共用，防压缩炸弹/超大文件。
     */
    fun readBounded(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < max) {
            val n = input.read(buffer, 0, minOf(buffer.size, max - total))
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /**
     * 解析单个 SKILL.md：frontmatter 提供默认 name/description；
     * 没有 frontmatter 时整篇当 body，name 须由调用方提供（返回 null）。
     */
    fun parseSkillMd(raw: String): ParsedSkill? {
        if (!raw.startsWith("---\n")) return null
        val end = raw.indexOf("\n---\n", 4)
        if (end < 0) return null
        val header = raw.substring(4, end)
        val body = raw.substring(end + 5).trim()
        if (body.isEmpty()) return null
        var name = ""
        var description = ""
        header.lineSequence().forEach { line ->
            when {
                line.startsWith("name:") -> name = line.removePrefix("name:").trim()
                line.startsWith("description:") -> description = line.removePrefix("description:").trim()
            }
        }
        if (!Regex("^[a-z0-9-]{1,40}$").matches(name)) return null
        return ParsedSkill(name, description, body)
    }
}

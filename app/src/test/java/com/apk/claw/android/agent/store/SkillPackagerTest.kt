package com.apk.claw.android.agent.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** F11 技能包 zip 打包/解析的纯 JVM 测试 */
class SkillPackagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun skillMd(name: String, description: String, body: String): String =
        "---\nname: $name\ndescription: $description\n---\n$body\n"

    // ==================== parseSkillMd ====================

    @Test
    fun parseSkillMd_validFrontmatter() {
        val parsed = SkillPackager.parseSkillMd(skillMd("demo", "演示技能", "步骤一"))
        assertEquals("demo", parsed!!.name)
        assertEquals("演示技能", parsed.description)
        assertEquals("步骤一", parsed.body)
    }

    @Test
    fun parseSkillMd_noFrontmatter_returnsNull() {
        assertNull(SkillPackager.parseSkillMd("纯正文，没有 frontmatter"))
    }

    @Test
    fun parseSkillMd_emptyBody_returnsNull() {
        assertNull(SkillPackager.parseSkillMd("---\nname: demo\ndescription: d\n---\n\n"))
    }

    @Test
    fun parseSkillMd_invalidName_returnsNull() {
        assertNull(SkillPackager.parseSkillMd("---\nname: Bad_Name!\ndescription: d\n---\nbody"))
    }

    @Test
    fun parseSkillMd_unclosedFrontmatter_returnsNull() {
        assertNull(SkillPackager.parseSkillMd("---\nname: demo\ndescription: no end"))
    }

    // ==================== zip round-trip ====================

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun exportImport_roundTrip() {
        val skillsDir = tmp.newFolder("skills")
        File(skillsDir, "alpha").mkdirs()
        File(skillsDir, "alpha/SKILL.md").writeText(skillMd("alpha", "Alpha 技能", "A 步骤"))
        File(skillsDir, "beta").mkdirs()
        File(skillsDir, "beta/SKILL.md").writeText(skillMd("beta", "Beta 技能", "B 步骤"))
        File(skillsDir, "broken").mkdirs()   // 无 SKILL.md 的目录被忽略

        val zip = SkillPackager.exportAll(skillsDir)
        val imported = SkillPackager.importZip(zip)

        assertEquals(listOf("alpha", "beta"), imported.map { it.name })
        assertEquals("Alpha 技能", imported[0].description)
        assertEquals("A 步骤", imported[0].body)
    }

    @Test
    fun exportAll_emptyDir_returnsEmptyZipWithNoSkills() {
        val skillsDir = tmp.newFolder("empty-skills")
        assertTrue(SkillPackager.importZip(SkillPackager.exportAll(skillsDir)).isEmpty())
    }

    @Test
    fun importZip_directoryNameWinsOverFrontmatter() {
        // frontmatter 名与目录名不一致时以目录名为准（与存储结构一致）
        val zip = zipOf(mapOf("actual-name/SKILL.md" to skillMd("frontmatter-name", "d", "b").toByteArray()))
        val imported = SkillPackager.importZip(zip)
        assertEquals(listOf("actual-name"), imported.map { it.name })
    }

    @Test
    fun importZip_rejectsPathTraversal() {
        val zip = zipOf(mapOf("../evil/SKILL.md" to skillMd("evil", "d", "b").toByteArray()))
        assertTrue(SkillPackager.importZip(zip).isEmpty())
    }

    @Test
    fun importZip_rejectsAbsolutePathsAndNestedDirs() {
        val zip = zipOf(
            mapOf(
                "/abs/SKILL.md" to skillMd("abs", "d", "b").toByteArray(),
                "a/b/SKILL.md" to skillMd("nested", "d", "b").toByteArray()
            )
        )
        assertTrue(SkillPackager.importZip(zip).isEmpty())
    }

    @Test
    fun importZip_skipsNonSkillMdEntries() {
        val zip = zipOf(
            mapOf(
                "good/SKILL.md" to skillMd("good", "d", "b").toByteArray(),
                "good/notes.txt" to "not a skill".toByteArray(),
                "readme.md" to "top-level readme".toByteArray()
            )
        )
        val imported = SkillPackager.importZip(zip)
        assertEquals(listOf("good"), imported.map { it.name })
    }

    @Test
    fun importZip_truncatesOversizedEntry() {
        val bigBody = "x".repeat(600 * 1024)   // 超过 MAX_ENTRY_BYTES(512KB)
        val zip = zipOf(mapOf("big/SKILL.md" to skillMd("big", "d", bigBody).toByteArray()))
        val imported = SkillPackager.importZip(zip)
        assertEquals(1, imported.size)
        assertTrue(imported[0].body.length < 600 * 1024)   // 被截断而不是无限读入
    }

    // ==================== readBounded ====================

    @Test
    fun readBounded_readsUpToMax() {
        val data = ByteArray(1000) { it.toByte() }
        val read = SkillPackager.readBounded(ByteArrayInputStream(data), 100)
        assertEquals(100, read.size)
    }

    @Test
    fun readBounded_shorterThanMax_readsAll() {
        val data = ByteArray(50) { 7 }
        val read = SkillPackager.readBounded(ByteArrayInputStream(data), 100)
        assertEquals(50, read.size)
    }
}

package com.apk.claw.android.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScreenshotCacheTest {

    private fun newTempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "screenshot-cache-test-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    private fun newFile(dir: File, name: String, modifiedMs: Long): File {
        val f = File(dir, name)
        f.writeText("x")
        f.setLastModified(modifiedMs)
        f.deleteOnExit()
        return f
    }

    @Test
    fun prunesOldestBeyondKeep() {
        val dir = newTempDir()
        val files = (1..5).map { newFile(dir, "s$it.png", it * 10_000L) }   // s1 最旧
        val deleted = ScreenshotCache.pruneOldest(dir, keep = 3)
        assertEquals(2, deleted)
        assertFalse(files[0].exists())
        assertFalse(files[1].exists())
        assertTrue(files[2].exists())
        assertTrue(files[3].exists())
        assertTrue(files[4].exists())
    }

    @Test
    fun noopWhenWithinKeep() {
        val dir = newTempDir()
        repeat(3) { newFile(dir, "s$it.png", it * 10_000L) }
        assertEquals(0, ScreenshotCache.pruneOldest(dir, keep = 3))
        assertEquals(0, ScreenshotCache.pruneOldest(dir, keep = 10))
    }

    @Test
    fun keepZeroIsProtectedNoop() {
        // keep<=0 视为调用异常，防误清空整个目录
        val dir = newTempDir()
        newFile(dir, "s.png", 1_000L)
        assertEquals(0, ScreenshotCache.pruneOldest(dir, keep = 0))
        assertEquals(0, ScreenshotCache.pruneOldest(dir, keep = -1))
        assertTrue(File(dir, "s.png").exists())
    }

    @Test
    fun nullOrMissingDirReturnsZero() {
        assertEquals(0, ScreenshotCache.pruneOldest(null, keep = 5))
        assertEquals(0, ScreenshotCache.pruneOldest(File(System.getProperty("java.io.tmpdir"), "no-such-dir-${System.nanoTime()}"), keep = 5))
    }
}

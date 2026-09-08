package com.apk.claw.android.tool

import java.io.File

/**
 * 截图缓存生命周期：TakeScreenshotTool 只写不删会让 cache/screenshots 随使用无限增长。
 * 每次写入后按保留数量剪枝（最旧先删）。截图注入 LLM 时已转为 base64 图像，
 * 文件只以路径文本形式被历史消息引用，删除旧文件不影响已完成任务的上下文内容。
 */
object ScreenshotCache {

    /** 默认保留张数 */
    const val DEFAULT_KEEP = 20

    /**
     * 删除 dir 下最旧的文件，只保留最新 [keep] 个。
     * @return 实际删除数；dir 为 null/不存在、keep<=0（调用异常保护，防误清空）时返回 0
     */
    @JvmStatic
    fun pruneOldest(dir: File?, keep: Int): Int {
        if (dir == null || !dir.isDirectory || keep <= 0) return 0
        val files = dir.listFiles()?.filter { it.isFile } ?: return 0
        if (files.size <= keep) return 0
        val toDelete = files.sortedBy { it.lastModified() }.take(files.size - keep)
        var deleted = 0
        for (f in toDelete) {
            if (f.delete()) deleted++
        }
        return deleted
    }
}

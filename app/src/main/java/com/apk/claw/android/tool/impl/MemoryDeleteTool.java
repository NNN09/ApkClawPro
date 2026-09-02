package com.apk.claw.android.tool.impl;

import com.apk.claw.android.agent.store.MemoryStore;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MemoryDeleteTool extends BaseTool {

    @Override
    public String getName() { return "memory_delete"; }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("text", "string", "要删除的记忆原文（须与 memory_list 显示的文本一致）", true));
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String text = requireString(params, "text");
        boolean ok = MemoryStore.delete(text);
        return ok ? ToolResult.success("已删除记忆") : ToolResult.error("未找到该记忆");
    }

    @Override
    public String getDescriptionEN() {
        return "Delete an outdated or wrong entry from long-term memory.";
    }

    @Override
    public String getDescriptionCN() {
        return "从长期记忆中删除一条过时或错误的条目。";
    }
}

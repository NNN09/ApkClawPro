package com.apk.claw.android.tool.impl;

import com.apk.claw.android.agent.store.MemoryStore;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MemorySaveTool extends BaseTool {

    @Override
    public String getName() { return "memory_save"; }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("text", "string", "要长期记住的一条事实或偏好，一句话", true));
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String text = requireString(params, "text");
        boolean ok = MemoryStore.save(text);
        return ok ? ToolResult.success("已保存记忆") : ToolResult.error("记忆内容为空");
    }

    @Override
    public String getDescriptionEN() {
        return "Save a durable fact or user preference to long-term memory for future tasks.";
    }

    @Override
    public String getDescriptionCN() {
        return "把一条稳定的事实或用户偏好保存到长期记忆，之后的任务都能看到。";
    }
}

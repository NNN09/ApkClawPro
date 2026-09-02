package com.apk.claw.android.tool.impl;

import com.apk.claw.android.agent.store.MemoryStore;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MemoryListTool extends BaseTool {

    @Override
    public String getName() { return "memory_list"; }

    @Override
    public List<ToolParameter> getParameters() { return Collections.emptyList(); }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        List<String> all = MemoryStore.all();
        if (all.isEmpty()) {
            return ToolResult.success("当前没有长期记忆");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            sb.append(i + 1).append(". ").append(all.get(i)).append("\n");
        }
        return ToolResult.success(sb.toString().trim());
    }

    @Override
    public String getDescriptionEN() {
        return "List all long-term memory entries.";
    }

    @Override
    public String getDescriptionCN() {
        return "列出当前全部长期记忆。";
    }
}

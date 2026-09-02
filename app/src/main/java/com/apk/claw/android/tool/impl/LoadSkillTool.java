package com.apk.claw.android.tool.impl;

import com.apk.claw.android.agent.store.SkillStore;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class LoadSkillTool extends BaseTool {

    @Override
    public String getName() { return "load_skill"; }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("name", "string", "技能名，来自系统提示词中的可用技能列表", true));
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String name = requireString(params, "name");
        String body = SkillStore.load(name);
        return body != null ? ToolResult.success(body) : ToolResult.error("技能不存在: " + name);
    }

    @Override
    public String getDescriptionEN() {
        return "Load the full step-by-step content of a skill by name.";
    }

    @Override
    public String getDescriptionCN() {
        return "按名称加载一个技能的完整分步内容。";
    }
}

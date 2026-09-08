package com.apk.claw.android.tool.impl.mobile;

import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.R;
import com.apk.claw.android.service.ClawAccessibilityService;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SwipeTool extends BaseTool {

    @Override
    public String getName() {
        return "swipe";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_swipe);
    }

    @Override
    public String getDescriptionEN() {
        return "Swipe from one point to another on the screen. Coordinates are permille (0-1000 of screen width/height), same space as bounds/center from get_screen_info. Useful for scrolling, pulling down notifications, etc.";
    }

    @Override
    public String getDescriptionCN() {
        return "在屏幕上从一个点滑动到另一个点。坐标为 0-1000 千分比（与 get_screen_info 的 bounds/center 同一坐标系）。适用于滚动、下拉通知等操作。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("start_x", "integer", "Start X in permille of screen width (0-1000)", true),
                new ToolParameter("start_y", "integer", "Start Y in permille of screen height (0-1000)", true),
                new ToolParameter("end_x", "integer", "End X in permille of screen width (0-1000)", true),
                new ToolParameter("end_y", "integer", "End Y in permille of screen height (0-1000)", true),
                new ToolParameter("duration_ms", "integer", "Swipe duration in milliseconds (default 500)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        int startX = requireInt(params, "start_x");
        int startY = requireInt(params, "start_y");
        int endX = requireInt(params, "end_x");
        int endY = requireInt(params, "end_y");
        String boundsError = validateCoordinates(startX, startY);
        if (boundsError != null) return ToolResult.error(boundsError);
        boundsError = validateCoordinates(endX, endY);
        if (boundsError != null) return ToolResult.error(boundsError);
        long duration = optionalLong(params, "duration_ms", 500);
        boolean success = service.performSwipe(pixelX(startX), pixelY(startY), pixelX(endX), pixelY(endY), duration);
        return success ? ToolResult.success("Swiped from (" + startX + ", " + startY + ") to (" + endX + ", " + endY + ")")
                : ToolResult.error("Failed to swipe");
    }
}

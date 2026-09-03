package com.apk.claw.android.tool.impl;

import android.graphics.Bitmap;

import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.R;
import com.apk.claw.android.service.ClawAccessibilityService;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class TakeScreenshotTool extends BaseTool {

    @Override
    public String getName() {
        return "take_screenshot";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_screenshot);
    }

    @Override
    public String getDescriptionEN() {
        return "Take a screenshot; the image is injected into your context for direct visual inspection (also saved as PNG, path returned). Use when the accessibility tree cannot read the screen content (WebView, custom-drawn UI, games) or when visual confirmation is needed. Requires Android 11+ (API 30).";
    }

    @Override
    public String getDescriptionCN() {
        return "截取当前屏幕，图像会直接进入你的上下文供视觉观察（同时保存 PNG 并返回路径）。当无障碍节点树读不到界面内容时使用（典型：WebView 网页、自绘 UI、游戏），或需要确认界面视觉效果时使用。需要 Android 11+（API 30）。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.emptyList();
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        Bitmap bitmap = service.takeScreenshot(5000);
        if (bitmap == null) {
            return ToolResult.error("Failed to take screenshot. Requires Android 11+ (API 30).");
        }

        try {
            Bitmap softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (softBitmap != null) {
                bitmap.recycle();
                bitmap = softBitmap;
            }

            File dir = new File(ClawApplication.Companion.getInstance().getCacheDir(), "screenshots");
            if (!dir.exists()) dir.mkdirs();

            String filename = System.currentTimeMillis() + ".png";
            File file = new File(dir, filename);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            bitmap.recycle();

            return ToolResult.success(file.getAbsolutePath());
        } catch (Exception e) {
            bitmap.recycle();
            return ToolResult.error("Failed to save screenshot: " + e.getMessage());
        }
    }
}

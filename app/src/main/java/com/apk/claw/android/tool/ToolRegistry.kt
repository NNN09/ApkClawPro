package com.apk.claw.android.tool

import com.apk.claw.android.tool.impl.*
import com.apk.claw.android.tool.impl.mobile.*
import com.apk.claw.android.tool.impl.system.*
import com.apk.claw.android.tool.impl.tv.*

object ToolRegistry {

    enum class DeviceType { TV, MOBILE }

    private val tools = LinkedHashMap<String, BaseTool>()
    var deviceType: DeviceType = DeviceType.TV
        private set

    @JvmStatic
    fun getInstance(): ToolRegistry = this

    fun registerAllTools(type: DeviceType = DeviceType.TV) {
        deviceType = type
        tools.clear()
        registerCommonTools()
        when (type) {
            DeviceType.TV -> registerTvTools()
            DeviceType.MOBILE -> registerMobileTools()
        }
    }

    private fun registerCommonTools() {
        register(GetScreenInfoTool())
        register(FindNodeInfoTool())
        register(InputTextTool())
        register(SystemKeyTool())
        register(OpenAppTool())
        register(GetInstalledAppsTool())
        register(TakeScreenshotTool())
        register(WaitTool())
        register(RepeatActionsTool())
        register(ClipboardTool())
        register(SendFileTool())
        register(FinishTool())
        register(MemorySaveTool())
        register(MemoryDeleteTool())
        register(MemoryListTool())
        register(LoadSkillTool())
        register(ScheduleTaskTool())
        register(CancelScheduledTaskTool())
        register(ListScheduledTasksTool())
    }

    private fun registerTvTools() {
        register(DpadUpTool())
        register(DpadDownTool())
        register(DpadLeftTool())
        register(DpadRightTool())
        register(DpadCenterTool())
        register(VolumeUpTool())
        register(VolumeDownTool())
        register(PressMenuTool())
        register(PressPowerTool())
    }

    private fun registerMobileTools() {
        register(TapTool())
        register(LongPressTool())
        register(SwipeTool())
        register(ScrollToFindTool())
        registerSystemTools()
    }

    /** F9：系统服务工具组（Intent/系统 API，比 UI 自动化可靠；手机设备才有电话/日历等能力） */
    private fun registerSystemTools() {
        register(SetAlarmTool())
        register(SetTimerTool())
        register(QueryContactsTool())
        register(CreateCalendarEventTool())
        register(QueryCalendarTool())
        register(MediaControlTool())
        register(SetVolumeTool())
        register(SetBrightnessTool())
        register(SetDndTool())
        register(OpenSettingsPageTool())
        register(DialPrefillTool())
        register(SmsPrefillTool())
        register(OpenUrlTool())
        register(QueryBatteryTool())
    }

    fun register(tool: BaseTool) {
        tools[tool.getName()] = tool
    }

    fun getTool(name: String): BaseTool? = tools[name]

    fun getDisplayName(name: String): String = tools[name]?.getDisplayName() ?: name

    fun getAllTools(): List<BaseTool> = tools.values.toList()

    fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name")
        return try {
            tool.executeWithWaitAfter(params)
        } catch (e: Exception) {
            ToolResult.error("Tool execution failed: ${e.message}")
        }
    }
}

package com.apk.claw.android.compliance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPolicyEngineTest {

    private val systemMode = { pkg: String -> pkg.startsWith("android") || pkg.startsWith("com.android") }
    private val sys = { pkg: String -> systemMode(pkg) }

    private fun modeOf(vararg entries: Pair<String, Mode>) = { pkg: String ->
        entries.firstOrNull { it.first == pkg }?.second
    }

    private fun ctx(
        foreground: String? = null,
        master: Boolean = true,
        isSystem: (String) -> Boolean = sys,
        modeOf: (String) -> Mode? = { null }
    ) = AppPolicyEngine.Ctx(
        foregroundPackage = foreground,
        masterThirdPartyEnabled = master,
        isSystemApp = isSystem,
        modeOf = modeOf
    )

    private fun isBlock(v: AppPolicyEngine.Verdict) = v is AppPolicyEngine.Verdict.Block
    private fun isConfirm(v: AppPolicyEngine.Verdict) = v is AppPolicyEngine.Verdict.Confirm
    private fun isAllow(v: AppPolicyEngine.Verdict) = v === AppPolicyEngine.Verdict.Allow

    // ==================== 作用域：非界面改动工具一律放行 ====================

    @Test
    fun systemTools_andObservations_alwaysAllowed() {
        val c = ctx(foreground = "com.tencent.mm", master = false)   // 即使总开关关着
        assertTrue(isAllow(AppPolicyEngine.evaluate("query_battery", mapOf(), c)))
        assertTrue(isAllow(AppPolicyEngine.evaluate("get_screen_info", mapOf(), c)))
        assertTrue(isAllow(AppPolicyEngine.evaluate("find_node_info", mapOf(), c)))
        assertTrue(isAllow(AppPolicyEngine.evaluate("take_screenshot", mapOf(), c)))
        assertTrue(isAllow(AppPolicyEngine.evaluate("system_key", mapOf("key" to "back"), c)))
        assertTrue(isAllow(AppPolicyEngine.evaluate("dial_prefill", mapOf("number" to "10086"), c)))
        assertTrue(isAllow(AppPolicyEngine.evaluate("open_settings_page", mapOf("page" to "main"), c)))
    }

    @Test
    fun tap_unknownForeground_allowed() {
        // 无障碍根节点不可用（系统弹窗遮挡等）→ 无法归属任何 App → 放行，由工具自身/F2 处理
        val c = ctx(foreground = null, master = false)
        assertTrue(isAllow(AppPolicyEngine.evaluate("tap", mapOf("x" to 1, "y" to 2), c)))
    }

    // ==================== BLOCK ====================

    @Test
    fun blockPolicy_blocksUiMutatingToolsInThatApp() {
        val c = ctx(
            foreground = "com.tencent.mm",
            modeOf = modeOf("com.tencent.mm" to Mode.BLOCK)
        )
        val v = AppPolicyEngine.evaluate("tap", mapOf("x" to 1, "y" to 2), c)
        assertTrue(isBlock(v))
        val b = v as AppPolicyEngine.Verdict.Block
        assertEquals(AppPolicyEngine.Verdict.Reason.APP_BLOCKED, b.reason)
        assertEquals("com.tencent.mm", b.targetPackage)
        assertTrue(isBlock(AppPolicyEngine.evaluate("input_text", mapOf("text" to "hi"), c)))
    }

    @Test
    fun blockPolicy_blocksOpenAppTargetingIt() {
        val c = ctx(
            foreground = "com.android.settings",
            modeOf = modeOf("com.alipay.android" to Mode.BLOCK)
        )
        val v = AppPolicyEngine.evaluate(
            "open_app", mapOf("package_name" to "com.alipay.android"), c)
        assertTrue(isBlock(v))
        assertEquals("com.alipay.android", (v as AppPolicyEngine.Verdict.Block).targetPackage)
    }

    @Test
    fun blockPolicy_doesNotAffectOtherApps() {
        val c = ctx(
            foreground = "com.android.settings",
            modeOf = modeOf("com.tencent.mm" to Mode.BLOCK)
        )
        assertTrue(isAllow(AppPolicyEngine.evaluate("tap", mapOf("x" to 1, "y" to 2), c)))
    }

    // ==================== CONFIRM ====================

    @Test
    fun confirmPolicy_confirmsMutatingOpsInThatApp() {
        val c = ctx(
            foreground = "com.tencent.mm",
            modeOf = modeOf("com.tencent.mm" to Mode.CONFIRM)
        )
        val v = AppPolicyEngine.evaluate("tap", mapOf("x" to 1, "y" to 2), c)
        assertTrue(isConfirm(v))
        assertEquals("com.tencent.mm", (v as AppPolicyEngine.Verdict.Confirm).targetPackage)
    }

    @Test
    fun confirmPolicy_openAppTarget_confirms() {
        val c = ctx(modeOf = modeOf("com.tencent.mm" to Mode.CONFIRM))
        assertTrue(isConfirm(AppPolicyEngine.evaluate("open_app", mapOf("package_name" to "com.tencent.mm"), c)))
    }

    @Test
    fun confirmPolicy_doesNotAffectOtherApps() {
        val c = ctx(
            foreground = "com.android.settings",
            modeOf = modeOf("com.tencent.mm" to Mode.CONFIRM)
        )
        assertTrue(isAllow(AppPolicyEngine.evaluate("tap", mapOf("x" to 1, "y" to 2), c)))
    }

    // ==================== C5 总开关 ====================

    @Test
    fun masterDisabled_blocksThirdPartyUiOps() {
        val c = ctx(foreground = "com.example.app", master = false)
        val v = AppPolicyEngine.evaluate("tap", mapOf("x" to 1, "y" to 2), c)
        assertTrue(isBlock(v))
        assertEquals(AppPolicyEngine.Verdict.Reason.MASTER_DISABLED, (v as AppPolicyEngine.Verdict.Block).reason)
    }

    @Test
    fun masterDisabled_blocksOpenAppToThirdPartyButAllowsSystem() {
        val third = ctx(master = false)
        assertTrue(isBlock(AppPolicyEngine.evaluate("open_app", mapOf("package_name" to "com.example.app"), third)))
        assertTrue(isAllow(AppPolicyEngine.evaluate("open_app", mapOf("package_name" to "com.android.settings"), third)))
    }

    @Test
    fun masterDisabled_blocksListedAUTOAndCONFIRMAppsToo() {
        // 总开关关闭 = 一律不碰第三方：即使显式配置了 CONFIRM/AUTO 也按关闭处理
        val c = ctx(
            foreground = "com.tencent.mm", master = false,
            modeOf = modeOf("com.tencent.mm" to Mode.CONFIRM)
        )
        assertTrue(isBlock(AppPolicyEngine.evaluate("tap", mapOf("x" to 1, "y" to 2), c)))
    }

    @Test
    fun masterDisabled_systemAppForeground_allowed() {
        val c = ctx(foreground = "com.android.settings", master = false)
        assertTrue(isAllow(AppPolicyEngine.evaluate("tap", mapOf("x" to 1, "y" to 2), c)))
    }

    @Test
    fun masterEnabled_unlistedThirdParty_allowed_F2FloorApplies() {
        // 未配置策略 + 总开关开 → 放行（危险操作仍由 F2 在调用方兜底确认）
        val c = ctx(foreground = "com.example.app", master = true)
        assertTrue(isAllow(AppPolicyEngine.evaluate("tap", mapOf("x" to 1, "y" to 2), c)))
        assertTrue(isAllow(AppPolicyEngine.evaluate("tap", mapOf("x" to 1, "y" to 2), ctx())))
    }

    // ==================== AUTO ====================

    @Test
    fun autoPolicy_allowsMutatingOps() {
        val c = ctx(
            foreground = "com.tencent.mm",
            modeOf = modeOf("com.tencent.mm" to Mode.AUTO)
        )
        assertTrue(isAllow(AppPolicyEngine.evaluate("tap", mapOf("x" to 1, "y" to 2), c)))
    }

    // ==================== 未安装包查询失败 ====================

    @Test
    fun unknownPackage_isSystemReturnsFalse_conservativeBlock() {
        val c = ctx(foreground = "com.weird.typo", master = false, isSystem = { false })
        assertTrue(isBlock(AppPolicyEngine.evaluate("input_text", mapOf("text" to "x"), c)))
    }
}

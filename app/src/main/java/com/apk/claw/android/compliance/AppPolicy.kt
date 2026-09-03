package com.apk.claw.android.compliance

/**
 * C6 按 App 自动化策略（ROADMAP §6 C6，F2 延伸）。
 *
 * 三种模式覆盖 ROADMAP 描述：
 * - [Mode.AUTO]：全自动——与无策略一致，危险操作仍受 F2 全局确认约束（硬底线，永不被弱化）；
 * - [Mode.CONFIRM]：该 App 内每次改动类操作都经渠道人工确认（比 F2 更严，命中后跳过 F2 避免双重弹窗）；
 * - [Mode.BLOCK]：禁止自动化该 App（改动类操作被拒；观察与导航离开不受影响，系统工具不受影响）。
 */
enum class Mode { AUTO, CONFIRM, BLOCK }

data class AppPolicy(
    /** Android 包名，如 com.tencent.mm */
    val packageName: String,
    val mode: Mode
) {

    fun isValid(): Boolean = isValidPackageName(packageName)

    companion object {
        const val MAX_POLICIES = 50

        /** Android 包名：字母开头的点分小写字母/数字/下划线段 */
        private val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

        fun isValidPackageName(pkg: String): Boolean =
            pkg.length <= 255 && PACKAGE_REGEX.matches(pkg)

        fun modeOf(raw: String?): Mode? = when (raw?.uppercase()) {
            "AUTO" -> Mode.AUTO
            "CONFIRM" -> Mode.CONFIRM
            "BLOCK" -> Mode.BLOCK
            else -> null
        }
    }
}

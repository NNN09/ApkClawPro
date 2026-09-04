package com.apk.claw.android.floating

import android.app.Application
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.blankj.utilcode.util.ThreadUtils
import com.apk.claw.android.R
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.blankj.utilcode.util.BarUtils
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import com.lzf.easyfloat.enums.SidePattern
import com.lzf.easyfloat.interfaces.OnFloatCallbacks
import com.lzf.easyfloat.utils.DisplayUtils

/**
 * 圆形悬浮窗管理器
 * 使用 EasyFloat 实现可拖动、记录位置的圆形悬浮窗
 * 支持多种状态：等待任务(IDLE)、任务执行中(RUNNING)、任务成功(SUCCESS)、任务失败(ERROR)
 */
object FloatingCircleManager {

    private const val TAG = "FloatingCircle"
    private const val FLOAT_TAG = "circle_float"
    private const val KEY_FLOAT_X = "floating_circle_x"
    private const val KEY_FLOAT_Y = "floating_circle_y"
    private const val AUTO_RESET_DELAY_MS = 5000L // 5秒后自动重置

    /**
     * 悬浮窗状态
     */
    enum class State {
        IDLE,           // 等待任务（默认）
        TASK_NOTIFY,    // 收到任务通知（胶囊展开）
        LISTENING,      // 语音按住聆听中（胶囊展开；按住期间录音，松手结束识别）
        RUNNING,        // 任务执行中
        SUCCESS,        // 任务完成
        ERROR           // 任务失败
    }

    private var isShowing = false
    private var currentState: State = State.IDLE
    private var currentRound: Int = 0
    private var currentChannel: Channel? = null

    private const val TASK_NOTIFY_DURATION_MS = 3000L // 任务通知显示 3 秒后收回

    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoResetRunnable: Runnable? = null
    private var notifyCollapseRunnable: Runnable? = null
    private var pendingTaskText: String = ""

    private var appRef: Application? = null

    /**
     * 显示悬浮窗
     * @param application Application 实例
     * @param x 初始位置 X（可选，默认屏幕右边偏中心）
     * @param y 初始位置 Y（可选，默认屏幕中心）
     */
    fun show(
        application: Application,
        x: Int? = null,
        y: Int? = null
    ) {
        if (isShowing) {
            return
        }
        appRef = application

        // 计算默认位置：屏幕中心的右边
        val screenWidth = DisplayUtils.getScreenWidth(application)
        val screenHeight = DisplayUtils.getScreenHeight(application)
        val defaultX = 0
        val defaultY = screenHeight / 2

        // 从本地读取保存的位置
        val savedX = getSavedX() ?: x ?: defaultX
        val savedY = getSavedY() ?: y ?: defaultY

        EasyFloat.with(application)
            .setLayout(R.layout.layout_floating_circle)
            .setShowPattern(ShowPattern.ALL_TIME)
            .setSidePattern(SidePattern.DEFAULT)
            .setGravity(android.view.Gravity.START or android.view.Gravity.TOP, savedX, savedY)
            // 关闭 EasyFloat 自带拖拽：它只在位移 ≥9px 时拦截，静止长按（400ms）会先于拦截触发
            // 系统 long-click，导致"想拖动、手先停了一下"就误唤起语音。手势统一走 handleFloatTouch
            .setDragEnable(false)
            .hasEditText(false)
            .setTag(FLOAT_TAG)
            .registerCallbacks(object : OnFloatCallbacks {

                override fun createdResult(
                    isCreated: Boolean,
                    msg: String?,
                    view: View?
                ) {
                    // 缓存圆形原始宽度（必须在任何 setFloatRootWidth 之前）
                    view?.findViewById<View>(R.id.floatRoot)?.let { root ->
                        if (circleWidthPx <= 0) {
                            circleWidthPx = root.layoutParams?.width ?: -1
                        }
                    }
                    // 点按 / 长按语音 / 拖动全部手动判定（见 handleFloatTouch）
                    view?.setOnTouchListener { v, event -> handleFloatTouch(v, event) }
                    // 初始化状态
                    updateStateView(view, currentState)
                    // 布局完成后检测位置，防止圆球卡在屏幕外
                    view?.post {
                        ensureFloatInBounds(view)
                    }
                }

                override fun dismiss() {
                    isShowing = false
                }

                override fun drag(view: View, event: MotionEvent) {
                }

                override fun dragEnd(view: View) {
                    // 拖动结束，修正位置并保存
                    ensureFloatInBounds(view)
                }

                override fun hide(view: View) {
                    isShowing = false
                }

                override fun show(view: View) {
                    isShowing = true
                }

                override fun touchEvent(view: View, event: MotionEvent) {

                }
            })
            .show()
    }

    /**
     * 隐藏悬浮窗
     */
    fun hide() {
        if (isShowing) {
            EasyFloat.dismiss(FLOAT_TAG)
            isShowing = false
        }
    }

    /**
     * 判断是否显示中
     */
    fun isShowing(): Boolean = isShowing

    /**
     * 切换到等待任务状态（默认）
     */
    fun setIdleState() {
        ThreadUtils.runOnUiThread {
            setState(State.IDLE)
        }
    }

    /**
     * 显示任务通知：悬浮窗展开为胶囊，显示任务内容，3 秒后自动收回进入 RUNNING 状态。
     * @param taskText 任务文本（会截断显示）
     * @param channel 消息来源渠道
     */
    fun showTaskNotify(taskText: String, channel: Channel) {
        ThreadUtils.runOnUiThread {
            pendingTaskText = taskText
            currentChannel = channel
            cancelNotifyCollapse()
            setState(State.TASK_NOTIFY)
            // 3 秒后自动收回为 RUNNING（直接 setState，绕过 TASK_NOTIFY 守卫）
            notifyCollapseRunnable = Runnable {
                setState(State.RUNNING)
            }
            mainHandler.postDelayed(notifyCollapseRunnable!!, TASK_NOTIFY_DURATION_MS)
        }
    }

    private fun cancelNotifyCollapse() {
        notifyCollapseRunnable?.let {
            mainHandler.removeCallbacks(it)
            notifyCollapseRunnable = null
        }
    }

    /**
     * 切换到语音按住聆听中状态（胶囊展开；按住期间持续录音，松手结束识别）
     */
    fun setListeningState() {
        ThreadUtils.runOnUiThread {
            setState(State.LISTENING)
        }
    }

    /**
     * 更新聆听中的流式部分结果文本（仅 LISTENING 状态生效）
     */
    fun updateListeningPartial(text: String) {
        ThreadUtils.runOnUiThread {
            if (currentState != State.LISTENING) return@runOnUiThread
            val view = EasyFloat.getFloatView(FLOAT_TAG)
            view?.findViewById<TextView>(R.id.tvListeningPartial)?.text = text
        }
    }

    // —— 手势状态（仅主线程访问）：点按 / 拖动 / 静止长按录音、松手识别 ——

    /** 位移超过触摸阈值视为拖动意图（按下后移动则取消待触发的长按） */
    private val dragSlopPx: Int by lazy {
        appRef?.let { ViewConfiguration.get(it).scaledTouchSlop } ?: 24
    }

    /** 长按（语音）已触发后位移超过该阈值视为放弃语音转拖动；2 倍 slop 容忍按住说话时的手部抖动 */
    private val voiceAbortSlopPx: Int get() = dragSlopPx * 2

    private var gestureActive = false
    private var gesturePointerId = -1
    private var gestureDownRawX = 0f
    private var gestureDownRawY = 0f
    private var gestureWinX = 0 // 按下时悬浮窗窗口坐标（WindowManager.LayoutParams 坐标系）
    private var gestureWinY = 0
    private var gestureDragged = false
    private var gestureVoiceFired = false // 静止长按已触发（语音已唤起）
    private var gestureVoiceAborted = false // 长按后位移过大：语音已放弃并转为拖动
    private var gestureLongPress: Runnable? = null
    private val locationTmp = IntArray(2)

    /** 悬浮窗窗口的 WindowManager.LayoutParams（rootView 即窗口根布局） */
    private fun floatWindowLp(view: View): android.view.WindowManager.LayoutParams? =
        view.rootView?.layoutParams as? android.view.WindowManager.LayoutParams

    /**
     * 悬浮球统一手势入口（消费全部事件，框架点按/长按不再生效）。
     * 按下静止满长按时长 → 唤起语音，按住期间录音，松手结束识别；
     * 按下后位移超阈值 → 视为拖动：未触发长按则直接拖动，已触发长按则先放弃语音再拖动；
     * 无位移且未长按的点按 → [onFloatClick]。
     */
    private fun handleFloatTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onFloatTouchDown(view, event)
            MotionEvent.ACTION_MOVE -> {
                if (gestureActive) onFloatTouchMove(view, event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下：放弃当前手势（不点按、不拖动、不识别）
                if (gestureActive) cancelHoldingVoice()
                resetGesture()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (gestureActive && event.getPointerId(event.actionIndex) == gesturePointerId) {
                    finishFloatGesture(view)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                // 事件流被系统中断（如下拉通知栏）：正在录的语音直接放弃，不识别
                if (gestureActive) {
                    cancelHoldingVoice()
                    resetGesture()
                }
            }
        }
        return true
    }

    private fun onFloatTouchDown(view: View, event: MotionEvent) {
        resetGesture() // 兜底：权限弹窗等场景可能丢失 UP，新按下先清理残留手势
        gestureActive = true
        gesturePointerId = event.getPointerId(0)
        gestureDownRawX = event.getRawX(0)
        gestureDownRawY = event.getRawY(0)
        // 拖动锚点取窗口 LayoutParams 坐标系（y 不含状态栏偏移），与 EasyFloat.updateFloat 保持一致
        val lp = floatWindowLp(view)
        if (lp != null) {
            gestureWinX = lp.x
            gestureWinY = lp.y
        } else {
            view.getLocationOnScreen(locationTmp)
            gestureWinX = locationTmp[0]
            gestureWinY = locationTmp[1]
        }
        // 静止长按 → 唤起语音（按住期间持续录音，松手识别）
        gestureLongPress = Runnable {
            gestureVoiceFired = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            XLog.d(TAG, "Long press fired, hold to speak")
            onFloatLongClick()
        }.also {
            mainHandler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong())
        }
    }

    private fun onFloatTouchMove(view: View, event: MotionEvent) {
        val idx = event.findPointerIndex(gesturePointerId)
        if (idx < 0) return
        val dx = event.getRawX(idx) - gestureDownRawX
        val dy = event.getRawY(idx) - gestureDownRawY
        // 未开始拖动前需越过阈值才判定为拖动；开始后窗口始终跟随手指
        val threshold = if (gestureVoiceFired) voiceAbortSlopPx else dragSlopPx
        if (!gestureDragged && dx * dx + dy * dy < threshold * threshold) return
        if (!gestureDragged) {
            gestureDragged = true
            if (gestureVoiceFired) {
                // 长按已唤起语音但位移过大：放弃本次录音，转为拖动
                cancelHoldingVoice()
                XLog.d(TAG, "Drag started (voice dropped)")
            } else {
                // 拖动意图：取消未触发的长按，绝不唤起语音
                cancelGestureLongPress()
                XLog.d(TAG, "Drag started")
            }
        }
        dragFloatTo(view, (gestureWinX + dx).toInt(), (gestureWinY + dy).toInt())
    }

    /** 拖动窗口到指定坐标（窗口 LayoutParams 坐标系，限制在屏幕可用区域内） */
    private fun dragFloatTo(view: View, x: Int, y: Int) {
        val dm = Resources.getSystem().displayMetrics
        val maxX = (dm.widthPixels - view.width).coerceAtLeast(0)
        val maxY = (dm.heightPixels - view.height - getNavigationBarHeight() - 50).coerceAtLeast(0)
        EasyFloat.updateFloat(FLOAT_TAG, x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }

    /** 主手指抬起（松手）：长按语音 → 结束并识别；拖动 → 修正并保存位置；否则视为点按 */
    private fun finishFloatGesture(view: View) {
        cancelGestureLongPress()
        val fired = gestureVoiceFired
        val aborted = gestureVoiceAborted
        val dragged = gestureDragged
        resetGesture()
        when {
            fired && !aborted -> {
                XLog.d(TAG, "Long press released, recognize")
                onFloatVoiceRelease()
            }
            dragged -> ensureFloatInBounds(view)
            else -> {
                XLog.d(TAG, "Float tapped")
                onFloatClick()
            }
        }
    }

    /** 按住中放弃语音（转拖动 / 第二指 / 系统中断），由上层丢弃录音，不识别不提示 */
    private fun cancelHoldingVoice() {
        if (gestureVoiceFired && !gestureVoiceAborted) {
            gestureVoiceAborted = true
            XLog.d(TAG, "Voice hold cancelled")
            onFloatVoiceCancel()
        }
    }

    private fun cancelGestureLongPress() {
        gestureLongPress?.let {
            mainHandler.removeCallbacks(it)
            gestureLongPress = null
        }
    }

    private fun resetGesture() {
        cancelGestureLongPress()
        gestureActive = false
        gesturePointerId = -1
        gestureVoiceFired = false
        gestureVoiceAborted = false
        gestureDragged = false
    }

    /**
     * 切换到任务执行中状态
     * @param round 当前轮数
     * @param channel 消息来源渠道
     */
    fun setRunningState(round: Int, channel: Channel) {
        ThreadUtils.runOnUiThread {
            currentRound = round
            currentChannel = channel
            // 如果正在显示任务通知胶囊，只更新数据，不切换 UI（等定时器到期自动切）
            if (currentState == State.TASK_NOTIFY) {
                return@runOnUiThread
            }
            setState(State.RUNNING)
        }
    }

    /**
     * 切换到任务完成状态（5秒后自动回到 IDLE）
     */
    fun setSuccessState() {
        ThreadUtils.runOnUiThread {
            setState(State.SUCCESS)
            scheduleAutoReset()
        }
    }

    /**
     * 切换到任务失败状态（5秒后自动回到 IDLE）
     */
    fun setErrorState() {
        ThreadUtils.runOnUiThread {
            setState(State.ERROR)
            scheduleAutoReset()
        }

    }

    /**
     * 设置状态
     */
    private fun setState(state: State) {
        currentState = state
        val view = EasyFloat.getFloatView(FLOAT_TAG)
        view?.let { updateStateView(it, state) }
    }

    /**
     * 更新视图状态
     */
    private fun updateStateView(view: View?, state: State) {
        if (view == null) return

        val cardIdle = view.findViewById<View>(R.id.cardIdle)
        val cardTaskNotify = view.findViewById<View>(R.id.cardTaskNotify)
        val cardListening = view.findViewById<View>(R.id.cardListening)
        val cardRunning = view.findViewById<View>(R.id.cardRunning)
        val cardSuccess = view.findViewById<View>(R.id.cardSuccess)
        val cardError = view.findViewById<View>(R.id.cardError)

        // 隐藏所有状态
        cardIdle?.visibility = View.GONE
        cardTaskNotify?.visibility = View.GONE
        cardListening?.visibility = View.GONE
        cardRunning?.visibility = View.GONE
        cardSuccess?.visibility = View.GONE
        cardError?.visibility = View.GONE

        // 取消之前的自动重置
        cancelAutoReset()

        // 显示对应状态
        when (state) {
            State.IDLE -> {
                cardIdle?.visibility = View.VISIBLE
                setFloatRootWidth(view, getCircleWidth(view))
            }
            State.TASK_NOTIFY -> {
                cardTaskNotify?.visibility = View.VISIBLE
                val tvNotify = view.findViewById<TextView>(R.id.tvTaskNotify)
                val app = appRef ?: return
                val displayText = if (pendingTaskText.length > 40) {
                    pendingTaskText.substring(0, 40) + "…"
                } else {
                    pendingTaskText
                }
                tvNotify?.text = app.getString(R.string.floating_task_received, displayText)
                val ivLogo = view.findViewById<ImageView>(R.id.ivNotifyChannelLogo)
                ivLogo?.setImageResource(getChannelIcon(currentChannel))
                // 展开为 wrap_content
                setFloatRootWidth(view, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            State.LISTENING -> {
                cardListening?.visibility = View.VISIBLE
                val tvPartial = view.findViewById<TextView>(R.id.tvListeningPartial)
                tvPartial?.text = appRef?.getString(R.string.voice_listening_hint) ?: ""
                setFloatRootWidth(view, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            State.RUNNING -> {
                cancelNotifyCollapse()
                // 收回为固定圆形
                setFloatRootWidth(view, getCircleWidth(view))
                cardRunning?.visibility = View.VISIBLE
                // 更新轮数显示
                val tvRound = view.findViewById<TextView>(R.id.tvRound)
                tvRound?.text = currentRound.toString()
                // 更新渠道 Logo
                val ivChannelLogo = view.findViewById<ImageView>(R.id.ivChannelLogo)
                ivChannelLogo?.setImageResource(getChannelIcon(currentChannel))
            }
            State.SUCCESS -> {
                cancelNotifyCollapse()
                cardSuccess?.visibility = View.VISIBLE
                setFloatRootWidth(view, getCircleWidth(view))
            }
            State.ERROR -> {
                cancelNotifyCollapse()
                cardError?.visibility = View.VISIBLE
                setFloatRootWidth(view, getCircleWidth(view))
            }
        }
    }

    /**
     * 获取渠道对应的图标
     */
    @DrawableRes
    private fun getChannelIcon(channel: Channel?): Int {
        return when (channel) {
            Channel.DINGTALK -> R.drawable.ic_channel_dingtalk
            Channel.FEISHU -> R.drawable.ic_channel_feishu
            Channel.QQ -> R.drawable.ic_channel_qq
            Channel.DISCORD -> R.drawable.ic_channel_discord
            Channel.TELEGRAM -> R.drawable.ic_channel_telegram
            Channel.WECHAT -> R.drawable.ic_channel_wechat
            Channel.IN_APP -> R.drawable.ic_launcher
            else -> R.drawable.ic_launcher
        }
    }

    /**
     * 5秒后自动重置到 IDLE 状态
     */
    private fun scheduleAutoReset() {
        cancelAutoReset()
        autoResetRunnable = Runnable {
            setIdleState()
        }
        mainHandler.postDelayed(autoResetRunnable!!, AUTO_RESET_DELAY_MS)
    }

    /**
     * 取消自动重置
     */
    private fun cancelAutoReset() {
        autoResetRunnable?.let {
            mainHandler.removeCallbacks(it)
            autoResetRunnable = null
        }
    }

    /**
     * 确保悬浮窗在屏幕可见范围内，超出则修正
     */
    private fun ensureFloatInBounds(view: View) {
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        // 获取导航栏高度，确保圆球不会被导航栏遮挡
        val navBarHeight = getNavigationBarHeight()

        // 方式1：尝试从 view 层级找到 WindowManager.LayoutParams
        var wmParams: WindowManager.LayoutParams? = null
        var wmView: View? = view
        while (wmView != null) {
            val lp = wmView.layoutParams
            if (lp is WindowManager.LayoutParams) {
                wmParams = lp
                break
            }
            wmView = wmView.parent as? View
        }

        if (wmParams != null) {
            val floatHeight = (wmView ?: view).height
            val floatWidth = (wmView ?: view).width
            val maxX = (screenWidth - floatWidth).coerceAtLeast(0)
            // 减去导航栏高度和额外安全边距
            val maxY = (screenHeight - floatHeight - navBarHeight - 50).coerceAtLeast(0)
            val clampedX = wmParams.x.coerceIn(0, maxX)
            val clampedY = wmParams.y.coerceIn(0, maxY)
            if (clampedX != wmParams.x || clampedY != wmParams.y) {
                EasyFloat.updateFloat(FLOAT_TAG, clampedX, clampedY)
            }
            savePosition(clampedX, clampedY)
            return
        }

        // 兜底：用 getLocationOnScreen 检测，updateFloat 修正
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val viewBottom = location[1] + view.height
        if (viewBottom > screenHeight - navBarHeight || location[1] < 0) {
            val safeY = screenHeight / 3
            EasyFloat.updateFloat(FLOAT_TAG, location[0].coerceIn(0, screenWidth), safeY)
            savePosition(location[0].coerceIn(0, screenWidth), safeY)
        } else {
            savePosition(location[0], location[1])
        }
    }

    private fun getNavigationBarHeight(): Int = BarUtils.getNavBarHeight()

    /** 圆形状态的原始宽度（首次从 layout 读取并缓存） */
    private var circleWidthPx: Int = -1

    /** 动态修改悬浮窗根布局宽度（展开胶囊 / 收回圆形） */
    private fun setFloatRootWidth(view: View, widthPx: Int) {
        val root = view.findViewById<View>(R.id.floatRoot) ?: return
        val lp = root.layoutParams
        if (lp != null && lp.width != widthPx) {
            lp.width = widthPx
            root.layoutParams = lp
        }
    }

    /** 获取圆形状态的宽度（createdResult 时缓存，确保与 XML 定义一致） */
    private fun getCircleWidth(@Suppress("UNUSED_PARAMETER") view: View): Int {
        return if (circleWidthPx > 0) circleWidthPx else WindowManager.LayoutParams.WRAP_CONTENT
    }


    /**
     * 保存位置
     */
    private fun savePosition(x: Int, y: Int) {
        KVUtils.putInt(KEY_FLOAT_X, x)
        KVUtils.putInt(KEY_FLOAT_Y, y)
    }

    /**
     * 获取保存的 X 坐标
     */
    private fun getSavedX(): Int? {
        val x = KVUtils.getInt(KEY_FLOAT_X, -1)
        return if (x == -1) null else x
    }

    /**
     * 获取保存的 Y 坐标
     */
    private fun getSavedY(): Int? {
        val y = KVUtils.getInt(KEY_FLOAT_Y, -1)
        return if (y == -1) null else y
    }

    /**
     * 点按回调，可以在外部设置
     */
    var onFloatClick: () -> Unit = {}

    /**
     * 长按（静止按住满长按时长）触发语音输入回调；按住期间持续录音，松手时 onFloatVoiceRelease
     */
    var onFloatLongClick: () -> Unit = {}

    /**
     * 长按后松手回调：结束录音并识别
     */
    var onFloatVoiceRelease: () -> Unit = {}

    /**
     * 长按中放弃回调（按住时转为拖动 / 第二指落下 / 事件流被系统中断）：丢弃本次录音，不识别
     */
    var onFloatVoiceCancel: () -> Unit = {}
}

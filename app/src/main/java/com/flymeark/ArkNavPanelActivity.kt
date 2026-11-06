package com.flymeark

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView

/**
 * v3.15.61：点击**导航胶囊**（高德）后从状态栏下方展开的导航大卡。
 *
 * 与 [ArkMessagePanelActivity] 完全同构（窗口 / 动画 / 可见性广播三件事一模一样）：
 *  - 窗口：顶部悬浮、满屏透明（点空白收起）、不压暗背景；
 *  - 动画：[ArkMorphAnim]「胶囊长大成卡片」，收起时缩回胶囊位置；
 *  - 联动：onResume / 收起时广播 [ArkConstants.ACTION_PANEL_STATE]，
 *    SystemUI 侧同一套逻辑负责淡出 / 恢复胶囊。
 *
 * ## 数据从哪来（三进程隔离）
 *
 * 载荷生产者 [ArkNavLiveInjector] 在**高德进程**，读取者 [ArkLiveExpandHook] 在
 * **SystemUI 进程**，本面板在**模块自身进程**。三者没有共享内存，唯一通道是
 * 影子通知的 extras：高德进程写 `ArkConstants.NAV_KEY_*` → SystemUI 原样搬进 Intent
 * → 这里读。所以本类只读 Intent，**完全不碰通知**（本进程也没那个权限）。
 *
 * ## 三条产品取舍（设计稿没有、但必须这么做的）
 *
 * 1. 三栏标签是「耗时 / 全程 / 到达」而不是「剩余时间 / 剩余距离 / 预计到达」——
 *    高德不给实时剩余值，只有起点播报里的总量（见 `docs/live-nav-research.md` §7）；
 * 2. 进度条是**时间口径**（已用时间 / 总耗时），不是距离口径；
 * 3. 右下角按钮是「回到导航」而不是「结束导航」——
 *    外部 App 无法结束别人的导航，硬做一个点了没反应的「结束导航」更糟。
 *
 * ## 隐私
 *
 * **不设 showWhenLocked**：锁屏上点胶囊不弹卡（与消息卡一致）。
 */
class ArkNavPanelActivity : Activity() {

    private lateinit var root: View
    private lateinit var card: View
    private lateinit var content: View

    private lateinit var liveView: TextView
    private lateinit var destView: TextView
    private lateinit var collapseView: ImageView
    private lateinit var dotRing: View
    private lateinit var dotCore: View
    private lateinit var sepView: View

    private lateinit var turnView: ArkNavTurnBlockView
    private lateinit var instrView: TextView
    private lateinit var subView: TextView

    private lateinit var progressView: ArkNavProgressView

    private lateinit var timeValueView: TextView
    private lateinit var distValueView: TextView
    private lateinit var etaValueView: TextView
    private lateinit var timeLabelView: TextView
    private lateinit var distLabelView: TextView
    private lateinit var etaLabelView: TextView

    private lateinit var divider1: View
    private lateinit var divider2: View
    private lateinit var footDivider: View

    private lateinit var btnView: TextView
    private lateinit var sourceView: TextView

    /** 自绘底板（原因见 [ArkChipDrawable] 类注释：Flyme 深色模式会反色形状 drawable） */
    private lateinit var btnChip: ArkChipDrawable
    private lateinit var dotRingChip: ArkChipDrawable
    private lateinit var dotCoreChip: ArkChipDrawable
    private lateinit var sepChip: ArkChipDrawable
    private lateinit var divider1Chip: ArkChipDrawable
    private lateinit var divider2Chip: ArkChipDrawable
    private lateinit var footDividerChip: ArkChipDrawable

    private lateinit var morph: ArkMorphAnim
    private lateinit var glassParams: GlassParams

    private val handler = Handler(Looper.getMainLooper())

    private var nightMode = false
    private var closing = false

    // ---- 载荷（从 Intent 读，见类注释）
    private var instrText = ""
    private var subText = ""
    private var destText = ""
    private var distValue = ""
    private var distUnit = ""
    private var timeValue = ""
    private var timeUnit = ""
    private var etaValue = ""
    private var turnCode = ArkConstants.NAV_TURN_STRAIGHT
    private var startedAt = 0L
    private var totalMs = 0L
    private var appPkg = ArkConstants.NAV_LIVE_PKG

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与音乐 / 消息面板同一套窗口参数
        window.setGravity(Gravity.TOP)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.attributes = window.attributes.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }

        setContentView(R.layout.activity_nav_panel)
        bindViews()
        readIntent(intent)
        bindActions()

        morph = ArkMorphAnim(root, card, content, glassParams)
        card.alpha = 0f
        root.post {
            morph.prepare(capsuleRect())
            root.post {
                card.alpha = 1f
                morph.expand()
            }
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.nav_root)
        card = findViewById(R.id.nav_card)
        content = findViewById(R.id.nav_content)

        liveView = findViewById(R.id.nav_live)
        destView = findViewById(R.id.nav_dest)
        collapseView = findViewById(R.id.nav_collapse)
        dotRing = findViewById(R.id.nav_dot_ring)
        dotCore = findViewById(R.id.nav_dot_core)
        sepView = findViewById(R.id.nav_sep)

        turnView = findViewById(R.id.nav_turn)
        instrView = findViewById(R.id.nav_instr)
        subView = findViewById(R.id.nav_sub)

        progressView = findViewById(R.id.nav_progress)

        timeValueView = findViewById(R.id.nav_time_value)
        distValueView = findViewById(R.id.nav_dist_value)
        etaValueView = findViewById(R.id.nav_eta_value)
        timeLabelView = findViewById(R.id.nav_time_label)
        distLabelView = findViewById(R.id.nav_dist_label)
        etaLabelView = findViewById(R.id.nav_eta_label)

        divider1 = findViewById(R.id.nav_divider_1)
        divider2 = findViewById(R.id.nav_divider_2)
        footDivider = findViewById(R.id.nav_foot_divider)

        btnView = findViewById(R.id.nav_btn)
        sourceView = findViewById(R.id.nav_source)

        // 圆点 / 分隔点 / 分割线 / 按钮底板全部走自绘（与消息卡 CTA 同一套理由）
        dotRingChip = ArkChipDrawable(0x292F6BFF, 0f).apply { pill = true }
        dotCoreChip = ArkChipDrawable(ArkTokens.BRAND_LIGHT, 0f).apply { pill = true }
        sepChip = ArkChipDrawable(0xFFC8CDD6.toInt(), 0f).apply { pill = true }
        divider1Chip = ArkChipDrawable(ArkTokens.DIVIDER_LIGHT, 0f)
        divider2Chip = ArkChipDrawable(ArkTokens.DIVIDER_LIGHT, 0f)
        footDividerChip = ArkChipDrawable(ArkTokens.DIVIDER_LIGHT, 0f)
        btnChip = ArkChipDrawable(ArkTokens.SUBTLE_CHIP_LIGHT, 0f).apply { pill = true }

        dotRing.background = dotRingChip
        dotCore.background = dotCoreChip
        sepView.background = sepChip
        divider1.background = divider1Chip
        divider2.background = divider2Chip
        footDivider.background = footDividerChip
        btnView.background = btnChip

        nightMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        glassParams = ArkLiquidGlass.resolve(this, nightMode)
        applyPalette()
    }

    private fun bindActions() {
        root.setOnClickListener { dismiss() }
        // 设计稿 §六「点击展开卡 → 收起为迷你条」：卡片本体也要收起，
        // 不能吞掉点击（那样只有 20dp 的收起箭头可点，误触率太高）。
        // card 是 clickable 的，点卡片不会穿透到 root 触发两次 dismiss。
        card.setOnClickListener { dismiss() }
        collapseView.setOnClickListener { dismiss() }
        btnView.setOnClickListener { openNavigation() }
    }

    /**
     * 读载荷。所有字段都可能是空串（高德刚进导航、播报还没来），
     * 空串一律走「占位 / 隐藏」分支，绝不留半张空白卡。
     */
    private fun readIntent(i: Intent?) {
        if (i == null) return
        instrText = i.getStringExtra(ArkConstants.NAV_KEY_INSTR).orEmpty().trim()
        subText = i.getStringExtra(ArkConstants.NAV_KEY_SUB).orEmpty().trim()
        destText = i.getStringExtra(ArkConstants.NAV_KEY_DEST).orEmpty().trim()
        distValue = i.getStringExtra(ArkConstants.NAV_KEY_DIST).orEmpty().trim()
        distUnit = i.getStringExtra(ArkConstants.NAV_KEY_DIST_UNIT).orEmpty().trim()
        timeValue = i.getStringExtra(ArkConstants.NAV_KEY_TIME).orEmpty().trim()
        timeUnit = i.getStringExtra(ArkConstants.NAV_KEY_TIME_UNIT).orEmpty().trim()
        etaValue = i.getStringExtra(ArkConstants.NAV_KEY_ETA).orEmpty().trim()
        startedAt = i.getLongExtra(ArkConstants.NAV_KEY_STARTED, 0L)
        totalMs = i.getLongExtra(ArkConstants.NAV_KEY_TOTAL_MS, 0L)
        turnCode = i.getIntExtra(ArkConstants.NAV_KEY_TURN, ArkConstants.NAV_TURN_STRAIGHT)
        appPkg = i.getStringExtra(ArkConstants.EXTRA_PANEL_APP_PKG)
            ?.takeIf { it.isNotBlank() } ?: ArkConstants.NAV_LIVE_PKG
        if (i.hasExtra(ArkConstants.EXTRA_PANEL_NIGHT)) {
            nightMode = i.getBooleanExtra(ArkConstants.EXTRA_PANEL_NIGHT, nightMode)
        }

        renderContent()
        applyPalette()
        updateProgress()
    }

    /**
     * 把载荷写进控件（与配色无关的部分）。
     *
     * 配色相关的（数值单位色）在 [applyPalette] 里重新渲染一次 —— 因为单位色
     * 是写进 Spannable 里的，深浅切换必须重拼。
     */
    private fun renderContent() {
        val instr = instrText.ifBlank { getString(R.string.ark_nav_default_instr) }
        instrView.text = instr
        turnView.setTurn(if (instrText.isBlank()) ArkConstants.NAV_TURN_STRAIGHT else turnCode)

        subView.text = subText
        subView.visibility = if (subText.isBlank()) View.GONE else View.VISIBLE

        destView.text = destText.ifBlank { ArkConstants.NAV_LABEL }
        sourceView.text = ArkConstants.NAV_LABEL

        renderValues()
    }

    /** 三栏数值：数值 + 小号单位（设计稿 `.val em`） */
    private fun renderValues() {
        val subtle = if (glassParams.light) {
            ArkTokens.SUBTLE_TEXT_LIGHT
        } else {
            ArkTokens.SUBTLE_TEXT_DARK
        }
        timeValueView.text = valueSpan(timeValue, timeUnit, subtle)
        distValueView.text = valueSpan(distValue, distUnit, subtle)
        etaValueView.text = valueSpan(etaValue, "", subtle)

        timeLabelView.text = getString(R.string.ark_nav_time_label)
        distLabelView.text = getString(R.string.ark_nav_dist_label)
        etaLabelView.text = getString(R.string.ark_nav_eta_label)
    }

    /**
     * 「16sp 数值 + 11sp 弱化单位」拼成一条（设计稿 `.val` / `.val em`）。
     *
     * 用 Spannable 而不是两个 TextView：设计稿要求两者基线对齐，两个 TextView
     * 要靠 baseline 对齐布局，动画期间容易抖；一条 Spannable 天然共享基线。
     * 单位前插一个窄空格（U+2009）近似设计的 `margin-left:3px`。
     */
    @Suppress("DEPRECATION") // scaledDensity 在 API 34 起标记废弃，但这里必须尊重用户字体缩放
    private fun valueSpan(value: String, unit: String, unitColor: Int): CharSequence {
        if (value.isBlank()) return getString(R.string.ark_nav_placeholder)
        val sb = SpannableStringBuilder(value)
        if (unit.isNotBlank()) {
            sb.append('\u2009')
            val start = sb.length
            sb.append(unit)
            // 按 scaledDensity 折算成 px，等价于 11sp（AbsoluteSizeSpan 的 dip 模式只认 dp，
            // 会漏掉用户的字体缩放）
            val unitPx = (ArkTokens.TYPE_NAV_UNIT_SP * resources.displayMetrics.scaledDensity).toInt()
            sb.setSpan(
                AbsoluteSizeSpan(unitPx),
                start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.setSpan(
                ForegroundColorSpan(unitColor),
                start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sb
    }

    /**
     * 深浅配色。全部按 [GlassParams.light] 运行时下发，**不吃 values-night** ——
     * 与消息卡同一套规则（系统夜间 + 亮壁纸时会错配成浅底浅字）。
     */
    private fun applyPalette() {
        if (!this::glassParams.isInitialized) {
            glassParams = ArkLiquidGlass.resolve(this, nightMode)
        }
        val p = glassParams
        if (this::morph.isInitialized) morph.setGlass(p)

        val light = p.light
        val primary = if (light) ArkTokens.TEXT_PRIMARY_LIGHT else ArkTokens.TEXT_PRIMARY_DARK
        val subtle = if (light) ArkTokens.SUBTLE_TEXT_LIGHT else ArkTokens.SUBTLE_TEXT_DARK
        val ink3 = if (light) ArkTokens.INK3_LIGHT else ArkTokens.INK3_DARK
        val brand = if (light) ArkTokens.BRAND_LIGHT else ArkTokens.BRAND_DARK
        val divider = if (light) ArkTokens.DIVIDER_LIGHT else ArkTokens.DIVIDER_DARK
        val subtleChip = if (light) ArkTokens.SUBTLE_CHIP_LIGHT else ArkTokens.SUBTLE_CHIP_DARK
        val sepColor = if (light) 0xFFC8CDD6.toInt() else 0x40FFFFFF
        val chevronColor = if (light) 0xFF8A9099.toInt() else ArkTokens.INK3_DARK

        liveView.setTextColor(ArkLiquidGlass.ink(brand, p))
        destView.setTextColor(ArkLiquidGlass.ink(subtle, p))
        instrView.setTextColor(ArkLiquidGlass.ink(primary, p))
        subView.setTextColor(ArkLiquidGlass.ink(subtle, p))
        timeValueView.setTextColor(ArkLiquidGlass.ink(primary, p))
        distValueView.setTextColor(ArkLiquidGlass.ink(primary, p))
        etaValueView.setTextColor(ArkLiquidGlass.ink(primary, p))
        timeLabelView.setTextColor(ArkLiquidGlass.ink(ink3, p))
        distLabelView.setTextColor(ArkLiquidGlass.ink(ink3, p))
        etaLabelView.setTextColor(ArkLiquidGlass.ink(ink3, p))
        btnView.setTextColor(ArkLiquidGlass.ink(primary, p))
        sourceView.setTextColor(ArkLiquidGlass.ink(ink3, p))

        // 圆点：外环 = 品牌色 16%，内核 = 品牌色（设计稿 `.live i` + box-shadow 0 0 0 3px）
        dotRingChip.setFill(withAlpha(brand, 0.16f))
        dotCoreChip.setFill(brand)
        sepChip.setFill(sepColor)
        divider1Chip.setFill(divider)
        divider2Chip.setFill(divider)
        footDividerChip.setFill(divider)
        btnChip.setFill(subtleChip)

        collapseView.imageTintList = ColorStateList.valueOf(chevronColor)
        progressView.setPalette(
            accent = brand,
            track = if (light) ArkTokens.TRACK_NEUTRAL_LIGHT else ArkTokens.TRACK_NEUTRAL_DARK,
            ringOnAccent = 0xFFFFFFFF.toInt()
        )

        // 单位色写进了 Spannable，必须重拼（见 renderValues 注释）
        renderValues()
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255f).toInt(),
        Color.red(color), Color.green(color), Color.blue(color)
    )

    // ------------------------------------------------------------------
    // 进度（时间口径）
    // ------------------------------------------------------------------

    private fun updateProgress() {
        if (startedAt <= 0L || totalMs <= 0L) {
            progressView.setProgress(0f, false)
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val f = if (elapsed <= 0L) 0f else elapsed.toFloat() / totalMs.toFloat()
        progressView.setProgress(f, true)
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    /**
     * 「回到导航」：拉起高德主界面。
     *
     * 不能做成「结束导航」——外部 App 无法结束别人的导航（高德没有暴露任何
     * 第三方可调用的停止入口），做一个点了没反应的按钮比换个文案更糟。
     */
    private fun openNavigation() {
        var launched = false
        try {
            packageManager.getLaunchIntentForPackage(appPkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
                launched = true
            }
        } catch (_: Throwable) {
        }
        if (!launched) {
            // 兜底：拿不到 Launcher Intent 时，再试着发原导航通知的 contentIntent。
            // 只在这里用，是因为 QQ 那类「PI 创建方未 opt-in Android 15 BAL」的拦截
            // 不会抛异常给调用方，成功了却什么都没拉起来 —— 不能当主路径。
            try {
                @Suppress("DEPRECATION")
                val pi = intent.getParcelableExtra<android.app.PendingIntent>(
                    ArkConstants.EXTRA_PANEL_CLICK_INTENT
                )
                pi?.send(applicationContext, 0, null)
            } catch (_: Throwable) {
            }
        }
        dismiss()
    }

    private fun dismiss() {
        if (closing) return
        closing = true
        // 卡片缩回胶囊的中途让状态栏胶囊淡入（与音乐 / 消息面板同一节奏）
        handler.postDelayed({ publishVisibility(false) }, 90L)
        morph.collapse { finish() }
    }

    private fun publishVisibility(visible: Boolean) {
        try {
            sendBroadcast(
                Intent(ArkConstants.ACTION_PANEL_STATE)
                    .setPackage(ArkConstants.SYSTEMUI_PKG)
                    .putExtra(ArkConstants.EXTRA_PANEL_VISIBLE, visible)
            )
        } catch (_: Throwable) {
        }
    }

    /** SystemUI 传来的胶囊屏幕矩形；缺失时交给 [ArkMorphAnim] 用兜底尺寸 */
    private fun capsuleRect(): Rect? {
        val i = intent ?: return null
        val w = i.getIntExtra(ArkConstants.EXTRA_CAPSULE_WIDTH, 0)
        val h = i.getIntExtra(ArkConstants.EXTRA_CAPSULE_HEIGHT, 0)
        if (w <= 0 || h <= 0) return null
        val l = i.getIntExtra(ArkConstants.EXTRA_CAPSULE_LEFT, 0)
        val t = i.getIntExtra(ArkConstants.EXTRA_CAPSULE_TOP, 0)
        return Rect(l, t, l + w, t + h)
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 面板已经开着时再点胶囊：CLEAR_TOP 会复用本实例，必须重读一次载荷
        if (intent == null) return
        setIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        publishVisibility(true)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        publishVisibility(false)
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        publishVisibility(false)
        handler.removeCallbacksAndMessages(null)
        if (this::morph.isInitialized) morph.stop()
        super.onDestroy()
    }

    @Deprecated("Activity 级别返回键处理")
    override fun onBackPressed() {
        dismiss()
    }

    private companion object {
        /** 进度的刷新节奏：1s 一次足够（分母是几十分钟量级），也不影响锁屏功耗 */
        const val TICK_MS = 1_000L
    }
}

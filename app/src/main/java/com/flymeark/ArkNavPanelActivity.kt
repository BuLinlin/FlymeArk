package com.flymeark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.ViewOutlineProvider
import android.os.Handler
import android.os.Looper
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
 * v3.15.65 起多了一条**实时**通道：面板打开期间 SystemUI 每看到影子更新一帧，
 * 就把同一份载荷广播过来（[ArkConstants.ACTION_NAV_PANEL_UPDATE]，`onResume` 注册 /
 * `onStop` 注销、`RECEIVER_NOT_EXPORTED`）。第一次仍走 Intent，广播只负责「之后」。
 *
 * ## 三条产品取舍（设计稿没有、但必须这么做的）
 *
 * 1. 三栏标签是「剩余耗时 / 总里程 / 实时到达」（v3.15.65 改）；
 *    v3.15.61 ~ v3.15.64 是「耗时 / 全程 / 到达」的全量口径 —— 用户实测反馈
 *    「开出去十分钟了还显示出发时的总耗时」，因为高德只播一次总量。
 *    现在「剩余」由高德进程按每次播报重算（`NAV_KEY_REMAIN_MS`），
 *    「到达」也随剩余实时刷新（播报明确给过就让它赢）；
 * 2. 进度条是**时间口径**的**锚点**进度（`(总耗时 - 剩余) / 总耗时`），
 *    两帧之间**冻结** —— 面板绝不自己按秒往前推（那样进度和「剩余耗时」必然互相打脸）；
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
    private lateinit var appIconView: ImageView
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

    // ---- 载荷（首次从 Intent 读，之后从广播读；见类注释）
    private var instrText = ""
    private var subText = ""
    private var destText = ""
    private var distValue = ""
    private var distUnit = ""
    private var timeValue = ""
    private var timeUnit = ""
    private var etaValue = ""
    private var turnCode = ArkConstants.NAV_TURN_STRAIGHT
    private var totalMs = 0L

    /** 剩余毫秒；`< 0` = 本趟还没有锚点 → 只画轨不画球（见 [ArkConstants.NAV_KEY_REMAIN_MS]） */
    private var remainMs = -1L
    private var appPkg = ArkConstants.NAV_LIVE_PKG

    /** 上一次渲染的载荷指纹（去抖用；见 [applyPayload]） */
    private var lastPayloadSig: String? = null

    /** 动态广播是否已注册（`onResume` / `onStop` 必须成对，靠它防重复注册 / 重复注销） */
    private var updateReceiverRegistered = false

    /**
     * 导航实时刷新（v3.15.65）。
     *
     * SystemUI 侧每看到导航影子更新一帧就广播一次（[ArkConstants.ACTION_NAV_PANEL_UPDATE]），
     * 这里只管把 extras 交给 [applyPayload]。
     *
     * 注意：这是**跨进程**广播（SystemUI → 本进程），但注册时用的是
     * `RECEIVER_NOT_EXPORTED` —— 只有系统（system uid）与本 App 自己能发进来，
     * 别的第三方 App 发不进来（也没有任何 App 有理由发这个 action）。
     */
    private val navUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action != ArkConstants.ACTION_NAV_PANEL_UPDATE) return
                val b = intent.extras ?: return
                applyPayload(b)
            } catch (_: Throwable) {
                // 面板只是展示层，任何异常都不许崩给用户看
            }
        }
    }

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
        appIconView = findViewById(R.id.nav_app_icon)
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

        // v3.15.62：右上角来源 logo（28dp / R9 = 边长 × 0.32，handoff-spec.md §四）。
        // 圆角用 outline 裁，不用 shape drawable —— Flyme 深色模式会反色形状 drawable。
        // logo 不设点击监听，点击自然穿透到 nav_card 触发收起。
        val logoRadius = 28f * 0.32f * resources.displayMetrics.density
        appIconView.clipToOutline = true
        appIconView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, logoRadius)
            }
        }
        try {
            appIconView.setImageDrawable(
                packageManager.getApplicationIcon(ArkConstants.NAV_LIVE_PKG)
            )
        } catch (_: Throwable) {
            appIconView.visibility = View.GONE
        }

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
        btnView.setOnClickListener { openNavigation() }
    }

    /**
     * 读载荷（**首次投递**，走 Intent）。
     *
     * 与实时广播共用 [applyPayload] —— 两处各解析一遍字段，必然会出现「点开有目的地、
     * 一推送就变成高德地图」这种静态错位。
     *
     * @param i 启动 / 复用本面板的 Intent；null 表示没有（什么都不做）
     */
    private fun readIntent(i: Intent?) {
        if (i == null) return
        try {
            // 启动专用字段（App 包名 / 夜间模式）只在这里认，广播里没有这两个 key
            appPkg = i.getStringExtra(ArkConstants.EXTRA_PANEL_APP_PKG)
                ?.takeIf { it.isNotBlank() } ?: ArkConstants.NAV_LIVE_PKG
            if (i.hasExtra(ArkConstants.EXTRA_PANEL_NIGHT)) {
                nightMode = i.getBooleanExtra(ArkConstants.EXTRA_PANEL_NIGHT, nightMode)
            }
            val b = i.extras ?: return
            applyPayload(b)
        } catch (_: Throwable) {
        }
    }

    /**
     * 把一份 `NAV_KEY_*` 载荷写进控件（Intent 与实时广播的唯一入口）。
     *
     * 三件事按顺序做：
     *  1. **先算指纹**（用户看得见的字段），与上一帧一致就直接返回 —— SystemUI 一次播报
     *     可能连着重绘好几帧，不去抖的话文字会「闪一下又回来」；
     *  2. 赋值 → 重绘文本；
     *  3. 重算进度（锚点口径，见 [updateProgress]）。
     *
     * 配色只在**首帧**与**夜间模式变化**时重刷：`applyPalette()` 里连 Spannable 都要重拼，
     * 每帧做一次纯属浪费（它自己也调 [renderValues]）。
     *
     * @param b 只含 `NAV_KEY_*` 的载荷
     */
    private fun applyPayload(b: Bundle) {
        val nextInstr = b.getString(ArkConstants.NAV_KEY_INSTR).orEmpty().trim()
        val nextSub = b.getString(ArkConstants.NAV_KEY_SUB).orEmpty().trim()
        val nextDest = b.getString(ArkConstants.NAV_KEY_DEST).orEmpty().trim()
        val nextDistValue = b.getString(ArkConstants.NAV_KEY_DIST).orEmpty().trim()
        val nextDistUnit = b.getString(ArkConstants.NAV_KEY_DIST_UNIT).orEmpty().trim()
        val nextTimeValue = b.getString(ArkConstants.NAV_KEY_TIME).orEmpty().trim()
        val nextTimeUnit = b.getString(ArkConstants.NAV_KEY_TIME_UNIT).orEmpty().trim()
        val nextEta = b.getString(ArkConstants.NAV_KEY_ETA).orEmpty().trim()
        val nextTotal = b.getLong(ArkConstants.NAV_KEY_TOTAL_MS, 0L)
        val nextRemain = b.getLong(ArkConstants.NAV_KEY_REMAIN_MS, -1L)
        val nextTurn = b.getInt(ArkConstants.NAV_KEY_TURN, ArkConstants.NAV_TURN_STRAIGHT)
        // 广播里没有启动参数；首次 Intent 已经设过，这里只在真的带了值时才改
        b.getString(ArkConstants.EXTRA_PANEL_APP_PKG)?.takeIf { it.isNotBlank() }
            ?.let { appPkg = it }
        val nightChanged = if (b.containsKey(ArkConstants.EXTRA_PANEL_NIGHT)) {
            val n = b.getBoolean(ArkConstants.EXTRA_PANEL_NIGHT, nightMode)
            val changed = n != nightMode
            nightMode = n
            changed
        } else {
            false
        }

        // ① 指纹去抖（不含 anchor_at：它是纯取证字段，每帧都会变，算进来等于没去抖）
        val sig = listOf(
            nextInstr, nextSub, nextDest, nextDistValue, nextDistUnit,
            nextTimeValue, nextTimeUnit, nextEta,
            nextTotal.toString(), nextRemain.toString(), nextTurn.toString()
        ).joinToString("\u0001")
        if (sig == lastPayloadSig && !nightChanged) return
        val firstRender = lastPayloadSig == null
        lastPayloadSig = sig

        // ② 赋值 + 重绘
        instrText = nextInstr
        subText = nextSub
        destText = nextDest
        distValue = nextDistValue
        distUnit = nextDistUnit
        timeValue = nextTimeValue
        timeUnit = nextTimeUnit
        etaValue = nextEta
        totalMs = nextTotal
        remainMs = nextRemain
        turnCode = nextTurn

        renderContent()
        if (firstRender || nightChanged) {
            applyPalette()      // 内部会再 renderValues 一次（单位色在 Spannable 里）
        }

        // ③ 锚点进度：只在**收到新载荷**时更新
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
        val timePair = formatRemainTime(timeValue, timeUnit)
        timeValueView.text = valueSpan(timePair.first, timePair.second, subtle)
        distValueView.text = valueSpan(distValue, distUnit, subtle)
        etaValueView.text = valueSpan(etaValue, "", subtle)

        timeLabelView.text = getString(R.string.ark_nav_time_label)
        distLabelView.text = getString(R.string.ark_nav_dist_label)
        etaLabelView.text = getString(R.string.ark_nav_eta_label)
    }

    /**
     * 「剩余耗时」的展示折叠（v3.15.65）。
     *
     * 载荷里的 `NAV_KEY_TIME` **恒为分钟**（单一语义，见 `ArkNavLiveInjector.remainTimePair`），
     * 长途就会出现「133 分钟」这种读不动的数字，所以在这里折成 `2 小时 13` + `分`。
     *
     * 只在**展示层**折：载荷口径不动，日志 / 取证 / 将来别的读法都还是分钟。
     * 单位与数值分开返回，是为了让 [valueSpan] 仍然能把单位排成 11sp 的弱化小字。
     *
     * @param value 载荷里的分钟数（可能为空 / 非数字）
     * @param unit  载荷里的单位（`分钟` / `小时` / 空）
     * @return 展示用（数值, 单位）；不需要折叠时原样返回
     */
    private fun formatRemainTime(value: String, unit: String): Pair<String, String> {
        if (unit != "分钟") return value to unit
        val minutes = value.toLongOrNull() ?: return value to unit
        if (minutes < 60L) return value to unit
        val h = minutes / 60L
        val m = minutes % 60L
        return if (m == 0L) h.toString() to "小时" else "$h 小时 $m" to "分"
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
    // 进度（时间口径 · 锚点式，v3.15.65）
    // ------------------------------------------------------------------

    /**
     * 画进度条：`(总耗时 - 剩余) / 总耗时`，**只认载荷里的剩余值**。
     *
     * ## 为什么不再「按秒自己往前走」
     *
     * v3.15.61 ~ v3.15.64 是 `(elapsedRealtime() - 出发时刻) / 总耗时`，每秒重算一次。
     * 用户实测的问题就在这里：**进度条自己在跑，而「耗时」栏是出发时的总量、一动不动**，
     * 两套口径互相打脸；而且高德只播一次总量，真到了「还剩 5 分钟」时进度条还停在一半。
     *
     * 现在剩余值由高德进程在**每次播报**时重算（`NAV_KEY_REMAIN_MS`），面板两帧之间
     * 完全冻结 —— 高德没有实时剩余数据时，宁可不动，也不要编一个假进度出来。
     *
     * `remainMs < 0` = 本趟还没有锚点（没拿到出发播报 / 没拿到总耗时）→
     * `setProgress(0f, false)`：只画轨和终点白球，不画球（画个 0% 的球会让人以为刚出发）。
     */
    private fun updateProgress() {
        if (totalMs <= 0L || remainMs < 0L) {
            progressView.setProgress(0f, false)
            return
        }
        // remainMs > totalMs（改道后剩余变长）时算出负数 → 钳到 0；
        // remainMs = 0（到达）→ 1.0。两端都必须钳，否则 ArkNavProgressView 会收到越界值。
        val fraction = (totalMs - remainMs).toFloat() / totalMs.toFloat()
        progressView.setProgress(fraction.coerceIn(0f, 1f), true)
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
        registerUpdateReceiver()
    }

    override fun onStop() {
        super.onStop()
        unregisterUpdateReceiver()
        publishVisibility(false)
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        unregisterUpdateReceiver()
        publishVisibility(false)
        handler.removeCallbacksAndMessages(null)
        if (this::morph.isInitialized) morph.stop()
        super.onDestroy()
    }

    /**
     * 注册导航实时刷新广播（v3.15.65）。
     *
     * 只在**可见**期间注册（`onResume` → `onStop`）：面板不在前台时没人需要刷新，
     * 而常驻注册会让本进程的广播队列一直命中，纯耗电。
     *
     * `RECEIVER_NOT_EXPORTED`（API 33+ 必须显式给导出标记，否则直接抛
     * `SecurityException`）：这条通道只有 SystemUI 与本 App 自己用，别的 App 发不进来。
     * API 32 及以下没有这个标记，走两参重载即可（动态注册默认只收「同 App + 系统」）。
     */
    private fun registerUpdateReceiver() {
        if (updateReceiverRegistered) return
        try {
            val filter = IntentFilter(ArkConstants.ACTION_NAV_PANEL_UPDATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(navUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(navUpdateReceiver, filter)
            }
            updateReceiverRegistered = true
        } catch (_: Throwable) {
            // 注册失败只是「没有实时刷新」，静态载荷照旧显示，不该让面板崩
            updateReceiverRegistered = false
        }
    }

    /** 注销实时刷新广播（与 [registerUpdateReceiver] 严格成对） */
    private fun unregisterUpdateReceiver() {
        if (!updateReceiverRegistered) return
        updateReceiverRegistered = false
        try {
            unregisterReceiver(navUpdateReceiver)
        } catch (_: Throwable) {
            // 已经因为进程/窗口销毁被系统注销过：吞掉，别在 onStop 里崩
        }
    }

    @Deprecated("Activity 级别返回键处理")
    override fun onBackPressed() {
        dismiss()
    }
}

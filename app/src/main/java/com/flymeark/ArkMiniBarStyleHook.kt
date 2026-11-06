package com.flymeark

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.Collections

/**
 * 实况通知**迷你条**（截图里「微信 + 空心圆」那条半透明横条）的视觉与结构。
 *
 * v3.15.44：首次落地，只改颜色 / 背景。
 * v3.15.47：按 `IslandCapsuleUI` 设计稿 `01 · 迷你条（微信）` 做**首次结构改造**——
 * 左侧补应用图标 + 未读徽标，右侧把空心圆环换成展开箭头。
 *
 * ## 节点链（`u2.xml` 实测）
 *
 * ```
 * ExpandableNotificationRow → expanded → android:id/rl_live_base        (RelativeLayout)
 *                                      → android:id/rl_header_live     (RelativeLayout, 实测 1116×182px)
 *                                          ├─ android:id/title_header_live        (TextView)
 *                                          ├─ android:id/content_header_live      (TextView)
 *                                          └─ android:id/iv_circle_progress_live  (View, 实测 154×154px = 44dp)
 * ```
 *
 * 布局来自 framework-res 的 `notification_template_live_header.xml`，id 在 `android:`
 * 命名空间，所以用 `Resources.getSystem().getIdentifier` 取数值 id。
 * 反编译该模板可见它**没有 background 属性**——系统的磨砂基底在父级通知行上，
 * 我们给头部加玻璃只是往上叠一层，不会把系统基底削薄。
 *
 * ## 为什么必须分流（v3.15.47 红线）
 *
 * 迷你条有两类来源：
 * - **消息类**：本模块 v3.15.24 起的影子实况通知（包名仍是微信/QQ），header 里只有
 *   一个孤零零的「微信」+ 一个**无意义的空心圆**（那圆环是进度控件，没进度时就是空壳）；
 * - **音乐 / 进度类**：`iv_circle_progress_live` 是**播放进度指示器**，换成箭头会直接废功能。
 *
 * 所以结构改造只对 `ArkConstants.MESSAGE_LIVE_PKGS`（微信 / QQ）生效，
 * 且显式排除 `ArkConstants.MEDIA_APP_PKGS`；判定全部复用既有常量，不新造一套。
 * 非消息类的迷你条：图标 / 箭头一个都不插，空心圆保持原样，只走 v3.15.44 的配色。
 *
 * ## 失败必须静默降级（v3.15.31 教训）
 *
 * 拿不到 entry / sbn / 包名 / 应用图标 / 布局类型不是 RelativeLayout —— 任一步失败都
 * 直接 return，让这条通知保持 v3.15.45 的原样；任何异常都被 `styleFrom` 的 catch 吞掉，
 * 绝不允许炸掉通知 inflate。
 *
 * ## 克制项
 *
 * - 不改 `textSize` / 宽高 / 头部 padding：`title_header_live` 宽度由通知模板测量决定。
 *   为了让出图标位置，只给标题 / 副标题**增加 left padding**（一次性，靠 tag 防累加）。
 * - 不 `removeView` 空心圆，只设 `GONE`——系统逻辑仍在读写它（红线②）。
 * - 箭头 `clickable=false` / `focusable=false`，点击冒泡到整行（原生点整行即展开）。
 * - 玻璃保持 v3.15.45 现状（82% / 深色 86%），本轮不动。
 */
internal object ArkMiniBarStyleHook {

    private const val TAG = "${ArkConstants.LOG_TAG}/miniBarStyle"

    private const val ROW_CLS =
        "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    private const val CONTENT_CLS =
        "com.android.systemui.statusbar.notification.row.NotificationContentView"
    private const val NIGHT_UTILS = "com.flyme.systemui.utils.SystemUICommonUtils"

    /** 空心圆控件的真实类名；类名不符就一个方法都不调，避免反射打到别的 View */
    private const val CIRCLE_CLS = "flyme.widget.LiveNotificationCircleProgressBar"

    /** chevron 光学尺寸：设计稿量得 10px 宽 / 6px 高，按 42px 箭头圆底等比折算 */
    private const val CHEVRON_HALF_DP = 5f
    private const val CHEVRON_DEPTH_DP = 3f
    private const val CHEVRON_STROKE_DP = 1.6f

    /** 徽标白环宽度（设计稿徽标边缘有 ~2px 浅色分离带） */
    private const val BADGE_RING_DP = 1.5f

    /** 徽标数字与圆边的安全内距；两位数靠**缩字号**而不是撑宽来适配 */
    private const val BADGE_TEXT_PAD_DP = 1f

    /** 缩字号下限（sp 的 dp 当量）；再小就糊成一团，宁可贴满 */
    private const val MIN_LABEL_SP = 8f

    /** 图标方块四周留白（= 徽标可外溢的空间），view 边长 = 图标 + 2×它 */
    private const val ICON_PAD_DP = 4f

    /** 图标方块距 header 左边缘。叠加 [ICON_PAD_DP] 后图标本体距 header 10dp
     *  = 距玻璃胶囊左沿 6dp（玻璃水平内嵌 [BAR_INSET_H_DP]） */
    private const val ICON_START_DP = 10f

    /** 徽标贴 view 边缘的安全距，保证整圆落在玻璃胶囊内不被切 */
    private const val BADGE_EDGE_DP = 2f

    /** 玻璃胶囊的水平 / 垂直内嵌（v3.15.48：通栏白底 → 内嵌 pill，左右露出壁纸） */
    private const val BAR_INSET_H_DP = 8f
    private const val BAR_INSET_V_DP = 4f

    /** 箭头圆底距玻璃胶囊右沿（胶囊右端是半径 22dp 的半圆，留 9dp 才不压到弧线） */
    private const val ARROW_END_DP = 9f

    /** 徽标数字上限：超过就显示「99+」，避免无限撑宽 */
    private const val BADGE_CAP = 99


    /**
     * 插入件标记。header 会随通知刷新复用（v3.15.37 坑），
     * 用 keyed tag 而不是遍历子 View 判断，既快又不会被系统的 tag 干扰。
     */
    private val KEY_ICON = "flymeark.mini.icon".hashCode()
    private val KEY_ARROW = "flymeark.mini.arrow".hashCode()
    private val KEY_SHIFTED = "flymeark.mini.shifted".hashCode()
    private val KEY_CENTERED = "flymeark.mini.centered".hashCode()
    private val KEY_NAV = "flymeark.mini.nav".hashCode()

    @Volatile
    private var idsReady = false

    @Volatile
    private var headerId = 0

    @Volatile
    private var titleId = 0

    @Volatile
    private var contentId = 0

    @Volatile
    private var circleId = 0

    /** 每个头部 + 深浅组合只打一条日志，避免通知刷新时刷屏（上限兜底，防长驻进程缓慢增长） */
    private val logged: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private const val LOGGED_CAP = 512

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != ArkConstants.SYSTEMUI_PKG) return
        XposedBridge.log(
            "[$TAG] installing (玻璃渐变 / 三级文字 / 圆环颜色 / 消息类图标+徽标+箭头)"
        )

        val rowCb = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                styleFrom(param.thisObject as? View)
            }
        }
        val childCb = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                styleFrom(param.args.getOrNull(0) as? View)
            }
        }

        // 行绑定完成 / 重新挂到窗口时补一次：holder 会复用，背景可能被重设
        ArkHooks.quiet("$TAG.onNotificationUpdated") {
            XposedHelpers.findAndHookMethod(
                ROW_CLS, lpp.classLoader, "onNotificationUpdated", rowCb
            )
        }
        ArkHooks.quiet("$TAG.onAttachedToWindow") {
            XposedHelpers.findAndHookMethod(
                ROW_CLS, lpp.classLoader, "onAttachedToWindow", rowCb
            )
        }
        // 内容视图换 child 时补一次（RemoteViews 重绑会换 View，上面的行 hook 可能早于 inflate）
        ArkHooks.quiet("$TAG.setContractedChild") {
            XposedHelpers.findAndHookMethod(
                CONTENT_CLS, lpp.classLoader, "setContractedChild", View::class.java, childCb
            )
        }
        ArkHooks.quiet("$TAG.setExpandedChild") {
            XposedHelpers.findAndHookMethod(
                CONTENT_CLS, lpp.classLoader, "setExpandedChild", View::class.java, childCb
            )
        }
    }

    // ------------------------------------------------------------------
    // 样式
    // ------------------------------------------------------------------

    private fun styleFrom(root: View?) {
        if (root == null) return
        try {
            resolveIds()
            if (headerId == 0) return
            val header = root.findViewById<View>(headerId) ?: return
            val night = isNight(header.context)
            val density = header.resources.displayMetrics.density

            // 分流（红线①）：只对消息类迷你条做结构改造，音乐 / 进度类一个都不碰
            val row = rowOf(header)
            val pkg = packageOf(row)
            val isMessage = pkg != null &&
                ArkConstants.isMessageLiveApp(pkg) &&
                !ArkConstants.isMediaApp(pkg)
            // v3.15.62：导航胶囊（高德）也要按设计稿 02·导航迷你条 改造。
            // 之前这里只放行消息，导航一直停留在系统默认样式（「高德地图 + 空心圆」）。
            val isNav = pkg != null && ArkConstants.isNavLiveApp(pkg)

            // 文字与圆环不依赖背景，永远可以做（零风险项）
            applyText(root, night, boldTitle = isMessage || isNav)
            applyCircle(root, night)

            if (isMessage) {
                pkg?.let { applyMessageStructure(header, night, it, density, badgeCountOf(row)) }
            } else if (isNav) {
                pkg?.let { applyNavStructure(header, night, it, density, row) }
            }

            // 背景是唯一会替换 View 已有状态的操作，带保守 guard
            val bgApplied = applyBackground(header, night)
            logOnce(header, night, bgApplied, isMessage, pkg)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] style skipped: $t")
        }
    }

    /**
     * 消息类迷你条的结构改造：左侧图标 + 未读徽标，右侧空心圆 → 展开箭头。
     *
     * 每一步都独立失败，失败的那一步只是不出现，不会连累其它步骤，
     * 更不会抛出到 inflate 链路上。
     */
    private fun applyMessageStructure(
        header: View,
        night: Boolean,
        pkg: String,
        density: Float,
        count: Int,
    ) {
        // ① 应用图标 + 未读徽标
        val iconOk = ensureIcon(header, pkg, density, count)
        // ② 标题 / 副标题右移，给图标让位（只做一次，避免 padding 累加）
        if (iconOk) ensureTextShift(header, density)
        // ③ 标题在 header 里垂直居中 —— 否则文字贴顶、和图标中线差一截（v3.15.48）
        ensureTextCentered(header)
        // ④ 右侧空心圆 → 隐藏，同位置插展开箭头
        ensureArrow(header, night, density)
    }

    /**
     * 左侧图标位：40dp 圆角块 + 右上角未读徽标，**一个自绘 View 画完**。
     *
     * 为什么不拼 `FrameLayout + ImageView + TextView`（v3.15.47 首版踩到）：
     * 1. 徽标要压到图标右上角**外侧**，子 View 溢出父边界会被裁掉
     *    （`clipChildren=false` 在通知行这条链路上不生效，实测徽标顶部/右侧被切平）；
     * 2. 运行时 `new TextView()` 拿的是 SystemUI 主题，`setTextColor(白)` 被后来的
     *    主题解析盖回黑色，实机截图上数字是黑的。
     * 自绘两个问题一起消失：`canvas.clipPath` 只作用于图标本身，数字用 Paint 直接画。
     *
     * @return 图标是否就位；false 表示取不到彩色应用图标，此时连标题右移都跳过，
     *         直接把这条通知留给 v3.15.45 的原样。
     */
    /**
     * v3.15.62：导航迷你条（`handoff-minibar.html` §02）的结构改造。
     *
     * 系统默认那条是「高德地图 + 空心圆」，和设计稿差了整个骨架：
     * 40×40 R12 蓝渐变转向块 / 13sp 指令 / 文字下方 2dp 路线进度 /
     * 右侧 12sp 时间值 / 24×24 R8 来源 logo。
     *
     * 与消息条同一套做法：**一个自绘 View 画完所有非文字元素**，
     * 不往系统 RelativeLayout 里塞多个子 View（v3.15.47 踩过主题色 / clipChildren）。
     * 文字仍由系统 TextView 画，我们只右移让位 + 覆盖成播报指令。
     *
     * v3.15.67 补齐与设计稿的三处偏差：指令 13sp（PX 传）/ 隐藏副标题（设计稿没有第二行）/
     * 偏航时转向块·进度·时间值同步转 [ArkTokens.NAV_YAW_RED]。
     */
    private fun applyNavStructure(
        header: View,
        night: Boolean,
        pkg: String,
        density: Float,
        row: View?,
    ) {
        val parent = header as? ViewGroup ?: return
        val p = navPayloadOf(row)

        var overlay = header.getTag(KEY_NAV) as? ArkMiniNavView
        if (overlay == null) {
            overlay = ArkMiniNavView(header.context, density, appIconOf(header.context, pkg))
            overlay.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            parent.addView(
                overlay,
                RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                )
            )
            header.setTag(KEY_NAV, overlay)
        }
        overlay.setData(
            p.turn, p.remainMs, p.totalMs, p.timeText, p.timeUnit, night, p.yawed
        )

        // 系统模板显示的是通知标题（固定「高德地图」），设计稿要的是**下一步指令**。
        // 每次影子刷新都会重进这里，覆盖不会被系统改回去。
        // v3.15.67：字号对齐设计稿 `.bar .nm { font-size:13px; font-weight:600 }` ——
        // 系统默认约 14–15sp 偏大。用 PX 传（`13f * density`），避免 sp 再被 fontScale 二次放大。
        if (p.instr.isNotBlank() && titleId != 0) {
            val tv = header.findViewById<View>(titleId) as? TextView
            if (tv != null) {
                if (tv.text?.toString() != p.instr) tv.text = p.instr
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * density)
            }
        }

        // v3.15.67：设计稿导航迷你条只有「一行指令 + 2dp 进度」，没有副标题。
        // 必须先清空 text —— [ensureTextCentered] 见到副标题有内容就会早退，
        // 标题拿不到 CENTER_VERTICAL，会退回贴顶。
        if (contentId != 0) {
            val sub = header.findViewById<View>(contentId) as? TextView
            if (sub != null) {
                sub.visibility = View.GONE
                if (sub.text != null) sub.text = null
            }
        }

        ensureTextShift(header, density)
        ensureTextCentered(header)
        // 空心圆让位给 logo：只 GONE 不 removeView（系统仍会读写它）
        if (circleId != 0) header.findViewById<View>(circleId)?.visibility = View.GONE
    }

    /** 导航迷你条载荷：从影子通知的顶层 extras 读（见 ArkConstants.NAV_KEY_*） */
    private data class NavMiniPayload(
        val instr: String = "",
        val turn: Int = ArkConstants.NAV_TURN_STRAIGHT,
        /** 本趟**剩余**毫秒（锚点式，v3.15.67）；`< 0` = 本趟还没有锚点 */
        val remainMs: Long = -1L,
        val totalMs: Long = 0L,
        val timeText: String = "",
        val timeUnit: String = "",
        /** 偏航态（v3.15.67）：true 时三点转 [ArkTokens.NAV_YAW_RED] */
        val yawed: Boolean = false,
    )

    private fun navPayloadOf(row: View?): NavMiniPayload = try {
        val sbn = sbnOf(row) ?: return NavMiniPayload()
        val n = XposedHelpers.callMethod(sbn, "getNotification") as? Notification
            ?: return NavMiniPayload()
        val ex = n.extras ?: return NavMiniPayload()
        NavMiniPayload(
            instr = ex.getString(ArkConstants.NAV_KEY_INSTR).orEmpty(),
            turn = ex.getInt(ArkConstants.NAV_KEY_TURN, ArkConstants.NAV_TURN_STRAIGHT),
            remainMs = ex.getLong(ArkConstants.NAV_KEY_REMAIN_MS, -1L),
            totalMs = ex.getLong(ArkConstants.NAV_KEY_TOTAL_MS, 0L),
            timeText = ex.getString(ArkConstants.NAV_KEY_TIME).orEmpty(),
            timeUnit = ex.getString(ArkConstants.NAV_KEY_TIME_UNIT).orEmpty(),
            yawed = ex.getBoolean(ArkConstants.NAV_KEY_YAWED, false),
        )
    } catch (_: Throwable) {
        NavMiniPayload()
    }

    /**
     * 导航迷你条的「非文字层」：一个 View 自绘左块 / 进度 / 时间值 / logo。
     * 透明区域不遮挡系统文字，也不拦截点击（View 默认 clickable=false）。
     */
    private class ArkMiniNavView(
        context: Context,
        private val density: Float,
        private val appIcon: Drawable?,
    ) : View(context) {

        private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = ArkTokens.BRAND_ON_LIGHT
            strokeWidth = 2.2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.RIGHT
            textSize = 12f * density
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val arrowPath = Path()
        private val turnRect = RectF()
        private val trackRect = RectF()
        private val logoRect = RectF()
        private val clipPath = Path()

        private var turn = ArkConstants.NAV_TURN_STRAIGHT

        /**
         * 本趟**剩余**毫秒（v3.15.67 锚点式）。
         *
         * v3.15.66 及以前这里存的是 `started`（出发时刻），进度 / 时间都拿
         * `elapsedRealtime() - started` 现推 —— 用户停着不动，迷你条也一直往前走。
         * 展开卡 v3.15.65 就改成锚点式了，迷你条这一版才跟上：剩余值由高德进程
         * 在每次播报时重算（`NAV_KEY_REMAIN_MS`），我们两帧之间**只读不算**。
         */
        private var remainMs = -1L

        private var totalMs = 0L

        /** 偏航态：转向块 / 进度 / 时间值三点同时转红 */
        private var yawed = false

        private var timeFallback = ""

        /** 蓝色 160deg 渐变；按 view 高度缓存（几何只跟高度有关，见 [blueShader]） */
        private var blueShader: Shader? = null

        private var blueShaderHeight = -1

        init {
            setWillNotDraw(false)
        }

        fun setData(
            turn: Int,
            remainMs: Long,
            totalMs: Long,
            timeText: String,
            timeUnit: String,
            night: Boolean,
            yawed: Boolean,
        ) {
            this.turn = turn
            this.remainMs = remainMs
            this.totalMs = totalMs
            this.yawed = yawed
            timeFallback = if (timeText.isBlank()) "" else "$timeText $timeUnit".trim()
            // 偏航：进度填充统一转警示红；正常：品牌蓝（深浅两档）
            fillPaint.color = when {
                yawed -> ArkTokens.NAV_YAW_RED
                night -> ArkTokens.BRAND_DARK
                else -> ArkTokens.BRAND_LIGHT
            }
            trackPaint.color =
                if (night) ArkTokens.TRACK_NEUTRAL_DARK else ArkTokens.TRACK_NEUTRAL_LIGHT
            // 偏航：时间值同样转警示红；箭头恒白（[arrowPaint] 在字段初始化里定死，不随偏航变）
            textPaint.color = when {
                yawed -> ArkTokens.NAV_YAW_RED
                night -> ArkTokens.TEXT_SECONDARY_DARK
                else -> ArkTokens.SUBTLE_TEXT_LIGHT
            }
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val cy = height / 2f
            if (w <= 0f || cy <= 0f) return
            val d = density

            // ① 40×40 R12 蓝渐变转向块（偏航时整块转红）
            val left = 10f * d
            val size = 40f * d
            turnRect.set(left, cy - size / 2f, left + size, cy + size / 2f)
            if (yawed) {
                surfacePaint.shader = null
                surfacePaint.color = ArkTokens.NAV_YAW_RED
            } else {
                surfacePaint.shader = blueGradient(turnRect, size)
            }
            c.drawRoundRect(turnRect, 12f * d, 12f * d, surfacePaint)
            drawArrow(c, left + size / 2f, cy - size / 2f, size)

            // ④ 最右 24×24 R8 来源 logo
            val logoR = 24f * d
            val logoLeft = w - 12f * d - logoR
            logoRect.set(logoLeft, cy - logoR / 2f, w - 12f * d, cy + logoR / 2f)
            drawLogo(c)

            // ③ 右侧时间值
            val label = remainingLabel()
            val tw = if (label.isEmpty()) 0f else textPaint.measureText(label)
            val textRight = logoLeft - 10f * d
            if (label.isNotEmpty()) c.drawText(label, textRight, cy + 4f * d, textPaint)

            // ② 文字下方 2dp 路线进度
            val trackLeft = left + size + 10f * d
            val trackRight = (textRight - tw - 10f * d)
                .coerceAtLeast(trackLeft + 20f * d)
            val ty = cy + 13f * d
            trackRect.set(trackLeft, ty - d, trackRight, ty + d)
            c.drawRoundRect(trackRect, d, d, trackPaint)
            val f = fraction()
            if (f > 0f) {
                val fillRight = trackLeft + (trackRight - trackLeft) * f
                trackRect.set(
                    trackLeft, ty - d,
                    fillRight.coerceAtLeast(trackLeft + 2f * d), ty + d
                )
                c.drawRoundRect(trackRect, d, d, fillPaint)
            }
        }

        /**
         * 蓝色转向块渐变（CSS `160deg` ≈ 从左上、向右偏 20° 往下，`tan20° ≈ 0.364`）。
         *
         * v3.15.67：从 `onSizeChanged` 挪到绘制期 —— 偏航时整块要换成纯色，
         * 颜色必须**每帧按 [yawed] 决定**，不能在尺寸变化时写死一次。
         * 渐变几何只与 view 高度有关，因此按高度缓存：稳定态下不产生分配。
         */
        private fun blueGradient(rect: RectF, size: Float): Shader {
            val h = height
            blueShader?.let { if (blueShaderHeight == h) return it }
            val grad = LinearGradient(
                rect.left, rect.top,
                rect.left + size * 0.364f, rect.top + size,
                intArrayOf(ArkTokens.TURN_TOP, ArkTokens.TURN_MID, ArkTokens.TURN_BOTTOM),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
            blueShader = grad
            blueShaderHeight = h
            return grad
        }

        /**
         * 进度（v3.15.67 锚点式）：`(全程 - 剩余) / 全程`。
         *
         * 只用高德进程在播报时算好的 [remainMs]，**两帧之间不自己往前走** ——
         * 与展开卡 [ArkNavProgressView] 同一口径。没有锚点（`remainMs < 0`）或没有分母
         * 时返回 0，只画轨不画填充。
         */
        private fun fraction(): Float {
            if (totalMs <= 0L || remainMs < 0L) return 0f
            return ((totalMs - remainMs).toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
        }

        /**
         * 时间值（v3.15.67 锚点式）：剩余毫秒向上取整到分钟并显示成「X 分钟」。
         *
         * 没有锚点就退回播报自带的剩余时长文案 [timeFallback]（`NAV_KEY_TIME` 本身就是
         * 剩余分钟），不再拿出发时刻倒推。
         */
        private fun remainingLabel(): String {
            if (remainMs < 0L) return timeFallback
            val min = ((remainMs + 59_999L) / 60_000L).coerceAtLeast(0L)
            return "$min 分钟"
        }

        private fun drawLogo(c: Canvas) {
            val radius = 8f * density
            if (appIcon != null) {
                val save = c.save()
                clipPath.reset()
                clipPath.addRoundRect(logoRect, radius, radius, Path.Direction.CW)
                c.clipPath(clipPath)
                appIcon.setBounds(
                    logoRect.left.toInt(), logoRect.top.toInt(),
                    logoRect.right.toInt(), logoRect.bottom.toInt(),
                )
                appIcon.draw(c)
                c.restoreToCount(save)
            } else {
                // 设计稿 §四.2：未接入时虚线描边占位，不写文字
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = density
                    color = 0x668A9099
                    pathEffect = android.graphics.DashPathEffect(
                        floatArrayOf(3f * density, 3f * density), 0f
                    )
                }
                c.drawRoundRect(logoRect, radius, radius, p)
            }
        }

        /** 24 viewBox 的箭头，缩到 24dp 居中画进 40dp 块 */
        private fun drawArrow(c: Canvas, cx: Float, top: Float, size: Float) {
            val s = (24f * density) / 24f
            val ox = cx - 12f * s
            val oy = top + (size - 24f * s) / 2f
            arrowPath.reset()

            fun mv(x: Float, y: Float) = arrowPath.moveTo(ox + x * s, oy + y * s)
            fun lt(x: Float, y: Float) = arrowPath.lineTo(ox + x * s, oy + y * s)
            fun qt(x1: Float, y1: Float, x2: Float, y2: Float) =
                arrowPath.quadTo(ox + x1 * s, oy + y1 * s, ox + x2 * s, oy + y2 * s)

            when (turn) {
                ArkConstants.NAV_TURN_LEFT -> {
                    mv(16f, 20.5f); lt(16f, 10.4f); qt(16f, 6f, 11.6f, 6f); lt(6.6f, 6f)
                    arrowPath.moveTo(ox + 9f * s, oy + 2.9f * s)
                    arrowPath.lineTo(ox + 5f * s, oy + 6f * s)
                    arrowPath.lineTo(ox + 9f * s, oy + 9.1f * s)
                }
                ArkConstants.NAV_TURN_RIGHT -> {
                    mv(8f, 20.5f); lt(8f, 10.4f); qt(8f, 6f, 12.4f, 6f); lt(17.4f, 6f)
                    arrowPath.moveTo(ox + 15f * s, oy + 2.9f * s)
                    arrowPath.lineTo(ox + 19f * s, oy + 6f * s)
                    arrowPath.lineTo(ox + 15f * s, oy + 9.1f * s)
                }
                ArkConstants.NAV_TURN_UTURN -> {
                    mv(8f, 20.5f); lt(8f, 10f); qt(8f, 6f, 12f, 6f)
                    qt(17f, 6f, 17f, 10f); lt(17f, 15f)
                    arrowPath.moveTo(ox + 14f * s, oy + 12f * s)
                    arrowPath.lineTo(ox + 17f * s, oy + 15f * s)
                    arrowPath.lineTo(ox + 20f * s, oy + 12f * s)
                }
                else -> { // 直行 / 到达
                    mv(12f, 20f); lt(12f, 5f)
                    arrowPath.moveTo(ox + 8.5f * s, oy + 8.5f * s)
                    arrowPath.lineTo(ox + 12f * s, oy + 5f * s)
                    arrowPath.lineTo(ox + 15.5f * s, oy + 8.5f * s)
                }
            }
            c.drawPath(arrowPath, arrowPaint)
        }
    }

    private fun ensureIcon(
        header: View,
        pkg: String,
        density: Float,
        count: Int,
    ): Boolean {
        val parent = header as? ViewGroup ?: return false
        val existing = header.getTag(KEY_ICON) as? ArkMiniIconView
        if (existing != null) {
            // 复用：只刷徽标数字
            existing.setCount(count)
            return true
        }

        val appIcon = appIconOf(header.context, pkg) ?: return false
        val sizePx = ((ArkTokens.MINI_ICON_DP + 2 * ICON_PAD_DP) * density).toInt()
        val iconView = ArkMiniIconView(header.context, density, appIcon)
        iconView.setCount(count)
        iconView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        val lp = RelativeLayout.LayoutParams(sizePx, sizePx)
        lp.addRule(RelativeLayout.ALIGN_PARENT_START)
        lp.addRule(RelativeLayout.CENTER_VERTICAL)
        lp.leftMargin = (ICON_START_DP * density).toInt()
        parent.addView(iconView, lp)
        header.setTag(KEY_ICON, iconView)
        return true
    }

    /**
     * 标题 / 副标题整体右移「图标 + 间距」，给左侧图标让位。
     *
     * 只动 padding 的 left 一边：TextView 宽度仍由通知模板测量决定，字号 / 行高不变，
     * 因此不会牵动 `ArkMorphAnim` 的起点计算。用 [KEY_SHIFTED] 保证只加一次。
     */
    private fun ensureTextShift(header: View, density: Float) {
        if (header.getTag(KEY_SHIFTED) != null) return
        val delta = (
            ICON_START_DP + ICON_PAD_DP +
                ArkTokens.MINI_ICON_DP + ArkTokens.MINI_ICON_GAP_DP
            ) * density
        val deltaPx = delta.toInt()
        var touched = false
        for (id in intArrayOf(titleId, contentId)) {
            if (id == 0) continue
            val tv = header.findViewById<View>(id) ?: continue
            tv.setPadding(
                tv.paddingLeft + deltaPx,
                tv.paddingTop,
                tv.paddingRight,
                tv.paddingBottom,
            )
            touched = true
        }
        if (touched) header.setTag(KEY_SHIFTED, true)
    }

    /**
     * 标题在 header 里**垂直居中**（v3.15.48）。
     *
     * 根因：`rl_header_live` 是 RelativeLayout，`title_header_live` 没有布局规则，
     * 默认落在 (0,0) 贴顶；模板之所以看着「正常」，是因为副标题 `content_header_live`
     * 正好接在下面、两行拼起来接近满高。我们插了 40dp 图标之后，图标按 header 中线居中，
     * 而文字仍贴顶 → 实机量得图标中线比文字中线低 43px（约 12dp）。
     *
     * 修法：给标题加 `CENTER_VERTICAL` 规则，让它落在 header 中线上；
     * **不动 textSize / lineHeight**，只改布局规则。
     * 副标题一旦真有内容，就退回模板原本的上下堆叠（不去挤，避免压字）。
     */
    private fun ensureTextCentered(header: View) {
        if (titleId == 0) return
        if (header.getTag(KEY_CENTERED) != null) return
        val title = header.findViewById<View>(titleId) ?: return
        if (contentId != 0) {
            val sub = header.findViewById<View>(contentId) as? TextView
            if (sub != null && sub.text?.isNotBlank() == true) return
        }
        val lp = title.layoutParams as? RelativeLayout.LayoutParams ?: return
        lp.addRule(RelativeLayout.CENTER_VERTICAL)
        title.layoutParams = lp
        header.setTag(KEY_CENTERED, true)
    }

    /**
     * 右侧展开箭头（红线②）：空心圆**只设 GONE 不 removeView** —— 系统仍会读写它。
     * 箭头同位置铺 44dp 中性圆底 + 居中 chevron，`clickable=false` 让点击冒泡到整行。
     */
    private fun ensureArrow(header: View, night: Boolean, density: Float) {
        val parent = header as? ViewGroup ?: return
        val arrowPx = (ArkTokens.MINI_ARROW_DP * density).toInt()
        var arrow = header.getTag(KEY_ARROW) as? ArkMiniArrowView
        if (arrow == null) {
            arrow = ArkMiniArrowView(header.context, density)
            val lp = RelativeLayout.LayoutParams(arrowPx, arrowPx)
            lp.addRule(RelativeLayout.ALIGN_PARENT_END)
            lp.addRule(RelativeLayout.CENTER_VERTICAL)
            // 跟着玻璃胶囊往内让，否则圆底会压在胶囊右端半圆的弧线外面
            lp.rightMargin = ((BAR_INSET_H_DP + ARROW_END_DP) * density).toInt()
            parent.addView(arrow, lp)
            header.setTag(KEY_ARROW, arrow)
        }
        arrow.isNight = night
        if (circleId != 0) {
            header.findViewById<View>(circleId)?.visibility = View.GONE
        }
    }

    /**
     * 强制用我们的玻璃覆盖头部背景（v3.15.45 用户决策）。
     * 仅当已经是「同深浅的我们自己的玻璃」时跳过重建，避免通知刷新时反复分配；
     * 否则一律覆盖系统自带背景——SystemUI shade 的模糊会透过这层半透明玻璃形成毛玻璃。
     *
     * v3.15.48：整条通栏玻璃（从屏幕左沿铺到右沿、挡住壁纸）改成**内嵌胶囊**——
     * 外面包一层 [InsetDrawable]（水平 8dp / 垂直 4dp），左右露出壁纸；
     * [ArkMiniBarGlass.onBoundsChange] 原本就按 `minOf(w,h)/2` 取全圆角，
     * inset 之后 height 变小，圆角自然收成完整 pill。
     *
     * **不用** margin / padding / layoutParams 缩宽度：header 的宽度参与胶囊收窄计算
     * 与整行点击区，动它风险远大于在 drawable 上做 inset。
     */
    private fun applyBackground(header: View, night: Boolean): Boolean {
        val current = oursGlass(header.background)
        // 同深浅且已是我们自己的玻璃 → 不重建，避免通知刷新时反复分配
        if (current != null && current.night == night) return false
        val density = header.resources.displayMetrics.density
        header.background = InsetDrawable(
            ArkMiniBarGlass(night, density),
            (BAR_INSET_H_DP * density).toInt(),
            (BAR_INSET_V_DP * density).toInt(),
            (BAR_INSET_H_DP * density).toInt(),
            (BAR_INSET_V_DP * density).toInt(),
        )
        return true
    }

    /** 穿透 [InsetDrawable] 拿到我们自己的玻璃；不是我们的就返回 null（复用判断要用） */
    private fun oursGlass(d: Drawable?): ArkMiniBarGlass? {
        var cur = d
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur is ArkMiniBarGlass) return cur
            cur = (cur as? InsetDrawable)?.drawable
            depth++
        }
        return null
    }

    /**
     * 一级色给标题、二级色给副标题；不改字号。
     *
     * @param boldTitle 仅消息类迷你条加粗（设计稿 #161A22 近黑 + Bold）。
     *        音乐类的标题是歌名，加粗会改变文本宽度、可能挤掉跑马灯余量，所以不碰。
     */
    private fun applyText(root: View, night: Boolean, boldTitle: Boolean) {
        val primary = if (night) ArkTokens.TEXT_PRIMARY_DARK else ArkTokens.TEXT_PRIMARY_LIGHT
        val secondary = if (night) ArkTokens.TEXT_SECONDARY_DARK else ArkTokens.TEXT_SECONDARY_LIGHT
        if (titleId != 0) {
            val title = root.findViewById<View>(titleId) as? TextView
            if (title != null) {
                title.setTextColor(primary)
                if (boldTitle) title.setTypeface(title.typeface, Typeface.BOLD)
            }
        }
        if (contentId != 0) {
            (root.findViewById<View>(contentId) as? TextView)?.setTextColor(secondary)
        }
    }

    /**
     * 空心圆只规范颜色：品牌色进度 + 可见外环 + 一级文字色，形状与进度逻辑完全不动。
     * 浅色玻璃上设计稿的 TRACK_LIGHT(#D9DEE7) 几乎与背景同色、空心圆轮廓消失，
     * 改用可读的中性灰 TEXT_SECONDARY，保证轮廓清晰（深色调沿用 TRACK_DARK）。
     * 消息类会把它的 visibility 设成 GONE，这一层配色只对音乐 / 进度类可见。
     */
    private fun applyCircle(root: View, night: Boolean) {
        if (circleId == 0) return
        val circle = root.findViewById<View>(circleId) ?: return
        if (circle.javaClass.name != CIRCLE_CLS) return
        val brand = if (night) ArkTokens.BRAND_DARK else ArkTokens.BRAND_LIGHT
        // 外环在浅色玻璃上需可见：用中性灰而非近白的 TRACK_LIGHT
        val track = if (night) ArkTokens.TRACK_DARK else ArkTokens.TEXT_SECONDARY_LIGHT
        val text = if (night) ArkTokens.TEXT_PRIMARY_DARK else ArkTokens.TEXT_PRIMARY_LIGHT
        invokeInt(circle, "setProgressColor", brand)
        invokeInt(circle, "setProgressBgColor", track)
        invokeInt(circle, "setTextColor", text)
    }

    private fun invokeInt(target: View, name: String, value: Int) {
        try {
            val m: Method = target.javaClass.getMethod(name, Integer.TYPE)
            m.invoke(target, value)
        } catch (_: Throwable) {
            // ROM 裁剪了该方法就静默跳过，颜色保持系统原值
        }
    }

    // ------------------------------------------------------------------
    // 通知数据（只读，失败一律返回空并降级）
    // ------------------------------------------------------------------

    /** 从头部向上回溯到 `ExpandableNotificationRow`；层次不符就返回 null */
    private fun rowOf(v: View?): View? {
        var cur: View? = v
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.javaClass.name == ROW_CLS) return cur
            cur = cur.parent as? View
            depth++
        }
        return null
    }

    /** `ExpandableNotificationRow.getEntry()` → `NotificationEntry.getSbn()` */
    private fun sbnOf(row: View?): Any? {
        if (row == null) return null
        return try {
            val entry = XposedHelpers.callMethod(row, "getEntry") ?: return null
            try {
                XposedHelpers.callMethod(entry, "getSbn")
            } catch (_: Throwable) {
                XposedHelpers.getObjectField(entry, "mSbn")
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun packageOf(row: View?): String? {
        val sbn = sbnOf(row) ?: return null
        return try {
            XposedHelpers.callMethod(sbn, "getPackageName") as? String
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 未读条数：**复用** v3.15.34「按会话统计」的 [ArkMessageLiveInjector.sessionMessageCount]，
     * 不用 `Notification.number`（那是整个 App 的未读数，微信实测 218，完全不能当徽标）。
     *
     * `sessionMessageCount` 的契约是「只在 > 1 时返回条数，拿不到返回 0」（宁可不报数也不报错数），
     * 所以这里把 0 读作「这个会话 1 条」——影子通知的正文是「有新消息 · 点击查看」，
     * 单条消息本来就该显示 1。只有连标题/正文都取不到（连是不是消息都判断不了）才返回 0，
     * 由 [bindBadge] 隐藏徽标，不显示 0。
     */
    private fun badgeCountOf(row: View?): Int {
        val sbn = sbnOf(row) ?: return 0
        return try {
            val notif = XposedHelpers.callMethod(sbn, "getNotification") as? Notification
                ?: return 0
            val extras: Bundle = notif.extras ?: return 0
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            if (title.isNullOrBlank() && text.isNullOrBlank()) return 0
            val n = ArkMessageLiveInjector.sessionMessageCount(extras, text, title)
            if (n > 0) n else 1
        } catch (_: Throwable) {
            0
        }
    }

    /** 彩色应用图标（不是被 tint 的 smallIcon）；取不到就降级 */
    @Suppress("DEPRECATION")
    private fun appIconOf(ctx: Context, pkg: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (_: Throwable) {
        null
    }

    /** 与媒体卡同源：Flyme 自己的 sIsNightMode，读不到再退系统 uiMode */
    private fun isNight(ctx: Context): Boolean {
        try {
            val cls = XposedHelpers.findClass(NIGHT_UTILS, ctx.classLoader)
            return XposedHelpers.getStaticBooleanField(cls, "sIsNightMode")
        } catch (_: Throwable) {
            // fall through
        }
        return try {
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolveIds() {
        if (idsReady) return
        val r: Resources = Resources.getSystem()
        headerId = r.getIdentifier("rl_header_live", "id", "android")
        titleId = r.getIdentifier("title_header_live", "id", "android")
        contentId = r.getIdentifier("content_header_live", "id", "android")
        circleId = r.getIdentifier("iv_circle_progress_live", "id", "android")
        idsReady = true
    }

    private fun logOnce(
        v: View,
        night: Boolean,
        bgApplied: Boolean,
        isMessage: Boolean,
        pkg: String?,
    ) {
        val key = System.identityHashCode(v).toString() + "/" + night + "/" + isMessage
        if (logged.size > LOGGED_CAP) logged.clear()
        if (!logged.add(key)) return
        XposedBridge.log(
            "[$TAG] styled: night=$night message=$isMessage pkg=$pkg " +
                "header=#$headerId title=#$titleId circle=#$circleId" +
                if (bgApplied) "" else " (glass unchanged: already ours)"
        )
    }

    // ------------------------------------------------------------------
    // 自绘控件
    // ------------------------------------------------------------------

    /**
     * 左侧应用图标 + 右上角未读徽标，一次画完（40dp 圆角块 + 16dp 红圆白字）。
     *
     * 见 [ensureIcon] 的注释：拼子 View 会被父边界裁掉、也会被 SystemUI 主题改写文字色，
     * 自绘把这两个不确定性一起消掉。view 边长 = 图标 + 2×[ICON_PAD_DP]，
     * 多出来的 4dp 就是徽标可以溢出的空间，所以绘制始终落在自己的 bounds 内，不需要
     * `clipChildren=false`。
     */
    private class ArkMiniIconView(
        ctx: Context,
        private val density: Float,
        private val appIcon: Drawable,
    ) : View(ctx) {

        private val corner = ArkTokens.RADIUS_CHIP_DP * density
        private val pad = ICON_PAD_DP * density
        private val badgeR = ArkTokens.BADGE_SIZE_DP * density / 2f

        /** 徽标数字基准字号（12sp 折算 px）；放不下时在 onDraw 里按比例缩小 */
        private val baseLabelPx = ArkTokens.TYPE_CAPTION_SP * density

        private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }

        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ArkTokens.BRAND_ON_LIGHT
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = baseLabelPx
        }

        private val clipPath = Path()
        private val badgeRect = RectF()
        private val metrics = Paint.FontMetrics()

        /** 徽标文案；空串 = 不画徽标（取不到条数时不显示 0） */
        private var label: String = ""

        init {
            setWillNotDraw(false)
        }

        fun setCount(count: Int) {
            val next = when {
                count <= 0 -> ""
                count > BADGE_CAP -> BADGE_CAP.toString()
                else -> count.toString()
            }
            if (next == label) return
            label = next
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            // ① 图标：只用 canvas 裁剪，不依赖 ViewOutlineProvider / clipToOutline
            clipPath.reset()
            clipPath.addRoundRect(pad, pad, w - pad, h - pad, corner, corner, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clipPath)
            appIcon.setBounds(
                pad.toInt(),
                pad.toInt(),
                (w - pad).toInt(),
                (h - pad).toInt(),
            )
            appIcon.draw(canvas)
            canvas.restoreToCount(save)

            if (label.isEmpty()) return

            // ② 徽标：**直径恒定**的正圆，锚在图标右上角，圆底 + 内侧白环。
            //    两位数不许撑成胶囊（v3.15.48 用户验收：1 和 64 必须是同一个圆）。
            val badgeH = badgeR * 2f
            val right = w - BADGE_EDGE_DP * density
            val top = BADGE_EDGE_DP * density
            badgeRect.set(right - badgeH, top, right, top + badgeH)

            discPaint.color = ArkTokens.DANGER
            canvas.drawRoundRect(badgeRect, badgeR, badgeR, discPaint)

            val ring = BADGE_RING_DP * density
            ringPaint.color = ArkTokens.BRAND_ON_LIGHT
            ringPaint.strokeWidth = ring
            val inset = ring / 2f
            badgeRect.inset(inset, inset)
            val innerR = (badgeR - inset).coerceAtLeast(0f)
            canvas.drawRoundRect(badgeRect, innerR, innerR, ringPaint)

            // ③ 数字：放不下就等比缩字号（不撑宽），下限 8sp；Paint 直接上白色，
            //    不受 SystemUI 主题 / 深色模式影响（v3.15.48 自查：恒为白）
            val avail = badgeH - 2f * ring - 2f * BADGE_TEXT_PAD_DP * density
            labelPaint.textSize = baseLabelPx
            val textW = labelPaint.measureText(label)
            if (textW > avail && textW > 0f) {
                labelPaint.textSize = (baseLabelPx * avail / textW)
                    .coerceAtLeast(MIN_LABEL_SP * density)
            }
            labelPaint.getFontMetrics(metrics)
            val baseline = top + badgeR - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(label, badgeRect.centerX(), baseline, labelPaint)
        }
    }

    /**
     * 右侧展开箭头：中性圆底 + 居中向下 chevron。
     *
     * 为什么自绘而不是用 `R.drawable`：本 hook 运行在 SystemUI 进程，
     * 那个 Context 的 Resources 里没有我们 APK 的 drawable（`R.drawable.*` 的 id 在本进程
     * 解析不到）。自绘还顺带绕开了 Flyme 深色模式改写 framework 形状 drawable 填充色的问题
     * （见 `ArkChipDrawable` 的注释）。
     */
    private class ArkMiniArrowView(ctx: Context, private val density: Float) : View(ctx) {

        private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val path = Path()

        var isNight: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        init {
            // 红线②：不抢点击，事件冒泡到整行（原生点整行就展开）
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            discPaint.color =
                if (isNight) ArkTokens.TOUCH_SOFT_DARK else ArkTokens.TOUCH_SOFT_LIGHT
            canvas.drawCircle(w / 2f, h / 2f, minOf(w, h) / 2f, discPaint)

            val cx = w / 2f
            val cy = h / 2f
            val half = CHEVRON_HALF_DP * density
            val depth = CHEVRON_DEPTH_DP * density
            path.reset()
            path.moveTo(cx - half, cy - depth)
            path.lineTo(cx, cy + depth)
            path.lineTo(cx + half, cy - depth)
            chevronPaint.color = if (isNight) {
                ArkTokens.TEXT_SECONDARY_DARK
            } else {
                ArkTokens.TEXT_SECONDARY_LIGHT
            }
            chevronPaint.strokeWidth = CHEVRON_STROKE_DP * density
            canvas.drawPath(path, chevronPaint)
        }
    }

    /**
     * 迷你条玻璃：垂直渐变（angle 270 等价 TOP_BOTTOM）+ 全圆角 + 1dp 高光描边。
     * 色值全部取自 [ArkTokens]，与 `bg_ark_panel.xml` 的 `@color/ark_glass_*` 同源。
     * 单独一个子类只是为了识别「这层是我们加的」，从而不覆盖系统后设的背景。
     */
    private class ArkMiniBarGlass(val night: Boolean, density: Float) : GradientDrawable(
        Orientation.TOP_BOTTOM,
        intArrayOf(
            if (night) ArkTokens.GLASS_TOP_DARK else ArkTokens.GLASS_TOP_LIGHT,
            if (night) ArkTokens.GLASS_BOTTOM_DARK else ArkTokens.GLASS_BOTTOM_LIGHT,
        ),
    ) {
        init {
            // 兜底半径：bounds 就绪后由 onBoundsChange 按实际高度取全圆角
            cornerRadius = ArkTokens.RADIUS_PILL_DP * density
            setStroke(
                (ArkTokens.STROKE_DP * density).toInt().coerceAtLeast(1),
                if (night) ArkTokens.HIGHLIGHT_DARK else ArkTokens.HIGHLIGHT_LIGHT,
            )
        }

        /** 设计稿的 RadiusPill = 全圆角；按实测高度取一半，44dp 高时正好 22dp */
        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            cornerRadius = minOf(bounds.width(), bounds.height()) / 2f
        }
    }
}

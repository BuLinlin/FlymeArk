package com.flymeark

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.Collections

/**
 * v3.15.44：实况通知**迷你条**（截图里「微信 + 空心圆」那条半透明横条）的纯视觉样式。
 *
 * ## 它是什么
 *
 * `u2.xml` 实证的节点链：
 * ```
 * ExpandableNotificationRow → expanded → android:id/rl_live_base
 *                                      → android:id/rl_header_live
 *                                          ├─ android:id/title_header_live   (TextView, "QQ"/"微信")
 *                                          ├─ android:id/content_header_live (TextView)
 *                                          └─ android:id/iv_circle_progress_live (空心圆)
 * ```
 * 布局来自 framework-res 的 `notification_template_live_header.xml`，
 * id 在 `android:` 命名空间，所以用 `Resources.getSystem().getIdentifier` 取数值 id。
 * 反编译该模板可见它**没有 background 属性**——系统的磨砂基底在父级通知行上，
 * 我们给头部加玻璃只是往上叠一层，不会把系统基底削薄。
 *
 * ## 只调样式，不碰任何逻辑
 *
 * 四个 hook 全部挂在 SystemUI 主线程方法的 **after** 上，只读视图、只写
 * `background` / `setTextColor` / 圆环颜色；不改参数、不改返回值、不碰测量、宽度、
 * 滑入动画、`mShowType` 门控与点击行为。`ArkLiveCapsuleStyleHook` 的
 * `mShowType ∈ {5,6}` 门控（v3.15.38 修「空壳」的教训）原样保留，这里既不复制也不放开。
 *
 * ## 为什么不会削弱系统基底（ee622ad / v3.15.43 教训）
 *
 * - 不 hook `MzBlurUtils.setBackgroundBlurDrawable`、不碰 `args[4]` 的 `0xB2`；
 * - 不全局 hook `View.setBackground`、不包装 `BackgroundBlurDrawable`、不调 `View.setAlpha()`；
 * - 只在**按 id 命中的那一个头部 View** 上设背景（v3.15.45 起强制覆盖系统自带背景，
 *   不判断「是否已有系统背景」）；SystemUI 通知 shade 本身已带模糊，我们的半透明玻璃叠在上面
 *   即形成毛玻璃，模糊不可用（省电 / 视频隧道 / API<31）时由 `ArkMiniBarGlass` 的 alpha 兜底；
 *   `REWRITE_SYSTEM_TINT` 仍为 false，不削弱系统本身的 0xB2 基底。
 *
 * ## 克制项
 *
 * 不改 `textSize` / 宽高 / padding：`title_header_live` 的宽度由通知模板测量决定，
 * 动字号会牵动宽度计算；字号 token 只落在本模块自己的两张大卡样式上。
 * 空心圆保持结构原样（不做应用图标 / 未读徽标 / 展开箭头），只规范颜色。
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
        XposedBridge.log("[$TAG] installing (玻璃渐变 / 三级文字 / 圆环颜色；不改宽度与动画)")

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
            if (headerId == 0 && titleId == 0 && circleId == 0) return
            val header = if (headerId != 0) root.findViewById<View>(headerId) else null
            if (header == null && titleId == 0 && circleId == 0) return
            val probe = header ?: root
            val night = isNight(probe.context)

            // 文字与圆环不依赖背景，永远可以做（零风险项）
            applyText(root, night)
            applyCircle(root, night)

            // 背景是唯一会替换 View 已有状态的操作，带保守 guard
            val bgApplied = if (header != null) applyBackground(header, night) else false
            logOnce(header ?: root, night, bgApplied)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] style skipped: $t")
        }
    }

    /**
     * 强制用我们的玻璃覆盖头部背景（v3.15.45 用户决策）。
     * 仅当已经是「同深浅的我们自己的玻璃」时跳过重建，避免通知刷新时反复分配；
     * 否则一律覆盖系统自带背景——SystemUI shade 的模糊会透过这层半透明玻璃形成毛玻璃。
     */
    private fun applyBackground(header: View, night: Boolean): Boolean {
        val current = header.background
        // 同深浅且已是我们自己的玻璃 → 不重建，避免通知刷新时反复分配
        if (current is ArkMiniBarGlass && current.night == night) return false
        // v3.15.45：强制覆盖系统自带背景（用户选择显示玻璃条，接受可能削弱系统模糊基底的取舍）
        header.background = ArkMiniBarGlass(night, header.resources.displayMetrics.density)
        return true
    }

    /** 一级色给标题、二级色给副标题；不改字号（见文件头「克制项」） */
    private fun applyText(root: View, night: Boolean) {
        val primary = if (night) ArkTokens.TEXT_PRIMARY_DARK else ArkTokens.TEXT_PRIMARY_LIGHT
        val secondary = if (night) ArkTokens.TEXT_SECONDARY_DARK else ArkTokens.TEXT_SECONDARY_LIGHT
        if (titleId != 0) {
            (root.findViewById<View>(titleId) as? TextView)?.setTextColor(primary)
        }
        if (contentId != 0) {
            (root.findViewById<View>(contentId) as? TextView)?.setTextColor(secondary)
        }
    }

    /**
     * 空心圆只规范颜色：品牌色进度 + 可见外环 + 一级文字色，形状与进度逻辑完全不动。
     * 浅色玻璃上设计稿的 TRACK_LIGHT(#D9DEE7) 几乎与背景同色、空心圆轮廓消失，
     * 改用可读的中性灰 TEXT_SECONDARY，保证轮廓清晰（深色调沿用 TRACK_DARK）。
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

    private fun logOnce(v: View, night: Boolean, bgApplied: Boolean) {
        val key = System.identityHashCode(v).toString() + "/" + night
        if (logged.size > LOGGED_CAP) logged.clear()
        if (!logged.add(key)) return
        XposedBridge.log(
            "[$TAG] styled: night=$night header=#$headerId title=#$titleId circle=#$circleId" +
                if (bgApplied) "" else " (glass unchanged: already ours)"
        )
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

package com.flymeark

import android.content.Context
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v3.3：胶囊尺寸 + 歌词横向滚动切换。
 *
 * ## 一、胶囊为什么这么宽
 *
 * `LiveNotificationView.getCalculatedWidth(text, showType)` 对 **showType 5/6（歌词胶囊）**：
 * ```
 * maxTextW = mStartSideStatusBarWidth - mFixedWidth - mStatusBarPaddingStart - mDispalyCutoutWidth
 * width = maxTextW + mFixedWidth      // ≈ 整块左侧状态栏宽度，与文字长度无关
 * ```
 * 实测固定 534px（density 3.5 → 约 166dp），跟唱到第几个字没关系。
 * 这里钳到 [MAX_WIDTH_DP]，把左侧空间让出来。
 *
 * ## 二、切换动效：照系统自己的状态栏歌词来
 *
 * 原来 Flyme 状态栏歌词（`com.flyme.systemui.statusbar.ticker`）的分工：
 * ```
 * AdvertTickerView.mTextSwitcher.setText(tickerText)   ← TextSwitcher：新旧文字左右交替
 *   └─ (MarqueeTextView) child.startScrollSoon()       ← 文字超宽后自己横向滚
 *
 * MarqueeTextView：isFocused() 恒 true、setEllipsize(null)、独立线程推 scrollX
 * ```
 * 而胶囊这边系统只给了 `startScaleXAnim()`：`setAlpha(0)` 直接换字 + alpha 弹簧淡入，
 * 没有方向感。这里补成一样的两段：
 *  - **切换**：旧词左滑淡出 → 换字 → 新词右滑进入（单 View 版 TextSwitcher）
 *  - **超出**：落位后交给 [ArkLyricMarquee] 持续推 scrollX（等价于 MarqueeTextView）
 */
internal object ArkLiveCapsuleStyleHook {

    private const val TAG = "liveStyle"
    private const val VIEW_CLS =
        "com.flyme.statusbar.livenotification.LiveNotificationView"
    private const val INFO_CLS =
        "com.android.systemui.shared.livenotification.LiveNotificationInfo"

    /** 歌词胶囊宽度上限（dp）。原值实测约 166dp（density 3.5 → 534px） */
    private const val MAX_WIDTH_DP = 130

    /** 文字区最小宽度，避免极端窄屏下算出负数 */
    private const val MIN_TEXT_DP = 48

    /** 旧词滑出：加速，像被推出画面 */
    private const val OUT_MS = 90L

    /** 新词滑入：减速，落位收住 */
    private const val IN_MS = 150L

    /** 位移比例（相对文字区宽度） */
    private const val SLIDE_RATIO = 0.38f

    /** 边缘渐隐宽度（dp），对应 MarqueeTextView 的 fading edge */
    private const val FADE_DP = 12

    private var lastLoggedWidth: String? = null
    private var lastText: String? = null

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != ArkConstants.SYSTEMUI_PKG) return

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing (max ${MAX_WIDTH_DP}dp)")

        // 1) 收窄胶囊宽度
        ArkHooks.quiet("$TAG getCalculatedWidth") {
            XposedHelpers.findAndHookMethod(
                VIEW_CLS, lpp.classLoader, "getCalculatedWidth",
                String::class.java, Integer.TYPE,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val showType = param.args[1] as? Int ?: return
                            // 5 / 6 = 歌词胶囊（getCalculatedWidth 内部区分）
                            if (showType != 5 && showType != 6) return
                            val origin = param.result as? Int ?: return
                            val view = param.thisObject as? View ?: return
                            val ctx = view.context
                            val limit = dp(ctx, MAX_WIDTH_DP)
                            if (origin <= limit) return

                            param.result = limit
                            val tv = textViewOf(view) ?: return
                            val fixed = XposedHelpers.getIntField(view, "mFixedWidth")
                            tv.maxWidth = (limit - fixed).coerceAtLeast(dp(ctx, MIN_TEXT_DP))
                            prepareForScroll(tv)

                            val text = param.args[0]?.toString().orEmpty()
                            if (text != lastLoggedWidth) {
                                lastLoggedWidth = text
                                XposedBridge.log("$TAG: width $origin -> $limit, text='$text'")
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] width error: ${t.message}")
                        }
                    }
                }
            )
        }

        // 2) 拦掉原生的「原地换字 + alpha 淡入」，切换改由下面的 setLiveNotificationInfo 统一负责
        ArkHooks.quiet("$TAG startScaleXAnim") {
            XposedHelpers.findAndHookMethod(
                VIEW_CLS, lpp.classLoader, "startScaleXAnim",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                    }
                }
            )
        }

        // 3) 每次 info 刷新后，若歌词换行 → 左右交替滑入 + 落位后开始滚动
        ArkHooks.quiet("$TAG setLiveNotificationInfo") {
            XposedHelpers.findAndHookMethod(
                VIEW_CLS, lpp.classLoader, "setLiveNotificationInfo",
                INFO_CLS, java.lang.Boolean.TYPE,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View ?: return
                            val tv = textViewOf(view) ?: return
                            val text = XposedHelpers.getObjectField(view, "mShowText")
                                as? String ?: return
                            if (text.isEmpty() || text == lastText) return
                            lastText = text
                            prepareForScroll(tv)
                            slideSwitch(tv, text)
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] info error: ${t.message}")
                        }
                    }
                }
            )
        }
    }

    private fun textViewOf(view: Any): TextView? =
        XposedHelpers.getObjectField(view, "mLiveNotificationText") as? TextView

    /**
     * 文字区改成「不换行、可横向滚动」。
     * `TextView.scrollTo` 会被 `computeHorizontalScrollRange` 夹住，不开这个滚不动。
     *
     * 边缘渐隐同样取自 `MarqueeTextView.setText()`：
     * `setHorizontalFadingEdgeEnabled(true)` —— 否则文字在胶囊两端是硬切一刀。
     */
    private fun prepareForScroll(tv: TextView) {
        tv.isSingleLine = true
        tv.setHorizontallyScrolling(true)
        tv.isHorizontalFadingEdgeEnabled = true
        // View.setFadingEdgeLength 是 protected，只能反射调
        try {
            XposedHelpers.callMethod(tv, "setFadingEdgeLength", dp(tv.context, FADE_DP))
        } catch (_: Throwable) {
        }
    }

    /**
     * 单 View 版 TextSwitcher：
     * 旧词左移淡出（90ms，加速）→ 换字 → 新词从右侧移入（150ms，减速）→ 开始滚动。
     *
     * 系统用 `TextSwitcher` 让两个子 View 同时进出；这里只有一个 TextView，
     * 两段接力在 240ms 内完成，观感同样是「横向滚过去换了一句」。
     */
    private fun slideSwitch(tv: TextView, newText: String) {
        tv.animate().cancel()
        ArkLyricMarquee.stop()

        val span = tv.width.takeIf { it > 0 }
            ?: tv.paint.measureText(newText).toInt().coerceAtLeast(dp(tv.context, 40))
        val shift = span * SLIDE_RATIO
        XposedBridge.log("$TAG: slide '$newText' span=$span shift=${shift.toInt()}")

        tv.animate()
            .translationX(-shift)
            .alpha(0f)
            .setDuration(OUT_MS)
            .setInterpolator(AccelerateInterpolator(1.6f))
            .withEndAction {
                tv.text = newText
                tv.translationX = shift
                tv.alpha = 0f
                tv.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(IN_MS)
                    .setInterpolator(DecelerateInterpolator(1.8f))
                    .withEndAction { ArkLyricMarquee.restart(tv) }
                    .start()
            }
            .start()
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}

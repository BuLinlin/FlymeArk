package com.flymeark

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XposedBridge

/**
 * 卡片前景色 = 系统给时钟算出来的颜色。
 *
 * SystemUI 的时钟实现了 `DarkReceiver`：
 * `onDarkChanged(regions, darkIntensity, rootViewTint)` 里的 **rootViewTint**
 * 就是它最终赋给时钟文字的颜色。用户可以在主题里自定义时钟颜色，
 * 自己按壁纸明暗去猜一定对不上，所以这里直接读那个值（见 [onSystemTint]）。
 *
 * 兜底：万一 `onDarkChanged` 没被调用（第三方主题换了时钟实现），
 * 退回 [WallpaperManager.getWallpaperColors] 的 `HINT_SUPPORTS_DARK_TEXT` ——
 * 那也是 SystemUI 判定"该配黑字还是白字"的依据。
 *
 * 为什么要防抖：开合下拉栏时 `darkIntensity` 会从 0 渐变到 1，`rootViewTint`
 * 会在黑/白之间来回跳几次。每次跳都跟着改，卡片就会闪。所以最后 500ms 内
 * 没有新值才采纳，采纳后再通知卡片重刷。
 */
internal object ArkShadeTint {

    private const val TAG = "${ArkConstants.LOG_TAG}/tint"

    /** 颜色稳定这么久才采纳 */
    private const val DEBOUNCE_MS = 500L

    /** H:mm / HH:mm，允许中文冒号 */
    private val TIME = Regex("^\\d{1,2}[:：]\\d{2}$")

    /** 大时钟的字号下限（px）：下拉栏大时钟 ~112px，状态栏小钟 ~42px，卡片里的 00:20 ~36px */
    private const val CLOCK_MIN_PX = 60f

    private var cache = 0
    private var pending = 0
    private var posted = false
    private var lastLogged = 0

    /** 是否已经从「大时钟实际被赋的颜色」拿到过值（拿到后 onDarkChanged 兜底就不再生效） */
    private var clockSeen = false

    /** onDarkChanged 传来的兜底色，只在前两级都拿不到时使用 */
    private var fallback = 0

    private val handler = Handler(Looper.getMainLooper())

    private val adopt = Runnable {
        posted = false
        val c = pending
        if (c == 0 || c == cache) return@Runnable
        cache = c
        XposedBridge.log("[$TAG] tint = #${Integer.toHexString(c)}")
        ArkMediaCardStyleHook.refreshCards()
    }

    /**
     * 系统给某个 `HH:mm` 文本赋色时调用。
     *
     * 只认**大字号**的那个：下拉栏 header 的大时钟 ~112px，状态栏小钟 ~42px，
     * 卡片里的 "00:20" ~36px，靠字号就能区分。用户可以在主题里自定义时钟颜色，
     * 所以这里读的是它实际被赋的值，而不是按壁纸去猜。
     */
    fun noteClockColor(view: TextView): Boolean {
        val t = view.text?.toString() ?: return false
        if (!TIME.matches(t)) return false
        if (view.textSize < CLOCK_MIN_PX) return false

        val c = view.currentTextColor
        if (c == 0) return false
        if (c != lastLogged) {
            lastLogged = c
            XposedBridge.log("[$TAG] clock color = #${Integer.toHexString(c)} (${view.textSize}px)")
        }
        pending = c
        clockSeen = true
        if (!posted) {
            posted = true
            handler.postDelayed(adopt, DEBOUNCE_MS)
        }
        return true
    }

    /**
     * 兜底：`Clock.onDarkChanged(regions, darkIntensity, rootViewTint)` 的第三个参数。
     *
     * 只在大时钟赋色抓不到、壁纸信息也不可用时才生效。实测这个值是给**状态栏图标**的
     * （展开后恒为白），而下拉栏里的大时钟此时已经是黑色 —— 直接用它会出现
     * "浅色壁纸配白字"。保留它是因为换主题后时钟实现被替换时，这是唯一可用来源。
     */
    fun onDarkTint(tint: Int) {
        if (clockSeen || tint == 0) return
        fallback = tint
    }

    /**
     * @param view 任意一个挂在 SystemUI 窗口上的视图，用来拿 Context
     * @return 前景色；信息还没就绪时返回 0，调用方自行重试
     */
    fun fromView(view: View): Int {
        if (cache != 0) return cache

        if (fallback != 0) {
            cache = fallback
            XposedBridge.log("[$TAG] tint = #${Integer.toHexString(fallback)} (onDarkChanged)")
            return cache
        }

        val color = try {
            val wc = WallpaperManager.getInstance(view.context)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            if (wc == null) {
                0
            } else if ((wc.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0) {
                Color.BLACK
            } else {
                Color.WHITE
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] getWallpaperColors failed: $t")
            0
        }

        if (color == 0) return 0
        cache = color
        XposedBridge.log("[$TAG] tint = #${Integer.toHexString(color)} (wallpaper hints)")
        return color
    }
}

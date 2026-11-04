package com.flymeark

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * v3.3：胶囊歌词的超宽横向滚动。
 *
 * ## 为什么不直接用 TextView 自带的 MARQUEE
 *
 * 系统的 `LiveNotificationView.setLiveNotificationInfo` 每次刷新都会
 * `setEllipsize(null)` + `setSelected(false)`，把跑马灯状态清掉；
 * 而且宽度是弹簧动画在改，等我们把状态设回去时 `TextView` 的 `isShown()/layout`
 * 往往还没就绪，`startMarquee()` 直接失败 —— 实测就是**文字被硬裁、不滚**。
 *
 * ## 所以照 MarqueeTextView 的做法自己推
 *
 * 反编译 `com.flyme.systemui.statusbar.ticker.MarqueeTextView`：
 * ```
 * isFocused() 恒返回 true                      ← 内置 marquee 靠这个启动
 * setText() → setEllipsize(null) + 开边缘渐隐
 * onAttachedToWindow → 起一个 HandlerThread
 * startScrollSoon() → 延迟 1500ms 后开始推进 scrollX
 * ```
 * 这里只保留「延迟启动 + 周期推进 scrollX + 到头归零重来」这套节奏，
 * 线程改用主线程 Handler（一次只动一个 TextView，开销可以忽略）。
 *
 * `TextView` 的 `scrollTo` 会被 `computeHorizontalScrollRange` 夹住，
 * 所以调用方必须同时 `setHorizontallyScrolling(true)`，否则滚不动。
 */
internal object ArkLyricMarquee {

    private const val TAG = "FlymeArk/liveMarquee"

    /** 每帧推进量（dp）。原生量级约 1~2dp/帧，取 1.2 接近原生观感 */
    private const val STEP_DP = 1.2f

    private const val FRAME_MS = 16L

    /** 一行滚动完回到开头前停一下，避免「无缝循环」看着晕 */
    private const val RESTART_DELAY_MS = 1_400L

    /** 首字前停留。原生 startScrollSoon 是 1500ms，歌词一行只停 3~4 秒，这里收短到 800ms */
    private const val START_DELAY_MS = 800L

    /** 文字末尾留白，让最后一个字完全露出来再回头 */
    private const val TAIL_GAP_DP = 24

    private val handler = Handler(Looper.getMainLooper())
    private var ref: WeakReference<TextView>? = null
    private var offset = 0

    private val tick = object : Runnable {
        override fun run() {
            val tv = ref?.get()
            if (tv == null || !tv.isShown) {
                stop()
                return
            }
            val textW = tv.paint.measureText(tv.text?.toString().orEmpty()).toInt()
            val viewW = tv.width
            if (viewW <= 0 || textW <= viewW) {
                // 装得下就摆正，不用滚
                tv.scrollTo(0, 0)
                offset = 0
                return
            }

            val tail = (TAIL_GAP_DP * tv.resources.displayMetrics.density).toInt()
            val end = (textW - viewW + tail).coerceAtLeast(1)
            val step = (STEP_DP * tv.resources.displayMetrics.density).toInt().coerceAtLeast(1)

            offset += step
            if (offset >= end) {
                tv.scrollTo(0, 0)
                offset = 0
                handler.postDelayed(this, RESTART_DELAY_MS)
                return
            }
            tv.scrollTo(offset, 0)
            handler.postDelayed(this, FRAME_MS)
        }
    }

    /** 换词后重新开始：回到起点，停 [START_DELAY_MS] 再滚 */
    fun restart(tv: TextView) {
        handler.removeCallbacks(tick)
        ref = WeakReference(tv)
        offset = 0
        tv.scrollTo(0, 0)
        handler.postDelayed(tick, START_DELAY_MS)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        ref?.get()?.scrollTo(0, 0)
        ref = null
        offset = 0
    }
}

package com.flymeark

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * v3.15.61：导航展开卡的路线进度条（设计稿 nav.html `.prog`）。
 *
 * ## 口径：**时间**，不是距离
 *
 * 高德不给我们实时剩余距离（导航通知的 `android.text` 永远是占位值 `正在导航`），
 * 所以进度只能按时间口径算：`(总耗时 - 剩余) / 总耗时`。
 * v3.15.65 起剩余值由高德进程按**每次播报**重算（`ArkConstants.NAV_KEY_REMAIN_MS`），
 * 面板只在收到新载荷时更新一次、两帧之间冻结（见 `ArkNavPanelActivity.updateProgress`）——
 * 不再「按秒自己往前走」。拿不到剩余值时（[setProgress] 的 `available=false`）
 * 只画轨和终点白球，**不画球**——画个 0% 的球会让人以为刚出发。
 *
 * ## 为什么自绘
 *
 * 与 [ArkChipDrawable] 同一个原因：Flyme 深色模式会改写 `GradientDrawable` /
 * `ColorDrawable` 的填充色，而进度条的颜色必须和转向块、蓝点严格同源。
 * 自绘之后色值只来自 [ArkTokens]，深浅切换只需 [setPalette]。
 */
class ArkNavProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val endRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()

    /** 0..1；[hasProgress] 为 false 时无意义 */
    private var fraction = 0f

    /** 能不能算出进度（有出发时刻 + 有总耗时） */
    private var hasProgress = false

    init {
        trackPaint.color = ArkTokens.TRACK_NEUTRAL_LIGHT
        fillPaint.color = ArkTokens.BRAND_LIGHT
        thumbPaint.color = ArkTokens.BRAND_LIGHT
        thumbRingPaint.color = 0xFFFFFFFF.toInt()
        endRingPaint.color = ArkTokens.NAV_PROGRESS_END_RING_COLOR
        endPaint.color = 0xFFFFFFFF.toInt()
    }

    private fun dp(v: Number): Int = (v.toFloat() * density).toInt()

    private fun dpf(v: Number): Float = v.toFloat() * density

    /**
     * 按玻璃深浅下发配色（浅玻璃走浅色轨道，深玻璃走深色轨道）。
     *
     * @param accent      进度填充 + 移动球（品牌色，深浅两档）
     * @param track       未走完的轨
     * @param ringOnAccent 球外面的白环（深玻璃上仍取白，设计稿如此）
     */
    fun setPalette(accent: Int, track: Int, ringOnAccent: Int) {
        var changed = false
        if (fillPaint.color != accent) {
            fillPaint.color = accent
            thumbPaint.color = accent
            changed = true
        }
        if (trackPaint.color != track) {
            trackPaint.color = track
            changed = true
        }
        if (thumbRingPaint.color != ringOnAccent) {
            thumbRingPaint.color = ringOnAccent
            changed = true
        }
        if (changed) invalidate()
    }

    /**
     * @param fraction 0..1，超出范围会被钳制
     * @param available false = 算不出进度，只画轨 + 终点白球
     */
    fun setProgress(fraction: Float, available: Boolean) {
        val f = if (available) fraction.coerceIn(0f, 1f) else 0f
        if (f == this.fraction && available == this.hasProgress) return
        this.fraction = f
        this.hasProgress = available
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> MeasureSpec.getSize(widthMeasureSpec)
            else -> max(suggestedMinimumWidth, dp(200))
        }
        val fixedH = dp(ArkTokens.NAV_PROGRESS_HEIGHT_DP)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            fixedH
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val trackHalf = dpf(ArkTokens.NAV_PROGRESS_TRACK_DP) / 2f
        val left = dpf(ArkTokens.NAV_PROGRESS_H_INSET_DP)
        val right = width - dpf(ArkTokens.NAV_PROGRESS_H_INSET_DP)
        if (right <= left || cy <= 0f) return

        // ① 底轨
        rect.set(left, cy - trackHalf, right, cy + trackHalf)
        canvas.drawRoundRect(rect, trackHalf, trackHalf, trackPaint)

        // ② 终点白球（始终画：它是「目的地」的锚点，与进度无关）
        val endR = dpf(ArkTokens.NAV_PROGRESS_END_DP) / 2f
        val endRing = dpf(ArkTokens.NAV_PROGRESS_END_RING_DP)
        canvas.drawCircle(right, cy, endR + endRing, endRingPaint)
        canvas.drawCircle(right, cy, endR, endPaint)

        if (!hasProgress) return

        // ③ 已走完的段：右端至少留一个圆的宽度，否则 0% 会画成一条竖线
        val thumbR = dpf(ArkTokens.NAV_PROGRESS_THUMB_DP) / 2f
        val thumbRing = dpf(ArkTokens.NAV_PROGRESS_THUMB_RING_DP)
        val thumbCx = (left + (right - left) * fraction).coerceIn(left, right)
        val fillRight = max(thumbCx, left + trackHalf * 2f)
        rect.set(left, cy - trackHalf, fillRight, cy + trackHalf)
        canvas.drawRoundRect(rect, trackHalf, trackHalf, fillPaint)

        // ④ 移动球 + 白环（先画大白圆，再画品牌圆，得到「0 0 0 3dp 白环」的观感）
        canvas.drawCircle(thumbCx, cy, thumbR + thumbRing, thumbRingPaint)
        canvas.drawCircle(thumbCx, cy, thumbR, thumbPaint)
    }
}

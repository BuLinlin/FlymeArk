package com.flymeark

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 播放面板进度条。
 *
 * 不用系统 ProgressBar 是为了绕开各 ROM 主题对 minHeight / padding / tint 的差异，
 * 这里直接画两条圆角实心线。
 *
 * 触摸热区：布局高度给到 25dp（可按），进度线永远画在垂直中心、高 3dp。
 *
 * v3.12：[barColor] 跟专辑取色走（[ArkAlbumColor]），换色用 ArgbEvaluator 平滑过渡。
 * v3.13：对齐系统媒体卡 —— 支持**拖动 seek**（按住拖动、松手回调），新增
 * [trackColor]（深浅模式轨道色），拖动期间宿主应暂停秒表写入（见 [isScrubbing]）。
 */
class ArkProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF.toInt() }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF7A2F.toInt() }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF7A2F.toInt() }

    /** 拖拽圆点的白环（设计稿 02：13dp 圆点 + 3px 白环） */
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
    }

    /** 拖拽圆点投影（硬件加速下 setShadowLayer 不保证生效，直接用一层半透明圆近似） */
    private val thumbShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }

    /** 滑块圆点半径：设计稿 13dp 圆点 → 半径 6.5dp（v3.15.46） */
    private val thumbRadius =
        resources.displayMetrics.density * (ArkTokens.PROGRESS_THUMB_DP / 2f)

    /** 白环宽度：设计稿写 3px，按 3x 屏折算 1dp（v3.15.46） */
    private val thumbRingWidth =
        resources.displayMetrics.density * ArkTokens.PROGRESS_THUMB_RING_DP

    /** 进度线 / 轨道厚度：设计稿 ProgressHeight = 4dp（v3.15.46，原 3dp） */
    private val trackHeightPx =
        resources.displayMetrics.density * ArkTokens.PROGRESS_HEIGHT_DP

    private var colorAnim: ValueAnimator? = null

    /** 0f..1f */
    var ratio: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                invalidate()
            }
        }

    /** 拖动中。宿主秒表/推送在此期间不要覆盖进度显示 */
    var isScrubbing = false
        private set

    /** 归零动画进行中（切歌时），宿主推送不要覆盖 ratio */
    var isCollapsing = false

    /** 松手时回调，单位毫秒 */
    var onSeek: ((Int) -> Unit)? = null

    /** 拖动中实时回调（面板刷新时间显示），单位毫秒；durationMs <= 0 时传 -1 */
    var onScrub: ((Int) -> Unit)? = null

    /** 宿主当前时长（毫秒），用于把拖动比例换算成时间；<=0 时拖动无效 */
    var durationMs: Int = 0

    /** 进度条主题色，设置后 350ms 平滑过渡到新色（滑块同步） */
    var barColor: Int
        get() = barPaint.color
        set(value) {
            if (value == barPaint.color) return
            colorAnim?.cancel()
            val from = barPaint.color
            val evaluator = ArgbEvaluator()
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 350
                interpolator = DecelerateInterpolator()
                addUpdateListener { a ->
                    @Suppress("UNCHECKED_CAST")
                    val c = evaluator.evaluate(a.animatedValue as Float, from, value) as Int
                    barPaint.color = c
                    thumbPaint.color = c
                    invalidate()
                }
            }
            anim.start()
            colorAnim = anim
        }

    /** 轨道色（深浅模式切换用），无动画直接换 */
    var trackColor: Int
        get() = trackPaint.color
        set(value) {
            if (value != trackPaint.color) {
                trackPaint.color = value
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val h = trackHeightPx.coerceAtLeast(2f)
        val r = h / 2f
        canvas.drawRoundRect(0f, cy - h / 2, width.toFloat(), cy + h / 2, r, r, trackPaint)
        if (ratio > 0f) {
            canvas.drawRoundRect(0f, cy - h / 2, width * ratio, cy + h / 2, r, r, barPaint)
        }
        // 滑块圆点：13dp 圆点 + 3px 白环 + 一层很淡的投影（设计稿 02）
        val cx = width * ratio
        val shadowDy = resources.displayMetrics.density * 0.5f
        canvas.drawCircle(
            cx, cy + shadowDy, thumbRadius + resources.displayMetrics.density,
            thumbShadowPaint,
        )
        canvas.drawCircle(cx, cy, thumbRadius, thumbPaint)
        thumbRingPaint.strokeWidth = thumbRingWidth
        canvas.drawCircle(cx, cy, thumbRadius - thumbRingWidth / 2f, thumbRingPaint)
    }

    // ------------------------------------------------------------------
    // 拖动 seek（v3.13）
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0 || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isScrubbing) {
                    isScrubbing = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    scrubTo(event.x)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScrubbing) scrubTo(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isScrubbing) {
                    scrubTo(event.x)
                    isScrubbing = false
                    onSeek?.invoke((ratio * durationMs).toInt())
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun scrubTo(x: Float) {
        val r = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        if (r != ratio) {
            ratio = r
            onScrub?.invoke((r * durationMs).toInt())
        }
    }

    override fun onDetachedFromWindow() {
        colorAnim?.cancel()
        super.onDetachedFromWindow()
    }
}

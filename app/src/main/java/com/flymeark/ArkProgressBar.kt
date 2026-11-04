package com.flymeark

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 播放面板进度条。
 *
 * 不用系统 ProgressBar 是为了绕开各 ROM 主题对 minHeight / padding / tint 的差异，
 * 这里直接画两条圆角实心线，高度完全由 layout_height 决定（3dp）。
 *
 * v3.12：[barColor] 跟专辑取色走（[ArkAlbumColor]），换色用 ArgbEvaluator 平滑过渡，
 * 避免切歌瞬间颜色硬跳。
 */
class ArkProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF.toInt() }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF7A2F.toInt() }

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

    /** 进度条主题色，设置后 350ms 平滑过渡到新色 */
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
                    barPaint.color = evaluator.evaluate(a.animatedValue as Float, from, value) as Int
                    invalidate()
                }
            }
            anim.start()
            colorAnim = anim
        }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        if (h <= 0f) return
        val r = h / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, trackPaint)
        if (ratio > 0f) {
            canvas.drawRoundRect(0f, 0f, width * ratio, h, r, r, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        colorAnim?.cancel()
        super.onDetachedFromWindow()
    }
}

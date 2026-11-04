package com.flymeark

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * v2.6：播放面板进度条。
 *
 * 不用系统 ProgressBar 是为了绕开各 ROM 主题对 minHeight / padding / tint 的差异，
 * 这里直接画两条圆角实心线，高度完全由 layout_height 决定（3dp）。
 */
class ArkProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF.toInt() }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF7A2F.toInt() }

    /** 0f..1f */
    var ratio: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                invalidate()
            }
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
}

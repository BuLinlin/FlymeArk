package com.flymeark

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * v3.6：媒体卡的玻璃层 —— **只做透明度 + 轮廓高光**，不再采集下层纹理。
 *
 * 之前的做法（v3.5 及以前）是自己去 `WallpaperManager` 抓壁纸、逐级缩放、三次盒式模糊、
 * 再用 RuntimeShader 做折射 / 色散。实测下来两个硬伤：
 *
 *  1. **不同步**：抓的是静态壁纸，而卡片下方是 SurfaceFlinger 的实时模糊。
 *     两者不是同一帧内容，亮度、纹理都对不上，越"精细"越假。
 *  2. **卡顿**：每次开合都要同步解码 + 缩放 + 模糊，首帧直接掉帧。
 *
 * 现在的做法：系统给的 `WallpaperBlurDrawable` 本身就是实时窗口模糊，天然同步、零开销。
 * 我们只负责把它画得更透一点（[ALPHA]），再补一圈由上到下衰减的细描边，模拟玻璃厚度。
 * 全程只有一次 draw + 一条 1.2dp 的圆角描边，没有位图、没有 shader、没有采样。
 */
class ArkGlassDrawable(
    private val radius: Float,
    private val density: Float,
    private val content: Drawable
) : Drawable() {

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val rimRect = RectF()
    private var rimShader: Shader? = null
    private var rimHeight = -1f

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width()
        val h = b.height()
        if (w <= 0 || h <= 0) return

        // 系统的模糊底：整层按 ALPHA 合成，比改 drawable.alpha 靠谱
        // （BackgroundBlurDrawable 走的是 RenderNode，alpha 不一定会被读）
        val save = canvas.saveLayerAlpha(null, (ALPHA * 255).toInt())
        try {
            content.setBounds(b)
            content.draw(canvas)
        } finally {
            canvas.restoreToCount(save)
        }

        drawRim(canvas, w, h)
    }

    /** 由上到下衰减的细描边：顶部有一道亮边，底部几乎消失，不做硬边框 */
    private fun drawRim(canvas: Canvas, w: Int, h: Int) {
        val hf = h.toFloat()
        if (rimShader == null || rimHeight != hf) {
            rimShader = LinearGradient(
                0f, 0f, 0f, hf,
                intArrayOf(RIM_TOP, RIM_UPPER, RIM_LOWER, RIM_BOTTOM),
                floatArrayOf(0f, 0.28f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            rimPaint.shader = rimShader
            rimHeight = hf
        }
        rimRect.set(0f, 0f, w.toFloat(), hf)
        val inset = rimPaint.strokeWidth * 0.5f
        rimRect.inset(inset, inset)
        canvas.drawRoundRect(rimRect, radius - inset, radius - inset, rimPaint)
    }

    override fun getOutline(outline: Outline) {
        super.getOutline(outline)
        outline.setRoundRect(bounds, radius)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** 玻璃层整体不透明度：比原来更透一点，但还看得出是一块面板 */
        const val ALPHA = 0.86f

        /** 轮廓高光：顶部亮、两侧衰减、底部几乎不见 */
        const val RIM_TOP = 0x40FFFFFF
        const val RIM_UPPER = 0x1FFFFFFF
        const val RIM_LOWER = 0x0DFFFFFF
        const val RIM_BOTTOM = 0x05FFFFFF
    }
}

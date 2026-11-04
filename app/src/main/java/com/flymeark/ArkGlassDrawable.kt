package com.flymeark

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 媒体卡的玻璃层：**只负责背景**，前景色一律由系统决定。
 *
 * ## 为什么不自己采集纹理
 *
 * v3.5 及以前是自己去 `WallpaperManager` 抓壁纸、逐级缩放、三次盒式模糊，再用
 * RuntimeShader 做折射 / 色散。两个硬伤：
 *
 *  1. **不同步**：抓的是静态壁纸，卡片下方是 SurfaceFlinger 的实时模糊，
 *     不是同一帧内容，越"精细"越假；
 *  2. **卡顿**：每次开合都要同步解码 + 缩放 + 模糊，首帧直接掉帧。
 *
 * 现在系统给的 `WallpaperBlurDrawable` 本身就是实时窗口模糊，天然同步、零开销。
 * 我们只做两件事：调透明度（[ALPHA]）+ 补一圈由上到下衰减的细描边模拟玻璃厚度。
 *
 * ## 亮度安全窗
 *
 * 系统前景色来自**专辑封面**的 Monet 色板，而玻璃背景来自**壁纸**。两者不保证一致：
 * 封面亮 → 系统给黑字，但壁纸可能很暗 → 黑字糊掉；封面暗 → 白字配亮壁纸同理。
 *
 * 这里不改前景色（那是系统的活），而是反过来把**背景**拉进一个安全区间：
 *
 * | 系统前景 | 处理 | 效果 |
 * |---|---|---|
 * | 浅色（白字） | 整体压暗 [DARK_SCALE] | 背景明度 ≤ 0.55，白字有对比 |
 * | 深色（黑字） | 提亮到 [LIGHT_FLOOR] 起 | 背景明度 ≥ 0.36，黑字有对比 |
 *
 * 用 `ColorMatrix` 做线性映射而不是叠纯色遮罩：色相全部保留，只是明暗被规范，
 * 所以玻璃依然是"透的"，只是透出来的画面不会把文字吃掉。
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
    private val rimRect = android.graphics.RectF()
    private var rimShader: Shader? = null
    private var rimHeight = -1f

    /** 画背景那一层用：同时承载整体透明度和亮度映射 */
    private val layerPaint = Paint()

    private var toneFilter: ColorMatrixColorFilter? = null

    /** 上一次设定的前景色，用于去重（避免每帧重建 ColorMatrix） */
    private var toneKey = Int.MIN_VALUE

    /**
     * 按系统给的前景色调整背景明暗。
     *
     * @param color 系统实际使用的前景色（ARGB）；0 表示还没拿到，保持中性不做映射
     */
    fun setForegroundColor(color: Int) {
        if (color == toneKey) return
        toneKey = color

        toneFilter = when {
            color == 0 -> null
            isLightColor(color) -> linear(DARK_SCALE, 0f)   // 白字 → 压暗
            else -> linear(LIGHT_SCALE, LIGHT_OFFSET)       // 黑字 → 提亮
        }
        invalidateSelf()
    }

    /** 通道映射：out = in * scale + offset（offset 为 0~1 的比例） */
    private fun linear(scale: Float, offset: Float): ColorMatrixColorFilter {
        val m = ColorMatrix()
        m.setScale(scale, scale, scale, 1f)
        if (offset != 0f) {
            val s = offset * 255f
            val a = m.array
            a[4] = s
            a[9] = s
            a[14] = s
        }
        return ColorMatrixColorFilter(m)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width()
        val h = b.height()
        if (w <= 0 || h <= 0) return

        // 系统模糊底：整层按 ALPHA 合成，顺带在这里叠加亮度映射。
        // 用 saveLayer 而不是改 drawable.alpha —— BackgroundBlurDrawable 走 RenderNode，
        // alpha / colorFilter 只有落在 layer paint 上才会被真正读到。
        layerPaint.alpha = (ALPHA * 255).toInt()
        layerPaint.colorFilter = toneFilter
        val save = canvas.saveLayer(
            b.left.toFloat(),
            b.top.toFloat(),
            b.right.toFloat(),
            b.bottom.toFloat(),
            layerPaint
        )
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

        /** 白字场景：整体压暗，明度 1.0 → 0.58，暗部基本保持 */
        const val DARK_SCALE = 0.58f

        /** 黑字场景：提亮，明度 [0,1] → [0.36, 0.98] */
        const val LIGHT_SCALE = 0.62f
        const val LIGHT_OFFSET = 0.36f

        /** 轮廓高光：顶部亮、两侧衰减、底部几乎不见 */
        const val RIM_TOP = 0x40FFFFFF
        const val RIM_UPPER = 0x1FFFFFFF
        const val RIM_LOWER = 0x0DFFFFFF
        const val RIM_BOTTOM = 0x05FFFFFF
    }
}

/** 颜色明度判断：> 0.55 视为浅色（白字），否则深色（黑字） */
internal fun isLightColor(color: Int): Boolean {
    val lum =
        (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0
    return lum > 0.55
}

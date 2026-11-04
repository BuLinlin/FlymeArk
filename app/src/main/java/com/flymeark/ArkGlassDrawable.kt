package com.flymeark

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 媒体卡的玻璃层：**只画背景**，前景色由 [ArkForegroundStandard] 统一强制。
 *
 * ## 为什么不自己采集纹理
 *
 * 早期版本自己去 `WallpaperManager` 抓壁纸、缩放、盒式模糊、RuntimeShader 折射。两个硬伤：
 *
 *  1. **不同步**：抓的是静态壁纸，卡片下方是 SurfaceFlinger 的实时模糊，不是同一帧内容；
 *  2. **卡顿**：每次开合都要同步解码 + 缩放 + 模糊，首帧直接掉帧。
 *
 * 现在系统给的 `BackgroundBlurDrawable` 本身就是实时窗口模糊，天然同步、零开销。
 * 我们只做三件事：调透明度（[ALPHA]）、铺一层染色（scrim）、补一圈由上到下衰减的细描边。
 *
 * ## 染色为什么是 scrim 而不是 ColorFilter
 *
 * 曾经用 `canvas.saveLayer(bounds, paint)` 把 `ColorMatrixColorFilter` 挂在图层上做提亮/压暗，
 * 想保住模糊的色相纹理。但系统模糊底走的是 RenderNode，layer paint 上的 colorFilter 会不会
 * 被 RenderNode 播放时读到，属于实现细节，实测不可预期。
 *
 * 改成 scrim（半透明纯色盖在模糊层上）：
 *
 *  - 由我们自己的 Canvas 画，**结果 100% 确定**；
 *  - 这正是真实玻璃的做法 —— 磨砂玻璃本身就是「模糊 + 染色」两层；
 *  - 一层 scrim 同时解决对比度与冷暖，不需要知道壁纸是什么颜色。
 *
 * 强度按 [ArkForegroundStandard] 的标准走：黑字盖白，白字盖黑。
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

    /** 画模糊底那一层：只承载整体透明度 */
    private val layerPaint = Paint()

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 当前 scrim 颜色；0 表示还没定标准，先不染 */
    private var scrim = 0

    /** 上次设定的深浅色（-1 未知 / 0 浅色 / 1 深色），用于去重 */
    private var night = -1

    /**
     * 按标准定玻璃明暗。
     *
     * @param isNight 系统是否深色模式（= 前景是白字），背景相应压暗
     */
    fun setNight(isNight: Boolean) {
        val v = if (isNight) 1 else 0
        if (night == v) return
        night = v
        scrim = if (isNight) SCRIM_NIGHT else SCRIM_DAY
        rimShader = null // 描边色随深浅变，必须重建
        rimHeight = -1f
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width()
        val h = b.height()
        if (w <= 0 || h <= 0) return

        // 系统模糊底：整层按 ALPHA 合成。alpha 只有落在 layer paint 上才会被 RenderNode 读到
        layerPaint.alpha = (ALPHA * 255).toInt()
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

        // 染色层：盖在模糊之上、文字之下（背景 Drawable 先于子 View 绘制）
        if (scrim != 0) {
            scrimPaint.color = scrim
            canvas.drawRoundRect(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                radius, radius, scrimPaint
            )
        }

        drawRim(canvas, w, h)
    }

    /** 由上到下衰减的细描边：顶部有一道亮边，底部几乎消失，不做硬边框 */
    private fun drawRim(canvas: Canvas, w: Int, h: Int) {
        val hf = h.toFloat()
        val dark = night != 0
        val colors = if (dark) RIM_DARK else RIM_LIGHT
        if (rimShader == null || rimHeight != hf) {
            rimShader = LinearGradient(
                0f, 0f, 0f, hf,
                colors,
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
        /** 玻璃层整体不透明度：留一丝底层透出，才像玻璃 */
        const val ALPHA = 0.86f

        /** 深色模式（白字）：压一层黑，把背景明度压到白字够用的区间 */
        const val SCRIM_NIGHT = 0x73000000

        /** 浅色模式（黑字）：铺一层白，把背景托起来 */
        const val SCRIM_DAY = 0x57FFFFFF

        /** 轮廓高光：深色玻璃用白色描边，浅色玻璃用极淡的深色（白描边在白底上等于没有） */
        private val RIM_DARK = intArrayOf(0x40FFFFFF, 0x1FFFFFFF, 0x0DFFFFFF, 0x05FFFFFF)
        private val RIM_LIGHT = intArrayOf(0x26000000, 0x12000000, 0x08000000, 0x03000000)
    }
}

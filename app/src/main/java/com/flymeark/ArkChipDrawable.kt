package com.flymeark

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * v3.15.46：面板内的圆角 chip 底板（通知卡底部 CTA、音乐卡播放主按钮共用）。
 *
 * ## 为什么不用 GradientDrawable / 形状资源 drawable
 *
 * 真机取证（Flyme / 481QHGFN226P5，系统深色模式开启）：`GradientDrawable` /
 * `ColorDrawable` 的**填充色会被系统深色模式改写**——浅色（如 `#FFE7EEFF`）被反色成
 * `#FF171102`，`setBackgroundTintList()` 覆写品牌色也不生效；而自绘 Drawable 的
 * 填充只被轻微压暗（实测 `#2F6BFF` → `(38,86,204)`），颜色语义可控。
 *
 * 因此 CTA 底板与播放主按钮的品牌圆都由本类绘制：色值/半径全部由 [ArkTokens] 传入，
 * 深浅切换只需 [setFill]。已知边界：深色模式下 CTA 的浅色实心底板仍会被系统反色
 * （见 docs/changelog.md v3.15.46「取舍」），本轮以对齐设计稿为先。
 */
internal class ArkChipDrawable(
    fillColor: Int,
    radiusPx: Float,
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var fill: Int = fillColor

    /** drawable 自身 alpha（0..255），与各颜色的 alpha 相乘后生效 */
    private var drawAlpha: Int = 255

    /** 圆角半径（px），绘制时再按短边一半钳制 */
    var radius: Float = radiusPx
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    /**
     * v3.15.50：**全圆角（胶囊）模式**。
     *
     * 置 true 后每次 bounds 变化都按「短边一半」重算 [radius]，用于「刚刚」那颗时间胶囊
     * （设计稿要求药丸形，不能用固定的 10dp chip 圆角）。默认 false，
     * CTA 底板仍然走构造时传入的固定半径，行为完全不变。
     */
    var pill: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) applyPill(bounds)
            invalidateSelf()
        }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (pill) applyPill(bounds)
    }

    private fun applyPill(bounds: Rect) {
        if (bounds.isEmpty) return
        radius = minOf(bounds.width(), bounds.height()) / 2f
    }

    /** 换填充色（`Color.TRANSPARENT` = 不填） */
    fun setFill(color: Int) {
        if (color == fill) return
        fill = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        rect.set(bounds)
        val r = radius.coerceIn(0f, minOf(rect.width(), rect.height()) / 2f)
        if (Color.alpha(fill) == 0) return
        fillPaint.color = fill
        fillPaint.alpha = scaledAlpha(fill)
        canvas.drawRoundRect(rect, r, r, fillPaint)
    }

    private fun scaledAlpha(color: Int): Int =
        ((Color.alpha(color) * drawAlpha) / 255).coerceIn(0, 255)

    override fun setAlpha(alpha: Int) {
        val a = alpha.coerceIn(0, 255)
        if (a == drawAlpha) return
        drawAlpha = a
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

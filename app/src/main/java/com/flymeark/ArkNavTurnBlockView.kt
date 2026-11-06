package com.flymeark

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * v3.15.61：导航展开卡的转向块（设计稿 nav.html `.turn`）。
 *
 * ## 形态
 *
 * 52×52dp、16dp 圆角、`linear-gradient(160deg, #3D77FF混白 → #2F6BFF → #2455D6混黑)`，
 * 中央 27dp 白色人字箭头（[ic_ark_turn_left] 等 5 张矢量图）。
 *
 * ## 为什么是 FrameLayout 而不是 ImageView + shape drawable
 *
 * 背景渐变必须自绘（Flyme 深色模式会反色 `GradientDrawable` 的填充，见
 * [ArkChipDrawable] 的取证），而箭头又要按转向类型换图 —— 所以「自绘背景 + 一个
 * 子 ImageView」是最省事的组合：背景只画一次，箭头换图不影响渐变。
 *
 * ## 阴影
 *
 * 设计稿是 `box-shadow: 0 6px 16px accent@30%, inset 0 1px 0 #fff@34%`。
 * 外阴影交给系统 `elevation = 6dp` + 圆角 outline（比 `Paint.setShadowLayer`
 * 在硬件层上更稳定），内高光在 [onDraw] 里画一条 34% 白的 1dp 顶线。
 */
class ArkNavTurnBlockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val iconView = ImageView(context)
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var turnCode: Int = ArkConstants.NAV_TURN_STRAIGHT

    init {
        setWillNotDraw(false)

        iconView.scaleType = ImageView.ScaleType.FIT_CENTER
        iconView.isClickable = false
        iconView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(
            iconView,
            LayoutParams(
                dp(ArkTokens.NAV_TURN_ICON_DP),
                dp(ArkTokens.NAV_TURN_ICON_DP),
                Gravity.CENTER
            )
        )

        highlightPaint.color = 0x57FFFFFF
        highlightPaint.style = Paint.Style.STROKE
        highlightPaint.strokeWidth = dp(1).toFloat()
        highlightPaint.strokeCap = Paint.Cap.ROUND

        // 设计稿 0 6px 16px：elevation 就是「离屏高度」，系统按它生成模糊半径
        elevation = dp(6).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0, 0, view.width, view.height, dpf(ArkTokens.RADIUS_TURN_DP)
                )
            }
        }

        setTurn(ArkConstants.NAV_TURN_STRAIGHT)
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun dpf(v: Number): Float = v.toFloat() * density

    /**
     * 切换转向类型并换箭头图。
     *
     * @param code 取值见 `ArkConstants.NAV_TURN_*`；未知值按直行处理
     */
    fun setTurn(code: Int) {
        turnCode = code
        val (res, desc) = when (code) {
            ArkConstants.NAV_TURN_LEFT -> R.drawable.ic_ark_turn_left to R.string.ark_nav_turn_left
            ArkConstants.NAV_TURN_RIGHT -> R.drawable.ic_ark_turn_right to R.string.ark_nav_turn_right
            ArkConstants.NAV_TURN_UTURN -> R.drawable.ic_ark_turn_uturn to R.string.ark_nav_turn_uturn
            ArkConstants.NAV_TURN_ARRIVE -> R.drawable.ic_ark_turn_arrive to R.string.ark_nav_turn_arrive
            else -> R.drawable.ic_ark_turn_straight to R.string.ark_nav_turn_straight
        }
        iconView.setImageResource(res)
        contentDescription = context.getString(desc)
    }

    /** 当前转向类型（测试 / 日志用） */
    fun getTurn(): Int = turnCode

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 160deg 在 CSS 里是「近乎垂直、略往左下」，原生按垂直近似（可接受的取舍，
        // 见 docs/changelog.md）；三段色停 0% / 60% / 100% 与设计稿一致。
        surfacePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(ArkTokens.TURN_TOP, ArkTokens.TURN_MID, ArkTokens.TURN_BOTTOM),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = dpf(ArkTokens.RADIUS_TURN_DP)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, surfacePaint)

        // inset 0 1px 0 rgba(255,255,255,.34)：顶部 1dp 高光，两端收在圆角之内
        val y = dp(1).toFloat()
        canvas.drawLine(r, y, w - r, y, highlightPaint)
    }
}

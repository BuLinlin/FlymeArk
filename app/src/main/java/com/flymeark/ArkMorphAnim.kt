package com.flymeark

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout

/**
 * v3.2：面板的「胶囊长大成卡片」动画。
 *
 * ## 为什么要重做
 *
 * v3.1 的做法是：胶囊瞬间 alpha=0，同时卡片从上方 -55% 位移滑入。两个动作没有关联，
 * 观感是「胶囊闪一下没了 → 上面掉下来一张卡片」，和系统自己的胶囊动效不是一回事。
 *
 * ## 系统怎么做的（反编译 `LiveNotificationView` 得到）
 *
 * Flyme 的胶囊是两段式节奏：
 * ```
 * ① widthSpringAnimation : SpringForce(stiffness=180, dampingRatio=0.76)   ← 容器先变宽
 * ② alphaSpringAnimation : SpringForce(stiffness=240, dampingRatio=0.75)   ← 文字后淡入
 * ```
 * 也就是「**先变形、后出内容**」，而且带一点回弹（damping < 1 是欠阻尼）。
 *
 * ## 本类的做法
 *
 * 沿用同一个节奏，只不过把「变宽」换成「从胶囊矩形长到卡片矩形」：
 * - 120×32 的胶囊矩形 → 卡片矩形，位置/宽高/圆角同一条曲线插值
 * - 内容层宽高固定成最终尺寸（不重排、不换行抖动），只做 alpha
 * - 容器走到 40% 时内容才开始浮现，与系统「先变形容器、再淡入文字」一致
 * - 曲线用 [OvershootInterpolator] 近似 dampingRatio≈0.76 的轻微过冲
 */
internal class ArkMorphAnim(
    private val root: View,
    private val card: View,
    private val content: View,
    /** v3.15.42：液态玻璃参数（着色浓度由背景采样决定，不再硬编码底色） */
    private var glassParams: GlassParams
) {

    private val density = root.resources.displayMetrics.density

    private val finalRect = Rect()
    private val startRect = Rect()
    private var finalRadius = 0f
    private var startRadius = 0f

    /** 卡片背景：液态玻璃 Drawable，圆角随动画逐帧变化 */
    private lateinit var bg: ArkLiquidGlassDrawable

    private var animator: ValueAnimator? = null
    private var prepared = false

    private fun dp(v: Int): Int = (v * density).toInt()

    /**
     * 计算起始/结束矩形。
     *
     * @param capsule 胶囊在**屏幕**坐标系下的矩形，来自 SystemUI；为 null 时用兜底尺寸
     */
    fun prepare(capsule: Rect?) {
        if (prepared) return
        prepared = true

        val rootW = root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
        val rootH = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels

        val margin = dp(10)
        val cardW = (rootW - margin * 2).coerceAtLeast(dp(200))

        // 内容按最终宽度量一次，拿到固定高度（之后动画期间不再重排）
        content.measure(
            View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val contentH = content.measuredHeight
            .coerceAtLeast(dp(80))
            .coerceAtMost(rootH - dp(140))

        val clp = content.layoutParams as FrameLayout.LayoutParams
        clp.width = cardW
        clp.height = contentH
        clp.gravity = Gravity.TOP or Gravity.START
        clp.leftMargin = 0
        clp.topMargin = 0
        content.layoutParams = clp

        // 窗口内容区在屏幕上的原点：Activity 窗口是否延伸到状态栏下因 ROM 而异，
        // 用 root 自己的屏幕位置校准，两种定位都能对齐。
        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)

        val topOnScreen = statusBarHeight(root.resources) + dp(4)
        val left = margin
        val top = topOnScreen - rootLoc[1]
        finalRect.set(left, top, left + cardW, top + contentH)

        if (capsule != null && capsule.width() > 0 && capsule.height() > 0) {
            startRect.set(
                capsule.left - rootLoc[0],
                capsule.top - rootLoc[1],
                capsule.left - rootLoc[0] + capsule.width(),
                capsule.top - rootLoc[1] + capsule.height()
            )
        } else {
            // 兜底：假设胶囊在状态栏左侧、高约 32dp
            startRect.set(left, top, left + dp(120), top + dp(32))
        }
        // 起点不得超过终点（否则会先缩小再变大）
        if (startRect.width() > finalRect.width()) startRect.right = startRect.left + finalRect.width()
        if (startRect.height() > finalRect.height()) startRect.bottom = startRect.top + finalRect.height()

        startRadius = minOf(startRect.height() / 2f, dp(18).toFloat())
        finalRadius = dp(30).toFloat()

        // v3.15.42：attach 会装上 6 层液态玻璃背景 + elevation 投影 + 圆角 outline。
        // XML 的 bg_ark_panel 只是首帧占位，morph.prepare 之后一律由玻璃 Drawable 接管。
        bg = ArkLiquidGlass.attach(card, glassParams, finalRadius)

        applyRect(startRect, startRadius, 0f)
    }

    /** 深浅模式 / 采样浓度变化（主题切换）时由 Activity 调用，动画中/后都生效 */
    fun setGlass(p: GlassParams) {
        glassParams = p
        if (prepared) {
            bg.setGlass(p)
            card.invalidateOutline()
        }
    }

    /** 展开：胶囊矩形 → 卡片矩形，内容在后半段浮现 */
    fun expand() {
        prepare(null)
        animator?.cancel()
        content.alpha = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = EXPAND_MS
            interpolator = OvershootInterpolator(0.85f)
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            start()
        }
    }

    /** 收起：内容先退场，再把卡片缩回胶囊位置 */
    fun collapse(onEnd: () -> Unit) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = COLLAPSE_MS
            interpolator = DecelerateInterpolator(1.7f)
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onEnd()
                }
            })
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    private fun applyProgress(t: Float) {
        val left = lerp(startRect.left, finalRect.left, t)
        val top = lerp(startRect.top, finalRect.top, t)
        val right = lerp(startRect.right, finalRect.right, t)
        val bottom = lerp(startRect.bottom, finalRect.bottom, t)
        val radius = startRadius + (finalRadius - startRadius) * t
        // 40% 之后内容才出现：对应系统「宽度先弹、文字后淡入」的两段式
        val alpha = ((t - 0.40f) / 0.55f).coerceIn(0f, 1f)
        applyRect(Rect(left, top, right, bottom), radius, alpha)
    }

    private fun applyRect(r: Rect, radius: Float, contentAlpha: Float) {
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = r.left
        lp.topMargin = r.top
        lp.rightMargin = 0
        lp.bottomMargin = 0
        lp.width = r.width().coerceAtLeast(0)
        lp.height = r.height().coerceAtLeast(0)
        card.layoutParams = lp
        bg.cornerRadius = radius
        // outline 决定投影形状，圆角变了必须让系统重新取一次
        card.invalidateOutline()
        content.alpha = contentAlpha
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt()

    private fun statusBarHeight(res: Resources): Int {
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else dp(30)
    }

    private companion object {
        /** 与原生的 320ms 量级一致，略长一点让位置变化看清楚 */
        const val EXPAND_MS = 300L

        /** 收起比展开快，符合「展开慢、收起快」的通用动效节奏 */
        const val COLLAPSE_MS = 230L
    }
}

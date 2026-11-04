package com.flymeark

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/**
 * 下拉栏媒体卡：**只接管背景**，前景色完全由系统决定。
 *
 * ## 系统本来就有标准（所以不要去覆盖）
 *
 * ```
 * Icon artwork                                    ← 专辑封面（不是壁纸）
 *   → WallpaperColors.fromDrawable(artwork)        ← MediaControlPanel.getWallpaperColor()
 *   → new ColorScheme(colors, false, 6)            ← Monet 生成调和色板
 *   → MediaColorSchemesKt.textPrimaryFromScheme()   ← neutral1.s50
 *     textSecondaryFromScheme()                     ← neutral2.s200
 *     textTertiaryFromScheme()                      ← neutral2.s400
 *     accentPrimary / accentSecondary / primary / onPrimary ...
 *   → AnimatingColorTransition（333ms ValueAnimator）
 *   → setPlayerTextColor / setPlayerButtonStyle / setProgressBarStyle
 * ```
 *
 * 换歌、换壁纸、改主题，颜色都会自己跟着走，还带过渡动画。
 * 之前那套「读时钟色 → 覆盖整卡 → 拦截 setTextColor 别被刷回去 → 换剪影图标 →
 * 绘制前再校正」，是在覆盖系统已经算好的结果，遇到的问题全是自己造的，已整体移除。
 *
 * ## 现在只做两件事
 *
 * 1. **背景**：把系统给的实时模糊底包一层 [ArkGlassDrawable]；
 * 2. **让背景跟上系统前景色的明暗** —— 见下。
 *
 * 系统前景色来自**专辑封面**，玻璃背景来自**壁纸**，两者不保证一致。
 * 所以我们不碰前景色，而是反过来把背景拉进安全区间：
 * 读到系统要用白字就把玻璃压暗，要用黑字就把玻璃提亮（[ArkGlassDrawable.setForegroundColor]）。
 *
 * 这个前景色只是**读**（`ColorSchemeTransition.textPrimary.targetColor`，
 * 系统最终值，不受 333ms 动画影响），一个字都不改。
 */
object ArkMediaCardStyleHook {

    private const val TAG = "${ArkConstants.LOG_TAG}/mediaCard"

    private const val CARD_ROOT =
        "com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout"
    private const val MEDIA_VIEW_HOLDER =
        "com.android.systemui.media.controls.ui.view.MediaViewHolder"
    private const val MEDIA_CONTROL_PANEL =
        "com.android.systemui.media.controls.ui.controller.MediaControlPanel"

    /** 播放键圆盘描边宽度 */
    private const val STROKE_DP = 1.2f

    /** 卡片圆角兜底（读不到 mClipCornerRadius 时用），与 qs_media_player_radius 一致 */
    private const val FALLBACK_RADIUS_DP = 24f

    /** 播放键底盘：按系统前景明暗给两套，保证在对应背景上可见 */
    private class PlayStyle(val drawable: RippleDrawable, val light: Boolean)

    private val playGlass = WeakHashMap<View, PlayStyle>()

    /** 上一次读到的系统前景色。create 时 panel 还没建好，用这个兜底 */
    private var lastFg = 0

    fun install(lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam) {
        // 1) 视图创建：把卡片背景换成玻璃
        ArkHooks.quiet("mediaCard.create") {
            XposedHelpers.findAndHookMethod(
                "$MEDIA_VIEW_HOLDER\$Companion",
                lpp.classLoader,
                "create",
                android.view.LayoutInflater::class.java,
                android.view.ViewGroup::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val holder = param.result ?: return
                        style(holder, null)
                    }
                }
            )
            XposedBridge.log("[$TAG] installing (glass only, 前景色交给系统)")
        }

        // 2) 每次绑定之后补一次：holder 会被复用，背景可能被重设
        ArkHooks.quiet("mediaCard.panel") {
            val clazz = XposedHelpers.findClass(MEDIA_CONTROL_PANEL, lpp.classLoader)
            listOf("setPlayerTextColor", "bindPlayer", "setPlayerButtonStyle").forEach { m ->
                ArkHooks.quiet("mediaCard.$m") {
                    XposedBridge.hookAllMethods(clazz, m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            styleOf(param.thisObject)
                        }
                    })
                }
            }
        }

        // 3) 背景保活：系统在 attach / alpha 变化时会重设背景，这里顶掉
        ArkHooks.quiet("mediaCard.setBackground") {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setBackground",
                Drawable::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return

                        // 播放键的玻璃圆盘：系统在 bind 里会重设 background
                        val mine = playGlass[v]
                        if (mine != null) {
                            if (param.args[0] !== mine.drawable) param.args[0] = mine.drawable
                            return
                        }

                        if (v.javaClass.name != CARD_ROOT) return
                        val raw = param.args[0] as? Drawable ?: return
                        val glass = glassOf(v, raw) ?: return
                        glass.setForegroundColor(lastFg)
                        param.args[0] = glass
                    }
                }
            )
        }
    }

    // ---------------------------------------------------------------
    // 样式
    // ---------------------------------------------------------------

    private fun style(holder: Any, panel: Any?) {
        try {
            val fg = systemForeground(panel).let { if (it != 0) it else lastFg }
            if (fg != 0 && fg != lastFg) {
                lastFg = fg
                XposedBridge.log(
                    "[$TAG] system foreground = #${Integer.toHexString(fg)} " +
                        if (isLightColor(fg)) "(白字 → 玻璃压暗)" else "(黑字 → 玻璃提亮)"
                )
            }

            (field(holder, "player") as? View)?.let { player ->
                val raw = player.background ?: return@let
                val glass = glassOf(player, raw) ?: return@let
                glass.setForegroundColor(fg)
                if (raw !== glass) player.background = glass
            }

            (field(holder, "actionPlayPause") as? ImageView)?.let { ensurePlayGlass(it, fg) }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] style failed: $t")
        }
    }

    private fun styleOf(panel: Any?) {
        if (panel == null) return
        val holder = try {
            XposedHelpers.getObjectField(panel, "mMediaViewHolder")
        } catch (_: Throwable) {
            null
        } ?: return
        style(holder, panel)
    }

    /**
     * 读系统实际用在卡片上的前景色。
     *
     * 实测 `AnimatingColorTransition` **没有任何更新 `targetColor` 的方法** ——
     * 构造时把 `sourceColor` / `currentColor` / `targetColor` 都设成默认色，之后再也不动，
     * `valueAnimator` 也没人 start。也就是说那套 Monet 色板动画在这台机器上是**静止的**。
     *
     * 真正决定颜色的是 `MediaControlPanel.setPlayerTextColor()`（**无参**）：
     * 它把标题设成 `R.color.qs_mediapalyer_titleText_color` 这个主题资源色，
     * 深色主题给白、浅色主题给黑。所以直接读标题当前颜色就是系统真正在用的值，
     * 而且 `setPlayerTextColor` 之后读是同步生效的。
     *
     * 读不到时退回 `ColorSchemeTransition.textPrimary.targetColor`（等于同一个默认色）。
     */
    private fun systemForeground(panel: Any?): Int {
        if (panel == null) return 0

        try {
            val holder = XposedHelpers.getObjectField(panel, "mMediaViewHolder")
            val t = field(holder, "titleText") as? TextView
            if (t != null && t.currentTextColor != 0) return t.currentTextColor
        } catch (_: Throwable) {
        }

        return try {
            val cst = XposedHelpers.getObjectField(panel, "mColorSchemeTransition") ?: return 0
            val tp = XposedHelpers.getObjectField(cst, "textPrimary") ?: return 0
            XposedHelpers.getIntField(tp, "targetColor")
        } catch (_: Throwable) {
            0
        }
    }

    // ---------------------------------------------------------------
    // 背景
    // ---------------------------------------------------------------

    /** 包成玻璃层；已经是玻璃的直接返回（避免嵌套） */
    private fun glassOf(v: View, base: Drawable): ArkGlassDrawable? {
        (base as? ArkGlassDrawable)?.let { return it }
        return try {
            val ctx = v.context
            ArkGlassDrawable(
                radius = radiusOf(v, ctx),
                density = ctx.resources.displayMetrics.density,
                content = base
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] wrap failed: $t")
            null
        }
    }

    /** 卡片圆角：优先用系统自己的 mClipCornerRadius，保证和裁剪轮廓一致 */
    private fun radiusOf(v: View, ctx: Context): Float = try {
        val f = XposedHelpers.getFloatField(v, "mClipCornerRadius")
        if (f > 0f) f else dp(ctx, FALLBACK_RADIUS_DP).toFloat()
    } catch (_: Throwable) {
        dp(ctx, FALLBACK_RADIUS_DP).toFloat()
    }

    // ---------------------------------------------------------------
    // 播放键圆盘
    // ---------------------------------------------------------------

    /**
     * 播放键的玻璃圆盘。
     *
     * 明暗跟着系统前景色走：前景浅（深背景）用亮白圆盘，前景深（浅背景）用淡黑圆盘，
     * 保证两种情况都有对比。
     */
    private fun ensurePlayGlass(play: ImageView, fg: Int) {
        val base = if (fg != 0) fg else (play.imageTintList?.defaultColor ?: 0)
        val light = base == 0 || isLightColor(base)

        val made = playGlass[play]
        if (made != null && made.light == light && play.background === made.drawable) return

        val ctx = play.context
        val body = if (light) 0x40FFFFFF else 0x14000000
        val edge = if (light) 0x66FFFFFF else 0x2E000000
        val press = if (light) 0x33FFFFFF else 0x1F000000

        val disc = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(body)
            setStroke(dp(ctx, STROKE_DP), edge)
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(press), disc, null)

        playGlass[play] = PlayStyle(ripple, light)
        play.background = ripple
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    /** 先按字段取，取不到再试 getter（Kotlin 的 val 编译成 get 方法） */
    private fun field(o: Any, name: String): Any? {
        try {
            return XposedHelpers.getObjectField(o, name)
        } catch (_: Throwable) {
        }
        return try {
            XposedHelpers.callMethod(o, "get" + name.replaceFirstChar { it.uppercaseChar() })
        } catch (_: Throwable) {
            null
        }
    }

    private fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()
}

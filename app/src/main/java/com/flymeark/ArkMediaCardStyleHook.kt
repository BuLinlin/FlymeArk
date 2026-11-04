package com.flymeark

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/**
 * 下拉栏媒体卡：**只接管背景**，前景色完全交还给系统。
 *
 * ## 为什么不需要自己算颜色
 *
 * 系统本来就有完整的一套标准，链路是：
 *
 * ```
 * Icon artwork                                   ← 专辑封面（不是壁纸）
 *   → WallpaperColors.fromDrawable(artwork)       ← MediaControlPanel.getWallpaperColor()
 *   → new ColorScheme(colors, false, 6)           ← Monet 生成调和色板
 *   → MediaColorSchemesKt.textPrimaryFromScheme()  ← neutral1.s50
 *     textSecondaryFromScheme()                    ← neutral2.s200
 *     textTertiaryFromScheme()                     ← neutral2.s400
 *     accentPrimary / accentSecondary / primary / onPrimary ...
 *   → AnimatingColorTransition（333ms ValueAnimator）
 *   → setPlayerTextColor / setPlayerButtonStyle / setProgressBarStyle
 * ```
 *
 * 所以换歌、换壁纸、改主题，颜色都会自己跟着变，还带过渡动画。
 *
 * 之前那套「读下拉栏时钟色 → 覆盖整卡 → 拦截 setTextColor 别被刷回去 →
 * 把位图图标换成 extractAlpha 剪影 → 绘制前再校正一次」是在覆盖系统已经算好的结果，
 * 遇到的所有问题（颜色被刷回、图标不生效、切歌变黑）都是自己造出来的，已整体移除。
 *
 * ## 现在只做两件事
 *
 * 1. 把系统给的实时模糊底包一层 [ArkGlassDrawable]（调透明度 + 轮廓高光），
 *    不采样下层纹理 —— 采样会卡顿且与实时模糊不同步；
 * 2. 播放键的玻璃圆盘，在系统重设 background 时保住。
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

    /** 播放键底盘：按「系统给图标上的色」决定明暗两套，保证在对应背景上可见 */
    private class PlayStyle(val drawable: RippleDrawable, val light: Boolean)

    private val playGlass = WeakHashMap<View, PlayStyle>()

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
                        style(holder)
                    }
                }
            )
            XposedBridge.log("[$TAG] installing (glass only, 前景色交给系统)")
        }

        // 2) 每次 bind 之后补一次（holder 可能被复用，背景会被重设）
        ArkHooks.quiet("mediaCard.panel") {
            val clazz = XposedHelpers.findClass(MEDIA_CONTROL_PANEL, lpp.classLoader)
            ArkHooks.quiet("mediaCard.bindPlayer") {
                XposedBridge.hookAllMethods(clazz, "bindPlayer", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        styleOf(param.thisObject)
                    }
                })
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
                        param.args[0] = wrapBackground(v, raw)
                    }
                }
            )
        }
    }

    private fun style(holder: Any) {
        try {
            (field(holder, "player") as? View)?.let { player ->
                // create 返回时背景已由 onFinishInflate 设好，这里补包一次；
                // 之后系统重设背景由 setBackground 钩子接管。
                player.background?.let { player.background = wrapBackground(player, it) }
            }
            (field(holder, "actionPlayPause") as? ImageView)?.let { ensurePlayGlass(it) }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] style failed: $t")
        }
    }

    private fun styleOf(panel: Any?) {
        val holder = try {
            XposedHelpers.getObjectField(panel, "mMediaViewHolder")
        } catch (_: Throwable) {
            null
        } ?: return
        style(holder)
    }

    // ---------------------------------------------------------------
    // 背景
    // ---------------------------------------------------------------

    /**
     * 把系统给的模糊壁纸背景包一层：只调节透明度 + 补一条轮廓高光，
     * 不采样任何下层纹理（采样会卡顿、且与实时模糊不同步）。
     */
    private fun wrapBackground(v: View, base: Drawable): Drawable {
        if (base is ArkGlassDrawable) return base
        return try {
            val ctx = v.context
            ArkGlassDrawable(
                radius = radiusOf(v, ctx),
                density = ctx.resources.displayMetrics.density,
                content = base
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] wrap failed: $t")
            base
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
     * 明暗判定直接用**系统给这个图标设的 tint**（`imageTintList`）——
     * 那正是 ColorSchemeTransition 算出来的前景色，不需要我们另外去猜。
     * 读不到时按浅色前景处理。
     */
    private fun ensurePlayGlass(play: ImageView) {
        val tint = play.imageTintList?.defaultColor ?: 0
        val light = tint == 0 || isLight(tint)

        val made = playGlass[play]
        if (made != null && made.light == light && play.background === made.drawable) return

        val ctx = play.context
        // 前景浅（深背景）→ 亮白圆盘；前景深（浅背景）→ 淡黑圆盘
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

    private fun isLight(color: Int): Boolean {
        val lum =
            (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0
        return lum > 0.55
    }

    private fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()
}

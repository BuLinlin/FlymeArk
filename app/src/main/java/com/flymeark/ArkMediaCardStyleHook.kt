package com.flymeark

import android.content.res.ColorStateList
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
 * 下拉栏媒体卡：**只接管背景**，前景色由 [ArkForegroundStandard] 在资源层统一强制。
 *
 * ## 系统本来就有标准，只是值是散的
 *
 * ```
 * MediaControlPanel.getColor(R.color.qs_mediapalyer_xxx)
 *   → Resources.getColor(id)          ← 唯一的出口，所有前景色都从这里流出来
 *   → title / artist / seamless / play button / progress / thumb
 * ```
 *
 * 所以颜色不需要一个个 View 去改，也不需要读"系统现在用了什么色"——
 * 在出口处把这一族资源统一成标准三层色即可（见 [ArkForegroundStandard]），
 * 换歌、点播放、切主题、重绑都不会回流成 #333。
 *
 * 这里只负责两件事：
 *
 * 1. **背景**：把系统给的实时模糊底包一层 [ArkGlassDrawable]；
 * 2. **玻璃明暗跟标准对齐**：标准是黑字就把玻璃提亮，白字就压暗；
 *    播放键圆盘同源（黑字配淡黑圆盘，白字配亮白圆盘）。
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

    /** 播放键底盘：按深浅色给两套，保证在对应玻璃上可见 */
    private class PlayStyle(val drawable: RippleDrawable, val night: Boolean)

    private val playGlass = WeakHashMap<View, PlayStyle>()

    /** 上一次记录的标准（-1 未知 / 0 浅色 / 1 深色），只为少打日志 */
    private var lastLogged = -1

    fun install(lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam) {
        // 0) 前景色标准：资源层拦截，全进程生效
        ArkForegroundStandard.install(lpp)

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
            XposedBridge.log("[$TAG] installing (玻璃背景 + 前景标准)")
        }

        // 2) 每次绑定之后补一次：holder 会被复用，背景可能被重设
        ArkHooks.quiet("mediaCard.panel") {
            val clazz = XposedHelpers.findClass(MEDIA_CONTROL_PANEL, lpp.classLoader)
            listOf("setPlayerTextColor", "bindPlayer", "setPlayerButtonStyle", "setProgressBarStyle")
                .forEach { m ->
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
                        glass.setNight(ArkForegroundStandard.isNight(v.context))
                        param.args[0] = glass
                    }
                }
            )
        }
    }

    // ---------------------------------------------------------------
    // 样式
    // ---------------------------------------------------------------

    private fun style(holder: Any) {
        try {
            val player = field(holder, "player") as? View ?: return
            val night = ArkForegroundStandard.isNight(player.context)

            val v = if (night) 1 else 0
            if (v != lastLogged) {
                lastLogged = v
                XposedBridge.log(
                    "[$TAG] 标准 = ${if (night) "深色(白字 + 暗玻璃)" else "浅色(黑字 + 亮玻璃)"}"
                )
            }

            val raw = player.background ?: return
            val glass = glassOf(player, raw) ?: return
            glass.setNight(night)
            if (raw !== glass) player.background = glass

            (field(holder, "actionPlayPause") as? ImageView)?.let { ensurePlayGlass(it, night) }
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
        style(holder)
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
    private fun radiusOf(v: View, ctx: android.content.Context): Float = try {
        val f = XposedHelpers.getFloatField(v, "mClipCornerRadius")
        if (f > 0f) f else dp(ctx, FALLBACK_RADIUS_DP).toFloat()
    } catch (_: Throwable) {
        dp(ctx, FALLBACK_RADIUS_DP).toFloat()
    }

    // ---------------------------------------------------------------
    // 播放键圆盘
    // ---------------------------------------------------------------

    /**
     * 播放键的玻璃圆盘：跟标准同源。
     *
     * 深色模式（白字）用亮白圆盘，浅色模式（黑字）用淡黑圆盘，
     * 保证白图标压在暗玻璃上、黑图标压在亮玻璃上都看得见。
     */
    private fun ensurePlayGlass(play: ImageView, night: Boolean) {
        val made = playGlass[play]
        if (made != null && made.night == night && play.background === made.drawable) return

        val ctx = play.context
        val body = if (night) 0x40FFFFFF else 0x14000000
        val edge = if (night) 0x66FFFFFF else 0x2E000000
        val press = if (night) 0x33FFFFFF else 0x1F000000

        val disc = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(body)
            setStroke(dp(ctx, STROKE_DP), edge)
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(press), disc, null)

        playGlass[play] = PlayStyle(ripple, night)
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

    private fun dp(ctx: android.content.Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()
}

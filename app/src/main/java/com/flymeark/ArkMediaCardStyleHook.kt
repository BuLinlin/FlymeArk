package com.flymeark

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/**
 * v3.5：把下拉栏顶部那张媒体卡换成「液态玻璃」。
 *
 * 改造对象是 AOSP 的 MediaCarousel 卡片（`MediaViewHolder` → `res/layout/media_session_view.xml`），
 * 根节点 `player`（Flyme 的 `MediaCarouseTransitionLayout`），背景由 `WallpaperBlurDrawable` 提供。
 *
 * 我们只接管两件事：
 *  - **背景**：把系统给的实时模糊底包一层 [ArkGlassDrawable]，调透明度 + 补轮廓高光，
 *    **不采样**下层纹理（采样必然与实时模糊不同步，而且会卡）；
 *  - **前景色**：把整卡的文字 / 图标 / 进度条统一成系统时钟的颜色。
 *
 * 三个难点：
 *  1. `MediaControlPanel` 每次 bind 都会重设文字色、图标色、进度条样式、按钮背景，
 *     所以样式要在 `setPlayerTextColor` / `setPlayerButtonStyle` / `setProgressBarStyle` /
 *     `bindPlayer` 之后反复应用。
 *  2. 卡片背景会被系统重设（attach / alpha 变化），在 `View.setBackground` 上保活。
 *  3. 播放键的圆盘 background 会被 `bindButtonCommon` 覆盖，同样在保活钩子里顶掉。
 *     注意一个进程里有好几张卡（通知栏 / 锁屏 / QS），必须按 View 记表，不能只存一个引用。
 *
 * v3.5 起前景色不再写死：整卡（标题 / 歌手 / 图标 / 进度条 / 无缝按钮）统一跟随
 * **系统时钟** 的颜色 —— 见 [ArkShadeTint]。系统用什么颜色画时钟，我们就用什么颜色画字。
 */
object ArkMediaCardStyleHook {

    private const val TAG = "${ArkConstants.LOG_TAG}/mediaCard"

    private const val CARD_ROOT =
        "com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout"
    private const val MEDIA_VIEW_HOLDER =
        "com.android.systemui.media.controls.ui.view.MediaViewHolder"
    private const val MEDIA_CONTROL_PANEL =
        "com.android.systemui.media.controls.ui.controller.MediaControlPanel"

    /** 描边宽度 */
    private const val STROKE_DP = 1.2f

    /** 卡片圆角兜底（读不到 mClipCornerRadius 时用），与 qs_media_player_radius 一致 */
    private const val FALLBACK_RADIUS_DP = 24f

    /** 文字透明度：标题 / 副标题 / 图标 / 进度条底色 */
    private const val A_TITLE = 0xF2
    private const val A_SUB = 0xA6
    private const val A_ICON = 0xFF
    private const val A_TRACK = 0x40

    /** 播放键底盘：按前景明暗给两套描边 */
    private class PlayStyle(val drawable: RippleDrawable, val light: Boolean)

    private val playGlass = WeakHashMap<View, PlayStyle>()

    /** 颜色归属表：登记的 View 之后任何人设色都会被替换成这里的值 */
    private val owned = WeakHashMap<View, Int>()

    /** 进度条归属表：[0]=进度色 [1]=轨道色。系统重设 progressDrawable 后需要重新 tint */
    private val seekOwned = WeakHashMap<SeekBar, IntArray>()

    /** 绘制前校正命中的"坏颜色"，用于日志去重（同一个 View 同一种坏色只报一次） */
    private val guardLogged = WeakHashMap<View, Int>()

    /** 上一次染过色的 drawable 实例：系统换图标（播放↔暂停）后需要重新染 */
    private val iconDrawable = WeakHashMap<View, Drawable>()

    /** 图标当前被染成的颜色，用于判断是否需要重新生成剪影 */
    private val iconColor = WeakHashMap<View, Int>()

    /** 源 drawable + 颜色 → 剪影，避免每次 bind 都重新栅格化 */
    private val silhouetteCache = WeakHashMap<Drawable, MutableMap<Int, Drawable>>()

    /** 已经报过"图标还没就绪"的 View，避免刷屏 */
    private val noDrawable = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** 所有见过的 holder：时钟换色后需要把它们全部重新上色 */
    private val holders = java.util.Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    /** 上一次算出来的前景色，时钟暂时读不到时兜底 */
    private var lastBase = 0

    /** 上一次成功匹配的字段集合，用于日志去重 */
    private var lastLogged = ""
    private var lastFields = ""
    private var tintLogged = false

    /** 取不到前景色时的重试计数（每个 holder 独立） */
    private val retries = WeakHashMap<Any, Int>()

    private const val RETRY_MAX = 8
    private const val RETRY_MS = 250L

    fun install(lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam) {
        // 1) 视图创建：拿到 holder，先把背景换成玻璃
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
            XposedBridge.log("[$TAG] installing (liquid glass + clock tint)")
        }

        // 2) 每次 bind 之后重新应用样式
        ArkHooks.quiet("mediaCard.panel") {
            val clazz = XposedHelpers.findClass(MEDIA_CONTROL_PANEL, lpp.classLoader)
            listOf(
                "setPlayerTextColor",
                "setPlayerButtonStyle",
                "setProgressBarStyle",
                "bindPlayer"
            ).forEach { m ->
                ArkHooks.quiet("mediaCard.$m") {
                    XposedBridge.hookAllMethods(clazz, m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            styleOf(param.thisObject)
                        }
                    })
                }
            }
        }

        // 3) 背景与播放键底盘的保活
        ArkHooks.quiet("mediaCard.setBackground") {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setBackground",
                Drawable::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return

                        // 播放键的玻璃圆盘：系统在 bind 里会重设 background，这里直接顶掉
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

        // 4) 颜色归属：系统的 ColorSchemeTransition 每套颜色都是 333ms 的 ValueAnimator，
        //    会在我们设色之后一帧帧刷回来。把卡片里的 View 登记为"归我们管"，
        //    之后任何人设色都会被替换成我们的颜色。
        ArkHooks.quiet("mediaCard.colorOwner") {
            hookColorSet("setTextColor", Int::class.javaPrimitiveType)
            hookColorSet("setTextColor", ColorStateList::class.java)
            hookColorSet("setImageTintList", ColorStateList::class.java)
            hookColorSet("setColorFilter", Int::class.javaPrimitiveType)
            // setPlayerButtonStyle 走的是 (int, PorterDuff.Mode)，最终落到 setColorFilter(ColorFilter)，
            // 而 drawable 绘制时 colorFilter 优先级高于 tintList —— 不拦这两个就会盖掉我们的颜色
            hookColorSet("setColorFilter", Int::class.javaPrimitiveType, android.graphics.PorterDuff.Mode::class.java)
            hookColorSet("setColorFilter", android.graphics.ColorFilter::class.java)
            // setPlayerButtonStyle() 里有一句 clearColorFilter()：它把 drawable 上的滤镜清掉，
            // 图标会瞬间回到系统原色。这里直接吞掉这次调用。
            XposedHelpers.findAndHookMethod(
                ImageView::class.java,
                "clearColorFilter",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        if (owned.containsKey(v)) param.result = null
                    }
                }
            )
        }

        // 5) 前景色来源：系统给「大时钟」赋的那个颜色。
        //    不能只读 Clock.onDarkChanged 的 rootViewTint —— 实测那个值是给状态栏图标的
        //    （深色壁纸下恒为白），而下拉栏展开后大时钟自己被刷成黑，两者不一致。
        //    所以在下面的 setTextColor 钩子（hookColorSet）里抓：
        //    谁被赋色、且文本形如 HH:mm、字号够大，那个才是用户实际看到的时钟颜色。
        //    （主题里自定义的时钟颜色也走这条路。）
        //    onDarkChanged 仅作兜底，见 ArkShadeTint.onDarkTint。
        ArkHooks.quiet("mediaCard.darkReceiver") {
            val clock = XposedHelpers.findClass(
                "com.android.systemui.statusbar.policy.Clock",
                lpp.classLoader
            )
            XposedBridge.hookAllMethods(clock, "onDarkChanged", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val tint = param.args.getOrNull(2) as? Int ?: return
                    ArkShadeTint.onDarkTint(tint)
                }
            })
        }

        // 6) 最终防线：上面拦的是"设色入口"，但系统还有几条路能绕过 ——
        //    - ColorSchemeTransition 的 333ms ValueAnimator 每帧重放固定的 r3.color；
        //    - setProgressBarStyle() 重新 setProgressDrawable，tint 全部丢失；
        //    - 直接改 Paint / Drawable 的 tint。
        //    所以在绘制前比一次颜色，不一致就纠正。颜色相同时不会触发 invalidate，
        //    不会形成重绘环。
        ArkHooks.quiet("mediaCard.guard") {
            // 用 View.draw（一切绘制的总入口）而不是 TextView/ImageView.onDraw：
            // Flyme 的按钮有自定义 ImageView 子类，重写了 onDraw 且不调 super，
            // 挂在 onDraw 上的钩子根本不会触发。View.draw 绕不过去。
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "draw",
                Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        val want = owned[v] ?: return
                        when (v) {
                            is TextView -> {
                                if (v.currentTextColor == want) return
                                reportGuard(v, v.currentTextColor, want)
                                v.setTextColor(want)
                            }
                            is ImageView -> {
                                // 已经是我们的剪影就什么都不做
                                if (v.drawable === iconDrawable[v] && iconColor[v] == want) return
                                reportGuard(v, 0, want)
                                paintIcon(v, want)
                            }
                            else -> Unit
                        }
                    }
                }
            )

            // 系统在 bind 里会 setImageResource 换图标（播放↔暂停），
            // 新图是原色位图，必须在进来时立刻换成同色剪影。
            XposedHelpers.findAndHookMethod(
                ImageView::class.java,
                "setImageDrawable",
                Drawable::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? ImageView ?: return
                        val color = owned[v] ?: return
                        val d = param.args[0] as? Drawable ?: return
                        if (d === iconDrawable[v]) return
                        param.args[0] = silhouetteOf(d, color)
                    }
                }
            )

            // 进度条：系统重建 drawable 后重新上 tint（不能放在 onDraw，setTint 会 invalidate）
            XposedHelpers.findAndHookMethod(
                android.widget.ProgressBar::class.java,
                "setProgressDrawable",
                Drawable::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val sb = param.thisObject as? SeekBar ?: return
                        val c = seekOwned[sb] ?: return
                        tintSeekBar(sb, c[0], c[1])
                    }
                }
            )
            XposedBridge.log("[$TAG] guard hooks installed")
        }
    }

    /** 时钟换色后，把所有见过的卡片重新上一遍色（换壁纸 / 切深浅色） */
    internal fun refreshCards() {
        try {
            holders.toList().forEach { style(it) }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] refresh failed: $t")
        }
    }

    /** 记录一次"被系统改回去了"，同一个 View 同一种坏色只写一条日志 */
    private fun reportGuard(v: View, bad: Int, want: Int) {
        if (guardLogged[v] == bad) return
        guardLogged[v] = bad
        XposedBridge.log(
            "[$TAG] guard ${v.javaClass.simpleName} " +
                "#${Integer.toHexString(bad)} -> #${Integer.toHexString(want)}"
        )
    }

    /** 统一挂 "设色拦截"：Int 参数直接换值，ColorStateList 参数包成单色 */
    private fun hookColorSet(method: String, paramType: Class<*>?, paramType2: Class<*>? = null) {
        val target: Class<*> = when (method) {
            "setTextColor" -> TextView::class.java
            else -> ImageView::class.java
        }
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val v = param.thisObject as? View ?: return
                val color = owned[v] ?: return
                param.args[0] = when {
                    paramType == ColorStateList::class.java -> ColorStateList.valueOf(color)
                    paramType == android.graphics.ColorFilter::class.java ->
                        android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
                    else -> color
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                // 抓「大时钟被赋色」这件事：这就是用户实际看到的时钟颜色
                if (method != "setTextColor") return
                val v = param.thisObject as? TextView ?: return
                if (owned.containsKey(v)) return      // 卡片自己的文字，不是时钟
                ArkShadeTint.noteClockColor(v)
            }
        }
        if (paramType2 == null) {
            XposedHelpers.findAndHookMethod(target, method, paramType, hook)
        } else {
            XposedHelpers.findAndHookMethod(target, method, paramType, paramType2, hook)
        }
    }

    // ---------------------------------------------------------------
    // 样式
    // ---------------------------------------------------------------

    private fun style(holder: Any) {
        try {
            holders.add(holder)
            val found = StringBuilder()

            val player = field(holder, "player") as? View
            var base: Int? = null
            if (player != null) {
                // create 返回时背景已由 onFinishInflate 设好，这里补包一次；
                // 之后系统重设背景由 setBackground 钩子接管。
                player.background?.let { player.background = wrapBackground(player, it) }
                found.append("card ")
                base = currentColor(player)
            }

            applyTint(holder, base)
            found.append("tint ")

            (field(holder, "actionPlayPause") as? ImageView)?.let { play ->
                ensurePlayGlass(play, base?.let { isLight(it) } ?: true)
                found.append("play ")
            }

            val line = found.toString()
            if (line != lastLogged) {
                lastLogged = line
                XposedBridge.log("[$TAG] styled: $line")
            }

            // create / bind 往往发生在「时钟色」就绪之前，这时拿不到前景色；
            // 挂一次有限重试，避免整卡一直停在系统黑色。
            if (player != null && base == null) {
                val n = retries[holder] ?: 0
                if (n < RETRY_MAX) {
                    retries[holder] = n + 1
                    player.postDelayed({ style(holder) }, RETRY_MS)
                }
            } else if (base != null) {
                retries.remove(holder)
            }
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

    /** 卡片当前应该使用的前景色：跟系统给时钟的颜色一致 */
    private fun currentColor(card: View): Int? {
        val c = ArkShadeTint.fromView(card)
        if (c != 0) {
            lastBase = c
            return c
        }
        // 颜色还没就绪时沿用上一个已知值，绝不把颜色交还给系统主题色
        return if (lastBase != 0) lastBase else null
    }

    /**
     * 整卡前景色：标题 / 歌手 / 图标 / 进度条 / 无缝按钮全部跟随同一来源，
     * 只靠透明度区分层级。封面与 App 图标不参与。
     */
    private fun applyTint(holder: Any, base: Int?) {
        if (base == null) {
            if (!tintLogged) {
                tintLogged = true
                XposedBridge.log("[$TAG] tint skipped: no base color (holder=${holder.javaClass.name})")
            }
            return
        }

        val titleColor = withAlpha(base, A_TITLE)
        val subColor = withAlpha(base, A_SUB)
        val iconColor = withAlpha(base, A_ICON)

        val report = StringBuilder()
        report.append(if (text(holder, "titleText", titleColor)) "title " else "-title ")
        report.append(if (text(holder, "artistText", subColor)) "artist " else "-artist ")
        report.append(if (text(holder, "seamlessText", iconColor)) "seamless " else "-seamless ")
        report.append(if (tintIcon(holder, "actionPlayPause", iconColor)) "play " else "-play ")
        report.append(if (field(holder, "seekBar") != null) "seek " else "-seek ")

        text(holder, "titleTextExpanded", titleColor)
        text(holder, "artistTextExpanded", subColor)
        text(holder, "scrubbingElapsedTimeView", subColor)
        text(holder, "scrubbingTotalTimeView", subColor)

        report.append(if (tintIcon(holder, "seamlessIcon", iconColor)) "seamlessIcon " else "-seamlessIcon ")
        listOf(
            "actionPrev", "actionNext",
            "action0", "action1", "action2", "action3", "action4"
        ).forEach { n ->
            report.append(if (tintIcon(holder, n, iconColor)) "$n " else "-$n ")
        }

        (field(holder, "seekBar") as? SeekBar)?.let { sb ->
            val track = withAlpha(base, A_TRACK)
            seekOwned[sb] = intArrayOf(iconColor, track)
            tintSeekBar(sb, iconColor, track)
        }

        // 字段命中情况每次变化都打一次：哪一项取不到（前缀 -）一眼能看出来
        val line = report.toString()
        if (line != lastFields) {
            lastFields = line
            val t = field(holder, "titleText") as? TextView
            XposedBridge.log(
                "[$TAG] tint base=#${Integer.toHexString(base)} fields[$line] " +
                    "readback=#${t?.let { Integer.toHexString(it.currentTextColor) }}"
            )
        }
    }

    private fun text(holder: Any, name: String, color: Int): Boolean {
        val v = field(holder, name) as? TextView ?: return false
        owned[v] = color
        v.setTextColor(color)
        return true
    }

    private fun tintIcon(holder: Any, name: String, color: Int): Boolean {
        val v = field(holder, name) as? ImageView ?: return false
        paintIcon(v, color)
        return true
    }

    /**
     * 给图标上色。
     *
     * 这里**不能**只靠 `setColorFilter` / `imageTintList`：卡片的按钮图标是 75×75 的
     * `BitmapDrawable`（位图资源，不是矢量单色图），滤镜在共享 ConstantState 的位图上
     * 不一定真正改到像素，实测设成白色后画面仍是原图的黑色。
     *
     * 所以改成"换图"：把原图渲染一次，用 `extractAlpha()` 抽出 alpha 蒙版，
     * 再用目标色重新填一遍，得到同形状的纯色剪影。与源图内容无关，一定正确。
     */
    private fun paintIcon(v: ImageView, color: Int) {
        owned[v] = color
        try {
            val src = v.drawable
            if (src == null) {
                // 图标还没设进来，等 setImageDrawable 钩子接手
                if (noDrawable.add(v)) XposedBridge.log("[$TAG] paintIcon: drawable 尚未就绪")
                return
            }
            if (src === iconDrawable[v] && iconColor[v] == color) return

            val sil = silhouetteOf(src, color)
            iconDrawable[v] = sil
            iconColor[v] = color
            v.setImageDrawable(sil)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] paintIcon failed: $t")
        }
    }

    /** 源 drawable → 纯色剪影，按 (源, 颜色) 缓存，避免每次 bind 都重新栅格化 */
    private fun silhouetteOf(src: Drawable, color: Int): Drawable =
        silhouetteCache.getOrPut(src) { HashMap() }.getOrPut(color) { SilhouetteDrawable(src, color) }

    /**
     * 纯色剪影 drawable。
     *
     * 为什么不直接返回 `BitmapDrawable`：那玩意儿的 `intrinsicWidth` 会被 density 换算
     * （位图 density 与目标 density 不一致时按比例缩放），换上去图标会明显变小（实测约 1/3）。
     * 这里把尺寸原样转发给源 drawable，绘制时再把剪影拉伸到 bounds，尺寸与系统原图完全一致。
     */
    private class SilhouetteDrawable(
        private val src: Drawable,
        private val color: Int
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private var cache: Bitmap? = null

        private fun mask(): Bitmap? {
            cache?.let { return it }
            val w = src.intrinsicWidth.takeIf { it > 0 } ?: 96
            val h = src.intrinsicHeight.takeIf { it > 0 } ?: 96
            return try {
                val old = src.copyBounds()
                val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                src.setBounds(0, 0, w, h)
                src.draw(Canvas(full))
                src.bounds = old

                val alpha = full.extractAlpha()
                full.recycle()

                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.color = color
                Canvas(out).drawBitmap(alpha, 0f, 0f, p)
                alpha.recycle()

                cache = out
                out
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] silhouette failed: $t")
                null
            }
        }

        override fun getIntrinsicWidth(): Int = src.intrinsicWidth
        override fun getIntrinsicHeight(): Int = src.intrinsicHeight

        override fun draw(canvas: Canvas) {
            val m = mask() ?: return
            canvas.drawBitmap(m, null, bounds, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(cf: android.graphics.ColorFilter?) {
            paint.colorFilter = cf
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun tintSeekBar(sb: SeekBar, color: Int, track: Int) {
        (sb.progressDrawable as? LayerDrawable)?.let { ld ->
            ld.findDrawableByLayerId(android.R.id.progress)?.setTint(color)
            ld.findDrawableByLayerId(android.R.id.background)?.setTint(track)
        }
        sb.thumb?.setTint(withAlpha(color, 0xFF))
    }

    /** 播放键的玻璃圆盘：与前景同色系的低透明度底盘 + 亮边，带按压水波纹 */
    private fun ensurePlayGlass(play: ImageView, light: Boolean) {
        val made = playGlass[play]
        if (made != null && made.light == light && play.background === made.drawable) return

        val ctx = play.context
        val body = if (light) 0x40FFFFFF else 0x1AFFFFFF
        val edge = if (light) 0x66FFFFFF else 0x33FFFFFF
        val disc = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(body)
            setStroke(dp(ctx, STROKE_DP), edge)
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(if (light) 0x33FFFFFF else 0x1F000000), disc, null)

        playGlass[play] = PlayStyle(ripple, light)
        play.background = ripple
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

    /** 浅色前景 → 用透明白底盘；深色前景 → 用微亮白底盘 */
    private fun isLight(color: Int): Boolean {
        val lum = (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0
        return lum > 0.55
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()
}

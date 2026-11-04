package com.flymeark

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.util.SparseIntArray
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 下拉栏前景色的**强制标准**：在资源层把颜色改掉，全进程生效。
 *
 * ## 为什么改资源，而不是改 View
 *
 * 系统媒体卡的前景不自己管：`MediaControlPanel` 每次都现算
 * `getColor(R.color.qs_mediapalyer_xxx)`（内部就是 `Resources.getColor(id)`）：
 *
 * ```
 * setPlayerTextColor()     标题 / 歌手 / 无缝播放文字 / 时间
 * setPlayerButtonStyle()   播控按钮 setColorFilter(getColor(qs_mediaplayer_play_button_color))
 * setProgressBarStyle()    进度 / 底色 / 滑块的 Drawable.setTint(getColor(...))
 * ```
 *
 * 也就是说整个卡片所有前景色都从**同一族资源**流出来。之前我们在 View 层逐个改
 * （setTextColor / setImageTintList / setColorFilter + 绘制前校正），是在跟系统的
 * 每次重绑打架，所以点一下播放键就变回 #333。
 *
 * 这里换到**源头**：`Resources.getColor()` 命中这一族资源时直接返回标准色。
 * 之后谁调用、调用多少次、动画怎么刷，拿到的都是同一个值 —— 没有回流，不需要校正，
 * 也不需要每帧参与绘制。FlymeMod 是在 `TextView.onDraw`/`ImageView.onDraw` 里做同等
 * 的事（绘制时强制成白/黑），我们往下再挖一层，性能更好、覆盖面更全。
 *
 * ## 标准是什么
 *
 * 只有一套，跟系统深浅色走，不分壁纸、不分封面：
 *
 * | 层级 | 用途 | 浅色模式 | 深色模式 |
 * |---|---|---|---|
 * | primary | 标题、播控按钮、进度 | `#000000` | `#FFFFFF` |
 * | secondary | 歌手、无缝播放、时间 | `#99000000` | `#B3FFFFFF` |
 * | tertiary | 进度条底色 | `#4D000000` | `#59FFFFFF` |
 *
 * 背景由 [ArkGlassDrawable] 负责：标准是黑字就把玻璃提亮，白字就把玻璃压暗。
 * 前景和背景是同一个标准的两面，所以任何壁纸、任何封面下都有对比。
 */
object ArkForegroundStandard {

    private const val TAG = "${ArkConstants.LOG_TAG}/fg"

    /** SystemUI 资源包名：只有这一族资源会被改写 */
    private const val PKG = "com.android.systemui"

    // 层级编号（缓存里用，0 表示"还没判定"，-1 表示"不管"）
    const val TIER_PRIMARY = 1
    const val TIER_SECONDARY = 2
    const val TIER_TERTIARY = 3
    private const val SKIP = -1
    private const val UNKNOWN = 0

    /**
     * 纳入标准的资源名前缀。
     *
     * `qs_mediapalyer_` 是 Flyme 自己的拼写（少个 e），`qs_mediaplayer_` 是正确拼写，
     * 两个都存在，必须都覆盖。
     */
    private val FAMILIES = arrayOf("qs_mediapalyer_", "qs_mediaplayer_")

    /**
     * 是否把 QS 磁贴、通知行也纳入同一套标准。
     *
     * 打开后 `qs_tile_label_color` / `qs_icon_inactive_color` 这一族（正好是 FlymeMod
     * 的 palette 名单）也会被统一成上面的三层色 —— 下拉栏所有文字图标一起变。
     * 默认关闭：媒体卡是玻璃底，必须强制；磁贴是系统自己的浅底/深底，先不动，等确认。
     */
    private var includeQs = false

    private val QS_FAMILIES = arrayOf(
        "qs_tile_label_color",
        "qs_tile_secondlabel_color",
        "qs_icon_inactive_color",
        "qs_icon_unavailable_color",
        "qs_meida_icon_inactive_color",
        "qs_media_header_artist"
    )

    /** 资源 id → 层级。多线程只在未命中时加锁补算，命中后是无锁数组查 */
    private val tiers = SparseIntArray()

    private var logged = false

    fun install(lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam) {
        hookGetColor("getColor(int)", Int::class.javaPrimitiveType!!)
        hookGetColor(
            "getColor(int,Theme)",
            Int::class.javaPrimitiveType!!,
            android.content.res.Resources.Theme::class.java
        )
        XposedBridge.log("[$TAG] 前景标准已接管（资源层），includeQs=$includeQs")
    }

    private fun hookGetColor(tag: String, vararg sig: Class<*>) {
        ArkHooks.quiet("fg.$tag") {
            val args = sig.toMutableList<Any>()
            args.add(object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val res = param.thisObject as? Resources ?: return
                    val id = param.args[0] as? Int ?: return
                    val tier = tier(res, id)
                    if (tier == SKIP) return
                    val night = isNight(res.configuration)
                    param.result = color(tier, night)
                    if (!logged) {
                        logged = true
                        XposedBridge.log(
                            "[$TAG] 命中 #${Integer.toHexString(id)} tier=$tier " +
                                "→ ${if (night) "深色模式(白字)" else "浅色模式(黑字)"}"
                        )
                    }
                }
            })
            XposedHelpers.findAndHookMethod(
                Resources::class.java,
                "getColor",
                *args.toTypedArray()
            )
        }
    }

    // ---------------------------------------------------------------
    // 标准色
    // ---------------------------------------------------------------

    fun isNight(c: Configuration): Boolean =
        (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun isNight(ctx: Context): Boolean = isNight(ctx.resources.configuration)

    /** 按层级取标准色。任何没有明确定层的调用都按 primary 处理 */
    fun color(tier: Int, night: Boolean): Int = when (tier) {
        TIER_SECONDARY -> if (night) SECONDARY_NIGHT else SECONDARY_DAY
        TIER_TERTIARY -> if (night) TERTIARY_NIGHT else TERTIARY_DAY
        else -> if (night) PRIMARY_NIGHT else PRIMARY_DAY
    }

    fun color(tier: Int, ctx: Context): Int = color(tier, isNight(ctx))

    // ---------------------------------------------------------------
    // 资源 → 层级
    // ---------------------------------------------------------------

    private fun tier(res: Resources, id: Int): Int {
        val cached = tiers.get(id, UNKNOWN)
        if (cached != UNKNOWN) return cached
        synchronized(tiers) {
            val again = tiers.get(id, UNKNOWN)
            if (again != UNKNOWN) return again
            val t = resolve(res, id)
            tiers.put(id, t)
            return t
        }
    }

    private fun resolve(res: Resources, id: Int): Int {
        val name = try {
            res.getResourceName(id)
        } catch (_: Throwable) {
            return SKIP
        }
        if (!name.startsWith("$PKG:color/")) return SKIP
        val entry = name.substring(name.lastIndexOf('/') + 1)

        val inMedia = FAMILIES.any { entry.startsWith(it) }
        if (!inMedia) {
            if (!includeQs || !QS_FAMILIES.any { it == entry }) return SKIP
        }

        // 顺序有讲究：background 先判，否则 progressbar_background_tint 会被当成 primary
        return when {
            entry.contains("background") -> TIER_TERTIARY
            entry.contains("artist") || entry.contains("seamless") -> TIER_SECONDARY
            else -> TIER_PRIMARY
        }
    }

    private val PRIMARY_DAY = 0xFF000000.toInt()
    private val PRIMARY_NIGHT = 0xFFFFFFFF.toInt()
    private val SECONDARY_DAY = 0x99000000.toInt()
    private val SECONDARY_NIGHT = 0xB3FFFFFF.toInt()
    private val TERTIARY_DAY = 0x4D000000.toInt()
    private val TERTIARY_NIGHT = 0x59FFFFFF.toInt()
}

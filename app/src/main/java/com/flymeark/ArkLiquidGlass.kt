package com.flymeark

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.sqrt

/**
 * v3.15.42：液态玻璃材质参数。所有浓度都是**区间**，实际取值由背景复杂度插值决定。
 *
 * CSS 规范里的 `--glass-tint / --glass-blur / --glass-lens / --glass-edge / --glass-ink`
 * 在本工程里分别落到 AGSL shader 的着色/模糊/折射、View 的 elevation 投影、
 * 以及 [ArkLiquidGlass.ink] 算出的文字色。
 */
internal data class GlassParams(
    /** true = 浅色玻璃（白着色 + 正片叠底文字） */
    val light: Boolean,
    /** 着色层 RGB（不含 alpha） */
    val tintRgb: Int,
    /** 着色层不透明度：浅色 6%–22%，深色 16%–38% */
    val tintAlpha: Float,
    /** 背景采样平均色，供文字混合 */
    val ambientRgb: Int,
    val ambientLuma: Float,
    /** 背景复杂度 0..1（采样亮度标准差归一化），越高着色越浓 */
    val complexity: Float,
    val blurPx: Float,
    val saturation: Float,
    val brightness: Float,
    /** 折射边宽（px，Drawable 内会按 bounds 再钳制，保证中间平整） */
    val edgePx: Float,
    val edgeShift: Float,
    val dispersion: Float,
    val shadowDp: Float,
    val strokeRgb: Int,
) {
    val tintArgb: Int
        get() = ((tintAlpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or
            (tintRgb and 0x00FFFFFF)
}

/**
 * 液态玻璃的统一入口：背景多点采样 → 自动深浅判定 → 参数生成 → 挂到任意 View。
 *
 * ## 采样
 * 壁纸 drawable 缩到 32×32，取 5×5 共 25 个采样点算平均感知亮度与标准差（复杂度）；
 * `WallpaperColors` 作二级来源；都拿不到则退回系统夜间模式常量。
 * 普通 App 进程读不到实时屏幕像素，所以用壁纸作为背景亮度的代表值 —— 这是原生侧
 * 无需截屏权限时可获得的最接近数据源（见 docs/changelog.md「已知边界」）。
 */
internal object ArkLiquidGlass {

    /**
     * 规范定义：平均亮度低于 0.5 时自动切换为**浅色**模式。
     * 若想改成直觉方向（暗背景用深玻璃），把这个常量取反即可，其余逻辑不用动。
     */
    const val AUTO_LIGHT_ON_DARK = true

    private const val EDGE_PX = 44f
    private const val EDGE_SHIFT_RATIO = 1.1f
    private const val DISPERSION = 0.15f

    private const val CACHE_MS = 2_000L

    @Volatile
    private var cached: Pair<Long, GlassParams>? = null

    /** 主题切换（通常连壁纸一起换）时主动让采样失效 */
    fun invalidateCache() {
        cached = null
    }

    /**
     * v3.15.44：色值统一取自 [ArkTokens]（IslandTokens 的 Kotlin 镜像）。
     * 只改常量色值 / 投影尺寸 / 不透明度分档；25 点采样、深浅浓度区间、
     * 自动模式判定与 6 层结构一律不变。
     *
     * @param nightFallback 采样完全失败时使用的系统夜间模式判定
     */
    fun resolve(ctx: Context, nightFallback: Boolean): GlassParams {
        cached?.let { (at, p) ->
            if (SystemClock.elapsedRealtime() - at < CACHE_MS) return p
        }
        val s = sample(ctx)
        val luma = s?.luma ?: if (nightFallback) 0.18f else 0.82f
        val complexity = s?.complexity ?: 0.5f
        // v3.15.44：兜底环境色对齐 IslandTokens 中性基底（浅 #EFF3F8 / 深 #1B2029）
        val ambient = s?.rgb
            ?: if (nightFallback) ArkTokens.NEUTRAL_DARK else ArkTokens.NEUTRAL_LIGHT

        val light = if (s != null) {
            if (AUTO_LIGHT_ON_DARK) luma < 0.5f else luma >= 0.5f
        } else {
            // 无采样数据时不反转系统主题，避免壁纸读取失败导致观感突变
            !nightFallback
        }

        val lo = if (light) 0.06f else 0.16f
        val hi = if (light) 0.22f else 0.38f
        val density = ctx.resources.displayMetrics.density
        val edge = EDGE_PX * density

        val p = GlassParams(
            light = light,
            // v3.15.44：着色层 RGB 取玻璃渐变端点色；浓度区间 6%–22% / 16%–38% 不变
            tintRgb = if (light) ArkTokens.GLASS_TOP_RGB_LIGHT else ArkTokens.GLASS_BOTTOM_RGB_DARK,
            tintAlpha = lo + (hi - lo) * complexity,
            ambientRgb = ambient and 0x00FFFFFF,
            ambientLuma = luma,
            complexity = complexity,
            blurPx = 2f * density,
            saturation = if (light) 1.80f else 1.60f,
            brightness = if (light) 1.06f else 0.80f,
            edgePx = edge,
            edgeShift = edge * EDGE_SHIFT_RATIO,
            dispersion = DISPERSION,
            // v3.15.44：投影 18dp、描边取 IslandTokens 高光色
            shadowDp = ArkTokens.ELEVATION_DP.toFloat(),
            strokeRgb = if (light) ArkTokens.HIGHLIGHT_LIGHT else ArkTokens.HIGHLIGHT_DARK,
        )
        cached = SystemClock.elapsedRealtime() to p
        return p
    }

    /**
     * 第 6 层：文字着色。
     * 浅色玻璃 → 正片叠底；深色玻璃 → 叠加提亮（screen）。
     * 另外按小权重混入环境色，让纯黑/纯白字也带一点背景色，而不是死板纯色。
     */
    fun ink(base: Int, p: GlassParams, weight: Float = 0.32f): Int {
        val ambient = if (p.light) p.ambientRgb else lift(p.ambientRgb, 0.68f)
        fun channel(b: Int, a: Int): Int {
            val bf = b / 255f
            val af = a / 255f
            val blended = if (p.light) bf * af else 1f - (1f - bf) * (1f - af)
            val out = bf + (blended - bf) * weight + (af - bf) * weight * 0.22f
            return (out * 255f).toInt().coerceIn(0, 255)
        }
        return Color.argb(
            Color.alpha(base),
            channel(Color.red(base), Color.red(ambient)),
            channel(Color.green(base), Color.green(ambient)),
            channel(Color.blue(base), Color.blue(ambient)),
        )
    }

    /** 把暗色背景按比例提亮到目标亮度，保留色相（供深玻璃的 screen 混合用） */
    private fun lift(rgb: Int, target: Float): Int {
        val r = Color.red(rgb) / 255f
        val g = Color.green(rgb) / 255f
        val b = Color.blue(rgb) / 255f
        val l = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceAtLeast(0.02f)
        val k = (target / l).coerceIn(0.6f, 3.0f)
        return Color.rgb(
            (r * k * 255f).toInt().coerceIn(0, 255),
            (g * k * 255f).toInt().coerceIn(0, 255),
            (b * k * 255f).toInt().coerceIn(0, 255),
        )
    }

    /**
     * 通用挂载入口：卡片、播放器按钮、将来的搜索框都走这里。
     * 第 1 层投影由 elevation + 圆角 outline 交给系统合成器，Drawable 只画玻璃面。
     */
    fun attach(view: View, p: GlassParams, radiusPx: Float): ArkLiquidGlassDrawable {
        val d = (view.background as? ArkLiquidGlassDrawable)
            ?: ArkLiquidGlassDrawable(p).also { view.background = it }
        d.params = p
        d.cornerRadius = radiusPx
        view.elevation = p.shadowDp * view.resources.displayMetrics.density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, d.cornerRadius)
            }
        }
        // 不能 clipToOutline：会裁掉投影和边缘光
        view.clipToOutline = false
        return d
    }

    // ------------------------------------------------------------------
    // 背景采样
    // ------------------------------------------------------------------

    private class Sample(val luma: Float, val complexity: Float, val rgb: Int)

    private fun sample(ctx: Context): Sample? {
        val fromDrawable = readWallpaper(ctx)
        if (fromDrawable != null) return fromDrawable
        return readWallpaperColors(ctx)
    }

    private fun readWallpaper(ctx: Context): Sample? {
        return try {
            val wm = WallpaperManager.getInstance(ctx)
            val d: Drawable = wm.drawable ?: return null
            val n = 32
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, n, n)
            d.draw(Canvas(bmp))

            val points = 5
            var sum = 0f
            var sumSq = 0f
            var rs = 0
            var gs = 0
            var bs = 0
            for (y in 0 until points) {
                for (x in 0 until points) {
                    val px = bmp.getPixel(x * (n - 1) / (points - 1), y * (n - 1) / (points - 1))
                    val r = Color.red(px)
                    val g = Color.green(px)
                    val b = Color.blue(px)
                    val l = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                    sum += l
                    sumSq += l * l
                    rs += r
                    gs += g
                    bs += b
                }
            }
            bmp.recycle()
            val count = (points * points).toFloat()
            val avg = sum / count
            val sd = sqrt((sumSq / count - avg * avg).coerceAtLeast(0f))
            Sample(
                luma = avg,
                // 标准差 0.30 视为"很复杂"，映射到着色区间上限
                complexity = (sd / 0.30f).coerceIn(0f, 1f),
                rgb = Color.rgb(rs / count.toInt(), gs / count.toInt(), bs / count.toInt()),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun readWallpaperColors(ctx: Context): Sample? {
        if (Build.VERSION.SDK_INT < 27) return null
        return try {
            val wm = WallpaperManager.getInstance(ctx)
            val wc = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return null
            val c = wc.primaryColor.toArgb()
            val l = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) +
                0.0722f * Color.blue(c)) / 255f
            // 单一颜色无法算方差，取中间复杂度
            Sample(l, 0.5f, c and 0x00FFFFFF)
        } catch (_: Throwable) {
            null
        }
    }

    private fun systemNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}

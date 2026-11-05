package com.flymeark

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import java.lang.reflect.Method
import java.util.Locale

/**
 * v3.15.42：液态玻璃 6 层复合背景。
 *
 * ```
 * ① 投影      → View.elevation + 圆角 outline（ArkLiquidGlass.attach 里设置）
 * ② 模糊提亮  → AGSL glassTex() 里的噪声/低频起伏 + sat(c*brightness, saturation)
 * ③ 边缘折射  → k=1-smoothstep(0,edge,d)，采样坐标沿内法线偏移 shift*k²，
 *               R 通道多弯 dispersion、B 少弯 dispersion（中间 k=0 保持平整）
 * ④ 着色      → mix(c, tint, tintAlpha)，alpha 由背景复杂度在区间内插值
 * ⑤ 边缘光    → 左上/右下对角渐变描边 + 内侧缩进 2dp 再一道
 * ⑥ 文字着色  → ArkLiquidGlass.ink()（Activity 侧应用到 TextView）
 * ```
 *
 * AGSL 通过**反射**构造 RuntimeShader：模块 minSdk 26，避免低版本加载本类时
 * 因找不到 `android.graphics.RuntimeShader` 而抛 NoClassDefFoundError。
 * 设备是 API 36，正常运行走 AGSL；失败时降级为纯渐变玻璃，功能不受影响。
 */
internal class ArkLiquidGlassDrawable(initial: GlassParams) : Drawable() {

    var params: GlassParams = initial
        set(value) {
            field = value
            rebuild()
            invalidateSelf()
        }

    /** 圆角半径（px）。morph 动画中逐帧更新，玻璃边缘会跟着一起变形 */
    var cornerRadius: Float = 30f
        set(value) {
            field = value.coerceAtLeast(0f)
            invalidateSelf()
        }

    private val density = android.content.res.Resources.getSystem().displayMetrics.density

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val volumePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val innerEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val rect = RectF()
    private var shader: Shader? = null
    private var agslReady = false

    init {
        rebuild()
    }

    /** 兼容 ArkMorphAnim 的旧配色接口：拆出 tint 与浓度（新版 morph 直接走 setGlass） */
    fun setColor(argb: Int) {
        params = params.copy(
            tintRgb = argb and 0x00FFFFFF,
            tintAlpha = Color.alpha(argb) / 255f,
        )
    }

    fun setStroke(width: Int, color: Int) {
        params = params.copy(strokeRgb = color)
    }

    fun setGlass(p: GlassParams) {
        params = p
    }

    private fun rebuild() {
        shader = buildAgsl(params)
        agslReady = shader != null
        glassPaint.shader = if (agslReady) shader else fallbackGradient()
        Log.i(
            TAG,
            "glass ${if (agslReady) "AGSL enabled" else "gradient fallback"}: " +
                "light=${params.light} tint=${(params.tintAlpha * 100).toInt()}% " +
                "luma=${String.format(Locale.US, "%.2f", params.ambientLuma)} " +
                "complexity=${String.format(Locale.US, "%.2f", params.complexity)}"
        )
        applyEdgeColors()
        updateVolumeGradient()
    }

    /** AGSL 不可用（低版本 / shader 编译失败 / uniform 写入失败）时的渐变玻璃 */
    private fun fallbackGradient(): LinearGradient = LinearGradient(
        0f, 0f, rect.width().coerceAtLeast(1f), rect.height().coerceAtLeast(1f),
        blend(params.tintRgb, params.ambientRgb, 0.18f),
        params.tintArgb,
        Shader.TileMode.CLAMP,
    )

    private fun updateVolumeGradient() {
        volumePaint.shader = LinearGradient(
            0f, 0f, 0f, rect.height().coerceAtLeast(1f),
            Color.TRANSPARENT,
            if (params.light) 0x14000000 else 0x1A000000.toInt(),
            Shader.TileMode.CLAMP,
        )
    }

    private fun applyEdgeColors() {
        val hi = if (params.light) 0x66FFFFFF.toInt() else 0x5AFFFFFF.toInt()
        val clear = Color.TRANSPARENT
        val dim = if (params.light) 0x12000000 else 0x0CFFFFFF
        // 同一条对角渐变让左上与右下同时起亮，中段留空 → 「左上、右下各一道光」
        val colors = intArrayOf(hi, clear, clear, hi)
        val positions = floatArrayOf(0f, 0.34f, 0.66f, 1f)
        val w = rect.width().coerceAtLeast(1f)
        val h = rect.height().coerceAtLeast(1f)
        edgePaint.shader = LinearGradient(0f, 0f, w, h, colors, positions, Shader.TileMode.CLAMP)
        innerEdgePaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(dim, clear, clear, dim), positions, Shader.TileMode.CLAMP,
        )
        edgePaint.strokeWidth = density * 1.6f
        innerEdgePaint.strokeWidth = density * 1f
    }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        // morph 动画每帧都会改 layoutParams → bounds 变化。
        // AGSL shader 靠 uSize uniform 适配尺寸，**绝不能在这里重建 shader**
        // （每帧反射构造一次 RuntimeShader 会明显掉帧）。只更新尺寸相关的渐变。
        updateSizeUniform()
        if (!agslReady) glassPaint.shader = fallbackGradient()
        applyEdgeColors()
        updateVolumeGradient()
    }

    private fun updateSizeUniform() {
        val s = shader ?: return
        if (!agslReady) return
        try {
            val w = rect.width()
            val h = rect.height()
            if (w > 0f && h > 0f) {
                SET_FLOAT2?.invoke(s, "uSize", w, h)
            }
        } catch (t: Throwable) {
            // uniform 写不进去时 AGSL 会把 uSize 当 (0,0)，底纹会变成高频噪点 → 立即降级
            Log.w(TAG, "AGSL uSize uniform failed, fallback: ${t.message}")
            agslReady = false
            glassPaint.shader = fallbackGradient()
        }
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        val r = cornerRadius.coerceAtMost(minOf(rect.width(), rect.height()) / 2f)

        // ②③④：AGSL 一次算完模糊提亮 / 折射色散 / 着色（降级时是渐变 + tint）
        canvas.drawRoundRect(rect, r, r, glassPaint)

        // ① 的体积感补充：底部极淡压暗，配合系统 elevation 投影
        canvas.drawRoundRect(rect, r, r, volumePaint)

        // ⑤ 边缘光：外圈一道 + 内侧缩进 2dp 再一道
        canvas.drawRoundRect(rect, r, r, edgePaint)
        val inset = density * 2f
        val inner = RectF(
            rect.left + inset, rect.top + inset,
            (rect.right - inset).coerceAtLeast(rect.left + inset),
            (rect.bottom - inset).coerceAtLeast(rect.top + inset),
        )
        canvas.drawRoundRect(inner, (r - inset).coerceAtLeast(0f), (r - inset).coerceAtLeast(0f), innerEdgePaint)
    }

    override fun setAlpha(alpha: Int) {
        glassPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        glassPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // ------------------------------------------------------------------
    // AGSL
    // ------------------------------------------------------------------

    private companion object {
        private const val TAG = "FlymeArkGlass"

        /** RuntimeShader.setFloatUniform(String, float, float)，反射持有，兼容低版本 */
        val SET_FLOAT2: Method? = try {
            if (Build.VERSION.SDK_INT < 33) null
            else Class.forName("android.graphics.RuntimeShader").getMethod(
                "setFloatUniform",
                String::class.java,
                java.lang.Float.TYPE,
                java.lang.Float.TYPE,
            )
        } catch (_: Throwable) {
            null
        }

        fun f(v: Float): String = String.format(Locale.US, "%.5f", v)

        fun channel(color: Int, shift: Int): String =
            f(((color shr shift) and 0xFF) / 255f)

        fun blend(a: Int, b: Int, ratio: Float): Int {
            fun ch(s: Int): Int {
                val av = (a shr s) and 0xFF
                val bv = (b shr s) and 0xFF
                return (av + (bv - av) * ratio).toInt().coerceIn(0, 255)
            }
            return Color.argb(255, ch(16), ch(8), ch(0))
        }
    }

    private fun buildAgsl(p: GlassParams): Shader? {
        if (SET_FLOAT2 == null) return null
        val src = """
            uniform float2 uSize;

            half3 sat(half3 c, float s) {
                half l = dot(c, half3(0.2126, 0.7152, 0.0722));
                return clamp(mix(half3(l, l, l), c, s), half3(0.0, 0.0, 0.0), half3(1.0, 1.0, 1.0));
            }

            half3 glassTex(float2 p) {
                float2 q = p / max(uSize, float2(1.0, 1.0));
                float a = sin(q.x * 7.0 + sin(q.y * 5.0) * 1.2) * 0.5 + 0.5;
                float b = sin(q.y * 9.0 - q.x * 3.0) * 0.5 + 0.5;
                float grain = fract(sin(dot(floor(p * 0.5), float2(12.9898, 78.233))) * 43758.5453);
                half3 base = half3(${channel(p.ambientRgb, 16)}, ${channel(p.ambientRgb, 8)}, ${channel(p.ambientRgb, 0)});
                half3 wave = half3(
                    (a - 0.5) * 0.075 + (b - 0.5) * 0.030,
                    (b - 0.5) * 0.070 + (a - 0.5) * 0.020,
                    ((a + b) * 0.5 - 0.5) * 0.060);
                // blur: higher blurPx lowers the fine-grain weight (low-pass on the texture)
                half grainWeight = ${f((0.012f / (1f + p.blurPx * 0.35f)).coerceIn(0.002f, 0.02f))};
                half g = (grain - 0.5) * grainWeight;
                return clamp(base + wave + half3(g, g, g), half3(0.0, 0.0, 0.0), half3(1.0, 1.0, 1.0));
            }

            half4 main(float2 coord) {
                float2 sz = max(uSize, float2(1.0, 1.0));
                float dx = min(coord.x, sz.x - coord.x);
                float dy = min(coord.y, sz.y - coord.y);
                float d = min(dx, dy);
                // refraction: clamp edge width so the center stays flat
                float edge = min(${f(p.edgePx)}, min(sz.x, sz.y) * 0.34);
                float k = 1.0 - smoothstep(0.0, edge, d);
                float sx = coord.x < sz.x - coord.x ? 1.0 : -1.0;
                float sy = coord.y < sz.y - coord.y ? 1.0 : -1.0;
                float2 n = normalize(float2(sx / max(dx, 1.0), sy / max(dy, 1.0)) + float2(0.0001, 0.0001));
                float2 off = n * ${f(p.edgeShift)} * k * k;
                float disp = ${f(p.dispersion)};
                half3 c;
                c.r = glassTex(coord + off * (1.0 + disp)).r;  // red bends more than blue
                c.g = glassTex(coord + off).g;
                c.b = glassTex(coord + off * (1.0 - disp)).b;
                // brightness + saturation
                c = sat(clamp(c * ${f(p.brightness)}, half3(0.0, 0.0, 0.0), half3(1.0, 1.0, 1.0)), ${f(p.saturation)});
                // tint layer
                half3 tint = half3(${channel(p.tintRgb, 16)}, ${channel(p.tintRgb, 8)}, ${channel(p.tintRgb, 0)});
                c = mix(c, tint, ${f(p.tintAlpha)});
                return half4(c, 1.0);
            }
        """.trimIndent()
        return try {
            val cls = Class.forName("android.graphics.RuntimeShader")
            cls.getConstructor(String::class.java).newInstance(src) as? Shader
        } catch (t: Throwable) {
            // AGSL 是运行时编译：语法/驱动不支持都会在这里失败，日志留痕便于定位
            Log.w(TAG, "AGSL compile failed, gradient fallback: ${t.message}")
            null
        }
    }
}

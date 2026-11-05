package com.flymeark

import android.graphics.Color
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections

/**
 * 全局毛玻璃参数统一入口（v3.15.42：接入液态玻璃的「着色层」浓度规范）。
 *
 * Flyme 所有玻璃面（下拉媒体卡 / 通知行 / 横屏横幅 / 低电量提示 / 锁屏插件 /
 * 状态栏热区）最终都汇到 `MzBlurUtils.setBackgroundBlurDrawable`，传参结构统一：
 *
 * ```
 * setBackgroundBlurDrawable(
 *     view, blurRadius = 180, cornerRadius = -1f, clipCornerRadius,
 *     color,            // ← args[4]，染色 = ARGB，透明度就藏在这里
 *     debug, blurAlpha, zAdjustment, Function0, Consumer
 * )
 * ```
 *
 * `BackgroundBlurDrawable` 的两个透明度各管一层：
 *  - `setColor(color)` → `mPaint.setColor(color)`，颜色（含 alpha）是盖在模糊上的染色；
 *  - `setAlpha(a)`     → 只写 `mAlpha`，控制交给 SurfaceFlinger 的模糊强度，不碰染色。
 * 所以改玻璃观感必须改 color 里的 alpha，改 setAlpha 没用。
 *
 * 系统给主玻璃的染色是 `0xB2FFFFFF`（浅）/ `0xB21A1A1A`（深），即 70%。
 *
 * ## v3.15.43：默认停用，理由是真机实测
 *
 * CSS 规范里的 `--glass-tint` 是**叠加在 backdrop-filter 模糊结果之上**的一层，
 * 6%–22% 成立；但这里的 `args[4]` 是这块玻璃**唯一的不透明基底**，独自承担文字
 * 对比度。v3.15.42 把它降到 17%（`#b2ffffff → #2bffffff`）后，锁屏壁纸的大片
 * 粉红/红色图案直接透出来 → 用户看到「背景变成红色」，系统文字也发灰不可读。
 * 两者不是同一层，不能共用一组数字。
 *
 * 深浅浓度规范继续作用于模块**自己的** 6 层玻璃（[ArkLiquidGlassDrawable]）；
 * 系统主玻璃恢复原生观感。区间常量保留，若以后确实要调系统玻璃，必须按「基底」
 * 重新定标（不低于 50%），而不是套用 CSS 的着色层数值。
 *
 * 注意：SystemUI 里还有 `0x66FFFFFF` / `0x1AFFFFFF` 这类更淡的遮罩，那是系统有意
 * 做出来的层次，即便开启改写也必须原样放过，只认 0xB2 这一档。
 */
internal object ArkGlobalGlassHook {

    /**
     * 是否改写系统主玻璃染色。默认 **false**。
     * 见文件头注释：v3.15.42 实测已证明按 CSS 着色层浓度改写会毁掉系统玻璃。
     */
    private const val REWRITE_SYSTEM_TINT = false

    private const val MZ_BLUR_UTILS = "com.flyme.systemui.utils.MzBlurUtils"
    private const val METHOD = "setBackgroundBlurDrawable"

    /** 系统主玻璃染色的原始 alpha（浅色 0xB2FFFFFF / 深色 0xB21A1A1A 共用） */
    private const val SOURCE_ALPHA = 0xB2     // 70%

    /** 明暗分界：tint 亮度高于此值视为「白色玻璃」 */
    private const val LIGHT_LUMA = 0.5f

    /** 浅色玻璃着色浓度区间：白 6%–22% */
    private const val LIGHT_LO = 0.06f
    private const val LIGHT_HI = 0.22f

    /** 深色玻璃着色浓度区间：rgb(20 20 24) 16%–38% */
    private const val DARK_LO = 0.16f
    private const val DARK_HI = 0.38f

    /** 每个调用方只打一条日志，避免刷屏 */
    private val logged: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        // v3.15.43：系统主玻璃是「基底」不是「着色层」，默认完全不改写。
        if (!REWRITE_SYSTEM_TINT) return
        ArkHooks.quiet("glass") {
            val cls = XposedHelpers.findClass(MZ_BLUR_UTILS, lpp.classLoader)
            // 4 个重载最终都汇到 10 参数那个（经 setBackgroundBlurDrawable$default），
            // 一次 hookAllMethods 即可覆盖全部玻璃面；$default 方法名不同，不会被误命中。
            XposedBridge.hookAllMethods(cls, METHOD, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args
                    // (view, blurRadius) 两参版本不带染色，放过
                    if (args.size < 5) return
                    val original = args[4] as? Int ?: return
                    // 只认系统主玻璃那一档；其他透明度是系统设计的层次，原样保留
                    if ((original ushr 24) != SOURCE_ALPHA) return

                    val rgb = original and 0x00FFFFFF
                    val light = luma(rgb) > LIGHT_LUMA
                    // args[0] 是承载玻璃的 View，用它拿 Context 做背景采样（结果带 2s 缓存）
                    val ctx = (args[0] as? View)?.context
                    val complexity = ctx?.let {
                        try {
                            ArkLiquidGlass.resolve(it, false).complexity
                        } catch (_: Throwable) {
                            0.5f
                        }
                    } ?: 0.5f

                    val lo = if (light) LIGHT_LO else DARK_LO
                    val hi = if (light) LIGHT_HI else DARK_HI
                    val tint = lo + (hi - lo) * complexity
                    val target = (tint * 255f).toInt().coerceIn(0, 255)
                    val replaced = (target shl 24) or rgb
                    args[4] = replaced
                    logOnce(original, replaced, light, tint, complexity)
                }
            })
        }
    }

    /** 感知亮度（sRGB 加权），只用来判定这块玻璃是浅色还是深色 */
    private fun luma(rgb: Int): Float {
        return (0.2126f * Color.red(rgb) +
            0.7152f * Color.green(rgb) +
            0.0722f * Color.blue(rgb)) / 255f
    }

    private fun logOnce(original: Int, replaced: Int, light: Boolean, tint: Float, complexity: Float) {
        val caller = callerName()
        if (!logged.add(caller + "#" + Integer.toHexString(original))) return
        XposedBridge.log(
            "[${ArkConstants.LOG_TAG}/glass] $caller  " +
                "#${Integer.toHexString(original)} → #${Integer.toHexString(replaced)}" +
                " (${if (light) "浅色玻璃" else "深色玻璃"} 着色 ${(tint * 100).toInt()}%" +
                " · 背景复杂度 ${String.format(java.util.Locale.US, "%.2f", complexity)})"
        )
    }

    /** 找到真正发起调用的业务类（跳过 MzBlurUtils、Xposed 框架帧和本模块自身） */
    private fun callerName(): String {
        for (frame in Throwable().stackTrace) {
            val name = frame.className
            if (name.contains(MZ_BLUR_UTILS)) continue
            if (name.startsWith("com.flymeark.")) continue
            if (name.startsWith("de.robv.")) continue
            if (name.startsWith("java.") || name.startsWith("kotlin.")) continue
            if (name.endsWith("XC_MethodHook") || name.contains("HookBridge")) continue
            if (name.contains("XposedBridge") || name.contains("MethodHook")) continue
            return name.substringAfterLast('.') + "." + frame.methodName
        }
        return "unknown"
    }
}

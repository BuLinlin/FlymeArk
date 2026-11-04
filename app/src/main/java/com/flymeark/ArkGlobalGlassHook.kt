package com.flymeark

import android.graphics.Color
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections

/**
 * 全局毛玻璃参数统一入口。
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
 * `BackgroundBlurDrawable` 的实现决定了两个透明度各管一层：
 *  - `setColor(color)`  → `mPaint.setColor(color)`，颜色（含 alpha）就是盖在模糊上的染色；
 *  - `setAlpha(a)`      → 只写 `mAlpha`，控制交给 SurfaceFlinger 的模糊强度，不碰染色。
 * 所以"玻璃白得发死"要改的是 color 里的 alpha，改 setAlpha 没用。
 *
 * 系统给主玻璃的染色是 `0xB2FFFFFF`（浅色）/ `0xB21A1A1A`（深色），70% 的底色糊在
 * 模糊层上，壁纸的颜色和饱和度被洗得只剩一层灰白。这里把这一档降到 58%/64%，
 * **RGB 原样保留**，只放开透明度 —— 等于把玻璃调透，不是换一套配色，
 * 因此不会和系统自己的主题色逻辑打架。
 *
 * 注意：SystemUI 里还有 `0x66FFFFFF` / `0x1AFFFFFF` 这类更淡的遮罩，那是系统有意
 * 做出来的层次，必须原样放过，只动 0xB2 这一档。
 */
internal object ArkGlobalGlassHook {

    private const val MZ_BLUR_UTILS = "com.flyme.systemui.utils.MzBlurUtils"
    private const val METHOD = "setBackgroundBlurDrawable"

    /** 系统主玻璃染色的原始 alpha（浅色 0xB2FFFFFF / 深色 0xB21A1A1A 共用） */
    private const val SOURCE_ALPHA = 0xB2     // 70%

    /** 浅色玻璃目标不透明度 */
    private const val LIGHT_ALPHA = 0x94      // 58%（保守档；43% 为极限档，待定稿）

    /** 深色玻璃目标不透明度 */
    private const val DARK_ALPHA = 0xA3       // 64%

    /** 明暗分界：tint 亮度高于此值视为"白色玻璃" */
    private const val LIGHT_LUMA = 0.5f

    /** 每个调用方只打一条日志，避免刷屏 */
    private val logged: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
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
                    val target = if (luma(rgb) > LIGHT_LUMA) LIGHT_ALPHA else DARK_ALPHA
                    val replaced = (target shl 24) or rgb
                    args[4] = replaced
                    logOnce(original, replaced)
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

    private fun logOnce(original: Int, replaced: Int) {
        val caller = callerName()
        if (!logged.add(caller + "#" + Integer.toHexString(original))) return
        XposedBridge.log(
            "[${ArkConstants.LOG_TAG}/glass] $caller  " +
                "#${Integer.toHexString(original)} → #${Integer.toHexString(replaced)}" +
                " (${if (luma(original and 0x00FFFFFF) > LIGHT_LUMA) "浅色玻璃" else "深色玻璃"})"
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

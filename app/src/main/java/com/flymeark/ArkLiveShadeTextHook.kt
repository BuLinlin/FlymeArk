package com.flymeark

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v3.15.20：实况胶囊**副显示位**文字/宽度修复（SystemUI 侧）。
 *
 * ## 背景：Flyme 的胶囊只有一个主显示位
 *
 * `LiveNotificationController.updateStatusBarIcons` 遍历 `mNotificationEntries`，
 * 给满足 `shouldShowLiveNotification` 的每条 live 通知分配显示位：
 *
 * | 位置 | showType | 字段 |
 * |---|---|---|
 * | 主显示位 | 0（音乐占主位时是 5） | `mDisplayNotificationInfo` |
 * | 副显示位 | 1 | `mShadeNotificationInfo`（只存 1 条） |
 * | 其余 | 2 / 3 | 只进 `mToShow`，不单独显示 |
 *
 * 关键在 `LiveNotificationView.setLiveNotificationInfo`：
 *
 * ```java
 * if (showType == 1 || showType == 6) {          // 副显示位
 *     setText(info.getTopDisplayText(), false);  // ← 用全局 topDisplayText
 *     progressBar.setProgress(100, false);
 * } else {                                        // 主显示位
 *     setText(info.getShowText(), animate);       // ← 用胶囊自己的内容
 * }
 * ```
 *
 * 而 `topDisplayText` 是**全局共享**的：只有 `mNotificationEntries.isEmpty()`
 * （没有任何通知）时才取 `getTopLyricsDisplayText("")`（歌词行），否则恒为空串。
 *
 * ## 两个可见故障，同一个根因
 *
 * 1. **消息胶囊占了主位时，音乐胶囊进副位 → 看不见。** 副位文字取空的
 *    `topDisplayText`。
 * 2. **更狠的是宽度**：`LiveNotificationView.getShowWidth()` 在
 *    `showType ∉ {0,4,5}` 时用 `mTopDisplayText` 算宽度，空串宽度 = 0，
 *    整个副位胶囊直接塌缩成 0 宽 —— 不是「文字空白」，是整条胶囊消失。
 *
 * ## 本 hook 做的事
 *
 * 只 hook 一个 getter：`LiveNotificationInfo.getTopDisplayText()`。
 * 返回 null / 空串时回退到该 info 自己的 `getShowText()`。
 *
 * - 副位**消息**胶囊 → 显示自己的消息正文
 * - 副位**音乐**胶囊 → 显示自己的歌词行（`mLyricsInofo.showText`）
 * - 有歌词可用时（无通知）行为完全不变，歌词仍是歌词
 *
 * 不回退 `showType`（早期版本试过把副位 1 强改成 0），因为 showType 还决定
 * `makeShadeVisible()`、进度条语义与副位布局，强改会让副胶囊按主位尺寸摆放。
 */
internal object ArkLiveShadeTextHook {

    private const val TAG = "liveShadeText"
    private const val INFO_CLS =
        "com.android.systemui.shared.livenotification.LiveNotificationInfo"

    /** setLiveNotificationInfo 会被频繁重放，同一内容只打一次日志 */
    @Volatile
    private var lastLogged: String? = null

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != ArkConstants.SYSTEMUI_PKG) return

        ArkHooks.quiet("$TAG getTopDisplayText") {
            XposedHelpers.findAndHookMethod(
                INFO_CLS, lpp.classLoader, "getTopDisplayText",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val top = param.result as? String
                            if (!top.isNullOrEmpty()) return

                            val show = XposedHelpers.callMethod(
                                param.thisObject, "getShowText"
                            ) as? String
                            if (show.isNullOrEmpty()) return

                            param.result = show

                            val pkg = XposedHelpers.callMethod(
                                param.thisObject, "getPackageName"
                            ) as? String
                            val sig = "$pkg|$show"
                            if (sig != lastLogged) {
                                lastLogged = sig
                                XposedBridge.log(
                                    "[${ArkConstants.LOG_TAG}] $TAG: topDisplayText empty → " +
                                        "fallback showText for $pkg: '$show'"
                                )
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG error: ${t.message}")
                        }
                    }
                }
            )
        }

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing in SystemUI")
    }
}

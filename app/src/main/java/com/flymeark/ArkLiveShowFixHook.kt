package com.flymeark

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v3.15.31：修复「消息胶囊先出现空白、隔一会儿才有文字」。
 *
 * ## 根因（`LiveNotificationController.updateStatusBarIcons` 反编译确认）
 *
 * 首次展示一条实况时，系统是这样走的：
 *
 * ```java
 * if (view.getNotificationKey() == null) goto 淡入;              // ① view 还没绑过任何 key
 * if (!view.getNotificationKey().equals(info.getNotificationKey())) goto 淡入;  // ② key 变了
 * view.setLiveNotificationInfo(info);                            // ③ 只有 key 相同才设内容
 * 淡入: startDisplayAlphaAnim();                                  // ④ 只做 alpha 动画
 * ```
 *
 * 也就是说：**一条实况从无到有（或换了 key）时，系统只把 view 淡入，压根不设内容**，
 * 内容要等下一次 `updateStatusBarIcons`（下一条通知 / 面板变化 / tick）才会补上。
 * 于是看到的就是「胶囊先出现，内容是空白，过一会儿才填上」——用户说的"延迟"正是这段空白期。
 *
 * 系统自己的音乐实况之所以不明显，是因为它长时间常驻、key 不变，几乎不会走 ①/② 分支。
 * 我们的消息胶囊每条消息都可能重新建立，就每次都撞上。
 *
 * ## 修法
 *
 * hook `startDisplayAlphaAnim()`：淡入之前，如果 `mDisplayNotificationInfo` 已经准备好了
 * （就是这次要显示的那条），直接把它喂给 view。内容与淡入同一帧生效，没有空白窗口。
 *
 * 只处理微信 / QQ 的胶囊（[ArkConstants.isMessageLiveApp]），音乐实况完全不受影响；
 * 且只在「view 的 key 与 info 的 key 不一致」时才补，不会打断正常更新。
 */
internal object ArkLiveShowFixHook {

    private const val TAG = "liveShowFix"

    private const val CTRL_CLS =
        "com.flyme.statusbar.livenotification.LiveNotificationController"

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != ArkConstants.SYSTEMUI_PKG) return

        ArkHooks.quiet("$TAG startDisplayAlphaAnim") {
            XposedHelpers.findAndHookMethod(
                CTRL_CLS, lpp.classLoader, "startDisplayAlphaAnim",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            prefill(param.thisObject)
                        } catch (t: Throwable) {
                            XposedBridge.log(
                                "[${ArkConstants.LOG_TAG}] $TAG prefill error: ${t.message}"
                            )
                        }
                    }
                }
            )
        }

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing in SystemUI")
    }

    /**
     * 把 `mDisplayNotificationInfo` 提前写进胶囊 view。
     * 只补「view key ≠ info key」的场景——key 相同说明内容早就设过了，再设会打断宽度动画。
     */
    private fun prefill(ctrl: Any) {
        val info = try {
            XposedHelpers.getObjectField(ctrl, "mDisplayNotificationInfo")
        } catch (_: Throwable) {
            null
        } ?: return

        val pkg = try {
            XposedHelpers.callMethod(info, "getPackageName") as? String
        } catch (_: Throwable) {
            null
        } ?: return
        if (!ArkConstants.isMessageLiveApp(pkg)) return

        val text = try {
            XposedHelpers.callMethod(info, "getShowText") as? String
        } catch (_: Throwable) {
            null
        }
        // 没有内容就别动，空文本会让 view 走 "setText: text is void" 分支
        if (text.isNullOrBlank()) return

        val newKey = try {
            XposedHelpers.callMethod(info, "getNotificationKey") as? String
        } catch (_: Throwable) {
            null
        }

        val view = try {
            XposedHelpers.getObjectField(ctrl, "mLiveNotificationDisplay") as? View
        } catch (_: Throwable) {
            null
        } ?: return

        val curKey = try {
            XposedHelpers.callMethod(view, "getNotificationKey") as? String
        } catch (_: Throwable) {
            null
        }
        if (newKey != null && newKey == curKey) return

        try {
            // 单参重载 = animate=true，宽度会从 0 弹性展开到文字宽度
            XposedHelpers.callMethod(view, "setLiveNotificationInfo", info)
            XposedBridge.log(
                "[${ArkConstants.LOG_TAG}] $TAG prefill: pkg=$pkg key=$newKey " +
                    "text='${text.take(24)}' (oldKey=$curKey)"
            )
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG prefill set failed: ${t.message}")
        }
    }
}

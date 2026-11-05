package com.flymeark

import android.view.View
import android.widget.TextView
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

        installUpdateRetry(lpp)

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing in SystemUI")
    }

    // ------------------------------------------------------------ 更新被丢弃时补刷

    private var mainHandler: android.os.Handler? = null

    private val retryScheduled = java.util.WeakHashMap<Any, Boolean>()
    private val retryCount = java.util.WeakHashMap<Any, Int>()

    /**
     * v3.15.33：`updateStatusBarIcons` 开头有三个提前 return，会**整次丢弃更新**：
     *
     * ```java
     * if (mAnimateType != 0) return;                    // 胶囊动画中
     * if (mNotificationPanelAnimateStarted) return;     // 面板动画中
     * if (!mIsGlobalLayout) return;                     // 全局布局未完成
     * ```
     *
     * 连续发消息时，第一条触发淡入动画，后面几条正好落在动画窗口里被丢掉，
     * 胶囊就停在第一条上不再变化——用户描述的「卡住」。
     *
     * 处理：判定「这次一定被丢弃」时，260ms 后补刷一次（走 private
     * `updateLiveNotification()`）。最多连补 3 次，成功一次就清零，避免打转。
     */
    private fun installUpdateRetry(lpp: XC_LoadPackage.LoadPackageParam) {
        ArkHooks.quiet("$TAG updateStatusBarIcons") {
            XposedHelpers.findAndHookMethod(
                CTRL_CLS, lpp.classLoader, "updateStatusBarIcons",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val dropped = try {
                            val ctrl = param.thisObject
                            (XposedHelpers.getIntField(ctrl, "mAnimateType") != 0) ||
                                XposedHelpers.getBooleanField(
                                    ctrl, "mNotificationPanelAnimateStarted"
                                ) ||
                                !XposedHelpers.getBooleanField(ctrl, "mIsGlobalLayout")
                        } catch (_: Throwable) {
                            false
                        }
                        param.setObjectExtra("dropped", dropped)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctrl = param.thisObject
                        if (param.getObjectExtra("dropped") == true) {
                            scheduleRetry(ctrl)
                        } else {
                            // 成功刷过一次 → 计数清零，下次动画还能继续补
                            retryCount.remove(ctrl)
                        }
                    }
                }
            )
        }
    }

    private fun scheduleRetry(ctrl: Any) {
        if (retryScheduled[ctrl] == true) return
        if ((retryCount[ctrl] ?: 0) >= 3) return
        retryScheduled[ctrl] = true
        val h = mainHandler ?: android.os.Handler(android.os.Looper.getMainLooper())
            .also { mainHandler = it }
        h.postDelayed({
            retryScheduled[ctrl] = false
            retryCount[ctrl] = (retryCount[ctrl] ?: 0) + 1
            try {
                XposedHelpers.callMethod(ctrl, "updateLiveNotification")
            } catch (_: Throwable) {
            }
        }, 260)
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
            // v3.15.33：改用 **animate=false 重载**。
            // 单参重载会走 setText(text, true) → startProgressBarAnim()，那条路径只启动
            // 弹簧宽度动画，文字与 layoutParams.width 都依赖动画回调；首次显示时
            // mLastTextWidth=0、view 可能还没完成布局，动画没跑起来 → 文字被裁掉、只剩
            // 图标宽的一条（真机截图实证）。
            // animate=false 分支是同步的：直接算宽度、写 layoutParams.width、setText，
            // 不依赖任何动画回调。
            XposedHelpers.callMethod(view, "setLiveNotificationInfo", info, false)

            // startScaleXAnim() 会把文字 alpha 压到 0 再弹回 1；如果那一轮被打断，
            // alpha 会永远停在 0（文字存在但看不见）。这里直接恢复。
            (XposedHelpers.getObjectField(view, "mLiveNotificationText") as? TextView)
                ?.let { if (it.alpha != 1f) it.alpha = 1f }

            XposedBridge.log(
                "[${ArkConstants.LOG_TAG}] $TAG prefill: pkg=$pkg key=$newKey " +
                    "text='${text.take(24)}' (oldKey=$curKey)"
            )
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG prefill set failed: ${t.message}")
        }
    }
}

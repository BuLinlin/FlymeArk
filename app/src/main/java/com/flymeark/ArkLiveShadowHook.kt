package com.flymeark

import android.app.NotificationManager
import android.content.Context
import android.os.IBinder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v3.15.24：实况影子通知的 SystemUI 侧配套。
 *
 * 双通知拆分后，微信/QQ 进程会额外 post 一条带 [ArkMessageLiveInjector.KEY_SHADOW] 标记的
 * 实况通知，专门喂给状态栏胶囊。这条通知对用户不该可见，因此：
 *
 * 1. **从通知栏列表过滤**（[installShadeFilter]）
 *    只拦 `ShadeListBuilder.applyFilters()` 的返回值 —— 它是"构建显示列表"时的过滤判据，
 *    返回 true 即该条目不进通知栏。而 `NotifCollection`（`LiveNotificationController` 的
 *    数据源）不受影响，胶囊照常显示。
 *    注意不能去动 `NotifCollection` / `sbn.isLive()`：那会连带切断胶囊的点击追踪
 *    （`addTrackingLiveExpandableView`），v3.15.22 已经踩过这个坑。
 *
 * 2. **原通知消失时同步撤掉影子**（[installRemovalSync]）
 *    用户在通知栏 × 掉或滑掉原生卡片后，App 不会回调我们，所以监听
 *    `NotificationListener.onNotificationRemoved`：微信/QQ 的普通通知被移除 → 清掉影子，
 *    胶囊随之消失。清别人家的通知走 `IStatusBarService.onNotificationClear`
 *    （SystemUI 没有权限直接 cancel 第三方通知）。
 */
internal object ArkLiveShadowHook {

    private const val TAG = "liveShadow"
    private const val SHADE_LIST_BUILDER =
        "com.android.systemui.statusbar.notification.collection.ShadeListBuilder"
    private const val NOTIF_LISTENER = "com.android.systemui.statusbar.NotificationListener"

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != ArkConstants.SYSTEMUI_PKG) return
        installShadeFilter(lpp)
        installRemovalSync(lpp)
        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing in SystemUI")
    }

    // ---------------------------------------------------------------- 通知栏过滤

    private fun installShadeFilter(lpp: XC_LoadPackage.LoadPackageParam) {
        ArkHooks.quiet("$TAG applyFilters") {
            XposedHelpers.findAndHookMethod(
                SHADE_LIST_BUILDER, lpp.classLoader, "applyFilters",
                "com.android.systemui.statusbar.notification.collection.NotificationEntry",
                Long::class.javaPrimitiveType,
                java.util.List::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val entry = param.args[0] ?: return
                            val sbn = XposedHelpers.callMethod(entry, "getSbn") ?: return
                            val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String
                                ?: return
                            if (!ArkConstants.isMessageLiveApp(pkg)) return
                            val n = XposedHelpers.callMethod(sbn, "getNotification") ?: return
                            val extras = XposedHelpers.callMethod(n, "getExtras") as? android.os.Bundle
                                ?: return
                            if (!extras.getBoolean(ArkMessageLiveInjector.KEY_SHADOW, false)) return
                            // 过滤掉：不进通知栏列表，但仍在 NotifCollection 里 → 胶囊正常
                            param.result = true
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        }
    }

    // ---------------------------------------------------------------- 销毁同步

    private fun installRemovalSync(lpp: XC_LoadPackage.LoadPackageParam) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val sbn = param.args.getOrNull(0) ?: return
                    val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                    if (!ArkConstants.isMessageLiveApp(pkg)) return
                    val n = XposedHelpers.callMethod(sbn, "getNotification") ?: return
                    val extras = XposedHelpers.callMethod(n, "getExtras") as? android.os.Bundle
                        ?: return
                    // 影子自己被移除时不用再处理
                    if (extras.getBoolean(ArkMessageLiveInjector.KEY_SHADOW, false)) return
                    clearShadowNotification(param.thisObject, pkg)
                } catch (_: Throwable) {
                }
            }
        }

        ArkHooks.quiet("$TAG onNotificationRemoved(2)") {
            XposedHelpers.findAndHookMethod(
                NOTIF_LISTENER, lpp.classLoader, "onNotificationRemoved",
                "android.service.notification.StatusBarNotification",
                "android.service.notification.NotificationListenerService\$RankingMap",
                hook
            )
        }
        ArkHooks.quiet("$TAG onNotificationRemoved(3)") {
            XposedHelpers.findAndHookMethod(
                NOTIF_LISTENER, lpp.classLoader, "onNotificationRemoved",
                "android.service.notification.StatusBarNotification",
                "android.service.notification.NotificationListenerService\$RankingMap",
                Int::class.javaPrimitiveType,
                hook
            )
        }
    }

    private fun clearShadowNotification(listener: Any, pkg: String) {
        try {
            val ctx = XposedHelpers.callMethod(listener, "getContext") as? Context
                ?: XposedHelpers.getObjectField(listener, "mContext") as? Context
                ?: return
            val uid = ctx.packageManager.getApplicationInfo(pkg, 0).uid
            val key = "0|$pkg|${ArkMessageLiveInjector.SHADOW_ID}|" +
                "${ArkMessageLiveInjector.SHADOW_TAG}|$uid"

            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "statusbar") as? IBinder ?: return
            val iface = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)

            val visibilityCls =
                Class.forName("com.android.internal.statusbar.NotificationVisibility")
            iface.javaClass.getMethod(
                "onNotificationClear",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                visibilityCls
            ).invoke(iface, pkg, 0, key, 1, 1, null)

            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG shadow cleared for $pkg")
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG clear shadow failed: ${t.message}")
        }
    }
}

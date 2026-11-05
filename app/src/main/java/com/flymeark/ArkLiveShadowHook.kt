package com.flymeark

import android.os.Bundle
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v3.15.26：实况影子通知的 SystemUI 侧配套。
 *
 * 双通知拆分后，微信/QQ 进程会额外 post 一条带 [ArkMessageLiveInjector.KEY_SHADOW] 标记的
 * 实况通知，专门喂给状态栏胶囊。这条通知对用户不该可见，因此：
 *
 * 1. **行级隐藏**（[installRowHide]）
 *    反编译 + uiautomator 确认：通知栏里的实况卡就是 `notification_stack_scroller` 里的一个
 *    `expandableNotificationRow`（内容用 framework 实况模板 `rl_live_base`/`rl_header_live`）。
 *    尝试过在 `ShadeListBuilder.applyFilters` 数据层过滤——hook 安装成功但该方法在 Flyme
 *    管线里从未被调用（探针 0 命中），放弃。
 *    改在 `ExpandableNotificationRow` 绑定必经的 `ExpandableView.setLiveNotification` 里：
 *    影子行 setVisibility(GONE)，非影子行若曾被我们隐藏则恢复 VISIBLE（行会被回收复用）。
 *    `NotifCollection` 完全不动 → 胶囊/AOD/点击/滑动全部正常。
 *
 * 2. **原通知消失时同步撤掉影子**（[installRemovalSync]）
 *    用户在通知栏 × 掉或滑掉原生卡片后，App 不会回调我们，所以监听
 *    `NotificationListener.onNotificationRemoved`：微信/QQ 的普通通知被移除 → 清掉影子，
 *    胶囊随之消失。清别人家的通知走 `IStatusBarService.onNotificationClear`
 *    （SystemUI 没有权限直接 cancel 第三方通知）。
 */
internal object ArkLiveShadowHook {

    private const val TAG = "liveShadow"
    private const val NOTIF_LISTENER = "com.android.systemui.statusbar.NotificationListener"
    private const val ROW_CLS =
        "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    private const val EXPANDABLE_CLS =
        "com.android.systemui.statusbar.notification.row.ExpandableView"

    /** row.setTag 用的标记值 */
    private const val TAG_HIDDEN = "flymeark_hidden_shadow"

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != ArkConstants.SYSTEMUI_PKG) return
        installRowHide(lpp)
        installRemovalSync(lpp)
        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing in SystemUI")
    }

    // ---------------------------------------------------------------- 行级隐藏

    private fun installRowHide(lpp: XC_LoadPackage.LoadPackageParam) {
        ArkHooks.quiet("$TAG setLiveNotification") {
            XposedHelpers.findAndHookMethod(
                EXPANDABLE_CLS, lpp.classLoader, "setLiveNotification",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val row = param.thisObject as? View ?: return
                            if (row.javaClass.name != ROW_CLS) return
                            val entry = XposedHelpers.getObjectField(row, "mEntry") ?: return
                            val sbn = XposedHelpers.callMethod(entry, "getSbn") ?: return
                            val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String
                            var shadow = false
                            if (pkg != null && ArkConstants.isMessageLiveApp(pkg)) {
                                val n = XposedHelpers.callMethod(sbn, "getNotification")
                                val extras = XposedHelpers.callMethod(n, "getExtras") as? Bundle
                                shadow = extras?.getBoolean(
                                    ArkMessageLiveInjector.KEY_SHADOW, false
                                ) == true
                            }
                            val wasHidden = row.tag == TAG_HIDDEN
                            if (shadow && !wasHidden) {
                                row.tag = TAG_HIDDEN
                                row.visibility = View.GONE
                            } else if (!shadow && wasHidden) {
                                // 行被回收复用成了别的通知：恢复显示
                                row.tag = null
                                row.visibility = View.VISIBLE
                            }
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
                    val extras = XposedHelpers.callMethod(n, "getExtras") as? Bundle ?: return
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
            val ctx = try {
                XposedHelpers.callMethod(listener, "getContext") as? android.content.Context
            } catch (_: Throwable) {
                null
            } ?: XposedHelpers.getObjectField(listener, "mContext") as? android.content.Context
                ?: return
            val uid = ctx.packageManager.getApplicationInfo(pkg, 0).uid
            val key = "0|$pkg|${ArkMessageLiveInjector.SHADOW_ID}|" +
                "${ArkMessageLiveInjector.SHADOW_TAG}|$uid"

            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "statusbar") as? android.os.IBinder ?: return
            val iface = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java)
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

package com.flymeark

import android.os.Bundle
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap

/**
 * v3.15.27：实况影子通知的 SystemUI 侧配套。
 *
 * 双通知拆分后，微信/QQ 进程会额外 post 一条带 [ArkMessageLiveInjector.KEY_SHADOW] 标记的
 * 实况通知，专门喂给状态栏胶囊。这条通知对用户不该可见，因此：
 *
 * 1. **行级隐藏**（[installRowHide]）
 *    反编译 + uiautomator 确认：通知栏里的实况卡就是 `notification_stack_scroller` 里的一个
 *    `expandableNotificationRow`（内容用 framework 实况模板 `rl_live_base`/`rl_header_live`）。
 *    尝试过在 `ShadeListBuilder.applyFilters` 数据层过滤——hook 安装成功但该方法在 Flyme
 *    管线里从未被调用（探针 0 命中），放弃。
 *
 *    v3.15.26 用 `setVisibility(GONE)` 只挡得住第一次绑定：影子通知更新时行被复用，系统
 *    重算布局又把它设回 VISIBLE，玻璃卡复发。
 *
 *    v3.15.27 改为「高度钳零」：hook `ExpandableNotificationRow` 的四个高度方法
 *    （`getIntrinsicHeight` / `getMinHeight()` / `getMinHeight(boolean)` /
 *    `getCollapsedHeight` / `getMaxExpandHeight`），影子行一律返回 0，并把 alpha 设 0。
 *    这些方法是**每次布局都会调用**的，系统重置 visibility 也绕不过去，因此稳定。
 *    影子行仍留在 `NotifCollection` 里 → 状态栏胶囊 / AOD / 点击 / 滑动全部正常。
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

    /**
     * 当前被判定为「影子行」的视图集合。
     * 用 WeakHashMap 而不是 View.tag：tag 可能被系统内部复用，弱引用集合更安全。
     * 每次 `setLiveNotification` 都会刷新（绑定必经之路），所以复用后不会残留。
     */
    private val hiddenRows: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** 高度方法清单：方法名 → 参数类型 */
    private val HEIGHT_METHODS: List<Pair<String, Array<Class<*>>>> = listOf(
        "getIntrinsicHeight" to emptyArray<Class<*>>(),
        "getMinHeight" to emptyArray<Class<*>>(),
        "getMinHeight" to arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!),
        "getCollapsedHeight" to emptyArray<Class<*>>(),
        "getMaxExpandHeight" to emptyArray<Class<*>>()
    )

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != ArkConstants.SYSTEMUI_PKG) return
        installRowHide(lpp)
        installRemovalSync(lpp)
        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing in SystemUI")
    }

    // ---------------------------------------------------------------- 行级隐藏

    private fun installRowHide(lpp: XC_LoadPackage.LoadPackageParam) {
        // ① 绑定阶段判定：这条行是不是影子通知
        ArkHooks.quiet("$TAG setLiveNotification") {
            XposedHelpers.findAndHookMethod(
                EXPANDABLE_CLS, lpp.classLoader, "setLiveNotification",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val row = param.thisObject as? View ?: return
                            if (row.javaClass.name != ROW_CLS) return
                            val shadow = isShadowRow(row)
                            val wasHidden = hiddenRows.contains(row)
                            if (shadow && !wasHidden) {
                                hiddenRows.add(row)
                                row.alpha = 0f
                            } else if (!shadow && wasHidden) {
                                // 行被回收复用成了别的通知：解除隐藏
                                hiddenRows.remove(row)
                                row.alpha = 1f
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        }

        // ② 布局阶段钳零：每帧布局都会走这几个方法，系统重置 visibility 也绕不过。
        //    例外：**行处于展开/横幅状态时不钳零**——点胶囊弹出的实况大卡就是这一行的
        //    展开形态，一律压 0 会把它一起吃掉（v3.15.28 用户实测反馈）。
        for ((name, args) in HEIGHT_METHODS) {
            ArkHooks.quiet("$TAG $name(${args.size})") {
                XposedHelpers.findAndHookMethod(
                    ROW_CLS, lpp.classLoader, name, *args,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val row = param.thisObject as? View ?: return
                                if (!hiddenRows.contains(row)) return
                                if (isRowShowing(row)) {
                                    // 展开态：恢复可见与不透明，让展开大卡正常渲染
                                    if (row.alpha != 1f) row.alpha = 1f
                                    return
                                }
                                if (row.alpha != 0f) row.alpha = 0f
                                param.result = 0
                            } catch (_: Throwable) {
                            }
                        }
                    }
                )
            }
        }
    }

    /** 行是否处于「需要显示」的状态（展开 / 横幅 / AOD） */
    private fun isRowShowing(row: View): Boolean {
        return try {
            val expanded = XposedHelpers.callMethod(row, "isExpanded") as? Boolean ?: false
            if (expanded) {
                true
            } else {
                val headsUp = try {
                    XposedHelpers.callMethod(row, "isHeadsUpState") as? Boolean ?: false
                } catch (_: Throwable) {
                    false
                }
                headsUp
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 反射判断某行对应的通知是不是我们的影子通知 */
    private fun isShadowRow(row: View): Boolean {
        return try {
            val entry = XposedHelpers.getObjectField(row, "mEntry") ?: return false
            val sbn = XposedHelpers.callMethod(entry, "getSbn") ?: return false
            val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return false
            if (!ArkConstants.isMessageLiveApp(pkg)) return false
            // 优先用 tag 判定（跨进程最稳）：影子通知固定 tag = flymeark_live_msg。
            // 实测 SystemUI 进程里反射调 Notification.getExtras() 会抛
            // NoSuchMethodError（Flyme 改动），extras 判定完全不可用 —— v3.15.27 探针证实。
            val tag = XposedHelpers.callMethod(sbn, "getTag") as? String
            if (tag == ArkMessageLiveInjector.SHADOW_TAG) return true
            val n = XposedHelpers.callMethod(sbn, "getNotification") ?: return false
            val extras = XposedHelpers.callMethod(n, "getExtras") as? Bundle ?: return false
            extras.getBoolean(ArkMessageLiveInjector.KEY_SHADOW, false)
        } catch (_: Throwable) {
            false
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
                    // 用 tag 判定是不是影子自己（SystemUI 里 Notification.getExtras 会抛异常，
                    // v3.15.27 探针证实：整段 try 被吞 → 清除逻辑从来没执行过，胶囊永驻）
                    val tag = try {
                        XposedHelpers.callMethod(sbn, "getTag") as? String
                    } catch (_: Throwable) {
                        null
                    }
                    if (tag == ArkMessageLiveInjector.SHADOW_TAG) return
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

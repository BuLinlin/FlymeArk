package com.flymeark

import android.os.Bundle
import android.util.Log
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
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

    /**
     * 导航影子绑定的那一行（弱引用，v3.15.66）。
     *
     * 会话结束时用它调 `setLiveNotification(false)` 解除 live 标记 —— 只 cancel 通知
     * 有时不足以让 Flyme 的 live 容器立刻消失（胶囊残留）。
     */
    private var navRowRef: WeakReference<View>? = null

    /** 正在解除导航行：抑制自己那个 `setLiveNotification` after-hook（否则会把行再次压成 alpha=0） */
    private var releasingNavRow = false

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
                            if (releasingNavRow) return
                            val row = param.thisObject as? View ?: return
                            if (row.javaClass.name != ROW_CLS) return
                            val shadow = isShadowRow(row)
                            // v3.15.66：记住导航影子那一行，会话结束时要用它解除 live 标记
                            if (shadow && shadowTagOfRow(row) == ArkNavLiveInjector.NAV_SHADOW_TAG) {
                                navRowRef = WeakReference(row)
                            }
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
    private fun isShadowRow(row: View): Boolean = shadowTagOfRow(row) != null

    /**
     * 某行对应的影子 tag（消息 / 导航）；不是影子返回 null。
     *
     * 从 [isShadowRow] 抽出来（v3.15.66）是为了在绑定阶段区分「消息影子」与「导航影子」——
     * 会话结束时要单独解除**导航**那一行的 live 标记。
     */
    private fun shadowTagOfRow(row: View): String? {
        return try {
            val entry = XposedHelpers.getObjectField(row, "mEntry") ?: return null
            val sbn = XposedHelpers.callMethod(entry, "getSbn") ?: return null
            val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return null
            if (!ArkConstants.isLiveShadowApp(pkg)) return null
            // 优先用 tag 判定（跨进程最稳）：影子通知 tag 固定，
            // 消息 = flymeark_live_msg，导航 = flymeark_live_nav（v3.15.51）。
            // 实测 SystemUI 进程里反射调 Notification.getExtras() 会抛
            // NoSuchMethodError（Flyme 改动），extras 判定完全不可用 —— v3.15.27 探针证实。
            val tag = XposedHelpers.callMethod(sbn, "getTag") as? String
            if (tag == ArkMessageLiveInjector.SHADOW_TAG) return tag
            if (tag == ArkNavLiveInjector.NAV_SHADOW_TAG) return tag
            val n = XposedHelpers.callMethod(sbn, "getNotification") ?: return null
            val extras = XposedHelpers.callMethod(n, "getExtras") as? Bundle ?: return null
            if (extras.getBoolean(ArkMessageLiveInjector.KEY_SHADOW, false)) {
                ArkMessageLiveInjector.SHADOW_TAG
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- 销毁同步

    private fun installRemovalSync(lpp: XC_LoadPackage.LoadPackageParam) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val sbn = param.args.getOrNull(0) ?: return
                    val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                    if (!ArkConstants.isLiveShadowApp(pkg)) return
                    // 用 tag 判定是不是影子自己（SystemUI 里 Notification.getExtras 会抛异常，
                    // v3.15.27 探针证实：整段 try 被吞 → 清除逻辑从来没执行过，胶囊永驻）
                    val tag = try {
                        XposedHelpers.callMethod(sbn, "getTag") as? String
                    } catch (_: Throwable) {
                        null
                    }
                    if (tag == ArkMessageLiveInjector.SHADOW_TAG) return
                    if (tag == ArkNavLiveInjector.NAV_SHADOW_TAG) {
                        // v3.15.66（R4）：**导航影子被移除 = 会话结束**。
                        // 这是本进程能拿到的、最可靠的「导航真的结束了」信号
                        // （高德侧的 farewell / 源通知消失都会走到 cancel）。
                        // 必须转告展开卡，否则 ArkNavPanelActivity 会一直浮在桌面 / 多任务上。
                        releaseNavLiveRow()
                        ArkLiveExpandHook.onNavSessionEnded(pkg)
                        return
                    }
                    clearShadowNotification(param.thisObject, pkg)
                    // 导航的原通知没了 = 导航结束，顺手通知导航注入器「别再更新胶囊」。
                    // 注意：这里在 SystemUI 进程，改的是本进程那份 ArkNavLiveInjector 的状态，
                    // 跨不到高德进程；真正兜住「胶囊复活」的是高德侧的
                    // navNotificationAlive() 判定（见 ArkNavLiveInjector.updateShadowContent）。
                    if (ArkConstants.isNavLiveApp(pkg)) ArkNavLiveInjector.onNavEnded()
                } catch (t: Throwable) {
                    XposedBridge.log(
                        "[${ArkConstants.LOG_TAG}] $TAG removal sync error: ${Log.getStackTraceString(t)}"
                    )
                }
            }
        }

        // 只 hook 3 参版本：SystemUI 的 2 参 onNotificationRemoved 内部会转调 3 参，
        // 两个都 hook 会导致同一次移除触发两次清理（v3.15.34 日志已见重复调用）。
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

    /**
     * 解除导航影子那一行的 **live 标记**（v3.15.66，R4/D2）。
     *
     * 为什么需要：导航结束时我们只是 `cancelNotification` 掉影子，但 Flyme 的实况容器
     * （胶囊）有时不会立刻跟着消失 —— 用户看到的就是「退出导航后胶囊还挂着」。
     * 对那一行调 `setLiveNotification(false)` 是系统自己的「这一行不再是实况」入口，
     * 比我们手动改 visibility 更干净（不会和回收复用打架）。
     *
     * 三个保险：
     *  1. 弱引用：行被回收后引用自然失效，不会拦住 GC；
     *  2. `releasingNavRow` 抑制自己的 after-hook —— 否则我们会把这一行又压回 alpha=0；
     *  3. 全程 try/catch：这是 SystemUI 进程，方法签名对不上时只能降级（打日志），不能崩。
     */
    private fun releaseNavLiveRow() {
        try {
            val row = navRowRef?.get() ?: return
            navRowRef = null
            releasingNavRow = true
            try {
                XposedHelpers.callMethod(row, "setLiveNotification", false)
            } finally {
                releasingNavRow = false
            }
            XposedBridge.log("$TAG: nav live row released (setLiveNotification=false)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: nav live row release failed: ${t.message}")
        }
    }

    /**
     * 清掉某 App 的影子通知。
     *
     * v3.15.34 走 `IStatusBarService.onNotificationClear` 一直失败（日志只有
     * `clear shadow failed: null`，异常 message 为 null 说明是 NPE，拿不到堆栈就没法定位）。
     *
     * v3.15.35 改为**优先用 listener 自己的 `cancelNotification(key)`**：
     * SystemUI 的 `NotificationListener extends NotificationListenerService`，
     * 本身就有监听器权限，取消一条别人发的通知是这个类的本职能力，不需要拼 uid / 反射 binder。
     * key 也优先从 `getActiveNotifications()` 里直接取真实值，不自己拼。
     *
     * v3.15.51：影子分两种（消息 / 导航），tag 与 id 都不一样，按包名先定出是哪一种。
     */
    private fun clearShadowNotification(listener: Any, pkg: String) {
        val tag = shadowTagOf(pkg)
        val id = shadowIdOf(pkg)
        val key = activeShadowKey(listener, pkg, tag) ?: buildShadowKey(listener, pkg, tag, id)
        if (key == null) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG shadow key unresolved for $pkg")
            return
        }
        try {
            XposedHelpers.callMethod(listener, "cancelNotification", key)
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG shadow cancelled for $pkg key=$key")
            return
        } catch (t: Throwable) {
            XposedBridge.log(
                "[${ArkConstants.LOG_TAG}] $TAG listener cancel failed: ${Log.getStackTraceString(t)}"
            )
        }
        clearViaStatusBarService(listener, pkg, key)
    }

    /** 某个包对应哪一种影子通知（消息 / 导航） */
    private fun shadowTagOf(pkg: String): String =
        if (ArkConstants.isNavLiveApp(pkg)) {
            ArkNavLiveInjector.NAV_SHADOW_TAG
        } else {
            ArkMessageLiveInjector.SHADOW_TAG
        }

    private fun shadowIdOf(pkg: String): Int =
        if (ArkConstants.isNavLiveApp(pkg)) {
            ArkNavLiveInjector.NAV_SHADOW_ID
        } else {
            ArkMessageLiveInjector.SHADOW_ID
        }

    /** 从 listener 当前持有的活动通知里找出影子的真实 key（最可靠，不依赖 key 格式） */
    private fun activeShadowKey(listener: Any, pkg: String, shadowTag: String): String? = try {
        val arr = XposedHelpers.callMethod(listener, "getActiveNotifications") as? Array<*>
        arr?.firstNotNullOfOrNull { item ->
            if (item == null) return@firstNotNullOfOrNull null
            val p = XposedHelpers.callMethod(item, "getPackageName") as? String
            val tag = XposedHelpers.callMethod(item, "getTag") as? String
            if (p == pkg && tag == shadowTag) {
                XposedHelpers.callMethod(item, "getKey") as? String
            } else {
                null
            }
        }
    } catch (t: Throwable) {
        XposedBridge.log(
            "[${ArkConstants.LOG_TAG}] $TAG active notifications failed: ${Log.getStackTraceString(t)}"
        )
        null
    }

    /** 手工拼 key（兜底）：`0|pkg|id|tag|uid` */
    private fun buildShadowKey(
        listener: Any,
        pkg: String,
        shadowTag: String,
        shadowId: Int
    ): String? = try {
        val ctx = listenerContext(listener) ?: return null
        val uid = ctx.packageManager.getApplicationInfo(pkg, 0).uid
        "0|$pkg|$shadowId|$shadowTag|$uid"
    } catch (t: Throwable) {
        XposedBridge.log(
            "[${ArkConstants.LOG_TAG}] $TAG build key failed: ${Log.getStackTraceString(t)}"
        )
        null
    }

    private fun listenerContext(listener: Any): android.content.Context? {
        try {
            (XposedHelpers.callMethod(listener, "getContext") as? android.content.Context)
                ?.let { return it }
        } catch (_: Throwable) {
        }
        for (f in arrayOf("mContext", "mBarServiceContext")) {
            try {
                (XposedHelpers.getObjectField(listener, f) as? android.content.Context)
                    ?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** 最后兜底：直接找 statusbar 服务清通知 */
    private fun clearViaStatusBarService(listener: Any, pkg: String, key: String) {
        try {
            val ctx = listenerContext(listener) ?: return
            val uid = ctx.packageManager.getApplicationInfo(pkg, 0).uid
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "statusbar") as? android.os.IBinder ?: return
            val iface = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder) ?: return

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

            XposedBridge.log(
                "[${ArkConstants.LOG_TAG}] $TAG shadow cleared via statusbar for $pkg uid=$uid"
            )
        } catch (t: Throwable) {
            XposedBridge.log(
                "[${ArkConstants.LOG_TAG}] $TAG clear via statusbar failed: ${Log.getStackTraceString(t)}"
            )
        }
    }
}

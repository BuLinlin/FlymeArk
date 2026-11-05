package com.flymeark

import android.content.Context
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v2.3（Part F）：在 system_server 进程工作。
 *
 * **v3.15.11 起已停用「强制放行」** —— 它会把「设置 → 实况通知」的开关架空，
 * 用户关掉某个 App 后胶囊照样出现。现在改由 SystemUI 侧的 [LiveAllowGate]
 * 按「当前播放 App」的开关真值决定。
 *
 * **v3.15.15：新增「用户开关动作」感知。**
 *
 * 真机取证（2026-11-04 18:37）：`getNotificationLiveAllowForPackage` 的 raw 值
 * 会在播放通知重建（切歌/进度刷新/点收藏）时瞬时抖动，用户明明只关了一次开关，
 * raw 却 false→true→false→true 反复跳。SystemUI 侧无法靠 raw 区分「用户真关」和
 * 「通知重建抖动」，只能加延迟复核（3s）——结果是关开关要等 3 秒才生效（不实时）。
 *
 * 但「用户主动关/开开关」这个动作，一定会走
 * `INotificationManager#setNotificationLiveAllowForPackage(pkg, uid, allow)`，
 * 这是唯一可靠、无抖动的信号。本类 hook 这个方法，把用户动作广播给 SystemUI，
 * 让关闭**实时生效**；get 侧的延迟复核继续留着防「通知重建」抖动。
 */
internal object ArkLiveAllowHook {

    private const val TAG = "liveAllow"

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != "android" && lpp.packageName != "system") return

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG: hook setNotificationLiveAllowForPackage")

        // v3.15.17：`setNotificationLiveAllowForPackage(String,int,boolean)` 的实现不在
        // `NotificationManagerService` 本身，而在它的内部类 `NotificationManagerService$12`
        // （父类是 `INotificationManager$Stub`，即 Binder 服务端实现）。直接 hook 外层类会
        // 报 NoSuchMethodError。这里遍历所有 `NotificationManagerService$N` 内部类，找到
        // 真正声明该方法签名的那个再 hook，避免 `$12` 编号随 ROM 版本变化而失效。
        // v3.15.31：即使进程判断失手（例如被 RemoteViews inflate 误触发到 SystemUI），
        // 也不能让 ClassNotFound 逃逸 —— 它会让 LayoutInflater.createView 抛异常，
        // 整条通知布局 inflate 失败。这里兜住并只记一条日志。
        val nmCls = try {
            XposedHelpers.findClass(
                "com.android.server.notification.NotificationManagerService", lpp.classLoader
            )
        } catch (t: Throwable) {
            XposedBridge.log(
                "[${ArkConstants.LOG_TAG}] $TAG: NotificationManagerService not found " +
                    "in ${lpp.packageName}, skip (${t.javaClass.simpleName})"
            )
            return
        }

        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val pkg = param.args[0] as? String ?: return
                    if (!ArkConstants.isMediaApp(pkg)) return
                    val allow = param.args[2] as? Boolean ?: return
                    XposedBridge.log(
                        "[${ArkConstants.LOG_TAG}] $TAG: user toggled live for $pkg -> $allow"
                    )
                    notifySystemUI(pkg, allow)
                } catch (t: Throwable) {
                    XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG hook error: ${t.message}")
                }
            }
        }

        fun tryHook(m: java.lang.reflect.Method?): Boolean {
            if (m == null) return false
            XposedBridge.log(
                "[${ArkConstants.LOG_TAG}] $TAG: hooking ${m.declaringClass.name}#${m.name}"
            )
            XposedBridge.hookMethod(m, callback)
            return true
        }

        fun findIn(cls: Class<*>): java.lang.reflect.Method? = try {
            cls.getDeclaredMethod(
                "setNotificationLiveAllowForPackage",
                String::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
        } catch (_: Throwable) {
            null
        }

        var hooked = false
        // ① 内部类（早期版本 / 通用 ROM：方法在 INotificationManager$Stub 的实现里）
        for (inner in nmCls.declaredClasses) {
            if (!inner.name.startsWith("com.android.server.notification.NotificationManagerService$")) continue
            if (tryHook(findIn(inner))) {
                hooked = true
                break
            }
        }
        // ② 自身及父类链（Flyme 可能把实现放在别的类里）
        if (!hooked) {
            var c: Class<*>? = nmCls
            while (c != null && !hooked) {
                if (tryHook(findIn(c))) hooked = true
                c = c.superclass
            }
        }
        // ③ RankingConfig 接口（Flyme 把签名声明在接口里，实现在私有类）
        if (!hooked) {
            for (inner in nmCls.declaredClasses) {
                for (iface in inner.interfaces) {
                    if (iface.name.endsWith("RankingConfig")) {
                        XposedBridge.log(
                            "[${ArkConstants.LOG_TAG}] $TAG: RankingConfig impl = ${inner.name}"
                        )
                        if (tryHook(findIn(inner))) hooked = true
                    }
                }
            }
        }
        if (!hooked) {
            XposedBridge.log(
                "[${ArkConstants.LOG_TAG}] $TAG: setNotificationLiveAllowForPackage not found; " +
                    "开关实时同步不可用（胶囊功能不受影响）"
            )
        }
    }

    /** 发广播给 SystemUI 进程，让关闭/开启实时生效 */
    private fun notifySystemUI(pkg: String, allow: Boolean) {
        try {
            val ctx = systemContext() ?: run {
                XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG: no system context, skip broadcast")
                return
            }
            val intent = Intent(ArkConstants.ACTION_LIVE_ALLOW_CHANGED).apply {
                setPackage(ArkConstants.SYSTEMUI_PKG)
                putExtra(ArkConstants.EXTRA_LIVE_PKG, pkg)
                putExtra(ArkConstants.EXTRA_LIVE_ALLOW, allow)
            }
            ctx.sendBroadcast(intent)
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG: broadcast sent for $pkg=$allow")
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG broadcast failed: ${t.message}")
        }
    }

    /** system_server 进程的 System Context（ActivityThread.getSystemContext()） */
    private fun systemContext(): Context? = try {
        val at = XposedHelpers.callStaticMethod(
            Class.forName("android.app.ActivityThread"), "currentActivityThread"
        )
        XposedHelpers.callMethod(at, "getSystemContext") as? Context
    } catch (_: Throwable) {
        null
    }
}

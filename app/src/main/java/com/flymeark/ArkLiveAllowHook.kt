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
        if (lpp.packageName != "android") return

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG: hook setNotificationLiveAllowForPackage")

        // 定位 INotificationManager.setNotificationLiveAllowForPackage(String, int, boolean)
        ArkHooks.quiet("$TAG setNotificationLiveAllowForPackage") {
            XposedHelpers.findAndHookMethod(
                "com.android.server.notification.NotificationManagerService",
                lpp.classLoader,
                "setNotificationLiveAllowForPackage",
                String::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
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

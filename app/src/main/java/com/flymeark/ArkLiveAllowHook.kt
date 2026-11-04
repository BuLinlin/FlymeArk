package com.flymeark

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v2.3（Part F）：在 system_server 进程强制放行目标播放器的实况窗。
 *
 * 逆向定位到 Flyme 的「实况窗准入」判定的真正门槛（此前误判为 SystemUI 渲染层白名单）：
 *
 *  NotificationManagerService.enqueueNotificationInternal 里，当 sbn.isLive() 为 true 时：
 *    boolean allow = mPreferencesHelper.getNotificationLiveAllowForPackage(origPkg, uid);
 *    if (!allow) {
 *        // 强制把 notification.live.type 清零，SystemUI 随之判定 isLive()==false，胶囊不渲染
 *        notification.extras.putInt("notification.live.type", 0);
 *    }
 *
 * 而 getNotificationLiveAllowForPackage 读 PackagePreferences.allowLive 字段；
 * 未声明 live_notification_config 的普通 App（如 QQ音乐）该字段为 false（需在「设置→实况窗」
 * 里手动开启，而 Scanner 只扫描声明了 live_notification_config 的包，QQ音乐无法被开启）。
 *
 * 本类 hook system_server 的 PreferencesHelper.getNotificationLiveAllowForPackage，
 * 对目标包强制返回 true，让注入的 notification.live.type 不再被系统清零。
 */
internal object ArkLiveAllowHook {

    /** 目标播放器包名（v3.15：QQ音乐 + 网易云音乐，均走魅族状态栏歌词协议） */
    private val TARGET_PKGS = ArkConstants.MEDIA_APP_PKGS

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        // 仅在 system_server 进程（包名 "android"）安装
        if (lpp.packageName != "android") return

        val target = "com.android.server.notification.PreferencesHelper"
        ArkHooks.quiet("liveAllow getNotificationLiveAllowForPackage") {
            XposedHelpers.findAndHookMethod(
                target,
                lpp.classLoader,
                "getNotificationLiveAllowForPackage",
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val pkg = param.args[0] as? String ?: return
                            if (pkg in TARGET_PKGS && param.result != true) {
                                param.result = true
                                XposedBridge.log(
                                    "[${ArkConstants.LOG_TAG}] liveAllow: force allowLive=true for $pkg"
                                )
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[${ArkConstants.LOG_TAG}] liveAllow hook error: ${t.message}")
                        }
                    }
                }
            )
        }
    }
}

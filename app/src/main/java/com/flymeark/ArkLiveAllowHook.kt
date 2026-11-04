package com.flymeark

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v2.3（Part F）：在 system_server 进程强制放行目标播放器的实况窗。
 *
 * **v3.15.11 起已停用强制放行** —— 它会把「设置 → 实况通知」的开关架空，
 * 用户关掉某个 App 后胶囊照样出现。现在改由 SystemUI 侧的 [LiveAllowGate]
 * 按「当前播放 App」的开关真值决定，本类只保留一个空壳以免动作用域配置。
 *
 * 历史背景（保留供参考）：
 *
 *  NotificationManagerService.enqueueNotificationInternal 里，当 sbn.isLive() 为 true 时：
 *    boolean allow = mPreferencesHelper.getNotificationLiveAllowForPackage(origPkg, uid);
 *    if (!allow) {
 *        // 强制把 notification.live.type 清零，SystemUI 随之判定 isLive()==false，胶囊不渲染
 *        notification.extras.putInt("notification.live.type", 0);
 *    }
 *
 * 未声明 live_notification_config 的普通 App（如 QQ音乐）该字段默认为 false，
 * 所以在设置里原本无法开启 —— 这是 v2.4 通过 [ArkLiveSettingsHook] 注入设置项解决的问题。
 */
internal object ArkLiveAllowHook {

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != "android") return

        // v3.15.11：**停用强制放行**。
        //
        // 旧实现把两个播放器的 allowLive 一律改成 true，等于把
        // 「设置 → 实况通知」里的开关彻底架空：只要勾了「系统框架」作用域，
        // 用户在设置里关掉任何一个，胶囊照样出现（用户实测网易云关不掉）。
        //
        // 胶囊现在由渲染侧的 LiveAllowGate 按「当前播放 App」的开关真值决定，
        // 不再需要把 allowLive 强行改真。保留本类只为不动作用域与调用点。
        XposedBridge.log(
            "[${ArkConstants.LOG_TAG}] liveAllow: 强制放行已停用（开关交给设置页真值）"
        )
    }
}

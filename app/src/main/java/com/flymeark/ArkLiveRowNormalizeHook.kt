package com.flymeark

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v3.15.21：让微信/QQ 的通知在**通知栏里保持普通卡片**（恢复 × 一键清除）。
 *
 * ## 问题
 *
 * 我们把普通消息通知注入了 live 字段后，通知栏里那条通知变成了 Flyme 的实况小卡：
 * 高度压成 `mLiveNotifSmallHeight`、玻璃底、没有 × 按钮，也滑不掉 —— 因为
 * `ExpandableNotificationRow` 一旦 `isLiveNotification()` 为真，整行就走实况专用渲染：
 *
 * - `getMinHeight()` / 高度计算 → 用 live public view
 * - `NotificationStackScrollLayout` → `addTrackingLiveExpandableView`（悬浮跟踪、custom translation）
 * - 长按 → `NotificationInfo.mzBindGuts` 的 live 分支（只给"实况通知设置"，没有清除）
 *
 * ## 关键洞察：两个标志是分开的
 *
 * - **状态栏胶囊**由 `StatusBarNotification.isLive()`（extras 里的 `notification.live.type`）驱动，
 *   在 `LiveNotificationController.shouldShowLiveNotification` 里判断；
 * - **通知栏那一行的样式**由 `ExpandableView.mIsLiveNotification` 这个**视图字段**驱动，
 *   在 `onFinishInflate` 里 `setLiveNotification(sbn.isLive())` 写入。
 *
 * 所以只要拦住这次写入，通知栏那条就是普通微信通知（有头像、正文、× 按钮、可滑动删除），
 * 状态栏胶囊完全不受影响。
 *
 * ## 本 hook
 *
 * hook `ExpandableView.setLiveNotification(boolean)`：当行属于微信/QQ 时强制写 false。
 * 其余 live 通知（音乐胶囊对应的播放通知）保持原样。
 *
 * 注意：`NotificationContentView.buildContentContainer` 里用的是 `sbn.isLive()`（不是视图字段），
 * 所以那条通知仍会被当成 custom view 处理（圆角/内边距），只是不再压缩成实况小卡。
 */
internal object ArkLiveRowNormalizeHook {

    private const val TAG = "liveRowNormalize"
    private const val BASE_CLS =
        "com.android.systemui.statusbar.notification.row.ExpandableView"
    private const val ROW_CLS =
        "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"

    @Volatile
    private var lastLoggedPkg: String? = null

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != ArkConstants.SYSTEMUI_PKG) return

        ArkHooks.quiet("$TAG setLiveNotification") {
            XposedHelpers.findAndHookMethod(
                BASE_CLS, lpp.classLoader, "setLiveNotification",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (param.args[0] != true) return
                            // 只有通知行有 mEntry；其它 ExpandableView 子类直接放过
                            if (param.thisObject.javaClass.name != ROW_CLS) return

                            val entry = XposedHelpers.getObjectField(
                                param.thisObject, "mEntry"
                            ) ?: return
                            val sbn = XposedHelpers.callMethod(entry, "getSbn") ?: return
                            val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String
                                ?: return
                            if (!ArkConstants.isMessageLiveApp(pkg)) return

                            param.args[0] = false

                            if (pkg != lastLoggedPkg) {
                                lastLoggedPkg = pkg
                                XposedBridge.log(
                                    "[${ArkConstants.LOG_TAG}] $TAG: keep normal notification row for $pkg"
                                )
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG error: ${t.message}")
                        }
                    }
                }
            )
        }

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing in SystemUI")
    }
}

package com.flymeark

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v3.15.18：微信 / QQ 消息实况通知（参考「魅况」）。
 *
 * 微信/QQ 是纯消息 App，没有歌词、没有 MediaSession，无法走 QQ音乐/网易云 那条
 * 「歌词胶囊」（ticker 协议）链路。本类参考第三方「魅况」的做法，把它们的
 * **普通消息通知**转成魅族实况胶囊。
 *
 * ## 逆向结论（SystemUI 侧 LiveNotificationController，真机反编译确认）
 *
 * Flyme 判断一条通知能否渲染成实况胶囊只看 [shouldShowLiveNotification]：
 *   1. `sbn.isLive()`  —— 读 `notification.extras["notification.live.type"]` 非 0；
 *   2. `sbn.getCapsuleStatus() == 1` —— 读 `extras["notification.live.capsule"]`
 *      子 Bundle 里的 `notification.live.capsuleStatus` 为 1。
 *
 * 胶囊文本取 `getCapsuleContentExt`：优先 `tickerText`，否则 `capsuleContent` remoteView，
 * 否则 `notification.live.capsule` 子 Bundle 里的 `notification.live.capsuleContent`。
 * 标题取 `notification.live.capsuleTitle`。
 *
 * ## 本类做的事
 *
 * 在微信/QQ 进程 hook `NotificationManager.notify`，给每条消息通知注入：
 *  - `extras["notification.live.type"] = 1`
 *  - `extras["notification.live.capsule"]` 子 Bundle：
 *      `capsuleStatus=1`、`capsuleTitle=<发送者/title>`、
 *      `capsuleContent=<消息正文/text>`（系统自动轮播多行）、
 *      `capsuleIcon=<发送者头像 Icon>`（左侧头像，对应音乐胶囊的封面）。
 *
 * 多胶囊共存由 Flyme 原生处理（`LiveNotificationController` 的 `mToShow` 列表 +
 * `mFocusPackageName` 排序 + `LiveNotificationView` 上滑切换手势），本类无需介入。
 *
 * 只对「消息类」通知注入（有 title/text），跳过进程内常驻前台通知（微信/QQ 的
 * 「后台运行中」通知没有有意义的内容，注入只会产生一条占位胶囊）。
 */
internal object ArkMessageLiveInjector {

    private const val TAG = "liveMsg"

    /** extras 里标记「已注入」的键，避免同一条通知重复处理 */
    private const val KEY_INJECTED = "com.flymeark.msg_live_injected"

    private fun isMessageNotification(n: Notification): Boolean {
        // 有标题 + 正文的才算消息通知；纯 icon/常驻前台通知跳过
        val extras = try { n.extras } catch (_: Throwable) { null } ?: return false
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        if (title == null && text == null) return false
        // 已注入过的不重复处理
        if (extras.containsKey(KEY_INJECTED)) return false
        return true
    }

    private fun injectLiveFields(n: Notification) {
        try {
            val extras = n.extras
            // 1) live.type = 1 → isLive() 为真
            extras.putInt("notification.live.type", 1)

            // 2) capsule 子 Bundle
            val capsule = Bundle()
            capsule.putInt("notification.live.capsuleStatus", 1)
            // 标题优先用 title，其次用 subText，再次空
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            if (title != null) capsule.putString("notification.live.capsuleTitle", title)
            // 正文（系统会自动轮播多行）
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            if (text != null) capsule.putString("notification.live.capsuleContent", text)

            // 3) 左侧头像：胶囊左侧图标 = 发消息人头像（对应音乐胶囊的封面）。
            //    Flyme 取 `capsuleIcon`（Icon 类型）渲染到 mLiveNotificationIcon。
            //    来源：优先 largeIcon（微信/QQ 消息通知把发送者头像放这里），
            //    兜底 smallIcon。
            val icon = messageIcon(n)
            if (icon != null) {
                try {
                    capsule.putParcelable("notification.live.capsuleIcon", icon)
                } catch (t: Throwable) {
                    XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG put icon failed: ${t.message}")
                }
            }

            extras.putBundle("notification.live.capsule", capsule)

            // 4) 标记已注入，防重复
            extras.putBoolean(KEY_INJECTED, true)
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG inject failed: ${t.message}")
        }
    }

    /**
     * 取消息发送者头像。优先取 extras 里现成的 `android.largeIcon`（已经是 Icon 类型，
     * 微信/QQ 消息通知把发送者头像放这里，跨进程 parcel 不会丢）；其次 `getLargeIcon()`
     * 拿 Bitmap 构造；最后兜底 `getSmallIcon()`。
     *
     * 注意：不要用 `Icon.createWithBitmap` 重新构造——实测该 Icon 跨进程 parcel 到
     * SystemUI 后 `getCapsuleIcon()` 读回为 null（Bitmap 类型序列化丢失），导致胶囊
     * 左侧回退成 APP 图标。直接用通知里现成的 `android.largeIcon`（Icon）最稳。
     */
    private fun messageIcon(n: Notification): Icon? {
        // 1) extras 里现成的 android.largeIcon（Icon 类型，最稳）
        try {
            val fromExtras = n.extras?.getParcelable<Icon>("android.largeIcon")
            if (fromExtras != null) return fromExtras
        } catch (_: Throwable) {
        }
        // 2) EXTRA_LARGE_ICON_BIG 里的 Bitmap
        try {
            val extraLarge = n.extras?.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON_BIG)
            if (extraLarge != null && !extraLarge.isRecycled) {
                return Icon.createWithBitmap(extraLarge)
            }
        } catch (_: Throwable) {
        }
        // 3) getLargeIcon() 拿 Bitmap 构造
        val large = try { n.getLargeIcon() } catch (_: Throwable) { null }
        if (large is Bitmap && !large.isRecycled) {
            try {
                return Icon.createWithBitmap(large)
            } catch (_: Throwable) {
            }
        }
        // 4) 兜底 smallIcon
        return try { n.getSmallIcon() } catch (_: Throwable) { null }
    }

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpp.packageName
        if (!ArkConstants.isMessageLiveApp(pkg)) return

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing for $pkg")

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val n = param.args.filterIsInstance<Notification>().firstOrNull() ?: return
                    if (!isMessageNotification(n)) return
                    injectLiveFields(n)
                    XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG injected live fields for $pkg")
                } catch (t: Throwable) {
                    XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG hook error: ${t.message}")
                }
            }
        }

        ArkHooks.quiet("$TAG notify") {
            val nmClass = XposedHelpers.findClass("android.app.NotificationManager", lpp.classLoader)
            ArkHooks.quiet("$TAG notify(int,Notification)") {
                XposedHelpers.findAndHookMethod(
                    nmClass, "notify", Int::class.javaPrimitiveType, Notification::class.java, hook
                )
            }
            ArkHooks.quiet("$TAG notify(String,int,Notification)") {
                XposedHelpers.findAndHookMethod(
                    nmClass, "notify",
                    String::class.java, Int::class.javaPrimitiveType, Notification::class.java, hook
                )
            }
        }
    }
}

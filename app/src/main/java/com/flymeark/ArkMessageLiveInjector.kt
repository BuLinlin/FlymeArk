package com.flymeark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v3.15.24：微信 / QQ 消息实况胶囊 —— **双通知拆分架构**。
 *
 * ## 为什么要拆
 *
 * 之前是把 live 字段直接注入微信/QQ 的**原通知**，结果那条通知在通知栏被 Flyme 当成
 * 实况卡渲染。逆向确认：实况卡要显示内容，必须应用自己在通知布局里放
 * `rl_header_live`(0x010206da) / `iv_extends1_live`(0x0102066e) /
 * `iv_extends2_live`(0x0102066f) 三个 Flyme 约定插槽；微信/QQ 用的是系统模板，没有插槽，
 * 于是只能渲染成「空白玻璃底 + 空心圆」。而这条实况卡与状态栏胶囊由同一套
 * `LiveNotificationController` 产出，无法靠拦 `sbn.isLive()` 只留胶囊——
 * 那样会连带切断 `addTrackingLiveExpandableView` 的触摸追踪，胶囊直接点不动。
 *
 * 结论：**同一条通知上「原生卡片」与「实况胶囊」互斥**。因此改为两条：
 *
 * | 通知 | 归属 | 样式 |
 * |---|---|---|
 * | 原消息通知 | 微信/QQ 自己 | 完全不动 → 系统原生卡片（×、滑动、点击全原生）|
 * | 实况影子通知 | 本模块额外 post | 走实况链路 → 状态栏胶囊 + AOD |
 *
 * 影子通知由 [ArkLiveShadowFilterHook] 在 SystemUI 的 `ShadeListBuilder.applyFilters`
 * 里过滤掉，通知栏看不到它；但 `NotifCollection`（胶囊数据源）依然持有它，胶囊正常。
 *
 * ## 影子通知字段（反编译「魅况 1.21」确认的完整配方）
 *
 * - `is_live=true`、`notification.live.type=10`、`notification.live.status=1`、
 *   `notification.live.operation=0`
 * - `notification.live.capsule` 子 Bundle：`capsuleStatus=1`、`capsuleType=3`、
 *   `capsuleTitle`、`capsuleContent`、`capsuleIcon`（发送者头像，原样 Icon 不重建）、
 *   `capsuleBgColor`（品牌色）、`capsuleContentColor`（按底色亮度自适应）
 *
 * 影子通知用固定 tag/id（每个 App 一条），新消息直接覆盖更新，不会堆积。
 */
internal object ArkMessageLiveInjector {

    private const val TAG = "liveMsg"

    /** 影子通知标记；SystemUI 侧据此把它从通知栏列表剔除 */
    const val KEY_SHADOW = "com.flymeark.live_shadow"

    const val SHADOW_TAG = "flymeark_live_msg"
    const val SHADOW_CHANNEL = "flymeark_live_msg_ch"

    /** 每个 App 一条影子通知，id 固定 */
    const val SHADOW_ID = 0x4B000001

    fun isShadowNotification(n: Notification): Boolean = try {
        n.extras?.getBoolean(KEY_SHADOW) == true
    } catch (_: Throwable) {
        false
    }

    private fun isMessageNotification(n: Notification): Boolean {
        val extras = try { n.extras } catch (_: Throwable) { null } ?: return false
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        return title != null || text != null
    }

    private fun appContext(): Context? = try {
        XposedHelpers.callStaticMethod(
            Class.forName("android.app.AndroidAppHelper"), "currentApplication"
        ) as? Context
    } catch (_: Throwable) {
        null
    }

    /**
     * 取可用 Context。**优先用 NotificationManager 实例自带的 `mContext`**：
     * v3.15.24 实测微信/QQ 在 notify 时 `AndroidAppHelper.currentApplication()` 仍返回 null
     * （Application 尚未创建 / 调用发生在 push 子进程），导致影子通知被整个跳过。
     * NotificationManager 的 `mContext` 是构造时就持有的，任何时机都可用。
     */
    private fun contextOf(nm: Any?): Context? {
        try {
            if (nm != null) {
                val c = XposedHelpers.getObjectField(nm, "mContext") as? Context
                if (c != null) return c
            }
        } catch (_: Throwable) {
        }
        return appContext()
    }

    /** 发布/更新影子实况通知（不影响原通知） */
    private fun postShadow(pkg: String, src: Notification, nmAny: Any?) {
        val nm = nmAny as? NotificationManager
        val ctx = contextOf(nmAny) ?: run {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG no context, skip shadow")
            return
        }
        if (nm == null) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG no NotificationManager, skip shadow")
            return
        }

        val extras = try { src.extras } catch (_: Throwable) { null } ?: return
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        // 正文只用来判断「这条到底是不是一条消息」，不再进胶囊
        if (rawTitle.isNullOrBlank() && rawText.isNullOrBlank()) return

        // v3.15.33：胶囊只放隐私占位，不放昵称 / 群名 / 正文。
        // 正文仍在原生通知卡片里，点卡片或点胶囊都能进会话，信息不丢。
        val appLabel = ArkConstants.messageAppLabel(pkg)
        val count = sessionMessageCount(extras, rawText)
        val content = if (count > 1) {
            "有 $count 条新消息 · 点击查看"
        } else {
            "有新消息 · 点击查看"
        }
        val title = appLabel

        val smallIcon = try { src.getSmallIcon() } catch (_: Throwable) { null } ?: return
        val largeIcon = messageIcon(src)
        val bg = ArkConstants.brandColorOf(pkg)

        // 低优先级渠道：不响铃、不振动、不出横幅（-2 = IMPORTANCE_LOW 以下也无所谓）
        try {
            val ch = NotificationChannel(
                SHADOW_CHANNEL, "消息实况胶囊", NotificationManager.IMPORTANCE_LOW
            )
            ch.setSound(null, null)
            ch.enableVibration(false)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG channel failed: ${t.message}")
        }

        val shadow: Notification = try {
            val b = Notification.Builder(ctx, SHADOW_CHANNEL)
                .setSmallIcon(smallIcon)
                .setContentTitle(title ?: pkg)
                .setContentText(content)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setAutoCancel(false)
                .setOngoing(false)
            if (largeIcon != null) b.setLargeIcon(largeIcon)
            try {
                val ci: PendingIntent? = src.contentIntent
                if (ci != null) b.setContentIntent(ci)
            } catch (_: Throwable) {
            }
            b.build()
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG build shadow failed: ${t.message}")
            return
        }

        val se = shadow.extras
        // 影子标记（SystemUI 据此过滤；本 hook 据此避免自我递归）
        se.putBoolean(KEY_SHADOW, true)

        // 顶层 live 字段
        se.putBoolean("is_live", true)
        se.putInt("notification.live.operation", 0)
        se.putInt("notification.live.type", 10)
        se.putInt("notification.live.status", 1)
        // 不参与"钉住"逻辑，避免额外横幅
        se.putBoolean("notification.live.pinned", false)

        // 胶囊字段
        val capsule = Bundle()
        capsule.putInt("notification.live.capsuleStatus", 1)
        capsule.putInt("notification.live.capsuleType", 3)
        capsule.putString("notification.live.capsuleContent", content)
        if (title != null) capsule.putString("notification.live.capsuleTitle", title)
        capsule.putInt("notification.live.capsuleBgColor", bg)
        capsule.putInt(
            "notification.live.capsuleContentColor", ArkConstants.readableTextOn(bg)
        )
        if (largeIcon != null) {
            capsule.putParcelable("notification.live.capsuleIcon", largeIcon)
        }
        se.putBundle("notification.live.capsule", capsule)

        try {
            nm.notify(SHADOW_TAG, SHADOW_ID, shadow)
            XposedBridge.log(
                "[${ArkConstants.LOG_TAG}] $TAG shadow posted: pkg=$pkg bg=0x${Integer.toHexString(bg)} " +
                    "icon=${if (largeIcon != null) "yes" else "no"} title='$title' content='$content'"
            )
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG notify shadow failed: ${t.message}")
        }
    }

    /**
     * 统计「当前这个会话 / 这个发消息的人」给我发了多少条。
     *
     * **不能用 `Notification.number`** —— 那是整个 App 的未读总数。
     * 实测微信通知 `number=218`，而当前会话其实只有 4 条（正文前缀 `[4条]`）。
     * 用错值还会连带引发另一个 bug：条数长期不变 → 占位文案不变 →
     * `LiveNotificationView.setText` 判定「text is the same」直接 return → 胶囊不刷新（卡住）。
     *
     * 按可靠性依次回退：
     * ① 正文前缀 `[4条]xxx: yyy` —— 微信自己写的会话未读数，最准；
     * ② MessagingStyle 的 `android.messages` 数组 —— 统计与最新一条同 sender 的条数；
     * ③ `android.textLines` —— 每行 `名字: 内容`，统计与最后一行同名者的条数；
     * ④ 都拿不到返回 0，只显示「有新消息」，宁可不报数也不报错数。
     */
    private val COUNT_IN_TEXT = Regex("""(\d+)\s*条""")

    private fun sessionMessageCount(extras: Bundle, text: String?): Int {
        // ① 微信 / TIM 风格
        if (!text.isNullOrBlank()) {
            val n = COUNT_IN_TEXT.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (n > 1) return n
        }

        // ② MessagingStyle
        try {
            val msgs = extras.getParcelableArray("android.messages")
            if (!msgs.isNullOrEmpty()) {
                val senders = ArrayList<String?>(msgs.size)
                for (item in msgs) {
                    val b = item as? Bundle
                    senders.add(senderOf(b))
                }
                val last = senders.lastOrNull { !it.isNullOrBlank() }
                if (last != null) {
                    val n = senders.count { it == last }
                    if (n > 1) return n
                }
            }
        } catch (_: Throwable) {
        }

        // ③ textLines
        try {
            val lines = extras.getCharSequenceArray("android.textLines")
            if (!lines.isNullOrEmpty()) {
                val senders = lines.map { lineSenderOf(it.toString()) }
                val last = senders.lastOrNull { !it.isNullOrBlank() } ?: return 0
                val n = senders.count { it == last }
                if (n > 1) return n
            }
        } catch (_: Throwable) {
        }

        return 0
    }

    /** MessagingStyle 单条消息的 sender（优先 CharSequence `sender`，退回 `sender_person.name`） */
    private fun senderOf(b: Bundle?): String? {
        if (b == null) return null
        b.getCharSequence("sender")?.toString()?.let { if (it.isNotBlank()) return it }
        val person = try { b.getParcelable<android.os.Parcelable>("sender_person") } catch (_: Throwable) { null }
            ?: return null
        return try {
            XposedHelpers.callMethod(person, "getName") as? String
        } catch (_: Throwable) {
            null
        }
    }

    /** `名字: 内容` 里的名字，兼容中英文冒号 */
    private fun lineSenderOf(line: String): String? {
        val en = line.indexOf(':')
        val cn = line.indexOf('：')
        val idx = when {
            en < 0 -> cn
            cn < 0 -> en
            else -> minOf(en, cn)
        }
        if (idx <= 0) return null
        return line.substring(0, idx).trim().ifBlank { null }
    }

    /**
     * 取消息发送者头像。优先 extras 里现成的 `android.largeIcon`（Icon 类型，跨进程不丢），
     * 不要用 `Icon.createWithBitmap` 重建——实测重建的 Icon parcel 到 SystemUI 后读回 null。
     */
    private fun messageIcon(n: Notification): Icon? {
        try {
            val fromExtras = n.extras?.getParcelable<Icon>("android.largeIcon")
            if (fromExtras != null) return fromExtras
        } catch (_: Throwable) {
        }
        try {
            val extraLarge = n.extras?.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON_BIG)
            if (extraLarge != null && !extraLarge.isRecycled) {
                return Icon.createWithBitmap(extraLarge)
            }
        } catch (_: Throwable) {
        }
        return null
    }

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpp.packageName
        if (!ArkConstants.isMessageLiveApp(pkg)) return

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing for $pkg")

        val notifyHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val n = param.args.filterIsInstance<Notification>().firstOrNull() ?: return
                    // 影子通知自身的发布：放行，不再生成影子
                    if (isShadowNotification(n)) return
                    if (!isMessageNotification(n)) return
                    // 原通知**不做任何修改**，只在旁边补一条实况影子通知
                    postShadow(pkg, n, param.thisObject)
                } catch (t: Throwable) {
                    XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG hook error: ${t.message}")
                }
            }
        }

        // 注：不在 App 侧 hook cancel 同步影子。App 的 cancel 调用可能来自任意通知
        // （登录提示、下载进度等），一律清影子会让胶囊莫名消失。
        // 销毁同步统一放在 SystemUI 的 onNotificationRemoved（ArkLiveShadowHook），
        // 只在「这个 App 的消息通知真的被移除」时才清。

        ArkHooks.quiet("$TAG notify") {
            val nmClass = XposedHelpers.findClass("android.app.NotificationManager", lpp.classLoader)
            ArkHooks.quiet("$TAG notify(int,Notification)") {
                XposedHelpers.findAndHookMethod(
                    nmClass, "notify", Int::class.javaPrimitiveType, Notification::class.java, notifyHook
                )
            }
            ArkHooks.quiet("$TAG notify(String,int,Notification)") {
                XposedHelpers.findAndHookMethod(
                    nmClass, "notify",
                    String::class.java, Int::class.javaPrimitiveType, Notification::class.java, notifyHook
                )
            }
        }
    }
}

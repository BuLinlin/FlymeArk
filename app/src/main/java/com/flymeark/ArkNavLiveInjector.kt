package com.flymeark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier

/**
 * v3.15.51：高德地图导航实况胶囊 —— 复用「影子通知」通道。
 *
 * ## 为什么是高德而不只是微信/QQ
 *
 * 逆向确认（`docs/live-nav-research.md` §2.4）：高德**不发**实况通知，8 个 dex 里
 * `notification.live` 命中 0 次；也没有百度那个 `ExtGuideService`。它只发一条**常驻导航通知**：
 *
 * ```
 * NotifyServiceImpl.h()  (com.autonavi.bundle.amaphome.compat.service)
 *   setLargeIcon(转向箭头 Bitmap) / setSmallIcon(R.drawable.notification_amap)
 *   setContentTitle("导航中") / setContentText("剩余 3.2 公里 12 分钟")
 *   setExtras(bundle)  →  bundle["com.autonavi.minimap.navigating"] = true
 *   setCategory("navigation")  (SDK>=31) / setOngoing(true)
 *   通知 id：99910001 驾车 / 99910002 步行 / 99910003 骑行
 * ```
 *
 * 所以本模块不需要往高德进程塞任何业务逻辑，只要**在旁边补一条实况影子通知**即可 ——
 * 与 [ArkMessageLiveInjector]（微信/QQ）完全同一套架构，连 SystemUI 侧的过滤都不用新写。
 *
 * ## 与消息卡的三处差异
 *
 * | 字段 | 消息卡 | 导航卡 |
 * |---|---|---|
 * | `notification.live.capsuleType` | `3` | `1`（百度导航用的就是 1，见 §1 原文） |
 * | `notification.live.capsuleContent` | 隐私占位文案 | `"$title · $text"`，`text` 空则只放 `title` |
 * | `notification.live.capsuleIcon` | 原样 Icon | 由 `setLargeIcon(Bitmap)` 重建（高德只给 Bitmap） |
 *
 * 其余顶层字段（`is_live` / `notification.live.type=10` / `operation=0` / `pinned=false`）
 * 与消息卡一致，`notification.live.event` 换成 `"NAVIGATION"`。
 *
 * **不设 `notification.live.app.pkg.delegated*`**：那一组是百度「由智能服务代发」时才需要，
 * 我们是高德自己发（同包名），走自发布路径。
 *
 * **不设 `notification.live.status`**：百度那版没设，framework 里也没有任何代码读它
 * （`grep notification.live.status` 零命中），按「不加协议里没有的 key」原则略过。
 *
 * **不设 RemoteViews**：展开卡设计稿未定，本版让系统用默认展开样式。
 */
internal object ArkNavLiveInjector {

    private const val TAG = "navInject"

    /** 高德导航通知里那条显式业务标记（最可靠的识别依据） */
    private const val EXTRA_NAVIGATING = "com.autonavi.minimap.navigating"

    /** 高德导航常驻通知的 id（驾车 / 步行 / 骑行），作为第二判据 */
    private val NAV_NOTIF_IDS = intArrayOf(99910001, 99910002, 99910003)

    /** 影子通知 tag / id（每个 App 一条，新帧直接覆盖更新，不会堆积） */
    const val NAV_SHADOW_TAG = "flymeark_live_nav"
    const val NAV_SHADOW_CHANNEL = "flymeark_live_nav_ch"
    const val NAV_SHADOW_ID = 0x4B000002

    /** 胶囊底色：沿用模块品牌蓝，字色按底色亮度自适应（与消息卡同一套规则） */
    private const val CAPSULE_BG = ArkTokens.BRAND_LIGHT

    /**
     * 防重入标记。`notify(int,Notification)` 内部会转调 `notify(String,int,Notification)`，
     * 两个重载都 hook 时同一帧会生成两条影子（消息卡 v3.15.34 踩过）。
     */
    private val inNotify = ThreadLocal<Boolean>()

    private const val OWNER_KEY = "flymeark_nav_notify_owner"

    // ---------------------------------------------------------------- 识别

    /**
     * 判断这条通知是不是高德的导航通知。
     *
     * 只认三类判据，全部命中不了就放弃（宁可不显示，也不要给别的通知套一层实况皮）：
     *  1. `extras["com.autonavi.minimap.navigating"] == true` —— 显式业务标记，最可靠；
     *  2. 通知 id ∈ {99910001, 99910002, 99910003}；
     *  3. `category == "navigation"`。
     *
     * @return 命中的判据描述（写日志用），未命中返回 null
     */
    private fun matchReason(n: Notification, args: Array<Any?>): String? {
        val extras = try {
            n.extras
        } catch (_: Throwable) {
            null
        }
        if (extras != null && extras.getBoolean(EXTRA_NAVIGATING, false)) return "extras"

        val id = notifIdOf(args)
        if (id != null && id in NAV_NOTIF_IDS) return "id=$id"

        val category = try {
            n.category
        } catch (_: Throwable) {
            null
        }
        if (category == "navigation") return "category"

        return null
    }

    /** 从 hook 参数里取通知 id（三个重载里 id 都是 Int 参数） */
    private fun notifIdOf(args: Array<Any?>?): Int? {
        if (args == null) return null
        for (a in args) {
            if (a is Int) return a
        }
        return null
    }

    private fun isShadowNotification(n: Notification): Boolean = try {
        n.extras?.getBoolean(ArkMessageLiveInjector.KEY_SHADOW) == true
    } catch (_: Throwable) {
        false
    }

    // ---------------------------------------------------------------- Context

    private fun appContext(): Context? = try {
        XposedHelpers.callStaticMethod(
            Class.forName("android.app.AndroidAppHelper"), "currentApplication"
        ) as? Context
    } catch (_: Throwable) {
        null
    }

    /**
     * 取可用 Context。
     *
     * 两个来源都用得上：
     *  - `NotificationManager.notify` 的 this = NotificationManager，Context 在私有字段 `mContext`；
     *  - `Service.startForeground` 的 this = Service，**它本身就是 Context**（ContextWrapper），
     *    没有 `mContext` 字段 —— 这一条是 v3.15.52 补 startForeground hook 时踩的。
     *
     * 微信/QQ 上实测 `currentApplication()` 在 notify 期间仍可能返回 null（v3.15.24 日志），
     * 所以实例自带的 Context 优先，最后才退 `AndroidAppHelper.currentApplication()`。
     */
    private fun contextOf(any: Any?): Context? {
        if (any is Context) return any
        try {
            if (any != null) {
                val c = XposedHelpers.getObjectField(any, "mContext") as? Context
                if (c != null) return c
            }
        } catch (_: Throwable) {
        }
        return appContext()
    }

    /** 解析出 (NotificationManager, Context)；startForeground 场景下从 Service 的 Context 取 NM */
    private fun resolveNm(any: Any?): Pair<NotificationManager, Context>? {
        val direct = any as? NotificationManager
        if (direct != null) {
            val ctx = contextOf(any) ?: return null
            return direct to ctx
        }
        val ctx = contextOf(any) ?: return null
        val nm = try {
            ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        } catch (_: Throwable) {
            null
        } ?: return null
        return nm to ctx
    }

    // ---------------------------------------------------------------- 影子发布

    private fun postShadow(
        pkg: String,
        src: Notification,
        nmAny: Any?,
        reason: String,
        notifId: Int?
    ) {
        val resolved = resolveNm(nmAny)
        if (resolved == null) {
            log("skip reason=no_context_or_nm")
            return
        }
        val (nm, ctx) = resolved

        val extras = try {
            src.extras
        } catch (_: Throwable) {
            null
        }
        if (extras == null) {
            log("skip reason=no_extras")
            return
        }

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()

        // 高德是 setLargeIcon(Bitmap)，所以优先取 Bitmap 再重建 Icon。
        // 依次尝试：android.largeIcon.big(Bitmap) → android.largeIcon(Icon/Bitmap) →
        // Notification.getLargeIcon()。都拿不到就不发影子（空图标胶囊比没有更糟）。
        val bitmap = try {
            extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON_BIG)
        } catch (_: Throwable) {
            null
        }
        val iconFromExtras = try {
            extras.getParcelable<Icon>(Notification.EXTRA_LARGE_ICON)
        } catch (_: Throwable) {
            null
        }
        val iconFromNotif = try {
            // 注意：不能写 src.largeIcon —— Kotlin 会命中 Notification 里那个
            // legacy 的 `public Bitmap largeIcon` **字段**（已废弃），拿到的不是 Icon。
            // 这里显式走 getLargeIcon()（API 23+ 返回 Icon）。
            XposedHelpers.callMethod(src, "getLargeIcon") as? Icon
        } catch (_: Throwable) {
            null
        }
        val icon: Icon?
        val iconSrc: String
        when {
            bitmap != null && !bitmap.isRecycled -> {
                icon = Icon.createWithBitmap(bitmap)
                iconSrc = "bmp"
            }
            iconFromExtras != null -> {
                icon = iconFromExtras
                iconSrc = "icon"
            }
            iconFromNotif != null -> {
                icon = iconFromNotif
                iconSrc = "getLargeIcon"
            }
            else -> {
                icon = null
                iconSrc = "none"
            }
        }

        log(
            "src title='${title ?: ""}' text='${text ?: ""}' largeIcon=$iconSrc " +
                "reason=$reason id=${notifId ?: "-"}"
        )

        if (title.isNullOrBlank()) {
            log("skip reason=no_title")
            return
        }
        if (icon == null) {
            // 没有转向箭头就不发：空图标胶囊比没有胶囊更糟（宁可不显示）
            log("skip reason=no_large_icon")
            return
        }

        val smallIcon = try {
            src.getSmallIcon()
        } catch (_: Throwable) {
            null
        }
        if (smallIcon == null) {
            log("skip reason=no_small_icon")
            return
        }

        val content = if (text.isNullOrBlank()) title else "$title · $text"

        try {
            val ch = NotificationChannel(
                NAV_SHADOW_CHANNEL, "导航实况胶囊", NotificationManager.IMPORTANCE_LOW
            )
            ch.setSound(null, null)
            ch.enableVibration(false)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        } catch (t: Throwable) {
            log("channel failed: ${t.message}")
        }

        val shadow: Notification = try {
            val b = Notification.Builder(ctx, NAV_SHADOW_CHANNEL)
                .setSmallIcon(smallIcon)
                .setContentTitle(title)
                .setContentText(text ?: title)
                .setLargeIcon(icon)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setAutoCancel(false)
                // 导航本身是常驻通知，影子同样常驻；结束时由 SystemUI 侧
                // onNotificationRemoved 同步撤销（ArkLiveShadowHook）
                .setOngoing(true)
            try {
                val ci: PendingIntent? = src.contentIntent
                if (ci != null) b.setContentIntent(ci)
            } catch (_: Throwable) {
            }
            b.build()
        } catch (t: Throwable) {
            log("skip reason=build_failed ${t.message}")
            return
        }

        val se = shadow.extras
        // 影子标记（SystemUI 据此隐藏行；本 hook 据此避免自我递归）
        se.putBoolean(ArkMessageLiveInjector.KEY_SHADOW, true)

        // 顶层 live 字段（与消息卡一致，type=10 即「实况」；event 换成导航）
        se.putBoolean("is_live", true)
        se.putInt("notification.live.operation", 0)
        se.putInt("notification.live.type", 10)
        se.putString("notification.live.event", "NAVIGATION")
        se.putBoolean("notification.live.pinned", false)

        // 胶囊子 Bundle —— 注意：子 Bundle 内部的 key **仍然带 notification.live. 全前缀**。
        // 这写法反直觉，但就是智能服务代发百度实况时的原样（§1 原文 q(Icon,String)），
        // framework 的 getCapsuleContent() 也是按全前缀 key 从子 Bundle 里取。
        val capsule = Bundle()
        capsule.putInt("notification.live.capsuleStatus", 1)
        capsule.putInt("notification.live.capsuleType", 1)
        capsule.putString("notification.live.capsuleContent", content)
        capsule.putParcelable("notification.live.capsuleIcon", icon)
        capsule.putInt("notification.live.capsuleBgColor", CAPSULE_BG)
        capsule.putInt(
            "notification.live.capsuleContentColor", ArkConstants.readableTextOn(CAPSULE_BG)
        )
        se.putBundle("notification.live.capsule", capsule)

        try {
            nm.notify(NAV_SHADOW_TAG, NAV_SHADOW_ID, shadow)
            log(
                "shadow posted tag=$NAV_SHADOW_TAG capsuleType=1 content='$content' " +
                    "bg=0x${Integer.toHexString(CAPSULE_BG)}"
            )
        } catch (t: Throwable) {
            log("skip reason=notify_failed ${t.message}")
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG: $msg")
    }

    // ---------------------------------------------------------------- 安装

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpp.packageName
        if (!ArkConstants.isNavLiveApp(pkg)) return

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing for $pkg")

        val notifyHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (inNotify.get() == true) return
                    val n = param.args.filterIsInstance<Notification>().firstOrNull() ?: return
                    // 影子自己：放行，不再生成影子（否则无限递归）
                    if (isShadowNotification(n)) return
                    val reason = matchReason(n, param.args) ?: return
                    val id = notifIdOf(param.args)
                    log(
                        "hit pkg=$pkg id=${id ?: "-"} " +
                            "nav=${isNavigating(n)} category=${categoryOf(n)} via=$reason"
                    )
                    // 只有「真正把标记置位的那次调用」才有权在 after 里复位
                    param.setObjectExtra(OWNER_KEY, true)
                    inNotify.set(true)
                    // 原通知一个字段都不改
                    postShadow(pkg, n, param.thisObject, reason, id)
                } catch (t: Throwable) {
                    log("hook error: ${t.message}")
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.getObjectExtra(OWNER_KEY) == true) {
                    param.setObjectExtra(OWNER_KEY, null)
                    inNotify.set(false)
                }
            }
        }

        ArkHooks.quiet("$TAG notify") {
            val nmClass = XposedHelpers.findClass("android.app.NotificationManager", lpp.classLoader)
            ArkHooks.quiet("$TAG notify(int,Notification)") {
                XposedHelpers.findAndHookMethod(
                    nmClass, "notify", Int::class.javaPrimitiveType, Notification::class.java,
                    notifyHook
                )
            }
            ArkHooks.quiet("$TAG notify(String,int,Notification)") {
                XposedHelpers.findAndHookMethod(
                    nmClass, "notify",
                    String::class.java, Int::class.javaPrimitiveType, Notification::class.java,
                    notifyHook
                )
            }
        }

        // v3.15.52：高德在 SDK>=30 走 `Service.startForeground(...)` 发这条常驻导航通知，
        // **根本不经过 NotificationManager.notify**。v3.15.51 实测：日志里只有 installing，
        // 没有任何 hit —— 胶囊因此从未出现。这里补上两个重载。
        // 注意 `startForeground` 的 this 是 Service（本身就是 Context），
        // postShadow → resolveNm 会从它拿 NotificationManager。
        ArkHooks.quiet("$TAG startForeground") {
            val svcClass = XposedHelpers.findClass("android.app.Service", lpp.classLoader)
            ArkHooks.quiet("$TAG startForeground(int,Notification)") {
                XposedHelpers.findAndHookMethod(
                    svcClass, "startForeground",
                    Int::class.javaPrimitiveType, Notification::class.java, notifyHook
                )
            }
            ArkHooks.quiet("$TAG startForeground(int,Notification,int)") {
                XposedHelpers.findAndHookMethod(
                    svcClass, "startForeground",
                    Int::class.javaPrimitiveType, Notification::class.java,
                    Int::class.javaPrimitiveType, notifyHook
                )
            }
        }

        // 不在 App 侧 hook cancel：高德 cancel 的通知不一定是导航那条，一律清影子会让胶囊
        // 在高德刷新通知的间隙里闪没。销毁同步统一放 SystemUI 的 onNotificationRemoved。

        installProbes(lpp.classLoader)
    }

    // ---------------------------------------------------------------- 探针（log-only）
    //
    // v3.15.52：只为定位「真正的导航数据在哪」。三个探针全部 try/catch 包死，
    // 类找不到只打一行日志，绝不让高德崩（主力应用，崩了要挨骂）。

    /** 探针节流：同 key 内容没变化就直接跳过；有变化也最多 1500ms 一条 */
    private const val PROBE_MIN_INTERVAL_MS = 1500L

    private val probeLastAt = HashMap<String, Long>(4)
    private val probeLastMsg = HashMap<String, String>(4)

    private fun probe(key: String, msg: String) {
        try {
            val now = SystemClock.elapsedRealtime()
            synchronized(probeLastAt) {
                // 内容没变 → 不刷屏
                if (probeLastMsg[key] == msg) return
                val last = probeLastAt[key] ?: 0L
                if (now - last < PROBE_MIN_INTERVAL_MS) return
                probeLastAt[key] = now
                probeLastMsg[key] = msg
            }
            XposedBridge.log("[${ArkConstants.LOG_TAG}] probe $key: $msg")
        } catch (_: Throwable) {
        }
    }

    /** 一次性状态日志（安装结果 / 找不到 / hook 失败） */
    private fun probeRaw(msg: String) {
        XposedBridge.log("[${ArkConstants.LOG_TAG}] probe $msg")
    }

    /** 反射 dump 一个对象的全部实例字段（字段名是混淆的，遍历才不会因改名挂掉） */
    private fun dumpFields(obj: Any?): String {
        if (obj == null) return "null"
        return try {
            val sb = StringBuilder("{")
            var first = true
            for (f in obj.javaClass.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(obj)
                    if (!first) sb.append(", ")
                    first = false
                    sb.append(f.name).append('=').append(v)
                } catch (_: Throwable) {
                }
            }
            sb.append('}')
            sb.toString()
        } catch (_: Throwable) {
            // Kotlin 里 `String` 是 kotlin.String，没有 valueOf，用 java.lang.String
            java.lang.String.valueOf(obj)
        }
    }

    /** 依次按几个候选名找类（默认包类 jadx 会显示成 defpackage.xxx，真名是 xxx） */
    private fun findCls(cl: ClassLoader, vararg names: String): Class<*>? {
        for (n in names) {
            try {
                return XposedHelpers.findClass(n, cl)
            } catch (_: Throwable) {
            }
            try {
                return Class.forName(n, false, cl)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun installProbes(cl: ClassLoader) {
        // ---- 探针 1：fj5.notifyNaviInfo(ej5) —— 所有导航信息更新的唯一漏斗
        try {
            val c = findCls(cl, "fj5", "defpackage.fj5")
            if (c == null) {
                probeRaw("fj5: not found")
            } else {
                val set = XposedBridge.hookAllMethods(
                    c, "notifyNaviInfo",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                probe("fj5", dumpFields(param.args.getOrNull(0)))
                            } catch (_: Throwable) {
                            }
                        }
                    }
                )
                if (set.isEmpty()) {
                    probeRaw("fj5: method notifyNaviInfo not found in ${c.name}")
                } else {
                    probeRaw("fj5: hooked ${c.name}.notifyNaviInfo x${set.size}")
                }
            }
        } catch (t: Throwable) {
            probeRaw("fj5: hook failed ${t.message}")
        }

        // ---- 探针 2：ModuleRoute.notifyNaviInfo(JSONObject) —— AJX 页面下来的原始载荷
        try {
            val c = findCls(cl, "com.autonavi.bundle.routecommon.ajx.ModuleRoute")
            if (c == null) {
                probeRaw("routeJson: ModuleRoute not found")
            } else {
                val set = XposedBridge.hookAllMethods(
                    c, "notifyNaviInfo",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val a = param.args.getOrNull(0)
                                probe(
                                    "routeJson",
                                    if (a is org.json.JSONObject) a.toString() else dumpFields(a)
                                )
                            } catch (_: Throwable) {
                            }
                        }
                    }
                )
                if (set.isEmpty()) {
                    probeRaw("routeJson: method notifyNaviInfo not found in ${c.name}")
                } else {
                    probeRaw("routeJson: hooked ${c.name}.notifyNaviInfo x${set.size}")
                }
            }
        } catch (t: Throwable) {
            probeRaw("routeJson: hook failed ${t.message}")
        }

        // ---- 探针 3：GStatusObserver.onTbtStatusChanged(int,int) —— native TBT 状态回调
        // 注意：这是一个**抽象接口**，dex 里没有任何 class_def 实现它（很可能是 lambda /
        // JNI 侧注册的匿名实现），所以直接 hook 接口方法大概率失败。失败也照实打日志，
        // 不吞、不假装成功。
        try {
            val c = findCls(cl, "com.autonavi.jni.ae.guide.observer.GStatusObserver")
            if (c == null) {
                probeRaw("tbt: not found")
            } else {
                val set = try {
                    XposedBridge.hookAllMethods(
                        c, "onTbtStatusChanged",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val i = param.args.getOrNull(0)
                                    val i2 = param.args.getOrNull(1)
                                    probe("tbt", "i=$i i2=$i2")
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    )
                } catch (t: Throwable) {
                    probeRaw(
                        "tbt: ${c.name} is ${if (c.isInterface) "interface" else "class"}, " +
                            "cannot hook: ${t.message}"
                    )
                    null
                }
                if (set != null) {
                    if (set.isEmpty()) {
                        probeRaw("tbt: method onTbtStatusChanged not found in ${c.name}")
                    } else {
                        probeRaw("tbt: hooked ${c.name}.onTbtStatusChanged x${set.size}")
                    }
                }
            }
        } catch (t: Throwable) {
            probeRaw("tbt: hook failed ${t.message}")
        }

        // ---- 探针 4：动态挂所有 NaviEventReceiver 实现（v3.15.53）
        //
        // 高德 TBT 引擎的 Java 入口是
        //   com.autonavi.jni.eyrie.amap.tbt.NaviManager.registerEventReceiver(NaviEventReceiver)
        // `NaviEventReceiver` 是 abstract 类，dex 里的具体子类只有 6 个，且全是混淆名
        // （ke1$a / ua4$a / hy1$a …）—— 硬编码类名必然随版本挂掉。
        // 所以只挂「注册点」，拿到实参对象后在它的 getClass() 上装那两个抽象方法。
        try {
            val naviMgr = findCls(cl, "com.autonavi.jni.eyrie.amap.tbt.NaviManager")
            if (naviMgr == null) {
                probeRaw("recv: NaviManager not found")
            } else {
                val regSet = XposedBridge.hookAllMethods(
                    naviMgr, "registerEventReceiver",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val receiver = param.args.getOrNull(0) ?: return
                                val rc = receiver.javaClass
                                if (!recvHooked.add(rc.name)) return
                                probeRaw("recv: registered ${rc.name}")
                                hookReceiverMethods(rc)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                )
                probeRaw(
                    if (regSet.isEmpty()) {
                        "recv: registerEventReceiver not found in ${naviMgr.name}"
                    } else {
                        "recv: hooked ${naviMgr.name}.registerEventReceiver x${regSet.size}"
                    }
                )

                // 注销探针：判断导航结束时高德是否真的反注册（决定胶囊该何时消失）
                try {
                    val unregSet = XposedBridge.hookAllMethods(
                        naviMgr, "unregisterEventReceiver",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val receiver = param.args.getOrNull(0) ?: return
                                    probeRaw("recv: unregistered ${receiver.javaClass.name}")
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    )
                    if (unregSet.isEmpty()) {
                        probeRaw("recv: unregisterEventReceiver not found in ${naviMgr.name}")
                    } else {
                        probeRaw("recv: hooked ${naviMgr.name}.unregisterEventReceiver x${unregSet.size}")
                    }
                } catch (t: Throwable) {
                    probeRaw("recv: unregisterHook failed ${t.message}")
                }
            }
        } catch (t: Throwable) {
            probeRaw("recv: NaviManager hook failed ${t.message}")
        }

        // ---- 探针 5：NaviManager.queryData(int, String)（v3.15.53）
        //
        // UI 每帧要显示的数据都是从这里 poll 出来的，比盲猜事件类型快得多：
        // 能看到它在轮询哪些 key、以及返回 JSON 的字段结构。
        try {
            val naviMgr = findCls(cl, "com.autonavi.jni.eyrie.amap.tbt.NaviManager")
            if (naviMgr == null) {
                probeRaw("qdata: NaviManager not found")
            } else {
                val set = XposedBridge.hookAllMethods(
                    naviMgr, "queryData",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val i = param.args.getOrNull(0)
                                val k = param.args.getOrNull(1)
                                val r = param.result
                                probe("qdata", "i=$i key=$k -> ${r?.toString()?.take(500)}")
                            } catch (_: Throwable) {
                            }
                        }
                    }
                )
                probeRaw(
                    if (set.isEmpty()) {
                        "qdata: queryData not found in ${naviMgr.name}"
                    } else {
                        "qdata: hooked ${naviMgr.name}.queryData x${set.size}"
                    }
                )
            }
        } catch (t: Throwable) {
            probeRaw("qdata: hook failed ${t.message}")
        }

        // ---- 探针 6：语音播报任务（JNIPlayer 实现类）（v3.15.54）
        //
        // v3.15.53 的 NaviManager 探针在真机真实导航中零触发 —— 高德手机端不走
        // eyrie TBT 引擎（那是车机投射 / HiCar 用的）。改看语音播报：
        //   com.autonavi.jni.tts.AudioPlayerTask  { taskType(short), data, groupData, ... }
        //   com.autonavi.jni.tts.JNIPlayer (abstract)
        //       playAudioFileStart(AudioPlayerTask) / playPCMStart(AudioPlayerTask)
        //       playAudioFile(long, short, String, int, int)   // 第 3 参是 .spx 路径
        // 假设待验证：固定短句是预录 .spx 文件，TTS 合成文本在 AudioPlayerTask.data。
        try {
            val taskCls = findCls(cl, "com.autonavi.jni.tts.AudioPlayerTask")
            if (taskCls == null) {
                probeRaw("tts: AudioPlayerTask not found")
            } else {
                probeRaw("tts: AudioPlayerTask = ${taskCls.name}")
                // 快速路径：混淆名通常是 j25，命中就立刻挂
                val fast = findCls(cl, "j25")
                if (fast == null) {
                    probeRaw("tts: fast path j25 absent, rely on loadClass watcher")
                } else if (fast.superclass?.name == JNI_PLAYER_CLS) {
                    hookPlayerImpl(fast, taskCls)
                } else {
                    probeRaw("tts: j25 super=${fast.superclass?.name} (not JNIPlayer), skip fast path")
                }
                // 兜底：运行期发现（类名随版本变也不怕）
                installClassLoadWatcher(taskCls)
            }
        } catch (t: Throwable) {
            probeRaw("tts: probe install failed ${t.message}")
        }
    }

    // ---------------------------------------------------------------- 探针 6 的实现

    private const val JNI_PLAYER_CLS = "com.autonavi.jni.tts.JNIPlayer"

    /** 语音任务日志总量上限，防止播报密集时刷爆 logcat */
    private var ttsCount = 0
    private const val TTS_MAX_LOG = 200

    /** 已挂过探针的 JNIPlayer 实现类（按类名去重） */
    private val playerHooked = HashSet<String>(4)

    /**
     * 运行期发现 `JNIPlayer` 的实现类。
     *
     * 为什么不硬编码 `j25`：混淆类名会随高德版本变。这里挂 `ClassLoader.loadClass`，
     * 每次类加载后只做一次 `superclass?.name` 字符串比较，非目标立刻 return，
     * 开销可以忽略；整个 hook 也包在 try/catch 里。
     */
    private fun installClassLoadWatcher(taskCls: Class<*>) {
        try {
            XposedBridge.hookAllMethods(
                ClassLoader::class.java, "loadClass",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 同一个名字还有 loadClass(String, boolean) 重载，只处理单参那个
                        if (param.args.size != 1) return
                        if (param.args[0] !is String) return
                        val c = param.result as? Class<*> ?: return
                        if (c.superclass?.name != JNI_PLAYER_CLS) return
                        try {
                            hookPlayerImpl(c, taskCls)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            probeRaw("tts: watching ClassLoader.loadClass for $JNI_PLAYER_CLS impl")
        } catch (t: Throwable) {
            probeRaw("tts: loadClass watcher failed ${t.message}")
        }
    }

    /** 在一个 JNIPlayer 实现类上挂三个方法（逐个 try/catch） */
    private fun hookPlayerImpl(c: Class<*>, taskCls: Class<*>) {
        if (!playerHooked.add(c.name)) return
        probeRaw("tts: player impl ${c.name}")

        hookTaskMethod(c, taskCls, "playAudioFileStart", "tts", "start")
        hookTaskMethod(c, taskCls, "playPCMStart", "ttspcm", "pcm")

        // playAudioFile(long, short, String, int, int)：第 3 参是音频文件路径
        try {
            val m = c.getDeclaredMethod(
                "playAudioFile",
                Long::class.javaPrimitiveType,
                Short::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            XposedBridge.hookMethod(
                m,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (ttsCount > TTS_MAX_LOG) return
                            val snd = param.args.getOrNull(1)
                            val file = param.args.getOrNull(2) as? String
                            ttsCount++
                            probe("ttsf", "snd=$snd file='${file?.take(120) ?: ""}'")
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            probeRaw("tts: hooked ${c.name}.playAudioFile")
        } catch (t: Throwable) {
            probeRaw("tts: playAudioFile unavailable on ${c.name}: ${t.message}")
        }
    }

    /** 挂 `playAudioFileStart` / `playPCMStart`（参数是 AudioPlayerTask） */
    private fun hookTaskMethod(
        c: Class<*>,
        taskCls: Class<*>,
        methodName: String,
        key: String,
        prefix: String
    ) {
        try {
            val m = c.getDeclaredMethod(methodName, taskCls)
            m.isAccessible = true
            XposedBridge.hookMethod(
                m,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (ttsCount > TTS_MAX_LOG) return
                            val task = param.args.getOrNull(0) ?: return
                            val type = try {
                                XposedHelpers.getShortField(task, "taskType")
                            } catch (_: Throwable) {
                                -1
                            }
                            val data = try {
                                XposedHelpers.getObjectField(task, "data") as? String
                            } catch (_: Throwable) {
                                null
                            }
                            val group = try {
                                XposedHelpers.getObjectField(task, "groupData") as? String
                            } catch (_: Throwable) {
                                null
                            }
                            ttsCount++
                            probe(
                                key,
                                "$prefix type=$type data='${data?.take(200) ?: ""}' " +
                                    "group='${group?.take(80) ?: ""}'"
                            )
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            probeRaw("tts: hooked ${c.name}.$methodName")
        } catch (t: Throwable) {
            probeRaw("tts: $methodName unavailable on ${c.name}: ${t.message}")
        }
    }

    /** 已挂过探针的 receiver 实现类（按类名去重，避免重复注册时重复挂） */
    private val recvHooked = HashSet<String>(8)

    /** TBT 事件日志总量上限（导航时每秒十几条，无上限会刷爆 logcat） */
    private var recvCount = 0
    private const val RECV_MAX_LOG = 300

    /**
     * 在一个具体的 NaviEventReceiver 实现类上装两个抽象方法的探针。
     * 每一步单独 try/catch —— 方法签名变了只丢这一条日志，绝不影响高德。
     */
    private fun hookReceiverMethods(rc: Class<*>) {
        try {
            val m = rc.getDeclaredMethod("onNaviEvent", String::class.java)
            m.isAccessible = true
            XposedBridge.hookMethod(
                m,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (recvCount > RECV_MAX_LOG) return
                            val s = param.args.getOrNull(0) as? String ?: return
                            recvCount++
                            probe("recv", "${rc.simpleName} ${s.take(600)}")
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            probeRaw("recv: hooked ${rc.name}.onNaviEvent")
        } catch (t: Throwable) {
            probeRaw("recv: onNaviEvent unavailable on ${rc.name}: ${t.message}")
        }

        // onNaviNonSerialEvent(String, byte[])：第二参是二进制，内容不打印（不刷屏），
        // 每个实现类只记一条「真的在走这条路」，用来判断哪条 receiver 是活跃的。
        try {
            val m = rc.getDeclaredMethod(
                "onNaviNonSerialEvent", String::class.java, ByteArray::class.java
            )
            m.isAccessible = true
            XposedBridge.hookMethod(
                m,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (!recvNonSerialSeen.add(rc.name)) return
                            val len = (param.args.getOrNull(1) as? ByteArray)?.size ?: 0
                            probeRaw("recv: nonSerial active on ${rc.name} bytes=$len")
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            probeRaw("recv: hooked ${rc.name}.onNaviNonSerialEvent")
        } catch (t: Throwable) {
            probeRaw("recv: onNaviNonSerialEvent unavailable on ${rc.name}: ${t.message}")
        }
    }

    /** 已经见过非序列事件的 receiver（每类只记一条） */
    private val recvNonSerialSeen = HashSet<String>(8)

    private fun isNavigating(n: Notification): Boolean = try {
        n.extras?.getBoolean(EXTRA_NAVIGATING, false) == true
    } catch (_: Throwable) {
        false
    }

    private fun categoryOf(n: Notification): String = try {
        n.category ?: "-"
    } catch (_: Throwable) {
        "?"
    }
}

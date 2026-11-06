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
 * ## v3.15.55：胶囊文案跟随语音播报实时更新
 *
 * 导航通知的 `android.text` 永远是占位值（`正在导航`），所以 v3.15.51 的胶囊只能显示
 * `高德地图 · 正在导航`。四轮探针（v3.15.52~54）最终在**语音播报任务**里找到了明文：
 *
 * ```
 * JNIPlayer 实现类.playPCMStart(AudioPlayerTask)
 *   AudioPlayerTask.taskType  = 0 / 7   → TTS 合成
 *   AudioPlayerTask.data      = '前方三百米右转'        ← 明文中文
 *   AudioPlayerTask.groupData = '["...","..."]'        ← 本轮播报的分句数组
 * ```
 *
 * 于是现在的链路是：
 *
 * ```
 * 高德发导航通知 (startForeground)   → 发影子（占位文案）→ navActive = true
 * 高德播报         (playPCMStart)   → extractNaviText() → 覆盖影子的 capsuleContent
 * 导航结束         (通知被移除)      → SystemUI 撤影子 / 下次播报时 navNotificationAlive() 拦下
 * ```
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

    /** 胶囊文案长度上限：胶囊区很窄，太长的播报句在 UI 上会被硬截断，这里主动收口 */
    private const val MAX_CAPSULE_TEXT = 40

    /** 提取候选串时要去掉的边缘字符（高德的 `data` / `groupData` 分句常带前后逗号） */
    private val TRIM_CHARS = charArrayOf(',', '，', ' ', '\u3000', '、', '。')

    /**
     * 无信息量的播报句（欢迎语 / 状态播报 / 启动语）。
     *
     * 这些句子在导航里确实会播，但把它们塞进胶囊等于把「前方三百米右转」挤掉，
     * 所以直接丢弃，宁可胶囊保持上一句有效指令。
     */
    private val NOISE_PHRASES = arrayOf(
        "真高兴", "又见到你", "准备出发", "持续为您导航", "开启导航", "欢迎使用"
    )

    // ---------------------------------------------------------------- 播报 → 胶囊 的实时状态
    //
    // 重要：以下字段全部活在**高德进程**里（hook 装在 App 侧）。SystemUI 侧那一份
    // ArkNavLiveInjector 是另一个进程里的另一份对象，两边静态字段互不影响。
    // 所以「导航什么时候算结束」必须由本进程自己判断，见 navNotificationAlive()。

    /** 导航进行中标志：成功发出第一条影子时置 true */
    private var navActive = false

    /** 上一次真正写进胶囊的播报文案（去重：同一句反复播不重复刷通知） */
    private var lastNaviText = ""

    /** 上一次「导航通知命中」的时刻，用于判断两次命中之间是否中断过（导航结束又重开） */
    private var lastHitAt = 0L

    /** 两次命中间隔超过这个值就认为上一轮导航已结束（导航中高德刷新通知是秒级的） */
    private const val SESSION_GAP_MS = 90_000L

    /**
     * 上一次从源导航通知里取到的素材。
     *
     * 播报更新胶囊时**不重新去源通知里抠**：高德更新导航通知的时机和播报不同步，
     * 重抠有可能抠到半成品（图标被回收 / text 为空）。素材只在 `navInject: hit`
     * 那一刻取一次，之后一直复用。
     */
    private var lastTitle: String? = null
    private var lastBody: String? = null
    private var lastNaviIcon: Icon? = null
    private var lastSmallIcon: Icon? = null
    private var lastContentIntent: PendingIntent? = null
    private var lastCtx: Context? = null
    private var lastNm: NotificationManager? = null

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

        // ---- 决定这一帧胶囊写什么
        //
        // 高德在导航中会**频繁刷新**这条通知（剩余距离 / ETA 每秒级），每次都进到这里。
        // 如果这里一律用 `title · text`（占位文案「高德地图 · 正在导航」），
        // 那么刚由语音播报写进胶囊的真实指令会在几十毫秒内被盖回去 —— 用户看到的就是
        // 胶囊在「前方三百米右转」和「正在导航」之间乱跳。所以：
        //   * 同一轮导航里已经有播报文案 → 继续沿用播报文案；
        //   * 新一轮导航（间隔超过 SESSION_GAP_MS）→ 丢掉上一轮的旧指令，回到占位文案。
        val now = SystemClock.elapsedRealtime()
        val sameSession = navActive && (now - lastHitAt) < SESSION_GAP_MS
        if (!sameSession) lastNaviText = ""
        lastHitAt = now
        val content = if (sameSession && lastNaviText.isNotEmpty()) {
            lastNaviText
        } else {
            if (text.isNullOrBlank()) title else "$title · $text"
        }

        // 素材只在这里存一次，之后所有播报更新都复用它（见 lastTitle 上的注释）
        lastTitle = title
        lastBody = text
        lastNaviIcon = icon
        lastSmallIcon = smallIcon
        lastContentIntent = try {
            src.contentIntent
        } catch (_: Throwable) {
            null
        }
        lastCtx = ctx
        lastNm = nm

        ensureChannel(nm)

        val shadow = buildShadow(content)
        if (shadow == null) {
            log("skip reason=build_failed")
            return
        }
        if (!publishShadow(nm, shadow)) return

        // 导航在跑 —— 从此语音播报可以实时改写胶囊文案。
        // **不能**在这里清 lastNaviText：导航中通知是秒级刷新的，清了就等于
        // 每次刷新都把刚写的指令抹掉（清旧文案的判断在 sameSession 那几行里）。
        navActive = true
        log(
            "shadow posted tag=$NAV_SHADOW_TAG capsuleType=1 content='$content' " +
                "bg=0x${Integer.toHexString(CAPSULE_BG)}"
        )
    }

    /** 建（或复用）影子通知通道：低优先级 + 静音 + 无振动 + 不显示角标 */
    private fun ensureChannel(nm: NotificationManager) {
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
    }

    /**
     * 用「上一次从源通知取到的素材」+ 指定胶囊文案，拼一条影子通知。
     *
     * 抽出来是为了让首次发布（[postShadow]）与跟随播报更新（[updateShadowContent]）
     * 共用同一份字段拼装逻辑 —— 两处各写一遍必然会出现「更新之后图标没了」这类问题。
     *
     * **静音保证**：`setOnlyAlertOnce(true)` 只压得住「同一条通知再次更新」的提醒，
     * 所以额外 `setDefaults(0)` 清掉 DEFAULT_SOUND / DEFAULT_VIBRATE / DEFAULT_LIGHTS，
     * 通道本身也是 IMPORTANCE_LOW + 无声音 + 无振动，三重保险，更新文案绝不响不震。
     *
     * @return 素材不全（无 Context / title / 大图标 / 小图标）时返回 null
     */
    @Suppress("DEPRECATION")
    private fun buildShadow(content: String): Notification? {
        val ctx = lastCtx ?: return null
        val title = lastTitle ?: return null
        val largeIcon = lastNaviIcon ?: return null
        val smallIcon = lastSmallIcon ?: return null
        return try {
            val b = Notification.Builder(ctx, NAV_SHADOW_CHANNEL)
                .setSmallIcon(smallIcon)
                .setContentTitle(title)
                .setContentText(lastBody ?: title)
                .setLargeIcon(largeIcon)
                .setOnlyAlertOnce(true)
                .setDefaults(0)
                .setShowWhen(false)
                .setAutoCancel(false)
                // 导航本身是常驻通知，影子同样常驻；结束时由 SystemUI 侧
                // onNotificationRemoved 同步撤销（ArkLiveShadowHook）
                .setOngoing(true)
            val ci = lastContentIntent
            if (ci != null) b.setContentIntent(ci)
            val n = b.build()
            val se = n.extras
            // 影子标记（SystemUI 据此隐藏行；本 hook 据此避免自我递归）
            se.putBoolean(ArkMessageLiveInjector.KEY_SHADOW, true)

            // 顶层 live 字段（与消息卡一致，type=10 即「实况」；event 换成导航）
            se.putBoolean("is_live", true)
            se.putInt("notification.live.operation", 0)
            se.putInt("notification.live.type", 10)
            se.putString("notification.live.event", "NAVIGATION")
            se.putBoolean("notification.live.pinned", false)

            // 胶囊子 Bundle —— 子 Bundle 内部的 key **仍然带 notification.live. 全前缀**。
            // 这写法反直觉，但就是智能服务代发百度实况时的原样（§1 原文 q(Icon,String)），
            // framework 的 getCapsuleContent() 也是按全前缀 key 从子 Bundle 里取。
            val capsule = Bundle()
            capsule.putInt("notification.live.capsuleStatus", 1)
            capsule.putInt("notification.live.capsuleType", 1)
            capsule.putString("notification.live.capsuleContent", content)
            capsule.putParcelable("notification.live.capsuleIcon", largeIcon)
            capsule.putInt("notification.live.capsuleBgColor", CAPSULE_BG)
            capsule.putInt(
                "notification.live.capsuleContentColor", ArkConstants.readableTextOn(CAPSULE_BG)
            )
            se.putBundle("notification.live.capsule", capsule)
            n
        } catch (t: Throwable) {
            log("build shadow failed: ${t.message}")
            null
        }
    }

    /** 发布 / 覆盖影子通知（同一个 tag+id，新帧直接顶掉旧帧，不会堆积） */
    private fun publishShadow(nm: NotificationManager, shadow: Notification): Boolean = try {
        nm.notify(NAV_SHADOW_TAG, NAV_SHADOW_ID, shadow)
        true
    } catch (t: Throwable) {
        log("skip reason=notify_failed ${t.message}")
        false
    }

    // ---------------------------------------------------------------- 播报 → 胶囊

    /**
     * 从一条 `AudioPlayerTask` 里抠出「可以放进胶囊的那句中文」。
     *
     * 真机日志（v3.15.54 验证）：
     * ```
     * pcm type=7 data='又见到你啦！真高兴啊！'
     *           group='["又见到你啦！真高兴啊！","准备出发,",",全程八公里,大约需要二十七分钟,右转,随后一百五十米右转"]'
     * pcm type=0 data='高德地图持续为您导航'  group='["高德地图持续为您导航"]'
     * ```
     *
     * 取法（按优先级）：
     *  1. `groupData` 是**这一轮播报的分句数组**，取最后一个非空元素 → 它就是「即将播报」的那句；
     *  2. 该元素内部还带逗号时（高德会把整段并成一条），再取第一个分句 —— 播报顺序是
     *     从前到后，所以第一个分句才是此刻该开口的那句（「全程八公里,大约…,右转」→「全程八公里」）；
     *  3. 第 1/2 步的结果没过筛（过短 / 噪音词）→ 退回 `data` 再试一次
     *     （两者有时不是同一句：`data` 单句、`groupData` 是整段队列）；
     *  4. 都不过 → 返回 null，胶囊保持上一句有效指令。
     *
     * @return 可写入胶囊的文案；无有效文案返回 null
     */
    private fun extractNaviText(task: Any?): String? {
        if (task == null) return null
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

        acceptCandidate(lastSentenceOf(group))?.let { return it }
        return acceptCandidate(data)
    }

    /**
     * 取分句数组里「此刻最新」的那一句。
     *
     * 最后一个非空元素 → 若其中还有逗号分句，取第一个分句。
     * 为什么是「第一个分句」：高德把整段待播队列并成一个元素（真机日志里
     * `",全程八公里,大约需要二十七分钟,右转,随后一百五十米右转"`），而播报顺序是
     * 从前到后 —— 第一个分句正是这一刻要开口的那句。
     *
     * 两步都 try/catch：`groupData` 偶尔不是合法 JSON（真机见过空数组 / 单字符串），
     * 解析失败就当没有，由调用方退回 `data`。
     */
    private fun lastSentenceOf(group: String?): String? {
        if (group.isNullOrBlank()) return null
        return try {
            val arr = org.json.JSONArray(group)
            var picked: String? = null
            for (i in arr.length() - 1 downTo 0) {
                val s = arr.optString(i, "").trim(*TRIM_CHARS)
                if (s.isNotEmpty()) {
                    picked = s
                    break
                }
            }
            picked?.let { p ->
                val firstSeg = p.split(',', '，').firstOrNull { it.isNotBlank() }
                firstSeg?.trim(*TRIM_CHARS)?.takeIf { it.isNotEmpty() } ?: p
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 候选串能不能进胶囊：长度 ≥ 2 且不含噪音词。
     *
     * @return 通过则返回（可能被截断的）文案，否则 null
     */
    private fun acceptCandidate(candidate: String?): String? {
        val raw = candidate?.trim(*TRIM_CHARS) ?: return null
        if (raw.length < 2) return null
        for (noise in NOISE_PHRASES) {
            if (raw.contains(noise)) return null
        }
        return if (raw.length > MAX_CAPSULE_TEXT) {
            raw.substring(0, MAX_CAPSULE_TEXT) + "…"
        } else {
            raw
        }
    }

    /**
     * 用新的播报文案覆盖胶囊。
     *
     * 三道闸，缺一不可：
     *  1. `navActive` —— 没在导航就不要凭空造胶囊；
     *  2. `content != lastNaviText` —— 同一句反复播（高德会重播）不重复刷通知；
     *  3. `navNotificationAlive()` —— 源导航通知已经没了说明导航结束，
     *     此时再来一条播报**绝不能把胶囊复活**（这是 v3.15.55 最容易出错的地方）。
     */
    private fun updateShadowContent(content: String) {
        try {
            if (!navActive) return
            if (content == lastNaviText) return
            val nm = lastNm ?: return
            if (!navNotificationAlive(nm)) {
                navActive = false
                lastNaviText = ""
                log("nav ended (source notification gone), stop updating capsule")
                return
            }
            val shadow = buildShadow(content) ?: return
            nm.notify(NAV_SHADOW_TAG, NAV_SHADOW_ID, shadow)
            lastNaviText = content
            XposedBridge.log("[${ArkConstants.LOG_TAG}] navTts: $content")
        } catch (_: Throwable) {
        }
    }

    /**
     * 本进程里高德那条导航通知还在不在。
     *
     * `NotificationManager.getActiveNotifications()` 只能看到**自己包**发的通知，
     * 而高德的导航通知正是同包（可能另一个进程，但 uid 相同，一样看得到），
     * 所以这是最便宜、也最不会误判的「导航是否还在进行」判据。
     *
     * @return 拿不到列表时返回 true（宁可多更新一帧，也不要提前把功能关掉）
     */
    private fun navNotificationAlive(nm: NotificationManager): Boolean = try {
        nm.activeNotifications.any { sbn ->
            sbn.id in NAV_NOTIF_IDS || (
                try {
                    sbn.notification?.extras?.getBoolean(EXTRA_NAVIGATING, false) == true
                } catch (_: Throwable) {
                    false
                }
                )
        }
    } catch (_: Throwable) {
        true
    }

    /**
     * 导航结束的通知入口。
     *
     * **调用方注意**：本函数由 SystemUI 进程里的 `ArkLiveShadowHook` 调用，改的是
     * SystemUI 那份对象，**影响不到高德进程里的这一份**（静态字段不跨进程）。
     * 真正让胶囊停止更新的是 [updateShadowContent] 里的 [navNotificationAlive] 判定。
     * 保留这个入口是为了让 SystemUI 侧的「导航结束」有明确落点，将来若加跨进程通道
     * （ContentProvider / 广播）可以从这里接。
     */
    internal fun onNavEnded() {
        navActive = false
        lastNaviText = ""
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

        installVoiceHook(lpp.classLoader)
    }

    // ---------------------------------------------------------------- 语音播报 → 胶囊
    //
    // v3.15.52~54 用四轮探针把「真正的导航数据在哪」挖出来了：不在通知层、不在 AJX 漏斗、
    // 不在 eyrie TBT 引擎，而在**语音播报任务**里 —— 具体是
    // `com.autonavi.jni.tts.JNIPlayer.playPCMStart(AudioPlayerTask)`，
    // `AudioPlayerTask.data` / `groupData` 里就是明文中文。
    //
    // v3.15.55 起这段不再是「探针」，而是**功能本体**：抓到播报文案就改写胶囊。
    // 仍然全程 try/catch 包死 —— 高德是主力应用，探针出错绝不能让它崩。

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

    /**
     * 反射 dump 一个对象的全部实例字段（字段名是混淆的，遍历才不会因改名挂掉）。
     *
     * v3.15.55 之后没有调用点，但保留它：下次再有「这个类里到底有什么」的排查，
     * 复制一行 `probeRaw(dumpFields(x))` 就能用，不必重新写反射。
     */
    @Suppress("unused")
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

    /**
     * 找到 `JNIPlayer` 的实现类并挂上三个播放方法。
     *
     * v3.15.54 起承担**真功能**：`playPCMStart` 的实参里就有即将播报的明文中文，
     * 抓到它就能把胶囊文案换成「前方三百米右转」这类真实指令。
     */
    private fun installVoiceHook(cl: ClassLoader) {
        // ---- 语音播报任务（JNIPlayer 实现类）
        //
        // v3.15.53 的 NaviManager 探针在真机真实导航中零触发 —— 高德手机端不走
        // eyrie TBT 引擎（那是车机投射 / HiCar 用的）。改看语音播报：
        //   com.autonavi.jni.tts.AudioPlayerTask  { taskType(short), data, groupData, ... }
        //   com.autonavi.jni.tts.JNIPlayer (abstract)
        //       playAudioFileStart(AudioPlayerTask) / playPCMStart(AudioPlayerTask)
        //       playAudioFile(long, short, String, int, int)   // 第 3 参是 .spx 路径
        // v3.15.54 真机日志已证实：taskType 0/7 是 TTS 合成，data/groupData 是明文中文。
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
        // playPCMStart 是**功能入口**：真机证实 TTS 合成（taskType 0/7）走这条路，
        // 实参 task 的 data / groupData 就是即将播报的明文中文。
        hookTaskMethod(c, taskCls, "playPCMStart", "ttspcm", "pcm", feedCapsule = true)

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

    /**
     * 挂 `playAudioFileStart` / `playPCMStart`（参数是 `AudioPlayerTask`）。
     *
     * @param feedCapsule true 表示这个方法除了打日志，还要把播报文案喂给胶囊
     *   （只有 `playPCMStart` 是 true；`playAudioFileStart` 是 type=1 的预录片段，不参与）
     */
    private fun hookTaskMethod(
        c: Class<*>,
        taskCls: Class<*>,
        methodName: String,
        key: String,
        prefix: String,
        feedCapsule: Boolean = false
    ) {
        try {
            val m = c.getDeclaredMethod(methodName, taskCls)
            m.isAccessible = true
            XposedBridge.hookMethod(
                m,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val task = try {
                            param.args.getOrNull(0)
                        } catch (_: Throwable) {
                            null
                        } ?: return

                        // ① 真功能：播报文案 → 胶囊。
                        //    注意必须排在日志上限判断**之前** —— 否则播报超过 200 条
                        //    之后功能就悄悄停了（v3.15.55 差点写错的地方）。
                        if (feedCapsule) {
                            try {
                                if (navActive) {
                                    extractNaviText(task)?.let { updateShadowContent(it) }
                                }
                            } catch (_: Throwable) {
                            }
                        }

                        // ② 日志：保留原有观测能力，仍有 200 条上限防止刷爆 logcat
                        try {
                            if (ttsCount > TTS_MAX_LOG) return
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

package com.flymeark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /**
     * 胶囊文案长度上限。
     *
     * 演进：40（v3.15.55）→ 24（v3.15.56）→ **28**（v3.15.57）。
     * 24 是真机试出来的「不截断就放不下」的上限，但实测会把 `七十米后左转，随后六十米右转`
     * （13 字）之外的正常长句也切掉，28 是折中值。
     */
    private const val MAX_CAPSULE_TEXT = 28

    /**
     * 纯数字串。
     *
     * 高德偶尔把资源 id 当文案塞进 `AudioPlayerTask.data`（真机见过 `2131558436`），
     * 这种串一旦写进胶囊就是一个莫名其妙的数字，必须挡掉。
     */
    private val PURE_DIGITS = Regex("^\\d+$")

    /**
     * 转向 / 动作关键词。命中这些词的段落才算「指令」，优先展示。
     *
     * 排序上「靠左行驶 / 靠右行驶」放在「靠左 / 靠右」之后没有影响 —— 这里用的是
     * `contains` 逐个试，只要任一词命中即可，不做最长匹配（`靠右行驶` 里必然也含 `靠右`）。
     */
    private val TURN_KEYWORDS = arrayOf(
        "左转", "右转", "直行", "掉头",
        "靠左", "靠右", "靠左行驶", "靠右行驶",
        "向左", "向右", "向左前方", "向右前方",
        "进入", "驶入", "上桥", "下桥", "上匝道", "下匝道"
    )

    /**
     * 无信息量的播报句（欢迎语 / 状态播报 / 骑行跑步提示）。
     *
     * 这些句子在导航里确实会播，但把它们塞进胶囊等于把「前方三百米右转」挤掉，
     * 所以直接丢弃，宁可胶囊保持上一句有效指令。
     *
     * v3.15.57 追加：真机里胶囊曾显示 `请移动到室外开阔地带`（骑行模式提示，出现 3 次），
     * 以及步行模式的 `请步行到室外开阔地带`、`以手机朝向为准`（都需要用户动作，不是导航指令）。
     *
     * **噪声词对整串做 `contains` 判断**（不是只对拼好的结果判断）——
     * `以手机朝向为准,路在右边,请向右走` 里最后一段含「向右」，如果只看结果就会被当成指令。
     */
    private val NOISE_PHRASES = arrayOf(
        "真高兴", "又见到你", "准备出发", "持续为您导航", "开启导航", "欢迎使用",
        "室外开阔", "开阔地带", "以手机朝向为准", "一路顺风", "祝您",
        // v3.15.58：导航启动语。只需一条 ——「导航开始」是「步行导航开始」「驾车导航开始」
        // 「骑行导航开始」的子串，`contains` 一条全覆盖。
        // 它不会误伤 `开始导航后请靠右`（那是「开始导航后」，顺序相反）。
        "导航开始"
    )

    /**
     * 结束语。命中不仅要丢掉文案，还要把导航判为结束（见 [endNavForFarewell]）。
     *
     * 原因（真机 v3.15.56/57 观测）：导航结束后高德仍会播 `这次的旅途结束了呐，下次再会！`、
     * `导航到此为止啦，下次再见哦`。此时影子已被 SystemUI 撤掉，若还允许
     * `updateShadowContent` 跑，就会**把胶囊复活**，屏幕上冒出一个没有意义的「旅途结束」。
     *
     * v3.15.58 补齐：真机退出导航时播的是 `导航到此为止啦，下次再见哦` —— 老的 4 个词
     * 一个都没命中，结束语被当普通文案写进胶囊，于是退出后胶囊常驻不消。这就是本版要修的 bug。
     *
     * 注意这些词**不在** [NOISE_PHRASES] 里 —— 两者处理方式不同（这里要副作用）。
     */
    private val END_PHRASES = arrayOf(
        "旅途结束", "下次再会", "期待与您再次", "导航结束啦",
        "导航到此为止", "下次再见", "导航结束", "已到达目的地", "您已到达"
    )

    /**
     * 出发类短语。只在导航**起点**播，用来解除退出静默窗。
     *
     * 为什么不复用 [pickCapsuleText] 的「任意有效指令」：真机实测高德在导航过程中
     * **不重发导航通知**（只有冷启动时重复提交一次，两次命中相隔 74ms），
     * 所以解窗后没有任何 `postShadow` 会来补发影子 —— 只能靠 [updateShadowContent]
     * 自愈。而且「任意有效指令」会让退出瞬间残留的转向播报把刚关上的窗顶开，
     * 让 v3.15.58 修好的「胶囊不销毁」复发。用出发类短语则两头都稳。
     */
    private val START_PHRASES = listOf("出发", "开始导航", "全程")

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
     * 退出导航后的静默窗长度。
     *
     * 真机时间线（v3.15.57 抓到的 bug）：
     * ```
     * 57.348  navTts: 导航到此为止啦
     * 57.351  navInject: hit id=99910001      ← 高德退出导航时又重发了一次导航通知
     * 57.354  shadow posted  content='导航到此为止啦'
     * 57.374  shadow cancelled for com.autonavi.minimap
     * 60.025  liveShadeText '导航到此为止啦'   ← 胶囊又冒出来，常驻不消
     * ```
     * 也就是说：结束语之后**紧跟着一次通知重发**，那次重发会绕过 endNavForFarewell 的落闸
     * 重新发一条影子。
     *
     * v3.15.60：15_000 → **5_000**。依据就是上面那两行时间戳 —— 重发发生在结束语之后
     * **3 毫秒**（`57.348` → `57.351`），5 秒绰绰有余；而 15 秒只会放大另一个残留边界：
     * 「退出后重开导航、首句正好是转向指令（不含出发语）」时胶囊要等窗过期才出现，
     * 15 秒用户能明显感到「胶囊不出来」，5 秒基本无感。
     */
    private const val NAV_ENDING_WINDOW_MS = 5_000L

    /** 进入退出态的时刻（`SystemClock.elapsedRealtime()`）；0 表示不在退出态 */
    private var navEndingAt = 0L

    /** 上一次打 `skip (ending window)` 的时刻（1s 节流，防退出时高频刷新刷屏） */
    private var lastSkipLogAt = 0L

    /** `skip (ending window)` 日志最短间隔 */
    private const val SKIP_LOG_INTERVAL_MS = 1_000L

    /** 现在是否处于「刚退出导航」的静默窗内 */
    private fun inEndingWindow(): Boolean = try {
        navEndingAt != 0L && SystemClock.elapsedRealtime() - navEndingAt < NAV_ENDING_WINDOW_MS
    } catch (_: Throwable) {
        false
    }

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

    // ---------------------------------------------------------------- 展开卡载荷（v3.15.61）
    //
    // 三进程隔离：本对象活在**高德进程**，展开卡 [ArkNavPanelActivity] 活在**模块自身进程**，
    // SystemUI 侧的 [ArkLiveExpandHook] 只负责搬运。三者之间没有共享内存 / 文件 / Provider，
    // 唯一现成的跨进程通道就是**影子通知的 extras**（SystemUI 本来就读得到这条通知）。
    //
    // 所以下面这些值全部写进 [buildShadow] 的**顶层** key（`ArkConstants.NAV_KEY_*`），
    // 由 SystemUI 原样搬进 Intent，最终由面板读取。
    // 生命周期 = 影子通知的生命周期：退出导航 → 影子被撤 → 载荷自然消失，不需要清理逻辑。
    //
    // 数据来源只有一个 —— **语音播报**（`AudioPlayerTask.groupData` 整段拼起来）：
    // ```
    // 准备出发,,全程一百三十三公里,大约需要两小时三分,预计中午十二点十九分到达,共有一个服务区
    // ```
    // 导航通知本身永远是占位值（`导航中` / `正在导航`），拿不到任何行程数字。

    /** 本趟出发时刻（`elapsedRealtime`），进度条用；0 表示本趟还没出发 */
    private var navStartedAt = 0L

    /** 本趟出发的**墙钟**时刻（`currentTimeMillis`），只用于算「预计到达」兜底 */
    private var startWallMs = 0L

    /** 每趟导航只 dump 一次原通知 extras，用于定位高德把目的地名放在哪个字段 */
    private var extrasDumped = false

    /** 全程距离数值：`"8"` / `"133"` / `"800"`（单位另存，面板负责加粗数值 / 弱化单位） */
    private var totalDistValue = ""

    /** 全程距离单位：`"公里"` / `"米"` */
    private var totalDistUnit = "公里"

    /** 总耗时数值：`"27"` / `"2"` */
    private var totalTimeValue = ""

    /** 总耗时单位：`"分钟"` / `"小时"` */
    private var totalTimeUnit = "分钟"

    /** 总耗时毫秒（进度条分母）；0 表示没解析出来 → 面板只画轨不画球 */
    private var totalMs = 0L

    /** 预计到达时刻 `HH:mm`；空串表示算不出来 */
    private var etaText = ""

    /** 目的地名；空串表示没解析到（面板回落显示「高德地图」） */
    private var destText = ""

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
        // v3.15.58：刚退出导航的静默窗内**一律不发影子**。
        // 这一步必须在取素材、置 navActive 之前 —— 高德退出导航时会重发一次导航通知，
        // 那次重发如果照常走完，就会在 SystemUI 撤掉旧影子之后又发出一条新的（见 bug 时间线）。
        //
        // v3.15.59：日志加 1s 节流 —— 真机上高德退出时可能高频刷新同一条通知，
        // 不节流会把这行刷满 logcat（`log()` 本身没有节流，`probe()` 才有）。
        if (inEndingWindow()) {
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSkipLogAt >= SKIP_LOG_INTERVAL_MS) {
                    lastSkipLogAt = now
                    log("skip (ending window)")
                }
            } catch (_: Throwable) {
            }
            return
        }

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

        // v3.15.62：目的地名既不在播报里（真机日志的出发播报只有
        // `全程…大约需要…预计…到达`，从没出现 POI 名），也不在通知的 title/text
        // （固定「高德地图 / 正在导航」）。所以这里两手准备：
        //  ① 每趟导航 dump 一次原通知全部 extras，供下一轮日志取证定位真实字段；
        //  ② 同时按「像地名」的字段即时兜底，能抓到就当场显示。
        dumpNavExtras(extras, src)
        if (destText.isEmpty()) guessDest(extras, src)?.let { destText = it }

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
        if (!sameSession) {
            lastNaviText = ""
            // v3.15.61：新一趟导航 → 上一趟的行程数字必须清掉，否则展开卡会显示上一趟
            // 的距离 / 耗时 / 到达时刻（数字错得比没有更糟）。
            //
            // `lastHitAt != 0L` 是防误清：自愈路径 [selfHealShadow] 不发通知、不更新
            // lastHitAt，此时若紧跟着来一次真正的 postShadow，不能把刚解析好的数字抹掉。
            if (lastHitAt != 0L) resetNavPayload()
        }
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
        // 新一轮导航开始了 → 解除退出静默窗。
        // 静默窗判断在函数最开头，所以窗口期内走不到这里，两者不会互相打架。
        navEndingAt = 0L
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
     * @param requireLargeIcon 大图标不存在时是否直接放弃。
     *   `postShadow`（首次发布）传 true —— 空图标胶囊比没有更糟；
     *   自愈路径（[selfHealShadow]）传 false —— 宁可先出个没图标的胶囊，
     *   也不能因为图钉（转向箭头）缓存丢了就整趟不显示。
     * @return 素材不全（无 Context / title / 小图标，或 requireLargeIcon 且无大图标）时返回 null
     */
    @Suppress("DEPRECATION")
    private fun buildShadow(content: String, requireLargeIcon: Boolean = true): Notification? {
        val ctx = lastCtx ?: return null
        val title = lastTitle ?: return null
        val largeIcon = lastNaviIcon
        if (requireLargeIcon && largeIcon == null) return null
        val smallIcon = lastSmallIcon ?: return null
        return try {
            val b = Notification.Builder(ctx, NAV_SHADOW_CHANNEL)
                .setSmallIcon(smallIcon)
                .setContentTitle(title)
                .setContentText(lastBody ?: title)
                .setOnlyAlertOnce(true)
                .setDefaults(0)
                .setShowWhen(false)
                .setAutoCancel(false)
                // 导航本身是常驻通知，影子同样常驻；结束时由 SystemUI 侧
                // onNotificationRemoved 同步撤销（ArkLiveShadowHook）
                .setOngoing(true)
            if (largeIcon != null) b.setLargeIcon(largeIcon)
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
            if (largeIcon != null) {
                capsule.putParcelable("notification.live.capsuleIcon", largeIcon)
            }
            capsule.putInt("notification.live.capsuleBgColor", CAPSULE_BG)
            capsule.putInt(
                "notification.live.capsuleContentColor", ArkConstants.readableTextOn(CAPSULE_BG)
            )
            se.putBundle("notification.live.capsule", capsule)

            // ---- v3.15.61：展开卡载荷（**顶层 key**，不再套 Bundle）
            //
            // 为什么不复用 capsule 子 Bundle：SystemUI 侧读一条通知的 extras 只有一层
            // （`sbn.notification.extras`），再取子 Bundle 只是为了拿胶囊正文；
            // 展开卡载荷字段多、类型杂（String / Long / Int），平铺在这里最好搬。
            //
            // 面板侧 [ArkNavPanelActivity] 只从 Intent 读，不碰通知，所以这些 key
            // 最终会被 SystemUI 原样 putExtra 进去（见 ArkLiveExpandHook.launchNavPanel）。
            val instrPair = splitInstruction(content)
            se.putString(ArkConstants.NAV_KEY_INSTR, instrPair.first)
            se.putString(ArkConstants.NAV_KEY_SUB, instrPair.second)
            se.putString(ArkConstants.NAV_KEY_DEST, destText)
            se.putString(ArkConstants.NAV_KEY_DIST, totalDistValue)
            se.putString(ArkConstants.NAV_KEY_DIST_UNIT, totalDistUnit)
            se.putString(ArkConstants.NAV_KEY_TIME, totalTimeValue)
            se.putString(ArkConstants.NAV_KEY_TIME_UNIT, totalTimeUnit)
            se.putString(ArkConstants.NAV_KEY_ETA, etaText)
            se.putLong(ArkConstants.NAV_KEY_STARTED, navStartedAt)
            se.putLong(ArkConstants.NAV_KEY_TOTAL_MS, totalMs)
            se.putInt(ArkConstants.NAV_KEY_TURN, turnCodeOf(instrPair.first))
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
     * 取候选串的顺序：
     *  1. `groupData` 是这一轮播报的分句数组 → 取**最后一个非空元素**（它才是此刻要播的）；
     *  2. 第一个候选没过筛（纯数字 / 噪音 / 空）→ 退回 `data` 再试一次
     *     （真机上两者不是同一句：`data` 是单句、`groupData` 是整段队列）；
     *  3. 中途命中**结束语**（[END_PHRASES]）→ 立刻把 `navActive` 关掉再返回 null ——
     *     导航结束了，后面的播报不许再复活胶囊；
     *  4. 都不过 → 返回 null，胶囊保持上一句有效指令。
     *
     * 文本选取在 [pickCapsuleText]（纯函数），结束语的副作用只在这里做 ——
     * 这样纯函数保持可推演，副作用点只有一个、看得见。
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

        // v3.15.61：展开卡的行程数字（全程距离 / 总耗时 / 预计到达）只在播报里有，
        // 而且散落在**中间**几段（`准备出发,,全程八公里,大约需要二十七分钟,…`），
        // 只看最后一句永远抓不到 —— 所以先整段解析一遍，再挑胶囊文案。
        captureTotals(group, data)

        for (candidate in listOfNotNull(lastSentenceOf(group), data)) {
            // v3.15.59：**出发类短语**才解静默窗（不再用「任意有效指令」，见 isDeparture 注释）
            if (isDeparture(candidate) && inEndingWindow()) {
                navEndingAt = 0L
                log("ending window cleared (departure)")
            }
            if (isFarewell(candidate)) {
                endNavForFarewell()
                return null
            }
            pickCapsuleText(candidate)?.let { return it }
        }
        return null
    }

    /**
     * 是不是「新一轮导航刚开始」的播报。
     *
     * 只认 [START_PHRASES]（`出发` / `开始导航` / `全程`）：这三个词只在导航**起点**播，
     * 结束语里绝不会出现，所以拿它解静默窗是安全的。
     *
     * v3.15.58 曾经用「任意有效指令」解窗，被真机证伪 —— 高德导航中**不重发导航通知**，
     * 解了窗也没有下一次 `postShadow` 来发影子，导致退出后重开导航整趟没有胶囊；
     * 而且退出瞬间若残留一句转向播报，窗会被提前解掉，刚修的「胶囊不销毁」bug 复发。
     */
    private fun isDeparture(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return try {
            val norm = raw.replace('，', ',')
            START_PHRASES.any { norm.contains(it) }
        } catch (_: Throwable) {
            false
        }
    }

    /** 是不是结束语（`这次的旅途结束了呐，下次再会！` 之类） */
    private fun isFarewell(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return try {
            END_PHRASES.any { raw.contains(it) }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 结束语 → 直接判定导航结束。
     *
     * 为什么不能只丢文案：真机 v3.15.56 观测到导航结束后高德仍会播
     * `这次的旅途结束了呐，下次再会！`，此时影子已被 SystemUI 撤掉，
     * 若 `navActive` 还是 true，`updateShadowContent` 会重新 post 一条影子，
     * 屏幕上就冒出一个没有意义的胶囊。所以这里主动落闸。
     */
    private fun endNavForFarewell() {
        try {
            // ① 先开静默窗。**必须在 navActive 判断之前**：哪怕这条结束语来的时候
            //    navActive 已经是 false，也要挡住随后高德重发通知那一下
            //    （真机：结束语 348ms 后就有一次 navInject: hit）。
            navEndingAt = SystemClock.elapsedRealtime()

            val wasActive = navActive
            navActive = false
            lastNaviText = ""
            // v3.15.61：影子马上被 cancel，展开卡的载荷也一起作废（否则下一次导航
            // 复用同一张卡时会带回这一趟的数字）。
            resetNavPayload()

            // ② 主动撤掉自己的影子。不撤的话，SystemUI 撤一次之后
            //    高德重发通知又会让我们发一条新的（v3.15.57 胶囊常驻不消的直接原因）。
            val nm = lastNm
                ?: (appContext()?.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? NotificationManager)
            if (nm == null) {
                log("nav ended (farewell, no nm)")
                return
            }
            nm.cancel(NAV_SHADOW_TAG, NAV_SHADOW_ID)
            log(if (wasActive) "nav ended (farewell, shadow cancelled)" else "nav ended (farewell)")
        } catch (t: Throwable) {
            log("nav ended (farewell, cancel failed: ${t.message})")
        }
    }

    /**
     * 取 `groupData` 数组里「此刻最新」的那一条**原始**记录（不做文本裁剪）。
     *
     * 只负责挑元素，切分与筛选全部交给 [pickCapsuleText] —— v3.15.55 把「取第一个逗号分句」
     * 写在这里，结果真机上 `右转,随后一百五十米右转` 被砍成 `右转`，信息全丢了。
     *
     * `groupData` 偶尔不是合法 JSON（真机见过空数组 / 单字符串），解析失败就当没有，
     * 由调用方退回 `data`。
     */
    private fun lastSentenceOf(group: String?): String? {
        if (group.isNullOrBlank()) return null
        return try {
            val arr = org.json.JSONArray(group)
            var picked: String? = null
            for (i in arr.length() - 1 downTo 0) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) {
                    picked = s
                    break
                }
            }
            picked
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 把一条原始播报串整理成胶囊文案（**纯函数**，无副作用、不碰任何状态）。
     *
     * ## 规则
     *
     * 1. 归一化：全角逗号 `，` → 半角 `,`，`trim()`，去掉首尾逗号与空白；
     * 2. 纯数字（`^\d+$`）→ null（资源 id，真机见过 `2131558436`）；
     * 3. **整串**命中 [NOISE_PHRASES] → null（必须查整串，不能只查结果，见下）；
     * 4. 按 `,` 切段，丢弃空段；
     * 5. 找**第一个**命中 [TURN_KEYWORDS] 的段落下标 `i` → 以它为起点；
     *    一个都没命中 → 取 `segments[0]`；
     * 6. 从 `i` 往后**逐个**判断要不要接上（[shouldFollow]），第一个不满足就停；
     * 7. 结果再查一次噪音词 / 长度 < 2 → null；
     * 8. 超过 [MAX_CAPSULE_TEXT]（28）截断加 `…`。
     *
     * ## 为什么第 6 步不是「连到末尾」（v3.15.57 修的就是这个）
     *
     * v3.15.56 是 `segments.subList(i, size).joinToString`，真机上
     * `您已偏航,七十米后左转,随后六十米右转,新路线预计多三点三公里`
     * 被拼成 `七十米后左转，随后六十米右转，新路线预计多三点三…` ——
     * **绕路提示挤掉了转向指令的额度**。现在只接「有效段」：
     *
     * - 含转向/动作关键词 → 接（`随后六十米右转`）；
     * - 或既含距离词（`米`/`公里`）又含承接词（`后`/`随后`/`然后`/`再`）→ 接（`八十米后`）；
     * - 否则 **break**（`新路线预计多三点三公里` 只有距离没有承接词 → 停）。
     *
     * ## 为什么第 3 步要查整串
     *
     * `以手机朝向为准,路在右边,请向右走` 的最后一段含「向右」，只看最终结果会把它
     * 当成指令显示出来；而整串命中噪音词「以手机朝向为准」才是真相 —— 这是步行模式
     * 让用户校准朝向的提示，不是导航指令。
     *
     * ## 验收样例（真机原始数据，逐条对应）
     *
     * | 输入候选串 | 期望输出 |
     * |---|---|
     * | `右转,随后一百五十米右转` | `右转，随后一百五十米右转` |
     * | `,全程一百三十三公里,大约需要两小时三分,预计中午十二点十九分到达,共有一个服务区,` | `全程一百三十三公里` |
     * | `2131558436` | `null` |
     * | `直行,然后右转进入学院路` | `直行，然后右转进入学院路` |
     * | `前方三百米右转,请靠右行驶` | `前方三百米右转，请靠右行驶` |
     * | `准备出发,` | `null` |
     * | `高德地图持续为您导航` | `null` |
     * | `掉头` | `掉头` |
     * | `您已偏航,七十米后左转,随后六十米右转,新路线预计多三点三公里` | `七十米后左转，随后六十米右转` |
     * | `请移动到室外开阔地带` | `null` |
     * | `请步行到室外开阔地带` | `null` |
     * | `以手机朝向为准,路在右边,请向右走` | `null` |
     * | `沿当前道路,向东出发,八十米后向右前方骑行` | `八十米后向右前方骑行` |
     * | `直达目的地全程二十八公里` | `直达目的地全程二十八公里`（28 字内不截断） |
     *
     * 结束语（`这次的旅途结束了呐，下次再会！`）在这里也返回 null，但**额外的副作用
     * `navActive = false` 由 [extractNaviText] 负责**，纯函数不碰状态。
     *
     * @param raw 原始播报串（可为 null）
     * @return 可写入胶囊的文案；不该进胶囊时返回 null
     */
    private fun pickCapsuleText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            // ① 归一化：全角逗号统一成半角，去掉首尾空白与逗号
            val norm = raw.replace('，', ',').trim().trim(',', ' ', '\u3000')
            if (norm.isEmpty()) return null

            // ② 纯数字 = 资源 id，不是文案
            if (PURE_DIGITS.matches(norm)) return null

            // ③ 整串噪音（见 KDoc「为什么第 3 步要查整串」）
            for (noise in NOISE_PHRASES) {
                if (norm.contains(noise)) return null
            }

            // ④ 切段
            val segments = norm.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (segments.isEmpty()) return null

            // ⑤ 找第一个转向/动作段落；找不到就退回第一段
            var start = 0
            for (idx in segments.indices) {
                if (isTurnSegment(segments[idx])) {
                    start = idx
                    break
                }
            }

            // ⑥ 从起点往后只接「有效段」，第一个不满足就停
            val sb = StringBuilder(segments[start])
            for (j in start + 1 until segments.size) {
                if (!shouldFollow(segments[j])) break
                sb.append('，').append(segments[j])
            }
            val joined = sb.toString()

            // ⑦ 再过一遍结果：噪音 / 过短
            if (joined.length < 2) return null
            for (noise in NOISE_PHRASES) {
                if (joined.contains(noise)) return null
            }

            // ⑧ 收口
            if (joined.length > MAX_CAPSULE_TEXT) {
                joined.substring(0, MAX_CAPSULE_TEXT) + "…"
            } else {
                joined
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 这一段是不是转向/动作指令 */
    private fun isTurnSegment(s: String): Boolean = try {
        TURN_KEYWORDS.any { s.contains(it) }
    } catch (_: Throwable) {
        false
    }

    /**
     * 紧跟在转向段后面的这一段，值不值得接上去。
     *
     * - 含转向/动作关键词 → 值得（`随后六十米右转`）；
     * - 含距离词且含承接词 → 值得（`八十米后`、`随后一百五十米`）；
     * - 其余（`新路线预计多三点三公里`、`大约需要两小时三分`）→ 不值得，调用方 break。
     */
    private fun shouldFollow(s: String): Boolean = try {
        if (isTurnSegment(s)) {
            true
        } else {
            val hasDistance = s.contains("米") || s.contains("公里")
            hasDistance && (
                s.contains("后") || s.contains("随后") ||
                    s.contains("然后") || s.contains("再")
                )
        }
    } catch (_: Throwable) {
        false
    }

    // ---------------------------------------------------------------- 展开卡：行程数字解析
    //
    // 这里只留「和播报格式绑死」的正则；中文数字解析 / 数值格式化 / 时刻解析三个纯函数
    // 抽到了 [ArkNavParse] —— 它不碰 Android 也不碰 Xposed，可以单独编译跑样例表自测
    // （自测脚本见 docs/live-nav-research.md §7.3）。

    /** 中文数字字符类（正则用），含 `十百千万` 与 `两` */
    private const val CN_NUM_CLASS = "[零〇一二三四五六七八九十百千万两]+"

    /** 阿拉伯数字 / 小数（正则用） */
    private const val AR_NUM_CLASS = "\\d+(?:\\.\\d+)?"

    /** `全程一百三十三公里` / `全程八百米` / `全程1.2公里` */
    private val DIST_RE = Regex("全程\\s*($AR_NUM_CLASS|$CN_NUM_CLASS)\\s*(公里|千米|米)")

    /** `大约需要二十七分钟` / `大约需要两小时三分` / `需要2小时` */
    private val TIME_RE =
        Regex("(?:大约)?需要\\s*($AR_NUM_CLASS|$CN_NUM_CLASS)\\s*(?:个)?(小时|分钟|分)")

    /** 小时制后面可能还跟的「三分」，只用于算 [totalMs]，不参与展示 */
    private val TRAIL_MIN_RE = Regex("^\\s*($AR_NUM_CLASS|$CN_NUM_CLASS)\\s*分")

    /** `预计中午十二点十九分到达` → 取「到达」前面的那段时刻描述 */
    private val ETA_RE = Regex("预计(.{1,20}?)到达")

    /** 目的地：`前往静安嘉里中心` / `目的地是…` / `导航到…` */
    private val DEST_PATTERNS = listOf(
        Regex("(?:目的地(?:是|为)|终点是)[：: ]?([^,，。！!？?]{2,16})"),
        Regex("(?:前往|驶向|导航到|去往)([^,，。！!？?]{2,16})")
    )

    /** 到达类关键词（优先于转向词判定，`已到达目的地` 不该被判成直行） */
    private val ARRIVE_KEYWORDS = listOf("到达目的地", "已到达", "抵达目的地", "即将到达")

    /** 左转类（`向左前方` 含 `向左`，`靠左行驶` 含 `靠左`，一条都不用多写） */
    private val LEFT_KEYWORDS = listOf("左转", "靠左", "向左", "左前方", "左侧")

    /** 右转类 */
    private val RIGHT_KEYWORDS = listOf("右转", "靠右", "向右", "右前方", "右侧")

    /**
     * 从一整段播报里抓目的地名（纯函数）。
     *
     * 高德的 TTS 只在少数场景说出目的地（`前往静安嘉里中心` / `目的地是…`），
     * 抓不到就返回 null —— 面板会回落显示「高德地图」，比显示错的地名好。
     */
    private fun parseDest(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        for (re in DEST_PATTERNS) {
            val m = re.find(raw) ?: continue
            val name = m.groupValues[1].trim().trim('的', ' ', '，', ',')
            if (name.length in 2..16) return name
        }
        return null
    }

    /** extras 里像「目的地」的 key 片段（命中即用） */
    private val DEST_KEY_HINTS = listOf(
        "destination", "dest", "poiname", "poi", "endname", "toname", "target", "终点"
    )

    /** 明显不是地名的片段 —— 宁可回落显示「高德地图」，也不能把播报词当目的地 */
    private val DEST_NOISE = listOf(
        "高德", "导航", "正在", "公里", "千米", "分钟", "小时", "服务区", "剩余",
        "预计", "全程", "路线", "播报", "语音", "后台", "提醒", "持续", "为您提供",
        "正在为您", "下车", "车道", "限速", "摄像头", "点击", "查看", "米后", "转"
    )

    // ---------------------------------------------------------------- 取证：通知 extras / 目的地
    //
    // v3.15.62 的目的地问题（展开卡状态行该显示目的地，三个来源全查空）：
    // 播报里没有「前往/目的地/终点」，通知 title/text 是固定占位，`last_route.xml` 只有经纬度。
    //
    // v3.15.63 补齐 v3.15.62 那版探针的三个盲区：
    //  ① 嵌套 `Bundle` 只打了里面的 key、**没打值** —— 目的地若藏在子 Bundle 里，日志等于白打；
    //  ② `guessDest()` 只看顶层 key，子 Bundle 里的 key 看不到；
    //  ③ **启动 Service 的 Intent extras** 完全没取（见 [hookServiceIntents]）——
    //     高德导航是 `Service.startForeground` 起的，启动它的 Intent 里通常带路线参数
    //     （含终点 POI 名），这条通道比通知 extras 更可能有货。
    //
    // 三个日志入口的前缀固定，开发/用户靠 grep 定位，**不要改**：
    //   `NAV EXTRAS DUMP:` / `NAV SVC INTENT DUMP:` / `NAV DEST HIT:`

    /** 嵌套 Bundle 展开深度：顶层 = 0，最多再展开两层子 Bundle */
    private const val EXTRAS_MAX_DEPTH = 2

    /** 整个 flatten 结果的长度上限（防一条通知把 logcat 刷爆） */
    private const val EXTRAS_FLAT_MAX_LEN = 1500

    /** 单个值的长度上限（沿用 v3.15.62 的 80） */
    private const val EXTRAS_VALUE_MAX_LEN = 80

    /** Service 启动 Intent 的 extras 日志长度上限 */
    private const val SVC_INTENT_EXTRAS_MAX_LEN = 800

    /** 顶层 key 之间的分隔符（沿用 v3.15.62 的 `" ; "`） */
    private const val EXTRAS_SEP_TOP = " ; "

    /** 子 Bundle 内部 key 之间的分隔符（`k={子k1=v1, 子k2=v2}`） */
    private const val EXTRAS_SEP_NESTED = ", "

    /** 单个数组最多展开的元素数（多出的只报数量，`…(+N)`） */
    private const val EXTRAS_ARRAY_MAX_ELEMS = 24

    /**
     * 嵌套层的拼装长度闸。
     *
     * 顶层用 [EXTRAS_FLAT_MAX_LEN] + 200（留出被上层截断前的余量），嵌套子 Bundle 用这个值。
     * 目的是**边拼边停**，而不是先把一个巨大的串拼出来再 `take`（超大数组 / 超多 key 时
     * 那一步本身就有开销，而这里跑在高德进程）。
     */
    private const val EXTRAS_NESTED_MAX_LEN = 800

    /**
     * 递归展开一个 Bundle 的全部 key/值，拼成一行可 grep 的字符串。
     *
     * 输出格式（与 v3.15.62 的单层版同源，只是子 Bundle 从「只打 key」变成「连值一起打」）：
     * ```
     * key1=value1 ; key2={sub1=v1, sub2=v2} ; key3=String
     * ```
     *
     * 取值规则沿用 v3.15.62 的 `when`（`CharSequence` / `Array<*>` / `Bundle` /
     * `Number`·`Boolean` / 其余打 `javaClass.simpleName`），新增的只有递归本身。
     *
     * **防自引用**：`depth >= maxDepth` 时子 Bundle 只输出 `Bundle{depth-limit}`，不再递归 ——
     * 异常数据里出现 Bundle 自引用也不会把宿主（高德）进程拖死。
     *
     * @param extras   要展开的 Bundle（顶层传原通知 extras）
     * @param depth    当前层号（顶层 = 0）
     * @param maxDepth 允许展开的最大层号
     * @return 一行字符串；任何异常都返回已拼好的部分，**绝不抛出**
     */
    @Suppress("DEPRECATION") // Bundle.get(String) 是全量 dump 唯一能用的泛型取法
    private fun flattenExtras(extras: Bundle, depth: Int, maxDepth: Int): String =
        flattenExtras(extras, depth, maxDepth, EXTRAS_SEP_TOP, EXTRAS_FLAT_MAX_LEN + 200)

    /**
     * [flattenExtras] 的实现体。
     *
     * @param sep    顶层 `" ; "` / 嵌套 `", "`（两种分隔符便于人读）
     * @param maxLen 拼装长度闸：`sb.length` 超过它就 break，不再继续遍历 key
     */
    @Suppress("DEPRECATION")
    private fun flattenExtras(
        extras: Bundle,
        depth: Int,
        maxDepth: Int,
        sep: String,
        maxLen: Int,
    ): String {
        val sb = StringBuilder()
        try {
            for (k in extras.keySet()) {
                // 长度闸：先判再拼，超大 Bundle 不会先把整串拼出来
                if (sb.length > maxLen) break
                if (sb.isNotEmpty()) sb.append(sep)
                sb.append(k).append('=')
                val v = try {
                    extras.get(k)
                } catch (_: Throwable) {
                    null
                }
                sb.append(flattenValue(v, depth, maxDepth))
            }
        } catch (_: Throwable) {
        }
        return sb.toString()
    }

    /**
     * 单个值 → 日志里的字符串形式（[flattenExtras] 的递归单元）。
     *
     * 只有 `Bundle` / `Array<*>` 会继续递归，其余都是叶子：
     *  - `CharSequence` → 原文（截 [EXTRAS_VALUE_MAX_LEN] = 80 字符）；
     *  - `Number` / `Boolean` → `toString()`；
     *  - 其余 → `javaClass.simpleName`（Bitmap / PendingIntent / Icon 之类看类型就够）。
     *
     * 数组只展开前 [EXTRAS_ARRAY_MAX_ELEMS] = 24 个元素，其余用 `…(+N)` 报数量 ——
     * 高德有些 extras 是几百项的数组，全展开没有信息增量、只会把日志顶爆。
     *
     * 数组内部同样推进一层深度 —— `Object[]` 里塞 Bundle（甚至自引用）也不会无限递归。
     */
    private fun flattenValue(v: Any?, depth: Int, maxDepth: Int): String = try {
        when {
            v == null -> "null"
            v is Bundle -> if (depth >= maxDepth) {
                "Bundle{depth-limit}"
            } else {
                "{" + flattenExtras(v, depth + 1, maxDepth, EXTRAS_SEP_NESTED, EXTRAS_NESTED_MAX_LEN) + "}"
            }
            v is Array<*> -> if (depth >= maxDepth) {
                "Array{${v.size}}"
            } else {
                val shown = v.take(EXTRAS_ARRAY_MAX_ELEMS).joinToString("|") {
                    flattenValue(it, depth + 1, maxDepth).take(EXTRAS_VALUE_MAX_LEN)
                }
                if (v.size > EXTRAS_ARRAY_MAX_ELEMS) {
                    "$shown…(+${v.size - EXTRAS_ARRAY_MAX_ELEMS})"
                } else {
                    shown
                }
            }
            v is CharSequence -> v.toString().take(EXTRAS_VALUE_MAX_LEN)
            v is Number || v is Boolean -> v.toString()
            else -> v.javaClass.simpleName ?: "?"
        }
    } catch (_: Throwable) {
        "?"
    }

    /**
     * 每趟导航打一次原通知 extras 全量取证（v3.15.62 引入，v3.15.63 递归化）。
     *
     * 这一次 dump 是下一轮精确定位目的地的唯一依据，**不要删**；
     * 日志前缀 `NAV EXTRAS DUMP:` 一字不改（用户靠 grep 这行）。
     *
     * 一次性语义由 [extrasDumped] 保证（每趟导航一次），重置点在 [resetNavPayload]。
     */
    @Suppress("DEPRECATION")
    private fun dumpNavExtras(extras: Bundle, src: Notification) {
        if (extrasDumped) return
        extrasDumped = true
        try {
            val flat = flattenExtras(extras, 0, EXTRAS_MAX_DEPTH)
            val body = if (flat.length > EXTRAS_FLAT_MAX_LEN) {
                flat.take(EXTRAS_FLAT_MAX_LEN) + "…(truncated)"
            } else {
                flat
            }
            val ticker = try {
                src.tickerText?.toString()
            } catch (_: Throwable) {
                null
            }
            log("NAV EXTRAS DUMP: $body | ticker='${ticker.orEmpty()}'")
        } catch (_: Throwable) {
        }
    }

    /** 目的地递归查找的深度上限（与 [EXTRAS_MAX_DEPTH] 同口径：顶层 = 0） */
    private const val DEST_MAX_DEPTH = 2

    /**
     * 在 Bundle（含嵌套子 Bundle）里找一个「像地名」的值。
     *
     * 顺序（v3.15.63 规定，不可交换）：
     *  1. 先扫**当前层**全部 key，命中 [DEST_KEY_HINTS] 任一片段 → 取 `CharSequence` /
     *     数组首个 → [sanitizeDest] 通过就返回；
     *  2. 再遍历当前层的嵌套 Bundle 递归（只在 `depth < DEST_MAX_DEPTH` 时下探）；
     *  3. 当前层与子层都没有 → 返回 null，由 [guessDest] 继续走 ticker / 补充文案。
     *
     * 命中时额外打一行 `NAV DEST HIT: <text> (from=<key> depth=<n>)`，事后能确认是哪条 key
     * 命中的；未命中**不打日志**（否则每帧都是一行噪声，导航中通知是秒级刷新的）。
     *
     * @param b     待查 Bundle
     * @param depth 当前层号（顶层 = 0）
     * @return 通过 [sanitizeDest] 的地名；没有返回 null
     */
    @Suppress("DEPRECATION") // 按 key 取任意类型值的唯一泛型取法
    private fun findDestInBundle(b: Bundle, depth: Int): String? {
        // ① 当前层：命中「像地名」的 key 片段就直接取
        try {
            for (k in b.keySet()) {
                val lk = try {
                    k.lowercase(Locale.ROOT)
                } catch (_: Throwable) {
                    k
                }
                if (!DEST_KEY_HINTS.any { lk.contains(it) }) continue
                val v = try {
                    b.get(k)
                } catch (_: Throwable) {
                    null
                }
                val s = when (v) {
                    is CharSequence -> v.toString()
                    is Array<*> -> v.firstOrNull()?.toString()
                    else -> null
                }
                sanitizeDest(s.orEmpty())?.let { hit ->
                    log("NAV DEST HIT: $hit (from=$k depth=$depth)")
                    return hit
                }
            }
        } catch (_: Throwable) {
        }

        // ② 子 Bundle（深度上限内才下探，防自引用）
        if (depth < DEST_MAX_DEPTH) {
            try {
                for (k in b.keySet()) {
                    val child = try {
                        b.get(k) as? Bundle
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    findDestInBundle(child, depth + 1)?.let { return it }
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** 从原通知里找一个「像地名」的值；找不到返回 null（面板回落「高德地图」）。 */
    @Suppress("DEPRECATION") // 同上：按 key 取任意类型的值
    private fun guessDest(extras: Bundle, src: Notification): String? {
        // ① 明确的「目的地类」key（v3.15.63：递归进子 Bundle）
        findDestInBundle(extras, 0)?.let { return it }
        // ② tickerText
        try {
            sanitizeDest(src.tickerText?.toString().orEmpty())?.let { return it }
        } catch (_: Throwable) {
        }
        // ③ 常见的「补充文案」字段（取值语义与 v3.15.62 完全一致，只是每次都兜住异常：
        //    这几个 key 若带自定义 Parcelable，get 会抛 BadParcelableException，一抛就会把
        //    整帧 postShadow 打断 —— 本帧胶囊不发，用户看到的是「胶囊卡住不动」）
        for (k in listOf(
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
            Notification.EXTRA_TEXT_LINES,
        )) {
            val v = try {
                extras.get(k)
            } catch (_: Throwable) {
                null
            }
            val s = when (v) {
                is CharSequence -> v.toString()
                is Array<*> -> v.firstOrNull()?.toString()
                else -> null
            }
            sanitizeDest(s.orEmpty())?.let { return it }
        }
        return null
    }

    private fun sanitizeDest(raw: String): String? {
        val s = raw.trim().trim('，', ',', '。', '!', '！', ' ')
        if (s.length !in 2..16) return null
        if (!s.any { it in '\u4e00'..'\u9fff' }) return null
        if (DEST_NOISE.any { s.contains(it) }) return null
        return s
    }

    /**
     * 把整段播报里的「全程距离 / 总耗时 / 预计到达」解析出来并回写状态。
     *
     * 三项各自独立：解析失败**不动旧值**（宁可显示上一趟的数字，也不要显示 0）。
     * `totalMs` 一律按毫秒落盘，`两小时三分` 会额外吃掉后面那个「三分」，
     * 否则 2h3m 会被当成 2h，进度球会跑偏 0.8%。
     *
     * @param raw 一整段播报（`groupData` 全部元素用逗号拼起来）
     */
    private fun parseTotals(raw: String) {
        if (raw.isBlank()) return

        DIST_RE.find(raw)?.let { m ->
            val v = ArkNavParse.parseCnNumber(m.groupValues[1])
            if (v != null && v > 0.0) {
                totalDistValue = ArkNavParse.fmtNum(v)
                totalDistUnit = if (m.groupValues[2] == "千米") "公里" else m.groupValues[2]
            }
        }

        TIME_RE.find(raw)?.let { m ->
            val v = ArkNavParse.parseCnNumber(m.groupValues[1])
            val unit = m.groupValues[2]
            if (v != null && v > 0.0) {
                if (unit == "小时") {
                    totalTimeValue = ArkNavParse.fmtNum(v)
                    totalTimeUnit = "小时"
                    // `两小时三分`：把尾巴上的分钟也吃进 totalMs（展示仍是「2 小时」）
                    var extraMin = 0.0
                    val rest = raw.substring(m.range.last + 1)
                    TRAIL_MIN_RE.find(rest)?.let { t ->
                        extraMin = ArkNavParse.parseCnNumber(t.groupValues[1]) ?: 0.0
                    }
                    totalMs = ((v * 60.0 + extraMin) * 60_000.0).toLong()
                } else {
                    totalTimeValue = ArkNavParse.fmtNum(v)
                    totalTimeUnit = "分钟"
                    totalMs = (v * 60_000.0).toLong()
                }
            }
        }

        ETA_RE.find(raw)?.let { m ->
            ArkNavParse.parseClock(m.groupValues[1])?.let { etaText = it }
        }

        // v3.15.62：高德**不是每趟都播**「预计X点X分到达」（真机日志里
        // `全程九公里,大约需要三十三分钟,` 就整句没有 ETA）。播报里拿不到时，
        // 用「出发墙钟 + 总耗时」自己算一个，别让第三栏一直显示 `--`。
        computeEtaFallback()
    }

    /**
     * 「预计到达」兜底：出发时刻 + 总耗时。
     *
     * 两个值都来自同一帧的出发播报：全程距离 / 总耗时在 `全程…大约需要…` 里，
     * 出发时刻用单调时钟记进度、用墙钟记 ETA。用墙钟而不是 `elapsedRealtime + 开机时间`
     * 是为了避开用户改系统时间后格式化出错。
     */
    private fun computeEtaFallback() {
        if (etaText.isNotEmpty() || totalMs <= 0L || startWallMs <= 0L) return
        try {
            etaText = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(startWallMs + totalMs))
            log("eta fallback (departure + duration) = '$etaText'")
        } catch (_: Throwable) {
        }
    }

    /**
     * 收口：每次拿到播报先跑一遍 [parseTotals]，并在**出发类**播报出现时记下出发时刻。
     *
     * 出发时刻用 `elapsedRealtime`（单调时钟，不受用户改系统时间影响），
     * 面板拿它和 `总耗时` 一起算进度。
     *
     * @param group `AudioPlayerTask.groupData`（JSON 数组字符串）
     * @param data  `AudioPlayerTask.data`（单句字符串）
     */
    private fun captureTotals(group: String?, data: String?) {
        try {
            val full = fullTextOf(group) ?: data ?: return
            // v3.15.62：出发时刻必须**先于** parseTotals 设置 —— ETA 兜底要用
            // 「出发墙钟 + 总耗时」，而总耗时就在这一帧才被解析出来。
            if (navStartedAt == 0L && isDeparture(full)) {
                navStartedAt = SystemClock.elapsedRealtime()
                startWallMs = System.currentTimeMillis()
            }
            parseTotals(full)
            if (destText.isEmpty()) parseDest(full)?.let { destText = it }
        } catch (_: Throwable) {
        }
    }

    /**
     * `groupData` 数组 → 整段文本（逗号拼接）。
     *
     * 与 [lastSentenceOf] 的区别：那个只要「最后一句」（喂胶囊），
     * 这个要**全段**（行程数字散落在中间几段里，只看最后一句永远抓不到 `全程`）。
     */
    private fun fullTextOf(group: String?): String? {
        if (group.isNullOrBlank()) return null
        return try {
            val arr = org.json.JSONArray(group)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim().trim(',', ' ')
                if (s.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(s)
            }
            sb.toString().takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 胶囊文案 → （主指令, 副指令），供展开卡的两行文本用（纯函数）。
     *
     * 胶囊只有一句话，而设计稿是「主指令 + 副指令」两行：
     * `右转，随后一百五十米右转` → 主 `右转` / 副 `随后一百五十米右转`；
     * `70米后左转` → 主 `70米后左转` / 副 空（面板把副行藏起来）。
     *
     * v3.15.61 补充：设计稿的副行其实是**路名**（`进入 建国路`）。播报里若真带
     * `进入…` / `驶入…` 段，优先把它抽出来当副行，主行用剩下的转向段拼；
     * 没有路名段时才退化成「首句 / 后续句」，避免把「新路线预计多三点三公里」
     * 这类尾巴和路名挤在同一行。
     */
    private fun splitInstruction(content: String): Pair<String, String> {
        if (content.isBlank()) return "" to ""
        return try {
            val segs = content.replace('，', ',')
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            when {
                segs.isEmpty() -> "" to ""
                segs.size == 1 -> segs[0] to ""
                else -> {
                    val roadIdx = segs.indexOfFirst {
                        it.startsWith("进入") || it.startsWith("驶入")
                    }
                    if (roadIdx >= 0) {
                        val road = segs[roadIdx]
                        val verb = if (road.startsWith("进入")) "进入" else "驶入"
                        val sub = "$verb " + road.removePrefix(verb).trim()
                        val main = segs.filterIndexed { i, _ -> i != roadIdx }
                            .joinToString("，")
                        (main.ifBlank { road }) to sub
                    } else {
                        segs[0] to segs.drop(1).joinToString("，")
                    }
                }
            }
        } catch (_: Throwable) {
            content.trim() to ""
        }
    }

    /**
     * 主指令 → 转向码（纯函数，对应 `ArkConstants.NAV_TURN_*`）。
     *
     * 判定顺序有意义：`到达` 优先（`已到达目的地` 里可能含其它词），
     * `掉头` 次之（`向左掉头` 同时含 `向左`，但语义是掉头），再左 / 右，最后直行。
     */
    private fun turnCodeOf(text: String?): Int {
        if (text.isNullOrBlank()) return ArkConstants.NAV_TURN_STRAIGHT
        return try {
            when {
                ARRIVE_KEYWORDS.any { text.contains(it) } -> ArkConstants.NAV_TURN_ARRIVE
                text.contains("掉头") -> ArkConstants.NAV_TURN_UTURN
                LEFT_KEYWORDS.any { text.contains(it) } -> ArkConstants.NAV_TURN_LEFT
                RIGHT_KEYWORDS.any { text.contains(it) } -> ArkConstants.NAV_TURN_RIGHT
                else -> ArkConstants.NAV_TURN_STRAIGHT
            }
        } catch (_: Throwable) {
            ArkConstants.NAV_TURN_STRAIGHT
        }
    }

    /**
     * 清空展开卡载荷（一趟导航结束 / 新一轮导航开始）。
     *
     * 为什么必须清：[buildShadow] 是唯一出口，载荷是「贴在影子上的快照」。
     * 如果上一趟的 `133 公里` 留到下一趟，用户会看到一张数字完全错的卡。
     */
    private fun resetNavPayload() {
        navStartedAt = 0L
        startWallMs = 0L
        totalDistValue = ""
        totalDistUnit = "公里"
        totalTimeValue = ""
        totalTimeUnit = "分钟"
        totalMs = 0L
        etaText = ""
        destText = ""
        extrasDumped = false
    }

    /**
     * 用新的播报文案覆盖胶囊；**没在导航时则自愈补发影子**。
     *
     * 三道闸（更新路径）：
     *  1. `navActive` —— 没在导航走自愈分支（见下）；
     *  2. `content != lastNaviText` —— 同一句反复播（高德会重播）不重复刷通知；
     *  3. `navNotificationAlive()` —— 源导航通知已经没了说明导航结束，
     *     此时再来一条播报**绝不能把胶囊复活**（这是 v3.15.55 最容易出错的地方）。
     *
     * ## v3.15.59 的自愈分支（为什么必须有）
     *
     * 真机实测：**高德在导航过程中不重发导航通知**（整个 7 秒导航只有冷启动时那两次
     * `navInject: hit`，相隔 74ms）。所以「退出导航 → 5 秒内重开」时：
     *
     * - 静默窗把退出后那次重发挡掉了 → `navActive` 一直是 false；
     * - 之后再没有任何通知会来触发 `postShadow`；
     * - 结果整趟行程没有胶囊。
     *
     * 因此这里在 `navActive == false` 时**自己把影子补发出来**：素材沿用上一次缓存的
     * （标题 / 图标 / Context），缺大图标也照发（`requireLargeIcon = false`）。
     */
    private fun updateShadowContent(content: String) {
        try {
            // 刚退出导航：这条播报可能是退出时残留的尾巴，不许刷胶囊
            if (inEndingWindow()) return
            if (!navActive) {
                selfHealShadow(content)
                return
            }
            if (content == lastNaviText) return
            val nm = lastNm
                ?: (appContext()?.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? NotificationManager)
                ?: return
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
     * 自愈：没有导航通知触发 `postShadow` 的情况下，自己把影子发出来。
     *
     * 触发场景（真机 v3.15.58 的坑）：退出导航后 5 秒内重开 → 静默窗挡掉了退出时那次
     * 通知重发 → `navActive` 一直 false → 高德导航中**不再发任何通知** → 胶囊整趟不出现。
     *
     * 素材全部来自缓存（上一次导航的标题 / 图标 / Context），所以：
     *  - 标题缺失 → 用应用名（`applicationInfo.loadLabel`）兜底；
     *  - 小图标缺失 → 用本包 `applicationInfo.icon` 兜底（必须是本包资源，
     *    `Icon.createWithResource` 只认本包，正好我们是高德进程）；
     *  - **大图标缺失照发**（`requireLargeIcon = false`）—— 少一个转向箭头，
     *    也好过整趟没有胶囊。
     *
     * 成功后把状态推回「导航中」，后续播报继续走正常更新路径。
     */
    private fun selfHealShadow(content: String) {
        try {
            val ctx = lastCtx ?: appContext()
            if (ctx == null) {
                log("self-heal skipped (no context)")
                return
            }
            lastCtx = ctx

            val nm = lastNm
                ?: (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            if (nm == null) {
                log("self-heal skipped (no nm)")
                return
            }
            lastNm = nm

            // 自愈的前提：高德此刻**真的挂着导航通知**。
            // 没有这道闸，非导航场景的语音（比如巡航时的测速提醒）也会让我们凭空发胶囊。
            // 这道闸不影响验收第 3 条：退出后重开导航时高德会重新 post 导航通知
            // （只是那次命中被静默窗跳过），通知本身在系统里是存在的。
            if (!navNotificationAlive(nm)) {
                log("self-heal skipped (no nav notification)")
                return
            }

            if (lastTitle.isNullOrBlank()) lastTitle = appLabel(ctx) ?: "高德地图"
            if (lastSmallIcon == null) lastSmallIcon = appSmallIcon(ctx)
            if (lastSmallIcon == null) {
                log("self-heal skipped (no small icon)")
                return
            }

            ensureChannel(nm)
            val shadow = buildShadow(content, requireLargeIcon = false)
            if (shadow == null) {
                log("self-heal skipped (build failed)")
                return
            }
            if (!publishShadow(nm, shadow)) return

            navActive = true
            lastNaviText = content
            navEndingAt = 0L
            log("self-heal shadow posted content='$content' hasIcon=${lastNaviIcon != null}")
        } catch (t: Throwable) {
            log("self-heal failed: ${t.message}")
        }
    }

    /** 应用名（自愈时没有缓存标题，用它当影子通知的 `contentTitle`） */
    private fun appLabel(ctx: Context): String? = try {
        ctx.applicationInfo.loadLabel(ctx.packageManager)?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    /** 应用自身图标（自愈时没有缓存 `smallIcon` 的兜底；必须是本包资源） */
    private fun appSmallIcon(ctx: Context): Icon? = try {
        val info = ctx.applicationInfo
        if (info.icon != 0) Icon.createWithResource(info.packageName, info.icon) else null
    } catch (_: Throwable) {
        null
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
        resetNavPayload()
    }

    private fun log(msg: String) {
        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG: $msg")
    }

    // ---------------------------------------------------------------- 取证：Service 启动 Intent
    //
    // v3.15.63 新增，本轮最关键的增量。
    //
    // 事实链：高德用 `Service.startForeground` 发导航通知（id 99910001/2/3，title/text 固定
    // 占位），导航通知里没有目的地；TTS 播报里也没有。剩下唯一「本该有地名」的地方就是
    // **启动那个 Service 的 Intent** —— 路线参数（起点/终点 POI）通常随 Intent extras 传进
    // 导航 Service。所以这里 hook `ActivityThread` 的两个 Service 派发入口把它取出来。
    //
    // 为什么要用 `Class.forName` 而不是 import `android.app.ActivityThread`：它是隐藏类，
    // 编译期不可见（会直接编译失败），只能在运行期取。
    //
    // 三条硬约束（违反任何一条都可能被用户感知为「高德崩了」）：
    //  1. 每个回调整体包 `try/catch (_: Throwable)`，**绝不把异常抛进宿主进程**；
    //  2. 必须有**总 dump 次数上限**（[SVC_INTENT_MAX_DUMP]），高德后台 Service 启动很频繁；
    //  3. 过滤（cap / action / extras / 去重）全部排在 [flattenExtras] **之前**，
    //     不能对每个 SDK Service 启动都做字符串拼接。
    //
    // v3.15.63 修订（QA R1/R2，这两条会直接让本轮取证白跑）：
    //
    //  R1 **双额度**：老版「action/extras 都为空才丢」，但高德进程里大量 Service 是
    //     「有 action、无 extras」（push / 统计 / SDK 各一路），每来一个就吃掉 1/8 主额度并打一行
    //     `extras: <none>` —— 8 条额度很可能在导航 Service 起来**之前**就被 `<none>` 占满。
    //     现在 8 次主额度**只给带 extras 的 Intent**；无 extras 的另给一条**弱额度**
    //     （[SVC_INTENT_NOEXTRA_BUDGET] = 2），且只放 action 像导航的（[NAV_SVC_ACTION_RE]），
    //     用完静默不打 `cap reached`。「这个 Service 起过、但参数不在 Intent 里」本身是有价值的结论，
    //     所以留 2 条弱线索，但绝不挤占主额度。
    //     两套额度各用**独立的去重集**（[svcIntentSeen] / [svcIntentWeakSeen]）：弱额度的一行
    //     `<none>` 不能把「同一个 svc:action 后来带着 extras 再来一次」这条有价值的日志去重掉。
    //
    //  R2 **服务名映射**：`ServiceArgsData` / `BindServiceData` 按 AOSP 只有 `token` / `intent` /
    //     `flags` / `startId`，**不含 Service 实例**，所以 `svc=` 恒为 `?`，去重键退化成 `?:action`，
    //     日志也丢掉服务名这个最关键的定位信息。现在 hook `handleCreateService`（before），把
    //     `token(IBinder) → 服务全类名` 记进 [svcNameCache]（容量 [SVC_NAME_CACHE_MAX]，LRU 式淘汰），
    //     Service 派发时用 `data` 里的 token 反查。

    /** 已 dump 过的 `serviceClassName:action` 组合（主额度去重） */
    private val svcIntentSeen = HashSet<String>(8)

    /** 已 dump 过的 `serviceClassName:action` 组合（弱额度去重，与主额度隔离） */
    private val svcIntentWeakSeen = HashSet<String>(4)

    /** 已 dump 次数（主额度：带 extras 的 Intent） */
    private var svcIntentDumps = 0

    /** Service Intent dump 主额度上限（只给带 extras 的 Intent） */
    private const val SVC_INTENT_MAX_DUMP = 8

    /** 已 dump 次数（弱额度：无 extras 但 action 像导航） */
    private var svcIntentNoExtraDumps = 0

    /** 弱额度上限 */
    private const val SVC_INTENT_NOEXTRA_BUDGET = 2

    /** action 像导航 Service 的关键词（弱额度只放这些；不区分大小写） */
    private val NAV_SVC_ACTION_RE = Regex("(?i)(nav|navi|route|guide|tbt|drive|simulat)")

    /** 「cap reached」提示是否已打过（只打一次，只针对主额度） */
    private var svcIntentCapLogged = false

    /** 额度 / 去重集的统一锁（Service 派发可能跨线程，计数器用同一把锁才自洽） */
    private val svcIntentLock = Any()

    /** `Service` token（IBinder）→ 服务全类名。由 [hookServiceCreates] 填充，容量有界 */
    private val svcNameCache = LinkedHashMap<IBinder, String>(32)

    /** 映射表容量上限（超出淘汰最早插入的一条） */
    private const val SVC_NAME_CACHE_MAX = 32

    /**
     * 注册 Service 启动 Intent 的取证探针。
     *
     * 挂两个入口（都是单参数，参数是 `ActivityThread$XxxData`）：
     *  - `handleServiceArgs`（`ServiceArgsData`）—— `startService` / `startForegroundService`；
     *  - `handleBindService`（`BindServiceData`）—— `bindService`。
     *
     * 用 `hookAllMethods` 而不是 `findAndHookMethod`：ActivityThread 上这些方法名在部分 ROM
     * 有重载，逐个按签名找容易漏。回调里用 `args.size != 1` 过滤。
     */
    private fun hookServiceIntents() {
        try {
            val atCls = Class.forName("android.app.ActivityThread")
            hookServiceIntentMethod(atCls, "handleServiceArgs")
            hookServiceIntentMethod(atCls, "handleBindService")
        } catch (t: Throwable) {
            // 取不到 ActivityThread（极少数 ROM）就直接放弃，不影响导航胶囊本身
            probeRaw("svc intent: ActivityThread unavailable (${t.javaClass.simpleName})")
        }
    }

    /** 在 `ActivityThread` 的某个 Service 入口上挂 after hook（失败只记日志） */
    private fun hookServiceIntentMethod(atCls: Class<*>, methodName: String) {
        try {
            XposedBridge.hookAllMethods(
                atCls, methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        dumpServiceIntent(param)
                    }
                }
            )
            probeRaw("svc intent: hooked ActivityThread.$methodName")
        } catch (t: Throwable) {
            probeRaw("svc intent: hook $methodName failed (${t.javaClass.simpleName})")
        }
    }

    /**
     * 注册 `handleCreateService` 的 **before** hook：只记录 `token → 服务名`，不改宿主行为。
     *
     * **before hook 里绝不调 `param.setResult`** —— 那会直接跳过原方法，Service 就起不来了。
     * 这里只读字段、写本地映射表，宿主完全无感。
     */
    private fun hookServiceCreates() {
        try {
            val atCls = Class.forName("android.app.ActivityThread")
            XposedBridge.hookAllMethods(
                atCls, "handleCreateService",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        rememberServiceName(param)
                    }
                }
            )
            probeRaw("svc intent: hooked ActivityThread.handleCreateService (token→name)")
        } catch (t: Throwable) {
            probeRaw("svc intent: hook handleCreateService failed (${t.javaClass.simpleName})")
        }
    }

    /**
     * 把一次 Service 创建的 `token → 全类名` 记进 [svcNameCache]。
     *
     * `CreateServiceData` 的结构（AOSP）：`token: IBinder` / `info: LoadedApk$ServiceData` /
     * `compatInfo`。服务名在 `info.name`（String，全类名）。字段名不硬编码查找逻辑：
     * token 用「第一个 `instanceof IBinder` 的字段」，名字则从 `info` 里读 `name`。
     */
    private fun rememberServiceName(param: XC_MethodHook.MethodHookParam) {
        try {
            val args = param.args
            if (args == null || args.size != 1) return
            val data = args[0] ?: return
            val token = firstFieldOfType(data, IBinder::class.java) as? IBinder ?: return
            val name = serviceClassNameOf(data) ?: return
            synchronized(svcNameCache) {
                if (!svcNameCache.containsKey(token) && svcNameCache.size >= SVC_NAME_CACHE_MAX) {
                    // 淘汰最早插入的一条（LinkedHashMap 的迭代顺序 = 插入顺序）
                    svcNameCache.keys.firstOrNull()?.let { svcNameCache.remove(it) }
                }
                svcNameCache[token] = name
            }
        } catch (_: Throwable) {
            // 宿主进程：映射表读不出来就当没有，绝不外抛
        }
    }

    /**
     * 从 `CreateServiceData` 里取服务全类名：先找 `Service` 实例字段，再从 `info` 里读 `name`。
     *
     * @return 全类名（含 `.`）；取不到返回 null
     */
    private fun serviceClassNameOf(data: Any): String? {
        try {
            firstFieldOfType(data, Service::class.java)?.let { svc ->
                svc.javaClass.name?.let { if (it.isNotBlank()) return it }
            }
        } catch (_: Throwable) {
        }
        try {
            var c: Class<*>? = data.javaClass
            var guard = 0
            while (c != null && guard++ < 8) {
                for (f in c.declaredFields) {
                    try {
                        if (Modifier.isStatic(f.modifiers)) continue
                        f.isAccessible = true
                        val v = f.get(data) ?: continue
                        // `info`（LoadedApk$ServiceData）里的 name 字段就是服务全类名
                        readNameField(v)?.let { if (it.contains('.')) return it }
                    } catch (_: Throwable) {
                    }
                }
                c = c.superclass
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /** 读一个对象上 `name: String` 字段的值（`LoadedApk$ServiceData.name` 是 AOSP 固定字段） */
    private fun readNameField(obj: Any): String? {
        try {
            var c: Class<*>? = obj.javaClass
            var guard = 0
            while (c != null && guard++ < 8) {
                for (f in c.declaredFields) {
                    try {
                        if (Modifier.isStatic(f.modifiers)) continue
                        if (f.type != String::class.java || f.name != "name") continue
                        f.isAccessible = true
                        val v = f.get(obj) as? String
                        if (v != null && v.isNotBlank()) return v
                    } catch (_: Throwable) {
                    }
                }
                c = c.superclass
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * 服务名解析（v3.15.63 / R2）：① `Service` 实例字段 → ② `token` 查 [svcNameCache] → ③ `"?"`。
     */
    private fun serviceNameOf(data: Any): String {
        try {
            firstFieldOfType(data, Service::class.java)?.let { svc ->
                svc.javaClass.name?.let { if (it.isNotBlank()) return it }
            }
        } catch (_: Throwable) {
        }
        try {
            val token = firstFieldOfType(data, IBinder::class.java) as? IBinder
            if (token != null) {
                synchronized(svcNameCache) {
                    svcNameCache[token]?.let { return it }
                }
            }
        } catch (_: Throwable) {
        }
        return "?"
    }

    /**
     * 从一次 Service 派发里取出启动 Intent，dump 其 extras。
     *
     * 额度分流（v3.15.63 / R1）：
     *  - `extras != null` → 主额度（[SVC_INTENT_MAX_DUMP] = 8），日志行末 `| extras: <内容>`；
     *  - `extras == null` → 只有 action 命中 [NAV_SVC_ACTION_RE] 才走弱额度
     *    （[SVC_INTENT_NOEXTRA_BUDGET] = 2），行末 `| extras: <none> (noextra n/2)`；
     *    其余（有 action 无 extras 的 push / 统计 / SDK Service）**直接丢，不消耗任何额度、不打日志**。
     *
     * 全部失败路径都静默 —— 宿主是高德，探针绝不能让它崩。
     */
    private fun dumpServiceIntent(param: XC_MethodHook.MethodHookParam) {
        try {
            val args = param.args
            if (args == null || args.size != 1) return
            val data = args[0] ?: return

            // 闸门⓪：两套额度都空了就立刻返回（连反射都不做）
            if (!svcIntentAnyBudgetLeft()) return

            val intent = firstFieldOfType(data, Intent::class.java) as? Intent ?: return
            val action = try {
                intent.action
            } catch (_: Throwable) {
                null
            }
            val extras = try {
                intent.extras
            } catch (_: Throwable) {
                null
            }

            // 闸门②：无 extras 且 action 不像导航 → 直接丢（不消耗任何额度、不打日志、不解析服务名）
            if (extras == null && (action == null || !NAV_SVC_ACTION_RE.containsMatchIn(action))) return

            // 服务名只解析一次（反射 + 查表），日志与去重键共用
            val svcName = serviceNameOf(data)

            // 闸门③：无 extras 的导航类只走弱额度
            if (extras == null) {
                if (!svcIntentClaimWeak("$svcName:$action")) return
                val seq = synchronized(svcIntentLock) { svcIntentNoExtraDumps }
                log(
                    "NAV SVC INTENT DUMP: svc=${svcName.substringAfterLast('.')} " +
                        "action=$action | extras: <none> (noextra $seq/$SVC_INTENT_NOEXTRA_BUDGET)"
                )
                return
            }

            // 闸门④：主额度 + 同一 `服务名:action` 只 dump 一次
            if (!svcIntentClaim("$svcName:$action")) return

            val flat = flattenExtras(extras, 0, EXTRAS_MAX_DEPTH).let {
                if (it.length > SVC_INTENT_EXTRAS_MAX_LEN) {
                    it.take(SVC_INTENT_EXTRAS_MAX_LEN) + "…"
                } else {
                    it
                }
            }
            log(
                "NAV SVC INTENT DUMP: svc=${svcName.substringAfterLast('.')} " +
                    "action=${action ?: "?"} | extras: $flat"
            )
        } catch (_: Throwable) {
            // 宿主进程：异常必须在这里消失
        }
    }

    /** 两套额度是否都还有剩（都没有就完全跳过后续反射） */
    private fun svcIntentAnyBudgetLeft(): Boolean = synchronized(svcIntentLock) {
        svcIntentDumps < SVC_INTENT_MAX_DUMP || svcIntentNoExtraDumps < SVC_INTENT_NOEXTRA_BUDGET
    }

    /** 主额度还有吗；用尽时打一次 `cap reached` 提示后返回 false */
    private fun svcIntentHasBudget(): Boolean = synchronized(svcIntentLock) {
        if (svcIntentDumps < SVC_INTENT_MAX_DUMP) return@synchronized true
        if (!svcIntentCapLogged) {
            svcIntentCapLogged = true
            log("NAV SVC INTENT DUMP: cap reached ($SVC_INTENT_MAX_DUMP)")
        }
        false
    }

    /** 认领一个 `serviceClassName:action` 组合（主额度）；已认领过或主额度用尽返回 false */
    private fun svcIntentClaim(key: String): Boolean {
        if (!svcIntentHasBudget()) return false
        return synchronized(svcIntentLock) {
            if (svcIntentDumps >= SVC_INTENT_MAX_DUMP) return@synchronized false
            if (!svcIntentSeen.add(key)) return@synchronized false
            svcIntentDumps++
            true
        }
    }

    /**
     * 认领一个 `serviceClassName:action` 组合（弱额度）：已认领过或弱额度用尽返回 false。
     *
     * 弱额度用尽**静默**（不打 `cap reached`）—— 那是给主额度用的提示，弱线索没拿到不值得刷一行日志。
     */
    private fun svcIntentClaimWeak(key: String): Boolean = synchronized(svcIntentLock) {
        if (svcIntentNoExtraDumps >= SVC_INTENT_NOEXTRA_BUDGET) return@synchronized false
        if (!svcIntentWeakSeen.add(key)) return@synchronized false
        svcIntentNoExtraDumps++
        true
    }

    /**
     * 反射找一个对象里**第一个**指定类型的实例字段值（沿类继承链向上找，跳过 static）。
     *
     * 为什么不硬编码字段名：`ActivityThread$ServiceArgsData` / `$BindServiceData` 的字段名
     * 在 AOSP 与各 ROM 之间不一致（`args` / `intent` / `info`），按类型找才稳。
     *
     * @return 命中的字段值；没有返回 null（任何反射异常都吞掉）
     */
    private fun firstFieldOfType(obj: Any?, type: Class<*>): Any? {
        if (obj == null) return null
        try {
            var c: Class<*>? = obj.javaClass
            var guard = 0
            while (c != null && guard++ < 8) {
                for (f in c.declaredFields) {
                    try {
                        if (Modifier.isStatic(f.modifiers)) continue
                        f.isAccessible = true
                        val v = f.get(obj)
                        if (v != null && type.isInstance(v)) return v
                    } catch (_: Throwable) {
                    }
                }
                c = c.superclass
            }
        } catch (_: Throwable) {
        }
        return null
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

        // v3.15.63：取证探针 —— 启动导航 Service 的 Intent extras（路线参数里通常带终点
        // POI 名）。与 startForeground hook 装在同一处：两者都指向「高德怎么起导航」这条链路。
        // 内部已全程 try/catch，失败不影响上面的功能 hook。
        hookServiceIntents()
        // 同批：`handleCreateService` 只记录 token→服务名（before hook，绝不 setResult），
        // 让 dump 日志里的 `svc=` 不再恒为 `?`。
        hookServiceCreates()

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

    /**
     * 一次性状态日志（安装结果 / 找不到 / hook 失败）。
     *
     * 自己再包一层 try/catch：它经常被**当作 catch 分支里的日志**调用，若 `XposedBridge.log`
     * 本身出了状况，异常会从 catch 里继续往上抛，把 `install()` 整个打断 ——
     * 结果就是「一条探针日志把导航功能带走」。
     */
    private fun probeRaw(msg: String) {
        try {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] probe $msg")
        } catch (_: Throwable) {
        }
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
                        //
                        //    v3.15.59：**不能**再用 `if (navActive)` 拦住这里。
                        //    高德导航中不重发导航通知（真机实测），所以「退出后重开导航」时
                        //    没有任何通知会来置 navActive —— 只能靠这里自愈（见
                        //    updateShadowContent 的 navActive=false 分支）。加回这个门槛
                        //    就等于关掉自愈，胶囊整趟不出现。
                        if (feedCapsule) {
                            try {
                                extractNaviText(task)?.let { updateShadowContent(it) }
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

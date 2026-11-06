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
import android.view.View
import android.widget.TextView
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

    /**
     * v3.15.79：**实证有害，暂时关闭。**
     *
     * 它只在 `postShadow` 里调用一次，之后播报更新走 `updateShadowContent` 不再改标题，
     * 于是把高德原生那条的 `EXTRA_TITLE` 永久钉在起始帧的占位文案
     * （「高德地图 · 正在导航」）上 —— 用户屏幕上那行字就冻在这。
     *
     * 现在改由 SystemUI 侧统一写标题（`ArkMiniBarStyleHook.writeNavTitle`，
     * 每帧 preDraw + 自驱轮询兜底），数据源是影子的 `NAV_KEY_INSTR`。
     * 函数本体保留（[rewriteSourceTitle]），以后要用把这里改 true 即可。
     */
    private const val REWRITE_SRC_TITLE = false

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
        "导航到此为止", "下次再见", "导航结束", "已到达目的地", "您已到达",
        // v3.15.66（真机 14:16:01）：`旅程到这里就结束了…` 这一版措辞**一个旧词都没命中**，
        // 于是告别语被当成普通正文写进胶囊（截图里那句「旅程到这里就结…」就是它），
        // 退出后胶囊还常驻。补齐高德现用的几种收尾说法。
        "旅程到这里就结束", "本次导航结束", "导航已结束", "行程结束", "感谢使用高德"
    )

    /**
     * 出发类短语。只在导航**起点**播，是「新会话开始」最可靠的证据之一。
     *
     * v3.15.66 起它的作用是**建会话**（[beginSession]），而不是「解一个静默窗」：
     * 出发播报一辈子只有一次，任何形式的窗口都不许把它挡掉
     * （真机 14:13:35 的骑行导航就是因为旧 5 秒窗把它的出发播报吞了，整趟没有分母）。
     *
     * 为什么不复用 [pickCapsuleText] 的「任意有效指令」：真机实测高德在导航过程中
     * **不重发导航通知**（只有冷启动时重复提交一次，两次命中相隔 74ms），
     * 而且「任意有效指令」会让退出瞬间残留的转向播报把刚结束的会话顶成新一轮。
     * 只认 `出发 / 开始导航 / 全程` 这三个词，两头都稳。
     */
    private val START_PHRASES = listOf("出发", "开始导航", "全程")

    /**
     * 偏航类短语（v3.15.67）：命中即把 [navYawed] 置 true。
     *
     * 真机样例（整段 `groupData` 拼起来）：
     * ```
     * 您已偏航,七十米后左转,随后六十米右转,新路线预计多三点三公里
     * ```
     * 另一版口径会播 `已重新规划路线`，所以除了「偏航」还认「重新规划」。
     *
     * ⚠ 这些词**不进** [NOISE_PHRASES]：[pickCapsuleText] 的正则样例表已验收，
     * 偏航句本身就是有效指令（`七十米后左转`），必须照常投进胶囊 ——
     * 这里只多做一个「转红」的副作用。
     */
    private val YAW_ON_PHRASES = listOf("已偏航", "重新规划")

    /**
     * 回到主路类短语（v3.15.67）：命中即把 [navYawed] 清回 false。
     *
     * 高德重新规划成功后播 `已回到主路` / `回到主路,继续导航`；两种说法都认。
     */
    private val YAW_OFF_PHRASES = listOf("回到主路", "已回到")

    /**
     * 「已回到正常路线」的软提示词（v3.15.70）。
     *
     * 配合 [TURN_KEYWORDS] 一起用：只有**真正的转向指令**、或这几句明确的回归播报，
     * 才算导航已恢复正常并允许清掉偏航红。
     *
     * 为什么不再用 `pickCapsuleText(raw) != null`（v3.15.69 的写法）：
     * 那个函数表达的是「这句话能不能塞进胶囊」，不是「这句话是不是道路指引」——
     * `前方三百米有违章拍照` / `当前路段限速八十` / `请注意保持车距` / `今日限行尾号三和八`
     * 全都能过它，长绕路途中随便一句提示就把红抹掉了（QA 实测 7/7 误清）。
     */
    private val YAW_CLEAR_HINTS = arrayOf(
        "继续行驶", "沿当前道路", "沿主路", "沿原路线", "回到主路", "已回到", "路线已更新", "路线更新"
    )

    // ---------------------------------------------------------------- 播报 → 胶囊 的实时状态
    //
    // 重要：以下字段全部活在**高德进程**里（hook 装在 App 侧）。SystemUI 侧那一份
    // ArkNavLiveInjector 是另一个进程里的另一份对象，两边静态字段互不影响。
    // 所以「导航什么时候算结束」必须由本进程自己判断，见 navNotificationAlive()。

    /**
     * 导航进行中标志：当前会话处于 ACTIVE 状态。
     *
     * v3.15.66 起它只是 [navState] 的一个派生别名（两处必须一起维护），
     * 保留是因为 `NAV TEXT TICK` 等既有路径都读它。
     */
    private var navActive = false

    // ---------------------------------------------------------------- 会话状态机（v3.15.66）
    //
    // 为什么要有它（真机 14:13–14:16 的错误链）：
    //
    // ```
    // 14:13:09  hit id=99910001            → 会话 1 开始（驾车）
    // 14:13:28  nav ended (farewell)        → 会话 1 结束
    // 14:13:28  skip (ending window)        ← 5 秒静默窗开始
    // 14:13:35  hit id=99910003（骑行）     ← 被静默窗吞掉（窗还没过）★R2
    // 14:15:07  hit id=99910001            → 会话 3 开始，但三栏全 --（没重播「全程」）★R1
    // 14:16:01  content='旅程到这里就结…'   ← 告别语被当正文投出去 ★R5
    // ```
    //
    // 旧实现用「距上次命中是否超过 90 秒」猜会话边界，两头都错：
    // 90 秒内重开 → 判成同一趟，沿用上一趟残留；超过 90 秒 → 一律 `resetNavPayload()`，
    // 而高德**重开同一路线不会重播「全程X公里」**，于是新会话永远拿不到分母和目的地。
    //
    // 现在改成显式状态机，边界来自**源通知的真实生死**，时间只做辅助判据。
    private var navState = STATE_IDLE

    /**
     * 退出态下「同一次退出」的重投守卫（毫秒）。
     *
     * 真机时间线（v3.15.57）：结束语 `57.348` → 高德重发通知 `57.351`（相隔 **3ms**）。
     * 那次重发必须挡住，但它和「用户 4 秒后重新点开始导航」在通知层面长得**一模一样**
     * （同 id、同文案「正在导航」），唯一的区分依据就是时间。
     *
     * 所以守卫做得**比静默窗短得多**（1.5s）：既挡住 3ms 的那次重投，又不会像旧的 5 秒
     * 静默窗那样把 14:13:35 的骑行导航首帧一起吞掉。
     */
    private const val ENDING_REPOST_GUARD_MS = 1_500L

    // ---------------------------------------------------------------- 跨趟兜底值（v3.15.66）
    //
    // 高德「结束导航 → 重开同一条路线」时**不会重播** `全程X公里, 大约需要X分钟`，
    // 于是新会话拿不到分母 / 距离 / 目的地，三栏全 `--`、状态行退回「高德地图」。
    //
    // 对策：会话结束时把本趟的结论搬到这几个字段里当**兜底**，新会话解析不到时用它们顶上。
    // ⚠ 它们只被**读**，绝不参与本趟解析（否则上一趟的数字会污染这一趟）。

    /** 上一趟的全程耗时（进度条分母的兜底） */
    private var lastTripTotalMs = 0L

    /** 上一趟的总里程（数值 / 单位） */
    private var lastDistValue = ""
    private var lastDistUnit = ""

    /** 上一趟的目的地 */
    private var lastDest = ""

    /** 上一趟的耗时文案（`27` / `分钟`），仅在没有剩余值时兜底 */
    private var lastTimeValue = ""
    private var lastTimeUnit = ""

    // ---- 跨趟兜底的「过期」机制（v3.15.81）
    //
    // 真机 bug：先开驾车（37 分钟 / 9 公里），取消后改步行，高德 app 显示
    // 「9.3 公里 · 2 小时 23 分」，我们的展开卡却还是「37 分钟 / 9 公里」——
    // 因为新一趟在拿到自己的 totals 之前，`beginSession → stashLastTrip` 刚把
    // **上一趟驾车**的值搬进了兜底，而兜底没有时效。
    //
    // 兜底只该服务「同一趟的续报 / 同一路线重开」，不该服务「换了交通方式的新一趟」。
    // 所以给它一个 15 秒窗口 + 明确的清除触发点：宁可三栏 `--`，也不显示上一趟的数。

    /** 兜底有效窗口：新会话开始后这么久还拿不到本趟全程，兜底即失效 */
    private const val FALLBACK_TTL_MS = 15_000L

    /** 兜底失效时刻（elapsedRealtime）；`<= 0` = 当前没有有效兜底 */
    private var fallbackValidUntil = 0L

    /** 交通方式（`drive` / `walk` / `bike` / `bus` / `subway`），空 = 未知。跨趟保留用于比对 */
    private var travelMode = ""

    /** 高德源通知 extras 已全量 dump 过一次（v3.15.81 一次性诊断） */
    private var srcExtrasDumped = false

    /** 上一次真正写进胶囊的播报文案（去重：同一句反复播不重复刷通知） */
    private var lastNaviText = ""

    /**
     * 上一次真正写进影子通知的**整份载荷**指纹（v3.15.65）。
     *
     * 为什么单靠 [lastNaviText] 不够：高德在高速上会连着播同一句「沿当前道路继续行驶」，
     * 文案一个字都没变，但「剩余 27 分钟 → 剩余 19 分钟」已经变了。v3.15.65 的展开卡
     * 是**锚点式**进度 + 实时 ETA，全靠影子重新投递来推动（SystemUI 只有看到影子更新
     * 才会广播给面板），所以「文案没变但数字变了」这一帧**必须**重投。
     */
    private var lastShadowSig = ""

    /** 上一次「导航通知命中」的时刻，用于判断两次命中之间是否中断过（导航结束又重开） */
    private var lastHitAt = 0L

    /** 会话状态：空闲（还没见过导航通知） */
    private const val STATE_IDLE = 0

    /** 会话状态：导航中 */
    private const val STATE_ACTIVE = 1

    /** 会话状态：已结束（farewell / 源通知消失），禁止任何影子投递 */
    private const val STATE_ENDING = 2

    /**
     * 两次命中间隔超过这个值就认为上一轮导航已结束。
     *
     * v3.15.66 起它**降级为辅助判据**：会话边界以状态机（[navState]）为准，
     * 这里只在「既没收到 cancel、又长时间没有通知刷新」的自愈场景兜底
     * （例如高德被强杀但影子没撤干净）。
     */
    private const val SESSION_GAP_MS = 90_000L

    /**
     * 退出态的时间戳（`SystemClock.elapsedRealtime()`）；0 表示从没进过 ENDING。
     *
     * v3.15.66 起它不再是一个「5 秒静默窗」的起点，而只是 [ENDING_REPOST_GUARD_MS]
     * （1.5 秒）的基准。旧的 5 秒静默窗把**新一轮导航的首帧**也吞了（真机 14:13:35
     * 骑行导航的 `hit id=99910003` 直接被 skip），那一趟永远没有分母。
     *
     * 历史证据（v3.15.57，为什么需要这个守卫）：
     * ```
     * 57.348  navTts: 导航到此为止啦
     * 57.351  navInject: hit id=99910001      ← 高德退出导航时又重发了一次导航通知
     * 57.354  shadow posted  content='导航到此为止啦'
     * 57.374  shadow cancelled for com.autonavi.minimap
     * 60.025  liveShadeText '导航到此为止啦'   ← 胶囊又冒出来，常驻不消
     * ```
     * 结束语之后 **3 毫秒**就有一次重发，1.5 秒的守卫绰绰有余。
     */
    private var navEndingAt = 0L

    /** 上一次打 `skip (...)` 的时刻（1s 节流，防退出时高频刷新刷屏） */
    private var lastSkipLogAt = 0L

    /** `skip (...)` 日志最短间隔 */
    private const val SKIP_LOG_INTERVAL_MS = 1_000L

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

    /**
     * 偏航态（v3.15.67）：本趟是否处于「已偏航 / 正在重新规划」。
     *
     * 判定来源只有语音播报（[extractNaviText] 的整段文本），见 [YAW_ON_PHRASES] /
     * [YAW_OFF_PHRASES]。置位后迷你条的转向块 / 进度 / 时间值同时转红
     * （[ArkTokens.NAV_YAW_RED]），回到主路再转回品牌蓝。
     *
     * 跨趟必须清零：否则上一趟最后一句话是偏航，下一趟一出发就是红的
     * （重置点在 [beginSession]）。
     */
    private var navYawed = false

    /** 最近一次置红的时刻（elapsedRealtime）。用于「最短保持」：避免红只闪一下用户看不见 */
    private var yawedAt = 0L

    /** 偏航红最短保持时长：高德常在偏航后 3~5 秒内就播下一条转向，太短会看不出变过色 */
    private const val YAW_MIN_HOLD_MS = 12_000L

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

    /**
     * 播报里最近一次「大约需要 X」解析出的毫秒数；0 表示没解析出来。
     *
     * v3.15.65 起它**不再是**进度条分母（分母是 [tripTotalMs]）：途中重播的时长是
     * 「剩余」而不是「全程」，直接拿它当分母会让进度球倒着跑。
     */
    private var totalMs = 0L

    /**
     * 本趟**全程**耗时（v3.15.65 锚点进度的分母）。
     *
     * 为什么要和 [totalMs] 分家：高德途中会重播「大约需要十七分钟」，那个十七是**剩余**，
     * 不是新的全程。若直接拿它当分母，进度球会倒着跑（已用 10 分钟 / 新分母 17 分钟 → 58%）。
     * 所以分母在出发帧钉死，之后只动 [remainMs]。
     */
    private var tripTotalMs = 0L

    /** 剩余毫秒（v3.15.65 锚点进度）；[UNKNOWN_REMAIN_MS] = 本趟还没有锚点 */
    private var remainMs = UNKNOWN_REMAIN_MS

    /**
     * **锚点剩余**：在 [anchorAt] 那一刻还剩多少（v3.15.66）。
     *
     * 与 [remainMs] 的区别：[anchorRemainMs] 是「定值」，只在播报带来新时长时被改写；
     * [remainMs] 是它随时间递减的结果，每次投递前由 [refreshAnchor] 重算。
     *
     * 为什么不直接用 `navStartedAt` 推（v3.15.65 的做法）：途中重播的「大约需要十七分钟」
     * 是**剩余**，用「全程 - 已用」根本表达不了它；而锚点式只需要把锚点挪到这一刻、
     * 锚点剩余改成 17 分钟即可，两个语义各归各位。
     */
    private var anchorRemainMs = UNKNOWN_REMAIN_MS

    /** [remainMs] 是哪一刻算出来的（`elapsedRealtime`），只用于日志对齐 */
    private var anchorAt = 0L

    /**
     * 播报里**明确**给了到达时刻（`预计十二点十九分到达`）。
     *
     * v3.15.65：兜底 ETA 是「此刻 + 剩余」，每帧都要重算（剩余在减少，算出来的到达时刻
     * 基本钉住）。一旦播报里明确说过，就必须让它赢 —— 否则兜底会按分钟粒度把高德自己
     * 的判断覆盖掉。
     */
    private var etaExplicit = false

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

    /**
     * [remainMs] 的「还没有值」哨兵（v3.15.65）。
     *
     * 用 **-1 而不是 0**：0 是**合法**值（刚好到达 / 剩余已归零，此时进度就该是 100%），
     * 面板必须能区分「还剩 0」和「压根算不出来」。写 0 会让刚进导航的面板一上来就满格。
     */
    private const val UNKNOWN_REMAIN_MS = -1L


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
        // v3.15.74：我们自己重投高德那条通知时不要递归回来
        if (selfNotify) return
        // v3.15.66（R2）：这里只挡「同一次退出之后 1.5 秒内的重投」。
        //
        // 旧实现是 5 秒静默窗一刀切，真机上把 14:13:35 那次**骑行导航的新首帧**也吞了
        // （`skip (ending window)`），那一趟永远没有分母。现在窗缩到 1.5 秒：
        // 结束语后 3ms 的那次重发照样挡住，超过 1.5 秒的命中一律按**新会话**处理。
        if (navState == STATE_ENDING) {
            val now0 = SystemClock.elapsedRealtime()
            if (now0 - navEndingAt < ENDING_REPOST_GUARD_MS) {
                if (now0 - lastSkipLogAt >= SKIP_LOG_INTERVAL_MS) {
                    lastSkipLogAt = now0
                    log("skip (ending repost guard)")
                }
                return
            }
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

        // v3.15.81 诊断 C：一次性 dump 高德源通知 extras 的全部键值。
        // 目的：看步行/驾车切换时，通知里有没有带 `2小时23分` / `19:02` 这类结构化字段；
        // 有的话下一版就能彻底摆脱「等播报」。只读不写。
        dumpSrcExtrasOnce(extras)

        // v3.15.81：这一帧的交通方式（通知标题/正文里可能带「步行导航」之类字样）。
        // 必须在 beginSession 之前算出来 —— 新会话要靠它决定「要不要丢掉上一趟的兜底」。
        val modeChanged = noteTravelMode("${title.orEmpty()} ${text.orEmpty()}")

        // v3.15.62：目的地名既不在播报里（真机日志的出发播报只有
        // `全程…大约需要…预计…到达`，从没出现 POI 名），也不在通知的 title/text
        // （固定「高德地图 / 正在导航」）。
        // v3.15.62 / v3.15.65 / v3.15.66：目的地共两条来源，且**都要在会话复位之后落值**：
        //  ① 通知 extras 里「像地名」的字段（`guessDest`，见复位之后）；
        //  ② 文案网里的 `终点，XXX`（v3.15.65，见 §9）。
        // 这里**只取快照、不落值** —— 下面的 beginSession 会把 destText 连同候选池一起清掉，
        // 写在这里会被同一帧的 reset 抹掉；真正落值放在复位之后（见 `lastHitAt = now` 那一段）。
        val textDestThisTrip = synchronized(destPoolLock) { destFromText }

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

        // ---- 会话边界（v3.15.66）
        //
        // 状态机优先，时间只做辅助：
        //  ① 当前不是 ACTIVE（IDLE / ENDING）→ 一定是新会话（首帧 / 结束后重开）；
        //  ② 已经 ACTIVE，但距上次命中超过 SESSION_GAP_MS → 当新会话（自愈兜底：
        //     没收到 cancel 也没 farewell，但显然中间断过）。
        //
        // 走到这里说明**确实是一条导航通知**（上游 matchReason 已过滤），
        // 所以「命中」本身就是新会话的最强证据 —— 不再靠 90 秒猜。
        //
        // `textDestThisTrip` 在 [beginSession] 之前就取好了快照：beginSession 会清候选池，
        // 而网里这条（导航前搜索阶段捞到的 `终点，XXX`）恰恰是这一趟最有用的值。
        val now = SystemClock.elapsedRealtime()
        val newSession = navState != STATE_ACTIVE || (now - lastHitAt) >= SESSION_GAP_MS
        // v3.15.81：交通方式变了的新会话，不许沿用上一趟的路线量级兜底
        if (newSession) beginSession(now, dropFallback = modeChanged)
        lastHitAt = now
        navState = STATE_ACTIVE
        navActive = true

        // v3.15.62：目的地兜底 —— 通知 extras 里「像地名」的字段（复位之后才写，
        // 否则会被 beginSession 的 reset 清掉）。
        dumpNavExtras(extras, src)
        if (destText.isEmpty()) guessDest(extras, src)?.let { destText = it }

        // v3.15.65：文案网兜底落值（顺序：播报解析 → guessDest → 文案网）。
        // 文案网是**最新证据但语义最粗**（只有 `终点，XXX` 这一层），所以放最后；
        // 播报里真出现「前往XX」时仍然优先。
        if (destText.isEmpty()) textDestThisTrip?.let { destText = it }

        // v3.15.64：收网 —— 把「导航前文案缓冲」一次性倒进日志。
        //
        // 位置必须在会话复位之后：复位会把 [textBufDumped] 置回 false，而高德每趟开头
        // 会连发 2 次导航通知（实测相隔 74ms）。若在复位之前倒，第一次那行会被抹掉、
        // 第二次再倒一次 → 每趟两行。放在这里 = 每趟恰好一行。
        dumpTextBuf()

        // v3.15.66（R3）：投递前必须先把锚点 / 剩余 / ETA 算好。
        // 旧代码只在播报里算，通知路径直接拿旧值投递，于是出现「ETA 有值而 remain=-1」
        // 这种自相矛盾的载荷（真机 14:15:07 三栏全 `--` 就是它）。
        syncTripView()

        val content = if (!newSession && lastNaviText.isNotEmpty()) {
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

        // v3.15.74：改写**高德自己那条**通知的标题。
        //
        // 真机 `dumpsys notification` 实证：
        //   · 我们的影子 flymeark_live_nav → android.title='全程十公里'（改对了）
        //   · 高德自己的 99910001        → android.title='高德地图'
        //   · 屏幕上那条实况导航条显示的是**后者**
        //
        // 也就是说 Flyme 把高德那条 `category=navigation` 的通知原生渲染成了实况条，
        // 我们发的影子是另一条、排在后面。在 SystemUI 侧改 TextView 改不动，
        // 因为改的根本不是那条。
        //
        // 所以这里直接把高德那条的 contentTitle 换成指令再重投一次 —— 它自己每几秒
        // 会把标题刷回「高德地图」，所以每帧都要改。
        // v3.15.79：默认**关闭**（见 [REWRITE_SRC_TITLE] 的注释）——
        // 它只改一次标题，反而把原生条钉死在起始帧占位文案上。标题现由 SystemUI 侧写。
        if (REWRITE_SRC_TITLE) rewriteSourceTitle(nm, src, notifId, content)
        // v3.15.65：这一帧就是「上次投递过的载荷」，让后续播报的去重基准对齐，
        // 否则第一条播报会无谓地重投一次（内容一模一样）
        lastShadowSig = shadowSigOf(content, remainTimePair())

        // 导航在跑 —— 从此语音播报可以实时改写胶囊文案。
        // **不能**在这里清 lastNaviText：导航中通知是秒级刷新的，清了就等于
        // 每次刷新都把刚写的指令抹掉（清旧文案的判断在 newSession 那几行里）。
        navActive = true
        navState = STATE_ACTIVE
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
        // 素材完整性守卫：没有源通知标题说明还没抓到高德那条通知，什么都不发
        lastTitle ?: return null
        val largeIcon = lastNaviIcon
        if (requireLargeIcon && largeIcon == null) return null
        val smallIcon = lastSmallIcon ?: return null
        return try {
            val b = Notification.Builder(ctx, NAV_SHADOW_CHANNEL)
                .setSmallIcon(smallIcon)
                // v3.15.73：**标题就是指令**，不再用源通知的固定「高德地图」。
                //
                // 这是这套东西最关键的一改。迷你条那行字是系统直接从
                // `contentTitle` 画到 `title_header_live` 上的，我们在 SystemUI 侧
                // 用反射去改那个 TextView 一直改不动 —— 真机 dump 证明同一个 header
                // 会切成 `title_header_live_public`（锁屏/加密态），那时
                // `findViewById(titleId)` 直接返回 null，写了个寂寞；而且系统每次
                // 重新绑定都会把我们写的覆盖回去。
                //
                // 微信/QQ 那条从来不用折腾标题，就是因为它的标题本来就是内容。
                // 导航同理：把指令放进 `contentTitle`，系统自己画，零反射。
                .setContentTitle(content)
                .setContentText(lastBody ?: "")
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
            // v3.15.66：目的地 / 距离 / 耗时都带「上一趟兜底」（详见 destTextForPayload 等）
            se.putString(ArkConstants.NAV_KEY_DEST, destTextForPayload())
            val distPair = distPair()
            se.putString(ArkConstants.NAV_KEY_DIST, distPair.first)
            se.putString(ArkConstants.NAV_KEY_DIST_UNIT, distPair.second)
            // v3.15.65：「耗时」栏是**剩余**耗时（分钟），不再是出发那一刻的全程总耗时。
            val remainPair = remainTimePair()
            se.putString(ArkConstants.NAV_KEY_TIME, remainPair.first)
            se.putString(ArkConstants.NAV_KEY_TIME_UNIT, remainPair.second)
            se.putString(ArkConstants.NAV_KEY_ETA, etaText)
            se.putLong(ArkConstants.NAV_KEY_STARTED, navStartedAt)
            // 分母：本趟钉死的全程；本趟没有就用上一趟的（否则进度条整个不画球）。
            // v3.15.81：上一趟的值必须**仍在有效窗口内**才用，否则一律当未知（= 不画球）
            se.putLong(
                ArkConstants.NAV_KEY_TOTAL_MS,
                if (tripTotalMs > 0L) tripTotalMs else fallbackTotalMs()
            )
            // v3.15.65：锚点进度 —— 面板只按这个数画，两帧之间**不许自己走**
            se.putLong(ArkConstants.NAV_KEY_REMAIN_MS, remainMs)
            se.putLong(ArkConstants.NAV_KEY_ANCHOR_AT, anchorAt)
            se.putInt(ArkConstants.NAV_KEY_TURN, turnCodeOf(instrPair.first))
            // v3.15.67：偏航态 —— 迷你条据此把转向块 / 进度 / 时间值同时转 NAV_YAW_RED
            se.putBoolean(ArkConstants.NAV_KEY_YAWED, navYawed)
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

    /**
     * 自己在重投高德那条通知 —— 用来打断 「notify → 我们再改写 → 再 notify」的递归。
     */
    @Volatile
    private var selfNotify = false

    /**
     * 把**高德自己那条**导航通知的标题改写成指令，并重投一次（v3.15.74）。
     *
     * 为什么必须改它：Flyme 把 `category=navigation` 的通知原生渲染成实况导航条，
     * 屏幕上那条是它，不是我们发的影子。影子负责带载荷（`NAV_KEY_*`），
     * 但**显示的字来自高德这条的 contentTitle**。
     *
     * 幂等：标题已经是目标值就直接返回 —— 既省一次重投，也顺带打断递归
     * （重投会再次进入本函数，那时标题已相等 → 立即返回）。
     */
    private fun rewriteSourceTitle(
        nm: NotificationManager,
        src: Notification,
        notifId: Int?,
        content: String,
    ) {
        val id = notifId ?: return
        if (content.isBlank()) return
        try {
            val ex = src.extras ?: return
            val cur = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            if (cur == content) return
            ex.putCharSequence(Notification.EXTRA_TITLE, content)
            // 正文原本是「正在导航」，留着会在下面多出一行，清掉
            ex.putCharSequence(Notification.EXTRA_TEXT, "")
            selfNotify = true
            try {
                nm.notify(id, src)
            } finally {
                selfNotify = false
            }
            log("src title -> '$content'")
        } catch (t: Throwable) {
            log("src rewrite failed: ${t.message}")
        }
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
     *  0. （v3.15.66）先看整段是不是**出发播报** → 是且当前不在 ACTIVE → [beginSession]
     *     **先建会话再解析**，否则刚解析到的「全程X公里」会被 reset 抹掉；
     *  1. `groupData` 是这一轮播报的分句数组 → 取**最后一个非空元素**（它才是此刻要播的）；
     *  2. 第一个候选没过筛（纯数字 / 噪音 / 空）→ 退回 `data` 再试一次
     *     （真机上两者不是同一句：`data` 是单句、`groupData` 是整段队列）；
     *  3. 中途命中**结束语**（[END_PHRASES]）→ [endNavForFarewell] 进 ENDING 再返回 null ——
     *     导航结束了，后面的播报（含告别语本身）不许再被投成正文；
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

        val full = fullTextOf(group) ?: data

        // v3.15.66（R2/C）：**出发播报是新会话的铁证，且一辈子只有一次**。
        // 必须放在解析之前：
        //  ① 先建会话再解析 —— 否则刚解析出来的「全程X公里 / 大约需要X分钟」
        //     会被 beginSession 的 reset 清掉（顺序反了就是一整趟三栏全 `--`）；
        //  ② 不再受任何静默窗 / 守卫约束 —— 旧实现里 5 秒静默窗把 14:13:35 那次
        //     骑行导航的出发播报一起吞了，那一趟永远没有分母。
        if (full != null && isDeparture(full) && navState != STATE_ACTIVE) {
            beginSession(SystemClock.elapsedRealtime())
            log("session begin (departure tts)")
        }

        // v3.15.67：偏航态判定。放在 beginSession 之后 —— 新会话的「清零」优先，
        // 出发播报里不可能出现偏航词，所以这里的先后只影响「谁最终说了算」，
        // 而正确语义就是「新会话从蓝色开始」。
        updateYawState(full)

        // v3.15.61：展开卡的行程数字（全程距离 / 总耗时 / 预计到达）只在播报里有，
        // 而且散落在**中间**几段（`准备出发,,全程八公里,大约需要二十七分钟,…`），
        // 只看最后一句永远抓不到 —— 所以先整段解析一遍，再挑胶囊文案。
        captureTotals(full)

        for (candidate in listOfNotNull(lastSentenceOf(group), data)) {
            // v3.15.66：命中结束语 → 会话进 ENDING（不许再投递任何影子，见 enterEnding）
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
     * 按播报文本更新偏航态（v3.15.67 落地，v3.15.69 重写清零策略）。
     *
     * ## 为什么 v3.15.69 要重写
     *
     * 旧实现只有 `YAW_OFF_PHRASES = ["回到主路", "已回到"]` 这一条清零路径，而真机上
     * 高德重新规划成功后更常播的是「已为您重新规划路线，请沿当前道路继续行驶」——
     * 这句话**不含任何 OFF 词**，反而含 `重新规划` 会再次命中 ON 词，结果整趟红到底。
     *
     * ## 现在的判定顺序（顺序本身是正确性的一部分，别调换）
     *
     * 1. 命中偏航词 → 变红，并记下置红时刻 [yawedAt]；
     * 2. 本来就不红 → 直接返回（绝大多数帧走这条，零额外开销）；
     * 3. [YAW_MIN_HOLD_MS] 最短保持期内 → **任何播报都不许清**。高德常在偏航后
     *    3~5 秒内就播下一条转向，若立刻清，红只闪一两秒，用户根本看不出变过色；
     * 4. 兜底清零 A：明确的「回到主路」类措辞；
     * 5. 兜底清零 B：高德又给出了**真正的道路指引** —— 要么抠出来的句子含 [TURN_KEYWORDS]，
     *    要么整段命中 [YAW_CLEAR_HINTS]（`沿当前道路` / `继续行驶` / `路线已更新` 之类）。
     *    v3.15.70 收紧过：v3.15.69 只看 `pickCapsuleText != null`，测速 / 限速 / 车距 /
     *    拥堵 / 限行那些提示也能过，长绕路途中会把红误清（QA 实测 7/7）。
     *
     * 状态没变时**不写日志** —— 每 3 秒一句播报，重复刷日志没有价值。
     *
     * @param raw 本轮播报的整段文本（`groupData` 拼接结果，退回 `data`）
     */
    private fun updateYawState(raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            val now = SystemClock.elapsedRealtime()

            // ① 先判偏航词（命中即红，并记下置红时刻）
            if (YAW_ON_PHRASES.any { raw.contains(it) }) {
                // v3.15.70：置红时刻**每句都刷新**。v3.15.69 把它关在 if (!navYawed) 里面，
                // 导致连续偏航时「最短保持」是拿第一句算的 —— 第三句偏航播完后 1 秒就被清掉了。
                // 语义上「仍在偏航」应该从最后一句偏航播报起算。
                yawedAt = now
                if (!navYawed) {
                    navYawed = true
                    log("yaw state=true text='${raw.take(48)}'")
                }
                return
            }

            // ② 没在偏航态，什么都不用做
            if (!navYawed) return

            // ③ 最短保持期内的任何播报都不许清 —— 否则偏航那句之后紧跟的重复转向
            //    播报会把红在一两秒内抹掉，用户根本看不见
            if (now - yawedAt < YAW_MIN_HOLD_MS) return

            // ④ 兜底清零 A：明确的「回到主路」类措辞
            if (YAW_OFF_PHRASES.any { raw.contains(it) }) {
                navYawed = false
                log("yaw state=false (off-phrase) text='${raw.take(48)}'")
                return
            }

            // ⑤ 兜底清零 B（v3.15.70 收紧）：只认「真正的道路指引」。
            //    v3.15.69 用 pickCapsuleText(raw) != null 当判据，实测 7/7 误清
            //    （测速 / 限速 / 车距 / 拥堵 / 限行 全都能过那个函数）。
            //    现在要求：要么抠出来的这句话里含转向关键词（[TURN_KEYWORDS]），
            //    要么整段里出现明确的「已回到路线」措辞。
            val picked = pickCapsuleText(raw)
            val resumed = (picked != null && TURN_KEYWORDS.any { picked.contains(it) }) ||
                YAW_CLEAR_HINTS.any { raw.contains(it) }
            if (resumed) {
                navYawed = false
                log("yaw state=false (guidance resumed) text='${raw.take(48)}'")
            }
        } catch (_: Throwable) {
            // 判定失败保持原状：宁可沿用上一帧的颜色，也不许炸掉播报链路
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
        enterEnding("farewell")
    }

    /**
     * 进入 ENDING 态（v3.15.66）。
     *
     * 三件事，缺一件就会复发旧 bug：
     *  1. 状态置 ENDING —— 之后 `updateShadowContent` / `selfHealShadow` 一律直接返回，
     *     告别语再也不许当正文投出去（R5）；
     *  2. 把本趟结论搬进跨趟兜底（[stashLastTrip]）—— 下一次会话靠它补三栏与目的地（R1）；
     *  3. 主动撤掉影子 —— 不撤的话高德重发通知又会让我们发一条新的（v3.15.57 胶囊常驻的直接原因）。
     *
     * ⚠ **不在这里 `resetNavPayload()`**：一 reset 就把「兜底值」的来源清空了。
     * 本趟的值留到下一次 [beginSession] 里再清（那时已经 stash 完毕）。
     *
     * @param reason 进 ENDING 的原因（写日志用：`farewell` / `source gone` / `cancel`）
     */
    private fun enterEnding(reason: String) {
        try {
            val was = navState
            if (was == STATE_ENDING) return          // 幂等：同一次结束只处理一次
            navState = STATE_ENDING
            navActive = false
            // 守卫时间戳：挡「结束语后 3ms 的那次重发」，见 ENDING_REPOST_GUARD_MS
            navEndingAt = SystemClock.elapsedRealtime()
            lastNaviText = ""
            lastShadowSig = ""
            stashLastTrip()
            cancelShadowQuietly()
            log("session ending ($reason) was=$was")
        } catch (_: Throwable) {
        }
    }

    /**
     * 开一趟新会话（v3.15.66）。
     *
     * 顺序是硬要求：**先 stash、再 reset**（否则上一趟的结论先被清掉，兜底就空了）。
     *
     * `navStartedAt` 重置为**此刻**：新会话的进度必须从 0 开始，而不是接着上一趟的
     * 百分比往下走（上一趟的 `navStartedAt` 早于这一趟起点，沿用会让剩余直接变负）。
     */
    private fun beginSession(now: Long, dropFallback: Boolean = false) {
        try {
            stashLastTrip()
            // v3.15.81：交通方式变了 = 这是**另一条路线**，上一趟的路线量级（全程/里程/耗时）
            // 一律作废。必须在 stashLastTrip 之后清 —— 否则刚刚搬进来的旧值又活过来了。
            if (dropFallback) clearTripFallback("mode changed on new session")
            resetNavPayload()
            navStartedAt = now
            // v3.15.81：兜底只在新会话开头 15 秒内可信（详见 [fallbackValid]）
            fallbackValidUntil = if (dropFallback) 0L else now + FALLBACK_TTL_MS
            // v3.15.67：新一趟从蓝色开始 —— 上一趟最后一句若是偏航，不清零就会一出发就红
            navYawed = false
            // v3.15.69：置红时刻一并归零，否则新一趟的「最短保持」会被上一趟的旧时间戳算成已过期
            yawedAt = 0L
            navState = STATE_ACTIVE
            navActive = true
            navEndingAt = 0L
            log(
                "session begin (new) fallbackTotal=${lastTripTotalMs / 60_000}min " +
                    "fallbackDest='$lastDest' dropFallback=$dropFallback mode='$travelMode'"
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * 把本趟的结论搬进跨趟兜底字段（v3.15.66）。
     *
     * **只搬非空值** —— 这样它可以被反复调用（[beginSession] 与 [enterEnding] 都调），
     * 后一次调用不会用「本趟已被清空的值」把好不容易攒下的兜底覆盖掉。
     */
    private fun stashLastTrip() {
        try {
            if (tripTotalMs > 0L) lastTripTotalMs = tripTotalMs
            if (totalDistValue.isNotEmpty()) {
                lastDistValue = totalDistValue
                lastDistUnit = totalDistUnit
            }
            if (destText.isNotEmpty()) lastDest = destText
            if (totalTimeValue.isNotEmpty()) {
                lastTimeValue = totalTimeValue
                lastTimeUnit = totalTimeUnit
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 跨趟兜底**是否仍然可信**（v3.15.81）。
     *
     * 有效条件只有一个：新会话开始后的 [FALLBACK_TTL_MS] 窗口内。
     * 超时即清（幂等），调用方拿到 false 就当作「未知」→ 面板显示 `--`。
     *
     * 为什么用「读时判过期」而不是起一个定时器：本进程没有自己的调度器
     * （所有时机都由高德的通知 / 播报驱动），读时判是最省的写法，也不会漏。
     */
    private fun fallbackValid(): Boolean {
        if (fallbackValidUntil <= 0L) return false
        if (SystemClock.elapsedRealtime() <= fallbackValidUntil) return true
        clearTripFallback("ttl ${FALLBACK_TTL_MS}ms expired")
        return false
    }

    /** 本帧可用的跨趟全程（过期/没有 → 0 = 未知，进度条不画球） */
    private fun fallbackTotalMs(): Long = if (fallbackValid()) lastTripTotalMs else 0L

    /**
     * 清掉跨趟兜底（v3.15.81）。
     *
     * 只清「上一趟的路线量级」（全程 / 里程 / 耗时）——**保留 [lastDest]**：
     * 目的地跨趟复用是安全的，用户看到「杭州东站」不会有意见，看到「37 分钟」才有。
     */
    private fun clearTripFallback(reason: String) {
        try {
            val had = lastTripTotalMs != 0L ||
                lastDistValue.isNotEmpty() ||
                lastTimeValue.isNotEmpty()
            fallbackValidUntil = 0L
            lastTripTotalMs = 0L
            lastDistValue = ""
            lastDistUnit = ""
            lastTimeValue = ""
            lastTimeUnit = ""
            if (had) log("fallback cleared ($reason)")
        } catch (_: Throwable) {
        }
    }

    /** 交通方式关键词 → 归一化标记（v3.15.81，顺序有意义：先长后短避免误判） */
    private val TRAVEL_MODE_WORDS = listOf(
        "驾车" to "drive",
        "开车" to "drive",
        "步行" to "walk",
        "骑行" to "bike",
        "骑车" to "bike",
        "公交" to "bus",
        "地铁" to "subway",
    )

    /** 从一段文本里识别交通方式；识别不到返回 null（不改变现有判定） */
    private fun detectTravelMode(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for ((word, mode) in TRAVEL_MODE_WORDS) {
            if (text.contains(word)) return mode
        }
        return null
    }

    /**
     * 记录本帧识别到的交通方式（v3.15.81），与上一次不同则立即清掉跨趟兜底 ——
     * 换了交通方式，路线量级必然不同，旧值绝不能当兜底。
     *
     * 真机实证（16:38:46）：切步行时高德源通知正文是 `正在为您步行导航>>`（id 99910002），
     * 而**驾车**那趟的正文是 `正在导航`（没有任何方式词）。所以「上一趟方式未知
     * （空串）→ 这一趟显式声明了方式」也必须当作**可能换了方式**处理，否则这条最关键的
     * 切换会漏判（`"" != "walk"` 但 `""` 是未知而非驾车）。
     *
     * 反向安全：同一方式重开时（walk → walk，或驾车那趟永远识别不出方式）prev 与 mode
     * 相同或本帧识别不到，都不会误清 —— 「重开同一路线」的兜底因此保留。
     *
     * @return true 表示本帧**确认**发生了交通方式变化
     */
    private fun noteTravelMode(text: String?): Boolean {
        val mode = detectTravelMode(text) ?: return false
        val prev = travelMode
        // 有历史兜底值才值得判「未知 → 已知」；没有任何兜底时清了也没意义
        val changed = (prev.isNotEmpty() && prev != mode) ||
            (prev.isEmpty() && lastTripTotalMs != 0L)
        travelMode = mode
        if (changed) {
            clearTripFallback("mode ${prev.ifEmpty { "unknown" }} -> $mode")
            return true
        }
        return false
    }

    /** 一次性 dump 高德源通知 extras 的全部键值（v3.15.81 诊断；`Bundle.get` 已 deprecated） */
    @Suppress("DEPRECATION")
    private fun dumpSrcExtrasOnce(ex: Bundle?) {
        if (srcExtrasDumped) return
        srcExtrasDumped = true
        try {
            if (ex == null) {
                log("srcExtras: <null>")
                return
            }
            val keys = ex.keySet().sorted().joinToString(",") { k ->
                "$k=${ex.get(k)?.toString()?.take(32)}"
            }
            XposedBridge.log("[${ArkConstants.LOG_TAG}] srcExtras: keys=[$keys]")
        } catch (t: Throwable) {
            log("srcExtras failed: ${t.message}")
        }
    }

    /** 撤掉自己的影子通知（拿不到 NotificationManager 就静默放弃） */
    private fun cancelShadowQuietly() {
        try {
            val nm = lastNm
                ?: (appContext()?.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? NotificationManager)
            if (nm == null) {
                log("nav shadow cancel skipped (no nm)")
                return
            }
            nm.cancel(NAV_SHADOW_TAG, NAV_SHADOW_ID)
        } catch (t: Throwable) {
            log("nav shadow cancel failed: ${t.message}")
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
            ArkNavParse.parseClock(m.groupValues[1])?.let {
                etaText = it
                // v3.15.65：记下「这个值是播报明确给的」—— 只是记账，不改任何解析规则。
                // 兜底每帧都会重算，没有这个标记就会把高德自己的判断按分钟粒度覆盖掉。
                etaExplicit = true
            }
        }

        // v3.15.62：高德**不是每趟都播**「预计X点X分到达」（真机日志里
        // `全程九公里,大约需要三十三分钟,` 就整句没有 ETA）。播报里拿不到时兜底算一个。
        //
        // v3.15.66：兜底调用**挪到 [syncTripView]**，紧跟锚点更新之后。
        // 原来写在这里的话，用的是**上一帧**的 remainMs（这一帧的锚点还没落），
        // 于是日志里会出现 `eta fallback = '14:48' (remain=-1ms)` 这种自相矛盾的行
        // （真机 14:13:10 就是这么来的）。解析函数只解析，算值的事交给收口函数。
    }

    /**
     * 「预计到达」兜底：此刻墙钟 + 剩余时长。
     *
     * v3.15.65 起**每帧都重算**（直接覆盖 [etaText]），这是「实时 ETA」的关键：
     * 高德只在**出发**那一句里报一次到达时刻，之后再播「大约需要十七分钟」时
     * 到达时刻其实没变 —— 拿 `此刻 + 剩余` 反复算，得到的正是那个钉住的时刻，
     * 而 `剩余` 越来越小、`此刻` 越来越大，两者抵消。
     *
     * **只有一级口径**（v3.15.66 收窄）：[remainMs] >= 0 → `此刻 + 剩余`；
     * 算不出剩余就把 ETA **清空**，绝不允许「ETA 有值而 remainMs = -1」这种
     * 自相矛盾的载荷（真机 14:15:07 三栏全 `--` 时日志里却是
     * `eta fallback = '14:51' (remain=-1ms)`）。旧实现还有一级
     * `出发墙钟 + 全程` 兜底，它就是这种矛盾载荷的来源，已删。
     *
     * **播报明确给过到达时刻（[etaExplicit]）就一律不碰** —— 那是高德自己的判断。
     *
     * 用墙钟而不是 `elapsedRealtime + 开机时间`：后者在用户改系统时间后会格式化错。
     */
    private fun computeEtaFallback() {
        if (etaExplicit) return
        try {
            // v3.15.66（R3）：**剩余都没有，就不许有 ETA**。
            // 旧实现在 totalMs>0 时还能用「出发墙钟 + 全程」硬算一个 ETA，于是载荷里
            // 出现「etaText 有值、remainMs = -1」的自相矛盾组合（真机 14:15:07）。
            // 现在只有一种来源：锚点剩余。算不出来就把 ETA 清空，让三栏一起显示 `--`，
            // 至少语义是一致的。
            if (remainMs < 0L) {
                if (etaText.isNotEmpty()) {
                    etaText = ""
                    log("eta cleared (no anchor)")
                }
                return
            }
            val arrival = System.currentTimeMillis() + remainMs
            val text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(arrival))
            if (text == etaText) return          // 值没变就别刷日志（每帧都会走到这里）
            etaText = text
            log("eta fallback = '$etaText' (remain=${remainMs}ms)")
        } catch (_: Throwable) {
        }
    }

    /**
     * 「耗时」栏要显示的值 + 单位（v3.15.65：**剩余**，不是全程）。
     *
     * 有锚点 → 剩余分钟数（向上取整到分钟：27 分 01 秒应该显示 28 分钟，
     * 向下取整会让用户觉得「怎么还剩 27 分钟不动」）。
     * 没锚点 → 退回播报里的全程值，别让卡片显示 `--`（此时进度条本来也不画球）。
     *
     * 超过 60 分钟也不在这里折成小时 —— 那是**展示**的事，交给面板
     * （[ArkNavPanelActivity] 的 `formatRemainTime`）；这里保持「分钟」单一口径，
     * 否则同一份载荷里会出现两种单位语义。
     */
    private fun remainTimePair(): Pair<String, String> {
        val rm = remainMs
        if (rm >= 0L) {
            // rm = 0 = 已经到达（或已到底）→ 老老实实显示 `0 分钟`，
            // 不能退回全程值 —— 那时候显示「27 分钟」比显示 0 更离谱
            val minutes = (rm + 59_999L) / 60_000L
            return minutes.toString() to "分钟"
        }
        // v3.15.66：没有锚点时按「本趟解析值 → 上一趟兜底」退，别直接 `--`
        // v3.15.81：上一趟的值只在有效窗口内可用（超时/换交通方式后一律回到 `--`）
        if (totalTimeValue.isNotEmpty()) return totalTimeValue to totalTimeUnit
        if (fallbackValid() && lastTimeValue.isNotEmpty()) return lastTimeValue to lastTimeUnit
        // 两级都空 → 返回空对，面板显示 `--`（此时进度条本来也不画球）
        return "" to ""
    }

    /**
     * 「总里程」的值 + 单位（v3.15.66 起带跨趟兜底）。
     *
     * 高德重开同一路线时不重播「全程X公里」，没有这一级兜底第二栏就会空着。
     * 本趟解析到就用本趟的，绝不拿上一趟盖掉本趟。
     */
    private fun distPair(): Pair<String, String> = when {
        totalDistValue.isNotEmpty() -> totalDistValue to totalDistUnit
        // v3.15.81：上一趟的里程只在有效窗口内可用
        fallbackValid() && lastDistValue.isNotEmpty() -> lastDistValue to lastDistUnit
        else -> totalDistValue to totalDistUnit
    }

    /** 目的地（v3.15.66 起带跨趟兜底）：本趟 > 上一趟 > 空（面板回落「高德地图」） */
    private fun destTextForPayload(): String =
        if (destText.isNotEmpty()) destText else lastDest

    /**
     * 影子通知「整份载荷」的指纹（v3.15.65，v3.15.66 与载荷字段对齐）。
     *
     * 只取**会写进影子**且**用户看得见**的字段：胶囊文案 + 展开卡的 6 个数值/文案。
     * 用来判断这一帧要不要重投影子（[updateShadowContent]）—— 展开卡的锚点进度
     * 全靠「重投 → SystemUI 广播 → 面板」这条链推进，漏一个字段就是「数字永远不动」。
     *
     * ⚠ 必须与 [buildShadow] 写进 extras 的取值**完全同源**（v3.15.66）：
     * 那边用的是兜底后的 [destTextForPayload] / [distPair] / `tripTotalMs ?: lastTripTotalMs`，
     * 这里若还取原字段，就会出现「载荷变了但指纹没变」（面板钉在旧值）或反之。
     *
     * 刻意**不含** `anchorAt`：它每帧都变，算进来等于取消去重。
     *
     * v3.15.69 补入 [navYawed]：偏航翻转本身必须算一次「载荷变了」。否则偏航帧若文案
     * 与上一帧相同（同一路口重复播报，真机见过），指纹不变 → [updateShadowContent]
     * 直接 return → 影子不重投 → 迷你条永远不变红。
     *
     * @param content     这一帧要写进胶囊的文案
     * @param remainPair  已经算好的「剩余耗时（值, 单位）」（避免重复计算 / 两次取值不一致）
     */
    private fun shadowSigOf(content: String, remainPair: Pair<String, String>): String = try {
        val dist = distPair()
        listOf(
            content,
            destTextForPayload(),
            dist.first,
            dist.second,
            remainPair.first,
            remainPair.second,
            etaText,
            totalMs.toString(),
            (if (tripTotalMs > 0L) tripTotalMs else fallbackTotalMs()).toString(),
            remainMs.toString(),
            navYawed.toString()
        ).joinToString("\u0001")
    } catch (_: Throwable) {
        ""
    }

    /**
     * 收口：每次拿到播报先跑一遍 [parseTotals]，再把「这一帧声明的新时长」落成锚点。
     *
     * @param full 整段播报（`groupData` 拼起来 / 退回 `data`）；null 表示这一帧没有播报
     */
    private fun captureTotals(full: String?) {
        try {
            if (full.isNullOrBlank()) return
            // v3.15.81：播报里也可能出现「步行导航开始」这类词 —— 与通知侧同一套判定
            noteTravelMode(full)
            // v3.15.81：**转向播报已到、本趟仍无全程** → 说明这趟不会重播「全程…」，
            // 上一趟的全程不可信（换交通方式的典型形态），立刻让位，宁可显示 `--`。
            if (tripTotalMs == 0L && turnCodeOf(full) != ArkConstants.NAV_TURN_STRAIGHT) {
                clearTripFallback("turn before totals")
            }
            val departureFrame = isDeparture(full)
            // v3.15.62：出发时刻必须先于 parseTotals 设置 —— ETA 兜底要用它。
            // v3.15.66：会话开始（[beginSession]）已经写过 navStartedAt，这里只补「漏网的首帧」。
            if (navStartedAt == 0L && departureFrame) {
                navStartedAt = SystemClock.elapsedRealtime()
            }
            val beforeTotal = totalMs
            parseTotals(full)          // 纯解析：只写 totalDist* / totalTime* / totalMs / etaText
            if (destText.isEmpty()) parseDest(full)?.let { destText = it }
            // v3.15.65/v3.15.66：这一帧若带来了**新的时长**，它就是一次剩余值刷新
            applyAnchorFromDuration(departureFrame, totalMs != beforeTotal)
            // 锚点刚更新过，必须重算剩余 / ETA 才是「实时」的（v3.15.66：唯一收口点）
            syncTripView()
        } catch (_: Throwable) {
        }
    }

    /**
     * 把「这一帧声明的时长」落成锚点（v3.15.66）。
     *
     * 两种情况语义完全不同，混在一起就是 v3.15.65 的隐患：
     *  1. **出发帧 / 本趟还没定过分母**：`大约需要 X` = **全程** → 钉死分母 [tripTotalMs]；
     *  2. **途中重播了新时长**（改道 / 重新规划）：`大约需要 X` = **剩余** → 直接当剩余，
     *     绝不能当新分母（否则已用 10 分钟 / 新分母 17 分钟 → 58% 的假进度）。
     *
     * 重复播报同一个数字（`totalChanged == false`）时**不动锚点** —— 让剩余自然往下走，
     * 不然后一次播报会把剩余「回拨」到同一个值，进度条看起来在抖。
     *
     * @param departureFrame 这一帧是不是「准备出发 / 开始导航 / 全程…」这类出发播报
     * @param totalChanged   [parseTotals] 是否改写了 [totalMs]
     */
    private fun applyAnchorFromDuration(departureFrame: Boolean, totalChanged: Boolean) {
        try {
            if (!totalChanged || totalMs <= 0L) return
            val now = SystemClock.elapsedRealtime()
            if (departureFrame || tripTotalMs <= 0L) {
                tripTotalMs = totalMs
                anchorRemainMs = totalMs
            } else {
                // 途中：高德报的是「还需要 X」，分母不动
                anchorRemainMs = totalMs.coerceAtLeast(0L)
            }
            anchorAt = now
            remainMs = anchorRemainMs.coerceAtLeast(0L)
        } catch (_: Throwable) {
        }
    }

    /**
     * 投递前的统一收口（v3.15.66）：锚点 / 剩余 / ETA 三件套一次算完。
     *
     * **每一次 `publishShadow` / `nm.notify` 之前都必须先调它**。
     * 旧代码只在播报路径上算锚点，通知路径直接拿旧值投递，于是真机 14:15:07 出现了
     * `eta fallback`（说明有总耗时）却 `remain=-1` 的自相矛盾载荷 → 三栏全 `--`。
     */
    private fun syncTripView() {
        refreshAnchor()
        computeEtaFallback()
    }

    /**
     * 锚点 → 剩余（v3.15.65 起，v3.15.66 改为「锚点式递减」）。
     *
     * 核心口径：**锚点定一次，剩余随时间递减**；每次播报只是把锚点往前挪（并重置锚点剩余）。
     * 面板两帧之间永远不会自己走（只认 [remainMs]）。
     *
     * 分母的取值顺序（v3.15.66 新增第二级）：
     *  1. 本趟已解析到的 [tripTotalMs]；
     *  2. **上一趟的** [lastTripTotalMs] —— 高德重开同一路线时**不重播全程**，
     *     没有这一级就会整趟三栏 `--`；
     *  3. 都没有 → 剩余无意义（[UNKNOWN_REMAIN_MS]），面板只画轨不画球。
     *
     * 边界：剩余到 0 就停在 0（进度满格），不回绕、不变负。
     */
    private fun refreshAnchor() {
        try {
            val now = SystemClock.elapsedRealtime()
            if (anchorRemainMs < 0L) {
                // v3.15.81：上一趟的全程只在有效窗口内可用（过期即当作没有）
                val total = if (tripTotalMs > 0L) tripTotalMs else fallbackTotalMs()
                if (total <= 0L) {
                    remainMs = UNKNOWN_REMAIN_MS
                    anchorAt = 0L
                    return
                }
                // 新会话刚开始 / 还没收到任何时长播报：以「全程」为锚点剩余，进度天然是 0
                anchorRemainMs = total
                anchorAt = now
            }
            if (anchorAt <= 0L) anchorAt = now
            remainMs = (anchorRemainMs - (now - anchorAt)).coerceAtLeast(0L)
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
        totalDistValue = ""
        totalDistUnit = "公里"
        totalTimeValue = ""
        totalTimeUnit = "分钟"
        totalMs = 0L
        etaText = ""
        destText = ""
        extrasDumped = false
        // v3.15.65：锚点进度 / 实时 ETA 同样是「本趟」的结论，必须一起清。
        // remainMs 回哨兵（不是 0）—— 0 是合法的「刚好到达」，面板会画满格。
        tripTotalMs = 0L
        remainMs = UNKNOWN_REMAIN_MS
        // v3.15.66：锚点剩余也要归零，否则下一趟会拿着上一趟的锚点继续往下走
        // （表现是「刚点开始导航，剩余就已经少了 20 分钟」）
        anchorRemainMs = UNKNOWN_REMAIN_MS
        anchorAt = 0L
        etaExplicit = false
        // ⚠ v3.15.66：**不清 [lastTripTotalMs] / [lastDistValue] / [lastDest] / [lastTime*]**。
        // 它们是「上一趟的兜底值」，高德重开同一路线时不重播全程，全靠它们补三栏与目的地；
        // 在这里清掉 = 新一趟永远 `--`（这正是 v3.15.65 用户看到的 bug）。
        // 它们的唯一写入点是 [stashLastTrip]，只搬非空值，不会被这一趟的空值污染。
        // v3.15.81：**但它们现在有 15 秒时效**（[fallbackValid]）—— 换了交通方式或转向播报
        // 已到却仍无本趟全程时会被 [clearTripFallback] 清掉。这里不直接清，是因为
        // 「重开同一路线」仍需要它们；时效交给读取点判断。
        // v3.15.65：载荷指纹也要清 —— 下一趟第一帧的载荷可能和上一趟末帧「看起来一样」
        // （同一条路、同一个剩余），不清就会被误判成重复而漏投，胶囊整趟不更新
        lastShadowSig = ""
        // v3.15.64：文案捕捞网同样每趟一次，跟着 extras 一起复位
        textBufDumped = false
        // v3.15.65：30s 轻量取证的水位也要复位，否则上一趟的水位会把这一趟开头的
        // 「新增文案」全过滤掉（它们的时间戳都小于旧水位）。
        // 进 textBufLock：hook 回调可能在别的线程上 tick，字段改一半会被读到脏值。
        synchronized(textBufLock) {
            lastTextTickAt = 0L
            textTickSinceMs = 0L
        }
        // v3.15.65：目的地候选池是「本趟导航」的结论，跨趟保留会串味（上一趟的终点被当成这一趟的，
        // 错的目的地比没有更糟）→ 必须清。
        // ⚠ 但 [textBuf] **不许**跟着清：那是「最近看到的文案时间线」，要横跨这次复位才能覆盖
        // 「搜索 → 选点 → 开始导航」。两者语义不同，别一起处理。
        synchronized(destPoolLock) {
            destCandidates.clear()
            destFromText = null
        }
        // ⚠ **绝对不要在这里加 `textBuf.clear()`**。执行顺序是「reset → dump」，一清就永远是
        // `NAV TEXT BUF: n=0`（合法输出！看不出错），整个网白干。而且缓冲区必须横跨这一次
        // reset 才能覆盖「搜索 → 选点 → 开始导航」这段过程。详见 changelog v3.15.64 §4。
    }

    /**
     * 用新的播报文案覆盖胶囊；**没在导航时则自愈补发影子**。
     *
     * 三道闸（更新路径）：
     *  1. `navActive` —— 没在导航走自愈分支（见下）；
     *  2. 载荷指纹（[lastShadowSig]，v3.15.65 起含文案与全部展开卡数字）没变 ——
     *     同一句反复播**且数字也没变**才不重投；文案没变但剩余耗时变了必须重投，
     *     否则展开卡的锚点进度会整趟钉死（详见 [shadowSigOf] 的注释）；
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
            // v3.15.66（R5/E）：**ENDING 之后任何播报都不许再把影子投出去**。
            // 真机 14:16:01 的 `content='旅程到这里就结…'` 就是从这里漏出去的 ——
            // 告别语被当成正文写进胶囊，退出后还常驻。状态机现在是硬闸。
            if (navState == STATE_ENDING) {
                log("skip (session ending)")
                return
            }
            if (!navActive) {
                selfHealShadow(content)
                return
            }
            // v3.15.65：去重维度从「文案」升级成「整份载荷」。
            // 高德连续播同一句时文案不变、但剩余耗时 / ETA 一直在变，而展开卡的锚点进度
            // 完全靠「影子重新投递 → SystemUI 广播 → 面板」这条链推动；
            // 只看文案的话面板会整趟钉死在第一次点开的那一刻。
            //
            // v3.15.66：算指纹**之前**先同步锚点 / ETA —— 指纹里含 remainMs，
            // 不同步就会用上一帧的剩余做去重依据，该重投的可能被误判成重复。
            syncTripView()
            val remainPair = remainTimePair()
            val sig = shadowSigOf(content, remainPair)
            if (sig == lastShadowSig) return
            val nm = lastNm
                ?: (appContext()?.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? NotificationManager)
                ?: return
            if (!navNotificationAlive(nm)) {
                // 源导航通知已经没了 = 会话结束（R4 在高德进程侧的落点）
                enterEnding("source gone")
                return
            }
            val shadow = buildShadow(content) ?: return
            nm.notify(NAV_SHADOW_TAG, NAV_SHADOW_ID, shadow)
            lastNaviText = content
            lastShadowSig = sig
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
            // v3.15.66：自愈**也不许**在 ENDING 里复活胶囊（否则告别语会借自愈这条路漏出去）
            if (navState == STATE_ENDING) {
                log("self-heal skipped (session ending)")
                return
            }
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

            // v3.15.66：自愈说明「胶囊该在却没有」，那就是一趟新会话（状态机还没进 ACTIVE）。
            // 先建会话再投递：重置本趟、锚点归零，进度才从 0 开始。
            if (navState != STATE_ACTIVE) {
                beginSession(SystemClock.elapsedRealtime())
                log("session begin (self-heal)")
            }
            // 投递前同步锚点 / 剩余 / ETA（R3）
            syncTripView()

            ensureChannel(nm)
            val shadow = buildShadow(content, requireLargeIcon = false)
            if (shadow == null) {
                log("self-heal skipped (build failed)")
                return
            }
            if (!publishShadow(nm, shadow)) return

            navActive = true
            navState = STATE_ACTIVE
            lastNaviText = content
            // v3.15.65：自愈发出的这一帧同样是「上次投递过的载荷」（否则下一条播报会重投）
            lastShadowSig = shadowSigOf(content, remainTimePair())
            navEndingAt = 0L
            lastHitAt = SystemClock.elapsedRealtime()
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
        // v3.15.66：走统一出口（幂等）。注意这里**不 reset**，本趟的值要留作跨趟兜底。
        enterEnding("shadow cancelled")
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
    //     `token(IBinder) → 服务全类名` 记进 [svcNameCache]（容量 [SVC_NAME_CACHE_MAX]，**FIFO** 淘汰 ——
    //     `LinkedHashMap` 没开 accessOrder，是插入序，不是 LRU），Service 派发时用 `data` 里的 token 反查。
    //
    //  R5 **目的地预留额度**（QA 回归）：主额度是「先来先占」，带 extras 的 push / 统计 / SDK Service
    //     一样能吃掉 8 格 —— 真机上导航那条照样可能抓不到。**action 关键词过滤不可靠**
    //     （`NONAV` / `UNAVAILABLE` 这类都含 `nav` 子串，误命中），所以改成**按内容判定**：主额度被占满后，
    //     `looksLikeDest(extras)` 为真的 Intent 还有 [SVC_INTENT_RESERVE] = 4 次预留机会。

    /** 已 dump 过的 `serviceClassName:action` 组合（主额度去重） */
    private val svcIntentSeen = HashSet<String>(8)

    /** 已 dump 过的 `serviceClassName:action` 组合（弱额度去重，与主额度隔离） */
    private val svcIntentWeakSeen = HashSet<String>(4)

    /** 已 dump 过的 `serviceClassName:action` 组合（预留额度去重，与主 / 弱都隔离） */
    private val svcIntentReserveSeen = HashSet<String>(4)

    /** 已 dump 次数（主额度：带 extras 的 Intent） */
    private var svcIntentDumps = 0

    /**
     * Service Intent dump 主额度上限（只给带 extras 的 Intent）。
     *
     * v3.15.64 从 8 降到 4：真机实测这条路全是推送/网络 SDK（`LotusPoolService` /
     * `ChannelService` / `AmapAccsService` / `PushAidlService` …），高德是**单 Activity 架构**，
     * 「导航 Service 的启动参数」这个东西不存在。探针留着当旁证，但省日志。
     */
    private const val SVC_INTENT_MAX_DUMP = 4

    /** 已 dump 次数（弱额度：无 extras 但 action 像导航） */
    private var svcIntentNoExtraDumps = 0

    /** 弱额度上限 */
    private const val SVC_INTENT_NOEXTRA_BUDGET = 2

    /** 已 dump 次数（预留额度：主额度满后，内容像目的地的 Intent） */
    private var svcIntentReserveDumps = 0

    /** 预留额度上限 */
    private const val SVC_INTENT_RESERVE = 4

    /**
     * action 像导航 Service 的关键词（弱额度只放这些；不区分大小写）。
     *
     * v3.15.64 改严：原来裸 `nav` 会命中 `NONAV` / `UNAVAILABLE` 这类纯子串噪声
     * （`unav` 里含 `nav`；注意 `canvas` 里是 `nva`，新旧正则都不命中，别拿它当例子）。
     * 现在要求 `navigat` / `navi_` / `_nav` 这类带边界的形状（真机那批 push SDK 里没有一条
     * 是靠裸 `nav` 命中的，改严不会漏）。
     */
    private val NAV_SVC_ACTION_RE = Regex("(?i)(navigat|navi_|_nav|route|guide|tbt|drive|simulat)")

    /** 「cap reached」提示是否已打过（只打一次，只针对主额度） */
    private var svcIntentCapLogged = false

    /** 「reserve exhausted」提示是否已打过（只打一次） */
    private var svcIntentReserveCapLogged = false

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

            // 闸门③：无 extras 的导航类只走弱额度（claim 返回序号，领取与取号在同一把锁里完成）
            if (extras == null) {
                val seq = svcIntentClaimWeak("$svcName:$action")
                if (seq <= 0) return
                log(
                    "NAV SVC INTENT DUMP: svc=${svcName.substringAfterLast('.')} " +
                        "action=$action | extras: <none> (noextra $seq/$SVC_INTENT_NOEXTRA_BUDGET)"
                )
                return
            }

            // 闸门④：三级领取
            //  ① 主额度 8（逻辑不变）→ ② 主额度用尽时，「内容像目的地」的走预留 4 → ③ 都不行就丢
            var mark = ""
            if (!svcIntentClaim("$svcName:$action")) {
                // 主额度已满 / 该 key 已 dump 过；只有**内容像目的地**的才配动用预留额度
                if (!looksLikeDest(extras)) return
                val rseq = svcIntentClaimReserved("$svcName:$action")
                if (rseq <= 0) return
                mark = " (reserve $rseq/$SVC_INTENT_RESERVE)"
            }

            val flat = flattenExtras(extras, 0, EXTRAS_MAX_DEPTH).let {
                if (it.length > SVC_INTENT_EXTRAS_MAX_LEN) {
                    it.take(SVC_INTENT_EXTRAS_MAX_LEN) + "…"
                } else {
                    it
                }
            }
            log(
                "NAV SVC INTENT DUMP: svc=${svcName.substringAfterLast('.')} " +
                    "action=${action ?: "?"} | extras: $flat$mark"
            )
        } catch (_: Throwable) {
            // 宿主进程：异常必须在这里消失
        }
    }

    /**
     * 三套额度是否都还有剩（只读判断，不打日志；都没有就完全跳过后续反射）。
     *
     * **必须把预留额度也算进来**：预留是在「主额度领取失败」之后才领取的，若这里因为
     * 主 + 弱都满就提前 return，预留额度永远走不到 —— 那就是 E6 那个 bug 的翻版。
     */
    private fun svcIntentAnyBudgetLeft(): Boolean = synchronized(svcIntentLock) {
        if (svcIntentDumps < SVC_INTENT_MAX_DUMP) return@synchronized true
        noteCapReachedLocked()
        // v3.15.64：预留额度也满了就打一次 `reserve exhausted`。三满场景下这是它唯一的出口 ——
        // 预留额度是在「主额度认领失败之后」才领的，主额度一满这里就 return 的话永远轮不到它。
        if (svcIntentReserveDumps >= SVC_INTENT_RESERVE) noteReserveExhaustedLocked()
        svcIntentNoExtraDumps < SVC_INTENT_NOEXTRA_BUDGET || svcIntentReserveDumps < SVC_INTENT_RESERVE
    }

    /**
     * 打一次 `cap reached (8)`（调用方必须已持有 [svcIntentLock]）。
     *
     * 这个提示的可靠出口有两个：① 前置闸门发现主额度已满；② 主额度认领被拒。
     * 两处都调、由 [svcIntentCapLogged] 保证全局只打一次 —— 单靠 ② 会被 ① 的提前
     * return 挡掉（E6 实测就是这么漏的）。
     */
    private fun noteCapReachedLocked() {
        if (svcIntentCapLogged) return
        svcIntentCapLogged = true
        log("NAV SVC INTENT DUMP: cap reached ($SVC_INTENT_MAX_DUMP)")
    }

    /** 打一次 `reserve exhausted (4)`（调用方必须已持有 [svcIntentLock]） */
    private fun noteReserveExhaustedLocked() {
        if (svcIntentReserveCapLogged) return
        svcIntentReserveCapLogged = true
        log("NAV SVC INTENT DUMP: reserve exhausted ($SVC_INTENT_RESERVE)")
    }

    /**
     * 认领一个 `serviceClassName:action` 组合（主额度）；已认领过或主额度用尽返回 false。
     *
     * 主额度用尽时顺带打一次 `cap reached (8)`（[noteCapReachedLocked] 幂等）。
     */
    private fun svcIntentClaim(key: String): Boolean = synchronized(svcIntentLock) {
        if (svcIntentDumps >= SVC_INTENT_MAX_DUMP) {
            noteCapReachedLocked()
            return@synchronized false
        }
        if (!svcIntentSeen.add(key)) return@synchronized false
        svcIntentDumps++
        true
    }

    /**
     * 认领一个 `serviceClassName:action` 组合（弱额度）。
     *
     * 「领取 + 取序号」在**同一把锁**里一次完成，直接把序号返回给调用方拼日志 ——
     * 老写法是先 claim 再单独加锁读计数，并发下 `noextra n/2` 的 n 会重复。
     *
     * @return 1..[SVC_INTENT_NOEXTRA_BUDGET] = 领到的序号；0 = 没领到（额度用尽 / 重复）
     */
    private fun svcIntentClaimWeak(key: String): Int = synchronized(svcIntentLock) {
        if (svcIntentNoExtraDumps >= SVC_INTENT_NOEXTRA_BUDGET) return@synchronized 0
        if (!svcIntentWeakSeen.add(key)) return@synchronized 0
        svcIntentNoExtraDumps++
        svcIntentNoExtraDumps
    }

    /**
     * 认领一个 `serviceClassName:action` 组合（预留额度，只给 [looksLikeDest] 为真的 Intent）。
     *
     * 主额度已经 dump 过的 key **不再**走预留：那条日志已经在，不该再花预留额度重复一遍
     * （同一个导航 Service 反复启动时很容易踩到）。
     *
     * @return 1..[SVC_INTENT_RESERVE] = 领到的序号；0 = 额度用尽（已打 `reserve exhausted`）；
     *         负数 = 重复 / 已在主额度打过，静默丢弃
     */
    private fun svcIntentClaimReserved(key: String): Int = synchronized(svcIntentLock) {
        if (svcIntentSeen.contains(key)) return@synchronized -1
        if (svcIntentReserveDumps >= SVC_INTENT_RESERVE) {
            noteReserveExhaustedLocked()
            return@synchronized 0
        }
        if (!svcIntentReserveSeen.add(key)) return@synchronized -1
        svcIntentReserveDumps++
        svcIntentReserveDumps
    }

    /**
     * 一份 extras 里是否「有东西像目的地」—— 决定它配不配动用预留额度。
     *
     * 判定（任一成立即 true）：
     *  1. 某个 key 小写后命中 [DEST_KEY_HINTS] 任一片段（`dest` / `poi` / `终点` …），且取到的值
     *     能过 [sanitizeDest]；
     *  2. 某个 `CharSequence` / 数组元素的值本身能过 [sanitizeDest] —— 这条最有用：地名就是
     *     「2..16 字、含中文、不含 [DEST_NOISE]」的短串，[sanitizeDest] 的长度闸同时也挡住了
     *     长文本误判（例如整段播报、JSON、URL 一律不可能通过）；
     *  3. 递归进子 Bundle（`depth < DEST_MAX_DEPTH`，与 [findDestInBundle] 同口径）。
     *
     * 全程 `try/catch`，任何异常返回 false（这里跑在宿主进程里，宁可漏判也不许抛）。
     */
    @Suppress("DEPRECATION") // Bundle.get(String) 是遍历任意类型值的唯一泛型取法
    private fun looksLikeDest(extras: Bundle, depth: Int = 0): Boolean {
        try {
            for (k in extras.keySet()) {
                val v = try {
                    extras.get(k)
                } catch (_: Throwable) {
                    null
                }
                // ① key 本身像目的地 → 值也像才算（避免 destId=0 之类命中）
                val lk = try {
                    k.lowercase(Locale.ROOT)
                } catch (_: Throwable) {
                    k
                }
                if (DEST_KEY_HINTS.any { lk.contains(it) }) {
                    val s = when (v) {
                        is CharSequence -> v.toString()
                        is Array<*> -> v.firstOrNull()?.toString()
                        else -> null
                    }
                    if (sanitizeDest(s.orEmpty()) != null) return true
                }
                // ② 值本身像目的地
                when (v) {
                    is CharSequence -> if (sanitizeDest(v.toString()) != null) return true
                    is Array<*> -> {
                        for (e in v.take(EXTRAS_ARRAY_MAX_ELEMS)) {
                            if (e is CharSequence && sanitizeDest(e.toString()) != null) return true
                        }
                    }
                    is Bundle -> if (depth < DEST_MAX_DEPTH && looksLikeDest(v, depth + 1)) return true
                    else -> {
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return false
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

    // ---------------------------------------------------------------- 取证：导航前文案捕捞网（v3.15.64）
    //
    // 真机结论（docs/live-nav-research.md §8）三条旧通道全查空：
    //  ① 通知 extras —— 4 次 dump 的 16 个 key 完全一致，`android.subText/infoText/remoteInputHistory`
    //     全是 null，无地名；
    //  ② 导航 Service 启动参数 —— 高德是**单 Activity 架构**（只有 `SplashActivity` /
    //     `NewMapActivity`），导航只是 `NewMapActivity` 里的状态切换，「导航 Service 的启动 Intent」
    //     这个东西不存在（抓到的 14 条全是推送/网络 SDK）；
    //  ③ TTS 播报 —— 6 条全文无地名。
    //
    // 但**目的地是用户在导航开始之前就输入/看过的**：搜索框里打的字、POI 列表项、路线页标题，
    // 那一刻都是真实的 Java 文案，一定会经过 `TextView.setText` / `View.setContentDescription`。
    //
    // 所以换个打法：**全程撒网**（记住最近 30 条「像地名」的短文本 + 时间戳），
    // **导航开始时收网**（影子通知首次投递时一次性倒进日志）。
    //
    // 时间口径：日志里的秒数是「这条文案多少秒前被看到的」，**恒为正**（`now - atMs`，
    // `elapsedRealtime` 单调递增），越小越接近导航开始那一刻，`0.0s` 就是启动瞬间的文案。
    // **不要想改成「负号 = 导航前」**：那得拿 `navStartedAt` 当基准，而它要到
    // `beginSession()`（v3.15.66 起）/ 出发播报才置位 —— 首帧 dump 时它可能还是 0，
    // 会打出 `-1.4e9s` 这种垃圾数字。

    /** 一条文案命中：什么时候看到的（elapsedRealtime）、文本是什么 */
    private data class TextHit(val atMs: Long, val text: String)

    /** 环形缓冲容量：只要最近 30 条，够覆盖「搜索 → 选点 → 开始导航」这段输入过程 */
    private const val TEXT_BUF_MAX = 30

    /** 倒出时整行上限（超了截断，防一条日志把 logcat 刷爆） */
    private const val TEXT_BUF_LINE_MAX = 900

    /**
     * 环形缓冲（老 → 新）。读写都必须进 [textBufLock]：hook 回调跨线程。
     *
     * **跨趟不清空**（只在容量满时淘汰最老的）—— 缓冲区要覆盖「搜索 → 选点 → 开始导航」这段
     * 过程，而这段横跨一次 [resetNavPayload]；见那里的警告。
     */
    private val textBuf = ArrayDeque<TextHit>()

    /** [textBuf] 的锁 */
    private val textBufLock = Any()

    /** 每趟导航只倒一次网（重置点在 [resetNavPayload]） */
    private var textBufDumped = false

    // ---- v3.15.65：导航中的轻量取证（每 30 秒只打「新增」条目） ----
    //
    // 一锅端的 `NAV TEXT BUF` 只在影子首投时打一次，之后整趟黑屏 —— 而目的地在
    // 导航**过程中**还会继续刷（高德把 `终点，XXX` 写进 contentDescription，
    // 每次 UI 刷新都可能换一条）。所以再加一条低频流水：每 30 秒把这段时间里
    // **新进网**的条目打一行，用来观察「目的地文案是在哪一刻出现的」。
    //
    // 三条约束（都是硬要求）：
    // ① **只读不写**：绝不改 [textBuf]、不改文本去重、不清缓冲，`NAV TEXT BUF` 的输出
    //    与「跨趟保留」的语义完全不受影响；
    // ② 每 tick 最多 [TEXT_TICK_MAX] 条、整行最多 [TEXT_TICK_LINE_MAX] 字符 ——
    //    这是在导航过程中打日志，宁可少打也不能把 logcat 刷满；
    // ③ 水位用**时间戳**而不是下标：环形缓冲会淘汰最老的条目，下标会整体漂移。

    /** 两次轻量取证的间隔 */
    private const val TEXT_TICK_INTERVAL_MS = 30_000L

    /** 单个 tick 最多打几条 */
    private const val TEXT_TICK_MAX = 10

    /** 单个 tick 整行上限 */
    private const val TEXT_TICK_LINE_MAX = 600

    /** 上一次 tick 的时刻（0 = 本趟还没 tick 过）；进 [textBufLock] 保护 */
    private var lastTextTickAt = 0L

    /** 上一次 tick 的**水位**：只打 `atMs > textTickSinceMs` 的条目 */
    private var textTickSinceMs = 0L

    // ---- v3.15.65：从文案网里抽目的地 ----
    //
    // 真机实锤（v3.15.64 日志）：目的地在 `contentDescription` 里，高德自己写的模板就是
    // **`终点，XXX`**（同一趟还会出现 `终点，XXXP1地下停车场` 这种细化值），另有
    // `起点，我的位置` / `切换起终点` / `添加途经点` 这类同族文案必须排除。

    /** 高德终点文案模板（全角半角逗号冒号都吃）。`^` 锚定天然排除 `切换起终点`：它不以「终点」开头 */
    private val DEST_TEXT_RE = Regex("^(?:终点|目的地)[，,:：]\\s*(.+)$")

    /** 候选池上限 */
    private const val DEST_CAND_MAX = 3

    /** 展开卡状态行只有一行，超过这个长度就截断 */
    private const val DEST_TEXT_SHOW_MAX = 12

    /**
     * 文案网解析出的目的地候选（插入序 = 由旧到新，最多 [DEST_CAND_MAX] 条）。
     *
     * **本趟导航专用**：跨趟保留会串味（上一趟的终点被当成这一趟的），所以 [resetNavPayload]
     * 会清它 —— 这与 [textBuf] 必须跨趟保留的规矩**不冲突**：一个是「本趟结论」，一个是「时间线」。
     */
    private val destCandidates = LinkedHashSet<String>()

    /** [destCandidates] / [destFromText] 的锁（hook 回调可能不在主线程，reset 在主线程） */
    private val destPoolLock = Any()

    /** 从文案网解析出的本趟目的地（已按规则选好、已截断；null = 还没有） */
    private var destFromText: String? = null

    /**
     * 从一条候选文案里抽目的地并更新候选池。
     *
     * 只在 [rememberText] 通过 [sanitizeDest] 之后调用。取到的 group(1) **刻意不再过
     * [sanitizeDest]**：那张噪声表是给**播报**设计的（含「站」「停车场」「路线」这类），
     * 拿它筛 POI 名会把「杭州东站」「XX停车场」这类正常地名误伤掉，所以这里只做 trim + 长度闸。
     *
     * 其余排除项都由 `^` 锚定 + 非空 group 天然成立：
     *  - `起点，我的位置` —— 前缀是「起点」，不匹配；
     *  - `切换起终点` / `添加途经点` —— 不以「终点」开头；
     *  - `终点，`（空值）—— group(1) 为空，长度闸挡掉。
     *
     * @param text 已通过 [sanitizeDest] 的文本
     */
    private fun noteDestFromText(text: String) {
        try {
            val m = DEST_TEXT_RE.find(text) ?: return
            val cand = m.groupValues[1].trim()
            if (cand.length < 2 || cand.length > 24) return
            var outPicked: String? = null
            var outPool: List<String> = emptyList()
            synchronized(destPoolLock) {
                // 同一个值再看一次 = 「最近出现」，挪到队尾（否则规则②拿到的是旧位置）
                destCandidates.remove(cand)
                destCandidates.add(cand)
                while (destCandidates.size > DEST_CAND_MAX) {
                    destCandidates.firstOrNull()?.let { destCandidates.remove(it) }
                }
                outPool = destCandidates.toList()
                val raw = pickDestFromPool(outPool) ?: return
                val shown = if (raw.length > DEST_TEXT_SHOW_MAX) {
                    raw.take(DEST_TEXT_SHOW_MAX - 1) + "…"
                } else {
                    raw
                }
                if (shown == destFromText) return       // 没变就不打日志，避免每帧刷屏
                destFromText = shown
                outPicked = shown
            }
            val picked = outPicked ?: return
            log("NAV DEST FROM TEXT: $picked (pool=[${outPool.joinToString(",")}])")
        } catch (_: Throwable) {
        }
    }

    /**
     * 候选池 → 目的地（取值规则，不可自创）：
     *  1. 若某条是**其他所有候选的前缀**（`杭州东站` 之于 `杭州东站P1地下停车场`）→ 取它。
     *     展开卡只有一行，宽泛的那个更好看也更好读；细化值仍在 pool 里留证。
     *  2. 否则取**最后进池**的那条（最近出现的更贴近用户当前选择）。
     */
    private fun pickDestFromPool(pool: List<String>): String? {
        if (pool.isEmpty()) return null
        val commonPrefix = pool.filter { cand -> pool.all { it.startsWith(cand) } }
            .minByOrNull { it.length }
        return commonPrefix ?: pool.last()
    }

    /**
     * 记住一条「像地名」的文本。
     *
     * **高频路径**（高德主线程每秒可能调几百次 `setText`），所以过滤顺序是硬要求：
     * 先做最便宜的三刀 —— `null` / 长度不在 2..16 / 不含 CJK —— 任何一刀不过就返回，
     * 连 `toString()` 都不做；只有走到最后才 `toString()` + [sanitizeDest]
     * （噪声词、长度闸在里面再核一遍）。这样绝大多数 `setText`（数字、单位、英文、长句）
     * 都是零分配走完的。
     *
     * 同文本只保留**最新一次**（先删旧项再 `addLast`）：高德会把同一个地名反复 `setText`，
     * 不去重的话 30 条容量会被一条文案刷满，把别的候选挤掉。
     *
     * @param s 待记文本；null / 不合规静默丢弃
     */
    private fun rememberText(s: CharSequence?) {
        try {
            if (s == null) return
            val n = s.length
            if (n < 2 || n > 16) return                  // 第一刀：最便宜，砍掉绝大多数
            var hasCjk = false
            for (i in 0 until n) {
                if (s[i] in '\u4e00'..'\u9fff') {        // 第二刀：不含中文的多半是 id / action / 数字
                    hasCjk = true
                    break
                }
            }
            if (!hasCjk) return
            val text = sanitizeDest(s.toString()) ?: return   // 第三刀：复用既有纯函数（噪声 + 长度）
            // v3.15.65：顺手抽「终点，XXX」这类终点文案（高德自己写的 contentDescription 模板）。
            // 放在 sanitizeDest 之后是刻意的 —— 前面三刀已经把这批高频噪声挡掉了，这里只处理
            // 真正像地名的短文本，不必再为它单独做一遍长度/CJK 判断。
            noteDestFromText(text)
            val hit = TextHit(SystemClock.elapsedRealtime(), text)
            synchronized(textBufLock) {
                textBuf.removeAll { it.text == text }
                textBuf.addLast(hit)
                while (textBuf.size > TEXT_BUF_MAX) textBuf.removeFirst()
            }
            // v3.15.65：导航中每 30 秒把这段时间新进网的文案打一行（只读缓冲，见常量的注释）
            maybeDumpTextTick()
        } catch (_: Throwable) {
            // 宿主进程：捕捞网自己绝不能抛
        }
    }

    /**
     * 导航中的轻量取证：每 [TEXT_TICK_INTERVAL_MS] 把**新增**的缓冲条目打一行。
     *
     * 触发点在 [rememberText]（而不是 `postShadow`）：高德在导航中**不重发导航通知**
     * （见 `docs/live-nav-research.md` §8.2），`postShadow` 整趟只来一两次，
     * 拿它当心跳等于永远不会 tick。
     *
     * 首次调用只**初始化水位**、不输出 —— 否则第一个 tick 会把「导航前搜索阶段」的
     * 整锅文案再打一遍，与 `NAV TEXT BUF` 完全重复。
     *
     * 全部包在 try/catch 里：这是宿主进程的高频路径，绝不能因为取证把高德搞崩。
     */
    private fun maybeDumpTextTick() {
        try {
            if (!navActive) return               // 只在导航中打（导航前的证据由 NAV TEXT BUF 负责）
            val now = SystemClock.elapsedRealtime()
            // 计数器与「取条目」放在**同一个**临界区里：两个 synchronized 块中间会被别的线程
            // 插进来（addLast / resetNavPayload），水位和实际取到的条目就可能对不上。
            val fresh: List<TextHit> = synchronized(textBufLock) {
                if (lastTextTickAt == 0L) {      // 本趟第一次 → 只定水位
                    lastTextTickAt = now
                    textTickSinceMs = now
                    return
                }
                if (now - lastTextTickAt < TEXT_TICK_INTERVAL_MS) return
                val since = textTickSinceMs
                lastTextTickAt = now
                textTickSinceMs = now
                textBuf.filter { it.atMs > since }.take(TEXT_TICK_MAX)
            }
            if (fresh.isEmpty()) return          // 这 30 秒没有任何新文案 → 不打空行
            val sb = StringBuilder()
            for (h in fresh) {
                if (sb.isNotEmpty()) sb.append(" | ")
                sb.append('[').append(relSecText(h.atMs, now)).append("s] ").append(h.text)
            }
            val body = sb.toString()
            val shown = if (body.length > TEXT_TICK_LINE_MAX) {
                body.take(TEXT_TICK_LINE_MAX) + "…"
            } else {
                body
            }
            log("NAV TEXT TICK: $shown")
        } catch (_: Throwable) {
        }
    }

    /**
     * 收网：把环形缓冲一次性倒进日志（每趟导航一次，由 [textBufDumped] 保证）。
     *
     * 条数为 0 也打一行 `NAV TEXT BUF: n=0` —— 用来区分「网捞空了」和「网压根没挂上」，
     * 这两种情况的下一步动作完全不同。
     */
    private fun dumpTextBuf() {
        if (textBufDumped) return
        textBufDumped = true
        try {
            val snapshot = try {
                synchronized(textBufLock) { textBuf.toList() }
            } catch (_: Throwable) {
                emptyList()
            }
            if (snapshot.isEmpty()) {
                log("NAV TEXT BUF: n=0")
                return
            }
            val now = SystemClock.elapsedRealtime()
            val sb = StringBuilder()
            for (h in snapshot) {
                if (sb.isNotEmpty()) sb.append(" | ")
                sb.append('[').append(relSecText(h.atMs, now)).append("s] ").append(h.text)
            }
            val body = sb.toString()
            val shown = if (body.length > TEXT_BUF_LINE_MAX) {
                body.take(TEXT_BUF_LINE_MAX) + "…"
            } else {
                body
            }
            log("NAV TEXT BUF: n=${snapshot.size} | $shown")
        } catch (_: Throwable) {
        }
    }

    /**
     * 相对收网时刻的秒数（1 位小数）：**这条文案是多少秒前被看到的**。
     *
     * 恒为正 —— `now` 与 `atMs` 都来自 [SystemClock.elapsedRealtime]（单调递增），`atMs` 必然更早。
     * 越小越接近导航开始那一刻（`0.0s` = 启动瞬间的文案）。别改成负数语义，见本段顶部的说明。
     */
    private fun relSecText(atMs: Long, nowMs: Long): String = try {
        String.format(Locale.US, "%.1f", (nowMs - atMs) / 1000.0)
    } catch (_: Throwable) {
        "?"
    }

    /**
     * 挂两张文案网：`TextView.setText` / `View.setContentDescription`。
     *
     * `setText` 有 5 个重载（`CharSequence` / `CharSequence+BufferType` / `int` / `int+BufferType` /
     * `char[]+int+int`），逐个 `findAndHookMethod` 一定会漏（规格要求的两个只是其中两个），
     * 所以用 `hookAllMethods` 一次全挂，回调里按「首参是 `CharSequence`」过滤 ——
     * `int` / `char[]` 那两个重载自然被滤掉。
     *
     * hook 失败只记一行 probe，绝不影响导航胶囊本身。
     */
    private fun hookTextNets() {
        try {
            XposedBridge.hookAllMethods(
                TextView::class.java, "setText",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        rememberFirstCharSequence(param)
                    }
                }
            )
            probeRaw("text net: hooked TextView.setText (all overloads)")
        } catch (t: Throwable) {
            probeRaw("text net: hook TextView.setText failed (${t.javaClass.simpleName})")
        }
        try {
            XposedBridge.hookAllMethods(
                View::class.java, "setContentDescription",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        rememberFirstCharSequence(param)
                    }
                }
            )
            probeRaw("text net: hooked View.setContentDescription")
        } catch (t: Throwable) {
            probeRaw("text net: hook View.setContentDescription failed (${t.javaClass.simpleName})")
        }
    }

    /** 从 hook 参数里取首参（必须是 `CharSequence`）交给 [rememberText]；取不到就静默返回 */
    private fun rememberFirstCharSequence(param: XC_MethodHook.MethodHookParam) {
        try {
            val args = param.args
            if (args == null || args.isEmpty()) return
            val first = args[0]
            if (first !is CharSequence) return
            rememberText(first)
        } catch (_: Throwable) {
        }
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
        // v3.15.64：导航前文案捕捞网 —— 目的地是用户**导航前**输入/看过的，那时它是真实 Java 文案，
        // 一定走 `TextView.setText`。全程撒网，导航开始时由 [dumpTextBuf] 收网。
        hookTextNets()

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

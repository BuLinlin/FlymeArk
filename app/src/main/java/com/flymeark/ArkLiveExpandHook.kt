package com.flymeark

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import kotlin.math.hypot

/**
 * v2.6：点击实况胶囊 → 展开播放控制面板。
 *
 * ## 为什么原生点击「没反应」
 *
 * 状态栏触摸链路（反编译确认）：
 * ```
 * PhoneStatusBarViewController.LiveNotificationTouchHandler.onTouchEvent(ev)
 *   ├─ consumed = liveNotificationController.handleOnTouchEvent(ev)
 *   │    └─ LiveNotificationClickHelper.handleTouchEvent(ev)
 *   │         · ACTION_DOWN 命中 mLiveNotificationArea 时 mHasDown = true
 *   │         · ACTION_UP / CANCEL 时 mHasDown = false，直接 return true
 *   │         ★ 全程只维护一个 mHasDown 标志，没有任何点击动作
 *   ├─ if (consumed) onTouch(ev)
 *   └─ if (ACTION_UP) centralSurfaces.onLiveNotificationClick(null)
 *        └─ post 一个 Runnable（entry 传 null）
 *
 * 另一个入口 `LiveNotificationController.interruptClick()` →
 * `startNotificationPendingIntentForLiveCapsule()` 虽然能启动通知的 contentIntent，
 * 但该方法在整个 SystemUI 里 **没有任何调用方**（死代码）。
 *
 * 结论：胶囊点击在本 ROM 中本就无动作，只能自己接管。
 *
 * ## 本 hook 做的事
 *
 * 1. hook `handleOnTouchEvent` 的 after：DOWN 记录坐标与时刻，UP 时若位移与时长都在
 *    点击阈值内，拉起 `ArkCapsulePanelActivity`（顶部悬浮播放卡）。
 * 2. hook `CentralSurfacesImpl.onLiveNotificationClick` 的 before：命中我们自己的点击时
 *    跳过原生延迟 Runnable，避免它和面板抢焦点（例如顺手把通知栏拉下来）。
 */
internal object ArkLiveExpandHook {

    private const val TAG = "liveExpand"

    /** 点击收藏后的沉降窗口：这段时间内通知标题仍是点击前的状态，不能采信 */
    private const val FAV_SETTLE_MS = 5_000L
    private const val CTRL_CLS =
        "com.flyme.statusbar.livenotification.LiveNotificationController"
    private const val CENTRAL_CLS = "com.android.systemui.statusbar.phone.CentralSurfacesImpl"
    private const val PANEL_ACTIVITY = "com.flymeark.ArkCapsulePanelActivity"

    /** 点击最长按压时长 */
    private const val TAP_TIMEOUT_MS = 420L

    /** 点击允许的最大位移（dp） */
    private const val TAP_SLOP_DP = 24f

    /** 我们自己处理了点击后，多长时间内屏蔽原生 Runnable */
    private const val NATIVE_SUPPRESS_MS = 800L

    /** 两次展开之间的最小间隔，防止焦点抖动导致连续拉起 */
    private const val PANEL_COOLDOWN_MS = 1_200L

    /** 展开时胶囊淡出时长（与面板「长大」前段重叠，形成连续动作） */
    private const val CAPSULE_FADE_MS = 110L

    /** 收起时胶囊淡回时长 */
    private const val CAPSULE_RESTORE_MS = 170L

    /**
     * 胶囊视图可能挂在这几个字段上（按可靠性排序，命中第一个就停）。
     * 只淡出胶囊本身，不动整个状态栏左侧区域。
     */
    private val CAPSULE_FIELDS = arrayOf(
        "mLiveNotificationDisplay",
        "mLiveNotificationArea"
    )

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    @Volatile
    private var clickHandledAt = 0L

    @Volatile
    private var lastLaunchAt = 0L

    // ---------------- 面板联动状态 ----------------

    /** 面板是否正在显示（由面板广播驱动） */
    @Volatile
    private var panelVisible = false

    /** 当前 LiveNotificationController（弱引用，只用于淡出/恢复胶囊） */
    @Volatile
    private var ctrlRef: WeakReference<Any>? = null

    /** 胶囊容器视图（淡出/恢复直接作用在它上面） */
    @Volatile
    private var capsuleRef: WeakReference<View>? = null

    /** 上次推给面板的曲目 key，用于判断「是否换曲」 */
    @Volatile
    private var lastPushKey: String? = null

    private var lastPushAt = 0L

    /**
     * v3.15.3：pushToPanel 重入锁。
     *
     * v3.15.2 的致命 bug：pushToPanel(changed) → refreshFavFromActiveNotifications()
     * → 结尾 lastPushKey=null 后回推 pushToPanel(changed 必为 true) → 无限互递归，
     * SystemUI 主线程卡死 5s 触发 ANR，随后被系统反复杀死（表现为「上一首/下一首/
     * 暂停全部失效 + 系统界面崩溃」）。这里把推送本身做成不可重入。
     */
    private var pushing = false

    // ---------------- 曲目缓存（由 ArkLiveCoverHook 在每次歌词刷新时写入） ----------------

    @Volatile private var trackTitle: String? = null
    @Volatile private var trackArtist: String? = null
    @Volatile private var trackCover: Bitmap? = null

    /** v3.15：当前歌词通知归属的媒体 App 包名（QQ音乐/网易云），面板图标与跳转、路由都以它为准 */
    @Volatile var currentPkg: String? = null
        private set

    /** v3.13：媒体 App 跳转 PendingIntent（session 读不到时沿用旧值） */
    @Volatile private var lastClickIntent: PendingIntent? = null
    @Volatile private var trackProgress: Int = -1
    @Volatile private var trackDuration: Int = -1
    @Volatile private var trackPlaying: Boolean = false

    // ---------------- 收藏（v3.13.3 / v3.15.1 双向绑定） ----------------

    /** 播放通知上「喜欢」按钮的 PendingIntent（每首歌刷新） */
    @Volatile private var favIntent: PendingIntent? = null

    /**
     * 当前曲目的收藏状态（真值）。null = 所有来源都给不出（面板显示「未定」）。
     * 真值来源优先级：MediaSession metadata extras > 通知按钮标题状态词 > 指纹学习表。
     */
    @Volatile private var favState: Boolean? = null

    /** 收藏按钮的图标指纹（同一 App 的「已收藏/未收藏」通常是两个资源 id） */
    private var favHash: String? = null

    /**
     * v3.15.10：hook 侧缓存的收藏按钮语义（真机证明 `NotificationManager.activeNotifications`
     * 在本机 SystemUI 里拿不到列表，v3.15.8 的「实时扫描」因此永远返回 null，
     * 收藏彻底失效）。改为缓存播放通知里解析到的按钮 —— 通知本身每次进度刷新都会进来。
     */
    @Volatile private var favCachedState: Boolean? = null

    /** 缓存按钮所属通知的标题（用于判断它是不是上一首的按钮） */
    @Volatile private var favCachedSong: String? = null

    /** activeNotifications 取证日志：只在数量变化时打一次 */
    private var activeListLoggedOnce = false
    private var lastActiveListSize = -2

    /**
     * 指纹 → 状态 的自学习表（持久化在 SystemUI 数据目录）。
     *
     * QQ 音乐不给收藏接口、按钮标题固定「收藏」，唯一能区分两态的只有按钮图标。
     * 只要有一次从 metadata/标题拿到真值，就把「当前指纹 = 该真值」记下来，
     * 之后同 App 任意曲目都能按指纹直接判出状态 —— 打开面板即为真实状态。
     */
    private val favMap = HashMap<String, Boolean>()
    private var favMapReady = false

    /** 取证日志去重：每首歌只打一轮 action 列表 */
    private var favLogKey: String? = null

    /** metadata extras 的 key 只打一次（用于真机确认播放器有没有暴露收藏字段） */
    /** metadata 的键只按「播放器」打印一次（QQ音乐 / 网易云 的字段集不同） */
    private val metaLoggedPkgs = HashSet<String>()

    /** USER_RATING 的取值签名，变化时才打日志（取证收藏/取消收藏时它怎么变） */
    private var lastRatingSig: String? = null

    /** QQ 音乐 COLLECT_STATE 的取值签名，变化时才打日志 */
    private var lastCollectSig: String? = null

    /**
     * v3.15.5：点击后的沉降窗口。
     *
     * 用户点 ❤ 之后，播放器要过一会儿才会把新的收藏状态写回 metadata / 通知。
     * 这段窗口里读到的都是「点击前」的旧值，采信它会把刚点亮的 ❤ 又打回去。
     * 换曲时会重置 `lastFavSendAt`，所以窗口不会跨歌。
     */
    private fun inFavSettle(): Boolean =
        SystemClock.uptimeMillis() - lastFavSendAt < FAV_SETTLE_MS

    /**
     * v3.15.16：是否刚点过收藏（供 [ArkLiveLyricHook] 判断「通知重建抖动」）。
     *
     * 真机取证：点收藏/取消会触发播放通知重建，`getNotificationLiveAllowForPackage`
     * 会瞬时返回 raw=false（实测最长 ~15s），旧逻辑据此误判「用户关开关」→ 释放胶囊，
     * 随后 raw 恢复 true 胶囊又出现 —— 就是用户看到的「切歌/收藏导致胶囊重绘」。
     *
     * 收藏点击的沉降窗口（5s）内不采信 raw=false，把这条抖动挡在门控之外。
     */
    fun isInFavSettle(): Boolean = inFavSettle()

    /** 上次 send 收藏 action 的时间（SystemUI 侧防抖） */
    private var lastFavSendAt = 0L

    /** v3.15.16：最近一次换曲的时间（供 [ArkLiveLyricHook] 挡切歌触发的 raw 抖动） */
    @Volatile private var lastTrackChangeAt = 0L

    /** v3.15.16：是否刚换过曲（切歌后 raw 会长时间抖动，见 isInFavSettle 说明） */
    fun isInTrackChangeSettle(): Boolean =
        SystemClock.uptimeMillis() - lastTrackChangeAt < 8_000L

    /** 上次点击的目标状态（日志用） */
    @Volatile private var lastFavTarget: Boolean? = null

    /** refreshFavFromActiveNotifications 重入保护 */
    private var refreshingFav = false

    /** SystemUI 主线程 handler（切歌后短轮询收藏状态用） */
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** 点击闭环：点击前图标指纹 + 本次目标（仅当点击前状态已知时才用于学习） */
    @Volatile private var pendingFavHash: String? = null
    @Volatile private var pendingFavTarget: Boolean? = null
    @Volatile private var pendingFavLearnable = false

    /**
     * v3.15.7：收藏串行泵。
     *
     * 之前用「200ms 内直接丢弃」做节流 —— 这是错的：面板已经把 ❤ 乐观切成目标状态，
     * 而请求被丢弃，音乐端根本没收到，两边就永久错位（用户看到的「连点只有一次生效」）。
     *
     * 现在不再丢弃任何点击：
     *  - [favDesired] 永远保存「用户最后一次想要的状态」，连点 N 次只保留最终值
     *  - [favGen] 是意图代号，旧的校验回调发现自己过期就直接让位给新目标
     *  - 发送前先读真值，已等于目标就**不发**（幂等短路）—— 避免在音乐端的
     *    toggle 限流窗口里反复翻转
     *  - 串行执行：一次发送 + 校验走完才处理下一个目标，绝不并发 toggle
     */
    private var favDesired: Boolean? = null
    private var favBusy = false
    private var favGen = 0
    private var lastFavRecvAt = 0L

    /**
     * 最近一次**真正发出** toggle 的时间；拿到真值确认后清零。
     *
     * 用途：用户连点时，上一次 toggle 可能已经在音乐端翻转、但 metadata 还没刷新。
     * 这时如果直接读真值做幂等短路，会读到「点击前」的旧值并误判成「已经是目标」，
     * 于是漏发 → 最终状态反向错位。所以有未确认发送时先等一个确认窗口。
     */
    private var lastToggleSentAt = 0L

    /**
     * v3.15.9：本次发送的按钮是否**带明确语义**。
     *
     * QQ 音乐按钮标题会翻转（收藏/已收藏），语义已知，校验失败可以安全重发一次。
     * 网易云的 `like` 是纯 toggle、标题恒定 —— 这种情况重发等于把刚做的操作撤销，
     * 所以只允许发一次，失败就认命（以真值为准）。
     */
    private var lastSentSemanticKnown = true

    /** 上一次 toggle 发出后，等它落地的最短时间（给播放器写 metadata 的时间） */
    private const val FAV_CONFIRM_WAIT_MS = 1_200L

    /**
     * v3.15.8：等「通知按钮语义」刷新成可点状态的最长时间。
     *
     * 取证结论：SystemUI 侧点击就是直接 send 通知里的 action，**没有任何节流**
     * （LiveNotificationController$LiveNotificationClickHelper 只做 down/up 判定）。
     * 真正的限制来自按钮语义：QQ 音乐的通知 action 是「状态绑定」的 ——
     * 标题「收藏」= 当前未收藏（点了变已收藏），「已收藏」= 当前已收藏（点了取消）。
     *
     * 所以连点的第二次点击如果用的还是旧 PI，语义就是反的：想取消却再收藏一次。
     * 这正是「连点只生效一次」的真因。正确做法不是加时间锁，而是**等通知重建**。
     * 真机实测 QQ 音乐重建通知约 2.7 秒，这里给 5 秒上限。
     */
    private const val FAV_SEMANTIC_WAIT_MS = 5_000L

    /** 语义等待期间的轮询间隔 */
    private const val FAV_SEMANTIC_STEP_MS = 250L

    /**
     * 换曲时作废在途的收藏泵：旧目标的校验回调读到的是**新歌**的真值，
     * 不清掉会把新歌 ❤ 按上一首的目标写错，并且把新目标挤掉。
     */
    private fun resetFavPump() {
        favGen++
        favDesired = null
        favBusy = false
    }

    private fun loadFavMap(ctx: Context) {
        if (favMapReady) return
        favMapReady = true
        try {
            val sp = ctx.getSharedPreferences("flymeark_fav", Context.MODE_PRIVATE)
            for ((k, v) in sp.all) if (v is Boolean) favMap[k] = v
            XposedBridge.log("$TAG: fav map loaded (${favMap.size} entries)")
        } catch (_: Throwable) {
        }
    }

    private fun rememberFav(ctx: Context, key: String, value: Boolean) {
        favMap[key] = value
        try {
            ctx.getSharedPreferences("flymeark_fav", Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply()
        } catch (_: Throwable) {
        }
    }

    // ---------------- 按曲目记忆收藏状态（v3.15.4） ----------------

    /**
     * 曲目 → 收藏状态 的本地记忆表，key = `pkg#标题`。
     *
     * 为什么需要它：播放器的**通知**才带收藏真值，而通知刷新比切歌慢 1~5 秒
     * （QQ 音乐要在自己的主线程里重建通知）。切歌瞬间我们只能清空状态等通知，
     * 用户看到的就是「明明是收藏的歌，❤ 却要过好几秒才亮」。
     *
     * 这里把每首歌**已知的真值**（来自通知、metadata 或用户点击）按曲目存下来：
     *  · 切歌/打开面板时先查本表 → 立即显示正确状态（0 延迟）
     *  · 通知真值到达后仍然覆盖（用户在 QQ 音乐里改过收藏也能纠正）
     * 只对「从没见过的曲目」保留一次等待，之后永久命中。
     */
    private val trackFavMap = HashMap<String, Boolean>()
    private var trackFavLoaded = false

    private fun trackFavKey(pkg: String?, title: String?): String? {
        val p = pkg?.takeIf { it.isNotBlank() } ?: return null
        val t = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "$p#$t"
    }

    private fun loadTrackFav(ctx: Context) {
        if (trackFavLoaded) return
        trackFavLoaded = true
        try {
            val sp = ctx.getSharedPreferences("flymeark_track_fav", Context.MODE_PRIVATE)
            for ((k, v) in sp.all) if (v is Boolean) trackFavMap[k] = v
            XposedBridge.log("$TAG: track fav loaded (${trackFavMap.size} entries)")
        } catch (_: Throwable) {
        }
    }

    private fun rememberTrackFav(ctx: Context, key: String?, value: Boolean) {
        key ?: return
        if (trackFavMap[key] == value) return
        trackFavMap[key] = value
        try {
            ctx.getSharedPreferences("flymeark_track_fav", Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply()
            XposedBridge.log("$TAG: track fav memo '$key' -> $value")
        } catch (_: Throwable) {
        }
    }

    private fun trackFavOf(ctx: Context, key: String?): Boolean? {
        key ?: return null
        loadTrackFav(ctx)
        return trackFavMap[key]
    }

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != ArkConstants.SYSTEMUI_PKG) return

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing")

        ArkHooks.quiet("$TAG handleOnTouchEvent") {
            XposedHelpers.findAndHookMethod(
                CTRL_CLS, lpp.classLoader, "handleOnTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val consumed = param.result as? Boolean ?: false
                            val ev = param.args[0] as? MotionEvent ?: return
                            val ctrl = param.thisObject
                            ctrlRef = WeakReference(ctrl)

                            when (ev.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    if (!consumed) return
                                    downX = ev.rawX
                                    downY = ev.rawY
                                    downAt = SystemClock.uptimeMillis()
                                }

                                MotionEvent.ACTION_UP -> {
                                    if (!consumed) return
                                    val now = SystemClock.uptimeMillis()
                                    val dt = now - downAt
                                    val ctx = XposedHelpers.getObjectField(ctrl, "mContext")
                                        as? Context
                                    val density = ctx?.resources?.displayMetrics?.density ?: 3f
                                    val slop = density * TAP_SLOP_DP
                                    val dist = hypot(ev.rawX - downX, ev.rawY - downY)

                                    val inCapsule = hitCapsule(ctrl, ev.rawX, ev.rawY)
                                    XposedBridge.log(
                                        "$TAG: UP x=${ev.rawX} y=${ev.rawY} dt=${dt}ms " +
                                            "dist=${dist.toInt()} inCapsule=$inCapsule"
                                    )

                                    if (dt in 1..TAP_TIMEOUT_MS && dist <= slop && inCapsule) {
                                        launchPanel(ctrl, now)
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG touch error: $t")
                        }
                    }
                }
            )
        }

        // 原生 ACTION_UP 后会把 onLiveNotificationClick(null) post 出去；我们已接管，
        // 直接跳过，避免面板刚弹出又被原生逻辑（如展开通知栏）打断。
        ArkHooks.quiet("$TAG onLiveNotificationClick") {
            XposedHelpers.findAndHookMethod(
                CENTRAL_CLS, lpp.classLoader, "onLiveNotificationClick",
                "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val delta = SystemClock.uptimeMillis() - clickHandledAt
                        if (clickHandledAt > 0 && delta < NATIVE_SUPPRESS_MS) {
                            param.result = null
                        }
                    }
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // 曲目信息（由 ArkLiveCoverHook 调）
    // ------------------------------------------------------------------

    fun onTrackInfo(
        ctx: Context?,
        sbn: Any,
        notif: Notification,
        cover: Bitmap?,
        lyric: String?
    ) {
        try {
            val ex = notif.extras
            val t = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            val a = (ex?.getCharSequence(Notification.EXTRA_TEXT)
                ?: ex?.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim()

            trackTitle = t?.takeIf { it.isNotEmpty() } ?: lyric
            trackArtist = a
            if (cover != null) trackCover = cover
            // v3.15：歌词通知归属包名 = 当前媒体 App
            currentPkg = try {
                XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: currentPkg
            } catch (_: Throwable) {
                currentPkg
            }

            val p = ex?.getInt(Notification.EXTRA_PROGRESS, -1) ?: -1
            val max = ex?.getInt(Notification.EXTRA_PROGRESS_MAX, -1) ?: -1
            trackProgress = p
            trackDuration = if (max > 0) max else -1

            trackPlaying = (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0

            inspectFavoriteActions(notif, ctx)
            pushToPanel(ctx)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    // 面板联动：淡出原胶囊 + 实时推送曲目
    // ------------------------------------------------------------------

    /**
     * 面板可见性回调（由 SystemUILiveHost 收到面板广播后转到 SystemUI 主线程调用）。
     * 展开时把胶囊淡到全透明，视觉上像是胶囊本身向下展开；收起时恢复。
     */
    fun setPanelVisible(visible: Boolean) {
        panelVisible = visible
        if (visible) lastPushKey = null // 重新打开时强制推一次完整信息（含封面）
        val ctrl = ctrlRef?.get()
        if (ctrl == null) {
            XposedBridge.log("$TAG: panel visible=$visible (no controller yet)")
            return
        }
        applyCapsuleAlpha(ctrl, if (visible) 0f else 1f)
        XposedBridge.log("$TAG: panel visible=$visible")
    }

    /**
     * 歌词每行刷新都会重设视图，原生可能把 alpha 动画拉回来；
     * 面板开着时在每次渲染后重新压成 0，保证胶囊不会「闪回来」。
     * 只动容器视图（见 [capsuleRef]），子视图的 alpha 由容器统一乘算。
     */
    fun onLiveViewUpdated() {
        if (!panelVisible) return
        val v = capsuleRef?.get() ?: return
        try {
            // 淡出动画还在跑（animation != null）时不要硬压成 0，否则会「闪一下再消失」；
            // 只有原生刷新把 alpha 直接拉回来（没有动画在跑）时才需要再压一次。
            if (v.animation == null && v.alpha > 0.05f) {
                v.alpha = 0f
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 面板展开/收起时同步胶囊的透明度。
     *
     * v3.2：不再「瞬间消失」。胶囊用 110ms 淡出，和面板从胶囊矩形长大的前段重叠，
     * 视觉上是一个连续动作（胶囊一边长成卡片、一边退场），而不是先闪一下再冒出个卡片。
     * 收起时 170ms 淡回，等面板缩回胶囊位置后再出现。
     */
    private fun applyCapsuleAlpha(ctrl: Any, alpha: Float) {
        for (name in CAPSULE_FIELDS) {
            val v = try {
                XposedHelpers.getObjectField(ctrl, name) as? View
            } catch (_: Throwable) {
                null
            } ?: continue
            capsuleRef = WeakReference(v)
            v.animate().cancel()
            if (alpha >= 1f) {
                v.animate().alpha(1f).setDuration(CAPSULE_RESTORE_MS).start()
            } else {
                v.animate().alpha(0f).setDuration(CAPSULE_FADE_MS).start()
            }
            XposedBridge.log("$TAG: capsule[$name] alpha=$alpha")
            return
        }
        XposedBridge.log("$TAG: capsule view not found for alpha=$alpha")
    }

    /**
     * 面板开着时把当前曲目推过去：
     *  - 换曲 → 立刻推（带封面）
     *  - 同曲 → 最多每秒推一次，用来对齐进度（拖动进度条后也能纠正）
     */
    private fun pushToPanel(ctx: Context?, force: Boolean = false, favFinal: Boolean = false) {
        if (!panelVisible) return
        val c = ctx ?: return
        // v3.15.3：重入锁必须在最前面 —— 下面的 refreshFavFromActiveNotifications
        // 会回调回来，没有它就会无限互递归（见 pushing 字段注释）。
        if (pushing) return
        val title = trackTitle?.takeIf { it.isNotBlank() } ?: return
        val artist = trackArtist.orEmpty()
        val key = "$title|$artist"
        val now = SystemClock.uptimeMillis()
        // 曲目变化 → 必须让面板换封面、重查收藏；强制推送（收藏轮询）只是刷新同一首
        val trackChanged = key != lastPushKey
        val changed = force || trackChanged
        if (!changed && now - lastPushAt < 1_000L) return
        lastPushKey = key
        lastPushAt = now

        pushing = true
        try {
            val session = readMediaSession(c)
            // v3.15.1：换曲后旧曲的收藏状态不能继承；随后用 metadata / 通知真值覆盖
            if (trackChanged) {
                lastTrackChangeAt = now
                favHash = null
                // v3.15.4：换曲作废「点击后沉降窗口」与点击闭环 —— 那是上一首的语境，
                // 不清掉会让新歌的标题真值被静默 5 秒、❤ 白等
                lastFavSendAt = 0L
                pendingFavTarget = null
                pendingFavHash = null
                // v3.15.7：作废在途的收藏泵（旧校验会拿新歌真值比对）
                resetFavPump()
                // v3.15.5：favIntent 是「通知上的收藏按钮」，换曲后它仍指向上一首。
                // 不清掉的话，新歌通知还没刷出来时点 ❤ 会去取消收藏**上一首**。
                // 清空后由 refresh 在「标题匹配当前曲」时重新填充，宁可短暂无响应也不能误操作。
                favIntent = null
                // v3.15.4：先用「按曲目记忆」立刻给出状态（这首听过就 0 延迟），
                // 再用通知真值校正 —— 通知要 1~5 秒才刷到新歌，不能让用户干等
                favState = trackFavOf(c, trackFavKey(currentPkg, title))
                refreshFavFromActiveNotifications(c)
            }
            session?.fav?.let { truth ->
                // v3.15.5：点击后的沉降窗口内不采信（读到的是点击前的旧值）
                if (inFavSettle()) return@let
                favState = truth
                loadFavMap(c)
                favHash?.let { h -> learnFav(c, h, truth) }
            }
            val progress = session?.position?.takeIf { it >= 0 }?.toInt() ?: trackProgress
            val duration = session?.duration?.takeIf { it > 0 }?.toInt() ?: trackDuration
            val playing = session?.playing ?: trackPlaying
            session?.clickIntent?.let { lastClickIntent = it }

            // v3.15.6：封面优先取 MediaSession 的 ALBUM_ART。
            // trackCover 是歌词通知里缓存的 largeIcon，播放器重发通知才更新
            // （网易云尤其慢），切歌时面板会先显示上一首的封面。
            val cover = if (changed) {
                session?.art?.let { scaleArt(it) } ?: trackCover
            } else {
                null
            }
            if (cover != null) trackCover = cover

            sendTrack(
                c, title, artist, progress, duration, playing,
                cover, lastClickIntent, favFinal
            )
            if (changed) {
                XposedBridge.log("$TAG: push track '$title' pos=$progress/$duration playing=$playing")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: pushToPanel failed: ${t.message}")
        } finally {
            pushing = false
        }
    }

    /**
     * 面板按下上一首/下一首后主动来要一次同步：直接从 MediaSession 读
     * 标题 / 歌手 / 专辑图 / 进度，不等歌词通知刷新，换曲延迟从 1~3 秒降到毫秒级。
     */
    fun syncNow(ctx: Context?) {
        val c = ctx ?: return
        val s = readMediaSession(c) ?: run {
            XposedBridge.log("$TAG: sync skipped (no media session)")
            return
        }
        val title = s.title?.takeIf { it.isNotBlank() } ?: trackTitle?.takeIf { it.isNotBlank() }
            ?: run {
                XposedBridge.log("$TAG: sync skipped (no title)")
                return
            }
        val artist = s.artist?.takeIf { it.isNotBlank() } ?: trackArtist.orEmpty()
        val cover = s.art?.let { scaleArt(it) } ?: trackCover
        val progress = s.position.takeIf { it >= 0 }?.toInt() ?: trackProgress
        val duration = s.duration.takeIf { it > 0 }?.toInt() ?: trackDuration
        s.clickIntent?.let { lastClickIntent = it }
        // v3.15.1：同步收藏真值（换曲时旧值作废）
        val trackChanged = s.title != null && s.title != trackTitle
        if (trackChanged) {
            lastTrackChangeAt = SystemClock.uptimeMillis()
            favHash = null
            // v3.15.4：换曲作废点击沉降窗口/闭环（上一首的语境）
            lastFavSendAt = 0L
            pendingFavTarget = null
            pendingFavHash = null
            // v3.15.7：作废在途的收藏泵
            resetFavPump()
            // v3.15.5：同 pushToPanel —— 旧通知的收藏按钮不能留给新曲用
            favIntent = null
            // v3.15.4：session 没给收藏时用「按曲目记忆」顶上，避免面板先显示未定再点亮
            favState = s.fav ?: trackFavOf(c, trackFavKey(currentPkg, title))
        } else if (!inFavSettle()) {
            s.fav?.let { favState = it }
        }

        trackTitle = title
        if (trackChanged) pollFavAfterTrackChange(c)
        trackArtist = artist
        if (cover != null) trackCover = cover
        lastPushKey = "$title|$artist"
        lastPushAt = SystemClock.uptimeMillis()

        sendTrack(c, title, artist, progress, duration, s.playing, cover, lastClickIntent)
        XposedBridge.log("$TAG: sync '$title' pos=$progress/$duration playing=${s.playing}")
    }

    private fun sendTrack(
        ctx: Context,
        title: String,
        artist: String,
        progress: Int,
        duration: Int,
        playing: Boolean,
        cover: Bitmap?,
        clickIntent: PendingIntent? = null,
        favFinal: Boolean = false
    ) {
        val intent = Intent(ArkConstants.ACTION_PANEL_TRACK).apply {
            setPackage(ArkConstants.APP_PKG)
            putExtra(ArkConstants.EXTRA_PANEL_TITLE, title)
            putExtra(ArkConstants.EXTRA_PANEL_ARTIST, artist)
            putExtra(ArkConstants.EXTRA_PANEL_PROGRESS, progress)
            putExtra(ArkConstants.EXTRA_PANEL_DURATION, duration)
            putExtra(ArkConstants.EXTRA_PANEL_PLAYING, playing)
            // v3.13.1：媒体卡玻璃的深浅判断源就在 SystemUI，面板配色必须同源
            putExtra(ArkConstants.EXTRA_PANEL_NIGHT, isNightMode(ctx))
            // v3.13.3：收藏状态（null = 通知按钮不带状态词，推不出）
            favState?.let { putExtra(ArkConstants.EXTRA_PANEL_FAV, it) }
            // v3.15.7：这是点击后的确认结论，面板应无视推送锁直接采信
            if (favFinal) putExtra(ArkConstants.EXTRA_PANEL_FAV_FINAL, true)
            // v3.15：当前媒体 App 包名
            currentPkg?.let { putExtra(ArkConstants.EXTRA_PANEL_APP_PKG, it) }
            cover?.let { putExtra(ArkConstants.EXTRA_PANEL_COVER, it) }
            clickIntent?.let { putExtra(ArkConstants.EXTRA_PANEL_CLICK_INTENT, it) }
        }
        try {
            ctx.sendBroadcast(intent)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: push track failed: ${t.message}")
        }
    }

    /**
     * v3.15：选当前媒体 App 的 MediaController。
     * 优先级：歌词通知归属 App（currentPkg）> 正在播放的受支持 App > 第一个受支持 App。
     */
    private fun activeController(ctx: Context): MediaController? = try {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val sessions = msm?.getActiveSessions(null)?.filter { ArkConstants.isMediaApp(it.packageName) }
        if (sessions.isNullOrEmpty()) {
            null
        } else {
            val cur = currentPkg
            sessions.firstOrNull { it.packageName == cur }
                ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.first()
        }
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: activeController failed: ${t.message}")
        null
    }

    /**
     * v3.13：面板拖动进度条 → seekTo。
     * MediaController.seekTo 需要 system uid（MEDIA_CONTENT_CONTROL），
     * 面板是普通 App uid，统一在这里代派。
     */
    fun seekTo(ctx: Context?, positionMs: Long) {
        val c = ctx ?: return
        if (positionMs < 0) return
        try {
            val ctrl = activeController(c)
            if (ctrl == null) {
                XposedBridge.log("$TAG: seek skipped (no media session)")
                return
            }
            ctrl.transportControls.seekTo(positionMs)
            XposedBridge.log("$TAG: seekTo $positionMs (${ctrl.packageName})")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: seekTo failed: ${t.message}")
        }
    }

    /**
     * v3.13.2：面板播放/暂停 → 显式 play()/pause()。
     *
     * 为什么不用媒体键：KEYCODE_MEDIA_PLAY_PAUSE 是 toggle 语义，快速连点两次会被
     * 播放器（QQ 音乐线控双击下一首）/系统媒体键分发层判成「双击 → 下一首」，
     * 用户实测「频繁点暂停就切歌」正是这个原因。改成明确目标状态后：
     *  · 连点暂停 = 多次 pause()，幂等，不会切歌
     *  · 连点切换 = play()/pause() 交替，各按各的生效，不会互相抵消
     * 失败（session 未就绪 / 不支持）时回落到媒体键，保证按钮不会完全失效。
     */
    fun setPlayback(ctx: Context?, playing: Boolean) {
        val c = ctx ?: return
        try {
            val ctrl = activeController(c)
            if (ctrl != null) {
                if (playing) ctrl.transportControls.play() else ctrl.transportControls.pause()
                XposedBridge.log("$TAG: playback -> $playing (transportControls ${ctrl.packageName})")
                return
            }
            XposedBridge.log("$TAG: playback fallback to media key (no session)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: setPlayback failed: ${t.message}")
        }
        try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val code = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        } catch (_: Throwable) {
        }
    }

    /**
     * v3.13.3：枚举 QQ 音乐播放通知的 action 按钮，找「喜欢/收藏」。
     *
     * MediaSession 帮不上忙（custom actions=[]、rating type=0，见取证），但它的
     * 播放通知挂了 5 个 action 按钮，其中通常有收藏；action 的 PendingIntent
     * 谁拿到都能 send()，正好借 SystemUI 进程代触发。
     *
     * 每首歌只打一轮全量日志（取证用），之后只做静默匹配。
     *
     * 状态真值（v3.15.1 真机日志确认）：QQ 音乐按钮标题会在「收藏」/「已收藏」之间切换，
     * 所以标题本身就是真值 —— 「已…」= 已收藏，「喜欢/收藏」= 未收藏。
     * 唯一坑是它滞后 1~5 秒才随通知刷新，因此点击后的沉降窗口内不采信标题。
     */
    private fun inspectFavoriteActions(notif: Notification, ctx: Context?) {
        val acts = notif.actions
        if (acts.isNullOrEmpty()) return
        ctx?.let { loadFavMap(it) }
        val key = notif.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (key != null && key != favLogKey) {
            favLogKey = key
            acts.forEachIndexed { i, a ->
                XposedBridge.log(
                    "$TAG: notif action[$i] title='${a.title}' icon=${a.icon}"
                )
            }
        }
        var found: PendingIntent? = null
        var titleState: Boolean? = null
        var hit: Notification.Action? = null
        for (a in acts) {
            val t = a.title?.toString()?.trim() ?: continue
            // 中文（QQ 音乐：收藏/已收藏）与英文（网易云：like）都要认
            when (favTitleState(t)) {
                true -> {
                    found = a.actionIntent
                    titleState = true
                    hit = a
                    break
                }
                false -> {
                    found = a.actionIntent
                    titleState = false
                    hit = a
                }
                null -> if (found == null && isFavTitleLike(t)) {
                    found = a.actionIntent
                    hit = a
                }
            }
        }
        if (found == null) return
        favIntent = found
        // v3.15.10：缓存按钮语义与所属曲目 —— 本机拿不到 activeNotifications，
        // 点击时只能靠这份缓存；通知每次进度刷新都会重走这里，所以它会跟着更新。
        favCachedState = titleState
        favCachedSong = key

        // 图标指纹：同一 App 的「已收藏/未收藏」通常是两个资源 id
        val hash = hit?.let { "${currentPkg ?: "?"}#${it.icon}" }
        if (hash != null && hash != favHash) {
            favHash = hash
            XposedBridge.log("$TAG: fav fingerprint=$hash (title=$titleState)")
        }

        // 点击闭环：图标指纹变了说明翻转确实发生，新指纹对应的就是本次目标状态
        val pt = pendingFavTarget
        val ph = pendingFavHash
        if (pt != null && hash != null && hash != ph) {
            if (pendingFavLearnable && ctx != null) learnFav(ctx, hash, pt)
            XposedBridge.log(
                "$TAG: fav click closed (${ph}->$hash target=$pt learn=${pendingFavLearnable})"
            )
            pendingFavTarget = null
            pendingFavHash = null
        }

        // 点击后的沉降窗口内不采信标题（标题更新滞后，这一轮反映的是点击前状态）
        val settle = SystemClock.uptimeMillis() - lastFavSendAt < FAV_SETTLE_MS
        if (settle) {
            XposedBridge.log("$TAG: fav title ignored in settle window (title=$titleState)")
            return
        }

        val learned = hash?.let { favMap[it] }
        val state = titleState ?: learned
        // v3.15.15：网易云 `like` 标题不带状态词（titleState=null），但它的图标会
        // 4047↔8763 翻转，指纹表一旦学到 icon→状态 映射，就把 learned 回填到
        // favCachedState。这样下次点击时 favoriteActionFor 返回的按钮 state 不再是 null，
        // 走「状态绑定」路径（可安全重发、可等通知重建），而不是每次都退回
        // 「toggle 只发一次」—— 后者在校验窗口内等不到网易云写回 COLLECT_STATE。
        if (favCachedState == null) favCachedState = learned
        // 网易云的 `like` 不带状态词，state 可能仍是 null —— 不能把已知状态清掉
        if (state != null && state != favState) {
            favState = state
            XposedBridge.log("$TAG: favorite state=$state (title=$titleState learned=$learned)")
        }

        // 只有拿到真值才写入学习表，避免「方向猜反」把整张表污染
        if (titleState != null && hash != null && ctx != null) learnFav(ctx, hash, titleState)
        // v3.15.4：真值同时按曲目记账 —— 下次切到这首无需再等通知刷新
        if (titleState != null && ctx != null) {
            val nt = notif.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            rememberTrackFav(ctx, trackFavKey(currentPkg, nt), titleState)
        }
    }

    /**
     * v3.15.1：打开面板前主动查一次在播通知，立刻拿到最新收藏状态。
     *
     * 通知刷新有 1~5 秒延迟，等推送会让面板先显示「未定」。SystemUI 持有通知访问权，
     * 可以直接读 activeNotifications 里的 action 标题，把真值同步上。
     */
    private fun refreshFavFromActiveNotifications(ctx: Context?) {
        val c = ctx ?: return
        if (refreshingFav) return   // pushToPanel(changed) 会回调本函数，防重入
        refreshingFav = true
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val list = nm?.activeNotifications
            if (list != null) for (sbn in list) {
                val pkg = sbn?.packageName ?: continue
                if (!ArkConstants.isMediaApp(pkg)) continue
                val n = sbn.notification ?: continue
                if (n.actions.isNullOrEmpty()) continue
                // 通知可能还是上一首的（切歌后 1~5 秒才刷新）：标题对不上就跳过，
                // 否则会把上一首的收藏状态错安到新曲上。
                val nt = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
                val cur = trackTitle?.trim()
                if (!nt.isNullOrEmpty() && !cur.isNullOrEmpty() &&
                    !nt.contains(cur) && !cur.contains(nt)
                ) {
                    XposedBridge.log("$TAG: fav refresh skipped (notif='$nt' cur='$cur')")
                    continue
                }
                if (pkg != currentPkg) currentPkg = pkg
                inspectFavoriteActions(n, c)
                XposedBridge.log("$TAG: fav refreshed from active notif pkg=$pkg state=$favState")
                break
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: active notif query failed: ${t.message}")
        } finally {
            refreshingFav = false
        }
        // v3.15.3：这里**不能**再回推面板。
        // v3.15.2 在这里写 pushToPanel(c) 并把 lastPushKey 清空，等于强制 changed=true，
        // 而 pushToPanel(changed) 又会回调本函数 → 无限互递归 → SystemUI 主线程 ANR 崩溃。
        // 现在本函数只负责刷新状态，推送由调用方按需发起（pushToPanel 顺带、轮询后显式 force 推）。
    }

    /**
     * v3.15.1：切歌后短轮询收藏状态。
     *
     * 播放器通知刷新滞后 1~5 秒，切歌瞬间查到的往往还是旧通知。这里按
     * 250/700/1500/2500ms 查几次，谁先对上曲目标题就用谁，把「延迟切状态」压到最短。
     */
    private fun pollFavAfterTrackChange(ctx: Context?) {
        val c = ctx ?: return
        val title = trackTitle ?: return
        listOf(250L, 700L, 1_500L, 2_500L, 4_000L).forEach { delay ->
            mainHandler.postDelayed({
                if (trackTitle != title) return@postDelayed   // 又换曲了，交给新一轮轮询
                // v3.15.5：先读 metadata（COLLECT_STATE，随切歌即时更新），再查通知兜底
                if (!inFavSettle()) readFavFromSession(c)?.let { favState = it }
                refreshFavFromActiveNotifications(c)
                // v3.15.3：refresh 不再自己回推（那会无限递归），由这里显式推一次
                if (panelVisible) pushToPanel(c, force = true)
            }, delay)
        }
    }

    /**
     * 学到真值 → 写入指纹表（v3.15.1）。
     *
     * 同一 App 的图标语义是全局一致的：若本次真值与已记录的映射相反，
     * 说明之前的映射方向反了，把该 App 的整张表取反重写（自动纠错）。
     */
    private fun learnFav(ctx: Context, hash: String, truth: Boolean) {
        val pkg = hash.substringBefore('#')
        val known = favMap[hash]
        if (known != null && known != truth) {
            val keys = favMap.keys.filter { it.substringBefore('#') == pkg }
            val snapshot = keys.mapNotNull { k -> favMap[k]?.let { k to it } }
            snapshot.forEach { (k, v) -> rememberFav(ctx, k, !v) }
            XposedBridge.log("$TAG: fav map flipped for $pkg (${snapshot.size} entries, truth=$truth)")
        }
        if (favMap[hash] != truth) {
            rememberFav(ctx, hash, truth)
            XposedBridge.log("$TAG: fav learn '$hash' -> $truth")
        }
    }

    /**
     * 从 MediaSession 的 metadata extras 里找收藏字段（v3.15.1）。
     *
     * 播放器常把业务状态塞进 metadata.extras（key 五花八门）。这里只认
     * 含 fav/favourite/favorite/collect/liked 的键，且值必须是可解释的
     * Boolean/数字/字符串；找不到返回 null，不影响其他来源。
     */
    /**
     * 取 MediaMetadata 的原始 Bundle。
     *
     * v3.15.5：`getExtras()` 是 hidden/@SystemApi，公开 stub 里没有，反射调用会抛异常
     * （上一版因此一直没打出 key）。`mBundle` 才是权威来源 —— 标准字段和播放器自塞的
     * extras 都在里面。
     */
    private fun metaBundle(md: MediaMetadata?): android.os.Bundle? {
        md ?: return null
        try {
            val ex = XposedHelpers.callMethod(md, "getExtras") as? android.os.Bundle
            if (ex != null) return ex
        } catch (_: Throwable) {
        }
        return try {
            XposedHelpers.getObjectField(md, "mBundle") as? android.os.Bundle
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * v3.15.5：从 `METADATA_KEY_USER_RATING` 读收藏真值 —— **最优先的真值源**。
     *
     * 真机取证（metadata bundle dump）发现 QQ 音乐把「喜欢」写在这个标准字段里：
     * `android.media.metadata.USER_RATING:Rating`。
     *
     * 相比「通知按钮标题」这条路，metadata 是**随切歌即时更新**的（MediaSession 的
     * setMetadata 与切歌同一个动作），而通知要重建 RemoteViews、要 1~5 秒。
     * 这就是系统媒体卡没有延迟的原因 —— 它读的也是 metadata。
     */
    private fun favFromRating(md: MediaMetadata?): Boolean? {
        md ?: return null
        val r = try {
            md.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
        } catch (_: Throwable) {
            null
        } ?: return null
        val style = r.ratingStyle
        val state = when (style) {
            Rating.RATING_HEART -> r.hasHeart()
            Rating.RATING_THUMB_UP_DOWN -> r.isThumbUp
            Rating.RATING_3_STARS, Rating.RATING_4_STARS, Rating.RATING_5_STARS ->
                r.starRating > 0f
            Rating.RATING_PERCENTAGE -> r.percentRating > 0f
            else -> null
        }
        val raw = when (style) {
            Rating.RATING_HEART -> "heart=${r.hasHeart()}"
            Rating.RATING_THUMB_UP_DOWN -> "thumb=${r.isThumbUp}"
            Rating.RATING_PERCENTAGE -> "percent=${r.percentRating}"
            Rating.RATING_3_STARS, Rating.RATING_4_STARS, Rating.RATING_5_STARS ->
                "star=${r.starRating}"
            else -> "unsupported"
        }
        val sig = "style=$style $raw"
        if (sig != lastRatingSig) {
            lastRatingSig = sig
            XposedBridge.log("$TAG: USER_RATING $sig -> $state")
        }
        return state
    }

    private fun favFromMetadata(md: MediaMetadata?): Boolean? {
        val ex = metaBundle(md) ?: return null
        if (metaLoggedPkgs.add(currentPkg ?: "?")) {
            val dump = ex.keySet().joinToString("; ") { k ->
                "$k:${ex.get(k)?.javaClass?.simpleName}"
            }
            XposedBridge.log("$TAG: metadata bundle [${currentPkg}] (${ex.size()}): $dump")
        }
        // ① QQ 音乐（UCAR 内核）明确暴露的收藏位：ucar.media.metadata.COLLECT_STATE = "1"/"0"
        //    真机取证 2026-11-04 17:35：这条与 QQ 音乐界面的 ❤ 完全一致，且随切歌即时更新
        val collect = try {
            ex.getString("ucar.media.metadata.COLLECT_STATE")
        } catch (_: Throwable) {
            null
        }
        if (collect != null) {
            val state = collect == "1" || collect.equals("true", true)
            val sig = "COLLECT_STATE=$collect"
            if (sig != lastCollectSig) {
                lastCollectSig = sig
                XposedBridge.log("$TAG: fav from $sig -> $state")
            }
            return state
        }
        // ② 标准 USER_RATING（其它播放器可能用这个表达「喜欢」）
        favFromRating(md)?.let { return it }
        // ③ 兜底：extras 里任何像收藏的键
        for (k in ex.keySet()) {
            val lk = k.lowercase()
            val hit = lk.contains("favor") || lk.contains("collect") ||
                lk.contains("liked") || lk.endsWith(".like") || lk == "like"
            if (!hit) continue
            val v = ex.get(k)
            val state = when (v) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                is String -> v == "1" || v.equals("true", true)
                else -> null
            }
            if (state != null) {
                XposedBridge.log("$TAG: fav from metadata '$k'=$v -> $state")
                return state
            }
        }
        return null
    }

    /**
     * 面板点 ❤ → 触发通知上的收藏按钮（PendingIntent 是播放器自己的，send 无需权限）。
     *
     * v3.15.7：不再按时间丢弃请求。目标状态记进 [favDesired]，由 [pumpFav] 串行落地。
     * 这样「连点 5 次」不会变成「只生效 1 次」，也不会在音乐端的 toggle 限流里翻转。
     */
    fun toggleFavorite(ctx: Context?, target: Boolean) {
        val c = ctx ?: return
        val now = SystemClock.uptimeMillis()
        // 只挡同一物理点击产生的重复广播（<150ms 且目标相同），不做语义丢弃
        if (target == favDesired && now - lastFavRecvAt < 150L) {
            XposedBridge.log("$TAG: favorite duplicate ignored")
            return
        }
        lastFavRecvAt = now
        favDesired = target
        favGen++
        // 乐观回显 + 按曲目记账，最终以真值校正
        lastFavSendAt = now
        favState = target
        rememberTrackFav(c, trackFavKey(currentPkg, trackTitle), target)
        XposedBridge.log("$TAG: favorite request target=$target gen=$favGen busy=$favBusy")
        if (panelVisible) pushToPanel(c, force = true)
        if (!favBusy) pumpFav(c)
    }

    /** 取当前目标并尝试落地；[favBusy] 保证同一时刻只有一条发送/校验链 */
    private fun pumpFav(ctx: Context) {
        val target = favDesired
        if (target == null) {
            favBusy = false
            return
        }
        if (favBusy) return
        favBusy = true
        val gen = favGen
        // 上一次 toggle 还没确认落地：先等它写进 metadata，再判断"是否已经等于目标"，
        // 否则读到的是点击前的旧值，会漏发（音乐端其实已经翻转了）
        val sinceSend = SystemClock.uptimeMillis() - lastToggleSentAt
        if (lastToggleSentAt > 0L && sinceSend < FAV_CONFIRM_WAIT_MS) {
            mainHandler.postDelayed({
                favBusy = false
                pumpFav(ctx)
            }, FAV_CONFIRM_WAIT_MS - sinceSend)
            return
        }
        // 幂等短路：真值已经是目标（连点偶数次会回到原状态），根本不需要 toggle。
        // 既省掉一次音乐端操作，也避开它的 toggle 限流。
        val actual = readFavFromSession(ctx)
        if (actual == target) {
            XposedBridge.log("$TAG: fav already=$target, skip send")
            finishFav(ctx, gen, target)
            return
        }
        sendFavAction(ctx, target, gen, attempt = 1)
    }

    /**
     * v3.15.6：发送收藏动作，并用 metadata 的 COLLECT_STATE 做**闭环校验**。
     *
     * 为什么需要：播放器的收藏按钮挂在通知上，PendingIntent 可能因爲通知未重建而指向
     * 上一个动作，send 出去等于「什么也没发生」。以前只能盲信，用户看到的就是
     * 「点了没反应 / 有概率失败」。
     *
     * 现在：发完 1.2s 读一次真值，没变再等 0.8s 读一次，仍然没变才重发（最多重试 1 次）。
     * 真值读不到（null）就放弃，交给通知/记忆表兜底 —— 绝不盲目反复发送导致来回翻转。
     */
    /** 收藏按钮：PendingIntent + 按钮当前状态（null = 标题不带状态词，方向不可知） + 来源包名 */
    private class FavButton(
        val pi: PendingIntent,
        val state: Boolean?,
        val pkg: String
    )

    /**
     * 从在播通知里取收藏按钮。
     *
     * 真机取证（2026-11-04 18:04）两家按钮标题完全不同：
     *  - QQ 音乐：中文、**状态绑定** ——「收藏」（=未收藏）/「已收藏」（=取消收藏）
     *  - 网易云：  英文、**恒定** —— `like` 一直是 `like`，标题不随状态变
     *
     * v3.15.8 只认中文，网易云必然匹配不到（日志 `favorite skipped (no like action)`），
     * 且候选被 `currentPkg` 死绑 —— 歌词通知归属与正在播的 App 不一致时，QQ 音乐也会全被过滤掉。
     *
     * 现在：候选放开到所有受支持 App 的通知，按「标题匹配当前曲 > currentPkg > 其余」排序；
     * 标题语义也支持英文；语义不可知时返回 state = null，由调用方直接发送 + 真值校验。
     */
    private fun favoriteActionFor(ctx: Context, title: String): FavButton? {
        val cur = title.trim()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val list = try {
            nm?.activeNotifications
        } catch (_: Throwable) {
            null
        }
        if (activeListLoggedOnce.not() || (list?.size ?: -1) != lastActiveListSize) {
            activeListLoggedOnce = true
            lastActiveListSize = list?.size ?: -1
            XposedBridge.log("$TAG: activeNotifications size=${list?.size ?: "null"}")
        }
        val media = list?.filter { ArkConstants.isMediaApp(it.packageName) }.orEmpty()
        if (media.isNotEmpty()) {
            val strict = if (cur.isEmpty()) emptyList() else media.filter { sbn ->
                val nt = sbn.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)
                    ?.toString()?.trim().orEmpty()
                nt.isNotEmpty() && (nt.contains(cur) || cur.contains(nt))
            }
            val preferred = currentPkg?.let { p -> media.filter { it.packageName == p } }
                ?: emptyList()
            for (group in listOf(strict, preferred, media)) {
                for (sbn in group) {
                    val btn = favButtonIn(sbn) ?: continue
                    XposedBridge.log("$TAG: fav button from active notif ${btn.pkg}")
                    return btn
                }
            }
        }

        // ② 回落到 hook 缓存（本机唯一可用的路径）
        val pi = favIntent ?: return null
        val cachedSong = favCachedSong
        if (!cachedSong.isNullOrEmpty() && cur.isNotEmpty() &&
            !cachedSong.contains(cur) && !cur.contains(cachedSong)
        ) {
            // 缓存按钮还是上一首的，点下去会改错歌 —— 宁可这次无响应
            XposedBridge.log("$TAG: cached fav button is for '$cachedSong' (cur='$cur')")
            return null
        }
        return FavButton(pi, favCachedState, currentPkg ?: "?")
    }

    /** 在单条通知里找收藏按钮；优先带状态词的，再退化为泛指（网易云 `like`） */
    private fun favButtonIn(sbn: android.service.notification.StatusBarNotification): FavButton? {
        val acts = sbn.notification?.actions ?: return null
        var generic: FavButton? = null
        for (a in acts) {
            val t = a.title?.toString()?.trim().orEmpty()
            val pi = a.actionIntent ?: continue
            if (t.isEmpty()) continue
            val state = favTitleState(t)
            when (state) {
                true -> return FavButton(pi, true, sbn.packageName)
                false -> if (generic == null) generic = FavButton(pi, false, sbn.packageName)
                // 可能是英文泛指 like：只当候选，继续找带明确状态的
                null -> {
                    if (!isFavTitleLike(t)) continue
                    if (generic == null) generic = FavButton(pi, null, sbn.packageName)
                }
            }
        }
        return generic
    }

    /**
     * 标题 → 收藏状态：true = 已收藏（点它=取消），false = 未收藏（点它=收藏），null = 不确定。
     *
     * 关键区分：中文「收藏/喜欢」是状态词（明确=未收藏）；英文单独的 `like`/`favorite`/`collect`
     * 在网易云这类通知里是**纯 toggle**（标题恒定、切歌不翻转、无「已 like」对应物），
     * 方向不可判断 —— 必须返回 null，让发送走「直接 send + metadata 真值校验」的 toggle 路径，
     * 而不是误判成 false 后卡进语义等待死循环（v3.15.13 实测网易云「能读状态但点不动」即此因）。
     */
    private fun favTitleState(t: String): Boolean? {
        val s = t.lowercase()
        // 「已…」「取消…」都表示当前已收藏
        if (t.contains("已喜欢") || t.contains("已收藏") ||
            t.contains("取消喜欢") || t.contains("取消收藏")
        ) return true
        if (s.contains("liked") || s.contains("favorited") || s.contains("favourited") ||
            s.contains("unlike") || s.contains("unfavorite") || s.contains("uncollect") ||
            s.contains("remove from") || s.contains("added")
        ) return true
        // 中文状态词：泛指「收藏/喜欢」= 当前未收藏（QQ 音乐「收藏」↔「已收藏」会翻转，语义明确）
        if (t.contains("喜欢") || t.contains("收藏")) return false
        // 英文单独泛指词（like/favorite/collect 等）语义不可知 → null，交由 toggle 路径处理
        return null
    }

    /** 是否像一个「收藏」类按钮（用于状态未知的候选识别） */
    private fun isFavTitleLike(t: String): Boolean {
        val s = t.lowercase()
        if (s.contains("dislike")) return false
        return s == "like" || s == "liked" ||
            s.contains("favorite") || s.contains("favourite") ||
            s.contains("collect") || s.contains("like song")
    }

    private fun sendFavAction(
        ctx: Context,
        target: Boolean,
        gen: Int,
        attempt: Int,
        waited: Long = 0L,
        noBtnWait: Long = 0L
    ) {
        // 目标已被新点击覆盖 → 立刻让位，别把过期目标打出去
        if (gen != favGen) {
            favBusy = false
            pumpFav(ctx)
            return
        }
        // v3.15.8：**实时**取按钮，并用它的语义判断能不能点。
        // 缓存 favIntent 是错的：它的标题语义会随通知重建而翻转，
        // 拿旧 PI 去点「取消」实际会再收藏一次。
        val live = favoriteActionFor(ctx, trackTitle.orEmpty())
        if (live == null) {
            // v3.15.16：拿不到按钮 ≠ 用户没点。真机实测「有时收藏不上」的根因正是这里：
            // 切歌/通知重建瞬间 favIntent 被清空、或缓存的按钮标题还是上一首，
            // favoriteActionFor 返回 null，旧代码直接「skipped」放弃 —— 用户就感觉失灵。
            // 歌词每一行刷新都会重填 favIntent（见 inspectFavoriteActions），所以这里
            // 短暂等待（最多 ~2.5s）重取，几乎总能等到；等不到才认命。
            if (noBtnWait < 2_500L) {
                XposedBridge.log("$TAG: fav no button yet, wait ${noBtnWait}ms")
                mainHandler.postDelayed({
                    sendFavAction(ctx, target, gen, attempt, waited, noBtnWait + FAV_SEMANTIC_STEP_MS)
                }, FAV_SEMANTIC_STEP_MS)
                return
            }
            XposedBridge.log("$TAG: favorite skipped (no like action)")
            // 没有可点的按钮：不能硬拗，交给真值/记忆表兜底
            finishFav(ctx, gen, readFavFromSession(ctx))
            return
        }
        val pi = live.pi
        val btnState = live.state
        // 按钮已经处于目标状态 → 真实状态若还不是目标，说明通知还没重建。
        // 此刻点下去得到的是反方向（想取消却再收藏），必须等它刷新。
        // btnState == null（网易云恒为 `like`，标题不带状态）时无法判断方向，
        // 只能按 toggle 语义直接发送，正确性交给下面的 metadata 真值校验兜底。
        if (btnState != null && btnState == target) {
            // 等待期间真值可能已经到位（上一次操作生效了），别再无谓地等
            if (waited >= FAV_SEMANTIC_STEP_MS) {
                val now = readFavFromSession(ctx)
                if (now == target) {
                    XposedBridge.log("$TAG: fav already=$target while waiting, done")
                    finishFav(ctx, gen, now)
                    return
                }
            }
            if (waited < FAV_SEMANTIC_WAIT_MS) {
                XposedBridge.log(
                    "$TAG: fav action stale (btn=$btnState target=$target), wait ${waited}ms"
                )
                mainHandler.postDelayed({
                    sendFavAction(ctx, target, gen, attempt, waited + FAV_SEMANTIC_STEP_MS)
                }, FAV_SEMANTIC_STEP_MS)
                return
            }
            XposedBridge.log("$TAG: fav action never refreshed, give up")
            finishFav(ctx, gen, readFavFromSession(ctx))
            return
        }
        favIntent = pi
        lastFavSendAt = SystemClock.uptimeMillis()
        lastFavTarget = target
        lastSentSemanticKnown = btnState != null
        if (attempt == 1) {
            // 点击闭环：记下点击前的指纹与目标。只有点击前状态已知时，翻转结果才可断言
            pendingFavHash = favHash
            pendingFavTarget = target
            pendingFavLearnable = favState != null
        }
        // 乐观置为目标状态：通知刷新若拿到真值会覆盖它，不会长期错下去
        favState = target
        try {
            pi.send(ctx, 0, null)
            lastToggleSentAt = SystemClock.uptimeMillis()
            XposedBridge.log("$TAG: favorite action sent (target=$target gen=$gen attempt=$attempt)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: favorite send failed: ${t.message}")
        }

        verifyFavLater(ctx, target, gen, attempt, round = 0)
    }

    /** 校验调度：900ms → 再 700ms；仍未生效才重试（每次目标最多 2 次发送） */
    private fun verifyFavLater(ctx: Context, target: Boolean, gen: Int, attempt: Int, round: Int) {
        mainHandler.postDelayed({
            // 用户又点了别的目标：本次校验作废，交给最新目标（不回退它的 ❤）
            if (gen != favGen) {
                XposedBridge.log("$TAG: fav verify stale (gen=$gen now=$favGen)")
                finishFav(ctx, gen, null)
                return@postDelayed
            }
            val actual = readFavFromSession(ctx)
            if (actual == null) {
                XposedBridge.log("$TAG: fav verify skipped (no metadata)")
                finishFav(ctx, gen, null)
                return@postDelayed
            }
            if (actual == target) {
                val ph = pendingFavHash
                if (ph != null && pendingFavLearnable) learnFav(ctx, ph, target)
                XposedBridge.log("$TAG: fav verified -> $actual (attempt=$attempt)")
                finishFav(ctx, gen, actual)
                return@postDelayed
            }
            if (round == 0) {
                XposedBridge.log("$TAG: fav not applied yet (actual=$actual), recheck")
                verifyFavLater(ctx, target, gen, attempt, round = 1)
                return@postDelayed
            }
            // 语义未知（网易云 `like`）时绝不能重发：那是 toggle，重发等于撤销刚才的操作。
            // 只能被动等它落地。网易云实测 COLLECT_STATE 写回要 1~2s（有时更久），
            // 原来只等 2.8s（round 0→1→2）太短，toggle 永远等不到就「gave up」，
            // 表现为「收藏点了没反应」。延长到 round 4（约 5.2s），仍不落地才以真值收尾。
            if (!lastSentSemanticKnown) {
                if (round < 4) {
                    XposedBridge.log("$TAG: fav toggle pending (actual=$actual), wait round=$round")
                    verifyFavLater(ctx, target, gen, attempt, round = round + 1)
                } else {
                    XposedBridge.log("$TAG: fav toggle gave up, accept actual=$actual")
                    finishFav(ctx, gen, actual)
                }
                return@postDelayed
            }
            if (attempt < 2) {
                XposedBridge.log("$TAG: fav verify failed (actual=$actual), retry")
                sendFavAction(ctx, target, gen, attempt + 1)
            } else {
                // 两次都失败：以播放器真值为准，不再硬拗
                XposedBridge.log("$TAG: fav failed twice, accept actual=$actual")
                finishFav(ctx, gen, actual)
            }
        }, when (round) {
            0 -> 900L
            1 -> 700L
            else -> 1_200L
        })
    }

    /**
     * 收尾：释放串行泵。若期间用户又点了（gen 变了），立刻接着处理最新目标。
     * [actual] 为 null 表示「没拿到真值」—— 保留乐观状态，不覆盖。
     */
    private fun finishFav(ctx: Context, gen: Int, actual: Boolean?) {
        favBusy = false
        if (gen != favGen) {
            pumpFav(ctx)
            return
        }
        favDesired = null
        pendingFavTarget = null
        pendingFavHash = null
        if (actual != null) {
            // 拿到真值才撤掉沉降窗口：否则随后一次推送会把点击前的旧值采信回来
            lastFavSendAt = 0L
            lastToggleSentAt = 0L
            favState = actual
            rememberTrackFav(ctx, trackFavKey(currentPkg, trackTitle), actual)
        }
        // 把结论明确告诉面板（即使 actual == null 也要推，让面板知道本次点击到此为止）
        if (panelVisible) pushToPanel(ctx, force = true, favFinal = actual != null)
    }

    /**
     * 重新从当前在播通知里取收藏按钮（只更新 favIntent / 指纹，不改 favState）。
     * 重试时用：通知若已重建，按钮语义可能从「收藏」变成「已收藏」，必须取新的。
     */
    private fun refreshFavAction(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val list = nm?.activeNotifications ?: return
            for (sbn in list) {
                val pkg = sbn?.packageName ?: continue
                if (!ArkConstants.isMediaApp(pkg)) continue
                val n = sbn.notification ?: continue
                val nt = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
                val cur = trackTitle?.trim()
                if (!nt.isNullOrEmpty() && !cur.isNullOrEmpty() &&
                    !nt.contains(cur) && !cur.contains(nt)
                ) continue
                val a = n.actions?.firstOrNull { act ->
                    val t = act.title?.toString()?.trim().orEmpty()
                    favTitleState(t) != null || isFavTitleLike(t)
                } ?: continue
                favIntent = a.actionIntent
                XposedBridge.log("$TAG: fav action refreshed ('${a.title}')")
                return
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: fav action refresh failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // 输出切换（v3.15）：反射 MediaRouter2Manager（@SystemApi，不在公共 SDK，
    // SystemUI 进程内可调用；系统媒体卡的输出切换就是这套 API）
    // ------------------------------------------------------------------

    /** 面板请求输出路由列表 → 回广播给面板弹窗 */
    fun outputRoutes(ctx: Context?) {
        val c = ctx ?: return
        try {
            val pkg = currentPkg ?: run {
                XposedBridge.log("$TAG: output routes skipped (no media app)")
                return
            }
            val routes = reflectRoutes(c, pkg) ?: run {
                XposedBridge.log("$TAG: output routes reflect failed")
                return
            }
            val selectedId = reflectSelectedRouteId(c, pkg)

            // 排序：当前选中的在最前，其余保持系统顺序
            val sorted = routes.sortedBy { if (selectedId != null && it.second == selectedId) 0 else 1 }
            val selectedIndex = if (selectedId != null) sorted.indexOfFirst { it.second == selectedId } else -1

            val intent = Intent(ArkConstants.ACTION_OUTPUT_ROUTES).apply {
                setPackage(ArkConstants.APP_PKG)
                putExtra(ArkConstants.EXTRA_ROUTE_NAMES, ArrayList(sorted.map { it.first }))
                putExtra(ArkConstants.EXTRA_ROUTE_IDS, ArrayList(sorted.map { it.second }))
                putExtra(ArkConstants.EXTRA_ROUTE_SELECTED, selectedIndex)
            }
            c.sendBroadcast(intent)
            XposedBridge.log("$TAG: output routes -> ${sorted.map { it.first }} selected=$selectedIndex")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: outputRoutes failed: ${t.message}")
        }
    }

    /** 面板选定路由 → 切换 */
    fun transferOutput(ctx: Context?, routeId: String?) {
        val c = ctx ?: return
        if (routeId.isNullOrBlank()) return
        val pkg = currentPkg ?: return
        try {
            val mgrCls = Class.forName("android.media.MediaRouter2Manager")
            val mgr = mgrCls.getMethod("getInstance", Context::class.java).invoke(null, c)
            // API 31+ 是 transferToRoute(String, String)；更早版本是 (String, MediaRoute2Info)
            val byId = try {
                mgrCls.getMethod("transferToRoute", String::class.java, String::class.java)
            } catch (_: Throwable) {
                null
            }
            if (byId != null) {
                byId.invoke(mgr, pkg, routeId)
            } else {
                val infoCls = Class.forName("android.media.MediaRoute2Info")
                val route = (reflectRoutes(c, pkg) ?: emptyList())
                    .firstOrNull { it.second == routeId }?.third ?: return
                mgrCls.getMethod("transferToRoute", String::class.java, infoCls)
                    .invoke(mgr, pkg, route)
            }
            XposedBridge.log("$TAG: transfer output -> $routeId ($pkg)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: transferOutput failed: ${t.message}")
        }
    }

    /**
     * 列出 [pkg] 可用的输出路由。
     * @return List<(显示名, id, MediaRoute2Info)>
     */
    private fun reflectRoutes(
        ctx: Context,
        pkg: String
    ): List<Triple<String, String, Any>>? = try {
        val mgrCls = Class.forName("android.media.MediaRouter2Manager")
        val mgr = mgrCls.getMethod("getInstance", Context::class.java).invoke(null, ctx)
        val raw: List<*> = try {
            mgrCls.getMethod("getAvailableRoutes", String::class.java).invoke(mgr, pkg) as? List<*>
        } catch (_: Throwable) {
            mgrCls.getMethod("getRoutes", String::class.java).invoke(mgr, pkg) as? List<*>
        } ?: return null

        raw.mapNotNull { r ->
            val id = try {
                XposedHelpers.callMethod(r, "getId") as? String
            } catch (_: Throwable) {
                null
            } ?: return@mapNotNull null
            val name = routeName(ctx, r ?: return@mapNotNull null) ?: return@mapNotNull null
            Triple(name, id, r)
        }.filter { it.first.isNotBlank() }
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: reflectRoutes error: ${t.message}")
        null
    }

    /** MediaRoute2Info.getName() 在低版本需要 Context 参数，两个签名都试 */
    private fun routeName(ctx: Context, route: Any): String? = try {
        try {
            (XposedHelpers.callMethod(route, "getName") as? CharSequence)?.toString()
        } catch (_: Throwable) {
            (XposedHelpers.callMethod(route, "getName", ctx) as? CharSequence)?.toString()
        }
    } catch (_: Throwable) {
        null
    }

    /** 当前选中路由的 id（取不到返回 null，面板就不高亮） */
    private fun reflectSelectedRouteId(ctx: Context, pkg: String): String? = try {
        val mgrCls = Class.forName("android.media.MediaRouter2Manager")
        val mgr = mgrCls.getMethod("getInstance", Context::class.java).invoke(null, ctx)
        val route = mgrCls.getMethod("getSelectedRoute", String::class.java).invoke(mgr, pkg)
        route?.let { XposedHelpers.callMethod(it, "getId") as? String }
    } catch (_: Throwable) {
        null
    }

    /** MediaSession 里的专辑图 → 面板用的方形圆角缩略图（绑 Binder 事务 1MB 上限） */
    private fun scaleArt(src: Bitmap): Bitmap? = try {
        val size = minOf(src.width, src.height)
        if (size <= 0) {
            null
        } else {
            val square = if (src.width == size && src.height == size) {
                src
            } else {
                Bitmap.createBitmap(src, (src.width - size) / 2, (src.height - size) / 2, size, size)
            }
            val target = minOf(size, 240)
            val scaled = if (size > target) {
                Bitmap.createScaledBitmap(square, target, target, true)
            } else {
                square
            }
            val out = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(out)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val r = out.width * 0.16f
            canvas.drawRoundRect(
                android.graphics.RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()),
                r, r, paint
            )
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(scaled, 0f, 0f, paint)
            out
        }
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------
    // 拉起面板
    // ------------------------------------------------------------------

    /**
     * 胶囊视图在**屏幕坐标系**下的矩形：`[left, top, width, height]`。
     * 面板拿它当展开动画的起点，所以必须是屏幕绝对坐标（`getLocationOnScreen`），
     * 不能用相对父容器的位置。
     */
    private fun capsuleRectOnScreen(ctrl: Any): IntArray? = try {
        val view = XposedHelpers.getObjectField(ctrl, "mLiveNotificationDisplay") as? View
        if (view == null || view.width <= 0 || view.height <= 0) {
            null
        } else {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            intArrayOf(loc[0], loc[1], view.width, view.height)
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 精确命中判定：落点必须真的在胶囊视图内。
     * `LiveNotificationClickHelper` 只判断 mHasDown 与整个 mLiveNotificationArea 范围，
     * 粒度过粗，状态栏的其它手势也常常被判成 consumed —— 这里再用视图矩形收一次。
     */
    private fun hitCapsule(ctrl: Any, x: Float, y: Float): Boolean = try {
        val r = capsuleRectOnScreen(ctrl)
        if (r == null) {
            false
        } else {
            val pad = (ctrl as? Any)?.let {
                val ctx = XposedHelpers.getObjectField(ctrl, "mContext") as? Context
                (ctx?.resources?.displayMetrics?.density ?: 3f) * 8f
            } ?: 24f
            val left = r[0].toFloat()
            val top = r[1].toFloat()
            x >= left - pad && x <= left + r[2] + pad &&
                y >= top - pad && y <= top + r[3] + pad
        }
    } catch (_: Throwable) {
        false
    }

    private fun launchPanel(ctrl: Any, now: Long) {
        ctrlRef = WeakReference(ctrl)
        if (now - lastLaunchAt < PANEL_COOLDOWN_MS) {
            XposedBridge.log("$TAG: launch skipped (cooldown)")
            return
        }

        // 歌词胶囊走的是 mLyricsInofo（showType=5），普通实况窗才用 mDisplayNotificationInfo；
        // 两个都要试，否则歌词胶囊点击拿不到归属包名。
        val info = XposedHelpers.getObjectField(ctrl, "mDisplayNotificationInfo")
            ?: XposedHelpers.getObjectField(ctrl, "mLyricsInofo")
            ?: run {
                XposedBridge.log("$TAG: launch skipped (no live info)")
                return
            }
        val pkg = XposedHelpers.callMethod(info, "getPackageName") as? String
        if (!ArkConstants.isMediaApp(pkg) && !ArkConstants.isMessageLiveApp(pkg)) {
            XposedBridge.log("$TAG: launch skipped (pkg=$pkg)")
            return
        }

        val ctx = XposedHelpers.getObjectField(ctrl, "mContext") as? Context ?: return

        // v3.15.39：消息胶囊（微信 / QQ）→ 消息大卡（ArkMessagePanelActivity），
        // 与音乐面板共用冷却 / 原生点击屏蔽 / 胶囊淡出联动。
        if (pkg != null && ArkConstants.isMessageLiveApp(pkg)) {
            launchMessagePanel(ctrl, ctx, pkg, now)
            return
        }

        val showText = (XposedHelpers.callMethod(info, "getShowText") as? String)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val title = trackTitle?.takeIf { it.isNotBlank() } ?: showText ?: run {
            XposedBridge.log("$TAG: launch skipped (empty title)")
            return
        }

        val playing = trackPlaying || isMusicActive(ctx)

        // QQ音乐 的播放通知不带 android.progress，进度条会退化成静态。
        // SystemUI 持有 MEDIA_CONTENT_CONTROL，可直接读 MediaSession 的
        // PlaybackState.position 与 MEDIA_KEY_DURATION，拿到真实进度与总时长。
        val session = readMediaSession(ctx)
        val progressMs = session?.position?.takeIf { it >= 0 }?.toInt() ?: trackProgress
        val durationMs = session?.duration?.takeIf { it > 0 }?.toInt() ?: trackDuration
        val isPlaying = session?.playing ?: playing
        session?.clickIntent?.let { lastClickIntent = it }
        // v3.15.1：打开面板前先刷新一次收藏真值（主动查在播通知，其次 metadata，最后指纹表）
        loadFavMap(ctx)
        refreshFavFromActiveNotifications(ctx)
        session?.fav?.let { truth ->
            if (inFavSettle()) return@let
            favState = truth
            favHash?.let { h -> learnFav(ctx, h, truth) }
        }
        if (session?.fav == null) favHash?.let { favMap[it] }?.let { favState = it }
        // v3.15.4：通知还没刷到这首时，用按曲目记忆兜底（比指纹表更准，直接对应这首歌）
        if (favState == null) favState = trackFavOf(ctx, trackFavKey(pkg, title))

        val intent = Intent().apply {
            setClassName(ArkConstants.APP_PKG, PANEL_ACTIVITY)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            putExtra(ArkConstants.EXTRA_PANEL_TITLE, title)
            putExtra(ArkConstants.EXTRA_PANEL_ARTIST, trackArtist ?: "")
            putExtra(ArkConstants.EXTRA_PANEL_PROGRESS, progressMs)
            putExtra(ArkConstants.EXTRA_PANEL_DURATION, durationMs)
            putExtra(ArkConstants.EXTRA_PANEL_PLAYING, isPlaying)
            trackCover?.let { putExtra(ArkConstants.EXTRA_PANEL_COVER, it) }
            // v3.13：媒体 App 跳转（点卡片空白处拉起 App，对齐系统媒体卡）
            lastClickIntent?.let { putExtra(ArkConstants.EXTRA_PANEL_CLICK_INTENT, it) }
            // v3.13.1：SystemUI 同源的深浅模式
            putExtra(ArkConstants.EXTRA_PANEL_NIGHT, isNightMode(ctx))
            // v3.15：当前媒体 App 包名（右上角图标 + 跳转兜底）
            pkg?.let { putExtra(ArkConstants.EXTRA_PANEL_APP_PKG, it) }
            // v3.15.1：收藏真值（没有就不带，面板显示「未定」）
            favState?.let { putExtra(ArkConstants.EXTRA_PANEL_FAV, it) }
            // 胶囊矩形：面板以它为展开动画起点（长大）与收起终点（缩回）
            capsuleRectOnScreen(ctrl)?.let { r ->
                putExtra(ArkConstants.EXTRA_CAPSULE_LEFT, r[0])
                putExtra(ArkConstants.EXTRA_CAPSULE_TOP, r[1])
                putExtra(ArkConstants.EXTRA_CAPSULE_WIDTH, r[2])
                putExtra(ArkConstants.EXTRA_CAPSULE_HEIGHT, r[3])
            }
        }

        try {
            ctx.startActivity(intent)
            lastLaunchAt = now
            clickHandledAt = now
            lastPushKey = null // 面板打开后第一条推送必须带全量信息
            XposedBridge.log(
                "$TAG: panel launched, title='$title', pos=$progressMs/$durationMs, playing=$isPlaying"
            )
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG startActivity failed: $t")
        }
    }

    // ------------------------------------------------------------------
    // v3.15.39：消息胶囊 → 消息大卡
    // ------------------------------------------------------------------

    /** 消息大卡类名 */
    private const val MESSAGE_PANEL_ACTIVITY = "com.flymeark.ArkMessagePanelActivity"

    /**
     * 拉起消息大卡。
     *
     * 数据源：`LiveNotificationController.mNotificationEntries`（List<PipelineEntry>），
     * 反编译确认 `updateStatusBarIcons` 就是靠它遍历通知的
     * （`getRepresentativeEntry().getSbn()`），SystemUI 进程读 extras /
     * largeIcon / contentIntent 都有权限 —— 本面板进程没有，所以数据必须在这边取好。
     *
     * 取「postTime 最新的一条原通知」（tag != 影子 tag）：同一 App 同时有多条通知时，
     * 最新的那条就是胶囊正在代表的消息。
     */
    private fun launchMessagePanel(ctrl: Any, ctx: Context, pkg: String, now: Long) {
        val sbn = latestMessageSbn(ctrl, pkg) ?: run {
            XposedBridge.log("$TAG: msg panel skipped (no source notification for $pkg)")
            // 通知已经被清掉了：直接按包名拉主界面兜底，别让点击完全没反应
            try {
                ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(it)
                }
            } catch (_: Throwable) {
            }
            return
        }

        val n = try { XposedHelpers.callMethod(sbn, "getNotification") as? Notification } catch (_: Throwable) { null }
        val extras = try { n?.extras } catch (_: Throwable) { null }
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = (extras?.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim()

        // largeIcon 的静态类型在不同 framework 下有 Bitmap / Icon 两种，运行时按实际类型取；
        // 微信/QQ 的发送者头像通常放在 extras 的 android.largeIcon（Icon 类型）里
        val largeIconObj = try { XposedHelpers.getObjectField(n, "largeIcon") } catch (_: Throwable) { null }
        val extrasLargeIcon = try {
            @Suppress("DEPRECATION")
            extras?.getParcelable<android.os.Parcelable>("android.largeIcon")
        } catch (_: Throwable) {
            null
        }
        val avatar = (largeIconObj as? Bitmap)
            ?: avatarBitmapOf(ctx, largeIconObj as? android.graphics.drawable.Icon)
            ?: avatarBitmapOf(ctx, extrasLargeIcon as? android.graphics.drawable.Icon)
        val postAt = try { XposedHelpers.callMethod(sbn, "getPostTime") as? Long ?: 0L } catch (_: Throwable) { 0L }
        val contentIntent = n?.contentIntent

        val intent = Intent().apply {
            setClassName(ArkConstants.APP_PKG, MESSAGE_PANEL_ACTIVITY)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            putExtra(ArkConstants.EXTRA_MSG_TITLE, title ?: "")
            putExtra(ArkConstants.EXTRA_MSG_TEXT, text ?: "")
            putExtra(ArkConstants.EXTRA_MSG_TIME, postAt)
            putExtra(ArkConstants.EXTRA_MSG_BRAND, ArkConstants.brandColorOf(pkg))
            putExtra(ArkConstants.EXTRA_PANEL_APP_PKG, pkg)
            putExtra(ArkConstants.EXTRA_PANEL_NIGHT, isNightMode(ctx))
            avatar?.let { putExtra(ArkConstants.EXTRA_MSG_AVATAR, it) }
            contentIntent?.let { putExtra(ArkConstants.EXTRA_PANEL_CLICK_INTENT, it) }
            // 胶囊矩形：面板以它为展开动画起点（长大）与收起终点（缩回）
            capsuleRectOnScreen(ctrl)?.let { r ->
                putExtra(ArkConstants.EXTRA_CAPSULE_LEFT, r[0])
                putExtra(ArkConstants.EXTRA_CAPSULE_TOP, r[1])
                putExtra(ArkConstants.EXTRA_CAPSULE_WIDTH, r[2])
                putExtra(ArkConstants.EXTRA_CAPSULE_HEIGHT, r[3])
            }
        }

        try {
            ctx.startActivity(intent)
            lastLaunchAt = now
            clickHandledAt = now
            XposedBridge.log(
                "$TAG: msg panel launched, pkg=$pkg title='$title' textLen=${text?.length ?: 0} avatar=${avatar != null}"
            )
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG msg startActivity failed: ${t.message}")
        }
    }

    /** mNotificationEntries 里 postTime 最新的微信/QQ 原通知（排除影子通知） */
    private fun latestMessageSbn(ctrl: Any, pkg: String): Any? = try {
        val entries = XposedHelpers.getObjectField(ctrl, "mNotificationEntries") as? List<*>
        var best: Any? = null
        var bestAt = -1L
        entries?.forEach { pe ->
            if (pe == null) return@forEach
            val entry = try {
                XposedHelpers.callMethod(pe, "getRepresentativeEntry") ?: return@forEach
            } catch (_: Throwable) {
                return@forEach
            }
            val sbn = try {
                XposedHelpers.callMethod(entry, "getSbn") ?: return@forEach
            } catch (_: Throwable) {
                return@forEach
            }
            val p = try {
                XposedHelpers.callMethod(sbn, "getPackageName") as? String
            } catch (_: Throwable) {
                null
            } ?: return@forEach
            if (p != pkg) return@forEach
            val tag = try {
                XposedHelpers.callMethod(sbn, "getTag") as? String
            } catch (_: Throwable) {
                null
            }
            if (tag == ArkMessageLiveInjector.SHADOW_TAG) return@forEach
            val at = try {
                XposedHelpers.callMethod(sbn, "getPostTime") as? Long ?: 0L
            } catch (_: Throwable) {
                0L
            }
            if (at >= bestAt) {
                bestAt = at
                best = sbn
            }
        }
        best
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: latestMessageSbn failed: ${t.message}")
        null
    }

    /** largeIcon → Bitmap（位图型直接取，其余走 loadDrawable 转码；失败返回 null） */
    private fun avatarBitmapOf(ctx: Context, icon: android.graphics.drawable.Icon?): Bitmap? {
        if (icon == null) return null
        return try {
            // TYPE_BITMAP(1) / TYPE_ADAPTIVE_BITMAP(5)：getBitmap 直接取
            val t = XposedHelpers.callMethod(icon, "getType") as? Int
            if (t == 1 || t == 5) {
                XposedHelpers.callMethod(icon, "getBitmap") as? Bitmap
            } else {
                val d = XposedHelpers.callMethod(icon, "loadDrawable", ctx)
                    as? android.graphics.drawable.Drawable ?: return null
                val w = d.intrinsicWidth.takeIf { it > 0 } ?: 128
                val h = d.intrinsicHeight.takeIf { it > 0 } ?: 128
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                d.setBounds(0, 0, w, h)
                d.draw(c)
                bmp
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 一次 MediaSession 快照（当前媒体 App：QQ音乐/网易云音乐）
     */
    private class SessionSnapshot(
        val title: String?,
        val artist: String?,
        val position: Long,
        val duration: Long,
        val playing: Boolean,
        val art: Bitmap?,
        /** 媒体 App 的跳转 PendingIntent（v3.13，点面板卡片空白处拉起 App） */
        val clickIntent: PendingIntent? = null,
        /** v3.15.1：metadata extras 里的收藏真值（没有则为 null） */
        val fav: Boolean? = null
    )

    /**
     * v3.15.5：只取收藏真值（metadata → COLLECT_STATE / RATING），**不解码封面**。
     *
     * 切歌轮询要连查几次，`readMediaSession` 每次都跨进程搬一张几百 KB 的位图，
     * 主线程扛不住；收藏判断只需要 metadata，所以单独开这条轻量路径。
     */
    private fun readFavFromSession(ctx: Context): Boolean? = try {
        activeController(ctx)?.metadata?.let { favFromMetadata(it) }
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: readFavFromSession failed: ${t.message}")
        null
    }

    /**
     * 读取当前播放会话。仅在 SystemUI（system uid / 持有 MEDIA_CONTENT_CONTROL）内可用。
     * 除了进度，还能拿到 MediaSession 自己的标题/歌手/专辑图——切歌时比等通知刷新快得多。
     */
    private fun readMediaSession(ctx: Context): SessionSnapshot? = try {        val ctrl = activeController(ctx)
        if (ctrl == null) {
            null
        } else {
            val ps = ctrl.playbackState
            val md = ctrl.metadata
            SessionSnapshot(
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                position = ps?.position ?: -1L,
                duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L,
                playing = ps?.state == PlaybackState.STATE_PLAYING,
                art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                clickIntent = ctrl.sessionActivity,
                fav = favFromMetadata(md)
            )
        }
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: mediaSession read failed: ${t.message}")
        null
    }

    private fun isMusicActive(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive ?: false
    } catch (_: Throwable) {
        false
    }

    /**
     * SystemUI 侧深色模式判断——必须与媒体卡玻璃同源。
     *
     * 反编译确认：MediaCarouseTransitionLayout.isInNightMode() →
     * SystemUICommonUtils.isNightModel(ctx) → 静态字段 `sIsNightMode`
     * （Flyme 自己维护的深色开关，不读 configuration）。
     * 面板 App 进程读 configuration 判断不可靠（实测 Flyme 深色开关下两者不一致）。
     */
    private fun isNightMode(ctx: Context): Boolean = try {
        val cls = XposedHelpers.findClass(
            "com.flyme.systemui.utils.SystemUICommonUtils", ctx.classLoader
        )
        XposedHelpers.getStaticBooleanField(cls, "sIsNightMode")
    } catch (_: Throwable) {
        try {
            (ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        } catch (_: Throwable) {
            false
        }
    }
}

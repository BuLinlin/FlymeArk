package com.flymeark

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
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
    private const val CTRL_CLS =
        "com.flyme.statusbar.livenotification.LiveNotificationController"
    private const val CENTRAL_CLS = "com.android.systemui.statusbar.phone.CentralSurfacesImpl"
    private const val PANEL_ACTIVITY = "com.flymeark.ArkCapsulePanelActivity"
    private const val QQ_MUSIC = "com.tencent.qqmusic"

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

    // ---------------- 曲目缓存（由 ArkLiveCoverHook 在每次歌词刷新时写入） ----------------

    @Volatile private var trackTitle: String? = null
    @Volatile private var trackArtist: String? = null
    @Volatile private var trackCover: Bitmap? = null
    @Volatile private var trackProgress: Int = -1
    @Volatile private var trackDuration: Int = -1
    @Volatile private var trackPlaying: Boolean = false

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

            val p = ex?.getInt(Notification.EXTRA_PROGRESS, -1) ?: -1
            val max = ex?.getInt(Notification.EXTRA_PROGRESS_MAX, -1) ?: -1
            trackProgress = p
            trackDuration = if (max > 0) max else -1

            trackPlaying = (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0

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
    private fun pushToPanel(ctx: Context?) {
        if (!panelVisible) return
        val c = ctx ?: return
        val title = trackTitle?.takeIf { it.isNotBlank() } ?: return
        val artist = trackArtist.orEmpty()
        val key = "$title|$artist"
        val now = SystemClock.uptimeMillis()
        val changed = key != lastPushKey
        if (!changed && now - lastPushAt < 1_000L) return
        lastPushKey = key
        lastPushAt = now

        val session = readMediaSession(c)
        val progress = session?.position?.takeIf { it >= 0 }?.toInt() ?: trackProgress
        val duration = session?.duration?.takeIf { it > 0 }?.toInt() ?: trackDuration
        val playing = session?.playing ?: trackPlaying

        sendTrack(c, title, artist, progress, duration, playing, if (changed) trackCover else null)
        if (changed) {
            XposedBridge.log("$TAG: push track '$title' pos=$progress/$duration playing=$playing")
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

        trackTitle = title
        trackArtist = artist
        if (cover != null) trackCover = cover
        lastPushKey = "$title|$artist"
        lastPushAt = SystemClock.uptimeMillis()

        sendTrack(c, title, artist, progress, duration, s.playing, cover)
        XposedBridge.log("$TAG: sync '$title' pos=$progress/$duration playing=${s.playing}")
    }

    private fun sendTrack(
        ctx: Context,
        title: String,
        artist: String,
        progress: Int,
        duration: Int,
        playing: Boolean,
        cover: Bitmap?
    ) {
        val intent = Intent(ArkConstants.ACTION_PANEL_TRACK).apply {
            setPackage(ArkConstants.APP_PKG)
            putExtra(ArkConstants.EXTRA_PANEL_TITLE, title)
            putExtra(ArkConstants.EXTRA_PANEL_ARTIST, artist)
            putExtra(ArkConstants.EXTRA_PANEL_PROGRESS, progress)
            putExtra(ArkConstants.EXTRA_PANEL_DURATION, duration)
            putExtra(ArkConstants.EXTRA_PANEL_PLAYING, playing)
            cover?.let { putExtra(ArkConstants.EXTRA_PANEL_COVER, it) }
        }
        try {
            ctx.sendBroadcast(intent)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: push track failed: ${t.message}")
        }
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
        if (pkg != QQ_MUSIC) {
            XposedBridge.log("$TAG: launch skipped (pkg=$pkg)")
            return
        }

        val ctx = XposedHelpers.getObjectField(ctrl, "mContext") as? Context ?: return

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

    /** 一次 MediaSession 快照（QQ音乐 当前播放） */
    private class SessionSnapshot(
        val title: String?,
        val artist: String?,
        val position: Long,
        val duration: Long,
        val playing: Boolean,
        val art: Bitmap?
    )

    /**
     * 读取当前播放会话。仅在 SystemUI（system uid / 持有 MEDIA_CONTENT_CONTROL）内可用。
     * 除了进度，还能拿到 MediaSession 自己的标题/歌手/专辑图——切歌时比等通知刷新快得多。
     */
    private fun readMediaSession(ctx: Context): SessionSnapshot? = try {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val ctrl = msm?.getActiveSessions(null)?.firstOrNull { it.packageName == QQ_MUSIC }
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
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
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
}

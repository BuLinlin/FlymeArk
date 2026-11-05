package com.flymeark

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.flymeark.R
import java.util.Locale

/** 封面轮播单程时长 */
private const val COVER_SLIDE_MS = 340L

/** 上一首 / 下一首节流窗口：切歌链路本身有延迟，连点只会一次跳好几首 */
private const val KEY_THROTTLE_MS = 400L

/** 播放 / 暂停防连发窗口：显式 play()/pause() 幂等，这里只挡同一帧内的连发 IPC */
private const val PLAY_THROTTLE_MS = 180L

/** 点卡片跳转的防抖窗口：避免连点拉起多个实例 */
private const val OPEN_APP_DEBOUNCE_MS = 800L

/**
 * 收藏按钮的点击锁。
 *
 * v3.15.8 取证：SystemUI 的实况通知点击就是直接 send 通知 action，**没有节流**
 * （`LiveNotificationClickHelper` 只判 down/up）。所以这里不该照抄任何"系统限制时间"，
 * 只挡误触双击即可；连点的正确性由 SystemUI 侧的「按钮语义匹配 + 串行泵」保证
 * （语义不对时会等通知重建，最多 3 秒，不会发出反方向的点击）。
 */
private const val FAV_CLICK_LOCK_MS = 450L

/**
 * 点击后多久内不采信推送来的收藏状态。
 *
 * 原本是 6 秒：那会儿真值只有「通知按钮标题」一个来源，滞后 1~5 秒，推送来的是旧值，
 * 会把面板乐观切换的 ❤ 冲掉。
 *
 * v3.15.7：2 秒。现在的发送链是「220ms 合并 + 900ms 校验（+700ms 复检 + 重试）」，
 * 全程都会有心跳/轮询推送带着旧真值过来，锁必须覆盖到首次校验结论出来（~1.2s）。
 * 失败重试最长约 2.9s，那时 SystemUI 会主动把真实状态推下来，面板已经解锁能接住。
 */
private const val FAV_PUSH_LOCK_MS = 2_000L

/**
 * v2.6：点击实况胶囊后从状态栏下方展开的播放控制面板。
 *
 * 触发方：[ArkLiveExpandHook] 在 SystemUI 进程拦截胶囊点击后 startActivity，
 * 曲目信息（曲名 / 歌手 / 封面 / 进度）随 Intent 一起带过来 —— 因为 SystemUI 进程
 * 有权限读取通知的 largeIcon 与 extras，而本 Activity 所在的普通应用进程没有。
 *
 * 媒体键：本面板运行在普通 App uid，`AudioManager.dispatchMediaKeyEvent` 未必生效，
 * 因此统一通过 [ArkConstants.ACTION_PANEL_MEDIA_KEY] 交给 SystemUI 进程
 * （system uid）派发，见 SystemUILiveHost.dispatchPanelKey。
 *
 * 交互：顶部下滑入场；点击卡片外空白 / 返回键 / 应用退到后台 → 收起。
 */
class ArkCapsulePanelActivity : Activity() {

    private lateinit var root: View
    private lateinit var card: View
    private lateinit var content: View
    private lateinit var coverSlot: View
    private lateinit var coverA: ImageView
    private lateinit var coverB: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var progressBar: ArkProgressBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var playBtn: ImageView
    private lateinit var prevBtn: ImageView
    private lateinit var nextBtn: ImageView
    private lateinit var favBtn: ImageView
    private lateinit var outputBtn: ImageView
    private lateinit var appIconView: ImageView

    private lateinit var morph: ArkMorphAnim

    private var durationMs = 0
    private var positionMs = 0
    private var playing = false
    private var closing = false

    /** v3.13：系统深色模式（配色与背景定标都跟它走） */
    private var nightMode = false

    /** v3.13：当前专辑主题色（进度条） */
    private var albumColor: Int? = null

    /**
     * v3.13.3：收藏状态（true=已收藏）。
     *
     * v3.15.1：加 [favKnown] 三态 —— 没拿到真值时按钮显示「未定」（空心 + 半透明），
     * 拿到真值（metadata extras / 通知标题 / 指纹学习表）后立即变成确定的实心或空心。
     * 不再把「未知」直接当成「未收藏」。
     */
    private var favState = false
    private var favKnown = false

    /** v3.13：媒体 App 跳转 PendingIntent（SystemUI 从 sessionActivity 带来） */
    private var lastClickIntent: PendingIntent? = null

    /** v3.15：当前媒体 App 包名（SystemUI 从歌词通知带来；右上角图标/跳转兜底都用它） */
    private var mediaPkg: String? = null

    /** 媒体键节流时间戳（按按键分开记，暂停和切歌互不干扰） */
    private val lastKeyAt = HashMap<Int, Long>(4)

    /** 播放/暂停防抖时间戳（按目标状态分开记：true=play，false=pause） */
    private val lastPlaybackAt = HashMap<Boolean, Long>(2)

    /** 跳转防抖时间戳 */
    private var lastOpenAppAt = 0L

    /** v3.15.42：当前液态玻璃参数（morph 动画层创建时要用，早于 applyPalette 生效时序） */
    private lateinit var paletteGlass: GlassParams

    /** 最近一次点击 ❤ 的时间：既是点击锁，也是「锁定期内不采信推送旧状态」的依据 */
    private var lastFavAt = 0L

    /** 输出切换点击防抖（请求路由列表 → SystemUI 回广播 → 弹窗） */
    private var lastOutputAt = 0L

    /** 封面轮播：当前露在前面的是 A 还是 B */
    private var frontIsA = true

    /** 下次换曲的滑动方向（+1 下一首向右进，-1 上一首向左进） */
    private var pendingDir = 1

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (!playing || durationMs <= 0) return
            positionMs = (positionMs + 1_000).coerceAtMost(durationMs)
            renderProgress()
            handler.postDelayed(this, 1_000)
        }
    }

    /** 换曲看门狗：按下上一首/下一首后 6 秒内没收到新曲目就收起，避免停在旧信息上 */
    private var pendingTitle: String? = null

    private val switchWatchdog = Runnable {
        val expected = pendingTitle
        pendingTitle = null
        if (expected != null && titleView.text.toString() == expected) dismiss()
    }

    /** 主动向 SystemUI 要一次曲目同步（不用等歌词通知刷新） */
    private val syncRequest = Runnable { requestSync() }

    /** SystemUI 推来的曲目更新（换曲 / 进度 / 播放态）+ 输出路由列表回复 */
    private val trackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ArkConstants.ACTION_PANEL_TRACK -> applyTrack(intent)
                ArkConstants.ACTION_OUTPUT_ROUTES -> showRouteDialog(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 顶部悬浮：宽度满屏、高度满屏（下方透明区域用于点击收起），不压暗背景
        window.setGravity(Gravity.TOP)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.attributes = window.attributes.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        applySystemGlass()

        setContentView(R.layout.activity_capsule_panel)
        registerTrackReceiver()
        bindViews()
        bindData()
        bindActions()

        morph = ArkMorphAnim(root, card, content, paletteGlass)
        // 第一帧先按胶囊尺寸摆好（此时不可见，避免「完整卡片闪一下再缩回去」），
        // 第二帧再显示并开始长大。
        card.alpha = 0f
        root.post {
            morph.prepare(capsuleRect())
            root.post {
                card.alpha = 1f
                morph.expand()
                restartTicker()
            }
        }
    }

    /**
     * v3.14.1：背景改回透明。
     *
     * v3.14.0 试过「窗口 blurBehind + 全屏 scrim」，但面板窗口是全屏的，
     * blur 作用于整个屏幕（用户反馈「整个页面都变成毛玻璃」）。
     * 普通 App 没有局部背后模糊的 API，所以只保留卡片的玻璃配色，
     * 卡片外完全透明（点空白收起的手势区域也保持原样）。
     */
    private fun applySystemGlass() {
        // 背景：无模糊、无染色；玻璃质感只由 panel_card 自身承担
    }

    /** SystemUI 传来的胶囊屏幕矩形；缺失时交给 [ArkMorphAnim] 用兜底尺寸 */
    private fun capsuleRect(): Rect? {
        val i = intent ?: return null
        val w = i.getIntExtra(ArkConstants.EXTRA_CAPSULE_WIDTH, 0)
        val h = i.getIntExtra(ArkConstants.EXTRA_CAPSULE_HEIGHT, 0)
        if (w <= 0 || h <= 0) return null
        val l = i.getIntExtra(ArkConstants.EXTRA_CAPSULE_LEFT, 0)
        val t = i.getIntExtra(ArkConstants.EXTRA_CAPSULE_TOP, 0)
        return Rect(l, t, l + w, t + h)
    }

    private fun registerTrackReceiver() {
        val filter = IntentFilter(ArkConstants.ACTION_PANEL_TRACK).apply {
            addAction(ArkConstants.ACTION_OUTPUT_ROUTES)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(trackReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(trackReceiver, filter)
            }
        } catch (_: Throwable) {
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.panel_root)
        card = findViewById(R.id.panel_card)
        content = findViewById(R.id.panel_content)
        coverSlot = findViewById(R.id.panel_cover_slot)
        coverA = findViewById(R.id.panel_cover)
        coverB = findViewById(R.id.panel_cover_b)
        titleView = findViewById(R.id.panel_title)
        artistView = findViewById(R.id.panel_artist)
        progressBar = findViewById(R.id.panel_progress)
        timeCurrent = findViewById(R.id.panel_time_current)
        timeTotal = findViewById(R.id.panel_time_total)
        playBtn = findViewById(R.id.panel_play)
        prevBtn = findViewById(R.id.panel_prev)
        nextBtn = findViewById(R.id.panel_next)
        favBtn = findViewById(R.id.panel_fav)
        outputBtn = findViewById(R.id.panel_output)
        appIconView = findViewById(R.id.panel_app_icon)

        // v3.15.42：多点采样背景亮度/复杂度 → 自动决定玻璃深浅与着色浓度
        nightMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        paletteGlass = ArkLiquidGlass.resolve(this, nightMode)
        applyPalette()

        // 点空白收起；点卡片跳转音乐 App（对齐系统媒体卡行为）
        root.setOnClickListener { dismiss() }
        card.setOnClickListener { openMusicApp() }

        // v3.13：拖动进度条 → seek
        progressBar.onScrub = { ms -> timeCurrent.text = formatTime(ms) }
        progressBar.onSeek = { ms ->
            positionMs = ms
            timeCurrent.text = formatTime(ms)
            sendSeek(ms)
            // seek 后立刻向 SystemUI 要一次真实进度，修正播放器实际落点
            handler.postDelayed(syncRequest, 400)
        }

        // v3.15.1：首帧就把收藏图标画出来（默认未收藏），不要等 SystemUI 推收藏状态
        updateFavIcon()
    }

    /** 右上角媒体 App 图标（v3.15：跟随 SystemUI 传来的真实播放 App；取不到则隐藏） */
    private fun applyAppIcon() {
        val pkg = mediaPkg ?: ArkConstants.MEDIA_APP_PKG
        try {
            appIconView.setImageDrawable(packageManager.getApplicationIcon(pkg))
            appIconView.visibility = View.VISIBLE
            android.util.Log.i("FlymeArkPanel", "app icon shown pkg=$pkg")
        } catch (t: Throwable) {
            // v3.15.9：包可见性未声明时这里会抛 NameNotFoundException，图标整块消失
            appIconView.visibility = View.GONE
            android.util.Log.w("FlymeArkPanel", "app icon failed pkg=$pkg: ${t.message}")
        }
    }

    private fun bindData() {
        val i = intent ?: return

        titleView.text = i.getStringExtra(ArkConstants.EXTRA_PANEL_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.ark_panel_unknown)

        val artist = i.getStringExtra(ArkConstants.EXTRA_PANEL_ARTIST).orEmpty()
        artistView.text = artist
        artistView.visibility = if (artist.isBlank()) View.GONE else View.VISIBLE

        coverOf(i)?.let { resetCover(it) }

        i.getStringExtra(ArkConstants.EXTRA_PANEL_APP_PKG)?.let { mediaPkg = it }
        applyAppIcon()

        durationMs = i.getIntExtra(ArkConstants.EXTRA_PANEL_DURATION, -1)
        positionMs = i.getIntExtra(ArkConstants.EXTRA_PANEL_PROGRESS, -1).coerceAtLeast(0)
        playing = i.getBooleanExtra(ArkConstants.EXTRA_PANEL_PLAYING, true)
        clickIntentOf(i)?.let { lastClickIntent = it }
        setNight(i.getBooleanExtra(ArkConstants.EXTRA_PANEL_NIGHT, nightMode))
        if (i.hasExtra(ArkConstants.EXTRA_PANEL_FAV) &&
            (i.getBooleanExtra(ArkConstants.EXTRA_PANEL_FAV_FINAL, false) ||
                android.os.SystemClock.uptimeMillis() - lastFavAt > FAV_PUSH_LOCK_MS)
        ) {
            favState = i.getBooleanExtra(ArkConstants.EXTRA_PANEL_FAV, false)
            favKnown = true
        }
        updateFavIcon()

        if (durationMs <= 0) {
            // 时长未知时不画进度条，避免展示假进度
            progressBar.visibility = View.GONE
            timeTotal.visibility = View.GONE
            timeCurrent.text = formatTime(positionMs)
        } else {
            if (positionMs > durationMs) positionMs = 0
            renderProgress()
        }
        updatePlayIcon()
    }

    /**
     * 应用 SystemUI 推来的曲目更新。
     * 同曲的每秒进度推送不带封面，因此只在换曲时才替换封面/歌手，避免把封面刷没。
     */
    private fun applyTrack(i: Intent) {
        val title = i.getStringExtra(ArkConstants.EXTRA_PANEL_TITLE)?.takeIf { it.isNotBlank() }
        var titleChanged = false
        var dir = 1
        if (title != null && title != titleView.text.toString()) {
            titleChanged = true
            // v3.15.1：换曲后收藏状态归零为「未定」，等 SystemUI 推新曲真值，别继承上一首
            favKnown = false
            // v3.15.4：换曲即作废「点击后 5 秒不看推送」的锁。
            // 那把锁只防「同一首」通知滞后把刚点的 ❤ 冲掉；换到新歌还锁着，
            // 会让新歌的状态白等 5 秒（用户实测「切歌后要好几秒」的成因之一）。
            lastFavAt = 0L
            // 换曲方向：按用户按的是上一首还是下一首决定封面滑入方向
            dir = pendingDir
            pendingDir = 1
            pendingTitle = null
            handler.removeCallbacks(switchWatchdog)
            handler.removeCallbacks(syncRequest)
            titleView.text = title
        }

        val artist = i.getStringExtra(ArkConstants.EXTRA_PANEL_ARTIST).orEmpty()
        if (artist.isNotBlank()) {
            artistView.text = artist
            artistView.visibility = View.VISIBLE
        }

        // 换曲 → 轮播滑动；同曲补封面 → 直接替换
        coverOf(i)?.let { bmp -> applyCover(bmp, if (titleChanged) dir else 0) }

        // v3.15：换到另一个播放器时更新右上角 App 图标与跳转兜底
        if (i.hasExtra(ArkConstants.EXTRA_PANEL_APP_PKG)) {
            val p = i.getStringExtra(ArkConstants.EXTRA_PANEL_APP_PKG)
            if (p != mediaPkg) {
                mediaPkg = p
                applyAppIcon()
            }
        }

        durationMs = i.getIntExtra(ArkConstants.EXTRA_PANEL_DURATION, -1)
        positionMs = i.getIntExtra(ArkConstants.EXTRA_PANEL_PROGRESS, -1).coerceAtLeast(0)
        playing = i.getBooleanExtra(ArkConstants.EXTRA_PANEL_PLAYING, playing)
        clickIntentOf(i)?.let { lastClickIntent = it }
        setNight(i.getBooleanExtra(ArkConstants.EXTRA_PANEL_NIGHT, nightMode))
        // v3.13.3：SystemUI 从通知按钮标题推来的收藏状态。
        // QQ 音乐按钮标题变化滞后 1~5 秒（随通知刷新才更新），点击后的首轮推送
        // 反映的是「点击前」的状态，会把你刚点亮的 ❤ 重置回去（实测 bug）。
        // 所以点击后 6 秒内忽略推送，只信本地乐观状态；6 秒后推送即可纠正真实漂移。
        if (i.hasExtra(ArkConstants.EXTRA_PANEL_FAV)) {
            val sinceClick = android.os.SystemClock.uptimeMillis() - lastFavAt
            // v3.15.7：FINAL 是 SystemUI 的确认结论（成功/失败都有），必须直接采信；
            // 否则点击锁期间失败结论会被推送锁挡住，面板一直停在错误状态。
            val isFinal = i.getBooleanExtra(ArkConstants.EXTRA_PANEL_FAV_FINAL, false)
            if (isFinal || sinceClick > FAV_PUSH_LOCK_MS) {
                val s = i.getBooleanExtra(ArkConstants.EXTRA_PANEL_FAV, false)
                favKnown = true
                if (s != favState) {
                    favState = s
                    updateFavIcon()
                }
            }
        }

        if (durationMs <= 0) {
            progressBar.visibility = View.GONE
            timeTotal.visibility = View.GONE
            timeCurrent.text = formatTime(positionMs)
        } else {
            progressBar.visibility = View.VISIBLE
            timeTotal.visibility = View.VISIBLE
            if (positionMs > durationMs) positionMs = 0
            renderProgress()
        }
        updatePlayIcon()
        restartTicker()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        closing = false
        handler.removeCallbacksAndMessages(null)
        bindData()
        // 面板已经开着了，换一批数据即可；重新播一遍展开会让画面闪，
        // 只有在还没走完入场动画时才补一次。
        if (card.alpha < 1f) {
            card.alpha = 1f
            morph.expand()
        }
        restartTicker()
    }

    @Suppress("DEPRECATION")
    private fun coverOf(i: Intent): Bitmap? =
        try {
            i.getParcelableExtra(ArkConstants.EXTRA_PANEL_COVER) as? Bitmap
        } catch (_: Throwable) {
            null
        }

    @Suppress("DEPRECATION")
    private fun clickIntentOf(i: Intent): PendingIntent? =
        try {
            i.getParcelableExtra(ArkConstants.EXTRA_PANEL_CLICK_INTENT) as? PendingIntent
        } catch (_: Throwable) {
            null
        }

    // ------------------------------------------------------------------
    // 封面轮播 + 专辑取色（v3.12）
    // ------------------------------------------------------------------

    private fun frontView(): ImageView = if (frontIsA) coverA else coverB

    private fun backView(): ImageView = if (frontIsA) coverB else coverA

    /** 首次显示 / onNewIntent 整批重绑：不做动画，直接落在 A 位 */
    private fun resetCover(bmp: Bitmap) {
        coverA.animate().cancel()
        coverB.animate().cancel()
        frontIsA = true
        coverA.setImageBitmap(bmp)
        coverA.visibility = View.VISIBLE
        coverA.translationX = 0f
        coverB.visibility = View.GONE
        coverB.translationX = 0f
        applyAlbumColor(bmp)
    }

    /**
     * 换封面。
     *
     * @param dir +1 下一首：新封面从右边滑入、旧的向左滑出；
     *            -1 上一首：方向相反；
     *             0 同曲补封面：直接替换，不滑动
     */
    private fun applyCover(bmp: Bitmap, dir: Int) {
        if (dir == 0 || coverSlot.width <= 0) {
            resetCover(bmp)
            return
        }
        applyAlbumColor(bmp)

        val w = coverSlot.width.toFloat()
        val outgoing = frontView()
        val incoming = backView()

        incoming.animate().cancel()
        outgoing.animate().cancel()

        incoming.setImageBitmap(bmp)
        incoming.visibility = View.VISIBLE
        incoming.translationX = dir * w

        outgoing.animate()
            .translationX(-dir * w)
            .setDuration(COVER_SLIDE_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        incoming.animate()
            .translationX(0f)
            .setDuration(COVER_SLIDE_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        frontIsA = !frontIsA

        // 归还旧封面：若这期间又换过曲，它可能已经重新上场，不能再藏
        handler.postDelayed({
            if (outgoing !== frontView()) {
                outgoing.animate().cancel()
                outgoing.translationX = 0f
                outgoing.visibility = View.GONE
            }
        }, COVER_SLIDE_MS + 60)
    }

    /** 专辑取色 → 进度条主题色 */
    private fun applyAlbumColor(bmp: Bitmap) {
        val color = ArkAlbumColor.vibrantOf(bmp)
        albumColor = color
        color?.let { progressBar.barColor = it }
    }

    /**
     * SystemUI 推来的深浅模式（与媒体卡玻璃同源：SystemUICommonUtils.sIsNightMode）。
     * 变化时整套配色 + 玻璃染色一起重刷。
     */
    private fun setNight(night: Boolean) {
        if (night == nightMode) return
        nightMode = night
        // 广播可能在 bindViews 之前到达，此时 lateinit view 还没绑定
        if (!this::titleView.isInitialized) return
        // 主题切换常常伴随壁纸切换，重新采样一次以刷新玻璃浓度
        ArkLiquidGlass.invalidateCache()
        paletteGlass = ArkLiquidGlass.resolve(this, nightMode)
        applyPalette()
    }

    // ------------------------------------------------------------------
    // 液态玻璃配色（v3.15.42：6 层复合，深浅浓度按背景采样插值）
    // ------------------------------------------------------------------

    private fun applyPalette() {
        if (!this::paletteGlass.isInitialized) {
            paletteGlass = ArkLiquidGlass.resolve(this, nightMode)
        }
        val p = paletteGlass
        // 卡片本体：morph.prepare 之后由 ArkLiquidGlassDrawable 接管
        if (this::morph.isInitialized) morph.setGlass(p)

        val title = if (p.light) 0xE6000000.toInt() else Color.WHITE
        val sub = if (p.light) 0x99000000.toInt() else 0xB3FFFFFF.toInt()
        val time = if (p.light) 0x80000000.toInt() else 0x8AFFFFFF.toInt()
        val icon = if (p.light) 0xE6000000.toInt() else Color.WHITE

        // 第 6 层：文字着色（浅玻璃正片叠底 / 深玻璃叠加提亮）
        titleView.setTextColor(ArkLiquidGlass.ink(title, p))
        artistView.setTextColor(ArkLiquidGlass.ink(sub, p))
        timeCurrent.setTextColor(ArkLiquidGlass.ink(time, p))
        timeTotal.setTextColor(ArkLiquidGlass.ink(time, p))
        val iconInk = ArkLiquidGlass.ink(icon, p)
        prevBtn.imageTintList = ColorStateList.valueOf(iconInk)
        nextBtn.imageTintList = ColorStateList.valueOf(iconInk)
        playBtn.imageTintList = ColorStateList.valueOf(iconInk)
        outputBtn.imageTintList = ColorStateList.valueOf(iconInk)
        favBtn.imageTintList = ColorStateList.valueOf(
            // 已收藏：跟专辑主题色走（系统媒体卡同款思路），未收藏用玻璃 ink 色
            if (favKnown && favState) albumColor ?: iconInk else iconInk
        )

        // 播放器主按钮：同一套玻璃材质做成圆形（58dp / 2 半径）
        ArkLiquidGlass.attach(playBtn, p, 29f * resources.displayMetrics.density)
        // 进度条轨道也吃玻璃 ink，避免死白/死黑
        progressBar.trackColor = ArkLiquidGlass.ink(
            if (p.light) 0x26000000 else 0x33FFFFFF.toInt(), p
        )
    }

    // ------------------------------------------------------------------
    // seek / 跳转（v3.13）
    // ------------------------------------------------------------------

    /** 拖动进度条 → SystemUI（system uid）调 MediaController.seekTo */
    private fun sendSeek(ms: Int) {
        try {
            sendBroadcast(
                Intent(ArkConstants.ACTION_PANEL_SEEK)
                    .setPackage(ArkConstants.SYSTEMUI_PKG)
                    .putExtra(ArkConstants.EXTRA_SEEK_POSITION, ms.toLong())
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * 点卡片空白处 → 拉起媒体 App。
     * 优先 sessionActivity（系统媒体卡同款行为）；QQ 音乐实测不设置它
     * （dumpsys media_session: launchIntent=null），兜底按包名拉主界面。
     */
    private fun openMusicApp() {
        val now = SystemClock.uptimeMillis()
        if (now - lastOpenAppAt < OPEN_APP_DEBOUNCE_MS) return
        lastOpenAppAt = now

        val pi = lastClickIntent
        if (pi != null) {
            try {
                pi.send(applicationContext, 0, null)
                return
            } catch (_: Throwable) {
            }
        }
        try {
            packageManager.getLaunchIntentForPackage(
                mediaPkg ?: ArkConstants.MEDIA_APP_PKG
            )?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
            }
        } catch (_: Throwable) {
        }
    }

    private fun bindActions() {
        prevBtn.setOnClickListener {
            if (!sendKeyThrottled(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KEY_THROTTLE_MS)) return@setOnClickListener
            onTrackSwitched(-1)
        }
        nextBtn.setOnClickListener {
            if (!sendKeyThrottled(KeyEvent.KEYCODE_MEDIA_NEXT, KEY_THROTTLE_MS)) return@setOnClickListener
            onTrackSwitched(1)
        }
        playBtn.setOnClickListener {
            // v3.13.2：发给 SystemUI 的是「目标状态」(play/pause)，不是 PLAY_PAUSE。
            // toggle 媒体键连点两次会被播放器判成线控双击 → 下一首。
            val target = !playing
            if (!sendPlaybackThrottled(target)) return@setOnClickListener
            playing = target
            updatePlayIcon()
            restartTicker()
        }
        favBtn.setOnClickListener {
            // v3.13.3：收藏 = 触发播放器通知上的「喜欢」按钮。
            // v3.15.7：点击锁 —— 播放器的 toggle 有限流，连点只有第一次生效；
            // 我们若跟着连点，UI 与音乐端必然错位。锁定期内直接不响应（UI 也不动）。
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastFavAt < FAV_CLICK_LOCK_MS) return@setOnClickListener
            lastFavAt = now
            val target = if (favKnown) !favState else true
            favState = target
            favKnown = true
            updateFavIcon()
            sendFavorite(target)
        }
        outputBtn.setOnClickListener {
            // v3.15：请求 SystemUI 列出当前媒体 App 的输出路由（MediaRouter2Manager）
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastOutputAt < 800L) return@setOnClickListener
            lastOutputAt = now
            try {
                sendBroadcast(
                    Intent(ArkConstants.ACTION_PANEL_OUTPUT_LIST)
                        .setPackage(ArkConstants.SYSTEMUI_PKG)
                )
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * SystemUI 回来的输出路由列表 → 弹窗选择。
     * selected=-1 表示当前路由未知，列表只展示不高亮。
     */
    private fun showRouteDialog(i: Intent) {
        val names = try {
            i.getStringArrayListExtra(ArkConstants.EXTRA_ROUTE_NAMES).orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        val ids = try {
            i.getStringArrayListExtra(ArkConstants.EXTRA_ROUTE_IDS).orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        val selected = i.getIntExtra(ArkConstants.EXTRA_ROUTE_SELECTED, -1)

        if (names.isEmpty() || names.size != ids.size) {
            Toast.makeText(this, R.string.ark_panel_output_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val theme =
            if (nightMode) android.R.style.Theme_DeviceDefault_Dialog
            else android.R.style.Theme_DeviceDefault_Light_Dialog
        AlertDialog.Builder(this, theme)
            .setTitle(getString(R.string.ark_panel_output))
            .setSingleChoiceItems(names.toTypedArray(), selected) { d, which ->
                d.dismiss()
                if (which != selected && which in ids.indices) {
                    try {
                        sendBroadcast(
                            Intent(ArkConstants.ACTION_PANEL_OUTPUT_TRANSFER)
                                .setPackage(ArkConstants.SYSTEMUI_PKG)
                                .putExtra(ArkConstants.EXTRA_ROUTE_ID, ids[which])
                        )
                    } catch (_: Throwable) {
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 收藏请求 → SystemUI（system uid）send QQ 音乐通知 action */
    private fun sendFavorite(target: Boolean) {
        try {
            sendBroadcast(
                Intent(ArkConstants.ACTION_PANEL_FAVORITE)
                    .setPackage(ArkConstants.SYSTEMUI_PKG)
                    .putExtra(ArkConstants.EXTRA_FAV_TARGET, target)
            )
        } catch (_: Throwable) {
        }
    }

    private fun updateFavIcon() {
        val faved = favKnown && favState
        favBtn.setImageResource(if (faved) R.drawable.ic_ark_fav_filled else R.drawable.ic_ark_fav)
        // 状态未定：空心 + 半透明，明确表达「还没拿到真值」，而不是假装修未收藏
        favBtn.alpha = if (favKnown) 1f else 0.42f
        // 已收藏用专辑主题色点亮（系统媒体卡同款思路）；未收藏用普通图标色
        val plain = if (nightMode) Color.WHITE else 0xE6000000.toInt()
        favBtn.imageTintList = ColorStateList.valueOf(
            if (faved) albumColor ?: plain else plain
        )
        favBtn.contentDescription =
            getString(if (faved) R.string.ark_panel_faved else R.string.ark_panel_fav)
    }

    /**
     * 带节流的媒体键派发（v3.13.1）。
     *
     * 面板按钮是「发一次广播、SystemUI 转发 IPC」的链路，连点会连发按键：
     * 上一首/下一首连点会一次跳好几首。节流窗口按按键各自记录——
     * 上一首的节流窗口不能挡住紧接着的暂停（这正是「按钮失效」的成因）。
     * 窗口内直接丢弃，不做排队补发。
     *
     * @return true = 本次按键已派发；false = 处于节流窗口内，已忽略
     */
    private fun sendKeyThrottled(keyCode: Int, windowMs: Long): Boolean {
        val now = SystemClock.uptimeMillis()
        val last = lastKeyAt[keyCode] ?: 0L
        if (now - last < windowMs) return false
        lastKeyAt[keyCode] = now
        sendMediaKey(keyCode)
        return true
    }

    /**
     * v3.13.2：显式播放/暂停请求（幂等）。
     *
     * 按目标状态各自防抖：`pause` 与 `play` 互不阻塞，所以「暂停→马上播放」
     * 不会被上一次 pause 的窗口吃掉；同一目标在窗口内重复点击直接忽略
     * （连续 pause 本来就是同一结果，没有必要再发一次）。
     */
    private fun sendPlaybackThrottled(target: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        val last = lastPlaybackAt[target] ?: 0L
        if (now - last < PLAY_THROTTLE_MS) return false
        lastPlaybackAt[target] = now
        try {
            sendBroadcast(
                Intent(ArkConstants.ACTION_PANEL_PLAYBACK)
                    .setPackage(ArkConstants.SYSTEMUI_PKG)
                    .putExtra(ArkConstants.EXTRA_PLAY_TARGET, target)
            )
        } catch (_: Throwable) {
        }
        return true
    }

    /** 交给 SystemUI（system uid）派发，命中率远高于普通 App 直接调用 */
    private fun sendMediaKey(keyCode: Int) {
        try {
            sendBroadcast(
                Intent(ArkConstants.ACTION_PANEL_MEDIA_KEY)
                    .setPackage(ArkConstants.SYSTEMUI_PKG)
                    .putExtra(ArkConstants.EXTRA_KEY_CODE, keyCode)
            )
        } catch (_: Throwable) {
        }
    }

    private fun onTrackSwitched(dir: Int) {
        // v3.1：不再收起面板。切歌后先请 SystemUI 直接读 MediaSession 同步一次
        // （毫秒级），同步不到再等歌词通知推送；6 秒仍没换曲才判定失败并收起。
        pendingTitle = titleView.text.toString()
        pendingDir = dir
        positionMs = 0
        timeCurrent.text = formatTime(0)
        collapseProgress()
        handler.removeCallbacks(switchWatchdog)
        handler.postDelayed(switchWatchdog, 6_000)
        // 两次请求：第一次可能赶在媒体键生效之前，补一次兜底
        handler.postDelayed(syncRequest, 250)
        handler.postDelayed(syncRequest, 900)
    }

    /**
     * 切歌时进度条滑回起点（v3.13.1）。
     * 硬归零观感是「卡一下突然清零」；220ms 滑动回零顺滑自然，
     * 新曲目推送到达时 renderProgress 会从真实位置接管（两者都从小值开始，无跳变）。
     */
    private fun collapseProgress() {
        val from = progressBar.ratio
        if (from <= 0f) return
        progressBar.isCollapsing = true
        ValueAnimator.ofFloat(from, 0f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                progressBar.ratio = a.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    progressBar.isCollapsing = false
                }
            })
            start()
        }
    }

    /** 让 SystemUI 立即从 MediaSession 读一次曲目（标题/歌手/专辑图/进度） */
    private fun requestSync() {
        try {
            sendBroadcast(
                Intent(ArkConstants.ACTION_PANEL_SYNC)
                    .setPackage(ArkConstants.SYSTEMUI_PKG)
            )
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    // 状态渲染
    // ------------------------------------------------------------------

    private fun renderProgress() {
        if (durationMs <= 0) return
        progressBar.durationMs = durationMs
        if (!progressBar.isScrubbing && !progressBar.isCollapsing) {
            // 拖动中/归零动画中：显示由各自驱动，秒表不要覆盖
            progressBar.ratio = positionMs.toFloat() / durationMs.toFloat()
            timeCurrent.text = formatTime(positionMs)
        }
        timeTotal.text = formatTime(durationMs)
    }

    private fun updatePlayIcon() {
        playBtn.setImageResource(if (playing) R.drawable.ic_ark_pause else R.drawable.ic_ark_play)
        playBtn.contentDescription =
            getString(if (playing) R.string.ark_panel_pause else R.string.ark_panel_play)
    }

    /** 重启本地秒表（播放态 / 时长变了都要重排） */
    private fun restartTicker() {
        handler.removeCallbacks(ticker)
        if (playing && durationMs > 0) handler.postDelayed(ticker, 1_000)
    }

    private fun formatTime(ms: Int): String {
        val total = (ms.coerceAtLeast(0)) / 1000
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    // ------------------------------------------------------------------
    // 动画（几何计算全部在 ArkMorphAnim 里）
    // ------------------------------------------------------------------

    private fun dismiss() {
        if (closing) return
        closing = true
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(switchWatchdog)
        handler.removeCallbacks(syncRequest)
        // 卡片缩回胶囊的中途让状态栏胶囊淡入，两个动作重叠，
        // 视觉上是「卡片收回胶囊里」，而不是「卡片消失后胶囊凭空出现」。
        handler.postDelayed({ publishVisibility(false) }, 90L)
        morph.collapse { finish() }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        // 让 SystemUI 把状态栏胶囊淡出：视觉上由胶囊「展开」成本面板
        publishVisibility(true)
        // 打开时补一次精确同步（封面走 MediaSession 的专辑图，进度也对齐）
        handler.postDelayed(syncRequest, 350)
    }

    override fun onStop() {
        super.onStop()
        publishVisibility(false)
        // 切到别的应用/锁屏后不留悬浮残影
        if (!isChangingConfigurations) {
            handler.removeCallbacks(ticker)
            handler.removeCallbacks(switchWatchdog)
            finish()
        }
    }

    override fun onDestroy() {
        publishVisibility(false)
        if (this::morph.isInitialized) morph.stop()
        try {
            unregisterReceiver(trackReceiver)
        } catch (_: Throwable) {
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** 面板可见性 → SystemUI（用于淡出/恢复状态栏胶囊） */
    private fun publishVisibility(visible: Boolean) {
        try {
            sendBroadcast(
                Intent(ArkConstants.ACTION_PANEL_STATE)
                    .setPackage(ArkConstants.SYSTEMUI_PKG)
                    .putExtra(ArkConstants.EXTRA_PANEL_VISIBLE, visible)
            )
        } catch (_: Throwable) {
        }
    }

    @Deprecated("Activity 级别返回键处理")
    override fun onBackPressed() {
        dismiss()
    }
}

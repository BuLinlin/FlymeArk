package com.flymeark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.flymeark.R
import java.util.Locale

/** 封面轮播单程时长 */
private const val COVER_SLIDE_MS = 340L

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

    private lateinit var morph: ArkMorphAnim

    private var durationMs = 0
    private var positionMs = 0
    private var playing = false
    private var closing = false

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

    /** SystemUI 推来的曲目更新（换曲 / 进度 / 播放态），面板保持展开实时刷新 */
    private val trackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ArkConstants.ACTION_PANEL_TRACK) return
            applyTrack(intent)
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

        setContentView(R.layout.activity_capsule_panel)
        registerTrackReceiver()
        bindViews()
        bindData()
        bindActions()

        morph = ArkMorphAnim(root, card, content)
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
        val filter = IntentFilter(ArkConstants.ACTION_PANEL_TRACK)
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

        // 点空白收起；卡片本身消费点击
        root.setOnClickListener { dismiss() }
        card.setOnClickListener { }
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

        durationMs = i.getIntExtra(ArkConstants.EXTRA_PANEL_DURATION, -1)
        positionMs = i.getIntExtra(ArkConstants.EXTRA_PANEL_PROGRESS, -1).coerceAtLeast(0)
        playing = i.getBooleanExtra(ArkConstants.EXTRA_PANEL_PLAYING, true)

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

        durationMs = i.getIntExtra(ArkConstants.EXTRA_PANEL_DURATION, -1)
        positionMs = i.getIntExtra(ArkConstants.EXTRA_PANEL_PROGRESS, -1).coerceAtLeast(0)
        playing = i.getBooleanExtra(ArkConstants.EXTRA_PANEL_PLAYING, playing)

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

    /** 专辑取色 → 进度条主题色（取不出时保持原色） */
    private fun applyAlbumColor(bmp: Bitmap) {
        ArkAlbumColor.vibrantOf(bmp)?.let { progressBar.barColor = it }
    }

    private fun bindActions() {
        findViewById<View>(R.id.panel_prev).setOnClickListener {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            onTrackSwitched(-1)
        }
        findViewById<View>(R.id.panel_next).setOnClickListener {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            onTrackSwitched(1)
        }
        playBtn.setOnClickListener {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            playing = !playing
            updatePlayIcon()
            restartTicker()
        }
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
        progressBar.ratio = 0f
        handler.removeCallbacks(switchWatchdog)
        handler.postDelayed(switchWatchdog, 6_000)
        // 两次请求：第一次可能赶在媒体键生效之前，补一次兜底
        handler.postDelayed(syncRequest, 250)
        handler.postDelayed(syncRequest, 900)
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
        progressBar.ratio = positionMs.toFloat() / durationMs.toFloat()
        timeCurrent.text = formatTime(positionMs)
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

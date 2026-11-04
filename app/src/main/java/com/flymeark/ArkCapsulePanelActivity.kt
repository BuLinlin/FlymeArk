package com.flymeark

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.flymeark.R
import java.util.Locale

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
    private lateinit var coverView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var progressBar: ArkProgressBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var playBtn: ImageView

    private var durationMs = 0
    private var positionMs = 0
    private var playing = false
    private var closing = false

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (!playing || durationMs <= 0) return
            positionMs = (positionMs + 1_000).coerceAtMost(durationMs)
            renderProgress()
            handler.postDelayed(this, 1_000)
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
        bindViews()
        bindData()
        bindActions()
        placeBelowStatusBar()
        animateIn()
    }

    private fun bindViews() {
        root = findViewById(R.id.panel_root)
        card = findViewById(R.id.panel_card)
        coverView = findViewById(R.id.panel_cover)
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

        coverOf(i)?.let { coverView.setImageBitmap(it) }

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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        closing = false
        handler.removeCallbacksAndMessages(null)
        bindData()
        animateIn()
    }

    @Suppress("DEPRECATION")
    private fun coverOf(i: Intent): Bitmap? =
        try {
            i.getParcelableExtra(ArkConstants.EXTRA_PANEL_COVER) as? Bitmap
        } catch (_: Throwable) {
            null
        }

    private fun bindActions() {
        findViewById<View>(R.id.panel_prev).setOnClickListener {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            onTrackSwitched()
        }
        findViewById<View>(R.id.panel_next).setOnClickListener {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            onTrackSwitched()
        }
        playBtn.setOnClickListener {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            playing = !playing
            updatePlayIcon()
            if (playing) handler.postDelayed(ticker, 1_000) else handler.removeCallbacks(ticker)
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

    private fun onTrackSwitched() {
        positionMs = 0
        renderProgress()
        // 新曲目信息由 SystemUI 侧缓存，这里不主动刷新，先收起避免展示旧曲目
        handler.postDelayed({ dismiss() }, 450)
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

    private fun formatTime(ms: Int): String {
        val total = (ms.coerceAtLeast(0)) / 1000
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    // ------------------------------------------------------------------
    // 窗口位置与动画
    // ------------------------------------------------------------------

    /** 卡片贴状态栏下沿出现，像从胶囊垂下来，同时不遮挡时间/信号 */
    private fun placeBelowStatusBar() {
        val lp = card.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = statusBarHeight() + dp(4)
        card.layoutParams = lp
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(30)
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun animateIn() {
        card.post {
            card.translationY = -card.height * 0.55f
            card.alpha = 0f
            card.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(280L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
            if (playing) handler.postDelayed(ticker, 1_000)
        }
    }

    private fun dismiss() {
        if (closing) return
        closing = true
        handler.removeCallbacks(ticker)
        card.animate()
            .translationY(-card.height * 0.45f)
            .alpha(0f)
            .setDuration(170L)
            .withEndAction { finish() }
            .start()
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onStop() {
        super.onStop()
        // 切到别的应用/锁屏后不留悬浮残影
        if (!isChangingConfigurations) {
            handler.removeCallbacks(ticker)
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Activity 级别返回键处理")
    override fun onBackPressed() {
        dismiss()
    }
}

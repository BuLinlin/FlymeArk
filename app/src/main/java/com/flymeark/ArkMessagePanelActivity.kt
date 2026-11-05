package com.flymeark

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.flymeark.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.15.39：点击消息胶囊（微信 / QQ）后从状态栏下方展开的消息大卡。
 *
 * 与 [ArkCapsulePanelActivity]（音乐面板）完全同构：
 *  - 窗口：顶部悬浮、满屏透明（点空白收起）、不压暗背景；
 *  - 动画：[ArkMorphAnim]「胶囊长大成卡片」，收起时缩回胶囊位置；
 *  - 联动：onResume / 收起时广播 [ArkConstants.ACTION_PANEL_STATE]，
 *    SystemUI 侧同一套逻辑负责淡出 / 恢复胶囊。
 *
 * 数据由 [ArkLiveExpandHook] 在 SystemUI 进程读取原通知后随 Intent 带入
 * （SystemUI 有权限读通知的 extras / largeIcon / contentIntent，本进程没有）。
 *
 * 隐私：**不设 showWhenLocked** —— 锁屏上点胶囊不弹卡，
 * 聊天正文只会在解锁后的展开卡里出现（胶囊本体仍是「有新消息 · 点击查看」占位）。
 */
class ArkMessagePanelActivity : Activity() {

    private lateinit var root: View
    private lateinit var card: View
    private lateinit var content: View
    private lateinit var avatarView: ImageView
    private lateinit var titleView: TextView
    private lateinit var timeView: TextView
    private lateinit var textView: TextView
    private lateinit var hintView: TextView
    private lateinit var dotView: View
    private lateinit var appIconView: ImageView

    private lateinit var morph: ArkMorphAnim

    private val handler = Handler(Looper.getMainLooper())

    private var clickIntent: PendingIntent? = null
    private var brandColor: Int = 0xFF333333.toInt()
    private var nightMode = false
    private var closing = false

    /** 点卡片进会话的防抖（避免连点拉起多个会话页） */
    private var lastOpenAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与音乐面板同一套窗口参数
        window.setGravity(Gravity.TOP)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.attributes = window.attributes.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }

        setContentView(R.layout.activity_message_panel)
        bindViews()
        bindData()
        bindActions()

        morph = ArkMorphAnim(
            root, card, content,
            if (nightMode) 0xC216161A.toInt() else 0xC9FAFAFA.toInt(),
            if (nightMode) 0x24FFFFFF.toInt() else 0x1F000000.toInt()
        )
        card.alpha = 0f
        root.post {
            morph.prepare(capsuleRect())
            root.post {
                card.alpha = 1f
                morph.expand()
            }
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.msg_root)
        card = findViewById(R.id.msg_card)
        content = findViewById(R.id.msg_content)
        avatarView = findViewById(R.id.msg_avatar)
        titleView = findViewById(R.id.msg_title)
        timeView = findViewById(R.id.msg_time)
        textView = findViewById(R.id.msg_text)
        hintView = findViewById(R.id.msg_hint)
        dotView = findViewById(R.id.msg_dot)
        appIconView = findViewById(R.id.msg_app_icon)

        nightMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        applyPalette()
    }

    private fun bindData() {
        val i = intent ?: return

        val title = i.getStringExtra(ArkConstants.EXTRA_MSG_TITLE)
            ?.takeIf { it.isNotBlank() }
        titleView.text = title ?: getString(R.string.ark_msg_unknown)

        val text = i.getStringExtra(ArkConstants.EXTRA_MSG_TEXT).orEmpty()
        textView.text = text
        textView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE

        val postAt = i.getLongExtra(ArkConstants.EXTRA_MSG_TIME, 0L)
        timeView.text = relativeTime(postAt)
        timeView.visibility = if (postAt <= 0L) View.GONE else View.VISIBLE

        brandColor = i.getIntExtra(ArkConstants.EXTRA_MSG_BRAND, brandColor)
        (dotView.background as? GradientDrawable)?.setColor(brandColor)
            ?: run { dotView.setBackgroundColor(brandColor) }

        // 头像：圆形裁剪 + 品牌色底（Icon 解码失败时至少有色块不空洞）
        val avatar: Bitmap? =
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(ArkConstants.EXTRA_MSG_AVATAR, Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                i.getParcelableExtra(ArkConstants.EXTRA_MSG_AVATAR)
            }
        avatarView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(brandColor)
        }
        if (avatar != null) avatarView.setImageBitmap(avatar)

        // 右上角应用图标
        val pkg = i.getStringExtra(ArkConstants.EXTRA_PANEL_APP_PKG)
        if (pkg != null) {
            try {
                appIconView.setImageDrawable(packageManager.getApplicationIcon(pkg))
            } catch (_: Throwable) {
                appIconView.visibility = View.GONE
            }
        } else {
            appIconView.visibility = View.GONE
        }

        // 底部提示：应用名 · 点击进入会话
        val label = ArkConstants.messageAppLabel(pkg)
        hintView.text = "$label · ${getString(R.string.ark_msg_hint_open)}"

        clickIntent =
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(ArkConstants.EXTRA_PANEL_CLICK_INTENT, PendingIntent::class.java)
            } else {
                @Suppress("DEPRECATION")
                i.getParcelableExtra(ArkConstants.EXTRA_PANEL_CLICK_INTENT)
            }

        if (i.hasExtra(ArkConstants.EXTRA_PANEL_NIGHT)) {
            nightMode = i.getBooleanExtra(ArkConstants.EXTRA_PANEL_NIGHT, nightMode)
            applyPalette()
        }
    }

    private fun bindActions() {
        root.setOnClickListener { dismiss() }
        card.setOnClickListener { openConversation() }
    }

    /** 深浅模式配色（与音乐面板 applyPalette 同一套） */
    private fun applyPalette() {
        val cardBg = if (nightMode) 0xC216161A.toInt() else 0xC9FAFAFA.toInt()
        val stroke = if (nightMode) 0x24FFFFFF.toInt() else 0x1F000000.toInt()
        if (this::morph.isInitialized) morph.setColors(cardBg, stroke)
        (card.background as? GradientDrawable)?.apply {
            setColor(cardBg)
            setStroke((resources.displayMetrics.density * 1).toInt(), stroke)
        }

        val title = if (nightMode) Color.WHITE else 0xE6000000.toInt()
        val sub = if (nightMode) 0xB3FFFFFF.toInt() else 0x99000000.toInt()
        val body = if (nightMode) 0xE6FFFFFF.toInt() else 0xD9000000.toInt()
        val hint = if (nightMode) 0x8AFFFFFF.toInt() else 0x80000000.toInt()

        titleView.setTextColor(title)
        timeView.setTextColor(sub)
        textView.setTextColor(body)
        hintView.setTextColor(hint)
        appIconView.imageTintList = ColorStateList.valueOf(sub)
    }

    /** 点卡片 → 发送原通知的 contentIntent（进会话）→ 收起 */
    private fun openConversation() {
        val now = SystemClock.uptimeMillis()
        if (now - lastOpenAt < 800L) return
        lastOpenAt = now

        val pi = clickIntent
        if (pi != null) {
            try {
                pi.send(applicationContext, 0, null)
            } catch (_: Throwable) {
                openAppFallback()
            }
        } else {
            openAppFallback()
        }
        dismiss()
    }

    private fun openAppFallback() {
        try {
            val pkg = intent?.getStringExtra(ArkConstants.EXTRA_PANEL_APP_PKG) ?: return
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
            }
        } catch (_: Throwable) {
        }
    }

    private fun dismiss() {
        if (closing) return
        closing = true
        // 卡片缩回胶囊的中途让状态栏胶囊淡入（与音乐面板同一节奏）
        handler.postDelayed({ publishVisibility(false) }, 90L)
        morph.collapse { finish() }
    }

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

    /** 相对时间：刚刚 / N 分钟前 / 当天 hh:mm / MM-dd hh:mm */
    private fun relativeTime(postAt: Long): String {
        if (postAt <= 0L) return ""
        val delta = System.currentTimeMillis() - postAt
        return when {
            delta < 60_000L -> "刚刚"
            delta < 3_600_000L -> "${delta / 60_000L} 分钟前"
            else -> {
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
                    .format(Date(postAt)) ==
                    SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                if (sameDay) {
                    fmt.format(Date(postAt))
                } else {
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(postAt))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        publishVisibility(true)
    }

    override fun onStop() {
        super.onStop()
        publishVisibility(false)
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        publishVisibility(false)
        if (this::morph.isInitialized) morph.stop()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Activity 级别返回键处理")
    override fun onBackPressed() {
        dismiss()
    }
}

package com.flymeark

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
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

    /** v3.15.46：CTA 底板 / chevron（按 p.light 运行时下发，见 applyPalette） */
    private lateinit var ctaView: View
    private lateinit var chevronView: ImageView

    /**
     * v3.15.46：CTA 底板用自绘 [ArkChipDrawable]。
     * 系统深色模式会把浅色 ShapeDrawable / ColorDrawable 反色（实测 #FFE7EEFF → #FF171102），
     * 自绘 Drawable 不受影响，才能保证「浅底 + 深字」始终成对出现。
     */
    private lateinit var ctaChip: ArkChipDrawable

    private lateinit var morph: ArkMorphAnim

    private val handler = Handler(Looper.getMainLooper())

    private var clickIntent: PendingIntent? = null
    private var brandColor: Int = 0xFF333333.toInt()
    private var nightMode = false
    private var closing = false

    /** v3.15.42：液态玻璃参数，由 bindViews 采样一次，morph 与文字着色共用 */
    private lateinit var glassParams: GlassParams

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

        morph = ArkMorphAnim(root, card, content, glassParams)
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
        ctaView = findViewById(R.id.msg_cta)
        chevronView = findViewById(R.id.msg_chevron)

        // v3.15.50：时间「刚刚」加 12% 二级色**全圆角**胶囊底（设计稿 rgba(78,87,102,0.12)）。
        // 用 ArkChipDrawable 的 pill 模式（bounds 变化时按短边一半重算半径），
        // 而不是新加一个 shape 资源 —— 与 CTA 底板走同一套自绘，绕开 Flyme 深色模式下
        // framework 形状 drawable 被反色的问题。
        timeView.background = ArkChipDrawable(
            fillColor = ArkTokens.TIME_CHIP_FILL,
            radiusPx = 0f,
        ).apply { pill = true }

        // v3.15.46：CTA 底板用自绘圆角 chip（原因见 ArkChipDrawable 的类注释）：
        // 填充 = brandContainer @60%（设计稿 `copy(alpha = 0.6f)`），无描边。
        ctaChip = ArkChipDrawable(
            fillColor = ArkTokens.BRAND_CONTAINER_FILL_LIGHT,
            radiusPx = ArkTokens.RADIUS_CHIP_DP * resources.displayMetrics.density,
        )
        ctaView.background = ctaChip

        nightMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        glassParams = ArkLiquidGlass.resolve(this, nightMode)
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
        // v3.15.46：CTA 品牌圆点的颜色改由 applyPalette() 统一按品牌 token 下发
        // （原逻辑用通知里的 App 主色，浅色 App 主色压在 brandContainer@60% 底上会不可见）。
        // 头像占位块仍用 App 主色，保证取不到头像时也能区分来源应用。

        // 头像：v3.15.46 按设计稿改成 40dp + RadiusChip(10dp) 圆角块（原 54dp 正圆），
        // 位图同样按 10dp 圆角裁切；底色用通知里的 App 主色，解码失败时至少有色块不空洞。
        val avatarRadius = ArkTokens.RADIUS_CHIP_DP * resources.displayMetrics.density
        avatarView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = avatarRadius
            setColor(brandColor)
        }
        avatarView.clipToOutline = true
        avatarView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, avatarRadius)
            }
        }

        // 头像位图（SystemUI 侧解码后随 Intent 带来；本进程没有读通知权限）
        val avatar: Bitmap? =
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(ArkConstants.EXTRA_MSG_AVATAR, Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                i.getParcelableExtra(ArkConstants.EXTRA_MSG_AVATAR)
            }
        if (avatar != null) {
            avatarView.setImageBitmap(avatar)
        }

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

    /**
     * v3.15.42：液态玻璃配色。卡片本体由 [ArkMorphAnim] 持有的玻璃 Drawable 绘制，
     * 这里只负责第 6 层「文字着色」——正片叠底 / 叠加提亮，让文字带一点背景色。
     */
    private fun applyPalette() {
        if (!this::glassParams.isInitialized) {
            glassParams = ArkLiquidGlass.resolve(this, nightMode)
        }
        val p = glassParams
        if (this::morph.isInitialized) morph.setGlass(p)

        // v3.15.44：文字基色统一取 IslandTokens 三级语义色（ArkTokens 镜像），
        // 分支仍按 p.light，第 6 层 ink() 混色与 6 层结构不变。
        // v3.15.46：按设计稿 NotificationCapsule 重排层级 ——
        // 标题 = 一级、时间 = 三级（caption）、正文 = 一级、底部 CTA 文案 = 一级
        // （原 CTA 文案是三级 11sp，对比度与层级都偏弱）。
        val title = if (p.light) ArkTokens.TEXT_PRIMARY_LIGHT else ArkTokens.TEXT_PRIMARY_DARK
        val caption = if (p.light) ArkTokens.TEXT_TERTIARY_LIGHT else ArkTokens.TEXT_TERTIARY_DARK
        val body = title
        val hint = title

        titleView.setTextColor(ArkLiquidGlass.ink(title, p))
        timeView.setTextColor(ArkLiquidGlass.ink(caption, p))
        textView.setTextColor(ArkLiquidGlass.ink(body, p))
        hintView.setTextColor(ArkLiquidGlass.ink(hint, p))

        // v3.15.46：右上角应用图标不再整体染色（统一 tint 会把彩色 App 图标压成一块深色方块，
        // 既认不出是微信还是 QQ，也和音乐卡的「原生图标」不一致）；图标本身在 96% 玻璃上已足够清晰。

        // v3.15.46：CTA 品牌圆点走设计稿品牌 token（浅 #2F6BFF / 暗 #8FB0FF）
        if (this::dotView.isInitialized) {
            dotView.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (p.light) ArkTokens.BRAND_LIGHT else ArkTokens.BRAND_DARK)
            }
        }

        // v3.15.46：CTA 描边色 / chevron 一律按 p.light（壁纸采样结论）运行时下发，
        // 不吃 values-night 资源 —— 否则「系统夜间 + 亮壁纸」时会错配成
        // 浅蓝描边压浅玻璃。布局里的 ic_ark_chevron 只作首帧占位。
        // v3.15.46：CTA 底板 = brandContainer @60%，按 p.light 下发（浅 #99E7EEFF / 深 #9923304C）。
        // 已知边界：系统深色模式下 Flyme 会把浅色实心底板反色（#FFE7EEFF → #FF171102），
        // 此时深色文字压在近黑底上对比度不足 —— 本轮以「对齐设计稿」为先，接受该边界并记录。
        if (this::ctaChip.isInitialized) {
            ctaChip.setFill(
                if (p.light) ArkTokens.BRAND_CONTAINER_FILL_LIGHT
                else ArkTokens.BRAND_CONTAINER_FILL_DARK
            )
        }
        if (this::chevronView.isInitialized) {
            chevronView.imageTintList = ColorStateList.valueOf(
                if (p.light) ArkTokens.BRAND_LIGHT else ArkTokens.BRAND_DARK
            )
        }
    }

    /** 点卡片 → 发送原通知的 contentIntent（进会话）→ 收起 */
    private fun openConversation() {
        val now = SystemClock.uptimeMillis()
        if (now - lastOpenAt < 800L) return
        lastOpenAt = now

        // v3.15.41：优先交给 SystemUI 代发原通知的 contentIntent。
        // 我们自己 send PendingIntent 时，QQ 的通知 PI 创建方未 opt-in Android 15 BAL，
        // 系统直接 `Background activity launch blocked`（微信碰巧能过），表现为「点卡片没反应」。
        val key = intent?.getStringExtra(ArkConstants.EXTRA_MSG_NOTIF_KEY)
        val pkg = intent?.getStringExtra(ArkConstants.EXTRA_PANEL_APP_PKG)
        if (!key.isNullOrBlank()) {
            try {
                sendBroadcast(
                    Intent(ArkConstants.ACTION_PANEL_OPEN_MSG)
                        .setPackage(ArkConstants.SYSTEMUI_PKG)
                        .putExtra(ArkConstants.EXTRA_MSG_NOTIF_KEY, key)
                        .putExtra(ArkConstants.EXTRA_PANEL_APP_PKG, pkg)
                )
                dismiss()
                return
            } catch (_: Throwable) {
            }
        }

        // 兜底：拿不到 key / 广播发失败时，仍用旧路径（微信可用）
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

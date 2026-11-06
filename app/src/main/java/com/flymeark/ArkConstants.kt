package com.flymeark

/**
 * FlymeArk 全局常量类。
 *
 * 本模块（v3.0 起）只做三件事，全部围绕**别的 App 自己的实况通知**：
 *  1. 打开 QQ音乐 的魅族状态栏歌词通道（ticker 协议）
 *  2. 把通知里的大图写成胶囊图标（专辑封面）
 *  3. 接管胶囊点击 → 拉起原生播放面板；面板的媒体键交给 SystemUI 派发
 *
 * 因此这里不再有上报/配置/胶囊控制类常量（Uni 面板与自建胶囊链路已整体退场）。
 */
object ArkConstants {

    /** 本应用包名（面板由 SystemUI 进程按此包名显式拉起） */
    const val APP_PKG = "com.flymeark"

    /** SystemUI 进程包名（面板媒体键定向广播目标） */
    const val SYSTEMUI_PKG = "com.android.systemui"

    /**
     * 支持的媒体 App（v3.15 起）。QQ音乐 与 网易云音乐 都走魅族状态栏歌词
     * （ticker 协议），SystemUI 侧链路对二者通用，只是各自包名不同。
     */
    const val MEDIA_APP_PKG = "com.tencent.qqmusic"
    const val NETEASE_MUSIC_PKG = "com.netease.cloudmusic"

    val MEDIA_APP_PKGS: Set<String> = setOf(MEDIA_APP_PKG, NETEASE_MUSIC_PKG)

    /** 是否为本模块支持的媒体 App 包名（网易云/QQ音乐） */
    fun isMediaApp(pkg: String?): Boolean = pkg != null && pkg in MEDIA_APP_PKGS

    // ------------------------------------------------------------------
    // 微信 / QQ 消息实况通知（v3.15.18，参考「魅况」）
    // ------------------------------------------------------------------

    /**
     * 微信 / QQ 的包名。它们不是媒体 App（无歌词、无 MediaSession），不走
     * [MEDIA_APP_PKGS] 的歌词胶囊链路，而是**消息实况胶囊**：在 SystemUI 侧把
     * 它们发出的普通消息通知渲染成魅族实况胶囊（标题=发送者，正文=消息内容）。
     */
    const val WECHAT_PKG = "com.tencent.mm"
    const val QQ_PKG = "com.tencent.mobileqq"

    /** 消息实况 App 集合（微信 / QQ） */
    val MESSAGE_LIVE_PKGS: Set<String> = setOf(WECHAT_PKG, QQ_PKG)

    /** 是否为本模块支持的消息实况 App 包名（微信/QQ） */
    fun isMessageLiveApp(pkg: String?): Boolean = pkg != null && pkg in MESSAGE_LIVE_PKGS

    // ------------------------------------------------------------------
    // 高德地图导航实况通知（v3.15.51）
    //
    // 高德**不发**实况通知（8 个 dex 里 `notification.live` 命中 0 次），它只发一条常驻
    // 导航通知（extras["com.autonavi.minimap.navigating"]=true，id 99910001/2/3）。
    // 由 [ArkNavLiveInjector] 在旁边补一条实况影子通知，架构与微信/QQ 完全相同。
    // 详见 docs/live-nav-research.md。
    // ------------------------------------------------------------------

    const val NAV_LIVE_PKG = "com.autonavi.minimap"

    /** 是否为导航实况 App 包名（高德地图） */
    fun isNavLiveApp(pkg: String?): Boolean = pkg == NAV_LIVE_PKG

    /**
     * 是否为「走影子通知通道」的 App（消息 + 导航）。
     *
     * SystemUI 侧的 [ArkLiveShadowHook] 用它统一判断：只要这个包会产出影子通知，
     * 就要参与「行隐藏」和「原通知消失时同步撤销」。
     */
    fun isLiveShadowApp(pkg: String?): Boolean = isMessageLiveApp(pkg) || isNavLiveApp(pkg)

    /** 设置页「实况通知」列表里每条 App 的副标题，按业务类型分类 */
    fun liveKindLabel(pkg: String?): String = when {
        isMessageLiveApp(pkg) -> "消息实况通知"
        isNavLiveApp(pkg) -> "导航实况通知"
        else -> "音乐播放实况通知"
    }

    // ------------------------------------------------------------------
    // 胶囊品牌色（v3.15.19，用户指定标准）
    //
    // 不再从内容里算平均色 —— 头像取不到位图时算出来是近黑，胶囊直接变「黑底」。
    // 固定用各家品牌色，稳定、可预期，也和系统下拉栏里 App 的视觉语言一致。
    // ------------------------------------------------------------------

    /** 微信｜绿 #07C160 */
    const val BRAND_WECHAT = 0xFF07C160.toInt()

    /** QQ｜亮蓝 #00CAFC（新版 Q 语言主题色 qdesign） */
    const val BRAND_QQ = 0xFF00CAFC.toInt()

    /** QQ 音乐｜绿 #31C27C */
    const val BRAND_QQMUSIC = 0xFF31C27C.toInt()

    /** 网易云音乐｜红 #C20C0C */
    const val BRAND_NETEASE = 0xFFC20C0C.toInt()

    /** 兜底底色（未知 App） */
    const val BRAND_FALLBACK = 0xFF333333.toInt()

    /** 按包名取胶囊底色 */
    fun brandColorOf(pkg: String?): Int = when (pkg) {
        WECHAT_PKG -> BRAND_WECHAT
        QQ_PKG -> BRAND_QQ
        MEDIA_APP_PKG -> BRAND_QQMUSIC
        NETEASE_MUSIC_PKG -> BRAND_NETEASE
        else -> BRAND_FALLBACK
    }

    /**
     * 消息类 App 在胶囊上对外展示的名字（v3.15.33）。
     *
     * 胶囊改成隐私占位后，标题只放「谁发来的」这一层信息，不放昵称/群名/正文。
     */
    fun messageAppLabel(pkg: String?): String = when (pkg) {
        WECHAT_PKG -> "微信"
        QQ_PKG -> "QQ"
        else -> "消息"
    }

    /**
     * 给定底色选可读的前景字色。
     *
     * QQ 的亮蓝 #00CAFC 相对亮度约 0.49，白字对比度只有 1.9:1（几乎糊成一片），
     * 必须换深色字；微信绿 #07C160（0.39）与网易云红 #C20C0C（0.12）用白字正好，
     * 也符合各家 App 自己的品牌用法。阈值 0.42 落在两者之间。
     */
    fun readableTextOn(bgColor: Int): Int = if (luminance(bgColor) > 0.42f) {
        0xFF0A2A33.toInt()   // 深色字（QQ 亮蓝底）
    } else {
        -1                   // 白色字（微信绿 / 网易云红 / 兜底深色）
    }

    /** WCAG 相对亮度（0..1），用于判断底色深浅 */
    private fun luminance(color: Int): Float {
        fun channel(c: Int): Float {
            val s = c / 255f
            return if (s <= 0.03928f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        val r = channel((color shr 16) and 0xFF)
        val g = channel((color shr 8) and 0xFF)
        val b = channel(color and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** 设置进程包名（把 QQ音乐登记进「实况通知」设置页） */
    const val SETTINGS_PKG = "com.android.settings"

    // ------------------------------------------------------------------
    // 播放面板 Intent 参数：曲名 / 歌手 / 专辑封面 / 当前进度 / 总时长 / 是否在播
    // ------------------------------------------------------------------

    const val EXTRA_PANEL_TITLE = "panel_title"
    const val EXTRA_PANEL_ARTIST = "panel_artist"
    const val EXTRA_PANEL_COVER = "panel_cover"
    const val EXTRA_PANEL_PROGRESS = "panel_progress"
    const val EXTRA_PANEL_DURATION = "panel_duration"
    const val EXTRA_PANEL_PLAYING = "panel_playing"

    /**
     * 胶囊在屏幕上的矩形（px，左上角 + 宽高）。
     * 面板用它做「从胶囊长大成卡片」的展开动画起点，收起时再缩回同一位置。
     */
    const val EXTRA_CAPSULE_LEFT = "capsule_left"
    const val EXTRA_CAPSULE_TOP = "capsule_top"
    const val EXTRA_CAPSULE_WIDTH = "capsule_width"
    const val EXTRA_CAPSULE_HEIGHT = "capsule_height"

    /**
     * 播放面板 → SystemUI：媒体键请求。
     * 面板运行在普通 App uid，`AudioManager.dispatchMediaKeyEvent` 可能被隐藏 API 限制；
     * 统一交给 SystemUI（system uid）派发，命中率 100%。
     */
    const val ACTION_PANEL_MEDIA_KEY = "com.flymeark.PANEL_MEDIA_KEY"
    const val EXTRA_KEY_CODE = "key_code"

    /**
     * SystemUI → 播放面板：曲目更新（换曲 / 进度跳变 / 播放态变化）。
     * 面板开着时由 [ArkLiveExpandHook] 主动推送，用来刷新曲名、封面与进度，
     * 不再需要在切歌后把面板收起来。
     */
    const val ACTION_PANEL_TRACK = "com.flymeark.PANEL_TRACK"

    /**
     * 播放面板 → SystemUI：立即同步一次曲目。
     * 切歌后不必等歌词通知刷新（可能慢 1~3 秒），面板直接要一次，SystemUI 从
     * MediaSession 读 title/artist/albumArt/progress 立刻推回，换曲几乎无延迟。
     */
    const val ACTION_PANEL_SYNC = "com.flymeark.PANEL_SYNC"

    /**
     * 播放面板 → SystemUI：面板可见性。
     * 面板展开时要求 SystemUI 把状态栏胶囊淡出（视觉上像胶囊向下展开成面板），
     * 收起时恢复，避免「面板下面还挂着原胶囊」。
     */
    const val ACTION_PANEL_STATE = "com.flymeark.PANEL_STATE"
    const val EXTRA_PANEL_VISIBLE = "panel_visible"

    /**
     * 播放面板 → SystemUI：拖动进度条后的 seekTo 请求（v3.13）。
     * MediaController.seekTo(long) 需要 system uid 权限，与媒体键一样统一代派。
     */
    const val ACTION_PANEL_SEEK = "com.flymeark.PANEL_SEEK"
    const val EXTRA_SEEK_POSITION = "seek_position"

    /**
     * 播放面板 → SystemUI：显式播放/暂停请求（v3.13.2）。
     *
     * **不要用 KEYCODE_MEDIA_PLAY_PAUSE 做 toggle。** 连续两次 PLAY_PAUSE 会被
     * 播放器/系统判成「双击线控」→ 下一首（用户实测：快速连点暂停就切歌）。
     * 这里改发明确目标状态，SystemUI 侧走 MediaController.transportControls
     * 的 play() / pause()，幂等、无双击语义，也不会互相抵消。
     */
    const val ACTION_PANEL_PLAYBACK = "com.flymeark.PANEL_PLAYBACK"
    const val EXTRA_PLAY_TARGET = "play_target"

    /**
     * 播放面板 → SystemUI：收藏请求（v3.13.3）。
     * QQ 音乐 MediaSession 不暴露收藏接口（custom actions=[], rating type=0），
     * 只能借它播放通知上的「喜欢」按钮 action（PendingIntent）代触发。
     */
    const val ACTION_PANEL_FAVORITE = "com.flymeark.PANEL_FAVORITE"

    /** SystemUI → 播放面板：当前收藏状态（Boolean，真值来源见 ArkLiveExpandHook.favOf） */
    const val EXTRA_PANEL_FAV = "panel_fav"

    /**
     * 收藏结论标记：SystemUI 已经把点击后的搜索结果（成功或失败）确认下来。
     * 面板收到它时无视「点击后 2 秒内不看推送」的锁 —— 那是防旧值的，不该防结论。
     */
    const val EXTRA_PANEL_FAV_FINAL = "panel_fav_final"

    /**
     * 播放面板 → SystemUI：本次点击期望的目标状态（Boolean，v3.15.1）。
     * QQ 音乐的通知按钮是 toggle 语义，只能发「翻转」；把目标一起带上，
     * SystemUI 才能在通知刷新后用目标状态校准指纹映射（自学习双向绑定）。
     */
    const val EXTRA_FAV_TARGET = "fav_target"

    /**
     * SystemUI → 播放面板：媒体 App 的点击跳转 PendingIntent（v3.13）。
     * 来源 MediaController.sessionActivity，点卡片空白处时 send() 拉起对应 App，
     * 与下拉栏媒体卡「点卡片进 App」行为对齐。
     */
    const val EXTRA_PANEL_CLICK_INTENT = "panel_click_intent"

    /**
     * SystemUI → 播放面板：当前媒体 App 的包名（v3.15）。
     * 来自歌词通知的归属包名；面板用它显示右上角 App 图标 + 跳转兜底。
     */
    const val EXTRA_PANEL_APP_PKG = "panel_app_pkg"

    // ------------------------------------------------------------------
    // 输出切换（v3.15）：面板 → SystemUI 列出/切换音频输出路由。
    // SystemUI（system uid）内反射调用 MediaRouter2Manager，面板普通 uid 拿不到。
    // ------------------------------------------------------------------

    /** 面板 → SystemUI：请求列出当前媒体 App 可用的输出路由 */
    const val ACTION_PANEL_OUTPUT_LIST = "com.flymeark.PANEL_OUTPUT_LIST"

    /** SystemUI → 面板：路由列表回复。extras：routes_names / routes_ids / routes_selected */
    const val ACTION_OUTPUT_ROUTES = "com.flymeark.OUTPUT_ROUTES"
    const val EXTRA_ROUTE_NAMES = "route_names"
    const val EXTRA_ROUTE_IDS = "route_ids"
    const val EXTRA_ROUTE_SELECTED = "route_selected"

    /** 面板 → SystemUI：切换输出路由（extra：EXTRA_ROUTE_ID） */
    const val ACTION_PANEL_OUTPUT_TRANSFER = "com.flymeark.PANEL_OUTPUT_TRANSFER"
    const val EXTRA_ROUTE_ID = "route_id"

    /**
     * SystemUI → 播放面板：SystemUI 侧判断的深色模式（v3.13.1）。
     * 媒体卡玻璃色用 isInNightMode() 决定，面板样式必须与它同源，
     * 不能用面板 App 进程自己的 uiMode（Flyme 对第三方 App 的配置可能不一致）。
     */
    const val EXTRA_PANEL_NIGHT = "panel_night"

    // ------------------------------------------------------------------
    // 消息胶囊展开面板（v3.15.39）
    // ------------------------------------------------------------------

    /** 消息面板：发送者 / 群名（String） */
    const val EXTRA_MSG_TITLE = "msg_title"

    /** 消息面板：正文（String，可能多行） */
    const val EXTRA_MSG_TEXT = "msg_text"

    /** 消息面板：发送者头像（Bitmap，可缺省） */
    const val EXTRA_MSG_AVATAR = "msg_avatar"

    /** 消息面板：通知 postTime（Long，毫秒） */
    const val EXTRA_MSG_TIME = "msg_time"

    /** 消息面板：品牌色（Int，ARGB） */
    const val EXTRA_MSG_BRAND = "msg_brand"

    /**
     * 消息面板 → SystemUI：点卡片进会话（v3.15.41）。
     * 面板是普通 App uid，直接 `PendingIntent.send()` 发微信/QQ 的通知 PI 会撞上
     * Android 15 的 BAL 后台启动限制（QQ 的 PI 创建方未 opt-in，日志里
     * `Background activity launch blocked / result code=3 bal_blocked`）。
     * 改由 SystemUI 调 `startNotificationPendingIntentForLiveCapsule(entry)`，
     * 与系统「点胶囊 / 点通知卡片」走同一条豁免路径。
     */
    const val ACTION_PANEL_OPEN_MSG = "com.flymeark.PANEL_OPEN_MSG"

    /** 消息面板 → SystemUI：原通知的 key（StatusBarNotification.getKey()） */
    const val EXTRA_MSG_NOTIF_KEY = "msg_notif_key"

    // ------------------------------------------------------------------
    // 导航展开卡载荷（v3.15.61）
    //
    // 三个进程互相隔离：载荷生产者 [ArkNavLiveInjector] 在**高德进程**，
    // 读取者 [ArkLiveExpandHook] 在 **SystemUI 进程**，面板
    // [ArkNavPanelActivity] 在**模块自身进程**。高德进程与 SystemUI 之间没有
    // 任何共享内存 / 文件 / Provider，唯一现成的跨进程通道就是**影子通知的 extras**
    // （SystemUI 本来就能从 mNotificationEntries 里读到这条通知）。
    //
    // 所以这些 key 会被写进影子通知的 extras（顶层，不再套 Bundle），
    // 由 ArkLiveExpandHook 原样搬进 Intent，最终由面板读取。
    // 生命周期 = 影子通知的生命周期，不需要额外清理逻辑。
    // ------------------------------------------------------------------

    /** 导航展开卡：主指令（String，如「300 米后右转」） */
    const val NAV_KEY_INSTR = "flymeark.nav.instr"

    /** 导航展开卡：副指令（String，如「进入 建国路」；空串表示没有） */
    const val NAV_KEY_SUB = "flymeark.nav.sub"

    /** 导航展开卡：目的地（String，可能为空 → 面板回落显示「高德地图」） */
    const val NAV_KEY_DEST = "flymeark.nav.dest"

    /** 导航展开卡：全程距离数值（String，如「8.0」/「133」/「800」） */
    const val NAV_KEY_DIST = "flymeark.nav.dist"

    /** 导航展开卡：全程距离单位（String，「公里」/「米」） */
    const val NAV_KEY_DIST_UNIT = "flymeark.nav.dist_unit"

    /** 导航展开卡：总耗时数值（String，分钟数或小时数） */
    const val NAV_KEY_TIME = "flymeark.nav.time"

    /** 导航展开卡：总耗时单位（String，「分钟」/「小时」） */
    const val NAV_KEY_TIME_UNIT = "flymeark.nav.time_unit"

    /** 导航展开卡：预计到达时刻（String，HH:mm；空串表示算不出来） */
    const val NAV_KEY_ETA = "flymeark.nav.eta"

    /** 导航展开卡：本趟出发时刻（Long，elapsedRealtime；0 表示没拿到出发播报） */
    const val NAV_KEY_STARTED = "flymeark.nav.started"

    /** 导航展开卡：总耗时毫秒（Long，进度条分母；0 表示没有 → 只画轨不画球） */
    const val NAV_KEY_TOTAL_MS = "flymeark.nav.total_ms"

    /**
     * 导航展开卡：**剩余**毫秒（Long，v3.15.65 锚点进度）。
     *
     * - `>= 0`：真实剩余时长。由 [ArkNavLiveInjector] 在**每一次播报**时按
     *   `max(0, 总耗时 - 已用时间)` 重算一次，算出来的时刻见 [NAV_KEY_ANCHOR_AT]；
     * - `< 0`（实现取 -1）：本趟还没有锚点（没拿到出发播报 / 没拿到总耗时）→
     *   面板只画轨、不画球。
     *
     * 面板**只认这个值**画进度，绝不自己拿 `elapsedRealtime() - 出发时刻` 推算 ——
     * 那样两帧之间会自己往前走，而高德并不给我们实时剩余（见
     * `docs/live-nav-research.md` §7）。v3.15.65 之前就是这么写的，用户实测
     * 「进度条自己跑、数字不动」，两套口径必然对不上。
     */
    const val NAV_KEY_REMAIN_MS = "flymeark.nav.remain_ms"

    /**
     * 导航展开卡：锚点时刻（Long，`elapsedRealtime`）。
     *
     * = [NAV_KEY_REMAIN_MS] 是**哪一刻**算出来的，只增不改。当前只用于日志取证；
     * 面板**刻意不读**它（读了就又回到「自己插值往前跑」的老路）。
     */
    const val NAV_KEY_ANCHOR_AT = "flymeark.nav.anchor_at"

    /** 导航展开卡：转向类型（Int，0 直行 / 1 左转 / 2 右转 / 3 掉头 / 4 到达） */
    const val NAV_KEY_TURN = "flymeark.nav.turn"

    /** 导航展开卡 Activity 全类名（SystemUI 按类名显式拉起） */
    const val NAV_PANEL_ACTIVITY = "com.flymeark.ArkNavPanelActivity"

    /** 导航展开卡里对外的 App 展示名（拿不到目的地时的回落文案之一） */
    const val NAV_LABEL = "高德地图"

    /**
     * SystemUI → 模块进程：导航展开卡**实时刷新**（v3.15.65）。
     *
     * 面板一打开就只读一次 Intent，而高德在导航中每几秒就播一句（`remain_ms` 随之变小），
     * 所以 SystemUI 侧每看到导航影子通知更新一帧，就把同一份 `NAV_KEY_*` 载荷广播过来
     * （见 `ArkLiveExpandHook.pushNavPanelUpdate`）。
     *
     * 收件人只有一个：正在显示的 [NAV_PANEL_ACTIVITY]。面板在 `onResume` 动态注册、
     * `onStop` 注销，并且用 `RECEIVER_NOT_EXPORTED` —— 这条通道**只在本进程内可见**，
     * 别的 App 既发不进来也收不到。
     *
     * 为什么不用 `startActivity` 复投 Intent：那会把面板反复推到前台、重跑一遍展开动画，
     * 用户的观感是「卡片自己闪」。
     */
    const val ACTION_NAV_PANEL_UPDATE = "com.flymeark.NAV_PANEL_UPDATE"

    /** 转向码常量（与 [NAV_KEY_TURN] 一一对应） */
    const val NAV_TURN_STRAIGHT = 0
    const val NAV_TURN_LEFT = 1
    const val NAV_TURN_RIGHT = 2
    const val NAV_TURN_UTURN = 3
    const val NAV_TURN_ARRIVE = 4

    /** Xposed 日志 TAG（logcat / LSPosed 日志里搜这个） */
    const val LOG_TAG = "FlymeArk"

    // ------------------------------------------------------------------
    // 实况通知开关「用户动作」跨进程通知（v3.15.15）
    // ------------------------------------------------------------------

    /**
     * system_server → SystemUI：用户在「设置 → 实况通知」里主动切换了某个媒体 App 的开关。
     *
     * 背景：`getNotificationLiveAllowForPackage` 返回的 raw 值会在播放通知重建时
     * 瞬时抖动（1.6~4.2s 后自恢复），无法用它区分「用户真关开关」和「通知重建抖动」。
     * 前者走 `setNotificationLiveAllowForPackage`（用户动作，唯一可靠信号），
     * 后者只在 get 侧抖动。所以 system_server hook set 方法，把「用户动作」广播给
     * SystemUI，让关闭**实时生效**，同时保留 get 侧延迟复核来防抖动。
     */
    const val ACTION_LIVE_ALLOW_CHANGED = "com.flymeark.LIVE_ALLOW_CHANGED"

    /** extra：被切换开关的 App 包名（String） */
    const val EXTRA_LIVE_PKG = "live_pkg"

    /** extra：用户设定的新开关值（Boolean） */
    const val EXTRA_LIVE_ALLOW = "live_allow"
}

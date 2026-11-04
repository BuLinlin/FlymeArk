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

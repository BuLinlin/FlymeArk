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

    /** Xposed 日志 TAG（logcat / LSPosed 日志里搜这个） */
    const val LOG_TAG = "FlymeArk"
}

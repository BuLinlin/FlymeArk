package com.flymeark

import android.app.Notification
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v2.6：播放器侧的**唯一职责**——把 QQ音乐 的「魅族状态栏歌词」通道打开。
 *
 * ## 历史包袱（v2.3 已废弃，勿恢复）
 *
 * 旧版本这里还做 `notification.live.*` extras 注入，让 QQ音乐 的普通播放通知
 * 摇身变成 Flyme 实况窗通知。那条路线（连同 v2.1 前台服务保活、v2.2 SystemUI 代发）
 * 现在已经全部弃用，原因：
 *  - 注入的 live 胶囊与 v2.5 的 ticker 歌词胶囊会同时存在，互相抢渲染位，表现为闪屏/不显示；
 *  - extras 在 Flyme 通知管道重建时会被剥离，行为不稳定；
 *  - 方舟自己发的胶囊归属方舟，与播放器状态不同步，暂停/切歌容易残留。
 *
 * 现在胶囊完全由 **QQ音乐 自己的播放通知** 承载：
 *   播放通知的 `tickerText` = 当前歌词行，`extras[ticker_icon]` = 音符图标，
 *   Flyme 的 `AdvertSwitcherView` → `LiveNotificationController.getLyricsNotifications`
 *   自动把它渲染成 showType=5 的歌词胶囊（详见 ArkLiveLyricHook）。
 *
 * 本类只需保证那个开关是开着的：QQ音乐 收到 `ACTION_MEIZU_LYRIC_TURN_ON` 后
 * 才会逐行往通知 ticker 上写歌词。
 */
internal object ArkLiveInjector {

    private const val TAG = "liveInject"

    /**
     * 各播放器侧「魅族状态栏歌词」开关广播（RemoteLyricController.LyricSwitchReceiver 接收）。
     *  - QQ音乐：action 确认（反编译）。
     *  - 网易云：action 无公开文档，按 QQ音乐 的 `<pkg>.ACTION_MEIZU_LYRIC_TURN_ON.<进程>`
     *    模式探测两种常见形式；收不到就是无效果（无接收者广播是 no-op），用户仍可在
     *    网易云【设置 → 状态栏歌词】手动开启（官方支持，一次性）。
     */
    private val LYRIC_ON_ACTIONS: Map<String, List<String>> = mapOf(
        "com.tencent.qqmusic" to listOf(
            "com.tencent.qqmusic.ACTION_MEIZU_LYRIC_TURN_ON.QQMusicPhone"
        ),
        "com.netease.cloudmusic" to listOf(
            "com.netease.cloudmusic.ACTION_MEIZU_LYRIC_TURN_ON",
            "com.netease.cloudmusic.ACTION_MEIZU_LYRIC_TURN_ON.com.netease.cloudmusic"
        )
    )

    /** 上次请求开启歌词的时间戳（按包分记，节流 60s） */
    private val lastLyricRequestAt = HashMap<String, Long>(2)

    /** 已做过首次歌词通道补发的包 */
    private val lyricPrimedPkgs = HashSet<String>(2)

    /**
     * 判断一条通知是否为媒体通知。
     * 依据：category=transport，或 extras 含 android.mediaSession。
     */
    private fun isMediaNotification(n: Notification): Boolean {
        val category = try {
            n.category
        } catch (_: Throwable) {
            null
        }
        if (category == Notification.CATEGORY_TRANSPORT) return true
        val extras = try {
            n.extras
        } catch (_: Throwable) {
            null
        } ?: return false
        return extras.containsKey("android.mediaSession")
    }

    /**
     * 打开播放器的「魅族状态栏歌词」通道。
     *
     * 实况胶囊的歌词来自通知 `tickerText`，播放器只有收到自己的
     * `ACTION_MEIZU_LYRIC_TURN_ON.*` 后才会逐行往播放通知上写 ticker。
     * 这里在播放器首次发媒体通知时补发一次，让用户无需手动触发；
     * 若检测到 ticker 又变空（用户关了歌词开关），60s 后允许重发一次。
     */
    private fun ensureMeizuLyric(pkg: String) {
        // 取不到 Context 时不写节流时间戳，留待下一次通知重试
        val now = System.currentTimeMillis()
        synchronized(lastLyricRequestAt) {
            if (now - (lastLyricRequestAt[pkg] ?: 0L) < 60_000L) return
            val ctx = ArkHooks.appContext() ?: return
            try {
                val actions = LYRIC_ON_ACTIONS[pkg] ?: listOf("$pkg.ACTION_MEIZU_LYRIC_TURN_ON")
                for (action in actions) {
                    ctx.sendBroadcast(Intent(action).setPackage(pkg))
                }
                lastLyricRequestAt[pkg] = now
                XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG: sent lyric-turn-on -> $pkg (${actions.size} action)")
            } catch (t: Throwable) {
                XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG: enable lyric failed: ${t.message}")
            }
        }
    }

    /**
     * 在播放器进程安装 hook：只观察媒体通知，用于维持歌词通道开启。
     * 不再修改 Notification 的任何字段（不改 extras、不加 live 标记）。
     */
    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpp.packageName
        if (pkg !in ArkConstants.MEDIA_APP_PKGS) return

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing for $pkg (lyric priming only)")

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val n = param.args.filterIsInstance<Notification>().firstOrNull() ?: return
                    if (!isMediaNotification(n)) return

                    // 进程内首条媒体通知无条件补发一次；
                    // 之后仅在 ticker 为空（歌词被关掉/未就绪）时按 60s 节流重试。
                    val primed = synchronized(lyricPrimedPkgs) { lyricPrimedPkgs.contains(pkg) }
                    if (!primed) {
                        synchronized(lyricPrimedPkgs) { lyricPrimedPkgs.add(pkg) }
                        ensureMeizuLyric(pkg)
                    } else if (n.tickerText == null) {
                        ensureMeizuLyric(pkg)
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG hook error: ${t.message}")
                }
            }
        }

        ArkHooks.quiet("$TAG notify") {
            val nmClass = XposedHelpers.findClass("android.app.NotificationManager", lpp.classLoader)
            ArkHooks.quiet("$TAG notify(int,Notification)") {
                XposedHelpers.findAndHookMethod(
                    nmClass, "notify", Int::class.javaPrimitiveType, Notification::class.java, hook
                )
            }
            ArkHooks.quiet("$TAG notify(String,int,Notification)") {
                XposedHelpers.findAndHookMethod(
                    nmClass, "notify",
                    String::class.java, Int::class.javaPrimitiveType, Notification::class.java, hook
                )
            }
        }
    }
}

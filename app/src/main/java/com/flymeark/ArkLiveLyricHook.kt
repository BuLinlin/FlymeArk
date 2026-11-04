package com.flymeark

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v2.5：借用 QQ音乐 的「魅族状态栏歌词」通道，把播放信息变成实况窗胶囊。
 *
 * ## 逆向结论（真机日志已闭环）
 *
 * QQ音乐 为魅族做的接口并不是 `notification.live.*`，而是 **播放通知上的 ticker**：
 *  - 开关：广播 `com.tencent.qqmusic.ACTION_MEIZU_LYRIC_TURN_ON.QQMusicPhone`
 *    / `..._TURN_OFF...`（`RemoteLyricController.LyricSwitchReceiver`）
 *  - 开启后 `PlayerNotificationUtils.e0(song, ..., String 歌词行, ...)` →
 *    `j0(...)` 给 **同一条播放通知** 补上：
 *      `notification.tickerText = 当前歌词行`
 *      `extras["ticker_icon"] = 2131236687`、`extras["ticker_icon_switch"] = false`
 *
 * SystemUI 侧（本 hook 所在进程）实测链路：
 *   `NotificationTickController.tick(entry)`                     ← 媒体通知确实进来
 *     → `AdvertSwitcherView.addNotification(sbn)`  accepted=true
 *     → `LiveNotificationController.getLyricsNotifications(text, drawable, entry)`
 *         → `mLyricsInofo = LiveNotificationInfo(showType = 5)`
 *
 * 唯一卡点：`getTopLyricsDisplayText()` 第一道判断
 *   `if (mIsMediaShow == 0) return`  —— 该字段在本 ROM 里无人写入，恒为 false，
 *   于是歌词 info 永远不会进入 `mToShow`，状态栏什么都不会出现。
 *
 * ## 本 hook 做的事
 *
 * 在 `getTopLyricsDisplayText` 执行前，若 `mLyricsInofo` 已就绪且播放通知带
 * `tickerText`，就把 `mIsMediaShow` 置为 true，让歌词实况走完原生渲染路径
 * （showType=5 的胶囊 + 歌词文本 + 音乐图标）。
 *
 * 只动这一个门控字段，不伪造 LiveNotificationInfo、不改渲染逻辑，
 * 因此胶囊的布局/动画/避让全部仍由 Flyme 原生代码处理。
 */
internal object ArkLiveLyricHook {

    private const val TAG = "liveLyric"
    private const val CTRL_CLS =
        "com.flyme.statusbar.livenotification.LiveNotificationController"

    @Volatile
    private var lastLoggedText: String? = null

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != ArkConstants.SYSTEMUI_PKG) return

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing")

        // 门控放行：有歌词 + 用户没关开关 → 视作媒体实况在显示
        ArkHooks.quiet("$TAG getTopLyricsDisplayText") {
            XposedHelpers.findAndHookMethod(
                CTRL_CLS, lpp.classLoader, "getTopLyricsDisplayText",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val ctrl = param.thisObject
                            val lyrics = XposedHelpers.getObjectField(ctrl, "mLyricsInofo")
                            if (lyrics == null) return

                            // v3.2：每次都要问一次开关，不能只在首次放行时判断 ——
                            // 否则用户在设置里关掉后，mIsMediaShow 仍是 true，胶囊会一直留着。
                            val ctx = XposedHelpers.getObjectField(ctrl, "mContext")
                                as? android.content.Context
                            // v3.15：受支持的媒体 App（QQ音乐/网易云）任一放行即可。
                            // 不同时听两个 App 时实际只有一个在出歌词，这里 OR 判断足够。
                            val allowed = ArkConstants.MEDIA_APP_PKGS.any {
                                LiveAllowGate.isAllowed(ctx, it)
                            }
                            if (!allowed) {
                                if (XposedHelpers.getBooleanField(ctrl, "mIsMediaShow")) {
                                    XposedHelpers.setBooleanField(ctrl, "mIsMediaShow", false)
                                    lastLoggedText = null
                                    XposedBridge.log("$TAG: switch OFF -> capsule released")
                                }
                                return
                            }

                            if (XposedHelpers.getBooleanField(ctrl, "mIsMediaShow")) return

                            XposedHelpers.setBooleanField(ctrl, "mIsMediaShow", true)

                            val text = param.args[0]?.toString()
                            if (text != lastLoggedText) {
                                lastLoggedText = text
                                XposedBridge.log("$TAG: mIsMediaShow forced true, text='$text'")
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG error: ${t.message}")
                        }
                    }
                }
            )
        }

        // 歌词来源观测：确认歌词确实来自 QQ音乐 的播放通知 ticker（便于后续收紧作用域）
        ArkHooks.quiet("$TAG getLyricsNotifications") {
            XposedHelpers.findAndHookMethod(
                CTRL_CLS, lpp.classLoader, "getLyricsNotifications",
                CharSequence::class.java,
                android.graphics.drawable.Drawable::class.java,
                "com.android.systemui.statusbar.notification.collection.NotificationEntry",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val entry = param.args[2] ?: return
                            val sbn = XposedHelpers.callMethod(entry, "getSbn")
                            val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String
                            if (!ArkConstants.isMediaApp(pkg)) return
                            XposedBridge.log("$TAG: lyrics from $pkg -> '${param.args[0]}'")
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        }
    }
}

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

    /**
     * 「关闭」判定防抖（v3.15.14）。
     *
     * 真机取证：点收藏/取消会触发播放通知重建，`getNotificationLiveAllowForPackage`
     * 在 system_server 侧会瞬时返回一次 `raw=false`（实测 1.6~4.2s 后自恢复 true），
     * 而用户开关根本没动。旧逻辑把瞬时 false 当成「用户关开关」，立即释放胶囊。
     *
     * 所以关闭动作改成**延迟复核**：读到 false 不立即释放，记一个待关闭任务，
     * 延迟 [OFF_RECHECK_MS] 后重新查开关，仍 false 才真正 `switch OFF`；
     * 期间只要又读到 true 就取消待关闭。延迟取 3s —— 压过 1.6s 抖动、
     * 又不至于让「真关开关」等太久（上一版 6s 被用户反馈太久）。
     */
    private const val OFF_RECHECK_MS = 3_000L

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 待确认关闭的 App；非空表示已调度了一个延迟复核任务 */
    @Volatile
    private var pendingOffPkg: String? = null

    private val offRunnable = Runnable { confirmOff() }

    private fun confirmOff() {
        val pkg = pendingOffPkg ?: return
        pendingOffPkg = null
        // 延迟到了再查一次：仍 false 才真正释放，避免瞬时抖动
        val cur = ArkLiveExpandHook.currentPkg
        val allowed = if (cur != null && ArkConstants.isMediaApp(cur)) {
            LiveAllowGate.isAllowed(appContext, cur)
        } else {
            ArkConstants.MEDIA_APP_PKGS.any { LiveAllowGate.isAllowed(appContext, it) }
        }
        if (!allowed) {
            releaseCapsule(pkg)
        } else {
            XposedBridge.log("$TAG: off cancelled (allowLive back to true)")
        }
    }

    private var appContext: android.content.Context? = null

    private fun releaseCapsule(pkg: String) {
        val ctrl = capsuleController ?: return
        try {
            if (XposedHelpers.getBooleanField(ctrl, "mIsMediaShow")) {
                XposedHelpers.setBooleanField(ctrl, "mIsMediaShow", false)
                lastLoggedText = null
                XposedBridge.log("$TAG: switch OFF -> capsule released ($pkg)")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG release error: ${t.message}")
        }
    }

    /** 最近一次门控拿到的 controller，供延迟复核时释放胶囊 */
    @Volatile
    private var capsuleController: Any? = null

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

                            val ctx = XposedHelpers.getObjectField(ctrl, "mContext")
                                as? android.content.Context
                            appContext = ctx
                            capsuleController = ctrl

                            // v3.15.11：必须按**当前正在出歌词的那个 App** 判断开关。
                            val cur = ArkLiveExpandHook.currentPkg
                            val allowed = if (cur != null && ArkConstants.isMediaApp(cur)) {
                                LiveAllowGate.isAllowed(ctx, cur)
                            } else {
                                ArkConstants.MEDIA_APP_PKGS.any {
                                    LiveAllowGate.isAllowed(ctx, it)
                                }
                            }
                            if (!allowed) {
                                // v3.15.14：不立即释放，改为延迟复核（防瞬时抖动误关）。
                                // 已经调度过同一个 App 的复核就跳过，避免重复 post。
                                if (pendingOffPkg == null) {
                                    pendingOffPkg = cur
                                    mainHandler.removeCallbacks(offRunnable)
                                    mainHandler.postDelayed(offRunnable, OFF_RECHECK_MS)
                                }
                                return
                            }

                            // 又读到 true：取消任何待关闭复核
                            if (pendingOffPkg != null) {
                                mainHandler.removeCallbacks(offRunnable)
                                pendingOffPkg = null
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

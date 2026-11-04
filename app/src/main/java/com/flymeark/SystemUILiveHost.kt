package com.flymeark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * SystemUI 进程内的特权操作宿主（唯一职责：**代替播放面板派发媒体键**）。
 *
 * 播放面板 [ArkCapsulePanelActivity] 运行在普通应用 uid，
 * `AudioManager.dispatchMediaKeyEvent` 在部分版本受限制；本类在 SystemUI 进程
 * （system uid）注册接收器，收到 [ArkConstants.ACTION_PANEL_MEDIA_KEY] 后
 * 以 system 身份派发 PLAY_PAUSE / NEXT / PREVIOUS，稳定生效。
 */
internal object SystemUILiveHost {

    @Volatile
    private var installed = false

    /**
     * 在 SystemUI 进程安装媒体事件接收器。
     * 必须在 Application.attach 之后调用（需要 Context 注册接收器）。
     */
    fun install(lpp: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
        XposedBridge.log("[${ArkConstants.LOG_TAG}] SystemUILiveHost installing in SystemUI")

        // 等 Application 就绪后注册接收器
        ArkHooks.quiet("SystemUI Application.attach") {
            XposedHelpers.findAndHookMethod(
                android.app.Application::class.java, "attach",
                Context::class.java,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val ctx = param.args[0] as? Context ?: return
                            registerReceiver(ctx)
                        } catch (t: Throwable) {
                            XposedBridge.log("[${ArkConstants.LOG_TAG}] SystemUILiveHost register failed: ${t.message}")
                        }
                    }
                }
            )
        }
    }

    private fun registerReceiver(ctx: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // v3.13.1：只允许 SystemUI 主进程处理。
                // com.android.systemui:screenshot 等子进程也会收到 exported 广播，
                // 媒体键被派发两次 → 播放/暂停连发互相抵消（用户看到的「暂停失效」）、
                // 切歌一次跳两首。非主进程一律忽略。
                val proc = try {
                    android.app.Application.getProcessName()
                } catch (_: Throwable) {
                    null
                }
                if (proc != null && proc != "com.android.systemui") return

                // v2.6：只接播放面板的媒体键请求。
                // v2.2 的「播放器媒体事件 → SystemUI 代发实况通知」已废弃：
                // 方舟不再自己发胶囊，避免与 QQ音乐 原生 ticker 胶囊冲突。
                when (intent.action) {
                    ArkConstants.ACTION_PANEL_MEDIA_KEY -> dispatchPanelKey(context, intent)

                    // v3.13.2：播放/暂停改为显式目标状态，不再发 toggle 媒体键
                    // （连点 PLAY_PAUSE 会被判成双击切歌）
                    ArkConstants.ACTION_PANEL_PLAYBACK -> ArkLiveExpandHook.setPlayback(
                        context,
                        intent.getBooleanExtra(ArkConstants.EXTRA_PLAY_TARGET, true)
                    )

                    // v3.13.3：收藏 → 触发 QQ 音乐通知上的「喜欢」按钮
                    ArkConstants.ACTION_PANEL_FAVORITE -> ArkLiveExpandHook.toggleFavorite(context)

                    // v3.1：面板展开/收起 → 淡出/恢复状态栏胶囊
                    ArkConstants.ACTION_PANEL_STATE -> {
                        val visible =
                            intent.getBooleanExtra(ArkConstants.EXTRA_PANEL_VISIBLE, false)
                        ArkLiveExpandHook.setPanelVisible(visible)
                    }

                    // v3.1：面板要求立即同步曲目（切歌后不用等歌词通知刷新）
                    ArkConstants.ACTION_PANEL_SYNC -> ArkLiveExpandHook.syncNow(context)

                    // v3.13：面板拖动进度条 → seekTo（需 system uid）
                    ArkConstants.ACTION_PANEL_SEEK ->
                        ArkLiveExpandHook.seekTo(context, intent.getLongExtra(ArkConstants.EXTRA_SEEK_POSITION, -1L))
                }
            }
        }
        val filter = IntentFilter(ArkConstants.ACTION_PANEL_MEDIA_KEY).apply {
            addAction(ArkConstants.ACTION_PANEL_STATE)
            addAction(ArkConstants.ACTION_PANEL_SYNC)
            addAction(ArkConstants.ACTION_PANEL_SEEK)
            addAction(ArkConstants.ACTION_PANEL_PLAYBACK)
            addAction(ArkConstants.ACTION_PANEL_FAVORITE)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            XposedBridge.log("[${ArkConstants.LOG_TAG}] SystemUILiveHost receiver registered")
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] SystemUILiveHost registerReceiver failed: ${t.message}")
        }
    }

    /**
     * v2.6：替播放面板派发媒体键。
     * 面板是普通应用 uid，`AudioManager.dispatchMediaKeyEvent` 在部分版本受限制；
     * 在 SystemUI 进程内以 system 身份派发可稳定生效（切歌/暂停）。
     */
    private fun dispatchPanelKey(ctx: Context, intent: Intent) {
        val code = intent.getIntExtra(ArkConstants.EXTRA_KEY_CODE, 0)
        if (code == 0) return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            XposedBridge.log("[${ArkConstants.LOG_TAG}] SystemUILiveHost media key $code dispatched")
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] SystemUILiveHost dispatch key failed: ${t.message}")
        }
    }
}

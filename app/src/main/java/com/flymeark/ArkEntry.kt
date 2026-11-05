package com.flymeark

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * FlymeArk LSP 模块入口。
 *
 * 设计原则：**只 hook，不产出任何自己的通知/窗口/常驻进程**。
 * 状态栏胶囊、展开动效、收起逻辑全部由 Flyme 原生实况窗负责，
 * 我们只负责把播放器的信息喂给原生链路、并接管胶囊点击。
 *
 * 四个注入点：
 *  - `android`（system_server）：实况窗准入兜底（[ArkLiveAllowHook]）
 *  - `com.android.systemui`：歌词通道放开 + 专辑封面 + 点击展开 + 媒体键代派
 *  - `com.android.settings`：把 QQ音乐 登记进「实况通知」开关列表
 *  - 其余作用域（即播放器进程）：打开魅族歌词通道（[ArkLiveInjector]）
 */
class ArkEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpp: XC_LoadPackage.LoadPackageParam) {
        // 兜底：RemoteViews 异步 inflate 会经由 LSPosed 的 classloader 钩子误触发一次
        // handleLoadPackage（包名报成 "android"），此时任何未捕获异常都会穿透到
        // LayoutInflater.createView → 整条通知布局 inflate 失败（v3.15.31 日志实证）。
        // 因此整段分发必须自己兜住。
        try {
            dispatch(lpp)
        } catch (t: Throwable) {
            log("dispatch(${lpp.packageName}) error: ${t.message}")
        }
    }

    private fun dispatch(lpp: XC_LoadPackage.LoadPackageParam) {
        when (lpp.packageName) {
            // system_server 在不同 LSPosed 上可能以 "android" 或 "system" 上报包名，
            // 两个都接住，否则模块进不去系统进程（[ArkLiveAllowHook] 不加载）。
            "android", "system" -> {
                // 关键：包名不可靠。SystemUI 里 RemoteViews inflate 也会被报成 "android"。
                // 必须确认当前进程真的是 system_server，否则会在 SystemUI 里找
                // NotificationManagerService 而抛 ClassNotFound（并炸掉通知 inflate）。
                if (!isSystemServerProcess()) return
                ArkLiveAllowHook.install(lpp)
                return
            }

            ArkConstants.APP_PKG -> return

            ArkConstants.SYSTEMUI_PKG -> {
                log("SystemUI: installing lyric + cover + expand + media-key")
                // 全局玻璃：统一染色透明度（所有玻璃面一处生效）
                ArkGlobalGlassHook.install(lpp)
                // 媒体键代派（system uid）
                SystemUILiveHost.install(lpp)
                // 放开歌词胶囊门控（mIsMediaShow 恒 false）
                ArkLiveLyricHook.install(lpp)
                // 副显示位胶囊文字/宽度修复（topDisplayText 空 → 回退 showText）
                ArkLiveShadeTextHook.install(lpp)
                // 消息胶囊滑动销毁（原生只有点击，无滑动）
                ArkLiveSwipeDismissHook.install(lpp)
                // 微信/QQ 通知栏样式：已废弃 sbn.isLive 拦截（会连带断掉胶囊点击追踪）
                // 实况影子通知：从通知栏列表过滤 + 原通知消失时同步销毁胶囊
                ArkLiveShadowHook.install(lpp)
                // 胶囊首次展示补内容（系统只淡入不设文字 → 空白窗口）
                ArkLiveShowFixHook.install(lpp)
                // 专辑封面写入 smallIcon + 清 showType=5 单色滤镜
                ArkLiveCoverHook.install(lpp)
                // 胶囊宽度收窄 + 歌词横向滑入
                ArkLiveCapsuleStyleHook.install(lpp)
                // 点击胶囊 → 原生播放面板
                ArkLiveExpandHook.install(lpp)
                return
            }

            ArkConstants.SETTINGS_PKG -> {
                ArkLiveSettingsHook.install(lpp)
                return
            }
        }

        // 播放器进程（作用域内其余应用）：只做「打开魅族歌词通道」这一件事
        ArkLiveInjector.install(lpp)
        // 微信 / QQ：注入消息实况字段，把消息通知渲染成实况胶囊（v3.15.18）
        ArkMessageLiveInjector.install(lpp)
    }

    /** 当前进程是否真的是 system_server（而不是被 RemoteViews inflate 误触发的 SystemUI） */
    private fun isSystemServerProcess(): Boolean = try {
        val at = Class.forName("android.app.ActivityThread")
        val name = XposedHelpers.callStaticMethod(at, "currentProcessName") as? String
        name == "android" || name == "system" || name == "system_server"
    } catch (_: Throwable) {
        false
    }

    private fun log(msg: String) {
        XposedBridge.log("[${ArkConstants.LOG_TAG}] $msg")
    }
}

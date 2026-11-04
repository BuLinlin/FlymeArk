package com.flymeark

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
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
        when (lpp.packageName) {
            "android" -> {
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
                // 微信/QQ 消息胶囊文字渲染补丁（副显示位 topDisplayText 空白修复）
                ArkMessageLiveTextHook.install(lpp)
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

    private fun log(msg: String) {
        XposedBridge.log("[${ArkConstants.LOG_TAG}] $msg")
    }
}

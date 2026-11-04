package com.flymeark

import android.content.Context
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Hook 安装辅助：单个 Hook 点失败不能影响其它采集能力，也不能让被 Hook 应用崩溃。
 */
internal object ArkHooks {

    /** 静默安装一个 Hook，失败仅写 Xposed 日志 */
    fun quiet(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] hook '$tag' skipped: $t")
        }
    }

    /**
     * 被Hook应用进程的 Application Context（封面 URI 解码等需要 ContentResolver 的场景）。
     * SystemUI 进程 currentApplication 可能为 null，调用方需判空。
     */
    fun appContext(): Context? = try {
        XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null),
            "currentApplication"
        ) as? Context
    } catch (t: Throwable) {
        null
    }
}

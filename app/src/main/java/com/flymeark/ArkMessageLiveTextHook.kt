package com.flymeark

import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v3.15.18：微信/QQ 消息实况胶囊渲染补丁（SystemUI 侧）。
 *
 * ## 问题
 *
 * 消息通知的胶囊字段（content/icon）注入正确，头像与 showText 数据都到位，但
 * **副显示位的胶囊文字空白/错乱**。根因（真机反编译 LiveNotificationController +
 * LiveNotificationView 确认）：
 *
 *  `updateStatusBarIcons` 给多条 live 通知分配 showType：
 *   - 主显示位 showType=0 → `LiveNotificationView.setLiveNotificationInfo` 走 showText 渲染；
 *   - 副显示位 showType=1 → 改用 `topDisplayText` 渲染；
 *   - 第 3 条及以后 showType=2。
 *  而 `topDisplayText` 来自 `getTopLyricsDisplayText()`，其第一道门控 `mIsMediaShow`
 *  只对媒体（歌词）通知为 true。消息通知没有歌词 → 副显示位文字空白。
 *  更糟的是 topDisplayText 是**全局共享**的（updateStatusBarIcons 里一个 r4 喂给
 *  所有胶囊），无法按胶囊区分，导致副显示位错显其它胶囊的内容。
 *
 * ## 本类做的事
 *
 * hook `LiveNotificationInfo.setShowType(int)`：当该 info 的 packageName 是微信/QQ
 * （消息实况 App）时，把 showType=1（副显示位）强制改回 0，让消息胶囊无论处于
 * 主/副显示位都走 showText 分支渲染自己的内容，彻底绕开 topDisplayText 的空白/错乱。
 */
internal object ArkMessageLiveTextHook {

    private const val TAG = "liveMsgText"
    private const val INFO_CLS =
        "com.android.systemui.shared.livenotification.LiveNotificationInfo"

    /**
     * showType 1→0 补丁开关。
     *
     * v3.15.18 后期按「魅况」协议（live.type=10 / status=1 / operation=0 / capsuleType=3）
     * 重写注入后先关闭本补丁，避免与魅况协议的 showType 语义冲突（capsuleType=3 需要
     * 系统自分配 showType 才能正确渲染进度条语义的胶囊）。确认新协议效果后再决定是否启用。
     */
    private const val ENABLE_SHOWTYPE_PATCH = false

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != ArkConstants.SYSTEMUI_PKG) return

        ArkHooks.quiet("$TAG setShowType") {
            XposedHelpers.findAndHookMethod(
                INFO_CLS, lpp.classLoader, "setShowType",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ENABLE_SHOWTYPE_PATCH) return
                        try {
                            val pkg = XposedHelpers.getObjectField(
                                param.thisObject, "packageName"
                            ) as? String ?: return
                            if (!ArkConstants.isMessageLiveApp(pkg)) return
                            val type = param.args[0] as? Int ?: return
                            if (type == 1) {
                                // 副显示位：消息胶囊改回主显示位语义，走 showText 渲染
                                param.args[0] = 0
                                XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG: msg capsule showType 1→0 for $pkg")
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG error: ${t.message}")
                        }
                    }
                }
            )
        }
        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing in SystemUI")

        // ===== 渲染探针：setLiveNotificationInfo 后延迟读 View 真实状态 =====
        // 背景：数据层全对（showText 有值、颜色 0xFF333333、showType=0），但胶囊文字
        // 不可见。静态分析无法定位（text/color/width/alpha 链路看似都通），加运行时
        // 探针直接读 TextView 的 text/alpha/visibility/width，一次看清哪个属性不对。
        ArkHooks.quiet("$TAG probe") {
            val viewCls = XposedHelpers.findClass(
                "com.flyme.statusbar.livenotification.LiveNotificationView", lpp.classLoader
            )
            XposedHelpers.findAndHookMethod(
                viewCls, "setLiveNotificationInfo",
                XposedHelpers.findClass(INFO_CLS, lpp.classLoader),
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? android.view.View ?: return
                            val info = param.args[0]
                            val pkg = XposedHelpers.getObjectField(info, "packageName") as? String
                            if (pkg != null && !ArkConstants.isMessageLiveApp(pkg)) return
                            val showType = XposedHelpers.callMethod(info, "getShowType")
                            val showText = XposedHelpers.callMethod(info, "getShowText")
                            view.postDelayed({
                                try {
                                    val tv = XposedHelpers.getObjectField(
                                        view, "mLiveNotificationText"
                                    ) as? TextView
                                    val linear = XposedHelpers.getObjectField(
                                        view, "mLinearIconText"
                                    ) as? android.view.View
                                    val prog = XposedHelpers.getObjectField(
                                        view, "mLiveProgressBar"
                                    ) as? android.view.View
                                    fun v(x: android.view.View?): String =
                                        if (x == null) "null" else
                                            "w=${x.width} h=${x.height} vis=${x.visibility} alpha=${x.alpha}"
                                    XposedBridge.log(
                                        "[${ArkConstants.LOG_TAG}] $TAG probe: type=$showType " +
                                            "infoText='${showText}' viewW=${view.width} vis=${view.visibility} " +
                                            "tv[v='${tv?.text}' color=0x${Integer.toHexString(tv?.currentTextColor ?: 0)} " +
                                            "alpha=${tv?.alpha} vis=${tv?.visibility} w=${tv?.width} ${v(tv)}] " +
                                            "linear[${v(linear)}] prog[${v(prog)}]"
                                    )
                                } catch (t: Throwable) {
                                    XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG probe-inner: ${t.message}")
                                }
                            }, 600)
                        } catch (t: Throwable) {
                            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG probe error: ${t.message}")
                        }
                    }
                }
            )
        }
    }
}

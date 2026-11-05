package com.flymeark

import android.graphics.Rect
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v3.15.20：消息实况胶囊的**滑动销毁**（SystemUI 侧）。
 *
 * ## 为什么需要自己造
 *
 * Flyme 原生胶囊只有点击，没有任何滑动手势：
 *
 * - `LiveNotificationController.LiveNotificationClickHelper.handleTouchEvent` 只做一件事 ——
 *   判断手指落点是否在 `mLiveNotificationArea` 里，用于**拦截状态栏下拉**，不做销毁；
 * - `PhoneStatusBarViewController.LiveNotificationTouchHandler` 在 ACTION_UP 时固定调用
 *   `onLiveNotificationClick(null)`（原生点击路径也一样传 null），没有滑动分支。
 *
 * 结果就是：消息胶囊只能点进 App 让通知被消费才消失，没法像通知一样滑掉。
 *
 * ## 实现
 *
 * hook `LiveNotificationController.handleOnTouchEvent(MotionEvent)`，只对**消息类 App**
 * （微信 / QQ）且当前显示在主显示位的胶囊接管手势：
 *
 * | 阶段 | 行为 |
 * |---|---|
 * | DOWN | 落点在胶囊矩形内才记录起点 + 当时的通知 key |
 * | MOVE | 水平位移超过 24dp 且横向占优 → 判定为滑动，胶囊跟手位移，吞掉事件（不拉通知栏） |
 * | UP | 位移超过 80dp → 朝该方向淡出，并清除对应通知；否则弹回原位 |
 *
 * 为什么限定消息 App：音乐胶囊背后是播放通知，滑动清掉会把播放器通知也抹了，
 * 音乐 App 自己还得重建——不值得。
 *
 * ## 清通知的路径
 *
 * SystemUI 是 `android.uid.systemui`，没有权限直接 `NotificationManager.cancel` 别人的
 * 通知，所以走 SystemUI 自己删通知用的同一条路：`IStatusBarService.onNotificationClear`。
 * `NotifCollection.lambda$dismissNotifications$1` 里就是拿 `mStatusBarService` 调它。
 */
internal object ArkLiveSwipeDismissHook {

    private const val TAG = "liveSwipe"
    private const val CTRL_CLS =
        "com.flyme.statusbar.livenotification.LiveNotificationController"

    /** 判定为滑动的水平位移（dp） */
    private const val TOUCH_SLOP_DP = 24f

    /** 触发销毁的水平位移（dp） */
    private const val DISMISS_DP = 80f

    // ---- 手势状态（handleOnTouchEvent 在主线程，单线程访问）----

    private var tracking = false
    private var swiping = false
    private var downX = 0f
    private var downY = 0f
    private var notifKey: String? = null
    private var notifPkg: String? = null
    private var targetView: View? = null

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != ArkConstants.SYSTEMUI_PKG) return

        ArkHooks.quiet("$TAG handleOnTouchEvent") {
            XposedHelpers.findAndHookMethod(
                CTRL_CLS, lpp.classLoader, "handleOnTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (handleTouch(param)) {
                                // 手势已被我们接管：吞掉事件，别让状态栏继续当"下拉/点击"处理
                                param.result = true
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG error: ${t.message}")
                            reset()
                        }
                    }
                }
            )
        }

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing in SystemUI")
    }

    /** @return true 表示事件已被本手势消费 */
    private fun handleTouch(param: XC_MethodHook.MethodHookParam): Boolean {
        val ev = param.args[0] as? MotionEvent ?: return false
        val ctrl = param.thisObject

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                val view = XposedHelpers.getObjectField(ctrl, "mLiveNotificationDisplay") as? View
                    ?: return false
                if (view.visibility != View.VISIBLE) return false

                val info = XposedHelpers.getObjectField(ctrl, "mDisplayNotificationInfo")
                    ?: return false
                val pkg = XposedHelpers.callMethod(info, "getPackageName") as? String ?: return false
                // 只接管消息类胶囊
                if (!ArkConstants.isMessageLiveApp(pkg)) return false

                val key = XposedHelpers.callMethod(info, "getNotificationKey") as? String
                    ?: return false
                // 落点必须在胶囊矩形内（与原生 handleTouchEvent 同一判据）
                if (!hitTest(view, ev.rawX, ev.rawY)) return false

                tracking = true
                downX = ev.rawX
                downY = ev.rawY
                notifKey = key
                notifPkg = pkg
                targetView = view
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val view = targetView ?: return false
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                val slop = TOUCH_SLOP_DP * density(view)

                if (!swiping) {
                    if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        swiping = true
                        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG start swipe: $notifPkg")
                    } else {
                        return false
                    }
                }
                // 跟手位移 + 随位移淡出，给用户"能滑掉"的反馈
                view.translationX = dx
                view.alpha = (1f - kotlin.math.abs(dx) / (dismissPx(view) * 2f)).coerceIn(0.2f, 1f)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                val view = targetView
                val dx = ev.rawX - downX
                val wasSwiping = swiping
                val key = notifKey
                val pkg = notifPkg
                reset()

                if (!wasSwiping || view == null) return false

                if (ev.actionMasked == MotionEvent.ACTION_UP &&
                    kotlin.math.abs(dx) > dismissPx(view) && key != null && pkg != null
                ) {
                    // 朝滑动方向滑出并淡出
                    view.animate()
                        .translationX(if (dx > 0) view.width.toFloat() else -view.width.toFloat())
                        .alpha(0f)
                        .setDuration(160)
                        .withEndAction {
                            view.translationX = 0f
                            view.alpha = 1f
                        }
                        .start()
                    dismissNotification(pkg, key)
                } else {
                    // 没滑够：弹回
                    view.animate().translationX(0f).alpha(1f).setDuration(140).start()
                }
                return true
            }
        }
        return false
    }

    private fun dismissNotification(pkg: String, key: String) {
        try {
            // key 形如 "0|com.tencent.mm|123|null|10123"，第一段是 userId
            val userId = key.substringBefore('|').toIntOrNull() ?: 0

            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "statusbar") as? IBinder
            if (binder == null) {
                XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG dismiss: no statusbar service")
                return
            }
            val stubCls = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            val iface = stubCls.getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)

            val visibilityCls = Class.forName("com.android.internal.statusbar.NotificationVisibility")
            val method = iface.javaClass.getMethod(
                "onNotificationClear",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                visibilityCls
            )
            // dismissalSurface / dismissalSentiment 传 1（用户主动清除的常规值），
            // visibility 传 null —— SystemUI 只把它用于用量统计
            method.invoke(iface, pkg, userId, key, 1, 1, null)
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG dismissed $pkg key=$key")
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG dismiss failed: ${t.message}")
        }
    }

    private fun hitTest(view: View, rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        return rect.contains(rawX.toInt(), rawY.toInt())
    }

    private fun density(view: View): Float = view.resources.displayMetrics.density

    private fun dismissPx(view: View): Float = DISMISS_DP * density(view)

    private fun reset() {
        tracking = false
        swiping = false
        downX = 0f
        downY = 0f
        notifKey = null
        notifPkg = null
        targetView = null
    }
}

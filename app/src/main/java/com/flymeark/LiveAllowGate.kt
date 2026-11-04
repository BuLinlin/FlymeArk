package com.flymeark

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field

/**
 * v3.2：读取「设置 → 实况通知」里该 App 的开关真实状态。
 *
 * ## 为什么原来的开关是「假」的
 *
 * Flyme 设置里那个开关最终走到
 * `NotificationBackend.setNotificationLiveAllowForPackage(uid, pkg, checked)`
 * → `INotificationManager.setNotificationLiveAllowForPackage`，落库在
 * `PreferencesHelper.PackagePreferences.allowLive`。
 *
 * 而它唯一的消费点是 `NotificationManagerService` 入队时的这一句：
 * ```
 * if (!preferencesHelper.getNotificationLiveAllowForPackage(pkg, uid)) {
 *     extras.putInt("notification.live.type", 0)     // "Reset live notification---->NON"
 * }
 * ```
 * 也就是说：**开关只决定 `notification.live.type` 这个 extras 字段要不要被抹掉**，
 * 进而决定 `StatusBarNotification.isLive()` 的真假。
 *
 * QQ音乐 的播放通知压根没有 `notification.live.*` 字段（走的是 ticker 歌词通道），
 * 我们的胶囊又从 `getLyricsNotifications()` 这条**旁路**渲染，全程不碰 `isLive()`。
 * 所以开关点开点关，胶囊都照常出现 —— 这不是开关坏了，是它和胶囊之间**没有任何连线**。
 *
 * ## 本类做的事
 *
 * 直接向 system_server 查询 allowLive，作为 [ArkLiveLyricHook] 放行门控的前置条件，
 * 把这条线补上：**用户在设置里关掉 QQ音乐 的实况通知 → 胶囊消失**。
 *
 * 读不到（Binder 异常 / 字段改名）时按放行处理：宁可保持原行为，也不要因为兼容问题
 * 让整个功能静默失效。
 */
internal object LiveAllowGate {

    private const val TAG = "FlymeArk/liveAllow"

    /** 同一帧流程里 `getTopLyricsDisplayText` 会被调两次，用短 TTL 挡住重复 Binder 调用 */
    private const val TTL_MS = 400L

    /**
     * 「关闭」判定防抖：false 必须持续超过该时长才认定为真关闭。
     *
     * 真机取证（v3.15.12，2026-11-04 18:22 / 18:05）：
     * 在音乐端点收藏/取消，会触发播放通知重建，`getNotificationLiveAllowForPackage`
     * 在 system_server 侧会短暂返回一次 `raw=false`（实测 1.6s ~ 4.2s 后自己变回 true），
     * 而用户的实况开关根本没动。旧逻辑把这次瞬时 false 当成「用户关了开关」，
     * 立刻 `switch OFF -> capsule released` 把胶囊释放掉了 —— 这就是「点收藏概率导致胶囊丢失」。
     *
     * 用户真实关闭开关是持久性的（一直 false），瞬时抖动只闪一下。
     * 所以用**时长防抖**：从第一次读到 false 起计时，持续超过该阈值仍为 false
     * 才放行关闭。阈值取 6s，覆盖实测抖动窗口（最大 4.2s）且不影响「关开关后胶囊尽快消失」的手感。
     */
    private const val OFF_HOLD_MS = 6_000L

    private var cachedAt = 0L
    private var cached = true
    private var lastLogged: Boolean? = null

    /** 当前防抖所属的包名（不同 App 的开关互相独立，不能共用计时） */
    private var offHoldPkg: String? = null
    /** 第一次读到 false 的时间戳；null 表示当前没有待确认的关闭 */
    private var offSince = 0L

    @Synchronized
    fun isAllowed(ctx: Context?, pkg: String): Boolean {
        if (ctx == null) return true
        val now = SystemClock.uptimeMillis()
        if (now - cachedAt in 0 until TTL_MS) return cached
        cachedAt = now
        cached = query(ctx, pkg)

        if (cached) {
            // 恢复 true：清掉任何待确认的关闭计时
            if (offSince != 0L) {
                offSince = 0L
                offHoldPkg = null
            }
        } else {
            // 读到 false：进入/继续关闭确认计时
            if (offSince == 0L || offHoldPkg != pkg) {
                offSince = now
                offHoldPkg = pkg
            }
            if (now - offSince < OFF_HOLD_MS) {
                // 抖动窗口内：仍按「开着」返回，绝不释放胶囊
                if (lastLogged != true) {
                    lastLogged = true
                    XposedBridge.log(
                        "[$TAG] allowLive($pkg) false hold ${now - offSince}ms/${OFF_HOLD_MS}ms"
                    )
                }
                return true
            }
            // 持续 false 超过阈值：认定用户真的关了开关
        }

        if (lastLogged != cached) {
            lastLogged = cached
            XposedBridge.log("[$TAG] allowLive($pkg) = $cached")
        }
        return cached
    }

    /** 开关被点后强制下次重新查（由设置进程的写入/SystemUI 的通知刷新触发） */
    fun invalidate() {
        cachedAt = 0L
    }

    private fun query(ctx: Context, pkg: String): Boolean = try {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return true.also { XposedBridge.log("[$TAG] no NotificationManager") }
        val svc = notificationService(nm)
            ?: return true.also { XposedBridge.log("[$TAG] no INotificationManager") }
        val uid = ctx.packageManager.getPackageUid(pkg, 0)
        val r = XposedHelpers.callMethod(svc, "getNotificationLiveAllowForPackage", pkg, uid)
        val allowed = r as? Boolean ?: true
        if (allowed != cached) {
            XposedBridge.log("[$TAG] raw=$r uid=$uid cls=${svc.javaClass.name}")
        }
        allowed
    } catch (t: Throwable) {
        XposedBridge.log("[$TAG] query failed: $t")
        true
    }

    /**
     * 取 `INotificationManager`（`NotificationManager.sService`）。
     * 优先按字段名反射，失败再走静态方法 `getService()`，避免某个 ROM 改字段名后整条链路失效。
     */
    private fun notificationService(nm: NotificationManager): Any? {
        for (name in arrayOf("sService", "mService")) {
            try {
                val f: Field = NotificationManager::class.java.getDeclaredField(name)
                f.isAccessible = true
                val v = f.get(null)
                if (v != null) return v
            } catch (_: Throwable) {
            }
        }
        return try {
            XposedHelpers.callStaticMethod(
                NotificationManager::class.java, "getService"
            )
        } catch (_: Throwable) {
            null
        }
    }
}

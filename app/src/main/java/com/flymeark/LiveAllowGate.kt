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

    private var cachedAt = 0L
    private var cached = true
    private var lastLogged: Boolean? = null

    @Synchronized
    fun isAllowed(ctx: Context?, pkg: String): Boolean {
        if (ctx == null) return true
        val now = SystemClock.uptimeMillis()
        if (now - cachedAt in 0 until TTL_MS) return cached
        cachedAt = now
        cached = query(ctx, pkg)
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

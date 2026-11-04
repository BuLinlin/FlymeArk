package com.flymeark

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v2.6：把 QQ音乐 的专辑封面注入成实况胶囊图标。
 *
 * ## 为什么需要 hook 两处
 *
 * 1. `getLyricsNotifications(...)` 里 `smallIcon` 被**硬编码**成
 *    `R.drawable.ic_control_musiccontrol`（通用音乐图标），QQ音乐 传入的 drawable 参数
 *    在方法内被 `r5.getSbn()` 覆盖后丢弃。因此只能在方法返回后（after）改写
 *    `mLyricsInofo.smallIcon` —— 该 info 随后由 `updateLiveNotification` 交给
 *    `LiveNotificationView.setLiveNotificationInfo` 渲染。
 *
 * 2. `LiveNotificationView.setLiveNotificationInfo` 中 `showType == 5`（歌词）会执行
 *    `mLiveNotificationIcon.setColorFilter(progressContentColor)`，把图标整体染成单色。
 *    封面是彩色位图，必须清掉该滤镜；同时把 ScaleType 设为 CENTER_CROP 让方形封面
 *    铺满原图标的圆形容器（原图标是线条图形，默认 fitCenter）。
 *
 * 圆角在注入前用 Canvas 预裁剪完成，避免依赖 SystemUI 侧 View 的 outline provider。
 */
internal object ArkLiveCoverHook {

    private const val TAG = "liveCover"
    private const val CTRL_CLS =
        "com.flyme.statusbar.livenotification.LiveNotificationController"
    private const val VIEW_CLS = "com.flyme.statusbar.livenotification.LiveNotificationView"
    private const val INFO_CLS = "com.android.systemui.shared.livenotification.LiveNotificationInfo"
    private const val QQ_MUSIC = "com.tencent.qqmusic"

    /** 圆角半径占边长的比例（接近实况胶囊图标的 22% 圆角视觉） */
    private const val CORNER_RATIO = 0.24f

    /** 传给展开面板的封面最大边长（Binder 事务 1MB 上限，务必压小） */
    private const val PANEL_COVER_MAX = 320

    /** 缓存 key → 已圆角化的 Icon，避免歌词每行变化都重做位图 */
    private var cachedKey: String? = null
    private var cachedIcon: Icon? = null
    private var cachedPanelCover: Bitmap? = null
    private var lastNoCoverKey: String? = null

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != ArkConstants.SYSTEMUI_PKG) return

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG installing")

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
                            val sbn = XposedHelpers.callMethod(entry, "getSbn") ?: return
                            val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String
                            if (pkg != QQ_MUSIC) return

                            val ctrl = param.thisObject
                            val info = XposedHelpers.getObjectField(ctrl, "mLyricsInofo") ?: return
                            val notif = XposedHelpers.callMethod(sbn, "getNotification")
                                as? Notification ?: return
                            val ctx = XposedHelpers.getObjectField(ctrl, "mContext") as? Context

                            val raw = loadCover(ctx, notif)
                            val key = trackKey(notif)
                            if (raw != null) {
                                if (key != cachedKey || cachedIcon == null) {
                                    val rounded = roundCorners(raw, CORNER_RATIO)
                                    cachedIcon = Icon.createWithBitmap(rounded)
                                    cachedPanelCover = scaleForPanel(raw, ctx)
                                    cachedKey = key
                                    XposedBridge.log(
                                        "$TAG: cover ${raw.width}x${raw.height} -> capsule icon"
                                    )
                                }
                                cachedIcon?.let {
                                    XposedHelpers.setObjectField(info, "smallIcon", it)
                                }
                            } else if (key != lastNoCoverKey) {
                                lastNoCoverKey = key
                                XposedBridge.log(
                                    "$TAG: no cover in notification, extras=" +
                                        "${notif.extras?.keySet()?.joinToString()}"
                                )
                            }

                            // 交给展开面板用（同进程静态缓存，点击时直接打包进 Intent；
                            // 面板已展开时这里还会实时推送换曲信息）
                            ArkLiveExpandHook.onTrackInfo(
                                ctx = ctx,
                                sbn = sbn,
                                notif = notif,
                                cover = cachedPanelCover,
                                lyric = param.args[0]?.toString()
                            )
                        } catch (t: Throwable) {
                            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG inject error: $t")
                        }
                    }
                }
            )
        }

        // showType=5 会给图标上 colorFilter；封面必须在渲染后清掉，否则变成纯色剪影
        ArkHooks.quiet("$TAG setLiveNotificationInfo") {
            XposedHelpers.findAndHookMethod(
                VIEW_CLS, lpp.classLoader, "setLiveNotificationInfo",
                INFO_CLS, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            // 面板展开时胶囊需要保持隐形：原生每次刷新都会重设视图，
                            // 这里在渲染后立刻把胶囊容器压回 alpha=0，防止「闪回来」。
                            ArkLiveExpandHook.onLiveViewUpdated()

                            val info = param.args[0] ?: return
                            val icon = XposedHelpers.callMethod(info, "getSmallIcon") as? Icon ?: return
                            // 仅处理位图型图标（= 我们注入的封面），原生资源图标保持原样
                            if (icon.type != Icon.TYPE_BITMAP) return
                            val view =
                                XposedHelpers.getObjectField(param.thisObject, "mLiveNotificationIcon")
                                    as? ImageView ?: return
                            view.clearColorFilter()
                            if (view.scaleType != ImageView.ScaleType.CENTER_CROP) {
                                view.scaleType = ImageView.ScaleType.CENTER_CROP
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // 封面提取 / 处理
    // ------------------------------------------------------------------

    /**
     * 播放通知的大图即专辑封面。
     * 注意两个来源类型不同：`Notification.largeIcon` 是废弃的 Bitmap 字段，
     * `getLargeIcon()` 才是 Icon；QQ音乐 两种都可能用，逐级兜底。
     */
    private fun loadCover(ctx: Context?, n: Notification): Bitmap? {
        n.largeIcon?.let { return it }

        val icons = ArrayList<Icon>(2)
        n.getLargeIcon()?.let { icons.add(it) }
        (n.extras?.getParcelable(Notification.EXTRA_LARGE_ICON_BIG) as? Icon)?.let { icons.add(it) }
        (n.extras?.getParcelable(Notification.EXTRA_LARGE_ICON) as? Icon)?.let { icons.add(it) }

        for (icon in icons) {
            iconToBitmap(ctx, icon)?.let { return it }
        }
        return null
    }

    private fun iconToBitmap(ctx: Context?, icon: Icon): Bitmap? {
        if (icon.type == Icon.TYPE_BITMAP) {
            // compileSdk 上 getBitmap() 未作为 Kotlin 属性暴露（平台注解差异），走反射
            return try {
                XposedHelpers.callMethod(icon, "getBitmap") as? Bitmap
            } catch (_: Throwable) {
                null
            }
        }
        val c = ctx ?: return null
        val d = try {
            icon.loadDrawable(c)
        } catch (_: Throwable) {
            null
        } ?: return null
        if (d is BitmapDrawable) return d.bitmap

        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 256
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 256
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return bmp
    }

    /** 方形居中裁剪 + 圆角 */
    private fun roundCorners(src: Bitmap, cornerRatio: Float): Bitmap {
        val size = minOf(src.width, src.height)
        if (size <= 0) return src

        val square = if (src.width == size && src.height == size) {
            src
        } else {
            Bitmap.createBitmap(src, (src.width - size) / 2, (src.height - size) / 2, size, size)
        }

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val r = size * cornerRatio
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(square, 0f, 0f, paint)
        return out
    }

    /** 面板用的封面：缩到 320px 内再进 Intent，避免 TransactionTooLarge */
    private fun scaleForPanel(src: Bitmap, ctx: Context?): Bitmap {
        val size = minOf(src.width, src.height)
        val square = if (src.width == size && src.height == size) {
            src
        } else {
            Bitmap.createBitmap(src, (src.width - size) / 2, (src.height - size) / 2, size, size)
        }
        val target = minOf(size, PANEL_COVER_MAX)
        val scaled = if (size > target) {
            Bitmap.createScaledBitmap(square, target, target, true)
        } else {
            square
        }
        val density = ctx?.resources?.displayMetrics?.density ?: 3f
        return roundCorners(scaled, 0.16f).also { it.density = density.toInt() }
    }

    /** 同一首歌内复用已处理的位图（按曲名+歌手判重，不能用通知 when——每次更新都会变） */
    private fun trackKey(n: Notification): String {
        val ex = n.extras
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val artist = (ex?.getCharSequence(Notification.EXTRA_TEXT)
            ?: ex?.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString() ?: ""
        return "$title|$artist"
    }
}

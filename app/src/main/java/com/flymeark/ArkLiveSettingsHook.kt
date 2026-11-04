package com.flymeark

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * v2.4：让「设置 → 实况通知」能选到未声明 live_notification_config 的普通 App（QQ音乐）。
 *
 * 逆向确认（Flyme Settings / SystemUI）：
 *  - 实况通知主页 FlymeLiveFragmentNew.initConfigs() 通过
 *    queryIntentServices("com.android.systemui.LIVE_NOTIFICATION_SERVICE") 扫描各 App 声明的
 *    live_notification_config XML，把 LiveNotificationConfig 放进 mConfigs。
 *    QQ音乐未声明该 service，所以列表里根本没有它 —— 这就是「设置里选不到」的根因。
 *  - 单 App 通知设置页的「实况通知」开关由
 *    FlymeAppNotificationLiveEnableController.getAvailabilityStatus() 用同一套扫描判定，
 *    返回 3（不可用）时开关不显示。
 *  - 开关点击最终走
 *    NotificationBackend.setNotificationLiveAllowForPackage(uid, pkg, checked)
 *    → INotificationManager#setNotificationLiveAllowForPackage(pkg, uid, allow)，
 *    即系统原生的 allowLive 正规通道（与 ArkLiveAllowSetter 反射做的是同一件事，但由用户掌控）。
 *
 * 本类不直接改 allowLive，只负责「登记」：把 QQ音乐注入设置页列表 + 放开单 App 开关可见性，
 * 使系统原生开关可见、可点、状态可回显。用户自己勾选后，allowLive 由系统正规写入。
 */
internal object ArkLiveSettingsHook {

    private const val TAG = "liveSettings"
    private const val QQ_MUSIC = "com.tencent.qqmusic"

    private const val CLS_FRAGMENT =
        "com.meizu.settings.notification.fragment.FlymeLiveFragmentNew"
    private const val CLS_CONFIG =
        "com.meizu.settings.notification.config.LiveNotificationConfig"
    private const val CLS_CONTROLLER =
        "com.meizu.settings.notification.controller.FlymeAppNotificationLiveEnableController"

    fun install(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != ArkConstants.SETTINGS_PKG) return

        // 1) 实况通知主页：initConfigs 结束后往 mConfigs 注入一条 QQ音乐配置
        ArkHooks.quiet("$TAG initConfigs") {
            XposedHelpers.findAndHookMethod(
                CLS_FRAGMENT, lpp.classLoader, "initConfigs",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        injectConfig(param, lpp.classLoader)
                    }
                }
            )
        }

        // 2) 单 App 通知设置页：让「实况通知」开关对 QQ音乐可见
        ArkHooks.quiet("$TAG getAvailabilityStatus") {
            XposedHelpers.findAndHookMethod(
                CLS_CONTROLLER, lpp.classLoader, "getAvailabilityStatus",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val pkg = XposedHelpers.getObjectField(param.thisObject, "mAppPkg") as? String
                            if (pkg == QQ_MUSIC) {
                                param.result = 0   // BasePreferenceController.AVAILABLE
                                XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG: force AVAILABLE for $pkg")
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG availability error: ${t.message}")
                        }
                    }
                }
            )
        }

        XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG: hooks installed in ${lpp.packageName}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectConfig(param: XC_MethodHook.MethodHookParam, cl: ClassLoader) {
        try {
            val list = XposedHelpers.getObjectField(param.thisObject, "mConfigs") as? MutableList<Any>
            if (list == null) {
                XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG: mConfigs null, skip")
                return
            }
            // 去重（initConfigs 每次 onResume 都会重建列表，这里防御性判断）
            for (item in list) {
                val p = XposedHelpers.getObjectField(item, "packageName") as? String
                if (p == QQ_MUSIC) return
            }

            val cfgClass = XposedHelpers.findClass(CLS_CONFIG, cl)
            val cfg = newConfig(cfgClass)
            val ctx = fragmentContext(param.thisObject)
            val uid = resolveUid(param.thisObject, ctx)

            XposedHelpers.setObjectField(cfg, "packageName", QQ_MUSIC)
            // delegatedPackageName 留空：updatePreferenceStates 会走最干净的
            // app_category 非代理分支（回显 getNotificationLiveAllowForPackage(uid, packageName)，
            // 点击 setNotificationLiveAllowForPackage），避开美团/百度地图等厂商特判。
            XposedHelpers.setObjectField(cfg, "delegatedPackageName", null)
            XposedHelpers.setObjectField(cfg, "title", "QQ音乐")
            XposedHelpers.setObjectField(cfg, "description", "音乐播放实况通知")
            XposedHelpers.setBooleanField(cfg, "isService", false)  // false → app_category
            XposedHelpers.setIntField(cfg, "uid", uid)
            XposedHelpers.setIntField(cfg, "delegatedUid", uid)

            // 图标：取 QQ音乐自身 launcher 图标，与系统扫描出来的条目视觉一致。
            // sort/action/channelId 留空（app_category 分支不读取）。
            try {
                val icon = ctx?.packageManager?.getApplicationIcon(QQ_MUSIC)
                if (icon != null) XposedHelpers.setObjectField(cfg, "icon", icon)
            } catch (t: Throwable) {
                XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG icon failed: ${t.message}")
            }

            list.add(cfg)
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG: injected $QQ_MUSIC uid=$uid, mConfigs.size=${list.size}")
        } catch (t: Throwable) {
            XposedBridge.log("[${ArkConstants.LOG_TAG}] $TAG inject failed: $t")
        }
    }

    /**
     * 创建 [LiveNotificationConfig] 实例。
     *
     * 实测：`getDeclaredConstructor()` 抛 NoSuchMethodException（R8 处理后的类无可见无参构造），
     * 因此按「有参构造 → Unsafe 直接分配」三级降级：
     *  - 该类只被 `FlymeLiveFragmentNew` 读取 title/description/icon/packageName/uid/delegatedPackageName
     *    （不读 action / sort / channelId），绕过构造不会有未初始化字段引发的 NPE。
     */
    private fun newConfig(cfgClass: Class<*>): Any {
        // 1) 标准无参构造
        try {
            return cfgClass.getDeclaredConstructor().newInstance()
        } catch (_: Throwable) {
        }

        // 2) 任意可填充的有参构造
        val ctors = cfgClass.declaredConstructors
        try {
            XposedBridge.log(
                "[${ArkConstants.LOG_TAG}] $TAG ctors=" +
                    ctors.joinToString("; ") { it.toGenericString() }
            )
        } catch (_: Throwable) {
        }
        for (c in ctors) {
            try {
                c.isAccessible = true
                val args = c.parameterTypes.map { zeroOf(it) }.toTypedArray()
                return c.newInstance(*args)
            } catch (_: Throwable) {
            }
        }

        // 3) 兜底：Unsafe.allocateInstance 绕过构造函数（不执行 <init>，字段留默认值）
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val holder = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = holder.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, cfgClass)
    }

    /** 为构造参数生成默认值（0 / false / null） */
    private fun zeroOf(type: Class<*>): Any? = when (type) {
        Int::class.javaPrimitiveType -> 0
        Boolean::class.javaPrimitiveType -> false
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> ' '
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        else -> null
    }

    /** Fragment 的 Context（Settings 进程内可用；Fragment 未 attach 时返回 null） */
    private fun fragmentContext(thisObj: Any): Context? = try {
        XposedHelpers.callMethod(thisObj, "requireContext") as? Context
    } catch (_: Throwable) {
        null
    }

    /** 动态取 QQ音乐 uid（重装/多用户后会变，不能硬编码） */
    private fun resolveUid(thisObj: Any, hint: Context?): Int {
        // 优先用 Fragment 的 Context
        try {
            val ctx = hint ?: fragmentContext(thisObj)
            if (ctx != null) {
                @Suppress("DEPRECATION")
                val uid = ctx.packageManager.getPackageUid(QQ_MUSIC, 0)
                if (uid > 0) return uid
            }
        } catch (_: Throwable) {
        }
        // 兜底：当前进程 Application
        return try {
            val ctx = ArkHooks.appContext()
            @Suppress("DEPRECATION")
            ctx?.packageManager?.getPackageUid(QQ_MUSIC, 0) ?: -1
        } catch (_: Throwable) {
            -1
        }
    }
}

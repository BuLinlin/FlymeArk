# FlymeArk 混淆规则
# 入口类由 assets/xposed_init 里的字符串引用，必须保留
-keep class com.flymeark.ArkEntry { *; }
# 模块内所有类都被 Xposed 反射/跨进程按名字引用（面板 Activity、hook 类），整体保留
-keep class com.flymeark.** { *; }
# 面板 Activity 由 SystemUI 侧按类名显式启动
-keepnames class com.flymeark.ArkCapsulePanelActivity

# FlymeArk - 魅族实况通知优化 LSPosed 模块

> 为第三方应用接入 Flyme 实况通知，支持消息 / 音乐 / 导航三类实况。

## 📥 下载

前往 [Releases](https://github.com/BuLinlin/FlymeArk/releases) 下载最新的 APK 安装包。

## 📦 安装

> 在 LSPosed 勾选目标应用后，前往系统「设置 - 通知与状态栏 - 实况通知」开启对应开关即可。

**参考步骤：**

1. 设备需要配置好 LSPosed 环境（Magisk / KernelSU）
2. 下载并安装最新 APK
3. 在 LSPosed 中启用模块，作用域勾选：`SystemUI`、`设置`，以及目标应用（见下方「支持应用」）
4. 重启 SystemUI 或整机重启
5. 进入：设置 → 通知与状态栏 → 实况通知，打开对应应用的开关

## ✨ 功能

- 🟢 **消息类实况通知（微信 / QQ / TIM）**
  状态栏胶囊迷你条 + 展开消息卡片，实时查看未读消息。胶囊标题显示真实会话名/昵称，副标题显示「N 条新消息 · 相对时间」，长标题自动省略。
- 🎵 **音乐类实况通知（QQ音乐 / 网易云音乐 / Sait Player）**
  支持音乐播放实况卡片、媒体输出切换，可启用歌词实况展示，在状态栏查看播放状态与歌词。
- 🧭 **导航类实况通知（高德地图）**
  驾车 / 骑行 / 步行导航三格数据（总里程 / 剩余耗时 / 预计到达）投到实况迷你条，支持转向指令与展开导航卡。

## 📱 支持应用

| 应用 | 包名 | 类型 |
|---|---|---|
| 微信 | `com.tencent.mm` | 消息 |
| QQ | `com.tencent.mobileqq` | 消息 |
| TIM | `com.tencent.tim` | 消息 |
| QQ音乐 | `com.tencent.qqmusic` | 音乐 |
| 网易云音乐 | `com.netease.cloudmusic` | 音乐 |
| Sait Player | `com.salt.music` | 音乐 |
| 高德地图 | `com.autonavi.minimap` | 导航 |

## ⚙️ 兼容性

- 仅适用于 Flyme 系统（依赖 `com.flyme.*` 的实况通知实现）
- 安装包为 debug 签名，仅供自用、测试用途

## ⚠️ 免责声明

本项目属于改机模块，**使用风险自负**，操作前请确保已做好系统恢复备份。

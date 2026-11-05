# 导航类实况通知：百度 vs 高德，可行性研究

> 研究时间：2026-11-05
> 结论口径：**静态分析**（jadx）+ 已确认的线上系统行为。未在真机上抓过包 / dump 过通知。
> 相关文件：`apk-work/nav/`（amap.apk / baidu.apk / suggestion.apk 与分析脚本）

---

## 0. 一句话结论

**可以复刻，而且不需要往高德进程里注入。**

高德的导航通知本身就已经带齐了做实况卡所需的全部素材（转向箭头大图标 + 标题 + 剩余里程/时间 + 点击意图），
FlymeArk 只要在 **SystemUI 侧**把这条通知"认出来"，补上 `notification.live.*` 一套实况字段，系统自己就会把它渲染成实况通知。

百度那条链路（智能服务 bindService 拉 `GuideInfo` 再代发）是**百度不自己发通知**导致的绕路，
高德自己就发通知，所以我们走的是**更短**的那条路。

---

## 1. 百度实况是怎么实现的（已证实）

这是系统内置行为，不是我们造的。链路：

```
百度地图  <--bindService-->  com.meizu.suggestion（智能服务，系统应用）
                                |
                                | 作为"委托发布者"发通知
                                v
                          notification.live.* 实况通知
```

关键实现类：`com.meizu.suggestion.notification.baidumap`（包内**只有**这一个地图实现）

委托协议要点（`notification/baidumap/a.java`，1100 行）：

| 字段 | 值 |
|---|---|
| `notification.live.operation` | `0` |
| `notification.live.type` | `10`（导航类） |
| `notification.live.event` | `"NAVIGATION"` |
| `notification.live.capsule` | 子 Bundle（见下） |
| `notification.live.custom.remoteview.nopadding` | `true` |
| `notification.live.app.pkg.delegated` | `"com.baidu.BaiduMap"` |
| `notification.live.app.pkg.delegated.forsettings` | `"com.baidu.BaiduMap"` |
| `notification.live.bgColorOverlay` | 颜色 int |
| `notification.live.pinned` | `false` |
| `notification.live.app.delegatedpac.nopined` | `true` |

`capsule` 子 Bundle：

| 键 | 类型 | 含义 |
|---|---|---|
| `capsuleStatus` | int = 1 | 胶囊状态 |
| `capsuleType` | int = 1 | 胶囊类型 |
| `capsuleContent` | String | 胶囊文案 |
| `capsuleIcon` | Parcelable `Icon` | 胶囊图标 |
| `capsuleBgColor` | int | 胶囊底色 |
| `capsuleContentColor` | int | 胶囊文字色 |

数据来源：智能服务用 Messenger `bindService` 到
`com.baidu.BaiduMap/com.baidu.baidumaps.desktopwidget.walkinfo.ExtGuideService`，
携带 `packName` + 固定 `appSha1`（校验签名），持续接收 `GuideInfo`。

> **为什么百度要这么绕**：百度地图自己不往通知栏发导航实况通知（它只发一条普通常驻导航通知），
> 所以魅族只能写一个系统级的中间人，去它进程里**主动拉**数据，再以"委托"身份代发。

---

## 2. 高德：我们有什么

### 2.1 加固情况：**无加固，可静态分析**

`amap.apk` 194 MB，`classes.dex` ~ `classes8.dex` 共 8 个，dex 总量约 96 MB。

- 没有 `libDexHelper` / `libjiagu` / `libshella` / `libmobisec` 之类壳 so
- 全部类名、方法名、字符串可正常读出（`com.autonavi.*` 语义完整，只有少量短名混淆如 `ug4` / `rg4` / `b03`）

### 2.2 高德**不发**实况通知

对 8 个 dex 全量做字节扫描：

| 模式 | 命中 |
|---|---|
| `notification.live` | **0** |
| `live.capsule` | **0** |
| `capsuleContent` | **0** |
| `livealert` | 1（只在 `AMapImmerseNaviService`，见 2.5，与实况通知无关） |

→ 高德完全没有适配 Flyme 实况协议，也没有任何"委托发布"接入口。

### 2.3 高德也**没有**百度的那个 `ExtGuideService`

manifest 里与导航相关、可能对外暴露的组件：

| 组件 | 判断 |
|---|---|
| `com.autonavi.minimap.service.NAVIGATION_SERVICE` | 有配套 `com.autonavi.minimap.permission.NAVIGATION_SERVICE` 权限，签名级，外部拿不到 |
| `com.autonavi.minimap.immersiveNavi.AMapImmerseNaviService` | 沉浸导航，见 2.5 |
| `com.autonavi.navigation.service.CarNaviService` | 车机/HiCar 投屏用 |
| `com.autonavi.minimap.MainProcessEdgeProvider` | ContentProvider，"edge"通道，未展开 |
| `com.autonavi.minimap.hiboardcardprovider` / `hiboardcardimageprovider` | 华为负一屏卡片 |

没有 `ExtGuideService` 那种"外部传 packName+sha1 就能订阅导航状态"的入口。

### 2.4 ★ 关键发现：高德导航通知的真实来源

这条链是完整的、可用的。

```
导航页 / TBT 引擎
    │
    ▼
NotificationServiceAdapter            (com.autonavi.minimap.route.common.notification)
    │  INotificationServiceAdapter.notify(IRouteNotification)
    ▼
INotifyService  ──startNotifyService/updateBackStageInfo──►  NotifyServiceImpl
                                                             (com.autonavi.bundle.amaphome.compat.service)
                                                             ★ 真正 NotificationManager.notify 的地方
```

`NotificationServiceAdapter.notify()` 只传 4 个字段进去：

```java
this.d.updateBackStageInfo(
    iRouteNotification.getChannelId(),
    iRouteNotification.getIconResId(),
    iRouteNotification.getTitle(),
    iRouteNotification.getContent());
```

`IRouteNotification` 接口（`com.autonavi.bundle.routecommon.api`）就这几个字段：

```java
ug4    getChannelId();
String getContent();
int    getIconResId();
String getTitle();
int    getType();
```

`getType()` 取值：`1`=公交导航 `2`=步行导航 `3`=骑行导航 `4`=跑步 `5`=顺风车 `6`=共享单车 `7`=步行副屏 `8`=骑行副屏。

#### `NotifyServiceImpl.h()` 构造的通知（原文关键行）

```java
Bitmap bitmap = BitmapFactory.decodeResource(getResources(), iconResId);   // ← 转向箭头大图标
NotificationCompat.Builder ongoing =
    new NotificationCompat.Builder(this)
        .setLargeIcon(bitmap)                       // 转向箭头
        .setSmallIcon(R.drawable.notification_amap) // 高德 logo
        .setTicker(title)
        .setWhen(System.currentTimeMillis())
        .setContentTitle(title)                     // 例："导航中"
        .setContentText(content)                    // 例：剩余里程/时间
        .setContentIntent(pendingIntent)            // amapuri://... → SplashActivity，flags 0x24000000
        .setExtras(bundle)                          // bundle: "com.autonavi.minimap.navigating" = true
        .setAutoCancel(false)
        .setOngoing(true);
if (SDK_INT >= 31) ongoing.setCategory("navigation");
```

#### 识别特征（给 SystemUI 侧用）

| 特征 | 值 | 稳定性 |
|---|---|---|
| extras | `com.autonavi.minimap.navigating` = `true` | ★★★ 显式业务标记，最可靠 |
| category | `"navigation"`（SDK≥31） | ★★★ |
| notification id | `99910001`=驾车 / `99910002`=步行 / `99910003`=骑行，其它 `10000` | ★★ 硬编码常量，随版本可能变 |
| channel id | `ROUTE_car` / `ROUTE_walk` / `ROUTE_ride` / `ROUTE_bus_navi` … | ★★ |
| flags | `FLAG_ONGOING_EVENT`，`autoCancel=false` | ★ |
| 点击 | `amapuri://` scheme + `com.autonavi.map.activity.SplashActivity` | ★★ |

`channel id` 生成规则（`ug4` / `rg4`）：`<组前缀> + "_" + <业务键>`，组前缀只有三个：
`COMMON` / `ROUTE` / `ORDER`。驾车导航 = `ROUTE` + `_car`。

> 注：通道 id 用到的业务键常量 `DriveUtil.NAVI_TYPE_CAR` / `MiniAppRouteHelper.SEARCH_TYPE_WALK`
> 的值是按命名推断为 `"car"` / `"walk"`，**未逐字核对常量值**；但因为有 `navigating` extras 兜底，不影响实现。

### 2.5 沉浸导航服务（另一条路，v2 再考虑）

`com.autonavi.minimap.immersiveNavi.AMapImmerseNaviService`（classes6，约 470 行）

这是一个 **Messenger 协议的服务**，能把高德的导航地图渲染成 `SurfaceControlViewHost.SurfacePackage` 交给宿主窗口显示——
本质就是"把高德的实时导航地图嵌到你自己的界面里"。协议：

| what | 方向 | 含义 |
|---|---|---|
| `11` | →服务 | `hostToken`(Binder) + `extra` Bundle，内含 `livealert.immersive.display`{width,height} → 请求绑定 |
| `12` | →服务 | 尺寸变化（`event=1`） |
| `13` | →服务 | 状态变化 |
| `14` | →服务 | 解绑 |
| `10000003` | →服务 | 概览态开关（`openOverview` 1/2） |
| `10000004` | →服务 | 通用 JSON 指令 |
| `21` | ←服务 | 回传 `SurfacePackage` |

限制：`SDK_INT >= 30`；服务在 manifest 里挂了 `com.autonavi.minimap.permission.NAVIGATION_SERVICE`；
初始化还依赖高德自己进程内已运行的 `IAjxContext` 与 `initConfig`（`h8`）。

→ 用它可以做出"展开卡里是高德真地图"的效果（很惊艳），但**必须先注入高德进程**，风险高，留作 v2。

### 2.6 高德已有的厂商适配痕迹（说明它愿意做，只是没做魅族）

字符串表里能扫到：

- `HasAdaptedLockScreenNavigation-HuaWei`
- `huawei_navi_extend`、`hi_car_navi_page`、`autonavi_hicar_drivemode_state`
- `vivo_navigation_started`、`vivo_navigation_started_config`
- `miui.appwidget.action.APPWIDGET_UPDATE`

→ 高德对华为/荣耀/vivo 的锁屏导航、灵动岛、负一屏卡片都有专门适配，**唯独没有 Flyme**。
这也侧面说明：它内部有"把导航状态吐给外部"的抽象，只是按厂商分支，魅族分支不存在。

---

## 3. 三条可选方案

### 方案 A（推荐）：SystemUI 侧注入实况字段 —— 不改高德一行代码

```
高德自己发导航通知（ROUTE_car / 99910001 / navigating=true）
        │
        ▼
SystemUI 的通知管线  ←── FlymeArk 在这里拦一下
        │                · 认出高德的导航通知
        │                · 读 title / content / largeIcon / contentIntent
        │                · 注入 notification.live.* + capsule 子 Bundle
        ▼
系统按实况通知渲染（胶囊 / 展开卡 / 迷你条）
```

**为什么这条最优：**

- 数据已经在通知里了：`largeIcon` 是**真的 Bitmap 转向箭头**，`title` = "导航中"，`content` = 剩余里程/时间
- 点击意图原样保留 → 点卡片直接回高德导航页
- 高德撤通知 = 实况自动消失，不用自己管生命周期
- 只在 SystemUI 一个进程内工作，和现有 `ArkLiveExpandHook` 同一套机制
- 高德升级不影响（识别靠 `navigating` extras，不是靠类名）

**代价 / 限制：**

- 胶囊里能显示的信息上限 = 高德通知里有的那些（转向箭头 + "导航中" + 剩余 X 公里/Y 分钟）。
  高德通知里**没有**"下一条路名"、没有"前方 300 米右转"这类文案，静态分析确认它只传 title/content 两个字符串。
  想要路名，只能往高德进程里注入去读 TBT 状态（方案 B 的事）。
- Flyme 的实况管线对 `type=10` 可能还有额外校验（比如要求 app 在某个白名单里），需要真机验证一次。

### 方案 B：注入高德进程，自己构造并发送通知

需要：把 `com.autonavi.minimap` 加进 LSPosed 作用域 → 找 TBT 状态回调（`com.autonavi.jni.eyrie.amap.tbt.NaviManager` /
`com.autonavi.bundle.routecommon.api.INaviStatus` / `bnv` 引擎的 `NaviPointInfo`）→ 自己拼 `notification.live.*` 发出去。

问题：**收益为零**。通知模型里就 title/content/iconResId 三个字段，
就算拿到路名，最终也还是要塞进同一个 `content` 字符串。除非我们完全绕开高德的 `NotifyServiceImpl`、自己做 RemoteViews 展开卡。

→ 不划算。除非后面要做"路名 / 红绿灯读秒 / 车道级"这类高德通知里没有的信息，才值得上。

### 方案 C：`AMapImmerseNaviService` 嵌真地图

见 2.5。视觉最好，风险最高。v2 候选。

---

## 4. 方案 A 的实现规格（下一步就干这个）

### 4.1 识别

```kotlin
// SystemUI 侧，通知入库/上屏前
val isAmapNavi = sbn.packageName == "com.autonavi.minimap" &&
    sbn.notification.extras.getBoolean("com.autonavi.minimap.navigating", false) &&
    (Build.VERSION.SDK_INT < 31 || sbn.notification.category == "navigation")
```

兜底：`sbn.id in intArrayOf(99910001, 99910002, 99910003)`，或 `channelId.startsWith("ROUTE_")`。

### 4.2 取素材

| 实况字段 | 来源 |
|---|---|
| `capsuleContent` | `extras.getCharSequence(EXTRA_TITLE)` + `EXTRA_TEXT` 拼接（"导航中 · 剩余 3.2 公里 12 分钟"） |
| `capsuleIcon` | `Icon.createWithBitmap(extras.getParcelable(EXTRA_LARGE_ICON_BITMAP))`，退化到 `largeIcon`、再退化到 app icon |
| `capsuleBgColor` | 沿用 `ArkTokens` 现有胶囊底色 |
| `capsuleContentColor` | 沿用 `ArkTokens` 主文字色 |
| `type` | `10` |
| `event` | `"NAVIGATION"` |
| `operation` | `0` |
| `status` | `1` |

### 4.3 展开卡

复用 `ArkMessagePanelActivity` 的骨架，但要新增一个**导航卡**布局：
- 顶部：转向箭头 + 剩余距离/时间（大字）
- 中部：目的地
- 底部：结束导航
→ **这块缺设计稿，等设计。**

### 4.4 待真机验证的未知项

1. 自发布（非委托）通知注入 `notification.live.type=10` 后，SystemUI 是否真的走实况渲染（会不会只认 `delegated`）。
2. `capsuleIcon` 传 `Icon.createWithBitmap` 是否被接受（百度传的是 `Parcelable Icon`，来源可能是资源 id）。
3. 高德导航通知是**另一个 AMap 进程**（`NotifyServiceImpl` 是 Service）发的，id 是否会随进程重启变化。
4. 高德在导航结束、切后台、锁屏时是否会改发非 ongoing 通知（影响实况何时消失）。

---

## 5. 顺手记一条：白名单那件事的定性

`ArkLiveSettingsHook.TARGETS` 那个白名单**不是**实况能否工作的开关。
`PreferencesHelper.PackagePreferences.allowLive` 默认就是 `true`（构造函数默认值 + XML 默认 + 无记录时 `return 1`），
它只控制「设置 → 实况通知 → 应用列表」里是否显示这一行。

→ 把 `com.autonavi.minimap` 加进去只是**让开关可见**，是个纯粹的 UI 完善项，和方案 A 能不能跑通没有因果关系。
可以顺手加，但不要指望它解决问题。

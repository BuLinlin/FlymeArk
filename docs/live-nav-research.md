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

### 3.1 如果将来要走方案 B：注入点已经找到了

`com.autonavi.bundle.routecommon.inter.IRouteNaviInfoController`（classes5）是现成的观察者接口：

```java
public interface IRouteNaviInfoController {
    boolean addNaviInfoListener(INaviInfoListener l);
    boolean removeNaviInfoListener(INaviInfoListener l);
    void notifyNaviInfo(@NonNull ej5 info);
    void setNotifyStateListener(INotifyStateListener l);
    boolean getNotifyState();

    interface INaviInfoListener { void onNaviInfoChanged(@NonNull ej5 info); }
    interface INotifyStateListener { void onNotifyStateChanged(boolean z); }
}
```

载荷 `ej5`（`defpackage.ej5`，`toString()` 自报 `RouteNaviInfo`）：

```java
public final class ej5 {
    public final String f13969a;  // bizType
    public final String b;        // title
    public final String c;        // content
    public final int    d;        // status
    public final int    e;        // icon（转向箭头 drawable res）
    // toString: "RouteNaviInfo{bizType='..', title='..', content='..', status=.., icon=..}"
}
```

hook `notifyNaviInfo(ej5)` 或 `INaviInfoListener.onNaviInfoChanged` 就能拿到导航页上那一套实时信息。
旁边还有 `com.autonavi.bundle.routecommon.util.RouteNaviInfoConstant`（内含 `ManeuverIconID` / `STARTEND` / `SUBWAY_ICON_INDEXS`
三个嵌套接口，常量被 jadx 内联成空接口，转向码枚举需要另行 dump）。

> **★ 一个重要推论**：`RouteNaviInfo` 的 `title` / `content` / `icon` 三个字段，
> 正好就是 `IRouteNotification`、也就是最终进通知的那三个字段。
> 换句话说——**通知里的 title/content 就是导航页顶部的导航信息本身**。
> 如果高德的 `title` 其实是转向指令（"右转"）、`content` 是路名/距离，
> 那我们**不用注入任何进程**就已经和百度拿到同等信息了。
>
> 这一点静态分析确认不了（取决于运行期赋值），**必须靠真机日志看 `EXTRA_TITLE` / `EXTRA_TEXT` 的实际值**。
> v3.15.51 的三段日志就是为此埋的——这是整个方案最关键的一个待验证假设。



### 方案 B：注入高德进程，读实时导航信息

需要：把 `com.autonavi.minimap` 加进 LSPosed 作用域 → hook 上面 §3.1 的 `IRouteNaviInfoController.notifyNaviInfo(ej5)`
（或 TBT 层的 `com.autonavi.jni.eyrie.amap.tbt.NaviManager`、`com.autonavi.jni.ae.guide.model.NaviPointInfo`）
→ 自己拼 `notification.live.*` 发出去，或把富字段回传给 SystemUI 侧。

**它到底值不值得做，取决于 §3.1 那个假设：**
- 若真机日志显示高德的 `title` 就已经是转向指令、`content` 是路名/距离 → **方案 A 完全够用，方案 B 收益为零**；
- 若 `title` 只是「导航中」、`content` 只是「剩余 X 公里 Y 分钟」→ 方案 A 只能到这一步，
  想要百度那种「右转 / 进入学院路」就**只能**上方案 B。

→ **不要现在决定。等 v3.15.51 的真机日志。** 这是唯一能定性的证据。

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

## 4.5 协议权威定义（`framework.jar`，比反推智能服务更可靠）

`apk-work/systemui/framework-jadx/sources/` 是魅族改造过的 framework，实况协议的常量和读取逻辑都在这儿。

### ★ 唯一的实况判定条件

`android/service/notification/StatusBarNotification.java`：

```java
public boolean isLive() {
    return notification.extras.getInt("notification.live.type", 0) != 0;
}
```

**就这一行。** 没有任何白名单校验、没有"必须是委托发布者"的校验、没有 `allowLive` 校验。
`type != 0` 即视为实况通知。**这是整个方案能成立的根本依据。**

### capsule 的读取方式（`getCapsuleBundle()` + 逐字段 getter）

```java
private Bundle getCapsuleBundle() {
    return notification.extras.getBundle("notification.live.capsule");   // ← 确认是嵌套 Bundle
}
public String getCapsuleContent() {
    Bundle b = getCapsuleBundle();
    return b == null ? null : b.getString("notification.live.capsuleContent");  // ← 子 Bundle 内 key 仍带全前缀
}
```

框架侧的完整 getter 清单（对应 key 完全一致）：

| getter | key | 类型 | 取不到时的默认 |
|---|---|---|---|
| `getCapsuleContent()` | `notification.live.capsuleContent` | String | null |
| `getCapsuleTitle()` | `notification.live.capsuleTitle` | String | null |
| `getCapsuleIcon()` | `notification.live.capsuleIcon` | Parcelable **Icon** | null |
| `getCapsuleIconLottie()` | `notification.live.capsuleIcon.lottie` | String | "" |
| `getCapsuleStatus()` | `notification.live.capsuleStatus` | int | -1 |
| `getCapsuleType()` | `notification.live.capsuleType` | int | -1 |
| `getCapsuleBgColor()` | `notification.live.capsuleBgColor` | int | -1 |
| `getCapsuleContentColor()` | `notification.live.capsuleContentColor` | int | -1 |
| `getCapsuleTime()` | `notification.live.capsuleTime` | long | `elapsedRealtime()` |
| `getCapsuleCountDown()` | `notification.live.capsuleCountDown` | boolean | false |
| `getCapsuleFormat()` | `notification.live.capsuleFormat` | String | null |
| `getCapsulePause()` | `notification.live.capsulePause` | boolean | false |
| `getCapsulePauseTime()` | `notification.live.capsulePauseTime` | String | "0" |
| `getCapsuleRemind()` | `notification.live.capsuleRemind` | boolean | false |
| `getCapsuleClickLaunchPendingIntent()` | `notification.live.capsule.click_launch_pending_intent` | boolean | false |
| `getCapsuleContentRemoteView()` | `notification.live.capsule.content.remote.view` | **RemoteViews** | null |

→ 后三个（`capsuleCountDown` / `capsuleTime` / `capsuleFormat`）之前不知道，**对导航很有用**：
`CountDown + capsuleTime` 可以直接让系统跑一个倒计时，适合「预计 18:42 到达」这类表达。

### framework 定义的 type 常量

只有 4 个：`TYPE_BASE=1`、`TYPE_PROGRESS=3`、`TYPE_EMPHASIZE=4`、`TYPE_SYMMETRY=5`。
**`10` 不在其中**——说明 `10` 是应用自定义类型（百度用的就是 10），框架只校验 `!= 0`，
具体卡片样式由 SystemUI 的 `com.flyme.statusbar.livenotification` 按数值再分发。

### 其他可用字段（顶层 extras，非 capsule 内）

`notification.live.bgColorOverlay` / `contentOverlay` / `titleOverlay` / `contentColor` / `titleColor`
`notification.live.can.delete`
`notification.live.feature.*`（`titleText` / `contentText` / `firstTitleText` / `firstContentText` / `extendImage` / `chronometer*`）
`notification.live.circle.*`（进度环：`currentprogress` / `previousprogress` / `parentprogress.color` / `closeIcon` / `setprogress.need.animation`）
`notification.live.custom.remoteview.nopadding`
`notification.live.app.pkg.delegated` / `...forsettings` / `app.delegatedpac.nopined`

---

## 4.6 界面参考：系统里百度那张导航卡的真实规格

用户要求照系统里百度那张卡做。那张卡**不在 baidu.apk 里**，而是智能服务自己画的——
`com.meizu.suggestion.notification.baidumap.a#s(Context, GuideInfo, Bitmap)`：

```java
RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.live_notification_guide_info);
// GuideInfo.guideInfo 形如 "右转#进入学院路"，按 '#' 拆成两段
rv.setTextViewText(R.id.tv_title, 第一段);      // "右转"
rv.setTextViewText(R.id.tv_content, 第二段);    // "进入学院路"，为空则 setVisibility(GONE)
rv.setImageViewBitmap(R.id.iv_guide, bitmap);   // 转向箭头
// 特例：guideInfo 含 "卫星信号弱" → 换成 R.drawable.baidu_map_guide_satellite
```

布局 `res/layout/live_notification_guide_info.xml` 实测结构（aapt2 导出）：

```
RelativeLayout  (match_parent × wrap_content)
└─ RelativeLayout  padding = 16dp
   ├─ ImageView  iv_guide   44×44dp   layout_centerVertical=true
   └─ LinearLayout  垂直  match × wrap  centerVertical
      │              layout_marginStart = 8dp   layout_toEndOf = iv_guide
      ├─ TextView tv_title     20sp  #FFEEEEEE  sans-serif-medium
      │                        singleLine  ellipsize=marquee  gravity=center_horizontal
      └─ TextView tv_content   14sp  #B3EEEEEE (70% 白)  gravity=center_horizontal
```

配色取自同包资源：卡片底 `baidu_map_walk_bike_notify_bg = #B21A1A1A`（70% 深色玻璃），
标题 `#FFEEEEEE`，正文 `#B3EEEEEE`，胶囊文字色 `baidu_map_walk_bike_capture`。

**结构其实极简**：一枚 44dp 转向箭头 + 两行居中文本（20sp 指令 / 14sp 路名），没有面板、没有分割线、没有按钮。
比我们现在的消息卡/音乐卡简单得多。

### ⚠ 一个必须说清的数据落差

百度那张卡的三个信息位是：**转向图标 + 转向指令（"右转"） + 路名（"进入学院路"）**。

高德通知里能拿到的只有：**转向图标 + `"导航中"` + 剩余里程/时间**。

也就是说——版式可以 1:1 照抄，但**内容结构对不上**：高德没有「转向指令」和「路名」这两个字段。
照抄版式的结果会是「arrow + 导航中 + 剩余 3.2 公里 12 分钟」，
虽然仍然成立、甚至信息量不比百度少，但**不是**百度那张卡的样子。

要真的做出百度那种「右转 / 进入学院路」，只有两条路：
1. 注入高德进程读 TBT 状态（方案 B，见 §3）——高德 `libamaptbt.so` 引擎里有转向码和路名，但要找到稳定的回调点；
2. 解析高德展开通知里的 RemoteViews（高德导航通知没设自定义 RemoteViews，所以没有可解析的文本）。

→ 建议：**第一版先按「arrow + 导航中 + 剩余里程/时间」出**，版式照抄百度（44dp 图标 + 20sp/14sp 居中文本），
等真机日志确认高德 `content` 到底长什么样，再决定要不要为了两个字段去注入高德进程。

---

## 5. 顺手记一条：白名单那件事的定性

`ArkLiveSettingsHook.TARGETS` 那个白名单**不是**实况能否工作的开关。
`PreferencesHelper.PackagePreferences.allowLive` 默认就是 `true`（构造函数默认值 + XML 默认 + 无记录时 `return 1`），
它只控制「设置 → 实况通知 → 应用列表」里是否显示这一行。

→ 把 `com.autonavi.minimap` 加进去只是**让开关可见**，是个纯粹的 UI 完善项，和方案 A 能不能跑通没有因果关系。
可以顺手加，但不要指望它解决问题。

---

## 6. 结论修正：数据是从「语音播报」拿到的，不是通知里

§4.6 末尾那个「数据落差」的判断（第一版只能做 `arrow + 导航中 + 剩余里程`），
在后来的真机取证里被**推翻了一半**：

- 高德导航通知的 `android.title` / `android.text` 确实是占位值（`高德地图` / `正在导航`），
  而且 `setLargeIcon` 里那枚箭头也拿不到语义；
- 但是 `com.amap.bundle.audio` + `com.autonavi.jni.tts` 这条链里，
  `AudioPlayerTask` 的公开字段 `data`(String) / `groupData`(String，JSON 数组)
  **就是即将播报的明文中文**。抽象 `JNIPlayer` 在 Java 层只有一个实现（混淆类 `j25`，
  classes4），hook `playPCMStart` / `playAudioFileStart` 即可在播报前拿到文本：

```
10:24:42.747 ttspcm data='您已偏航,七十米后左转,随后六十米右转,新路线预计多三点三公里'
10:24:42.747 navTts: 七十米后左转，随后六十米右转，新路线预计多三点三…
```

已证伪的死路（不要再试）：`NaviManager.registerEventReceiver/queryData`（真机 0 次调用，
只服务车机投屏 / HiCar）、`fj5.notifyNaviInfo` / `ModuleRoute.notifyNaviInfo`（0 次）、
`CrossNaviInfo` / `CurrentNaviInfo` / `SoundInfo`（8 个 dex 全量扫描 0 个 Java 引用）、
AJX 导航模块、任何 Java 层导航卡 View 类、Android `android.speech.tts`。

**仍然成立的那半句**：实时「剩余距离 / 剩余时间 / 预计到达」这三个**递减值**依然拿不到。
播报里只有出发那一次的总量，以及每次转向时的距离短语。这就是下一节三栏口径的由来。

---

## 7. 展开卡载荷通道（v3.15.61）

### 7.1 三进程隔离

```
com.autonavi.minimap        com.android.systemui          com.flymeark
  ArkNavLiveInjector    →     ArkLiveExpandHook      →     ArkNavPanelActivity
  (写语音播报解析结果)        (响应胶囊点击，搬运)          (渲染卡片)
        │                          │                          │
        └── 影子通知 extras ───────┘                          │
             tag=flymeark_live_nav                            │
             id =0x4B000002                                  │
                                    └── Intent extras ───────┘
```

三个进程没有共享内存。`SharedPreferences` 是 app 私有、`ContentProvider` 要新增组件、
文件写不进彼此的沙箱；而**影子通知本来就是一条真实的系统通知**，SystemUI 作为通知宿主
天然能读它的 extras。所以载荷挂在那里，不新增任何跨进程基础设施。

生命周期也自动对齐：退出导航 → 影子被 `nm.cancel` → 载荷随通知一起消失，
不需要额外的清理协议（`resetNavPayload()` 只防「同一进程里下一趟复用旧静态值」）。

### 7.2 读写两侧

- 写：`ArkNavLiveInjector.buildShadow()`（发布 / 播报更新 / 自愈三条路径的唯一出口），
  在 `notification.live.capsule` 子 Bundle 之外**平铺** 9 组顶层 key，避免 SystemUI 侧再拆一层。
- 读：`ArkLiveExpandHook.latestNavShadowSbn()` 从 `mNotificationEntries` 里按 tag 找影子，
  `sbn.notification.extras` 取值，原样 `putExtra` 给 `ArkNavPanelActivity`。

⚠️ 这里必须用 Kotlin 属性 `n.extras`。Flyme 上 `XposedHelpers.callMethod(n, "getExtras")`
会抛 `NoSuchMethodError`（v3.15.27 探针取证，见 `ArkLiveShadowHook` 注释）；
消息面板走的也是属性访问，已验证可用。

### 7.3 纯函数自测

`ArkNavParse.kt` 不依赖 Android / Xposed，可以脱离 APK 单独编译运行（用 Gradle 缓存里的
`kotlin-compiler-embeddable` + `kotlin-stdlib`），样例表直接用**生产源码**跑：

| 输入 | 期望 | 输入 | 期望 |
|---|---|---|---|
| `八` / `十` / `十二` | 8 / 10 / 12 | `二十七` / `二十八` | 27 / 28 |
| `一百三十三` | 133 | `一千零五十` | 1050 |
| `三点三` | 3.3 | `两` / `133` / `3.3` | 2 / 133 / 3.3 |
| `中午十二点十九分` | 12:19 | `下午三点` | 15:00 |
| `上午九点五` | 09:05 | `三点半` | 03:30 |
| `凌晨十二点` | 00:00 | `fmtNum(8.0)` | `"8"` |

v3.15.61 首轮自测发现 `九点五` 被解析成 `09:00`（`CLOCK_RE` 强制要求分钟段带「分」），
已把 `分` 改成可选并补上「点半」→ 30 分支，20 项样例全过。

### 7.4 口径

> ⚠ **本节是 v3.15.61 的历史口径，v3.15.65 已推翻前两行**，见 §9.3。
> 保留原文是为了记录「当时为什么这么定」。

| 卡片位置 | 设计稿 | 实际 | 原因 |
|---|---|---|---|
| 三栏 | 剩余时间 / 剩余距离 / 预计到达 | 耗时 / 全程 / 到达 | 实时递减值外部拿不到，只有出发播报的总量 |
| 进度条 | 按剩余里程，示例 58% | 已用时间 / 总耗时 | 同上；解析不到总耗时就只画轨，不画球 |
| 底部按钮 | 结束导航 | 回到导航 | 外部无法结束高德的导航 |
| 状态行目标 | 目的地名 | 抓不到时回落「高德地图」 | 播报很少说出目的地名 |

---

## 8. v3.15.63 真机取证结论：三条通道全部关闭（实证）

这一节是 **v3.15.63 装机实测**的结论汇总。三条旧通道都拿到了证伪证据，写在这里避免下一轮重踩。

### 8.1 通知 extras：无地名，此路永久关闭

`dumpNavExtras()` 在 4 次导航（2 趟）里全量倒出了通知 extras，**每一次的 key 集合完全一致**，
只有 16 个标准字段，其中三个可疑项实测全为 null：

| key | 实测值 |
|---|---|
| `android.subText` | `null` |
| `android.infoText` | `null` |
| `android.remoteInputHistory` | `null` |

没有出现任何 `dest` / `poi` / `destination` 类字段，也没有子 Bundle。**导航通知里不存在目的地名**，
不要再在通知 extras 上找。

### 8.2 导航不是 Service：`dumpsys activity` 显示单 Activity 架构

三条 hook 全部挂上（日志里 `probe svc intent: hooked …` 三条齐全），抓到 14 条 Service 派发，
**全部是推送 / 网络 SDK**：

```
LotusPoolService / ChannelService / AmapAccsService
PushAidlService / MsgDistributeService / TaobaoIntentService
```

`dumpsys activity` 显示高德的 Activity 只有 `SplashActivity` 与 `NewMapActivity`。
**导航既不是独立 Service，也不是独立 Activity，而是在 `NewMapActivity` 里切状态。**
也就是说「启动导航 Service 的 Intent 里带路线参数」这个前提本身不成立 ——
**「导航 Service 启动参数」这个东西不存在**，此路关闭。

> 探针保留（v3.15.64 把主额度 8 降到 4 只是省日志），作为旁证用。

### 8.3 TTS 播报：6 条全文无地名

这一趟的 6 条播报全文：

```
右转，随后一百五十米右转
全程二十一公里
预计下午一点二十七分到达
（其余 3 条同为转向 / 距离 / 时间类）
```

`前往 / 目的地 / 终点` 命中 0 次。高德**不会把目的地念出来**。

### 8.4 由此得出的下一步（v3.15.64 的做法）

目的地是在**导航开始之前**就输入 / 展示过的（搜索框、POI 列表、路线页标题），
那时它是**真实的 Java 文案**，一定经过 `TextView.setText` / `setContentDescription`。

所以 v3.15.64 换打法：**全程撒网**（环形缓冲记最近 30 条「像地名」的短文本 + 时间戳），
**导航开始时收网**（影子通知首次投递时倒成一行 `NAV TEXT BUF`，时间戳为负 = 导航前）。
拿到这条日志就能知道「导航前那几秒屏幕上出现过哪些中文短串」，再决定用哪条 key / 哪个 View 定位。

---

## 9. 目的地找到了：`contentDescription` 里的 `终点，XXX`（v3.15.64 真机实证，v3.15.65 落地）

### 9.1 证据原文

v3.15.64 的文案网在真机（2026-11-06 13:43:27）捞到了这一行：

```
NAV TEXT BUF: n=30 | [5.1s] 湖州市 | [3.5s] 终点，杭州东站 | [3.5s] 松江区 | … | [1.7s] 终点，杭州东站P1地下停车场
```

目的地在 **`View.setContentDescription`** 里，而且**高德自己写好了模板**：`终点，XXX`。
判断依据：`起点，我的位置` / `切换起终点` / `添加途经点` 这一整族都是**可访问性文案**
（屏幕上并没有「终点，」这几个字），而 `TextView.setText` 那一路没有任何带前缀的短串。

### 9.2 同一族文案（必须区分）

真机同一趟里出现过的 `contentDescription`：

| 文案 | 含义 | 是否取 |
|---|---|---|
| `终点，杭州东站` | 终点 POI 名 | ✅ |
| `终点，杭州东站P1地下停车场` | 终点细化值（同一地点的子 POI） | ✅（进池，按前缀规则让位给短的那个） |
| `起点，我的位置` | 起点 | ❌（前缀是「起点」） |
| `切换起终点` | 按钮 | ❌（不以「终点」开头） |
| `添加途经点` | 按钮 | ❌（不以「终点」开头） |

由此得到的规则：`^(?:终点|目的地)[，,:：]\s*(.+)$` + `group(1)` 非空 + 长度 `2..24`。
`^` 锚定 + 非空 group 这两条**天然**排掉了上表后三行，不需要额外的黑名单。

### 9.3 三栏口径的最终结论（推翻 §7.4 前两行）

- **剩余耗时**：高德**不会**给实时剩余，但它会在途中反复播「大约需要 X 分钟」。
  把每一次这样的播报都当成**一次剩余值刷新**（而不是新的全程），
  就能得到一个「锚点式」的剩余：每次播报更新一次，两次播报之间冻结。
  分母（全程）在出发帧钉死，这样进度条不会因为分母被改小而后退。
- **总里程**：仍然只有出发播报里的那个数（「全程八公里」）。高德不给剩余距离，
  也不给「已走 / 未走」，所以这一栏只能是总里程，别指望它变。
- **实时到达**：播报里明确说过「预计X点X分到达」就用它（高德自己的判断）；
  没说就用 `此刻 + 剩余` 每帧重算 —— 剩余在减小、此刻在增加，两者抵消，
  得到的到达时刻自然钉住。这比「出发墙钟 + 全程」算一次然后一直显示要准得多
  （后者在堵车 / 改道后会明显偏早）。

### 9.4 面板实时刷新的物理约束

三个进程之间**没有**共享内存 / 文件 / Provider：
- 高德进程写影子通知的顶层 extras（`NAV_KEY_*`）；
- SystemUI 进程读得到这条通知，于是「重投一次影子 → SystemUI 看得到 → 广播给面板」；
- 面板只在自己的进程里渲染。

所以「实时」这件事的**唯一推手是影子重新投递**：`updateShadowContent()` 若因为
「文案没变」就不重投，整条链就断了（面板会整趟钉在第一次点开的那一刻）。
v3.15.65 的把关条件从「文案变了」升级成「整份载荷指纹变了」，原因就在这里。

---

## 10. 会话生命周期：不能用「时间静默」来建模（v3.15.66 真机复盘）

前面九节解决的都是「**数据从哪来**」。这一节解决的是另一个维度的问题：
**「这批数据属于哪一趟导航」**。v3.15.65 上线后用户复现了一条完整的错误链，
三个症状（三栏全 `--`、终点丢失、退出后实况不销毁）看起来是三个独立 bug，
逐行对码之后发现**根因只有一个**：会话的边界是靠「距上次命中多少秒」猜出来的。

### 10.1 现场时间线（真机，2026-11-06 14:13–14:16，v3.15.65）

```
14:13:09  hit id=99910001 nav=true        → 影子投递（会话 1：驾车开始）
14:13:10  eta fallback = '14:48' (remain=-1ms)
14:13:28  nav ended (farewell, shadow cancelled)
14:13:28  skip (ending window)            ← 5 秒静默窗开始
14:13:35  hit id=99910003（骑行）          ← 被静默窗吞掉，首帧丢弃
14:14:46  liveShadow shadow cancelled for com.autonavi.minimap
14:15:07  hit id=99910001 → shadow posted '高德地图 · 正在导航'
14:15:07  eta fallback = '14:51' (remain=-1ms)   ← 三栏全空
14:16:01  hit nav=true → shadow posted content='旅程到这里就结…'  ← 告别语当正文
14:16:01  liveShadow shadow cancelled
```

三个症状在这条时间线上是**同一个失误的三种表现**：

| 时刻 | 用户看到的 | 实际发生 |
|---|---|---|
| 14:13:35 | 骑行导航没有实况 | 静默窗把新会话的**首帧**吞了。出发播报一辈子只有一次，吞掉就永远没有 `totalMs` |
| 14:15:07 | 三栏全 `--`、终点是空的 | 会话被 `resetNavPayload()` 清空，而高德**重开同一路线不会重播「全程X公里」**，也没有再进路线页（没有新的 `终点，XXX`）→ 分母与目的地同时绝收 |
| 14:16:01 | 胶囊里显示「旅程到这里就结…」 | 这版措辞 `旅程到这里就结束` 一个 `END_PHRASES` 词都没命中（词表里只有 `旅途结束`），于是它走了**普通文案**路径被投成正文 |
| 退出后 | 展开卡还浮在桌面上 | 影子 cancel 有日志，但**没有任何人告诉面板进程「会话结束了」** |

### 10.2 为什么「时间静默」是错误的建模

旧模型只有两个隐式状态：`navActive=true/false` + 一个 5 秒静默窗。
它把「会话结束」表达成「一段时间内没有命中」，于是必然踩三个坑：

1. **静默窗是会话级的，但它挡的是帧级的重复**。真正需要挡的只有「结束语之后那一帧原样重发」
   （v3.15.57 的时间线里这条重发只隔了 **3 毫秒**），而 5 秒窗顺手把 7 秒后另一趟导航的首帧也挡了。
2. **`navActive=false` 之后没有任何状态**。既不知道「是结束了」还是「还没开始」，
   于是下一个命中只能靠 `SESSION_GAP_MS` 去猜是新会话还是旧会话的残留刷新 —— 猜错两个方向都出问题：
   猜成同一趟 → 沿用上一趟残留；猜成新会话 → `reset` 把兜底值一起清掉。
3. **「结束」不是一个事件，只是「没数据了」**。这导致清理动作（撤影子、撤实况、关面板）
   无处挂载，只能散落在各个「顺便判一下」的地方，漏一个就是个 bug。

结论：**会话边界必须来自源通知的真实生死，而不是时间**。

### 10.3 显式状态机（v3.15.66 落地）

三个状态、四条迁移：

| 迁移 | 触发条件 | 语义 |
|---|---|---|
| `IDLE → ACTIVE` | 首帧导航通知命中并投递成功 | 新会话 |
| `ACTIVE → ENDING` | farewell 播报 / 源导航通知消失 / 影子被 SystemUI 撤 | 收摊 |
| `ENDING → ACTIVE` | **任意一次导航通知命中**（过了 1.5 秒重投守卫）**或任意一句出发播报** | 立刻新会话 |
| `ACTIVE → ACTIVE` | 会话内秒级刷新 | 不改会话 |

三条设计要点：

- **`ENDING → ACTIVE` 只看「有没有命中」，不看过了多久。** 这是修 14:13:35 那个坑的关键：
  结束之后 7 秒来的骑行导航，第一帧就是新会话的第一帧，必须被完整处理。
- **`SESSION_GAP_MS`（90 秒）降级为辅助判据**，只在「既没收到 cancel、又长时间没刷新」
  这种两者都缺的窄缝里兜底。它不再是主要依据，因此猜错也不会致命。
- **重投守卫从 5 秒收紧到 1.5 秒**（`ENDING_REPOST_GUARD_MS`）。证据是那次 **3 毫秒**的重发；
  1.5 秒有两个数量级的余量，同时短到不会误伤任何真实的新导航首帧。

`beginSession()` 里三件事的顺序是硬要求，顺序反了就是新的 bug：

```
stashLastTrip()        // 先把上一趟的非空值搬进兜底槽
resetNavPayload()      // 再清本趟状态（注意：它不清兜底槽，也不清 textBuf）
navStartedAt = now     // 最后写起点（先写会被 reset 清掉；后写则可能拿上一趟的起点算剩余）
```

### 10.4 跨趟兜底值：为什么 `resetNavPayload()` 不能清它们

这是修「三栏全 `--`」的核心。高德的行为是：**重开同一路线不会再播「全程X公里」**，
也不再经过路线页（没有新的 `终点，XXX`）。所以新会话很可能一开局就三样全无。
取值链定为 **本趟 → 上一趟兜底 → 文案网 → `--`**：

| 字段 | 兜底槽 | 备注 |
|---|---|---|
| 目的地 | `lastDest` | 上一趟的终点，重开同一路线时通常就是对的 |
| 总里程 | `lastDistValue` / `lastDistUnit` | 同上 |
| 进度分母 | `lastTripTotalMs` | 本趟 `tripTotalMs == 0` 时用它；**这是唯一能让进度条从 0 开始的东西** |
| 剩余耗时 | `remainMs` | 锚点算出来就一定有值；只有四个字段同时缺失才显示 `--` |

`stashLastTrip()` **只搬非空值**，因此可以被 `beginSession` 与 `enterEnding` 反复调用而互相不覆盖
（`enterEnding` 里也调一次，是为了把「结束前刚解析到的数字」也存进去）。

由此得到一条与 §4 同级的硬约束：`resetNavPayload()` **不许**清 `lastTrip* / lastDest`，
也不许清 `textBuf`（§4 已规定）。前者是本节的跨趟兜底，后者是目的地文案网的唯一来源。

### 10.5 不变式：`remainMs` 与 `etaText` 不许自相矛盾

日志里 `eta fallback = '14:48' (remain=-1ms)` 这一行，`-1` 是**打印时机**问题
（当时 `computeEtaFallback()` 在锚点更新之前跑），但它暴露的**真实缺陷**是结构性的：
ETA 曾经可以脱离剩余独立存在（旧代码有一条「出发墙钟 + 全程」的兜底分支）。
一旦这两者能各自为政，载荷里就会出现「有到达时刻、没有剩余」的组合，三栏看起来就像坏了。

v3.15.66 把它收紧成一条不变式：

- `syncTripView()` = `refreshAnchor()` + `computeEtaFallback()`，
  **每一次影子投递之前都必须先调**（`postShadow` / `updateShadowContent` / `selfHealShadow` 三处）；
- `refreshAnchor()` 用真正的锚点递减：`remainMs = anchorRemainMs - (now - anchorAt)`，
  `anchorRemainMs` 只在**播报带来新时长**时改写；
- `computeEtaFallback()` **只剩一级口径**：`此刻 + 剩余`。算不出剩余就**把 ETA 清空**
  （打一行 `eta cleared (no anchor)`）。「出发墙钟 + 全程」的分支连同 `startWallMs` 一并删除。

于是「有 ETA 必有剩余」成为编译期之外的结构性事实，而不是靠调用顺序去维持。

### 10.6 实况销毁：为什么 cancel 通知还不够

`ArkLiveShadowHook` 的 `onNotificationRemoved` 里，导航影子 tag 过去是直接 `return`
（因为它不该走普通影子的收尾逻辑）。问题是「不该走普通逻辑」被实现成了「什么都不做」，
于是 Flyme 的实况容器没有被告知，卡片留在桌面 / 多任务上。

v3.15.66 在这一支里做两件事，**缺一不可**：

1. `releaseNavLiveRow()`：对记录下来的那一行调 `setLiveNotification(false)`。
   只 `cancel` 通知在部分版本上不足以让实况容器立刻消失，live 标记要单独解除。
2. `ArkLiveExpandHook.onNavSessionEnded(pkg)`：向模块进程发
   `ACTION_NAV_PANEL_UPDATE` + `EXTRA_NAV_ENDED=true`，并把胶囊透明度还原成 1
   （防止面板没来得及收起时胶囊永久隐形）。

⚠ 为什么必须由 **SystemUI 进程**发这条广播：面板的接收器是 `RECEIVER_NOT_EXPORTED`，
只有同 uid 或 system uid 发得进来，高德进程发的会被系统直接丢掉。
这也是 §9.4「三进程之间只有通知 extras」这条物理约束的必然结果。

面板侧对应 `onNavSessionEnded()`：先**注销接收器**、再 `dismiss()`，
并用 `sessionEnded` 去重（SystemUI 可能因为多次 cancel 连发几条 ENDED，`dismiss()` 只能执行一次）。
之后 `applyPayload` 一律拒收，防止「已经收起了又被一条迟到的载荷拉回来」。

### 10.7 本节结论

- 会话是**事件驱动**的，不是时间驱动的。任何一个「过了 N 秒就当新会话」的判据都只能是兜底。
- 「结束」必须是一个**可挂载清理动作的事件**，否则撤影子 / 撤实况 / 关面板一定会漏。
- 跨趟兜底值必须存在且不被 reset 清理，因为**高德不会为重开的同一路线重播全程**。
- 剩余的缺失与 ETA 的缺失必须绑定（有 ETA 必有剩余），否则载荷会自相矛盾。
- §9.4 的物理约束没有被绕过：实时刷新仍然只靠影子重投，本节的改动只是让「投什么值」正确。

### 10.8 仍然无解的部分

- **剩余距离**：高德不给，只有出发时的「全程X公里」。§7.4 那条结论依然成立。
- **精确的会话结束时刻**：只能取三者中先到的那个（farewell 播报 / 源通知消失 / 影子被撤）。
  如果用户从多任务里直接划掉高德，三者可能同时到，`enterEnding` 幂等保证了不会重复收摊。
- **跨趟兜底值的时效**：目前不设过期时间。如果上一趟是去杭州、这一趟是去苏州且高德不播全程，
  目的地会短暂显示成杭州 —— 这是有意的取舍（错一个名字好过三栏全 `--`），
  一旦新一趟拿到自己的 `终点，XXX` 会立刻覆盖。

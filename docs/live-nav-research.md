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

# FlymeArk 变更记录

> 纯 Kotlin LSPosed 模块。只 hook 播放器与 SystemUI，不自建胶囊、不发通知、无常驻进程。

## 2026-11-05 · v3.15.36 空壳根因实锤：resetState() 在淡出后抹掉 prefill 内容

用户 v3.15.35 实测：条数已正确（`有 3 条新消息`，与卡片标题一致）、影子不再重复、
× 掉能清除，但**胶囊仍是空壳、点击后才显示文字**。

反编译补上一环：`startDisplayAlphaAnim` 根本不是淡入，而是把胶囊区域**淡出**
（`mDisplayHidAlpha`: `mLiveNotificationArea` alpha 1→0，180ms）。key 不匹配
（新消息替换旧消息）时系统走的就是它；淡出结束的回调里会调
`LiveNotificationView.resetState()`：

```java
mNotificationKey = null;
mLiveNotificationText.setText("");      // prefill 写的内容在这里被抹掉
mLinearIconText.layoutParams.width = 0; // 宽度归零 → 只剩图标宽的空壳
```

之后重新显示时系统不写文字 → 空壳。prefill 设得再早也是白写。
用户「点击后才显示」是因为点击触发了 key 相同分支的
`setLiveNotificationInfo(info)` 正常写入。

修法：prefill 时在 view 消息队列上排两个检查点（320ms / 900ms），满足
「当前 info 是微信/QQ + view 在显示 + 文字为空」才重新
`setLiveNotificationInfo(info, false)` 同步写入，并打视图状态日志
（text/tvW/tvA/linW）便于后续定位。

## 2026-11-05 · v3.15.35 条数补 QQ 标题来源 + 修重复影子 + 修影子清不掉

v3.15.34 装上后从模块日志与 `dumpsys notification` 抓到三个确凿问题：

1. **QQ 条数永远是 0** —— 之前只用正则扫 `EXTRA_TEXT`，但 QQ 的条数在**标题**里：
   `android.title=佩奇VIP通知38群(2条新消息)`，正文只有最新一条、`number=0`。
   → 正则依次扫正文（微信 `[4条]`）与标题（QQ `(2条新消息)`）。
2. **同一条消息 post 两条影子** —— 日志里同一 tid 相隔 5ms 出现两条 `shadow posted`。
   `NotificationManager.notify(int,Notification)` 内部转调
   `notify(String,int,Notification)`，两个重载都被 hook 时命中两次。
   → 加 ThreadLocal 防重入（before 置位 / after 复位），内层调用直接跳过。
3. **影子通知清不掉（胶囊永驻）** —— 日志只有 `clear shadow failed: null`，
   异常 message 为 null 说明是 NPE，无从定位。原实现手工拼 key +
   反射 `IStatusBarService.onNotificationClear`，任一环节炸掉就静默失败。
   → 改为优先调 SystemUI listener 自身（`NotificationListener extends
   NotificationListenerService`）的 `cancelNotification(key)`，key 直接从
   `getActiveNotifications()` 取真实值；失败才回退 IStatusBarService，且异常一律打完整堆栈。

另外 `onNotificationRemoved` 只 hook 3 参版本（2 参内部转调 3 参，双 hook 会清理两次）。

## 2026-11-05 · v3.15.34 条数改「按会话统计」+ 连续消息不再卡住

用户反馈两点：**连续发消息胶囊会卡住**、**条数统计错了**（要的是"当前这个人给我发了多少条"）。

真机 `dumpsys notification --noredact` 取证：

| | 微信 | QQ |
|---|---|---|
| `number=` | **218**（App 总未读） | 0 |
| 标题 | `玄圭互联-chihiro`（会话名） | `佩奇VIP通知38群` |
| 正文 | `[4条]玄圭互联-chihiro: …` | `佩奇⁴: ⏰12点 …` |

`Notification.number` 是**整个 App 的未读数**，跟当前会话无关，之前直接拿它当条数是错的。
而且条数长期不变 → 占位文案不变 → `LiveNotificationView.setText` 判定
「text is the same」直接 return → 胶囊停止刷新，这就是「卡住」。

**一、条数按会话统计**（[ArkMessageLiveInjector.sessionMessageCount]）

按可靠性回退：① 正文前缀 `[N条]`（微信自己写的会话未读数，最准）→
② `android.messages` 数组按最新一条的 sender 统计 → ③ `android.textLines` 按
`名字: 内容` 统计同名条数 → ④ 都拿不到返回 0，只显示「有新消息」。
**宁可不报数，也不报错数**（QQ 的通知本身不带条数，就不显示数字）。

**二、连续消息不再卡住**

`updateStatusBarIcons` 开头三个提前 return（`mAnimateType != 0` / 面板动画中 /
`!mIsGlobalLayout`）会**整次丢弃更新**。第一条消息触发淡入动画，后面几条正好落在动画窗口里
被丢掉 → 胶囊停在第一条。现在判定「这次必被丢弃」时 260ms 后补刷一次
（反射调 private `updateLiveNotification()`），最多连补 3 次，成功即清零。

- versionCode 57 / 3.15.34

## 2026-11-05 · v3.15.33 胶囊改隐私占位 + 渲染走同步路径

真机实测：胶囊出现时是**只有图标宽的一条空壳**，点一下才出文字。用户提出改方案：
默认只显示占位提示，点击后才看具体消息——顺带保护隐私。

**一、胶囊不再承载消息正文**

v3.15.32 的胶囊会把 `EXTRA_TITLE` / `EXTRA_TEXT` 原样塞进 `capsuleContent`，
日志里连「佩奇VIP通知38群(8条新消息)」和整段京东推广链接都进去了，锁屏 / AOD 等于公开聊天内容。

现在 `capsuleContent` 固定为：
- 单条：`有新消息 · 点击查看`
- 多条（`Notification.number > 1`）：`有 N 条新消息 · 点击查看`

`capsuleTitle` 只放应用名（`微信` / `QQ`，[ArkConstants.messageAppLabel]），不放昵称和群名。
正文仍在原生通知卡片里，点卡片或胶囊都能进会话，信息不丢。发送者头像保留。
日志也不再打印正文，只打印占位文案。

**二、prefill 改走 animate=false 同步路径**

`setLiveNotificationInfo(info)` 单参重载 → `setText(text, true)` → `startProgressBarAnim()`：
这条路径**只启动弹簧宽度动画**，文字与 `layoutParams.width` 全靠动画回调写入。
首次展示时 `mLastTextWidth=0`、view 尚未完成布局，动画没跑起来，
结果就是「文字在、宽度是图标宽、看着像空壳」——正好是截图的样子。

改为 `setLiveNotificationInfo(info, false)`：算宽度、写 `layoutParams.width`、
`setText` 全同步完成，不依赖动画。另外把 `mLiveNotificationText` 的 alpha 复位为 1
（`startScaleXAnim()` 会先压 0 再弹回，被打断就永远停在 0）。

- versionCode 56 / 3.15.33

## 2026-11-05 · v3.15.32 修「通知布局 inflate 被模块异常打断」

真机日志（SystemUI 重启后 inflate QQ 通知时）：

```
E/LSPosedFramework: XposedHelpers$ClassNotFoundError:
  com.android.server.notification.NotificationManagerService
  at com.flymeark.ArkLiveAllowHook.install(ArkLiveAllowHook.kt:43)
  at com.flymeark.ArkEntry.handleLoadPackage(ArkEntry.kt:27)
  at android.view.LayoutInflater.createView
  at android.widget.RemoteViews.inflateView
```

**包名 `android` / `system` 不可靠**：SystemUI 异步 inflate RemoteViews 时，
LSPosed 的 `createOrUpdateClassLoaderLocked` 钩子会再触发一次 `handleLoadPackage`，
上报包名 `android`，于是进了 system_server 分支，在 SystemUI 里找
`NotificationManagerService` → ClassNotFound → 异常沿 inflate 逃逸 →
**整条通知布局 inflate 失败**（这正是历史上「消息不显示」的成因之一）。

修复三层：
1. [ArkEntry] 的 `handleLoadPackage` 整体 try/catch，任何异常都不再穿透到 LayoutInflater；
2. system_server 分支增加 `ActivityThread.currentProcessName()` 校验，
   只有进程名真是 `android`/`system`/`system_server` 才安装；
3. [ArkLiveAllowHook] 的 `findClass` 单独 try，找不到就记日志返回。

- versionCode 55 / 3.15.32

## 2026-11-05 · v3.15.31 胶囊「先空白、后补字」修复

用户反馈：**每条消息的胶囊都会延迟，延迟期间内容是空白**。

反编译 `LiveNotificationController.updateStatusBarIcons` 得到确定结论：一条实况**从无到有
（或 key 变化）时，系统只把胶囊 view 淡入，不设内容**：

```java
if (view.getNotificationKey() == null) goto 淡入;                          // ①
if (!view.getNotificationKey().equals(info.getNotificationKey())) goto 淡入; // ②
view.setLiveNotificationInfo(info);   // ③ 只有 key 相同时才写文字
淡入: startDisplayAlphaAnim();          // ④ 只跑 alpha 动画
```

内容要等**下一次** `updateStatusBarIcons`（下一条通知 / 面板变化 / tick）才补上，
中间这段就是用户看到的「空白延迟」。系统音乐实况长期常驻、key 不变，几乎不会走 ①②，
所以只有我们的消息胶囊每次都撞上。

修复（[ArkLiveShowFixHook]）：hook `startDisplayAlphaAnim()`，淡入前若
`mDisplayNotificationInfo` 已就绪（正是这次要显示的那条）且 view 的 key 与之不符，
就直接 `setLiveNotificationInfo(info)`，文字与淡入同帧生效，空白窗口消失。
只对微信/QQ 生效，音乐实况零影响。

- versionCode 54 / 3.15.31

## 2026-11-05 · v3.15.30 作用域补全（system + android 双声明）

- 真机取证（`/data/adb/lspd/config/modules_config.db` 的 `scope` 表）确认模块已勾选的
  作用域为：`android` / `system` / `systemui` / `settings` / 微信 / QQ / QQ音乐 / 网易云。
- 问题：`scope.list` 只声明了 `system`，而本机 LSPosed 把「推荐应用」标签打在
  **Flyme 系统服务（包名 android）** 那条上，「系统框架（system）」没有标签，
  用户不知道该勾哪个。
- 修复：`scope.list` 与 `arrays.xml` 的 `xposed_scope` 同时声明 `system` 和 `android`；
  [ArkEntry] 与 [ArkLiveAllowHook] 的进程判断也同时接受这两个包名，
  保证任何 LSPosed 展示形态下模块都能进系统进程。
- 顺带修 [ArkLiveAllowHook] 的方法查找：原实现只遍历 `NotificationManagerService$N`
  内部类，本机日志为 `no inner class declares setNotificationLiveAllowForPackage`。
  Flyme 把签名声明在 `RankingConfig` 接口里，改为「内部类 → 自身及父类链 →
  实现 RankingConfig 的内部类」三级回退。

## 2026-11-05 · v3.15.27~29 影子通知隐藏定稿（高度钳零 + tag 判定）

三个根因，全部由真机探针定位：

- **v3.15.27 隐藏方式换成「高度钳零」**。v3.15.26 的 `setVisibility(GONE)` 只在首次绑定
  有效，影子通知更新时行被复用、系统重算布局又设回 VISIBLE，玻璃卡复发。
  改为 hook `ExpandableNotificationRow` 的 `getIntrinsicHeight` / `getMinHeight()` /
  `getMinHeight(boolean)` / `getCollapsedHeight` / `getMaxExpandHeight`，影子行返回 0，
  并把 alpha 设 0。这些方法每帧布局都会调用，系统重置 visibility 也绕不过去。
- **v3.15.27 tag 判定取代 extras 判定**。探针打出 `probe-err:
  android.app.Notification#getExtras[]`：**SystemUI 进程里反射调
  `Notification.getExtras()` 直接抛 NoSuchMethodError**（Flyme 改动），
  此前用 extras 里的 `KEY_SHADOW` 判定每条都落到 false。
  改用 `sbn.getTag() == "flymeark_live_msg"`（影子通知固定 tag），一次命中。
- **v3.15.29 展开形态不被压掉**。高度钳零会连点胶囊弹出的实况大卡一起吃掉。
  在钳零前判断 `isExpanded()` / `isHeadsUpState()`：展开/横幅状态不钳零且恢复 alpha=1，
  收起时才压 0。
- **v3.15.29 销毁同步修复**。`onNotificationRemoved` 里同样用 `getExtras()` 判定，异常把
  整段 try 吞掉 → 清除逻辑从未执行 → **胶囊永驻**。改为 tag 判定（tag 不是影子才同步清除）。

真机验证：通知栏无玻璃空卡 ✓、原生卡片保留 ✓、状态栏胶囊（绿底+头像+白字）正常 ✓。

## 2026-11-05 · v3.15.26 影子通知落地修复（Context + 行级隐藏）

- **v3.15.25**：`AndroidAppHelper.currentApplication()` 在微信/QQ notify 时仍为 null
  （Application 未创建 / push 子进程），影子通知被跳过（日志 `no app context`）。
  改为从 `NotificationManager.mContext`（notify 的 thisObject 持有）取 Context，
  任何时机都可用；同时移除 App 侧 cancel hook（App 取消任意通知都会误清胶囊），
  销毁同步统一走 SystemUI 的 onNotificationRemoved。
- **v3.15.26**：`ShadeListBuilder.applyFilters` 在 Flyme 管线中从未被调用（探针 0 命中），
  数据层过滤不可行。uiautomator 确认实况卡就是 `notification_stack_scroller` 里的
  `expandableNotificationRow`（framework 实况模板 `rl_live_base`/`rl_header_live`），
  改在 `ExpandableView.setLiveNotification`（行绑定必经）里对影子行 GONE、
  复用恢复 VISIBLE。真机验证：原生卡片 ✓、玻璃卡消失 ✓、状态栏胶囊恢复 ✓。

## 2026-11-05 · v3.15.24 双通知拆分：通知栏原生卡片 + 独立实况胶囊

**架构改动**：微信/QQ 的消息胶囊不再靠"篡改原通知"实现，改为两条通知分工。

| 通知 | 归属 | 表现 |
|---|---|---|
| 原消息通知 | 微信/QQ 自己 | **完全不注入**，通知栏就是系统原生卡片（标题/正文/时间/×、原生滑动删除、点击进聊天）|
| 实况影子通知 | 模块额外 post（per-App 固定 tag/id）| 走实况链路，只负责状态栏胶囊 + AOD；用固定 id 覆盖更新，不堆积 |

- `ArkMessageLiveInjector` 重写：hook `NotificationManager.notify` 后**不再改原 Notification**，
  而是用 `Notification.Builder(ctx, "flymeark_live_msg_ch")` 构造一条影子通知（IMPORTANCE_LOW、
  静音、不振动、`live.pinned=false` 避免额外横幅），注入魅况配方的 live 字段后单独 notify。
  影子 extras 打 `com.flymeark.live_shadow=true` 标记。
- `ArkLiveShadowHook`（新增，SystemUI）：
  1. hook `ShadeListBuilder.applyFilters()` → 影子条目返回 true，**只从通知栏显示列表剔除**，
     `NotifCollection` 仍持有 → 胶囊正常。刻意不动 `sbn.isLive()`：会切断
     `addTrackingLiveExpandableView` 的触摸追踪导致胶囊点不动（v3.15.22 已踩坑）。
  2. hook `NotificationListener.onNotificationRemoved` → 微信/QQ 普通通知被移除（×/滑动/App 消费）
     时，通过 `IStatusBarService.onNotificationClear` 同步清掉影子，胶囊随之消失。
- 清掉旧版本遗留问题：原通知不再带 live 字段，所以「空白玻璃卡 + 空心圆 + 无法一键清除」全部消失。

## 2026-11-05 · v3.15.23 撤销 sbn.isLive 拦截（修复胶囊点击失效）

- **撤销 v3.15.22 的 `StatusBarNotification.isLive()` 调用栈分流 hook**。该方案虽让通知栏渲染链读到 false，
  但同时切断了 `NotificationStackScrollLayout.addTrackingLiveExpandableView` 的实况视图触摸追踪，
  导致**状态栏胶囊无法点击**。已验证：撤掉后点击恢复。
- **新的逆向结论**：SystemUI 渲染实况卡片时，从通知自身布局里找三个 Flyme 约定插槽
  `rl_header_live`(0x010206da)、`iv_extends1_live`(0x0102066e)、`iv_extends2_live`(0x0102066f)。
  魅况能显示内容是因为它自带含这些 id 的 RemoteViews；微信/QQ 用的是系统模板，没有这些插槽，
  所以实况卡片必然渲染成「空白玻璃底 + 空心圆」。
- 也就是说：**同一条通知上，「原生通知卡片」与「实况卡片」互斥**，无法靠拦 `isLive()` 两全。
  如需原生卡片样式，必须拆成两条通知（原通知不注入 + 另一条实况通知供胶囊使用），
  并在 ShadeListBuilder 层把实况通知从通知栏列表里过滤掉。

## 2026-11-05 · v3.15.21 通知栏恢复 × 一键清除（行级标志，已被 v3.15.22/23 取代）

### 问题

用户反馈：注入 live 字段后，通知栏里的微信/QQ 通知变成 Flyme 实况小卡（压缩高度、
玻璃底、右上角只有空心圆圈），**原来的 × 一键清除没有了**，滑动也删不掉。

### 根因：两个 live 标志被混为一谈

- **状态栏胶囊**由 `StatusBarNotification.isLive()`（extras 的 `notification.live.type`）驱动；
- **通知栏那一行的样式**由视图字段 `ExpandableView.mIsLiveNotification` 驱动
  （`onFinishInflate` 时 `setLiveNotification(sbn.isLive())` 写入）。

行一旦被标记 live：`getMinHeight()` 走 live public view、
`NotificationStackScrollLayout.addTrackingLiveExpandableView` 悬浮跟踪、
长按 guts 走 `NotificationInfo.mzBindGuts` 的 live 分支（只有"实况通知设置"）——
整条通知变成不可清除的实况卡。

### 修复：`ArkLiveRowNormalizeHook`（新增）

hook `ExpandableView.setLiveNotification(boolean)`：当行属于微信/QQ 时强制写 false。
通知栏里恢复成普通微信/QQ 通知卡片（头像 + 正文 + × 按钮 + 原生滑动删除）；
状态栏胶囊不受影响（它只认 SBN 的 extras，不认这个视图字段）。
其余 live 通知（音乐播放通知）保持原样。

### 验证

- 编译通过，装机，SystemUI 已重启（未整机重启），hook 安装日志确认。
- 状态栏胶囊仍正常显示（截图确认 QQ 亮蓝胶囊在）。
- 待用户拉通知栏确认：QQ/微信卡片是否恢复普通样式、× 是否可点。

---

## 2026-11-05 · v3.15.20 副显示位修复 + 消息胶囊滑动销毁

### 一、副显示位胶囊「消失」的真正根因（音乐胶囊共存问题）

上一版遗留的「存在消息胶囊时音乐胶囊不显示」，与「多条消息第二个胶囊空白」
是**同一个根因**，且比之前判断的更严重 —— 不是文字空白，是整条胶囊塌缩：

`LiveNotificationView` 的渲染逻辑：

```java
if (showType == 1 || showType == 6)  setText(info.getTopDisplayText());  // 副位
else                                  setText(info.getShowText());       // 主位
```

`topDisplayText` 是全局共享值，只有 `mNotificationEntries.isEmpty()`（无任何通知）
时才填歌词行，否则恒为空串。而 `getShowWidth()` 在 `showType ∉ {0,4,5}` 时
**用 mTopDisplayText 算宽度** → 空串宽度 0 → 副位胶囊整体塌缩不可见。

Flyme 的胶囊只有一个主显示位：第 1 条 live 通知占 `mDisplayNotificationInfo`，
第 2 条进 `mShadeNotificationInfo`（showType=1），音乐胶囊由
`getTopLyricsDisplayText` 在末尾补进 —— 消息占主位时音乐只能进副位，
于是被「宽度 0」吞掉。

### 二、修复：`ArkMessageLiveTextHook` 重写为 `ArkLiveShadeTextHook`

只 hook 一个 getter：`LiveNotificationInfo.getTopDisplayText()` ——
返回 null/空串时回退该 info 自己的 `getShowText()`：

- 副位消息胶囊 → 显示自己的消息正文
- 副位音乐胶囊 → 显示自己的歌词行
- 无通知时行为不变（歌词仍是歌词）

不再强改 showType 1→0（旧方案会破坏 `makeShadeVisible`/进度条/副位布局语义）。
日志带防重（同一 pkg+内容只打一次）。装机日志已确认生效：
`liveShadeText: topDisplayText empty → fallback showText for com.tencent.mobileqq: '头号调皮鬼: ...'`。

### 三、消息胶囊滑动销毁（`ArkLiveSwipeDismissHook`，新增）

真机反编译确认 Flyme 原生胶囊**没有滑动手势**：
`LiveNotificationClickHelper.handleTouchEvent` 只判断落点用于拦截下拉，
`PhoneStatusBarViewController` 在 UP 时固定走 `onLiveNotificationClick(null)`。

hook `LiveNotificationController.handleOnTouchEvent` 实现手势（仅微信/QQ）：

- DOWN：落点在胶囊矩形内记录起点 + 当前通知 key（用与原生相同的 `hitTest`）
- MOVE：水平位移 > 24dp 且横向占优 → 跟手位移 + 淡出反馈，吞事件（不触发下拉）
- UP：位移 > 80dp → 朝该方向滑出并清除对应通知；否则弹回

清通知走 SystemUI 删通知的同一条路：反射 `IStatusBarService.onNotificationClear`
（`NotifCollection.lambda$dismissNotifications$1` 同款调用），SystemUI 无权限直接
cancel 别人通知。音乐胶囊不启用滑动（避免误清播放通知）。

### 四、验证

- 编译通过，装机，kill 重启 SystemUI（未整机重启）。
- 待真机验证：①消息 + 音乐胶囊共存显示 ②横向滑动销毁消息胶囊。

---

## 2026-11-05 · v3.15.19 消息胶囊配色改为固定品牌色

### 一、底色变黑 + 头像消失（v3.15.18 魅况配方引入的两个回归）

真机实测 v3.15.18 的「魅况配方」版：胶囊底色变成纯黑，头像也不见了。

- **黑底**：上一版用「头像 8×8 采样平均色 × 0.7」算底色，而头像位图在多数通知上取不到
  （微信消息的 `android.largeIcon` 是 `Icon`，反射 `getBitmap` 未必拿得到），于是落到
  `0xFF333333` 兜底 → 看起来就是一块黑。
- **头像消失**：上一版把头像缩到 28dp 后用 `Icon.createWithBitmap` 重建胶囊图标，
  该 Icon 跨进程 parcel 到 SystemUI 时读回 null。**重写前已有注记「不要重建 Icon」，
  上一版为了照抄魅况把这个坑又踩了一次。**

### 二、修复

1. 底色改用**固定品牌色**（用户指定标准），不再依赖位图采样：

   | App | 色值 | RGB |
   |---|---|---|
   | 微信 | `#07C160` | (7,193,96) |
   | QQ | `#00CAFC` | (0,202,252) |
   | QQ 音乐 | `#31C27C` | (49,194,124) |
   | 网易云音乐 | `#C20C0C` | (194,12,12) |

   落在 `ArkConstants.brandColorOf(pkg)`，未知 App 兜底 `#333333`。

2. 字色按底色 WCAG 相对亮度自适应（`ArkConstants.readableTextOn`）：
   亮度 > 0.42 用深色字 `#0A2A33`（QQ 亮蓝底白字对比度仅 1.9:1，必须换深字），
   否则白字（微信绿 2.4:1 / 网易云红 6.3:1，与各家 App 自己的品牌用法一致）。

3. `capsuleIcon` 恢复为**原样 putParcelable 通知里现成的 `Icon`**，不缩放、不重建。

### 三、验证

- 编译通过；已安装并 kill 重启 SystemUI(29503) 与微信(29785) 进程，未整机重启。
- 日志 `liveMsg fields: pkg=... bg=0x... icon=yes ...` 打印实际注入的颜色与是否有头像。

---

## 2026-11-04 晚 · v3.15.18 补丁（进行中，待真机验证）

### 一、消息胶囊头像修复（已验证生效）

真机验证发现左侧图标显示的是微信/QQ 的 APP 图标而非发送者头像。根因：微信把发送者
头像放在 `extras["android.largeIcon"]`（`Icon` 对象，约 140~144px），**不在**
`n.getLargeIcon()` 返回的 Bitmap 上。修复 `ArkMessageLiveInjector.messageIcon`：
优先读 `extras["android.largeIcon"]`（现成 `Icon`，直接 putParcelable 进 capsule
子 Bundle 的 `notification.live.capsuleIcon`），不再从 Bitmap 重新构造。

用户已确认：**头像正确显示**。

### 二、消息文字显示修复（已编译，待验证）

真机 dump 确认 `LiveNotificationInfo.showText` 有完整内容（如 `玄圭互联-chihiro: 111`），
但视觉上不显示。根因（反编译 `LiveNotificationView.setLiveNotificationInfo`）：

- showType=0/2/3/4/5 → 用 `showText` 渲染（各自内容，正确）
- showType=1/6 → 用 `topDisplayText` 渲染，而该值是 `updateStatusBarIcons` 里的
  **全局共享变量**（第 1420 行 `getTopLyricsDisplayText("")` 的返回值），
  消息通知无歌词属性 → 为空 → 副显示位胶囊文字空白

修复：新增 `ArkMessageLiveTextHook`（SystemUI 侧），hook
`LiveNotificationInfo.setShowType`：当 `packageName` 是微信/QQ 且传入 showType=1
（副显示位）时改为 0，让消息胶囊始终走 showText 分支显示各自内容。

> 中间废弃方案：曾 hook `getTopLyricsDisplayText` 回填第一条消息的正文，但 r4 全局
> 共享会导致所有副显示位胶囊显示同一条消息的内容（QQ 胶囊显示微信消息），已重写。

### 三、遗留问题（明天继续）

1. **消息与音乐胶囊共存**：`updateStatusBarIcons` 第 1416 行 `mNotificationEntries`
   非空时跳过 `getTopLyricsDisplayText`，但**结尾第 1506 行还有一次无条件调用**，
   音乐胶囊理论上应能共存（进副显示位），需真机验证共存表现再定是否需要 hook。
2. **滑动销毁**：消息胶囊目前只能点进消息才销毁，应支持滑动销毁，未开始。
3. 该补丁 APK 编译成功但**安装/重启被打断，尚未装机验证**。

---

## 2026-11-04 · v3.15.18｜新增微信/QQ 消息实况通知（参考「魅况」，本版收尾）

### 一、微信/QQ 消息实况胶囊

参考第三方「魅况」的做法，给微信（`com.tencent.mm`）与 QQ（`com.tencent.mobileqq`）
加实况通知支持。它们不是媒体 App（无歌词、无 MediaSession），走的是独立的**消息实况胶囊**
通道，与 QQ音乐/网易云 的歌词胶囊互不冲突。

逆向确认（SystemUI `LiveNotificationController.shouldShowLiveNotification`）：
一条通知要变成实况胶囊需同时满足 `sbn.isLive()`（`extras["notification.live.type"]` 非 0）
与 `sbn.getCapsuleStatus()==1`（`extras["notification.live.capsule"].capsuleStatus`）。

新增 `ArkMessageLiveInjector`：在微信/QQ 进程 hook `NotificationManager.notify`，
对「有标题+正文」的消息通知注入上述 live 字段（`capsuleTitle`=发送者、`capsuleContent`=正文），
跳过常驻前台通知，并带已注入标记防重复。

### 二、推荐应用与设置登记同步

- `xposed_scope` / `scope.list` 加入微信、QQ（`com.tencent.mm`、`com.tencent.mobileqq`）。
- `ArkLiveSettingsHook.TARGETS` 登记微信、QQ，让它们在「设置 → 实况通知」开关列表出现，
  描述按类型区分（音乐播放实况 / 消息实况）。
- manifest `<queries>` 补微信、QQ 包可见性。

### 三、本版收尾

放弃 system_server 侧 `setNotificationLiveAllowForPackage` hook（SELinux Enforcing 下
无法可靠写入 LSPosed 配置库，且该方法在 `NotificationManagerService$12` 内部类、随 ROM 变化），
关闭开关的实时生效改由现有 SystemUI 侧门控 + 延迟复核承担。至此微信/QQ 消息实况、QQ音乐/网易云
歌词实况均已覆盖，本版收尾。

---

## 2026-11-04 · v3.15.17｜修正系统框架作用域 key + 开关 hook 目标类

### 一、系统框架作用域 key 是 `system` 不是 `android`

上一版在 `xposedscope` 数组里加的 `<item>android</item>` 是错的。LSPosed v2.x 里
system_server（系统框架）的 scope key 是 **`system`**（真机 `scope` 表里其他模块
如 `flymemod`/`flyme10` 都用 `system` 条目，无任何模块用 `android`）。

已改为 `<item>system</item>`，并直接改写设备 `modules_config.db` 给本模块插入了
`system` 作用域条目。

### 二、开关 hook 目标类错误 → NoSuchMethodError

真机日志（18:58，pid 3850）：`hook setNotificationLiveAllowForPackage` 报
`NoSuchMethodError: NotificationManagerService#setNotificationLiveAllowForPackage`。

反编译 services.jar 确认：该方法签名 `(String, int, boolean)` 是对的，但**实现不在
`NotificationManagerService` 本身，而在它的内部类 `NotificationManagerService$12`**
（父类 `INotificationManager$Stub`，即 Binder 服务端实现）。

修复：遍历 `NotificationManagerService` 的所有内部类，找到声明该签名的那个再 hook，
避免 `$12` 编号随 ROM 版本变化失效。

---

## 2026-11-04 · v3.15.16｜收藏「有时点不上」修复 + 胶囊重绘缓解

### 一、收藏「有时成功有时失败」——拿不到按钮就放弃

真机日志（19:01，pid 8313 = 3.15.15）铁证：QQ音乐连续点「取消收藏」10+ 次，
**每次都 `favorite skipped (no like action)`**：

```
favorite request target=false gen=2 busy=false
favorite skipped (no like action)     ← 每次都是这里
```

根因：`favoriteActionFor` 先查 `activeNotifications`（本机 SystemUI 拿不到，size=0），
回落到 hook 缓存的 `favIntent`；但切歌/通知重建瞬间 `favIntent` 被清空、或缓存的按钮
标题还是上一首，标题匹配失败 → 返回 null → 旧代码直接 `skipped` 放弃，用户感觉失灵。

修复：`sendFavAction` 拿不到按钮时**不再立即放弃**，改为 250ms 步进、最多等 2.5s
重取（歌词每一行刷新都会重填 favIntent，几乎总能等到），等不到才认命交真值兜底。

### 二、切歌/收藏导致胶囊重绘——raw 抖动误释放

日志：19:01:07 `raw=false` → 19:01:10 `switch OFF 胶囊释放` → 19:01:22 `raw=true 胶囊恢复`，
这段 15 秒的 false 既非收藏也非切歌，是 Flyme `getNotificationLiveAllowForPackage` 在
播放通知重建时的长时间抖动。

缓解：`ArkLiveLyricHook` 门控在「刚点收藏（5s）/ 刚换曲（8s）」的沉降窗口内，
把 raw=false 当抖动忽略、不触发延迟复核，避免「收藏/切歌导致胶囊重绘」。
播放中周期性通知重建的抖动，仍需 system_server 广播方案（v3.15.15 已实现）彻底解决，
依赖勾选「系统框架」作用域。

## 2026-11-04 · v3.15.15｜收藏 toggle 校验延窗 + 关闭开关实时生效

### 一、网易云收藏「点了没反应」——toggle 校验窗口太短

真机日志（18:37，pid 12895 = 3.15.14）铁证：

```
favorite action sent (target=true)              ← like 按钮 toggle 已发出
fav not applied yet (actual=false), recheck      ← 900ms 后 COLLECT_STATE 还是旧值
fav toggle not applied yet, last wait            ← 再等 700ms
fav toggle gave up, accept actual=false          ← 放弃，用户看到「无效」
```

网易云 `like` 是纯 toggle（`btnState=null` → `lastSentSemanticKnown=false`），
**绝不能重发**（重发 = 撤销刚做的操作），只能被动等它写回 COLLECT_STATE。
但网易云 COLLECT_STATE 写回要 1~2s（有时更久），原校验窗口 900+700+1200=2.8s 太短，
toggle 永远等不到落地就 gave up。

修复：
1. toggle 路径校验窗口从 2.8s 延到 5.2s（round 0→1→2→3→4，间隔 900/700/1200/1200/1200ms），
   最后一轮仍不落地才以真值收尾。
2. `inspectFavoriteActions` 里，当 `titleState == null`（网易云 like）但指纹表已学到
   icon→状态 映射时，把 learned 回填到 `favCachedState`。这样网易云一旦学到
   icon 4047↔8763 的语义，后续点击就走「状态绑定」路径（可安全重发、可等通知重建），
   不再每次都退回「toggle 只发一次」。

### 二、关闭开关「不实时」——raw 抖动无法区分用户动作

真机日志（18:37:59，pid 12895）：

```
18:37:59  raw=false → 用户关 QQ音乐
18:38:02  switch OFF → capsule released    ← 3s 延迟复核才释放（用户嫌慢）
```

之前为了防「通知重建」引起的 raw 抖动（1.6~4.2s），给关闭加了 3s 延迟复核，
结果用户真关开关也要等 3 秒才消失——不实时。

根因：`getNotificationLiveAllowForPackage` 的 raw 值在播放通知重建时会瞬时抖动，
单靠它无法区分「用户真关」和「通知重建抖动」。

修复（彻底解耦）：
- system_server 进程（`ArkLiveAllowHook`）hook `NotificationManagerService#setNotificationLiveAllowForPackage`，
  用户点开关必走这里，是**唯一无抖动的「用户动作」信号**；媒体 App 开关变化时发
  `ACTION_LIVE_ALLOW_CHANGED` 广播给 SystemUI。
- SystemUI（`ArkLiveLyricHook.onUserToggleLive`）收到广播：
  · 关闭 → 立即释放胶囊 + 记入 `userOffPkgs`（实时）；
  · 开启 → 移出 `userOffPkgs`，胶囊恢复。
- 门控 `getTopLyricsDisplayText` 里，`userOffPkgs` 中的 App 强制不放行——即使 raw
  随后短暂跳回 true（通知重建抖动）也不会让胶囊「自己回来」。
- get 侧的 3s 延迟复核继续保留，专防「通知重建」抖动（那条路径用户没动开关）。

- versionCode 36 / 3.15.15

## 2026-11-04 · v3.1｜展开时胶囊淡出 + 面板实时跟随换曲

**起因**：v3.0 遗留两条体验问题——点开面板时状态栏里原来的胶囊还挂着；切歌后面板不刷新、
靠 450ms 后自动收起掩盖问题。

### 一、展开时胶囊淡出（视觉上是胶囊「展开」成面板）

- `ArkLiveExpandHook` 新增 `setPanelVisible(visible)`：
  面板广播 `ACTION_PANEL_STATE` → `SystemUILiveHost`（SystemUI 进程）→ 取
  `LiveNotificationController` 的 `mLiveNotificationDisplay`（回退 `mLiveNotificationArea`）
  做 `alpha = 0`；收起时 `alpha = 1`（160ms 淡入）。
- 控制器与容器视图都用 `WeakReference` 持有，避免 hook 侧长期强引用 SystemUI 视图。
- 歌词每行刷新会重走 `setLiveNotificationInfo`，该 hook 里再压一次 `alpha = 0`
  （`onLiveViewUpdated()`），防止原生重设后胶囊「闪回来」。
- 只动容器 alpha，不动布局、不动可见性 —— 不会引起状态栏重排。

### 二、面板实时跟随换曲（不再收起）

- 新增 `ACTION_PANEL_TRACK`（SystemUI → 面板）与 `ACTION_PANEL_SYNC`（面板 → SystemUI）。
- 面板开着时，`ArkLiveCoverHook` 每次歌词刷新都会调 `pushToPanel()`：
  **换曲立刻推（带封面）**，同曲最多每秒推一次对齐进度（拖动进度条后也能纠正）。
- 按上一首/下一首后，面板主动发 `ACTION_PANEL_SYNC`（250ms / 900ms 两次），
  SystemUI 直接从 `MediaSession` 读 `METADATA_KEY_TITLE / ARTIST / ALBUM_ART` +
  `PlaybackState.position` 立刻推回 —— **实测换曲信息 250~260ms 到达**，
  不再依赖歌词通知刷新（那条链路要 1~3 秒）。
- `readMediaSession()` 从 `Triple` 升级为 `SessionSnapshot`（标题/歌手/进度/时长/播放态/专辑图），
  专辑图在 SystemUI 侧裁方形 + 16% 圆角 + 缩到 240px 再进广播（Binder 1MB 上限）。
- 面板侧 `applyTrack()`：标题变了才换封面/歌手并取消看门狗；进度/播放态每次都跟。
- 换曲看门狗从 3s 放宽到 6s（原值会在慢速机型上把面板误收起，实测 11:09:11 点下一首、
  14.553 才收到新曲，正好被 3s 看门狗抢先收起）。

### 真机验证（MEIZU 21 Pro / `481QHGFN226P5`，4→3.1.0）

```
liveExpand: panel launched, title='我想更懂你', pos=8837/266040, playing=true
liveExpand: capsule[mLiveNotificationDisplay] alpha=0.0
liveExpand: panel visible=true
liveExpand: sync '我想更懂你' pos=9239/266040 playing=true      ← 打开时自动对齐
SystemUILiveHost media key 87 dispatched
liveExpand: sync '老鼠爱大米' pos=24444/266040 playing=true     ← 258ms 后新曲已到
liveExpand: push track '老鼠爱大米' pos=537/301946              ← 通知侧再校正一次
SystemUILiveHost media key 88 dispatched                       ← 上一首同样生效
liveExpand: capsule[mLiveNotificationDisplay] alpha=1.0        ← 收起后胶囊恢复
```

- 截图确认：面板展开时状态栏左上角无胶囊；切「下一首」后面板原地刷新为
  《老鼠爱大米 / 香香 - 猪之歌》，封面与进度同步；返回键收起后胶囊带封面回归。
- 播放/暂停按钮：`media key 85` → 播放态 `PAUSED`，按钮切为三角图标。

### 已知限制（保留）

- 面板展开期间状态栏胶囊区域留空（不重排布局），收起后淡入恢复。
- 若媒体键派发失败（例如播放器无响应），6 秒看门狗会收起面板，避免停在旧曲目上。

## 2026-11-04 · v3.2｜系统开关真正生效 + 胶囊长大成卡片

**起因**：设置里「实况通知」开关点了没用 —— 无论开还是关，歌词胶囊照样出现。
「现在可以调整默认动画了」之后，展开动画也要重做成「胶囊直接长大」。

### 一、开关为什么无效（链路已查到底）

真实链路：

```
设置页开关 → NotificationBackend.setNotificationLiveAllowForPackage(uid, pkg, checked)
           → INotificationManager.setNotificationLiveAllowForPackage   (TRANSACTION=175，读 176)
           → PreferencesHelper.PackagePreferences.allowLive            (默认 true)
```

`allowLive` 全系统只在一个地方消费 —— `NotificationManagerService` 入队时：

```java
if (!preferencesHelper.getNotificationLiveAllowForPackage(pkg, uid))
    extras.putInt("notification.live.type", 0);   // 日志：Reset live notification---->NON
```

而 `StatusBarNotification.isLive()` 只读 `extras["notification.live.type"] != 0`。
**QQ音乐 的通知里根本没有这个字段**：胶囊走的是 ticker 旁路
（`getLyricsNotifications()` → `LiveNotificationController`），**从不经过 `isLive()`**，
所以 `allowLive` 关掉也拦不住。

另外歌词胶囊真正的门控是四个条件：

```java
mIsMediaShow && mSwitcherViewShow && mLyricsInofo != null && !mIsAddLiveLyricsNotification
```

其中 `mIsMediaShow` 在整个 SystemUI 里**只有读取、没有任何写入**（死字段），
v3.0 起由模块无条件置 true —— 这等于把开关彻底焊死了。

### 二、补上开关连线

- 新增 `LiveAllowGate`：SystemUI 侧用 `XposedHelpers.callMethod(svc, "getNotificationLiveAllowForPackage", pkg, uid)`
  读开关，`TTL_MS = 400` 缓存；`sNotificationManager` 取服务的三级降级
  （字段 `sService` → `mService` → 静态 `getService()`）；读不到时一律返回 `true`
  —— 宁可保持原行为，不因为异常把胶囊误杀。
- `ArkLiveLyricHook` 的门控由「首次放行后不再判断」改成**每次调用都查**：
  开关关闭时把 `mIsMediaShow` 重新置 false 让胶囊释放；打开时再置 true。
  （只在首次判断的话，关掉开关后 `mIsMediaShow` 仍是 true，胶囊不会消失。）

真机验证日志（设置里手动关 → 开）：

```
11:23:28 [FlymeArk/liveAllow] allowLive(com.tencent.qqmusic) = false
11:23:28 liveLyric: switch OFF -> capsule released
11:24:35 [FlymeArk/liveAllow] allowLive(com.tencent.qqmusic) = true
11:24:35 liveLyric: mIsMediaShow forced true
```

> 排查中一度以为开关值在 true/false 之间跳变，实为用户在同一时间段内手动点开关所致；
> 日志恰好证明 175/176 这条 Binder 链路是通的。

### 三、展开动画：胶囊连续长大成卡片

原来的做法是「胶囊闪没 + 卡片从状态栏下滑」，两段不连续。新增 `ArkMorphAnim`：

- 打开面板前先量内容，把 `panel_content` 宽高**固定**住，动画期间不让它重排；
  再用 `root.getLocationOnScreen` 校准坐标系（root 本身在 0 处，早期误判过「偏下 17dp」，
  实际是 `status_bars` 高 130px、`146 = 130 + 4dp×3.5`，几何本来就对）。
- `applyProgress(t)` 对 位置 / 宽高 / 圆角 做同曲线插值；`alpha = ((t - 0.40f) / 0.55f)`
  —— 前 40% 只长壳、文字后浮现，对应原生「宽度先弹、文字后淡入」的节奏。
- 展开 `OvershootInterpolator(0.85f)` 近似原生 `SpringForce(stiffness=180, dampingRatio=0.76)`；
  收起 `COLLAPSE_MS = 230` 反向缩回胶囊。
- 背板改成代码构造的 `GradientDrawable`（XML shape 改不了动态圆角），
  颜色 `0xFF18181C` —— 原来 `0xF018181C` 是 94% 不透明，会透出桌面时钟（实测差值
  `(37,36,34)` vs 桌面 `(231,219,141)`，恰好等于 94% 覆色）。
- 胶囊侧从瞬设 alpha 改为 `CAPSULE_FADE_MS = 110` 淡出 / `CAPSULE_RESTORE_MS = 170` 淡回，
  与卡片生长重叠；`onLiveViewUpdated()` 加 `v.animation == null` 保护，不打断正在跑的动画。
- 布局拆两层：`panel_card`（只负责背景与动画尺寸）+ `panel_content`（内容层，宽高由代码固定），
  外层加 `clipChildren/clipToPadding=true`。

## 2026-11-04 · v3.3｜胶囊缩窄 + 歌词横向滚动

**起因**：胶囊太宽，把左边的时钟位置全吃掉了；歌词换行是「原地淡入」，没有方向感。
要求「参考原来的状态栏歌词的逻辑」。

### 一、胶囊为什么是固定 534px

`LiveNotificationView.getCalculatedWidth(text, showType)` 对 **showType 5/6（歌词胶囊）**：

```java
maxTextW = mStartSideStatusBarWidth - mFixedWidth - mStatusBarPaddingStart - mDispalyCutoutWidth;
width    = maxTextW + mFixedWidth;      // ≈ 整块左侧状态栏，与文字长度无关
```

`mStartSideStatusBarWidth = getShowStartWidth() = screenWidth / 2`，所以实测恒为 534px
（density 3.5 → 166dp），跟唱到第几个字没关系。而普通实况通知（美团那种）走
`getTextRect(text).width()`，是内容宽度 —— 这就是两者观感不同的根因。

### 二、收窄到 130dp

`ArkLiveCapsuleStyleHook` hook `getCalculatedWidth(String, int)` 的 `after`：
`showType` 是 5/6 且结果超过 `MAX_WIDTH_DP = 130` 时把结果钳掉，
同时把 `mLiveNotificationText.maxWidth` 改成 `limit - mFixedWidth`
（不低于 `MIN_TEXT_DP = 48`，防止极端窄屏算出负数）。

实测 `534 → 455`（166dp → 130dp），右侧让出约 36dp。

### 三、切换动效照系统状态栏歌词来做

原生状态栏歌词（`com.flyme.systemui.statusbar.ticker`）的分工是两段：

```
AdvertTickerView.mTextSwitcher.setText(tickerText)   ← TextSwitcher：新旧文字左右交替
  └─ (MarqueeTextView) child.startScrollSoon()       ← 超宽后自己横向滚
```

`MarqueeTextView` 的三个关键点：`isFocused()` 恒 true、`setText()` 里 `setEllipsize(null)`、
`onAttachedToWindow` 起 `HandlerThread` 推 `scrollX`（`startScrollSoon()` 延迟 1500ms）。

胶囊这边系统只给了 `startScaleXAnim()`：`setAlpha(0)` 直接换字 + alpha 弹簧淡入，
原地换字、没有方向。改法：

1. **拦掉原生** `startScaleXAnim`（`param.result = null`），避免和我们抢字。
2. **切换**：`setLiveNotificationInfo` 之后，若 `mShowText` 变了就走单 View 版 TextSwitcher
   —— 旧词左移淡出（`OUT_MS = 90`，`AccelerateInterpolator(1.6f)`）→ 换字 →
   新词从右侧移入（`IN_MS = 150`，`DecelerateInterpolator(1.8f)`）→ 落位后开滚。
   位移 `SLIDE_RATIO = 0.38f` × 文字区宽度；`tv.width` 未 layout 为 0 时用
   `paint.measureText` 兜底（否则位移为 0，看不出滑动）。
3. **滚动**：新增 `ArkLyricMarquee` 自驱 `scrollX`。
   **内置 MARQUEE 起不来**的原因：系统每次刷新都 `setEllipsize(null)` + `setSelected(false)`，
   且宽度是弹簧动画在改，`isSelected` 设回去时 layout 还没就绪。
   自驱参数照 `MarqueeTextView` 的节奏：`FRAME_MS = 16`、`STEP_DP = 1.2`、
   `START_DELAY_MS = 800`、末尾 `TAIL_GAP_DP = 24`、到末尾归零后 `RESTART_DELAY_MS = 1400` 再滚。
   必须在 `prepareForScroll` 里调 `setHorizontallyScrolling(true)`
   —— 否则 `scrollTo` 会被 `computeHorizontalScrollRange` 夹住，滚不动。
4. **边缘渐隐**：同样取自 `MarqueeTextView.setText()` 的 `setHorizontalFadingEdgeEnabled(true)`，
   再把 `FADE_DP = 12` 塞进去。注意 `View.setFadingEdgeLength` 是 **protected**，
   直接写 `tv.fadingEdgeLength = ...` 编译不过，只能 `XposedHelpers.callMethod` 反射调。

真机验证日志（MEIZU 21 Pro / `481QHGFN226P5`，versionCode 4 / 3.3.0）：

```
[FlymeArk] liveStyle installing (max 130dp)
liveStyle: width 534 -> 455, text='你会轻轻地问我'
liveStyle: width 534 -> 455, text='我为什么要歌唱 - 邵夷贝'
liveStyle: slide '我为什么要歌唱 - 邵夷贝' span=426 shift=161
[FlymeArk/liveAllow] allowLive(com.tencent.qqmusic) = true
liveLyric: mIsMediaShow forced true
```

- 截图实测：胶囊 `x=75..518`（宽 455px），右边缘到右侧信号区之间空出约 340px。
- 连续帧确认：同一句歌词在相邻帧之间文字整体左移，滚到末尾归零后重新开始。

### 关于左上角时钟

胶囊占的是**时钟的槽位**，`CollapsedStatusBarFragment` 在歌词胶囊显示时会走
`hideClock()`（`mClockView` 的 `leftMargin` 先置 `-width`，胶囊结束后再
`animatTranslationXAndShow` 滑回来），所以胶囊期间左上角没有时间显示是系统设计行为，
不是被我们的胶囊盖住。缩窄之后多出来的空间目前是空的。

> **试过把时钟挪到胶囊右侧，已放弃（未合入）。**
> 实测 `mClockView.visibility == GONE(8)`，所以必须 hook `hideClock(boolean)` 才能让它可见；
> 而它一变 VISIBLE 就会在同级 `LinearLayout` 里占掉一格，把胶囊整体往右推。
> 用 `translationX` 平移又踩到「平移 → 胶囊跟着动 → 再平移」的正反馈
> （日志 `70 -> 2604 -> 3094 -> 3584` 一路跑飞），改 `leftMargin` 同样成环。
> 结论：Flyme 原生状态栏歌词本身也是「胶囊占位、时钟让开」这套逻辑，保持一致即可。
> 当前代码里没有这条链路（`ArkStatusBarClockHook` 已删除）。

## 2026-11-04 · v3.4｜下拉栏媒体卡换成液态玻璃

**目标**：`MediaCarousel` 顶部那张媒体卡不再用系统磨砂底色，改成透出壁纸的玻璃面板。

- 新增 `ArkMediaCardStyleHook`：从 `MediaViewHolder.Companion.create` 拿到 holder，
  把 `player`（`MediaCarouseTransitionLayout`）的背景包一层 `ArkGlassDrawable`。
- **不再采样下层纹理**：实测截屏取色会有 1~2 帧延迟，与实时模糊对不上，还会卡。
  改为在系统 `WallpaperBlurDrawable` 之上只做「调透明度 + 轮廓高光」，底子仍是系统实时模糊。
- `ArkGlassDrawable` 用 AGSL（`RuntimeShader`）画圆角矩形：曲面折射扰动 + 弱色散 +
  菲涅尔高光 + 非均匀透明度（中心更透、边缘收暗）。
- `MediaControlPanel` 的 `setPlayerTextColor` / `setPlayerButtonStyle` /
  `setProgressBarStyle` / `bindPlayer` 之后反复应用，避免被 bind 覆盖。

## 2026-11-04 · v3.5｜整卡前景色跟随系统时钟

**起因**：卡片原来用的是 `R.color.qs_mediapalyer_*`（夜间模式主题色），深色壁纸下依然是黑字。

- 新增 `ArkShadeTint`：从视图树里找字号最大的 `HH:mm` 文本（下拉栏大时钟），
  直接读它的 `currentTextColor` —— 那就是 SystemUI 按壁纸明暗算好的前景色。
- 标题 / 歌手 / 时间 / 图标 / 进度条统一用这个颜色，只靠透明度分层。
- 播放键加玻璃圆盘底盘（`RippleDrawable`），并在 `View.setBackground` 钩子里顶掉系统重设。

## 2026-11-04 · v3.6｜前景色跟随系统时钟，切歌/播放不再刷回黑色

**现象**：在 QQ 音乐里点播放/暂停/上一首/下一首，卡片文字与底部图标变回深色（`#333`）。

### 根因一：前景色来源取错了

最初从视图树里找 `<HH:mm>` 文本读颜色，但 Flyme 下拉栏展开时存在两个同形文本：
状态栏小钟（约 42px，白）和 header 大时钟（约 112px，黑），时序交错，
取到的经常是错的那个 —— 表现就是「浅色壁纸配白字」。

也试过 `Clock.onDarkChanged(regions, darkIntensity, rootViewTint)` 的第三个参数，
但实测它给的是**状态栏图标**的颜色（展开后恒为 `#e6ffffff`），
和大时钟实际的 `#e6000000` 不一致，直接用会复现同一个问题。

**修复**：在 `TextView.setTextColor` 的 after 里抓「谁被赋色」——
文本形如 `HH:mm` 且字号 ≥ 60px 的那个才是用户看到的大时钟（卡片里的 `00:20` 只有 36px，
靠字号即可排除），读它被赋的实际颜色。用户在主题里自定义时钟颜色也走这条路。
`onDarkChanged` 保留作兜底（时钟实现被主题替换时）。颜色变化后 500ms 防抖再采纳，
避免开合动画抖动；采纳后通知所有卡片重刷。

### 根因二：按钮图标是位图，颜色滤镜改不到像素

`action0..action4` / `seamlessIcon` 的 `src` 是 75×75 的 `BitmapDrawable`。
`setColorFilter` / `imageTintList` 都设上了（日志 `cf=#e6ffffff tint=#e6ffffff`），
但画面仍是原图的黑色 —— 滤镜没有落到共享 ConstantState 的位图像素上。

**修复**：不再靠滤镜，改成**换图**：

- `SilhouetteDrawable`：把原图标渲染一次，`extractAlpha()` 抽出 alpha 蒙版，
  再用目标色重填，得到同形状的纯色剪影；
- `intrinsicWidth/Height` 原样转发给源 drawable，绘制时把剪影拉伸到 bounds ——
  直接返回 `BitmapDrawable` 会被 density 换算，图标缩水约 1/3；
- 在 `ImageView.setImageDrawable` 的 before 里拦截，系统换播放↔暂停图标时自动重新生成剪影；
- 保留 `onDraw` 前的校正和 `setColorFilter` 拦截作为兜底。

### 真机验证（MEIZU 21 Pro / `481QHGFN226P5`，7 / 3.6.0）

依次点击播放、下一首、上一首，每次截图对比：

```
z1 diff=6.5   z2 diff=9.4   z3 diff=6.7     （画面确实变了 = 点击生效）
标题 / 歌手 / 时间 / 进度条 / 5 个按钮        全程白色
```

图标在播放↔暂停之间切换后仍是白色，说明 `setImageDrawable` 拦截生效。

## 2026-11-04 · v3.7｜撤回前景色接管，颜色交回系统 Monet 色板

**结论**：v3.4~v3.6 那套「取时钟色 + 覆盖整卡」是多余且错误的，已整体删除。

### 系统本来就有标准

反编译 `MediaControlPanel` / `ColorSchemeTransition` / `MediaColorSchemesKt` 后确认，
颜色并不是按壁纸明暗临时算的，而是一条完整的 Monet 链路：

```
Icon artwork                                    ← 专辑封面（不是壁纸）
  → WallpaperColors.fromDrawable(artwork)        ← getWallpaperColor(Icon)
  → new ColorScheme(colors, false, 6)            ← Monet 生成调和色板
  → MediaColorSchemesKt.textPrimaryFromScheme()   ← neutral1.s50
    textSecondaryFromScheme()                     ← neutral2.s200
    textTertiaryFromScheme()                      ← neutral2.s400
    accentPrimary / accentSecondary / primary / onPrimary ...
  → AnimatingColorTransition（333ms ValueAnimator 平滑过渡）
  → setPlayerTextColor / setPlayerButtonStyle / setProgressBarStyle
```

换歌 → 换封面 → 色板变；换壁纸 / 改主题 → 同样会重算。
颜色变化自带 333ms 过渡，本来就是「会自己变色」的。

### 为什么要撤

- **取色源是错的**：系统用封面色，我们去读下拉栏时钟色，两者毫无关系；
- **覆盖会打架**：系统每次 bind 都重设颜色，我们得拦截 `setTextColor` /
  `setColorFilter` / `clearColorFilter`，再在 `View.draw` 前校正一次 ——
  这些补丁全部是为了对抗系统，成本 100% 来自覆盖本身；
- **覆盖后反而不可读**：系统按「背景 = 封面色渐变」设计前景色，
  我们把背景换成壁纸玻璃，前提就被打破了。

### 删除清单

- `ArkShadeTint.kt` 整个文件（时钟取色 / 511px 阈值 / 防抖 / onDarkChanged 兜底）；
- `owned` 颜色归属表与全部设色拦截（`setTextColor` / `setImageTintList` /
  `setColorFilter` ×3 重载 / `clearColorFilter`）；
- `SilhouetteDrawable` + `extractAlpha` 位图剪影（连同 density 尺寸修正）；
- `View.draw` 前的颜色校正、`ImageView.setImageDrawable` 拦截、
  `ProgressBar.setProgressDrawable` 重新 tint；
- `refreshCards` / 重试链 / 各类诊断日志。

### 保留

- `ArkGlassDrawable`：接在系统 `WallpaperBlurDrawable` 之上，只做透明度 + 轮廓高光，
  **不采样下层纹理**（v3.4 已验证采样会卡顿且与实时模糊不同步）；
- 播放键玻璃圆盘：明暗改为直接读 `imageTintList`（那正是系统算出的前景色），
  不再自己判断壁纸明暗；前景深时改用淡黑圆盘，浅色背景下也有对比；
- `View.setBackground` 保活钩子，防止系统把玻璃背景换回去。

### 状态

`versionCode 8 / 3.7.0`，编译通过。设备电量不足，**未做真机验证**，待用户自测。

## 2026-11-04 · v3.8｜玻璃层亮度安全窗（前景色仍完全交给系统）

### 要解决的矛盾

v3.7 把前景色交回系统后，暴露出一个结构性冲突：

- 系统前景色来自**专辑封面**的 Monet 色板；
- 我们的玻璃背景来自**壁纸**。

两者不保证一致。封面亮 → 系统给黑字，但壁纸可能很暗；
封面暗 → 白字配亮壁纸同理。表现就是「字和背景糊在一起」。

### 处理方向：改背景，不改前景

系统的活是决定黑/白，我们的活是让背景跟上这个明暗。所以：

| 系统前景 | 玻璃处理 | 结果 |
|---|---|---|
| 浅色（白字） | `ColorMatrix` 整体压暗 `×0.58` | 背景明度 ≤ 0.55，白字有对比 |
| 深色（黑字） | 提亮到 `[0.36, 0.98]` | 黑字有对比 |

用 `ColorMatrix` 做线性映射而不是叠纯色遮罩：**色相全部保留**，只是明暗被规范。
所以玻璃依然是透的，只是透出来的画面不会把文字吃掉。

### 前景色怎么读（只读不改）

先说一个反编译时才发现的坑：

`AnimatingColorTransition` **没有任何更新 `targetColor` 的方法** ——
构造时把 `sourceColor` / `currentColor` / `targetColor` 全设成默认色，之后再也不动，
`valueAnimator` 也没人 `start()`。`MediaControlPanel` 里除了两个 getter，没有任何地方
调用 `mColorSchemeTransition` 的成员。**那套 Monet 色板动画在这台机器上是静止的。**

真正决定颜色的是 `MediaControlPanel.setPlayerTextColor()`（**无参**）：
它把标题设成 `R.color.qs_mediapalyer_titleText_color` 这个主题资源色 ——
深色主题给白、浅色主题给黑，与壁纸无关。

所以读 `titleText.currentTextColor` 就是系统真正在用的值，且 `setPlayerTextColor`
之后同步生效；取不到才退回 `ColorSchemeTransition.textPrimary.targetColor`（同一个默认色）。

这也解释了之前一直没想通的现象：换成浅色壁纸后白字看不清，
不是我们取色取错了，而是**系统本来就只看主题、不看壁纸**。

### 为什么不需要感知壁纸

压暗 / 提亮是把背景亮度**区间规范化**，不是按壁纸明暗做补偿：

- 白字 → 背景明度一律 ≤ 0.55 → 深壁纸更暗、浅壁纸压到 0.55 → 都够读；
- 黑字 → 背景明度一律 ≥ 0.36 → 反向同理。

所以换壁纸不需要重新判断，规则本身就把结果锁在安全区间里。

### 实现要点

- `ArkGlassDrawable.setForegroundColor(color)`：按明暗换 `ColorMatrixColorFilter`，
  相同值直接 return，避免每帧重建；
- 绘制改成 `canvas.saveLayer(bounds, layerPaint)`，`layerPaint` 同时承载整体透明度
  和亮度映射 —— `BackgroundBlurDrawable` 走 RenderNode，只有落在 layer paint 上的
  `alpha` / `colorFilter` 才会被真正读到；
- 播放键圆盘的明暗也改成跟同一个前景色，前景深时改用淡黑圆盘（原来浅色背景上看不见）。

### 状态

`versionCode 9 / 3.8.0`，编译通过。**未真机验证**。

验证时看日志这一行确认取色成功：

```
[FlymeArk/mediaCard] system foreground = #ff000000 (黑字 → 玻璃提亮)
```

若日志出现但画面明暗没有变化，说明 `saveLayer` 的 `colorFilter` 对系统模糊层不生效，
需要改用离屏重绘或轻量 scrim 兜底。

## 2026-11-04 · v3.9｜前景色改成「资源层强制标准」，玻璃改 scrim

### 参考：FlymeMod 怎么做全局修改

`dev.rikumi.flymemod` 1.4.3（设备上已安装，作用域含 SystemUI）走的是**绘制时改写**：

- hook `android.widget.TextView.onDraw` 与 `android.widget.ImageView.onDraw`（框架基类）；
- 每次绘制前向上遍历父容器，判断这一坨文字/图标属于通知行、锁屏横幅还是媒体卡；
- 属于就把前景强制成常量 `WHITE` / `BLACK`，原值存进 `WeakHashMap` 便于恢复；
- QS 磁贴走另一套：把「白天资源色 → 对应夜间资源色」建一张 palette 表，绘制时按值替换。

这比逐个 View 打补丁稳——**系统写回去也没用，因为最后一帧还是它说了算**。

但它这组 hook 受 `shouldDarken(context)` 门控。设备日志：

```
Control center settings loaded: ... darken=false ...
```

即 **FlymeMod 当前并没有在改前景**，媒体卡的黑字不是它给的，是资源本身就是黑。

### 我们的落点：再往下一层，改资源

反编译 `MediaControlPanel` 可以看到，卡片所有前景都从同一族资源流出来：

```java
getColor(R.color.qs_mediapalyer_titleText_color)      // 标题
getColor(R.color.qs_mediapalyer_artistText_color)     // 歌手
getColor(R.color.qs_mediapalyer_seamlessText_color)   // 无缝播放
getColor(R.color.qs_mediaplayer_play_button_color)    // 播控按钮 → setColorFilter
getColor(R.color.qs_mediapalyer_progressbar_tint)     // 进度 / 底色 / 滑块 → Drawable.setTint
```

`getColor(int)` 内部就是 `Resources.getColor(id)`。所以**在出口处拦截**即可：

- 不需要每帧参与绘制（FlymeMod 是每帧每 TextView 一次）；
- 连它管不到的 `progressbar_*`、`seekbar thumb` 也一起统一；
- 不跟系统的重绑打架——`setPlayerButtonStyle()` 里那句 `setColorFilter(getColor(...))`
  拿到的已经是我们给的值，所以「点播放键变回 #333」从源头消失。

`ArkForegroundStandard` 按资源名分三层，只认 `com.android.systemui:color/qs_mediapalyer_*`
和 `qs_mediaplayer_*`（Flyme 少写了个 e，两种拼写都存在）：

| 层级 | 覆盖资源 | 浅色模式 | 深色模式 |
|---|---|---|---|
| primary | 标题 / 播控按钮 / 进度 / 滑块 | `#000000` | `#FFFFFF` |
| secondary | 歌手 / 无缝播放 / 时间 | `#99000000` | `#B3FFFFFF` |
| tertiary | 进度条底色（`*_background_tint`） | `#4D000000` | `#59FFFFFF` |

id → 层级的判定结果缓存在 `SparseIntArray`，命中后是一次数组查，未命中才加锁补算。

### 玻璃：ColorFilter 换成 scrim

v3.8 用 `saveLayer` 的 `ColorMatrixColorFilter` 提亮/压暗，前提是 RenderNode 播放时
会读 layer paint 上的 colorFilter —— 属于实现细节，不可预期。

改成 scrim（半透明纯色盖在模糊层上，也在文字之下）：

- 由我们自己的 Canvas 画，结果 100% 确定；
- 这本来就是磨砂玻璃的做法：模糊 + 染色两层；
- 浅色模式盖 `#57FFFFFF`（黑字），深色模式盖 `#73000000`（白字）；
- 描边同步换色：深色玻璃用白描边，浅色玻璃用极淡的深色（白描边在白底上等于没有）。

`ALPHA` 仍为 0.86，模糊底留一丝透出。

### 与 FlymeMod 的边界

两边都在改前景时，**以绘制时改写为准**（它在我们后面、且每帧都压一次）。所以：

- 想让标准只有一套 → FlymeMod 里「深色背景（darken）」保持关闭，由我们这层负责；
- 想交给它 → 我们把 `ArkForegroundStandard` 撤掉，只留玻璃跟随。

### 状态

`versionCode 10 / 3.9.0`，已装机（MEIZU 21 Pro）。SystemUI 重启后日志：

```
[FlymeArk/fg] 前景标准已接管（资源层），includeQs=false
[FlymeArk/mediaCard] 标准 = 浅色(黑字 + 亮玻璃)
[FlymeArk/fg] 命中 #7f06074c tier=1 → 浅色模式(黑字)
```

`#7f06074c` = `qs_mediapalyer_titleText_color`，命中 primary 层，返回 `#000000`。

QS 磁贴 / 通知行暂不纳入（`includeQs = false`），需要时把它打开即可，
名单就是 FlymeMod 那张 palette 表。

## 2026-11-04 · v3.10｜撤回媒体卡的全部单独修改（毛玻璃本来就是全局的）

### 结论：这个组件的玻璃不是我们该管的事

反编译 `MediaCarouseTransitionLayout.setBlurBgForLive()`：

```java
MzBlurUtils.setBackgroundBlurDrawable(
    this,
    /* blurRadius       */ 180,
    /* cornerRadius     */ -1.0f,
    /* clipCornerRadius */ mClipCornerRadius,
    /* color            */ isInNightMode() ? 0xB21A1A1A : 0xB2FFFFFF,
    /* alpha            */ mTotalAlpha
);
```

也就是说卡片本来就是 **180 模糊 + 70% 白（白天）/ 70% 深灰（夜间）** 的磨砂玻璃，
和 iOS 那套「重模糊 + 高不透明度染色」是同一个配方。

`MzBlurUtils.setBackgroundBlurDrawable` 是**全局工具**，用它的不止媒体卡：

| 调用方 | 用途 |
|---|---|
| `MediaCarouseTransitionLayout` | 下拉栏媒体卡 |
| `NotificationBackgroundView` | 通知行背景 |
| `LandscapeHeadsUpNotificationView` | 横屏横幅 |
| `BatteryWarningView` | 低电量提示 |
| `PluginBlurView` / `PluginLayout` | 锁屏插件 |

**一处调参，所有玻璃面一起变** —— 这才是"全局修改"该待的地方。

### 我们之前错在哪

v3.4 起把这张卡的背景包进 `ArkGlassDrawable`，并且设了 `alpha = 0.86`。
系统的 70% 白底被乘掉 14%，实际只剩 **60%**，再叠自己的 scrim ——
等于一边把系统调好的白底削薄，一边又拿半透明色去补，补出来是发灰的。

黑字看着"不显眼"，不是系统色不对，是**我们自己把底压暗了**。
后面几版（提亮 / 强制标准 / scrim）都是在补偿这个自己造成的偏差。

### 撤回清单

- 删除 `ArkMediaCardStyleHook.kt`（`MediaViewHolder.create` / `setBackground` / 播放键圆盘）
- 删除 `ArkGlassDrawable.kt`（背景包装、scrim、描边）
- 删除 `ArkForegroundStandard.kt`（资源层前景强制）
- `ArkEntry` 的 SystemUI 分支不再安装媒体卡相关 hook

媒体卡回到系统原生：自带模糊玻璃 + 主题资源色前景，我们不碰。

### 下一步（待真机确认后再动）

如果原生玻璃还不够"液态"，正确的改法是在全局入口调参，一处生效：

- `MzBlurUtils.setBackgroundBlurDrawable` —— 统一 blurRadius / color 染色；
- `setBackgroundBlurDrawableAlpha` —— 统一透明度；
- 静态壁纸路径另走 `WallpaperBlurDrawableManager`。

### 状态

`versionCode 11 / 3.10.0`，编译通过。**装机时设备断开（adb devices 为空），未安装、未验证。**
重连后 `adb install -r` + 重启 SystemUI 即可，日志里不应再出现任何 `[FlymeArk/mediaCard]`。

## 2026-11-04 · v3.11｜全局玻璃入口：统一染色透明度

### 为什么改这里

Flyme 所有玻璃面最终都汇到一个方法：

```java
MzBlurUtils.setBackgroundBlurDrawable(
    view, blurRadius = 180, cornerRadius = -1f, clipCornerRadius,
    color,          // ← args[4]，染色 = ARGB
    debug, blurAlpha, zAdjustment, Function0, Consumer
)
```

6 个调用方：`MediaCarouseTransitionLayout`（媒体卡）、`NotificationBackgroundView`（通知行）、
`LandscapeHeadsUpNotificationView`、`BatteryWarningView`、`PluginBlurView`（锁屏插件）、
`StatusBarHotZoneWindowController`。4 个重载全汇到 10 参版（经 `setBackgroundBlurDrawable$default`），
所以**一次 `hookAllMethods` 覆盖全部玻璃面**，不再单独碰任何一个组件。

### 两个透明度各管一层（关键结论）

读 AOSP `BackgroundBlurDrawable` 源码：

```java
public void setColor(int color) { mPaint.setColor(color); }   // 染色，含 alpha，原样进 Paint
public void setAlpha(int alpha) { mAlpha = alpha / 255f; }    // 只控制 SurfaceFlinger 模糊强度
public void draw(Canvas c) {
    c.drawPath(mRectPath, mPaint);        // ① 先画染色
    c.drawRenderNode(mRenderNode);        // ② 模糊层后盖上去
}
```

两个要点：

1. **染色透明度在 `setColor` 的 alpha 里**，`setAlpha` 管的是模糊强度 —— 之前一直调错了参数；
2. 绘制顺序是「先画染色、再盖模糊」，染色被混进模糊源，等于**先掺白再模糊**，
   这才是玻璃「白得发死、颜色被洗掉」的真正机制。

### 只动 0xB2 这一档

实测系统有三档染色 alpha，只有第一档是主玻璃：

| alpha | 用途 | 处理 |
|---|---|---|
| `0xB2`（70%） | 主玻璃（白 / 深灰） | **改动目标** |
| `0x66`（40%） | 次级遮罩 | 原样保留 |
| `0x1A`（10%） | 弱遮罩 | 原样保留 |

第一版把三档一律压成 58%，会把 10% 的弱遮罩抬到 58%，层次全毁。已改成只认 `0xB2`。

改写只换 alpha，**RGB 原样保留** —— 是「把玻璃调透」，不是换一套配色，因此不与系统主题色逻辑冲突。

### 效果与上限（实测）

同一场景三档对照，卡片背景平均饱和度：

| 染色不透明度 | 平均饱和度 |
|---|---|
| 70%（原值） | 0.0557 |
| 58%（0x94） | 0.0714 |
| 43%（0x6E） | 0.0886 |

饱和度涨了 59%，但**肉眼差异很小**，卡片仍是一片近白。原因有三：

1. 染色画在模糊层**下面**，再高的模糊（180）也会把掺进去的颜色一起抹平；
2. 下拉栏本身已有一层浅色底，卡片背后的「模糊源」早被洗过一遍；
3. `BackgroundBlurDrawable` 只支持 blurRadius / cornerRadius / color / alpha，
   **没有折射、没有边缘高光**，而液态玻璃的辨识度恰恰来自这两样。

结论：这个入口能调的是「透多少」，调不出「液态」。要真正的液态玻璃必须自己渲染
（离屏抓背景 → 模糊 → 提饱和 → 边缘菲涅尔高光），代价是实时性，
v3.8 前后已经因为「截取下层纹理导致卡顿」否决过一次。

### 状态

`versionCode 14 / 3.11.0`，浅色玻璃临时设为 43%（0x6E）作通透上限对照，已装机。
定稿值待用户目视确认后回填（58% 为保守档，43% 为极限档）。

注意：43% 在**深色壁纸 + 浅色模式**下会让黑字对比度不足，这是压透明度的固有代价。

## 2026-11-04 · v3.12｜面板：进度条跟专辑取色 + 封面左右轮播

### 背景

「音乐壁纸」（点击展开 → 取色 → 背景换成取色样式）目前**项目里没有**，
面板背景仍是固定 `#F018181C`。本轮先落第一步：进度条跟专辑颜色走；
顺带把换曲时的封面切换做成轮播。

### 取色：`ArkAlbumColor`（零依赖）

模块除 LSPosed（compileOnly）外零依赖，不引入 androidx.palette，
48px 量化分桶就够面板用：

1. 封面缩到 ≤48px；
2. RGB 各 4bit 量化分桶，饱和度 < 0.15 的灰像素丢弃；
3. 桶得分 = 像素数 × (0.35 + 平均饱和度) × 亮度适配度
   （亮度适配度压制过暗/过亮的桶，两者在深色底上都发闷）；
4. 最优桶取平均色，HSV 域整形：`S ≥ 0.55`、`V ∈ [0.60, 0.78]`，
   保证在 `#F018181C` 上够亮够艳。

封面缺失 / 全灰 / 已回收 → 返回 null，调用方保持原色，不会把进度条洗成白色。

### 进度条：`ArkProgressBar.barColor`

新增 `barColor` 属性，换色用 `ArgbEvaluator` 350ms 过渡（切歌不硬跳），
`onDetachedFromWindow` 取消动画。默认色仍是 `#FF7A2F`（无封面时的兜底）。

### 封面轮播：双图插槽

布局把 62dp 封面改成 `panel_cover_slot`（FrameLayout，clipChildren）
内含 `panel_cover`(A) + `panel_cover_b`(B) 两层：

- 换曲时新图落到底层，旧图向反向滑出、新图滑入（340ms，DecelerateInterpolator），
  裁剪在 62dp 方框内，观感是轮播；
- **方向跟按键走**：上一首从左进（dir=-1），下一首从右进（dir=+1），
  自动切歌默认 +1；同曲补封面（每秒进度推送）不动画；
- 快速连按安全：入场前先 `animate().cancel()`，动画收尾用
  `handler.postDelayed` 归位旧图，且归位前检查它是否已重新上场。

### 真机验证（MEIZU 21 Pro，3.12.0 / versionCode 15）

- `am start` 直接拉面板 → SystemUI 同步推来真实曲目《不煽情 - 许嵩》+ 真封面，
  面板渲染正常、无崩溃、无错位；
- **进度条实测 `#6B9FC3`（蓝，H205°）**，不再是默认橙 —— 取色链路在真机生效
  （该封面即淡蓝色调）；
- 轮播滑动动画需换曲触发，待用户实测。

### 顺带

全局玻璃的浅色透明度从实验值 43%（0x6E）回退到保守档 58%（0x94），
43% 未定稿，且深色壁纸 + 浅色模式下有黑字对比度风险。

## 2026-11-04 · v3.13.1｜样式对齐系统媒体卡 + 按钮节流 + 三个真机反馈修复

### 样式对齐（用户反馈「明显样式不对，是黑的」）

**根因链有两层：**

1. **Flyme 的深色判断不走 configuration**。反编译确认：
   `MediaCarouseTransitionLayout.isInNightMode()` → `SystemUICommonUtils.isNightModel(ctx)`
   → 静态字段 `sIsNightMode`。面板 App 进程读 `configuration.uiMode` 判断，与它不一致。
   修法：SystemUI 侧反射读同一个字段（`XposedHelpers.getStaticBooleanField`），随曲目推送
   （`EXTRA_PANEL_NIGHT`）传给面板，模式变化时整套配色 + 背景定标重刷。
2. **`ArkMorphAnim.prepare()` 硬编码深黑背景**：展开动画会把卡片背景换成自己构造的
   `GradientDrawable`（`0xFF18181C`），把 applyPalette 设置的浅色盖掉。
   修法：morph 颜色改为构造传入 + `setColors()` 动态下发。

浅色模式最终效果：`#C9FAFAFA` 玻璃卡 + 黑字 + 深色描边 `#1F000000`，进度条滑块圆点
（thumbRadius 5.5dp，颜色随专辑取色），对齐系统媒体卡观感。

### 「暂停按钮失效」的真正根因：媒体键被派发两次

`SystemUILiveHost` 的 exported 广播接收器在 `com.android.systemui` 主进程和
`:screenshot` 子进程**各注册了一份**，媒体键连发两次：播放/暂停互相抵消（看起来就是没反应），
切歌一次跳两首。之前一直存在，本轮 LSPosed 日志暴露（每次点击两条 `media key dispatched`）。

修法：`onReceive` 开头过滤进程名，只有主进程处理（media key / state / sync / seek 全部受益）。
注意 **`:screenshot` 子进程是常驻的**，改完只 kill 主进程不够，子进程也要 kill
（或等系统自然回收）才会用新代码——本次实测踩过。

### 用户反馈的其他修复

- **按钮节流**：新增 `sendKeyThrottled`，窗口按按键各自记录（上一首 400ms / 播放暂停 250ms /
  卡片跳转 800ms）。切歌的节流窗口不挡暂停。
- **切歌进度条滑回起点**：新增 `collapseProgress()`，220ms Decelerate 动画从当前位置滑到 0，
  替代硬归零；动画期间（`isCollapsing`）宿主推送不覆盖进度。

### 真机验证（3.13.1 / versionCode 17）

- 浅色玻璃卡 + 黑字 + 专辑取色滑块：截图确认（陈红鲤/庄心妍曲目，进度条分别取到黄/暗红）
- 播放/暂停点击：图标正确切换，媒体键单次派发
- seek 广播链路：`seekTo 60000` → QQ 音乐真实跳到 62.9s
- 音乐壁纸背景：封面模糊 + 提饱和铺满全屏，换曲 crossfade
- 卡片跳转：QQ 音乐 `launchIntent=null`（不设置 sessionActivity），兜底按包名拉主界面

### 遗留（下阶段）

- 收藏 ❤ / 歌词「词」按钮、输出切换：需 hook MediaSession 的 custom actions 与 MediaRouter2，
  与系统媒体卡对齐的最后一部分
- 移除面板侧临时诊断日志（`FlymeArk` tag 的 Log.d）

## 2026-11-04 · v3.13.2｜暂停不再用媒体键 toggle（连点导致切歌）

**起因**：用户反馈「频繁点击暂停播放按钮就会切歌」。

### 根因

播放/暂停按钮发的是 `KEYCODE_MEDIA_PLAY_PAUSE`（toggle 语义）。这条链路
`面板 → 广播 → SystemUI → AudioManager.dispatchMediaKeyEvent` 走的就是**线控耳机键**
通道：QQ 音乐有「线控双击切换歌曲」、Flyme 媒体键分发层也可能把两次 PLAY_PAUSE
识别成双击。250ms 的节流窗口挡不住人手连点（间隔常在 300~500ms），
于是「点两下暂停」变成「下一首」。

### 改法：发目标状态，不发 toggle

- 新增 `ACTION_PANEL_PLAYBACK` + `EXTRA_PLAY_TARGET`（Boolean）。
  面板点击后发的是「我要播放」或「我要暂停」，不是「翻转一下」。
- `ArkLiveExpandHook.setPlayback(ctx, playing)`：取 QQ 音乐的 `MediaController`，
  直接 `transportControls.play()` / `transportControls.pause()`。
  **play/pause 是幂等的** —— 连点十次暂停也只是暂停十次，不会切歌；
  也不会像 toggle 那样两次互相抵消。
- 兜底：拿不到 MediaController 时仍回落 `dispatchMediaKeyEvent(PLAY_PAUSE)`，
  保证按钮不至于完全失效（日志会打 `playback fallback to media key`）。
- 面板侧防抖从「按键 250ms」改为「按目标状态 180ms」：`play` 与 `pause`
  各自计时，互不阻塞，所以「暂停→立刻播放」不会被上一次 pause 吃掉；
  同一目标窗口内重复点击直接丢弃（结果本来就相同）。
- 上一首/下一首仍走媒体键（`KEYCODE_MEDIA_NEXT/PREVIOUS` 不是 toggle，
  没有双击歧义），节流窗口保持 400ms。

### 待真机验证

- 连点暂停 5~10 次：应停在暂停态，不切歌、不跳曲
- 暂停 → 立刻播放 → 再暂停：图标与真实状态一致，无延迟
- 关掉 QQ 音乐进程后再点：日志出现 `playback fallback to media key`

## 2026-11-04 · v3.14.0｜面板收藏 ❤ + 背景改系统玻璃

### 一、收藏歌曲（❤）

**取证**：QQ 音乐 MediaSession 无收藏接口 —— `custom actions=[]`、`rating type=0`
（dumpsys media_session）。但它的播放通知挂了 5 个 action 按钮（`actions=5`），
通知 action 的 PendingIntent 谁拿到都能 send()。

- `ArkLiveExpandHook.inspectFavoriteActions()`：每次歌词通知刷新时枚举
  `notif.actions`，**每首歌打一轮全量日志**（`notif action[i] title='...'`，
  用于真机确认按钮文案），并按标题匹配收藏按钮：
  - 含「已喜欢 / 已收藏 / 取消喜欢」→ 已收藏状态
  - 含「喜欢 / 收藏」→ 未收藏状态
  - 都不含 → 保存按钮但状态记 null（面板乐观切换，无法同步）
- `toggleFavorite()`：send 该 PendingIntent（SystemUI 进程内，无需额外权限）。
- 新广播 `ACTION_PANEL_FAVORITE`（面板 → SystemUI）。
- 面板：按钮行改为「❤ 上一首 播放 下一首」四键 weight 均布（对齐系统媒体卡）；
  ❤ 已收藏时用专辑主题色点亮，未收藏普通图标色；600ms 防抖 + 乐观切换，
  真实状态随 SystemUI 推送（`EXTRA_PANEL_FAV`）纠正。

### 二、背景改系统同款玻璃（v3.13 音乐壁纸背景整体移除）

用户反馈面板背景与下拉栏顶部媒体卡样式不一致（面板是封面糊满的彩色底，
媒体卡是透壁纸的玻璃）。v3.13.3 起面板背景与系统玻璃同构：

- 窗口层：`FLAG_BLUR_BEHIND` + `blurBehindRadius = 24dp`，模糊面板背后的真实壁纸；
- 染色层：root 铺 scrim，浅色 `#B2FFFFFF` / 深色 `#B21A1A1A`，与系统玻璃
  `MzBlurUtils` 的 0xB2 染色**完全同值**；
- 降级：`isCrossWindowBlurEnabled=false`（省电模式等）时 scrim 提到 88% 保可读；
- 删除 `ArkBackdrop.kt` 及全部引用（applyBackdrop/swapBackdrop/backdropJob/lastCover），
  专辑取色仅保留给进度条与收藏点亮色。

### 顺手清理

- 移除面板侧遗留临时日志（`panel init nightMode` / `panel setNight`）

### 待真机验证

- ❤ 点击 → QQ 音乐里当前歌变成「我喜欢」（首次看日志确认按钮文案命中匹配规则）
- 再点 ❤ → 取消收藏
- 面板背景：模糊透壁纸、色调与下拉栏媒体卡一致；深浅模式切换跟随

## 2026-11-04 · v3.14.1｜收藏 toggle 抖动修复 + 撤回全屏毛玻璃 + 按钮居中 + 右上角 App 图标

### 一、收藏「第二次点击取消失败」（日志实锤）

真机日志还原（16:21:10~16:22:33）：QQ 音乐通知按钮标题**固定叫「收藏」**，
收藏状态变化要等 1~5 秒后随通知刷新才体现（「已收藏/取消喜欢」类文案）。
点 ❤ 后：
1. 面板乐观点亮 ❤ → QQ 音乐收藏成功
2. 滞后的通知推送把按钮推断成「未收藏」→ 面板 ❤ 被重置熄灭（用户以为取消失败）
3. 用户再点 → 又发一次 toggle → 状态被切走……连点几次后状态漂移不可预期

修复：
- 面板：点击后 **6 秒内忽略 SystemUI 推送的收藏状态**（FAV_PUSH_LOCK_MS），
  只信本地乐观状态；6 秒后推送反映真实状态，可正常纠正
- SystemUI：`toggleFavorite` 增加 800ms 防抖（面板 600ms 之外的窗口期），
  toggle 语义的按钮连点不再抖动

### 二、撤回全屏毛玻璃（用户反馈「整个页面都变成毛玻璃」）

v3.14.0 的 `blurBehindRadius` 作用于全屏窗口，把整个启动器都糊了。
普通 App 没有局部背后模糊的 API —— 撤回 blur/scrim，背景恢复全透明，
玻璃质感只由卡片自身配色（#C9FAFAFA / #C216161A）承担。

### 三、按钮居中（用户反馈跑偏）

按钮行改三段式：播放键**绝对居中**；左区 ❤+上一首靠拢播放键（❤ 落到最左），
右区下一首对称位置，与参考图四排版一致；右侧留白给后续输出切换/歌词按钮。

### 四、右上角显示媒体 App 图标（参考图四）

标题行右上角 24dp 显示当前媒体 App 图标（`packageManager.getApplicationIcon`，
面板进程直接取，无需 SystemUI 参与）；取不到时隐藏。

### 待真机验证

- 点 ❤：图标立即点亮且**不再被滞后推送重置**；QQ 音乐内确认已收藏
- 等 6 秒后 ❤ 状态与 QQ 音乐一致；再点一次 → 取消收藏成功
- 背景：卡片外完全清晰，不再整页糊；卡片自身玻璃色不变
- 播放键在卡片正中；右上角有 QQ 音乐图标

## 2026-11-04 · v3.15.0｜网易云音乐适配 + 输出切换 + App 图标动态化

### 一、网易云音乐同步适配（走魅族状态栏歌词协议）

网易云 7.2.10 起原生支持魅族状态栏歌词（ticker 协议与 QQ音乐同款，设置里需手动开启）。
SystemUI 的歌词胶囊链路对它本来就能跑，卡点只是本模块各处的包名过滤。全部泛化：

- `ArkConstants.MEDIA_APP_PKGS = {QQ音乐, 网易云音乐}` + `isMediaApp()`
- `ArkLiveCoverHook` / `ArkLiveLyricHook` / `ArkLiveExpandHook.launchPanel`：包名过滤改集合判断
- `ArkLiveExpandHook`：新增 `currentPkg`（歌词通知归属包名），
  MediaSession 选取优先级 = 当前歌词 App > 在播的受支持 App > 第一个受支持 App，
  seek/播放暂停/读 session 跟随真实播放器
- `ArkLiveInjector`：网易云进程也装歌词通道补发；开关广播按
  `<pkg>.ACTION_MEIZU_LYRIC_TURN_ON.<进程>` 模式探测两种形式（无公开文档，
  广播无人接收即 no-op，无害）；探测不中时用户在网易云【设置 → 状态栏歌词】
  手动开启一次即可
- `ArkLiveAllowHook` / `ArkLiveSettingsHook`：system_server 准入兜底与设置页登记
  覆盖两个 App（未安装的自动跳过）

**注意：LSPosed 模块作用域需勾选「网易云音乐」**，否则网易云进程内 hook 不会装载。

### 二、输出切换（右下角投放按钮，参考用户图二）

- 图标 `ic_ark_output.xml`：三条声波弧线 + 实心三角（照用户图二手绘）
- 布局：右区 = 下一首 + 输出按钮，与左区 ❤+上一首 对称；播放键仍绝对居中
- 链路：面板点击 → `ACTION_PANEL_OUTPUT_LIST` → SystemUI 反射
  `MediaRouter2Manager`（@SystemApi，系统媒体卡输出切换同源）取
  `getAvailableRoutes`/`getSelectedRoute` → `ACTION_OUTPUT_ROUTES` 回面板 →
  弹窗（深浅主题跟随）单选 → `transferToRoute` 切换
- 兼容：`getAvailableRoutes`/`getRoutes`、`getName()`/`getName(ctx)`、
  `transferToRoute(pkg, id)`/`(pkg, MediaRoute2Info)` 全部双签名降级；
  无路由时 toast「没有可用的输出设备」

### 三、右上角 App 图标动态化（v3.14.1 未显示问题的根治）

v3.14.1 图标固定取 `MEDIA_APP_PKG`，依赖装机时 QQ音乐包名可解析。v3.15 改为：
SystemUI 随曲目推送 `EXTRA_PANEL_APP_PKG`（歌词通知归属包名），
面板按真实播放 App 显示图标（bindData/applyTrack 都刷新，换播放器即换图标），
跳转兜底 `getLaunchIntentForPackage` 同步跟随；解析失败仍隐藏不占位。

### 待真机验证（设备没电，用户稍后测）

- 网易云播放 → 状态栏歌词胶囊出现（先在网易云设置里开「状态栏歌词」；
  LSPosed 作用域勾选网易云后重启网易云进程）
- 点胶囊展开面板：右上角显示网易云图标，播放/切歌/seek/收藏正常
- 右下角投放按钮：弹窗列出「本机/蓝牙设备」，选择后声音切过去
- QQ音乐回归：面板各功能与 v3.14.1 一致

## 2026-11-04 · v3.15.2｜收藏真值双向绑定 + 模块描述/推荐作用域更新

### 一、收藏状态真值（真机日志推翻此前判断）

真机日志确认 QQ 音乐通知按钮标题**会在「收藏」/「已收藏」之间切换**
（16:45:57 `已收藏` → 16:46:25 `收藏` → 16:54:03 `已收藏`），标题本身就是真值。
之前"固定叫收藏"的结论来自残缺日志，导致把「收藏」误判为真值缺失。

- **真值来源**（优先级）：通知按钮标题 > MediaSession metadata extras（key 含
  fav/favorite/collect/liked，只认可解释的 Boolean/数字/字符串）> 指纹学习表
- **打开面板即时同步**：SystemUI 主动查 `NotificationManager.activeNotifications`
  读当前播放器通知的 action 标题，不等 1~5 秒的推送（`refreshFavFromActiveNotifications`）；
  读到的通知标题与当前曲目对不上时跳过（防止把上一首的状态安到新曲）
- **切歌即时刷新**：换曲（通知刷新 / 面板 sync）立即清空旧状态并查一次在播通知，
  再按 250/700/1500/2500ms 短轮询，谁先对上曲目标题就用谁 —— 把「延迟切状态」压到最短
- **沉降窗口**：点击收藏后 5 秒内不采信通知标题（那一轮反映的是点击前状态），
  面板沿用乐观状态；窗口过后正常纠正
- **指纹自学习**：收藏按钮图标 resId 作指纹，学到真值就持久化到 SystemUI 数据目录
  （`flymeark_fav` SharedPreferences）；新真值与已记映射冲突时自动把该 App 整张表
  取反纠错；点击后指纹变化且点击前状态已知时也闭环学习
- **面板三态**：已收藏（实心+专辑色）/ 未收藏（空心）/ 未定（空心+42% 半透明），
  不再把「未知」假装修成未收藏；换曲归零为未定

### 二、网易云取证（下一阶段的真双向绑定）

dumpsys 发现**网易云的 MediaSession 带 custom actions**：
`like(2131239517)`、`收藏(2131239379, extras)`、`播放模式`、`播放倍速` ——
网易云走 MediaController.sendCustomAction 就能收藏/读 extras 里的状态，
比 QQ 音乐的"通知代点"干净得多。下一阶段实现。

### 三、模块元数据

- LSPosed 描述改为：「一个针对魅族实况通知优化插件模块，使用方法安装插件激活后
  去设置-通知与状态栏-实况通知开启对应app开关 · 开发者：多爱梦」
- 推荐作用域（xposedscope）加入 `com.netease.cloudmusic`

### 其他

- 修复 MediaMetadata stub 无 getExtras 的编译问题（反射调用）
- 已知观察项：频繁播放/暂停时实况胶囊偶发消失 —— SystemUI 重启后 Flyme 侧
  实况状态不重建（切歌后 QQ 音乐重发通知即恢复），是否与模块相关待进一步取证

### 待真机验证

- 已收藏的歌 → 打开面板 ❤ 即点亮；切到未收藏的歌 → ❤ 数百 ms 内变空心/未定
- 点 ❤ 收藏 / 取消，5 秒后 ❤ 与 QQ 音乐状态一致
- 收藏若干首后重启手机，打开面板 ❤ 状态仍正确（学习表持久化）

## 2026-11-04 · v3.15.3｜紧急修复：面板一点就 ANR，系统界面崩溃

**现象**（真机）：打开面板后点上一首/下一首/暂停**全部无响应**，随后 SystemUI 报
「系统界面已停止运行」，反复重启。

**取证**：`/data/anr/anr_2026-11-04-17-18-29-678`

```
"main" prio=5 tid=1 Runnable
  at java.lang.Character.isWhitespace(...)
  at com.flymeark.ArkLiveExpandHook.pushToPanel
  at com.flymeark.ArkLiveExpandHook.refreshFavFromActiveNotifications
  at com.flymeark.ArkLiveExpandHook.pushToPanel
  at com.flymeark.ArkLiveExpandHook.refreshFavFromActiveNotifications
  ...（同一对栈帧重复数百层）
```

`ANR in com.android.systemui` / `Reason: Input dispatching timed out (Waited 5016ms)`，
随后 `Process com.android.systemui has crashed too many times, killing!`

### 根因：两个方法无限互递归

v3.15.2 为了让「切歌后收藏状态尽快更新」加了两处互相调用的逻辑：

1. `pushToPanel(changed=true)` → `refreshFavFromActiveNotifications()`
2. `refreshFavFromActiveNotifications()` 结尾 → `lastPushKey = null; pushToPanel()`

`lastPushKey = null` 让第 2 步的 `pushToPanel` 一定判成 `changed=true`，
又回到第 1 步。而 `refreshingFav` 这个"重入保护"写在 `finally` 里、**在回调
`pushToPanel` 之前就已复位**，等于完全没生效 → 主线程原地打转，5 秒后 ANR，
被系统判为反复崩溃直接杀掉系统界面。按钮"全部失效"是主线程已经卡死的结果，
不是三个按钮各自的问题。

### 修复（三道防线）

1. **`pushToPanel` 加真正的重入锁 `pushing`**：进入即置位、`finally` 复位，
   重入直接 return。递归链在这一步被切断。
2. **`refreshFavFromActiveNotifications` 不再回推面板**：它只负责刷新
   `favState/favHash/favIntent`，推送由调用方发起（`pushToPanel` 自身顺带；
   切歌轮询在 refresh 后显式 `pushToPanel(c, force = true)`）。职责分离后
   不存在 A↔B 环。
3. **`SystemUILiveHost.onReceive` 整体 try-catch**：这是 SystemUI 主线程的广播
   回调，任何未捕获异常都会直接打死系统界面。现在异常只记日志，不影响系统。

### 顺带修正

- `pushToPanel` 增加 `force` 参数，区分「换曲」（清收藏、带封面）与「同曲刷新」
  （只为更新收藏状态，不清 favState），避免收藏轮询把状态打回「未定」。
- 收藏轮询仍保留 250/700/1500/2500ms 四档，双向绑定的实时性不变。

## 2026-11-04 · v3.15.4｜收藏状态提速：按曲目记忆 + 解除跨歌误锁

**反馈**：切歌后 ❤ 要过好几秒才变成正确状态；顶部系统自带的栏（Flyme 实况胶囊）
换歌没有延迟。

**结论：链路不慢，慢的是播放器写通知。**
胶囊读的是**歌词通知**（ticker 协议，切歌即时刷新），而收藏真值在**播放通知**的
action 按钮标题里 —— QQ 音乐重建那条通知要 1~5 秒。我们读的是同一份数据，
不可能比它自己更新更早。所以只能「先答，再校正」。

### 一、按曲目记忆收藏状态（核心）

新增 `trackFavMap`（持久化 prefs `flymeark_track_fav`，key = `包名#标题`）：

- 切歌 / 打开面板时**先查记忆**：这首歌见过就立刻显示当时的真实状态，0 延迟；
- 通知真值到达后照常覆盖（用户在 QQ 音乐里改过收藏也能纠正）；
- 用户点 ❤ 立即写入记忆（意图先落地）；
- 从通知拿到确定的标题真值时也写入记忆。

效果：**只有「从未见过的曲目」需要等一次**，之后永久命中。指纹表（按 App 状态）
继续保留，作为记忆缺失时的兜底。

### 二、解除跨歌误锁（延迟的真凶之一）

两个 5 秒锁本是防「同一首」的滞后推送把刚点的 ❤ 冲掉，但换歌后仍然生效：

| 位置 | 问题 | 修复 |
| --- | --- | --- |
| 面板 `applyTrack` | 点 ❤ 后 5 秒内切歌，新歌状态被锁住不显示 | 换曲时 `lastFavAt = 0` |
| `pushToPanel` / `syncNow` | 换曲后 5 秒内不采信新歌的通知标题 | 换曲时 `lastFavSendAt = 0` |

同时换曲清空 `pendingFavTarget/pendingFavHash` 点击闭环 —— 那是上一首的语境，
留着会把新歌的指纹变化错记到上一首的因果上。

### 三、轮询延长

`pollFavAfterTrackChange` 增加 4s 一档（250/700/1500/2500/4000），覆盖 QQ 音乐
最慢的通知刷新。

### 待取证

`dumpsys media_session` 显示 QQ 音乐 MediaMetadata 有 **20 个字段**（标准字段仅约 10 个），
若其中含收藏字段，就能彻底甩开通知延迟。已在 `favFromMetadata` 打了 `metadata extras keys=`
日志，下次真机确认。

## 2026-11-04 · v3.15.5｜找到真正的快源：metadata 里的 COLLECT_STATE

**线索**：用户指出「顶部系统自带的栏换歌没有延迟」。系统媒体卡读的是
**MediaSession 的 metadata**（setMetadata 与切歌是同一个动作），而我们一直盯着
**播放通知的按钮标题**（要重建 RemoteViews，慢 1~5 秒）。读错数据源了。

### 取证：QQ 音乐把收藏写在 metadata 里

前几版一直读不到 metadata extras，原因是 `MediaMetadata.getExtras()` 是 hidden API，
反射必然失败（`favFromMetadata` 直接 return）。改用内部字段 `mBundle` 后拿到完整 20 键：

```
android.media.metadata.ALBUM_ART:Bitmap
android.media.metadata.RATING:Rating          ← 用户评分
ucar.media.metadata.COLLECT_STATE:String      ← ★ 收藏状态，值 "1"/"0"
ucar.media.metadata.PLAY_MODE:Long
ucar.media.metadata.VIP:String
...（共 20 项）
```

真机日志验证（随歌切换实时变化）：

```
fav from COLLECT_STATE=1 -> true
fav from COLLECT_STATE=0 -> false
```

**这就是 0 延迟的真值源**：`ucar.media.metadata.COLLECT_STATE`，QQ 音乐（UCAR 内核）
直接给字符串 "1"/"0"，且随切歌即时更新。

### 改动

1. **`metaBundle()`**：先试 `getExtras()`，失败则反射内部 `mBundle`（真正能用的路径）。
2. **`favFromMetadata()` 三级真值源**：
   - ① `ucar.media.metadata.COLLECT_STATE`（QQ 音乐，最准最快）
   - ② `METADATA_KEY_USER_RATING`（标准字段，其它播放器可能用）
   - ③ extras 里任何像收藏的键（原有模糊匹配，兜底）
3. **`readFavFromSession()`**：只读 metadata、**不解码封面**。切歌轮询用它，
   避免每次跨进程搬几百 KB 位图。
4. **`inFavSettle()` 统一沉降窗口**：点 ❤ 后 5 秒内不采信外部真值（读到的是点击前旧值），
   所有真值入口（metadata / 通知 / session）统一走这个判断。换曲时 `lastFavSendAt` 归零，
   窗口不跨歌。
5. **切歌清空 `favIntent`**（安全修复）：它是「通知上的收藏按钮」，换曲后仍指向上一首。
   不清掉的话，新歌通知还没刷出时点 ❤ 会去**取消收藏上一首**。清空后由 refresh 在
   「通知标题匹配当前曲」时重新填充 —— 宁可短暂无响应，也不能误操作别人的歌。

### 保留

v3.15.4 的 `trackFavMap`（按曲目记忆）保留为兜底：metadata 读不到时（缓存、异常、
其它播放器）仍能立即给出上次已知状态。

## 2026-11-04 · v3.15.6｜收藏点击闭环校验 + 网易云封面 + 描述修正

**反馈**：收藏一首没收藏的歌后再点取消，有概率失败（收藏同样）；网易云封面更新慢；
模块描述里的「去设置开启实况通知」与实际不符（实际默认开启，且设置里没有网易云）。

### 一、收藏「点了没反应」：改成闭环校验

原因有两层：

1. **防抖把第二次点击吞了**。SystemUI 侧 800ms + 面板 600ms，两级叠加，
   「收藏后马上取消」的第二次点击很容易落在窗口内被丢弃 —— 面板已经乐观切换，
   但动作根本没发出去，看起来就是「取消失败」。
   → SystemUI `800ms → 200ms`，面板 `600ms → 350ms`（只挡真正的误触双击）。
2. **动作发出去了但没生效**。收藏按钮的 PendingIntent 挂在通知上，通知没重建时
   它可能仍指向上一首/上一个语义。以前只能盲信。

现在做成**闭环**：

```
send → 1.2s 用 COLLECT_STATE 校验 → 没变 → 再等 0.8s 再校验 → 仍未变才重发（最多 2 次）
```
- 重发前 `refreshFavAction()` 重新从通知取一次按钮（语义可能已切换）
- 校验成功：撤销沉降窗口，写记忆表，推送面板
- 两次都失败：以播放器真值为准，把 ❤ 拉回真实状态（不再硬拗）
- 读不到真值（null）：放弃校验，交给通知/记忆表兜底，绝不盲目重发导致来回翻转

顺带把面板的 `FAV_PUSH_LOCK_MS` 从 6s 降到 1s：真值保护已经上移到 SystemUI
（`inFavSettle()` + 闭环校验），它不会再推旧值；锁 6 秒反而会让校验失败时
面板一直显示错误状态。

### 二、网易云封面延迟

`pushToPanel()` 原来用 `trackCover`（歌词通知里缓存的 largeIcon），网易云重发通知慢，
切歌时面板会先显示上一首封面。改为**优先读 MediaSession 的 `ALBUM_ART`**
（与切歌同一时刻可用），读不到才回落通知缓存。

### 三、模块描述与实际不符

原文案让用户「去设置-通知与状态栏-实况通知开启对应 app 开关」，实际：

- 模块的 `LiveAllowGate` 在查不到开关时**默认放行**，所以看起来「默认就是开的」；
- Flyme 的实况通知列表有白名单，网易云不会出现在里面。

改为：
> 一个针对魅族实况通知优化插件模块。在 LSPosed 中启用并勾选 系统界面 / QQ音乐 /
> 网易云音乐 即可使用，无需在系统设置里额外开启实况通知 · 开发者：多爱梦

### 四、取证

`metadata bundle` 日志改为**按播放器**各打一次（原来整个进程只打一次，先命中 QQ 音乐
就再也看不到网易云的字段集），便于确认网易云是否暴露 `ALBUM_ART` / 收藏字段。

## 2026-11-04 · v3.15.7｜收藏改成「点击锁」+ 串行泵，修连点只生效一次

**起因**：用户实测「收藏还是失效，短期连续点击只触发一次」。这不是没加防抖，而是
**加错了位置**：面板在 350ms 内直接 `return`，❤ 却已经乐观切换了 ——
请求被丢弃、UI 却动了，两边永久错位。而播放器那侧是 toggle + 限流，本来就
不该被连点。

### 一、点击层直接锁（面板）

- 一次点击后 **900ms 内不再接受输入，UI 也不切换**（`FAV_CLICK_LOCK_MS`）。
  这个窗口覆盖「发请求 + 首次真值校验」，结果出来才允许下一次。
- 删掉了「窗口内静默丢弃但已切 UI」的逻辑 —— 那正是错位的来源。

### 二、SystemUI 侧：串行目标泵，不丢弃任何意图

- `favDesired` 保存用户最后一次想要的状态；`favGen` 是意图代号，旧校验回调
  发现自己过期就直接让位（不允许过期目标被发出去）。
- `favBusy` 保证同一时刻只有一条「发送 → 校验」链，绝不并发 toggle。
- **幂等短路**：发送前先读 `COLLECT_STATE`，已等于目标就不发（省掉音乐端一次操作）。
- **确认等待 `FAV_CONFIRM_WAIT_MS=1200ms`**：上一次 toggle 刚发出、metadata 还没刷新时
  不做短路判断（否则会把旧值误读成「已经是目标」而漏发，最终反向错位）。
- 校验节奏 900ms → 700ms，每次目标最多 2 次发送；两次都失败就以播放器真值为准。

### 三、结论推送可以突破面板的推送锁

新增 `EXTRA_PANEL_FAV_FINAL`：SystemUI 校验出结论（成功或失败）时打上这个标记，
面板收到**无视 2 秒推送锁直接采信**。否则点击锁期间失败结论会被挡掉，
面板会一直停在错误状态。

### 四、换曲作废在途泵

`resetFavPump()` 接进 `pushToPanel` / `syncNow` 的换曲分支：旧目标的校验回调读到的是
**新歌**真值，不作废会按上一首的目标把新歌 ❤ 写错。

## 2026-11-04 · v3.15.8｜查清「系统组件限制时间」：系统没有节流，错在按钮语义

**取证方法**：pull `/system_ext/priv-app/SystemUI/SystemUI.apk`（71MB，5 个 dex），
`dexdump -d` 反汇编后定位 `com.flyme.statusbar.livenotification.LiveNotificationController$LiveNotificationClickHelper`。

**结论：系统侧没有任何点击节流**。`handleTouchEvent` 只做三件事：判断触点是否落在
实况区域（`SystemUICommonUtils.calcViewScreenLocation`）、记录 `mHasDown`、判断
`ACTION_UP`/`ACTION_CANCEL`。没有任何 `uptimeMillis` 比较或延迟常量 —— 点一下就直接
`send` 通知里的 action。

### 真正的限制来自「按钮语义」

`dumpsys notification --noredact` 拿到的 QQ 音乐通知：

```
actions={
  [0] "已收藏" -> PendingIntent{... broadcastIntent}
  [1] "上一首" -> ...
  [2] "播放"   -> ...
  [3] "下一首" -> ...
  [4] "显示桌面歌词" -> ...
}
```

收藏按钮的标题是**状态绑定**的：

| 标题 | 表示当前状态 | 点下去的实际效果 |
|---|---|---|
| `收藏` / `喜欢` | 未收藏 | 收藏 |
| `已收藏` / `取消收藏` | 已收藏 | 取消收藏 |

而这条通知要 1~5 秒才重建。连点的第二次点击如果还用旧的 `favIntent`，
语义就是反的 —— **想取消收藏，实际又发了一次「收藏」**。这才是「连点只生效一次」。

### 改法：按语义实时取按钮，语义不符就等通知重建

- 新增 `favoriteActionFor()`：每次发送前**实时**扫 activeNotifications，返回
  `(PendingIntent, 按钮当前状态)`；标题先严格匹配当前曲（防串台），匹配不到退化为
  当前 App 的媒体通知。
- `sendFavAction()` 只在 `btnState != target` 时发送（语义正确才点）。
  `btnState == target` 说明通知还没刷新，每 250ms 重试，最多 3 秒
  （`FAV_SEMANTIC_WAIT_MS`）；过程中真值一旦到位立即结束。
- 等不到就放弃并回推真实状态，**绝不发反方向的点击**。
- 面板点击锁 900ms → 450ms：只挡误触双击，正确性已由语义匹配保证。









## 2026-11-04 · v3.15.9｜修收藏彻底失效（英文按钮）+ 右上角 App 图标不显示

**起因**：用户反馈「现在不能取消收藏也不能添加收藏了，网易云和 QQ 音乐都不行，右上角图标一直没出现」。

### 一、收藏彻底失效：只认中文按钮，网易云按钮是英文

真机日志（18:03~18:05）直接抓到了原因：

```
liveExpand: favorite request target=true gen=33 busy=false
liveExpand: favorite skipped (no like action)      ← 每一次都是这句
```

再看通知按钮的取证输出，两家标题完全不同：

```
(网易云) notif action[0] title='like'  [1] 'pre' [2] 'toggle' [3] 'next' [4] 'lyric'
(QQ音乐) notif action[0] title='收藏'  [1] '上一首' [2] '暂停' [3] '下一首' [4] '显示桌面歌词'
```

v3.15.8 的 `favoriteActionFor()` 只匹配 `喜欢/收藏` 中文词，网易云按钮标题固定是
`like`（不是 `已喜欢`），必然全部落空；同时候选通知被 `currentPkg` 死绑，
歌词通知归属与正在播放的 App 不一致时，连 QQ 音乐也会被整组过滤掉 → 两家都点不动。

修复：

- 新增 `favTitleState()`：中文（收藏/已收藏/取消收藏）+ 英文
  （`like`/`liked`/`favorite`/`favourited`/`unlike`/`collect`…）统一解析，
  返回三态：`true` 已收藏 / `false` 未收藏 / `null` 语义不可知。
- 候选范围放开：所有受支持 App 的通知，按「标题匹配当前曲 → currentPkg → 其余」排序，
  不再一刀切过滤；命中其它包时打日志便于取证。
- `inspectFavoriteActions()` 同步改用同一套解析，且 `state == null` 时**不再清掉已知状态**
  （否则网易云一开面板 ❤ 就变「未定」）。

### 二、网易云 `like` 是纯 toggle —— 禁止「校验失败就重发」

`like` 标题恒定，无法从按钮判断方向，只能按 toggle 直接发送。这种情况若沿用
「900ms 未生效 → 重发一次」的逻辑，第二次点击等于把刚做的收藏撤销，比失效更糟。

- 新增 `lastSentSemanticKnown`：发送时记录按钮是否带明确语义。
- 语义不可知（网易云）时**只发一次**：900ms → 700ms → 再宽限 1200ms，
  仍未生效就以 metadata 真值收尾，绝不重发。
- QQ 音乐（标题会翻转，语义明确）保留原有「最多重发 1 次」。

### 三、右上角 App 图标一直不显示：包可见性

`applyAppIcon()` 用 `PackageManager.getApplicationIcon(pkg)` 取图标，失败就 `GONE`。
模块 targetSdk 36，但 AndroidManifest 里没有 `<queries>` 声明 —— 面板跑在模块自身进程
（不是 SystemUI），受包可见性限制，`getApplicationIcon("com.tencent.qqmusic")`
直接抛 `NameNotFoundException`，图标被 catch 成隐藏，所以「从来没出现过」。

- Manifest 增加 `<queries>`：`com.tencent.qqmusic` + `com.netease.cloudmusic`
  （已用 aapt2 dump 确认进 APK）。
- `applyAppIcon()` 捕获异常时改为打日志（`FlymeArkPanel: app icon shown/failed pkg=...`），
  下次可以直接从 logcat 判断是可见性问题还是资源问题。

### 验证要点

- 网易云播歌 → 点 ❤ → 日志应出现 `fav button from com.netease.cloudmusic (currentPkg=...)`
  → `favorite action sent target=...`（不再有 `no like action`）
- QQ 音乐同样操作，`fav verified -> true/false` 出现即为闭环成功
- 打开面板右上角应显示当前播放 App 图标（QQ 音乐红色 / 网易云红色音符）

## 2026-11-04 · v3.15.10｜真凶是 activeNotifications 拿不到：收藏改用 hook 缓存兜底

**起因**：装机实测 v3.15.9 后，收藏仍然每次都是 `favorite skipped (no like action)`。

### 取证

- v3.15.9 的中英文解析与候选放宽都没问题，但 `NotificationManager.activeNotifications`
  在本机 SystemUI 进程里**拿不到通知列表**（v3.15.8 起按钮来源从 hook 缓存换成实时扫描，
  这条路径在本机根本走不通，所以两家一起失效）。加了 `activeNotifications size=` 日志取证。
- 之前能用的版本靠的是 `inspectFavoriteActions()` 从 hook 拦截到的播放通知里缓存的按钮
  —— 通知每次进度刷新都会重走这里，缓存是新鲜的。

### 修复

- `favoriteActionFor()` 改两级：先试 `activeNotifications`（有的系统可用），
  拿不到就回落 hook 缓存的 `(favIntent, favCachedState, favCachedSong)`；
  缓存按钮若属于**上一首**（标题对不上当前曲）则拒绝使用，防止改错歌。
- `inspectFavoriteActions()` 顺手缓存按钮语义与所属曲目。
- 语义等待上限 3s → 5s（真机实测 QQ 音乐通知重建约 2.7s）。

### 装机实测（真机广播直发，双向闭环均通过）

```
18:13:52 favorite action sent (target=false gen=7 attempt=1)
18:13:52 fav from COLLECT_STATE=0 -> false
18:13:52 fav verified -> false          ← 取消收藏成功
18:14:07 favorite action sent (target=true gen=8 attempt=1)
18:14:08 fav from COLLECT_STATE=1 -> true
18:14:08 fav verified -> true           ← 再收藏成功（已恢复原状态）
```

滞后场景也按设计工作：缓存按钮语义过期时先等通知重建（`fav action stale (btn=false
target=false), wait 1250ms`），刷新后（title=true）立即发送并校验通过。

右上角图标：`FlymeArkPanel: app icon shown pkg=com.tencent.qqmusic`（queries 声明生效）。

- versionCode 31 / 3.15.10

## 2026-11-04 · v3.15.11｜实况开关按「当前播放 App」判断（修网易云关不掉）

**起因**：用户反馈「设置里关掉网易云的实况通知，网易云依旧出现实况；QQ 音乐可以控制」。

### 根因一：`any {}` 把两个开关混在一起

`ArkLiveLyricHook` 的门控是这么写的：

```kotlin
val allowed = ArkConstants.MEDIA_APP_PKGS.any { LiveAllowGate.isAllowed(ctx, it) }
```

语义变成「**只要有一个 App 开着就放行**」。用户关掉网易云时 QQ 音乐的开关仍然是开的，
`any` 依旧是 true，所以网易云的胶囊照样出现 —— 开关看起来完全没生效。

现在改成按**当前正在出歌词的 App** 判断：

```kotlin
val cur = ArkLiveExpandHook.currentPkg          // 由歌词通知归属实时更新
val allowed = if (cur != null && isMediaApp(cur)) LiveAllowGate.isAllowed(ctx, cur)
              else MEDIA_APP_PKGS.any { ... }   // 未知归属时才退回任一放行
```

### 根因二：system_server 的「强制放行」把开关架空了

`ArkLiveAllowHook` 会 hook `PreferencesHelper.getNotificationLiveAllowForPackage`，
对两个播放器**一律返回 true**。只要用户在 LSPosed 里勾了「系统框架」，
设置里的开关就彻底失效（写进去的 false 被 hook 覆盖）。

胶囊出现在渲染侧已由 `LiveAllowGate` 按真值决定，这个强制放行不再需要，
v3.15.11 起**停用**（`install()` 只留一行日志），`allowLive` 恢复为用户真实选择。

### 真机验证

```
liveLyric: lyrics from com.tencent.qqmusic -> '今夜茉莉要开'
[FlymeArk/liveAllow] allowLive(com.tencent.qqmusic) = true
liveLyric: mIsMediaShow forced true
```

日志里现在**只查当前播放 App 一个包**（旧版会连网易云一起查），开关语义正确。

### 顺带：连点收藏实测通过（v3.15.10 的串行泵）

连续 5 次交替 `false→true→false→true→false`：

```
favorite action sent (target=false gen=26 attempt=1)
favorite request target=true  gen=27 busy=true     ← 在途，只记目标
favorite request target=false gen=28 busy=true
favorite request target=true  gen=29 busy=true
fav verify stale (gen=26 now=29)                   ← 旧校验作废
favorite request target=false gen=30 busy=true
fav already=false, skip send                       ← 幂等短路
```

最终状态 = 最后一次点击的目标，没有多翻一次。

- versionCode 32 / 3.15.11

## 2026-11-04 · v3.15.12｜模块描述与开关行为对齐

开关现在真正生效了（v3.15.11），描述里那句「无需在系统设置里额外开启实况通知」就不再成立 ——
不打开开关的话 allowLive=false，胶囊不会出现。改为：

> 一个针对魅族实况通知优化插件模块。在 LSPosed 中启用并勾选 系统界面 / QQ音乐 / 网易云音乐，
> 然后到 设置-通知与状态栏-实况通知 里打开对应 App 的开关（两个 App 的开关互相独立）

- LSPosed 管理器里的模块信息是缓存的，重新打开管理器或下拉刷新才会看到新描述。
- versionCode 33 / 3.15.12

## 2026-11-04 · v3.15.13｜实况开关判定加时长防抖，修「点收藏概率导致胶囊丢失」

**起因**：用户反馈「在音乐端点收藏/取消，会概率触发胶囊重绘导致胶囊丢失」。

### 取证（真机日志两次复现，同一现象）

```
18:22:11.563 allowLive(com.netease.cloudmusic) = true        ← 正常
18:22:17.510 raw=false uid=10213 ...                          ← 瞬时抖动：false
18:22:17.511 allowLive(com.netease.cloudmusic) = false
18:22:17.511 liveLyric: switch OFF -> capsule released       ← 胶囊被释放！
18:22:21.679 raw=true  ...
18:22:21.680 liveLyric: mIsMediaShow forced true             ← 4 秒后才重建
```

18:05:21（QQ 音乐）同样出现 `raw=false → 1.6s 后 raw=true`。

### 根因

在音乐端点收藏/取消，会触发**播放通知重建**；此时 `getNotificationLiveAllowForPackage`
在 system_server 侧会短暂返回一次 `false`（实测 1.6s~4.2s 后自己恢复 true），
而用户的实况开关根本没动。旧门控逻辑把这次瞬时 false 当成「用户关了开关」，
立即执行 `switch OFF -> capsule released` 释放胶囊 —— 这就是「点收藏概率导致胶囊丢失」。

### 修复（LiveAllowGate 时长防抖）

- 关闭判定从「读到 false 就关」改为**时长防抖**：从第一次读到 false 起计时，
  **持续超过 6 秒仍为 false 才认定真关闭**（覆盖实测最大 4.2s 抖动窗口）。
- 抖动窗口内仍按「开着」返回，日志打 `false hold xxxms/6000ms`，胶囊不释放。
- 防抖计时按**包名隔离**（`offHoldPkg`），避免网易云/QQ音乐切换时共用计时互相污染。
- 读到 true 立即清零待确认关闭计时。

用户真实关开关是持久性 false，6 秒后仍会正确释放胶囊，不影响「关开关 → 胶囊消失」的正常行为。

- versionCode 34 / 3.15.13

## 2026-11-04 · v3.15.14｜修网易云收藏失效（like 误判）+ 胶囊释放改延迟复核 + 关闭延迟降到 3s

### 一、网易云收藏失效：`like` 被误判成「未收藏」

真机日志（18:30）锁定根因：

```
favorite request target=false gen=5 busy=false
fav action stale (btn=false target=false), wait 0ms   ← 卡死
fav action stale (btn=false target=false), wait 250ms
... 一路 wait 到 4750ms 超时，从未真正 send ...
fav map flipped (truth=false/true 反复横跳)          ← 连锁污染
```

`favTitleState("like")` 之前把英文泛指词 `like` 归为「未收藏」返回 `false`，
于是 `sendFavAction` 判定「按钮已是目标状态」进入语义等待死循环，永远不发送。
网易云的 `like` 是**纯 toggle**（标题恒定、切歌不翻转），方向不可判断，必须返回 `null`。

修复：`favTitleState` 只对**中文状态词**（收藏/已收藏，会翻转）返回 true/false，
英文泛指 `like/favorite/collect` 一律返回 null → 走「直接 send + metadata 真值校验」的
toggle 路径（`lastSentSemanticKnown=false`，只发一次不重发，重发=撤销）。

### 二、胶囊释放改延迟复核（上一版 6s 时长防抖未拦住抖动）

真机日志（18:28，pid 31360 = 3.15.13）显示 `raw=false → 2.3s 后 switch OFF`，
`offSince` 状态机在真实调用序列下失效，防抖没生效，胶囊仍被误释放。

放弃 `LiveAllowGate` 内的 offSince 状态机，改为在 `ArkLiveLyricHook` 层做**延迟复核**：

- 读到 false 不立即释放，记 `pendingOffPkg` + `postDelayed(3s)`；
- 3s 后重新查开关，仍 false 才真正 `switch OFF`；
- 期间任何一次读到 true 就取消待关闭任务。

逻辑与「开关真值」解耦、可验证，瞬时抖动（1.6~4.2s）不会再误释放。

### 三、关闭延迟 6s → 3s

用户反馈 6s 太久。3s 既能压过实测 1.6s 抖动（4.2s 那次是罕见长抖，3s 复核时若仍 false
会正确释放，若已恢复 true 则取消），又让「真关开关 → 胶囊消失」更快响应。

- versionCode 35 / 3.15.14

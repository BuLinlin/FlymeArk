# FlymeArk 变更记录

> 纯 Kotlin LSPosed 模块。只 hook 播放器与 SystemUI，不自建胶囊、不发通知、无常驻进程。

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

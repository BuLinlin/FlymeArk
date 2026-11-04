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

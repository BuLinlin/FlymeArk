package com.flymeark

/**
 * v3.15.44：IslandTokens 在 Kotlin 侧的**唯一镜像**。
 *
 * 唯一令牌源是
 * `C:/Users/1/WorkBuddy/2026-11-05-14-04-34/IslandCapsuleUI/compose/IslandTokens.kt`，
 * View 版资源是同工程的 `view/res/values/island_tokens.xml` /
 * `view/res/values-night/island_tokens.xml`。本工程按「只迁移视觉 token」的原则，
 * 只把数值搬到这里（Kotlin 用）与 `res/values/colors.xml` +
 * `res/values-night/colors.xml`（XML 用），**不引入 Compose、不并入设计稿任何代码**。
 *
 * 与设计稿自身约定一致：三处一一对应，改色必须同步；组件内禁止再写魔法数字。
 * 本对象只放常量，不做任何采样、计算或运行时探测，避免产生第二套配色逻辑。
 */
internal object ArkTokens {

    // ------------------------------------------------------------------
    // 玻璃：82% / 86% 常态，96% 降级（系统禁用模糊或高对比文字）
    // ------------------------------------------------------------------

    const val GLASS_TOP_LIGHT = 0xD1FDFEFF.toInt()
    const val GLASS_BOTTOM_LIGHT = 0xD1EFF3F8.toInt()
    const val GLASS_TOP_DARK = 0xDB2A303B.toInt()
    const val GLASS_BOTTOM_DARK = 0xDB1B2029.toInt()

    const val GLASS_TOP_LIGHT_OPAQUE = 0xF5FDFEFF.toInt()
    const val GLASS_BOTTOM_LIGHT_OPAQUE = 0xF5EFF3F8.toInt()
    const val GLASS_TOP_DARK_OPAQUE = 0xF52A303B.toInt()
    const val GLASS_BOTTOM_DARK_OPAQUE = 0xF51B2029.toInt()

    /** 不透明档位（ARGB 的 alpha 字节）：浅 82% / 深 86% / 降级 96% */
    const val ALPHA_GLASS_LIGHT = 0xD1
    const val ALPHA_GLASS_DARK = 0xDB
    const val ALPHA_FALLBACK = 0xF5

    /**
     * 面板窗口是否具备跨窗口模糊。
     *
     * 本模块两张大卡是透明窗口，v3.15.42 已实测**禁用**窗口级 blurBehind（会糊整屏），
     * 所以恒为 false → 直接走 96% 降级基底，与 v3.15.43「基底必须够厚」的修复一致。
     * 若将来真的接入 Window.setBackgroundBlurRadius，把它置 true 即切回 82%/86%，
     * 不需要新增采样或配色表。
     */
    const val WINDOW_BLUR_AVAILABLE = false

    /** 玻璃渐变端点（不含 alpha），供降级渐变与 AGSL 底纹复用 */
    const val GLASS_TOP_RGB_LIGHT = 0xFDFEFF
    const val GLASS_BOTTOM_RGB_LIGHT = 0xEFF3F8
    const val GLASS_TOP_RGB_DARK = 0x2A303B
    const val GLASS_BOTTOM_RGB_DARK = 0x1B2029

    /** ⓪ 中性基底：取渐变下端色的不透明版本，承担文字对比度 */
    const val NEUTRAL_LIGHT = 0xFFEFF3F8.toInt()
    const val NEUTRAL_DARK = 0xFF1B2029.toInt()

    /** 1dp 高光描边（View 版简化值；Compose 版是顶部白渐隐） */
    const val HIGHLIGHT_LIGHT = 0x66FFFFFF
    const val HIGHLIGHT_DARK = 0x30FFFFFF

    // ------------------------------------------------------------------
    // 文字三级 / 品牌 / 轨道 / 分割线
    // ------------------------------------------------------------------

    const val TEXT_PRIMARY_LIGHT = 0xFF161A22.toInt()
    const val TEXT_SECONDARY_LIGHT = 0xFF4E5766.toInt()
    const val TEXT_TERTIARY_LIGHT = 0xFF5B6472.toInt()
    const val TEXT_PRIMARY_DARK = 0xFFF4F6FA.toInt()
    const val TEXT_SECONDARY_DARK = 0xFFC3CAD6.toInt()
    const val TEXT_TERTIARY_DARK = 0xFFAAB3C2.toInt()

    const val BRAND_LIGHT = 0xFF2F6BFF.toInt()
    const val BRAND_DARK = 0xFF8FB0FF.toInt()
    const val BRAND_ON_LIGHT = 0xFFFFFFFF.toInt()
    const val BRAND_ON_DARK = 0xFF0B1730.toInt()

    // v3.15.46：品牌容器色（设计稿 Light.BrandContainer / Dark.BrandContainer）。
    // 本体只作设计稿镜像（XML 侧对应 @color/ark_brand_container），CTA 用的是下面 60% 档。
    const val BRAND_CONTAINER_LIGHT = 0xFFE7EEFF.toInt()
    const val BRAND_CONTAINER_DARK = 0xFF23304C.toInt()

    /**
     * v3.15.46：主 CTA 底板 = brandContainer @60%
     * （设计稿 `NotificationCapsule`: `c.brandContainer.copy(alpha = 0.6f)`）。
     * 只作用于本模块自己的面板，不改变品牌容器色本身。
     */
    const val BRAND_CONTAINER_FILL_LIGHT = 0x99E7EEFF.toInt()
    const val BRAND_CONTAINER_FILL_DARK = 0x9923304C.toInt()

    /**
     * v3.15.46：封面 1dp 描边（设计稿 island_cover_frame.xml 的 #80FFFFFF，亮暗同值）。
     * 实际落地在 `@color/ark_cover_stroke`（XML drawable 用），这里只作 Kotlin 侧镜像。
     */
    const val COVER_STROKE = 0x80FFFFFF

    const val TRACK_LIGHT = 0xFFD9DEE7.toInt()
    const val TRACK_DARK = 0xFF454C58.toInt()

    /** v3.15.46：进度条「未播放段」中性色（设计稿规范原文 #E9ECF2） */
    const val TRACK_NEUTRAL_LIGHT = 0xFFE9ECF2.toInt()
    const val TRACK_NEUTRAL_DARK = 0xFF454C58.toInt()

    // ------------------------------------------------------------------
    // 形状 / 排版 / 模糊（dp、sp、px）
    // ------------------------------------------------------------------

    const val RADIUS_CARD_DP = 28
    const val RADIUS_PILL_DP = 22
    const val RADIUS_COVER_DP = 14
    const val RADIUS_CHIP_DP = 10
    const val RADIUS_ICON_DP = 6
    const val MINI_HEIGHT_DP = 44
    const val STROKE_DP = 1
    const val ELEVATION_DP = 18
    const val MIN_TOUCH_DP = 48

    /** v3.15.46：播放主按钮的品牌色投影（04 规范「主按钮加品牌色投影」） */
    const val PLAY_SHADOW_DP = 6

    const val TYPE_TITLE_SP = 18
    const val TYPE_BODY_SP = 14
    const val TYPE_CAPTION_SP = 12

    /** v3.15.46：设计稿 TypeTitle = 16sp（通知卡群名 / 发送者标题） */
    const val TYPE_TITLE_GROUP_SP = 16

    /** v3.15.46：进度条（设计稿 02：4dp 轨道 / 13dp 拖拽圆点 + 3px 白环） */
    const val PROGRESS_HEIGHT_DP = 4
    const val PROGRESS_THUMB_DP = 13

    /** 拖拽圆点白环宽度：设计稿写 3px，按 3x 屏折算 1dp */
    const val PROGRESS_THUMB_RING_DP = 1

    const val BLUR_RADIUS_PX = 80
    const val BLUR_MAX_PX = 150

    // ------------------------------------------------------------------
    // v3.15.47：实况迷你条（通知 header）结构件
    //
    // 尺寸全部由设计稿 `01 · 迷你条（微信）` 等比量取后按 dp 落位：
    // 设计稿里图标 38px / 徽标 15px / 箭头圆底 42px，比值为 1 : 0.39 : 1.10，
    // 以 40dp 图标为基准折算即 16dp / 44dp，与 4dp 栅格相容。
    // ------------------------------------------------------------------

    /** 迷你条左侧应用图标：40dp + [RADIUS_CHIP_DP] 10dp 圆角 */
    const val MINI_ICON_DP = 40

    /** 图标 → 标题间距：4dp 栅格 S3 */
    const val MINI_ICON_GAP_DP = 12

    /**
     * 右侧展开箭头圆底直径。
     *
     * v3.15.47 曾取 44dp（= 原生空心圆实测尺寸）；v3.15.48 玻璃收成内嵌胶囊后
     * 胶囊高度只有 52 - 2×4 = 44dp，44dp 圆会顶满整高，按设计稿「箭头 42px / 条高 54px = 0.78」
     * 等比折算改成 34dp（44 × 0.78 ≈ 34）。
     * 触控由整行承担（header 高 52dp > [MIN_TOUCH_DP]），不靠这个圆。
     */
    const val MINI_ARROW_DP = 34

    /** 未读徽标直径：设计稿 15px / 图标 38px → 40dp 图标折算 15.8dp */
    const val BADGE_SIZE_DP = 16

    /**
     * v3.15.50：通知卡时间胶囊底色 = 二级文字色 `#4E5766` 的 12% alpha（设计稿
     * `rgba(78,87,102,0.12)` → ARGB `0x1F4E5766`）。已知边界：深色玻璃上这层 12%
     * 叠层几乎看不出，本轮按设计稿单值落地，未造深色镜像色。
     */
    const val TIME_CHIP_FILL = 0x1F4E5766

    /** 徽标红色：设计稿 #C62F3B，白字对比度 5.4:1（WCAG AA 通过） */
    const val DANGER = 0xFFC62F3B.toInt()

    /**
     * 箭头圆底中性色：设计稿 #F2F4F7。
     * 深色无设计稿值，取白 14% 的中性叠层，不做反色。
     */
    const val TOUCH_SOFT_LIGHT = 0xFFF2F4F7.toInt()
    const val TOUCH_SOFT_DARK = 0x24FFFFFF
}

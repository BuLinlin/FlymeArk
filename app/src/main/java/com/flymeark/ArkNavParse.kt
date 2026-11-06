package com.flymeark

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * v3.15.61：导航展开卡用到的**纯解析函数**（中文数字 / 数值格式化 / 时刻）。
 *
 * ## 为什么要单独一个文件
 *
 * 这三个函数是整条链路里唯一「不碰 Android、不碰 Xposed、不碰状态」的部分，
 * 抽出来之后可以用 `kotlinc` 单独编译、直接跑样例表自测（见
 * `docs/live-nav-research.md` §7.3），不必装机就能发现解析错。
 * 生产代码与自测跑的是**同一份源码**，不存在「测试副本和实现跑偏」的问题。
 *
 * 另外两个纯函数（[ArkNavLiveInjector.splitInstruction] / `turnCodeOf`）依赖
 * `ArkConstants` 的转向码常量，留在原处。
 */
internal object ArkNavParse {

    /** 中文数字字面表（`零` / `〇` 只是占位，导航播报里不会出现 `零五` 这种读法） */
    private val CN_DIGITS: Map<Char, Int> = mapOf(
        '零' to 0, '〇' to 0,
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    /** 时刻前的时段词，按「先长后短」的顺序匹配，避免 `下午` 被 `午` 抢先 */
    private val ETA_PERIODS = listOf("凌晨", "早上", "上午", "中午", "下午", "傍晚", "晚上")

    /** 中文 / 阿拉伯数字字符类（正则用），含 `十百千万` 与 `两` */
    private const val NUM_CLASS = "(\\d+(?:\\.\\d+)?|[零〇一二三四五六七八九十百千万两]+)"

    /** `中午十二点十九分` 这类时刻（也吃 `12点19` / `九点五` / `三点半` / `十二点`） */
    private val CLOCK_RE = Regex("$NUM_CLASS\\s*[点时](?:$NUM_CLASS\\s*分?)?")

    /**
     * 中文数字 → Double（纯函数）。
     *
     * 支持阿拉伯数字原样返回、中文口语整数（含 `十百千万` 位权）、
     * 以及 `三点三` 这种口语小数（`点` / `.` 都当小数点）。
     * 解析不出来返回 null（调用方据此**保留旧值**，不要写成 0）。
     *
     * ## 验收样例（工单原文，逐条对应）
     *
     * | 输入 | 输出 |
     * |---|---|
     * | `八` | `8.0` |
     * | `十` | `10.0` |
     * | `十二` | `12.0` |
     * | `二十七` | `27.0` |
     * | `一百三十三` | `133.0` |
     * | `二十八` | `28.0` |
     * | `三点三` | `3.3` |
     * | `两` | `2.0` |
     * | `133` / `3.3` | 阿拉伯数字原样 |
     *
     * @param raw 待解析串（可为 null / 空）
     * @return 数值；无法解析返回 null
     */
    fun parseCnNumber(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()

        // ① 阿拉伯数字（含小数）直接交给标准库
        s.toDoubleOrNull()?.let { return it }

        var section = 0.0 // 当前节的千位以内累加
        var total = 0.0   // 已经结转的整段（`万` 以上）
        var num = 0.0     // 还没被位权吃掉的数字
        var seen = false
        var fracDigits: StringBuilder? = null

        for (ch in s) {
            if (ch == '点' || ch == '.') {
                if (!seen) return null
                fracDigits = StringBuilder()
                continue
            }
            val digit = CN_DIGITS[ch]
            val frac = fracDigits
            if (frac != null) {
                // 小数部分只认单个数字（`三点三` / `三点三三`）
                if (digit == null) return null
                frac.append(digit)
                continue
            }
            when {
                ch.isWhitespace() -> Unit
                digit != null -> {
                    num = digit.toDouble()
                    seen = true
                }
                ch == '十' -> {
                    if (num == 0.0) num = 1.0
                    section += num * 10
                    num = 0.0
                    seen = true
                }
                ch == '百' -> {
                    if (num == 0.0) num = 1.0
                    section += num * 100
                    num = 0.0
                    seen = true
                }
                ch == '千' -> {
                    if (num == 0.0) num = 1.0
                    section += num * 1000
                    num = 0.0
                    seen = true
                }
                ch == '万' -> {
                    total += (section + num) * 10_000
                    section = 0.0
                    num = 0.0
                    seen = true
                }
                else -> return null
            }
        }
        if (!seen) return null
        val integral = total + section + num
        val fracPart = fracDigits?.toString().orEmpty()
        if (fracPart.isEmpty()) return integral
        val f = ("0." + fracPart).toDoubleOrNull() ?: 0.0
        return integral + f
    }

    /**
     * 数值 → 展示用字符串（整数不带小数点，小数最多 1 位）。
     * `8.0` → `"8"`、`3.3` → `"3.3"`、`133.0` → `"133"`；非正数 / 非有限 → `""`。
     */
    fun fmtNum(v: Double): String {
        if (v.isNaN() || v.isInfinite() || v <= 0.0) return ""
        val rounded = (v * 10.0).roundToLong() / 10.0
        return if (abs(rounded - floor(rounded)) < 1e-9) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
    }

    /**
     * `预计X到达` 里的 X → `HH:mm`（纯函数）。
     *
     * 支持中文 / 阿拉伯两种写法与常见时段词：
     * `中午十二点十九分` → `12:19`、`下午三点` → `15:00`、`九点五` → `09:05`、
     * `三点半` → `03:30`。口语里 `分` 常省略，所以分钟段不强制带 `分`。
     *
     * @return `HH:mm`；不是合法时刻返回 null
     */
    fun parseClock(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        var period = ""
        for (p in ETA_PERIODS) {
            if (s.contains(p)) {
                period = p
                break
            }
        }
        val m = CLOCK_RE.find(s) ?: return null
        var hour = parseCnNumber(m.groupValues[1])?.toInt() ?: return null
        val minute = when {
            m.groupValues[2].isNotBlank() -> parseCnNumber(m.groupValues[2])?.toInt() ?: 0
            s.contains("点半") -> 30
            else -> 0
        }
        if (hour !in 0..24 || minute !in 0..59) return null

        val isPm = period == "下午" || period == "傍晚" || period == "晚上"
        val isNoon = period == "中午"
        // 中午 / 下午 / 晚上 说「十二点」是 12:00，说「三点」是 15:00
        if ((isPm || isNoon) && hour in 1..11) hour += 12
        // 凌晨 / 早上 / 上午 说「十二点」是 00:00
        if (hour == 12 && (period == "凌晨" || period == "早上" || period == "上午")) hour = 0
        if (hour == 24) hour = 0
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }
}

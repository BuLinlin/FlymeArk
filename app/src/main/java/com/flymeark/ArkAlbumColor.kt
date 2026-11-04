package com.flymeark

import android.graphics.Bitmap
import android.graphics.Color

/**
 * v3.12：专辑封面取色（播放面板进度条用）。
 *
 * 刻意不引入 androidx.palette —— 本模块除 LSPosed（compileOnly）外零依赖，
 * 且面板只需要「一个在深色底上够亮的主题色」，48px 量化 + 饱和度加权即可：
 *
 *  1. 缩到 ≤48px（取色精度不需要更多像素）；
 *  2. 按 RGB 各 4bit 量化分桶，灰像素（饱和度 < 0.15）直接丢弃；
 *  3. 桶得分 = 像素数 × (0.35 + 平均饱和度) × 亮度适配度，
 *     亮度适配度压制过暗/过亮的桶（两者在 #F018181C 上都发闷）；
 *  4. 取最优桶的平均色，再到 HSV 域整形：S ≥ 0.55、V ∈ [0.60, 0.78]。
 *
 * 返回 null 的情况（封面缺失 / 全灰 / 已回收）由调用方保持原色。
 */
object ArkAlbumColor {

    private const val SAMPLE = 48

    /** 封面主题色；null = 取不出（调用方保持当前颜色） */
    fun vibrantOf(src: Bitmap?): Int? {
        if (src == null || src.isRecycled || src.width <= 0 || src.height <= 0) return null
        val bmp = downscale(src)

        val buckets = HashMap<Int, Bucket>(64)
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val c = bmp.getPixel(x, y)
                if (Color.alpha(c) < 0x80) continue
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val max = maxOf(r, g, b)
                if (max == 0) continue
                val sat = (max - minOf(r, g, b)) / max.toFloat()
                if (sat < 0.15f) continue
                val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                val bk = buckets.getOrPut(key) { Bucket() }
                bk.count++
                bk.sumR += r
                bk.sumG += g
                bk.sumB += b
                bk.sumSat += sat
                bk.sumLuma += luma
            }
        }
        if (buckets.isEmpty()) return null

        var best: Bucket? = null
        var bestScore = -1f
        for (bk in buckets.values) {
            val n = bk.count.toFloat()
            val avgSat = bk.sumSat / n
            val avgLuma = bk.sumLuma / n
            val lumaFit = (1f - Math.abs(avgLuma - 0.5f) * 1.7f).coerceAtLeast(0.15f)
            val score = n * (0.35f + avgSat) * lumaFit
            if (score > bestScore) {
                bestScore = score
                best = bk
            }
        }
        val bk = best ?: return null

        val r = (bk.sumR / bk.count).toInt()
        val g = (bk.sumG / bk.count).toInt()
        val b = (bk.sumB / bk.count).toInt()

        val hsv = FloatArray(3)
        Color.colorToHSV(Color.rgb(r, g, b), hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.55f)
        hsv[2] = hsv[2].coerceIn(0.60f, 0.78f)
        return Color.HSVToColor(hsv)
    }

    private fun downscale(src: Bitmap): Bitmap {
        val m = maxOf(src.width, src.height)
        if (m <= SAMPLE) return src
        val s = SAMPLE.toFloat() / m
        return try {
            Bitmap.createScaledBitmap(
                src,
                (src.width * s + 0.5f).toInt().coerceAtLeast(1),
                (src.height * s + 0.5f).toInt().coerceAtLeast(1),
                true
            )
        } catch (_: Throwable) {
            src
        }
    }

    private class Bucket {
        var count = 0
        var sumR = 0f
        var sumG = 0f
        var sumB = 0f
        var sumSat = 0f
        var sumLuma = 0f
    }
}

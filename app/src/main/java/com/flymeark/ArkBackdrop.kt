package com.flymeark

import android.graphics.Bitmap
import android.graphics.Bitmap.Config.ARGB_8888
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * v3.13：面板「音乐壁纸」背景 —— 专辑封面放大模糊 + 提饱和 + 噪点颗粒。
 *
 * 对标「点击胶囊展开 → 背景换成取色样式」的观感：
 *  - 源图取专辑封面（没有就用纯色兜底），缩到 128px 后做三次 box blur；
 *  - ColorMatrix 提饱和度（模糊会把颜色洗灰），并按深浅模式定标亮度；
 *  - CENTER_CROP 放大到屏幕尺寸；
 *  - 上下沿轻压暗（增强卡片层次），最后平铺噪点消色带。
 *
 * 全程一次性计算（onCreate / 换曲各一次），不在绘制路径上，无实时性负担。
 * 128px 上的三次 box blur 耗时在毫秒级，放大只是内存拷贝。
 */
object ArkBackdrop {

    /** 中间处理分辨率：够模糊、够快 */
    private const val SMALL = 128

    /**
     * 生成背景。
     *
     * @param cover 专辑封面；null 时退化为纯 [fallback] 色的模糊渐变
     * @param outW/outH 目标尺寸（屏幕 px）
     * @param night 深色模式：背景整体压暗，让白字卡片浮起；浅色模式轻微压亮
     * @return 合成后的背景，失败返回 null（调用方保持现有背景）
     */
    fun build(cover: Bitmap?, fallback: Int?, outW: Int, outH: Int, night: Boolean): Bitmap? {
        if (outW <= 0 || outH <= 0) return null
        return try {
            val small = source(cover, fallback, night) ?: return null
            val blurred = stackBlur(small, (SMALL / 10).coerceIn(4, 14))
            val tuned = tune(blurred, night)
            assemble(tuned, outW, outH, night)
        } catch (_: Throwable) {
            null
        }
    }

    /** 估算背景平均亮度（0..1），用于文字/控件颜色自适应 */
    fun averageLuma(bmp: Bitmap?): Float {
        if (bmp == null || bmp.isRecycled) return 0f
        return try {
            val step = max(1, min(bmp.width, bmp.height) / 16)
            var sum = 0f
            var n = 0
            var y = 0
            while (y < bmp.height) {
                var x = 0
                while (x < bmp.width) {
                    val c = bmp.getPixel(x, y)
                    sum += (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
                    n++
                    x += step
                }
                y += step
            }
            if (n == 0) 0f else sum / n
        } catch (_: Throwable) {
            0f
        }
    }

    // ------------------------------------------------------------------

    private fun source(cover: Bitmap?, fallback: Int?, night: Boolean): Bitmap? {
        if (cover != null && !cover.isRecycled && cover.width > 0 && cover.height > 0) {
            val m = max(cover.width, cover.height)
            val s = SMALL.toFloat() / m
            return Bitmap.createScaledBitmap(
                cover,
                (cover.width * s + 0.5f).toInt().coerceAtLeast(1),
                (cover.height * s + 0.5f).toInt().coerceAtLeast(1),
                true
            )
        }
        if (fallback == null) return null
        val b = Bitmap.createBitmap(SMALL, SMALL, ARGB_8888)
        b.eraseColor(fallback)
        return b
    }

    /** 三轮 box blur 近似高斯模糊（零依赖，128px 上毫秒级） */
    private fun stackBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(ARGB_8888, true)
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)
        val div = radius * 2 + 1
        val tmp = IntArray(w * h)
        repeat(2) {
            boxBlurH(pixels, tmp, w, h, div)
            boxBlurV(tmp, pixels, w, h, div)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, div: Int) {
        val half = div / 2
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (i in -half..half) {
                val c = src[row + min(w - 1, max(0, i))]
                sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c)
            }
            for (x in 0 until w) {
                dst[row + x] = Color.rgb(sr / div, sg / div, sb / div)
                val outIdx = row + min(w - 1, max(0, x - half))
                val inIdx = row + min(w - 1, max(0, x + half + 1))
                val outC = src[outIdx]; val inC = src[inIdx]
                sr += Color.red(inC) - Color.red(outC)
                sg += Color.green(inC) - Color.green(outC)
                sb += Color.blue(inC) - Color.blue(outC)
            }
        }
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, div: Int) {
        val half = div / 2
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -half..half) {
                val c = src[min(h - 1, max(0, i)) * w + x]
                sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c)
            }
            for (y in 0 until h) {
                dst[y * w + x] = Color.rgb(sr / div, sg / div, sb / div)
                val outC = src[min(h - 1, max(0, y - half)) * w + x]
                val inC = src[min(h - 1, max(0, y + half + 1)) * w + x]
                sr += Color.red(inC) - Color.red(outC)
                sg += Color.green(inC) - Color.green(outC)
                sb += Color.blue(inC) - Color.blue(outC)
            }
        }
    }

    /** 提饱和 + 亮度定标：模糊洗灰的色彩拉回来，深色模式整体压暗 */
    private fun tune(bmp: Bitmap, night: Boolean): Bitmap {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val cm = ColorMatrix().apply {
            setSaturation(1.45f)
            // 深色模式整体乘 0.72；浅色模式乘 0.96 保底不过曝
            val s = if (night) 0.72f else 0.96f
            postConcat(
                ColorMatrix(
                    floatArrayOf(s, 0f, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
                )
            )
        }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        return out
    }

    /** 放大铺满 + 上下沿轻压暗 + 噪点 */
    private fun assemble(small: Bitmap, outW: Int, outH: Int, night: Boolean): Bitmap {
        val out = Bitmap.createBitmap(outW, outH, ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        // CENTER_CROP
        val scale = max(outW.toFloat() / small.width, outH.toFloat() / small.height)
        val dw = small.width * scale
        val dh = small.height * scale
        canvas.drawBitmap(small, (outW - dw) / 2f, (outH - dh) / 2f, paint)

        // 上下沿轻压暗：给顶部卡片一个更稳的底
        val shade = if (night) 0x66000000.toInt() else 0x1A000000
        val edge = Paint().apply { color = shade }
        canvas.drawRect(0f, 0f, outW.toFloat(), outH * 0.10f, edge)
        canvas.drawRect(0f, outH * 0.92f, outW.toFloat(), outH.toFloat(), edge)

        // 噪点 tile：消色带 + 细颗粒
        drawNoise(canvas, outW, outH)

        return out
    }

    private fun drawNoise(canvas: Canvas, w: Int, h: Int) {
        val size = 96
        val noise = Bitmap.createBitmap(size, size, ARGB_8888)
        val rnd = Random(0x5EED)
        val px = IntArray(size * size)
        for (i in px.indices) {
            val v = rnd.nextInt(255)
            px[i] = Color.argb(rnd.nextInt(14), v, v, v)
        }
        noise.setPixels(px, 0, size, 0, 0, size, size)
        val paint = Paint().apply { alpha = 8 }
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                canvas.drawBitmap(noise, x.toFloat(), y.toFloat(), paint)
                x += size
            }
            y += size
        }
        noise.recycle()
    }

    /** 兜底：无封面时的纯色渐变背景 */
    fun solidBitmap(color: Int, outW: Int, outH: Int): Bitmap? = try {
        val b = Bitmap.createBitmap(outW, outH, ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply { setColor(color) }
        c.drawRect(RectF(0f, 0f, outW.toFloat(), outH.toFloat()), p)
        b
    } catch (_: Throwable) {
        null
    }
}

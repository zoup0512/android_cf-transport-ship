// 程序化噪声与画布辅助（零外部资源）
package com.cf.transportship.gfx

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** 确定性随机（mulberry32） */
class Rng(seedIn: Int) {
    private var a = seedIn
    fun next(): Float {
        a += 0x6D2B79F5.toInt()
        var t = a xor (a ushr 15)
        t *= (1 or a)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        val r = (t xor (t ushr 14)).toLong() and 0xFFFFFFFFL
        return (r / 4294967296.0).toFloat()
    }
    fun range(a: Float, b: Float) = a + next() * (b - a)
    fun nextInt(n: Int) = (next() * n).toInt()
}

/** 可平铺 fBm 值噪声，返回 0..1 */
fun fbm(w: Int, h: Int, fx: Int, fy: Int, octaves: Int, seed: Int, persistence: Float = 0.5f): FloatArray {
    val out = FloatArray(w * h)
    val rnd = Rng(seed)
    var amp = 1f
    var total = 0f
    for (o in 0 until octaves) {
        val gx = fx shl o
        val gy = fy shl o
        val lat = FloatArray(gx * gy)
        for (i in lat.indices) lat[i] = rnd.next()
        val sx = gx.toFloat() / w
        val sy = gy.toFloat() / h
        for (y in 0 until h) {
            val fyv = y * sy
            val y0 = floor(fyv).toInt()
            val ty = fyv - y0
            val tyS = ty * ty * (3 - 2 * ty)
            val r0 = (y0 % gy) * gx
            val r1 = ((y0 + 1) % gy) * gx
            for (x in 0 until w) {
                val fxv = x * sx
                val x0 = floor(fxv).toInt()
                val tx = fxv - x0
                val txS = tx * tx * (3 - 2 * tx)
                val c0 = x0 % gx
                val c1 = (x0 + 1) % gx
                val a = lat[r0 + c0]; val b = lat[r0 + c1]
                val c = lat[r1 + c0]; val d = lat[r1 + c1]
                val top = a + (b - a) * txS
                val bot = c + (d - c) * txS
                out[y * w + x] += amp * (top + (bot - top) * tyS)
            }
        }
        total += amp
        amp *= persistence
    }
    for (i in out.indices) out[i] /= total
    return out
}

/** 高度图 -> 法线贴图（OpenGL 约定，Y 翻转适配 Bitmap 自上而下） */
fun normalFromHeight(hgt: FloatArray, w: Int, h: Int, strength: Float): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val px = IntArray(w * h)
    for (y in 0 until h) {
        val ym = ((y - 1 + h) % h) * w
        val yp = ((y + 1) % h) * w
        val yr = y * w
        for (x in 0 until w) {
            val xm = (x - 1 + w) % w
            val xp = (x + 1) % w
            val dx = (hgt[yr + xp] - hgt[yr + xm]) * strength
            val dy = (hgt[yp + x] - hgt[ym + x]) * strength
            val nx = -dx; val ny = dy; val nz = 1f
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            val r = ((nx / l * 0.5f + 0.5f) * 255).toInt()
            val g = ((ny / l * 0.5f + 0.5f) * 255).toInt()
            val b = ((nz / l * 0.5f + 0.5f) * 255).toInt()
            px[yr + x] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }
    }
    bmp.setPixels(px, 0, w, 0, 0, w, h)
    return bmp
}

/** 画布封装：简化 Canvas/Paint 使用 */
class Ctx(val w: Int, val h: Int) {
    val bmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun fill(color: Int) {
        p.color = color
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }
    fun clear() { bmp.eraseColor(Color.TRANSPARENT) }
    fun rect(x: Float, y: Float, rw: Float, rh: Float, color: Int) {
        p.shader = null; p.color = color
        c.drawRect(x, y, x + rw, y + rh, p)
    }
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {
        p.shader = null; p.color = color; p.strokeWidth = width; p.style = Paint.Style.STROKE
        c.drawLine(x0, y0, x1, y1, p)
        p.style = Paint.Style.FILL
    }
    fun circle(x: Float, y: Float, r: Float, color: Int) {
        p.shader = null; p.color = color
        c.drawCircle(x, y, r, p)
    }
    fun radial(x: Float, y: Float, r: Float, stops: FloatArray, colors: IntArray) {
        p.shader = RadialGradient(x, y, r, colors, stops, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
        p.shader = null
    }
    /** 模糊阴影矩形（模拟 ctx.shadowBlur） */
    fun shadowRect(x: Float, y: Float, rw: Float, rh: Float, color: Int, blurPx: Float) {
        p.shader = null; p.color = color; p.style = Paint.Style.FILL
        if (blurPx > 0) {
            val old = p.maskFilter
            p.maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            c.drawRect(x, y, x + rw, y + rh, p)
            p.maskFilter = old
        } else c.drawRect(x, y, x + rw, y + rh, p)
    }
    fun vGrad(x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int): Paint {
        val pp = Paint(Paint.ANTI_ALIAS_FLAG)
        pp.shader = LinearGradient(x0, y0, x1, y1, c0, c1, Shader.TileMode.CLAMP)
        return pp
    }
    fun drawGrad(x: Float, y: Float, rw: Float, rh: Float, c0: Int, c1: Int, horizontal: Boolean = false) {
        val pp = if (horizontal) vGrad(x, y, x + rw, y, c0, c1) else vGrad(x, y, x, y + rh, c0, c1)
        c.drawRect(x, y, x + rw, y + rh, pp)
    }
    fun erase(fn: (Canvas, Paint) -> Unit) {
        val ep = Paint(Paint.ANTI_ALIAS_FLAG)
        ep.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        fn(c, ep)
    }
    /** 做旧文字：先绘制再随机擦除像素 */
    fun weatheredText(text: String, x: Float, y: Float, size: Float, color: Int, rnd: Rng, erode: Float = 0.25f, align: Paint.Align = Paint.Align.CENTER, bold: Boolean = true) {
        val tmp = Ctx(w, h)
        tmp.bmp.eraseColor(Color.TRANSPARENT)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.color = color
        tp.typeface = if (bold) Typeface.create("sans-serif-black", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.BOLD)
        tp.textSize = size
        tp.textAlign = align
        val fm = tp.fontMetrics
        val baseline = y - (fm.ascent + fm.descent) / 2f
        tmp.c.drawText(text, x, baseline, tp)
        tmp.erase { ec, ep ->
            val n = (900 * erode).toInt()
            for (i in 0 until n) {
                ep.color = Color.argb((0.3f + rnd.next() * 0.7f).times(255).toInt(), 0, 0, 0)
                val s = (1 + rnd.next() * 5) * (w / 1024f)
                ec.drawRect(rnd.next() * w, rnd.next() * h, rnd.next() * w + s * (1 + rnd.next() * 3), rnd.next() * h + s, ep)
            }
        }
        c.drawBitmap(tmp.bmp, 0f, 0f, null)
    }
    fun save() = c.save()
    fun restore() = c.restore()
    fun translate(x: Float, y: Float) = c.translate(x, y)
    fun rotate(deg: Float) = c.rotate(deg)
    fun px(): IntArray {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return px
    }
    fun setPx(px: IntArray) = bmp.setPixels(px, 0, w, 0, 0, w, h)
}

/** 噪声叠色（就地修改 ctx 位图颜色） */
inline fun paintNoise(ctx: Ctx, noise: FloatArray, fn: (Float, Int, Int, Int, Int) -> IntArray) {
    val px = ctx.px()
    for (i in px.indices) {
        val c = px[i]
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val out = fn(noise[i], r, g, b, i)
        px[i] = (c and 0xff000000.toInt()) or
            (out[0].coerceIn(0, 255) shl 16) or
            (out[1].coerceIn(0, 255) shl 8) or
            out[2].coerceIn(0, 255)
    }
    ctx.setPx(px)
}

/** 锈迹流挂 */
fun rustStreaks(ctx: Ctx, w: Int, h: Int, rnd: Rng, count: Int, fromY: Float, len: Float, alpha: Float, color: Int = 0xff6E3719u.toInt()) {
    val cr = Color.red(color); val cg = Color.green(color); val cb = Color.blue(color)
    for (i in 0 until count) {
        val x = rnd.next() * w
        val wd = 2 + rnd.next() * 10
        val L = len * (0.3f + rnd.next())
        val y0 = fromY + (rnd.next() - 0.5f) * 8
        val a = alpha * (0.3f + rnd.next() * 0.7f)
        val g = LinearGradient(0f, y0, 0f, y0 + L,
            Color.argb((a * 255).toInt(), cr, cg, cb), Color.argb(0, cr, cg, cb), Shader.TileMode.CLAMP)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = g
        ctx.c.drawRect(x, y0, x + wd, y0 + L, p)
    }
}

/** 随机污渍圆斑 */
fun blobs(ctx: Ctx, rnd: Rng, w: Int, h: Int, count: Int, rMin: Float, rMax: Float, color: Int, aMin: Float, aMax: Float) {
    val cr = Color.red(color); val cg = Color.green(color); val cb = Color.blue(color)
    for (i in 0 until count) {
        val x = rnd.next() * w; val y = rnd.next() * h
        val r = rMin + rnd.next() * (rMax - rMin)
        val a = aMin + rnd.next() * (aMax - aMin)
        ctx.radial(x, y, r, floatArrayOf(0f, 1f),
            intArrayOf(Color.argb((a * 255).toInt(), cr, cg, cb), Color.argb(0, cr, cg, cb)))
    }
}

fun rgbInt(r: Int, g: Int, b: Int) = -0x1000000 or (r shl 16) or (g shl 8) or b

// 程序化纹理：全部由 Canvas 生成（移植 textures.js）
package com.cf.transportship.gfx

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

data class TexTriple(val map: Bitmap, val normalMap: Bitmap?, val roughnessMap: Bitmap?)

class ContainerColor(val name: String, val rgb: IntArray, val text: String, val tc: Int)

val CONTAINER_COLORS = listOf(
    ContainerColor("blue", intArrayOf(38, 72, 118), "GLOBAL RISK", -0x1771e),
    ContainerColor("red", intArrayOf(132, 40, 32), "LENSVELD", -0x101520),
    ContainerColor("green", intArrayOf(52, 92, 60), "TRANSLINE", -0x17720),
    ContainerColor("rust", intArrayOf(128, 70, 42), "ORIENT CARGO", -0xf1928),
    ContainerColor("gray", intArrayOf(112, 118, 120), "NORDSEE", -0x1e2d5c),
    ContainerColor("teal", intArrayOf(36, 102, 104), "OCEAN STAR", -0xd0d16),
    ContainerColor("orange", intArrayOf(184, 96, 36), "KESTREL LINE", -0x20916),
    ContainerColor("cream", intArrayOf(176, 164, 132), "BLACK LIST", -0xd5d5d6),
)

// ---------- 甲板 ----------
private fun deckTextures(scl: Float): TexTriple {
    val S = (1024 * scl).toInt().coerceAtLeast(64)
    val rnd = Rng(11)
    val n1 = fbm(S, S, 4, 4, 5, 101)
    val n2 = fbm(S, S, 16, 16, 3, 102)
    val n3 = fbm(S, S, 8, 8, 4, 103)
    val ctx = Ctx(S, S)
    ctx.fill(rgbInt(66, 72, 72))
    paintNoise(ctx, n1) { v, r, g, b, i ->
        val k = 0.82f + v * 0.36f + (n2[i] - 0.5f) * 0.08f
        intArrayOf((r * k).toInt(), (g * k).toInt(), (b * k).toInt())
    }
    // 磨损的浅色人行轨迹（沿 X 方向）
    for (i in 0 until 260) {
        val y = rnd.next() * S; val x = rnd.next() * S; val L = (40 + rnd.next() * 260) * scl
        ctx.line(x, y, x + L, y, Color.argb((0.03f + rnd.next() * 0.06f).times(255).toInt(), 160, 165, 160), (1 + rnd.next() * 2) * scl)
    }
    // 锈斑与油污
    val rustMask = fbm(S, S, 6, 6, 5, 104)
    paintNoise(ctx, rustMask) { v, r, g, b, i ->
        val t = ((v - 0.66f).coerceAtLeast(0f) * 4f) * (0.5f + n2[i] * 0.6f)
        val tt = t.coerceAtMost(0.7f)
        intArrayOf((r + (92 - r) * tt).toInt(), (g + (58 - g) * tt).toInt(), (b + (40 - b) * tt).toInt())
    }
    blobs(ctx, rnd, S, S, 18, 20 * scl, 80 * scl, rgbInt(20, 20, 18), 0.08f, 0.25f)
    // 高度图：噪声 + 焊缝 + 防滑点 + 螺栓
    val hgt = FloatArray(S * S)
    for (i in hgt.indices) hgt[i] = n3[i] * 0.25f + n2[i] * 0.08f
    val seamHalf = (512 * scl).toInt()
    for (pp in intArrayOf(1, seamHalf + 1)) {
        if (pp < S) {
            ctx.line(pp.toFloat(), 0f, pp.toFloat(), S.toFloat(), Color.argb(204, 30, 32, 33), 3f * scl)
            ctx.line(pp + 2f, 2f, pp + 2f, S.toFloat(), Color.argb(89, 140, 140, 135), 1f * scl)
            ctx.line(0f, pp.toFloat(), S.toFloat(), pp.toFloat(), Color.argb(204, 30, 32, 33), 3f * scl)
            ctx.line(2f, pp + 2f, S.toFloat(), pp + 2f, Color.argb(89, 140, 140, 135), 1f * scl)
        }
    }
    for (y in 0 until S) for (x in 0 until S) {
        val dx = min(abs(x % seamHalf - 1f), seamHalf - abs(x % seamHalf - 1f))
        val dy = min(abs(y % seamHalf - 1f), seamHalf - abs(y % seamHalf - 1f))
        val dd = min(dx, dy)
        if (dd < 4 * scl) hgt[y * S + x] += if (dd < 2 * scl) 0.9f else 0.5f
    }
    val dots = (4000 * scl * scl).toInt().coerceAtLeast(500)
    for (i in 0 until dots) {
        val x = (rnd.next() * S).toInt(); val y = (rnd.next() * S).toInt()
        hgt[y * S + x] += 0.6f
    }
    // 螺栓（每块钢板四角）
    val bOff = 24 * scl; val bSpan = seamHalf - 48 * scl
    for (px in 0..1) for (py in 0..1) for (k in 0..3) {
        val bx = (px * seamHalf + bOff + (k % 2) * bSpan).toInt()
        val by = (py * seamHalf + bOff + (k / 2) * bSpan).toInt()
        ctx.circle(bx.toFloat(), by.toFloat(), 5 * scl, Color.argb(204, 40, 40, 40))
        for (yy in -6..6) for (xx in -6..6) {
            val r = hypot(xx.toDouble(), yy.toDouble())
            if (r < 6) {
                val iy = by + yy; val ix = bx + xx
                if (iy in 0 until S && ix in 0 until S) hgt[iy * S + ix] += (6 - r).toFloat() * 0.25f
            }
        }
    }
    val nrm = normalFromHeight(hgt, S, S, 2.2f)
    // 粗糙度图
    val rough = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
    val rpx = IntArray(S * S)
    for (i in rpx.indices) {
        val v = (0.62f + n1[i] * 0.3f + (rustMask[i] - 0.62f).coerceAtLeast(0f) * 0.5f).coerceIn(0f, 1f)
        val g = (v * 255).toInt()
        rpx[i] = -0x1000000 or (g shl 16) or (g shl 8) or g
    }
    rough.setPixels(rpx, 0, S, 0, 0, S, S)
    return TexTriple(ctx.bmp, nrm, rough)
}

// ---------- 集装箱 ----------
private fun corrugationHeight(W: Int, H: Int, ribs: Int, railFrac: Float, postFrac: Float): FloatArray {
    val hgt = FloatArray(W * H)
    val n = fbm(W, H, 8, 4, 3, 77)
    val railT = H * railFrac
    val postW = W * postFrac
    for (y in 0 until H) {
        for (x in 0 until W) {
            val v: Float
            if (y < railT || y > H - railT) v = 0.55f
            else if (x < postW || x > W - postW) v = 0.8f
            else {
                val t = ((x - postW) / (W - 2 * postW)) * ribs
                val f = t - floor(t)
                var vv = (if (f < 0.15f) f / 0.15f else if (f < 0.45f) 1f else if (f < 0.6f) 1 - (f - 0.45f) / 0.15f else 0f)
                vv = vv * 0.6f + 0.1f
                val e = min(y - railT, H - railT - y)
                if (e < 6) vv = vv * (e / 6f) + 0.55f * (1 - e / 6f)
                v = vv
            }
            hgt[y * W + x] = v + (n[y * W + x] - 0.5f) * 0.06f
        }
    }
    return hgt
}

private fun containerSide(color: ContainerColor, W: Int, H: Int, seed: Int, hgt: FloatArray): Bitmap {
    val rnd = Rng(seed)
    val ctx = Ctx(W, H)
    ctx.fill(rgbInt(color.rgb[0], color.rgb[1], color.rgb[2]))
    val n = fbm(W, H, 6, 3, 5, seed + 5)
    paintNoise(ctx, n) { v, rr, gg, bb, i ->
        val hv = hgt[i]
        val k = (0.8f + v * 0.35f) * (0.78f + hv * 0.3f)
        intArrayOf((rr * k).toInt(), (gg * k).toInt(), (bb * k).toInt())
    }
    val fs = H * 0.26f
    ctx.weatheredText(color.text, W / 2f, H * 0.5f, fs, color.tc, rnd, 0.35f)
    val codes = arrayOf("GRSU", "LNVU", "TRLU", "OCSU", "KSTU", "BLKU", "NDSU")
    val code = codes[seed % 7] + " " + (100000 + (rnd.next() * 899999).toInt())
    ctx.weatheredText(code, W - W * 0.05f, H * 0.16f, H * 0.06f, color.tc, rnd, 0.2f, Paint.Align.RIGHT)
    ctx.weatheredText(if (rnd.next() > 0.5f) "22G1" else "45G1", W - W * 0.05f, H * 0.25f, H * 0.05f, color.tc, rnd, 0.2f, Paint.Align.RIGHT)
    rustStreaks(ctx, W, H, rnd, 60, H * 0.07f, H * 0.5f, 0.35f)
    rustStreaks(ctx, W, H, rnd, 25, H * 0.5f, H * 0.35f, 0.25f)
    ctx.rect(0f, H * 0.92f, W.toFloat(), H * 0.08f, Color.argb(89, 70, 40, 20))
    blobs(ctx, rnd, W, H, 30, 4f, 30f, rgbInt(95, 50, 25), 0.2f, 0.55f)
    ctx.drawGrad(0f, H * 0.7f, W.toFloat(), H * 0.3f, Color.argb(0, 30, 25, 20), Color.argb(115, 30, 25, 20))
    for (i in 0 until 40) {
        val x = rnd.next() * W; val y = rnd.next() * H
        ctx.line(x, y, x + (rnd.next() - 0.5f) * 60, y + (rnd.next() - 0.5f) * 12,
            Color.argb((0.15f + rnd.next() * 0.3f).times(255).toInt(), 170, 165, 155), 1f)
    }
    return ctx.bmp
}

class DoorTex(val map: Bitmap, val hgt: FloatArray, val W: Int, val H: Int)

private fun containerDoor(color: ContainerColor, seed: Int, scl: Float): DoorTex {
    val W = (512 * scl).toInt().coerceAtLeast(32)
    val H = (544 * scl).toInt().coerceAtLeast(32)
    val rnd = Rng(seed)
    val ctx = Ctx(W, H)
    val hgt = FloatArray(W * H)
    val n = fbm(W, H, 4, 4, 4, seed)
    val postW = 26f * scl; val railT = 30f * scl
    for (y in 0 until H) for (x in 0 until W) {
        var v = 0.3f
        if (x < postW || x > W - postW || y < railT || y > H - railT) v = 0.8f
        else {
            val dx = if (x < W / 2) (x - postW) else (x - W / 2).toFloat()
            val t = (dx / (W / 2 - postW)) * 5
            val f = t - floor(t)
            v = 0.25f + (if (f < 0.2f) f / 0.2f else if (f < 0.5f) 1f else if (f < 0.7f) 1 - (f - 0.5f) / 0.2f else 0f) * 0.35f
            if (abs(x - W / 2) < 3) v = 0.05f
        }
        hgt[y * W + x] = v + (n[y * W + x] - 0.5f) * 0.05f
    }
    val rods = listOf(W * 0.14f, W * 0.36f, W * 0.64f, W * 0.86f)
    for (rx in rods) {
        val y0 = (railT - 10).toInt().coerceAtLeast(0)
        val y1 = (H - railT + 10).toInt().coerceAtMost(H)
        val x0 = (rx - 7).toInt().coerceIn(0, W - 1)
        val x1 = (rx + 7).toInt().coerceIn(0, W - 1)
        for (y in y0 until y1) for (x in x0..x1) {
            val d = abs(x - rx)
            hgt[y * W + x] = 1.2f - d * 0.06f
        }
    }
    ctx.fill(rgbInt(color.rgb[0], color.rgb[1], color.rgb[2]))
    paintNoise(ctx, n) { v, rr, gg, bb, i ->
        val k = (0.8f + v * 0.35f) * (0.75f + hgt[i] * 0.3f)
        intArrayOf((rr * k).toInt(), (gg * k).toInt(), (bb * k).toInt())
    }
    for (rx in rods) {
        ctx.rect(rx - 7, railT - 10, 14f, H - 2 * railT + 20f, Color.argb(89, 40, 40, 40))
        ctx.rect(rx - 4, H * 0.55f, 30f * (if (rx < W / 2) 1 else -1), 8f, Color.argb(178, 30, 30, 30))
        for (yy in listOf(railT + 30, H * 0.3f, H * 0.75f, H - railT - 30)) {
            ctx.rect(rx - 11, yy, 22f, 12f, Color.argb(153, 30, 30, 30))
        }
    }
    ctx.weatheredText(color.text, W * 0.25f, H * 0.2f, 34f * scl, color.tc, rnd, 0.3f)
    ctx.rect(W * 0.58f, H * 0.14f, 90 * scl, 60 * scl, Color.argb(204, 210, 205, 190))
    for (i in 0 until 5) ctx.rect(W * 0.58f + 8, H * 0.14f + 8 + i * 10 * scl, (60 + rnd.next() * 14) * scl, 4f, Color.argb(204, 50, 50, 50))
    rustStreaks(ctx, W, H, rnd, 30, railT, H * 0.5f, 0.35f)
    blobs(ctx, rnd, W, H, 14, 5 * scl, 22 * scl, rgbInt(95, 50, 25), 0.2f, 0.5f)
    return DoorTex(ctx.bmp, hgt, W, H)
}

private fun containerRoof(W: Int, H: Int, seed: Int): Pair<Bitmap, FloatArray> {
    val rnd = Rng(seed)
    val n = fbm(W, H, 8, 2, 5, seed)
    val hgt = FloatArray(W * H)
    for (y in 0 until H) for (x in 0 until W) {
        val t = (x.toFloat() / W) * 28
        val f = t - floor(t)
        var v = sin(f * Math.PI).toFloat() * 0.3f
        if (y < 10 || y > H - 10) v = 0.7f
        hgt[y * W + x] = v + n[y * W + x] * 0.1f
    }
    val ctx = Ctx(W, H)
    ctx.fill(rgbInt(200, 200, 200))
    paintNoise(ctx, n) { v, rr, gg, bb, _ ->
        val k = 0.7f + v * 0.35f
        intArrayOf((rr * k).toInt(), (gg * k).toInt(), (bb * k).toInt())
    }
    blobs(ctx, rnd, W, H, 40, 10f, 60f, rgbInt(120, 70, 35), 0.15f, 0.5f)
    blobs(ctx, rnd, W, H, 20, 20f, 80f, rgbInt(40, 40, 40), 0.1f, 0.3f)
    return Pair(ctx.bmp, hgt)
}

// ---------- 木箱 / 铁箱 ----------
private fun woodCrate(seed: Int, variant: Int): Pair<Bitmap, FloatArray> {
    val S = 512
    val rnd = Rng(seed)
    val ctx = Ctx(S, S)
    val grain = fbm(S, S, 2, 32, 4, seed)
    val n = fbm(S, S, 8, 8, 4, seed + 1)
    val hgt = FloatArray(S * S)
    val base = if (variant == 1) intArrayOf(150, 112, 70) else intArrayOf(178, 140, 88)
    val frame = 58; val planks = 6
    for (y in 0 until S) for (x in 0 until S) {
        val i = y * S + x
        val inFrame = x < frame || x > S - frame || y < frame || y > S - frame
        var hv = if (inFrame) 0.9f else 0.5f
        if (!inFrame) {
            val py = ((y - frame).toFloat() / (S - 2 * frame)) * planks
            if (py - floor(py) < 0.03f) hv = 0.1f
        }
        if (inFrame) {
            if (abs(x - frame) < 2 || abs(x - (S - frame)) < 2 || abs(y - frame) < 2 || abs(y - (S - frame)) < 2) hv = 0.2f
        }
        hgt[i] = hv + grain[i] * 0.1f
    }
    val px = IntArray(S * S)
    for (y in 0 until S) for (x in 0 until S) {
        val i = y * S + x
        val inX = x > frame && x < S - frame && y > frame && y < S - frame
        if (inX && abs((x - frame) - (y - frame)) < 34 && variant != 1) hgt[i] = 0.85f + grain[i] * 0.1f
        val gr = grain[i]
        val k = (0.72f + gr * 0.45f) * (0.8f + n[i] * 0.3f) * (0.55f + hgt[i] * 0.5f)
        px[i] = rgbInt((base[0] * k).toInt().coerceIn(0, 255), (base[1] * k).toInt().coerceIn(0, 255), (base[2] * k).toInt().coerceIn(0, 255))
    }
    ctx.setPx(px)
    val dark = Paint(Paint.ANTI_ALIAS_FLAG); dark.color = Color.argb(230, 40, 35, 30)
    for (k in 0 until 16) {
        val t = k / 4; val s = k % 4
        val pos = when (t) {
            0 -> floatArrayOf(frame / 2f, frame + s * (S - 2 * frame) / 3f)
            1 -> floatArrayOf((S - frame / 2).toFloat(), frame + s * (S - 2 * frame) / 3f)
            2 -> floatArrayOf(frame + s * (S - 2 * frame) / 3f, frame / 2f)
            else -> floatArrayOf(frame + s * (S - 2 * frame) / 3f, (S - frame / 2).toFloat())
        }
        ctx.c.drawCircle(pos[0], pos[1], 3f, dark)
    }
    val labels = arrayOf("FRAGILE", "CF-07", "HANDLE WITH CARE", "NO.36", "易碎物品", "LAGOS")
    val lab = labels[seed % labels.size]
    ctx.weatheredText(lab, S / 2f, S / 2f + (if (variant == 1) 0f else 60f), if (lab.length > 8) 34f else 54f,
        Color.argb(204, 25, 25, 25), rnd, 0.5f, bold = false)
    if (seed % 2 == 0) {
        val arr = Paint(Paint.ANTI_ALIAS_FLAG); arr.color = Color.argb(178, 25, 25, 25)
        for (ax in listOf(S / 2f - 40, S / 2f + 40)) {
            val path = Path()
            path.moveTo(ax, S * 0.28f); path.lineTo(ax - 18, S * 0.36f); path.lineTo(ax + 18, S * 0.36f); path.close()
            ctx.c.drawPath(path, arr)
            ctx.c.drawRect(ax - 6, S * 0.36f, ax + 6, S * 0.36f + 34, arr)
        }
    }
    blobs(ctx, rnd, S, S, 12, 10f, 50f, rgbInt(60, 40, 25), 0.1f, 0.35f)
    return Pair(ctx.bmp, hgt)
}

private fun metalCrate(seed: Int): Pair<Bitmap, FloatArray> {
    val S = 512
    val rnd = Rng(seed)
    val n = fbm(S, S, 8, 8, 5, seed)
    val hgt = FloatArray(S * S)
    for (y in 0 until S) for (x in 0 until S) {
        val e = min(min(x, y), min(S - x, S - y))
        var v = if (e < 24) 0.9f else if (e < 30) 0.4f else 0.55f
        val ry = (y.toFloat() / S) * 4
        if (abs(ry - kotlin.math.round(ry)) < 0.02f && e > 30) v = 0.75f
        hgt[y * S + x] = v + n[y * S + x] * 0.05f
    }
    val ctx = Ctx(S, S)
    ctx.fill(rgbInt(72, 80, 54))
    paintNoise(ctx, n) { v, r, g, b, i ->
        val k = (0.8f + v * 0.3f) * (0.7f + hgt[i] * 0.4f)
        intArrayOf((r * k).toInt(), (g * k).toInt(), (b * k).toInt())
    }
    val dp = Paint(); dp.color = Color.argb(204, 30, 30, 30)
    for (x in listOf(S * 0.25f, S * 0.75f)) {
        ctx.c.drawRect(x - 22, 32f, x + 22, 58f, dp)
        ctx.c.drawRect(x - 22, (S - 58).toFloat(), x + 22, (S - 32).toFloat(), dp)
    }
    val names = arrayOf("AMMO 7.62", "CARTRIDGES", "EXPLOSIVE", "CF ARMORY")
    ctx.weatheredText(names[seed % 4], S / 2f, S * 0.45f, 48f, Color.argb(217, 230, 200, 60), rnd, 0.4f)
    ctx.weatheredText("LOT " + (rnd.next() * 9000 + 1000).toInt(), S / 2f, S * 0.6f, 26f, Color.argb(178, 230, 220, 180), rnd, 0.3f, bold = false)
    for (i in 0 until 60) {
        val x = rnd.next() * S; val y = rnd.next() * S
        ctx.line(x, y, x + (rnd.next() - 0.5f) * 40, y + (rnd.next() - 0.5f) * 10,
            Color.argb((0.2f + rnd.next() * 0.3f).times(255).toInt(), 150, 150, 140), 1f)
    }
    return Pair(ctx.bmp, hgt)
}

// ---------- 船体 / 舱壁 / 上层建筑 ----------
private fun hullTexture(scl: Float): TexTriple {
    val W = (1024 * scl).toInt().coerceAtLeast(64)
    val H = (1024 * scl).toInt().coerceAtLeast(64)
    val rnd = Rng(55)
    val n = fbm(W, H, 8, 8, 5, 56)
    val ctx = Ctx(W, H)
    val yToPx = { y: Float -> H - ((y + 12) / 13.5f) * H }
    val wl = yToPx(-7.3f)
    ctx.fill(rgbInt(38, 42, 48))
    ctx.rect(0f, wl, W.toFloat(), yToPx(-8.2f) - wl, rgbInt(24, 24, 26))
    ctx.rect(0f, yToPx(-8.2f), W.toFloat(), H - yToPx(-8.2f), rgbInt(118, 34, 30))
    paintNoise(ctx, n) { v, r, g, b, _ ->
        val k = 0.75f + v * 0.45f
        intArrayOf((r * k).toInt(), (g * k).toInt(), (b * k).toInt())
    }
    val hgt = FloatArray(W * H)
    for (i in hgt.indices) hgt[i] = n[i] * 0.1f
    var x = 0
    while (x < W) {
        ctx.line(x.toFloat(), 0f, x.toFloat(), H.toFloat(), Color.argb(153, 15, 15, 18), 2f)
        for (y in 0 until H) {
            hgt[y * W + x] += 0.6f
            if (x + 1 < W) hgt[y * W + x + 1] += 0.4f
        }
        x += (128 * scl).toInt().coerceAtLeast(8)
    }
    for (yy in listOf(-2f, -5f, -10f)) {
        val py = yToPx(yy).toInt()
        ctx.line(0f, py.toFloat(), W.toFloat(), py.toFloat(), Color.argb(153, 15, 15, 18), 2f)
        if (py in 0 until H) for (xx in 0 until W) hgt[py * W + xx] += 0.6f
    }
    rustStreaks(ctx, W, H, rnd, 70, yToPx(1.2f), H * 0.35f, 0.45f, rgbInt(120, 58, 28))
    rustStreaks(ctx, W, H, rnd, 20, yToPx(-3f), H * 0.2f, 0.3f, rgbInt(120, 58, 28))
    for (i in 0 until 3) {
        val dx = 100 + i * 330 + rnd.next() * 40
        val dy = yToPx(-0.6f)
        val ep = Paint(Paint.ANTI_ALIAS_FLAG); ep.color = rgbInt(12, 12, 12)
        ctx.c.drawOval(android.graphics.RectF(dx - 10, dy - 6, dx + 10, dy + 6), ep)
        rustStreaks(ctx, W, H, Rng((rnd.next() * 10000).toInt()), 1, dy, H * 0.3f, 0.6f, rgbInt(130, 62, 30))
    }
    ctx.drawGrad(0f, wl - 40, W.toFloat(), 60f, Color.argb(0, 60, 70, 60), Color.argb(128, 60, 70, 60))
    return TexTriple(ctx.bmp, normalFromHeight(hgt, W, H, 3f), null)
}

private fun paintedSteel(seed: Int, rgb: IntArray, stiffeners: Boolean = true, rust: Int = 30, scl: Float): TexTriple {
    val S = (512 * scl).toInt().coerceAtLeast(64)
    val rnd = Rng(seed)
    val n = fbm(S, S, 4, 4, 5, seed)
    val n2 = fbm(S, S, 16, 16, 3, seed + 1)
    val hgt = FloatArray(S * S)
    val ctx = Ctx(S, S)
    ctx.fill(rgbInt(rgb[0], rgb[1], rgb[2]))
    paintNoise(ctx, n) { v, r, g, b, i ->
        val k = 0.82f + v * 0.28f + (n2[i] - 0.5f) * 0.06f
        intArrayOf((r * k).toInt(), (g * k).toInt(), (b * k).toInt())
    }
    if (stiffeners) {
        var x = 0
        while (x < S) {
            ctx.rect(x + 6f, 0f, 4f, S.toFloat(), Color.argb(31, 0, 0, 0))
            ctx.rect(x.toFloat(), 0f, 6f, S.toFloat(), Color.argb(20, 255, 255, 255))
            for (y in 0 until S) for (k in 0 until 8) if (x + k < S) hgt[y * S + x + k] = 0.8f
            x += (128 * scl).toInt().coerceAtLeast(8)
        }
        ctx.rect(0f, 255f * scl, S.toFloat(), 2f, Color.argb(51, 0, 0, 0))
        for (xx in 0 until S) hgt[(255 * scl).toInt().coerceIn(0, S - 1) * S + xx] = 0.6f
    }
    for (i in hgt.indices) hgt[i] += n2[i] * 0.05f
    rustStreaks(ctx, S, S, rnd, rust, 0f, S * 0.6f, 0.3f)
    blobs(ctx, rnd, S, S, 16, 4f, 20f, rgbInt(110, 60, 30), 0.2f, 0.5f)
    ctx.drawGrad(0f, S * 0.75f, S.toFloat(), S * 0.25f, Color.argb(0, 40, 35, 30), Color.argb(102, 40, 35, 30))
    return TexTriple(ctx.bmp, normalFromHeight(hgt, S, S, 2.5f), null)
}

private fun superWall(scl: Float): Pair<Bitmap, Bitmap> {
    val W = (512 * scl).toInt().coerceAtLeast(64)
    val H = (384 * scl).toInt().coerceAtLeast(48)
    val rnd = Rng(77)
    val n = fbm(W, H, 4, 3, 5, 78)
    val ctx = Ctx(W, H)
    ctx.fill(rgbInt(214, 216, 212))
    paintNoise(ctx, n) { v, r, g, b, _ ->
        val k = 0.85f + v * 0.2f
        intArrayOf((r * k).toInt(), (g * k).toInt(), (b * k).toInt())
    }
    val wx = 150 * scl; val wy = 90 * scl; val ww = 212 * scl; val wh = 150 * scl
    ctx.rect(wx - 10, wy - 10, ww + 20, wh + 20, rgbInt(160, 164, 160))
    // 窗玻璃渐变（三段）
    ctx.drawGrad(wx, wy, ww, wh * 0.45f, rgbInt(120, 150, 175), rgbInt(40, 58, 72))
    ctx.drawGrad(wx, wy + wh * 0.45f, ww, wh * 0.55f, rgbInt(40, 58, 72), rgbInt(22, 28, 34))
    val path = Path()
    path.moveTo(wx + 20, wy); path.lineTo(wx + 80, wy); path.lineTo(wx + 20, wy + wh); path.lineTo(wx, wy + wh); path.close()
    ctx.c.drawPath(path, Paint().apply { color = Color.argb(31, 255, 255, 255) })
    ctx.rect(wx + ww / 2 - 3f, wy, 6f, wh, rgbInt(150, 154, 150))
    rustStreaks(ctx, W, H, rnd, 10, wy + wh + 10, 110f, 0.35f)
    rustStreaks(ctx, W, H, rnd, 10, 0f, 60f, 0.2f)
    ctx.rect(0f, H - 6f, W.toFloat(), 6f, Color.argb(64, 0, 0, 0))
    val em = Ctx(W, H)
    em.fill(Color.BLACK)
    em.rect(wx, wy + wh * 0.5f, ww, wh * 0.5f, Color.argb(26, 255, 220, 160))
    return Pair(ctx.bmp, em.bmp)
}

// ---------- 网格 / 格栅 ----------
private fun meshFence(scl: Float): Bitmap {
    val S = (256 * scl).toInt().coerceAtLeast(32)
    val ctx = Ctx(S, S)
    ctx.clear()
    val cells = 8
    val lp = Paint(Paint.ANTI_ALIAS_FLAG)
    lp.color = Color.argb(255, 225, 228, 222)
    lp.strokeWidth = 3.2f * scl
    for (i in 0..cells) {
        val pp = (i.toFloat() / cells) * S
        ctx.c.drawLine(pp, 0f, pp, S.toFloat(), lp)
        ctx.c.drawLine(0f, pp, S.toFloat(), pp, lp)
    }
    return ctx.bmp
}

private fun grating(scl: Float): Bitmap {
    val S = (256 * scl).toInt().coerceAtLeast(32)
    val ctx = Ctx(S, S)
    ctx.fill(rgbInt(70, 74, 74))
    ctx.erase { ec, ep ->
        val cell = 16f * S / 256f
        for (x in 0 until 16) for (y in 0 until 4) {
            ec.drawRect(x * cell + 3, y * (S / 4f) + 4, x * cell + 3 + 11 * (S / 256f), y * (S / 4f) + 4 + 56 * (S / 256f), ep)
        }
    }
    return ctx.bmp
}

// ---------- 贴花 / 粒子 ----------
private fun bulletHole(kind: String): Bitmap {
    val S = 64
    val ctx = Ctx(S, S)
    ctx.clear()
    val rnd = Rng(if (kind == "wood") 3 else 4)
    if (kind == "wood") {
        for (i in 0 until 10) {
            val a = rnd.next() * 6.28f
            ctx.line(32f, 32f, 32 + cos(a) * (10 + rnd.next() * 16), 32 + sin(a) * (10 + rnd.next() * 16),
                Color.argb((0.5f + rnd.next() * 0.4f).times(255).toInt(), 210, 170, 110), 1f + rnd.next() * 2f)
        }
    } else {
        ctx.radial(32f, 32f, 20f, floatArrayOf(0f, 0.4f, 1f),
            intArrayOf(Color.argb(230, 200, 200, 195), Color.argb(128, 120, 120, 115), Color.argb(0, 60, 60, 60)))
    }
    ctx.radial(32f, 32f, 8f, floatArrayOf(0f, 0.7f, 1f),
        intArrayOf(Color.argb(255, 5, 5, 5), Color.argb(242, 15, 15, 15), Color.argb(0, 20, 20, 20)))
    return ctx.bmp
}

private fun scorchTex(scl: Float): Bitmap {
    val S = (256 * scl).toInt().coerceAtLeast(32)
    val ctx = Ctx(S, S)
    ctx.clear()
    val n = fbm(S, S, 8, 8, 4, 91)
    val px = IntArray(S * S)
    for (y in 0 until S) for (x in 0 until S) {
        val r = (hypot((x - S / 2).toDouble(), (y - S / 2).toDouble()) / (S / 2)).toFloat()
        val a = Math.pow((1 - r * (0.8f + n[y * S + x] * 0.6f)).coerceAtLeast(0f).toDouble(), 1.3).toFloat()
        px[y * S + x] = Color.argb((a * 235).toInt().coerceIn(0, 255), 10, 10, 10)
    }
    ctx.setPx(px)
    return ctx.bmp
}

private fun bloodTex(): Bitmap {
    val S = 128
    val ctx = Ctx(S, S)
    ctx.clear()
    val rnd = Rng(5)
    for (i in 0 until 26) {
        val a = rnd.next() * 6.28f; val r = rnd.next() * 40
        val s = 3 + rnd.next() * 12 * (1 - r / 50)
        ctx.c.drawCircle(64 + cos(a) * r, 64 + sin(a) * r, s,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb((0.7f + rnd.next() * 0.3f).times(255).toInt(), (100 + rnd.next() * 40).toInt(), 8, 8) })
    }
    return ctx.bmp
}

private fun muzzleFlash(seed: Int, petals: Int, scl: Float): Bitmap {
    val S = (256 * scl).toInt().coerceAtLeast(32)
    val ctx = Ctx(S, S)
    ctx.clear()
    val rnd = Rng(seed)
    ctx.save()
    ctx.translate(S / 2f, S / 2f)
    val lp = Paint(Paint.ANTI_ALIAS_FLAG)
    lp.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
    for (pp in 0 until petals) {
        val a = (pp.toFloat() / petals) * 2 * Math.PI + rnd.next() * 0.4
        val L = S * (0.28f + rnd.next() * 0.2f)
        val wd = S * 0.05f
        ctx.save()
        ctx.rotate(Math.toDegrees(a).toFloat())
        val g = LinearGradient(0f, 0f, L, 0f,
            Color.argb(242, 255, 240, 200), Color.argb(0, 255, 90, 10), Shader.TileMode.CLAMP)
        lp.shader = g
        val path = Path()
        path.moveTo(0f, -wd)
        path.quadTo(L * 0.5f, -wd * 0.8f, L, 0f)
        path.quadTo(L * 0.5f, wd * 0.8f, 0f, wd)
        path.close()
        ctx.c.drawPath(path, lp)
        ctx.restore()
    }
    ctx.radial(0f, 0f, S * 0.22f, floatArrayOf(0f, 0.35f, 1f),
        intArrayOf(Color.argb(255, 255, 255, 235), Color.argb(204, 255, 200, 110), Color.argb(0, 255, 120, 20)))
    ctx.restore()
    return ctx.bmp
}

private fun muzzleSide(scl: Float): Bitmap {
    val W = (256 * scl).toInt().coerceAtLeast(32)
    val H = (64 * scl).toInt().coerceAtLeast(16)
    val ctx = Ctx(W, H)
    ctx.clear()
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = LinearGradient(0f, 0f, W.toFloat(), 0f,
        Color.argb(255, 255, 250, 220), Color.argb(0, 255, 100, 20), Shader.TileMode.CLAMP)
    val path = Path()
    path.moveTo(0f, H / 2f - 14 * scl)
    path.quadTo(W * 0.4f, 2f, W.toFloat(), H / 2f)
    path.quadTo(W * 0.4f, H - 2f, 0f, H / 2f + 14 * scl)
    path.close()
    ctx.c.drawPath(path, p)
    return ctx.bmp
}

private fun softPuff(scl: Float): Bitmap {
    val S = (128 * scl).toInt().coerceAtLeast(32)
    val ctx = Ctx(S, S)
    ctx.clear()
    val n = fbm(S, S, 4, 4, 4, 33)
    val px = IntArray(S * S)
    for (y in 0 until S) for (x in 0 until S) {
        val r = (hypot((x - S / 2).toDouble(), (y - S / 2).toDouble()) / (S / 2)).toFloat()
        val a = Math.pow((1 - r).coerceAtLeast(0f).toDouble(), 1.5).toFloat() * (0.5f + n[y * S + x] * 0.8f)
        px[y * S + x] = Color.argb((a * 255).toInt().coerceIn(0, 255), 255, 255, 255)
    }
    ctx.setPx(px)
    return ctx.bmp
}

private fun glowTex(): Bitmap {
    val S = 64
    val ctx = Ctx(S, S)
    ctx.clear()
    ctx.radial(32f, 32f, 32f, floatArrayOf(0f, 0.3f, 1f),
        intArrayOf(Color.argb(255, 255, 255, 255), Color.argb(128, 255, 255, 255), Color.argb(0, 255, 255, 255)))
    return ctx.bmp
}

private fun sparkTex(): Bitmap {
    val W = 16; val H = 64
    val ctx = Ctx(W, H)
    ctx.clear()
    val p = Paint()
    p.shader = LinearGradient(0f, 0f, 0f, H.toFloat(),
        Color.argb(255, 255, 240, 180), Color.argb(0, 255, 140, 40), Shader.TileMode.CLAMP)
    ctx.c.drawRect(W / 2f - 2, H * 0.2f, W / 2f + 2, H.toFloat(), p)
    return ctx.bmp
}

// ---------- 标识牌图集 ----------
object SIGN_UV {
    val hazard = floatArrayOf(0f, 0f, 512f, 128f)
    val nosmoke = floatArrayOf(512f, 0f, 256f, 256f)
    val danger = floatArrayOf(768f, 0f, 256f, 256f)
    val hold = floatArrayOf(0f, 128f, 512f, 128f)
    val muster = floatArrayOf(0f, 256f, 256f, 256f)
    val hydrant = floatArrayOf(256f, 256f, 256f, 256f)
    val shipname = floatArrayOf(0f, 512f, 1024f, 160f)
    val bl = floatArrayOf(0f, 672f, 512f, 176f)
    val gr = floatArrayOf(512f, 672f, 512f, 176f)
    val arrow = floatArrayOf(512f, 256f, 256f, 256f)
    val yline = floatArrayOf(800f, 256f, 160f, 256f)
}

private fun signAtlas(scl: Float): Bitmap {
    val S = (1024 * scl).toInt().coerceAtLeast(128)
    val k = S / 1024f
    val ctx = Ctx(S, S)
    ctx.fill(Color.BLACK)
    // 0: 黄黑警示条
    var i = -4
    while (i < 40) {
        ctx.p.shader = null
        ctx.p.color = if (i % 2 == 0) -0x1F4DE4 else -0xE5E5E6
        val path = Path()
        path.moveTo(i * 32 * k, 0f); path.lineTo(i * 32 * k + 32 * k, 0f)
        path.lineTo(i * 32 * k + 32 * k - 128 * k, 128 * k); path.lineTo(i * 32 * k - 128 * k, 128 * k)
        path.close()
        ctx.c.drawPath(path, ctx.p)
        i++
    }
    // 1: 禁止烟火
    signRect(ctx, 512 * k, 0f, 256 * k, 256 * k, -0xD1D14, -0x3FDFE4,
        arrayOf("禁止烟火" to 40f, "NO SMOKING" to 22f), k) { x, y ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = -0x3FDFE4; p.strokeWidth = 14 * k; p.style = Paint.Style.STROKE
        ctx.c.drawCircle(x + 128 * k, y + 95 * k, 70 * k, p)
        ctx.c.drawLine(x + 78 * k, y + 45 * k, x + 178 * k, y + 145 * k, p)
        p.style = Paint.Style.FILL; p.color = -0xDDDDDE
        ctx.c.drawRect(x + 90 * k, y + 88 * k, x + 160 * k, y + 102 * k, p)
    }
    // 2: DANGER
    signRect(ctx, 768 * k, 0f, 256 * k, 256 * k, -0x1F4DE4, -0xEEEEEE,
        arrayOf("DANGER" to 50f, "危险" to 64f, "高压 · 禁止攀爬" to 24f), k, black = true)
    // 3: 舱号
    ctx.rect(0f, 128 * k, 512 * k, 128 * k, -0x26252C)
    ctx.c.save()
    ctx.p.shader = null
    ctx.p.color = -0xE4D5B6
    ctx.p.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    ctx.p.textSize = 76 * k
    ctx.c.drawText("HOLD No.3", 256 * k - ctx.p.measureText("HOLD No.3") / 2, 192 * k + 26 * k, ctx.p)
    ctx.c.restore()
    // 4: 集合点
    signRect(ctx, 0f, 256 * k, 256 * k, 256 * k, -0xE075C6, Color.WHITE,
        arrayOf("集合点" to 50f, "MUSTER STATION" to 20f), k) { x, y ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.color = Color.WHITE
        for (j in 0 until 4) ctx.c.drawCircle(x + 70 * k + j * 38 * k, y + 90 * k, 16 * k, p)
    }
    // 5: 消防栓
    signRect(ctx, 256 * k, 256 * k, 256 * k, 256 * k, -0x3FDFE4, Color.WHITE,
        arrayOf("消防栓" to 56f, "FIRE HYDRANT" to 22f), k) { x, y ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.color = Color.WHITE
        ctx.c.drawRect(x + 108 * k, y + 40 * k, x + 148 * k, y + 110 * k, p)
        ctx.c.drawRect(x + 90 * k, y + 60 * k, x + 166 * k, y + 78 * k, p)
    }
    // 6: 船名
    ctx.rect(0f, 512 * k, 1024 * k, 160 * k, -0x161419)
    ctx.c.save()
    ctx.p.shader = null
    ctx.p.color = -0xEDDC41
    ctx.p.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    ctx.p.textSize = 110 * k
    val sn = "KESTREL  STAR"
    ctx.c.drawText(sn, 512 * k - ctx.p.measureText(sn) / 2, 594 * k + 38 * k, ctx.p)
    ctx.c.restore()
    // 7: BL / GR
    ctx.rect(0f, 672 * k, 512 * k, 176 * k, -0xDFDAD5)
    ctx.rect(512 * k, 672 * k, 512 * k, 176 * k, -0xDFDAD5)
    ctx.c.save()
    ctx.p.shader = null
    ctx.p.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    ctx.p.textSize = 120 * k
    ctx.p.color = -0x2DC4D2
    ctx.c.drawText("BL", 256 * k - ctx.p.measureText("BL") / 2, 762 * k + 42 * k, ctx.p)
    ctx.p.color = -0xC57120
    ctx.c.drawText("GR", 768 * k - ctx.p.measureText("GR") / 2, 762 * k + 42 * k, ctx.p)
    ctx.c.restore()
    // 8: 甲板箭头
    ctx.rect(512 * k, 256 * k, 512 * k, 256 * k, Color.BLACK)
    ctx.p.shader = null
    ctx.p.color = Color.argb(230, 235, 235, 225)
    val ap = Path()
    ap.moveTo(640 * k, 280 * k); ap.lineTo(720 * k, 380 * k); ap.lineTo(670 * k, 380 * k)
    ap.lineTo(670 * k, 490 * k); ap.lineTo(610 * k, 490 * k); ap.lineTo(610 * k, 380 * k)
    ap.lineTo(560 * k, 380 * k); ap.close()
    ctx.c.drawPath(ap, ctx.p)
    ctx.rect(800 * k, 256 * k, 60 * k, 256 * k, Color.argb(242, 224, 178, 28))
    ctx.rect(900 * k, 256 * k, 60 * k, 256 * k, Color.argb(242, 224, 178, 28))
    // 做旧
    val n = fbm(S, S, 16, 16, 3, 124)
    paintNoise(ctx, n) { v, r, g, b, _ ->
        val kk = 0.8f + v * 0.3f
        intArrayOf((r * kk).toInt(), (g * kk).toInt(), (b * kk).toInt())
    }
    val rnd = Rng(123)
    for (j in 0 until 300) {
        ctx.rect(rnd.next() * S, rnd.next() * S, 2 + rnd.next() * 8, 2 + rnd.next() * 20,
            Color.argb((rnd.next() * 0.3f * 255).toInt(), 90, 60, 40))
    }
    return ctx.bmp
}

private fun signRect(ctx: Ctx, x: Float, y: Float, w: Float, h: Float, bg: Int, fg: Int, lines: Array<Pair<String, Float>>, k: Float, black: Boolean = false, icon: ((Float, Float) -> Unit)? = null) {
    ctx.rect(x, y, w, h, bg)
    val bp = Paint(Paint.ANTI_ALIAS_FLAG)
    bp.color = fg; bp.strokeWidth = 6 * k; bp.style = Paint.Style.STROKE
    ctx.c.drawRect(x + 6 * k, y + 6 * k, x + w - 6 * k, y + h - 6 * k, bp)
    bp.style = Paint.Style.FILL
    bp.typeface = if (black) Typeface.create("sans-serif-black", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.BOLD)
    ctx.c.save()
    for ((idx, l) in lines.withIndex()) {
        bp.textSize = l.second * k
        val tw = bp.measureText(l.first)
        ctx.c.drawText(l.first, x + w / 2 - tw / 2, y + (idx + 1) * h / (lines.size + 1) + l.second * k * 0.35f, bp)
    }
    ctx.c.restore()
    icon?.invoke(x, y)
}

// ---------- 汇总 ----------
class Textures {
    lateinit var deck: TexTriple
    var containers: List<ContainerTex> = listOf()
    var crates: List<CrateTex> = listOf()
    lateinit var hull: TexTriple
    lateinit var bulkhead: TexTriple
    lateinit var darkSteel: TexTriple
    lateinit var yellowSteel: TexTriple
    lateinit var redSteel: TexTriple
    lateinit var greenSteel: TexTriple
    lateinit var superWall: Pair<Bitmap, Bitmap>
    lateinit var fence: Bitmap
    lateinit var grating: Bitmap
    lateinit var holeMetal: Bitmap
    lateinit var holeWood: Bitmap
    lateinit var scorch: Bitmap
    lateinit var blood: Bitmap
    lateinit var flash: List<Bitmap>
    lateinit var flashSide: Bitmap
    lateinit var puff: Bitmap
    lateinit var glow: Bitmap
    lateinit var spark: Bitmap
    lateinit var signs: Bitmap

    class ContainerTex(
        val color: ContainerColor,
        val side20: Bitmap, val side40: Bitmap,
        val n20: Bitmap, val n40: Bitmap,
        val door: Bitmap, val doorN: Bitmap,
        val roof: Bitmap, val roofN: Bitmap,
    )

    class CrateTex(val map: Bitmap, val normalMap: Bitmap, val kind: String)

    companion object {
        fun build(quality: String): Textures {
            val scl = when (quality) {
                "high" -> 1f
                "medium" -> 0.7f
                else -> 0.45f
            }
            val T = Textures()
            T.deck = deckTextures(scl)
            val w20 = (1024 * scl).toInt().coerceAtLeast(64)
            val h20p = (448 * scl).toInt().coerceAtLeast(32)
            val w40 = (2048 * scl).toInt().coerceAtLeast(128)
            val h20 = corrugationHeight(w20, h20p, 22, 0.07f, 0.03f)
            val h40 = corrugationHeight(w40, h20p, 44, 0.07f, 0.015f)
            val n20 = normalFromHeight(h20, w20, h20p, 3.2f)
            val n40 = normalFromHeight(h40, w40, h20p, 3.2f)
            val roof = containerRoof((1024 * scl).toInt().coerceAtLeast(64), (256 * scl).toInt().coerceAtLeast(32), 900)
            val roofN = normalFromHeight(roof.second, roof.first.width, roof.first.height, 2f)
            T.containers = CONTAINER_COLORS.mapIndexed { i, col ->
                val s20 = containerSide(col, w20, h20p, 200 + i, h20)
                val s40 = containerSide(col, w40, h20p, 300 + i, h40)
                val door = containerDoor(col, 400 + i, scl)
                Textures.ContainerTex(col, s20, s40, n20, n40, door.map, normalFromHeight(door.hgt, door.W, door.H, 3f), roof.first, roofN)
            }
            val crates = mutableListOf<Textures.CrateTex>()
            for (i in 0 until 4) {
                val w = woodCrate(500 + i, i % 2)
                crates.add(Textures.CrateTex(w.first, normalFromHeight(w.second, 512, 512, 3f), "wood"))
            }
            for (i in 0 until 2) {
                val m = metalCrate(600 + i)
                crates.add(Textures.CrateTex(m.first, normalFromHeight(m.second, 512, 512, 3f), "metal"))
            }
            T.crates = crates
            T.hull = hullTexture(scl)
            T.bulkhead = paintedSteel(700, intArrayOf(168, 172, 170), scl = scl)
            T.darkSteel = paintedSteel(701, intArrayOf(70, 76, 80), rust = 40, scl = scl)
            T.yellowSteel = paintedSteel(702, intArrayOf(214, 170, 38), stiffeners = false, rust = 20, scl = scl)
            T.redSteel = paintedSteel(703, intArrayOf(150, 44, 36), stiffeners = false, rust = 20, scl = scl)
            T.greenSteel = paintedSteel(704, intArrayOf(70, 96, 78), rust = 25, scl = scl)
            T.superWall = superWall(scl)
            T.fence = meshFence(scl)
            T.grating = grating(scl)
            T.holeMetal = bulletHole("metal")
            T.holeWood = bulletHole("wood")
            T.scorch = scorchTex(scl)
            T.blood = bloodTex()
            T.flash = listOf(muzzleFlash(1, 5, scl), muzzleFlash(2, 6, scl), muzzleFlash(3, 4, scl))
            T.flashSide = muzzleSide(scl)
            T.puff = softPuff(scl)
            T.glow = glowTex()
            T.spark = sparkTex()
            T.signs = signAtlas(scl)
            return T
        }
    }
}

// 地图核心：材质定义、按材质合批、船体轮廓、AO 烘焙
package com.cf.transportship.game

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.cf.transportship.engine.Mat4
import com.cf.transportship.engine.Quat
import com.cf.transportship.gfx.Geo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------- 可增长缓冲 ----------
class FBuf(cap: Int = 1024) {
    var a = FloatArray(cap)
    var n = 0
    private fun ensure(k: Int) {
        if (n + k > a.size) {
            var s = a.size * 2
            while (s < n + k) s *= 2
            a = a.copyOf(s)
        }
    }
    fun add3(x: Float, y: Float, z: Float) { ensure(3); a[n++] = x; a[n++] = y; a[n++] = z }
    fun add2(x: Float, y: Float) { ensure(2); a[n++] = x; a[n++] = y }
    fun add1(x: Float) { ensure(1); a[n++] = x }
}

class IBuf(cap: Int = 1024) {
    var a = IntArray(cap)
    var n = 0
    private fun ensure(k: Int) {
        if (n + k > a.size) {
            var s = a.size * 2
            while (s < n + k) s *= 2
            a = a.copyOf(s)
        }
    }
    fun add(x: Int) { ensure(1); a[n++] = x }
}

/** sRGB hex -> 线性 RGB（three.js Color(0x...) 的行为） */
fun linearOf(hex: Int): FloatArray {
    val r = ((hex shr 16) and 0xff) / 255f
    val g = ((hex shr 8) and 0xff) / 255f
    val b = (hex and 0xff) / 255f
    return floatArrayOf(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b))
}

fun srgbToLinear(v: Float): Float =
    if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055) / 1.055).toDouble(), 2.4).toFloat()

class MatDef(
    val key: String,
    val map: Bitmap?,
    val normalMap: Bitmap?,
    val roughnessMap: Bitmap?,
    val color: FloatArray,
    val roughness: Float,
    val metalness: Float,
    val emissiveMap: Bitmap?,
    val emissive: FloatArray,
    val uv: Any,                      // 'unit' | 'custom' | Float | FloatArray(2)
    val alphaTest: Float = 0f,
    val twoSided: Boolean = false,
    val shadow: Boolean = true,
) {
    // GL 纹理 id（渲染器上传后填充）
    var glMap = 0; var glNormal = 0; var glRough = 0; var glEmissive = 0
    var drawNormalMap = false
}

/** 按材质合批的几何缓冲 */
class Batch {
    val p = FBuf(4096)
    val n = FBuf(4096)
    val uv = FBuf(4096)
    val uv2 = FBuf(4096)
    val idx = IBuf(4096)
    var count = 0
    var hasAO = false

    fun quad(
        v0: FloatArray, v1: FloatArray, v2: FloatArray, v3: FloatArray,
        nrm: FloatArray, uvs: FloatArray,
    ) {
        val b = count
        for (v in arrayOf(v0, v1, v2, v3)) p.add3(v[0], v[1], v[2])
        for (i in 0 until 4) n.add3(nrm[0], nrm[1], nrm[2])
        for (i in 0 until 4) uv.add2(uvs[i * 2], uvs[i * 2 + 1])
        for (i in 0 until 4) uv2.add2(1f, 1f)
        idx.add(b); idx.add(b + 1); idx.add(b + 2)
        idx.add(b); idx.add(b + 2); idx.add(b + 3)
        count += 4
    }

    fun geom(g: Geo, m4: Mat4, aoU: Float = 1f, aoV: Float = 1f) {
        val t = g.clone().applyMatrix(m4)
        val b = count
        val vn = t.vertCount
        for (i in 0 until vn) {
            p.add3(t.pos[i * 3], t.pos[i * 3 + 1], t.pos[i * 3 + 2])
            n.add3(t.nrm[i * 3], t.nrm[i * 3 + 1], t.nrm[i * 3 + 2])
            uv.add2(t.uv[i * 2], t.uv[i * 2 + 1])
            uv2.add2(aoU, aoV)
        }
        for (i in t.idx) idx.add(b + i)
        count += vn
    }
}

// ---------- 图元变换辅助 ----------
fun matTRS(px: Float, py: Float, pz: Float, yaw: Float, pitch: Float, roll: Float, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f): Mat4 {
    // three.js 默认欧拉顺序 'XYZ'：R = Rx * Ry * Rz? 实际 quaternion setFromEuler XYZ:
    // q = qx * qy * qz
    val qx = Quat().setFromAxisAngle(1f, 0f, 0f, pitch)
    val qy = Quat().setFromAxisAngle(0f, 1f, 0f, yaw)
    val qz = Quat().setFromAxisAngle(0f, 0f, 1f, roll)
    val q = qx.copy().multiply(qy).multiply(qz)
    return Mat4().compose(px, py, pz, q, sx, sy, sz)
}

fun mulChain(vararg ms: Mat4): Mat4 {
    val out = Mat4().set(ms[0])
    for (i in 1 until ms.size) out.multiply(ms[i])
    return out
}

// ---------- 船体轮廓 ----------
// t∈[0,1) 沿边界一周；右舷(0..0.35) 船头(0.35..0.5) 左舷(0.5..0.85) 船尾(0.85..1)
fun hullContour(t: Float, sc: Float, bowK: Float): FloatArray {
    val halfW = 12.25f * sc
    val xs = -66f
    val xb0 = 58f
    val xb1 = 58f + 28f * bowK
    return if (t < 0.35f) {
        val k = t / 0.35f
        floatArrayOf(xs + (xb0 - xs) * k, halfW)
    } else if (t < 0.5f) {
        val a = ((t - 0.35f) / 0.15f) * PI.toFloat()
        floatArrayOf(xb0 + (xb1 - xb0) * Math.pow(sin(a.toDouble()), 0.8).toFloat(), halfW * cos(a) * (1 - 0.1f * sin(a)))
    } else if (t < 0.85f) {
        val k = (t - 0.5f) / 0.35f
        floatArrayOf(xb0 + (xs - xb0) * k, -halfW)
    } else {
        val a = ((t - 0.85f) / 0.15f) * PI.toFloat()
        floatArrayOf(xs - 3f * sin(a) * sc, -halfW * cos(a))
    }
}

/** 多边形耳切三角化（处理任意简单多边形） */
fun triangulate(pts: List<FloatArray>): IntArray {
    val n = pts.size
    if (n < 3) return IntArray(0)
    // 计算有向面积决定绕向
    var area = 0f
    for (i in 0 until n) {
        val j = (i + 1) % n
        area += pts[i][0] * pts[j][1] - pts[j][0] * pts[i][1]
    }
    val ccw = area > 0
    val idx = ArrayList<Int>(n)
    for (i in 0 until n) idx.add(i)
    val tris = ArrayList<Int>()
    var guard = 0
    while (idx.size > 3 && guard++ < 10000) {
        var clipped = false
        val m = idx.size
        for (i in 0 until m) {
            val a = idx[(i + m - 1) % m]; val b = idx[i]; val c = idx[(i + 1) % m]
            val ax = pts[a][0]; val ay = pts[a][1]
            val bx = pts[b][0]; val by = pts[b][1]
            val cx = pts[c][0]; val cy = pts[c][1]
            val cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (if (ccw) cross <= 1e-9f else cross >= -1e-9f) continue
            // 检查其余点是否在三角形内
            var inside = false
            for (j in idx) {
                if (j == a || j == b || j == c) continue
                if (pointInTri(pts[j], ax, ay, bx, by, cx, cy)) { inside = true; break }
            }
            if (inside) continue
            tris.add(a); tris.add(b); tris.add(c)
            idx.removeAt(i)
            clipped = true
            break
        }
        if (!clipped) break
    }
    if (idx.size == 3) { tris.add(idx[0]); tris.add(idx[1]); tris.add(idx[2]) }
    return tris.toIntArray()
}

private fun pointInTri(p: FloatArray, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Boolean {
    val d1 = sign(p[0], p[1], ax, ay, bx, by)
    val d2 = sign(p[0], p[1], bx, by, cx, cy)
    val d3 = sign(p[0], p[1], cx, cy, ax, ay)
    val hasNeg = d1 < 0 || d2 < 0 || d3 < 0
    val hasPos = d1 > 0 || d2 > 0 || d3 > 0
    return !(hasNeg && hasPos)
}

private fun sign(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float =
    (px - bx) * (ay - by) - (ax - bx) * (py - by)

/** 甲板 AO 烘焙：footprint = [cx, cz, sx, sz, yaw, dark] */
fun bakeDeckAO(fps: List<FloatArray>): Bitmap {
    val W = 2048
    val H = 692
    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(Color.WHITE)
    val c = Canvas(bmp)
    val sx = W / 74f
    val sz = H / 25f
    val draw = { blur: Float, grow: Float, alphaMul: Float ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.BLACK
        if (blur > 0) p.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        for (f in fps) {
            val cx = (f[0] + 37) * sx
            val cz = (f[1] + 12.5f) * sz
            val dark = f[5] * alphaMul
            p.alpha = (dark * 255).toInt().coerceIn(0, 255)
            c.save()
            c.translate(cx, cz)
            c.rotate(-Math.toDegrees(f[4].toDouble()).toFloat())
            val fx0 = -(f[2] / 2 + grow) * sx
            val fz0 = -(f[3] / 2 + grow) * sz
            c.drawRect(fx0, fz0, fx0 + (f[2] + grow * 2) * sx, fz0 + (f[3] + grow * 2) * sz, p)
            c.restore()
        }
    }
    draw(40f, 0.1f, 0.35f)
    draw(12f, 0.02f, 0.45f)
    // 管道内部较暗
    val p2 = Paint()
    p2.color = Color.argb(140, 0, 0, 0)
    c.drawRect((-29.5f + 37) * sx, (9.4f + 12.5f) * sz, (-29.5f + 37 + 36.6f) * sx, (9.4f + 12.5f + 2.44f) * sz, p2)
    c.drawRect((29.5f - 36.6f + 37) * sx, (-11.84f + 12.5f) * sz, (29.5f - 36.6f + 37 + 36.6f) * sx, (-11.84f + 12.5f + 2.44f) * sz, p2)
    return bmp
}

// 程序生成船体：多层轮廓环
fun buildHullGeometry(): Geo {
    val rings = arrayOf(
        floatArrayOf(0.12f, 1.0f, 1.0f), floatArrayOf(-0.4f, 1.0f, 1.0f), floatArrayOf(-2.5f, 0.995f, 0.97f),
        floatArrayOf(-5f, 0.985f, 0.93f), floatArrayOf(-7.5f, 0.965f, 0.88f), floatArrayOf(-9.5f, 0.93f, 0.82f),
        floatArrayOf(-12f, 0.85f, 0.74f),
    )
    val N = 120
    val posA = ArrayList<Float>()
    val uvA = ArrayList<Float>()
    val idxA = ArrayList<Int>()
    for (r in rings.indices) {
        val R = rings[r]
        var acc = 0f
        var prevX = 0f; var prevZ = 0f
        for (i in 0..N) {
            val ct = hullContour((i % N).toFloat() / N, R[1], R[2])
            if (i > 0) acc += kotlin.math.hypot((ct[0] - prevX).toDouble(), (ct[1] - prevZ).toDouble()).toFloat()
            prevX = ct[0]; prevZ = ct[1]
            posA.add(ct[0]); posA.add(R[0]); posA.add(ct[1])
            uvA.add(acc / 16f); uvA.add((R[0] + 12f) / 13.5f)
        }
    }
    val row = N + 1
    for (r in 0 until rings.size - 1) for (i in 0 until N) {
        val a = r * row + i; val b = a + 1; val c2 = a + row; val d = c2 + 1
        idxA.add(a); idxA.add(c2); idxA.add(b)
        idxA.add(b); idxA.add(c2); idxA.add(d)
    }
    // 顶点法线：面法线累积
    val vn = IntArray(posA.size / 3) { 0 }
    val pos = posA.toFloatArray()
    val nrm = FloatArray(pos.size)
    for (t in 0 until idxA.size step 3) {
        val a = idxA[t] * 3; val b = idxA[t + 1] * 3; val c2 = idxA[t + 2] * 3
        val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
        val vx = pos[c2] - pos[a]; val vy = pos[c2 + 1] - pos[a + 1]; val vz = pos[c2 + 2] - pos[a + 2]
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        for (k in intArrayOf(idxA[t], idxA[t + 1], idxA[t + 2])) {
            nrm[k * 3] += nx; nrm[k * 3 + 1] += ny; nrm[k * 3 + 2] += nz
            vn[k]++
        }
    }
    for (i in vn.indices) {
        val l = kotlin.math.sqrt(nrm[i * 3] * nrm[i * 3] + nrm[i * 3 + 1] * nrm[i * 3 + 1] + nrm[i * 3 + 2] * nrm[i * 3 + 2])
        if (l > 1e-9f) { nrm[i * 3] /= l; nrm[i * 3 + 1] /= l; nrm[i * 3 + 2] /= l }
    }
    return Geo(pos, nrm, uvA.toFloatArray(), idxA.toIntArray())
}

// 程序化几何图元：返回可变换合并的原始数组
package com.cf.transportship.gfx

import com.cf.transportship.engine.Mat4
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class Geo(
    var pos: FloatArray = FloatArray(0),
    var nrm: FloatArray = FloatArray(0),
    var uv: FloatArray = FloatArray(0),
    var idx: IntArray = IntArray(0),
    var col: FloatArray? = null,
    var skin: FloatArray? = null,
) {
    val vertCount: Int get() = pos.size / 3

    fun clone(): Geo {
        val g = Geo()
        g.pos = pos.copyOf(); g.nrm = nrm.copyOf(); g.uv = uv.copyOf(); g.idx = idx.copyOf()
        g.col = col?.copyOf(); g.skin = skin?.copyOf()
        return g
    }

    fun applyMatrix(m: Mat4): Geo {
        val n = vertCount
        val np = FloatArray(n * 3)
        val nn = FloatArray(n * 3)
        // col/skin 不受矩阵影响
        // 法线矩阵 = 逆转置（缩放均匀/非均匀均正确）
        val inv = m.copy().invert()
        for (i in 0 until n) {
            val x = pos[i * 3]; val y = pos[i * 3 + 1]; val z = pos[i * 3 + 2]
            np[i * 3] = m.m[0] * x + m.m[4] * y + m.m[8] * z + m.m[12]
            np[i * 3 + 1] = m.m[1] * x + m.m[5] * y + m.m[9] * z + m.m[13]
            np[i * 3 + 2] = m.m[2] * x + m.m[6] * y + m.m[10] * z + m.m[14]
            val nx = nrm[i * 3]; val ny = nrm[i * 3 + 1]; val nz = nrm[i * 3 + 2]
            var tx = inv.m[0] * nx + inv.m[1] * ny + inv.m[2] * nz
            var ty = inv.m[4] * nx + inv.m[5] * ny + inv.m[6] * nz
            var tz = inv.m[8] * nx + inv.m[9] * ny + inv.m[10] * nz
            val l = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz)
            if (l > 1e-9f) { tx /= l; ty /= l; tz /= l }
            nn[i * 3] = tx; nn[i * 3 + 1] = ty; nn[i * 3 + 2] = tz
        }
        pos = np; nrm = nn
        return this
    }
}

object Prims {

    /** 盒（含每面 UV） */
    fun box(w: Float, h: Float, d: Float): Geo {
        val hx = w / 2f; val hy = h / 2f; val hz = d / 2f
        // 每面 4 顶点：px nx py ny pz nz
        val pos = FloatArray(24 * 3)
        val nrm = FloatArray(24 * 3)
        val uv = FloatArray(24 * 2)
        val idx = IntArray(36)
        var vi = 0
        var ii = 0
        val faces = arrayOf(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
            floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f),
        )
        for (f in 0 until 6) {
            val n = faces[f]
            // 切线 u/v
            val u: FloatArray
            val v: FloatArray
            when (f) {
                0 -> { u = floatArrayOf(0f, 0f, -1f); v = floatArrayOf(0f, 1f, 0f) }
                1 -> { u = floatArrayOf(0f, 0f, 1f); v = floatArrayOf(0f, 1f, 0f) }
                2 -> { u = floatArrayOf(1f, 0f, 0f); v = floatArrayOf(0f, 0f, -1f) }
                3 -> { u = floatArrayOf(1f, 0f, 0f); v = floatArrayOf(0f, 0f, 1f) }
                4 -> { u = floatArrayOf(1f, 0f, 0f); v = floatArrayOf(0f, 1f, 0f) }
                else -> { u = floatArrayOf(-1f, 0f, 0f); v = floatArrayOf(0f, 1f, 0f) }
            }
            val su = if (f < 2) d else w
            val sv = if (f < 2) h else if (f < 4) d else h
            val cx = n[0] * hx; val cy = n[1] * hy; val cz = n[2] * hz
            val corners = arrayOf(
                floatArrayOf(-0.5f, -0.5f), floatArrayOf(0.5f, -0.5f),
                floatArrayOf(0.5f, 0.5f), floatArrayOf(-0.5f, 0.5f),
            )
            val uvs = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 1f))
            val base = vi
            for (cc in 0 until 4) {
                val a = corners[cc][0]; val b = corners[cc][1]
                pos[vi * 3] = cx + u[0] * su * a + v[0] * sv * b
                pos[vi * 3 + 1] = cy + u[1] * su * a + v[1] * sv * b
                pos[vi * 3 + 2] = cz + u[2] * su * a + v[2] * sv * b
                nrm[vi * 3] = n[0]; nrm[vi * 3 + 1] = n[1]; nrm[vi * 3 + 2] = n[2]
                uv[vi * 2] = uvs[cc][0]; uv[vi * 2 + 1] = uvs[cc][1]
                vi++
            }
            idx[ii++] = base; idx[ii++] = base + 1; idx[ii++] = base + 2
            idx[ii++] = base; idx[ii++] = base + 2; idx[ii++] = base + 3
        }
        return Geo(pos, nrm, uv, idx)
    }

    /** 圆柱（含端盖），Y 轴 */
    fun cylinder(rTop: Float, rBot: Float, len: Float, seg: Int): Geo {
        val posA = ArrayList<Float>()
        val nrmA = ArrayList<Float>()
        val uvA = ArrayList<Float>()
        val idxA = ArrayList<Int>()
        val half = len / 2f
        val slope = (rBot - rTop)
        val nl = kotlin.math.sqrt(1f + slope * slope)
        // 侧面
        for (i in 0..seg) {
            val a = i.toFloat() / seg * 2f * PI.toFloat()
            val cx = cos(a); val cz = sin(a)
            posA.add(cx * rTop); posA.add(half); posA.add(cz * rTop)
            nrmA.add(cx / nl); nrmA.add(slope / nl); nrmA.add(cz / nl)
            uvA.add(i.toFloat() / seg); uvA.add(1f)
            posA.add(cx * rBot); posA.add(-half); posA.add(cz * rBot)
            nrmA.add(cx / nl); nrmA.add(slope / nl); nrmA.add(cz / nl)
            uvA.add(i.toFloat() / seg); uvA.add(0f)
        }
        for (i in 0 until seg) {
            val b = i * 2
            idxA.add(b); idxA.add(b + 2); idxA.add(b + 1)
            idxA.add(b + 1); idxA.add(b + 2); idxA.add(b + 3)
        }
        // 端盖
        val base = posA.size / 3
        posA.add(0f); posA.add(half); posA.add(0f)
        nrmA.add(0f); nrmA.add(1f); nrmA.add(0f)
        uvA.add(0.5f); uvA.add(0.5f)
        val baseB = base + 1
        posA.add(0f); posA.add(-half); posA.add(0f)
        nrmA.add(0f); nrmA.add(-1f); nrmA.add(0f)
        uvA.add(0.5f); uvA.add(0.5f)
        for (i in 0..seg) {
            val a = i.toFloat() / seg * 2f * PI.toFloat()
            val cx = cos(a); val cz = sin(a)
            posA.add(cx * rTop); posA.add(half); posA.add(cz * rTop)
            nrmA.add(0f); nrmA.add(1f); nrmA.add(0f)
            uvA.add(cx * 0.5f + 0.5f); uvA.add(cz * 0.5f + 0.5f)
            posA.add(cx * rBot); posA.add(-half); posA.add(cz * rBot)
            nrmA.add(0f); nrmA.add(-1f); nrmA.add(0f)
            uvA.add(cx * 0.5f + 0.5f); uvA.add(cz * 0.5f + 0.5f)
        }
        val topRing = base + 2
        val botRing = topRing + seg + 1
        for (i in 0 until seg) {
            idxA.add(base); idxA.add(topRing + i); idxA.add(topRing + i + 1)
            idxA.add(baseB); idxA.add(botRing + i + 1); idxA.add(botRing + i)
        }
        return Geo(posA.toFloatArray(), nrmA.toFloatArray(), uvA.toFloatArray(), idxA.toIntArray())
    }

    /** 球（支持部分球面） */
    fun sphere(r: Float, widthSeg: Int, heightSeg: Int, phiStart: Float = 0f, phiLen: Float = 2f * PI.toFloat(), thetaStart: Float = 0f, thetaLen: Float = PI.toFloat()): Geo {
        val posA = ArrayList<Float>()
        val nrmA = ArrayList<Float>()
        val uvA = ArrayList<Float>()
        val idxA = ArrayList<Int>()
        for (y in 0..heightSeg) {
            val v = y.toFloat() / heightSeg
            val theta = thetaStart + v * thetaLen
            val st = sin(theta); val ct = cos(theta)
            for (x in 0..widthSeg) {
                val u = x.toFloat() / widthSeg
                val phi = phiStart + u * phiLen
                val sp = sin(phi); val cp = cos(phi)
                val nx = -r * st * cp; val ny = r * ct; val nz = r * st * sp
                posA.add(nx); posA.add(ny); posA.add(nz)
                nrmA.add(nx / r); nrmA.add(ny / r); nrmA.add(nz / r)
                uvA.add(u); uvA.add(1f - v)
            }
        }
        val row = widthSeg + 1
        for (y in 0 until heightSeg) for (x in 0 until widthSeg) {
            val a = y * row + x; val b = a + 1; val c = a + row; val d = c + 1
            idxA.add(a); idxA.add(c); idxA.add(b)
            idxA.add(b); idxA.add(c); idxA.add(d)
        }
        return Geo(posA.toFloatArray(), nrmA.toFloatArray(), uvA.toFloatArray(), idxA.toIntArray())
    }

    /** 圆环（XZ 平面内绕，法线朝外） */
    fun torus(R: Float, r: Float, radSeg: Int, tubSeg: Int): Geo {
        val posA = ArrayList<Float>()
        val nrmA = ArrayList<Float>()
        val uvA = ArrayList<Float>()
        val idxA = ArrayList<Int>()
        for (j in 0..radSeg) {
            val v = j.toFloat() / radSeg * 2f * PI.toFloat()
            val cv = cos(v); val sv = sin(v)
            for (i in 0..tubSeg) {
                val u = i.toFloat() / tubSeg * 2f * PI.toFloat()
                val cu = cos(u); val su = sin(u)
                val x = (R + r * cv) * cu
                val y = r * sv
                val z = (R + r * cv) * su
                posA.add(x); posA.add(y); posA.add(z)
                nrmA.add(r * cv * cu / r); nrmA.add(sv); nrmA.add(r * cv * su / r)
                uvA.add(i.toFloat() / tubSeg); uvA.add(1f - j.toFloat() / radSeg)
            }
        }
        val row = tubSeg + 1
        for (j in 0 until radSeg) for (i in 0 until tubSeg) {
            val a = j * row + i; val b = a + 1; val c = a + row; val d = c + 1
            idxA.add(a); idxA.add(c); idxA.add(b)
            idxA.add(b); idxA.add(c); idxA.add(d)
        }
        return Geo(posA.toFloatArray(), nrmA.toFloatArray(), uvA.toFloatArray(), idxA.toIntArray())
    }

    /** 胶囊（Y 轴） */
    fun capsule(r: Float, len: Float, capSeg: Int, radSeg: Int): Geo {
        val posA = ArrayList<Float>()
        val nrmA = ArrayList<Float>()
        val uvA = ArrayList<Float>()
        val idxA = ArrayList<Int>()
        val half = len / 2f
        // 圆柱部分
        val cylRows = 1
        for (y in 0..cylRows) {
            val t = y.toFloat() / cylRows
            val yy = half - t * len
            for (i in 0..radSeg) {
                val a = i.toFloat() / radSeg * 2f * PI.toFloat()
                val cx = cos(a); val cz = sin(a)
                posA.add(cx * r); posA.add(yy); posA.add(cz * r)
                nrmA.add(cx); nrmA.add(0f); nrmA.add(cz)
                uvA.add(i.toFloat() / radSeg); uvA.add(t)
            }
        }
        for (i in 0 until radSeg) {
            val b = i * 2
            idxA.add(b); idxA.add(b + 2); idxA.add(b + 1)
            idxA.add(b + 1); idxA.add(b + 2); idxA.add(b + 3)
        }
        // 顶部半球
        val topBase = posA.size / 3
        for (y in 0..capSeg) {
            val v = y.toFloat() / capSeg
            val theta = v * (PI.toFloat() / 2f)
            val st = sin(theta); val ct = cos(theta)
            for (i in 0..radSeg) {
                val a = i.toFloat() / radSeg * 2f * PI.toFloat()
                val cx = cos(a); val cz = sin(a)
                posA.add(cx * r * st); posA.add(half + r * ct); posA.add(cz * r * st)
                nrmA.add(cx * st); nrmA.add(ct); nrmA.add(cz * st)
                uvA.add(i.toFloat() / radSeg); uvA.add(1f - v)
            }
        }
        for (y in 0 until capSeg) for (i in 0 until radSeg) {
            val a = topBase + y * (radSeg + 1) + i
            val b = a + 1; val c = a + radSeg + 1; val d = c + 1
            idxA.add(a); idxA.add(c); idxA.add(b)
            idxA.add(b); idxA.add(c); idxA.add(d)
        }
        // 底部半球
        val botBase = posA.size / 3
        for (y in 0..capSeg) {
            val v = y.toFloat() / capSeg
            val theta = v * (PI.toFloat() / 2f)
            val st = sin(theta); val ct = cos(theta)
            for (i in 0..radSeg) {
                val a = i.toFloat() / radSeg * 2f * PI.toFloat()
                val cx = cos(a); val cz = sin(a)
                posA.add(cx * r * st); posA.add(-half - r * ct); posA.add(cz * r * st)
                nrmA.add(cx * st); nrmA.add(-ct); nrmA.add(cz * st)
                uvA.add(i.toFloat() / radSeg); uvA.add(v)
            }
        }
        for (y in 0 until capSeg) for (i in 0 until radSeg) {
            val a = botBase + y * (radSeg + 1) + i
            val b = a + 1; val c = a + radSeg + 1; val d = c + 1
            idxA.add(a); idxA.add(b); idxA.add(c)
            idxA.add(b); idxA.add(d); idxA.add(c)
        }
        return Geo(posA.toFloatArray(), nrmA.toFloatArray(), uvA.toFloatArray(), idxA.toIntArray())
    }
}

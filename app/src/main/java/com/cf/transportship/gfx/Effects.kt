// 特效：粒子、贴花、曳光、枪口火焰、爆炸、烟囱排烟（移植 effects.js）
package com.cf.transportship.gfx

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import com.cf.transportship.engine.DynamicMesh
import com.cf.transportship.engine.Layouts
import com.cf.transportship.engine.Mat4
import com.cf.transportship.engine.Quat
import com.cf.transportship.engine.Renderer
import com.cf.transportship.engine.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class ParticleDef(
    var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float, var vz: Float,
    var life: Float, var max: Float,
    var s0: Float, var s1: Float,
    var r: Float, var g: Float, var b: Float,
    var a0: Float, var a1: Float,
    var grav: Float, var drag: Float,
    var floor: Float = Float.NaN,
)

class ParticlePool(val max: Int) {
    val list = ArrayList<ParticleDef>(max)
    private val out = FloatArray(max * 8)

    fun emit(o: ParticleDef) {
        if (list.size < max) list.add(o)
        else list[(Math.random() * max).toInt()] = o
    }

    /** 返回可上传的交错数组 [pos3,color4,size1]，返回有效数量 */
    fun update(dt: Float): Pair<FloatArray, Int> {
        var n = 0
        var i = 0
        while (i < list.size) {
            val q = list[i]
            q.life += dt
            if (q.life >= q.max) { list.removeAt(i); continue }
            val dr = exp(-q.drag * dt)
            q.vx *= dr
            q.vy = q.vy * dr - q.grav * dt
            q.vz *= dr
            q.x += q.vx * dt; q.y += q.vy * dt; q.z += q.vz * dt
            if (!q.floor.isNaN() && q.y < q.floor) {
                q.y = q.floor; q.vy *= -0.3f; q.vx *= 0.5f; q.vz *= 0.5f
            }
            val t = q.life / q.max
            if (n < max) {
                val o = n * 8
                out[o] = q.x; out[o + 1] = q.y; out[o + 2] = q.z
                out[o + 3] = q.r; out[o + 4] = q.g; out[o + 5] = q.b
                out[o + 6] = q.a0 + (q.a1 - q.a0) * t
                out[o + 7] = q.s0 + (q.s1 - q.s0) * t
                n++
            }
            i++
        }
        return Pair(out, n)
    }
}

class DecalQuad(
    val x: Float, val y: Float, val z: Float,
    val nx: Float, val ny: Float, val nz: Float,
    val size: Float,
)

class Tracer(val from: Vec3, val dir: Vec3, val len: Float, var t: Float)

class FlashSprite {
    val pos = Vec3()
    var scale = 1f
    var t = 0f
    var rot = 0f
    var texIdx = 0
}

class DynLight {
    val l = Vec3()
    var colorR = 1f; var colorG = 0.65f; var colorB = 0.31f
    var peak = 0f
    var t = 0f
    var dur = 0.06f
    var radius = 9f
}

class Effects(val sceneMax: Int = 900) {
    val add = ParticlePool(sceneMax)
    val smoke = ParticlePool(sceneMax)

    // 贴花池
    val decalMetal = ArrayList<DecalQuad>()
    val decalWood = ArrayList<DecalQuad>()
    val decalBlood = ArrayList<DecalQuad>()
    val decalScorch = ArrayList<DecalQuad>()
    private var decalMI = 0; private var decalWI = 0; private var decalBI = 0; private var decalSI = 0
    private val decalMaxMetal = 160; private val decalMaxWood = 100
    private val decalMaxBlood = 60; private val decalMaxScorch = 12
    // 脏标记：贴花列表无变化时跳过网格重建（原先每帧全量重建，数千次分配）
    private var decalDirty = true

    // 曳光
    val tracers = ArrayList<Tracer>()
    private val tracerMax = 48

    // 枪口火焰板
    val flashes = Array(12) { FlashSprite() }
    private var flashI = 0

    // 动态点光源池
    val lights = Array(3) { DynLight() }
    private var lightI = 0

    var shake = 0f
    var funnelTop: Vec3? = null
    private var funnelT = 0f

    // 烟囱烟雾由 smoke 池承担

    fun tracerTex(): Bitmap {
        val W = 64; val H = 16
        val ctx = Ctx(W, H)
        ctx.clear()
        // 垂直渐变（中间亮）
        val px = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            val vy = 1f - kotlin.math.abs(y - H / 2f) / (H / 2f)
            val a = (vy * 255).toInt().coerceIn(0, 255)
            px[y * W + x] = Color.argb(a, 255, 240, 200)
        }
        // 头部淡出
        for (y in 0 until H) for (x in 0 until W) {
            val fade = x.toFloat() / W
            val i = y * W + x
            val a = ((1 - fade) * Color.alpha(px[i])).toInt()
            px[i] = Color.argb(a, Color.red(px[i]), Color.green(px[i]), Color.blue(px[i]))
        }
        ctx.setPx(px)
        return ctx.bmp
    }

    fun light(pos: Vec3, intensity: Float, dur: Float, color: Int = 0xffffa850.toInt(), dist: Float = 9f) {
        val L = lights[lightI++ % lights.size]
        L.l.set(pos)
        L.colorR = Color.red(color) / 255f; L.colorG = Color.green(color) / 255f; L.colorB = Color.blue(color) / 255f
        L.radius = dist
        L.t = dur; L.dur = dur; L.peak = intensity
    }

    fun muzzle(pos: Vec3, dir: Vec3, big: Float = 1f) {
        val F = flashes[flashI++ % flashes.size]
        F.pos.set(pos).addScaled(dir, 0.06f)
        F.rot = (Math.random() * 6.28f).toFloat()
        F.scale = 0.45f * big * (0.8f + Math.random().toFloat() * 0.4f)
        F.texIdx = (Math.random() * 3).toInt()
        F.t = 0.05f
        light(pos, 6 * big, 0.06f)
        smoke.emit(ParticleDef(pos.x, pos.y, pos.z, dir.x * 1.5f, 0.4f, dir.z * 1.5f,
            0f, 0.7f, 0.1f, 0.5f, 0.7f, 0.7f, 0.7f, 0.18f, 0f, -0.3f, 3f))
    }

    fun tracer(from: Vec3, to: Vec3) {
        val d = to.copy().sub(from)
        val L = d.len()
        if (L < 2f) return
        d.mul(1f / L)
        tracers.add(Tracer(from.copy(), d, L, 0f))
        if (tracers.size > tracerMax) tracers.removeAt(0)
    }

    fun impact(p: Vec3, n: Vec3, mat: String, dir: Vec3) {
        val nx = n.x; val ny = n.y; val nz = n.z
        if (mat == "flesh") {
            for (i in 0 until 10) {
                smoke.emit(ParticleDef(p.x, p.y, p.z,
                    dir.x * 2 + (Math.random().toFloat() - 0.5f) * 1.5f, Math.random().toFloat() * 1.2f, dir.z * 2 + (Math.random().toFloat() - 0.5f) * 1.5f,
                    0f, 0.35f + Math.random().toFloat() * 0.3f, 0.06f, 0.28f, 0.45f, 0.02f, 0.02f, 0.8f, 0f, 2f, 4f))
            }
            for (i in 0 until 6) {
                smoke.emit(ParticleDef(p.x, p.y, p.z,
                    dir.x * 3 + (Math.random().toFloat() - 0.5f) * 2, Math.random().toFloat() * 2, dir.z * 3 + (Math.random().toFloat() - 0.5f) * 2,
                    0f, 0.6f, 0.035f, 0.03f, 0.35f, 0f, 0f, 1f, 0.6f, 9f, 0.5f, floor = 0.01f))
            }
            return
        }
        if (mat == "metal") {
            val cnt = 6 + (Math.random() * 6).toInt()
            for (i in 0 until cnt) {
                val sp = 3 + Math.random().toFloat() * 5
                add.emit(ParticleDef(p.x, p.y, p.z,
                    (nx + (Math.random().toFloat() - 0.5f) * 1.4f) * sp, (ny + Math.random().toFloat() * 0.8f) * sp, (nz + (Math.random().toFloat() - 0.5f) * 1.4f) * sp,
                    0f, 0.18f + Math.random().toFloat() * 0.25f, 0.035f, 0.01f, 1.6f, 1.1f, 0.5f, 1f, 0.3f, 9f, 1.5f))
            }
            add.emit(ParticleDef(p.x + nx * 0.02f, p.y + ny * 0.02f, p.z + nz * 0.02f, 0f, 0f, 0f,
                0f, 0.05f, 0.25f, 0.1f, 1.5f, 1.2f, 0.7f, 1f, 0f, 0f, 0f))
            addDecal(decalMetal, decalMaxMetal, { decalMI = (decalMI + 1) }, p, n, 0.07f + Math.random().toFloat() * 0.03f)
        } else if (mat == "wood") {
            for (i in 0 until 8) {
                smoke.emit(ParticleDef(p.x, p.y, p.z,
                    (nx + (Math.random().toFloat() - 0.5f)) * 3, (ny + Math.random().toFloat()) * 3, (nz + (Math.random().toFloat() - 0.5f)) * 3,
                    0f, 0.6f, 0.03f, 0.025f, 0.55f, 0.4f, 0.25f, 1f, 0.8f, 9f, 1f, floor = 0.01f))
            }
            addDecal(decalWood, decalMaxWood, { decalWI = (decalWI + 1) }, p, n, 0.08f + Math.random().toFloat() * 0.03f)
        }
        // 尘烟
        val dust = if (mat == "wood") floatArrayOf(0.62f, 0.52f, 0.4f) else floatArrayOf(0.55f, 0.55f, 0.53f)
        for (i in 0 until 3) {
            smoke.emit(ParticleDef(p.x + nx * 0.05f, p.y + ny * 0.05f, p.z + nz * 0.05f,
                nx * (0.5f + Math.random().toFloat()) + (Math.random().toFloat() - 0.5f) * 0.4f,
                ny * 0.8f + 0.3f,
                nz * (0.5f + Math.random().toFloat()) + (Math.random().toFloat() - 0.5f) * 0.4f,
                0f, 0.8f + Math.random().toFloat() * 0.6f, 0.12f, 0.6f, dust[0], dust[1], dust[2], 0.35f, 0f, -0.2f, 2.5f))
        }
    }

    private fun addDecal(list: ArrayList<DecalQuad>, max: Int, bump: () -> Unit, p: Vec3, n: Vec3, size: Float) {
        val q = DecalQuad(p.x + n.x * 0.004f, p.y + n.y * 0.004f, p.z + n.z * 0.004f, n.x, n.y, n.z, size)
        if (list.size < max) list.add(q) else { list[(Math.random() * max).toInt()] = q }
        bump()
        decalDirty = true
    }

    fun bloodSplat(p: Vec3) {
        addDecal(decalBlood, decalMaxBlood, { decalBI = (decalBI + 1) }, p, Vec3(0f, 1f, 0f), 0.5f + Math.random().toFloat() * 0.4f)
    }

    fun explosion(p: Vec3) {
        light(p, 60f, 0.35f, 0xffFF9040.toInt(), 22f)
        add.emit(ParticleDef(p.x, p.y + 0.3f, p.z, 0f, 0f, 0f, 0f, 0.18f, 2f, 7f, 2.2f, 1.6f, 0.9f, 1f, 0f, 0f, 0f))
        for (i in 0 until 26) {
            val a = Math.random().toFloat() * 6.28f
            val e = Math.random().toFloat() * 1.2f
            val sp = 3 + Math.random().toFloat() * 6
            add.emit(ParticleDef(p.x, p.y + 0.3f, p.z,
                cos(a) * cos(e) * sp, sin(e) * sp + 1, sin(a) * cos(e) * sp,
                0f, 0.35f + Math.random().toFloat() * 0.35f, 0.9f, 2.2f, 1.8f, 0.8f, 0.25f, 0.9f, 0f, -1f, 4f))
        }
        for (i in 0 until 40) {
            val a = Math.random().toFloat() * 6.28f
            val sp = 8 + Math.random().toFloat() * 14
            add.emit(ParticleDef(p.x, p.y + 0.2f, p.z,
                cos(a) * sp, Math.random().toFloat() * 10, sin(a) * sp,
                0f, 0.4f + Math.random().toFloat() * 0.5f, 0.06f, 0.02f, 1.8f, 1.2f, 0.5f, 1f, 0.4f, 12f, 1.2f, floor = 0.02f))
        }
        for (i in 0 until 22) {
            val a = Math.random().toFloat() * 6.28f
            val sp = 1 + Math.random().toFloat() * 3
            smoke.emit(ParticleDef(p.x + cos(a) * 0.5f, p.y + 0.4f + Math.random().toFloat(), p.z + sin(a) * 0.5f,
                cos(a) * sp, 1 + Math.random().toFloat() * 2, sin(a) * sp,
                0f, 3f + Math.random().toFloat() * 3f, 1.2f, 5f, 0.22f, 0.21f, 0.2f, 0.75f, 0f, -0.4f, 1.2f))
        }
        addDecal(decalScorch, decalMaxScorch, { decalSI = (decalSI + 1) }, Vec3(p.x, 0.01f, p.z), Vec3(0f, 1f, 0f), 3.2f)
        shake = kotlin.math.max(shake, 1f)
    }

    fun initAmbient(funnelTop: Vec3) {
        this.funnelTop = funnelTop
    }

    fun update(dt: Float, camPos: Vec3, camRight: Vec3, camUp: Vec3, shipSpeed: Float) {
        shake *= exp(-dt * 5f)
        for (F in flashes) if (F.t > 0) F.t -= dt
        for (L in lights) {
            if (L.t > 0) { L.t -= dt; L.peak *= 1f } // 强度在 Game 侧换算
        }
        // 烟囱排烟
        val f = funnelTop
        if (f != null) {
            funnelT -= dt
            if (funnelT <= 0) {
                funnelT = 0.14f
                val g = 0.18f + Math.random().toFloat() * 0.1f
                smoke.emit(ParticleDef(
                    f.x + (Math.random().toFloat() - 0.5f) * 2, f.y, f.z + (Math.random().toFloat() - 0.5f) * 1.5f,
                    -shipSpeed * 0.9f - Math.random().toFloat(), 2.2f + Math.random().toFloat(), (Math.random().toFloat() - 0.5f) * 0.8f,
                    0f, 9f, 2.2f, 11f, g, g, g * 1.02f, 0.42f, 0f, -0.05f, 0.08f))
            }
        }
    }

    // ---------- 网格构建 ----------
    fun buildDecalMeshes(meshes: Map<String, DynamicMesh>) {
        if (!decalDirty) return
        decalDirty = false
        meshes["metal"]?.let { fill(it, decalMetal) }
        meshes["wood"]?.let { fill(it, decalWood) }
        meshes["blood"]?.let { fill(it, decalBlood) }
        meshes["scorch"]?.let { fill(it, decalScorch) }
    }

    private fun fill(mesh: DynamicMesh, list: List<DecalQuad>) {
        mesh.reset()
        for (q in list) {
            // 法线朝向的四边形
            val n = Vec3(q.nx, q.ny, q.nz)
            var up = Vec3(0f, 1f, 0f)
            if (kotlin.math.abs(n.y) > 0.9f) up = Vec3(0f, 0f, 1f)
            val right = up.cross(n).normalize()
            val upv = n.cross(right).normalize()
            val s = q.size / 2
            val a = q.x + n.x * 0f
            val p0 = floatArrayOf(q.x - right.x * s - upv.x * s, q.y - right.y * s - upv.y * s, q.z - right.z * s - upv.z * s)
            val p1 = floatArrayOf(q.x + right.x * s - upv.x * s, q.y + right.y * s - upv.y * s, q.z + right.z * s - upv.z * s)
            val p2 = floatArrayOf(q.x + right.x * s + upv.x * s, q.y + right.y * s + upv.y * s, q.z + right.z * s + upv.z * s)
            val p3 = floatArrayOf(q.x - right.x * s + upv.x * s, q.y - right.y * s + upv.y * s, q.z - right.z * s + upv.z * s)
            mesh.quad(
                p0[0], p0[1], p0[2], 0f, 1f,
                p1[0], p1[1], p1[2], 1f, 1f,
                p2[0], p2[1], p2[2], 1f, 0f,
                p3[0], p3[1], p3[2], 0f, 0f,
            )
        }
    }

    fun buildTracerMesh(mesh: DynamicMesh, camPos: Vec3) {
        mesh.reset()
        val speed = 380f
        var n = 0
        val it = tracers.iterator()
        while (it.hasNext()) {
            val tr = it.next()
            tr.t += 0f // 由 Game 传入 dt 推进（见 advanceTracers）
            val head = min(tr.len, tr.t * speed)
            val tail = kotlin.math.max(0f, head - 7f)
            if (tail >= tr.len) { it.remove(); continue }
            if (n >= tracerMax) continue
            val mid = tr.from.copy().addScaled(tr.dir, (head + tail) / 2)
            val toCam = camPos.copy().sub(mid).normalize()
            val up = tr.dir.cross(toCam).normalize()
            val right = up.cross(tr.dir).normalize()
            val lenSeg = head - tail
            val wd = 0.018f + mid.dist(camPos) * 0.0012f
            val u0 = 1 - tail / tr.len
            val u1 = 1 - head / tr.len
            val p0 = mid.copy().addScaled(right, -wd).addScaled(up, -wd)
            val p1 = mid.copy().addScaled(right, wd).addScaled(up, -wd)
            val p2 = mid.copy().addScaled(right, wd).addScaled(up, wd)
            val p3 = mid.copy().addScaled(right, -wd).addScaled(up, wd)
            mesh.quad(
                p0.x - tr.dir.x * lenSeg / 2, p0.y - tr.dir.y * lenSeg / 2, p0.z - tr.dir.z * lenSeg / 2, u0, 0f,
                p1.x - tr.dir.x * lenSeg / 2, p1.y - tr.dir.y * lenSeg / 2, p1.z - tr.dir.z * lenSeg / 2, u0, 1f,
                p2.x + tr.dir.x * lenSeg / 2, p2.y + tr.dir.y * lenSeg / 2, p2.z + tr.dir.z * lenSeg / 2, u1, 1f,
                p3.x + tr.dir.x * lenSeg / 2, p3.y + tr.dir.y * lenSeg / 2, p3.z + tr.dir.z * lenSeg / 2, u1, 0f,
            )
            n++
        }
    }

    fun advanceTracers(dt: Float) {
        for (tr in tracers) tr.t += dt
    }

    /** 枪口火焰板（第三人称）：面向相机的四边形 */
    fun buildFlashMesh(mesh: DynamicMesh, camPos: Vec3, camRight: Vec3, camUp: Vec3) {
        mesh.reset()
        for (F in flashes) {
            if (F.t <= 0) continue
            val s = F.scale
            val cR = camRight; val cU = camUp
            val p0 = F.pos.copy().addScaled(cR, -s).addScaled(cU, -s)
            val p1 = F.pos.copy().addScaled(cR, s).addScaled(cU, -s)
            val p2 = F.pos.copy().addScaled(cR, s).addScaled(cU, s)
            val p3 = F.pos.copy().addScaled(cR, -s).addScaled(cU, s)
            val (u0, v0, u1, v1) = rotUV(F.rot)
            mesh.quad(p0.x, p0.y, p0.z, u0, v0, p1.x, p1.y, p1.z, u1, v0, p2.x, p2.y, p2.z, u1, v1, p3.x, p3.y, p3.z, u0, v1)
        }
    }

    private fun rotUV(rot: Float): FloatArray {
        if (rot == 0f) return floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)
        // 旋转 UV（近似：用四角旋转）
        val c = cos(rot); val s = sin(rot)
        fun r(u: Float, v: Float): FloatArray {
            val du = u - 0.5f; val dv = v - 0.5f
            return floatArrayOf(0.5f + du * c - dv * s, 0.5f + du * s + dv * c)
        }
        val a = r(0f, 1f); val b = r(1f, 1f); val c2 = r(1f, 0f); val d = r(0f, 0f)
        return floatArrayOf(a[0], a[1], b[0], b[1], c2[0], c2[1], d[0], d[1])
    }

    fun particleScaleOf(renderH: Int, fovDeg: Float): Float =
        (renderH / (2f * kotlin.math.tan(Math.toRadians(fovDeg.toDouble()) / 2f).toFloat()))
}

// 简单 vec 辅助（避免到处 new）
fun Vec3.copy(): Vec3 = Vec3(x, y, z)

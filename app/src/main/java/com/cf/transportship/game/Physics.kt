// 碰撞世界：所有静态碰撞体都是绕 Y 轴旋转的 OBB（移植 physics.js）
// 约定：yaw 旋转，local->world: (x,z) -> (c*x + s*z, -s*x + c*z)
package com.cf.transportship.game

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import com.cf.transportship.gfx.Rng

class Collider(o: ColliderOpts) {
    val x = o.x; val y = o.y; val z = o.z
    val hx = o.sx / 2f; val hy = o.sy / 2f; val hz = o.sz / 2f
    val yaw = o.yaw
    val c = cos(yaw); val s = sin(yaw)
    val mat: String = o.mat ?: "metal"
    val solid: Boolean = o.solid
    val bullet: String = o.bullet       // block | pass | pen
    val sight: Boolean = o.sight
    val surface: String = o.surface ?: (if (mat == "wood") "wood" else "metal")
    val top = y + hy; val bottom = y - hy
    val ex = abs(c) * hx + abs(s) * hz
    val ez = abs(s) * hx + abs(c) * hz
    val minX = x - ex; val maxX = x + ex
    val minZ = z - ez; val maxZ = z + ez
    var stamp = 0
    val tag: String = o.tag ?: ""

    fun toLocal(wx: Float, wz: Float): FloatArray {
        val dx = wx - x; val dz = wz - z
        return floatArrayOf(c * dx - s * dz, s * dx + c * dz)
    }
    fun toWorldDir(lx: Float, lz: Float): FloatArray = floatArrayOf(c * lx + s * lz, -s * lx + c * lz)
}

class ColliderOpts(
    val x: Float, val y: Float, val z: Float,
    val sx: Float, val sy: Float, val sz: Float,
    val yaw: Float = 0f,
    val mat: String? = null,
    val solid: Boolean = true,
    val bullet: String = "block",
    val sight: Boolean = true,
    val surface: String? = null,
    val tag: String? = null,
)

class RayHit {
    var t = 0f; var exit = 0f
    var nx = 0f; var ny = 0f; var nz = 0f
    var collider: Collider? = null
}

class MoveEnt {
    var x = 0f; var y = 0f; var z = 0f   // 脚底位置
    var velX = 0f; var velY = 0f; var velZ = 0f
    var radius = 0.36f
    var height = 1.8f
    var onGround = false
    var stepHeight = 0.42f
    var ground: Collider? = null
    var landed = false; var landSpeed = 0f
    var stepped = 0f
    var groundSurface = "metal"
}

class SupportRes(val y: Float, val collider: Collider)

private const val CELL = 4f

class World {
    val colliders = ArrayList<Collider>()
    private val grid = HashMap<Long, ArrayList<Collider>>()
    private var stamp = 1
    private val candA = ArrayList<Collider>()
    private val candB = ArrayList<Collider>()
    private var candFlip = false
    private val ro = RayHit()
    private val co = FloatArray(3)
    private var built = false

    fun add(o: ColliderOpts): Collider {
        val c = Collider(o)
        colliders.add(c)
        return c
    }

    fun addCollider(c: Collider): Collider { colliders.add(c); return c }

    fun build() {
        grid.clear()
        for (c in colliders) {
            val x0 = floor(c.minX / CELL).toInt(); val x1 = floor(c.maxX / CELL).toInt()
            val z0 = floor(c.minZ / CELL).toInt(); val z1 = floor(c.maxZ / CELL).toInt()
            for (gx in x0..x1) for (gz in z0..z1) {
                val k = gx.toLong() * 100000L + gz
                var arr = grid[k]
                if (arr == null) { arr = ArrayList(); grid[k] = arr }
                arr.add(c)
            }
        }
        built = true
    }

    fun query(minX: Float, minZ: Float, maxX: Float, maxZ: Float): List<Collider> {
        val out = if (candFlip) candA else candB
        candFlip = !candFlip
        out.clear()
        val st = ++stamp
        val x0 = floor(minX / CELL).toInt(); val x1 = floor(maxX / CELL).toInt()
        val z0 = floor(minZ / CELL).toInt(); val z1 = floor(maxZ / CELL).toInt()
        for (gx in x0..x1) for (gz in z0..z1) {
            val arr = grid[(gx.toLong() * 100000L + gz)] ?: continue
            for (c in arr) {
                if (c.stamp == st) continue
                c.stamp = st
                if (c.maxX < minX || c.minX > maxX || c.maxZ < minZ || c.minZ > maxZ) continue
                out.add(c)
            }
        }
        return out
    }

    companion object {
        /** 射线 vs 单个 OBB，填充 res；返回是否命中 */
        fun rayOBB(c: Collider, ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxT: Float, res: RayHit): Boolean {
            val rx = ox - c.x; val rz = oz - c.z
            val lox = c.c * rx - c.s * rz; val loz = c.s * rx + c.c * rz; val loy = oy - c.y
            val ldx = c.c * dx - c.s * dz; val ldz = c.s * dx + c.c * dz; val ldy = dy
            var tmin = 0f; var tmax = maxT; var axis = -1; var sign = 0f
            // X
            if (abs(ldx) < 1e-9f) { if (lox < -c.hx || lox > c.hx) return false }
            else {
                var t1 = (-c.hx - lox) / ldx; var t2 = (c.hx - lox) / ldx; var sg = -1f
                if (t1 > t2) { val t = t1; t1 = t2; t2 = t; sg = 1f }
                if (t1 > tmin) { tmin = t1; axis = 0; sign = sg }
                if (t2 < tmax) tmax = t2
                if (tmin > tmax) return false
            }
            // Y
            if (abs(ldy) < 1e-9f) { if (loy < -c.hy || loy > c.hy) return false }
            else {
                var t1 = (-c.hy - loy) / ldy; var t2 = (c.hy - loy) / ldy; var sg = -1f
                if (t1 > t2) { val t = t1; t1 = t2; t2 = t; sg = 1f }
                if (t1 > tmin) { tmin = t1; axis = 1; sign = sg }
                if (t2 < tmax) tmax = t2
                if (tmin > tmax) return false
            }
            // Z
            if (abs(ldz) < 1e-9f) { if (loz < -c.hz || loz > c.hz) return false }
            else {
                var t1 = (-c.hz - loz) / ldz; var t2 = (c.hz - loz) / ldz; var sg = -1f
                if (t1 > t2) { val t = t1; t1 = t2; t2 = t; sg = 1f }
                if (t1 > tmin) { tmin = t1; axis = 2; sign = sg }
                if (t2 < tmax) tmax = t2
                if (tmin > tmax) return false
            }
            if (axis < 0) { // 起点在盒内
                res.t = 0f; res.exit = tmax; res.nx = -dx; res.ny = -dy; res.nz = -dz
                return true
            }
            res.t = tmin; res.exit = tmax
            var lnx = 0f; var lny = 0f; var lnz = 0f
            when (axis) {
                0 -> lnx = sign
                1 -> lny = sign
                else -> lnz = sign
            }
            res.nx = c.c * lnx + c.s * lnz; res.ny = lny; res.nz = -c.s * lnx + c.c * lnz
            return true
        }

        /** 圆（XZ）与 OBB 的最近点关系；out = [nx, nz, pen] */
        fun circleOBB(c: Collider, px: Float, pz: Float, r: Float, out: FloatArray): Boolean {
            val l = c.toLocal(px, pz)
            val lx = l[0]; val lz = l[1]
            val qx = c.hx.coerceAtMost(lx.coerceAtLeast(-c.hx))
            val qz = c.hz.coerceAtMost(lz.coerceAtLeast(-c.hz))
            val dx = lx - qx; val dz = lz - qz
            val d2 = dx * dx + dz * dz
            if (d2 >= r * r) return false
            if (d2 > 1e-10f) {
                val d = sqrt(d2)
                val w = c.toWorldDir(dx / d, dz / d)
                out[0] = w[0]; out[1] = w[1]; out[2] = r - d
            } else {
                val px2 = c.hx - abs(lx); val pz2 = c.hz - abs(lz)
                val lnx: Float; val lnz: Float; val pen: Float
                if (px2 < pz2) { lnx = if (lx >= 0) 1f else -1f; lnz = 0f; pen = px2 + r } else { lnx = 0f; lnz = if (lz >= 0) 1f else -1f; pen = pz2 + r }
                val w = c.toWorldDir(lnx, lnz)
                out[0] = w[0]; out[1] = w[1]; out[2] = pen
            }
            return true
        }
    }

    /** 射线检测。mode: 'move' | 'bullet' | 'sight'。返回最近命中（复用对象，勿长期持有） */
    fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxT: Float, mode: String = "bullet"): RayHit? {
        val ex = ox + dx * maxT; val ez = oz + dz * maxT
        val cands = query(minOf(ox, ex) - 0.1f, minOf(oz, ez) - 0.1f, maxOf(ox, ex) + 0.1f, maxOf(oz, ez) + 0.1f)
        var best = maxT; var hit: Collider? = null
        for (c in cands) {
            if (mode == "bullet" && c.bullet == "pass") continue
            if (mode == "sight" && !c.sight) continue
            if (mode == "move" && !c.solid) continue
            if (rayOBB(c, ox, oy, oz, dx, dy, dz, best, ro) && ro.t < best) {
                best = ro.t; hit = c
            }
        }
        if (hit == null) return null
        val out = RayHit()
        out.t = best; out.collider = hit
        out.nx = ro.nx; out.ny = ro.ny; out.nz = ro.nz; out.exit = ro.exit
        return out
    }

    /** 返回沿射线所有命中（按 t 排序），用于穿透 */
    fun raycastAll(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxT: Float): List<RayHit> {
        val ex = ox + dx * maxT; val ez = oz + dz * maxT
        val cands = query(minOf(ox, ex) - 0.1f, minOf(oz, ez) - 0.1f, maxOf(ox, ex) + 0.1f, maxOf(oz, ez) + 0.1f)
        val hits = ArrayList<RayHit>()
        val r = RayHit()
        for (c in cands) {
            if (c.bullet == "pass") continue
            if (rayOBB(c, ox, oy, oz, dx, dy, dz, maxT, r)) {
                val h = RayHit()
                h.t = r.t; h.exit = r.exit; h.nx = r.nx; h.ny = r.ny; h.nz = r.nz; h.collider = c
                hits.add(h)
            }
        }
        hits.sortBy { it.t }
        return hits
    }

    /** 判断胶囊空间是否被占用（用于站起/上台阶检查） */
    fun blocked(px: Float, py: Float, pz: Float, r: Float, h: Float): Boolean {
        val cands = query(px - r, pz - r, px + r, pz + r)
        for (c in cands) {
            if (!c.solid) continue
            if (c.top <= py + 0.001f || c.bottom >= py + h - 0.001f) continue
            if (circleOBB(c, px, pz, r, co)) return true
        }
        return false
    }

    /** 脚下最高支撑面（top <= maxY） */
    fun support(px: Float, pz: Float, r: Float, maxY: Float): SupportRes? {
        val cands = query(px - r, pz - r, px + r, pz + r)
        var best = Float.NEGATIVE_INFINITY; var bc: Collider? = null
        for (c in cands) {
            if (!c.solid) continue
            if (c.top > maxY || c.top <= best) continue
            if (circleOBB(c, px, pz, r, co)) { best = c.top; bc = c }
        }
        return if (bc != null) SupportRes(best, bc) else null
    }

    /** 角色移动 */
    fun move(ent: MoveEnt, dt: Float) {
        val vx = ent.velX; val vy = ent.velY; val vz = ent.velZ
        val dist = hypot(hypot(vx, vy), vz) * dt
        val n = maxOf(1, ceil(dist / 0.12f).toInt())
        val h = dt / n
        val r = ent.radius
        var landed = false; var landSpeed = 0f
        for (step in 0 until n) {
            // 水平
            ent.x += ent.velX * h; ent.z += ent.velZ * h
            for (it2 in 0 until 3) {
                val cands = query(ent.x - r, ent.z - r, ent.x + r, ent.z + r)
                var any = false
                for (c in cands) {
                    if (!c.solid) continue
                    val head = ent.y + ent.height
                    if (c.top <= ent.y + 0.001f || c.bottom >= head - 0.001f) continue
                    if (!circleOBB(c, ent.x, ent.z, r, co)) continue
                    // 上台阶
                    val rise = c.top - ent.y
                    if (ent.onGround && rise > 0 && rise <= ent.stepHeight &&
                        !blocked(ent.x, c.top, ent.z, r * 0.95f, ent.height)
                    ) {
                        ent.y = c.top; ent.stepped += rise; any = true; continue
                    }
                    ent.x += co[0] * (co[2] + 0.0005f); ent.z += co[1] * (co[2] + 0.0005f)
                    val vn = ent.velX * co[0] + ent.velZ * co[1]
                    if (vn < 0) { ent.velX -= co[0] * vn; ent.velZ -= co[1] * vn }
                    any = true
                }
                if (!any) break
            }
            // 垂直
            val prevY = ent.y
            ent.y += ent.velY * h
            if (ent.velY <= 0) {
                val sup = support(ent.x, ent.z, r * 0.92f, prevY + 0.001f)
                val snap = if (ent.onGround && ent.velY <= 0.01f) maxOf(0.3f, -ent.velY * h) else 0f
                if (sup != null && ent.y <= sup.y + snap) {
                    if (!ent.onGround) { landed = true; landSpeed = -ent.velY }
                    ent.y = sup.y; ent.velY = 0f; ent.onGround = true; ent.ground = sup.collider
                    ent.groundSurface = sup.collider.surface
                } else {
                    ent.onGround = false; ent.ground = null
                }
            } else {
                ent.onGround = false; ent.ground = null
                // 顶头
                val cands = query(ent.x - r, ent.z - r, ent.x + r, ent.z + r)
                for (c in cands) {
                    if (!c.solid) continue
                    val headPrev = prevY + ent.height; val head = ent.y + ent.height
                    if (c.bottom >= headPrev - 0.01f && c.bottom < head && circleOBB(c, ent.x, ent.z, r * 0.9f, co)) {
                        ent.y = c.bottom - ent.height - 0.001f; ent.velY = 0f
                    }
                }
            }
        }
        ent.landed = landed; ent.landSpeed = landSpeed
    }
}

// ============== 寻路网格（地面层） ==============
class NavGrid(world: World, x0: Float, z0: Float, x1: Float, z1: Float, cell: Float, agentR: Float) {
    val x0 = x0; val z0 = z0; val cell = cell
    val w = ceil((x1 - x0) / cell).toInt()
    val h = ceil((z1 - z0) / cell).toInt()
    val block = ByteArray(w * h)
    val cost = FloatArray(w * h)
    private val g = FloatArray(w * h)
    private val f = FloatArray(w * h)
    private val parent = IntArray(w * h)
    private val seen = IntArray(w * h)
    private val closed = IntArray(w * h)
    private var gen = 1
    private val heap = IntArray(w * h + 1)
    private var heapN = 0
    private val out = FloatArray(3)

    init {
        for (j in 0 until h) for (i in 0 until w) {
            val cx = x0 + (i + 0.5f) * cell
            val cz = z0 + (j + 0.5f) * cell
            val cands = world.query(cx - agentR - 0.3f, cz - agentR - 0.3f, cx + agentR + 0.3f, cz + agentR + 0.3f)
            var b = 0; var near = 0
            for (c in cands) {
                if (!c.solid) continue
                if (c.bottom > 1.7f || c.top < 0.36f) continue
                if (World.circleOBB(c, cx, cz, agentR, out)) { b = 1; break }
                if (World.circleOBB(c, cx, cz, agentR + 0.45f, out)) near = 1
            }
            block[j * w + i] = b.toByte()
            cost[j * w + i] = if (near == 1) 1.6f else 1f
        }
        // 屋顶检查：头顶过低（< 2m）的格子也不可走
        for (j in 0 until h) for (i in 0 until w) {
            if (block[j * w + i].toInt() != 0) continue
            val cx = x0 + (i + 0.5f) * cell
            val cz = z0 + (j + 0.5f) * cell
            if (world.blocked(cx, 0.4f, cz, agentR * 0.8f, 1.4f)) block[j * w + i] = 1
        }
    }

    fun idx(x: Float, z: Float): Int {
        val i = floor((x - x0) / cell).toInt()
        val j = floor((z - z0) / cell).toInt()
        if (i < 0 || j < 0 || i >= w || j >= h) return -1
        return j * w + i
    }

    fun centerX(k: Int) = x0 + ((k % w) + 0.5f) * cell
    fun centerZ(k: Int) = z0 + ((k / w) + 0.5f) * cell
    fun walkable(k: Int) = k >= 0 && block[k].toInt() == 0

    fun nearestFree(k: Int): Int {
        if (walkable(k)) return k
        val i0 = k % w; val j0 = k / w
        for (r in 1 until 12) {
            for (dj in -r..r) for (di in -r..r) {
                if (maxOf(abs(di), abs(dj)) != r) continue
                val i = i0 + di; val j = j0 + dj
                if (i < 0 || j < 0 || i >= w || j >= h) continue
                val kk = j * w + i
                if (block[kk].toInt() == 0) return kk
            }
        }
        return -1
    }

    /** 网格视线（采样） */
    fun lineFree(ax: Float, az: Float, bx: Float, bz: Float): Boolean {
        val d = hypot(bx - ax, bz - az)
        val n = ceil(d / (cell * 0.5f)).toInt()
        for (i in 1 until n) {
            val t = i.toFloat() / n
            val k = idx(ax + (bx - ax) * t, az + (bz - az) * t)
            if (k < 0 || block[k].toInt() != 0) return false
        }
        return true
    }

    private fun heapPush(k: Int) {
        heap[heapN++] = k
        var i = heapN - 1
        while (i > 0) {
            val p = (i - 1) shr 1
            if (f[heap[p]] <= f[heap[i]]) break
            val t = heap[p]; heap[p] = heap[i]; heap[i] = t
            i = p
        }
    }

    private fun heapPop(): Int {
        val top = heap[0]
        heapN--
        if (heapN > 0) {
            heap[0] = heap[heapN]
            var i = 0
            while (true) {
                val l = i * 2 + 1; val r = l + 1; var m = i
                if (l < heapN && f[heap[l]] < f[heap[m]]) m = l
                if (r < heapN && f[heap[r]] < f[heap[m]]) m = r
                if (m == i) break
                val t = heap[m]; heap[m] = heap[i]; heap[i] = t
                i = m
            }
        }
        return top
    }

    fun findPath(ax: Float, az: Float, bx: Float, bz: Float): Array<FloatArray>? {
        var s = idx(ax, az); var e = idx(bx, bz)
        if (s < 0 || e < 0) return null
        s = nearestFree(s); e = nearestFree(e)
        if (s < 0 || e < 0) return null
        val g2 = ++gen
        val ex = e % w; val ez = e / w
        heapN = 0
        g[s] = 0f
        f[s] = hfn(s, ex, ez)
        seen[s] = g2; parent[s] = -1
        heapPush(s)
        var found = false; var iter = 0
        while (heapN > 0 && iter++ < 20000) {
            val k = heapPop()
            if (closed[k] == g2) continue
            closed[k] = g2
            if (k == e) { found = true; break }
            val ki = k % w; val kj = k / w
            for (dj in -1..1) for (di in -1..1) {
                if (di == 0 && dj == 0) continue
                val ni = ki + di; val nj = kj + dj
                if (ni < 0 || nj < 0 || ni >= w || nj >= h) continue
                val nk = nj * w + ni
                if (block[nk].toInt() != 0 || closed[nk] == g2) continue
                if (di != 0 && dj != 0 && (block[kj * w + ni].toInt() != 0 || block[nj * w + ki].toInt() != 0)) continue
                val ng = g[k] + (if (di != 0 && dj != 0) 1.4142f else 1f) * cost[nk]
                if (seen[nk] != g2 || ng < g[nk]) {
                    seen[nk] = g2; g[nk] = ng
                    f[nk] = ng + hfn(nk, ex, ez)
                    parent[nk] = k
                    heapPush(nk)
                }
            }
        }
        if (!found) return null
        val raw = ArrayList<FloatArray>()
        var k = e
        while (k != -1) {
            raw.add(floatArrayOf(centerX(k), centerZ(k)))
            k = parent[k]
        }
        raw.reverse()
        raw[raw.size - 1] = floatArrayOf(bx, bz)
        // 拉直
        val outList = ArrayList<FloatArray>()
        outList.add(raw[0])
        var a = 0
        while (a < raw.size - 1) {
            var b = raw.size - 1
            while (b > a + 1 && !lineFree(raw[a][0], raw[a][1], raw[b][0], raw[b][1])) b--
            outList.add(raw[b]); a = b
        }
        return outList.toTypedArray()
    }

    private fun hfn(k: Int, ex: Int, ez: Int): Float {
        val dx = abs(k % w - ex)
        val dz = abs(k / w - ez)
        return dx + dz + (1.4142f - 2f) * min(dx, dz)
    }

    fun randomFree(rnd: () -> Float, x0: Float, z0: Float, x1: Float, z1: Float): FloatArray? {
        for (t in 0 until 60) {
            val x = x0 + rnd() * (x1 - x0)
            val z = z0 + rnd() * (z1 - z0)
            val k = idx(x, z)
            if (walkable(k)) return floatArrayOf(x, z)
        }
        return null
    }
}

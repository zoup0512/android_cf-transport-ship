// 运输船地图构建（移植 map.js）
// 坐标：X 沿船长方向（-X 船尾/上层建筑/潜伏者出生点，+X 船头/保卫者出生点），Z 沿船宽，Y 向上，甲板 Y=0，海面 Y=-7.5
package com.cf.transportship.game

import android.graphics.Bitmap
import android.graphics.Paint
import com.cf.transportship.engine.Mat4
import com.cf.transportship.engine.Vec3
import com.cf.transportship.gfx.Ctx
import com.cf.transportship.gfx.Geo
import com.cf.transportship.gfx.Prims
import com.cf.transportship.gfx.Rng
import com.cf.transportship.gfx.SIGN_UV
import com.cf.transportship.gfx.Textures
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

const val CH = 2.59f
const val CW = 2.44f
const val L20 = 6.06f
const val L40 = 12.19f
const val SEA_Y = -7.5f
const val DECK_HALF_Z = 9.4f
const val H2 = 2.6f

class SpawnPoint(val x: Float, val z: Float, val yaw: Float)

/** 动画网格（模型矩阵每帧变化） */
class MapMesh(val parts: List<Pair<MatDef, Geo>>, var model: Mat4 = Mat4())

class GameMap(
    val batches: List<Triple<MatDef, Batch, FloatArray?>>, // 材质、几何、AO uv 区间（null=全图）
    val spawns: Map<String, List<SpawnPoint>>,
    val lampSpots: List<Vec3>,
    val funnelTop: Vec3,
    val hanging: MapMesh,
    val radar: MapMesh,
    val aoMap: Bitmap,
) {
}

class MapBuilder(val T: Textures, val world: World) {
    private val rnd = Rng(2024)
    val batches = LinkedHashMap<String, Batch>()
    val matDefs = LinkedHashMap<String, MatDef>()
    val footprints = ArrayList<FloatArray>()
    val lampSpots = ArrayList<Vec3>()
    val cylG = Prims.cylinder(1f, 1f, 1f, 16)
    val cylG8 = Prims.cylinder(1f, 1f, 1f, 8)
    val sphG = Prims.sphere(1f, 10, 8)
    val torG = Prims.torus(1f, 1f, 8, 20)

    fun defMat(
        key: String, map: Bitmap? = null, normalMap: Bitmap? = null, roughnessMap: Bitmap? = null,
        color: Int = -1, colorLinear: FloatArray? = null,
        roughness: Float = 1f, metalness: Float = 0f,
        emissiveMap: Bitmap? = null, emissive: Int = 0, emissiveLinear: FloatArray? = null, emissiveIntensity: Float = 1f,
        uv: Any = "unit", alphaTest: Float = 0f, twoSided: Boolean = false, shadow: Boolean = true,
    ) {
        val col = colorLinear ?: (if (color == -1) floatArrayOf(1f, 1f, 1f) else linearOf(color))
        val em = emissiveLinear ?: (if (emissive == 0) floatArrayOf(0f, 0f, 0f) else {
            val l = linearOf(emissive)
            floatArrayOf(l[0] * emissiveIntensity, l[1] * emissiveIntensity, l[2] * emissiveIntensity)
        })
        matDefs[key] = MatDef(key, map, normalMap, roughnessMap, col, roughness, metalness, emissiveMap, em, uv, alphaTest, twoSided, shadow)
    }

    fun mat(key: String): MatDef = matDefs[key]!!

    fun batch(key: String): Batch = batches.getOrPut(key) { Batch() }

    // ---------- 盒子：faces 为各面材质或单一材质 ----------
    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, yaw: Float, faces: Any, uvOff: FloatArray? = null) {
        val c = cos(yaw); val s = sin(yaw)
        val half = floatArrayOf(sx / 2, sy / 2, sz / 2)
        val size = floatArrayOf(sx, sy, sz)
        val off = uvOff ?: floatArrayOf(rnd.next(), rnd.next())
        val faceDefs = arrayOf(
            Triple("px", floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f)),
            Triple("nx", floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f)),
            Triple("py", floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f)),
            Triple("ny", floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f)),
            Triple("pz", floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f)),
            Triple("nz", floatArrayOf(0f, 0f, -1f), floatArrayOf(-1f, 0f, 0f)),
        )
        for ((fk, nn, u) in faceDefs) {
            val key = if (faces is String) faces else (faces as? Map<String, String?>)?.get(fk) ?: continue
            if (key.isEmpty()) continue
            val v = when (fk) {
                "px", "nx" -> floatArrayOf(0f, 1f, 0f)
                "py" -> floatArrayOf(0f, 0f, -1f)
                "ny" -> floatArrayOf(0f, 0f, 1f)
                else -> floatArrayOf(0f, 1f, 0f)
            }
            val def = matDefs[key] ?: continue
            val su = kotlin.math.abs(u[0] * size[0] + u[1] * size[1] + u[2] * size[2])
            val sv = kotlin.math.abs(v[0] * size[0] + v[1] * size[1] + v[2] * size[2])
            val ctr = floatArrayOf(nn[0] * half[0], nn[1] * half[1], nn[2] * half[2])
            fun corner(a: Float, b: Float): FloatArray {
                val lx = ctr[0] + u[0] * su * a + v[0] * sv * b
                val ly = ctr[1] + u[1] * su * a + v[1] * sv * b
                val lz = ctr[2] + u[2] * su * a + v[2] * sv * b
                return floatArrayOf(cx + c * lx + s * lz, cy + ly, cz - s * lx + c * lz)
            }
            val n = floatArrayOf(c * nn[0] + s * nn[2], nn[1], -s * nn[0] + c * nn[2])
            var u0 = 0f; var v0 = 0f; var u1 = 1f; var v1 = 1f
            if (def.uv != "unit" && def.uv != "custom") {
                val tu = if (def.uv is FloatArray) def.uv[0] else def.uv as Float
                val tv = if (def.uv is FloatArray) def.uv[1] else def.uv as Float
                u0 = off[0]
                v0 = if (def.uv is FloatArray) 0f else off[1]
                u1 = u0 + su / tu
                v1 = v0 + sv / tv
            }
            batch(key).quad(corner(-0.5f, -0.5f), corner(0.5f, -0.5f), corner(0.5f, 0.5f), corner(-0.5f, 0.5f), n,
                floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1))
        }
    }

    fun solid(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, yaw: Float, props: SolidProps = SolidProps()) {
        world.add(ColliderOpts(cx, cy, cz, sx, sy, sz, yaw, props.mat, props.solid, props.bullet, props.sight, props.surface, props.tag))
    }

    fun foot(cx: Float, cz: Float, sx: Float, sz: Float, yaw: Float, dark: Float = 0.6f) {
        footprints.add(floatArrayOf(cx, cz, sx, sz, yaw, dark))
    }

    fun geom(key: String, g: Geo, x: Float, y: Float, z: Float, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f) {
        val m = matTRS(x, y, z, ry, rx, rz, sx, sy, sz)
        batch(key).geom(g, m)
    }

    /** 两点之间的圆柱 */
    fun rod(key: String, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float) {
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        val L = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        val d = floatArrayOf(dx / L, dy / L, dz / L)
        // 从 (0,1,0) 旋转到 d
        val q = com.cf.transportship.engine.Quat().setFromUnitVectors(
            com.cf.transportship.engine.Vec3(0f, 1f, 0f), com.cf.transportship.engine.Vec3(d[0], d[1], d[2]))
        val m = Mat4().compose((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2, q, r, L, r)
        batch(key).geom(cylG8, m)
    }

    /** 自定义 UV 的矩形贴片（标识牌） */
    fun decal(key: String, cx: Float, cy: Float, cz: Float, w: Float, h: Float, yaw: Float, pitch: Float, rect: FloatArray, texW: Int = 1024) {
        val c = cos(yaw); val s = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        fun tr(lx: Float, ly: Float): FloatArray {
            val y1 = ly * cp; val z1 = ly * sp
            return floatArrayOf(cx + c * lx + s * z1, cy + y1, cz - s * lx + c * z1)
        }
        val nz = floatArrayOf(s * cp, -sp, c * cp)
        val rx = rect[0]; val ry = rect[1]; val rw = rect[2]; val rh = rect[3]
        val u0 = rx / texW; val u1 = (rx + rw) / texW
        val v1 = 1 - ry / texW; val v0 = 1 - (ry + rh) / texW
        batch(key).quad(tr(-w / 2, -h / 2), tr(w / 2, -h / 2), tr(w / 2, h / 2), tr(-w / 2, h / 2), nz,
            floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1))
    }

    // ---------- 预制件 ----------
    fun container(x: Float, z: Float, yawDeg: Float, len: Int, colorIdx: Int, level: Int = 0, o: ContainerOpts = ContainerOpts()) {
        val L = if (len == 40) L40 else L20
        val yaw = yawDeg * PI.toFloat() / 180f
        val cy = level * CH + CH / 2
        val ci = colorIdx % T.containers.size
        val side = "c${ci}${if (len == 40) "s40" else "s20"}"
        box(x, cy, z, L, CH, CW, yaw, mapOf(
            "px" to (if (o.noEnds) "" else "c${ci}door"),
            "nx" to (if (o.noEnds) "" else "c${ci}door"),
            "py" to (if (o.noTop) "" else "c${ci}roof"),
            "ny" to "",
            "pz" to side,
            "nz" to side,
        ))
        if (o.collide) solid(x, cy, z, L, CH, CW, yaw, SolidProps(mat = "metal", surface = "container", tag = "container"))
        if (level == 0) foot(x, z, L, CW, yaw)
    }

    fun crate(x: Float, z: Float, sx: Float, sy: Float, sz: Float, idx: Int, y: Float = 0f, yawDeg: Float = 0f) {
        val yaw = yawDeg * PI.toFloat() / 180f
        box(x, y + sy / 2, z, sx, sy, sz, yaw, "crate$idx")
        val wood = T.crates[idx].kind == "wood"
        solid(x, y + sy / 2, z, sx, sy, sz, yaw, SolidProps(
            mat = if (wood) "wood" else "metal",
            bullet = if (wood) "pen" else "block",
            surface = if (wood) "wood" else "metal",
        ))
        if (y < 0.05f) foot(x, z, sx, sz, yaw, 0.45f)
    }

    fun barrel(x: Float, z: Float, colorKey: String = "red", y: Float = 0f) {
        geom(colorKey, cylG, x, y + 0.45f, z, 0f, rnd.next() * 6f, 0f, 0.3f, 0.9f, 0.3f)
        geom("black", cylG, x, y + 0.9f, z, 0f, 0f, 0f, 0.29f, 0.02f, 0.29f)
        for (hy in floatArrayOf(0.25f, 0.65f)) geom("darkSteel", cylG, x, y + hy, z, 0f, 0f, 0f, 0.305f, 0.03f, 0.305f)
        solid(x, y + 0.45f, z, 0.56f, 0.9f, 0.56f, 0f, SolidProps(mat = "metal", bullet = "pen"))
        foot(x, z, 0.6f, 0.6f, 0f, 0.4f)
    }

    fun railing(x0: Float, z0: Float, x1: Float, z1: Float, y: Float = 0f, key: String = "railYellow", h: Float = 1.1f, collide: Boolean = true) {
        val dx = x1 - x0; val dz = z1 - z0
        val L = hypot(dx, dz)
        val yaw = atan2(-dz, dx)
        val mx = (x0 + x1) / 2; val mz = (z0 + z1) / 2
        val n = maxOf(1, (L / 1.5f).toInt())
        for (i in 0..n) {
            val t = i.toFloat() / n
            geom(key, cylG8, x0 + dx * t, y + h / 2, z0 + dz * t, 0f, 0f, 0f, 0.03f, h, 0.03f)
        }
        for (hh in floatArrayOf(h, h * 0.5f, 0.08f)) {
            rod(key, x0, y + hh, z0, x1, y + hh, z1, if (hh == 0.08f) 0.02f else 0.028f)
        }
        if (collide) solid(mx, y + 0.8f, mz, L, 1.6f, 0.1f, yaw, SolidProps(bullet = "pass", sight = false, mat = "metal"))
    }

    fun fence(x0: Float, z0: Float, x1: Float, z1: Float, y0: Float, y1: Float, frameKey: String = "railYellow", collide: Boolean = true) {
        val dx = x1 - x0; val dz = z1 - z0
        val L = hypot(dx, dz)
        val yaw = atan2(-dz, dx)
        val mx = (x0 + x1) / 2; val mz = (z0 + z1) / 2
        val h = y1 - y0
        box(mx, (y0 + y1) / 2, mz, L, h, 0.001f, yaw, mapOf("pz" to "fence", "nz" to "", "px" to "", "nx" to "", "py" to "", "ny" to ""))
        geom(frameKey, cylG8, x0, (y0 + y1) / 2, z0, 0f, 0f, 0f, 0.04f, h, 0.04f)
        geom(frameKey, cylG8, x1, (y0 + y1) / 2, z1, 0f, 0f, 0f, 0.04f, h, 0.04f)
        rod(frameKey, x0, y1, z0, x1, y1, z1, 0.035f)
        rod(frameKey, x0, y0 + 0.02f, z0, x1, y0 + 0.02f, z1, 0.035f)
        if (collide) solid(mx, (y0 + y1) / 2, mz, L, h, 0.08f, yaw, SolidProps(bullet = "pass", sight = false, mat = "mesh"))
    }

    fun lamp(x: Float, y: Float, z: Float, pointLight: Boolean = false) {
        geom("lamp", sphG, x, y, z, 0f, 0f, 0f, 0.09f, 0.09f, 0.09f)
        geom("darkSteel", cylG8, x, y + 0.1f, z, 0f, 0f, 0f, 0.12f, 0.05f, 0.12f)
        if (pointLight) lampSpots.add(Vec3(x, y - 0.15f, z))
    }

    fun lifeRing(x: Float, y: Float, z: Float, yaw: Float) {
        geom("orange", torG, x, y, z, 0f, yaw, 0f, 0.32f, 0.32f, 0.07f)
        for (i in 0 until 4) {
            val a = i * PI.toFloat() / 2 + PI.toFloat() / 4
            geom("white", cylG8, x + cos(yaw) * cos(a) * 0.32f, y + sin(a) * 0.32f, z - sin(yaw) * cos(a) * 0.32f,
                0f, yaw, a + PI.toFloat() / 2, 0.075f, 0.12f, 0.075f)
        }
    }
}

class ContainerOpts(val collide: Boolean = true, val noEnds: Boolean = false, val noTop: Boolean = false)

class SolidProps(
    val mat: String? = null,
    val solid: Boolean = true,
    val bullet: String = "block",
    val sight: Boolean = true,
    val surface: String? = null,
    val tag: String? = null,
)

// ================== 主构建 ==================
fun buildMap(T: Textures, world: World): GameMap {
    val B = MapBuilder(T, world)
    val animMeshes = ArrayList<MapMesh>()

    // ---------- 材质 ----------
    B.defMat("deck", T.deck.map, T.deck.normalMap, T.deck.roughnessMap, roughness = 1.15f, metalness = 0.12f, uv = 4f, shadow = false)
    T.containers.forEachIndexed { i, c ->
        val r = c.color.rgb[0]; val g = c.color.rgb[1]; val b = c.color.rgb[2]
        B.defMat("c${i}s20", c.side20, c.n20, roughness = 0.62f, metalness = 0.3f)
        B.defMat("c${i}s40", c.side40, c.n40, roughness = 0.62f, metalness = 0.3f)
        B.defMat("c${i}door", c.door, c.doorN, roughness = 0.6f, metalness = 0.3f)
        B.defMat("c${i}roof", c.roof, c.roofN,
            colorLinear = floatArrayOf((r * 0.6f + 60) / 200f, (g * 0.6f + 60) / 200f, (b * 0.6f + 60) / 200f),
            roughness = 0.75f, metalness = 0.25f)
    }
    T.crates.forEachIndexed { i, c ->
        B.defMat("crate$i", c.map, c.normalMap, roughness = if (c.kind == "wood") 0.85f else 0.55f, metalness = if (c.kind == "wood") 0f else 0.4f)
    }
    B.defMat("bulkhead", T.bulkhead.map, T.bulkhead.normalMap, roughness = 0.6f, metalness = 0.25f, uv = 3f)
    B.defMat("darkSteel", T.darkSteel.map, T.darkSteel.normalMap, roughness = 0.55f, metalness = 0.5f, uv = 3f)
    B.defMat("interior", T.darkSteel.map, T.darkSteel.normalMap, color = 0x8a8a84, roughness = 0.7f, metalness = 0.3f, uv = 2.4f)
    B.defMat("yellow", T.yellowSteel.map, T.yellowSteel.normalMap, roughness = 0.5f, metalness = 0.2f, uv = 3f)
    B.defMat("red", T.redSteel.map, T.redSteel.normalMap, roughness = 0.5f, metalness = 0.2f, uv = 3f)
    B.defMat("green", T.greenSteel.map, T.greenSteel.normalMap, roughness = 0.6f, metalness = 0.25f, uv = 3f)
    B.defMat("railYellow", color = 0xd4a51c, roughness = 0.45f, metalness = 0.25f, uv = 1f)
    B.defMat("railWhite", color = 0xe6e6e0, roughness = 0.5f, metalness = 0.2f, uv = 1f)
    B.defMat("black", color = 0x1a1b1c, roughness = 0.8f, metalness = 0.1f, uv = 1f)
    B.defMat("steel", color = 0x8a8f94, roughness = 0.35f, metalness = 0.9f, uv = 1f)
    B.defMat("orange", color = 0xe0621a, roughness = 0.5f, metalness = 0.1f, uv = 1f)
    B.defMat("white", color = 0xdedfda, roughness = 0.6f, metalness = 0.1f, uv = 1f)
    B.defMat("lamp", color = 0xfff2d0, emissive = 0xffe2a8, emissiveIntensity = 3.5f, roughness = 0.3f, uv = 1f, shadow = false)
    B.defMat("redLamp", color = 0xff3020, emissive = 0xff2010, emissiveIntensity = 4f, roughness = 0.3f, uv = 1f, shadow = false)
    B.defMat("greenLamp", color = 0x30ff60, emissive = 0x20ff50, emissiveIntensity = 4f, roughness = 0.3f, uv = 1f, shadow = false)
    B.defMat("superWall", T.superWall.first, null, null, emissiveMap = T.superWall.second,
        emissiveLinear = floatArrayOf(0.4f, 0.4f, 0.4f), roughness = 0.55f, metalness = 0.15f, uv = floatArrayOf(4f, 3f))
    B.defMat("hull", T.hull.map, T.hull.normalMap, roughness = 0.6f, metalness = 0.3f, uv = "custom")
    B.defMat("fence", T.fence, alphaTest = 0.5f, twoSided = true, roughness = 0.45f, metalness = 0.6f, uv = 1f)
    B.defMat("grating", T.grating, alphaTest = 0.5f, twoSided = true, roughness = 0.6f, metalness = 0.6f, uv = 0.5f)
    B.defMat("signs", T.signs, roughness = 0.6f, metalness = 0.1f, uv = "custom", shadow = false)
    B.defMat("paintY", T.deck.map, color = 0xf0c030, roughness = 0.75f, metalness = 0.1f, uv = 4f, shadow = false)
    B.defMat("paintW", T.deck.map, color = 0xf4f4ee, roughness = 0.75f, metalness = 0.1f, uv = 4f, shadow = false)
    B.defMat("wood", T.crates[1].map, roughness = 0.85f, uv = 1.2f)

    // ================== 甲板与边界 ==================
    run {
        // 甲板：按船体轮廓生成（耳切三角化）
        val pts = ArrayList<FloatArray>()
        for (i in 0 until 160) {
            val c = hullContour(i / 160f, 1f, 1f)
            pts.add(floatArrayOf(c[0], -c[1])) // shape 空间 (x, -z)
        }
        val tris = triangulate(pts)
        val batch = B.batch("deck")
        for (t in 0 until tris.size step 3) {
            for (k in 0 until 3) {
                val sp = pts[tris[t + k]]
                val x = sp[0]; val z = -sp[1]
                batch.p.add3(x, 0f, z)
                batch.n.add3(0f, 1f, 0f)
                batch.uv.add2(x / 4f, -z / 4f)
                batch.uv2.add2((x + 37) / 74f, 1 - (z + 12.5f) / 25f)
                batch.hasAO = true
            }
            val b = batch.count
            batch.idx.add(b); batch.idx.add(b + 1); batch.idx.add(b + 2)
            batch.count += 3
        }
        B.solid(0f, -0.5f, 0f, 90f, 1f, 30f, 0f, SolidProps(mat = "metal", surface = "metal", tag = "deck"))
        // 舷侧看不见的墙（防止跳海）与两端
        for (sz in intArrayOf(-1, 1)) B.solid(0f, 3f, sz * 12.15f, 80f, 6f, 0.1f, 0f, SolidProps(bullet = "pass", sight = false))
        B.solid(-36.6f, 6f, 0f, 0.4f, 12f, 26f, 0f, SolidProps(mat = "metal"))
        B.solid(36.6f, 6f, 0f, 0.4f, 12f, 26f, 0f, SolidProps(mat = "metal"))
        // 舷边栏杆（整船）
        for (sz in intArrayOf(-1, 1)) {
            var x = -64f
            while (x < 56f) {
                B.railing(x, sz * 12.05f, kotlin.math.min(x + 10f, 56f), sz * 12.05f, 0f, "railYellow", 1.1f, false)
                x += 10f
            }
            B.box(-4f, 0.08f, sz * 12.12f, 120f, 0.16f, 0.06f, 0f, "yellow")
        }
    }

    // ================== 对称结构 ==================
    val sideColors = mapOf(1 to intArrayOf(1, 3, 7, 1, 3), -1 to intArrayOf(0, 5, 4, 0, 5))
    for (s in intArrayOf(1, -1)) {
        fun X(x: Float) = x * s
        fun Z(z: Float) = z * s
        fun YAW(deg: Float) = deg + (if (s < 0) 180f else 0f)
        fun R(deg: Float) = YAW(deg) * PI.toFloat() / 180f
        val col = sideColors[s]!!

        // ---- 出生点舱壁（带两扇门）----
        val wallX = X(-28.15f)
        val wallTh = 0.3f; val wallH = 4.5f; val doorH = 2.5f
        val doors = arrayOf(floatArrayOf(-4.6f, -1.8f), floatArrayOf(2.8f, 5.4f))
        val segs = arrayOf(floatArrayOf(-12f, doors[0][0]), floatArrayOf(doors[0][1], doors[1][0]), floatArrayOf(doors[1][1], 6.8f))
        for (sg in segs) {
            val z0 = sg[0]; val z1 = sg[1]
            val zc = Z((z0 + z1) / 2); val L = z1 - z0
            B.box(wallX, wallH / 2, zc, wallTh, wallH, L, 0f, "bulkhead")
            B.solid(wallX, wallH / 2, zc, wallTh, wallH, L, 0f, SolidProps(mat = "metal"))
            B.foot(wallX, zc, wallTh, L, 0f, 0.5f)
        }
        for (dr in doors) {
            val z0 = dr[0]; val z1 = dr[1]
            val zc = Z((z0 + z1) / 2); val L = z1 - z0
            B.box(wallX, doorH + (wallH - doorH) / 2, zc, wallTh, wallH - doorH, L, 0f, "bulkhead")
            B.solid(wallX, doorH + (wallH - doorH) / 2, zc, wallTh, wallH - doorH, L, 0f, SolidProps(mat = "metal"))
            for (face in intArrayOf(-1, 1)) {
                val fx = wallX + face * (wallTh / 2 + 0.005f)
                B.decal("signs", fx, doorH + 0.12f, zc, L + 0.3f, 0.24f, face * PI.toFloat() / 2, 0f, SIGN_UV.hazard)
                for (zz in floatArrayOf(z0, z1)) {
                    B.decal("signs", fx, doorH / 2, Z(zz) + (if (zz == z0) 0.12f else -0.12f) * s, 0.24f, doorH, face * PI.toFloat() / 2, 0f, floatArrayOf(0f, 0f, 128f, 128f))
                }
            }
            B.box(wallX, 0.05f, zc, wallTh + 0.2f, 0.1f, L, 0f, "darkSteel")
        }
        for (face in intArrayOf(-1, 1)) {
            val fx = wallX + face * (wallTh / 2 + 0.006f)
            B.decal("signs", fx, 3.4f, Z(-8.2f), 3.2f, 0.8f, face * PI.toFloat() / 2, 0f, SIGN_UV.hold)
            B.decal("signs", fx, 3.3f, Z(0.5f), 2.4f, 0.84f, face * PI.toFloat() / 2, 0f, if (s > 0) SIGN_UV.bl else SIGN_UV.gr)
            B.decal("signs", fx, 1.6f, Z(-6.2f), 0.6f, 0.6f, face * PI.toFloat() / 2, 0f, SIGN_UV.nosmoke)
        }
        B.lamp(wallX + s * 0.35f, 3.0f, Z(-3.2f), false)
        B.lamp(wallX + s * 0.35f, 3.0f, Z(4.1f), false)
        B.lamp(wallX - s * 0.35f, 3.0f, Z(-3.2f), false)
        B.box(wallX - s * 0.3f, 1.3f, Z(-9.8f), 0.25f, 0.8f, 0.6f, 0f, "red")
        B.decal("signs", wallX - s * 0.43f, 2.05f, Z(-9.8f), 0.5f, 0.5f, if (s > 0) -PI.toFloat() / 2 else PI.toFloat() / 2, 0f, SIGN_UV.hydrant)

        // ---- 出生点内：吊机底座、楼梯、平台 ----
        val cx = X(-33f); val cz = Z(-9.6f)
        B.geom("yellow", B.cylG, cx, 3f, cz, 0f, 0f, 0f, 1.15f, 6f, 1.15f)
        B.geom("darkSteel", B.cylG, cx, 0.15f, cz, 0f, 0f, 0f, 1.45f, 0.3f, 1.45f)
        B.solid(cx, 3f, cz, 2.2f, 6f, 2.2f, R(45f), SolidProps(mat = "metal"))
        B.solid(cx, 3f, cz, 2.2f, 6f, 2.2f, 0f, SolidProps(mat = "metal"))
        B.foot(cx, cz, 2.6f, 2.6f, 0f, 0.6f)
        // 楼梯（上二楼）
        val nSteps = 10; val stepH = H2 / nSteps
        val sx0 = -34f; val sx1 = -29.5f
        val tread = (sx1 - sx0) / nSteps
        val stz0 = 6.9f; val stz1 = 8.5f
        for (i in 0 until nSteps) {
            val x0 = sx0 + i * tread
            val cxs = X((x0 + sx1) / 2); val h = (i + 1) * stepH
            B.box(cxs, h / 2, Z((stz0 + stz1) / 2), sx1 - x0, h, stz1 - stz0, 0f, mapOf(
                "py" to "grating", "px" to "darkSteel", "nx" to "darkSteel", "pz" to "darkSteel", "nz" to "darkSteel", "ny" to ""))
            B.box(X(x0 + 0.03f), h - 0.02f, Z((stz0 + stz1) / 2), 0.06f, 0.04f, stz1 - stz0, 0f, "railYellow")
            B.solid(cxs, h / 2, Z((stz0 + stz1) / 2), sx1 - x0, h, stz1 - stz0, 0f, SolidProps(mat = "metal", surface = "grate"))
        }
        B.foot(X((sx0 + sx1) / 2), Z((stz0 + stz1) / 2), sx1 - sx0, stz1 - stz0, 0f, 0.5f)
        // 楼梯斜扶手
        B.rod("railYellow", X(sx0), 1.0f, Z(stz0), X(sx1), H2 + 1.0f, Z(stz0), 0.03f)
        B.rod("railYellow", X(sx0), 0.55f, Z(stz0), X(sx1), H2 + 0.55f, Z(stz0), 0.025f)
        for (i in 0..4) {
            val t = i / 4f
            val hx = sx0 + (sx1 - sx0) * t; val hy = H2 * t
            B.rod("railYellow", X(hx), hy, Z(stz0), X(hx), hy + 1.0f, Z(stz0), 0.03f)
        }
        B.box(X(-28.75f), H2 / 2, Z(8.1f), 1.5f, H2, 2.6f, 0f, mapOf(
            "py" to "grating", "px" to "darkSteel", "nx" to "darkSteel", "pz" to "darkSteel", "nz" to "darkSteel", "ny" to ""))
        B.solid(X(-28.75f), H2 / 2, Z(8.1f), 1.5f, H2, 2.6f, 0f, SolidProps(mat = "metal", surface = "grate"))
        B.foot(X(-28.75f), Z(8.1f), 1.5f, 2.6f, 0f, 0.6f)
        B.railing(X(-29.5f), Z(6.8f), X(-28.0f), Z(6.8f), H2, "railYellow", 1.1f, true)

        // 出生点杂物
        B.crate(X(-29.6f), Z(-6.6f), 1.3f, 1.3f, 1.3f, 4, 0f, 10f)
        B.crate(X(-29.5f), Z(-5.3f), 1.0f, 0.9f, 1.0f, 5, 0f, -5f)
        B.crate(X(-35.6f), Z(4.2f), 1.4f, 1.4f, 1.4f, 4, 0f, 0f)
        for (bz in floatArrayOf(-11.3f, 11.3f)) {
            val bx = X(-35.3f); val bzz = Z(bz)
            B.geom("black", B.cylG, bx - 0.35f, 0.35f, bzz, 0f, 0f, 0f, 0.2f, 0.7f, 0.2f)
            B.geom("black", B.cylG, bx + 0.35f, 0.35f, bzz, 0f, 0f, 0f, 0.2f, 0.7f, 0.2f)
            B.box(bx, 0.05f, bzz, 1.3f, 0.1f, 0.55f, 0f, "black")
            B.solid(bx, 0.35f, bzz, 1.2f, 0.7f, 0.45f, 0f, SolidProps(mat = "metal"))
        }
        B.lifeRing(X(-31.5f), 0.85f, Z(11.98f), if (s > 0) 0f else PI.toFloat())
        B.lifeRing(X(-34.5f), 0.85f, Z(-11.98f), if (s > 0) PI.toFloat() else 0f)
        for (vv in arrayOf(floatArrayOf(-35.4f, -7.8f), floatArrayOf(-35.4f, 0.3f))) {
            B.geom("white", B.cylG, X(vv[0]), 0.6f, Z(vv[1]), 0f, 0f, 0f, 0.18f, 1.2f, 0.18f)
            B.geom("white", B.sphG, X(vv[0]), 1.25f, Z(vv[1]), 0f, 0f, 0f, 0.36f, 0.2f, 0.36f)
            B.solid(X(vv[0]), 0.7f, Z(vv[1]), 0.5f, 1.4f, 0.5f, 0f, SolidProps(mat = "metal"))
        }

        // ---- 集装箱管道（单向小道）+ 二楼 ----
        val tz0 = 9.4f; val tz1 = 9.4f + CW; val tzc = (tz0 + tz1) / 2
        val tx0 = -29.5f
        val segCols = intArrayOf(col[0], col[1], col[2])
        val wT = 0.08f
        for (k in 0 until 3) {
            val x0 = tx0 + k * L40; val x1 = x0 + L40; val xc = (x0 + x1) / 2
            val ci = segCols[k] % T.containers.size
            B.box(X(xc), CH / 2, Z(tz0 + wT / 2), L40, CH, wT, R(0f), mapOf(
                "nz" to "c${ci}s40", "pz" to "interior", "px" to "", "nx" to "", "py" to "", "ny" to ""))
            B.solid(X(xc), CH / 2, Z(tz0 + wT / 2), L40, CH, wT, 0f, SolidProps(mat = "metal"))
            val win = floatArrayOf(x0 + 3.2f, x0 + 8.4f)
            var cur = x0
            for (wx in win) {
                val a = cur; val b2 = wx
                B.box(X((a + b2) / 2), CH / 2, Z(tz1 - wT / 2), b2 - a, CH, wT, R(0f), mapOf(
                    "pz" to "c${ci}s20", "nz" to "interior", "px" to "", "nx" to "", "py" to "", "ny" to ""))
                B.solid(X((a + b2) / 2), CH / 2, Z(tz1 - wT / 2), b2 - a, CH, wT, 0f, SolidProps(mat = "metal"))
                val f0 = wx; val f1 = wx + 1.6f
                B.fence(X(f0), Z(tz1 - wT / 2), X(f1), Z(tz1 - wT / 2), 0.9f, 2.2f, "railYellow", false)
                B.box(X((f0 + f1) / 2), 0.45f, Z(tz1 - wT / 2), 1.6f, 0.9f, wT, R(0f), mapOf(
                    "pz" to "c${ci}s20", "nz" to "interior", "px" to "", "nx" to "", "py" to "", "ny" to ""))
                B.box(X((f0 + f1) / 2), (2.2f + CH) / 2, Z(tz1 - wT / 2), 1.6f, CH - 2.2f, wT, R(0f), mapOf(
                    "pz" to "c${ci}s20", "nz" to "interior", "px" to "", "nx" to "", "py" to "", "ny" to ""))
                B.solid(X((f0 + f1) / 2), CH / 2, Z(tz1 - wT / 2), 1.6f, CH, wT, 0f, SolidProps(mat = "metal", bullet = "pass", sight = false))
                cur = f1
            }
            B.box(X((cur + x1) / 2), CH / 2, Z(tz1 - wT / 2), x1 - cur, CH, wT, R(0f), mapOf(
                "pz" to "c${ci}s20", "nz" to "interior", "px" to "", "nx" to "", "py" to "", "ny" to ""))
            B.solid(X((cur + x1) / 2), CH / 2, Z(tz1 - wT / 2), x1 - cur, CH, wT, 0f, SolidProps(mat = "metal"))
            B.box(X(xc), CH - 0.07f, Z(tzc), L40, 0.14f, CW + 0.001f, R(0f), mapOf(
                "py" to "c${ci}roof", "ny" to "interior", "px" to "", "nx" to "", "pz" to "", "nz" to ""))
            B.solid(X(xc), CH - 0.07f, Z(tzc), L40, 0.14f, CW, 0f, SolidProps(mat = "metal", surface = "container"))
            for (px in floatArrayOf(x0, x1)) for (pz in floatArrayOf(tz0, tz1)) {
                B.box(X(px), CH / 2, Z(pz), 0.16f, CH, 0.16f, 0f, "darkSteel")
            }
            B.lamp(X(xc - 3), CH - 0.25f, Z(tzc), k != 1)
            B.lamp(X(xc + 3), CH - 0.25f, Z(tzc), false)
            B.geom("darkSteel", B.cylG, X(xc), 0.35f, Z(tz1 - 0.3f), 0f, 0f, PI.toFloat() / 2, 0.16f, L40, 0.16f)
            B.geom("red", B.cylG, X(xc), 0.75f, Z(tz1 - 0.28f), 0f, 0f, PI.toFloat() / 2, 0.08f, L40, 0.08f)
        }
        B.solid(X(tx0 + 1.5f * L40), 0.4f, Z(tz1 - 0.3f), 3 * L40, 0.8f, 0.36f, 0f, SolidProps(mat = "metal", bullet = "pass"))
        val tEnd = tx0 + 3 * L40
        for (pp in arrayOf(floatArrayOf(tx0, 1f), floatArrayOf(tEnd, -1f))) {
            val ex = pp[0]; val dir = pp[1]
            val ciD = segCols[if (dir > 0) 0 else 2] % T.containers.size
            B.box(X(ex + dir * 0.62f), CH / 2, Z(tz0 - 0.05f), 1.2f, CH - 0.12f, 0.06f, R(0f), mapOf(
                "nz" to "c${ciD}door", "pz" to "c${ciD}door", "px" to "", "nx" to "", "py" to "", "ny" to ""))
            B.box(X(ex + dir * 0.62f), CH / 2, Z(tz1 + 0.05f), 1.2f, CH - 0.12f, 0.06f, R(0f), mapOf(
                "nz" to "c${ciD}door", "pz" to "c${ciD}door", "px" to "", "nx" to "", "py" to "", "ny" to ""))
        }
        B.crate(X(-18.5f), Z(9.9f), 0.8f, 0.8f, 0.8f, 1, 0f, 0f)
        B.crate(X(-4.0f), Z(9.95f), 0.9f, 1.0f, 0.9f, 5, 0f, 0f)
        B.container(X(11.83f), Z(tzc), YAW(0f), 20, col[3])
        B.container(X(21.05f), Z(tzc), YAW(0f), 40, col[4])
        B.container(X(21.05f), Z(tzc), YAW(0f), 40, col[0] + 2, 1)
        B.railing(X(tx0), Z(11.8f), X(14.86f), Z(11.8f), H2, "railYellow", 1.1f, true)
        B.railing(X(-24f), Z(9.45f), X(-16f), Z(9.45f), H2, "railYellow", 1.1f, true)
        B.railing(X(-8f), Z(9.45f), X(-2f), Z(9.45f), H2, "railYellow", 1.1f, true)
        for (i in 0 until 5) B.crate(X(8.8f + 0.59f + i * 1.18f), Z(8.85f), 1.16f, 0.9f, 1.1f, if (i % 2 == 1) 1 else 3, 0f, 0f)
        B.crate(X(11.5f), Z(8.85f), 1.2f, 0.95f, 1.2f, 0, 0.9f, 5f)
        B.crate(X(11.3f), Z(7.5f), 1.6f, 1.6f, 1.6f, 2, 0f, -6f)

        // ---- 主甲板障碍（半场）----
        B.container(X(-21.5f), Z(-1.8f), YAW(90f), 20, col[1])
        B.container(X(-12.8f), Z(0.9f), YAW(90f), 20, col[2])
        B.container(X(-13.6f), Z(5.15f), YAW(0f), 20, col[0])
        B.container(X(-13.6f), Z(5.15f), YAW(0f), 20, col[3] + 1, 1)
        B.crate(X(-14.65f), Z(2.3f), 1.2f, 1.8f, 1.2f, 4, 0f, 0f)
        B.crate(X(-15.8f), Z(2.3f), 1.0f, 0.9f, 1.0f, 1, 0f, 8f)
        B.crate(X(-26.9f), Z(0.4f), 1.6f, 1.6f, 1.6f, 4, 0f, 0f)
        B.crate(X(-26.6f), Z(2.1f), 1.2f, 1.2f, 1.2f, 5, 0f, 15f)
        B.crate(X(-6.4f), Z(8.3f), 1.4f, 1.4f, 1.4f, if (s > 0) 0 else 2, 0f, 4f)
        B.barrel(X(-19.2f), Z(-8.3f), "red"); B.barrel(X(-18.5f), Z(-8.7f), "red"); B.barrel(X(-18.8f), Z(-7.7f), "green")
        B.barrel(X(-9.6f), Z(8.7f), "green"); B.barrel(X(-9.0f), Z(8.95f), "red")
        for (lz in floatArrayOf(-7.2f, 7.4f)) B.box(X(-17f), 0.004f, Z(lz), 16f, 0.001f, 0.12f, 0f, mapOf(
            "py" to "paintY", "px" to "", "nx" to "", "pz" to "", "nz" to "", "ny" to ""))
        B.decal("signs", X(-24.5f), 0.006f, Z(-6.5f), 1.2f, 1.2f, if (s > 0) -PI.toFloat() / 2 else PI.toFloat() / 2, -PI.toFloat() / 2, SIGN_UV.arrow)
    }

    // ---- 中路：V 形斜放集装箱 + 木箱 ----
    B.container(-1.1f, -4.4f, -39f, 20, 2)
    B.container(0.9f, 4.0f, 33f, 20, 6)
    B.crate(-6.9f, 1.3f, 1.6f, 1.6f, 1.6f, 0, 0f, 3f)
    B.crate(-5.2f, 1.4f, 1.2f, 1.2f, 1.2f, 2, 0f, -8f)
    B.crate(-6.9f, 1.3f, 1.0f, 0.8f, 1.0f, 1, 1.6f, 20f)

    // ================== 保卫者背后：集装箱墙与船头 ==================
    val stackCols = intArrayOf(0, 5, 4, 1, 2, 6, 3, 7)
    for (tier in 0 until 3) for (i in 0 until 4) {
        B.container(37.6f, -9.09f + i * L20, 90f, 20, stackCols[(i + tier * 3) % 8], tier, ContainerOpts(collide = tier == 0))
    }
    B.solid(37.6f, 6f, 0f, CW, 12f, 24.4f, 0f, SolidProps(mat = "metal"))
    for (row in 0 until 3) for (bay in 0 until 8) {
        val h = 2 + ((row * 7 + bay * 3) % 3)
        for (tier in 0 until h) {
            B.container(41f + row * 7, -10.6f + bay * 3.03f, 0f, 20, (bay + row * 3 + tier * 5) % 8, tier, ContainerOpts(collide = false))
        }
    }
    B.geom("white", B.cylG, 64f, 8f, 0f, 0f, 0f, 0f, 0.35f, 16f, 0.35f)
    B.geom("white", B.cylG8, 64f, 13f, 0f, 0f, 0f, PI.toFloat() / 2, 0.08f, 6f, 0.08f)
    B.geom("redLamp", B.sphG, 64f, 16.3f, 0f, 0f, 0f, 0f, 0.2f, 0.2f, 0.2f)

    // ================== 上层建筑（潜伏者背后） ==================
    val sx0 = -56f; val sx1 = -36.7f; val sw = 11.2f
    for (lvl in 0 until 5) {
        val y0 = lvl * 3f
        val inset = lvl * 0.4f
        val x1 = sx1 - (if (lvl > 3) 1.5f else 0f)
        val wz = sw + (if (lvl == 4) 3f else 0f)
        B.box((sx0 + x1) / 2, y0 + 1.5f, 0f, x1 - sx0 - inset, 3f, wz * 2, 0f,
            mapOf("px" to "superWall", "pz" to "superWall", "nz" to "superWall", "nx" to "superWall", "py" to "", "ny" to ""),
            floatArrayOf(0f, 0f))
        B.box((sx0 + x1) / 2, y0 + 3 - 0.08f, 0f, x1 - sx0 - inset + 0.6f, 0.16f, wz * 2 + 0.6f, 0f, "white")
        if (lvl < 4) B.railing(x1 + 0.3f, -wz, x1 + 0.3f, wz, y0 + 3, "railWhite", 1.0f, false)
    }
    B.box((sx0 + sx1) / 2, 15.1f, 0f, sx1 - sx0 - 1f, 0.2f, 29f, 0f, "white")
    B.decal("signs", sx1 - 1.49f, 13.3f, 0f, 12f, 1.9f, PI.toFloat() / 2, 0f, SIGN_UV.shipname)
    B.box(sx1 - 1.52f, 13.4f, 0f, 0.05f, 1.3f, 26f, 0f, "black")
    // 烟囱
    val fx = -51f
    B.box(fx, 20f, 0f, 5f, 10f, 4.2f, 0f, mapOf(
        "px" to "white", "nx" to "white", "pz" to "white", "nz" to "white", "py" to "black", "ny" to ""))
    B.box(fx, 22.5f, 0f, 5.05f, 1.6f, 4.25f, 0f, mapOf(
        "px" to "red", "nx" to "red", "pz" to "red", "nz" to "red", "py" to "", "ny" to ""))
    B.box(fx, 24.8f, 0f, 5.1f, 0.6f, 4.3f, 0f, "black")
    val funnelTop = Vec3(fx, 25.3f, 0f)
    // 雷达桅
    B.geom("white", B.cylG, -45f, 18.5f, 0f, 0f, 0f, 0f, 0.18f, 7f, 0.18f)
    B.box(-45f, 21.5f, 0f, 0.3f, 0.3f, 4f, 0f, "white")
    B.geom("redLamp", B.sphG, -45f, 22.1f, 1.9f, 0f, 0f, 0f, 0.12f, 0.12f, 0.12f)
    B.geom("greenLamp", B.sphG, -45f, 22.1f, -1.9f, 0f, 0f, 0f, 0.12f, 0.12f, 0.12f)
    // 救生艇（橙色）
    for (sz in intArrayOf(-1, 1)) {
        val cap = Prims.capsule(1.1f, 5.2f, 4, 12)
        B.geom("orange", cap, -46f, 7.2f, sz * 12.4f, 0f, 0f, PI.toFloat() / 2, 1f, 1f, 1f)
        B.box(-46f, 8.15f, sz * 12.4f, 6f, 0.3f, 1.6f, 0f, "white")
        for (dx in floatArrayOf(-2.6f, 2.6f)) {
            B.geom("white", B.cylG8, -46f + dx, 7.8f, sz * 11.8f, sz * 0.5f, 0f, 0f, 0.08f, 3f, 0.08f)
        }
    }
    for (dz in floatArrayOf(-6f, 6f)) B.box(sx1 + 0.02f, 1.1f, dz, 0.06f, 2.2f, 1.1f, 0f, "darkSteel")

    // ================== 吊机 ==================
    fun crane(px: Float, pz: Float, yawDeg: Float, luff: Float, jibLen: Float): FloatArray {
        val yaw = yawDeg * PI.toFloat() / 180f
        val base = Mat4().compose(px, 6f, pz, com.cf.transportship.engine.Quat().setFromAxisAngle(0f, 1f, 0f, yaw), 1f, 1f, 1f)
        val cabM = mulChain(base, Mat4().compose(-0.4f, 1.3f, 0f, com.cf.transportship.engine.Quat(), 1f, 1f, 1f))
        B.batch("yellow").geom(Prims.box(3.2f, 2.6f, 2.6f), cabM)
        val winM = mulChain(base, Mat4().compose(1.21f, 1.6f, 0f, com.cf.transportship.engine.Quat(), 1f, 1f, 1f))
        B.batch("black").geom(Prims.box(0.05f, 1f, 1.8f), winM)
        val pivotM = mulChain(base, Mat4().compose(0.8f, 1.8f, 0f, com.cf.transportship.engine.Quat().setFromAxisAngle(0f, 0f, 1f, luff), 1f, 1f, 1f))
        val jibM = mulChain(pivotM, Mat4().compose(jibLen / 2, 0f, 0f, com.cf.transportship.engine.Quat(), 1f, 1f, 1f))
        B.batch("yellow").geom(Prims.box(jibLen, 0.7f, 0.9f), jibM)
        val jib2M = mulChain(pivotM, Mat4().compose(jibLen / 2, 0.4f, 0f, com.cf.transportship.engine.Quat(), 1f, 1f, 1f))
        B.batch("yellow").geom(Prims.box(jibLen * 0.98f, 0.12f, 1.3f), jib2M)
        val tip = pivotM.transformPoint(com.cf.transportship.engine.Vec3(jibLen, 0f, 0f))
        return floatArrayOf(tip.x, tip.y, tip.z)
    }
    val tipA = crane(-33f, -9.6f, -12f, 0.36f, 30f)
    crane(33f, 9.6f, 168f, 0.95f, 22f)

    // 吊着的集装箱（独立动画网格）
    val hangParts = ArrayList<Pair<MatDef, Geo>>()
    run {
        val cableLen = tipA[1] - 12f
        val hc = Prims.box(L20, CH, CW)
        hangParts.add(Pair(B.mat("c5s20"), hc.clone().applyMatrix(
            Mat4().compose(0f, -cableLen - CH / 2 - 0.4f, 0f, com.cf.transportship.engine.Quat(), 1f, 1f, 1f))))
        hangParts.add(Pair(B.mat("yellow"), Prims.box(L20 - 0.2f, 0.25f, CW - 0.2f).applyMatrix(
            Mat4().compose(0f, -cableLen - 0.2f, 0f, com.cf.transportship.engine.Quat(), 1f, 1f, 1f))))
        val cable = Prims.cylinder(0.03f, 0.03f, cableLen, 4).applyMatrix(
            Mat4().compose(0f, -cableLen / 2, 0f, com.cf.transportship.engine.Quat(), 1f, 1f, 1f))
        for (dx in floatArrayOf(-0.25f, 0.25f)) {
            hangParts.add(Pair(B.mat("black"), cable.clone().applyMatrix(
                Mat4().compose(dx, 0f, 0f, com.cf.transportship.engine.Quat(), 1f, 1f, 1f))))
        }
    }
    val hanging = MapMesh(hangParts, Mat4().compose(tipA[0], tipA[1], tipA[2], com.cf.transportship.engine.Quat(), 1f, 1f, 1f))

    // 旋转雷达
    val radar = MapMesh(listOf(Pair(B.mat("white"), Prims.box(0.25f, 0.18f, 3.2f))),
        Mat4().compose(-45f, 22.4f, 0f, com.cf.transportship.engine.Quat(), 1f, 1f, 1f))

    // ================== 船体 ==================
    B.batch("hull").geom(buildHullGeometry(), Mat4())

    world.build()

    // ================== 出生点 ==================
    val spawns = HashMap<String, List<SpawnPoint>>()
    val bl = ArrayList<SpawnPoint>()
    val gr = ArrayList<SpawnPoint>()
    for (i in 0 until 10) {
        val zz = -6.6f + (i % 5) * 3.0f
        val xx = -34.0f + (i / 5) * 2.8f
        bl.add(SpawnPoint(xx, zz, -PI.toFloat() / 2))
        gr.add(SpawnPoint(-xx, -zz, PI.toFloat() / 2))
    }
    spawns["BL"] = bl
    spawns["GR"] = gr

    // 合批输出
    val out = ArrayList<Triple<MatDef, Batch, FloatArray?>>()
    for ((key, b) in B.batches) {
        if (b.count == 0) continue
        val def = B.mat(key)
        out.add(Triple(def, b, if (key == "deck") floatArrayOf(0f, 0f) else null))
    }
    val ao = bakeDeckAO(B.footprints)
    return GameMap(out, spawns, B.lampSpots, funnelTop, hanging, radar, ao)
}

/** 甲板 AO 纹理引用（供渲染器绑定） */
fun mapAOuv(x: Float, z: Float): FloatArray = floatArrayOf((x + 37) / 74f, 1 - (z + 12.5f) / 25f)

// 雷达底图由 HUD 侧用碰撞体绘制
fun buildRadarBitmap(world: World, pxPerM: Int = 8): Bitmap {
    val S = pxPerM
    val W = (74 * S)
    val H = (26 * S)
    val ctx = Ctx(W, H)
    ctx.fill(ColorArgb(242, 70, 80, 84))
    val cols = world.colliders
        .filter { it.solid && it.top > 0.3f && it.bottom < 2f && it.hx < 30f && it.tag != "deck" }
        .sortedBy { it.top }
    for (k in cols) {
        if (k.bullet == "pass" && k.mat != "mesh") continue
        ctx.c.save()
        ctx.c.translate((k.x + 37) * S, (k.z + 13) * S)
        ctx.c.rotate(-Math.toDegrees(k.yaw.toDouble()).toFloat())
        val hgt = k.top
        ctx.p.color = if (k.mat == "mesh") ColorArgb(128, 200, 200, 190)
        else when {
            hgt > 4 -> ColorArgb(255, 29, 35, 39)
            hgt > 2 -> ColorArgb(255, 45, 53, 58)
            hgt > 1.3f -> ColorArgb(255, 59, 69, 75)
            else -> ColorArgb(255, 86, 97, 106)
        }
        ctx.c.drawRect(-k.hx * S, -k.hz * S, k.hx * 2 * S, k.hz * 2 * S, ctx.p)
        val sp = Paint()
        sp.color = ColorArgb(128, 0, 0, 0)
        sp.strokeWidth = 1f
        sp.style = Paint.Style.STROKE
        ctx.c.drawRect(-k.hx * S, -k.hz * S, k.hx * 2 * S, k.hz * 2 * S, sp)
        ctx.c.restore()
    }
    // 管道顶棚（二楼）用虚线表示
    val dp = Paint(Paint.ANTI_ALIAS_FLAG)
    dp.color = ColorArgb(89, 245, 179, 33)
    dp.strokeWidth = 2f
    dp.style = Paint.Style.STROKE
    dp.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
    ctx.c.drawRect((-29.5f + 37) * S, (9.4f + 13) * S, (-29.5f + 37 + 36.6f) * S, (9.4f + 13 + 2.44f) * S, dp)
    ctx.c.drawRect((29.5f - 36.6f + 37) * S, (-11.84f + 13) * S, (29.5f - 36.6f + 37 + 36.6f) * S, (-11.84f + 13 + 2.44f) * S, dp)
    return ctx.bmp
}

fun ColorArgb(a: Int, r: Int, g: Int, b: Int) = android.graphics.Color.argb(a, r, g, b)

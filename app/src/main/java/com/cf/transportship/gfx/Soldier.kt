// 第三人称士兵：刚性蒙皮 + 程序动画 + 骨骼命中盒（移植 character.js）
package com.cf.transportship.gfx

import android.graphics.Bitmap
import android.graphics.Color
import com.cf.transportship.engine.Mat4
import com.cf.transportship.engine.Quat
import com.cf.transportship.engine.Vec3
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ---------- 布料图集 ----------
fun soldierAtlas(): Bitmap {
    val W = 512; val H = 128
    val ctx = Ctx(W, H)
    val n = fbm(W, H, 16, 4, 4, 91)
    val camo = fbm(W, H, 8, 2, 3, 92)
    val camo2 = fbm(W, H, 8, 2, 3, 93)
    val px = IntArray(W * H)
    for (y in 0 until H) for (x in 0 until W) {
        val i = y * W + x
        val v: Float
        if (x < 128) {
            val weave = (if ((x + y) % 3 == 0) 0.92f else 1f) * (if ((x - y + 300) % 4 == 0) 0.94f else 1f)
            v = (0.82f + n[i] * 0.3f) * weave
        } else if (x < 256) {
            val qx = (x / 4) * 4; val qy = (y / 4) * 4; val qi = qy * W + qx
            val a = camo[qi]; val b = camo2[qi]
            var vv = if (a > 0.56f) 0.55f else if (b > 0.58f) 1.25f else if (a < 0.42f) 0.8f else 1f
            vv *= 0.92f + n[i] * 0.15f
            v = vv
        } else v = 0.93f + n[i] * 0.12f
        val g = (v * 200).toInt().coerceIn(0, 255)
        px[i] = -0x1000000 or (g shl 16) or (g shl 8) or g
    }
    ctx.setPx(px)
    return ctx.bmp
}

class SoldierOutfit(val key: IntArray)

private val OUTFIT = mapOf(
    "BL" to mapOf(
        "pants" to Pair(0x2c2d31, "fab"), "jacket" to Pair(0x1f2124, "fab"), "vest" to Pair(0x2e3128, "fab"),
        "pouch" to Pair(0x3a3d30, "fab"), "boots" to Pair(0x141414, "plain"), "gloves" to Pair(0x18181a, "plain"),
        "skin" to Pair(0xb88c6c, "plain"), "mask" to Pair(0x151618, "fab"), "head" to Pair(0x151618, "fab"),
        "band" to Pair(0xa3161a, "plain"), "armband" to Pair(0xb01c1c, "plain"), "goggles" to Pair(0x1a1a1a, "plain"),
        "lens" to Pair(0xd66a1a, "plain"), "pads" to Pair(0x222326, "plain"),
    ),
    "GR" to mapOf(
        "pants" to Pair(0x5e6b7c, "camo"), "jacket" to Pair(0x566478, "camo"), "vest" to Pair(0x27344a, "fab"),
        "pouch" to Pair(0x2e3c52, "fab"), "boots" to Pair(0x16161a, "plain"), "gloves" to Pair(0x1c1d20, "plain"),
        "skin" to Pair(0xc49a7a, "plain"), "mask" to Pair(0x202328, "fab"), "head" to Pair(0x33404f, "plain"),
        "band" to Pair(0x1a1a1a, "plain"), "armband" to Pair(0x1f62c8, "plain"), "goggles" to Pair(0x151515, "plain"),
        "lens" to Pair(0xe0c040, "plain"), "pads" to Pair(0x2a3340, "plain"),
    ),
)

val BONES = arrayOf(
    // name, parent, x, y, z
    arrayOf<Any>("hips", -1, 0f, 0.98f, 0f), arrayOf<Any>("spine", 0, 0f, 0.12f, 0f),
    arrayOf<Any>("chest", 1, 0f, 0.2f, 0f), arrayOf<Any>("neck", 2, 0f, 0.2f, 0f),
    arrayOf<Any>("head", 3, 0f, 0.08f, 0f),
    arrayOf<Any>("shoulderR", 2, 0.17f, 0.13f, 0f), arrayOf<Any>("upperArmR", 5, 0.04f, 0f, 0f),
    arrayOf<Any>("forearmR", 6, 0f, -0.29f, 0f), arrayOf<Any>("handR", 7, 0f, -0.26f, 0f),
    arrayOf<Any>("shoulderL", 2, -0.17f, 0.13f, 0f), arrayOf<Any>("upperArmL", 9, -0.04f, 0f, 0f),
    arrayOf<Any>("forearmL", 10, 0f, -0.29f, 0f), arrayOf<Any>("handL", 11, 0f, -0.26f, 0f),
    arrayOf<Any>("thighR", 0, 0.1f, -0.05f, 0f), arrayOf<Any>("shinR", 13, 0f, -0.44f, 0f),
    arrayOf<Any>("footR", 14, 0f, -0.44f, 0f),
    arrayOf<Any>("thighL", 0, -0.1f, -0.05f, 0f), arrayOf<Any>("shinL", 16, 0f, -0.44f, 0f),
    arrayOf<Any>("footL", 17, 0f, -0.44f, 0f),
)

val BI: Map<String, Int> = BONES.indices.associate { i -> (BONES[i][0] as String) to i }

fun bindPositions(): Array<Vec3> {
    val out = ArrayList<Vec3>()
    for (b in BONES) {
        val v = Vec3(b[2] as Float, b[3] as Float, b[4] as Float)
        val p = b[1] as Int
        if (p >= 0) v.add(out[p])
        out.add(v)
    }
    return out.toTypedArray()
}

class HitBox(
    val bone: String, val cx: Float, val cy: Float, val cz: Float,
    val hx: Float, val hy: Float, val hz: Float, val part: String,
)

val HITBOXES = arrayOf(
    HitBox("head", 0f, 0.1f, 0f, 0.1f, 0.12f, 0.11f, "head"),
    HitBox("chest", 0f, 0.1f, 0f, 0.21f, 0.18f, 0.145f, "chest"),
    HitBox("spine", 0f, 0.09f, 0f, 0.17f, 0.12f, 0.12f, "stomach"),
    HitBox("hips", 0f, -0.02f, 0f, 0.18f, 0.11f, 0.13f, "stomach"),
    HitBox("upperArmR", 0f, -0.14f, 0f, 0.065f, 0.16f, 0.065f, "arm"),
    HitBox("upperArmL", 0f, -0.14f, 0f, 0.065f, 0.16f, 0.065f, "arm"),
    HitBox("forearmR", 0f, -0.13f, 0f, 0.055f, 0.15f, 0.055f, "arm"),
    HitBox("forearmL", 0f, -0.13f, 0f, 0.055f, 0.15f, 0.055f, "arm"),
    HitBox("thighR", 0f, -0.21f, 0f, 0.09f, 0.24f, 0.09f, "leg"),
    HitBox("thighL", 0f, -0.21f, 0f, 0.09f, 0.24f, 0.09f, "leg"),
    HitBox("shinR", 0f, -0.22f, 0f, 0.07f, 0.24f, 0.075f, "leg"),
    HitBox("shinL", 0f, -0.22f, 0f, 0.07f, 0.24f, 0.075f, "leg"),
)

/** 构建带颜色与骨骼索引的合并几何 */
fun buildSoldierGeometry(team: String): Geo {
    val O = OUTFIT[team]!!
    val bp = bindPositions()
    // 累积缓冲
    val pos = ArrayList<Float>(8000)
    val nrm = ArrayList<Float>(8000)
    val uv = ArrayList<Float>(8000)
    val col = ArrayList<Float>(8000)
    val skin = ArrayList<Float>(8000)
    val idx = ArrayList<Int>(12000)

    fun add(geo: Geo, bone: String, colorKey: String, x: Float, y: Float, z: Float, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f) {
        val q = Quat().setFromAxisAngle(1f, 0f, 0f, rx)
        q.multiply(Quat().setFromAxisAngle(0f, 1f, 0f, ry))
        q.multiply(Quat().setFromAxisAngle(0f, 0f, 1f, rz))
        val m = Mat4().compose(x, y, z, q, 1f, 1f, 1f)
        val g = geo.clone().applyMatrix(m)
        val pair = O[colorKey]!!
        val hex = pair.first; val kind = pair.second
        val cr = Color.red(hex) / 255f; val cg = Color.green(hex) / 255f; val cb = Color.blue(hex) / 255f
        // 注意：这里用 sRGB 值直接当线性（与 vertexColors 行为一致）
        val u0 = when (kind) { "fab" -> 0.02f; "camo" -> 0.27f; else -> 0.6f }
        val uw = if (kind == "plain") 0.35f else 0.2f
        val base = pos.size / 3
        val bi = BI[bone]!!
        for (i in 0 until g.vertCount) {
            pos.add(g.pos[i * 3]); pos.add(g.pos[i * 3 + 1]); pos.add(g.pos[i * 3 + 2])
            nrm.add(g.nrm[i * 3]); nrm.add(g.nrm[i * 3 + 1]); nrm.add(g.nrm[i * 3 + 2])
            val su = g.uv[i * 2]; val sv = g.uv[i * 2 + 1]
            uv.add(u0 + (su % 1f) * uw); uv.add(0.05f + (sv % 1f) * 0.9f)
            col.add(cr); col.add(cg); col.add(cb)
            skin.add(bi.toFloat())
        }
        for (k in g.idx) idx.add(base + k)
    }

    val cap = { r: Float, l: Float -> Prims.capsule(r, l, 3, 10) }
    val box = { w: Float, h: Float, d: Float -> Prims.box(w, h, d) }
    val sph = { r: Float -> Prims.sphere(r, 14, 10) }
    fun P(name: String): Vec3 = bp[BI[name]!!]

    // 腿
    for (s in arrayOf("R", "L")) {
        val sx = if (s == "R") 1f else -1f
        val th = P("thigh$s"); val sh = P("shin$s"); val ft = P("foot$s")
        add(cap(0.085f, 0.3f), "thigh$s", "pants", th.x, th.y - 0.2f, th.z)
        add(cap(0.068f, 0.32f), "shin$s", "pants", sh.x, sh.y - 0.2f, sh.z)
        add(box(0.12f, 0.14f, 0.1f), "shin$s", "pads", sh.x, sh.y - 0.02f, sh.z - 0.06f)
        add(box(0.12f, 0.13f, 0.27f), "foot$s", "boots", ft.x, ft.y + 0.03f, ft.z - 0.05f)
        add(box(0.11f, 0.1f, 0.14f), "shin$s", "boots", sh.x, sh.y - 0.38f, sh.z)
        add(box(0.07f, 0.1f, 0.1f), "thigh$s", "pouch", th.x + sx * 0.08f, th.y - 0.18f, th.z)
    }
    // 躯干
    val hp = P("hips"); val sp = P("spine"); val ch = P("chest")
    add(box(0.36f, 0.22f, 0.24f), "hips", "pants", hp.x, hp.y - 0.02f, hp.z)
    add(box(0.38f, 0.06f, 0.26f), "hips", "band", hp.x, hp.y + 0.08f, hp.z)
    add(box(0.33f, 0.22f, 0.22f), "spine", "jacket", sp.x, sp.y + 0.1f, sp.z)
    add(box(0.4f, 0.3f, 0.25f), "chest", "jacket", ch.x, ch.y + 0.1f, ch.z)
    add(box(0.42f, 0.34f, 0.29f), "chest", "vest", ch.x, ch.y + 0.06f, ch.z)
    for (i in 0 until 3) add(box(0.085f, 0.11f, 0.05f), "chest", "pouch", ch.x - 0.1f + i * 0.1f, ch.y - 0.02f, ch.z - 0.16f)
    add(box(0.3f, 0.34f, 0.12f), "chest", "pouch", ch.x, ch.y + 0.05f, ch.z + 0.19f)
    add(box(0.03f, 0.25f, 0.03f), "chest", "goggles", ch.x + 0.1f, ch.y + 0.32f, ch.z + 0.2f)
    // 头
    val nk = P("neck"); val hd = P("head")
    add(cap(0.055f, 0.06f), "neck", "skin", nk.x, nk.y + 0.04f, nk.z)
    add(sph(0.108f), "head", "skin", hd.x, hd.y + 0.09f, hd.z)
    if (team == "GR") {
        val helm = Prims.sphere(0.128f, 16, 10, 0f, (2 * Math.PI).toFloat(), 0f, (Math.PI * 0.55).toFloat())
        add(helm, "head", "head", hd.x, hd.y + 0.12f, hd.z + 0.005f)
        add(box(0.23f, 0.05f, 0.05f), "head", "goggles", hd.x, hd.y + 0.155f, hd.z - 0.1f)
        add(box(0.18f, 0.035f, 0.02f), "head", "lens", hd.x, hd.y + 0.155f, hd.z - 0.125f)
        val mask = Prims.sphere(0.1f, 14, 10)
        mask.applyMatrix(Mat4().compose(0f, 0f, 0f, Quat(), 1f, 0.62f, 1.05f))
        add(mask, "head", "mask", hd.x, hd.y + 0.035f, hd.z - 0.02f)
    } else {
        val hood = Prims.sphere(0.114f, 14, 10)
        hood.applyMatrix(Mat4().compose(0f, 0f, 0f, Quat(), 0.98f, 1.1f, 1.05f))
        add(hood, "head", "mask", hd.x, hd.y + 0.1f, hd.z + 0.008f)
        add(box(0.16f, 0.035f, 0.03f), "head", "skin", hd.x, hd.y + 0.12f, hd.z - 0.105f)
        add(box(0.24f, 0.035f, 0.235f), "head", "band", hd.x, hd.y + 0.185f, hd.z)
        add(box(0.07f, 0.022f, 0.02f), "head", "goggles", hd.x - 0.04f, hd.y + 0.12f, hd.z - 0.118f)
        add(box(0.07f, 0.022f, 0.02f), "head", "goggles", hd.x + 0.04f, hd.y + 0.12f, hd.z - 0.118f)
    }
    // 手臂
    for (s in arrayOf("R", "L")) {
        val ua = P("upperArm$s"); val fa = P("forearm$s"); val hn = P("hand$s")
        add(sph(0.085f), "upperArm$s", "jacket", ua.x, ua.y - 0.02f, ua.z)
        add(cap(0.062f, 0.2f), "upperArm$s", "jacket", ua.x, ua.y - 0.15f, ua.z)
        add(cap(0.066f, 0.05f), "upperArm$s", "armband", ua.x, ua.y - 0.12f, ua.z)
        add(cap(0.052f, 0.19f), "forearm$s", "jacket", fa.x, fa.y - 0.13f, fa.z)
        add(box(0.075f, 0.1f, 0.05f), "hand$s", "gloves", hn.x, hn.y - 0.04f, hn.z)
    }
    return Geo(pos.toFloatArray(), nrm.toFloatArray(), uv.toFloatArray(), idx.toIntArray(),
        col.toFloatArray(), skin.toFloatArray())
}

// ---------- 骨骼 ----------
class Bone(val name: String, val parent: Int, val restX: Float, val restY: Float, val restZ: Float) {
    var x = restX; var y = restY; var z = restZ
    val quat = Quat()
    val world = Mat4()
    val skin = Mat4()
}

class Soldier(val team: String) {
    val bones = Array(BONES.size) { i ->
        Bone(BONES[i][0] as String, BONES[i][1] as Int, BONES[i][2] as Float, BONES[i][3] as Float, BONES[i][4] as Float)
    }
    val B: Map<String, Bone> = bones.associateBy { it.name }
    val bindInv = Array(BONES.size) { Mat4() }

    var phase = (Math.random() * 10).toFloat()
    var crouchK = 0f
    var deadT = -1f
    var fallDir = 1f; var fallSide = 0f; var fallSpeed = 1f
    var limbR = FloatArray(4)
    var recoilK = 0f
    var amp = 0f

    // 位置/朝向（由 Actor 同步）
    var px = 0f; var py = 0f; var pz = 0f
    var yaw = 0f
    var visible = true
    var opacity = 1f

    // 枪
    var gunId: String? = null
    var gunAnchor = floatArrayOf(0f, 0f, 0f)     // 枪相对胸骨位置
    var gunRotY = 0.37f
    var gunReloadK = 0f                           // 换弹时下沉/翻转
    var gunVisible = true
    // 每武器锚点（枪身局部空间）
    var gunGrip = floatArrayOf(0f, -0.035f, 0.03f)
    var gunFore = floatArrayOf(0f, 0f, -0.29f)
    var gunMuzzle = floatArrayOf(0f, 0.042f, -0.66f)

    private val invMats = Array(HITBOXES.size) { Mat4() }
    private var invFrame = -1

    init {
        val bp = bindPositions()
        for (i in bones.indices) {
            val m = Mat4().compose(bp[i].x, bp[i].y, bp[i].z, Quat(), 1f, 1f, 1f)
            bindInv[i] = m.invert()
        }
    }

    fun reset() {
        deadT = -1f
        fallDir = 1f; fallSide = 0f
        opacity = 1f
        gunVisible = true
    }

    fun kick() { recoilK = 1f }

    fun die(dirX: Float, dirZ: Float, headshot: Boolean) {
        deadT = 0f
        val fx = -sin(yaw); val fz = -cos(yaw)
        val dot = fx * dirX + fz * dirZ
        fallDir = if (dot > 0f) 1f else -1f
        fallSide = (Math.random().toFloat() - 0.5f) * 0.8f
        fallSpeed = if (headshot) 1.4f else 1f
        for (i in 0 until 4) limbR[i] = Math.random().toFloat()
        opacity = 1f
    }

    fun setWeapon(id: String) {
        if (gunId == id) return
        gunId = id
    }

    /** 更新骨骼（st 参数与 JS 一致） */
    fun update(dt: Float, speed: Float, fwd: Float, crouch: Boolean, pitch: Float, onGround: Boolean, reloading: Boolean) {
        val Bk = B
        if (deadT >= 0) { updateDeath(dt); return }
        crouchK += ((if (crouch) 1f else 0f) - crouchK) * min(1f, dt * 10)
        val ck = crouchK
        val moving = speed > 0.3f && onGround
        phase += dt * (if (moving) 2.2f + speed * 1.15f else 0f) * (if (ck > 0.5f) 0.7f else 1f)
        val target = if (moving) min(1f, speed / 5f) else 0f
        amp += (target - amp) * min(1f, dt * 8)
        val A = amp
        val ph = phase
        val back = if (fwd < -0.3f) -1f else 1f
        // 腿
        val swing = sin(ph) * 0.62f * A * back
        val bendR = kotlin.math.max(0f, sin(ph + Math.PI.toFloat() * 0.5f * back)) * 1.0f * A
        val bendL = kotlin.math.max(0f, sin(ph + Math.PI.toFloat() + Math.PI.toFloat() * 0.5f * back)) * 1.0f * A
        var thR = swing; var thL = -swing; var shR = -bendR; var shL = -bendL
        thR = thR * (1 - ck) + (1.25f + swing * 0.3f) * ck
        thL = thL * (1 - ck) + (0.55f - swing * 0.3f) * ck
        shR = shR * (1 - ck) + -1.9f * ck
        shL = shL * (1 - ck) + (-1.2f + min(0f, -swing)) * ck
        if (!onGround) { thR = 0.7f; thL = 0.25f; shR = -1.1f; shL = -0.6f }
        Bk["thighR"]!!.quat.setFromEulerYXZ(0f, 0f, 0f)
        Bk["thighR"]!!.quat.setFromAxisAngle(1f, 0f, 0f, thR)
        Bk["thighL"]!!.quat.setFromAxisAngle(1f, 0f, 0f, thL)
        Bk["shinR"]!!.quat.setFromAxisAngle(1f, 0f, 0f, shR)
        Bk["shinL"]!!.quat.setFromAxisAngle(1f, 0f, 0f, shL)
        Bk["footR"]!!.quat.setFromAxisAngle(1f, 0f, 0f, -thR - shR)
        Bk["footL"]!!.quat.setFromAxisAngle(1f, 0f, 0f, -thL - shL)
        if (ck > 0.01f) {
            val f = Bk["footL"]!!
            f.quat.setFromAxisAngle(1f, 0f, 0f, 0.5f * ck + (-thL - shL) * (1 - ck))
        }
        // 髋部高度
        val bob = if (moving) kotlin.math.abs(cos(ph)) * 0.035f * A else 0f
        val hips = Bk["hips"]!!
        hips.y = 0.98f - ck * 0.38f - bob + (if (onGround) 0f else 0.02f)
        hips.z = ck * 0.08f
        hips.quat.setFromAxisAngle(0f, 1f, 0f, sin(ph) * 0.08f * A)
        // 上身跟随俯仰
        val pt = pitch.coerceIn(-1.2f, 1.2f)
        Bk["spine"]!!.quat.setQ(eulerX(pt * 0.3f + ck * 0.15f, -sin(ph) * 0.08f * A - 0.25f))
        Bk["chest"]!!.quat.setQ(eulerX(pt * 0.45f - recoilK * 0.08f, -0.12f))
        Bk["neck"]!!.quat.setQ(eulerX(pt * 0.2f, 0.3f))
        Bk["head"]!!.quat.setQ(eulerX(0f, 0.05f))
        recoilK *= exp(-dt * 12f)
        gunReloadK = if (reloading) 1f else 0f
        computeWorlds()
        // 手臂 IK 挂枪
        if (gunId != null) {
            val chest = Bk["chest"]!!
            // 枪相对胸骨
            val gx = 0.1f; val gy = 0.1f; val gz = -0.3f
            val gunM = Mat4().compose(gx, gy + (if (reloading) -0.08f else 0f), gz + recoilK * 0.05f,
                Quat().setFromAxisAngle(0f, 1f, 0f, 0.37f).multiply(Quat().setFromAxisAngle(1f, 0f, 0f, if (reloading) -0.5f else 0f))
                    .multiply(Quat().setFromAxisAngle(0f, 0f, 1f, if (reloading) 0.4f else 0f)), 1f, 1f, 1f)
            gunAnchorWorld.set(0f, 0f, 0f)
            chest.world.transformPoint(Vec3(gx, gy, gz), gunAnchorWorld)
            gunWorldM.set(chest.world).multiply(gunM)
            val gripW = gunWorldM.transformPoint(Vec3(gunGrip[0], gunGrip[1], gunGrip[2]))
            val poleR = chest.world.transformPoint(Vec3(0.6f, -0.5f, 0.1f))
            solveArm("R", gripW, poleR)
            val foreW = gunWorldM.transformPoint(Vec3(gunFore[0], gunFore[1], gunFore[2]))
            val poleL = chest.world.transformPoint(Vec3(-0.5f, -0.6f, 0f))
            solveArm("L", foreW, poleL)
        } else {
            Bk["upperArmR"]!!.quat.setQ(eulerX(0.3f, 0f, -0.15f))
            Bk["forearmR"]!!.quat.setQ(eulerX(0.6f, 0f, 0f))
            Bk["upperArmL"]!!.quat.setQ(eulerX(0.3f, 0f, -0.15f))
            Bk["forearmL"]!!.quat.setQ(eulerX(0.6f, 0f, 0f))
            computeWorlds()
        }
        Bk["handR"]!!.quat.setFromAxisAngle(0f, 0f, 1f, 0f)
        Bk["handL"]!!.quat.setFromAxisAngle(0f, 0f, 1f, 0f)
    }

    private fun eulerX(rx: Float, ry: Float, rz: Float = 0f): Quat =
        Quat().setFromAxisAngle(1f, 0f, 0f, rx)
            .multiply(Quat().setFromAxisAngle(0f, 1f, 0f, ry))
            .multiply(Quat().setFromAxisAngle(0f, 0f, 1f, rz))

    private fun computeWorlds() {
        for (i in bones.indices) {
            val b = bones[i]
            val local = Mat4().compose(b.x, b.y, b.z, b.quat, 1f, 1f, 1f)
            if (b.parent >= 0) b.world.set(bones[b.parent].world).multiply(local)
            else b.world.set(rootModel).multiply(local)
            b.skin.set(b.world).multiply(bindInv[i])
        }
    }

    private val rootModel = Mat4()

    /** 由 Actor 每帧设置根矩阵 */
    fun setRoot(x: Float, y: Float, z: Float, yaw: Float, fallX: Float = 0f, fallZ: Float = 0f) {
        px = x; py = y; pz = z
        this.yaw = yaw
        rootModel.identity()
        val qy = Quat().setFromAxisAngle(0f, 1f, 0f, yaw)
        val qx = Quat().setFromAxisAngle(1f, 0f, 0f, fallX)
        val qz = Quat().setFromAxisAngle(0f, 0f, 1f, fallZ)
        rootModel.compose(x, y, z, qy.multiply(qx).multiply(qz), 1f, 1f, 1f)
    }

    private fun updateDeath(dt: Float) {
        deadT += dt
        val t = min(1f, deadT * 1.6f * fallSpeed)
        val e = t * t
        val ang = e * Math.PI.toFloat() * 0.5f * 0.96f
        val Bk = B
        val hips = Bk["hips"]!!
        hips.y = 0.98f - sin(t * Math.PI.toFloat()) * 0.25f - t * 0.3f
        val r = limbR
        val k = min(1f, deadT * 3)
        fun approach(b: Bone, target: Float, kk: Float) {
            val cur = extractX(b.quat)
            b.quat.setFromAxisAngle(1f, 0f, 0f, cur + (target - cur) * kk)
        }
        approach(Bk["upperArmR"]!!, (r[0] * 2 - 1) * 1.5f, k * 0.2f)
        approach(Bk["upperArmL"]!!, (r[1] * 2 - 1) * 1.5f, k * 0.2f)
        Bk["forearmR"]!!.quat.setFromAxisAngle(1f, 0f, 0f, 0.3f * r[2])
        Bk["forearmL"]!!.quat.setFromAxisAngle(1f, 0f, 0f, 0.4f * r[3])
        dampX(Bk["thighR"]!!, 0.9f)
        approach(Bk["thighL"]!!, 0.4f * r[2], 0.1f)
        dampX(Bk["shinR"]!!, 0.9f); dampX(Bk["shinL"]!!, 0.9f)
        dampX(Bk["spine"]!!, 0.9f); dampX(Bk["chest"]!!, 0.9f)
        approach(Bk["neck"]!!, 0.3f * fallDir, 0.1f)
        gunVisible = deadT < 0.25f
        if (deadT > 4.5f) opacity = kotlin.math.max(0f, 1 - (deadT - 4.5f) / 1.2f)
        computeWorlds()
    }

    private fun extractX(q: Quat): Float {
        // 从四元数近似提取 X 欧拉角
        val sinp = 2f * (q.w * q.x - q.y * q.z)
        return if (kotlin.math.abs(sinp) >= 1f) Math.copySign(1f, sinp) * (Math.PI.toFloat() / 2) else kotlin.math.atan2(sinp, 1f - 2f * (q.x * q.x + q.y * q.y))
    }

    private fun dampX(b: Bone, k: Float) {
        b.quat.setFromAxisAngle(1f, 0f, 0f, extractX(b.quat) * k)
    }

    val gunWorldM = Mat4()
    val gunAnchorWorld = Vec3()

    /** 两骨骼 IK：让手到达世界坐标目标点 */
    fun solveArm(side: String, targetWorld: Vec3, poleWorld: Vec3) {
        val up = B["upperArm$side"]!!
        val fore = B["forearm$side"]!!
        val S = Vec3()
        up.parentWorld(out = S)
        val a = 0.29f; val b = 0.28f
        val toT = targetWorld.copy().sub(S)
        var d = toT.len()
        d = min(d, a + b - 0.001f)
        val dir = toT.normalize()
        val cosA = ((a * a + d * d - b * b) / (2 * a * d)).coerceIn(-1f, 1f)
        val ang = acos(cosA)
        val pole = poleWorld.copy().sub(S)
        pole.addScaled(dir, -pole.dot(dir)).normalize()
        val elbowDir = dir.copy().mul(cos(ang)).addScaled(pole, sin(ang)).normalize()
        // 上臂：世界方向 -> 局部四元数
        val parentQ = Quat()
        up.parentQuat(parentQ)
        val wq = Quat().setFromUnitVectors(Vec3(0f, -1f, 0f), elbowDir)
        up.quat.setQ(parentQ.conjugate().multiply(wq)).normalize()
        up.updateWorld()
        // 前臂指向目标
        val T2 = S.copy().addScaled(dir, d)
        val fdir = T2.sub(elbowPos).normalize()
        val pq = Quat()
        up.worldQuat(pq)
        fore.quat.setQ(pq.conjugate().multiply(Quat().setFromUnitVectors(Vec3(0f, -1f, 0f), fdir))).normalize()
        fore.updateWorld()
    }

    private val elbowPos = Vec3()

    private fun Bone.parentWorld(out: Vec3): Vec3 {
        val p = if (parent >= 0) bones[parent] else null
        if (p != null) return p.world.transformPoint(Vec3(x, y, z), out)
        return rootModel.transformPoint(Vec3(x, y, z), out)
    }

    private fun Bone.parentQuat(out: Quat): Quat {
        val p = if (parent >= 0) bones[parent] else null
        if (p != null) { out.setQ(worldQuatFromMat(p.world)); return out }
        out.setQ(worldQuatFromMat(rootModel)); return out
    }

    private fun Bone.worldQuat(out: Quat): Quat {
        out.setQ(worldQuatFromMat(world)); return out
    }

    private fun Bone.updateWorld() {
        val local = Mat4().compose(x, y, z, quat, 1f, 1f, 1f)
        val p = if (parent >= 0) bones[parent] else null
        if (p != null) world.set(p.world).multiply(local) else world.set(rootModel).multiply(local)
        elbowPos.set(world.transformPoint(Vec3(0f, -0.29f, 0f)))
    }

    private fun worldQuatFromMat(m: Mat4): Quat {
        // 列主序旋转矩阵 -> 四元数
        val m00 = m.m[0]; val m01 = m.m[4]; val m02 = m.m[8]
        val m10 = m.m[1]; val m11 = m.m[5]; val m12 = m.m[9]
        val m20 = m.m[2]; val m21 = m.m[6]; val m22 = m.m[10]
        val tr = m00 + m11 + m22
        val q = Quat()
        if (tr > 0) {
            val s = sqrt(tr + 1f) * 2f
            q.w = 0.25f * s
            q.x = (m21 - m12) / s; q.y = (m02 - m20) / s; q.z = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            q.w = (m21 - m12) / s; q.x = 0.25f * s
            q.y = (m01 + m10) / s; q.z = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            q.w = (m02 - m20) / s; q.x = (m01 + m10) / s; q.y = 0.25f * s
            q.z = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            q.w = (m10 - m01) / s; q.x = (m02 + m20) / s; q.y = (m12 + m21) / s; q.z = 0.25f * s
        }
        return q.normalize()
    }

    // ---------- 命中测试 ----------
    private val hLocalO = Vec3()
    private val hLocalD = Vec3()

    fun hitTest(o: Vec3, d: Vec3, maxT: Float, frame: Int): HitResult? {
        val hp = Vec3()
        B["hips"]!!.world.transformPoint(Vec3(0f, 0f, 0f), hp)
        val wx = hp.x - o.x; val wy = hp.y + 0.3f - o.y; val wz = hp.z - o.z
        val tc = wx * d.x + wy * d.y + wz * d.z
        if (tc < -1.5f || tc > maxT + 1.5f) return null
        val px2 = wx - d.x * tc; val py2 = wy - d.y * tc; val pz2 = wz - d.z * tc
        if (px2 * px2 + py2 * py2 + pz2 * pz2 > 1.6f) return null
        if (invFrame != frame) {
            for (i in HITBOXES.indices) {
                invMats[i].set(B[HITBOXES[i].bone]!!.world.copy().invert())
            }
            invFrame = frame
        }
        var best = maxT; var part: String? = null
        for (i in HITBOXES.indices) {
            val hb = HITBOXES[i]
            val inv = invMats[i]
            hLocalO.set(o.x, o.y, o.z)
            transformPointInv(inv, hLocalO)
            hLocalD.set(d.x, d.y, d.z)
            transformDirInv(inv, hLocalD)
            val ox = hLocalO.x - hb.cx; val oy = hLocalO.y - hb.cy; val oz = hLocalO.z - hb.cz
            var tmin = 0f; var tmax = best
            var ok = true
            // slab
            var t1: Float; var t2: Float
            if (kotlin.math.abs(hLocalD.x) < 1e-9f) { if (ox < -hb.hx || ox > hb.hx) ok = false }
            else {
                t1 = (-hb.hx - ox) / hLocalD.x; t2 = (hb.hx - ox) / hLocalD.x
                if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
                tmin = kotlin.math.max(tmin, t1); tmax = kotlin.math.min(tmax, t2)
                if (tmin > tmax) ok = false
            }
            if (ok) {
                if (kotlin.math.abs(hLocalD.y) < 1e-9f) { if (oy < -hb.hy || oy > hb.hy) ok = false }
                else {
                    t1 = (-hb.hy - oy) / hLocalD.y; t2 = (hb.hy - oy) / hLocalD.y
                    if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
                    tmin = kotlin.math.max(tmin, t1); tmax = kotlin.math.min(tmax, t2)
                    if (tmin > tmax) ok = false
                }
            }
            if (ok) {
                if (kotlin.math.abs(hLocalD.z) < 1e-9f) { if (oz < -hb.hz || oz > hb.hz) ok = false }
                else {
                    t1 = (-hb.hz - oz) / hLocalD.z; t2 = (hb.hz - oz) / hLocalD.z
                    if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
                    tmin = kotlin.math.max(tmin, t1); tmax = kotlin.math.min(tmax, t2)
                    if (tmin > tmax) ok = false
                }
            }
            if (ok && tmin < best) { best = tmin; part = hb.part }
        }
        return if (part != null) HitResult(best, part) else null
    }

    private fun transformPointInv(m: Mat4, v: Vec3) {
        val x = v.x; val y = v.y; val z = v.z
        v.set(
            m.m[0] * x + m.m[4] * y + m.m[8] * z + m.m[12],
            m.m[1] * x + m.m[5] * y + m.m[9] * z + m.m[13],
            m.m[2] * x + m.m[6] * y + m.m[10] * z + m.m[14],
        )
    }

    private fun transformDirInv(m: Mat4, v: Vec3) {
        val x = v.x; val y = v.y; val z = v.z
        v.set(
            m.m[0] * x + m.m[4] * y + m.m[8] * z,
            m.m[1] * x + m.m[5] * y + m.m[9] * z,
            m.m[2] * x + m.m[6] * y + m.m[10] * z,
        )
        val l = v.len()
        if (l > 1e-9f) v.mul(1f / l)
    }

    // ---------- 世界坐标辅助 ----------
    fun muzzleWorld(out: Vec3): Vec3 {
        if (gunId == null) return B["head"]!!.world.transformPoint(Vec3(0f, 0.1f, 0f), out)
        return gunWorldM.transformPoint(Vec3(gunMuzzle[0], gunMuzzle[1], gunMuzzle[2]), out)
    }

    fun headWorld(out: Vec3): Vec3 = B["head"]!!.world.transformPoint(Vec3(0f, 0.1f, 0f), out)
    fun chestWorld(out: Vec3): Vec3 = B["chest"]!!.world.transformPoint(Vec3(0f, 0.1f, 0f), out)
}

class HitResult(val t: Float, val part: String)

fun com.cf.transportship.engine.Quat.setQ(o: com.cf.transportship.engine.Quat) {
    x = o.x; y = o.y; z = o.z; w = o.w
}

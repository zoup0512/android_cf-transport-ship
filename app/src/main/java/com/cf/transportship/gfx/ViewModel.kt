// 第一人称武器与手臂（移植 viewmodel.js）
package com.cf.transportship.gfx

import com.cf.transportship.engine.Mat4
import com.cf.transportship.engine.Quat
import com.cf.transportship.engine.Renderer
import com.cf.transportship.engine.StaticMesh
import com.cf.transportship.engine.Vec2
import com.cf.transportship.engine.Vec3
import com.cf.transportship.engine.Layouts
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

val HIP = mapOf(
    "ak47" to floatArrayOf(0.165f, -0.175f, -0.55f, 0.045f, 0.165f, 0.02f),
    "m4a1" to floatArrayOf(0.16f, -0.18f, -0.54f, 0.045f, 0.165f, 0.02f),
    "awm" to floatArrayOf(0.17f, -0.185f, -0.6f, 0.04f, 0.15f, 0.02f),
    "mp5" to floatArrayOf(0.155f, -0.165f, -0.5f, 0.05f, 0.17f, 0.02f),
    "deagle" to floatArrayOf(0.085f, -0.115f, -0.4f, 0.05f, 0.1f, 0f),
    "knife" to floatArrayOf(0.17f, -0.15f, -0.34f, 0.35f, -0.25f, 0.55f),
    "he" to floatArrayOf(0.13f, -0.12f, -0.3f, 0.1f, -0.2f, 0.2f),
)

val KICK = mapOf(
    "ak47" to floatArrayOf(0.04f, 0.07f), "m4a1" to floatArrayOf(0.032f, 0.05f),
    "awm" to floatArrayOf(0.09f, 0.2f), "mp5" to floatArrayOf(0.024f, 0.035f),
    "deagle" to floatArrayOf(0.05f, 0.22f),
)

private fun ease(t: Float) = t * t * (3 - 2 * t)
private fun seg(f: Float, a: Float, b: Float) = ((f - a) / (b - a)).coerceIn(0f, 1f)

class ViewModel(val M: GunMaterials, team: String) {
    // 枪
    var curId: String? = null
    var curModel: GunModel? = null
    private val gunCache = HashMap<String, GunModel>()

    // 部件动画偏移
    class PartState(var ox: Float = 0f, var oy: Float = 0f, var oz: Float = 0f, var rx: Float = 0f, var ry: Float = 0f, var rz: Float = 0f, var visible: Boolean = true)

    val partState = HashMap<String, PartState>()
    val parts = HashMap<String, GunPart>()

    // 手臂
    var sleeveColor = floatArrayOf(0.132f, 0.132f, 0.145f)
    var cuffColor = floatArrayOf(0.42f, 0.06f, 0.06f)

    // 枪口火焰
    var flashT = 0f
    var flashFrontRot = 0f
    var flashScale = 1f
    var flashTexIdx = 0

    // 弹壳
    class Shell {
        var x = 0f; var y = 0f; var z = 0f
        var vx = 0f; var vy = 0f; var vz = 0f
        var rx = 0f; var ry = 0f; var rz = 0f
        var wx = 0f; var wy = 0f
        var life = 0f
    }
    val shells = Array(14) { Shell() }
    private var shellIdx = 0

    // 状态
    var t = 0f
    var kick = 0f; var kickV = 0f; var kickRot = 0f; var kickRotV = 0f
    val sway = Vec2(); private val swayT = Vec2()
    var bobT = 0f; var bobK = 0f
    var land = 0f
    var drawT = 1f; var drawDur = 0.5f
    var animType: String? = null; var animT = 0f; var animDur = 0f
    var animEmpty = false; var animHeavy = false; var animDir = 1f
    var animEjected = false
    var slideT = 0f
    var visible = true

    // 输出矩阵（渲染用）
    val holderM = Mat4()

    // 手臂变换输出
    val armR = ArmPose()
    val armL = ArmPose()

    class ArmPose {
        var visible = true
        val foreM = Mat4()
        val cuffM = Mat4()
        val handM = Mat4()
    }

    fun setTeam(team: String) {
        if (team == "GR") {
            sleeveColor = floatArrayOf(0.048f, 0.066f, 0.096f)
            cuffColor = floatArrayOf(0.014f, 0.128f, 0.564f)
        } else {
            sleeveColor = floatArrayOf(0.016f, 0.017f, 0.021f)
            cuffColor = floatArrayOf(0.395f, 0.008f, 0.008f)
        }
    }

    fun equip(id: String, drawTime: Float) {
        curModel = gunCache.getOrPut(id) { GunModels.build(id, M) }
        curId = id
        parts.clear()
        for (p in curModel!!.parts) if (p.name != null) parts[p.name!!] = p
        partState.clear()
        for (k in parts.keys) partState[k] = PartState()
        drawT = 0f; drawDur = drawTime
        animType = null
        curVisible = true
        kick = 0f; kickV = 0f; kickRot = 0f; kickRotV = 0f
    }

    fun fire() {
        val id = curId ?: return
        val k = KICK[id] ?: floatArrayOf(0.03f, 0.05f)
        kickV += k[0] * 38; kickRotV += k[1] * 38
        flashT = 0.05f
        flashTexIdx = (Math.random() * 3).toInt()
        flashFrontRot = (Math.random() * Math.PI).toFloat()
        val sc = when (id) {
            "awm" -> 1.6f; "deagle" -> 1.2f; "mp5" -> 0.8f; else -> 1f
        }
        flashScale = sc * (0.8f + Math.random().toFloat() * 0.45f)
        if (id != "awm") ejectShell() else startAnim("bolt", 1.3f)
        if (id == "deagle") slideT = 0.09f
    }

    private fun ejectShell() {
        val s = shells[shellIdx++ % shells.size]
        // 从抛壳口（近似）出发
        val an = gunAnchorTmp("eject")
        holderM.transformPoint(Vec3(an[0], an[1], an[2]), shellPos)
        s.x = shellPos.x; s.y = shellPos.y; s.z = shellPos.z
        s.life = 0.9f
        s.vx = 0.9f + Math.random().toFloat() * 0.6f
        s.vy = 0.9f + Math.random().toFloat() * 0.5f
        s.vz = 0.25f + Math.random().toFloat() * 0.3f
        s.wx = (Math.random().toFloat() * 20)
        s.wy = (Math.random().toFloat() * 20)
        s.rx = 0f; s.ry = Math.random().toFloat()
    }

    private val shellPos = Vec3()
    private fun gunAnchorTmp(name: String): FloatArray = curModel?.anchors?.get(name) ?: floatArrayOf(0f, 0f, 0f)

    fun startAnim(type: String, dur: Float, empty: Boolean = false, heavy: Boolean = false) {
        animType = type; animT = 0f; animDur = dur; animEmpty = empty; animHeavy = heavy
        animDir = if (Math.random() > 0.5) 1f else -1f
        animEjected = false
    }

    fun reload(dur: Float, empty: Boolean) { startAnim("reload", dur, empty) }
    fun melee(heavy: Boolean) { startAnim(if (heavy) "stab" else "slash", if (heavy) 0.85f else 0.38f, heavy = heavy) }
    fun throwNade() { startAnim("throw", 0.75f) }
    fun inspect() { if (animType == null) startAnim("inspect", 2.6f) }
    fun cancelAnim() { if (animType != null && animType != "bolt") animType = null; resetParts() }

    private fun resetParts() {
        for ((k, ps) in partState) { ps.ox = 0f; ps.oy = 0f; ps.oz = 0f; ps.rx = 0f; ps.ry = 0f; ps.rz = 0f; ps.visible = true }
    }

    private val hipP = FloatArray(3)
    private val handLT = Vec3()
    private var handLSet = false
    private var handROverride: Vec3? = null

    fun update(dt: Float, speed: Float, onGround: Boolean, crouch: Boolean, lookDX: Float, lookDY: Float, light: Float, indoor: Boolean) {
        t += dt
        curVisible = true
        val id = curId ?: return
        val hip = HIP[id] ?: HIP["ak47"]!!
        hipP[0] = hip[0]; hipP[1] = hip[1]; hipP[2] = hip[2]
        // 弹簧：后坐
        val k1 = 260f; val d1 = 26f
        kickV += (-k1 * kick - d1 * kickV) * dt; kick += kickV * dt
        kickRotV += (-k1 * kickRot - d1 * kickRotV) * dt; kickRot += kickRotV * dt
        // 鼠标惯性摆动
        swayT.x = (-lookDX * 0.00055f).coerceIn(-0.05f, 0.05f)
        swayT.y = (lookDY * 0.00055f).coerceIn(-0.04f, 0.04f)
        sway.x += (swayT.x - sway.x) * min(1f, dt * 9)
        sway.y += (swayT.y - sway.y) * min(1f, dt * 9)
        // 行走晃动
        val moving = onGround && speed > 0.5f
        bobK += ((if (moving) min(1f, speed / 6f) else 0f) - bobK) * min(1f, dt * 6)
        bobT += dt * (5 + speed * 1.2f) * (if (moving) 1f else 0.3f)
        val bx = sin(bobT) * 0.011f * bobK
        val by = -abs(cos(bobT)) * 0.009f * bobK
        val breathe = sin(t * 1.6f) * 0.0018f
        land *= exp(-dt * 8f)
        // 拔枪
        drawT = min(1f, drawT + dt / drawDur)
        val dr = 1 - ease(drawT)
        var px = hipP[0] + bx + sway.x
        var py = hipP[1] + by + breathe + sway.y - dr * 0.22f - land * 0.03f - (if (crouch) 0.006f else 0f)
        var pz = hipP[2] + kick
        var rx = hip[3] + kickRot - dr * 0.9f + sway.y * 1.5f
        var ry = hip[4] + sway.x * 2f
        var rz = hip[5] + (if (crouch) -0.03f else 0f) + bx * 2f
        // 部件复位
        resetParts()
        handLSet = false; handROverride = null
        if (slideT > 0 && parts.containsKey("slide")) {
            slideT -= dt
            partState["slide"]!!.oz = maxOf(0f, slideT) * 0.5f
        }
        val aType = animType
        if (aType != null) {
            animT += dt
            val f = min(1f, animT / animDur)
            when (aType) {
                "reload" -> {
                    val tilt = ease(seg(f, 0f, 0.15f)) * (1 - ease(seg(f, 0.85f, 1f)))
                    rz += tilt * 0.45f; rx += tilt * 0.18f; py -= tilt * 0.03f; px -= tilt * 0.02f
                    val mag = parts["mag"]
                    if (mag != null) {
                        val ps = partState["mag"]!!
                        val out2 = ease(seg(f, 0.15f, 0.3f))
                        val gone = seg(f, 0.3f, 0.42f)
                        val back = ease(seg(f, 0.42f, 0.62f))
                        val seat = seg(f, 0.62f, 0.7f)
                        if (f < 0.3f) { ps.oy = -out2 * 0.12f; ps.oz = out2 * 0.02f; ps.visible = true }
                        else if (f < 0.42f) { ps.oy = -0.12f - gone * 0.4f; ps.visible = gone < 0.95f }
                        else { ps.visible = true; ps.oy = -(1 - back) * 0.3f - (1 - seat) * 0.015f; ps.oz = (1 - back) * 0.05f }
                        if (f > 0.12f && f < 0.72f) {
                            // 左手到弹匣位置
                            partWorld(mag, handLT)
                            handLT.y -= 0.06f
                            handLSet = true
                        }
                        if (f > 0.62f && f < 0.7f) kickRot -= dt * 0.8f
                    }
                    val bolt = parts["bolt"]
                    if (animEmpty && bolt != null && f > 0.74f && f < 0.92f) {
                        val bf = seg(f, 0.74f, 0.92f)
                        val pull = sin(bf * Math.PI).toFloat()
                        partState["bolt"]!!.oz = pull * 0.07f
                        if (id == "ak47" || id == "awm") { /* 右手 */ } else { partWorld(bolt, handLT); handLSet = true }
                        if (id == "ak47") { partWorld(bolt, handLT); handLSet = true }
                        rz -= pull * 0.1f
                    }
                    if (id == "deagle" && animEmpty && f > 0.8f && f < 0.9f) {
                        partState["slide"]!!.oz = 0.03f
                    }
                }
                "bolt" -> {
                    val bolt = parts["bolt"]
                    if (bolt != null) {
                        val f2 = seg(f, 0.15f, 0.85f)
                        val up = ease(seg(f2, 0f, 0.2f)) * (1 - ease(seg(f2, 0.8f, 1f)))
                        val backK = ease(seg(f2, 0.2f, 0.45f)) * (1 - ease(seg(f2, 0.55f, 0.8f)))
                        partState["bolt"]!!.rz = up * 1.2f
                        partState["bolt"]!!.oz = backK * 0.09f
                        val sw2 = kotlin.math.sin(f2 * Math.PI.toFloat()); rz += sw2 * 0.18f; rx += sw2 * 0.06f; py -= sw2 * 0.015f
                        if (f2 > 0 && f2 < 1) { partWorld(bolt, handLT); handROverride = Vec3(handLT.x, handLT.y, handLT.z) }
                        if (backK > 0.5f && !animEjected) { animEjected = true; ejectShell() }
                    }
                }
                "slash" -> {
                    val s = kotlin.math.sin(f * Math.PI.toFloat())
                    val dir = animDir
                    ry += dir * (f - 0.5f) * -2.2f; rz += dir * s * -0.6f
                    px += dir * (0.5f - f) * 0.12f; pz -= s * 0.08f; rx += s * 0.3f
                }
                "stab" -> {
                    val wind = ease(seg(f, 0f, 0.35f))
                    val thrust = ease(seg(f, 0.35f, 0.55f))
                    val ret = ease(seg(f, 0.65f, 1f))
                    pz += wind * 0.08f - thrust * 0.22f + ret * 0.14f
                    rx += wind * -0.4f + thrust * 0.9f - ret * 0.5f
                    py += wind * 0.05f - thrust * 0.04f
                }
                "throw" -> {
                    val pin = seg(f, 0f, 0.3f)
                    val wind = ease(seg(f, 0.3f, 0.55f))
                    val thr = ease(seg(f, 0.55f, 0.8f))
                    partState["pin"]?.visible = pin < 0.6f
                    if (pin < 1f) { handLSet = true; handLT.set(0.03f, 0.06f, 0f) }
                    py += wind * 0.08f - thr * 0.12f; pz += wind * 0.1f - thr * 0.3f
                    rx += wind * 0.8f - thr * 1.6f
                    if (f > 0.72f) curVisible = false
                    if (f >= 1f) { /* 手雷已掷出，等待切枪 */ }
                }
                "inspect" -> {
                    val s1 = ease(seg(f, 0f, 0.25f)) * (1 - ease(seg(f, 0.8f, 1f)))
                    val s2 = ease(seg(f, 0.4f, 0.6f)) * (1 - ease(seg(f, 0.8f, 1f)))
                    ry += s1 * 0.9f - s2 * 1.1f; rz += s1 * 0.5f
                    px -= s1 * 0.08f; py += s1 * 0.04f; rx += s2 * 0.3f
                }
            }
            if (f >= 1) {
                animType = null
                resetParts()
            }
        }
        holderM.identity()
        val hq = Quat().setFromEulerYXZ(rx, ry, rz)
        holderM.compose(px, py, pz, hq, 1f, 1f, 1f)
        holderPos.set(px, py, pz)
        // 手臂 IK
        val gripA = curModel?.anchors?.get("grip") ?: floatArrayOf(0f, 0f, 0f)
        val foreA = curModel?.anchors?.get("fore")
        val gripW = holderM.transformPoint(Vec3(gripA[0], gripA[1], gripA[2]))
        if (id == "knife") armL.visible = false
        else {
            armL.visible = true
            val target = if (handLSet) handLT else if (foreA != null) holderM.transformPoint(Vec3(foreA[0], foreA[1], foreA[2])) else gripW
            placeArm(armL, target, false, px, py, pz, id)
        }
        armR.visible = true
        placeArm(armR, handROverride ?: gripW, true, px, py, pz, id)
        // 枪口火焰
        if (flashT > 0) flashT -= dt
        // 弹壳
        for (s in shells) {
            if (s.life <= 0) continue
            s.life -= dt
            s.vy -= 9.8f * dt
            s.x += s.vx * dt; s.y += s.vy * dt; s.z += s.vz * dt
            s.rx += s.wx * dt; s.ry += s.wy * dt
        }
        this.lightK = light
        this.indoorK = indoor
    }

    var curVisible = true
    var lightK = 1f
    var indoorK = false

    private fun partWorld(p: GunPart, out: Vec3) {
        val ps = partState[p.name]
        val ox = ps?.ox ?: 0f; val oy = ps?.oy ?: 0f; val oz = ps?.oz ?: 0f
        holderM.transformPoint(Vec3(p.restPos[0] + ox, p.restPos[1] + oy, p.restPos[2] + oz), out)
    }

    private val tmpV = Vec3()
    private val tmpV2 = Vec3()

    private fun placeArm(A: ArmPose, handPos: Vec3, right: Boolean, hpx: Float, hpy: Float, hpz: Float, id: String) {
        val e = elbowBase(id, right)
        val dir = handPos.copy().sub(e)
        val L = dir.len()
        dir.normalize()
        val back = handPos.copy().addScaled(dir, -0.05f)
        val mid = e.copy().add(back).mul(0.5f)
        // 前臂：沿 dir 拉伸的胶囊
        val q = Quat().setFromUnitVectors(Vec3(0f, 1f, 0f), dir)
        A.foreM.compose(mid.x, mid.y, mid.z, q, 1f, kotlin.math.max(0.2f, (L - 0.05f) / 0.39f), 1f)
        val cuffP = handPos.copy().addScaled(dir, -0.075f)
        A.cuffM.compose(cuffP.x, cuffP.y, cuffP.z, q, 1f, 1f, 1f)
        val hq = Quat().setQ(holderMQuat())
        if (right) hq.multiply(Quat().setFromAxisAngle(1f, 0f, 0f, -0.35f))
        else hq.multiply(Quat().setFromAxisAngle(1f, 0f, 0f, 0.3f))
        A.handM.compose(handPos.x, handPos.y, handPos.z, hq, 1f, 1f, 1f)
    }

    private var holderQ = Quat()
    private fun holderMQuat(): Quat {
        // 从 holderM 提取旋转
        val m = holderM
        val m00 = m.m[0]; val m01 = m.m[4]; val m02 = m.m[8]
        val m10 = m.m[1]; val m11 = m.m[5]; val m12 = m.m[9]
        val m20 = m.m[2]; val m21 = m.m[6]; val m22 = m.m[10]
        val tr = m00 + m11 + m22
        val q = holderQ
        if (tr > 0) {
            val s = kotlin.math.sqrt(tr + 1f) * 2f
            q.w = 0.25f * s
            q.x = (m21 - m12) / s; q.y = (m02 - m20) / s; q.z = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = kotlin.math.sqrt(1f + m00 - m11 - m22) * 2f
            q.w = (m21 - m12) / s; q.x = 0.25f * s
            q.y = (m01 + m10) / s; q.z = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = kotlin.math.sqrt(1f + m11 - m00 - m22) * 2f
            q.w = (m02 - m20) / s; q.x = (m01 + m10) / s; q.y = 0.25f * s
            q.z = (m12 + m21) / s
        } else {
            val s = kotlin.math.sqrt(1f + m22 - m00 - m11) * 2f
            q.w = (m10 - m01) / s; q.x = (m02 + m20) / s; q.y = (m12 + m21) / s; q.z = 0.25f * s
        }
        return q.normalize()
    }

    private fun elbowBase(id: String, right: Boolean): Vec3 {
        val hip = HIP[id] ?: HIP["ak47"]!!
        return if (right) {
            val v = Vec3(0.5f, -0.44f, -0.3f)
            v.x += (holderPos.x - hip[0]) * 0.6f
            v.y += (holderPos.y - hip[1]) * 0.6f
            v.z += (holderPos.z - hip[2]) * 0.5f
            if (id == "deagle") { /* 右手默认 */ }
            v
        } else {
            if (id == "deagle") Vec3(0.0f, -0.46f, -0.3f)
            else {
                val v = Vec3(-0.04f, -0.5f, -0.5f)
                v.x += (holderPos.x - hip[0]) * 0.6f
                v.y += (holderPos.y - hip[1]) * 0.6f
                v.z += (holderPos.z - hip[2]) * 0.5f
                v
            }
        }
    }

    val holderPos = Vec3()
}

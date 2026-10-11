// 机器人 AI（移植 bots.js）
package com.cf.transportship.game

import com.cf.transportship.engine.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class Diff(
    val react0: Float, val react1: Float, val aimErr: Float, val turn: Float, val track: Float,
    val headP: Float, val ctrl: Float, val comp: Float, val strafe: Float,
    val see: Float, val fov: Float, val bunny: Float,
)

val DIFFS = mapOf(
    "easy" to Diff(0.55f, 0.9f, 0.075f, 3.2f, 2.2f, 0.07f, 1.0f, 0.55f, 0.35f, 50f, 1.6f, 0f),
    "normal" to Diff(0.38f, 0.62f, 0.058f, 5.2f, 3.2f, 0.12f, 0.85f, 0.8f, 0.65f, 62f, 1.85f, 0.05f),
    "hard" to Diff(0.2f, 0.34f, 0.032f, 8.5f, 5.5f, 0.3f, 0.6f, 0.9f, 0.85f, 75f, 2.05f, 0.12f),
    "hell" to Diff(0.12f, 0.2f, 0.02f, 13f, 8.5f, 0.5f, 0.42f, 0.95f, 1f, 95f, 2.3f, 0.2f),
)

val BOT_NAMES = arrayOf(
    "丶夜猫子", "CF灬战神", "狙神小白", "枪王之王", "火麒麟丶", "无敌小旋风", "天使の翼", "爆头专业户",
    "穿越者丨龙", "雷神M4", "灬冷血杀手", "沙鹰一哥", "老六本六", "我是菜鸟", "运输船之王", "Sniper丶K",
    "二楼架枪", "管道守门员", "一枪一个", "闪电侠丶", "黑名单丶影", "保卫者老王", "夜袭者", "零度丶",
    "狂暴战神", "别打我头", "满血复活", "疾风步",
)

// 各阵营的架点（潜伏者坐标，保卫者取反）
val HOLDS = arrayOf(
    floatArrayOf(-20.5f, -7.2f, 0.2f), floatArrayOf(-24.6f, 5.8f, -0.15f), floatArrayOf(-16.4f, 1.3f, 0.1f),
    floatArrayOf(-9.8f, -6.8f, 0.25f), floatArrayOf(-26.2f, -6.5f, 0.05f),
)
val LANES = floatArrayOf(-6.8f, -0.4f, 6.8f)

class Bot(game: Game, id: Int, name: String, team: String, diffKey: String) : Actor(game, id, name, team) {
    val diff = DIFFS[diffKey] ?: DIFFS["normal"]!!

    var path: Array<FloatArray>? = null
    var pi = 0
    var goal: FloatArray? = null
    var target: Actor? = null
    var visible = false
    var lastSeen: Vec3? = null
    var lastSeenT = -99f
    var reactUntil = 0f
    var errY = 0f; var errP = 0f
    var aimHead = false
    var strafeDir = 1; var strafeT = 0f
    var burst = 0; var burstPauseUntil = 0f
    var thinkT = (Math.random() * 0.2).toFloat()
    var stuckT = 0f; var stuckN = 0
    val lastCheck = Vec3()
    var role = "rush"; var holdT = 0f
    var crouchUntil = 0f
    var nadeT = 20f + Math.random().toFloat() * 20f
    var heard: Vec3? = null; var heardT = -99f; var heardLoud = false
    var fireHeld = false
    var wantJump = false
    var nadePlan: Vec3? = null
    var lane = 0f
    var holdYaw = 0f
    var lookYaw: Float? = null; var lookPitch: Float? = null
    var huntFor = 0f
    var stage = 0

    private var side = 1

    init {
        recoilControl = diff.ctrl
        side = if (team == "BL") 1 else -1
    }

    fun onSpawn() {
        path = null; goal = null; target = null; visible = false; lastSeen = null
        val r = Math.random().toFloat()
        role = if (primary == "awm") "hold" else if (r < 0.25f) "flank" else "rush"
        lane = LANES[(Math.random() * 3).toInt()]
        stage = 0
        pickGoal()
    }

    /** 己方坐标 -> 世界（潜伏者坐标系，保卫者取反） */
    private fun L(x: Float, z: Float): FloatArray = floatArrayOf(x * side, z * side)

    private fun pickGoal() {
        val nav = game.nav
        var gx: Float; var gz: Float
        if (role == "hold") {
            val h = HOLDS[(Math.random() * HOLDS.size).toInt()]
            val p = L(h[0], h[1])
            gx = p[0]; gz = p[1]
            holdYaw = if (side > 0) -kotlin.math.PI.toFloat() / 2 + h[2] else kotlin.math.PI.toFloat() / 2 + h[2]
        } else if (role == "flank" && stage < 3) {
            val pts = arrayOf(floatArrayOf(-31.5f, 10.5f), floatArrayOf(-8f, 10.6f), floatArrayOf(8f, 7.8f))
            val p = L(pts[stage][0], pts[stage][1])
            gx = p[0]; gz = p[1]
        } else if (stage < 1) {
            val p = L(4 + Math.random().toFloat() * 18, lane + (Math.random().toFloat() - 0.5f) * 2)
            gx = p[0]; gz = p[1]
        } else {
            val p = nav.randomFree({ Math.random().toFloat() }, -26f, -8.8f, 26f, 8.8f)
            if (p != null) { gx = p[0]; gz = p[1] } else { val q = L(10f, 0f); gx = q[0]; gz = q[1] }
        }
        goal = floatArrayOf(gx, gz)
        path = nav.findPath(pos.x, pos.z, gx, gz)
        pi = 1
    }

    fun hear(pos: Vec3, loud: Boolean) {
        if (!alive || visible) return
        heard = pos.copy()
        heardT = game.time
        heardLoud = loud
    }

    override fun onDamaged(att: Actor?) {
        if (att == null || !alive) return
        if (!visible || target !== att) {
            lastSeen = att.pos.copy()
            lastSeenT = game.time
            if (!visible) {
                target = att
                // 朝攻击者方向扭头（带误差）
                lookYaw = atan2(-(att.pos.x - pos.x), -(att.pos.z - pos.z)) + (Math.random().toFloat() - 0.5f) * 0.5f
            }
        }
    }

    private val eyeV = Vec3()
    private val tv2 = Vec3()
    private val tv3 = Vec3()

    fun canSee(t: Actor): Boolean {
        eye(eyeV)
        val dist = eyeV.dist(t.pos)
        if (dist > diff.see) return false
        // 视野角
        val dx = t.pos.x - pos.x
        val dz = t.pos.z - pos.z
        val ang = abs(wrapPiF(atan2(-dx, -dz) - yaw))
        if (ang > diff.fov / 2 && dist > 6) return false
        t.soldier.headWorld(tv2)
        t.soldier.chestWorld(tv3)
        for (pt in arrayOf(tv2, tv3)) {
            val d = pt.copy().sub(eyeV)
            val L = d.len()
            d.mul(1f / L)
            if (game.world.raycast(eyeV.x, eyeV.y, eyeV.z, d.x, d.y, d.z, L - 0.1f, "sight") == null) return true
        }
        return false
    }

    private val aimV = Vec3()

    fun think() {
        val g = game
        val now = g.time
        // 感知
        var best: Actor? = null
        var bestD = 1e9f
        for (a in g.actors) {
            if (!a.alive || a.team == team) continue
            val d = a.pos.dist(pos)
            if (d > bestD) continue
            if (canSee(a)) { best = a; bestD = d }
        }
        if (best != null) {
            if (!visible || target !== best) {
                val r0 = diff.react0; val r1 = diff.react1
                val surprise = if (target === best && now - lastSeenT < 1.5f) 0.4f else 1f
                reactUntil = now + (r0 + Math.random().toFloat() * (r1 - r0)) * surprise
                val k = diff.aimErr * (0.8f + bestD / 28f)
                val a = Math.random().toFloat() * kotlin.math.PI.toFloat() * 2
                errY = cos(a) * k * 1.3f
                errP = sin(a) * k * 0.8f - k * 0.1f
                aimHead = Math.random().toFloat() < diff.headP
                burst = 0
            }
            target = best; visible = true
            lastSeen = best.pos.copy(); lastSeenT = now
            path = null
        } else {
            visible = false
            if (target != null && (target!!.alive.not() || now - lastSeenT > 5)) target = null
        }
        // 移动决策
        if (!visible) {
            if (lastSeen != null && now - lastSeenT < 5 && role != "hold") {
                if (path == null || huntFor != lastSeenT) {
                    path = g.nav.findPath(pos.x, pos.z, lastSeen!!.x, lastSeen!!.z)
                    pi = 1; huntFor = lastSeenT
                }
            } else if (heard != null && now - heardT < 3 && role != "hold" && Math.random().toFloat() < 0.5f) {
                path = g.nav.findPath(pos.x, pos.z, heard!!.x, heard!!.z)
                pi = 1
                lookYaw = atan2(-(heard!!.x - pos.x), -(heard!!.z - pos.z))
                heard = null
            } else if (path == null || pi >= path!!.size) {
                if (role == "hold" && goal != null) {
                    val gd = hypot(goal!![0] - pos.x, goal!![1] - pos.z)
                    if (gd < 1.2f) {
                        holdT += 0.15f
                        if (holdT > 25 + Math.random().toFloat() * 20) { holdT = 0f; pickGoal() }
                    } else { stage++; pickGoal() }
                } else { stage++; pickGoal() }
            }
            // 换弹
            val w = weapon
            if (w.def.mag > 1 && w.mag < w.def.mag * 0.5f && w.canReload() && now - lastSeenT > 1.2f) startReload()
            // 手雷
            nadeT -= 0.15f
            if (nadeT <= 0 && lastSeen != null && now - lastSeenT < 3 && inv[3].mag > 0) {
                val d = lastSeen!!.dist(pos)
                if (d > 7 && d < 24 && Math.random().toFloat() < 0.35f) {
                    nadePlan = lastSeen!!.copy()
                    nadeT = 25 + Math.random().toFloat() * 25
                }
            }
        } else {
            // 狙击：开镜
            val w = weapon
            if (w.def.type == "sniper" && scoped == 0 && bestD > 7 && now >= w.boltUntil) wantScope = true
            if (Math.random().toFloat() < 0.12f * diff.strafe) crouchUntil = now + 0.6f + Math.random().toFloat() * 1.2f
        }
        // 卡住检测
        stuckT += 0.15f
        if (stuckT > 1f) {
            val moved = lastCheck.dist(pos)
            if (path != null && pi < path!!.size && moved < 0.35f && !visible) {
                stuckN++; wantJump = true
                if (stuckN > 2) { stuckN = 0; stage++; pickGoal() }
            } else stuckN = 0
            lastCheck.set(pos); stuckT = 0f
        }
    }

    override fun update(dt: Float) {
        val g = game
        val now = g.time
        if (!alive) return
        thinkT -= dt
        if (thinkT <= 0) { thinkT = 0.13f + Math.random().toFloat() * 0.06f; think() }
        val D = diff
        var wishX = 0f; var wishZ = 0f
        var fire = false; var firePressed = false; var alt = false
        val crouch = now < crouchUntil
        var walk = false
        var sw: Int? = null
        // 手雷投掷流程
        if (nadePlan != null) {
            if (slot != 3) sw = 3
            else if (now >= readyAt && pendingThrow <= 0) {
                val d = nadePlan!!.dist(pos)
                lookYaw = atan2(-(nadePlan!!.x - pos.x), -(nadePlan!!.z - pos.z))
                lookPitch = 0.25f + d * 0.012f
                firePressed = true
                nadePlan = null
            }
        } else if (slot == 3 && inv[3].mag <= 0 && pendingThrow <= 0 && autoSwitchAt <= 0) sw = 0
        // 瞄准
        var dYaw = yaw; var dPitch = pitch
        val tgt = target
        if (tgt != null && tgt.alive && visible) {
            eye(eyeV)
            val p = if (aimHead) tgt.soldier.headWorld(aimV) else tgt.soldier.chestWorld(aimV)
            p.addScaled(tgt.vel, 0.08f)
            val dx = p.x - eyeV.x; val dy = p.y - eyeV.y; val dz = p.z - eyeV.z
            val hd = hypot(dx, dz)
            val trueYaw = atan2(-dx, -dz)
            val truePitch = atan2(dy, hd)
            val k = kotlin.math.exp(-D.track * dt)
            errY *= k; errP *= k
            val wob = 0.004f * (1 + tgt.speed / 4)
            dYaw = trueYaw + errY + sin(now * 3.1f + id) * wob
            dPitch = truePitch + errP + cos(now * 2.7f + id) * wob * 0.6f - punchP * D.comp
            lookYaw = null; lookPitch = null
            // 开火判断
            val aimErr = hypot(wrapPiF(yaw - trueYaw), pitch - truePitch)
            val tol = atan2(0.32f, hd) * 1.4f + 0.01f
            val w = weapon
            val d = w.def
            if (now >= reactUntil && aimErr < tol && now >= readyAt && nadePlan == null) {
                if (d.type == "sniper") {
                    if (scoped > 0 && scopeReady && aimErr < tol * 0.6f) firePressed = true
                } else if (d.type == "pistol") {
                    firePressed = Math.random().toFloat() < dt * 5
                } else if (now >= burstPauseUntil) {
                    fire = true
                    if (hd > 14) {
                        burst++
                        if (burst > 3 + (Math.random().toFloat() * 4).toInt()) {
                            burst = 0
                            burstPauseUntil = now + 0.18f + Math.random().toFloat() * 0.3f * (hd / 30)
                        }
                    }
                }
            }
            if (wantScope) { alt = true; wantScope = false }
            // 横移
            strafeT -= dt
            if (strafeT <= 0) {
                strafeT = 0.25f + Math.random().toFloat() * 0.7f
                strafeDir = if (Math.random().toFloat() < 0.5f) -1 else 1
                if (Math.random().toFloat() < 0.2f) strafeDir = 0
            }
            val moveK = if (d.type == "sniper") 0f else D.strafe
            val rxx = cos(yaw); val rzz = -sin(yaw)
            wishX += rxx * strafeDir * moveK; wishZ += rzz * strafeDir * moveK
            if (hd > 22 && d.type != "sniper" && role != "hold") {
                wishX += -sin(yaw) * 0.5f; wishZ += -cos(yaw) * 0.5f
            }
            if (Math.random().toFloat() < D.bunny * dt && onGround && d.type != "sniper") wantJump = true
        } else {
            if (scoped > 0 && !visible && now - lastSeenT > 2) alt = true // 收镜
            // 沿路径行走
            val pth = path
            if (pth != null && pi < pth.size) {
                val wp = pth[pi]
                val dx = wp[0] - pos.x; val dz = wp[1] - pos.z
                val dd = hypot(dx, dz)
                if (dd < 0.55f) pi++
                else { wishX = dx / dd; wishZ = dz / dd }
                if (lookYaw == null || now - heardT > 1.5f) dYaw = atan2(-dx, -dz)
                dPitch = 0f
                walk = role == "flank" && stage == 1
            } else if (role == "hold") {
                dYaw = holdYaw + sin(now * 0.4f + id) * 0.35f
                dPitch = -0.02f
            }
            if (lookYaw != null) {
                dYaw = lookYaw!!
                if (lookPitch != null) dPitch = lookPitch!!
            }
        }
        // 队友分离
        for (a in g.actors) {
            if (a === this || !a.alive) continue
            val dx = pos.x - a.pos.x; val dz = pos.z - a.pos.z
            val d2 = dx * dx + dz * dz
            if (d2 < 1.2f && d2 > 1e-4f) {
                val d = kotlin.math.sqrt(d2)
                wishX += dx / d * 0.6f; wishZ += dz / d * 0.6f
            }
        }
        // 转向（限速）
        val turn = D.turn * dt * (if (visible) 1f else 0.7f)
        val ey = wrapPiF(dYaw - yaw)
        yaw = wrapPiF(yaw + ey.coerceIn(-turn, turn))
        pitch += (dPitch - pitch).coerceIn(-turn * 0.6f, turn * 0.6f)
        // 移动 & 武器
        move(dt, wishX, wishZ, wantJump, crouch, walk)
        wantJump = false
        val wi = WeaponInput()
        wi.fire = fire
        wi.firePressed = firePressed || (fire && !fireHeld)
        wi.alt = alt
        wi.altPressed = alt
        wi.reload = false
        wi.sw = sw
        weaponUpdate(dt, wi)
        fireHeld = fire
    }

    private var wantScope = false
}

fun wrapPiF(a: Float): Float {
    var r = a
    val pi = kotlin.math.PI.toFloat()
    while (r > pi) r -= kotlin.math.PI.toFloat() * 2
    while (r < -pi) r += kotlin.math.PI.toFloat() * 2
    return r
}

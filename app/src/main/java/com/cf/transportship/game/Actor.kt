// 角色基类：移动物理 + 武器状态机（玩家与机器人共用，移植 actor.js）
package com.cf.transportship.game

import com.cf.transportship.engine.Vec3
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

const val STAND_H = 1.8f
const val CROUCH_H = 1.15f
const val EYE_STAND = 1.62f
const val EYE_CROUCH = 1.05f
const val GRAV = 19f
const val JUMP_V = 6.6f
const val RUN = 5.7f

class WeaponInput {
    var fire = false
    var firePressed = false
    var alt = false
    var altPressed = false
    var reload = false
    var sw: Int? = null
}

class ActorStats {
    var k = 0; var d = 0; var hs = 0; var shots = 0; var hits = 0
}

abstract class Actor(
    val game: Game,
    val id: Int,
    val name: String,
    val team: String,
    val isPlayer: Boolean = false,
) {
    val pos = Vec3()
    val vel = Vec3()
    var radius = 0.36f
    var height = STAND_H
    var stepHeight = 0.42f
    var onGround = false
    var groundSurface = "metal"
    var crouch = false
    var eyeH = EYE_STAND
    var yaw = 0f; var pitch = 0f
    var punchP = 0f; var punchY = 0f; var aimPunch = 0f
    var hp = 100f; var armor = 100f
    var alive = false; var deadT = 0f; var respawnT = 0f; var protectT = 0f
    var inv = arrayOf<WeaponState>()
    var slot = 0; var lastSlot = 1; var readyAt = 0f
    val stats = ActorStats()
    var streak = 0; var lastKillT = -99f; var multi = 0
    var radarT = 0f
    var ping = 20 + (Math.random() * 40).toInt()
    var primary = "ak47"
    var nextPrimary: String? = null
    val soldier = com.cf.transportship.gfx.Soldier(team)
    var stepDist = 0f
    var scoped = 0; var scopeReady = false; var scopeT = 0f; var reScope = 0
    var lastHurt = -99f; var lastAttacker: Actor? = null
    var walk = false
    var pendingThrow = 0f
    var autoSwitchAt = 0f
    var jumpCD = 0f
    var speed = 0f
    var landed = false; var landSpeed = 0f
    var recoilControl: Float = 1f

    val weapon: WeaponState get() = inv[slot]

    fun eye(out: Vec3): Vec3 = out.set(pos.x, pos.y + eyeH, pos.z)

    fun forward(out: Vec3): Vec3 {
        val p = pitch + punchP
        val y = yaw + punchY
        out.set(-sin(y) * kotlin.math.cos(p), kotlin.math.sin(p), -kotlin.math.cos(y) * kotlin.math.cos(p))
        return out
    }

    open fun giveLoadout(primaryIn: String?) {
        if (primaryIn != null) primary = primaryIn
        inv = arrayOf(
            WeaponState(primary), WeaponState("deagle"), WeaponState("knife"), WeaponState("he"),
        )
        for (w in inv) w.patternSeed = (Math.random() * 6).toFloat()
        slot = 0; lastSlot = 1
        readyAt = game.time + 0.3f
        soldier.setWeapon(primary)
        applyGunAnchors()
    }

    fun applyGunAnchors() {
        // 锚点由 Game 在构建枪模型后填充；此处使用默认 AK
    }

    open fun spawn(sp: SpawnPoint) {
        pos.set(sp.x, 0.02f, sp.z)
        vel.set(0f, 0f, 0f)
        yaw = sp.yaw; pitch = 0f; punchP = 0f; punchY = 0f
        hp = 100f; armor = 100f; alive = true; deadT = 0f
        crouch = false; height = STAND_H; eyeH = EYE_STAND
        protectT = 3f; onGround = true
        giveLoadout(nextPrimary ?: primary)
        soldier.reset()
        soldier.setRoot(pos.x, pos.y, pos.z, yaw)
        soldier.visible = !isPlayer
        scoped = 0; scopeReady = false
        streak = 0
    }

    // wish: 世界坐标系下的期望移动方向（长度 0..1）
    fun move(dt: Float, wishX: Float, wishZ: Float, jump: Boolean, crouchIn: Boolean, walkIn: Boolean) {
        val g = game
        val w = if (inv.isNotEmpty()) weapon else null
        val spMul = w?.def?.speed ?: 1f
        // 下蹲 / 起身
        if (crouchIn && !crouch) {
            crouch = true; height = CROUCH_H
            if (!onGround) pos.y += STAND_H - CROUCH_H - 0.1f
        } else if (!crouchIn && crouch) {
            if (onGround) {
                if (!g.world.blocked(pos.x, pos.y + 0.05f, pos.z, radius * 0.95f, STAND_H - 0.05f)) { crouch = false; height = STAND_H }
            } else {
                val drop = STAND_H - CROUCH_H - 0.1f
                if (!g.world.blocked(pos.x, pos.y - drop, pos.z, radius * 0.95f, STAND_H)) {
                    crouch = false; height = STAND_H; pos.y -= drop
                }
            }
        }
        eyeH += ((if (crouch) EYE_CROUCH else EYE_STAND) - eyeH) * min(1f, dt * 14)
        val max = RUN * spMul * (if (crouch) 0.42f else if (walk) 0.5f else 1f) * (if (scoped > 0) 0.55f else 1f)
        val wl = hypot(wishX, wishZ)
        val wx = if (wl > 0) wishX / wl else 0f
        val wz = if (wl > 0) wishZ / wl else 0f
        val wishSpeed = max * min(1f, wl)
        if (onGround) {
            val sp = hypot(vel.x, vel.z)
            if (sp > 0) {
                val drop = kotlin.math.max(sp, 1.5f) * 9 * dt
                val ns = kotlin.math.max(0f, sp - drop) / sp
                vel.x *= ns; vel.z *= ns
            }
            val cur = vel.x * wx + vel.z * wz
            val add = wishSpeed - cur
            if (add > 0) {
                val acc = min(11f * dt * wishSpeed, add)
                vel.x += acc * wx; vel.z += acc * wz
            }
            if (jump && jumpCD <= 0) {
                vel.y = JUMP_V; onGround = false; jumpCD = 0.35f
                g.onJump(this)
            }
        } else {
            val ws = kotlin.math.min(wishSpeed, 1.2f)
            val cur = vel.x * wx + vel.z * wz
            val add = ws - cur
            if (add > 0) {
                val acc = min(12f * dt * ws, add)
                vel.x += acc * wx; vel.z += acc * wz
            }
        }
        jumpCD -= dt
        vel.y -= GRAV * dt
        // 世界移动
        val ent = MoveEnt()
        ent.x = pos.x; ent.y = pos.y; ent.z = pos.z
        ent.velX = vel.x; ent.velY = vel.y; ent.velZ = vel.z
        ent.radius = radius; ent.height = height
        ent.onGround = onGround; ent.stepHeight = stepHeight
        g.world.move(ent, dt)
        pos.set(ent.x, ent.y, ent.z)
        vel.set(ent.velX, ent.velY, ent.velZ)
        val wasGround = onGround
        landed = ent.landed; landSpeed = ent.landSpeed
        onGround = ent.onGround
        groundSurface = ent.groundSurface
        // 角色之间的实体碰撞（只推开自己）
        for (o in g.actors) {
            if (o === this || !o.alive) continue
            val dx = pos.x - o.pos.x
            val dz = pos.z - o.pos.z
            val d2 = dx * dx + dz * dz
            val R = radius + o.radius - 0.05f
            if (d2 < R * R && d2 > 1e-6f && kotlin.math.abs(pos.y - o.pos.y) < 1.6f) {
                val d = kotlin.math.sqrt(d2)
                val push = (R - d) * 0.5f
                val nx = dx / d; val nz = dz / d
                if (!g.world.blocked(pos.x + nx * push, pos.y + 0.05f, pos.z + nz * push, radius * 0.9f, height - 0.1f)) {
                    pos.x += nx * push; pos.z += nz * push
                }
            }
        }
        if (landed && landSpeed > 3) g.onLand(this, landSpeed)
        // 脚步
        val hs = hypot(vel.x, vel.z)
        speed = hs
        if (onGround && hs > 2.6f && !walk && !crouch) {
            stepDist += hs * dt
            if (stepDist > 2.3f) { stepDist = 0f; g.onFootstep(this) }
        }
        // 防卡死：掉出世界
        if (pos.y < -3f) { pos.y = 0.1f; vel.set(0f, 0f, 0f) }
    }

    /** 武器逻辑 */
    fun weaponUpdate(dt: Float, inp: WeaponInput) {
        val g = game
        val now = g.time
        // 切枪
        val sw = inp.sw
        if (sw != null && sw != slot && sw < inv.size) {
            val tgt = inv[sw]
            if (!(tgt.def.type == "grenade" && tgt.mag <= 0)) {
                weapon.reloadUntil = 0f
                lastSlot = slot; slot = sw
                readyAt = now + tgt.def.draw
                scoped = 0; scopeReady = false
                pendingThrow = 0f
                soldier.setWeapon(tgt.id)
                applyGunAnchors()
                g.onSwitch(this)
            }
        }
        val w = weapon
        val d = w.def
        // 散布恢复
        val spr = d.spread
        if (spr != null) {
            if (now - w.lastShot > 60f / d.rpm * 1.2f) w.spreadAcc *= exp(-spr.recover * dt)
            if (now - w.lastShot > 0.28f) w.shotsFired = 0
        }
        // 后坐恢复
        val rc = d.recoil
        if (rc != null && now - w.lastShot > 60f / d.rpm + 0.05f) {
            val k = exp(-rc.recover * dt)
            punchP *= k; punchY *= k
        }
        aimPunch *= exp(-dt * 10f)
        // 换弹完成
        if (w.reloading && now >= w.reloadUntil) { w.finishReload(); g.onReloadDone(this) }
        // 狙击镜
        if (d.type == "sniper") {
            if (inp.altPressed && now >= readyAt && !w.reloading && now >= w.boltUntil) {
                scoped = (scoped + 1) % 3; scopeT = now; scopeReady = false
                g.onScope(this)
            }
            if (scoped > 0 && now - scopeT > 0.18f) scopeReady = true
            if (reScope > 0 && now >= w.boltUntil && !w.reloading) {
                scoped = reScope; reScope = 0; scopeT = now; scopeReady = false
                g.onScope(this)
            }
        }
        if (now < readyAt) return
        // 换弹
        if (inp.reload && w.canReload()) startReload()
        if (w.reloading) return
        if (d.type == "melee") {
            if (now >= w.nextFire) {
                if (inp.fire) { w.nextFire = now + d.rateLight; g.melee(this, false) }
                else if (inp.alt) { w.nextFire = now + d.rateHeavy; g.melee(this, true) }
            }
            return
        }
        if (d.type == "grenade") {
            if (pendingThrow > 0) {
                pendingThrow -= dt
                if (pendingThrow <= 0) {
                    g.throwGrenade(this)
                    w.mag = 0
                    readyAt = now + 0.4f
                    autoSwitchAt = now + 0.45f
                }
                return
            }
            if (autoSwitchAt > 0 && now >= autoSwitchAt) {
                autoSwitchAt = 0f
                val back = if (lastSlot != 3) lastSlot else 0
                val wi = WeaponInput(); wi.sw = back
                weaponUpdate(0f, wi)
                return
            }
            if (inp.firePressed && w.mag > 0) { pendingThrow = 0.52f; g.onGrenadeStart(this) }
            return
        }
        // 枪械
        val trigger = if (d.auto) inp.fire else inp.firePressed
        if (!trigger || now < w.nextFire || now < w.boltUntil) return
        if (w.mag <= 0) {
            if (inp.firePressed) g.onDryFire(this)
            if (w.canReload()) startReload()
            return
        }
        w.mag--
        w.lastShot = now
        w.nextFire = now + 60f / d.rpm
        stats.shots++
        val spread = currentSpread(w, SpreadState(speed, onGround, crouch, scoped > 0, scopeReady))
        g.fireWeapon(this, w, spread)
        // 后坐
        val kick = recoilKick(w) { Math.random().toFloat() }
        val ctl = recoilControl
        punchP = kotlin.math.min(d.recoil!!.upMax, punchP + kick[0] * ctl)
        punchY += kick[1] * ctl
        w.shotsFired++
        if (d.spread != null) w.spreadAcc = kotlin.math.min(d.spread.max, w.spreadAcc + d.spread.perShot)
        protectT = 0f
        if (d.type == "sniper") {
            w.boltUntil = now + d.bolt
            if (scoped > 0) { reScope = scoped; scoped = 0; scopeReady = false }
        }
        if (w.mag == 0 && w.canReload() && !isPlayer) startReload()
    }

    fun startReload() {
        val w = weapon
        if (!w.canReload()) return
        w.reloadUntil = game.time + w.def.reload
        scoped = 0; scopeReady = false; reScope = 0
        game.onReloadStart(this, w.mag == 0)
    }

    abstract fun update(dt: Float)

    open fun onDamaged(att: Actor?) {}
}

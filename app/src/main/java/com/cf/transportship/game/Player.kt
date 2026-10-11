// 玩家：输入、镜头（移植 player.js）
package com.cf.transportship.game

import com.cf.transportship.engine.Camera
import com.cf.transportship.engine.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

class TouchState {
    var mx = 0f; var mz = 0f
    var fire = false; var firePressed = false
    var jump = false; var crouch = false
}

class DeathCam {
    var t = 0f
    val from = Vec3(); val to = Vec3(); val look = Vec3()
    var killer: Actor? = null
}

class Player(game: Game, id: Int, name: String, team: String) : Actor(game, id, name, team, true) {
    val keys = HashSet<String>()
    val pressed = HashSet<String>()
    var mouseL = false; var mouseR = false
    var mouseLP = false; var mouseRP = false
    var mouseDX = 0f; var mouseDY = 0f
    var wheel = 0
    var lookDX = 0f; var lookDY = 0f
    var bobT = 0f; var land = 0f
    var camRoll = 0f
    var deathCam: DeathCam? = null
    val touch = TouchState()
    var touchLookX = 0f; var touchLookY = 0f

    fun consumePressed(code: String): Boolean {
        val p = pressed.contains(code)
        pressed.remove(code)
        return p
    }

    override fun update(dt: Float) {
        val g = game
        val sens = (g.opts["sens"] as? Float ?: 1f) * 0.0022f
        var dx = mouseDX; var dy = mouseDY
        mouseDX = 0f; mouseDY = 0f
        dx += touchLookX; dy += touchLookY
        touchLookX = 0f; touchLookY = 0f
        lookDX = dx; lookDY = dy
        if (alive) {
            val fovK = if (scoped > 0) g.renderer.cam.fov / (g.opts["fov"] as? Int ?: 78).toFloat() else 1f
            yaw -= dx * sens * fovK
            pitch = (pitch - dy * sens * fovK).coerceIn(-1.5f, 1.5f)
            // 移动
            var f = 0f; var s = 0f
            if (keys.contains("KeyW") || keys.contains("ArrowUp")) f += 1f
            if (keys.contains("KeyS") || keys.contains("ArrowDown")) f -= 1f
            if (keys.contains("KeyD") || keys.contains("ArrowRight")) s += 1f
            if (keys.contains("KeyA") || keys.contains("ArrowLeft")) s -= 1f
            f += touch.mz; s += touch.mx
            val sy = sin(yaw); val cy = cos(yaw)
            val wx = -sy * f + cy * s
            val wz = -cy * f - sy * s
            val jump = keys.contains("Space") || touch.jump
            val crouchK = keys.contains("KeyC") || touch.crouch
            walk = keys.contains("ShiftLeft") || keys.contains("ShiftRight")
            move(dt, wx, wz, jump, crouchK, walk)
            if (touch.jump) touch.jump = false
            // 武器
            var sw: Int? = null
            for (i in 1..4) if (consumePressed("Digit$i")) sw = i - 1
            if (consumePressed("KeyQ")) sw = lastSlot
            if (wheel != 0) {
                val dir = if (wheel > 0) 1 else -1
                wheel = 0
                var n = slot
                for (k in 0 until 4) {
                    n = (n + dir + 4) % 4
                    val w = inv[n]
                    if (w != null && !(w.def.type == "grenade" && w.mag <= 0)) break
                }
                sw = n
            }
            if (sw != null && inv[sw].def.type == "grenade" && inv[sw].mag <= 0) {
                g.hud?.toast("没有手雷了", 1.2f)
                sw = null
            }
            val lp = mouseLP || touch.firePressed
            val rp = mouseRP
            mouseLP = false; mouseRP = false; touch.firePressed = false
            val wi = WeaponInput()
            wi.fire = mouseL || touch.fire
            wi.firePressed = lp
            wi.alt = mouseR
            wi.altPressed = rp
            wi.reload = consumePressed("KeyR")
            wi.sw = sw
            weaponUpdate(dt, wi)
            if (consumePressed("KeyF")) g.vm.inspect()
        } else {
            pressed.remove("KeyR")
            mouseLP = false; mouseRP = false
        }
        if (consumePressed("KeyB")) g.toggleLoadout()
        pressed.clear()
        updateCamera(dt)
    }

    fun updateCamera(dt: Float) {
        val g = game
        val cam = g.renderer.cam
        if (!alive) {
            val dc = deathCam
            if (dc != null) {
                dc.t += dt
                val k = min(1f, dc.t * 1.2f)
                val e = 1 - (1 - k) * (1 - k)
                cam.px = dc.from.x + (dc.to.x - dc.from.x) * e
                cam.py = dc.from.y + (dc.to.y - dc.from.y) * e
                cam.pz = dc.from.z + (dc.to.z - dc.from.z) * e
                val killer = dc.killer
                if (killer != null && killer.alive) {
                    killer.soldier.headWorld(tmpV)
                    dc.look.lerp(tmpV, min(1f, dt * 3))
                }
                // lookAt
                lookAtCam(cam, dc.look)
            }
            cam.fov += ((g.opts["fov"] as? Int ?: 78).toFloat() - cam.fov) * min(1f, dt * 8)
            return
        }
        // 晃动
        val sp = if (onGround) speed else 0f
        bobT += dt * (if (sp > 0.5f) 6 + sp * 0.9f else 0f)
        val bobK = min(1f, sp / 6f) * (if (scoped > 0) 0.2f else 1f)
        val by = abs(sin(bobT)) * 0.028f * bobK
        val bx = cos(bobT) * 0.012f * bobK
        if (landed && landSpeed > 3) land = min(0.14f, landSpeed * 0.012f)
        land *= exp(-dt * 7f)
        val shake = g.fx.shake * 0.06f
        val sy2 = sin(yaw); val cy2 = cos(yaw)
        eye(camPos)
        cam.px = camPos.x + cy2 * bx + (Math.random().toFloat() - 0.5f) * shake
        cam.py = camPos.y + by - land + (Math.random().toFloat() - 0.5f) * shake
        cam.pz = camPos.z - sy2 * bx
        camRoll += ((if (keys.contains("KeyA")) 0.008f else 0f) - (if (keys.contains("KeyD")) 0.008f else 0f) - camRoll) * min(1f, dt * 6)
        cam.pitch = pitch + punchP * 0.75f + aimPunch
        cam.yaw = yaw + punchY * 0.75f
        cam.roll = camRoll
        // FOV：狙击镜
        val w = weapon
        var fov = (g.opts["fov"] as? Int ?: 78).toFloat()
        if (w.def.type == "sniper" && scoped > 0) fov = w.def.zoom[scoped - 1]
        val fk = if (scoped > 0) 1f else min(1f, dt * 10f)
        cam.fov += (fov - cam.fov) * fk
    }

    private fun lookAtCam(cam: Camera, target: Vec3) {
        val dx = cam.px - target.x
        val dy = cam.py - target.y
        val dz = cam.pz - target.z
        val hd = kotlin.math.sqrt(dx * dx + dz * dz)
        cam.yaw = kotlin.math.atan2(-dx, -dz)
        cam.pitch = kotlin.math.atan2(dy, hd)
        cam.roll = 0f
    }

    fun deathCamClear() { deathCam = null }

    fun startDeathCam(killer: Actor?) {
        val cam = game.renderer.cam
        dcFrom.set(cam.px, cam.py, cam.pz)
        head.set(pos.x, pos.y + 1.2f, pos.z)
        want.set(sin(yaw) * 2.2f, 1.4f, cos(yaw) * 2.2f)
        val L = want.len()
        want.mul(1f / L)
        val hit = game.world.raycast(head.x, head.y, head.z, want.x, want.y, want.z, L, "move")
        val t = if (hit != null) kotlin.math.max(0.2f, hit.t - 0.35f) else L
        dcTo.set(head.x + want.x * t, head.y + want.y * t, head.z + want.z * t)
        look.set(pos.x, pos.y + 0.4f, pos.z)
        val dc = DeathCam()
        dc.from.set(dcFrom); dc.to.set(dcTo); dc.look.set(look)
        dc.killer = if (killer != null && killer !== this) killer else null
        deathCam = dc
        soldier.visible = true
    }

    private val dcFrom = Vec3(); private val dcTo = Vec3()
    private val head = Vec3(); private val want = Vec3(); private val look = Vec3()
    private val camPos = Vec3(); private val tmpV = Vec3()
}

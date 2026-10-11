// 武器数据（参考穿越火线手感：步枪首发精准，连射上跳后左右摆动；狙击枪开镜才准）
package com.cf.transportship.game

import kotlin.math.sqrt

class SpreadDef(
    val base: Float, val scoped: Float, val move: Float, val air: Float, val crouch: Float,
    val perShot: Float, val max: Float, val recover: Float,
)

class RecoilDef(
    val up: Float, val upMax: Float, val side: Float, val sideStart: Int, val recover: Float,
)

class WeaponDef(
    val id: String,
    val name: String,
    val slot: Int,
    val type: String,        // rifle | smg | sniper | pistol | melee | grenade
    val auto: Boolean,
    val dmg: Float,
    val headMul: Float,
    val limbMul: Float,
    val rpm: Int,
    val mag: Int,
    val reserve: Int,
    val reload: Float,
    val draw: Float,
    val speed: Float,        // 移动速度倍率
    val range: Float,
    val falloff: Float,
    val pen: Float,          // 穿透力
    val armorPen: Float,
    val spread: SpreadDef?,
    val recoil: RecoilDef?,
    val bolt: Float,         // 狙击栓动时间
    val zoom: FloatArray,    // 狙击镜 FOV
    val sound: String,
    val hudName: String,
    // 近战
    val dmgLight: Float = 0f, val dmgHeavy: Float = 0f,
    val rangeLight: Float = 0f, val rangeHeavy: Float = 0f,
    val rateLight: Float = 0f, val rateHeavy: Float = 0f,
    // 手雷
    val radius: Float = 0f, val fuse: Float = 0f,
)

private fun sp(base: Float, move: Float, air: Float, crouch: Float, perShot: Float, max: Float, recover: Float, scoped: Float = 0f) =
    SpreadDef(base, scoped, move, air, crouch, perShot, max, recover)

private fun rc(up: Float, upMax: Float, side: Float, sideStart: Int, recover: Float) =
    RecoilDef(up, upMax, side, sideStart, recover)

val WEAPONS: Map<String, WeaponDef> = mapOf(
    "ak47" to WeaponDef(
        "ak47", "AK-47", 0, "rifle", true,
        dmg = 36f, headMul = 4.0f, limbMul = 0.78f, rpm = 600, mag = 30, reserve = 90, reload = 2.45f, draw = 0.85f,
        speed = 0.93f, range = 220f, falloff = 0.985f, pen = 1.2f, armorPen = 0.78f,
        spread = sp(0.0028f, 0.045f, 0.16f, 0.6f, 0.0055f, 0.05f, 7f),
        recoil = rc(0.0105f, 0.11f, 0.0062f, 5, 6.5f),
        bolt = 0f, zoom = floatArrayOf(), sound = "ak47", hudName = "AK-47",
    ),
    "m4a1" to WeaponDef(
        "m4a1", "M4A1", 0, "rifle", true,
        dmg = 32f, headMul = 4.0f, limbMul = 0.8f, rpm = 700, mag = 30, reserve = 90, reload = 2.3f, draw = 0.8f,
        speed = 0.95f, range = 220f, falloff = 0.985f, pen = 1.0f, armorPen = 0.72f,
        spread = sp(0.0024f, 0.04f, 0.15f, 0.6f, 0.0044f, 0.042f, 8f),
        recoil = rc(0.0082f, 0.085f, 0.0048f, 6, 7f),
        bolt = 0f, zoom = floatArrayOf(), sound = "m4a1", hudName = "M4A1",
    ),
    "awm" to WeaponDef(
        "awm", "AWM", 0, "sniper", false,
        dmg = 118f, headMul = 2.2f, limbMul = 0.82f, rpm = 41, mag = 5, reserve = 20, reload = 3.5f, draw = 1.1f,
        speed = 0.82f, range = 400f, falloff = 0.998f, pen = 2.6f, armorPen = 0.95f,
        spread = sp(0.06f, 0.09f, 0.22f, 0.85f, 0f, 0.2f, 3f, scoped = 0.0004f),
        recoil = rc(0.035f, 0.035f, 0.004f, 0, 4f),
        bolt = 1.35f, zoom = floatArrayOf(30f, 11f), sound = "awm", hudName = "AWM",
    ),
    "mp5" to WeaponDef(
        "mp5", "MP5", 0, "smg", true,
        dmg = 25f, headMul = 3.6f, limbMul = 0.85f, rpm = 800, mag = 30, reserve = 120, reload = 2.1f, draw = 0.6f,
        speed = 1.0f, range = 120f, falloff = 0.97f, pen = 0.6f, armorPen = 0.6f,
        spread = sp(0.004f, 0.022f, 0.12f, 0.7f, 0.0035f, 0.04f, 9f),
        recoil = rc(0.0058f, 0.06f, 0.004f, 5, 8f),
        bolt = 0f, zoom = floatArrayOf(), sound = "mp5", hudName = "MP5",
    ),
    "deagle" to WeaponDef(
        "deagle", "沙漠之鹰", 1, "pistol", false,
        dmg = 54f, headMul = 3.8f, limbMul = 0.75f, rpm = 260, mag = 7, reserve = 35, reload = 2.0f, draw = 0.55f,
        speed = 1.0f, range = 150f, falloff = 0.98f, pen = 1.0f, armorPen = 0.8f,
        spread = sp(0.004f, 0.05f, 0.18f, 0.7f, 0.028f, 0.07f, 5f),
        recoil = rc(0.03f, 0.09f, 0.008f, 1, 5f),
        bolt = 0f, zoom = floatArrayOf(), sound = "deagle", hudName = "DESERT EAGLE",
    ),
    "knife" to WeaponDef(
        "knife", "军刀", 2, "melee", true,
        dmg = 0f, headMul = 1f, limbMul = 1f, rpm = 0, mag = 0, reserve = 0, reload = 0f, draw = 0.4f,
        speed = 1.08f, range = 0f, falloff = 1f, pen = 0f, armorPen = 0f,
        spread = null, recoil = null,
        bolt = 0f, zoom = floatArrayOf(), sound = "knife", hudName = "KNIFE",
        dmgLight = 52f, dmgHeavy = 100f, rangeLight = 1.9f, rangeHeavy = 1.6f, rateLight = 0.42f, rateHeavy = 1.05f,
    ),
    "he" to WeaponDef(
        "he", "手雷", 3, "grenade", false,
        dmg = 115f, headMul = 1f, limbMul = 1f, rpm = 0, mag = 1, reserve = 0, reload = 0f, draw = 0.5f,
        speed = 1.0f, range = 0f, falloff = 1f, pen = 0f, armorPen = 0f,
        spread = null, recoil = null,
        bolt = 0f, zoom = floatArrayOf(), sound = "grenade", hudName = "HE GRENADE",
        radius = 7.5f, fuse = 2.6f,
    ),
)

val PRIMARIES = listOf("ak47", "m4a1", "awm", "mp5")

class WeaponState(id: String) {
    val def = WEAPONS[id]!!
    val id = id
    var mag = def.mag
    var reserve = def.reserve
    var nextFire = 0f
    var reloadUntil = 0f
    var shotsFired = 0      // 本次连射计数
    var spreadAcc = 0f      // 连射累积扩散
    var lastShot = -10f
    var boltUntil = 0f
    var patternSeed = Math.random().toFloat() * 6f

    val reloading: Boolean get() = reloadUntil > 0f

    fun canReload(): Boolean {
        val d = def
        return d.type != "melee" && d.type != "grenade" && mag < d.mag && reserve > 0 && !reloading
    }

    fun finishReload() {
        val need = def.mag - mag
        val take = minOf(need, reserve)
        mag += take; reserve -= take; reloadUntil = 0f
    }

    fun refill() {
        mag = def.mag; reserve = def.reserve; reloadUntil = 0f; boltUntil = 0f
    }
}

class SpreadState(
    val speed: Float, val onGround: Boolean, val crouch: Boolean, val scoped: Boolean, val scopeReady: Boolean,
)

/** 当前散布（弧度，锥半角） */
fun currentSpread(ws: WeaponState, st: SpreadState): Float {
    val s = ws.def.spread ?: return 0f
    var v = s.base
    if (ws.def.type == "sniper") v = if (st.scoped && st.scopeReady) s.scoped else s.base
    v += s.move * ((st.speed - 0.6f) / 5.5f).coerceIn(0f, 1f)
    if (!st.onGround) v += s.air
    v += ws.spreadAcc
    if (st.crouch && st.onGround) v *= s.crouch
    return minOf(v, s.max + s.base + (if (st.onGround) 0f else s.air))
}

/** 后坐力：返回本发的 [pitch, yaw] 增量（弧度） */
fun recoilKick(ws: WeaponState, rnd: () -> Float): FloatArray {
    val r = ws.def.recoil!!
    val n = ws.shotsFired
    val up = r.up * (if (n < 3) 1.25f else 1f) * (0.85f + rnd() * 0.3f)
    val side: Float
    if (n >= r.sideStart) {
        val phase = sin2(n * 0.55f + ws.patternSeed)
        side = r.side * (phase * 1.3f + (rnd() - 0.5f) * 0.9f)
    } else side = (rnd() - 0.5f) * r.side * 0.4f
    return floatArrayOf(up, side)
}

private fun sin2(x: Float) = kotlin.math.sin(x)

/** 在锥内随机扰动方向（dir 已归一化，就地修改） */
fun jitterDir(dir: FloatArray, spread: Float, rnd: () -> Float): FloatArray {
    if (spread <= 0) return dir
    val ax: FloatArray
    if (kotlin.math.abs(dir[0]) < 0.9f) ax = floatArrayOf(1f, 0f, 0f) else ax = floatArrayOf(0f, 1f, 0f)
    var ux = dir[1] * ax[2] - dir[2] * ax[1]
    var uy = dir[2] * ax[0] - dir[0] * ax[2]
    var uz = dir[0] * ax[1] - dir[1] * ax[0]
    val ul = sqrt(ux * ux + uy * uy + uz * uz)
    ux /= ul; uy /= ul; uz /= ul
    val vx = dir[1] * uz - dir[2] * uy
    val vy = dir[2] * ux - dir[0] * uz
    val vz = dir[0] * uy - dir[1] * ux
    val a = rnd() * Math.PI.toFloat() * 2
    val r = sqrt(rnd()) * spread
    val ca = kotlin.math.cos(a) * r
    val sa = kotlin.math.sin(a) * r
    dir[0] += ux * ca + vx * sa
    dir[1] += uy * ca + vy * sa
    dir[2] += uz * ca + vz * sa
    val l = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
    dir[0] /= l; dir[1] /= l; dir[2] /= l
    return dir
}

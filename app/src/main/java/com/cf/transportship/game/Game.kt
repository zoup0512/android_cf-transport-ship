// 对局主控（移植 game.js）
package com.cf.transportship.game

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLES30.*
import com.cf.transportship.audio.AudioEngine
import com.cf.transportship.engine.Camera
import com.cf.transportship.engine.Mat4
import com.cf.transportship.engine.Quat
import com.cf.transportship.engine.Renderer
import com.cf.transportship.engine.StaticMesh
import com.cf.transportship.engine.Vec3
import com.cf.transportship.engine.Layouts
import com.cf.transportship.gfx.Effects
import com.cf.transportship.gfx.GunMaterials
import com.cf.transportship.gfx.GunModel
import com.cf.transportship.gfx.GunModels
import com.cf.transportship.gfx.GunPart
import com.cf.transportship.gfx.HIP
import com.cf.transportship.gfx.KICK
import com.cf.transportship.gfx.Soldier
import com.cf.transportship.gfx.Textures
import com.cf.transportship.gfx.ViewModel
import com.cf.transportship.gfx.buildSoldierGeometry
import com.cf.transportship.gfx.soldierAtlas
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import com.cf.transportship.ui.ST_END
import com.cf.transportship.ui.ST_LOADOUT
import com.cf.transportship.ui.ST_MENU
import com.cf.transportship.ui.ST_PAUSE
import com.cf.transportship.ui.ST_PLAY

val MULTI = arrayOf("", "", "DOUBLE KILL", "TRIPLE KILL", "MULTI KILL", "ULTRA KILL", "RAMPAGE", "UNSTOPPABLE", "GODLIKE")
val MULTI_CN = arrayOf("", "", "双杀", "三杀", "四杀", "五杀", "六杀！", "无人能挡", "超神")

class Nade {
    val pos = Vec3()
    val vel = Vec3()
    var fuse = 2.6f
    var owner: Actor? = null
    val spin = Vec3()
}

class GameTimer(var t: Float, val fn: () -> Unit)

class NameTag(var x: Float, var y: Float, var name: String, var color: Int, var visible: Boolean)

class Options {
    val map = LinkedHashMap<String, Any>(
        mapOf(
            "team" to "BL", "primary" to "ak47", "size" to 6, "goal" to 50,
            "diff" to "normal", "tod" to "day", "quality" to "medium",
            "sens" to 1.0f, "fov" to 78, "vol" to 0.8f,
        ),
    )
    operator fun get(k: String): Any? = map[k]
    operator fun set(k: String, v: Any) { map[k] = v }

    fun load(sp: android.content.SharedPreferences) {
        for (k in listOf("team", "primary", "diff", "tod", "quality")) {
            sp.getString(k, null)?.let { map[k] = it }
        }
        for (k in listOf("size", "goal", "fov")) {
            val v = sp.getInt(k, -1)
            if (v >= 0) map[k] = v
        }
        for (k in listOf("sens", "vol")) {
            val v = sp.getFloat(k, -1f)
            if (v >= 0) map[k] = v
        }
    }

    fun save(ed: android.content.SharedPreferences.Editor) {
        for (k in listOf("team", "primary", "diff", "tod", "quality")) ed.putString(k, map[k]?.toString())
        for (k in listOf("size", "goal", "fov")) ed.putInt(k, (map[k] as? Int) ?: 0)
        for (k in listOf("sens", "vol")) ed.putFloat(k, (map[k] as? Float) ?: 0f)
    }
}

class Game(val context: Context) {
    val renderer = Renderer()
    val world = World()
    val audio = AudioEngine(null)
    val fx = Effects()
    val opts = Options()
    var hudSetHud: ((Float, String) -> Unit)? = null

    lateinit var map: GameMap
    lateinit var nav: NavGrid
    lateinit var vm: ViewModel
    lateinit var T: Textures
    lateinit var gunMats: GunMaterials

    var w = 1f; var h = 1f
    var time = 0f
    var frame = 0
    var realTime = 0f
    var playing = false; var paused = false; var ended = false; var inLoadout = false
    val actors = ArrayList<Actor>()
    val nades = ArrayList<Nade>()
    val timers = ArrayList<GameTimer>()
    val score = LinkedHashMap<String, Int>(mapOf("BL" to 0, "GR" to 0))
    var player: Player? = null
    var goal = 50
    var timeLeft = 600f
    var killedBy = ""
    var dmgFlash = 0f
    var lightK = 1f
    var indoorK = 0f
    var aimTarget: Actor? = null
    var fps = 0
    private var fpsAcc = 0f; private var fpsN = 0
    val nameTags = ArrayList<NameTag>(16)

    // GL 资源
    private val mapMeshes = ArrayList<Triple<MatDef, StaticMesh, Int>>()
    private var hangingMeshes: List<Pair<MatDef, StaticMesh>> = listOf()
    private var radarMeshes: List<Pair<MatDef, StaticMesh>> = listOf()
    private val soldierMeshes = HashMap<String, StaticMesh>()
    private var soldierTex = 0
    private val gunRD = HashMap<String, List<Triple<com.cf.transportship.gfx.GunMat, StaticMesh, GunPart?>>>()
    private var armMeshes: HashMap<String, StaticMesh>? = null
    private var shellMesh: StaticMesh? = null
    private var radarBmpBuilt = false

    private val envPreset = LinkedHashMap<String, FloatArray>()
    var tod = "day"
    var exposure = 0.6f
    val sunDir = Vec3(0.5f, 0.8f, 0.3f)
    var shipDist = 0f
    var shipSpeed = 6.5f
    var vmCam = Camera()

    var autoStart = false

    fun sp() = context.getSharedPreferences("cf_ship_opts", Context.MODE_PRIVATE)

    fun loadOpts() {
        opts.load(sp())
    }

    fun saveOpts() {
        val ed = sp().edit()
        opts.save(ed)
        ed.apply()
    }

    fun onOption(k: String, v: Any) {
        when (k) {
            "vol" -> audio.volMaster = (v as? Float) ?: (v as? Int)?.toFloat() ?: 0.8f
            "fov" -> renderer.cam.fov = (v as? Int)?.toFloat() ?: (v as? Float) ?: 78f
            "tod" -> applyEnv(v.toString())
            "quality" -> saveOpts() // 画质切换需重建：由 MainActivity 处理（简化：下次启动生效）
            "team" -> vm.setTeam(v.toString())
        }
    }

    // ============ 构建阶段（GL 线程调用） ============
    fun buildAssets(progress: (Float, String) -> Unit) {
        progress(0.08f, "初始化渲染器")
        renderer.init(opts["quality"].toString(), w.toInt(), h.toInt())
        renderer.makeBlackTex()
        progress(0.15f, "生成集装箱 / 甲板 / 船体纹理")
        T = Textures.build(opts["quality"].toString())
        gunMats = GunMaterials()
        progress(0.55f, "搭建运输船")
        map = buildMap(T, world)
        createMapMeshes()
        progress(0.68f, "天空与海洋")
        renderer.initEnvMeshes()
        renderer.setWaves(
            listOf(
                floatArrayOf(1.0f, 0.25f, 0.10f, 58f), floatArrayOf(0.9f, 0.55f, 0.09f, 33f),
                floatArrayOf(0.75f, -0.45f, 0.08f, 19f), floatArrayOf(0.25f, 1.0f, 0.07f, 11f),
                floatArrayOf(-0.5f, 0.8f, 0.05f, 6.5f), floatArrayOf(0.95f, -0.1f, 0.05f, 4.2f),
            ),
        )
        applyEnv(opts["tod"].toString())
        progress(0.78f, "士兵与武器模型")
        createSoldierMeshes()
        createArmMeshes()
        for (id in listOf("ak47", "m4a1", "awm", "mp5", "deagle", "knife", "he")) {
            val gm = GunModels.build(id, gunMats)
            gunRD[id] = buildGunMeshes(gm)
        }
        val shellGeo = com.cf.transportship.gfx.Prims.cylinder(0.0055f, 0.0055f, 0.035f, 8)
        shellGeo.applyMatrix(matFromQuat(com.cf.transportship.engine.Quat().setFromAxisAngle(0f, 0f, 1f, PI.toFloat() / 2)))
        shellMesh = renderer.staticMeshOf(shellGeo, Layouts.MAP)
        vm = ViewModel(gunMats, opts["team"].toString())
        progress(0.86f, "计算寻路网格")
        nav = NavGrid(world, -36.2f, -12.1f, 36.2f, 12.1f, 0.5f, 0.42f)
        progress(0.92f, "预编译着色器")
        applyEnv(opts["tod"].toString())
        renderer.cam = Camera()
        renderer.cam.px = -20f; renderer.cam.py = 12f; renderer.cam.pz = 30f
        renderer.cam.yaw = 0.588f; renderer.cam.pitch = -0.23f
        renderer.cam.fov = (opts["fov"] as Int).toFloat()
        renderer.cam.update()
        vmCam.fov = 58f; vmCam.near = 0.01f; vmCam.far = 50f
        progress(1f, "完成")
    }

    private fun matFromQuat(q: Quat) = Mat4().compose(0f, 0f, 0f, q, 1f, 1f, 1f)

    private fun createMapMeshes() {
        for ((def, batch, _) in map.batches) {
            renderer.uploadMatTextures(def)
            val n = batch.count
            val stride = 10
            val verts = FloatArray(n * stride)
            for (i in 0 until n) {
                val o = i * stride
                verts[o] = batch.p.a[i * 3]; verts[o + 1] = batch.p.a[i * 3 + 1]; verts[o + 2] = batch.p.a[i * 3 + 2]
                verts[o + 3] = batch.n.a[i * 3]; verts[o + 4] = batch.n.a[i * 3 + 1]; verts[o + 5] = batch.n.a[i * 3 + 2]
                verts[o + 6] = batch.uv.a[i * 2]; verts[o + 7] = batch.uv.a[i * 2 + 1]
                verts[o + 8] = batch.uv2.a[i * 2]; verts[o + 9] = batch.uv2.a[i * 2 + 1]
            }
            val idx = batch.idx.a.copyOf(batch.idx.n)
            mapMeshes.add(Triple(def, StaticMesh(verts, idx, Layouts.MAP), 0))
        }
        fun meshOf(mm: MapMesh): List<Pair<MatDef, StaticMesh>> {
            val out = ArrayList<Pair<MatDef, StaticMesh>>()
            for ((def, geo) in mm.parts) {
                renderer.uploadMatTextures(def)
                out.add(Pair(def, renderer.staticMeshOf(geo, Layouts.MAP)))
            }
            return out
        }
        hangingMeshes = meshOf(map.hanging)
        radarMeshes = meshOf(map.radar)
        // AO
        val aoTex = renderer.uploadTex(map.aoMap, false, false)
        aoTexture = aoTex
        radarBmp = buildRadarBitmap(world)
        hud?.radarBmp = radarBmp
        // 特效纹理
        texGlow = renderer.uploadTex(T.glow, true, false, mipmap = false)
        texPuff = renderer.uploadTex(T.puff, true, false, mipmap = false)
        for (i in 0 until 3) texFlash[i] = renderer.uploadTex(T.flash[i], true, false, mipmap = false)
        texHoleMetal = renderer.uploadTex(T.holeMetal, true, false, mipmap = false)
        texHoleWood = renderer.uploadTex(T.holeWood, true, false, mipmap = false)
        texScorch = renderer.uploadTex(T.scorch, true, false, mipmap = false)
        texBlood = renderer.uploadTex(T.blood, true, false, mipmap = false)
        texTracer = renderer.uploadTex(fx.tracerTex(), true, false, mipmap = false)
        // 贴花网格
        val dmm = HashMap<String, com.cf.transportship.engine.DynamicMesh>()
        for (k in listOf("metal", "wood", "blood", "scorch")) {
            dmm[k] = com.cf.transportship.engine.DynamicMesh(Layouts.UNLIT, 2048 * 4)
        }
        decalMeshMap = dmm
    }

    var radarBmp: Bitmap? = null
    var aoTexture = 0

    private fun createSoldierMeshes() {
        soldierTex = renderer.uploadTex(soldierAtlas(), true, false)
        for (team in listOf("BL", "GR")) {
            val geo = buildSoldierGeometry(team)
            val n = geo.vertCount
            val stride = 12 // pos3 n3 uv2 col3 skin1
            val verts = FloatArray(n * stride)
            val col = geo.col!!
            val skin = geo.skin!!
            for (i in 0 until n) {
                val o = i * stride
                verts[o] = geo.pos[i * 3]; verts[o + 1] = geo.pos[i * 3 + 1]; verts[o + 2] = geo.pos[i * 3 + 2]
                verts[o + 3] = geo.nrm[i * 3]; verts[o + 4] = geo.nrm[i * 3 + 1]; verts[o + 5] = geo.nrm[i * 3 + 2]
                verts[o + 6] = geo.uv[i * 2]; verts[o + 7] = geo.uv[i * 2 + 1]
                verts[o + 8] = col[i * 3]; verts[o + 9] = col[i * 3 + 1]; verts[o + 10] = col[i * 3 + 2]
                verts[o + 11] = skin[i]
            }
            soldierMeshes[team] = StaticMesh(verts, geo.idx, Layouts.SKIN)
        }
    }


    private fun createArmMeshes() {
        val g = com.cf.transportship.gfx.Prims
        val meshes = HashMap<String, StaticMesh>()
        val fore = g.capsule(0.037f, 0.3f, 4, 12)
        meshes["fore"] = renderer.staticMeshOf(fore, Layouts.MAP)
        val cuff = g.cylinder(0.045f, 0.045f, 0.022f, 12)
        cuff.applyMatrix(matFromQuat(Quat().setFromAxisAngle(1f, 0f, 0f, PI.toFloat() / 2)))
        meshes["cuff"] = renderer.staticMeshOf(cuff, Layouts.MAP)
        val hand = com.cf.transportship.gfx.Geo()
        val palm = com.cf.transportship.gfx.Prims.box(0.075f, 0.085f, 0.045f)
        val fingers = com.cf.transportship.gfx.Prims.box(0.07f, 0.035f, 0.07f)
        fingers.applyMatrix(matFromQuat(Quat()).also { it.m[13] = -0.035f; it.m[14] = -0.03f })
        val thumb = g.capsule(0.013f, 0.04f, 3, 6)
        thumb.applyMatrix(matFromQuat(Quat().setFromAxisAngle(1f, 0f, 0f, -1.1f)).also { m ->
            m.m[12] = 0.035f; m.m[13] = 0.01f; m.m[14] = -0.03f
        })
        hand.pos = fcat(fcat(palm.pos, fingers.pos), thumb.pos)
        hand.nrm = fcat(fcat(palm.nrm, fingers.nrm), thumb.nrm)
        hand.uv = fcat(fcat(palm.uv, fingers.uv), thumb.uv)
        hand.idx = icat(icat(palm.idx, rebase(fingers.idx, palm.vertCount)), rebase(thumb.idx, palm.vertCount + fingers.vertCount))
        meshes["hand"] = renderer.staticMeshOf(hand, Layouts.MAP)
        armMeshes = meshes
    }

    private fun rebase(idx: IntArray, off: Int): IntArray = IntArray(idx.size) { idx[it] + off }

    private fun icat(a: IntArray, b: IntArray): IntArray {
        val o = IntArray(a.size + b.size)
        System.arraycopy(a, 0, o, 0, a.size)
        System.arraycopy(b, 0, o, a.size, b.size)
        return o
    }

    private fun fcat(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(a.size + b.size)
        System.arraycopy(a, 0, o, 0, a.size)
        System.arraycopy(b, 0, o, a.size, b.size)
        return o
    }

    private fun buildGunMeshes(gm: GunModel): List<Triple<com.cf.transportship.gfx.GunMat, StaticMesh, GunPart?>> {
        val out = ArrayList<Triple<com.cf.transportship.gfx.GunMat, StaticMesh, GunPart?>>()
        // 静态件按材质合并
        val byMat = LinkedHashMap<com.cf.transportship.gfx.GunMat, MutableList<com.cf.transportship.gfx.Geo>>()
        for (p in gm.parts) {
            if (p.name != null) continue
            for ((mat, geo) in p.pieces) byMat.getOrPut(mat) { ArrayList() }.add(geo)
        }
        for ((mat, geos) in byMat) {
            val merged = mergeGeos(geos)
            if (mat.map != null && mat.glTex == 0) mat.glTex = renderer.uploadTex(mat.map, true, true)
            out.add(Triple(mat, renderer.staticMeshOf(merged, Layouts.MAP), null))
        }
        for (p in gm.parts) {
            if (p.name == null) continue
            for ((mat, geo) in p.pieces) {
                if (mat.map != null && mat.glTex == 0) mat.glTex = renderer.uploadTex(mat.map, true, true)
                out.add(Triple(mat, renderer.staticMeshOf(geo, Layouts.MAP), p))
            }
        }
        return out
    }

    private fun mergeGeos(list: List<com.cf.transportship.gfx.Geo>): com.cf.transportship.gfx.Geo {
        var pv = 0; var iv = 0
        for (g in list) { pv += g.vertCount; iv += g.idx.size }
        val pos = FloatArray(pv * 3); val nrm = FloatArray(pv * 3); val uv = FloatArray(pv * 2)
        val idx = IntArray(iv)
        var vo = 0; var io = 0
        for (g in list) {
            System.arraycopy(g.pos, 0, pos, vo * 3, g.pos.size)
            System.arraycopy(g.nrm, 0, nrm, vo * 3, g.nrm.size)
            System.arraycopy(g.uv, 0, uv, vo * 2, g.uv.size)
            for (k in g.idx) idx[io++] = k + vo
            vo += g.vertCount
        }
        return com.cf.transportship.gfx.Geo(pos, nrm, uv, idx)
    }

    // ============ 环境 ============
    fun applyEnv(name: String) {
        tod = name
        val day = name != "dusk"
        val elev = if (day) 40f else 6.5f
        val azim = if (day) 128f else 160f
        val phi = (90 - elev) * PI.toFloat() / 180f
        val theta = azim * PI.toFloat() / 180f
        sunDir.set(sin(phi) * cos(theta), cos(phi), sin(phi) * sin(theta)).normalize()
        exposure = if (day) 0.6f else 0.7f
        renderer.exposure = exposure
        renderer.sunDir.set(sunDir)
        if (day) {
            faSet(renderer.sunColor, scaleAll(linearOf(0xfff0dc), 3.6f))
            faSet(renderer.hemiSky, scaleAll(linearOf(0xcfe2f4), 0.15f))
            faSet(renderer.hemiGround, scaleAll(linearOf(0x4a5560), 0.15f))
            faSet(renderer.fogColor, linearOf(0xa9c2d6))
            renderer.fogDensity = 0.0011f
            renderer.skyTurbidity = 3f; renderer.skyRayleigh = 1.8f; renderer.skyMie = 0.0022f; renderer.skyMieG = 0.76f
            faSet(renderer.oceanSunColor, scaleAll(linearOf(0xfff0dc), 1.2f))
            faSet(renderer.oceanDeep, linearOf(0x03263f)); faSet(renderer.oceanShallow, linearOf(0x0f6a78))
            faSet(renderer.oceanZen, linearOf(0x3f78b8)); faSet(renderer.oceanHor, linearOf(0xbcd3e4))
            faSet(renderer.cloudLit, linearOf(0xffffff)); faSet(renderer.cloudShade, linearOf(0x8d9aa8))
            renderer.cloudCover = 0.47f
        } else {
            faSet(renderer.sunColor, scaleAll(linearOf(0xffa55a), 3.0f))
            faSet(renderer.hemiSky, scaleAll(linearOf(0xf0b890), 0.15f))
            faSet(renderer.hemiGround, scaleAll(linearOf(0x2a2a38), 0.15f))
            faSet(renderer.fogColor, linearOf(0xd49a78))
            renderer.fogDensity = 0.0013f
            renderer.skyTurbidity = 9f; renderer.skyRayleigh = 2.6f; renderer.skyMie = 0.006f; renderer.skyMieG = 0.86f
            faSet(renderer.oceanSunColor, scaleAll(linearOf(0xffa55a), 1.0f))
            faSet(renderer.oceanDeep, linearOf(0x0a1c2c)); faSet(renderer.oceanShallow, linearOf(0x2a4a58))
            faSet(renderer.oceanZen, linearOf(0x3a4f78)); faSet(renderer.oceanHor, linearOf(0xf2a070))
            faSet(renderer.cloudLit, linearOf(0xffc08a)); faSet(renderer.cloudShade, linearOf(0x5a4a58))
            renderer.cloudCover = 0.5f
        }
        renderer.updateLightMat(Vec3(-6f, 0f, 0f))
    }

    private fun faSet(dst: FloatArray, src: FloatArray) { for (i in dst.indices) dst[i] = src[i] }
    private fun scaleAll(a: FloatArray, k: Float): FloatArray = FloatArray(a.size) { a[it] * k }
    private fun faSet3(v: Vec3, a: FloatArray) { v.x = a[0]; v.y = a[1]; v.z = a[2] }

    private fun linearOf(hex: Int): FloatArray {
        val r = ((hex shr 16) and 0xff) / 255f
        val g = ((hex shr 8) and 0xff) / 255f
        val b = (hex and 0xff) / 255f
        fun l(v: Float) = if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055) / 1.055).toDouble(), 2.4).toFloat()
        return floatArrayOf(l(r), l(g), l(b))
    }

    // ============ 流程 ============
    fun startMatch() {
        audio.start()
        audio.playUI("start")
        audio.startAmbient()
        audio.volMaster = (opts["vol"] as? Float) ?: 0.8f
        actors.clear(); nades.clear(); timers.clear()
        score["BL"] = 0; score["GR"] = 0
        goal = opts["goal"] as Int
        timeLeft = 600f
        ended = false; inLoadout = false
        applyEnv(opts["tod"].toString())
        val my = opts["team"].toString()
        val other = if (my == "BL") "GR" else "BL"
        val names = BOT_NAMES.toMutableList().shuffled().toMutableList()
        var id = 0
        val pl = Player(this, id++, "我", my)
        player = pl
        pl.primary = opts["primary"].toString()
        pl.applyGunAnchors()
        actors.add(pl)
        val N = opts["size"] as Int
        fun primFor(team: String, i: Int): String {
            if (i == 1 && N >= 4) return "awm"
            if (i == 3 && N >= 6) return "mp5"
            if (i == 5) return if (team == "BL") "m4a1" else "ak47"
            return if (team == "BL") "ak47" else "m4a1"
        }
        for (team in listOf(my, other)) {
            val count = if (team == my) N - 1 else N
            for (i in 0 until count) {
                val b = Bot(this, id++, if (names.isNotEmpty()) names.removeAt(0) else "Bot$id", team, opts["diff"].toString())
                b.primary = primFor(team, if (team == my) i + 1 else i)
                actors.add(b)
            }
        }
        for (a in actors) spawnActor(a, true)
        vm.setTeam(my)
        vm.equip(pl.weapon.id, 0.6f)
        audio.announce("Go go go!")
        hudSetHud?.invoke(3.5f, "团队竞技 · 率先达到 $goal 击杀的队伍获胜")
        playing = true; paused = false
        hudState = ST_PLAY
        audio.setLowHealth(false)
    }

    fun spawnActor(a: Actor, first: Boolean) {
        val pts = map.spawns[a.team]!!
        var best: SpawnPoint? = null
        var bestScore = -1e9f
        for (p in pts) {
            var sc = Random.nextFloat() * 3f
            for (o in actors) {
                if (!o.alive || o === a) continue
                val dd = hypot(o.pos.x - p.x, o.pos.z - p.z)
                if (dd < 1.2f) sc -= 100f
                if (o.team != a.team) sc += min(dd, 40f) * 0.1f
            }
            if (sc > bestScore) { bestScore = sc; best = p }
        }
        a.spawn(best!!)
        applyAnchors(a)
        if (a is Bot) a.onSpawn()
        if (a is Player) {
            a.deathCamClear()
            vm.equip(a.weapon.id, if (first) 0.6f else 0.5f)
            vm.curVisible = true
            audio.setLowHealth(false)
        }
    }

    fun pause() {
        if (!playing || ended) return
        paused = true
        hudState = ST_PAUSE
    }

    fun resume() {
        paused = false
        hudState = ST_PLAY
    }

    fun quitToMenu() {
        playing = false; paused = false; ended = true
        audio.stopAmbient()
        audio.setLowHealth(false)
        actors.clear()
        player = null
        vm.curVisible = false
        hudState = ST_MENU
    }

    fun toggleLoadout() {
        if (!playing) return
        if (inLoadout) { closeLoadout(); return }
        inLoadout = true
        hudState = ST_LOADOUT
    }

    fun closeLoadout() {
        inLoadout = false
        hudState = ST_PLAY
    }

    fun chooseLoadout(id: String) {
        val p = player ?: return
        p.nextPrimary = id
        opts["primary"] = id
        saveOpts()
        val inSpawn = p.alive && (if (p.team == "BL") p.pos.x < -28.3f else p.pos.x > 28.3f)
        if (inSpawn) {
            p.primary = id
            p.inv[0] = WeaponState(id)
            p.inv[0].patternSeed = Random.nextFloat() * 6f
            p.slot = 0
            p.readyAt = time + WEAPONS[id]!!.draw
            p.soldier.setWeapon(id)
            applyAnchors(p)
            vm.equip(id, WEAPONS[id]!!.draw)
            hudSetHud?.invoke(1.5f, "已更换为 ${WEAPONS[id]!!.name}")
        } else hudSetHud?.invoke(1.5f, "复活后使用 ${WEAPONS[id]!!.name}")
        audio.playUI("buy")
    }

    fun applyAnchors(a: Actor) {
        val gm = GunModels.build(a.weapon.id, gunMats)
        a.soldier.gunGrip = gm.anchors["grip"] ?: a.soldier.gunGrip
        a.soldier.gunFore = gm.anchors["fore"] ?: a.soldier.gunFore
        a.soldier.gunMuzzle = gm.anchors["muzzle"] ?: a.soldier.gunMuzzle
    }

    fun endMatch() {
        ended = true; playing = false
        hudState = ST_END
        audio.playUI("roundEnd")
        audio.setLowHealth(false)
        val my = player?.team ?: "BL"
        val other = if (my == "BL") "GR" else "BL"
        val win = if (score[my] == score[other]) null else (score[my] ?: 0) > (score[other] ?: 0)
        audio.announce(if (win == true) "Mission accomplished" else if (win == null) "Draw" else "Mission failed")
        vm.curVisible = false
    }

    // ============ 战斗 ============
    private val eyeV = Vec3(); private val fwdV = Vec3(); private val tmpV = Vec3(); private val tmpV2 = Vec3(); private val dirV = Vec3()

    fun fireWeapon(a: Actor, ws: WeaponState, spread: Float) {
        val d = ws.def
        a.eye(eyeV)
        a.forward(fwdV)
        jitterDir(floatArrayOf(fwdV.x, fwdV.y, fwdV.z), spread) { Random.nextFloat() }
        val dir = floatArrayOf(fwdV.x, fwdV.y, fwdV.z)
        var muzzle = Vec3()
        if (a.isPlayer) {
            val cam = renderer.cam
            val right = Vec3(cos(cam.yaw), 0f, -sin(cam.yaw))
            muzzle.set(cam.px, cam.py, cam.pz)
                .addScaled(Vec3(dir[0], dir[1], dir[2]), 0.9f)
                .addScaled(right, 0.14f)
            muzzle.y -= 0.1f
            vm.fire()
            fx.light(Vec3(cam.px, cam.py, cam.pz).addScaled(Vec3(dir[0], dir[1], dir[2]), 1.2f), if (d.type == "sniper") 10f else 5f, 0.06f)
            audio.playShot(d.sound, null)
            if (Random.nextFloat() < 0.5f) audio.playShellDrop(null)
        } else {
            a.soldier.muzzleWorld(muzzle)
            fx.muzzle(muzzle, Vec3(dir[0], dir[1], dir[2]), if (d.type == "sniper") 1.6f else if (d.type == "smg") 0.8f else 1f)
            a.soldier.kick()
            audio.playShot(d.sound, floatArrayOf(muzzle.x, muzzle.y, muzzle.z))
        }
        a.radarT = 1.6f
        for (b in actors) {
            if (b !== a && b is Bot && b.team != a.team && b.pos.dist(a.pos) < 45f) b.hear(a.pos, true)
        }
        val end = traceBullet(a, eyeV, dir, d)
        if (!a.isPlayer || Random.nextFloat() < 0.35f || d.type == "sniper") fx.tracer(muzzle, end)
        // 子弹掠过玩家
        val p = player
        if (p != null && p.alive && a !== p && a.team != p.team) {
            p.eye(tmpV)
            val t = (tmpV.x - eyeV.x) * dir[0] + (tmpV.y - eyeV.y) * dir[1] + (tmpV.z - eyeV.z) * dir[2]
            val eyeEnd = eyeV.dist(end)
            if (t > 2 && t < eyeEnd) {
                val closest = eyeV.copy().addScaled(Vec3(dir[0], dir[1], dir[2]), t)
                if (closest.dist(tmpV) < 1.3f) audio.playBulletWhiz(floatArrayOf(closest.x, closest.y, closest.z))
            }
        }
    }

    fun traceBullet(shooter: Actor, o: Vec3, dir: FloatArray, d: WeaponDef): Vec3 {
        val range = d.range
        val hits = world.raycastAll(o.x, o.y, o.z, dir[0], dir[1], dir[2], range)
        var power = d.pen; var mul = 1f; var wall = false; var from = 0f
        frame++
        for (i in 0..hits.size) {
            val h = if (i < hits.size) hits[i] else null
            val lim = h?.t ?: range
            // 角色命中
            var best: Actor? = null
            var bestT = lim; var part = ""
            for (a in actors) {
                if (!a.alive || a === shooter || a.team == shooter.team) continue
                val r = a.soldier.hitTest(o, Vec3(dir[0], dir[1], dir[2]), bestT, frame)
                if (r != null && r.t > from - 0.01f && r.t < bestT) { best = a; bestT = r.t; part = r.part }
            }
            if (best != null) {
                val pt = o.copy().addScaled(Vec3(dir[0], dir[1], dir[2]), bestT)
                val dist = bestT
                val partMul = when (part) {
                    "head" -> d.headMul
                    "arm", "leg" -> d.limbMul
                    else -> 1f
                }
                val dmg = d.dmg * mul * Math.pow(d.falloff.toDouble(), (dist / 10f).toDouble()).toFloat() * partMul
                fx.impact(pt, Vec3(-dir[0], -dir[1], -dir[2]), "flesh", Vec3(dir[0], dir[1], dir[2]))
                audio.playImpact(floatArrayOf(pt.x, pt.y, pt.z), "flesh")
                damage(best, shooter, dmg, part, d.id, Vec3(dir[0], dir[1], dir[2]), wall)
                return pt
            }
            if (h == null) break
            val pt = o.copy().addScaled(Vec3(dir[0], dir[1], dir[2]), h.t)
            val col = h.collider ?: continue
            val mat = col.mat
            fx.impact(pt, Vec3(h.nx, h.ny, h.nz), mat, Vec3(dir[0], dir[1], dir[2]))
            audio.playImpact(floatArrayOf(pt.x, pt.y, pt.z), if (mat == "wood") "wood" else "metal")
            if (col.bullet == "pen") {
                val thick = h.exit - h.t
                val cost = thick * (if (mat == "wood") 1.0f else 1.9f)
                if (power > cost) {
                    power -= cost; mul *= 0.6f; wall = true; from = h.exit
                    val ep = o.copy().addScaled(Vec3(dir[0], dir[1], dir[2]), h.exit)
                    fx.impact(ep, Vec3(dir[0], dir[1], dir[2]), mat, Vec3(dir[0], dir[1], dir[2]))
                    continue
                }
            } else if (Random.nextFloat() < 0.08f && mat == "metal") audio.playRicochet(floatArrayOf(pt.x, pt.y, pt.z))
            return pt
        }
        return o.copy().addScaled(Vec3(dir[0], dir[1], dir[2]), range)
    }

    fun melee(a: Actor, heavy: Boolean) {
        val d = WEAPONS["knife"]!!
        val range = if (heavy) d.rangeHeavy else d.rangeLight
        a.eye(eyeV)
        a.forward(fwdV)
        if (a.isPlayer) vm.melee(heavy)
        frame++
        var hitA: Actor? = null; var hitT2 = 0f; var hitPart = ""; var hitDir = FloatArray(3)
        outer@ for (off in floatArrayOf(0f, 0.12f, -0.12f, 0.24f, -0.24f)) {
            val ca = cos(off); val sa = sin(off)
            val dir = floatArrayOf(
                fwdV.x * ca + fwdV.z * sa,
                fwdV.y,
                -fwdV.x * sa + fwdV.z * ca,
            )
            for (b in actors) {
                if (!b.alive || b === a || b.team == a.team) continue
                val r = b.soldier.hitTest(eyeV, Vec3(dir[0], dir[1], dir[2]), range, frame)
                if (r != null && (hitA == null || r.t < hitT2)) {
                    hitA = b; hitT2 = r.t; hitPart = r.part; hitDir = dir
                }
            }
            if (hitA != null) break@outer
        }
        val delay = if (heavy) 0.33f else 0.1f
        timers.add(GameTimer(time + delay) {
            if (!a.alive) return@GameTimer
            val h = hitA
            if (h != null && h.alive) {
                val vf = Vec3(-sin(h.yaw), 0f, -cos(h.yaw))
                val dn = Vec3(hitDir[0], 0f, hitDir[2]).normalize()
                val back = vf.dot(dn) > 0.5f
                var dmg = if (heavy) d.dmgHeavy else d.dmgLight
                if (back) dmg *= if (heavy) 2f else 1.6f
                if (hitPart == "head") dmg *= 1.3f
                val pt = eyeV.copy().addScaled(Vec3(hitDir[0], hitDir[1], hitDir[2]), hitT2)
                fx.impact(pt, Vec3(-hitDir[0], -hitDir[1], -hitDir[2]), "flesh", Vec3(hitDir[0], hitDir[1], hitDir[2]))
                audio.playKnife(if (heavy) "heavy" else "light", "flesh", if (a.isPlayer) null else floatArrayOf(eyeV.x, eyeV.y, eyeV.z))
                damage(h, a, dmg, hitPart, "knife", Vec3(hitDir[0], hitDir[1], hitDir[2]), false)
            } else {
                val w = world.raycast(eyeV.x, eyeV.y, eyeV.z, fwdV.x, fwdV.y, fwdV.z, range, "bullet")
                if (w != null) {
                    val pt = eyeV.copy().addScaled(fwdV, w.t)
                    fx.impact(pt, Vec3(w.nx, w.ny, w.nz), w.collider!!.mat, fwdV)
                    audio.playKnife(if (heavy) "heavy" else "light", "wall", if (a.isPlayer) null else floatArrayOf(eyeV.x, eyeV.y, eyeV.z))
                } else audio.playKnife(if (heavy) "heavy" else "light", "miss", if (a.isPlayer) null else floatArrayOf(eyeV.x, eyeV.y, eyeV.z))
            }
        })
    }

    fun throwGrenade(a: Actor) {
        a.eye(eyeV)
        a.forward(fwdV)
        val right = Vec3(cos(a.yaw), 0f, -sin(a.yaw))
        val pos = eyeV.copy().addScaled(fwdV, 0.5f).addScaled(right, 0.12f)
        val vel = fwdV.copy().mul(16f)
        vel.y += 2.8f
        vel.addScaled(a.vel, 0.6f)
        val n = Nade()
        n.pos.set(pos); n.vel.set(vel)
        n.fuse = WEAPONS["he"]!!.fuse
        n.owner = a
        n.spin.set(Random.nextFloat() * 10f, Random.nextFloat() * 10f, 0f)
        nades.add(n)
        audio.playGrenadeThrow()
        if (a.isPlayer) audio.announce("Fire in the hole!")
        for (b in actors) {
            if (b is Bot && b.team != a.team && b.pos.dist(pos) < 20f) b.hear(pos, false)
        }
    }

    fun updateNades(dt: Float) {
        val it = nades.iterator()
        while (it.hasNext()) {
            val n = it.next()
            n.fuse -= dt
            val steps = 3; val h = dt / steps
            for (s in 0 until steps) {
                n.vel.y -= 14f * h
                val sp = n.vel.len()
                if (sp < 1e-4f) continue
                val d = Vec3(n.vel.x / sp, n.vel.y / sp, n.vel.z / sp)
                val L = sp * h + 0.07f
                val hit = world.raycast(n.pos.x, n.pos.y, n.pos.z, d.x, d.y, d.z, L, "move")
                if (hit != null) {
                    val nn = Vec3(hit.nx, hit.ny, hit.nz)
                    n.pos.addScaled(d, (hit.t - 0.07f).coerceAtLeast(0f))
                    val vn = n.vel.dot(nn)
                    n.vel.addScaled(nn, -1.45f * vn).mul(0.55f)
                    if (abs(vn) > 2f) audio.playGrenadeBounce(floatArrayOf(n.pos.x, n.pos.y, n.pos.z))
                    if (nn.y > 0.7f && abs(n.vel.y) < 1.2f) { n.vel.y = 0f; n.vel.x *= 0.8f; n.vel.z *= 0.8f }
                    n.spin.mul(0.6f)
                } else n.pos.addScaled(n.vel, h)
            }
            if (n.fuse <= 0) {
                explode(n.pos.copy(), n.owner!!)
                it.remove()
            }
        }
    }

    fun explode(p: Vec3, owner: Actor) {
        val d = WEAPONS["he"]!!
        fx.explosion(p)
        audio.playExplosion(floatArrayOf(p.x, p.y, p.z))
        val camD = hypot(hypot(renderer.cam.px - p.x, renderer.cam.py - p.y), renderer.cam.pz - p.z)
        fx.shake = kotlin.math.max(fx.shake, kotlin.math.max(0f, 1.4f - camD / 18f))
        for (a in actors) {
            if (!a.alive) continue
            if (a.team == owner.team && a !== owner) continue
            a.soldier.chestWorld(tmpV)
            val dist = tmpV.dist(p)
            if (dist > d.radius) continue
            val dir = tmpV.copy().sub(p)
            val L = dir.len(); dir.mul(1f / kotlin.math.max(L, 1f))
            val blocked = world.raycast(p.x, p.y + 0.2f, p.z, dir.x, dir.y, dir.z, kotlin.math.max(0f, L - 0.3f), "bullet")
            var dmg = d.dmg * Math.pow((1 - dist / d.radius).toDouble(), 1.1).toFloat()
            if (blocked != null) dmg *= 0.2f
            if (dmg > 1) damage(a, owner, dmg, "chest", "he", dir, false)
        }
        for (b in actors) {
            if (b is Bot && b.pos.dist(p) < 40f) b.hear(p, true)
        }
    }

    fun damage(v: Actor, att: Actor?, amt: Float, part: String, wid: String, dir: Vec3, wall: Boolean, melee: Boolean = false) {
        if (!v.alive || v.protectT > 0) return
        if (att != null && att !== v && att.team == v.team) return
        val def = WEAPONS[wid]
        var hpD = amt
        if (v.armor > 0 && part != "leg") {
            val ap = def?.armorPen ?: 0.75f
            hpD = amt * ap
            v.armor = kotlin.math.max(0f, v.armor - amt * (1 - ap) * 1.4f)
        }
        v.hp -= hpD
        v.lastAttacker = att; v.lastHurt = time
        val killed = v.hp <= 0
        if (att != null && att !== v) att.stats.hits++
        if (v.isPlayer) {
            if (att != null && att !== v) {
                hud?.damageFrom(kotlin.math.atan2(-(att.pos.x - v.pos.x), -(att.pos.z - v.pos.z)))
            }
            v.aimPunch += min(0.05f, hpD * 0.0012f)
            audio.playHurt(min(100f, hpD))
            dmgFlash = min(1.2f, dmgFlash + hpD / 45f)
            if (v.hp <= 30 && !killed) audio.setLowHealth(true)
        } else v.onDamaged(att)
        if (att != null && att.isPlayer && att !== v) {
            hud?.hitmarker(part == "head", killed)
            audio.playHitmarker(part == "head")
        }
        if (killed) kill(v, att, wid, part == "head" && !melee, wall, dir)
    }

    fun kill(v: Actor, att: Actor?, wid: String, hs: Boolean, wall: Boolean, dir: Vec3) {
        v.alive = false; v.hp = 0f; v.deadT = 0f; v.respawnT = 4f; v.stats.d++
        v.scoped = 0
        v.soldier.die(dir.x, dir.z, hs)
        v.soldier.chestWorld(tmpV)
        audio.playDeath(floatArrayOf(tmpV.x, tmpV.y, tmpV.z))
        val p = player
        if (att != null && att !== v) {
            att.stats.k++
            if (hs) att.stats.hs++
            score[att.team] = (score[att.team] ?: 0) + 1
            att.multi = if (time - att.lastKillT < 5) att.multi + 1 else 1
            att.lastKillT = time; att.streak++
        }
        hud?.killFeed(if (att != null && att !== v) att else null, v, wid, hs, wall, att === p || v === p)
        if (att === p && v !== p) {
            val m = min(att!!.multi, 8)
            var text = "KILL"; var sub = "击杀 ${v.name}"
            if (m >= 2) {
                text = MULTI[m]; sub = MULTI_CN[m] + " · " + sub
                timers.add(GameTimer(time + 0.15f) { audio.announce(MULTI[m] + "!") })
            } else if (hs) {
                text = "HEADSHOT"; sub = "爆头 · " + sub
                timers.add(GameTimer(time + 0.15f) { audio.announce("Headshot!") })
            } else if (wid == "knife") { text = "KNIFE KILL"; sub = "刀杀 · " + sub }
            else if (wid == "he") { text = "GRENADE KILL"; sub = "手雷击杀 · " + sub }
            else if (wall) { text = "WALLBANG"; sub = "穿墙击杀 · " + sub }
            hud?.badge(text, sub, hs)
            audio.playKillConfirm(hs)
        }
        if (v === p) {
            p.startDeathCam(if (att != null && att !== v) att else null)
            vm.curVisible = false
            audio.setLowHealth(false)
            val wn = WEAPONS[wid]?.name ?: wid
            killedBy = if (att != null && att !== v) "被 ${att.name} 用 $wn${if (hs) " 爆头" else ""}击杀" else "你阵亡了"
        }
        fx.bloodSplat(v.pos)
        if ((score["BL"] ?: 0) >= goal || (score["GR"] ?: 0) >= goal) {
            timers.add(GameTimer(time + 1.2f) { if (playing) endMatch() })
        }
    }

    // ============ 事件音效 ============
    fun onJump(a: Actor) = audio.playJump(if (a.isPlayer) null else floatArrayOf(a.pos.x, a.pos.y, a.pos.z))

    fun onLand(a: Actor, sp: Float) =
        audio.playLand(if (a.isPlayer) null else floatArrayOf(a.pos.x, a.pos.y, a.pos.z), a.groundSurface, min(1f, sp / 10f))

    fun onFootstep(a: Actor) {
        if (!a.isPlayer && a.pos.dist(Vec3(renderer.cam.px, renderer.cam.py, renderer.cam.pz)) > 30f) return
        audio.playFootstep(if (a.isPlayer) null else floatArrayOf(a.pos.x, a.pos.y, a.pos.z), a.groundSurface, true, a.crouch)
        if (!a.walk) {
            for (b in actors) {
                if (b is Bot && b.team != a.team && b.pos.dist(a.pos) < 12f) b.hear(a.pos, false)
            }
        }
    }

    fun onSwitch(a: Actor) {
        if (!a.isPlayer) return
        vm.equip(a.weapon.id, a.weapon.def.draw)
        audio.playWeaponSwitch(a.weapon.id)
        applyAnchors(a)
    }

    fun onReloadStart(a: Actor, empty: Boolean) {
        if (!a.isPlayer) return
        val d = a.weapon.def
        vm.reload(d.reload, empty)
        val id = d.id
        timers.add(GameTimer(time + d.reload * 0.2f) { audio.playReload(id, "magout") })
        timers.add(GameTimer(time + d.reload * 0.6f) { audio.playReload(id, "magin") })
        if (empty) {
            timers.add(GameTimer(time + d.reload * 0.82f) { audio.playReload(id, if (id == "awm") "bolt" else "boltback") })
            timers.add(GameTimer(time + d.reload * 0.88f) { audio.playReload(id, "boltforward") })
        }
    }

    fun onReloadDone(a: Actor) {}
    fun onScope(a: Actor) { if (a.isPlayer) audio.playScope(a.scoped > 0) }
    fun onDryFire(a: Actor) { if (a.isPlayer) audio.playDryFire() }
    fun onGrenadeStart(a: Actor) { if (a.isPlayer) { vm.throwNade(); audio.playGrenadePin() } }

    // ============ 主循环 ============
    var hudState = ST_MENU
    var hud: com.cf.transportship.ui.Hud? = null

    fun frameUpdate(nowMs: Long) {
        var dt = (nowMs - lastMs) / 1000f
        lastMs = nowMs
        if (dt > 0.1f) dt = 0.1f
        if (dt <= 0f) return
        realTime += dt
        val active = playing && !paused && !inLoadout
        if (active) simulate(dt)
        else if (!playing && !ended) {
            // 菜单：环绕运输船
            val cam = renderer.cam
            val t = realTime * 0.045f
            cam.px = cos(t) * 46 - 6
            cam.py = 13 + sin(t * 2.1f) * 3
            cam.pz = sin(t) * 34
            lookAtMenu(cam, -4f, 1.5f, 0f)
            cam.fov = 60f
        }
        renderFrame(dt)
    }
    private var lastMs = 0L

    private fun lookAtMenu(cam: Camera, tx: Float, ty: Float, tz: Float) {
        val dx = cam.px - tx; val dy = cam.py - ty; val dz = cam.pz - tz
        val hd = kotlin.math.sqrt(dx * dx + dz * dz)
        cam.yaw = kotlin.math.atan2(-dx, -dz)
        cam.pitch = kotlin.math.atan2(dy, hd)
        cam.roll = 0f
    }

    fun simulate(dt: Float) {
        time += dt
        timeLeft -= dt
        val due = ArrayList<GameTimer>()
        val it = timers.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (time >= t.t) { it.remove(); due.add(t) }
        }
        for (t in due) t.fn()
        player?.update(dt)
        for (a in actors) {
            if (a.isPlayer) continue
            a.update(dt)
        }
        for (a in actors) {
            a.radarT = kotlin.math.max(0f, a.radarT - dt)
            if (a.alive) {
                a.protectT = kotlin.math.max(0f, a.protectT - dt)
                val s = a.soldier
                val fallX = if (s.deadT >= 0) -s.fallDir * (min(1f, s.deadT * 1.6f * s.fallSpeed).let { it * it } * PI.toFloat() * 0.5f * 0.96f) else 0f
                s.setRoot(a.pos.x, a.pos.y, a.pos.z, a.yaw, fallX, 0f)
                val fwd = (a.vel.x * -sin(a.yaw) + a.vel.z * -cos(a.yaw)) / kotlin.math.max(0.01f, a.speed)
                s.update(dt, a.speed, fwd, a.crouch, a.pitch + a.punchP, a.onGround, a.weapon.reloading)
                if (!a.isPlayer) {
                    s.visible = !(a.protectT > 0 && sin(time * 30) > 0.3f)
                } else s.visible = false
            } else {
                a.deadT += dt
                a.soldier.update(dt, 0f, 0f, false, 0f, false, false)
                if (a.isPlayer) a.soldier.visible = true
                a.respawnT -= dt
                if (a.respawnT <= 0 && !ended) spawnActor(a, false)
            }
        }
        updateNades(dt)
        if (timeLeft <= 0 && !ended) endMatch()
        updateNameTags()
    }

    private val projV = Vec3()
    private fun updateNameTags() {
        nameTags.clear()
        val p = player ?: return
        val cam = renderer.cam
        for (a in actors) {
            if (a === p || a.team != p.team || !a.alive) continue
            if (a.pos.dist(Vec3(cam.px, cam.py, cam.pz)) > 45f) continue
            a.soldier.headWorld(projV)
            projV.y += 0.42f
            // 投影到屏幕
            val vp = cam.viewProj
            val x = projV.x; val y = projV.y; val z = projV.z
            val cw = vp.m[3] * x + vp.m[7] * y + vp.m[11] * z + vp.m[15]
            if (cw < 0.1f) continue
            val cxp = (vp.m[0] * x + vp.m[4] * y + vp.m[8] * z + vp.m[12]) / cw
            val cyp = (vp.m[1] * x + vp.m[5] * y + vp.m[9] * z + vp.m[13]) / cw
            nameTags.add(NameTag((cxp * 0.5f + 0.5f) * w, (0.5f - cyp * 0.5f) * h, a.name,
                if (a.team == "BL") Color999(255, 155, 112) else Color999(140, 200, 255), true))
        }
    }

    private fun Color999(r: Int, g: Int, b: Int) = android.graphics.Color.argb(255, r, g, b)

    fun renderFrame(dt: Float) {
        val cam = renderer.cam
        if (player != null && playing) {
            val p = player!!
            if (frame % 6 == 0) updateLightProbe()
            frame++
            // 相机应用（Player.updateCamera 已设置 cam 值）
            cam.update()
            vmCam.aspect = w / h
            vmCam.update()
            vm.update(dt, p.speed, p.onGround, p.crouch, p.lookDX, p.lookDY, lightK, indoorK > 0.5f)
            vm.curVisible = p.alive && !(p.scoped > 0 && p.weapon.def.type == "sniper")
        } else if (!playing) {
            cam.update()
        } else cam.update()
        // 动画网格
        map.hanging.model.compose(
            map.hanging.model.m[12], map.hanging.model.m[13], map.hanging.model.m[14],
            Quat().setFromAxisAngle(0f, 0f, 1f, sin(realTime * 0.55f) * 0.018f)
                .multiply(Quat().setFromAxisAngle(1f, 0f, 0f, sin(realTime * 0.4f + 1f) * 0.012f))
                .multiply(Quat().setFromAxisAngle(0f, 1f, 0f, sin(realTime * 0.21f) * 0.1f)),
            1f, 1f, 1f,
        )
        map.radar.model = Mat4().compose(-45f, 22.4f, 0f, Quat().setFromAxisAngle(0f, 1f, 0f, realTime * 1.6f), 1f, 1f, 1f)
        shipDist += dt * shipSpeed
        renderer.shipDist = shipDist
        // 帧率
        fpsAcc += dt; fpsN++
        if (fpsAcc > 1) { fps = (fpsN / fpsAcc).toInt(); fpsAcc = 0f; fpsN = 0 }
        // 特效推进（烟囱烟雾 / 屏幕震动衰减 / 灯光衰减）
        val camR = Vec3(cos(cam.yaw), 0f, -sin(cam.yaw))
        val camF2 = Vec3(); cam.forward(camF2)
        fx.update(dt, Vec3(cam.px, cam.py, cam.pz), camR, camF2.cross(camR).normalize(), shipSpeed)
        // 点光源映射：地图灯 + 动态灯
        var li = 0
        val lowQ = opts["quality"] == "low"
        if (!lowQ) {
            for (s in map.lampSpots) {
                if (li >= 8) break
                val L = renderer.pointLights[li++]
                L.pos.set(s)
                L.r = 1f; L.g = 0.85f; L.b = 0.63f
                L.radius = 9f
                L.intensity = 2.5f
            }
        }
        for (dl in fx.lights) {
            if (li >= 8) break
            if (dl.t <= 0 || dl.peak <= 0f) continue
            val L = renderer.pointLights[li++]
            L.pos.set(dl.l)
            L.r = dl.colorR; L.g = dl.colorG; L.b = dl.colorB
            L.radius = dl.radius
            L.intensity = dl.peak * (dl.t / dl.dur).coerceIn(0f, 1f)
        }
        renderer.pointCount = li
        // 音频监听
        val cf = Vec3(); cam.forward(cf)
        audio.setListener(floatArrayOf(cam.px, cam.py, cam.pz), floatArrayOf(cf.x, cf.y, cf.z))
        // HUD
        hud?.update(dt)
        updateHudValues()
        // 屏幕特效
        dmgFlash = kotlin.math.max(0f, dmgFlash - dt * 1.6f)
        val p = player
        renderer.fxTime = realTime
        renderer.fxDamage = dmgFlash
        renderer.fxLowHP = if (p != null && p.alive && playing) kotlin.math.max(0f, (35 - p.hp) / 35f) else 0f
        renderer.fxDeath = if (p != null && !p.alive && playing) min(1f, p.deadT * 2f) else 0f
        renderer.fxProtect = if (p != null && p.alive && playing) min(1f, p.protectT) else 0f
        renderer.fxVignette = if (p != null && p.scoped > 0) 0f else 0.3f
        renderer.particleScale = fx.particleScaleOf(renderer.renderH, cam.fov)
        renderGL()
    }

    private fun updateLightProbe() {
        val p = player ?: return
        p.eye(tmpV)
        val sunBlocked = world.raycast(tmpV.x, tmpV.y, tmpV.z, sunDir.x, sunDir.y, sunDir.z, 80f, "sight") != null
        val roof = world.raycast(tmpV.x, tmpV.y, tmpV.z, 0f, 1f, 0f, 5f, "sight") != null
        val tl = if (sunBlocked) 0.22f else 1f
        val ti = if (roof) 1f else 0f
        lightK += (tl - lightK) * 0.35f
        indoorK += (ti - indoorK) * 0.35f
    }

    private fun updateHudValues() {
        val hd = hud ?: return
        val p = player ?: return
        val wst = p.weapon
        val cam = renderer.cam
        var spreadPx = 0f
        val spr = wst.def.spread
        if (spr != null) {
            val sp = min(0.12f, wst.spreadAcc + spr.base * 2 +
                (if (p.speed > 0.6f) spr.move * min(1f, p.speed / 5.7f) else 0f) +
                (if (p.onGround) 0f else spr.air * 0.5f)) * (if (p.crouch) 0.7f else 1f)
            spreadPx = (kotlin.math.tan(sp.toDouble()) / kotlin.math.tan(Math.toRadians(cam.fov / 2.0)) * h / 2).toFloat()
        }
        hd.spreadPx = spreadPx
        // 准星下角色名
        if (p.alive && frame % 4 == 0) {
            cam.forward(dirV)
            val wh = world.raycast(cam.px, cam.py, cam.pz, dirV.x, dirV.y, dirV.z, 80f, "sight")
            val lim = wh?.t ?: 80f
            var best: Actor? = null; var bt = lim
            frame++
            for (a in actors) {
                if (a === p || !a.alive) continue
                val r = a.soldier.hitTest(Vec3(cam.px, cam.py, cam.pz), dirV, bt, frame)
                if (r != null) { best = a; bt = r.t }
            }
            aimTarget = best
        }
        hd.aimName = aimTarget?.takeIf { it.alive }?.name ?: ""
        hd.aimTeam = aimTarget?.takeIf { it.alive }?.team ?: ""
        hd.fps = fps
        hd.state = hudState
    }

    // ============ GL 渲染 ============
    private val boneMats = FloatArray(19 * 16)

    private fun renderGL() {
        val cam = renderer.cam
        // 1) 阴影
        renderer.shadowPass(
            drawMap = { drawMapDepth() },
            drawSkin = { prog -> drawSoldiersDepth(prog) },
        )
        // 2) 场景
        renderer.beginScene()
        renderer.drawSky(Vec3(cam.px, cam.py, cam.pz))
        renderer.drawOcean(realTime)
        renderer.drawClouds(realTime)
        renderer.drawStaticBatches(mapMeshes, aoTexture)
        // 动画网格
        for ((def, mesh) in hangingMeshes) {
            renderer.drawMatMesh(def, mesh, map.hanging.model)
        }
        for ((def, mesh) in radarMeshes) renderer.drawMatMesh(def, mesh, map.radar.model)
        // 士兵 + 枪
        drawSoldiers()
        // 手雷
        for (n in nades) {
            val rd = gunRD["he"] ?: continue
            val q = Quat().setFromAxisAngle(1f, 0f, 0f, n.spin.x * realTime)
                .multiply(Quat().setFromAxisAngle(0f, 1f, 0f, n.spin.y * realTime))
            val m = Mat4().compose(n.pos.x, n.pos.y, n.pos.z, q, 1.3f, 1.3f, 1.3f)
            for ((mat, mesh, part) in rd) {
                if (part != null) continue
                renderer.drawGunMesh(mat, mesh, m, renderer.cam.viewProj, renderer.cam.pos)
            }
        }
        // 贴花 / 曳光 / 火焰
        fx.buildDecalMeshes(decalMeshMap)
        fx.advanceTracers(0.016f)
        fx.buildTracerMesh(renderer.tracerMesh, Vec3(cam.px, cam.py, cam.pz))
        val camRight = Vec3(cos(cam.yaw), 0f, -sin(cam.yaw))
        val camF = Vec3(); cam.forward(camF)
        val camUp = camF.cross(camRight).normalize()
        fx.buildFlashMesh(renderer.flashMesh, Vec3(cam.px, cam.py, cam.pz), camRight, camUp)
        drawDecals()
        renderer.drawUnlit(renderer.tracerMesh, texTracer, floatArrayOf(1f, 0.88f, 0.63f, 1f), true, false)
        renderer.drawUnlit(renderer.flashMesh, texFlash[0], floatArrayOf(1f, 0.85f, 0.63f, 1f), true, false)
        // 粒子
        val (addData, addN) = fx.add.update(0.016f)
        val (smokeData, smokeN) = fx.smoke.update(0.016f)
        renderer.drawParticles(renderer.texGlow, addData, addN, true)
        renderer.drawParticles(renderer.texPuff, smokeData, smokeN, false)
        // 3) 第一人称
        drawViewModel()
        // 4) 后期
        renderer.composite()
    }

    private fun drawMapDepth() {
        val p = renderer.pDepthPrg()
        for ((_, mesh, _) in mapMeshes) mesh.draw()
        for ((_, mesh) in hangingMeshes) drawDepthMesh(mesh, map.hanging.model, p)
        for ((_, mesh) in radarMeshes) drawDepthMesh(mesh, map.radar.model, p)
    }

    private fun drawDepthMesh(mesh: StaticMesh, model: Mat4, p: com.cf.transportship.engine.Prg) {
        p.mat4("uModel", model.m)
        mesh.draw()
    }

    private fun drawSoldiersDepth(prog: com.cf.transportship.engine.Prg) {
        for (a in actors) {
            if (!a.alive && a.soldier.opacity <= 0f) continue
            if (a.isPlayer && !playerAliveVisible()) continue
            fillBones(a.soldier)
            prog.mat4fv("uBones", boneMats, 19)
            prog.mat4("uModel", identity.m)
            prog.bool("uSkin", true)
            soldierMeshes[a.team]?.draw()
        }
    }

    private fun playerAliveVisible(): Boolean {
        val p = player ?: return false
        return p.soldier.visible
    }

    private val identity = Mat4()

    private fun fillBones(s: Soldier) {
        for (i in 0 until 19) {
            val m = s.bones[i].skin.m
            System.arraycopy(m, 0, boneMats, i * 16, 16)
        }
    }

    private fun drawSoldiers() {
        val p = renderer.pSkinPrg()
        p.use()
        renderer.setCameraUniforms(p, renderer.cam.viewProj, renderer.cam.pos)
        p.mat4("uModel", identity.m)
        glActiveTexture(0x84C0) // GL_TEXTURE0
        glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, soldierTex)
        p.i("uMap", 0)
        p.f("uOpacity", 1f)
        p.bool("uHasNormalMap", false)
        p.bool("uUseAO", false)
        p.vec3v("uColor", floatArrayOf(1f, 1f, 1f))
        p.f("uRoughness", 0.82f)
        p.f("uMetalness", 0.05f)
        p.vec3v("uEmissive", floatArrayOf(0f, 0f, 0f))
        p.f("uAlphaTest", 0f)
        for (a in actors) {
            if (!a.alive && a.soldier.opacity <= 0f) continue
            if (a.isPlayer && a.alive) continue
            fillBones(a.soldier)
            p.mat4fv("uBones", boneMats, 19)
            p.f("uOpacity", a.soldier.opacity)
            soldierMeshes[a.team]?.draw()
            // 枪
            if (a.soldier.gunVisible && a.alive) {
                val rd = gunRD[a.weapon.id] ?: continue
                for ((mat, mesh, part) in rd) {
                    val m = gunPartMatrix(a.soldier, part)
                    renderer.drawGunMesh(mat, mesh, m, renderer.cam.viewProj, renderer.cam.pos)
                }
            }
        }
    }

    private fun gunPartMatrix(s: Soldier, part: GunPart?): Mat4 {
        if (part == null) return s.gunWorldM
        val m = Mat4().set(s.gunWorldM)
        val q = Quat().setFromAxisAngle(0f, 0f, 1f, part.restRot[2])
            .multiply(Quat().setFromAxisAngle(0f, 1f, 0f, part.restRot[1]))
            .multiply(Quat().setFromAxisAngle(1f, 0f, 0f, part.restRot[0]))
        val local = Mat4().compose(part.restPos[0], part.restPos[1], part.restPos[2], q, 1f, 1f, 1f)
        return m.multiply(local)
    }

    // ---------- 第一人称 ----------
    private val vmIdentity = Mat4()

    private fun drawViewModel() {
        val p = player ?: return
        if (!playing || !vm.curVisible || vm.curId == null) return
        val sf = renderer.sceneFbo() ?: return
        // 清深度，用 vm 相机
        glClear(android.opengl.GLES30.GL_DEPTH_BUFFER_BIT)
        val vp = vmCam.viewProj
        // vm 光照（相机空间）
        val savedSun = FloatArray(renderer.sunDir.let { floatArrayOf(it.x, it.y, it.z) }.size)
        savedSun[0] = renderer.sunDir.x; savedSun[1] = renderer.sunDir.y; savedSun[2] = renderer.sunDir.z
        val savedFog = renderer.fogDensity
        // 太阳方向变换到相机空间
        val inv = Mat4().set(renderer.cam.view)
        val sunCam = inv.transformDir(Vec3(sunDir.x, sunDir.y, sunDir.z))
        renderer.sunDir.set(sunCam)
        renderer.fogDensity = 0f
        val light = 2.6f * vm.lightK
        faSet(renderer.sunColor, floatArrayOf(light, light, light))
        val fillI = 0.35f * (if (vm.indoorK) 0.5f else 1f) * 0.9f
        faSet(renderer.hemiSky, floatArrayOf(fillI, fillI, fillI))
        faSet(renderer.hemiGround, floatArrayOf(fillI * 0.5f, fillI * 0.5f, fillI * 0.5f))
        val rd = gunRD[vm.curId!!] ?: return
        val camPosVm = Vec3(0f, 0f, 0f)
        for ((mat, mesh, part) in rd) {
            val show = part == null || (vm.partState[part.name]?.visible ?: true)
            if (!show) continue
            val m = vmPartMatrix(part)
            renderer.drawGunMeshVm(mat, mesh, m, vp)
        }
        // 手臂
        val arms = armMeshes ?: return
        for ((pose, side) in listOf(Pair(vm.armR, "R"), Pair(vm.armL, "L"))) {
            if (!pose.visible) continue
            val sleeve = if (side == "R") vm.sleeveColor else vm.sleeveColor
            renderer.drawGunMeshVmColor(arms["fore"]!!, pose.foreM, vp, sleeve, 0.9f, 0f)
            renderer.drawGunMeshVmColor(arms["cuff"]!!, pose.cuffM, vp, vm.cuffColor, 0.8f, 0f)
            renderer.drawGunMeshVmColor(arms["hand"]!!, pose.handM, vp, floatArrayOf(0.09f, 0.09f, 0.1f), 0.62f, 0f)
        }
        // 弹壳
        val shell = shellMesh ?: return
        for (s in vm.shells) {
            if (s.life <= 0) continue
            val q = Quat().setFromAxisAngle(1f, 0f, 0f, s.rx).multiply(Quat().setFromAxisAngle(0f, 1f, 0f, s.ry))
            val m = Mat4().compose(s.x, s.y, s.z, q, 1f, 1f, 1f)
            renderer.drawGunMeshVmColorBrass(shell, m, vp)
        }
        // 枪口火焰（vm 空间）
        if (vm.flashT > 0) {
            drawVmFlash(vp)
        }
        // 恢复
        faSet3(renderer.sunDir, savedSun)
        renderer.fogDensity = savedFog
        applyEnv(tod)
    }

    private fun vmPartMatrix(part: GunPart?): Mat4 {
        if (part == null) return vm.holderM
        val ps = vm.partState[part.name]
        val ox = ps?.ox ?: 0f; val oy = ps?.oy ?: 0f; val oz = ps?.oz ?: 0f
        val rx = part.restRot[0] + (ps?.rx ?: 0f)
        val ry = part.restRot[1] + (ps?.ry ?: 0f)
        val rz = part.restRot[2] + (ps?.rz ?: 0f)
        val q = Quat().setFromAxisAngle(1f, 0f, 0f, rx)
            .multiply(Quat().setFromAxisAngle(0f, 1f, 0f, ry))
            .multiply(Quat().setFromAxisAngle(0f, 0f, 1f, rz))
        val local = Mat4().compose(part.restPos[0] + ox, part.restPos[1] + oy, part.restPos[2] + oz, q, 1f, 1f, 1f)
        return Mat4().set(vm.holderM).multiply(local)
    }

    private fun drawVmFlash(vp: com.cf.transportship.engine.Mat4) {
        val id = vm.curId ?: return
        val gm = GunModels.build(id, gunMats)
        val mz = gm.anchors["muzzle"] ?: return
        val hip = HIP[id] ?: HIP["ak47"]!!
        val sc = when (id) { "awm" -> 1.6f; "deagle" -> 1.2f; "mp5" -> 0.8f; else -> 1f }
        val s = sc * vm.flashScale
        val base = Mat4().set(vm.holderM).multiply(
            Mat4().compose(mz[0], mz[1], mz[2], Quat(), s, s, s),
        )
        renderer.vmFlashMesh.reset()
        // 正面
        val f = 0.08f
        val m = base
        val p0 = m.transformPoint(Vec3(-f, -f, 0f)); val p1 = m.transformPoint(Vec3(f, -f, 0f))
        val p2 = m.transformPoint(Vec3(f, f, 0f)); val p3 = m.transformPoint(Vec3(-f, f, 0f))
        val rot = vm.flashFrontRot
        renderer.vmFlashMesh.quad(
            p0.x, p0.y, p0.z, 0f, 1f, p1.x, p1.y, p1.z, 1f, 1f,
            p2.x, p2.y, p2.z, 1f, 0f, p3.x, p3.y, p3.z, 0f, 0f,
        )
        // 侧面
        val side = Mat4().set(base).multiply(
            Mat4().compose(0f, 0f, -0.1f, Quat().setFromAxisAngle(0f, 1f, 0f, PI.toFloat() / 2), 1f, 1f, 1f),
        )
        val s0 = side.transformPoint(Vec3(0f, -0.035f, -0.11f)); val s1 = side.transformPoint(Vec3(0f, -0.035f, 0.11f))
        val s2 = side.transformPoint(Vec3(0f, 0.035f, 0.11f)); val s3 = side.transformPoint(Vec3(0f, 0.035f, -0.11f))
        renderer.vmFlashMesh.quad(
            s0.x, s0.y, s0.z, 0f, 1f, s1.x, s1.y, s1.z, 1f, 1f,
            s2.x, s2.y, s2.z, 1f, 0f, s3.x, s3.y, s3.z, 0f, 0f,
        )
        renderer.drawUnlitVm(renderer.vmFlashMesh, renderer.texFlash[vm.flashTexIdx], vp)
    }

    // GL 资源引用（由 Renderer 创建）
    var texFlash: IntArray = IntArray(3)
    var texFlashSide = 0
    var texHoleMetal = 0; var texHoleWood = 0; var texScorch = 0; var texBlood = 0
    var texTracer = 0
    var texGlow = 0; var texPuff = 0
    var decalMeshMap: Map<String, com.cf.transportship.engine.DynamicMesh> = mapOf()

    private fun drawDecals() {
        renderer.drawUnlit(decalMeshMap["metal"] ?: return, texHoleMetal, floatArrayOf(1f, 1f, 1f, 1f), false, true)
        renderer.drawUnlit(decalMeshMap["wood"] ?: return, texHoleWood, floatArrayOf(1f, 1f, 1f, 1f), false, true)
        renderer.drawUnlit(decalMeshMap["scorch"] ?: return, texScorch, floatArrayOf(1f, 1f, 1f, 1f), false, true)
        renderer.drawUnlit(decalMeshMap["blood"] ?: return, texBlood, floatArrayOf(1f, 1f, 1f, 1f), false, true)
    }
}
